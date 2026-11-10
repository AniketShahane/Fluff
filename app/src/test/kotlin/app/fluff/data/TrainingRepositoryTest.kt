package app.fluff.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fluff.SampleData
import app.fluff.core.model.SetKind
import app.fluff.core.progress.PersonalRecord
import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.SessionStatus
import app.fluff.data.repo.TrainingRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Clock
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class TrainingRepositoryTest {
    private lateinit var db: FluffDatabase
    private lateinit var repo: TrainingRepository
    /** Each reading moves time forward a minute, like a real session would. */
    private val clock = object : Clock() {
        private var t = SampleData.today.atTime(8, 0).toInstant(ZoneOffset.UTC)
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): java.time.Instant { t = t.plusSeconds(60); return t }
    }
    private val json = Json { ignoreUnknownKeys = true }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), FluffDatabase::class.java).allowMainThreadQueries().build()
        repo = TrainingRepository(db, clock, json)
    }

    @After fun tearDown() = db.close()

    @Test fun startCreatesEverySetAndResumesInsteadOfDuplicating() = runTest {
        val plan = SampleData.plan()
        val id = repo.start(plan)
        val s = repo.session(id)!!
        assertThat(s.session.status).isEqualTo(SessionStatus.ACTIVE)
        assertThat(s.sets).hasSize(plan.totalSets)
        assertThat(repo.start(plan)).isEqualTo(id)
    }

    @Test fun finishingDetectsPrsAndOnlyCountsLoggedSets() = runTest {
        // A previous session sets the bar: squat 100 kg × 5.
        val first = repo.start(SampleData.plan())
        repo.session(first)!!.sets.first { it.exerciseId == "back_squat" && it.kind != SetKind.WARMUP.name }.let { repo.completeSet(it, 100.0, 5, 8.0, null) }
        repo.finish(first, 7.0, null, 78.0)

        val second = repo.start(SampleData.plan())
        val sets = repo.session(second)!!.sets
        val squat = sets.first { it.exerciseId == "back_squat" && it.kind != SetKind.WARMUP.name }
        repo.completeSet(squat, 110.0, 5, 8.0, null)
        repo.skipSet(sets.first { it.id != squat.id })
        val summary = repo.finish(second, 8.0, "felt strong", 78.0)

        assertThat(summary.workSets).isEqualTo(1)
        assertThat(summary.volumeKg).isWithin(0.01).of(550.0)
        assertThat(summary.records.map { it.kind }).contains(PersonalRecord.Kind.E1RM)
        assertThat(repo.completedOn(SampleData.today.toEpochDay())).isTrue()
        assertThat(repo.activeSession.first()).isNull()
    }

    @Test fun undoAndRetargetUpdateTheSet() = runTest {
        val id = repo.start(SampleData.plan())
        val set = repo.session(id)!!.sets.first { it.kind != SetKind.WARMUP.name && it.targetWeightKg != null }
        repo.completeSet(set, 50.0, 8, 7.0, null)
        repo.undoSet(repo.session(id)!!.sets.first { it.id == set.id })
        repo.retarget(listOf(repo.session(id)!!.sets.first { it.id == set.id }.copy(targetWeightKg = 52.5)))
        val after = repo.session(id)!!.sets.first { it.id == set.id }
        assertThat(after.completedAt).isNull()
        assertThat(after.targetWeightKg).isEqualTo(52.5)
    }

    @Test fun goalsAreMarkedAchievedOnceTheEstimateCrossesTheTarget() = runTest {
        repo.addGoal("back_squat", 120.0, null, 110.0)
        repo.checkGoals(SampleData.history)
        assertThat(repo.goals.first().single().achievedEpochDay).isNotNull()
    }
}
