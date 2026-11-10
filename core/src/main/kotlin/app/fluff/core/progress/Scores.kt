package app.fluff.core.progress

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityType
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.MovementPattern.CARRY
import app.fluff.core.model.MovementPattern.HINGE
import app.fluff.core.model.MovementPattern.HORIZONTAL_PULL
import app.fluff.core.model.MovementPattern.HORIZONTAL_PUSH
import app.fluff.core.model.MovementPattern.LUNGE
import app.fluff.core.model.MovementPattern.SQUAT
import app.fluff.core.model.MovementPattern.VERTICAL_PULL
import app.fluff.core.model.MovementPattern.VERTICAL_PUSH
import app.fluff.core.model.Sex
import java.time.LocalDate
import kotlin.math.roundToInt

enum class StrengthLevel(val label: String) {
    UNTRAINED("Untrained"),
    NOVICE("Novice"),
    INTERMEDIATE("Intermediate"),
    ADVANCED("Advanced"),
    ELITE("Elite"),
    ;

    companion object {
        fun forScore(score: Int): StrengthLevel = when {
            score >= 100 -> ELITE
            score >= 75 -> ADVANCED
            score >= 50 -> INTERMEDIATE
            score >= 25 -> NOVICE
            else -> UNTRAINED
        }
    }
}

/**
 * Approximate e1RM ÷ bodyweight thresholds for novice, intermediate, advanced and elite, for the
 * reference lift of each pattern (male lifters). Derived from commonly published strength-standard
 * tables and intentionally rounded: they power a motivating relative score, not a competition rank.
 * For bodyweight and per-hand lifts the ratio is of total load (bodyweight included) or load per hand.
 */
object StrengthStandards {
    private val male: Map<MovementPattern, DoubleArray> = mapOf(
        SQUAT to doubleArrayOf(0.75, 1.25, 1.75, 2.5),
        HINGE to doubleArrayOf(1.0, 1.5, 2.0, 2.75),
        LUNGE to doubleArrayOf(0.15, 0.3, 0.45, 0.6),
        HORIZONTAL_PUSH to doubleArrayOf(0.5, 1.0, 1.5, 2.0),
        VERTICAL_PUSH to doubleArrayOf(0.35, 0.6, 0.85, 1.15),
        HORIZONTAL_PULL to doubleArrayOf(0.5, 0.8, 1.15, 1.5),
        VERTICAL_PULL to doubleArrayOf(0.9, 1.15, 1.4, 1.7),
        CARRY to doubleArrayOf(0.25, 0.45, 0.65, 0.85),
    )

    /** Female standards scale lower, more so for upper-body patterns. */
    private val femaleFactor: Map<MovementPattern, Double> = mapOf(
        SQUAT to 0.72, HINGE to 0.72, LUNGE to 0.75, HORIZONTAL_PUSH to 0.6, VERTICAL_PUSH to 0.62,
        HORIZONTAL_PULL to 0.65, VERTICAL_PULL to 0.78, CARRY to 0.7,
    )

    /** Pull-up ratios include bodyweight, so they cannot simply be scaled. */
    private val femaleVerticalPull = doubleArrayOf(0.7, 0.95, 1.15, 1.4)

    fun thresholds(pattern: MovementPattern, sex: Sex): DoubleArray? {
        val m = male[pattern] ?: return null
        val f = if (pattern == VERTICAL_PULL) femaleVerticalPull else DoubleArray(m.size) { m[it] * femaleFactor.getValue(pattern) }
        return when (sex) {
            Sex.MALE -> m
            Sex.FEMALE -> f
            Sex.UNSPECIFIED -> DoubleArray(m.size) { (m[it] + f[it]) / 2 }
        }
    }

