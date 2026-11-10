package app.fluff.core

import app.fluff.core.Fixtures.input
import app.fluff.core.Fixtures.session
import app.fluff.core.Fixtures.today
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.BlockType
import app.fluff.core.model.BodyRegion
import app.fluff.core.model.Equipment
import app.fluff.core.model.Experience
import app.fluff.core.model.Focus
import app.fluff.core.model.Limitation
import app.fluff.core.model.LoadType
import app.fluff.core.model.Muscle
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.SetKind
import app.fluff.core.model.Severity
import app.fluff.core.model.TrainingGoal
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.planner.WorkoutPlanner
import app.fluff.core.progress.Intensity
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlannerTest {
    private val planner = WorkoutPlanner()

    private fun WorkoutPlan.allIds() = blocks.flatMap { b -> b.exercises.map { it.exerciseId } }

    private fun assertWellFormed(plan: WorkoutPlan, profile: UserProfile) {
        assertThat(plan.block(BlockType.WARMUP)!!.exercises).isNotEmpty()
        assertThat(plan.block(BlockType.MAIN)!!.exercises).isNotEmpty()
        assertThat(plan.block(BlockType.COOLDOWN)!!.exercises).isNotEmpty()
        plan.allIds().forEach { id ->
            val ex = ExerciseLibrary.require(id)
            assertThat(ex.isAvailableWith(profile.equipment)).isTrue()
            assertThat(ex.isContraindicatedFor(profile.limitations)).isFalse()
        }
        assertThat(plan.rationale).isNotEmpty()
    }

    @Test fun strengthFullBodyHasRealStructure() {
        val profile = UserProfile(goal = TrainingGoal.STRENGTH, experience = Experience.INTERMEDIATE, daysPerWeek = 3, onboarded = true)
        val plan = planner.plan(input(profile, readinessScore = 85))
        assertWellFormed(plan, profile)
        assertThat(plan.focus).isEqualTo(Focus.FULL_BODY)
        val main = plan.mainExercises.first()
        assertThat(main.exerciseId).isAnyOf("back_squat", "deadlift", "trap_bar_deadlift", "front_squat")
        // Prime day + strength goal => top set followed by back-offs, with ramp sets first.
        assertThat(main.sets.first().kind).isEqualTo(SetKind.WARMUP)
        assertThat(main.sets.count { it.kind == SetKind.TOP }).isEqualTo(1)
        assertThat(main.sets.filter { it.kind == SetKind.WARMUP }.map { it.weightKg!! }).isInOrder()
        val top = main.sets.first { it.kind == SetKind.TOP }
        main.sets.filter { it.kind == SetKind.WARMUP }.forEach { assertThat(it.weightKg!!).isLessThan(top.weightKg!!) }
    }

    @Test fun effortNeverExceedsCap() {
        listOf(90, 70, 55, 40).forEach { score ->
            val inp = input(readinessScore = score)
            val plan = planner.plan(inp)
            val cap = inp.readiness.prescription.rpeCap
            plan.blocks.flatMap { it.exercises }.flatMap { it.sets }.mapNotNull { it.rpe }.forEach { assertThat(it).isAtMost(cap) }
        }
    }

    @Test fun lowReadinessMeansLessVolume() {
        val profile = UserProfile(goal = TrainingGoal.HYPERTROPHY, experience = Experience.INTERMEDIATE, sessionMinutes = 120, onboarded = true)
        fun workSets(score: Int) = planner.plan(input(profile, score)).mainExercises.sumOf { pe -> pe.sets.count { it.kind != SetKind.WARMUP } }
        assertThat(workSets(40)).isLessThan(workSets(70))
        assertThat(workSets(70)).isAtMost(workSets(90))
    }

    @Test fun recoverBandGivesRecoverySession() {
        val plan = planner.plan(input(readinessScore = 20))
        assertThat(plan.focus).isEqualTo(Focus.RECOVERY)
        assertThat(plan.mainExercises.flatMap { it.sets }.mapNotNull { it.weightKg }.filter { it > 0 }).isEmpty()
    }

    @Test fun severeKneeExcludesKneeStress() {
        val profile = UserProfile(limitations = listOf(Limitation(BodyRegion.KNEE, Severity.SEVERE)), onboarded = true)
        repeat(5) { seed ->
            val plan = planner.plan(input(profile, adjustments = PlanAdjustments(variationSeed = seed)))
            assertWellFormed(plan, profile)
            plan.allIds().forEach { assertThat(ExerciseLibrary.require(it).stress[BodyRegion.KNEE]).isNull() }
        }
    }

    @Test fun moderateShoulderAllowsOnlyLowShoulderStress() {
        val profile = UserProfile(daysPerWeek = 4, limitations = listOf(Limitation(BodyRegion.SHOULDER, Severity.MODERATE)), onboarded = true)
        val plan = planner.plan(input(profile, adjustments = PlanAdjustments(focusOverride = Focus.UPPER)))
        assertWellFormed(plan, profile)
        assertThat(plan.allIds()).doesNotContain("bench_press")
        assertThat(plan.allIds()).doesNotContain("overhead_press")
        assertThat(plan.rationale.joinToString()).contains("shoulder")
    }

    @Test fun homeDumbbellUserGetsNoBarbell() {
        val profile = UserProfile(equipment = Equipment.HOME_DUMBBELLS, onboarded = true)
        val plan = planner.plan(input(profile))
        assertWellFormed(plan, profile)
        plan.allIds().forEach { assertThat(ExerciseLibrary.require(it).equipment).doesNotContain(Equipment.BARBELL) }
        assertThat(plan.mainExercises.size).isAtLeast(3)
    }

    @Test fun bodyweightOnlyStillPlans() {
        val profile = UserProfile(equipment = emptySet(), onboarded = true)
        val plan = planner.plan(input(profile))
        assertWellFormed(plan, profile)
    }

    @Test fun respectsTimeBudget() {
        val profile = UserProfile(goal = TrainingGoal.HYPERTROPHY, experience = Experience.ADVANCED, sessionMinutes = 30, onboarded = true)
        val plan = planner.plan(input(profile, 90))
        assertThat(plan.estimatedMinutes).isAtMost(38)
        val long = planner.plan(input(profile.copy(sessionMinutes = 90), 90))
        assertThat(long.mainExercises.size).isAtLeast(plan.mainExercises.size)
    }

    @Test fun deterministicAndRegeneratable() {
        val a = planner.plan(input())
        val b = planner.plan(input())
        assertThat(a).isEqualTo(b)
        val variants = (1..8).map { planner.plan(input(adjustments = PlanAdjustments(variationSeed = it))).allIds() }.toSet()
        assertThat(variants.size).isGreaterThan(1)
    }

    @Test fun avoidsRequestedExercises() {
        val first = planner.plan(input()).mainExercises.first().exerciseId
        val plan = planner.plan(input(adjustments = PlanAdjustments(avoidExerciseIds = setOf(first))))
        assertThat(plan.allIds()).doesNotContain(first)
    }

    @Test fun upperLowerPicksFresherHalf() {
        val profile = UserProfile(daysPerWeek = 4, onboarded = true)
        val tiredLegs = Fixtures.fresh() + mapOf(Muscle.QUADS to 0.2, Muscle.GLUTES to 0.3, Muscle.HAMSTRINGS to 0.3)
        assertThat(planner.plan(input(profile, freshness = tiredLegs)).focus).isEqualTo(Focus.UPPER)
        val tiredUpper = Fixtures.fresh() + mapOf(Muscle.CHEST to 0.2, Muscle.LATS to 0.2, Muscle.UPPER_BACK to 0.3, Muscle.FRONT_DELTS to 0.3)
        assertThat(planner.plan(input(profile, freshness = tiredUpper)).focus).isEqualTo(Focus.LOWER)
    }

    @Test fun loadsComeFromHistory() {
        val profile = UserProfile(goal = TrainingGoal.GENERAL, experience = Experience.INTERMEDIATE, onboarded = true)
        val sessions = listOf(session(1, today.minusDays(4), sets = arrayOf(Triple("back_squat", 100.0, 5), Triple("deadlift", 140.0, 5))))
        val plan = planner.plan(input(profile, 75, sessions, adjustments = PlanAdjustments(avoidExerciseIds = setOf("deadlift", "trap_bar_deadlift", "front_squat"))))
        val squat = plan.mainExercises.first { it.exerciseId == "back_squat" }
        val work = squat.sets.first { it.kind != SetKind.WARMUP }
        val e1rm = 100.0 / Intensity.percentOf1rm(5, 8.0)
        val expected = e1rm * Intensity.percentOf1rm(work.reps!!, work.rpe!!)
        assertThat(work.weightKg!!).isAtMost(expected)
        assertThat(work.weightKg!!).isAtLeast(expected - 2.5)
    }

    @Test fun beginnersGetStartingEstimates() {
        val profile = UserProfile(experience = Experience.BEGINNER, bodyWeightKg = 70.0, onboarded = true)
        val plan = planner.plan(input(profile))
        val main = plan.mainExercises.first()
        val ex = ExerciseLibrary.require(main.exerciseId)
        if (ex.loadType != LoadType.BODYWEIGHT) {
            assertThat(main.sets.last().weightKg).isNotNull()
            assertThat(main.note).contains("Starting estimate")
        }
        plan.blocks.flatMap { it.exercises }.flatMap { it.sets }.mapNotNull { it.rpe }.forEach { assertThat(it).isAtMost(8.5) }
    }

    @Test fun cooldownStretchesTrainedMuscles() {
        val profile = UserProfile(daysPerWeek = 4, onboarded = true)
        val plan = planner.plan(input(profile, adjustments = PlanAdjustments(focusOverride = Focus.LOWER)))
        val stretches = plan.block(BlockType.COOLDOWN)!!.exercises.map { it.exerciseId }
        assertThat(stretches).containsAnyOf("couch_stretch", "figure_four", "hamstring_stretch")
    }
}
