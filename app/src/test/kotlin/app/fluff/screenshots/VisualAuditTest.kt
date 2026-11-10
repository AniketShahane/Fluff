package app.fluff.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fluff.AppContainer
import app.fluff.SampleData
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.BlockType
import app.fluff.core.model.CheckIn
import app.fluff.core.model.Muscle
import app.fluff.core.model.PlanSource
import app.fluff.core.model.SetKind
import app.fluff.core.progress.GoalProjector
import app.fluff.core.progress.PersonalRecord
import app.fluff.core.progress.StrengthScorer
import app.fluff.core.readiness.TrainingLoad
import app.fluff.data.db.SessionEntity
import app.fluff.data.db.SessionStatus
import app.fluff.data.db.SessionWithSets
import app.fluff.data.db.SetEntity
import app.fluff.data.prefs.AppSettings
import app.fluff.data.repo.WorkoutSummary
import app.fluff.domain.CoachStatus
import app.fluff.integrations.healthconnect.HcAvailability
import app.fluff.ui.components.ChartPoint
import app.fluff.ui.history.HistoryScreen
import app.fluff.ui.history.HistoryState
import app.fluff.ui.history.SessionDetailScreen
import app.fluff.ui.history.TimelineItem
import app.fluff.ui.onboarding.OnboardingScreen
import app.fluff.ui.profile.Connections
import app.fluff.ui.profile.ProfileActions
import app.fluff.ui.profile.ProfileScreen
import app.fluff.ui.profile.ProfileState
import app.fluff.ui.profile.ProfileViewModel
import app.fluff.ui.progress.LiftOption
import app.fluff.ui.progress.ProgressActions
import app.fluff.ui.progress.ProgressScreen
import app.fluff.ui.progress.ProgressState
import app.fluff.ui.theme.Fluff
import app.fluff.ui.theme.FluffTheme
import app.fluff.ui.today.CompletedToday
import app.fluff.ui.today.TodayActions
import app.fluff.ui.today.TodayScreen
import app.fluff.ui.today.TodayState
import app.fluff.ui.workout.Draft
import app.fluff.ui.workout.ExerciseGroup
import app.fluff.ui.workout.PrEvent
import app.fluff.ui.workout.RestTimer
import app.fluff.ui.workout.SummaryScreen
import app.fluff.ui.workout.SummaryState
import app.fluff.ui.workout.WorkoutActions
import app.fluff.ui.workout.WorkoutScreen
import app.fluff.ui.workout.WorkoutState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset

/**
 * Visual audit, not a regression test: renders every screen and state (loading, empty, error, overlays) on a 411dp
 * phone and on a 360dp phone at 130% font size, either at full scroll height or at viewport height, so a person can
 * look for clipping, truncation and spacing problems. Skipped unless FLUFF_AUDIT is set:
 *   FLUFF_AUDIT=1 ./gradlew :app:recordRoborazziDebug --tests '*VisualAudit*'   → app/build/audit/<config>/
 */
abstract class VisualAuditBase(private val config: String, private val fontScale: Float, private val viewportDp: Int) {
    @get:Rule val compose = createComposeRule()
    private val d = SampleData

    @Before fun onlyWhenAsked() = Assume.assumeTrue("Set FLUFF_AUDIT=1 to run the visual audit", System.getenv("FLUFF_AUDIT") != null)

    /** [full] = lay out at the tall window height so all scroll content is visible. */
    private fun shot(name: String, full: Boolean = true, settleMs: Long = 2_500, before: () -> Unit = {}, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FluffTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.testTag("shot").fillMaxWidth().then(if (full) Modifier.fillMaxSize() else Modifier.height(viewportDp.dp)).background(Fluff.Night)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(300)
        before()
        compose.mainClock.advanceTimeBy(settleMs)
        compose.onNodeWithTag("shot").captureRoboImage("build/audit/$config/$name.png")
    }

    private fun todayState(score: Int = 78) = d.plan(score).let { plan ->
        TodayState(
            loading = false, today = d.today, profile = d.profile, readiness = d.readiness(score),
            history = d.readinessHistory().takeLast(14), plan = plan.copy(source = PlanSource.AI, coachNote = "HRV is up. Let's push the squat top set today."),
            coach = CoachStatus.Refined(emptyList()), insights = d.insights(plan, score),
        )
    }

