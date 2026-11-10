package app.fluff.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.BlockType
import app.fluff.core.model.Exercise
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.PlannedSet
import app.fluff.core.model.SetKind
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.planner.Insight
import app.fluff.core.planner.LoadAdvisor
import app.fluff.core.progress.Loads
import app.fluff.core.progress.TrainingHistory
import app.fluff.data.db.SessionWithSets
import app.fluff.data.db.SetEntity
import app.fluff.data.prefs.AppSettings
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.domain.CoachEngine
import app.fluff.domain.WorkoutFinisher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class ExerciseGroup(
    val order: Int,
    val block: BlockType,
    val exerciseId: String,
    val exercise: Exercise?,
    val sets: List<SetEntity>,
) {
    val done: Boolean get() = sets.all { it.completedAt != null || it.skipped }
    val name: String get() = exercise?.name ?: exerciseId
}

/** The values on the logging controls, in the user's display unit. */
data class Draft(val setId: Long, val weight: Double?, val reps: Int?, val rpe: Double?, val durationSec: Int?)

data class RestTimer(val endsAtMs: Long, val totalSec: Int, val nextLabel: String?)

data class PrEvent(val exerciseName: String, val e1rmKg: Double, val previousKg: Double?, val id: Long = System.nanoTime())

data class WorkoutState(
    val loading: Boolean = true,
    val sessionId: Long? = null,
    val title: String = "",
    val startedAtMs: Long = 0,
    val groups: List<ExerciseGroup> = emptyList(),
    val currentSetId: Long? = null,
    val draft: Draft? = null,
    val rest: RestTimer? = null,
    val profile: UserProfile = UserProfile(),
    val settings: AppSettings = AppSettings(),
    val pr: PrEvent? = null,
    val toast: String? = null,
    val coachNote: String? = null,
    val finishing: Boolean = false,
    /** Set being corrected after it was logged, if any. */
    val editingSetId: Long? = null,
    /** Why each main exercise is prescribed the way it is, keyed by exercise id. */
    val insights: Map<String, List<Insight>> = emptyMap(),
) {
    val allSets: List<SetEntity> get() = groups.flatMap { it.sets }
    val doneCount: Int get() = allSets.count { it.completedAt != null || it.skipped }
    val totalCount: Int get() = allSets.size
    val loggedCount: Int get() = allSets.count { it.completedAt != null }
    val current: SetEntity? get() = allSets.firstOrNull { it.id == currentSetId }
    val editing: Boolean get() = editingSetId != null && editingSetId == currentSetId
    val currentGroup: ExerciseGroup? get() = groups.firstOrNull { g -> g.sets.any { it.id == currentSetId } }
}

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(
    private val training: TrainingRepository,
    private val engine: CoachEngine,
    private val prefs: PreferencesStore,
    private val finisher: WorkoutFinisher,
    private val json: Json,
) : ViewModel() {

    private val sessionId = MutableStateFlow<Long?>(null)
    private val selected = MutableStateFlow<Long?>(null)
    private val draft = MutableStateFlow<Draft?>(null)
    private val rest = MutableStateFlow<RestTimer?>(null)
    private val pr = MutableStateFlow<PrEvent?>(null)
    private val toast = MutableStateFlow<String?>(null)
    private val finishing = MutableStateFlow(false)
    private val editing = MutableStateFlow<Long?>(null)
    private val insights = MutableStateFlow<Map<String, List<Insight>>>(emptyMap())

    private var history: TrainingHistory? = null
    private val bestE1rm = mutableMapOf<String, Double?>()

    private val session = sessionId.filterNotNull().flatMapLatest { training.observeSession(it) }

    val state: StateFlow<WorkoutState> = combine(
        session,
        combine(prefs.profile, prefs.settings, ::Pair),
        combine(selected, draft, rest, ::Triple),
        combine(pr, toast, finishing, editing, insights) { p, t, f, e, i -> Extras(p, t, f, e, i) },
    ) { s, (profile, settings), (sel, d, r), x ->
        if (s == null) return@combine WorkoutState(loading = false, profile = profile, settings = settings, finishing = x.finishing)
        val groups = group(s)
        val all = groups.flatMap { it.sets }
        val editingSet = all.firstOrNull { it.id == x.editing && it.completedAt != null }
        val current = editingSet
            ?: all.firstOrNull { it.id == sel && it.completedAt == null && !it.skipped }
            ?: all.firstOrNull { it.completedAt == null && !it.skipped }
        val plan = s.session.planJson?.let { runCatching { json.decodeFromString(WorkoutPlan.serializer(), it) }.getOrNull() }
        WorkoutState(
            loading = false,
            sessionId = s.session.id,
            title = s.session.title,
            startedAtMs = s.session.startedAt,
            groups = groups,
            currentSetId = current?.id,
            draft = d?.takeIf { it.setId == current?.id } ?: current?.let { initialDraft(it, all, profile) },
            rest = r,
            profile = profile,
            settings = settings,
            pr = x.pr,
            toast = x.toast,
            coachNote = plan?.coachNote,
            finishing = x.finishing,
            editingSetId = editingSet?.id,
            insights = x.insights,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutState())

    /** Starts today's plan (or resumes the active session). */
    fun begin(freestyle: Boolean = false) {
        if (sessionId.value != null) return
        viewModelScope.launch {
            val active = training.activeSession.first()
            val id = active?.session?.id ?: if (freestyle) {
                training.start(freestylePlan())
            } else {
                engine.ensureTodayPlan()
                val plan = engine.observePlan().first() ?: return@launch
                training.start(plan)
            }
            val profile = prefs.currentProfile()
            history = training.history(profile.bodyWeightKg)
            sessionId.value = id
            training.session(id)?.session?.planJson
                ?.let { runCatching { json.decodeFromString(WorkoutPlan.serializer(), it) }.getOrNull() }
                ?.let { insights.value = engine.insights(it) }
        }
    }

    /** An empty session the user fills exercise by exercise (their own programme, a class, a quick log). */
    private fun freestylePlan() = WorkoutPlan(
        epochDay = engine.today().toEpochDay(),
        focus = app.fluff.core.model.Focus.FULL_BODY,
        title = "Your workout",
        rationale = emptyList(),
        blocks = emptyList(),
        estimatedMinutes = 0,
        source = app.fluff.core.model.PlanSource.ENGINE,
        readinessScore = null,
    )

    private fun group(s: SessionWithSets): List<ExerciseGroup> =
        s.sets.groupBy { it.exerciseOrder }.toSortedMap().map { (order, sets) ->
            val sorted = sets.sortedBy { it.setIndex }
            val id = sorted.first().exerciseId
            ExerciseGroup(order, runCatching { BlockType.valueOf(sorted.first().block) }.getOrDefault(BlockType.MAIN), id, ExerciseLibrary[id], sorted)
        }

    private fun initialDraft(set: SetEntity, all: List<SetEntity>, profile: UserProfile): Draft {
        val unit = profile.unit
        // Carry the last weight actually used on this exercise in this session forward.
        val lastUsed = all.filter { it.exerciseId == set.exerciseId && it.completedAt != null && it.kind == set.kind }
            .maxByOrNull { it.completedAt!! }?.weightKg
        val kg = set.weightKg ?: if (set.kind == SetKind.WARMUP.name) set.targetWeightKg else (set.targetWeightKg ?: lastUsed)
        return Draft(
            setId = set.id,
            weight = kg?.let { round1(unit.fromKg(it)) },
            reps = set.reps ?: set.targetReps,
            rpe = set.rpe ?: set.targetRpe,
            durationSec = set.durationSec ?: set.targetDurationSec,
        )
    }

    fun select(setId: Long) {
        selected.value = setId
        editing.value = null
        draft.value = null
    }

    /** Opens a logged set for correction; logging then saves the new values in place. */
    fun edit(setId: Long) {
        editing.value = setId
        draft.value = null
    }

    fun cancelEdit() {
        editing.value = null
        draft.value = null
    }

    private fun editDraft(transform: (Draft) -> Draft) {
        val d = state.value.draft ?: return
        draft.value = transform(d)
    }

    fun weightStep(direction: Int) {
        val ex = state.value.currentGroup?.exercise ?: return
        val unit = state.value.profile.unit
        val step = Loads.increment(ex, unit)
        editDraft { d ->
            val next = ((d.weight ?: 0.0) + step * direction).coerceAtLeast(0.0)
            d.copy(weight = round1(next))
        }
    }

    fun repsStep(direction: Int) = editDraft { d -> d.copy(reps = ((d.reps ?: 0) + direction).coerceIn(0, 100)) }
    fun durationStep(direction: Int) = editDraft { d -> d.copy(durationSec = ((d.durationSec ?: 30) + 5 * direction).coerceIn(5, 3600)) }
    fun rpe(value: Double?) = editDraft { d -> d.copy(rpe = value) }

    fun log() {
        val st = state.value
        val set = st.current ?: return
        val d = st.draft ?: return
        val profile = st.profile
        val ex = st.currentGroup?.exercise
        val kg = d.weight?.let { profile.unit.toKg(it) }
        if (st.editing) {
            viewModelScope.launch {
                training.editSet(set, kg ?: if (ex?.isLoaded == true) 0.0 else null, d.reps, d.rpe, d.durationSec)
                editing.value = null
                draft.value = null
                toast.value = "Set updated"
            }
            return
        }
        viewModelScope.launch {
            training.completeSet(set, kg ?: if (ex?.isLoaded == true) 0.0 else null, d.reps, d.rpe, d.durationSec)
            selected.value = null
            draft.value = null
            if (ex != null) {
                detectPr(ex, set, kg, d, profile)
                autoregulate(ex, set, kg, d, st)
            }
            startRest(set, st)
        }
    }

    private fun detectPr(ex: Exercise, set: SetEntity, kg: Double?, d: Draft, profile: UserProfile) {
        if (set.kind == SetKind.WARMUP.name || kg == null || (d.reps ?: 0) <= 0) return
        val logged = LoggedSet(ex.id, SetKind.valueOf(set.kind), kg, d.reps!!, d.rpe, null, Instant.now())
        val e1rm = Loads.e1rmOf(logged, profile.bodyWeightKg) ?: return
        val prior = bestE1rm.getOrPut(ex.id) { history?.allTimeBestE1rm(ex.id) }
        // Only celebrate genuine improvements on lifts the user has history with.
        if (prior != null && e1rm > prior + 0.25) pr.value = PrEvent(ex.name, e1rm, prior)
        if (prior == null || e1rm > prior) bestE1rm[ex.id] = e1rm
    }

    /**
     * Autoregulation: if a working set felt much easier or harder than prescribed, nudge the
     * remaining working sets of the same exercise by one increment.
     */
    private suspend fun autoregulate(ex: Exercise, set: SetEntity, kg: Double?, d: Draft, st: WorkoutState) {
        val target = set.targetRpe ?: return
        val actual = d.rpe ?: return
        if (set.kind == SetKind.WARMUP.name || kg == null || !ex.isLoaded) return
        val diff = actual - target
        val dir = when { diff <= -1.5 -> 1; diff >= 1.0 -> -1; else -> 0 }
        if (dir == 0) return
        val unit = st.profile.unit
        val stepKg = unit.toKg(Loads.increment(ex, unit))
        val newKg = Loads.roundDown(ex, (kg + dir * stepKg).coerceAtLeast(0.0), unit).let { if (dir > 0 && it <= kg) kg + stepKg else it }
        val remaining = st.allSets.filter { it.exerciseId == ex.id && it.id != set.id && it.completedAt == null && !it.skipped && it.kind != SetKind.WARMUP.name }
        if (remaining.isEmpty()) return
        training.retarget(remaining.map { it.copy(targetWeightKg = newKg) })
        toast.value = if (dir > 0) "Felt easy. Next sets bumped to ${Loads.format(newKg, unit)} ${unit.label}" else "Felt tough. Next sets eased to ${Loads.format(newKg, unit)} ${unit.label}"
    }

    private fun startRest(set: SetEntity, st: WorkoutState) {
        val next = st.allSets.firstOrNull { it.id != set.id && it.completedAt == null && !it.skipped }
        val restSec = set.restSec
        if (next == null || restSec < 15) { rest.value = null; return }
        val nextName = ExerciseLibrary[next.exerciseId]?.name
        rest.value = RestTimer(System.currentTimeMillis() + restSec * 1000L, restSec, nextName)
    }

    fun restAdd(seconds: Int) = rest.update { r -> r?.copy(endsAtMs = r.endsAtMs + seconds * 1000L, totalSec = r.totalSec + seconds) }
    fun restSkip() { rest.value = null }

    fun undo(set: SetEntity) = viewModelScope.launch { training.undoSet(set); editing.value = null; selected.value = set.id; draft.value = null }

    /** Every exercise the user can do with their equipment and limitations, for adding mid-workout. */
    fun library(): List<Exercise> {
        val profile = state.value.profile
        return ExerciseLibrary.all.filter { it.isAvailableWith(profile.equipment) && !it.isContraindicatedFor(profile.limitations) }
            .sortedWith(compareByDescending<Exercise> { history?.familiarity(it.id) ?: 0 }.thenBy { it.name })
    }

    /** Adds an exercise after the current main work with a sensible goal-based prescription. */
    fun addExercise(ex: Exercise) {
        val id = state.value.sessionId ?: return
        val profile = state.value.profile
        viewModelScope.launch {
            val sets = prescriptionFor(ex, profile)
            val first = training.addExercise(id, ex.id, sets) ?: return@launch
            editing.value = null
            selected.value = first
            draft.value = null
            toast.value = "Added ${ex.name}"
        }
    }

    private fun prescriptionFor(ex: Exercise, profile: UserProfile): List<PlannedSet> {
        val timed = !ex.isLoaded || ex.pattern == app.fluff.core.model.MovementPattern.CARRY
        if (timed) {
            val duration = ex.defaultDurationSec ?: if (ex.defaultReps == null) 30 else null
            return List(3) { PlannedSet(SetKind.WORKING, reps = ex.defaultReps.takeIf { duration == null }, durationSec = duration, restSec = 60) }
        }
        val reps = when (profile.goal) {
            app.fluff.core.model.TrainingGoal.STRENGTH, app.fluff.core.model.TrainingGoal.ATHLETIC -> if (ex.movementType == app.fluff.core.model.MovementType.ISOLATION) 10 else 6
            else -> if (ex.movementType == app.fluff.core.model.MovementType.ISOLATION) 12 else 10
        }
        val rpe = 8.0
        val kg = history?.let { LoadAdvisor(it, profile, LocalDate.now()).suggest(ex, reps, rpe).loggedKg }
        val rest = if (ex.movementType == app.fluff.core.model.MovementType.COMPOUND) 120 else 75
        return List(3) { PlannedSet(SetKind.WORKING, reps, null, kg, rpe, rest) }
    }
    fun skip(set: SetEntity) = viewModelScope.launch { training.skipSet(set); draft.value = null }
    fun skipExercise(group: ExerciseGroup) = viewModelScope.launch {
        group.sets.filter { it.completedAt == null && !it.skipped }.forEach { training.skipSet(it) }
        draft.value = null
    }
    fun addSet(group: ExerciseGroup) = viewModelScope.launch { training.addSet(group.sets.last()) }

    fun alternatives(group: ExerciseGroup): List<Exercise> {
        val ex = group.exercise ?: return emptyList()
        val profile = state.value.profile
        return ExerciseLibrary.all.filter {
            it.id != ex.id && it.pattern == ex.pattern && it.roles.any { r -> r in ex.roles } &&
                it.isAvailableWith(profile.equipment) && !it.isContraindicatedFor(profile.limitations)
        }.sortedByDescending { history?.familiarity(it.id) ?: 0 }.take(8)
    }

    fun swap(group: ExerciseGroup, to: Exercise) = viewModelScope.launch {
        val s = training.session(group.sets.first().sessionId) ?: return@launch
        val profile = state.value.profile
        val sample = group.sets.firstOrNull { it.kind != SetKind.WARMUP.name && it.completedAt == null }
        val weight = if (sample?.targetReps != null && history != null) {
            LoadAdvisor(history!!, profile, LocalDate.now()).suggest(to, sample.targetReps!!, sample.targetRpe ?: 8.0).loggedKg
        } else null
        training.swapExercise(s, group.order, to.id, weight)
        draft.value = null
        toast.value = "Swapped to ${to.name}"
    }

    fun consumePr() { pr.value = null }
    fun consumeToast() { toast.value = null }

    fun finish(sessionRpe: Double?, notes: String?, onDone: (Long) -> Unit) {
        val id = state.value.sessionId ?: return
        if (finishing.value) return
        finishing.value = true
        viewModelScope.launch {
            val summary = runCatching { finisher.finish(id, sessionRpe, notes?.takeIf { it.isNotBlank() }) }
            finishing.value = false
            rest.value = null
            summary.onSuccess { onDone(it.sessionId) }.onFailure { toast.value = "Couldn't save the session: ${it.message}" }
        }
    }

    fun discard(onDone: () -> Unit) {
        val id = state.value.sessionId ?: return
        viewModelScope.launch { training.discard(id); onDone() }
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    private data class Extras(
        val pr: PrEvent?,
        val toast: String?,
        val finishing: Boolean,
        val editing: Long?,
        val insights: Map<String, List<Insight>>,
    )
}

fun SetEntity.isDone() = completedAt != null
fun SetEntity.kindEnum(): SetKind = runCatching { SetKind.valueOf(kind) }.getOrDefault(SetKind.WORKING)
