package app.fluff.integrations.whoop

import android.net.Uri
import androidx.core.net.toUri
import app.fluff.BuildConfig
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.DataSource
import app.fluff.data.secure.Secret
import app.fluff.data.secure.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** WHOOP API v2 endpoints. https://developer.whoop.com */
object WhoopEndpoints {
    const val AUTHORIZE = "https://api.prod.whoop.com/oauth/oauth2/auth"
    const val TOKEN = "https://api.prod.whoop.com/oauth/oauth2/token"
    const val API = "https://api.prod.whoop.com/developer/v2"
    const val REDIRECT_URI = "fluff://oauth/whoop"
    val SCOPES = listOf("offline", "read:recovery", "read:cycles", "read:sleep", "read:workout", "read:profile", "read:body_measurement")
}

@Serializable
data class WhoopTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMs: Long,
)

@Serializable
private data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long = 3600,
)

@Serializable
data class Page<T>(val records: List<T> = emptyList(), @SerialName("next_token") val nextToken: String? = null)

@Serializable
data class WhoopRecovery(
    @SerialName("cycle_id") val cycleId: Long? = null,
    @SerialName("sleep_id") val sleepId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("score_state") val scoreState: String? = null,
    val score: Score? = null,
) {
    @Serializable
    data class Score(
        @SerialName("user_calibrating") val userCalibrating: Boolean? = null,
        @SerialName("recovery_score") val recoveryScore: Double? = null,
        @SerialName("resting_heart_rate") val restingHeartRate: Double? = null,
        @SerialName("hrv_rmssd_milli") val hrvRmssdMilli: Double? = null,
    )
}

@Serializable
data class WhoopSleep(
    val id: String? = null,
    val start: String? = null,
    val end: String? = null,
    val nap: Boolean = false,
    @SerialName("score_state") val scoreState: String? = null,
    val score: Score? = null,
) {
    @Serializable
    data class Score(
        @SerialName("stage_summary") val stageSummary: Stages? = null,
        @SerialName("sleep_performance_percentage") val sleepPerformance: Double? = null,
    )

    @Serializable
    data class Stages(
        @SerialName("total_in_bed_time_milli") val inBed: Long? = null,
        @SerialName("total_awake_time_milli") val awake: Long? = null,
    )
}

@Serializable
data class WhoopWorkout(
    val id: String,
    val start: String,
    val end: String,
    @SerialName("sport_name") val sportName: String? = null,
    @SerialName("score_state") val scoreState: String? = null,
    val score: Score? = null,
) {
    @Serializable
    data class Score(
        val strain: Double? = null,
        @SerialName("average_heart_rate") val averageHeartRate: Double? = null,
        val kilojoule: Double? = null,
        @SerialName("distance_meter") val distanceMeter: Double? = null,
    )
}

data class WhoopDay(val date: LocalDate, val recovery: Double?, val hrvMs: Double?, val restingHr: Double?, val sleepHours: Double?, val sleepPerformance: Double?)

class WhoopException(message: String, val needsReauth: Boolean = false) : IOException(message)

/**
 * OAuth 2.0 (authorization code + refresh) and data access for WHOOP.
 * WHOOP rotates refresh tokens on every refresh, so refreshes are serialised behind a mutex.
 */
