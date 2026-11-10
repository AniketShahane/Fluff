package app.fluff.integrations.healthconnect

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.DataSource
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Per-day physiological signals read from Health Connect. */
data class HcDay(val date: LocalDate, val hrvMs: Double?, val restingHr: Double?, val sleepHours: Double?)

enum class HcAvailability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

/**
 * Reads HRV, resting heart rate, sleep and workouts written by any wearable app (Google Health /
 * Fitbit, Pixel Watch, Garmin, Oura, Samsung…) and writes Fluff sessions back as strength training.
 */
class HealthConnectSource(private val context: Context) {

    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
    )
    val writePermissions: Set<String> = setOf(HealthPermission.getWritePermission(ExerciseSessionRecord::class))
    /** Lets the periodic sync read while Fluff isn't on screen (Health Connect 1.1+). */
    val backgroundPermissions: Set<String> = setOf(HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
    val allPermissions: Set<String> get() = readPermissions + writePermissions + backgroundPermissions

    fun availability(): HcAvailability = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> HcAvailability.AVAILABLE
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> HcAvailability.NEEDS_UPDATE
        else -> HcAvailability.UNAVAILABLE
    }

    private val client: HealthConnectClient? by lazy {
        if (availability() == HcAvailability.AVAILABLE) HealthConnectClient.getOrCreate(context) else null
    }

    suspend fun grantedPermissions(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions() }.getOrNull().orEmpty()

    suspend fun hasReadAccess(): Boolean = grantedPermissions().any { it in readPermissions }

    private suspend fun <T : Record> readAll(type: KClass<T>, from: Instant, to: Instant): List<T> {
        val c = client ?: return emptyList()
        if (HealthPermission.getReadPermission(type) !in grantedPermissions()) return emptyList()
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = c.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(from, to),
                    pageSize = 1000,
                    pageToken = token,
                ),
            )
            out += resp.records
            token = resp.pageToken
        } while (token != null && out.size < 20_000)
        return out
    }

    suspend fun readDays(days: Int, zone: ZoneId, now: Instant = Instant.now()): List<HcDay> {
        val from = now.minus(Duration.ofDays(days.toLong() + 1))
        // Night-time readings belong to the morning they lead into: shift by 6 h before bucketing.
        fun dayOf(t: Instant): LocalDate = t.plus(Duration.ofHours(6)).atZone(zone).toLocalDate()

        val hrv = readAll(HeartRateVariabilityRmssdRecord::class, from, now)
            .groupBy { dayOf(it.time) }
            .mapValues { (_, v) -> median(v.map { it.heartRateVariabilityMillis }) }
        val rhr = readAll(RestingHeartRateRecord::class, from, now)
            .groupBy { dayOf(it.time) }
            .mapValues { (_, v) -> v.map { it.beatsPerMinute.toDouble() }.average() }
        val sleep = readAll(SleepSessionRecord::class, from, now)
            .groupBy { dayOf(it.endTime) }
            .mapValues { (_, sessions) -> sessions.sumOf { asleepHours(it) } }

        return (hrv.keys + rhr.keys + sleep.keys).toSortedSet().map { d ->
            HcDay(d, hrv[d], rhr[d], sleep[d]?.takeIf { it > 0.5 })
        }
    }

    private fun asleepHours(s: SleepSessionRecord): Double {
        val awake = setOf(SleepSessionRecord.STAGE_TYPE_AWAKE, SleepSessionRecord.STAGE_TYPE_OUT_OF_BED, SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED)
        val stages = s.stages
        val seconds = if (stages.isEmpty()) {
            Duration.between(s.startTime, s.endTime).seconds
        } else {
            stages.filter { it.stage !in awake }.sumOf { Duration.between(it.startTime, it.endTime).seconds }
        }
        return seconds / 3600.0
    }

    suspend fun readWorkouts(days: Int, now: Instant = Instant.now()): List<ActivityRecord> {
        val from = now.minus(Duration.ofDays(days.toLong()))
        return readAll(ExerciseSessionRecord::class, from, now)
            .filter { it.metadata.dataOrigin.packageName != context.packageName }
            .mapNotNull { r ->
                val minutes = Duration.between(r.startTime, r.endTime).toMinutes().toDouble()
                if (minutes < 5) return@mapNotNull null
                ActivityRecord(
                    id = "hc:${r.metadata.id}",
                    type = mapExerciseType(r.exerciseType),
                    source = DataSource.HEALTH_CONNECT,
                    start = r.startTime,
                    durationMinutes = minutes,
                    intensity = null,
                    name = r.title,
                )
            }
    }

    /** Writes a completed Fluff session. The client record id makes retries idempotent. */
    suspend fun writeStrengthSession(sessionId: Long, title: String, start: Instant, end: Instant, notes: String?): String? {
        val c = client ?: return null
        if (writePermissions.first() !in grantedPermissions()) return null
        val zone = ZoneId.systemDefault()
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = zone.rules.getOffset(start),
            endTime = end,
            endZoneOffset = zone.rules.getOffset(end),
            metadata = Metadata.manualEntry(clientRecordId = "fluff-session-$sessionId", clientRecordVersion = 1L),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            title = title,
            notes = notes,
        )
        return c.insertRecords(listOf(record)).recordIdsList.firstOrNull()
    }

    companion object {
        fun mapExerciseType(type: Int): ActivityType = when (type) {
            ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> ActivityType.RUNNING
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> ActivityType.CYCLING
            ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> ActivityType.SWIMMING
            ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> ActivityType.CLIMBING
            ExerciseSessionRecord.EXERCISE_TYPE_TENNIS, ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON,
            ExerciseSessionRecord.EXERCISE_TYPE_SQUASH, ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS,
            ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL -> ActivityType.TENNIS
            ExerciseSessionRecord.EXERCISE_TYPE_SOCCER, ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN,
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN, ExerciseSessionRecord.EXERCISE_TYPE_RUGBY -> ActivityType.FOOTBALL
            ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL, ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL -> ActivityType.BASKETBALL
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS, ExerciseSessionRecord.EXERCISE_TYPE_BOXING -> ActivityType.MARTIAL_ARTS
            ExerciseSessionRecord.EXERCISE_TYPE_ROWING, ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> ActivityType.ROWING
            ExerciseSessionRecord.EXERCISE_TYPE_SKIING, ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> ActivityType.SKIING
            ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> ActivityType.HIKING
            ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
            ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING -> ActivityType.YOGA
            ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> ActivityType.WALKING
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP -> ActivityType.HIIT
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
            ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> ActivityType.STRENGTH
            else -> ActivityType.OTHER
        }

        internal fun median(v: List<Double>): Double? {
            if (v.isEmpty()) return null
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}
