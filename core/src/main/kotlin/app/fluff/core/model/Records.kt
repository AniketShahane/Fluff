package app.fluff.core.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate

@Serializable
enum class SetKind { WARMUP, WORKING, TOP, BACKOFF }

/** A completed set as logged by the user. */
data class LoggedSet(
    val exerciseId: String,
    val kind: SetKind,
    val weightKg: Double,
    val reps: Int,
    val rpe: Double?,
    val durationSec: Int? = null,
    val completedAt: Instant,
) {
    val isWork: Boolean get() = kind != SetKind.WARMUP
}

/** A completed lifting session. */
data class SessionRecord(
    val id: Long,
    val date: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val focus: Focus?,
    val sets: List<LoggedSet>,
    val sessionRpe: Double? = null,
) {
    val durationMinutes: Double get() = (endedAt.epochSecond - startedAt.epochSecond).coerceAtLeast(0) / 60.0
}

@Serializable
enum class DataSource(val label: String) {
    WHOOP("WHOOP"),
    HEALTH_CONNECT("Health Connect"),
    MANUAL("Manual"),
    FLUFF("Fluff"),
}

/** Any non-Fluff activity (a run, a ride, a climbing session...). */
data class ActivityRecord(
    val id: String,
    val type: ActivityType,
    val source: DataSource,
    val start: Instant,
    val durationMinutes: Double,
    /** Session RPE 1–10 if known (from WHOOP strain, heart rate, or the user). */
    val intensity: Double? = null,
    val name: String? = null,
    val energyKcal: Double? = null,
    val distanceMeters: Double? = null,
) {
    val effectiveIntensity: Double get() = (intensity ?: type.defaultIntensity).coerceIn(1.0, 10.0)

    /** Foster session-RPE training load (arbitrary units). */
    val load: Double get() = durationMinutes.coerceAtLeast(0.0) * effectiveIntensity
}

/** One day of physiological signals. Any field may be missing. */
data class DailySignals(
    val date: LocalDate,
    val whoopRecovery: Double? = null,
    val hrvRmssdMs: Double? = null,
    val restingHeartRate: Double? = null,
    val sleepHours: Double? = null,
    val sleepPerformance: Double? = null,
)

/** Morning check-in, each 1 (worst) to 5 (best), except soreness/stress where 1 = none. */
@Serializable
data class CheckIn(
    val sleepQuality: Int,
    val energy: Int,
    val soreness: Int,
    val stress: Int,
) {
    init {
        listOf(sleepQuality, energy, soreness, stress).forEach { require(it in 1..5) { "Check-in values must be 1..5" } }
    }
}

@Serializable
data class Goal(
    val id: Long,
    val exerciseId: String,
    val targetKg: Double,
    val targetDate: LocalDateSerial? = null,
    val startE1rmKg: Double? = null,
    val createdEpochDay: Long,
    val achievedEpochDay: Long? = null,
)

/** Epoch-day wrapper so goals stay trivially serializable. */
@Serializable
@JvmInline
value class LocalDateSerial(val epochDay: Long) {
    fun toLocalDate(): LocalDate = LocalDate.ofEpochDay(epochDay)

    companion object {
        fun of(date: LocalDate) = LocalDateSerial(date.toEpochDay())
    }
}
