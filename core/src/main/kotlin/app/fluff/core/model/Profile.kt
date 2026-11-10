package app.fluff.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class TrainingGoal(val label: String, val blurb: String) {
    STRENGTH("Get stronger", "Heavier lifts, lower reps, longer rests"),
    HYPERTROPHY("Build muscle", "More volume in the 8–15 rep range"),
    GENERAL("Feel fit", "Balanced strength, health and longevity"),
    ATHLETIC("Perform in my sport", "Strength that transfers to what you play"),
}

@Serializable
enum class Experience(val label: String, val blurb: String) {
    BEGINNER("New", "Less than a year of consistent lifting"),
    INTERMEDIATE("Intermediate", "1–3 years, comfortable with the big lifts"),
    ADVANCED("Advanced", "3+ years, structured training"),
}

@Serializable
enum class Sex(val label: String) { MALE("Male"), FEMALE("Female"), UNSPECIFIED("Prefer not to say") }

@Serializable
enum class WeightUnit(val label: String, val kgPerUnit: Double) {
    KG("kg", 1.0),
    LB("lb", 0.45359237),
    ;

    fun fromKg(kg: Double): Double = kg / kgPerUnit
    fun toKg(value: Double): Double = value * kgPerUnit
}

/** Other sports and activities a user does alongside lifting. */
@Serializable
enum class ActivityType(val label: String, val emoji: String, val defaultIntensity: Double) {
    RUNNING("Running", "🏃", 6.5),
    CYCLING("Cycling", "🚴", 5.5),
    SWIMMING("Swimming", "🏊", 6.0),
    CLIMBING("Climbing", "🧗", 6.5),
    TENNIS("Racket sports", "🎾", 6.0),
    FOOTBALL("Football", "⚽", 7.0),
    BASKETBALL("Basketball", "🏀", 7.0),
    MARTIAL_ARTS("Martial arts", "🥋", 7.5),
    ROWING("Rowing", "🚣", 6.5),
    SKIING("Skiing & snowboard", "⛷️", 6.0),
    HIKING("Hiking", "🥾", 4.5),
    YOGA("Yoga & pilates", "🧘", 2.5),
    WALKING("Walking", "🚶", 2.0),
    HIIT("HIIT & conditioning", "🔥", 8.0),
    STRENGTH("Strength training", "🏋️", 7.0),
    OTHER("Other", "✨", 5.0),
    ;

    companion object {
        /** Activities a user can pick during onboarding (strength is what Fluff itself tracks). */
        val SELECTABLE: List<ActivityType> = entries.filter { it != STRENGTH && it != OTHER }
    }
}

/** How the planner balances multi-joint and single-joint work. */
@Serializable
enum class MovementEmphasis(val label: String, val blurb: String) {
    COMPOUNDS("Compounds first", "Big multi-joint lifts only; isolation slots become compound accessories"),
    BALANCED("Balanced", "Compound lifts plus a little isolation for arms, delts and legs"),
    MORE_ISOLATION("More isolation", "Compounds plus extra single-joint work to bring up lagging muscles"),
}

@Serializable
data class ActivityHabit(val type: ActivityType, val sessionsPerWeek: Int)

@Serializable
data class UserProfile(
    val name: String = "",
    val sex: Sex = Sex.UNSPECIFIED,
    val birthYear: Int? = null,
    val bodyWeightKg: Double = 75.0,
    val heightCm: Double? = null,
    val goal: TrainingGoal = TrainingGoal.GENERAL,
    val experience: Experience = Experience.BEGINNER,
    val daysPerWeek: Int = 3,
    val sessionMinutes: Int = 60,
    val equipment: Set<Equipment> = Equipment.FULL_GYM,
    val limitations: List<Limitation> = emptyList(),
    val activities: List<ActivityHabit> = emptyList(),
    val unit: WeightUnit = WeightUnit.KG,
    val movementEmphasis: MovementEmphasis = MovementEmphasis.BALANCED,
    val onboarded: Boolean = false,
) {
    init {
        require(daysPerWeek in 1..7) { "daysPerWeek must be 1..7" }
        require(sessionMinutes in 15..180) { "sessionMinutes must be 15..180" }
        require(bodyWeightKg in 25.0..350.0) { "bodyWeightKg out of range" }
    }

    fun severityFor(region: BodyRegion): Severity? =
        limitations.filter { it.region == region }.maxByOrNull { it.severity.ordinal }?.severity
}
