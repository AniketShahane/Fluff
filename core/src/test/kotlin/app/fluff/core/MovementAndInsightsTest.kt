package app.fluff.core

import app.fluff.core.Fixtures.input
import app.fluff.core.Fixtures.session
import app.fluff.core.Fixtures.today
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.library.ExerciseVideos
import app.fluff.core.model.BodyRegion
import app.fluff.core.model.Experience
import app.fluff.core.model.Focus
import app.fluff.core.model.Limitation
import app.fluff.core.model.MovementEmphasis
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.MovementType
import app.fluff.core.model.Muscle
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.PlannedExercise
import app.fluff.core.model.PlannedSet
import app.fluff.core.model.SetKind
import app.fluff.core.model.Severity
import app.fluff.core.model.TrainingGoal
import app.fluff.core.model.UserProfile
import app.fluff.core.planner.InsightKind
import app.fluff.core.planner.InsightTone
import app.fluff.core.planner.PlanInsights
import app.fluff.core.planner.WorkoutPlanner
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class MovementAndInsightsTest {
    private val planner = WorkoutPlanner()

    @Test fun movementTypesFollowPatterns() {
        assertThat(ExerciseLibrary.require("back_squat").movementType).isEqualTo(MovementType.COMPOUND)
        assertThat(ExerciseLibrary.require("lateral_raise").movementType).isEqualTo(MovementType.ISOLATION)
        assertThat(ExerciseLibrary.require("plank").movementType).isEqualTo(MovementType.CORE)
        assertThat(ExerciseLibrary.require("farmer_carry").movementType).isEqualTo(MovementType.CARRY)
        assertThat(ExerciseLibrary.require("couch_stretch").movementType).isEqualTo(MovementType.MOBILITY)
        assertThat(ExerciseLibrary.require("bike_easy").movementType).isEqualTo(MovementType.CARDIO)
    }

    @Test fun everyStrengthAndCoreExerciseHasAFormVideo() {
        val needVideo = ExerciseLibrary.all.filter { it.pattern != MovementPattern.CARDIO && it.pattern != MovementPattern.MOBILITY }
        needVideo.forEach { ex -> assertWithMessage(ex.id).that(ExerciseVideos[ex.id]).isNotNull() }
        ExerciseVideos.all.forEach { (id, v) ->
            assertWithMessage(id).that(ExerciseLibrary[id]).isNotNull()
            assertThat(v.youtubeId).matches("[A-Za-z0-9_-]{11}")
        }
        // Every video is distinct.
        assertThat(ExerciseVideos.all.values.map { it.youtubeId }.toSet()).hasSize(ExerciseVideos.all.size)
        assertThat(ExerciseVideos.searchUrl("Couch Stretch")).contains("Couch+Stretch+form+shorts")
    }

    private fun upper(emphasis: MovementEmphasis) = UserProfile(
        goal = TrainingGoal.HYPERTROPHY, experience = Experience.INTERMEDIATE, daysPerWeek = 4,
        sessionMinutes = 90, movementEmphasis = emphasis, onboarded = true,
    )

    @Test fun compoundsFirstRemovesIsolationFromMainWork() {
        val plan = planner.plan(input(upper(MovementEmphasis.COMPOUNDS), adjustments = PlanAdjustments(focusOverride = Focus.UPPER)))
        val types = plan.mainExercises.map { ExerciseLibrary.require(it.exerciseId).movementType }
        assertThat(types).doesNotContain(MovementType.ISOLATION)
        assertThat(plan.mainExercises.size).isAtLeast(4)
        assertThat(plan.rationale.joinToString()).contains("Compounds first")
    }

    @Test fun moreIsolationAddsSingleJointWork() {
        val balanced = planner.plan(input(upper(MovementEmphasis.BALANCED), adjustments = PlanAdjustments(focusOverride = Focus.FULL_BODY)))
        val more = planner.plan(input(upper(MovementEmphasis.MORE_ISOLATION), adjustments = PlanAdjustments(focusOverride = Focus.FULL_BODY)))
        fun isoCount(p: app.fluff.core.model.WorkoutPlan) = p.mainExercises.count { ExerciseLibrary.require(it.exerciseId).movementType == MovementType.ISOLATION }
        assertThat(isoCount(more)).isGreaterThan(isoCount(balanced))
        // Isolation never comes before the first compound lift.
        assertThat(ExerciseLibrary.require(more.mainExercises.first().exerciseId).movementType).isEqualTo(MovementType.COMPOUND)
    }

    private fun work(id: String, kg: Double, reps: Int, rpe: Double = 8.0) =
        PlannedExercise(id, List(3) { PlannedSet(SetKind.WORKING, reps, null, kg, rpe, 120) })

    @Test fun insightsExplainProgressionAgainstLastSession() {
        val sessions = listOf(session(1, today.minusDays(4), Focus.FULL_BODY, Triple("back_squat", 100.0, 5), Triple("back_squat", 100.0, 5)))
        val inp = input(sessions = sessions)
        val insights = PlanInsights.forExercise(work("back_squat", 102.5, 5), inp, max = 6)
        val progress = insights.first { it.kind == InsightKind.PROGRESS }
        assertThat(progress.text).isEqualTo("+2.5 kg on last time")
        assertThat(progress.tone).isEqualTo(InsightTone.POSITIVE)
        assertThat(insights.first { it.kind == InsightKind.LAST_TIME }.text).isEqualTo("Last time (4 days ago): 2×5 at 100 kg @ RPE 8")
        // 102.5×5 @8 beats the old 100×5 @8 estimate, so the plan itself is a record attempt.
        assertThat(insights.map { it.kind }).contains(InsightKind.RECORD)
        // The first insight is always the most useful one: what changed.
        assertThat(PlanInsights.forExercise(work("back_squat", 102.5, 5), inp).first().kind).isEqualTo(InsightKind.PROGRESS)
    }

    @Test fun insightsFlagLighterLoadsAndTiredMuscles() {
        val sessions = listOf(session(1, today.minusDays(2), Focus.FULL_BODY, Triple("bench_press", 80.0, 5)))
        val tired = Muscle.entries.associateWith { if (it == Muscle.CHEST) 0.4 else 1.0 }
        val inp = input(sessions = sessions, freshness = tired, readinessScore = 40)
        val insights = PlanInsights.forExercise(work("bench_press", 70.0, 5, rpe = 7.5), inp, max = 6)
        assertThat(insights.first { it.kind == InsightKind.PROGRESS }.text).startsWith("10 kg lighter")
        assertThat(insights.first { it.kind == InsightKind.RECOVERY }.text).contains("Chest still recovering (40%)")
        assertThat(insights.first { it.kind == InsightKind.EFFORT }.tone).isEqualTo(InsightTone.CAUTION)
    }

    @Test fun firstTimeAndJointFriendlyPicksAreExplained() {
        val profile = UserProfile(limitations = listOf(Limitation(BodyRegion.KNEE, Severity.MODERATE)), onboarded = true)
        val insights = PlanInsights.forExercise(work("goblet_squat", 20.0, 10), input(profile), max = 6)
        assertThat(insights.map { it.kind }).contains(InsightKind.FIRST_TIME)
        // A knee limitation and a knee-loading pattern, but this pick keeps knee stress low or the planner would have
        // excluded it; insights only claim joint-friendliness when the stress really is low.
        val goblet = ExerciseLibrary.require("goblet_squat")
        val claimsJoint = insights.any { it.kind == InsightKind.JOINT }
        assertThat(claimsJoint).isEqualTo(goblet.stress[BodyRegion.KNEE] == null || goblet.stress[BodyRegion.KNEE] == app.fluff.core.model.Stress.LOW)
    }

    @Test fun everyPlannedMainExerciseGetsAtLeastOneInsight() {
        val sessions = (1..6).map { i ->
            session(i.toLong(), today.minusDays(i * 2L), Focus.FULL_BODY, Triple("back_squat", 100.0, 5), Triple("bench_press", 70.0, 6), Triple("barbell_row", 60.0, 8))
        }
        val inp = input(UserProfile(goal = TrainingGoal.STRENGTH, experience = Experience.INTERMEDIATE, onboarded = true), sessions = sessions)
        val plan = planner.plan(inp)
        val explained = PlanInsights.explain(plan, inp)
        plan.mainExercises.filter { ExerciseLibrary.require(it.exerciseId).isLoaded }.forEach { pe ->
            assertWithMessage(pe.exerciseId).that(explained[pe.exerciseId]).isNotEmpty()
        }
    }
}