class WhoopClient(
    private val http: OkHttpClient,
    private val secrets: SecretStore,
    private val json: Json,
    private val clock: Clock,
    private val apiBase: String = WhoopEndpoints.API,
    private val tokenUrl: String = WhoopEndpoints.TOKEN,
) {
    private val refreshLock = Mutex()

    suspend fun clientId(): String? = secrets.get(Secret.WHOOP_CLIENT_ID)?.takeIf { it.isNotBlank() } ?: BuildConfig.WHOOP_CLIENT_ID.takeIf { it.isNotBlank() }
    private suspend fun clientSecret(): String? = secrets.get(Secret.WHOOP_CLIENT_SECRET)?.takeIf { it.isNotBlank() } ?: BuildConfig.WHOOP_CLIENT_SECRET.takeIf { it.isNotBlank() }

    suspend fun isConfigured(): Boolean = clientId() != null && clientSecret() != null
    suspend fun isConnected(): Boolean = tokens() != null

    private suspend fun tokens(): WhoopTokens? = secrets.get(Secret.WHOOP_TOKENS)?.let { runCatching { json.decodeFromString(WhoopTokens.serializer(), it) }.getOrNull() }
    private suspend fun saveTokens(t: WhoopTokens?) = secrets.put(Secret.WHOOP_TOKENS, t?.let { json.encodeToString(WhoopTokens.serializer(), it) })

    /** Builds the authorization URL and remembers the CSRF state. */
    suspend fun authorizationUri(): Uri {
        val id = clientId() ?: throw WhoopException("Add your WHOOP client ID first")
        val state = randomState()
        secrets.put(Secret.WHOOP_PENDING_STATE, state)
        return WhoopEndpoints.AUTHORIZE.toUri().buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", id)
            .appendQueryParameter("redirect_uri", WhoopEndpoints.REDIRECT_URI)
            .appendQueryParameter("scope", WhoopEndpoints.SCOPES.joinToString(" "))
            .appendQueryParameter("state", state)
            .build()
    }

    /** Handles the redirect back into the app. */
    suspend fun handleRedirect(code: String?, state: String?, error: String?) {
        if (error != null) throw WhoopException("WHOOP sign-in was cancelled ($error)")
        val expected = secrets.get(Secret.WHOOP_PENDING_STATE)
        secrets.put(Secret.WHOOP_PENDING_STATE, null)
        if (expected == null || state != expected) throw WhoopException("Sign-in check failed. Please try again")
        if (code.isNullOrBlank()) throw WhoopException("WHOOP did not return an authorization code")
        val form = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId() ?: throw WhoopException("Missing WHOOP client ID"))
            .add("client_secret", clientSecret() ?: throw WhoopException("Missing WHOOP client secret"))
            .add("redirect_uri", WhoopEndpoints.REDIRECT_URI)
            .build()
        saveTokens(exchange(form))
    }

    suspend fun disconnect() = saveTokens(null)

    private suspend fun exchange(form: FormBody): WhoopTokens = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(tokenUrl).post(form).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body.string()
            if (!resp.isSuccessful) throw WhoopException("WHOOP token request failed (${resp.code})", needsReauth = resp.code in 400..401)
            val t = json.decodeFromString(TokenResponse.serializer(), body)
            WhoopTokens(t.accessToken, t.refreshToken, clock.millis() + t.expiresIn * 1000)
        }
    }

    private suspend fun validAccessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val t = tokens() ?: throw WhoopException("WHOOP is not connected", needsReauth = true)
        if (!forceRefresh && t.expiresAtEpochMs - clock.millis() > 60_000) return@withLock t.accessToken
        val refresh = t.refreshToken ?: throw WhoopException("WHOOP session expired. Reconnect", needsReauth = true)
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refresh)
            .add("client_id", clientId() ?: throw WhoopException("Missing WHOOP client ID", true))
            .add("client_secret", clientSecret() ?: throw WhoopException("Missing WHOOP client secret", true))
            .add("scope", "offline")
            .build()
        val fresh = try {
            exchange(form)
        } catch (e: WhoopException) {
            if (e.needsReauth) saveTokens(null)
            throw e
        }
        saveTokens(fresh.copy(refreshToken = fresh.refreshToken ?: refresh))
        fresh.accessToken
    }

    private suspend fun get(url: HttpUrl): String {
        suspend fun call(token: String) = withContext(Dispatchers.IO) {
            http.newCall(Request.Builder().url(url).header("Authorization", "Bearer $token").build()).execute()
        }
        var resp = call(validAccessToken())
        if (resp.code == 401) {
            resp.close()
            resp = call(validAccessToken(forceRefresh = true))
        }
        return resp.use {
            if (!it.isSuccessful) throw WhoopException("WHOOP request failed (${it.code})", needsReauth = it.code == 401)
            it.body.string()
        }
    }

    private suspend fun <T> collect(path: String, start: Instant, end: Instant, decode: (String) -> Page<T>): List<T> {
        val out = mutableListOf<T>()
        var next: String? = null
        var pages = 0
        do {
            val url = "$apiBase/$path".toHttpUrl().newBuilder()
                .addQueryParameter("start", start.toString())
                .addQueryParameter("end", end.toString())
                .addQueryParameter("limit", "25")
                .apply { next?.let { addQueryParameter("nextToken", it) } }
                .build()
            val page = decode(get(url))
            out += page.records
            next = page.nextToken?.takeIf { it.isNotBlank() }
        } while (next != null && ++pages < 20)
        return out
    }

    suspend fun recoveries(start: Instant, end: Instant): List<WhoopRecovery> =
        collect("recovery", start, end) { json.decodeFromString(Page.serializer(WhoopRecovery.serializer()), it) }

    suspend fun sleeps(start: Instant, end: Instant): List<WhoopSleep> =
        collect("activity/sleep", start, end) { json.decodeFromString(Page.serializer(WhoopSleep.serializer()), it) }

    suspend fun workouts(start: Instant, end: Instant): List<WhoopWorkout> =
        collect("activity/workout", start, end) { json.decodeFromString(Page.serializer(WhoopWorkout.serializer()), it) }

    /** Recovery + sleep merged into one row per day. */
    suspend fun days(days: Int, zone: ZoneId): List<WhoopDay> {
        val end = clock.instant()
        val start = end.minus(Duration.ofDays(days.toLong()))
        val rec = recoveries(start, end).filter { it.scoreState == "SCORED" && it.score?.userCalibrating != true && it.createdAt != null }
        val sleep = sleeps(start, end).filter { !it.nap && it.scoreState == "SCORED" && it.end != null }
        val recByDay = rec.associateBy { Instant.parse(it.createdAt).atZone(zone).toLocalDate() }
        val sleepByDay = sleep.groupBy { Instant.parse(it.end).atZone(zone).toLocalDate() }
        return (recByDay.keys + sleepByDay.keys).toSortedSet().map { d ->
            val r = recByDay[d]?.score
            val s = sleepByDay[d]?.maxByOrNull { it.score?.stageSummary?.inBed ?: 0 }
            val stages = s?.score?.stageSummary
            val asleepHours = stages?.inBed?.let { (it - (stages.awake ?: 0)) / 3_600_000.0 }
            WhoopDay(d, r?.recoveryScore, r?.hrvRmssdMilli, r?.restingHeartRate, asleepHours, s?.score?.sleepPerformance)
        }
    }

    suspend fun activities(days: Int): List<ActivityRecord> {
        val end = clock.instant()
        return workouts(end.minus(Duration.ofDays(days.toLong())), end).mapNotNull { w ->
            val s = Instant.parse(w.start)
            val e = Instant.parse(w.end)
            val minutes = Duration.between(s, e).toMinutes().toDouble()
            if (minutes < 5) return@mapNotNull null
            ActivityRecord(
                id = "whoop:${w.id}",
                type = mapSport(w.sportName),
                source = DataSource.WHOOP,
                start = s,
                durationMinutes = minutes,
                intensity = w.score?.strain?.let { strainToIntensity(it) },
                name = w.sportName?.replace('-', ' ')?.replaceFirstChar { it.uppercase() },
                energyKcal = w.score?.kilojoule?.let { it / 4.184 },
                distanceMeters = w.score?.distanceMeter,
            )
        }
    }

    private fun randomState(): String {
        // WHOOP requires an 8-character state value.
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rnd = SecureRandom()
        return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
    }

    companion object {
        /** WHOOP strain (0–21, logarithmic) mapped onto session RPE (1–10). */
        fun strainToIntensity(strain: Double): Double = (strain / 2.1).coerceIn(1.0, 10.0)

        fun mapSport(name: String?): ActivityType {
            val n = name?.lowercase().orEmpty()
            return when {
                "run" in n || "track" in n -> ActivityType.RUNNING
                "cycl" in n || "bik" in n || "spin" in n -> ActivityType.CYCLING
                "swim" in n -> ActivityType.SWIMMING
                "climb" in n || "boulder" in n -> ActivityType.CLIMBING
                listOf("tennis", "squash", "padel", "pickleball", "badminton", "racquet").any { it in n } -> ActivityType.TENNIS
                "soccer" in n || "football" in n || "rugby" in n -> ActivityType.FOOTBALL
                "basketball" in n || "volleyball" in n -> ActivityType.BASKETBALL
                listOf("jiu", "box", "martial", "mma", "kickbox", "wrestl", "judo", "muay").any { it in n } -> ActivityType.MARTIAL_ARTS
                "row" in n -> ActivityType.ROWING
                "ski" in n || "snowboard" in n -> ActivityType.SKIING
                "hik" in n || "ruck" in n -> ActivityType.HIKING
                "yoga" in n || "pilates" in n || "stretch" in n -> ActivityType.YOGA
                "walk" in n -> ActivityType.WALKING
                "hiit" in n || "functional" in n || "crossfit" in n || "interval" in n -> ActivityType.HIIT
                "weight" in n || "strength" in n || "powerlift" in n -> ActivityType.STRENGTH
                else -> ActivityType.OTHER
            }
        }
    }
}