    @Test fun today_full() = shot("today_full") { TodayScreen(todayState(), TodayActions()) }
    @Test fun today_viewport() = shot("today_viewport", full = false) { TodayScreen(todayState(), TodayActions()) }
    @Test fun today_recover() = shot("today_recover") { TodayScreen(todayState(28).copy(coach = CoachStatus.Idle), TodayActions()) }
    @Test fun today_loading() = shot("today_loading", full = false) { TodayScreen(TodayState(loading = true, today = d.today), TodayActions()) }
    @Test fun today_noData() = shot("today_noData", full = false) { TodayScreen(TodayState(loading = false, today = d.today, profile = d.profile), TodayActions()) }
    @Test fun today_thinking() = shot("today_thinking") { TodayScreen(todayState().copy(coach = CoachStatus.Thinking, syncing = true), TodayActions()) }
    @Test fun today_fallback() = shot("today_fallback") { TodayScreen(todayState().copy(coach = CoachStatus.Fallback("Claude API key rejected (401)"), settings = AppSettings(lastSyncError = "WHOOP: token expired, reconnect in Profile")), TodayActions()) }
    @Test fun today_done() = shot("today_done") { TodayScreen(todayState().copy(completed = CompletedToday(1, "Lower · Squat focus", 54, 16, 8420.0)), TodayActions()) }
    @Test fun today_active() = shot("today_active", full = false) { TodayScreen(todayState().copy(activeSessionId = 3), TodayActions()) }
    @Test fun checkIn() = shot("checkIn") { Column(Modifier.background(Fluff.Ink).padding(top = 24.dp)) { app.fluff.ui.today.CheckInSheet(CheckIn(3, 4, 2, 2)) {} } }

    private fun workoutState(): WorkoutState {
        val plan = d.plan(78)
        var id = 1L
        val groups = BlockType.entries.flatMap { bt -> plan.block(bt)?.exercises.orEmpty().map { bt to it } }.mapIndexed { order, (bt, pe) ->
            val sets = pe.sets.mapIndexed { i, s ->
                SetEntity(
                    id = id++, sessionId = 1, exerciseId = pe.exerciseId, block = bt.name, exerciseOrder = order, setIndex = i, kind = s.kind.name,
                    targetReps = s.reps, targetDurationSec = s.durationSec, targetWeightKg = s.weightKg, targetRpe = s.rpe, restSec = s.restSec,
                    completedAt = if (bt == BlockType.WARMUP) 1L else null, reps = s.reps, weightKg = s.weightKg,
                )
            }
            ExerciseGroup(order, bt, pe.exerciseId, ExerciseLibrary[pe.exerciseId], sets)
        }
        val main = groups.first { it.block == BlockType.MAIN }
        val mainSets = main.sets.map { if (it.kind == SetKind.WARMUP.name) it.copy(completedAt = 2L) else it }
        val all = groups.map { if (it == main) it.copy(sets = mainSets) else it }
        val current = mainSets.first { it.completedAt == null }
        return WorkoutState(
            loading = false, sessionId = 1, title = plan.title, startedAtMs = System.currentTimeMillis() - 19 * 60_000,
            groups = all, currentSetId = current.id, draft = Draft(current.id, current.targetWeightKg, current.targetReps, 8.0, null),
            profile = d.profile, insights = d.insights(plan),
        )
    }

    @Test fun workout_viewport() = shot("workout_viewport", full = false) { WorkoutScreen(workoutState(), WorkoutActions()) }
    @Test fun workout_full() = shot("workout_full") { WorkoutScreen(workoutState(), WorkoutActions()) }
    @Test fun workout_rest() = shot("workout_rest", full = false) {
        WorkoutScreen(workoutState().copy(rest = RestTimer(System.currentTimeMillis() + 95_000, 150, "Back Squat · set 2 of 4")), WorkoutActions())
    }
    @Test fun workout_pr() = shot("workout_pr", full = false, settleMs = 900) {
        WorkoutScreen(workoutState().copy(pr = PrEvent("Back Squat", 142.5, 138.0)), WorkoutActions())
    }
    @Test fun workout_editing() = shot("workout_editing", full = false) {
        val s = workoutState(); val done = s.allSets.first { it.completedAt != null }
        WorkoutScreen(s.copy(currentSetId = done.id, editingSetId = done.id, draft = Draft(done.id, done.weightKg, done.reps, null, done.targetDurationSec)), WorkoutActions())
    }
    @Test fun workout_toast() = shot("workout_toast", full = false, settleMs = 600) {
        WorkoutScreen(workoutState().copy(toast = "Weight bumped 2.5 kg: last set felt easy", coachNote = "Keep the bar over mid-foot."), WorkoutActions())
    }

