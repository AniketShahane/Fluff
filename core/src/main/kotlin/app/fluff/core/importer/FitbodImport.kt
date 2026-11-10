package app.fluff.core.importer

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.SetKind
import app.fluff.core.model.WeightUnit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.roundToInt

/** Thrown when a file can't be read as a Fitbod export. The message is safe to show the user. */
class FitbodFormatException(message: String) : Exception(message)

/** One row of a Fitbod export: one set, in the file's own weight unit. */
data class FitbodRow(
    val exercise: String,
    /** When the workout happened. Every set of one workout shares it. */
    val time: Instant,
    /** True for exports with a date but no time of day (Fitbod support's database extract). */
    val dateOnly: Boolean,
    val reps: Int,
    val weight: Double,
    val durationSec: Int,
    val distanceM: Double,
    val warmup: Boolean,
)

data class FitbodExport(val rows: List<FitbodRow>, val format: String, val unitFromHeader: WeightUnit)

/**
 * Reads the CSV Fitbod produces. Two formats exist (checked against published converters, Nov 2026):
 * - In-app export (Log → gear → Export Workout Data):
 *   `Date,Exercise,Reps,Weight(kg),Duration(s),Distance(m),Incline,Resistance,isWarmup,Note,multiplier`
 *   with dates like `2023-10-24 18:24:59 +0000`, one timestamp per workout.
 * - Fitbod support's extract (mailed on request):
 *   `date,exercise_name,Reps,weight_kg,duration_seconds,distance_meters,Incline,Resistance,isWarmup`
 *   with date-only values.
 * Columns are found by name, values are trimmed (Fitbod pads numbers with a space) and blank cells are 0.
 */