    /** Maps a ratio onto 0–100 with 25/50/75/100 at the four thresholds (capped at 120). */
    fun score(ratio: Double, t: DoubleArray): Int {
        if (ratio <= 0) return 0
        val anchors = doubleArrayOf(0.0) + t
        val points = doubleArrayOf(0.0, 25.0, 50.0, 75.0, 100.0)
        for (i in 1 until anchors.size) {
            if (ratio <= anchors[i]) {
                val f = (ratio - anchors[i - 1]) / (anchors[i] - anchors[i - 1])
                return (points[i - 1] + f * 25).roundToInt()
            }
        }
        val over = (ratio - t.last()) / (t.last() - t[t.size - 2]) * 25
        return (100 + over).coerceAtMost(120.0).roundToInt()
    }
}

data class PatternScore(
    val pattern: MovementPattern,
    val score: Int,
    val level: StrengthLevel,
    /** Reference-lift-equivalent e1RM in kg, if any data. */
    val referenceE1rmKg: Double?,
    val basedOnExerciseId: String?,
)

data class StrengthProfile(
    val patterns: List<PatternScore>,
    /** Weighted overall score over patterns that have data, 0–120. */
    val overall: Int,
    val level: StrengthLevel,
    val coverage: Double,
)

data class ActivityStrength(
    val activity: ActivityType,
    val score: Int,
    val limiter: MovementPattern?,
    val weights: Map<MovementPattern, Double>,
)

object StrengthScorer {

    private val overallWeights = mapOf(
        SQUAT to 1.2, HINGE to 1.2, HORIZONTAL_PUSH to 1.0, VERTICAL_PUSH to 0.8,
        HORIZONTAL_PULL to 1.0, VERTICAL_PULL to 1.0, LUNGE to 0.8, CARRY to 0.6,
    )

    fun profile(history: TrainingHistory, bodyWeightKg: Double, sex: Sex, today: LocalDate): StrengthProfile {
        val scores = MovementPattern.SCORED.map { pattern -> patternScore(pattern, history, bodyWeightKg, sex, today) }
        val withData = scores.filter { it.referenceE1rmKg != null }
        val wSum = withData.sumOf { overallWeights.getValue(it.pattern) }
        val overall = if (wSum == 0.0) 0 else (withData.sumOf { it.score * overallWeights.getValue(it.pattern) } / wSum).roundToInt()
        return StrengthProfile(scores, overall, StrengthLevel.forScore(overall), withData.size / scores.size.toDouble())
    }

    private fun patternScore(
        pattern: MovementPattern,
        history: TrainingHistory,
        bodyWeightKg: Double,
        sex: Sex,
        today: LocalDate,
    ): PatternScore {
        // Every exercise linked to the pattern contributes a reference-equivalent estimate.
        var best: Pair<String, Double>? = null
        ExerciseLibrary.all.filter { it.strengthLink?.pattern == pattern }.forEach { ex ->
            val e1rm = history.currentE1rm(ex.id, today) ?: return@forEach
            val refEquivalent = e1rm / ex.strengthLink!!.factor
            if (best == null || refEquivalent > best!!.second) best = ex.id to refEquivalent
        }
        val thresholds = StrengthStandards.thresholds(pattern, sex)
        val b = best
        if (b == null || thresholds == null) return PatternScore(pattern, 0, StrengthLevel.UNTRAINED, null, null)
        val score = StrengthStandards.score(b.second / bodyWeightKg, thresholds)
        return PatternScore(pattern, score, StrengthLevel.forScore(score), b.second, b.first)
    }

