package app.fluff.domain

import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.SignalsEntity
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.toEntity
import app.fluff.integrations.healthconnect.HcAvailability
import app.fluff.integrations.healthconnect.HealthConnectSource
import app.fluff.integrations.whoop.WhoopClient
import app.fluff.integrations.whoop.WhoopException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZoneId

data class SyncState(val running: Boolean = false, val lastMessage: String? = null)

/**
 * Pulls wearable data into the local database. WHOOP values win for a day when present (its
 * recovery already blends HRV, RHR and sleep); Health Connect fills any gaps.
 */
class SyncManager(
    private val db: FluffDatabase,
    private val whoop: WhoopClient,
    private val healthConnect: HealthConnectSource,
    private val prefs: PreferencesStore,
    private val clock: Clock,
    /** Runs after every sync, outside the lock (pushes fresh data to the Fluff MCP server). */
    private val afterSync: suspend () -> Unit = {},
) {
    private val lock = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /** Returns a human-readable error, or null on success. Safe to call concurrently. */
    suspend fun sync(days: Int = BASELINE_DAYS): String? = syncLocked(days).also { runCatching { afterSync() } }

    private suspend fun syncLocked(days: Int): String? = lock.withLock {
        _state.value = SyncState(running = true)
        val zone = ZoneId.systemDefault()
        val errors = mutableListOf<String>()
        val merged = mutableMapOf<Long, SignalsEntity>()
        val now = clock.millis()

        if (prefs.currentSettings().healthConnectEnabled && healthConnect.availability() == HcAvailability.AVAILABLE) {
            runCatching {
                healthConnect.readDays(days, zone).forEach { d ->
                    merged[d.date.toEpochDay()] = SignalsEntity(
                        epochDay = d.date.toEpochDay(), hrvRmssdMs = d.hrvMs, restingHeartRate = d.restingHr,
                        sleepHours = d.sleepHours, hrvSource = "HEALTH_CONNECT", updatedAt = now,
                    )
                }
                db.activities().upsertAll(healthConnect.readWorkouts(ACTIVITY_DAYS).map { it.toEntity() })
            }.onFailure { errors += "Health Connect: ${it.message ?: it.javaClass.simpleName}" }
        }

        if (whoop.isConnected()) {
            runCatching {
                whoop.days(days, zone).forEach { d ->
                    val key = d.date.toEpochDay()
                    val base = merged[key] ?: SignalsEntity(epochDay = key, updatedAt = now)
                    merged[key] = base.copy(
                        whoopRecovery = d.recovery ?: base.whoopRecovery,
                        hrvRmssdMs = d.hrvMs ?: base.hrvRmssdMs,
                        restingHeartRate = d.restingHr ?: base.restingHeartRate,
                        sleepHours = d.sleepHours ?: base.sleepHours,
                        sleepPerformance = d.sleepPerformance ?: base.sleepPerformance,
                        hrvSource = if (d.hrvMs != null) "WHOOP" else base.hrvSource,
                        updatedAt = now,
                    )
                }
                db.activities().upsertAll(whoop.activities(ACTIVITY_DAYS).map { it.toEntity() })
            }.onFailure {
                errors += if (it is WhoopException) it.message.orEmpty() else "WHOOP: ${it.message ?: it.javaClass.simpleName}"
            }
        }

        // Never lose previously synced values when one source fails this time.
        merged.values.forEach { new ->
            val old = db.signals().get(new.epochDay)
            db.signals().upsert(
                if (old == null) new else new.copy(
                    whoopRecovery = new.whoopRecovery ?: old.whoopRecovery,
                    hrvRmssdMs = new.hrvRmssdMs ?: old.hrvRmssdMs,
                    restingHeartRate = new.restingHeartRate ?: old.restingHeartRate,
                    sleepHours = new.sleepHours ?: old.sleepHours,
                    sleepPerformance = new.sleepPerformance ?: old.sleepPerformance,
                    hrvSource = new.hrvSource ?: old.hrvSource,
                ),
            )
        }
        val error = errors.joinToString("; ").ifBlank { null }
        prefs.updateSettings { it.copy(lastSyncEpochMs = now, lastSyncError = error) }
        _state.value = SyncState(running = false, lastMessage = error)
        error
    }

    companion object {
        const val BASELINE_DAYS = 35
        const val ACTIVITY_DAYS = 28
    }
}
