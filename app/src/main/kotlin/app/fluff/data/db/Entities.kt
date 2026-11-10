package app.fluff.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class SessionStatus { ACTIVE, COMPLETED, DISCARDED }

@Entity(tableName = "sessions", indices = [Index("epochDay"), Index("status")])
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val startedAt: Long,
    val endedAt: Long? = null,
    val focus: String?,
    val title: String,
    /** The plan the session was started from, as JSON (for history and debriefs). */
    val planJson: String?,
    val source: String,
    val readinessScore: Int?,
    val status: SessionStatus,
    val sessionRpe: Double? = null,
    val notes: String? = null,
    /** Health Connect record id once written, so we never write twice. */
    val healthConnectId: String? = null,
)

@Entity(
    tableName = "sets",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId"), Index("exerciseId")],
)
data class SetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: String,
    /** Which block the set belongs to (WARMUP / MAIN / COOLDOWN). */
    val block: String,
    val exerciseOrder: Int,
    val setIndex: Int,
    val kind: String,
    val targetReps: Int?,
    val targetDurationSec: Int?,
    val targetWeightKg: Double?,
    val targetRpe: Double?,
    val restSec: Int,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val rpe: Double? = null,
    val durationSec: Int? = null,
    val completedAt: Long? = null,
    val skipped: Boolean = false,
)

/** One merged row of physiological signals per day. */
@Entity(tableName = "signals")
data class SignalsEntity(
    @PrimaryKey val epochDay: Long,
    val whoopRecovery: Double? = null,
    val hrvRmssdMs: Double? = null,
    val restingHeartRate: Double? = null,
    val sleepHours: Double? = null,
    val sleepPerformance: Double? = null,
    val hrvSource: String? = null,
    val updatedAt: Long,
)

@Entity(tableName = "activities", indices = [Index("startEpochMs")])
data class ActivityEntity(
    /** Source-prefixed id, e.g. "whoop:…", "hc:…", "manual:…". */
    @PrimaryKey val id: String,
    val type: String,
    val source: String,
    val startEpochMs: Long,
    val durationMin: Double,
    val intensity: Double?,
    val name: String?,
    val energyKcal: Double?,
    val distanceMeters: Double?,
)

@Entity(tableName = "readiness")
data class ReadinessEntity(
    @PrimaryKey val epochDay: Long,
    val json: String,
    val computedAt: Long,
)

@Entity(tableName = "checkins")
data class CheckInEntity(
    @PrimaryKey val epochDay: Long,
    val sleepQuality: Int,
    val energy: Int,
    val soreness: Int,
    val stress: Int,
    val createdAt: Long,
)

@Entity(tableName = "plans")
data class PlanEntity(
    @PrimaryKey val epochDay: Long,
    val json: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val adjustmentsHash: Int = 0,
)

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: String,
    val targetKg: Double,
    val targetEpochDay: Long?,
    val startE1rmKg: Double?,
    val createdEpochDay: Long,
    val achievedEpochDay: Long? = null,
)
