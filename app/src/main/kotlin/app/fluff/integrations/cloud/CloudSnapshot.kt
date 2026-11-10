package app.fluff.integrations.cloud

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.progress.GoalProjector
import app.fluff.core.progress.Loads
import app.fluff.core.progress.StrengthScorer
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.readiness.MuscleFatigue
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.Goal
import app.fluff.core.model.SetKind
import app.fluff.data.db.ActivityEntity
import app.fluff.data.db.CheckInEntity
import app.fluff.data.db.PlanEntity
import app.fluff.data.db.ReadinessEntity
import app.fluff.data.db.SessionStatus
import app.fluff.data.db.SessionWithSets
import app.fluff.data.db.SignalsEntity
import app.fluff.data.repo.toRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/** Everything one upload needs, read from the database by [CloudSync]. */
data class SnapshotInput(
    val from: LocalDate,
    val to: LocalDate,
    val reset: Boolean,
    val today: LocalDate,
    val now: Instant,
    val zone: ZoneId,
    val profile: UserProfile,
    /** Sessions inside [from, to] (active and completed). */
    val sessions: List<SessionWithSets>,
    /** Every completed session, for history-wide insights. */
    val history: TrainingHistory,
    val activities: List<ActivityEntity>,
    /** Last 35 days of activities, for muscle freshness. */
    val recentActivities: List<ActivityRecord>,
    val signals: List<SignalsEntity>,
    val readiness: List<ReadinessEntity>,
    val checkIns: List<CheckInEntity>,
    val plans: List<PlanEntity>,
    val goals: List<Goal>,
)

/**
 * Builds the JSON the Fluff MCP server stores (mcp/src/schema.ts). All training maths (volume,
 * e1RM, strength scores, goal projections) happens here, so the server never re-implements it.
 */
object CloudSnapshot {
    const val VERSION = 1
    private val json = Json { encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    fun build(i: SnapshotInput): JsonObject = buildJsonObject {
        put("version", VERSION)
        put("generatedAt", i.now.toString())
        put("timeZone", i.zone.id)
        putJsonObject("range") { put("from", i.from.toString()); put("to", i.to.toString()) }
        put("reset", i.reset)
        put("profile", json.encodeToJsonElement(UserProfile.serializer(), i.profile))
        putJsonArray("goals") { goals(i).forEach { add(it) } }
        put("insights", insights(i))
        putJsonArray("exercises") { ExerciseLibrary.all.forEach { add(exercise(it)) } }
        putJsonArray("sessions") {
            i.sessions.filter { it.session.status != SessionStatus.DISCARDED && LocalDate.ofEpochDay(it.session.epochDay).inRange(i) }
                .forEach { add(session(it, i.profile.bodyWeightKg)) }
        }
        putJsonArray("activities") {
            i.activities.forEach { a ->
                val date = Instant.ofEpochMilli(a.startEpochMs).atZone(i.zone).toLocalDate()
                if (!date.inRange(i)) return@forEach
                addJsonObject {
                    put("id", a.id); put("date", date.toString()); put("type", a.type); put("source", a.source)
                    put("startMs", a.startEpochMs); put("durationMin", a.durationMin)
                    put("intensity", a.intensity); put("name", a.name); put("energyKcal", a.energyKcal); put("distanceMeters", a.distanceMeters)
                }
            }
        }
        putJsonArray("days") { days(i).forEach { add(it) } }
    }

    private fun LocalDate.inRange(i: SnapshotInput) = !isBefore(i.from) && !isAfter(i.to)

    private fun session(s: SessionWithSets, bodyWeightKg: Double): JsonObject {
        val e = s.session
        var volume = 0.0
        var workSets = 0
        val sets = s.sets.sortedWith(compareBy({ it.exerciseOrder }, { it.setIndex }))
        return buildJsonObject {
            put("id", e.id)
            put("date", LocalDate.ofEpochDay(e.epochDay).toString())
            put("startedAt", e.startedAt)
            put("endedAt", e.endedAt)
            put("title", e.title)
            put("focus", e.focus)
            put("source", e.source)
            put("status", e.status.name)
            put("readinessScore", e.readinessScore)
            put("sessionRpe", e.sessionRpe)
            put("notes", e.notes)
            put("plan", e.planJson?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() } ?: JsonNull)
            putJsonArray("sets") {
                sets.forEach { set ->
                    val ex = ExerciseLibrary[set.exerciseId]
                    val done = set.completedAt != null && !set.skipped
                    val kind = runCatching { SetKind.valueOf(set.kind) }.getOrDefault(SetKind.WORKING)
                    var e1rm: Double? = null
                    if (done && ex != null && ex.isLoaded && kind != SetKind.WARMUP && set.block == "MAIN" && (set.reps ?: 0) > 0) {
                        val total = Loads.totalLoadKg(ex, set.weightKg ?: 0.0, bodyWeightKg)
                        volume += total * (set.reps ?: 0)
                        workSets++
                        e1rm = app.fluff.core.progress.Intensity.e1rm(total, set.reps ?: 0, set.rpe)
                    } else if (done && kind != SetKind.WARMUP && set.block == "MAIN") {
                        workSets++
                    }
                    addJsonObject {
                        put("exerciseId", set.exerciseId); put("block", set.block); put("exerciseOrder", set.exerciseOrder); put("setIndex", set.setIndex)
                        put("kind", set.kind); put("targetReps", set.targetReps); put("targetDurationSec", set.targetDurationSec)
                        put("targetWeightKg", set.targetWeightKg); put("targetRpe", set.targetRpe); put("restSec", set.restSec)
                        put("weightKg", set.weightKg); put("reps", set.reps); put("rpe", set.rpe); put("durationSec", set.durationSec)
                        put("completedAt", set.completedAt); put("skipped", set.skipped); put("e1rmKg", e1rm?.round1())
                    }
                }
            }
            val end = e.endedAt ?: sets.mapNotNull { it.completedAt }.maxOrNull()
            put("durationMin", end?.let { ((it - e.startedAt) / 60_000.0).coerceAtLeast(0.0).round1() })
            put("workSets", workSets)
            put("volumeKg", volume.round1())
        }
    }

