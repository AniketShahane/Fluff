package app.fluff.core.readiness

import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.CheckIn
import app.fluff.core.model.DailySignals
import app.fluff.core.model.SessionRecord
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

@Serializable
enum class ReadinessBand(val label: String, val headline: String, val minScore: Int) {
    PRIME("Prime", "Green light. Go after it today.", 80),
    READY("Ready", "Good to train hard with smart limits.", 65),
    STEADY("Steady", "Solid day. Quality over grinding.", 50),
    LOW("Low", "Keep it light and leave reps in the tank.", 35),
    RECOVER("Recover", "Your body is asking for recovery.", 0),
    ;

    companion object {
        fun forScore(score: Int): ReadinessBand = entries.first { score >= it.minScore }
    }
}

@Serializable
enum class FactorKind { RECOVERY, HRV, RESTING_HR, SLEEP, SUBJECTIVE, TRAINING_LOAD, YESTERDAY }

@Serializable
data class ReadinessFactor(
    val kind: FactorKind,
    val title: String,
    val detail: String,
    /** Signed contribution relative to neutral (50), in score points. */
    val impact: Int,
)

/** How the plan should be shaped by readiness. */
@Serializable
data class Prescription(
    val volumeMultiplier: Double,
    val rpeCap: Double,
    val allowTopSets: Boolean,
    val recoverySession: Boolean,
    val warmupMinutes: Int,
)

@Serializable
enum class Confidence { HIGH, MEDIUM, LOW }

@Serializable
data class Readiness(
    val epochDay: Long,
    val score: Int,
    val band: ReadinessBand,
    val factors: List<ReadinessFactor>,
    val prescription: Prescription,
    val confidence: Confidence,
    val acwr: Double?,
)

data class LoadSnapshot(val acuteDaily: Double, val chronicDaily: Double, val yesterday: Double) {
    val acwr: Double? get() = if (chronicDaily < 1.0) null else acuteDaily / chronicDaily
}

object TrainingLoad {
    /** Assumed session RPE for lifting sessions without one: hard but not maximal. */
    private const val DEFAULT_LIFT_RPE = 7.0

    fun sessionLoad(s: SessionRecord): Double {
        val rpe = s.sessionRpe ?: s.sets.mapNotNull { it.rpe }.takeIf { it.isNotEmpty() }?.average() ?: DEFAULT_LIFT_RPE
        val minutes = s.durationMinutes.takeIf { it > 1 } ?: (s.sets.size * 2.5)
        return minutes * rpe.coerceIn(1.0, 10.0)
    }

    fun snapshot(
        today: LocalDate,
        sessions: List<SessionRecord>,
        activities: List<ActivityRecord>,
        zone: ZoneId,
    ): LoadSnapshot {
        val daily = mutableMapOf<LocalDate, Double>()
        sessions.forEach { daily.merge(it.date, sessionLoad(it), Double::plus) }
        activities.forEach { a -> daily.merge(a.start.atZone(zone).toLocalDate(), a.load, Double::plus) }
        fun sum(fromDaysAgo: Int, toDaysAgo: Int) =
            (toDaysAgo..fromDaysAgo).sumOf { daily[today.minusDays(it.toLong())] ?: 0.0 }
        // Loads up to and including yesterday; today's training hasn't happened yet.
        val acute = sum(7, 1) / 7.0
        val chronic = sum(28, 1) / 28.0
        return LoadSnapshot(acute, chronic, daily[today.minusDays(1)] ?: 0.0)
    }
}

/**
 * Fuses wearable recovery, raw physiological signals scored against the user's own baseline,
 * a subjective check-in and training load into one readiness score.
 */
object ReadinessEngine {

    private const val MIN_BASELINE_DAYS = 5

