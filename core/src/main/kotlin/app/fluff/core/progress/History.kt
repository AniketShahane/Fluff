package app.fluff.core.progress

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.LoggedSet
import app.fluff.core.model.Muscle
import app.fluff.core.model.SessionRecord
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

data class E1rmPoint(val date: LocalDate, val e1rmKg: Double)

data class PersonalRecord(
    val exerciseId: String,
    val kind: Kind,
    val newValue: Double,
    val previousValue: Double?,
    val reps: Int,
) {
    enum class Kind(val label: String) { E1RM("Estimated max"), WEIGHT("Heaviest weight"), REPS("Most reps") }
}

/** Read-only queries over the user's logged sessions. */
class TrainingHistory(sessions: List<SessionRecord>, private val bodyWeightKg: Double) {

    val sessions: List<SessionRecord> = sessions.sortedBy { it.startedAt }

    /** Best e1RM per session for one exercise, oldest first. */
    fun e1rmSeries(exerciseId: String): List<E1rmPoint> = sessions.mapNotNull { s ->
        s.sets.filter { it.exerciseId == exerciseId }
            .mapNotNull { Loads.e1rmOf(it, bodyWeightKg) }
            .maxOrNull()
            ?.let { E1rmPoint(s.date, it) }
    }

    /**
     * Current working estimate of e1RM: the best of the last three sessions that included the
     * lift, within [withinDays]. Using a short window means detraining is reflected.
     */
    fun currentE1rm(exerciseId: String, today: LocalDate, withinDays: Long = 90): Double? =
        e1rmSeries(exerciseId)
            .filter { !it.date.isBefore(today.minusDays(withinDays)) && !it.date.isAfter(today) }
            .takeLast(3)
            .maxOfOrNull { it.e1rmKg }

    fun allTimeBestE1rm(exerciseId: String): Double? = e1rmSeries(exerciseId).maxOfOrNull { it.e1rmKg }

    fun lastPerformed(exerciseId: String): LocalDate? =
        sessions.lastOrNull { s -> s.sets.any { it.exerciseId == exerciseId } }?.date

    fun exercisesInLastSession(): Set<String> = sessions.lastOrNull()?.sets?.map { it.exerciseId }?.toSet().orEmpty()

    fun familiarity(exerciseId: String): Int = sessions.count { s -> s.sets.any { it.exerciseId == exerciseId && it.isWork } }

    /** Detects records set by [newSets] compared with everything logged before [before]. */
    fun detectRecords(newSets: List<LoggedSet>, before: java.time.Instant): List<PersonalRecord> {
        val prior = sessions.filter { it.startedAt < before }.flatMap { it.sets }.filter { it.isWork }
        val out = mutableListOf<PersonalRecord>()
        newSets.filter { it.isWork }.groupBy { it.exerciseId }.forEach { (id, sets) ->
            val ex = ExerciseLibrary[id] ?: return@forEach
            if (!ex.isLoaded) return@forEach
            val priorForEx = prior.filter { it.exerciseId == id }
            val priorBestE1rm = priorForEx.mapNotNull { Loads.e1rmOf(it, bodyWeightKg) }.maxOrNull()
            val bestNew = sets.mapNotNull { s -> Loads.e1rmOf(s, bodyWeightKg)?.let { s to it } }.maxByOrNull { it.second }
            if (bestNew != null && priorForEx.isNotEmpty() && (priorBestE1rm == null || bestNew.second > priorBestE1rm * 1.005)) {
                out += PersonalRecord(id, PersonalRecord.Kind.E1RM, bestNew.second, priorBestE1rm, bestNew.first.reps)
            }
            val priorHeaviest = priorForEx.maxOfOrNull { it.weightKg }
            val heaviest = sets.maxByOrNull { it.weightKg }
            if (heaviest != null && priorHeaviest != null && heaviest.weightKg > priorHeaviest && heaviest.weightKg > 0) {
                out += PersonalRecord(id, PersonalRecord.Kind.WEIGHT, heaviest.weightKg, priorHeaviest, heaviest.reps)
            }
        }
        return out
    }

    /** Hard sets per muscle per ISO week (primary = 1, secondary = 0.5). Most recent [weeks]. */
    fun weeklyMuscleSets(today: LocalDate, weeks: Int = 8): List<WeeklyVolume> {
        val thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (weeks - 1 downTo 0).map { back ->
            val start = thisMonday.minusWeeks(back.toLong())
            val end = start.plusDays(6)
            val perMuscle = mutableMapOf<Muscle, Double>()
            var tonnage = 0.0
            var hardSets = 0
            sessions.filter { !it.date.isBefore(start) && !it.date.isAfter(end) }.forEach { s ->
                s.sets.filter { it.isWork }.forEach { set ->
                    val ex = ExerciseLibrary[set.exerciseId] ?: return@forEach
                    if (!ex.isLoaded) return@forEach
                    ex.primary.forEach { perMuscle.merge(it, 1.0, Double::plus) }
                    ex.secondary.forEach { perMuscle.merge(it, 0.5, Double::plus) }
                    tonnage += Loads.totalLoadKg(ex, set.weightKg, bodyWeightKg) * set.reps
                    hardSets++
                }
            }
            WeeklyVolume(start, perMuscle, tonnage, hardSets, sessions.count { !it.date.isBefore(start) && !it.date.isAfter(end) })
        }
    }

    /** Days with a completed session in the last [days] days (for the consistency heatmap). */
    fun trainingDays(today: LocalDate, days: Int = 182): Set<LocalDate> =
        sessions.map { it.date }.filter { !it.isBefore(today.minusDays(days.toLong() - 1)) && !it.isAfter(today) }.toSet()

    /** Consecutive weeks (ending this week) with at least [minSessions] sessions. */
    fun weekStreak(today: LocalDate, minSessions: Int = 1): Int {
        var streak = 0
        var monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val dates = sessions.map { it.date }
        // The current week counts if it already meets the bar; otherwise start from last week.
        fun count(m: LocalDate) = dates.count { !it.isBefore(m) && !it.isAfter(m.plusDays(6)) }
        if (count(monday) < minSessions) monday = monday.minusWeeks(1)
        while (count(monday) >= minSessions) {
            streak++
            monday = monday.minusWeeks(1)
        }
        return streak
    }
}

data class WeeklyVolume(
    val weekStart: LocalDate,
    val setsByMuscle: Map<Muscle, Double>,
    val tonnageKg: Double,
    val hardSets: Int,
    val sessions: Int,
)
