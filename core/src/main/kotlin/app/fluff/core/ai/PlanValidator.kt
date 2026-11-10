package app.fluff.core.ai

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.BlockType
import app.fluff.core.model.ExerciseRole
import app.fluff.core.model.Focus
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.PlanBlock
import app.fluff.core.model.PlanSource
import app.fluff.core.model.PlannedExercise
import app.fluff.core.model.PlannedSet
import app.fluff.core.model.SetKind
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.planner.LoadAdvisor
import app.fluff.core.planner.WorkoutPlanner
import app.fluff.core.progress.Loads
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.readiness.Readiness
import java.time.LocalDate

sealed interface ValidationResult {
    data class Valid(val plan: WorkoutPlan, val warnings: List<String>) : ValidationResult
    data class Invalid(val reason: String) : ValidationResult
}

/**
 * Turns an [AiPlan] into a safe [WorkoutPlan]. The AI never gets the last word on safety:
 * unknown or contraindicated exercises are dropped, effort is clamped to the readiness cap,
 * set counts are bounded and loads come from the deterministic [LoadAdvisor].
 */
class PlanValidator(
    private val profile: UserProfile,
    private val readiness: Readiness,
    private val history: TrainingHistory,
    private val today: LocalDate,
    private val baseline: WorkoutPlan,
) {
    private val advisor = LoadAdvisor(history, profile, today)
    private val allowed = AiCoachContract.allowedExercises(profile).map { it.id }.toSet()
    private val rpeCap = readiness.prescription.rpeCap

    fun validate(ai: AiPlan): ValidationResult {
        val warnings = mutableListOf<String>()
        val main = convert(ai.main, BlockType.MAIN, warnings)
        if (main.isEmpty()) return ValidationResult.Invalid("The coach's plan had no usable main exercises")
        val mainWorkSets = main.sumOf { pe -> pe.sets.count { it.kind != SetKind.WARMUP } }
        val maxSets = (baseline.mainExercises.sumOf { pe -> pe.sets.count { it.kind != SetKind.WARMUP } } * 1.5 + 4).toInt()
        if (mainWorkSets > maxSets) return ValidationResult.Invalid("The coach's plan had too much volume for today ($mainWorkSets sets)")

        val warmup = convert(ai.warmup, BlockType.WARMUP, warnings).ifEmpty {
            warnings += "Warm-up came back empty, so Fluff's warm-up was used"
            baseline.block(BlockType.WARMUP)?.exercises.orEmpty()
        }
        val cooldown = convert(ai.cooldown, BlockType.COOLDOWN, warnings).ifEmpty {
            warnings += "Cool-down came back empty, so Fluff's cool-down was used"
            baseline.block(BlockType.COOLDOWN)?.exercises.orEmpty()
        }
        val blocks = listOf(PlanBlock(BlockType.WARMUP, warmup), PlanBlock(BlockType.MAIN, addRamp(main)), PlanBlock(BlockType.COOLDOWN, cooldown))
        val focus = Focus.entries.firstOrNull { it.name.equals(ai.focus, ignoreCase = true) } ?: baseline.focus
        val plan = WorkoutPlan(
            epochDay = today.toEpochDay(),
            focus = focus,
            title = ai.title.trim().take(60).ifBlank { baseline.title },
            rationale = ai.rationale.map { it.trim() }.filter { it.isNotEmpty() }.take(5).ifEmpty { baseline.rationale },
            blocks = blocks,
            estimatedMinutes = WorkoutPlanner.estimateMinutes(blocks),
            source = PlanSource.AI,
            readinessScore = readiness.score,
            coachNote = ai.coachNote.trim().take(240).ifBlank { null },
        )
        return ValidationResult.Valid(plan, warnings)
    }

    private fun convert(items: List<AiExercise>, block: BlockType, warnings: MutableList<String>): List<PlannedExercise> {
        val seen = mutableSetOf<String>()
        return items.mapNotNull { item ->
            val ex = ExerciseLibrary[item.exerciseId]
            when {
                ex == null -> { warnings += "Dropped unknown exercise '${item.exerciseId}'"; return@mapNotNull null }
                item.exerciseId !in allowed -> { warnings += "Dropped ${ex.name}: not safe or not available for you"; return@mapNotNull null }
                block == BlockType.MAIN && item.exerciseId in seen -> return@mapNotNull null
            }
            seen += item.exerciseId
            ex!!
            val rawSets = item.sets.take(if (block == BlockType.MAIN) 8 else 3)
            if (rawSets.isEmpty()) return@mapNotNull null
            val sets = rawSets.map { s ->
                val kind = if (block != BlockType.MAIN) SetKind.WARMUP else when (s.kind.lowercase()) {
                    "top" -> if (readiness.prescription.allowTopSets) SetKind.TOP else SetKind.WORKING
                    "backoff" -> SetKind.BACKOFF
                    "warmup" -> SetKind.WARMUP
                    else -> SetKind.WORKING
                }
                val timed = !ex.isLoaded || ex.pattern == MovementPattern.CARRY
                val (reps, duration) = when {
                    !timed -> (s.reps.takeIf { it > 0 } ?: 8).coerceIn(1, 30) to null
                    s.durationSec > 0 -> null to s.durationSec.coerceIn(10, 3600)
                    s.reps > 0 -> s.reps.coerceIn(1, 50) to null
                    ex.defaultDurationSec != null -> null to ex.defaultDurationSec
                    else -> (ex.defaultReps ?: 10) to null
                }
                val rpe = if (block == BlockType.MAIN && ex.isLoaded && kind != SetKind.WARMUP) s.rpe.takeIf { it > 0 }?.coerceIn(5.0, rpeCap) ?: minOf(8.0, rpeCap) else null
                val weight = when {
                    !ex.isLoaded -> null
                    ex.pattern == MovementPattern.CARRY -> advisor.suggest(ex, 1, rpe ?: 7.5).loggedKg
                    kind == SetKind.WARMUP -> null
                    reps != null && rpe != null -> advisor.suggest(ex, reps, rpe).loggedKg
                    else -> null
                }
                val rest = s.restSec.coerceIn(0, 300)
                PlannedSet(kind, reps, duration, weight, rpe, rest)
            }
            if (block == BlockType.MAIN && rawSets.any { it.rpe > rpeCap }) warnings += "Clamped effort on ${ex.name}"
            PlannedExercise(ex.id, sets, item.note.trim().take(140).ifBlank { null })
        }.let { list ->
            // Keep compound work before isolation in the main block.
            if (block == BlockType.MAIN) list.sortedBy { pe -> rank(pe.exerciseId) } else list
        }
    }

    private fun rank(id: String): Int {
        val ex = ExerciseLibrary[id] ?: return 9
        return when {
            ExerciseRole.MAIN in ex.roles -> 0
            ExerciseRole.SECONDARY in ex.roles -> 1
            ExerciseRole.CORE in ex.roles && ExerciseRole.ACCESSORY !in ex.roles -> 3
            else -> 2
        }
    }

    /** Re-use the engine's ramp logic so AI plans get the same safe warm-up sets. */
    private fun addRamp(main: List<PlannedExercise>): List<PlannedExercise> {
        val first = main.firstOrNull() ?: return main
        val ex = ExerciseLibrary[first.exerciseId] ?: return main
        val working = first.sets.mapNotNull { it.weightKg }.maxOrNull() ?: return main
        if (!ex.isLoaded || working <= 0 || first.sets.any { it.kind == SetKind.WARMUP }) return main
        val ramp = listOf(0.5 to 6, 0.7 to 3, 0.85 to 1).mapNotNull { (f, r) ->
            val kg = Loads.roundDown(ex, working * f, profile.unit)
            if (kg < working) PlannedSet(SetKind.WARMUP, r, null, kg, null, 60) else null
        }.distinctBy { it.weightKg }
        return listOf(first.copy(sets = ramp + first.sets)) + main.drop(1)
    }
}
