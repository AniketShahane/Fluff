package app.fluff.data.repo

import androidx.room.withTransaction
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.BlockType
import app.fluff.core.model.Goal
import app.fluff.core.model.Muscle
import app.fluff.core.model.SessionRecord
import app.fluff.core.model.SetKind
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.progress.Loads
import app.fluff.core.progress.PersonalRecord
import app.fluff.core.progress.TrainingHistory
import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.GoalEntity
import app.fluff.data.db.SessionEntity
import app.fluff.data.db.SessionStatus
import app.fluff.data.db.SessionWithSets
import app.fluff.data.db.SetEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

data class WorkoutSummary(
    val sessionId: Long,
    val title: String,
    val durationMinutes: Int,
    val workSets: Int,
    val volumeKg: Double,
    val records: List<PersonalRecord>,
    val musclesWorked: Map<Muscle, Double>,
)

/** Sessions, sets, goals and manual activities. */
class TrainingRepository(
    private val db: FluffDatabase,
    private val clock: Clock,
    private val json: Json,
) {
    private val sessions = db.sessions()

    val completedSessions: Flow<List<SessionRecord>> = sessions.observeCompleted().map { list -> list.map { it.toRecord() } }
    val completedRaw: Flow<List<SessionWithSets>> = sessions.observeCompleted()
    val activeSession: Flow<SessionWithSets?> = sessions.observeActive()

    suspend fun history(bodyWeightKg: Double): TrainingHistory = TrainingHistory(sessions.completed().map { it.toRecord() }, bodyWeightKg)

    fun observeSession(id: Long): Flow<SessionWithSets?> = sessions.observe(id)

    /** Starts a session from a plan; resumes the active one if it exists. */
    suspend fun start(plan: WorkoutPlan): Long {
        sessions.active()?.let { return it.session.id }
        val now = clock.millis()
        return db.withTransaction {
            val id = sessions.insert(
                SessionEntity(
                    epochDay = LocalDate.now(clock).toEpochDay(),
                    startedAt = now,
                    focus = plan.focus.name,
                    title = plan.title,
                    planJson = json.encodeToString(WorkoutPlan.serializer(), plan),
                    source = plan.source.name,
                    readinessScore = plan.readinessScore,
                    status = SessionStatus.ACTIVE,
                ),
            )
            var order = 0
            val rows = BlockType.entries.flatMap { bt ->
                plan.block(bt)?.exercises.orEmpty().flatMap { pe ->
                    val o = order++
                    pe.sets.mapIndexed { i, s ->
                        SetEntity(
                            sessionId = id, exerciseId = pe.exerciseId, block = bt.name, exerciseOrder = o, setIndex = i,
                            kind = s.kind.name, targetReps = s.reps, targetDurationSec = s.durationSec,
                            targetWeightKg = s.weightKg, targetRpe = s.rpe, restSec = s.restSec,
                        )
                    }
                }
            }
            sessions.insertSets(rows)
            id
        }
    }

    suspend fun completeSet(set: SetEntity, weightKg: Double?, reps: Int?, rpe: Double?, durationSec: Int?) {
        sessions.updateSet(set.copy(weightKg = weightKg, reps = reps, rpe = rpe, durationSec = durationSec, completedAt = clock.millis(), skipped = false))
    }

    /** Updates prescriptions (e.g. auto-adjusted weights) for sets not yet done. */
    suspend fun retarget(sets: List<SetEntity>) = db.withTransaction { sets.forEach { sessions.updateSet(it) } }

    suspend fun undoSet(set: SetEntity) = sessions.updateSet(set.copy(completedAt = null, skipped = false))

    suspend fun skipSet(set: SetEntity) = sessions.updateSet(set.copy(skipped = true, completedAt = null))

    suspend fun addSet(after: SetEntity) {
        sessions.insertSet(after.copy(id = 0, setIndex = after.setIndex + 1, kind = if (after.kind == SetKind.WARMUP.name) SetKind.WARMUP.name else SetKind.WORKING.name, completedAt = null, skipped = false, weightKg = null, reps = null, rpe = null, durationSec = null))
    }

    /** Swaps every remaining (not yet completed) set of an exercise to an alternative exercise. */
    suspend fun swapExercise(session: SessionWithSets, exerciseOrder: Int, newExerciseId: String, newWeightKg: Double?) {
        db.withTransaction {
            session.sets.filter { it.exerciseOrder == exerciseOrder && it.completedAt == null }.forEach {
                sessions.updateSet(it.copy(exerciseId = newExerciseId, targetWeightKg = if (it.kind == SetKind.WARMUP.name) null else newWeightKg))
            }
        }
    }

    suspend fun finish(sessionId: Long, sessionRpe: Double?, notes: String?, bodyWeightKg: Double): WorkoutSummary {
        val s = sessions.get(sessionId) ?: error("Session $sessionId not found")
        val ended = clock.millis()
        val record = s.copy(session = s.session.copy(endedAt = ended)).toRecord()
        val prior = TrainingHistory(sessions.completed().map { it.toRecord() }, bodyWeightKg)
        val prs = prior.detectRecords(record.sets, record.startedAt)
        sessions.update(s.session.copy(status = SessionStatus.COMPLETED, endedAt = ended, sessionRpe = sessionRpe, notes = notes))
        return summarize(record, s.session.title, prs, bodyWeightKg)
    }

    suspend fun markWrittenToHealthConnect(sessionId: Long, recordId: String) {
        sessions.get(sessionId)?.let { sessions.update(it.session.copy(healthConnectId = recordId)) }
    }

    suspend fun discard(sessionId: Long) = sessions.delete(sessionId)

    suspend fun deleteSession(sessionId: Long) = sessions.delete(sessionId)

    suspend fun session(id: Long): SessionWithSets? = sessions.get(id)

    suspend fun completedOn(epochDay: Long): Boolean = sessions.countCompletedOn(epochDay) > 0

    fun summarize(record: SessionRecord, title: String, prs: List<PersonalRecord>, bodyWeightKg: Double): WorkoutSummary {
        val work = record.sets.filter { it.isWork }
        val muscles = mutableMapOf<Muscle, Double>()
        var volume = 0.0
        work.forEach { set ->
            val ex = ExerciseLibrary[set.exerciseId] ?: return@forEach
            if (!ex.isLoaded) return@forEach
            volume += Loads.totalLoadKg(ex, set.weightKg, bodyWeightKg) * set.reps
            ex.primary.forEach { muscles.merge(it, 1.0, Double::plus) }
            ex.secondary.forEach { muscles.merge(it, 0.5, Double::plus) }
        }
        return WorkoutSummary(record.id, title, record.durationMinutes.toInt(), work.size, volume, prs, muscles)
    }

    // ───────── Goals ─────────
    val goals: Flow<List<Goal>> = db.goals().observeAll().map { l -> l.map { it.toGoal() } }

    suspend fun addGoal(exerciseId: String, targetKg: Double, targetDate: LocalDate?, currentE1rm: Double?) {
        db.goals().insert(
            GoalEntity(
                exerciseId = exerciseId, targetKg = targetKg, targetEpochDay = targetDate?.toEpochDay(),
                startE1rmKg = currentE1rm, createdEpochDay = LocalDate.now(clock).toEpochDay(),
            ),
        )
    }

    suspend fun deleteGoal(id: Long) = db.goals().delete(id)

    /** Marks goals achieved when the current estimate reaches the target. */
    suspend fun checkGoals(history: TrainingHistory) {
        val today = LocalDate.now(clock)
        db.goals().all().filter { it.achievedEpochDay == null }.forEach { g ->
            val cur = history.currentE1rm(g.exerciseId, today) ?: return@forEach
            if (cur >= g.targetKg) db.goals().update(g.copy(achievedEpochDay = today.toEpochDay()))
        }
    }

    // ───────── Manual activities ─────────
    suspend fun logActivity(record: ActivityRecord) = db.activities().upsertAll(listOf(record.toEntity()))
    suspend fun deleteActivity(id: String) = db.activities().delete(id)
    fun observeActivities(since: Instant): Flow<List<ActivityRecord>> = db.activities().observeSince(since.toEpochMilli()).map { l -> l.map { it.toRecord() } }
    suspend fun activitiesSince(since: Instant): List<ActivityRecord> = db.activities().since(since.toEpochMilli()).map { it.toRecord() }
}
