package app.fluff.integrations

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fluff.SampleData
import app.fluff.core.model.PlanSource
import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.SessionEntity
import app.fluff.data.db.SessionStatus
import app.fluff.data.db.SetEntity
import app.fluff.data.db.SignalsEntity
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.data.secure.Cryptor
import app.fluff.data.secure.SecretStore
import app.fluff.domain.CoachEngine
import app.fluff.integrations.claude.ClaudeCoach
import app.fluff.integrations.cloud.CloudApi
import app.fluff.integrations.cloud.CloudSync
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import java.io.File
import java.time.Clock
import java.time.ZoneOffset

/** The phone side of the MCP server: uploads, chunking, and applying queued changes. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class CloudSyncTest {
    private lateinit var db: FluffDatabase
    private lateinit var prefs: PreferencesStore
    private lateinit var training: TrainingRepository
    private lateinit var cloud: CloudSync
    private lateinit var server: MockWebServer
    private val clock = Clock.fixed(SampleData.today.atTime(7, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    private val uploads = mutableListOf<JsonObject>()
    private val acks = mutableListOf<JsonObject>()
    private var inbox = "[]"

    private val plainCryptor = object : Cryptor {
        override fun encrypt(plain: ByteArray) = plain
        override fun decrypt(blob: ByteArray) = blob
    }

    @Before fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, FluffDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferencesStore(ctx)
        prefs.updateProfile { SampleData.profile }
        prefs.updateSettings { it.copy(cloudUrl = null, cloudFullSyncDone = false, cloudLastSyncMs = 0, cloudLastError = null) }
        training = TrainingRepository(db, clock, json)
        val engine = CoachEngine(db, prefs, training, ClaudeCoach { null }, clock, json, TestScope())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("Authorization") != "Bearer tok") return MockResponse().setResponseCode(401)
                val body = request.body.readUtf8()
                return when (request.path) {
                    "/sync/status" -> MockResponse().setBody("""{"ok":true}""")
                    "/sync/inbox" -> MockResponse().setBody("""{"inbox":$inbox}""")
                    "/sync/snapshot" -> { uploads += json.parseToJsonElement(body).jsonObject; MockResponse().setBody("""{"ok":true,"inbox":[]}""") }
                    "/sync/inbox/ack" -> { acks += json.parseToJsonElement(body).jsonObject; MockResponse().setBody("""{"ok":true}""") }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        cloud = CloudSync(db, prefs, SecretStore(ctx, plainCryptor), training, engine, CloudApi(OkHttpClient(), json), clock, json)

        // History: every sample session, plus one long ago to force several upload chunks.
        (SampleData.sessions + SampleData.sessions.first().copy(id = 999, date = SampleData.today.minusDays(400))).forEach { s ->
            db.sessions().insert(
                SessionEntity(
                    id = s.id, epochDay = s.date.toEpochDay(), startedAt = s.startedAt.toEpochMilli(), endedAt = s.endedAt.toEpochMilli(),
                    focus = s.focus?.name, title = "Session ${s.id}", planJson = null, source = "ENGINE", readinessScore = 70, status = SessionStatus.COMPLETED,
                ),
            )
            db.sessions().insertSets(
                s.sets.mapIndexed { i, set ->
                    SetEntity(
                        sessionId = s.id, exerciseId = set.exerciseId, block = "MAIN", exerciseOrder = i / 3, setIndex = i % 3, kind = set.kind.name,
                        targetReps = set.reps, targetDurationSec = null, targetWeightKg = set.weightKg, targetRpe = 8.0, restSec = 120,
                        weightKg = set.weightKg, reps = set.reps, rpe = set.rpe, completedAt = set.completedAt.toEpochMilli(),
                    )
                },
            )
        }
        db.signals().upsert(SignalsEntity(SampleData.today.toEpochDay(), whoopRecovery = 80.0, hrvRmssdMs = 66.0, restingHeartRate = 52.0, sleepHours = 7.6, updatedAt = 0))
        SampleData.goals.forEach { g -> db.goals().insert(app.fluff.data.db.GoalEntity(exerciseId = g.exerciseId, targetKg = g.targetKg, targetEpochDay = g.targetDate?.epochDay, startE1rmKg = g.startE1rmKg, createdEpochDay = g.createdEpochDay)) }
    }

    @After fun tearDown() { db.close(); server.shutdown() }

    private fun url() = server.url("/").toString()

    @Test fun rejectsAWrongToken() = runTest {
        val err = cloud.connect(url(), "nope")
        assertThat(err).contains("token")
        assertThat(cloud.isConnected()).isFalse()
    }

    @Test fun firstSyncUploadsEverythingInChunksThenOnlyRecentDays() = runTest {
        assertThat(cloud.connect(url(), "tok")).isNull()
        assertThat(uploads.size).isGreaterThan(2)
        assertThat(uploads.first()["reset"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(uploads.drop(1).all { !it["reset"]!!.jsonPrimitive.boolean }).isTrue()
        val ids = uploads.flatMap { u -> u["sessions"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.int } }
        assertThat(ids).containsExactlyElementsIn((SampleData.sessions.map { it.id.toInt() } + 999))
        // Chunks are contiguous and end today.
        val ranges = uploads.map { it["range"]!!.jsonObject.let { r -> r["from"]!!.jsonPrimitive.content to r["to"]!!.jsonPrimitive.content } }
        ranges.zipWithNext().forEach { (a, b) -> assertThat(java.time.LocalDate.parse(a.second).plusDays(1).toString()).isEqualTo(b.first) }
        assertThat(ranges.last().second).isEqualTo(SampleData.today.toString())

        val last = uploads.last()
        val session = last["sessions"]!!.jsonArray.last().jsonObject
        assertThat(session["workSets"]!!.jsonPrimitive.int).isEqualTo(6)
        assertThat(session["volumeKg"]!!.jsonPrimitive.content.toDouble()).isGreaterThan(0.0)
        assertThat(session["sets"]!!.jsonArray.first().jsonObject["e1rmKg"]!!.jsonPrimitive.content.toDouble()).isGreaterThan(0.0)
        assertThat(last["goals"]!!.jsonArray.first().jsonObject["exerciseName"]!!.jsonPrimitive.content).isEqualTo("Back Squat")
        assertThat(last["insights"]!!.jsonObject["strength"]!!.jsonObject["overall"]!!.jsonPrimitive.int).isGreaterThan(0)
        assertThat(last["exercises"]!!.jsonArray.size).isGreaterThan(50)
        assertThat(last["days"]!!.jsonArray.first().jsonObject["whoopRecovery"]!!.jsonPrimitive.content).isEqualTo("80.0")
        // Handy for checking the contract against the server's schema (mcp/src/schema.ts).
        File("build/cloud-snapshot-sample.json").writeText(json.encodeToString(JsonObject.serializer(), last))

        uploads.clear()
        assertThat(cloud.sync()).isNull()
        assertThat(uploads).hasSize(1)
        val r = uploads.single()["range"]!!.jsonObject
        assertThat(r["from"]!!.jsonPrimitive.content).isEqualTo(SampleData.today.minusDays(29).toString())
        assertThat(prefs.settings.first().cloudLastError).isNull()
    }

    @Test fun appliesQueuedChangesAndReportsTheOutcome() = runTest {
        assertThat(cloud.connect(url(), "tok")).isNull()
        val today = SampleData.today
        inbox = """[
          {"id":"a","kind":"add_goal","payload":{"exerciseId":"bench_press","targetKg":100,"targetDate":null},"status":"pending","createdAt":"x"},
          {"id":"b","kind":"remove_goal","payload":{"goalId":12345},"status":"pending","createdAt":"x"},
          {"id":"c","kind":"log_activity","payload":{"type":"TENNIS","date":"$today","startTime":"06:30","durationMin":60,"intensity":7,"name":"Doubles"},"status":"pending","createdAt":"x"},
          {"id":"d","kind":"schedule_workout","payload":{"date":"${today.plusDays(1)}","plan":{}},"status":"pending","createdAt":"x"},
          {"id":"e","kind":"schedule_workout","payload":{"date":"$today","plan":{"title":"Assistant push","focus":"PUSH","rationale":["Legs are tired."],"coach_note":"Go well.",
            "warmup":[],
            "main":[{"exercise_id":"bench_press","sets":[{"kind":"working","reps":8,"duration_sec":0,"rpe":8,"rest_sec":120},{"kind":"working","reps":8,"duration_sec":0,"rpe":8,"rest_sec":120}],"note":""},
                    {"exercise_id":"overhead_press","sets":[{"kind":"working","reps":8,"duration_sec":0,"rpe":8,"rest_sec":120}],"note":""}],
            "cooldown":[]}},"status":"pending","createdAt":"x"},
          {"id":"f","kind":"teleport","payload":{},"status":"pending","createdAt":"x"}
        ]"""
        assertThat(cloud.sync()).isNull()
        val results = acks.single()["results"]!!.jsonArray.associate { it.jsonObject["id"]!!.jsonPrimitive.content to it.jsonObject["status"]!!.jsonPrimitive.content }
        // "d" is for tomorrow, so it stays queued (no result yet).
        assertThat(results).containsExactly("a", "applied", "b", "rejected", "c", "applied", "e", "applied", "f", "rejected")
        assertThat(db.goals().all().map { it.exerciseId }).contains("bench_press")
        assertThat(db.activities().since(0).single { it.id == "manual:assistant:c" }.type).isEqualTo("TENNIS")
        val plan = db.plans().get(today.toEpochDay())!!.json
        assertThat(plan).contains("Assistant push")
        assertThat(json.decodeFromString(app.fluff.core.model.WorkoutPlan.serializer(), plan).source).isEqualTo(PlanSource.ASSISTANT)
        // The upload after applying carries the new goal.
        assertThat(uploads.last()["goals"]!!.jsonArray.map { it.jsonObject["exerciseId"]!!.jsonPrimitive.content }).contains("bench_press")
    }

    @Test fun normalizesTheServerAddress() {
        assertThat(CloudSync.normalizeUrl("fluff-mcp.me.workers.dev/mcp")).isEqualTo("https://fluff-mcp.me.workers.dev")
        assertThat(CloudSync.normalizeUrl("https://fluff-mcp.me.workers.dev/")).isEqualTo("https://fluff-mcp.me.workers.dev")
        assertThat(CloudSync.normalizeUrl("nonsense")).isNull()
    }
}
