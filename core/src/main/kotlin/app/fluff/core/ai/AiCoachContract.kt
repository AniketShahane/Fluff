package app.fluff.core.ai

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.BlockType
import app.fluff.core.model.Exercise
import app.fluff.core.model.Focus
import app.fluff.core.model.Muscle
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.readiness.Readiness
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** The JSON shape the Claude coach must return (enforced with structured outputs). */
@Serializable
data class AiPlan(
    val title: String,
    val focus: String,
    val rationale: List<String>,
    @SerialName("coach_note") val coachNote: String,
    val warmup: List<AiExercise>,
    val main: List<AiExercise>,
    val cooldown: List<AiExercise>,
)

@Serializable
data class AiExercise(
    @SerialName("exercise_id") val exerciseId: String,
    val sets: List<AiSet>,
    val note: String,
)

/** Zero means "not applicable" (structured outputs keeps every field required). */
@Serializable
data class AiSet(
    val kind: String,
    val reps: Int,
    @SerialName("duration_sec") val durationSec: Int,
    val rpe: Double,
    @SerialName("rest_sec") val restSec: Int,
)

object AiCoachContract {

    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    const val SYSTEM_PROMPT = """You are Fluff's strength coach: an experienced, evidence-based strength and conditioning coach who designs one training session at a time for a single athlete.

You receive a JSON context with the athlete's profile, injuries and limitations, today's readiness (score, band, contributing factors and a prescription with a volume multiplier and an RPE cap), per-muscle freshness, the last two weeks of lifting and other activities, goals, and the exact list of exercise IDs you may use. A deterministic baseline plan from Fluff's engine is included as a reference.

Design today's session:
- Respect the prescription. Never prescribe an RPE above the cap. Scale the number of hard sets with the volume multiplier.
- Only use exercise IDs from allowed_exercise_ids. They are already filtered for the athlete's equipment and limitations; never work around a limitation.
- Favour muscles with high freshness. Avoid loading muscles below 0.5 freshness unless the baseline does.
- Account for the other activities: hard runs, rides, matches or climbs from the last 48 hours count as training for the muscles they load.
- Always include a real warm-up (general cardio, dynamic mobility for the regions being trained and any limited regions, activation) and a cool-down (easy flush, static stretches for the muscles trained, breathing).
- Main work: 3 to 6 exercises, compound movements first. Honour profile.movement_emphasis: "compounds" means no single-joint isolation exercises in the main block; "more_isolation" means add one or two extra isolation exercises after the compounds. Use kind "top" for a single heavy top set, "backoff" for the sets after it, "working" otherwise, and "warmup" for every set in the warm-up and cool-down blocks.
- For rep-based sets set duration_sec to 0. For timed holds, carries, cardio and stretches set reps to 0 and give duration_sec. Use rpe 0 for warm-up, mobility, stretch and cool-down items.
- Do not choose weights: Fluff calculates loads from the athlete's history using your reps and RPE.
- Fit the whole session, including rests, inside time_budget_minutes.
- rationale: 2 to 4 short sentences a lifter would find useful, naming the specific signals that shaped the plan. coach_note: one encouraging, specific sentence. Note fields on exercises: a short cue or an empty string.
Return only the JSON object described by the schema."""

    /** Exercises the AI may use: available with the user's equipment and safe for their limitations. */
    fun allowedExercises(profile: UserProfile): List<Exercise> = ExerciseLibrary.all.filter {
        it.isAvailableWith(profile.equipment) && !it.isContraindicatedFor(profile.limitations)
    }

