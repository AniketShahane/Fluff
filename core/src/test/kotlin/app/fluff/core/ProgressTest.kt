package app.fluff.core

import app.fluff.core.Fixtures.session
import app.fluff.core.Fixtures.today
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.ActivityType
import app.fluff.core.model.Goal
import app.fluff.core.model.LocalDateSerial
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.SetKind
import app.fluff.core.model.Sex
import app.fluff.core.model.WeightUnit
import app.fluff.core.progress.E1rmPoint
import app.fluff.core.progress.GoalProjector
import app.fluff.core.progress.Intensity
import app.fluff.core.progress.Loads
import app.fluff.core.progress.PersonalRecord
import app.fluff.core.progress.StrengthLevel
import app.fluff.core.progress.StrengthScorer
import app.fluff.core.progress.StrengthStandards
import app.fluff.core.progress.TrainingHistory
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntensityTest {
    @Test fun singleAtRpe10IsOneRepMax() {
        assertThat(Intensity.percentOf1rm(1, 10.0)).isEqualTo(1.0)
    }

    @Test fun rpeAddsRepsInReserve() {
        // 5 reps @ RPE 8 == 7 reps to failure == 81.1%
        assertThat(Intensity.percentOf1rm(5, 8.0)).isWithin(1e-9).of(0.811)
        assertThat(Intensity.percentOf1rm(5, 8.5)).isWithin(1e-9).of((0.811 + 0.837) / 2)
    }

    @Test fun percentDecreasesMonotonically() {
        var last = 1.01
        for (reps in 1..20) {
            val p = Intensity.percentOf1rm(reps, 10.0)
            assertThat(p).isLessThan(last)
            last = p
        }
    }

    @Test fun e1rmFromSet() {
        assertThat(Intensity.e1rm(100.0, 5, 8.0)!!).isWithin(0.01).of(100 / 0.811)
        assertThat(Intensity.e1rm(100.0, 20, 8.0)).isNull()
        assertThat(Intensity.e1rm(0.0, 5, 8.0)).isNull()
    }
}

class LoadsTest {
    private val squat = ExerciseLibrary.require("back_squat")
    private val db = ExerciseLibrary.require("db_bench_press")
    private val pullUp = ExerciseLibrary.require("pull_up")

    @Test fun roundsDownToPlates() {
        assertThat(Loads.roundDown(squat, 83.7, WeightUnit.KG)).isEqualTo(82.5)
        assertThat(Loads.roundDown(db, 23.9, WeightUnit.KG)).isEqualTo(22.0)
    }

    @Test fun neverBelowEmptyBar() {
        assertThat(Loads.roundDown(squat, 5.0, WeightUnit.KG)).isEqualTo(20.0)
    }

    @Test fun poundsRoundInPounds() {
        val kg = Loads.roundDown(squat, 100.0, WeightUnit.LB) // 220.46 lb -> 220 lb
        assertThat(WeightUnit.LB.fromKg(kg)).isWithin(1e-6).of(220.0)
    }

    @Test fun bodyweightIncludesBodyweight() {
        assertThat(Loads.totalLoadKg(pullUp, 10.0, 80.0)).isEqualTo(90.0)
        assertThat(Loads.loggedKgFor(pullUp, 70.0, 80.0)).isEqualTo(0.0)
    }
}

class HistoryTest {
    @Test fun currentE1rmUsesRecentBest() {
        val h = TrainingHistory(
            listOf(
                session(1, today.minusDays(200), sets = arrayOf(Triple("back_squat", 150.0, 5))),
                session(2, today.minusDays(10), sets = arrayOf(Triple("back_squat", 100.0, 5))),
                session(3, today.minusDays(3), sets = arrayOf(Triple("back_squat", 105.0, 5))),
            ),
            80.0,
        )
        val e = h.currentE1rm("back_squat", today)!!
        assertThat(e).isWithin(0.01).of(105.0 / Intensity.percentOf1rm(5, 8.0))
        assertThat(h.allTimeBestE1rm("back_squat")!!).isGreaterThan(e)
    }

    @Test fun detectsPersonalRecords() {
        val h = TrainingHistory(listOf(session(1, today.minusDays(7), sets = arrayOf(Triple("bench_press", 80.0, 5)))), 80.0)
        val now = Fixtures.at(today)
        val new = listOf(LoggedSet("bench_press", SetKind.WORKING, 85.0, 5, 8.0, completedAt = now))
        val prs = h.detectRecords(new, now)
        assertThat(prs.map { it.kind }).containsExactly(PersonalRecord.Kind.E1RM, PersonalRecord.Kind.WEIGHT)
    }

    @Test fun firstEverSetIsNotARecord() {
        val h = TrainingHistory(emptyList(), 80.0)
        val now = Fixtures.at(today)
        assertThat(h.detectRecords(listOf(LoggedSet("bench_press", SetKind.WORKING, 85.0, 5, 8.0, completedAt = now)), now)).isEmpty()
    }

    @Test fun weekStreakCountsConsecutiveWeeks() {
        // today is Tuesday 2026-11-10
        val h = TrainingHistory(
            listOf(
                session(1, today.minusDays(1)), // this week (Mon)
                session(2, today.minusDays(8)), // last week
                session(3, today.minusDays(15)), // two weeks ago
                session(4, today.minusDays(29)), // gap before
            ),
            80.0,
        )
        assertThat(h.weekStreak(today)).isEqualTo(3)
    }