    fun compute(
        today: LocalDate,
        todaySignals: DailySignals?,
        baseline: List<DailySignals>,
        checkIn: CheckIn?,
        load: LoadSnapshot,
        sleepNeedHours: Double = 8.0,
    ): Readiness {
        val factors = mutableListOf<ReadinessFactor>()
        val physio = physiological(todaySignals, baseline.filter { it.date.isBefore(today) }, sleepNeedHours, factors)
        val subjective = checkIn?.let { subjective(it, factors) }

        val base: Double
        val confidence: Confidence
        when {
            physio != null && subjective != null -> { base = 0.7 * physio + 0.3 * subjective; confidence = Confidence.HIGH }
            physio != null -> { base = physio; confidence = Confidence.MEDIUM }
            subjective != null -> { base = subjective; confidence = Confidence.MEDIUM }
            else -> { base = 62.0; confidence = Confidence.LOW }
        }

        val loadAdj = loadAdjustment(load, factors)
        val score = (base + loadAdj).coerceIn(0.0, 100.0).roundToInt()
        val band = ReadinessBand.forScore(score)
        return Readiness(
            epochDay = today.toEpochDay(),
            score = score,
            band = band,
            factors = factors.sortedByDescending { kotlin.math.abs(it.impact) },
            prescription = prescriptionFor(band),
            confidence = confidence,
            acwr = load.acwr,
        )
    }

    fun prescriptionFor(band: ReadinessBand): Prescription = when (band) {
        ReadinessBand.PRIME -> Prescription(1.1, 9.5, allowTopSets = true, recoverySession = false, warmupMinutes = 5)
        ReadinessBand.READY -> Prescription(1.0, 9.0, allowTopSets = true, recoverySession = false, warmupMinutes = 6)
        ReadinessBand.STEADY -> Prescription(0.85, 8.5, allowTopSets = false, recoverySession = false, warmupMinutes = 7)
        ReadinessBand.LOW -> Prescription(0.65, 7.5, allowTopSets = false, recoverySession = false, warmupMinutes = 8)
        ReadinessBand.RECOVER -> Prescription(0.4, 6.5, allowTopSets = false, recoverySession = true, warmupMinutes = 10)
    }

    private fun physiological(
        today: DailySignals?,
        baseline: List<DailySignals>,
        sleepNeed: Double,
        factors: MutableList<ReadinessFactor>,
    ): Double? {
        if (today == null) return null
        today.whoopRecovery?.let { r ->
            val v = r.coerceIn(0.0, 100.0)
            factors += ReadinessFactor(FactorKind.RECOVERY, "WHOOP recovery ${v.roundToInt()}%", whoopCopy(v), (v - 50).roundToInt())
            today.sleepHours?.let { h -> factors += sleepFactor(h, sleepNeed, informational = true) }
            return v
        }

        val parts = mutableListOf<Pair<Double, Double>>() // score to weight
        today.hrvRmssdMs?.takeIf { it > 0 }?.let { hrv ->
            val hist = baseline.mapNotNull { it.hrvRmssdMs?.takeIf { v -> v > 0 } }.map { ln(it) }
            val s = if (hist.size >= MIN_BASELINE_DAYS) {
                val z = zScore(ln(hrv), hist, minSd = 0.06)
                val pct = (hrv / kotlin.math.exp(hist.average()) - 1) * 100
                factors += ReadinessFactor(
                    FactorKind.HRV, "HRV ${hrv.roundToInt()} ms",
                    "${signed(pct)}% vs your ${hist.size}-day baseline", (z * 15).roundToInt(),
                )
                50 + 15 * z
            } else {
                factors += ReadinessFactor(FactorKind.HRV, "HRV ${hrv.roundToInt()} ms", "Building your baseline (${hist.size}/$MIN_BASELINE_DAYS days)", 0)
                50.0
            }
            parts += s.coerceIn(0.0, 100.0) to 0.45
        }
        today.restingHeartRate?.takeIf { it > 0 }?.let { rhr ->
            val hist = baseline.mapNotNull { it.restingHeartRate?.takeIf { v -> v > 0 } }
            val s = if (hist.size >= MIN_BASELINE_DAYS) {
                val z = zScore(rhr, hist, minSd = 1.5)
                val diff = rhr - hist.average()
                factors += ReadinessFactor(
                    FactorKind.RESTING_HR, "Resting HR ${rhr.roundToInt()} bpm",
                    "${signed(diff)} bpm vs baseline", (-z * 15).roundToInt(),
                )
                50 - 15 * z
            } else {
                50.0
            }
            parts += s.coerceIn(0.0, 100.0) to 0.25
        }
        today.sleepHours?.takeIf { it > 0 }?.let { h ->
            val perf = today.sleepPerformance ?: (h / sleepNeed * 100)
            factors += sleepFactor(h, sleepNeed, informational = false)
            parts += perf.coerceIn(0.0, 100.0) to 0.30
        }
        if (parts.isEmpty()) return null
        return parts.sumOf { it.first * it.second } / parts.sumOf { it.second }
    }

