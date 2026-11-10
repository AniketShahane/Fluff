package app.fluff.core.planner

import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.BlockType
import app.fluff.core.model.BodyRegion
import app.fluff.core.model.Equipment
import app.fluff.core.model.Exercise
import app.fluff.core.model.ExerciseRole
import app.fluff.core.model.Experience
import app.fluff.core.model.Focus
import app.fluff.core.model.LoadType
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.MovementPattern.CARRY
import app.fluff.core.model.MovementPattern.CORE
import app.fluff.core.model.MovementPattern.HINGE
import app.fluff.core.model.MovementPattern.HORIZONTAL_PULL
import app.fluff.core.model.MovementPattern.HORIZONTAL_PUSH
import app.fluff.core.model.MovementPattern.LUNGE
import app.fluff.core.model.MovementPattern.SQUAT
import app.fluff.core.model.MovementPattern.VERTICAL_PULL
import app.fluff.core.model.MovementPattern.VERTICAL_PUSH
import app.fluff.core.model.Muscle
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.PlanBlock
import app.fluff.core.model.PlanSource
import app.fluff.core.model.PlannedExercise
import app.fluff.core.model.PlannedSet
import app.fluff.core.model.SetKind
import app.fluff.core.model.SystemicCost
import app.fluff.core.model.TrainingGoal
import app.fluff.core.model.UserProfile
import app.fluff.core.model.WorkoutPlan
import app.fluff.core.progress.Loads
import app.fluff.core.progress.TrainingHistory
import app.fluff.core.readiness.Prescription
import app.fluff.core.readiness.Readiness
import app.fluff.core.readiness.ReadinessBand
import app.fluff.core.readiness.ReadinessEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.roundToInt

data class PlannerInput(
    val today: LocalDate,
    val profile: UserProfile,
    val readiness: Readiness,
    val history: TrainingHistory,
    /** 0 (wrecked) .. 1 (fully fresh) per muscle. */
    val freshness: Map<Muscle, Double>,
    val adjustments: PlanAdjustments = PlanAdjustments(),
)

/** Sets/reps/effort/rest template for a role under a goal. */
data class Scheme(val sets: Int, val reps: Int, val rpe: Double, val restSec: Int)

/**
 * Deterministic, explainable workout designer. Given the same input it always produces the same
 * plan; [PlanAdjustments.variationSeed] perturbs tie-breaks for "regenerate".
 */
class WorkoutPlanner {

    fun plan(input: PlannerInput): WorkoutPlan {
        val p = input.profile
        val adj = input.adjustments
        val band = shiftedBand(input.readiness.band, adj.intensityShift)
        val rx = ReadinessEngine.prescriptionFor(band).let { base ->
            if (p.experience == Experience.BEGINNER) base.copy(rpeCap = minOf(base.rpeCap, 8.5)) else base
        }
        val budget = adj.minutesOverride ?: p.sessionMinutes
        val ctx = Ctx(input, rx, band, budget)

        val focus = adj.focusOverride ?: if (rx.recoverySession) Focus.RECOVERY else chooseFocus(input)
        return if (focus == Focus.RECOVERY) recoveryPlan(ctx) else strengthPlan(ctx, focus)
    }

    // ───────────────────────── Focus selection ─────────────────────────

    private fun splitFor(days: Int): List<Focus> = when {
        days <= 3 -> listOf(Focus.FULL_BODY)
        days == 4 -> listOf(Focus.UPPER, Focus.LOWER)
        else -> listOf(Focus.PUSH, Focus.PULL, Focus.LEGS)
    }

    private val keyMuscles: Map<Focus, Set<Muscle>> = mapOf(
        Focus.FULL_BODY to setOf(Muscle.QUADS, Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.CHEST, Muscle.LATS, Muscle.UPPER_BACK),
        Focus.UPPER to setOf(Muscle.CHEST, Muscle.LATS, Muscle.UPPER_BACK, Muscle.FRONT_DELTS),
        Focus.LOWER to setOf(Muscle.QUADS, Muscle.GLUTES, Muscle.HAMSTRINGS),
        Focus.PUSH to setOf(Muscle.CHEST, Muscle.FRONT_DELTS, Muscle.TRICEPS),
        Focus.PULL to setOf(Muscle.LATS, Muscle.UPPER_BACK, Muscle.BICEPS),
        Focus.LEGS to setOf(Muscle.QUADS, Muscle.GLUTES, Muscle.HAMSTRINGS, Muscle.CALVES),
    )

    internal fun focusFreshness(focus: Focus, freshness: Map<Muscle, Double>): Double =
        keyMuscles.getValue(focus).map { freshness[it] ?: 1.0 }.average()

    private fun chooseFocus(input: PlannerInput): Focus {
        val options = splitFor(input.profile.daysPerWeek)
        if (options.size == 1) return options.first()
        return options.maxBy { f ->
            val last = input.history.sessions.lastOrNull { it.focus == f }?.date
            val daysSince = last?.let { ChronoUnit.DAYS.between(it, input.today).toInt() } ?: 14
            val recency = (daysSince * 0.08).coerceAtMost(0.5)
            focusFreshness(f, input.freshness) + recency + jitter(f.name, input) * 0.01
        }
    }

