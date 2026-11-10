package app.fluff.core.readiness

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.Muscle
import app.fluff.core.model.MuscleSize
import app.fluff.core.model.SessionRecord
import java.time.Duration
import java.time.Instant
import kotlin.math.pow

/**
 * Per-muscle fatigue with exponential decay. Each hard set adds fatigue to the muscles it
 * trains (primary 1.0, secondary 0.5) scaled by effort; outside activities add fatigue to the
 * muscles they load. Freshness is 1 − fatigue/capacity, clamped to 0..1.
 */
object MuscleFatigue {

    private fun halfLifeHours(m: Muscle) = if (m.size == MuscleSize.LARGE) 36.0 else 24.0

    /** Roughly how many hard sets in a day bring a muscle to "fully fatigued". */
    private fun capacity(m: Muscle) = if (m.size == MuscleSize.LARGE) 9.0 else 7.0

    /** Muscles loaded by each activity with relative weights (per ~60 min at the default intensity). */
    val activityMuscles: Map<ActivityType, Map<Muscle, Double>> = mapOf(
        ActivityType.RUNNING to mapOf(Muscle.QUADS to 3.0, Muscle.CALVES to 4.0, Muscle.HAMSTRINGS to 2.5, Muscle.GLUTES to 2.0),
        ActivityType.CYCLING to mapOf(Muscle.QUADS to 4.0, Muscle.GLUTES to 2.0, Muscle.CALVES to 1.5, Muscle.HAMSTRINGS to 1.0),
        ActivityType.SWIMMING to mapOf(Muscle.LATS to 3.0, Muscle.FRONT_DELTS to 2.0, Muscle.SIDE_DELTS to 1.5, Muscle.TRICEPS to 1.5, Muscle.CHEST to 1.0),
        ActivityType.CLIMBING to mapOf(Muscle.FOREARMS to 5.0, Muscle.LATS to 3.5, Muscle.BICEPS to 3.0, Muscle.UPPER_BACK to 2.0, Muscle.ABS to 1.0),
        ActivityType.TENNIS to mapOf(Muscle.QUADS to 1.5, Muscle.CALVES to 2.0, Muscle.OBLIQUES to 2.0, Muscle.FRONT_DELTS to 1.5, Muscle.FOREARMS to 1.5),
        ActivityType.FOOTBALL to mapOf(Muscle.QUADS to 3.0, Muscle.HAMSTRINGS to 3.0, Muscle.CALVES to 2.5, Muscle.ADDUCTORS to 2.5, Muscle.GLUTES to 2.0),
        ActivityType.BASKETBALL to mapOf(Muscle.QUADS to 3.0, Muscle.CALVES to 3.0, Muscle.GLUTES to 2.0, Muscle.HAMSTRINGS to 1.5),
        ActivityType.MARTIAL_ARTS to mapOf(Muscle.FOREARMS to 2.0, Muscle.UPPER_BACK to 2.0, Muscle.ABS to 2.0, Muscle.OBLIQUES to 2.0, Muscle.GLUTES to 1.5, Muscle.QUADS to 1.5),
        ActivityType.ROWING to mapOf(Muscle.LATS to 2.5, Muscle.UPPER_BACK to 2.5, Muscle.QUADS to 2.5, Muscle.HAMSTRINGS to 1.5, Muscle.LOWER_BACK to 1.5, Muscle.BICEPS to 1.0),
        ActivityType.SKIING to mapOf(Muscle.QUADS to 4.0, Muscle.GLUTES to 2.0, Muscle.ADDUCTORS to 1.5, Muscle.ABS to 1.0),
        ActivityType.HIKING to mapOf(Muscle.QUADS to 2.0, Muscle.GLUTES to 2.0, Muscle.CALVES to 2.0, Muscle.HAMSTRINGS to 1.0),
        ActivityType.YOGA to mapOf(Muscle.ABS to 0.8, Muscle.FRONT_DELTS to 0.5),
        ActivityType.WALKING to mapOf(Muscle.CALVES to 0.8, Muscle.QUADS to 0.4),
        ActivityType.HIIT to mapOf(Muscle.QUADS to 2.0, Muscle.GLUTES to 1.5, Muscle.CHEST to 1.0, Muscle.ABS to 1.5, Muscle.CALVES to 1.0, Muscle.FRONT_DELTS to 1.0),
        ActivityType.STRENGTH to emptyMap(),
        ActivityType.OTHER to mapOf(Muscle.QUADS to 1.0, Muscle.CALVES to 1.0),
    )

    fun freshness(
        now: Instant,
        sessions: List<SessionRecord>,
        activities: List<ActivityRecord>,
        lookback: Duration = Duration.ofDays(7),
    ): Map<Muscle, Double> {
        val fatigue = DoubleArray(Muscle.entries.size)
        val from = now.minus(lookback)
        sessions.flatMap { it.sets }.filter { it.isWork && it.completedAt.isAfter(from) && !it.completedAt.isAfter(now) }.forEach { set ->
            val ex = ExerciseLibrary[set.exerciseId] ?: return@forEach
            val effort = effortFactor(set.rpe)
            val hours = Duration.between(set.completedAt, now).toMinutes() / 60.0
            ex.primary.forEach { m -> fatigue[m.ordinal] += effort * decay(m, hours) }
            ex.secondary.forEach { m -> fatigue[m.ordinal] += 0.5 * effort * decay(m, hours) }
        }
        activities.filter { it.start.isAfter(from) && !it.start.isAfter(now) }.forEach { a ->
            val weights = activityMuscles[a.type].orEmpty()
            // Scale relative to an hour at the activity's default intensity.
            val dose = (a.durationMinutes / 60.0) * (a.effectiveIntensity / a.type.defaultIntensity)
            val hours = Duration.between(a.start, now).toMinutes() / 60.0
            weights.forEach { (m, w) -> fatigue[m.ordinal] += w * dose * decay(m, hours) }
        }
        return Muscle.entries.associateWith { m -> (1.0 - fatigue[m.ordinal] / capacity(m)).coerceIn(0.0, 1.0) }
    }

    private fun decay(m: Muscle, hours: Double): Double = 0.5.pow(hours.coerceAtLeast(0.0) / halfLifeHours(m))

    private fun effortFactor(rpe: Double?): Double {
        val r = rpe ?: 8.0
        return (0.4 + 0.6 * ((r - 5.0) / 5.0)).coerceIn(0.3, 1.0)
    }
}