    private fun sleepFactor(hours: Double, need: Double, informational: Boolean): ReadinessFactor {
        val h = (hours * 10).roundToInt() / 10.0
        val ratio = hours / need
        val detail = when {
            ratio >= 0.95 -> "You hit your sleep need"
            ratio >= 0.8 -> "A little short of your ${need.roundToInt()} h need"
            else -> "Well short of your ${need.roundToInt()} h need"
        }
        val impact = if (informational) 0 else ((ratio.coerceAtMost(1.1) - 0.85) * 100).roundToInt().coerceIn(-30, 15)
        return ReadinessFactor(FactorKind.SLEEP, "Slept $h h", detail, impact)
    }

    private fun subjective(c: CheckIn, factors: MutableList<ReadinessFactor>): Double {
        // Each item maps 1..5 to 0..100 where higher is better.
        val items = listOf(
            (c.sleepQuality - 1) / 4.0,
            (c.energy - 1) / 4.0,
            (5 - c.soreness) / 4.0,
            (5 - c.stress) / 4.0,
        )
        val s = items.average() * 100
        val notes = buildList {
            if (c.soreness >= 4) add("quite sore")
            if (c.stress >= 4) add("stressed")
            if (c.energy <= 2) add("low energy")
            if (c.sleepQuality <= 2) add("slept poorly")
            if (c.energy >= 4) add("energised")
        }
        factors += ReadinessFactor(
            FactorKind.SUBJECTIVE, "How you feel",
            if (notes.isEmpty()) "Feeling normal" else notes.joinToString(", ").replaceFirstChar { it.uppercase() },
            ((s - 50) * 0.3).roundToInt(),
        )
        return s
    }

    private fun loadAdjustment(load: LoadSnapshot, factors: MutableList<ReadinessFactor>): Double {
        var adj = 0.0
        load.acwr?.let { r ->
            val a = when {
                r > 1.5 -> -12.0 - (r - 1.5) * 20
                r > 1.3 -> -(r - 1.3) * 40
                r < 0.8 -> 3.0
                else -> 0.0
            }.coerceAtLeast(-20.0)
            if (a != 0.0) {
                val detail = if (a < 0) "Last 7 days are ${((r - 1) * 100).roundToInt()}% above your usual load" else "Lighter week than usual: you're fresh"
                factors += ReadinessFactor(FactorKind.TRAINING_LOAD, "Training load", detail, a.roundToInt())
            }
            adj += a
        }
        // A very hard day yesterday (relative to normal) leaves residual fatigue.
        if (load.chronicDaily >= 1.0 && load.yesterday > load.chronicDaily * 2.5) {
            val a = -((load.yesterday / load.chronicDaily - 2.5) * 3 + 4).coerceAtMost(10.0)
            factors += ReadinessFactor(FactorKind.YESTERDAY, "Big day yesterday", "Yesterday's load was ${(load.yesterday / load.chronicDaily).roundToInt()}× your daily average", a.roundToInt())
            adj += a
        }
        return adj
    }

    private fun zScore(x: Double, hist: List<Double>, minSd: Double): Double {
        val m = hist.average()
        val sd = sqrt(hist.sumOf { (it - m) * (it - m) } / (hist.size - 1).coerceAtLeast(1)).coerceAtLeast(minSd)
        return ((x - m) / sd).coerceIn(-3.0, 3.0)
    }

    private fun whoopCopy(v: Double) = when {
        v >= 67 -> "In the green: well recovered"
        v >= 34 -> "In the yellow: moderately recovered"
        else -> "In the red: under-recovered"
    }

    private fun signed(v: Double): String {
        val r = v.roundToInt()
        return if (r > 0) "+$r" else "$r"
    }
}
