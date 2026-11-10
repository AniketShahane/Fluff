package app.fluff.core

import app.fluff.core.Fixtures.at
import app.fluff.core.Fixtures.session
import app.fluff.core.Fixtures.today
import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.CheckIn
import app.fluff.core.model.DailySignals
import app.fluff.core.model.DataSource
import app.fluff.core.model.Muscle
import app.fluff.core.readiness.Confidence
import app.fluff.core.readiness.FactorKind
import app.fluff.core.readiness.LoadSnapshot
import app.fluff.core.readiness.MuscleFatigue
import app.fluff.core.readiness.ReadinessBand
import app.fluff.core.readiness.ReadinessEngine
import app.fluff.core.readiness.TrainingLoad
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ReadinessTest {
    private val neutral = LoadSnapshot(0.0, 0.0, 0.0)
    private val baseline = (1..20).map { DailySignals(today.minusDays(it.toLong()), hrvRmssdMs = 60.0 + (it % 5) - 2, restingHeartRate = 52.0 + (it % 3) - 1, sleepHours = 7.8) }

    @Test fun whoopRecoveryDrivesScore() {
        val r = ReadinessEngine.compute(today, DailySignals(today, whoopRecovery = 88.0), emptyList(), null, neutral)
        assertThat(r.score).isEqualTo(88)
        assertThat(r.band).isEqualTo(ReadinessBand.PRIME)
        assertThat(r.factors.first().kind).isEqualTo(FactorKind.RECOVERY)
    }

    @Test fun noDataIsLowConfidenceSteady() {
        val r = ReadinessEngine.compute(today, null, emptyList(), null, neutral)
        assertThat(r.confidence).isEqualTo(Confidence.LOW)
        assertThat(r.band).isEqualTo(ReadinessBand.STEADY)
    }

    @Test fun suppressedHrvAndHighRhrLowerReadiness() {
        val good = ReadinessEngine.compute(today, DailySignals(today, hrvRmssdMs = 62.0, restingHeartRate = 51.0, sleepHours = 8.0), baseline, null, neutral)
        val bad = ReadinessEngine.compute(today, DailySignals(today, hrvRmssdMs = 42.0, restingHeartRate = 60.0, sleepHours = 5.5), baseline, null, neutral)
        assertThat(good.score).isGreaterThan(bad.score + 25)
        assertThat(bad.band.ordinal).isAtLeast(ReadinessBand.LOW.ordinal)
        assertThat(bad.factors.first { it.kind == FactorKind.HRV }.impact).isLessThan(0)
    }

    @Test fun hrvNeedsBaselineBeforeScoring() {
        val r = ReadinessEngine.compute(today, DailySignals(today, hrvRmssdMs = 30.0), baseline.take(2), null, neutral)
        assertThat(r.score).isEqualTo(50)
        assertThat(r.factors.single().detail).contains("Building your baseline")
    }

    @Test fun checkInBlendsWithPhysiology() {
        val physio = DailySignals(today, whoopRecovery = 70.0)
        val bad = CheckIn(sleepQuality = 1, energy = 1, soreness = 5, stress = 5)
        val r = ReadinessEngine.compute(today, physio, emptyList(), bad, neutral)
        assertThat(r.score).isEqualTo((0.7 * 70).toInt())
        assertThat(r.confidence).isEqualTo(Confidence.HIGH)
    }

    @Test fun loadSpikePenalises() {
        val spike = LoadSnapshot(acuteDaily = 400.0, chronicDaily = 200.0, yesterday = 300.0) // ACWR 2.0
        val r = ReadinessEngine.compute(today, DailySignals(today, whoopRecovery = 80.0), emptyList(), null, spike)
        assertThat(r.score).isLessThan(65)
        assertThat(r.factors.any { it.kind == FactorKind.TRAINING_LOAD }).isTrue()
    }

    @Test fun trainingLoadUsesSessionsAndActivities() {
        val sessions = listOf(session(1, today.minusDays(1)))
        val acts = listOf(ActivityRecord("r", ActivityType.RUNNING, DataSource.MANUAL, at(today.minusDays(2)), 60.0, intensity = 7.0))
        val snap = TrainingLoad.snapshot(today, sessions, acts, Fixtures.zone)
        assertThat(snap.yesterday).isEqualTo(60.0 * 7.0) // 60 min @ default lifting RPE 7 (no sets logged)
        assertThat(snap.acuteDaily).isWithin(1e-9).of((420.0 + 420.0) / 7)
    }

    @Test fun recoverBandIsRecoverySession() {
        assertThat(ReadinessEngine.prescriptionFor(ReadinessBand.RECOVER).recoverySession).isTrue()
        assertThat(ReadinessEngine.prescriptionFor(ReadinessBand.PRIME).allowTopSets).isTrue()
    }
}

class MuscleFatigueTest {
    @Test fun squatsFatigueLegsAndDecay() {
        val s = session(1, today.minusDays(1), sets = Array(5) { Triple("back_squat", 100.0, 5) })
        val tomorrow = MuscleFatigue.freshness(at(today), listOf(s), emptyList())
        assertThat(tomorrow.getValue(Muscle.QUADS)).isLessThan(0.75)
        assertThat(tomorrow.getValue(Muscle.CHEST)).isEqualTo(1.0)
        val later = MuscleFatigue.freshness(at(today.plusDays(3)), listOf(s), emptyList())
        assertThat(later.getValue(Muscle.QUADS)).isGreaterThan(tomorrow.getValue(Muscle.QUADS))
    }

    @Test fun runningLoadsCalvesAndQuads() {
        val run = ActivityRecord("r", ActivityType.RUNNING, DataSource.WHOOP, at(today.minusDays(1), 7), 90.0, intensity = 8.0)
        val f = MuscleFatigue.freshness(at(today, 7), emptyList(), listOf(run))
        assertThat(f.getValue(Muscle.CALVES)).isLessThan(0.6)
        assertThat(f.getValue(Muscle.LATS)).isEqualTo(1.0)
    }
}
