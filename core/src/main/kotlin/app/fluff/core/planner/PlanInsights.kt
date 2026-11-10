package app.fluff.core.planner

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.BodyRegion
import app.fluff.core.model.Exercise
import app.fluff.core.model.LoadType
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.PlannedExercise
import app.fluff.core.model.SetKind
import app.fluff.core.model.Stress
import app.fluff.core.model.WeightUnit
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.progress.Intensity
import app.fluff.core.progress.Loads
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** What an insight is about; the UI picks an icon from it. */
enum class InsightKind { PROGRESS, LAST_TIME, RECORD, RECOVERY, EFFORT, JOINT, FIRST_TIME }

enum class InsightTone { POSITIVE, NEUTRAL, CAUTION }

/** One short, specific reason behind a prescription ("+2.5 kg on last time"). */
data class Insight(val kind: InsightKind, val text: String, val tone: InsightTone)

/**
 * Explains each prescription in a plan in a few concrete lines, for engine and AI plans alike.
 * Everything here is derived from the same inputs the planner used, so the "why" always matches
 * the numbers on screen.
 */
object PlanInsights {

    /** Insights for every exercise in the main block, keyed by exercise id. */
    fun explain(plan: WorkoutPlan, input: PlannerInput): Map<String, List<Insight>> =
        plan.mainExercises.associate { it.exerciseId to forExercise(it, input) }

    fun forExercise(pe: PlannedExercise, input: PlannerInput, max: Int = 3): List<Insight> {
        val ex = ExerciseLibrary[pe.exerciseId] ?: return emptyList()
        val out = mutableListOf<Insight>()
        if (ex.isLoaded && ex.pattern != MovementPattern.CARRY) loadInsights(ex, pe, input, out)
        recovery(ex, input)?.let { out += it }
        effort(pe, input)?.let { out += it }
        joint(ex, input)?.let { out += it }
        // Most useful first: what changed since last time, then records, then cautions, then the rest.
        val order = listOf(InsightKind.PROGRESS, InsightKind.FIRST_TIME, InsightKind.RECORD, InsightKind.EFFORT, InsightKind.RECOVERY, InsightKind.JOINT, InsightKind.LAST_TIME)
        return out.sortedBy { order.indexOf(it.kind) }.take(max)
    }

    private fun loadInsights(ex: Exercise, pe: PlannedExercise, input: PlannerInput, out: MutableList<Insight>) {
        val unit = input.profile.unit
        val work = pe.sets.filter { it.kind != SetKind.WARMUP && it.reps != null }
        val top = work.maxByOrNull { it.weightKg ?: 0.0 } ?: return
        val topKg = top.weightKg
        val topReps = top.reps ?: return

        val lastSession = input.history.sessions.lastOrNull { s ->
            s.date.isBefore(input.today) && s.sets.any { it.exerciseId == ex.id && it.isWork }
        }
        if (lastSession == null) {
            val basis = LoadAdvisor(input.history, input.profile, input.today).estimateE1rm(ex)?.second
            val text = when {
                topKg == null -> "First time: pick a weight that leaves 2 to 3 reps in reserve"
                basis == LoadBasis.RELATED_LIFT -> "First time: weight estimated from a related lift you've logged"
                ex.loadType == LoadType.BODYWEIGHT -> "First time: start with bodyweight and add load once it's easy"
                else -> "First time: a starting estimate, so adjust after the first set"
            }
            out += Insight(InsightKind.FIRST_TIME, text, InsightTone.NEUTRAL)
            return
        }

        val lastWork = lastSession.sets.filter { it.exerciseId == ex.id && it.isWork }
        val lastTop = lastWork.maxWith(compareBy({ it.weightKg }, { it.reps }))
        val daysAgo = ChronoUnit.DAYS.between(lastSession.date, input.today)
        val ago = when (daysAgo) { 1L -> "yesterday"; else -> "$daysAgo days ago" }
        out += Insight(
            InsightKind.LAST_TIME,
            "Last time ($ago): ${lastWork.size}×${lastTop.reps} at ${weight(ex, lastTop.weightKg, unit)}${lastTop.rpe?.let { " @ RPE ${fmt(it)}" } ?: ""}",
            InsightTone.NEUTRAL,
        )

        if (topKg != null) {
            val delta = topKg - lastTop.weightKg
            val step = unit.toKg(Loads.increment(ex, unit)) / 2
            out += when {
                delta > step -> Insight(InsightKind.PROGRESS, "+${Loads.format(delta, unit)} ${unit.label} on last time", InsightTone.POSITIVE)
                delta < -step -> Insight(InsightKind.PROGRESS, "${Loads.format(-delta, unit)} ${unit.label} lighter than last time, to match today's recovery", InsightTone.CAUTION)
                topReps > lastTop.reps -> Insight(InsightKind.PROGRESS, "Same weight, ${topReps - lastTop.reps} more rep${if (topReps - lastTop.reps == 1) "" else "s"} than last time", InsightTone.POSITIVE)
                else -> Insight(InsightKind.PROGRESS, "Same weight as last time: make it look smoother", InsightTone.NEUTRAL)
            }
            record(ex, topKg, topReps, top.rpe, input)?.let { out += it }
        }
    }

