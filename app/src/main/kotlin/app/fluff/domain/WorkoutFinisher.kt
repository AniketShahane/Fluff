package app.fluff.domain

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.progress.Loads
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.repo.WorkoutSummary
import app.fluff.integrations.claude.ClaudeCoach
import app.fluff.integrations.claude.CoachResult
import app.fluff.integrations.healthconnect.HealthConnectSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** Everything that happens when the user taps Finish: persist, PRs, goals, Health Connect. */
class WorkoutFinisher(
    private val training: TrainingRepository,
    private val healthConnect: HealthConnectSource,
    private val prefs: PreferencesStore,
    private val coach: ClaudeCoach,
    private val json: Json,
    /** Runs after a session is saved (pushes it to the Fluff MCP server). */
    private val afterFinish: () -> Unit = {},
) {
    suspend fun finish(sessionId: Long, sessionRpe: Double?, notes: String?): WorkoutSummary {
        val profile = prefs.currentProfile()
        val summary = training.finish(sessionId, sessionRpe, notes, profile.bodyWeightKg)
        runCatching { training.checkGoals(training.history(profile.bodyWeightKg)) }
        val settings = prefs.currentSettings()
        if (settings.healthConnectEnabled && settings.writeWorkoutsToHealthConnect) {
            // Best effort: a failed write never blocks the user's summary.
            runCatching {
                val s = training.session(sessionId)?.session ?: return@runCatching
                if (s.healthConnectId == null && s.endedAt != null) {
                    val note = "${summary.workSets} working sets · ${Loads.format(summary.volumeKg, profile.unit)} ${profile.unit.label} volume"
                    healthConnect.writeStrengthSession(sessionId, s.title, Instant.ofEpochMilli(s.startedAt), Instant.ofEpochMilli(s.endedAt), note)
                        ?.let { training.markWrittenToHealthConnect(sessionId, it) }
                }
            }
        }
        afterFinish()
        return summary
    }

    /** Optional AI debrief; null when the coach is off or unavailable. */
    suspend fun debrief(summary: WorkoutSummary): String? {
        if (!prefs.currentSettings().aiCoachEnabled || !coach.isConfigured()) return null
        val session = training.session(summary.sessionId) ?: return null
        val unit = prefs.currentProfile().unit
        val payload = buildJsonObject {
            put("title", summary.title)
            put("duration_min", summary.durationMinutes)
            put("working_sets", summary.workSets)
            put("volume_${unit.label}", unit.fromKg(summary.volumeKg).toInt())
            session.session.sessionRpe?.let { put("session_rpe", it) }
            putJsonArray("personal_records") {
                summary.records.forEach { pr ->
                    add(buildJsonObject {
                        put("exercise", ExerciseLibrary[pr.exerciseId]?.name ?: pr.exerciseId)
                        put("kind", pr.kind.name)
                        put("value", pr.newValue)
                    })
                }
            }
            put("sets", buildJsonArray {
                session.sets.filter { it.completedAt != null && it.block == "MAIN" }.forEach { s ->
                    add(buildJsonObject {
                        put("exercise", ExerciseLibrary[s.exerciseId]?.name ?: s.exerciseId)
                        s.weightKg?.let { put("weight_${unit.label}", unit.fromKg(it)) }
                        s.reps?.let { put("reps", it) }
                        s.rpe?.let { put("rpe", it) }
                        s.targetReps?.let { put("target_reps", it) }
                        s.targetRpe?.let { put("target_rpe", it) }
                    })
                }
            })
        }
        return (coach.debrief(json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), payload)) as? CoachResult.Ok)?.value?.takeIf { it.isNotBlank() }
    }
}
