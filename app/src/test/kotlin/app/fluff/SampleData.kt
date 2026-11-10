package app.fluff

import app.fluff.core.model.ActivityHabit
import app.fluff.core.model.ActivityType
import app.fluff.core.model.DailySignals
import app.fluff.core.model.Focus
import app.fluff.core.model.Goal
import app.fluff.core.model.LocalDateSerial
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.Muscle
import app.fluff.core.model.SessionRecord
import app.fluff.core.model.SetKind
import app.fluff.core.model.Sex
import app.fluff.core.model.TrainingGoal
import app.fluff.core.model.Experience
import app.fluff.core.model.UserProfile
import app.fluff.core.planner.PlannerInput
import app.fluff.core.planner.WorkoutPlanner
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.readiness.LoadSnapshot
import app.fluff.core.readiness.Readiness
import app.fluff.core.readiness.ReadinessEngine
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin

/** Realistic data for screenshot and repository tests: ten weeks of steady progress. */
object SampleData {
    val today: LocalDate = LocalDate.of(2026, 11, 10)

    val profile = UserProfile(
        name = "Aniket", sex = Sex.MALE, bodyWeightKg = 78.0, goal = TrainingGoal.STRENGTH,
        experience = Experience.INTERMEDIATE, daysPerWeek = 4, sessionMinutes = 60,
        activities = listOf(ActivityHabit(ActivityType.RUNNING, 2), ActivityHabit(ActivityType.CLIMBING, 1)),
        onboarded = true,
    )

    val sessions: List<SessionRecord> = buildList {
        var id = 1L
        for (week in 0 until 10) {
            val monday = today.minusWeeks((10 - week).toLong()).with(java.time.DayOfWeek.MONDAY)
            fun s(day: Long, focus: Focus, vararg lifts: Triple<String, Double, Int>) {
                val date = monday.plusDays(day)
                if (date >= today) return
                val start = date.atTime(18, 0).toInstant(ZoneOffset.UTC)
                add(
                    SessionRecord(
                        id++, date, start, start.plusSeconds(3300), focus,
                        lifts.flatMapIndexed { i, (ex, kg, reps) ->
                            (0 until 3).map { k -> LoggedSet(ex, SetKind.WORKING, kg, reps, 8.0, completedAt = start.plusSeconds(600L * i + 150L * k)) }
                        },
                        sessionRpe = 7.0,
                    ),
                )
            }
            val w = week.toDouble()
            s(0, Focus.LOWER, Triple("back_squat", 100.0 + 2.5 * w, 5), Triple("romanian_deadlift", 90.0 + 2.0 * w, 8))
            s(1, Focus.UPPER, Triple("bench_press", 75.0 + 1.25 * w, 5), Triple("barbell_row", 70.0 + 1.5 * w, 8))
            s(3, Focus.LOWER, Triple("deadlift", 130.0 + 3.0 * w, 5), Triple("bulgarian_split_squat", 20.0 + w, 8))
            if (week % 3 != 1) s(4, Focus.UPPER, Triple("overhead_press", 47.5 + 0.75 * w, 6), Triple("pull_up", 2.5 * (w / 3).toInt(), 6))
        }
    }

    val history = TrainingHistory(sessions, profile.bodyWeightKg)

    val signals: List<DailySignals> = (0..30).map { back ->
        val d = today.minusDays(back.toLong())
        DailySignals(d, hrvRmssdMs = 62.0 + 6 * sin(back / 2.0), restingHeartRate = 54.0 + 2 * sin(back / 3.0), sleepHours = 7.2 + 0.6 * sin(back / 1.7))
    }

    fun readiness(score: Int = 78): Readiness = ReadinessEngine.compute(
        today,
        DailySignals(today, whoopRecovery = score.toDouble(), hrvRmssdMs = 70.0, restingHeartRate = 52.0, sleepHours = 7.8, sleepPerformance = 88.0),
        signals,
        null,
        LoadSnapshot(acuteDaily = 50.0, chronicDaily = 45.0, yesterday = 30.0),
    )

    fun readinessHistory(): List<Readiness> = (0..29).map { back ->
        val score = (66 + 18 * sin(back / 2.3)).toInt()
        readiness(score).copy(epochDay = today.minusDays(back.toLong()).toEpochDay())
    }.reversed()

    fun input(score: Int = 78) = PlannerInput(today, profile, readiness(score), history, Muscle.entries.associateWith { 1.0 })
    fun insights(plan: app.fluff.core.model.WorkoutPlan, score: Int = 78) = app.fluff.core.planner.PlanInsights.explain(plan, input(score))
    fun plan(score: Int = 78) = WorkoutPlanner().plan(
        input(score),
    )

    val goals = listOf(Goal(1, "back_squat", 150.0, LocalDateSerial.of(today.plusWeeks(16)), startE1rmKg = 116.0, createdEpochDay = today.minusWeeks(6).toEpochDay()))
}
