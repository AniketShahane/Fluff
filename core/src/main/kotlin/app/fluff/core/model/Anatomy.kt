package app.fluff.core.model

import kotlinx.serialization.Serializable

/** Body regions a user can flag as injured or limited. */
@Serializable
enum class BodyRegion(val label: String) {
    NECK("Neck"),
    SHOULDER("Shoulder"),
    ELBOW("Elbow"),
    WRIST("Wrist"),
    UPPER_BACK("Upper back"),
    LOWER_BACK("Lower back"),
    HIP("Hip"),
    KNEE("Knee"),
    ANKLE("Ankle"),
}

enum class MuscleSize { SMALL, LARGE }

enum class MuscleGroup(val label: String) { PUSH("Push"), PULL("Pull"), LEGS("Legs"), CORE("Core") }

@Serializable
enum class Muscle(val label: String, val size: MuscleSize, val group: MuscleGroup) {
    CHEST("Chest", MuscleSize.LARGE, MuscleGroup.PUSH),
    FRONT_DELTS("Front delts", MuscleSize.SMALL, MuscleGroup.PUSH),
    SIDE_DELTS("Side delts", MuscleSize.SMALL, MuscleGroup.PUSH),
    REAR_DELTS("Rear delts", MuscleSize.SMALL, MuscleGroup.PULL),
    TRICEPS("Triceps", MuscleSize.SMALL, MuscleGroup.PUSH),
    BICEPS("Biceps", MuscleSize.SMALL, MuscleGroup.PULL),
    FOREARMS("Forearms", MuscleSize.SMALL, MuscleGroup.PULL),
    LATS("Lats", MuscleSize.LARGE, MuscleGroup.PULL),
    UPPER_BACK("Upper back", MuscleSize.LARGE, MuscleGroup.PULL),
    LOWER_BACK("Lower back", MuscleSize.LARGE, MuscleGroup.CORE),
    ABS("Abs", MuscleSize.SMALL, MuscleGroup.CORE),
    OBLIQUES("Obliques", MuscleSize.SMALL, MuscleGroup.CORE),
    GLUTES("Glutes", MuscleSize.LARGE, MuscleGroup.LEGS),
    QUADS("Quads", MuscleSize.LARGE, MuscleGroup.LEGS),
    HAMSTRINGS("Hamstrings", MuscleSize.LARGE, MuscleGroup.LEGS),
    ADDUCTORS("Adductors", MuscleSize.SMALL, MuscleGroup.LEGS),
    CALVES("Calves", MuscleSize.SMALL, MuscleGroup.LEGS),
}

@Serializable
enum class MovementPattern(val label: String) {
    SQUAT("Squat"),
    HINGE("Hinge"),
    LUNGE("Single leg"),
    HORIZONTAL_PUSH("Horizontal push"),
    VERTICAL_PUSH("Vertical push"),
    HORIZONTAL_PULL("Horizontal pull"),
    VERTICAL_PULL("Vertical pull"),
    CARRY("Carry & grip"),
    CORE("Core"),
    ISOLATION("Isolation"),
    CARDIO("Cardio"),
    MOBILITY("Mobility"),
    ;

    companion object {
        /** Patterns that have strength standards and appear on the strength radar. */
        val SCORED: List<MovementPattern> = listOf(
            SQUAT, HINGE, LUNGE, HORIZONTAL_PUSH, VERTICAL_PUSH, HORIZONTAL_PULL, VERTICAL_PULL, CARRY,
        )
    }
}

@Serializable
enum class Equipment(val label: String) {
    BARBELL("Barbell"),
    SQUAT_RACK("Squat rack"),
    BENCH("Bench"),
    DUMBBELL("Dumbbells"),
    KETTLEBELL("Kettlebell"),
    CABLE("Cable station"),
    MACHINE("Machines"),
    PULLUP_BAR("Pull-up bar"),
    DIP_STATION("Dip station"),
    BANDS("Bands"),
    CARDIO_MACHINE("Bike / rower"),
    ;

    companion object {
        val FULL_GYM: Set<Equipment> = entries.toSet()
        val HOME_DUMBBELLS: Set<Equipment> = setOf(DUMBBELL, BENCH, BANDS, PULLUP_BAR)
        val BODYWEIGHT_ONLY: Set<Equipment> = setOf(BANDS)
    }
}

/** How hard an exercise loads a body region. */
enum class Stress { LOW, MEDIUM, HIGH }

@Serializable
enum class Severity(val label: String) {
    /** Avoid exercises that stress the region heavily. */
    MILD("Mild"),

    /** Avoid medium and high stress on the region. */
    MODERATE("Moderate"),

    /** Avoid any loading of the region. */
    SEVERE("Severe"),
    ;

    fun blocks(stress: Stress): Boolean = when (this) {
        MILD -> stress == Stress.HIGH
        MODERATE -> stress != Stress.LOW
        SEVERE -> true
    }
}

@Serializable
data class Limitation(
    val region: BodyRegion,
    val severity: Severity,
    val note: String = "",
)