    // ───────────────────────── Strength plan ─────────────────────────

    private data class Slot(
        val role: ExerciseRole,
        val patterns: List<MovementPattern>,
        val isolationTargets: Set<Muscle> = emptySet(),
    )

    private inner class Ctx(val input: PlannerInput, val rx: Prescription, val band: ReadinessBand, val budgetMinutes: Int) {
        val profile get() = input.profile

        /** Short sessions get a compact (but never skipped) warm-up and cool-down. */
        val tier: Int = when {
            budgetMinutes <= 35 -> 0
            budgetMinutes <= 50 -> 1
            else -> 2
        }
        val advisor = LoadAdvisor(input.history, input.profile, input.today)
        val chosen = mutableListOf<Exercise>()
        val rationale = mutableListOf<String>()
    }

    private fun strengthPlan(ctx: Ctx, focus: Focus): WorkoutPlan {
        val input = ctx.input
        val slots = slotsFor(focus, input)
        val main = mutableListOf<Pair<Exercise, ExerciseRole>>()
        for (slot in slots) {
            val ex = pick(slot, ctx) ?: continue
            ctx.chosen += ex
            main += ex to slot.role
        }

        val chosenEx = main.map { it.first }
        val warmup = warmupBlock(focus, chosenEx, ctx)
        val cooldownEstimate = cooldownBlock(chosenEx, emptyList(), ctx)
        val fixedSeconds = (warmup.exercises + cooldownEstimate.exercises).sumOf { exerciseSeconds(it) }
        val mainBudget = (ctx.budgetMinutes - fixedSeconds / 60.0).coerceAtLeast(10.0)

        var planned = main.map { (ex, role) -> prescribe(ex, role, ctx) }.toMutableList()
        planned = addRampSets(planned, ctx)
        planned = fitToBudget(planned, main.map { it.second }, mainBudget)

        val trained = planned.mapNotNull { ExerciseLibrary[it.exerciseId] }
        val cooldown = cooldownBlock(trained, planned, ctx)
        val blocks = listOf(warmup, PlanBlock(BlockType.MAIN, planned), cooldown)

        buildRationale(ctx, focus, trained)
        val mainName = trained.firstOrNull()?.name
        return WorkoutPlan(
            epochDay = input.today.toEpochDay(),
            focus = focus,
            title = if (mainName != null) "${focus.label} · $mainName" else focus.label,
            rationale = ctx.rationale.toList(),
            blocks = blocks,
            estimatedMinutes = estimateMinutes(blocks),
            source = PlanSource.ENGINE,
            readinessScore = input.readiness.score,
        )
    }