object FitbodCsv {
    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[ T](\d{2}):(\d{2})(?::(\d{2}))?(?:\s*(Z|[+-]\d{2}:?\d{2}))?)?$""")

    private val dateCols = setOf("date")
    private val exerciseCols = setOf("exercise", "exercise_name", "exercisename")
    private val repsCols = setOf("reps")
    private val durationCols = setOf("duration(s)", "duration_seconds", "duration")
    private val distanceCols = setOf("distance(m)", "distance_meters", "distance")
    private val warmupCols = setOf("iswarmup", "warmup")

    fun parse(text: String): FitbodExport {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() } ?: throw FitbodFormatException("The file is empty.")
        val sep = if (firstLine.count { it == ';' } > firstLine.count { it == ',' }) ';' else ','
        val rows = splitCsv(clean, sep)
        val header = rows.first().map { it.trim().lowercase() }
        fun col(names: Set<String>) = header.indexOfFirst { it in names }

        val date = col(dateCols)
        val exercise = col(exerciseCols)
        val reps = col(repsCols)
        val weight = header.indexOfFirst { it.startsWith("weight") }
        val missing = listOfNotNull("Date".takeIf { date < 0 }, "Exercise".takeIf { exercise < 0 }, "Reps".takeIf { reps < 0 }, "Weight".takeIf { weight < 0 })
        if (missing.isNotEmpty()) {
            throw FitbodFormatException("This doesn't look like a Fitbod export (missing ${missing.joinToString()}). Export it from Fitbod: Log → gear icon → Export Workout Data.")
        }
        val weightHeader = header[weight]
        val unit = if (weightHeader.contains("lb")) WeightUnit.LB else WeightUnit.KG
        val duration = col(durationCols)
        val distance = col(distanceCols)
        val warmup = col(warmupCols)

        val out = rows.drop(1).mapIndexedNotNull { i, row ->
            val line = i + 2
            fun cell(idx: Int) = if (idx in row.indices) row[idx].trim() else ""
            fun num(idx: Int): Double {
                val v = cell(idx)
                if (v.isEmpty()) return 0.0
                return v.toDoubleOrNull()?.takeIf { it.isFinite() } ?: throw FitbodFormatException("Row $line: \"$v\" is not a number.")
            }
            val name = cell(exercise)
            if (name.isEmpty()) return@mapIndexedNotNull null
            val (time, dateOnly) = parseDate(cell(date)) ?: throw FitbodFormatException("Row $line: couldn't read the date \"${cell(date)}\".")
            FitbodRow(
                exercise = name,
                time = time,
                dateOnly = dateOnly,
                reps = num(reps).roundToInt().coerceAtLeast(0),
                weight = num(weight).coerceAtLeast(0.0),
                durationSec = num(duration).roundToInt().coerceAtLeast(0),
                distanceM = num(distance).coerceAtLeast(0.0),
                warmup = cell(warmup).equals("true", ignoreCase = true),
            )
        }
        if (out.isEmpty()) throw FitbodFormatException("The file has no workouts in it.")
        val format = if (exercise >= 0 && header[exercise] == "exercise_name") "Fitbod support export" else "Fitbod app export"
        return FitbodExport(out, format, unit)
    }

    /**
     * Fitbod writes `yyyy-MM-dd HH:mm:ss +0000`. A time without an offset is read as local time; a bare date
     * (support export) becomes local noon, which keeps it on the right day in every time zone.
     */
    internal fun parseDate(raw: String, zone: ZoneId = ZoneId.systemDefault()): Pair<Instant, Boolean>? {
        val m = DATE.matchEntire(raw.trim()) ?: return null
        val g = m.groupValues
        return runCatching {
            val day = LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt())
            if (g[4].isEmpty()) return@runCatching day.atTime(LocalTime.NOON).atZone(zone).toInstant() to true
            val local = LocalDateTime.of(day, LocalTime.of(g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt()))
            val offset = g[7]
            val instant = if (offset.isEmpty()) local.atZone(zone).toInstant()
            else local.toInstant(if (offset == "Z") ZoneOffset.UTC else ZoneOffset.of(offset.let { if (it.length == 5) it.substring(0, 3) + ":" + it.substring(3) else it }))
            instant to false
        }.getOrNull()
    }

    /** RFC 4180: quoted fields may hold separators, doubled quotes and line breaks. Blank rows are dropped. */
    internal fun splitCsv(text: String, sep: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() { endField(); if (row.any { it.isNotBlank() }) rows.add(row); row = mutableListOf() }
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                quoted && ch == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && ch == '"' -> quoted = false
                quoted -> field.append(ch)
                ch == '"' -> quoted = true
                ch == sep -> endField()
                ch == '\n' -> endRow()
                ch == '\r' -> if (text.getOrNull(i + 1) != '\n') endRow()
                else -> field.append(ch)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}

/**
 * Fitbod exercise names that are the same movement, with the same way of logging weight, as an exercise in
 * Fluff's library. Only true equivalents are listed: a near miss (a Smith machine squat for a back squat,
 * an assisted pull-up whose weight is assistance) would corrupt strength estimates, so those are kept as
 * history only. Names are from Fitbod's catalogue as seen in real exports.
 */
object FitbodExercises {
    // Declared first: the alias table below is built with it during initialisation.
    private val nonWord = Regex("[^a-z0-9]+")

    private val aliases: Map<String, List<String>> = mapOf(
        "back_squat" to listOf("Back Squat", "Barbell Back Squat", "Barbell Squat"),
        "front_squat" to listOf("Front Squat", "Barbell Front Squat"),
        "goblet_squat" to listOf("Goblet Squat", "Dumbbell Goblet Squat", "Kettlebell Goblet Squat"),
        "leg_press" to listOf("Leg Press", "Machine Leg Press"),
        "leg_extension" to listOf("Leg Extension", "Machine Leg Extension"),
        "deadlift" to listOf("Deadlift", "Barbell Deadlift"),
        "trap_bar_deadlift" to listOf("Trap Bar Deadlift"),
        "romanian_deadlift" to listOf("Romanian Deadlift", "Barbell Romanian Deadlift"),
        "db_romanian_deadlift" to listOf("Dumbbell Romanian Deadlift"),
        "hip_thrust" to listOf("Barbell Hip Thrust", "Hip Thrust"),
        "kettlebell_swing" to listOf("Kettlebell Swing"),
        "leg_curl" to listOf("Leg Curl", "Seated Leg Curl", "Lying Leg Curl", "Lying Hamstrings Curl"),
        "back_extension" to listOf("Back Extension", "Back Extensions"),
        "bulgarian_split_squat" to listOf("Bulgarian Split Squat", "Dumbbell Bulgarian Split Squat"),
        "walking_lunge" to listOf("Walking Lunge", "Dumbbell Walking Lunge"),
        "step_up" to listOf("Step Up", "Dumbbell Step Up"),
        "single_leg_rdl" to listOf("Single Leg Romanian Deadlift"),
        "calf_raise" to listOf("Calf Raise", "Standing Calf Raise", "Standing Dumbbell Calf Raise"),
        "bench_press" to listOf("Barbell Bench Press", "Bench Press"),
        "incline_bench_press" to listOf("Barbell Incline Bench Press", "Incline Bench Press"),
        "db_bench_press" to listOf("Dumbbell Bench Press"),
        "machine_chest_press" to listOf("Machine Bench Press", "Machine Chest Press"),
        "push_up" to listOf("Push Up", "Pushup"),
        "dip" to listOf("Dip", "Dips", "Chest Dip"),
        "overhead_press" to listOf("Barbell Shoulder Press", "Overhead Press", "Standing Barbell Shoulder Press"),
        "db_shoulder_press" to listOf("Dumbbell Shoulder Press", "Standing Dumbbell Shoulder Press", "Seated Dumbbell Shoulder Press"),
        "landmine_press" to listOf("Landmine Press"),
        "lateral_raise" to listOf("Lateral Raise", "Dumbbell Lateral Raise", "Dumbbell Lateral Raises", "Side Lateral Raise", "Dumbbell Shoulder Raise"),
        "barbell_row" to listOf("Bent Over Barbell Row", "Barbell Row", "Barbell Bent Over Row"),
        "chest_supported_row" to listOf("Incline Dumbbell Row"),
        "db_row" to listOf("Dumbbell Row", "Single Arm Dumbbell Row", "One Arm Dumbbell Row"),
        "seated_cable_row" to listOf("Cable Row", "Seated Cable Row"),
        "face_pull" to listOf("Cable Face Pull", "Face Pull"),
        "rear_delt_fly" to listOf("Dumbbell Rear Delt Raise", "Dumbbell Bent Over Reverse Fly", "Rear Delt Fly"),
        "pull_up" to listOf("Pull Up", "Pullup"),
        "chin_up" to listOf("Chin Up", "Chinup"),
        "lat_pulldown" to listOf("Lat Pulldown", "Cable Lat Pulldown"),
        "db_curl" to listOf("Dumbbell Bicep Curl", "Dumbbell Biceps Curl", "Dumbbell Curl"),
        "hammer_curl" to listOf("Hammer Curl", "Hammer Curls", "Dumbbell Hammer Curl"),
        "triceps_pushdown" to listOf("Cable Tricep Pushdown", "Tricep Pushdown", "Triceps Pushdown", "Triceps Pressdown", "Cable Rope Tricep Extension"),
        "overhead_triceps_extension" to listOf("Dumbbell Tricep Extension"),
        "farmer_carry" to listOf("Farmer Walk", "Farmer's Walk", "Farmer Carry"),
        "suitcase_carry" to listOf("Single Arm Farmer Walk", "Kettlebell Single Arm Farmer Walk", "Suitcase Carry"),
        "plank" to listOf("Plank"),
        "side_plank" to listOf("Side Plank"),
        "dead_bug" to listOf("Dead Bug"),
        "pallof_press" to listOf("Pallof Press"),
        "hanging_knee_raise" to listOf("Hanging Knee Raise"),
        "ab_wheel" to listOf("Ab Rollout", "Ab Wheel Rollout"),
        "cable_woodchop" to listOf("Cable Wood Chop", "Cable Woodchop"),
        "copenhagen_plank" to listOf("Copenhagen Plank"),
        "glute_bridge" to listOf("Glute Bridge"),
    )

    private val byKey: Map<String, String> = buildMap {
        aliases.forEach { (id, names) ->
            check(ExerciseLibrary[id] != null) { "Fitbod alias points at unknown exercise $id" }
            names.forEach { n -> check(put(key(n), id) == null) { "Duplicate Fitbod alias $n" } }
        }
    }

    /** "Farmer's Walk", "farmers walk" and "Farmers-Walk" all compare equal. */
    private fun key(name: String) = name.lowercase().replace("'", "").replace(nonWord, " ").trim()

    /** The Fluff exercise id for a Fitbod name, or null when Fluff has no true equivalent. */
    fun match(fitbodName: String): String? = byKey[key(fitbodName)]

    /** Exercises Fluff doesn't know are stored under this id: shown by name in history, ignored by the planner. */
    const val HISTORY_ONLY_PREFIX = "fitbod:"

    fun idFor(fitbodName: String): String = match(fitbodName) ?: (HISTORY_ONLY_PREFIX + fitbodName.trim().take(120))
}

data class ImportedSet(
    val exerciseId: String,
    val exerciseOrder: Int,
    val setIndex: Int,
    val kind: SetKind,
    val weightKg: Double?,
    val reps: Int?,
    val durationSec: Int?,
)

data class ImportedWorkout(val start: Instant, val date: LocalDate, val durationMinutes: Int, val sets: List<ImportedSet>)

data class ExerciseCount(val fitbodName: String, val fluffId: String?, val sets: Int)

/** What an import would do, shown to the user before anything is written. */
data class FitbodImportPlan(
    val workouts: List<ImportedWorkout>,
    val exercises: List<ExerciseCount>,
    val unit: WeightUnit,
    val format: String,
    /** Heaviest working set of a matched exercise, as a unit sanity check: (Fitbod name, weight in kg). */
    val heaviest: Pair<String, Double>?,
) {
    val setCount: Int get() = workouts.sumOf { it.sets.size }
    val matched: List<ExerciseCount> get() = exercises.filter { it.fluffId != null }
    val historyOnly: List<ExerciseCount> get() = exercises.filter { it.fluffId == null }
    val firstDate: LocalDate? get() = workouts.minOfOrNull { it.date }
    val lastDate: LocalDate? get() = workouts.maxOfOrNull { it.date }
}

object FitbodImporter {
    /** Fitbod exports have no end time; this per-set allowance (work plus rest) estimates duration. */
    private const val SECONDS_PER_SET = 150

    fun plan(export: FitbodExport, unit: WeightUnit = export.unitFromHeader, zone: ZoneId = ZoneId.systemDefault()): FitbodImportPlan {
        // Keep only rows that record something; Fitbod writes all-zero rows for some skipped sets.
        val useful = export.rows.filter { it.reps > 0 || it.durationSec > 0 || it.distanceM > 0 }
        val workouts = useful.groupBy { it.time }.toSortedMap().map { (time, rows) ->
            val order = LinkedHashMap<String, Int>()
            val nextIndex = HashMap<String, Int>()
            val sets = rows.map { r ->
                val id = FitbodExercises.idFor(r.exercise)
                val o = order.getOrPut(id) { order.size }
                val idx = nextIndex.merge(id, 1, Int::plus)!! - 1
                ImportedSet(
                    exerciseId = id,
                    exerciseOrder = o,
                    setIndex = idx,
                    kind = if (r.warmup) SetKind.WARMUP else SetKind.WORKING,
                    weightKg = r.weight.takeIf { it > 0 }?.let { round2(unit.toKg(it)) },
                    reps = r.reps.takeIf { it > 0 },
                    durationSec = r.durationSec.takeIf { it > 0 },
                )
            }.sortedWith(compareBy({ it.exerciseOrder }, { it.setIndex }))
            val minutes = ((sets.size * SECONDS_PER_SET) / 60).coerceIn(20, 120)
            ImportedWorkout(time, time.atZone(zone).toLocalDate(), minutes, sets)
        }
        val exercises = useful.groupingBy { it.exercise.trim() }.eachCount()
            .map { (name, n) -> ExerciseCount(name, FitbodExercises.match(name), n) }
            .sortedByDescending { it.sets }
        val heaviest = useful.filter { !it.warmup && it.reps > 0 && it.weight > 0 && FitbodExercises.match(it.exercise) != null }
            .maxByOrNull { it.weight }?.let { it.exercise to round2(unit.toKg(it.weight)) }
        return FitbodImportPlan(workouts, exercises, unit, export.format, heaviest)
    }

    /** Rounds away lb→kg conversion noise such as 31.751465900000003. */
    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0
}
