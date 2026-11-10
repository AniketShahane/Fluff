package app.fluff.core.planner

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.Exercise
import app.fluff.core.model.Experience
import app.fluff.core.model.LoadType
import app.fluff.core.model.UserProfile
import app.fluff.core.progress.Intensity
import app.fluff.core.progress.Loads
import app.fluff.core.progress.StrengthStandards
import app.fluff.core.progress.TrainingHistory
import java.time.LocalDate

/** Where a suggested weight came from; shown to the user so numbers are never a mystery. */
enum class LoadBasis { HISTORY, RELATED_LIFT, LAST_WEIGHT, STANDARDS, NONE }

data class LoadSuggestion(val loggedKg: Double?, val basis: LoadBasis)

/** Turns history (or sensible defaults) into concrete weights for a prescription. */
class LoadAdvisor(
    private val history: TrainingHistory,
    private val profile: UserProfile,
    private val today: LocalDate,
) {
    private val bw = profile.bodyWeightKg

    /** e1RM (total load, kg) for an exercise plus how we know it. */
    fun estimateE1rm(ex: Exercise): Pair<Double, LoadBasis>? {
        history.currentE1rm(ex.id, today)?.let { return it to LoadBasis.HISTORY }
        val link = ex.strengthLink ?: return null
        // Best reference-equivalent from any related lift with history.
        val related = ExerciseLibrary.all
            .filter { it.id != ex.id && it.strengthLink?.pattern == link.pattern }
            .mapNotNull { other -> history.currentE1rm(other.id, today)?.let { it / other.strengthLink!!.factor } }
            .maxOrNull()
        if (related != null) {
            // Transfers are uncertain: start 10% conservative.
            return related * link.factor * 0.9 to LoadBasis.RELATED_LIFT
        }
        val t = StrengthStandards.thresholds(link.pattern, profile.sex) ?: return null
        val ratio = when (profile.experience) {
            Experience.BEGINNER -> t[0] * 0.8
            Experience.INTERMEDIATE -> t[1] * 0.8
            Experience.ADVANCED -> t[2] * 0.8
        }
        return ratio * bw * link.factor to LoadBasis.STANDARDS
    }

    fun suggest(ex: Exercise, reps: Int, rpe: Double): LoadSuggestion {
        if (!ex.isLoaded) return LoadSuggestion(null, LoadBasis.NONE)
        val est = estimateE1rm(ex)
        if (est != null) {
            val total = est.first * Intensity.percentOf1rm(reps, rpe)
            val logged = Loads.loggedKgFor(ex, total, bw)
            val rounded = Loads.roundDown(ex, logged, profile.unit)
            return LoadSuggestion(rounded, est.second)
        }
        // Isolation work: double progression from the last time it was performed.
        lastWorkingWeight(ex, reps, rpe)?.let { return LoadSuggestion(it, LoadBasis.LAST_WEIGHT) }
        return if (ex.loadType == LoadType.BODYWEIGHT) LoadSuggestion(0.0, LoadBasis.NONE) else LoadSuggestion(null, LoadBasis.NONE)
    }

    private fun lastWorkingWeight(ex: Exercise, targetReps: Int, targetRpe: Double): Double? {
        val last = history.sessions.lastOrNull { s -> s.sets.any { it.exerciseId == ex.id && it.isWork } } ?: return null
        val sets = last.sets.filter { it.exerciseId == ex.id && it.isWork && it.weightKg > 0 }
        val top = sets.maxByOrNull { it.weightKg } ?: return null
        val inc = profile.unit.toKg(Loads.increment(ex, profile.unit))
        val hitTarget = sets.all { it.reps >= targetReps } && (top.rpe ?: 8.0) <= targetRpe
        val missed = sets.any { it.reps < targetReps - 2 }
        val next = when {
            hitTarget -> top.weightKg + inc
            missed -> top.weightKg - inc
            else -> top.weightKg
        }
        return Loads.roundDown(ex, next.coerceAtLeast(0.0), profile.unit)
    }
}