    private fun slotsFor(focus: Focus, input: PlannerInput): List<Slot> {
        val f = input.freshness
        fun fresher(a: MovementPattern, b: MovementPattern): Pair<MovementPattern, MovementPattern> {
            val sa = patternFreshness(a, f) + recencyBonus(a, input)
            val sb = patternFreshness(b, f) + recencyBonus(b, input)
            return if (sa >= sb) a to b else b to a
        }
        return when (focus) {
            Focus.FULL_BODY -> {
                val (lowerMain, lowerOther) = fresher(SQUAT, HINGE)
                val push = fresher(HORIZONTAL_PUSH, VERTICAL_PUSH).first
                val pull = fresher(HORIZONTAL_PULL, VERTICAL_PULL).first
                listOf(
                    Slot(ExerciseRole.MAIN, listOf(lowerMain)),
                    Slot(ExerciseRole.SECONDARY, listOf(push)),
                    Slot(ExerciseRole.SECONDARY, listOf(pull)),
                    Slot(ExerciseRole.ACCESSORY, listOf(if (lowerOther == HINGE) HINGE else LUNGE, LUNGE)),
                    Slot(ExerciseRole.CORE, listOf(CORE, CARRY)),
                )
            }
            Focus.UPPER -> {
                val (push1, push2) = fresher(HORIZONTAL_PUSH, VERTICAL_PUSH)
                val (pull1, pull2) = fresher(VERTICAL_PULL, HORIZONTAL_PULL)
                listOf(
                    Slot(ExerciseRole.MAIN, listOf(push1)),
                    Slot(ExerciseRole.SECONDARY, listOf(pull1)),
                    Slot(ExerciseRole.SECONDARY, listOf(push2)),
                    Slot(ExerciseRole.ACCESSORY, listOf(pull2)),
                    Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.SIDE_DELTS, Muscle.REAR_DELTS)),
                    Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.BICEPS, Muscle.TRICEPS)),
                )
            }
            Focus.LOWER -> {
                val (l1, l2) = fresher(SQUAT, HINGE)
                listOf(
                    Slot(ExerciseRole.MAIN, listOf(l1)),
                    Slot(ExerciseRole.SECONDARY, listOf(l2)),
                    Slot(ExerciseRole.ACCESSORY, listOf(LUNGE)),
                    Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.HAMSTRINGS, Muscle.QUADS, Muscle.CALVES)),
                    Slot(ExerciseRole.CORE, listOf(CORE, CARRY)),
                )
            }
            Focus.PUSH -> listOf(
                Slot(ExerciseRole.MAIN, listOf(HORIZONTAL_PUSH)),
                Slot(ExerciseRole.SECONDARY, listOf(VERTICAL_PUSH)),
                Slot(ExerciseRole.ACCESSORY, listOf(HORIZONTAL_PUSH)),
                Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.SIDE_DELTS)),
                Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.TRICEPS)),
            )
            Focus.PULL -> {
                val (p1, p2) = fresher(VERTICAL_PULL, HORIZONTAL_PULL)
                listOf(
                    Slot(ExerciseRole.MAIN, listOf(p1)),
                    Slot(ExerciseRole.SECONDARY, listOf(p2)),
                    Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.REAR_DELTS)),
                    Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.BICEPS)),
                    Slot(ExerciseRole.CORE, listOf(CARRY, CORE)),
                )
            }
            Focus.LEGS -> listOf(
                Slot(ExerciseRole.MAIN, listOf(SQUAT)),
                Slot(ExerciseRole.SECONDARY, listOf(HINGE)),
                Slot(ExerciseRole.ACCESSORY, listOf(LUNGE)),
                Slot(ExerciseRole.ACCESSORY, listOf(MovementPattern.ISOLATION), setOf(Muscle.HAMSTRINGS, Muscle.CALVES)),
                Slot(ExerciseRole.CORE, listOf(CORE)),
            )
            Focus.RECOVERY -> emptyList()
        }
    }

    private val patternMuscles: Map<MovementPattern, Set<Muscle>> = mapOf(
        SQUAT to setOf(Muscle.QUADS, Muscle.GLUTES),
        HINGE to setOf(Muscle.HAMSTRINGS, Muscle.GLUTES, Muscle.LOWER_BACK),
        LUNGE to setOf(Muscle.QUADS, Muscle.GLUTES),
        HORIZONTAL_PUSH to setOf(Muscle.CHEST, Muscle.TRICEPS),
        VERTICAL_PUSH to setOf(Muscle.FRONT_DELTS, Muscle.TRICEPS),
        HORIZONTAL_PULL to setOf(Muscle.UPPER_BACK, Muscle.LATS),
        VERTICAL_PULL to setOf(Muscle.LATS, Muscle.BICEPS),
    )

    private fun patternFreshness(p: MovementPattern, f: Map<Muscle, Double>) =
        patternMuscles[p]?.map { f[it] ?: 1.0 }?.average() ?: 1.0

    /** Favour the pattern trained least recently as main lift (alternation). */
    private fun recencyBonus(p: MovementPattern, input: PlannerInput): Double {
        val last = input.history.sessions.lastOrNull { s ->
            s.sets.any { set -> set.isWork && ExerciseLibrary[set.exerciseId]?.let { it.pattern == p && ExerciseRole.MAIN in it.roles } == true }
        }?.date ?: return 0.15
        return (ChronoUnit.DAYS.between(last, input.today) * 0.02).coerceAtMost(0.15)
    }

    private fun roleAccepts(slotRole: ExerciseRole): List<ExerciseRole> = when (slotRole) {
        ExerciseRole.MAIN -> listOf(ExerciseRole.MAIN, ExerciseRole.SECONDARY, ExerciseRole.ACCESSORY)
        ExerciseRole.SECONDARY -> listOf(ExerciseRole.SECONDARY, ExerciseRole.MAIN, ExerciseRole.ACCESSORY)
        ExerciseRole.ACCESSORY -> listOf(ExerciseRole.ACCESSORY, ExerciseRole.SECONDARY)
        ExerciseRole.CORE -> listOf(ExerciseRole.CORE)
        else -> listOf(slotRole)
    }

    private fun pick(slot: Slot, ctx: Ctx): Exercise? {
        val p = ctx.profile
        val avoid = ctx.input.adjustments.avoidExerciseIds
        val chosenIds = ctx.chosen.map { it.id }.toSet()
        val chosenPrimary = ctx.chosen.flatMap { it.primary }.toSet()
        val lastSession = ctx.input.history.exercisesInLastSession()
        val accepted = roleAccepts(slot.role)

        val pool = ExerciseLibrary.all.filter { ex ->
            ex.id !in chosenIds && ex.id !in avoid &&
                ex.isAvailableWith(p.equipment) &&
                !ex.isContraindicatedFor(p.limitations) &&
                ex.roles.any { it in accepted } &&
                ex.pattern in slot.patterns &&
                (slot.isolationTargets.isEmpty() || ex.primary.any { it in slot.isolationTargets })
        }
        if (pool.isEmpty()) return null
        // On low-readiness days steer away from systemically expensive lifts when there is a choice.
        val gentle = if (ctx.rx.rpeCap <= 7.5) pool.filter { it.systemicCost != SystemicCost.HIGH }.ifEmpty { pool } else pool

        return gentle.maxBy { ex ->
            val roleRank = accepted.indexOfFirst { it in ex.roles }.let { if (it < 0) 3 else it }
            val freshness = ex.primary.map { ctx.input.freshness[it] ?: 1.0 }.average()
            var s = 0.0
            s += (2 - roleRank) * 0.6
            s += freshness
            s += minOf(ctx.input.history.familiarity(ex.id), 6) * 0.12
            s += slot.patterns.indexOf(ex.pattern).let { if (it == 0) 0.3 else 0.0 }
            if (slot.role != ExerciseRole.MAIN && ex.id in lastSession) s -= 0.35
            if (slot.role == ExerciseRole.MAIN && ex.loadType == LoadType.BARBELL &&
                p.goal in setOf(TrainingGoal.STRENGTH, TrainingGoal.ATHLETIC)
            ) s += 0.4
            if (slot.role == ExerciseRole.ACCESSORY && ex.primary.all { it in chosenPrimary }) s -= 0.2
            if (slot.isolationTargets.isNotEmpty()) {
                // Rotate between isolation targets day to day.
                val target = slot.isolationTargets.toList()[(ctx.input.today.toEpochDay() % slot.isolationTargets.size).toInt()]
                if (target in ex.primary) s += 0.25
            }
            s + jitter(ex.id, ctx.input) * 0.3
        }
    }

    private fun schemeFor(goal: TrainingGoal, role: ExerciseRole): Scheme = when (goal) {
        TrainingGoal.STRENGTH -> when (role) {
            ExerciseRole.MAIN -> Scheme(4, 4, 8.0, 180)
            ExerciseRole.SECONDARY -> Scheme(3, 6, 8.0, 150)
            ExerciseRole.CORE -> Scheme(3, 10, 7.5, 60)
            else -> Scheme(3, 10, 8.0, 90)
        }
        TrainingGoal.HYPERTROPHY -> when (role) {
            ExerciseRole.MAIN -> Scheme(4, 8, 8.0, 150)
            ExerciseRole.SECONDARY -> Scheme(3, 10, 8.5, 120)
            ExerciseRole.CORE -> Scheme(3, 12, 8.0, 60)
            else -> Scheme(3, 12, 9.0, 75)
        }
        TrainingGoal.GENERAL -> when (role) {
            ExerciseRole.MAIN -> Scheme(3, 6, 7.5, 150)
            ExerciseRole.SECONDARY -> Scheme(3, 8, 8.0, 120)
            ExerciseRole.CORE -> Scheme(2, 12, 7.5, 60)
            else -> Scheme(2, 12, 8.0, 75)
        }
        TrainingGoal.ATHLETIC -> when (role) {
            ExerciseRole.MAIN -> Scheme(4, 5, 7.5, 180)
            ExerciseRole.SECONDARY -> Scheme(3, 6, 8.0, 120)
            ExerciseRole.CORE -> Scheme(3, 10, 7.5, 60)
            else -> Scheme(3, 8, 8.0, 75)
        }
    }

    private fun prescribe(ex: Exercise, role: ExerciseRole, ctx: Ctx): PlannedExercise {
        val p = ctx.profile
        val base = schemeFor(p.goal, role)
        val freshness = ex.primary.map { ctx.input.freshness[it] ?: 1.0 }.average()
        val freshnessMult = when {
            freshness < 0.35 -> 0.6
            freshness < 0.6 -> 0.85
            else -> 1.0
        }
        val expDelta = when (p.experience) {
            Experience.BEGINNER -> if (role == ExerciseRole.MAIN) -1 else 0
            Experience.INTERMEDIATE -> 0
            Experience.ADVANCED -> if (role == ExerciseRole.MAIN) 1 else 0
        }
        val minSets = if (role == ExerciseRole.MAIN) 2 else 1
        val sets = max(minSets, ((base.sets + expDelta) * ctx.rx.volumeMultiplier * freshnessMult).roundToInt())
        val rpe = minOf(base.rpe, ctx.rx.rpeCap)

        // Timed / unloaded drills.
        if (!ex.isLoaded || ex.pattern == CARRY) {
            val duration = ex.defaultDurationSec
            val reps = if (duration == null) ex.defaultReps ?: base.reps else null
            val weight = if (ex.isLoaded) ctx.advisor.suggest(ex, 1, rpe).loggedKg else null
            val note = buildList {
                if (ex.unilateral) add(if (duration != null) "Each side" else "Reps per side")
                if (ex.pattern == CARRY) add("Walk for the full time")
            }.joinToString(" · ").ifEmpty { null }
            return PlannedExercise(ex.id, List(sets) { PlannedSet(SetKind.WORKING, reps, duration, weight, null, base.restSec) }, note)
        }

        val topSet = ctx.rx.allowTopSets && role == ExerciseRole.MAIN &&
            p.goal in setOf(TrainingGoal.STRENGTH, TrainingGoal.ATHLETIC) && p.experience != Experience.BEGINNER
        val list = mutableListOf<PlannedSet>()
        var basis: LoadBasis = LoadBasis.NONE
        if (topSet) {
            val topReps = max(1, base.reps - 1)
            val topRpe = minOf(rpe + 1.0, ctx.rx.rpeCap)
            val s = ctx.advisor.suggest(ex, topReps, topRpe)
            basis = s.basis
            list += PlannedSet(SetKind.TOP, topReps, null, s.loggedKg, topRpe, base.restSec + 30)
            val back = ctx.advisor.suggest(ex, base.reps, rpe)
            repeat(sets - 1) { list += PlannedSet(SetKind.BACKOFF, base.reps, null, back.loggedKg, rpe, base.restSec) }
        } else {
            val s = ctx.advisor.suggest(ex, base.reps, rpe)
            basis = s.basis
            repeat(sets) { list += PlannedSet(SetKind.WORKING, base.reps, null, s.loggedKg, rpe, base.restSec) }
        }
        val note = buildList {
            if (ex.unilateral) add("Reps per side")
            when (basis) {
                LoadBasis.STANDARDS -> add("Starting estimate. Adjust to hit the target effort")
                LoadBasis.RELATED_LIFT -> add("Estimated from a related lift")
                LoadBasis.NONE -> if (ex.loadType != LoadType.BODYWEIGHT) add("Pick a weight that leaves ~${(10 - rpe).roundToInt()} reps in reserve")
                else -> Unit
            }
        }.joinToString(" · ").ifEmpty { null }
        return PlannedExercise(ex.id, list, note)
    }

    /** Ramp-up sets inside the first main lift and a feeder set for the next heavy compound. */
    private fun addRampSets(planned: List<PlannedExercise>, ctx: Ctx): MutableList<PlannedExercise> {
        val out = planned.toMutableList()
        val unit = ctx.profile.unit
        var rampedFirst = false
        for (i in out.indices) {
            val pe = out[i]
            val ex = ExerciseLibrary[pe.exerciseId] ?: continue
            val working = pe.sets.filter { it.kind != SetKind.WARMUP }.mapNotNull { it.weightKg }.maxOrNull() ?: continue
            if (!ex.isLoaded || ex.pattern == CARRY || working <= 0) continue
            // Light dumbbell / machine work needs no ramp; the first working set is the warm-up.
            if (ex.loadType != LoadType.BARBELL && ex.loadType != LoadType.BODYWEIGHT && working < 12.0) continue
            val steps: List<Pair<Double, Int>> = if (!rampedFirst) {
                when (ex.loadType) {
                    LoadType.BARBELL -> buildList {
                        add(Loads.BAR_KG to 10)
                        add(0.4 to 5); add(0.6 to 3); add(0.8 to 2)
                        val top = pe.sets.firstOrNull { it.kind != SetKind.WARMUP }
                        if ((top?.rpe ?: 0.0) >= 8.5 && (top?.reps ?: 10) <= 5) add(0.9 to 1)
                    }
                    else -> listOf(0.5 to 8, 0.75 to 4)
                }
            } else if (ex.systemicCost != SystemicCost.LOW) {
                listOf(0.6 to 5)
            } else {
                emptyList()
            }
            rampedFirst = true
            if (steps.isEmpty()) continue
            val warmups = mutableListOf<PlannedSet>()
            var lastKg = -1.0
            val minJump = maxOf(unit.toKg(Loads.increment(ex, unit)) * 2, working * 0.1)
            for ((frac, reps) in steps) {
                // A value > 1 is an absolute kg (the empty bar).
                val target = if (frac > 1.0) frac else working * frac
                val kg = Loads.roundDown(ex, target, unit)
                if (kg > working - minJump + 1e-6 || (lastKg >= 0 && kg < lastKg + minJump - 1e-6)) continue
                if (ex.loadType == LoadType.BODYWEIGHT && kg <= 0.0) continue
                warmups += PlannedSet(SetKind.WARMUP, reps, null, kg, null, 60)
                lastKg = kg
            }
            if (warmups.isNotEmpty()) out[i] = pe.copy(sets = warmups + pe.sets)
        }
        return out
    }

    private fun fitToBudget(
        planned: MutableList<PlannedExercise>,
        roles: List<ExerciseRole>,
        mainMinutesBudget: Double,
    ): MutableList<PlannedExercise> {
        val items = planned.zip(roles).toMutableList()
        fun minutes() = items.sumOf { (pe, _) -> exerciseSeconds(pe) } / 60.0
        fun workSets(pe: PlannedExercise) = pe.sets.count { it.kind != SetKind.WARMUP }
        fun trimOne(floor: (ExerciseRole) -> Int): Boolean {
            // Trim from the least important exercise (last) that is above its floor.
            val idx = items.indices.reversed().firstOrNull { i -> workSets(items[i].first) > floor(items[i].second) } ?: return false
            val (pe, role) = items[idx]
            val lastWork = pe.sets.indexOfLast { it.kind != SetKind.WARMUP }
            items[idx] = pe.copy(sets = pe.sets.filterIndexed { j, _ -> j != lastWork }) to role
            return true
        }
        fun dropAccessory(minItems: Int): Boolean {
            if (items.size <= minItems) return false
            val idx = items.indexOfLast { it.second == ExerciseRole.ACCESSORY || it.second == ExerciseRole.CORE }
            if (idx < 0) return false
            items.removeAt(idx)
            return true
        }
        // Prefer keeping variety with fewer sets over many sets of fewer exercises.
        while (minutes() > mainMinutesBudget && trimOne { 3 }) Unit
        while (minutes() > mainMinutesBudget && dropAccessory(minItems = 4)) Unit
        while (minutes() > mainMinutesBudget && trimOne { if (it == ExerciseRole.MAIN || it == ExerciseRole.SECONDARY) 2 else 1 }) Unit
        while (minutes() > mainMinutesBudget && dropAccessory(minItems = 2)) Unit
        return items.map { it.first }.toMutableList()
    }

    // ───────────────────────── Warm-up & cool-down ─────────────────────────

    private fun allowed(id: String, ctx: Ctx): Exercise? = ExerciseLibrary[id]?.takeIf {
        it.isAvailableWith(ctx.profile.equipment) && !it.isContraindicatedFor(ctx.profile.limitations)
    }

    private fun timedSet(ex: Exercise, seconds: Int? = ex.defaultDurationSec): PlannedSet =
        if (ex.defaultReps != null && ex.role() != ExerciseRole.WARMUP_CARDIO) {
            PlannedSet(SetKind.WARMUP, reps = ex.defaultReps, restSec = 10)
        } else {
            PlannedSet(SetKind.WARMUP, durationSec = seconds ?: 45, restSec = 10)
        }

    private fun Exercise.role() = roles.first()

    private fun warmupBlock(focus: Focus, trained: List<Exercise>, ctx: Ctx): PlanBlock {
        val lowerFocus = focus in setOf(Focus.LOWER, Focus.LEGS)
        val upperFocus = focus in setOf(Focus.UPPER, Focus.PUSH, Focus.PULL)
        val cardioId = when {
            Equipment.CARDIO_MACHINE in ctx.profile.equipment && (lowerFocus || ctx.profile.severityFor(BodyRegion.LOWER_BACK) != null) -> "bike_easy"
            Equipment.CARDIO_MACHINE in ctx.profile.equipment -> "rower_easy"
            else -> null
        }
        val cardio = listOfNotNull(cardioId, "jump_rope", "march_in_place").firstNotNullOfOrNull { allowed(it, ctx) }
        val cardioSec = ((ctx.rx.warmupMinutes * 60 * (0.3 + 0.15 * ctx.tier)) / 30).roundToInt() * 30

        val mobilityIds = when {
            lowerFocus -> listOf("worlds_greatest_stretch", "hip_90_90", "ankle_rocks", "leg_swings")
            upperFocus -> listOf("thoracic_rotation", "arm_circles", "band_dislocates", "cat_cow")
            else -> listOf("worlds_greatest_stretch", "thoracic_rotation", "hip_90_90", "arm_circles")
        }
        val limitationDrills = ctx.profile.limitations.mapNotNull {
            when (it.region) {
                BodyRegion.NECK -> "neck_cars"
                BodyRegion.LOWER_BACK -> "cat_cow"
                BodyRegion.ANKLE -> "ankle_rocks"
                BodyRegion.HIP -> "hip_90_90"
                BodyRegion.UPPER_BACK -> "thoracic_rotation"
                BodyRegion.SHOULDER -> "arm_circles"
                else -> null
            }
        }
        val maxMobility = (if (ctx.band <= ReadinessBand.STEADY) 3 else 4) - (if (ctx.tier == 0) 1 else 0)
        val mobility = (limitationDrills + mobilityIds).distinct().mapNotNull { allowed(it, ctx) }.take(maxMobility)

        val activationIds = when {
            lowerFocus -> listOf("glute_bridge", "monster_walk")
            upperFocus -> listOf("band_pull_apart", "scap_push_up")
            else -> listOf("glute_bridge", "band_pull_apart", "scap_push_up")
        }
        val activation = activationIds.mapNotNull { allowed(it, ctx) }.take(if (ctx.tier == 0) 1 else 2)
        val activationSets = if (ctx.tier == 2 && ctx.band >= ReadinessBand.STEADY) 2 else 1

        val items = buildList {
            cardio?.let { add(PlannedExercise(it.id, listOf(PlannedSet(SetKind.WARMUP, durationSec = cardioSec.coerceAtLeast(120), restSec = 0)), it.cue)) }
            mobility.forEach { add(PlannedExercise(it.id, listOf(timedSet(it)), if (it.unilateral) "Each side" else null)) }
            activation.forEach { add(PlannedExercise(it.id, List(activationSets) { _ -> timedSet(it) }, null)) }
        }
        return PlanBlock(BlockType.WARMUP, items)
    }

    private fun cooldownBlock(trained: List<Exercise>, planned: List<PlannedExercise>, ctx: Ctx): PlanBlock {
        val load = mutableMapOf<Muscle, Double>()
        planned.forEach { pe ->
            val ex = ExerciseLibrary[pe.exerciseId] ?: return@forEach
            val n = pe.sets.count { it.kind != SetKind.WARMUP }.toDouble()
            ex.primary.forEach { load.merge(it, n, Double::plus) }
            ex.secondary.forEach { load.merge(it, n * 0.5, Double::plus) }
        }
        if (planned.isEmpty()) {
            if (trained.isEmpty()) Muscle.entries.forEach { load[it] = 1.0 }
            trained.forEach { ex -> ex.primary.forEach { load.merge(it, 1.0, Double::plus) }; ex.secondary.forEach { load.merge(it, 0.5, Double::plus) } }
        }
        val ranked = if (planned.isEmpty() && trained.isEmpty()) {
            listOf("couch_stretch", "figure_four", "hamstring_stretch", "lat_stretch", "doorway_pec_stretch", "childs_pose", "calf_stretch")
        } else {
            load.entries.sortedByDescending { it.value }.mapNotNull { ExerciseLibrary.stretchFor[it.key] }
        }
        val stretches = ranked
            .distinct()
            .mapNotNull { allowed(it, ctx) }
            .take(if (ctx.band == ReadinessBand.RECOVER) 5 else 2 + minOf(ctx.tier, 1))

        val flush = listOf(if (Equipment.CARDIO_MACHINE in ctx.profile.equipment) "bike_flush" else "easy_walk", "easy_walk")
            .firstNotNullOfOrNull { allowed(it, ctx) }
        val breathing = listOf(if (ctx.profile.severityFor(BodyRegion.LOWER_BACK) != null) "crocodile_breathing" else "box_breathing", "box_breathing")
            .firstNotNullOfOrNull { allowed(it, ctx) }
        val items = buildList {
            if (ctx.tier > 0) flush?.let { add(PlannedExercise(it.id, listOf(PlannedSet(SetKind.WARMUP, durationSec = 60 + 60 * ctx.tier, restSec = 0)), it.cue)) }
            val hold = if (ctx.tier == 2 || ctx.band == ReadinessBand.RECOVER) 45 else 30
            stretches.forEach { add(PlannedExercise(it.id, listOf(PlannedSet(SetKind.WARMUP, durationSec = hold, restSec = 5)), if (it.unilateral) "Each side" else null)) }
            breathing?.let { add(PlannedExercise(it.id, listOf(PlannedSet(SetKind.WARMUP, durationSec = if (ctx.tier == 0) 60 else 90, restSec = 0)), it.cue)) }
        }
        return PlanBlock(BlockType.COOLDOWN, items)
    }

    // ───────────────────────── Recovery session ─────────────────────────

    private fun recoveryPlan(ctx: Ctx): WorkoutPlan {
        val zone2 = listOf("bike_easy", "rower_easy", "march_in_place", "easy_walk").firstNotNullOfOrNull { allowed(it, ctx) }
        val flow = listOf("cat_cow", "worlds_greatest_stretch", "hip_90_90", "thoracic_rotation", "deep_squat_hold", "inchworm")
            .mapNotNull { allowed(it, ctx) }
        val core = listOf("dead_bug", "side_plank", "plank").mapNotNull { allowed(it, ctx) }.take(2)

        val warmup = PlanBlock(
            BlockType.WARMUP,
            flow.take(3).map { PlannedExercise(it.id, listOf(timedSet(it)), if (it.unilateral) "Each side" else null) },
        )
        val accessories = buildList {
            flow.drop(3).take(if (ctx.tier == 0) 1 else 3).forEach { add(PlannedExercise(it.id, List(2) { _ -> timedSet(it).copy(kind = SetKind.WORKING) }, "Slow and controlled")) }
            core.forEach { ex ->
                add(PlannedExercise(ex.id, List(2) { PlannedSet(SetKind.WORKING, reps = ex.defaultReps.takeIf { ex.defaultDurationSec == null }, durationSec = ex.defaultDurationSec, restSec = 45) }, "Easy effort"))
            }
        }
        val cooldown = cooldownBlock(emptyList(), emptyList(), ctx)
        val usedSeconds = (warmup.exercises + accessories + cooldown.exercises).sumOf { exerciseSeconds(it) }
        // Zone 2 fills whatever time is left, within sensible bounds.
        val zone2Min = ((ctx.budgetMinutes * 60 - usedSeconds - 90) / 60).coerceIn(8, 30)
        val main = PlanBlock(
            BlockType.MAIN,
            listOfNotNull(
                zone2?.let { PlannedExercise(it.id, listOf(PlannedSet(SetKind.WORKING, durationSec = zone2Min * 60, rpe = 4.0, restSec = 60)), "Zone 2: you could hold a conversation") },
            ) + accessories,
        )
        val blocks = listOf(warmup, main, cooldown)
        val r = ctx.input.readiness
        ctx.rationale += "Readiness ${r.score} (${ctx.band.label}): today is about recovery, not load."
        r.factors.firstOrNull { it.impact < 0 }?.let { ctx.rationale += "Biggest drag: ${it.title} (${it.detail.replaceFirstChar { c -> c.lowercase() }})." }
        ctx.rationale += "Easy aerobic work and mobility speed recovery without adding fatigue."
        return WorkoutPlan(
            epochDay = ctx.input.today.toEpochDay(),
            focus = Focus.RECOVERY,
            title = "Recovery flow",
            rationale = ctx.rationale.toList(),
            blocks = blocks,
            estimatedMinutes = estimateMinutes(blocks),
            source = PlanSource.ENGINE,
            readinessScore = r.score,
        )
    }

    // ───────────────────────── Rationale ─────────────────────────

    private fun buildRationale(ctx: Ctx, focus: Focus, trained: List<Exercise>) {
        val r = ctx.input.readiness
        val rx = ctx.rx
        val volumeNote = when {
            rx.volumeMultiplier > 1.0 -> "a little extra volume"
            rx.volumeMultiplier < 1.0 -> "volume trimmed ${((1 - rx.volumeMultiplier) * 100).roundToInt()}%"
            else -> "full volume"
        }
        ctx.rationale += "Readiness ${r.score} (${ctx.band.label}): $volumeNote, effort capped at RPE ${fmt(rx.rpeCap)}."
        val shift = ctx.input.adjustments.intensityShift
        if (shift != 0) ctx.rationale += if (shift > 0) "You asked to push harder, so the cap moved up a notch." else "You asked to go easier, so the cap moved down a notch."
        r.factors.firstOrNull { it.impact <= -5 }?.let { ctx.rationale += "Watching: ${it.title} (${it.detail.replaceFirstChar { c -> c.lowercase() }})." }
        r.factors.firstOrNull { it.impact >= 5 }?.let { ctx.rationale += "Working for you: ${it.title} (${it.detail.replaceFirstChar { c -> c.lowercase() }})." }

        val fresh = (focusFreshness(focus, ctx.input.freshness) * 100).roundToInt()
        val tired = Muscle.entries.filter { (ctx.input.freshness[it] ?: 1.0) < 0.5 }
        if (tired.isNotEmpty()) {
            ctx.rationale += "${focus.label} today: target muscles are $fresh% fresh, while ${tired.take(3).joinToString(", ") { it.label.lowercase() }} are still recovering."
        } else if (ctx.input.adjustments.focusOverride == null && splitFor(ctx.profile.daysPerWeek).size > 1) {
            ctx.rationale += "${focus.label} is most due and fully recovered ($fresh% fresh)."
        }
        if (ctx.profile.limitations.isNotEmpty()) {
            ctx.rationale += "Working around your ${ctx.profile.limitations.joinToString(" and ") { "${it.severity.label.lowercase()} ${it.region.label.lowercase()}" }} limitation: risky variations are excluded."
        }
        trained.firstOrNull()?.let { main ->
            val goalLine = when (ctx.profile.goal) {
                TrainingGoal.STRENGTH -> if (rx.allowTopSets) "Strength goal: one heavy top set of ${main.name}, then back-off sets." else "Strength goal: crisp, repeatable sets of ${main.name} below max effort."
                TrainingGoal.HYPERTROPHY -> "Muscle goal: moderate loads and close-to-failure accessories."
                TrainingGoal.GENERAL -> "Balanced session: every major pattern without grinding."
                TrainingGoal.ATHLETIC -> "Performance goal: move ${main.name} with speed; stop sets before they slow down."
            }
            ctx.rationale += goalLine
        }
    }

    private fun fmt(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

    // ───────────────────────── Helpers ─────────────────────────

    private fun shiftedBand(band: ReadinessBand, shift: Int): ReadinessBand {
        // Bands are ordered best → worst, so "harder" moves towards index 0.
        val idx = (band.ordinal - shift).coerceIn(0, ReadinessBand.entries.size - 1)
        return ReadinessBand.entries[idx]
    }

    /** Stable pseudo-random value in [0, 1) for tie-breaking. */
    private fun jitter(key: String, input: PlannerInput): Double {
        var h = 1125899906842597L
        val s = "$key|${input.today.toEpochDay()}|${input.adjustments.variationSeed}"
        for (c in s) h = 31 * h + c.code
        h = h xor (h ushr 33)
        h *= -0xae502812aa7333L
        h = h xor (h ushr 33)
        return ((h ushr 11).toDouble() / (1L shl 53).toDouble())
    }

    companion object {
        /** Seconds a set takes to perform (not counting rest). */
        fun workSeconds(set: PlannedSet): Int = set.durationSec ?: ((set.reps ?: 8) * 4 + 10)

        fun exerciseSeconds(pe: PlannedExercise): Int {
            val ex = ExerciseLibrary[pe.exerciseId]
            val sides = if (ex?.unilateral == true) 2 else 1
            val transition = if (pe.sets.all { it.kind == SetKind.WARMUP } && ex?.isLoaded != true) 10 else 30
            return pe.sets.sumOf { workSeconds(it) * sides + it.restSec } + transition
        }

        fun estimateMinutes(blocks: List<PlanBlock>): Int =
            (blocks.sumOf { b -> b.exercises.sumOf { exerciseSeconds(it) } } / 60.0).roundToInt()
    }
}
