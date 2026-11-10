package app.fluff.domain

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fluff.SampleData
import app.fluff.core.model.CheckIn
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.PlanSource
import app.fluff.data.db.FluffDatabase
import app.fluff.data.db.SignalsEntity
import app.fluff.data.prefs.PreferencesStore
import app.fluff.data.repo.TrainingRepository
import app.fluff.integrations.claude.ClaudeCoach
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
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
class CoachEngineTest {
    private lateinit var db: FluffDatabase
    private lateinit var engine: CoachEngine
    private lateinit var prefs: PreferencesStore
    private val clock = Clock.fixed(SampleData.today.atTime(7, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before fun setUp() = runTest {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(ctx, FluffDatabase::class.java).allowMainThreadQueries().build()
        prefs = PreferencesStore(ctx)
        prefs.updateProfile { SampleData.profile }
        val training = TrainingRepository(db, clock, json)
        // No API key: the coach is "not configured", so plans come from the on-device engine.
        engine = CoachEngine(db, prefs, training, ClaudeCoach { null }, clock, json, TestScope())
    }

    @After fun tearDown() = db.close()

    private suspend fun recovery(score: Double) =
        db.signals().upsert(SignalsEntity(epochDay = SampleData.today.toEpochDay(), whoopRecovery = score, updatedAt = 0))

    @Test fun plansFromTheEngineWhenTheCoachIsNotConfigured() = runTest {
        recovery(85.0)
        val plan = engine.planToday()
        assertThat(plan.source).isEqualTo(PlanSource.ENGINE)
        assertThat(engine.status.value).isEqualTo(CoachStatus.Idle)
        assertThat(engine.observePlan().first()).isEqualTo(plan)
        assertThat(engine.observeReadiness().first()!!.score).isAtLeast(65)
    }

    @Test fun ensureTodayPlanNeverReplacesAnAdjustedPlan() = runTest {
        recovery(80.0)
        val shorter = engine.planToday(PlanAdjustments(minutesOverride = 30), force = true)
        engine.ensureTodayPlan()
        assertThat(engine.observePlan().first()).isEqualTo(shorter)
    }

    @Test fun newSignalsRebuildThePlanOnlyWhenTheBandChanges() = runTest {
        recovery(85.0)
        val prime = engine.planToday(force = true)
        recovery(84.0)
        engine.onNewSignals()
        assertThat(engine.observePlan().first()).isEqualTo(prime)

        recovery(20.0)
        engine.onNewSignals()
        val after = engine.observePlan().first()!!
        assertThat(after.readinessScore).isLessThan(50)
        assertThat(after.focus).isEqualTo(app.fluff.core.model.Focus.RECOVERY)
    }

    @Test fun aRoughCheckInLowersReadiness() = runTest {
        val before = engine.refreshReadiness().score
        engine.saveCheckIn(CheckIn(sleepQuality = 1, energy = 1, soreness = 5, stress = 5))
        assertThat(engine.observeReadiness().first()!!.score).isLessThan(before)
    }
}
