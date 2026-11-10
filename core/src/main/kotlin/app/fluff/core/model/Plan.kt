package app.fluff.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class Focus(val label: String) {
    FULL_BODY("Full body"),
    UPPER("Upper body"),
    LOWER("Lower body"),
    PUSH("Push"),
    PULL("Pull"),
    LEGS("Legs"),
    RECOVERY("Recovery & mobility"),
}

@Serializable
enum class BlockType(val label: String) { WARMUP("Warm-up"), MAIN("Main work"), COOLDOWN("Cool-down") }

@Serializable
enum class PlanSource(val label: String) { ENGINE("Fluff engine"), AI("Claude coach") }

@Serializable
data class PlannedSet(
    val kind: SetKind,
    val reps: Int? = null,
    val durationSec: Int? = null,
    val weightKg: Double? = null,
    val rpe: Double? = null,
    val restSec: Int = 60,
)

@Serializable
data class PlannedExercise(
    val exerciseId: String,
    val sets: List<PlannedSet>,
    val note: String? = null,
) {
    init {
        require(sets.isNotEmpty()) { "A planned exercise needs at least one set" }
    }
}

@Serializable
data class PlanBlock(val type: BlockType, val exercises: List<PlannedExercise>)

@Serializable
data class WorkoutPlan(
    val epochDay: Long,
    val focus: Focus,
    val title: String,
    val rationale: List<String>,
    val blocks: List<PlanBlock>,
    val estimatedMinutes: Int,
    val source: PlanSource,
    val readinessScore: Int?,
    val coachNote: String? = null,
    val fallbackReason: String? = null,
) {
    fun block(type: BlockType): PlanBlock? = blocks.firstOrNull { it.type == type }
    val mainExercises: List<PlannedExercise> get() = block(BlockType.MAIN)?.exercises.orEmpty()
    val totalSets: Int get() = blocks.sumOf { b -> b.exercises.sumOf { it.sets.size } }
}

/** User-requested tweaks to today's plan. */
@Serializable
data class PlanAdjustments(
    val minutesOverride: Int? = null,
    /** -1 = go easier, +1 = push harder. */
    val intensityShift: Int = 0,
    val focusOverride: Focus? = null,
    val avoidExerciseIds: Set<String> = emptySet(),
    /** Changes deterministic tie-breaks so "regenerate" yields a different but valid plan. */
    val variationSeed: Int = 0,
) {
    init {
        require(intensityShift in -1..1) { "intensityShift must be -1..1" }
    }
}
