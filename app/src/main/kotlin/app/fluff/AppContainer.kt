package app.fluff

import android.content.Context
import app.fluff.data.db.FluffDatabase
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.secure.Secret
import app.fluff.data.secure.SecretStore
import app.fluff.domain.CoachEngine
import app.fluff.domain.SyncManager
import app.fluff.domain.WorkoutFinisher
import app.fluff.integrations.claude.ClaudeCoach
import app.fluff.integrations.healthconnect.HealthConnectSource
import app.fluff.integrations.whoop.WhoopClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit

/** Manual dependency graph. Everything here is a process-wide singleton. */
class AppContainer(context: Context) {
    private val app = context.applicationContext

    val clock: Clock = SystemClockProxy
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** App-wide, one-off messages (e.g. the result of a WHOOP sign-in) shown as snackbars. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val db: FluffDatabase by lazy { FluffDatabase.create(app) }
    val prefs by lazy { PreferencesStore(app) }
    val secrets by lazy { SecretStore(app) }
    val training by lazy { TrainingRepository(db, clock, json) }
    val healthConnect by lazy { HealthConnectSource(app) }
    val whoop by lazy { WhoopClient(http, secrets, json, clock) }
    val coach by lazy { ClaudeCoach { secrets.get(Secret.CLAUDE_API_KEY) } }
    val sync by lazy { SyncManager(db, whoop, healthConnect, prefs, clock) }
    val engine by lazy { CoachEngine(db, prefs, training, coach, clock, json, appScope) }
    val finisher by lazy { WorkoutFinisher(training, healthConnect, prefs, coach, json) }
}

/**
 * A clock that always follows the device's current time zone, so a user who travels gets
 * "today" right without restarting the app.
 */
object SystemClockProxy : Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
    override fun withZone(zone: java.time.ZoneId): Clock = Clock.system(zone)
    override fun instant(): java.time.Instant = java.time.Instant.now()
}
