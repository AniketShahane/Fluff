package app.fluff.domain

import app.fluff.core.ai.AiCoachContract
import app.fluff.core.ai.PlanValidator
import app.fluff.core.ai.ValidationResult
import app.fluff.core.model.CheckIn
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.PlanSource
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.planner.Insight
import app.fluff.core.planner.PlanInsights
import app.fluff.core.planner.PlannerInput
import app.fluff.core.planner.WorkoutPlanner
import app.fluff.core.readiness.MuscleFatigue
import app.fluff.core.readiness.Readiness
import app.fluff.core.readiness.ReadinessEngine
import app.fluff.core.readiness.TrainingLoad
import app.fluff.data.db.CheckInEntity
import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.PlanEntity
import app.fluff.data.db.ReadinessEntity
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.toSignals
import app.fluff.integrations.claude.ClaudeCoach
import app.fluff.integrations.claude.CoachResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

sealed interface CoachStatus {
    data object Idle : CoachStatus
    data object Thinking : CoachStatus
    data class Refined(val warnings: List<String>) : CoachStatus
    data class Fallback(val reason: String) : CoachStatus
}

/** Everything needed to plan a day, gathered once. */
private data class DayContext(
    val today: LocalDate,
    val profile: UserProfile,
    val readiness: Readiness,
    val input: PlannerInput,
)

/**
 * Orchestrates the daily loop: readiness → deterministic plan (instant) → Claude refinement
 * (async, validated) → persisted plan. A started workout is never replaced underneath the user.
 */