    private val summary = WorkoutSummary(
        1, "Lower · Squat focus", 54, 16, 8420.0,
        listOf(PersonalRecord("back_squat", PersonalRecord.Kind.E1RM, 142.5, 138.0, 5), PersonalRecord("romanian_deadlift", PersonalRecord.Kind.E1RM, 121.0, null, 8)),
        mapOf(Muscle.QUADS to 7.0, Muscle.GLUTES to 6.5, Muscle.HAMSTRINGS to 4.0, Muscle.LOWER_BACK to 2.0),
    )
    @Test fun summary_full() = shot("summary_full", settleMs = 3_000) {
        SummaryScreen(SummaryState(false, summary, d.profile, 78, 8.0, debrief = "Your squat moved faster than last week at the same weight: 5 clean reps at RPE 8. Next time, keep the bar path just as tight on the Romanian deadlifts as fatigue builds."), {})
    }
    @Test fun summary_loading() = shot("summary_loading", full = false, settleMs = 3_000) { SummaryScreen(SummaryState(false, summary, d.profile, 78, null, debriefLoading = true), {}) }

    private fun progressState(): ProgressState {
        val strength = StrengthScorer.profile(d.history, d.profile.bodyWeightKg, d.profile.sex, d.today)
        return ProgressState(
            loading = false, today = d.today, profile = d.profile, strength = strength,
            activityStrength = d.profile.activities.map { StrengthScorer.activityStrength(strength, it.type) },
            lifts = listOf("back_squat", "bench_press", "deadlift", "overhead_press").map { LiftOption(it, ExerciseLibrary[it]!!.name, d.history.currentE1rm(it, d.today)) },
            selectedLift = "back_squat", liftSeries = d.history.e1rmSeries("back_squat").map { ChartPoint(it.date, it.e1rmKg) },
            goals = d.goals.map { GoalProjector.project(it, d.history.e1rmSeries(it.exerciseId), d.today) }, weekly = d.history.weeklyMuscleSets(d.today, 8),
            heat = d.history.sessions.associate { it.date to (TrainingLoad.sessionLoad(it) / 500.0).toFloat().coerceIn(0.2f, 1f) },
            streak = 10, totalSessions = d.sessions.size, readiness = d.readinessHistory(),
        )
    }
    @Test fun progress_full() = shot("progress_full") { ProgressScreen(progressState(), ProgressActions()) }
    @Test fun progress_empty() = shot("progress_empty", full = false) { ProgressScreen(ProgressState(loading = false, today = d.today, profile = d.profile), ProgressActions()) }
    @Test fun progress_loading() = shot("progress_loading", full = false) { ProgressScreen(ProgressState(loading = true, today = d.today), ProgressActions()) }

