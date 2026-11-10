package app.fluff.core.progress

import app.fluff.core.model.Goal
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

data class GoalProgress(
    val goal: Goal,
    val currentE1rmKg: Double?,
    /** 0..1 from where the user started to the target. */
    val fraction: Double,
    /** e1RM gained per week over the recent trend, kg. */
    val weeklyGainKg: Double?,
    val projectedDate: LocalDate?,
    val onTrack: Boolean?,
    val achieved: Boolean,
)

object GoalProjector {

    /**
     * Fits a least-squares line to the last [weeks] of e1RM points and projects when the target
     * will be reached. Gains are capped (no one adds 10 kg a week forever) and stalls yield null.
     */
    fun project(goal: Goal, series: List<E1rmPoint>, today: LocalDate, weeks: Long = 10): GoalProgress {
        val current = series.lastOrNull()?.e1rmKg
        val start = goal.startE1rmKg ?: series.firstOrNull()?.e1rmKg ?: current
        val achieved = goal.achievedEpochDay != null || (current != null && current >= goal.targetKg)
        val fraction = when {
            achieved -> 1.0
            current == null || start == null -> 0.0
            goal.targetKg <= start -> if (current >= goal.targetKg) 1.0 else 0.0
            else -> ((current - start) / (goal.targetKg - start)).coerceIn(0.0, 1.0)
        }
        val recent = series.filter { !it.date.isBefore(today.minusWeeks(weeks)) }
        val slopePerDay = slope(recent)
        val weekly = slopePerDay?.times(7)?.coerceAtMost(MAX_WEEKLY_GAIN_KG)
        val projected = if (!achieved && current != null && weekly != null && weekly > MIN_WEEKLY_GAIN_KG) {
            val weeksNeeded = (goal.targetKg - current) / weekly
            today.plusDays(ceil(weeksNeeded * 7).toLong())
        } else {
            null
        }
        val onTrack = goal.targetDate?.let { td -> projected?.let { !it.isAfter(td.toLocalDate()) } ?: if (achieved) true else null }
        return GoalProgress(goal, current, fraction, weekly, projected, onTrack, achieved)
    }

    private fun slope(points: List<E1rmPoint>): Double? {
        if (points.size < 3) return null
        val base = points.first().date
        val xs = points.map { ChronoUnit.DAYS.between(base, it.date).toDouble() }
        val ys = points.map { it.e1rmKg }
        val mx = xs.average()
        val my = ys.average()
        val den = xs.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        return xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / den
    }

    private const val MAX_WEEKLY_GAIN_KG = 5.0
    private const val MIN_WEEKLY_GAIN_KG = 0.05
}
