package app.fluff.screenshots

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.fluff.SampleData
import app.fluff.core.model.BlockType
import app.fluff.core.model.PlanSource
import app.fluff.core.model.SetKind
import app.fluff.core.progress.GoalProjector
import app.fluff.core.progress.StrengthScorer
import app.fluff.core.readiness.TrainingLoad
import app.fluff.data.db.SetEntity
import app.fluff.data.repo.WorkoutSummary
import app.fluff.domain.CoachStatus
import app.fluff.core.library.ExerciseLibrary
import app.fluff.ui.components.ChartPoint
import app.fluff.ui.progress.LiftOption
import app.fluff.ui.progress.ProgressActions
import app.fluff.ui.progress.ProgressScreen
import app.fluff.ui.progress.ProgressState
import app.fluff.ui.theme.FluffTheme
import app.fluff.ui.today.TodayActions
import app.fluff.ui.today.TodayScreen
import app.fluff.ui.today.TodayState
import app.fluff.ui.workout.Draft
import app.fluff.ui.workout.ExerciseGroup
import app.fluff.ui.workout.SummaryScreen
import app.fluff.ui.workout.SummaryState
import app.fluff.ui.workout.WorkoutActions
import app.fluff.ui.workout.WorkoutScreen
import app.fluff.ui.workout.WorkoutState
import app.fluff.core.progress.PersonalRecord
import app.fluff.core.model.Muscle
import app.fluff.ui.history.HistoryScreen
import app.fluff.ui.history.HistoryState
import app.fluff.ui.history.TimelineItem
import app.fluff.ui.profile.Connections
import app.fluff.ui.profile.ProfileActions
import app.fluff.ui.profile.ProfileScreen
import app.fluff.ui.profile.ProfileState
import app.fluff.data.prefs.AppSettings
import app.fluff.integrations.healthconnect.HcAvailability
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * Renders each main screen with realistic data. Run `./gradlew :app:recordRoborazziDebug` to
 * refresh the images in app/screenshots, and `verifyRoborazziDebug` in CI to catch regressions.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun shot(name: String, settleMs: Long = 2_500, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false
        compose.setContent { FluffTheme { content() } }
        compose.mainClock.advanceTimeBy(settleMs)
        compose.onRoot().captureRoboImage("screenshots/$name.png")
    }

    private val d = SampleData

    @Test fun today() {
        val plan = d.plan(78).copy(source = PlanSource.AI, coachNote = "HRV is up. Let's push the squat top set today.")
        shot("01_today") {
            TodayScreen(
                TodayState(
                    loading = false, today = d.today, profile = d.profile, readiness = d.readiness(78),
                    history = d.readinessHistory().takeLast(14), plan = plan, coach = CoachStatus.Refined(emptyList()), insights = d.insights(plan),
                ),
                TodayActions(),
            )
        }
    }

    @Test fun todayRecover() {
        val r = d.readiness(28)
        shot("02_today_recover") {
            TodayScreen(TodayState(loading = false, today = d.today, profile = d.profile, readiness = r, history = d.readinessHistory().takeLast(14), plan = d.plan(28), insights = d.insights(d.plan(28), 28)), TodayActions())
        }
    }

    @Test fun workout() {
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
        // First main exercise: ramp sets done, now on the first working set.
        val main = groups.first { it.block == BlockType.MAIN }
        val mainSets = main.sets.map { if (it.kind == SetKind.WARMUP.name) it.copy(completedAt = 2L) else it }
        val all = groups.map { if (it == main) it.copy(sets = mainSets) else it }
        val current = mainSets.first { it.completedAt == null }
        shot("03_workout") {
            WorkoutScreen(
                WorkoutState(
                    loading = false, sessionId = 1, title = plan.title, startedAtMs = System.currentTimeMillis() - 19 * 60_000,
                    groups = all, currentSetId = current.id,
                    draft = Draft(current.id, current.targetWeightKg, current.targetReps, 8.0, null), profile = d.profile, insights = d.insights(plan),
                ),
                WorkoutActions(),
            )
        }
    }

    @Test fun summary() {
        val s = WorkoutSummary(
            1, "Lower · Squat focus", 54, 16, 8420.0,
            listOf(PersonalRecord("back_squat", PersonalRecord.Kind.E1RM, 142.5, 138.0, 5)),
            mapOf(Muscle.QUADS to 7.0, Muscle.GLUTES to 6.5, Muscle.HAMSTRINGS to 4.0, Muscle.LOWER_BACK to 2.0),
        )
        shot("04_summary", settleMs = 600) {
            SummaryScreen(SummaryState(false, s, d.profile, 78, 8.0, debrief = "Your squat moved faster than last week at the same weight: 5 clean reps at RPE 8. Next time, keep the bar path just as tight on the Romanian deadlifts as fatigue builds."), {})
        }
    }

    @Test fun progress() {
        val strength = StrengthScorer.profile(d.history, d.profile.bodyWeightKg, d.profile.sex, d.today)
        val series = d.history.e1rmSeries("back_squat").map { ChartPoint(it.date, it.e1rmKg) }
        val goals = d.goals.map { GoalProjector.project(it, d.history.e1rmSeries(it.exerciseId), d.today) }
        val heat = d.history.sessions.associate { it.date to (TrainingLoad.sessionLoad(it) / 500.0).toFloat().coerceIn(0.2f, 1f) }
        shot("05_progress") {
            ProgressScreen(
                ProgressState(
                    loading = false, today = d.today, profile = d.profile, strength = strength,
                    activityStrength = d.profile.activities.map { StrengthScorer.activityStrength(strength, it.type) },
                    lifts = listOf("back_squat", "bench_press", "deadlift").map { LiftOption(it, ExerciseLibrary[it]!!.name, d.history.currentE1rm(it, d.today)) },
                    selectedLift = "back_squat", liftSeries = series, goals = goals, weekly = d.history.weeklyMuscleSets(d.today, 8),
                    heat = heat, streak = 10, totalSessions = d.sessions.size, readiness = d.readinessHistory(),
                ),
                ProgressActions(),
            )
        }
    }

    @Test fun history() {
        val items = d.sessions.takeLast(8).map { s ->
            TimelineItem.Lift(
                WorkoutSummary(s.id, s.focus!!.label, 55, s.sets.size, s.sets.sumOf { it.weightKg * it.reps }, emptyList(), emptyMap()),
                s.date, 60 + (s.id % 30).toInt(), if (s.id % 2 == 0L) "AI" else "ENGINE", s.startedAt.toEpochMilli(),
            )
        } + listOf(
            TimelineItem.Activity(
                app.fluff.core.model.ActivityRecord("w1", app.fluff.core.model.ActivityType.RUNNING, app.fluff.core.model.DataSource.WHOOP, d.today.minusDays(2).atTime(7, 0).toInstant(ZoneOffset.UTC), 42.0, 6.5, "Running"),
                d.today.minusDays(2), d.today.minusDays(2).atTime(7, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            ),
        )
        shot("06_history", settleMs = 1_200) {
            HistoryScreen(HistoryState(false, d.profile, items.sortedByDescending { it.sortKey }, d.today), {}, { _, _, _, _ -> }, {})
        }
    }

    @Test fun profile() {
        shot("07_profile", settleMs = 600) {
            ProfileScreen(
                ProfileState(
                    d.profile, AppSettings(healthConnectEnabled = true, lastSyncEpochMs = 1_762_790_000_000),
                    Connections(claudeKeySet = true, whoopConfigured = true, whoopConnected = true, hcAvailability = HcAvailability.AVAILABLE, hcGranted = setOf("a", "b", "c"), hcAllPermissions = setOf("a", "b", "c", "d", "e")),
                ),
                ProfileActions(),
            )
        }
    }

    @Test fun exerciseDetail() {
        val plan = d.plan(78)
        val id = plan.mainExercises.first().exerciseId
        shot("08_exercise_detail", settleMs = 800) {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.background(app.fluff.ui.theme.Fluff.Card).padding(top = 24.dp)) {
                app.fluff.ui.exercise.ExerciseDetail(id, d.insights(plan)[id].orEmpty())
            }
        }
    }

    @Test fun fitbodImport() {
        val csv = buildString {
            appendLine("Date,Exercise,Reps,Weight(kg),Duration(s),Distance(m),Incline,Resistance,isWarmup,Note,multiplier")
            listOf("2025-03-04", "2025-06-10", "2026-10-28").forEach { day ->
                repeat(3) { appendLine("$day 18:00:00 +0000,Back Squat, 5,120.0,0.0,0.0,0.0,0.0,false,, 1.0") }
                repeat(3) { appendLine("$day 18:00:00 +0000,Dumbbell Bench Press, 10,32.5,0.0,0.0,0.0,0.0,false,, 2.0") }
                repeat(3) { appendLine("$day 18:00:00 +0000,Smith Machine Squat, 8,80.0,0.0,0.0,0.0,0.0,false,, 1.0") }
                appendLine("$day 18:00:00 +0000,Zottman Curl, 12,12.5,0.0,0.0,0.0,0.0,false,, 2.0")
            }
        }
        val plan = app.fluff.core.importer.FitbodImporter.plan(app.fluff.core.importer.FitbodCsv.parse(csv), zone = ZoneOffset.UTC)
        shot("09_fitbod_import") {
            androidx.compose.foundation.layout.Column(androidx.compose.ui.Modifier.background(app.fluff.ui.theme.Fluff.Ink).padding(24.dp)) {
                app.fluff.ui.profile.FitbodImportContent.Preview(
                    app.fluff.ui.profile.FitbodImportState.Preview(plan, alreadyImported = 1), d.profile.unit, {}, {}, {},
                )
            }
        }
    }
}