    @Test fun weeklyVolumeCountsMuscles() {
        val h = TrainingHistory(listOf(session(1, today, sets = arrayOf(Triple("bench_press", 80.0, 5), Triple("bench_press", 80.0, 5)))), 80.0)
        val week = h.weeklyMuscleSets(today, 1).single()
        assertThat(week.hardSets).isEqualTo(2)
        assertThat(week.setsByMuscle[app.fluff.core.model.Muscle.CHEST]).isEqualTo(2.0)
        assertThat(week.setsByMuscle[app.fluff.core.model.Muscle.FRONT_DELTS]).isEqualTo(1.0)
    }
}

class StrengthScoreTest {
    @Test fun scoreAnchorsMatchThresholds() {
        val t = StrengthStandards.thresholds(MovementPattern.HORIZONTAL_PUSH, Sex.MALE)!!
        assertThat(StrengthStandards.score(t[0], t)).isEqualTo(25)
        assertThat(StrengthStandards.score(t[1], t)).isEqualTo(50)
        assertThat(StrengthStandards.score(t[3], t)).isEqualTo(100)
        assertThat(StrengthStandards.score(10.0, t)).isEqualTo(120)
        assertThat(StrengthStandards.score(0.0, t)).isEqualTo(0)
    }

    @Test fun benchProfile() {
        // 100 kg x1 @10 at 80 kg bodyweight = 1.25x -> between intermediate (1.0) and advanced (1.5)
        val h = TrainingHistory(listOf(session(1, today, sets = arrayOf(Triple("bench_press", 100.0, 1)), rpe = 10.0)), 80.0)
        val profile = StrengthScorer.profile(h, 80.0, Sex.MALE, today)
        val push = profile.patterns.first { it.pattern == MovementPattern.HORIZONTAL_PUSH }
        assertThat(push.score).isEqualTo(63)
        assertThat(push.level).isEqualTo(StrengthLevel.INTERMEDIATE)
        assertThat(profile.overall).isEqualTo(63) // only pattern with data
        assertThat(profile.coverage).isWithin(1e-9).of(1.0 / 8)
    }

    @Test fun variationsMapToReferenceLift() {
        val h = TrainingHistory(listOf(session(1, today, sets = arrayOf(Triple("db_bench_press", 38.0, 1)), rpe = 10.0)), 80.0)
        val push = StrengthScorer.profile(h, 80.0, Sex.MALE, today).patterns.first { it.pattern == MovementPattern.HORIZONTAL_PUSH }
        assertThat(push.referenceE1rmKg!!).isWithin(0.01).of(100.0)
        assertThat(push.basedOnExerciseId).isEqualTo("db_bench_press")
    }

    @Test fun activityLimiterIsWeakestImportantPattern() {
        val h = TrainingHistory(
            listOf(session(1, today, sets = arrayOf(Triple("back_squat", 160.0, 1), Triple("deadlift", 200.0, 1), Triple("pull_up", 0.0, 1)), rpe = 10.0)),
            80.0,
        )
        val p = StrengthScorer.profile(h, 80.0, Sex.MALE, today)
        val climbing = StrengthScorer.activityStrength(p, ActivityType.CLIMBING)
        assertThat(climbing.limiter).isAnyOf(MovementPattern.VERTICAL_PULL, MovementPattern.CARRY)
        val cycling = StrengthScorer.activityStrength(p, ActivityType.CYCLING)
        assertThat(cycling.score).isGreaterThan(climbing.score)
        assertThat(climbing.weights.values.sum()).isWithin(1e-9).of(1.0)
    }
}

class GoalProjectorTest {
    @Test fun projectsLinearTrend() {
        val series = (0..6).map { E1rmPoint(today.minusWeeks((6 - it).toLong()), 100.0 + it * 2.0) } // +2 kg/week, now 112
        val goal = Goal(1, "back_squat", targetKg = 120.0, targetDate = LocalDateSerial.of(today.plusWeeks(8)), startE1rmKg = 100.0, createdEpochDay = today.minusWeeks(6).toEpochDay())
        val p = GoalProjector.project(goal, series, today)
        assertThat(p.weeklyGainKg!!).isWithin(0.01).of(2.0)
        assertThat(p.fraction).isWithin(1e-9).of(0.6)
        assertThat(p.projectedDate).isEqualTo(today.plusDays(28))
        assertThat(p.onTrack).isTrue()
    }

    @Test fun stallMeansNoProjection() {
        val series = (0..5).map { E1rmPoint(today.minusWeeks((5 - it).toLong()), 100.0) }
        val goal = Goal(1, "back_squat", 120.0, createdEpochDay = 0)
        val p = GoalProjector.project(goal, series, today)
        assertThat(p.projectedDate).isNull()
        assertThat(p.achieved).isFalse()
    }

    @Test fun achievedWhenCurrentBeatsTarget() {
        val goal = Goal(1, "back_squat", 100.0, createdEpochDay = 0)
        val p = GoalProjector.project(goal, listOf(E1rmPoint(today, 101.0)), today)
        assertThat(p.achieved).isTrue()
        assertThat(p.fraction).isEqualTo(1.0)
    }
}