    private fun historyItems() = d.sessions.takeLast(8).map { s ->
        TimelineItem.Lift(
            WorkoutSummary(s.id, s.focus!!.label, 55, s.sets.size, s.sets.sumOf { it.weightKg * it.reps }, emptyList(), emptyMap()),
            s.date, 60 + (s.id % 30).toInt(), listOf("AI", "ENGINE", "FITBOD", "ASSISTANT")[(s.id % 4).toInt()], s.startedAt.toEpochMilli(),
        )
    } + listOf(
        TimelineItem.Activity(
            app.fluff.core.model.ActivityRecord("w1", app.fluff.core.model.ActivityType.RUNNING, app.fluff.core.model.DataSource.WHOOP, d.today.minusDays(2).atTime(7, 0).toInstant(ZoneOffset.UTC), 42.0, 6.5, "Running"),
            d.today.minusDays(2), d.today.minusDays(2).atTime(7, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
        ),
    )
    @Test fun history_full() = shot("history_full", settleMs = 1_500) { HistoryScreen(HistoryState(false, d.profile, historyItems().sortedByDescending { it.sortKey }, d.today), {}, { _, _, _, _ -> }, {}) }
    @Test fun history_empty() = shot("history_empty", full = false) { HistoryScreen(HistoryState(false, d.profile, emptyList(), d.today), {}, { _, _, _, _ -> }, {}) }

    @Test fun sessionDetail() = shot("sessionDetail", settleMs = 1_500) {
        val s = d.sessions.last()
        var id = 1L
        val sets = s.sets.mapIndexed { i, ls ->
            SetEntity(id = id++, sessionId = 1, exerciseId = ls.exerciseId, block = BlockType.MAIN.name, exerciseOrder = s.sets.map { it.exerciseId }.distinct().indexOf(ls.exerciseId), setIndex = i,
                kind = ls.kind.name, targetReps = ls.reps, targetDurationSec = null, targetWeightKg = ls.weightKg, targetRpe = 8.0, restSec = 120, reps = ls.reps, weightKg = ls.weightKg, rpe = ls.rpe, completedAt = 1L)
        }
        val entity = SessionEntity(1, s.date.toEpochDay(), s.startedAt.toEpochMilli(), s.startedAt.toEpochMilli() + 55 * 60_000, s.focus?.name, s.focus!!.label, null, "AI", 74, SessionStatus.COMPLETED, 8.0)
        SessionDetailScreen(SessionWithSets(entity, sets), summary, d.profile, {}, {})
    }

    private fun profileState(connected: Boolean) = ProfileState(
        d.profile,
        if (connected) AppSettings(healthConnectEnabled = true, lastSyncEpochMs = 1_762_790_000_000)
        else AppSettings(lastSyncError = "WHOOP: token expired"),
        if (connected) Connections(claudeKeySet = true, whoopConfigured = true, whoopConnected = true, hcAvailability = HcAvailability.AVAILABLE, hcGranted = setOf("a", "b", "c"), hcAllPermissions = setOf("a", "b", "c", "d", "e"))
        else Connections(hcAvailability = HcAvailability.NEEDS_UPDATE),
    )
    @Test fun profile_connected() = shot("profile_connected", settleMs = 800) { ProfileScreen(profileState(true), ProfileActions()) }
    @Test fun profile_fresh() = shot("profile_fresh", settleMs = 800) { ProfileScreen(profileState(false), ProfileActions()) }

    @Test fun editors() = shot("editors", settleMs = 800) {
        Column(Modifier.background(Fluff.Ink).verticalScroll(rememberScrollState()).padding(24.dp)) {
            app.fluff.ui.profile.ClaudeKeyEditor(true) {}
            app.fluff.ui.profile.WhoopEditor { _, _ -> }
            app.fluff.ui.profile.GoalPicker(d.profile.goal) {}
            app.fluff.ui.profile.ActivitiesEditor(d.profile.activities) {}
            app.fluff.ui.profile.LimitationsEditor(listOf(app.fluff.core.model.Limitation(app.fluff.core.model.BodyRegion.entries.first(), app.fluff.core.model.Severity.entries.first(), "Old tweak"))) {}
            app.fluff.ui.profile.EquipmentEditor(d.profile.equipment) {}
        }
    }

    @Test fun exerciseDetail() = shot("exerciseDetail", settleMs = 800) {
        val plan = d.plan(78); val id = plan.mainExercises.first().exerciseId
        Box(Modifier.background(Fluff.Card).padding(top = 24.dp)) { app.fluff.ui.exercise.ExerciseDetail(id, d.insights(plan)[id].orEmpty()) }
    }
    @Test fun exercisePicker() = shot("exercisePicker", full = false, settleMs = 800) {
        Box(Modifier.background(Fluff.Card).padding(top = 24.dp)) { app.fluff.ui.exercise.ExercisePicker("Swap exercise", "Same pattern, your equipment", ExerciseLibrary.all.take(14), {}) }
    }

    @Test fun onboarding() {
        val vm = ProfileViewModel(AppContainer(ApplicationProvider.getApplicationContext<Application>()))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FluffTheme {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                    Box(Modifier.testTag("shot").fillMaxWidth().height(viewportDp.dp)) { OnboardingScreen(vm) {} }
                }
            }
        }
        for (page in 0..9) {
            compose.mainClock.advanceTimeBy(1_500)
            compose.onNodeWithTag("shot").captureRoboImage("build/audit/$config/onboarding_$page.png")
            if (page == 1) compose.onNode(hasSetTextAction()).performTextInput("Aniket")
            if (page < 9) compose.onNode(hasText("Continue").or(hasText("Let's build your plan"))).performClick()
        }
    }
}

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h2600dp-xhdpi", application = Application::class)
class VisualAuditPhoneTest : VisualAuditBase("phone_411_f100", 1.0f, 891)

@RunWith(AndroidJUnit4::class) @GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h3000dp-xhdpi", application = Application::class)
class VisualAuditSmallLargeFontTest : VisualAuditBase("small_360_f130", 1.3f, 640)
