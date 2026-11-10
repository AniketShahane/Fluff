package app.fluff.core

import app.fluff.core.model.DailySignals
import app.fluff.core.model.Focus
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.SessionRecord
import app.fluff.core.model.SetKind
import app.fluff.core.model.UserProfile
import app.fluff.core.planner.PlannerInput
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.readiness.LoadSnapshot
import app.fluff.core.readiness.Readiness
import app.fluff.core.readiness.ReadinessEngine
import app.fluff.core.model.Muscle
import java.time.LocalDate
import java.time.ZoneOffset

object Fixtures {
    val today: LocalDate = LocalDate.of(2026, 11, 10)
    val zone: ZoneOffset = ZoneOffset.UTC

    fun at(date: LocalDate, hour: Int = 18) = date.atTime(hour, 0).toInstant(zone)

    fun session(
        id: Long,
        date: LocalDate,
        focus: Focus? = Focus.FULL_BODY,
        vararg sets: Triple<String, Double, Int>,
        rpe: Double? = 8.0,
    ): SessionRecord {
        val start = at(date)
        return SessionRecord(
            id = id,
            date = date,
            startedAt = start,
            endedAt = start.plusSeconds(3600),
            focus = focus,
            sets = sets.mapIndexed { i, (ex, kg, reps) ->
                LoggedSet(ex, SetKind.WORKING, kg, reps, rpe, completedAt = start.plusSeconds(120L * i))
            },
        )
    }

    fun readiness(score: Int): Readiness {
        // Drive the engine with a WHOOP score so the band/prescription match production behaviour.
        return ReadinessEngine.compute(
            today,
            DailySignals(today, whoopRecovery = score.toDouble()),
            emptyList(),
            null,
            LoadSnapshot(0.0, 0.0, 0.0),
        )
    }

    fun fresh(): Map<Muscle, Double> = Muscle.entries.associateWith { 1.0 }

    fun input(
        profile: UserProfile = UserProfile(onboarded = true),
        readinessScore: Int = 75,
        sessions: List<SessionRecord> = emptyList(),
        freshness: Map<Muscle, Double> = fresh(),
        adjustments: app.fluff.core.model.PlanAdjustments = app.fluff.core.model.PlanAdjustments(),
    ) = PlannerInput(today, profile, readiness(readinessScore), TrainingHistory(sessions, profile.bodyWeightKg), freshness, adjustments)
}