    /** "Hit the plan and it's a PR" or "7 reps here would be a PR". */
    private fun record(ex: Exercise, loggedKg: Double, plannedReps: Int, rpe: Double?, input: PlannerInput): Insight? {
        val best = input.history.allTimeBestE1rm(ex.id) ?: return null
        val total = Loads.totalLoadKg(ex, loggedKg, input.profile.bodyWeightKg)
        if (total <= 0) return null
        val needed = (1..Intensity.MAX_REPS_FOR_E1RM).firstOrNull { r ->
            (Intensity.e1rm(total, r, rpe ?: Intensity.DEFAULT_RPE) ?: 0.0) > best * 1.005
        } ?: return null
        return when {
            needed <= plannedReps -> Insight(InsightKind.RECORD, "Hit the target and it's a new estimated max", InsightTone.POSITIVE)
            needed <= plannedReps + 2 -> Insight(InsightKind.RECORD, "$needed reps on your top set would be a new estimated max", InsightTone.POSITIVE)
            else -> null
        }
    }

    private fun recovery(ex: Exercise, input: PlannerInput): Insight? {
        if (ex.primary.isEmpty() || !ex.isLoaded && ex.pattern != MovementPattern.CORE) return null
        val scored = ex.primary.map { it to (input.freshness[it] ?: 1.0) }
        val worst = scored.minBy { it.second }
        return if (worst.second < 0.6) {
            Insight(InsightKind.RECOVERY, "${worst.first.label} still recovering (${pct(worst.second)}%), so volume is trimmed", InsightTone.CAUTION)
        } else {
            val names = scored.sortedByDescending { it.second }.take(2).joinToString(" and ") { it.first.label.lowercase() }
            val avg = scored.map { it.second }.average()
            if (avg >= 0.85) Insight(InsightKind.RECOVERY, "${names.replaceFirstChar { it.uppercase() }} are fresh (${pct(avg)}%)", InsightTone.POSITIVE) else null
        }
    }

    private fun effort(pe: PlannedExercise, input: PlannerInput): Insight? {
        val rpe = pe.sets.filter { it.kind != SetKind.WARMUP }.mapNotNull { it.rpe }.maxOrNull() ?: return null
        val r = input.readiness
        val cap = r.prescription.rpeCap
        return if (cap <= 8.5 && rpe <= cap) {
            Insight(InsightKind.EFFORT, "Effort capped at RPE ${fmt(cap)} for readiness ${r.score}: leave ${fmt(10 - rpe)} reps in the tank", InsightTone.CAUTION)
        } else {
            null
        }
    }

    /** Patterns that usually load a region, so "easy on your knee" only shows where it matters. */
    private val regionPatterns: Map<BodyRegion, Set<MovementPattern>> = mapOf(
        BodyRegion.KNEE to setOf(MovementPattern.SQUAT, MovementPattern.LUNGE),
        BodyRegion.ANKLE to setOf(MovementPattern.SQUAT, MovementPattern.LUNGE),
        BodyRegion.HIP to setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.LUNGE),
        BodyRegion.LOWER_BACK to setOf(MovementPattern.SQUAT, MovementPattern.HINGE, MovementPattern.HORIZONTAL_PULL),
        BodyRegion.SHOULDER to setOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH, MovementPattern.VERTICAL_PULL),
        BodyRegion.ELBOW to setOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH, MovementPattern.VERTICAL_PULL, MovementPattern.ISOLATION),
        BodyRegion.WRIST to setOf(MovementPattern.HORIZONTAL_PUSH, MovementPattern.VERTICAL_PUSH),
        BodyRegion.UPPER_BACK to setOf(MovementPattern.HORIZONTAL_PULL, MovementPattern.VERTICAL_PULL),
        BodyRegion.NECK to setOf(MovementPattern.CARRY, MovementPattern.VERTICAL_PUSH),
    )

    private fun joint(ex: Exercise, input: PlannerInput): Insight? {
        val lim = input.profile.limitations.firstOrNull { ex.pattern in regionPatterns[it.region].orEmpty() } ?: return null
        val stress = ex.stress[lim.region]
        return if (stress == null || stress == Stress.LOW) {
            Insight(InsightKind.JOINT, "Picked to go easy on your ${lim.region.label.lowercase()}", InsightTone.POSITIVE)
        } else {
            null
        }
    }

    private fun weight(ex: Exercise, kg: Double, unit: WeightUnit): String = when {
        ex.loadType == LoadType.BODYWEIGHT && kg <= 0.0 -> "bodyweight"
        ex.loadType == LoadType.BODYWEIGHT -> "+${Loads.format(kg, unit)} ${unit.label}"
        else -> "${Loads.format(kg, unit)} ${unit.label}"
    }

    private fun pct(v: Double) = (v * 100).roundToInt()

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()
}