    fun outputSchema(allowedIds: List<String>): JsonObject {
        val setSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("kind") { put("type", "string"); putJsonArray("enum") { listOf("warmup", "working", "top", "backoff").forEach { add(it) } } }
                putJsonObject("reps") { put("type", "integer") }
                putJsonObject("duration_sec") { put("type", "integer") }
                putJsonObject("rpe") { put("type", "number") }
                putJsonObject("rest_sec") { put("type", "integer") }
            }
            putJsonArray("required") { listOf("kind", "reps", "duration_sec", "rpe", "rest_sec").forEach { add(it) } }
            put("additionalProperties", false)
        }
        val exerciseSchema = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("exercise_id") { put("type", "string"); putJsonArray("enum") { allowedIds.forEach { add(it) } } }
                putJsonObject("sets") { put("type", "array"); put("items", setSchema) }
                putJsonObject("note") { put("type", "string") }
            }
            putJsonArray("required") { listOf("exercise_id", "sets", "note").forEach { add(it) } }
            put("additionalProperties", false)
        }
        return buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("title") { put("type", "string") }
                putJsonObject("focus") { put("type", "string"); putJsonArray("enum") { Focus.entries.forEach { add(it.name) } } }
                putJsonObject("rationale") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
                putJsonObject("coach_note") { put("type", "string") }
                putJsonObject("warmup") { put("type", "array"); put("items", exerciseSchema) }
                putJsonObject("main") { put("type", "array"); put("items", exerciseSchema) }
                putJsonObject("cooldown") { put("type", "array"); put("items", exerciseSchema) }
            }
            putJsonArray("required") { listOf("title", "focus", "rationale", "coach_note", "warmup", "main", "cooldown").forEach { add(it) } }
            put("additionalProperties", false)
        }
    }

    /** Compact, deterministic context (stable key order helps prompt caching). */
    fun buildContext(
        today: LocalDate,
        profile: UserProfile,
        readiness: Readiness,
        freshness: Map<Muscle, Double>,
        history: TrainingHistory,
        activities: List<ActivityRecord>,
        baseline: WorkoutPlan,
        adjustments: PlanAdjustments,
        zone: ZoneId,
    ): String {
        val allowed = allowedExercises(profile)
        val obj = buildJsonObject {
            put("date", today.toString())
            put("weekday", today.dayOfWeek.name.lowercase())
            put("time_budget_minutes", adjustments.minutesOverride ?: profile.sessionMinutes)
            putJsonObject("athlete") {
                put("goal", profile.goal.name.lowercase())
                put("experience", profile.experience.name.lowercase())
                put("sex", profile.sex.name.lowercase())
                put("bodyweight_kg", profile.bodyWeightKg)
                put("days_per_week", profile.daysPerWeek)
                put("movement_emphasis", profile.movementEmphasis.name.lowercase())
                putJsonArray("limitations") { profile.limitations.forEach { add("${it.severity.name.lowercase()} ${it.region.name.lowercase()}${if (it.note.isNotBlank()) " (${it.note})" else ""}") } }
                putJsonArray("other_activities") { profile.activities.forEach { add("${it.type.label}: ${it.sessionsPerWeek}x/week") } }
            }
            putJsonObject("readiness") {
                put("score", readiness.score)
                put("band", readiness.band.name.lowercase())
                put("confidence", readiness.confidence.name.lowercase())
                readiness.acwr?.let { put("acute_chronic_ratio", (it * 100).roundToInt() / 100.0) }
                putJsonArray("factors") { readiness.factors.forEach { add("${it.title}: ${it.detail} (${if (it.impact >= 0) "+" else ""}${it.impact})") } }
                putJsonObject("prescription") {
                    put("volume_multiplier", readiness.prescription.volumeMultiplier)
                    put("rpe_cap", readiness.prescription.rpeCap)
                    put("allow_top_sets", readiness.prescription.allowTopSets)
                    put("recovery_session", readiness.prescription.recoverySession)
                }
            }
            putJsonObject("muscle_freshness") { Muscle.entries.forEach { put(it.name.lowercase(), ((freshness[it] ?: 1.0) * 100).roundToInt() / 100.0) } }
            putJsonArray("recent_sessions") {
                history.sessions.filter { !it.date.isBefore(today.minusDays(14)) }.forEach { s ->
                    add(
                        buildJsonObject {
                            put("date", s.date.toString())
                            put("focus", s.focus?.name?.lowercase() ?: "custom")
                            putJsonArray("exercises") {
                                s.sets.filter { it.isWork }.groupBy { it.exerciseId }.forEach { (id, sets) ->
                                    add("$id: " + sets.joinToString(", ") { "${it.weightKg.roundToInt()}kg×${it.reps}${it.rpe?.let { r -> "@$r" } ?: ""}" })
                                }
                            }
                        },
                    )
                }
            }
            putJsonArray("recent_activities") {
                activities.filter { !it.start.atZone(zone).toLocalDate().isBefore(today.minusDays(14)) }.sortedBy { it.start }.forEach { a ->
                    add("${a.start.atZone(zone).toLocalDate()} ${a.type.label} ${a.durationMinutes.roundToInt()} min, intensity ${(a.effectiveIntensity * 10).roundToInt() / 10.0}/10")
                }
            }
            if (adjustments.focusOverride != null) put("requested_focus", adjustments.focusOverride.name)
            if (adjustments.intensityShift != 0) put("requested_intensity", if (adjustments.intensityShift > 0) "harder" else "easier")
            if (adjustments.avoidExerciseIds.isNotEmpty()) putJsonArray("avoid_exercise_ids") { adjustments.avoidExerciseIds.forEach { add(it) } }
            putJsonArray("allowed_exercise_ids") {
                allowed.forEach { ex -> add("${ex.id} | ${ex.name} | ${ex.roles.joinToString("/") { it.name.lowercase() }} | ${ex.primary.joinToString("/") { it.name.lowercase() }}") }
            }
            putJsonObject("baseline_plan") {
                put("focus", baseline.focus.name)
                BlockType.entries.forEach { bt ->
                    putJsonArray(bt.name.lowercase()) {
                        baseline.block(bt)?.exercises?.forEach { pe ->
                            add("${pe.exerciseId}: " + pe.sets.joinToString(", ") { s -> listOfNotNull(s.kind.name.lowercase(), s.reps?.let { "${it}r" }, s.durationSec?.let { "${it}s" }, s.rpe?.let { "@$it" }).joinToString(" ") })
                        }
                    }
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }
}
