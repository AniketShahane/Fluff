package app.fluff.data.repo

import app.fluff.core.model.ActivityRecord
import app.fluff.core.model.ActivityType
import app.fluff.core.model.DailySignals
import app.fluff.core.model.DataSource
import app.fluff.core.model.Focus
import app.fluff.core.model.Goal
import app.fluff.core.model.LocalDateSerial
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.SessionRecord
import app.fluff.core.model.SetKind
import app.fluff.data.db.ActivityEntity
import app.fluff.data.db.GoalEntity
import app.fluff.data.db.SessionWithSets
import app.fluff.data.db.SignalsEntity
import java.time.Instant
import java.time.LocalDate

fun SessionWithSets.toRecord(): SessionRecord = SessionRecord(
    id = session.id,
    date = LocalDate.ofEpochDay(session.epochDay),
    startedAt = Instant.ofEpochMilli(session.startedAt),
    endedAt = Instant.ofEpochMilli(session.endedAt ?: session.startedAt),
    focus = session.focus?.let { f -> Focus.entries.firstOrNull { it.name == f } },
    sessionRpe = session.sessionRpe,
    sets = sets.filter { it.completedAt != null && !it.skipped }
        .sortedWith(compareBy({ it.exerciseOrder }, { it.setIndex }))
        .map {
            LoggedSet(
                exerciseId = it.exerciseId,
                kind = runCatching { SetKind.valueOf(it.kind) }.getOrDefault(SetKind.WORKING),
                weightKg = it.weightKg ?: 0.0,
                reps = it.reps ?: 0,
                rpe = it.rpe,
                durationSec = it.durationSec,
                completedAt = Instant.ofEpochMilli(it.completedAt!!),
            )
        },
)

fun ActivityEntity.toRecord(): ActivityRecord = ActivityRecord(
    id = id,
    type = runCatching { ActivityType.valueOf(type) }.getOrDefault(ActivityType.OTHER),
    source = runCatching { DataSource.valueOf(source) }.getOrDefault(DataSource.MANUAL),
    start = Instant.ofEpochMilli(startEpochMs),
    durationMinutes = durationMin,
    intensity = intensity,
    name = name,
    energyKcal = energyKcal,
    distanceMeters = distanceMeters,
)

fun ActivityRecord.toEntity(): ActivityEntity = ActivityEntity(
    id = id, type = type.name, source = source.name, startEpochMs = start.toEpochMilli(),
    durationMin = durationMinutes, intensity = intensity, name = name, energyKcal = energyKcal, distanceMeters = distanceMeters,
)

fun SignalsEntity.toSignals(): DailySignals = DailySignals(
    date = LocalDate.ofEpochDay(epochDay),
    whoopRecovery = whoopRecovery,
    hrvRmssdMs = hrvRmssdMs,
    restingHeartRate = restingHeartRate,
    sleepHours = sleepHours,
    sleepPerformance = sleepPerformance,
)

fun GoalEntity.toGoal(): Goal = Goal(
    id = id, exerciseId = exerciseId, targetKg = targetKg,
    targetDate = targetEpochDay?.let { LocalDateSerial(it) },
    startE1rmKg = startE1rmKg, createdEpochDay = createdEpochDay, achievedEpochDay = achievedEpochDay,
)
