package app.fluff.core.progress

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.Exercise
import app.fluff.core.model.LoadType
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.WeightUnit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Relative intensity tables. %1RM at RPE 10 for 1–12 reps follows the widely used
 * RTS/Tuchscherer chart; each point of RPE below 10 is treated as one extra rep in reserve.
 */
object Intensity {
    private val pctAtFailure = doubleArrayOf(
        1.000, 0.955, 0.922, 0.892, 0.863, 0.837, 0.811, 0.786, 0.762, 0.739, 0.707, 0.680,
    )

    /** Fraction of 1RM that can be lifted for [reps] at [rpe]. */
    fun percentOf1rm(reps: Int, rpe: Double): Double {
        require(reps >= 1) { "reps must be >= 1" }
        val rir = (10.0 - rpe.coerceIn(5.0, 10.0))
        val effective = reps + rir // 1-based reps-to-failure
        return pctForEffectiveReps(effective)
    }

    private fun pctForEffectiveReps(effective: Double): Double {
        if (effective <= 1.0) return 1.0
        val maxTable = pctAtFailure.size.toDouble()
        if (effective <= maxTable) {
            val lo = floor(effective).toInt()
            val frac = effective - lo
            val a = pctAtFailure[lo - 1]
            val b = if (lo < pctAtFailure.size) pctAtFailure[lo] else a
            return a + (b - a) * frac
        }
        // Beyond the table, extend smoothly with Epley anchored at the last table value.
        val last = pctAtFailure.last()
        val epleyLast = 1.0 / (1.0 + maxTable / 30.0)
        val epley = 1.0 / (1.0 + effective / 30.0)
        return last * (epley / epleyLast)
    }

    /** Estimated one-rep max from a set. Returns null when the set is not informative. */
    fun e1rm(totalLoadKg: Double, reps: Int, rpe: Double?): Double? {
        if (totalLoadKg <= 0.0 || reps < 1 || reps > MAX_REPS_FOR_E1RM) return null
        val assumedRpe = rpe ?: DEFAULT_RPE
        return totalLoadKg / percentOf1rm(reps, assumedRpe)
    }

    /** Reps above this are too far from a max to estimate it reliably. */
    const val MAX_REPS_FOR_E1RM = 15

    /** When a set has no RPE we assume one rep was left in the tank. */
    const val DEFAULT_RPE = 9.0
}

/** Converts between what is logged and what the body moved. */
object Loads {
    /** Total load in kg used for strength maths (adds bodyweight for bodyweight movements). */
    fun totalLoadKg(exercise: Exercise, loggedKg: Double, bodyWeightKg: Double): Double = when (exercise.loadType) {
        LoadType.BODYWEIGHT -> bodyWeightKg * exercise.bodyweightFraction + loggedKg
        LoadType.TIMED -> 0.0
        else -> loggedKg
    }

    /** Inverse of [totalLoadKg]: what to put on the bar / belt for a target total load. */
    fun loggedKgFor(exercise: Exercise, totalKg: Double, bodyWeightKg: Double): Double = when (exercise.loadType) {
        LoadType.BODYWEIGHT -> max(0.0, totalKg - bodyWeightKg * exercise.bodyweightFraction)
        LoadType.TIMED -> 0.0
        else -> totalKg
    }

    fun e1rmOf(set: LoggedSet, bodyWeightKg: Double): Double? {
        if (!set.isWork) return null
        val ex = ExerciseLibrary[set.exerciseId] ?: return null
        if (!ex.isLoaded) return null
        return Intensity.e1rm(totalLoadKg(ex, set.weightKg, bodyWeightKg), set.reps, set.rpe)
    }

    /** Smallest sensible jump for an exercise in the user's unit, expressed in that unit. */
    fun increment(exercise: Exercise, unit: WeightUnit): Double = when (exercise.loadType) {
        LoadType.BARBELL -> if (unit == WeightUnit.KG) 2.5 else 5.0
        LoadType.PER_HAND -> if (unit == WeightUnit.KG) 2.0 else 5.0
        LoadType.MACHINE -> if (unit == WeightUnit.KG) 5.0 else 10.0
        LoadType.BODYWEIGHT -> if (unit == WeightUnit.KG) 2.5 else 5.0
        LoadType.TIMED -> 1.0
    }

    /** Empty barbell in kg; nothing lighter than this can be loaded. */
    const val BAR_KG = 20.0

    /**
     * Rounds a logged weight (kg) down to the nearest loadable increment in the user's unit,
     * never below the empty bar for barbell lifts. Returns kg.
     */
    fun roundDown(exercise: Exercise, kg: Double, unit: WeightUnit): Double {
        if (exercise.loadType == LoadType.TIMED) return 0.0
        val inc = increment(exercise, unit)
        val inUnit = unit.fromKg(kg)
        val rounded = floor(inUnit / inc + 1e-9) * inc
        val asKg = unit.toKg(rounded)
        return if (exercise.loadType == LoadType.BARBELL) max(asKg, unit.toKg(barInUnit(unit))) else max(0.0, asKg)
    }

    private fun barInUnit(unit: WeightUnit): Double = if (unit == WeightUnit.KG) BAR_KG else 45.0

    fun format(kg: Double, unit: WeightUnit): String {
        val v = unit.fromKg(kg)
        val r = (v * 10).roundToInt() / 10.0
        return if (r % 1.0 == 0.0) "${r.toInt()}" else "$r"
    }
}