    private fun days(i: SnapshotInput): List<JsonObject> {
        val dates = (i.signals.map { it.epochDay } + i.readiness.map { it.epochDay } + i.checkIns.map { it.epochDay } + i.plans.map { it.epochDay })
            .toSortedSet().map { LocalDate.ofEpochDay(it) }.filter { it.inRange(i) }
        val signals = i.signals.associateBy { it.epochDay }
        val readiness = i.readiness.associateBy { it.epochDay }
        val checkIns = i.checkIns.associateBy { it.epochDay }
        val plans = i.plans.associateBy { it.epochDay }
        return dates.map { d ->
            val k = d.toEpochDay()
            val s = signals[k]
            buildJsonObject {
                put("date", d.toString())
                put("whoopRecovery", s?.whoopRecovery); put("hrvMs", s?.hrvRmssdMs); put("restingHr", s?.restingHeartRate)
                put("sleepHours", s?.sleepHours); put("sleepPerformance", s?.sleepPerformance); put("hrvSource", s?.hrvSource)
                readiness[k]?.json?.let { r -> runCatching { json.parseToJsonElement(r) }.getOrNull()?.let { put("readiness", it) } }
                checkIns[k]?.let { c ->
                    putJsonObject("checkIn") { put("sleepQuality", c.sleepQuality); put("energy", c.energy); put("soreness", c.soreness); put("stress", c.stress) }
                }
                plans[k]?.json?.let { p -> runCatching { json.parseToJsonElement(p) }.getOrNull()?.let { put("plan", it) } }
            }
        }
    }

    private fun goals(i: SnapshotInput): List<JsonObject> = i.goals.map { g ->
        val p = GoalProjector.project(g, i.history.e1rmSeries(g.exerciseId), i.today)
        buildJsonObject {
            put("id", g.id)
            put("exerciseId", g.exerciseId)
            put("exerciseName", ExerciseLibrary[g.exerciseId]?.name ?: g.exerciseId)
            put("targetKg", g.targetKg)
            put("targetDate", g.targetDate?.toLocalDate()?.toString())
            put("startE1rmKg", g.startE1rmKg?.round1())
            put("currentE1rmKg", p.currentE1rmKg?.round1())
            put("progressPct", (p.fraction * 100).roundToInt())
            put("weeklyGainKg", p.weeklyGainKg?.round1())
            put("projectedDate", p.projectedDate?.toString())
            put("onTrack", p.onTrack)
            put("achieved", p.achieved)
            put("createdDate", LocalDate.ofEpochDay(g.createdEpochDay).toString())
        }
    }

