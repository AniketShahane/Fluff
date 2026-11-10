package app.fluff.integrations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fluff.data.secure.Cryptor
import app.fluff.data.secure.Secret
import app.fluff.data.secure.SecretStore
import app.fluff.integrations.whoop.WhoopClient
import app.fluff.integrations.whoop.WhoopException
import app.fluff.integrations.whoop.WhoopTokens
import app.fluff.core.model.ActivityType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Identity "encryption" so tests don't need Android Keystore. */
private object PlainCryptor : Cryptor {
    override fun encrypt(plain: ByteArray) = plain
    override fun decrypt(blob: ByteArray) = blob
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WhoopClientTest {
    private val server = MockWebServer()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val now = Instant.parse("2026-11-10T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var secrets: SecretStore
    private lateinit var whoop: WhoopClient
    private val requests = mutableListOf<RecordedRequest>()

    @Before fun setUp() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.requestUrl!!.encodedPath
                val next = request.requestUrl!!.queryParameter("nextToken")
                return when {
                    path.endsWith("/token") -> MockResponse().setBody("""{"access_token":"fresh","refresh_token":"rotated","expires_in":3600}""")
                    request.getHeader("Authorization") != "Bearer fresh" -> MockResponse().setResponseCode(401)
                    path.endsWith("/recovery") && next == null -> MockResponse().setBody(
                        """{"records":[{"cycle_id":1,"created_at":"2026-11-10T07:00:00Z","score_state":"SCORED","score":{"recovery_score":81,"resting_heart_rate":51,"hrv_rmssd_milli":72.5}}],"next_token":"p2"}""",
                    )
                    path.endsWith("/recovery") -> MockResponse().setBody(
                        """{"records":[{"cycle_id":2,"created_at":"2026-11-09T07:00:00Z","score_state":"SCORED","score":{"recovery_score":44,"resting_heart_rate":56,"hrv_rmssd_milli":51.0}},{"cycle_id":3,"created_at":"2026-11-08T07:00:00Z","score_state":"PENDING_SCORE"}]}""",
                    )
                    path.endsWith("/activity/sleep") -> MockResponse().setBody(
                        """{"records":[{"id":"a","start":"2026-11-09T23:00:00Z","end":"2026-11-10T06:30:00Z","nap":false,"score_state":"SCORED","score":{"stage_summary":{"total_in_bed_time_milli":27000000,"total_awake_time_milli":1800000},"sleep_performance_percentage":92}},{"id":"b","start":"2026-11-10T13:00:00Z","end":"2026-11-10T13:30:00Z","nap":true,"score_state":"SCORED"}]}""",
                    )
                    path.endsWith("/activity/workout") -> MockResponse().setBody(
                        """{"records":[{"id":"w1","start":"2026-11-09T17:00:00Z","end":"2026-11-09T17:45:00Z","sport_name":"running","score_state":"SCORED","score":{"strain":12.6,"kilojoule":2092}},{"id":"w2","start":"2026-11-09T19:00:00Z","end":"2026-11-09T19:03:00Z","sport_name":"walking"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        secrets = SecretStore(ApplicationProvider.getApplicationContext(), PlainCryptor)
        secrets.put(Secret.WHOOP_CLIENT_ID, "id")
        secrets.put(Secret.WHOOP_CLIENT_SECRET, "secret")
        // An expired access token forces a refresh on the first call.
        secrets.put(Secret.WHOOP_TOKENS, json.encodeToString(WhoopTokens.serializer(), WhoopTokens("stale", "refresh-1", now.toEpochMilli() - 1)))
        whoop = WhoopClient(OkHttpClient(), secrets, json, clock, apiBase = server.url("/developer/v2").toString().trimEnd('/'), tokenUrl = server.url("/token").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun refreshesRotatesTokenPaginatesAndMergesDays() = runTest {
        val days = whoop.days(7, ZoneOffset.UTC)

        val today = days.single { it.date == LocalDate.of(2026, 11, 10) }
        assertThat(today.recovery).isEqualTo(81.0)
        assertThat(today.hrvMs).isEqualTo(72.5)
        assertThat(today.sleepHours).isWithin(0.01).of(7.0) // 7.5 h in bed − 30 min awake; the nap is ignored
        assertThat(today.sleepPerformance).isEqualTo(92.0)
        assertThat(days.map { it.date }).doesNotContain(LocalDate.of(2026, 11, 8)) // unscored

        assertThat(requests.count { it.requestUrl!!.encodedPath.endsWith("/token") }).isEqualTo(1)
        assertThat(requests.first { it.requestUrl!!.encodedPath.endsWith("/token") }.body.readUtf8()).contains("refresh_token=refresh-1")
        val stored = json.decodeFromString(WhoopTokens.serializer(), secrets.get(Secret.WHOOP_TOKENS)!!)
        assertThat(stored.refreshToken).isEqualTo("rotated")
        assertThat(requests.filter { it.requestUrl!!.encodedPath.endsWith("/recovery") }.map { it.requestUrl!!.queryParameter("nextToken") }).containsExactly(null, "p2").inOrder()
    }

    @Test fun workoutsBecomeActivitiesWithStrainMappedToIntensity() = runTest {
        val acts = whoop.activities(7)
        val run = acts.single()
        assertThat(run.type).isEqualTo(ActivityType.RUNNING)
        assertThat(run.durationMinutes).isEqualTo(45.0)
        assertThat(run.intensity).isWithin(0.01).of(6.0)
        assertThat(run.energyKcal).isWithin(1.0).of(500.0)
    }

    @Test fun redirectWithWrongStateIsRejected() = runTest {
        whoop.authorizationUri()
        val e = runCatching { whoop.handleRedirect("code", "WRONG123", null) }.exceptionOrNull()
        assertThat(e).isInstanceOf(WhoopException::class.java)
        assertThat(secrets.get(Secret.WHOOP_PENDING_STATE)).isNull()
    }
}