class CoachEngine(
    private val db: FluffDatabase,
    private val prefs: PreferencesStore,
    private val training: TrainingRepository,
    private val coach: ClaudeCoach,
    private val clock: Clock,
    private val json: Json,
    private val scope: CoroutineScope,
) {
    private val planner = WorkoutPlanner()
    private val lock = Mutex()
    private var aiJob: Job? = null
    private val _status = MutableStateFlow<CoachStatus>(CoachStatus.Idle)
    val status: StateFlow<CoachStatus> = _status.asStateFlow()

    private val zone: ZoneId get() = clock.zone
    fun today(): LocalDate = LocalDate.now(clock)

    fun observeReadiness(day: LocalDate = today()): Flow<Readiness?> =
        db.readiness().observe(day.toEpochDay()).map { e -> e?.let { runCatching { json.decodeFromString(Readiness.serializer(), it.json) }.getOrNull() } }

    fun observeReadinessHistory(days: Int): Flow<List<Readiness>> {
        val t = today().toEpochDay()
        return db.readiness().observeRange(t - days + 1, t).map { l -> l.mapNotNull { runCatching { json.decodeFromString(Readiness.serializer(), it.json) }.getOrNull() } }
    }

    fun observePlan(day: LocalDate = today()): Flow<WorkoutPlan?> =
        db.plans().observe(day.toEpochDay()).map { e -> e?.let { runCatching { json.decodeFromString(WorkoutPlan.serializer(), it.json) }.getOrNull() } }

    fun observeCheckIn(day: LocalDate = today()): Flow<CheckIn?> =
        db.checkIns().observe(day.toEpochDay()).map { e -> e?.let { CheckIn(it.sleepQuality, it.energy, it.soreness, it.stress) } }

    private suspend fun gather(adjustments: PlanAdjustments): DayContext = compute(adjustments).also { ctx ->
        db.readiness().upsert(ReadinessEntity(ctx.today.toEpochDay(), json.encodeToString(Readiness.serializer(), ctx.readiness), clock.millis()))
    }

    /** Reads everything a plan depends on, without writing anything. */
    private suspend fun compute(adjustments: PlanAdjustments): DayContext {
        val today = today()
        val profile = prefs.currentProfile()
        val history = training.history(profile.bodyWeightKg)
        val now = clock.instant()
        val activities = training.activitiesSince(now.minus(Duration.ofDays(35)))
        val signals = db.signals().range(today.toEpochDay() - 35, today.toEpochDay()).map { it.toSignals() }
        val todaySignals = signals.firstOrNull { it.date == today }
        val checkIn = db.checkIns().get(today.toEpochDay())?.let { CheckIn(it.sleepQuality, it.energy, it.soreness, it.stress) }
        val load = TrainingLoad.snapshot(today, history.sessions, activities, zone)
        val readiness = ReadinessEngine.compute(today, todaySignals, signals, checkIn, load)
        val freshness = MuscleFatigue.freshness(now, history.sessions, activities)
        return DayContext(today, profile, readiness, PlannerInput(today, profile, readiness, history, freshness, adjustments))
    }

    /** Recomputes readiness only (e.g. after a sync). */
    suspend fun refreshReadiness(): Readiness = lock.withLock { gather(PlanAdjustments()).readiness }

    /**
     * Builds today's plan. The engine plan is stored immediately; if the AI coach is enabled it is
     * refined in the background and swapped in when (and only if) it validates.
     */
    suspend fun planToday(adjustments: PlanAdjustments = PlanAdjustments(), force: Boolean = false): WorkoutPlan = lock.withLock {
        val ctx = gather(adjustments)
        val existing = db.plans().get(ctx.today.toEpochDay())
        if (!force && existing != null && existing.adjustmentsHash == adjustments.hashCode()) {
            runCatching { json.decodeFromString(WorkoutPlan.serializer(), existing.json) }.getOrNull()?.let { return@withLock it }
        }
        val plan = planner.plan(ctx.input)
        store(plan, adjustments)
        refineWithAi(ctx, plan, adjustments)
        plan
    }

    private suspend fun store(plan: WorkoutPlan, adjustments: PlanAdjustments) {
        db.plans().upsert(PlanEntity(plan.epochDay, json.encodeToString(WorkoutPlan.serializer(), plan), clock.millis(), adjustments.hashCode()))
        db.plans().prune(plan.epochDay - 30)
    }

    private suspend fun refineWithAi(ctx: DayContext, baseline: WorkoutPlan, adjustments: PlanAdjustments) {
        aiJob?.cancel()
        if (!prefs.currentSettings().aiCoachEnabled || !coach.isConfigured()) {
            _status.value = CoachStatus.Idle
            return
        }
        _status.value = CoachStatus.Thinking
        aiJob = scope.launch {
            val allowed = AiCoachContract.allowedExercises(ctx.profile).map { it.id }
            val contextJson = AiCoachContract.buildContext(
                ctx.today, ctx.profile, ctx.readiness, ctx.input.freshness, ctx.input.history,
                training.activitiesSince(clock.instant().minus(Duration.ofDays(14))), baseline, adjustments, zone,
            )
            when (val r = coach.designPlan(contextJson, allowed)) {
                is CoachResult.Failed -> _status.value = CoachStatus.Fallback(r.reason)
                is CoachResult.Ok -> {
                    val validator = PlanValidator(ctx.profile, ctx.readiness, ctx.input.history, ctx.today, baseline)
                    when (val v = validator.validate(r.value)) {
                        is ValidationResult.Invalid -> _status.value = CoachStatus.Fallback(v.reason)
                        is ValidationResult.Valid -> lock.withLock {
                            // Don't swap the plan if the user already started training or re-planned meanwhile.
                            val current = db.plans().get(ctx.today.toEpochDay())
                            val stillBaseline = current?.json?.let { runCatching { json.decodeFromString(WorkoutPlan.serializer(), it) }.getOrNull() } == baseline
                            if (stillBaseline && db.sessions().active() == null) {
                                store(v.plan, adjustments)
                                _status.value = CoachStatus.Refined(v.warnings)
                            } else {
                                _status.value = CoachStatus.Idle
                            }
                        }
                    }
                }
            }
        }
    }

    /** Makes sure today has a plan without replacing one the user may have adjusted. */
    suspend fun ensureTodayPlan() {
        if (db.plans().get(today().toEpochDay()) == null) planToday()
    }

    /**
     * After new wearable data arrives: recompute readiness and, if the band moved and the user
     * hasn't trained yet today, rebuild the plan so it matches how they actually recovered.
     */
    suspend fun onNewSignals(adjustments: PlanAdjustments = PlanAdjustments()) {
        val r = refreshReadiness()
        val day = today().toEpochDay()
        val plan = db.plans().get(day)?.let { runCatching { json.decodeFromString(WorkoutPlan.serializer(), it.json) }.getOrNull() }
        val trainedToday = db.sessions().active() != null || training.completedOn(day)
        if (trainedToday) return
        val planBand = plan?.readinessScore?.let { app.fluff.core.readiness.ReadinessBand.forScore(it) }
        if (plan == null || planBand != r.band) planToday(adjustments, force = true)
    }

    suspend fun saveCheckIn(c: CheckIn) {
        db.checkIns().put(CheckInEntity(today().toEpochDay(), c.sleepQuality, c.energy, c.soreness, c.stress, clock.millis()))
        if (db.sessions().active() == null) planToday(force = true)
    }

    fun currentPlanSource(plan: WorkoutPlan?): PlanSource? = plan?.source

    /** A few concrete reasons behind each main exercise in [plan] (progression, records, recovery). */
    suspend fun insights(plan: WorkoutPlan): Map<String, List<Insight>> =
        runCatching { PlanInsights.explain(plan, compute(PlanAdjustments()).input) }.getOrDefault(emptyMap())
}