    /** How much each pattern matters for an activity. Weights sum to 1. */
    fun activityWeights(activity: ActivityType): Map<MovementPattern, Double> = normalize(
        when (activity) {
            ActivityType.RUNNING -> mapOf(LUNGE to 3.0, HINGE to 2.0, SQUAT to 2.0, CARRY to 1.5, HORIZONTAL_PULL to 0.5)
            ActivityType.CYCLING -> mapOf(SQUAT to 3.0, LUNGE to 2.0, HINGE to 2.0, CARRY to 1.0, HORIZONTAL_PULL to 0.5)
            ActivityType.SWIMMING -> mapOf(VERTICAL_PULL to 3.0, HORIZONTAL_PULL to 1.5, VERTICAL_PUSH to 1.5, HORIZONTAL_PUSH to 1.0, CARRY to 1.0, HINGE to 1.0)
            ActivityType.CLIMBING -> mapOf(VERTICAL_PULL to 3.5, CARRY to 2.5, HORIZONTAL_PULL to 1.5, LUNGE to 1.0, VERTICAL_PUSH to 0.5, HINGE to 0.5)
            ActivityType.TENNIS -> mapOf(LUNGE to 2.5, CARRY to 1.5, HORIZONTAL_PULL to 1.5, VERTICAL_PUSH to 1.0, HINGE to 1.5, SQUAT to 1.0, HORIZONTAL_PUSH to 0.5)
            ActivityType.FOOTBALL -> mapOf(LUNGE to 3.0, SQUAT to 2.0, HINGE to 2.5, CARRY to 1.0, HORIZONTAL_PULL to 0.5)
            ActivityType.BASKETBALL -> mapOf(SQUAT to 2.5, LUNGE to 2.5, HINGE to 2.0, CARRY to 1.0, HORIZONTAL_PUSH to 0.5, VERTICAL_PUSH to 0.5)
            ActivityType.MARTIAL_ARTS -> mapOf(HINGE to 2.0, HORIZONTAL_PULL to 2.0, CARRY to 2.0, SQUAT to 1.5, HORIZONTAL_PUSH to 1.5, VERTICAL_PULL to 1.0, LUNGE to 1.0)
            ActivityType.ROWING -> mapOf(HINGE to 3.0, HORIZONTAL_PULL to 3.0, SQUAT to 2.0, CARRY to 1.0, VERTICAL_PULL to 1.0)
            ActivityType.SKIING -> mapOf(SQUAT to 3.0, LUNGE to 2.5, HINGE to 1.5, CARRY to 1.0)
            ActivityType.HIKING -> mapOf(LUNGE to 3.0, SQUAT to 2.0, HINGE to 1.5, CARRY to 2.0)
            ActivityType.YOGA -> mapOf(VERTICAL_PUSH to 1.5, HORIZONTAL_PUSH to 1.5, LUNGE to 1.5, CARRY to 1.0, HINGE to 1.0, VERTICAL_PULL to 0.5)
            ActivityType.WALKING -> mapOf(LUNGE to 2.0, HINGE to 1.0, CARRY to 1.0)
            ActivityType.HIIT -> mapOf(SQUAT to 1.5, HINGE to 1.5, HORIZONTAL_PUSH to 1.0, VERTICAL_PULL to 1.0, LUNGE to 1.5, CARRY to 1.0, HORIZONTAL_PULL to 1.0)
            ActivityType.STRENGTH, ActivityType.OTHER -> overallWeights
        },
    )

    private fun normalize(m: Map<MovementPattern, Double>): Map<MovementPattern, Double> {
        val s = m.values.sum()
        return m.mapValues { it.value / s }
    }

    fun activityStrength(profile: StrengthProfile, activity: ActivityType): ActivityStrength {
        val weights = activityWeights(activity)
        val byPattern = profile.patterns.associateBy { it.pattern }
        val score = weights.entries.sumOf { (p, w) -> (byPattern[p]?.score ?: 0) * w }.roundToInt()
        // The limiter is the pattern with the biggest weighted gap to "advanced" (75).
        val limiter = weights.entries.maxByOrNull { (p, w) -> (75 - (byPattern[p]?.score ?: 0)).coerceAtLeast(0) * w }
            ?.takeIf { (p, _) -> (byPattern[p]?.score ?: 0) < 75 }?.key
        return ActivityStrength(activity, score, limiter, weights)
    }
}
