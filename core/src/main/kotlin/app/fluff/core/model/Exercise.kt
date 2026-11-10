package app.fluff.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class ExerciseRole {
    MAIN,
    SECONDARY,
    ACCESSORY,
    CORE,
    WARMUP_CARDIO,
    MOBILITY,
    ACTIVATION,
    STRETCH,
    BREATHING,
    COOLDOWN_CARDIO,
}

/** How the logged weight relates to the load on the body. */
enum class LoadType {
    /** Weight is the bar plus plates. */
    BARBELL,

    /** Weight is per dumbbell / kettlebell (per hand). */
    PER_HAND,

    /** Weight is a stack/plate machine or cable setting. */
    MACHINE,

    /** Bodyweight with optional added load (belt / vest). */
    BODYWEIGHT,

    /** Timed holds, cardio, stretches: weight not tracked. */
    TIMED,
}

/** What kind of movement an exercise is, as shown to the user and used for filtering. */
@Serializable
enum class MovementType(val label: String, val blurb: String) {
    COMPOUND("Compound", "Multi-joint lifts that train several muscles at once"),
    ISOLATION("Isolation", "Single-joint work that targets one muscle"),
    CORE("Core", "Bracing and trunk control"),
    CARRY("Carry", "Loaded walks for grip, posture and trunk"),
    CARDIO("Cardio", "Easy aerobic work"),
    MOBILITY("Mobility", "Range of motion, activation and stretching"),
}

/** How taxing an exercise is on the whole system (drives selection on low-readiness days). */
enum class SystemicCost { LOW, MEDIUM, HIGH }

/**
 * Relates an exercise to the reference lift of its movement pattern so strength scores
 * and starting loads can be estimated across variations. `factor` = typical e1RM of this
 * exercise (in its own logged units) ÷ e1RM of the reference lift.
 */
data class StrengthLink(val pattern: MovementPattern, val factor: Double)

data class Exercise(
    val id: String,
    val name: String,
    val pattern: MovementPattern,
    val roles: Set<ExerciseRole>,
    val primary: Set<Muscle>,
    val secondary: Set<Muscle> = emptySet(),
    /** Every listed piece of equipment is required. Empty means bodyweight. */
    val equipment: Set<Equipment> = emptySet(),
    val stress: Map<BodyRegion, Stress> = emptyMap(),
    val loadType: LoadType,
    val systemicCost: SystemicCost = SystemicCost.LOW,
    /** Fraction of bodyweight moved, for [LoadType.BODYWEIGHT] exercises. */
    val bodyweightFraction: Double = 0.0,
    val strengthLink: StrengthLink? = null,
    val unilateral: Boolean = false,
    val cue: String,
    /** Default prescription for timed or rep-based non-loaded drills. */
    val defaultDurationSec: Int? = null,
    val defaultReps: Int? = null,
) {
    val muscles: Set<Muscle> get() = primary + secondary

    fun isAvailableWith(available: Set<Equipment>): Boolean = available.containsAll(equipment)

    /** True when the exercise loads any of the user's limited regions beyond the allowed stress. */
    fun isContraindicatedFor(limitations: List<Limitation>): Boolean = limitations.any { lim ->
        val s = stress[lim.region] ?: return@any false
        lim.severity.blocks(s)
    }

    val isLoaded: Boolean get() = loadType != LoadType.TIMED

    val movementType: MovementType
        get() = when (pattern) {
            MovementPattern.ISOLATION -> MovementType.ISOLATION
            MovementPattern.CORE -> MovementType.CORE
            MovementPattern.CARRY -> MovementType.CARRY
            MovementPattern.CARDIO -> MovementType.CARDIO
            MovementPattern.MOBILITY -> MovementType.MOBILITY
            else -> MovementType.COMPOUND
        }
}
