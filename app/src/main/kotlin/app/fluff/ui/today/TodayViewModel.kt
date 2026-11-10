package app.fluff.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluff.core.model.CheckIn
import app.fluff.core.model.Focus
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.readiness.Readiness
import app.fluff.data.prefs.AppSettings
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.toRecord
import app.fluff.domain.CoachEngine
import app.fluff.domain.CoachStatus
import app.fluff.domain.SyncManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class CompletedToday(val sessionId: Long, val title: String, val minutes: Int, val sets: Int, val volumeKg: Double)

data class TodayState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate.now(),
    val profile: UserProfile = UserProfile(),
    val settings: AppSettings = AppSettings(),
    val readiness: Readiness? = null,
    val history: List<Readiness> = emptyList(),
    val plan: WorkoutPlan? = null,
    val checkIn: CheckIn? = null,
    val coach: CoachStatus = CoachStatus.Idle,
    val activeSessionId: Long? = null,
    val completed: CompletedToday? = null,
    val syncing: Boolean = false,
    val replanning: Boolean = false,
    val adjustments: PlanAdjustments = PlanAdjustments(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val engine: CoachEngine,
    private val training: TrainingRepository,
    private val prefs: PreferencesStore,
    private val sync: SyncManager,
) : ViewModel() {

    private val day = MutableStateFlow(engine.today())
    private val adjustments = MutableStateFlow(PlanAdjustments())
    private val replanning = MutableStateFlow(false)

    private val perDay = day.flatMapLatest { d ->
        combine(
            engine.observeReadiness(d),
            engine.observeReadinessHistory(14),
            engine.observePlan(d),
            engine.observeCheckIn(d),
        ) { r, h, p, c -> PerDay(d, r, h, p, c) }
    }

    private val sessions = combine(training.activeSession, training.completedRaw, day, prefs.profile) { active, done, d, profile ->
        val todays = done.filter { it.session.epochDay == d.toEpochDay() }.maxByOrNull { it.session.startedAt }
        active?.session?.id to todays?.let { s ->
            val rec = s.toRecord()
            val summary = training.summarize(rec, s.session.title, emptyList(), profile.bodyWeightKg)
            CompletedToday(s.session.id, s.session.title, rec.durationMinutes.toInt(), summary.workSets, summary.volumeKg)
        }
    }

    val state: StateFlow<TodayState> = combine(
        perDay,
        combine(prefs.profile, prefs.settings, ::Pair),
        combine(engine.status, sync.state, replanning, adjustments) { a, b, c, d -> Quad(a, b, c, d) },
        sessions,
    ) { pd, (profile, settings), q, (activeId, completed) ->
        TodayState(
            loading = pd.plan == null && pd.readiness == null,
            today = pd.day,
            profile = profile,
            settings = settings,
            readiness = pd.readiness,
            history = pd.history,
            plan = pd.plan,
            checkIn = pd.checkIn,
            coach = q.a,
            activeSessionId = activeId,
            completed = completed,
            syncing = q.b.running,
            replanning = q.c,
            adjustments = q.d,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    /** Called whenever Today becomes visible: handles a new day and stale wearable data. */
    fun onResume() {
        day.value = engine.today()
        viewModelScope.launch {
            runCatching { engine.ensureTodayPlan() }
            val last = prefs.currentSettings().lastSyncEpochMs
            if (System.currentTimeMillis() - last > STALE_MS) refresh()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            sync.sync()
            runCatching { engine.onNewSignals(adjustments.value) }
        }
    }

    fun saveCheckIn(c: CheckIn) = viewModelScope.launch {
        replanning.value = true
        runCatching { engine.saveCheckIn(c) }
        replanning.value = false
    }

    fun shorter() = adjust { a ->
        val current = a.minutesOverride ?: state.value.plan?.estimatedMinutes ?: state.value.profile.sessionMinutes
        a.copy(minutesOverride = (current - 15).coerceAtLeast(20))
    }

    fun longer() = adjust { a ->
        val current = a.minutesOverride ?: state.value.plan?.estimatedMinutes ?: state.value.profile.sessionMinutes
        a.copy(minutesOverride = (current + 15).coerceAtMost(120))
    }

    fun easier() = adjust { it.copy(intensityShift = (it.intensityShift - 1).coerceAtLeast(-1)) }
    fun harder() = adjust { it.copy(intensityShift = (it.intensityShift + 1).coerceAtMost(1)) }
    fun shuffle() = adjust { it.copy(variationSeed = it.variationSeed + 1) }
    fun focus(f: Focus?) = adjust { it.copy(focusOverride = f) }
    fun reset() = adjust { PlanAdjustments() }

    private fun adjust(transform: (PlanAdjustments) -> PlanAdjustments) {
        if (state.value.activeSessionId != null) return
        val next = transform(adjustments.value)
        adjustments.value = next
        viewModelScope.launch {
            replanning.value = true
            runCatching { engine.planToday(next, force = true) }
            replanning.value = false
        }
    }

    private data class PerDay(val day: LocalDate, val readiness: Readiness?, val history: List<Readiness>, val plan: WorkoutPlan?, val checkIn: CheckIn?)
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    companion object {
        private const val STALE_MS = 3 * 60 * 60 * 1000L
    }
}