    private fun insights(i: SnapshotInput): JsonObject {
        val h = i.history
        val strength = StrengthScorer.profile(h, i.profile.bodyWeightKg, i.profile.sex, i.today)
        val freshness = MuscleFatigue.freshness(i.now, h.sessions, i.recentActivities)
        return buildJsonObject {
            putJsonObject("strength") {
                put("overall", strength.overall)
                put("level", strength.level.label)
                put("coverage", strength.coverage.round2())
                putJsonArray("patterns") {
                    strength.patterns.forEach { ps ->
                        addJsonObject {
                            put("pattern", ps.pattern.name); put("score", ps.score); put("level", ps.level.label)
                            put("referenceE1rmKg", ps.referenceE1rmKg?.round1())
                            put("basedOn", ps.basedOnExerciseId?.let { ExerciseLibrary[it]?.name ?: it })
                        }
                    }
                }
            }
            putJsonArray("activityStrength") {
                i.profile.activities.map { it.type }.distinct().forEach { t ->
                    val a = StrengthScorer.activityStrength(strength, t)
                    addJsonObject { put("activity", t.label); put("score", a.score); put("limiter", a.limiter?.name) }
                }
            }
            putJsonArray("lifts") {
                ExerciseLibrary.all.filter { h.e1rmSeries(it.id).isNotEmpty() }
                    .sortedByDescending { h.familiarity(it.id) }
                    .take(25)
                    .forEach { ex ->
                        addJsonObject {
                            put("exerciseId", ex.id); put("name", ex.name)
                            put("currentE1rmKg", h.currentE1rm(ex.id, i.today)?.round1())
                            put("allTimeBestE1rmKg", h.allTimeBestE1rm(ex.id)?.round1())
                            put("lastPerformed", h.lastPerformed(ex.id)?.toString())
                            put("sessions", h.familiarity(ex.id))
                        }
                    }
            }
            putJsonArray("weeklyMuscleSets") {
                h.weeklyMuscleSets(i.today, 8).forEach { w ->
                    addJsonObject {
                        put("weekOf", w.weekStart.toString()); put("sessions", w.sessions); put("hardSets", w.hardSets); put("tonnageKg", w.tonnageKg.roundToInt())
                        putJsonObject("setsByMuscle") { w.setsByMuscle.entries.sortedByDescending { it.value }.forEach { (m, v) -> put(m.name.lowercase(), v.round1()) } }
                    }
                }
            }
            putJsonObject("muscleFreshness") { freshness.entries.sortedBy { it.value }.forEach { (m, v) -> put(m.name.lowercase(), v.round2()) } }
            put("streakWeeks", h.weekStreak(i.today))
            put("totalSessions", h.sessions.size)
        }
    }

    private fun exercise(ex: app.fluff.core.model.Exercise): JsonObject = buildJsonObject {
        put("id", ex.id)
        put("name", ex.name)
        put("pattern", ex.pattern.name)
        putJsonArray("roles") { ex.roles.forEach { add(it.name) } }
        putJsonArray("primary") { ex.primary.forEach { add(it.name.lowercase()) } }
        putJsonArray("secondary") { ex.secondary.forEach { add(it.name.lowercase()) } }
        putJsonArray("equipment") { ex.equipment.forEach { add(it.name) } }
        put("loadType", ex.loadType.name)
        put("unilateral", ex.unilateral)
        put("cue", ex.cue)
    }

    private fun Double.round1() = (this * 10).roundToInt() / 10.0
    private fun Double.round2() = (this * 100).roundToInt() / 100.0

    fun planOf(entity: PlanEntity): WorkoutPlan? = runCatching { json.decodeFromString(WorkoutPlan.serializer(), entity.json) }.getOrNull()
}
