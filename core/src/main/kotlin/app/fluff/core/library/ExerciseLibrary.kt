package app.fluff.core.library

import app.fluff.core.model.BodyRegion
import app.fluff.core.model.BodyRegion.ANKLE
import app.fluff.core.model.BodyRegion.ELBOW
import app.fluff.core.model.BodyRegion.HIP
import app.fluff.core.model.BodyRegion.KNEE
import app.fluff.core.model.BodyRegion.LOWER_BACK
import app.fluff.core.model.BodyRegion.NECK
import app.fluff.core.model.BodyRegion.SHOULDER
import app.fluff.core.model.BodyRegion.UPPER_BACK
import app.fluff.core.model.BodyRegion.WRIST
import app.fluff.core.model.Equipment
import app.fluff.core.model.Equipment.BANDS
import app.fluff.core.model.Equipment.BARBELL
import app.fluff.core.model.Equipment.BENCH
import app.fluff.core.model.Equipment.CABLE
import app.fluff.core.model.Equipment.CARDIO_MACHINE
import app.fluff.core.model.Equipment.DIP_STATION
import app.fluff.core.model.Equipment.DUMBBELL
import app.fluff.core.model.Equipment.KETTLEBELL
import app.fluff.core.model.Equipment.MACHINE
import app.fluff.core.model.Equipment.PULLUP_BAR
import app.fluff.core.model.Equipment.SQUAT_RACK
import app.fluff.core.model.Exercise
import app.fluff.core.model.ExerciseRole
import app.fluff.core.model.ExerciseRole.ACCESSORY
import app.fluff.core.model.ExerciseRole.ACTIVATION
import app.fluff.core.model.ExerciseRole.BREATHING
import app.fluff.core.model.ExerciseRole.COOLDOWN_CARDIO
import app.fluff.core.model.ExerciseRole.CORE
import app.fluff.core.model.ExerciseRole.MAIN
import app.fluff.core.model.ExerciseRole.MOBILITY
import app.fluff.core.model.ExerciseRole.SECONDARY
import app.fluff.core.model.ExerciseRole.STRETCH
import app.fluff.core.model.ExerciseRole.WARMUP_CARDIO
import app.fluff.core.model.LoadType
import app.fluff.core.model.MovementPattern
import app.fluff.core.model.Muscle
import app.fluff.core.model.Muscle.ABS
import app.fluff.core.model.Muscle.ADDUCTORS
import app.fluff.core.model.Muscle.BICEPS
import app.fluff.core.model.Muscle.CALVES
import app.fluff.core.model.Muscle.CHEST
import app.fluff.core.model.Muscle.FOREARMS
import app.fluff.core.model.Muscle.FRONT_DELTS
import app.fluff.core.model.Muscle.GLUTES
import app.fluff.core.model.Muscle.HAMSTRINGS
import app.fluff.core.model.Muscle.LATS
import app.fluff.core.model.Muscle.OBLIQUES
import app.fluff.core.model.Muscle.QUADS
import app.fluff.core.model.Muscle.REAR_DELTS
import app.fluff.core.model.Muscle.SIDE_DELTS
import app.fluff.core.model.Muscle.TRICEPS
import app.fluff.core.model.Stress
import app.fluff.core.model.Stress.HIGH
import app.fluff.core.model.Stress.LOW
import app.fluff.core.model.Stress.MEDIUM
import app.fluff.core.model.StrengthLink
import app.fluff.core.model.SystemicCost

/**
 * The curated exercise catalogue. IDs are stable: they are persisted in logs and sent to
 * the AI coach, so never rename one — add a new entry instead.
 */
object ExerciseLibrary {

    private val U_BACK = Muscle.UPPER_BACK
    private val L_BACK = Muscle.LOWER_BACK

    val all: List<Exercise> = buildList {
        // ───────────── Squat pattern ─────────────
        add(
            Exercise(
                id = "back_squat", name = "Back Squat", pattern = MovementPattern.SQUAT,
                roles = setOf(MAIN), primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, L_BACK, ABS),
                equipment = setOf(BARBELL, SQUAT_RACK), stress = mapOf(KNEE to HIGH, LOWER_BACK to HIGH, HIP to MEDIUM, SHOULDER to LOW),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.HIGH,
                strengthLink = StrengthLink(MovementPattern.SQUAT, 1.0),
                cue = "Brace, sit between your hips, drive the floor away.",
            ),
        )
        add(
            Exercise(
                id = "front_squat", name = "Front Squat", pattern = MovementPattern.SQUAT,
                roles = setOf(MAIN, SECONDARY), primary = setOf(QUADS), secondary = setOf(GLUTES, U_BACK, ABS),
                equipment = setOf(BARBELL, SQUAT_RACK), stress = mapOf(KNEE to HIGH, WRIST to MEDIUM, LOWER_BACK to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.HIGH,
                strengthLink = StrengthLink(MovementPattern.SQUAT, 0.82),
                cue = "Elbows high, torso tall, knees track over toes.",
            ),
        )
        add(
            Exercise(
                id = "goblet_squat", name = "Goblet Squat", pattern = MovementPattern.SQUAT,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(QUADS, GLUTES), secondary = setOf(ABS, U_BACK),
                equipment = setOf(DUMBBELL), stress = mapOf(KNEE to MEDIUM, LOWER_BACK to LOW),
                loadType = LoadType.PER_HAND, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.SQUAT, 0.32),
                cue = "Hold the bell at your chest, elbows inside the knees at the bottom.",
            ),
        )
        add(
            Exercise(
                id = "leg_press", name = "Leg Press", pattern = MovementPattern.SQUAT,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS),
                equipment = setOf(MACHINE), stress = mapOf(KNEE to MEDIUM, LOWER_BACK to LOW),
                loadType = LoadType.MACHINE, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.SQUAT, 1.6),
                cue = "Lower until hips want to tuck, then press through mid-foot.",
            ),
        )
        add(
            Exercise(
                id = "leg_extension", name = "Leg Extension", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(QUADS),
                equipment = setOf(MACHINE), stress = mapOf(KNEE to MEDIUM),
                loadType = LoadType.MACHINE, cue = "Pause at the top, squeeze the quads.",
            ),
        )
        // ───────────── Hinge pattern ─────────────
        add(
            Exercise(
                id = "deadlift", name = "Deadlift", pattern = MovementPattern.HINGE,
                roles = setOf(MAIN), primary = setOf(GLUTES, HAMSTRINGS, L_BACK), secondary = setOf(U_BACK, FOREARMS, QUADS, LATS),
                equipment = setOf(BARBELL), stress = mapOf(LOWER_BACK to HIGH, HIP to MEDIUM, KNEE to LOW),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.HIGH,
                strengthLink = StrengthLink(MovementPattern.HINGE, 1.0),
                cue = "Wedge in, push the floor away, lock out with glutes not lower back.",
            ),
        )
        add(
            Exercise(
                id = "trap_bar_deadlift", name = "Trap Bar Deadlift", pattern = MovementPattern.HINGE,
                roles = setOf(MAIN, SECONDARY), primary = setOf(GLUTES, QUADS, HAMSTRINGS), secondary = setOf(L_BACK, U_BACK, FOREARMS),
                equipment = setOf(BARBELL), stress = mapOf(LOWER_BACK to MEDIUM, KNEE to MEDIUM, HIP to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.HIGH,
                strengthLink = StrengthLink(MovementPattern.HINGE, 1.05),
                cue = "Stand tall in the handles, chest up, drive through the whole foot.",
            ),
        )
        add(
            Exercise(
                id = "romanian_deadlift", name = "Romanian Deadlift", pattern = MovementPattern.HINGE,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(L_BACK, FOREARMS),
                equipment = setOf(BARBELL), stress = mapOf(LOWER_BACK to MEDIUM, HIP to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HINGE, 0.7),
                cue = "Soft knees, push hips back until hamstrings bite, keep the bar close.",
            ),
        )
        add(
            Exercise(
                id = "db_romanian_deadlift", name = "Dumbbell RDL", pattern = MovementPattern.HINGE,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(L_BACK, FOREARMS),
                equipment = setOf(DUMBBELL), stress = mapOf(LOWER_BACK to MEDIUM, HIP to LOW),
                loadType = LoadType.PER_HAND, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HINGE, 0.3),
                cue = "Dumbbells trace your thighs, hips back, flat back.",
            ),
        )
        add(
            Exercise(
                id = "hip_thrust", name = "Barbell Hip Thrust", pattern = MovementPattern.HINGE,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(GLUTES), secondary = setOf(HAMSTRINGS),
                equipment = setOf(BARBELL, BENCH), stress = mapOf(LOWER_BACK to LOW, HIP to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HINGE, 1.1),
                cue = "Chin tucked, ribs down, finish with a hard glute squeeze.",
            ),
        )
        add(
            Exercise(
                id = "kettlebell_swing", name = "Kettlebell Swing", pattern = MovementPattern.HINGE,
                roles = setOf(ACCESSORY), primary = setOf(GLUTES, HAMSTRINGS), secondary = setOf(L_BACK, ABS, FOREARMS),
                equipment = setOf(KETTLEBELL), stress = mapOf(LOWER_BACK to MEDIUM, SHOULDER to LOW),
                loadType = LoadType.PER_HAND, systemicCost = SystemicCost.MEDIUM,
                cue = "Hike it back, snap the hips, let the bell float.",
            ),
        )
        add(
            Exercise(
                id = "leg_curl", name = "Leg Curl", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(HAMSTRINGS), secondary = setOf(CALVES),
                equipment = setOf(MACHINE), stress = mapOf(KNEE to LOW),
                loadType = LoadType.MACHINE, cue = "Control the way down for three seconds.",
            ),
        )
        add(
            Exercise(
                id = "back_extension", name = "Back Extension", pattern = MovementPattern.HINGE,
                roles = setOf(ACCESSORY), primary = setOf(L_BACK, GLUTES), secondary = setOf(HAMSTRINGS),
                equipment = setOf(MACHINE), stress = mapOf(LOWER_BACK to MEDIUM),
                loadType = LoadType.BODYWEIGHT, bodyweightFraction = 0.5,
                cue = "Hinge at the hips, finish neutral, don't hyperextend.",
            ),
        )
        // ───────────── Single leg ─────────────
        add(
            Exercise(
                id = "bulgarian_split_squat", name = "Bulgarian Split Squat", pattern = MovementPattern.LUNGE,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, ABS),
                equipment = setOf(DUMBBELL, BENCH), stress = mapOf(KNEE to MEDIUM, HIP to MEDIUM, ANKLE to LOW),
                loadType = LoadType.PER_HAND, systemicCost = SystemicCost.MEDIUM, unilateral = true,
                strengthLink = StrengthLink(MovementPattern.LUNGE, 1.0),
                cue = "Back foot on the bench, drop straight down, front heel planted.",
            ),
        )
        add(
            Exercise(
                id = "reverse_lunge", name = "Reverse Lunge", pattern = MovementPattern.LUNGE,
                roles = setOf(ACCESSORY), primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, HAMSTRINGS),
                equipment = setOf(DUMBBELL), stress = mapOf(KNEE to LOW, HIP to MEDIUM, ANKLE to LOW),
                loadType = LoadType.PER_HAND, unilateral = true,
                strengthLink = StrengthLink(MovementPattern.LUNGE, 1.1),
                cue = "Step back softly, both knees to 90°, drive through the front foot.",
            ),
        )
        add(
            Exercise(
                id = "walking_lunge", name = "Walking Lunge", pattern = MovementPattern.LUNGE,
                roles = setOf(ACCESSORY), primary = setOf(QUADS, GLUTES), secondary = setOf(ADDUCTORS, CALVES),
                equipment = setOf(DUMBBELL), stress = mapOf(KNEE to MEDIUM, HIP to MEDIUM, ANKLE to MEDIUM),
                loadType = LoadType.PER_HAND, unilateral = true, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.LUNGE, 1.05),
                cue = "Long stride, tall torso, smooth steps.",
            ),
        )
        add(
            Exercise(
                id = "step_up", name = "Step-Up", pattern = MovementPattern.LUNGE,
                roles = setOf(ACCESSORY), primary = setOf(QUADS, GLUTES), secondary = setOf(CALVES),
                equipment = setOf(DUMBBELL, BENCH), stress = mapOf(KNEE to MEDIUM, ANKLE to LOW),
                loadType = LoadType.PER_HAND, unilateral = true,
                strengthLink = StrengthLink(MovementPattern.LUNGE, 1.0),
                cue = "Whole foot on the box, stand up without pushing off the back leg.",
            ),
        )
        add(
            Exercise(
                id = "single_leg_rdl", name = "Single-Leg RDL", pattern = MovementPattern.HINGE,
                roles = setOf(ACCESSORY), primary = setOf(HAMSTRINGS, GLUTES), secondary = setOf(ABS, OBLIQUES),
                equipment = setOf(DUMBBELL), stress = mapOf(LOWER_BACK to LOW, HIP to MEDIUM, ANKLE to MEDIUM),
                loadType = LoadType.PER_HAND, unilateral = true,
                cue = "Hips square, reach long through the back heel.",
            ),
        )
        add(
            Exercise(
                id = "calf_raise", name = "Standing Calf Raise", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(CALVES),
                equipment = setOf(DUMBBELL), stress = mapOf(ANKLE to MEDIUM),
                loadType = LoadType.PER_HAND, cue = "Full stretch at the bottom, pause at the top.",
            ),
        )
        // ───────────── Horizontal push ─────────────
        add(
            Exercise(
                id = "bench_press", name = "Bench Press", pattern = MovementPattern.HORIZONTAL_PUSH,
                roles = setOf(MAIN), primary = setOf(CHEST, TRICEPS), secondary = setOf(FRONT_DELTS),
                equipment = setOf(BARBELL, BENCH, SQUAT_RACK), stress = mapOf(SHOULDER to HIGH, ELBOW to MEDIUM, WRIST to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PUSH, 1.0),
                cue = "Shoulder blades pinned, touch low chest, press back over the shoulders.",
            ),
        )
        add(
            Exercise(
                id = "incline_bench_press", name = "Incline Bench Press", pattern = MovementPattern.HORIZONTAL_PUSH,
                roles = setOf(MAIN, SECONDARY), primary = setOf(CHEST, FRONT_DELTS), secondary = setOf(TRICEPS),
                equipment = setOf(BARBELL, BENCH, SQUAT_RACK), stress = mapOf(SHOULDER to HIGH, ELBOW to MEDIUM, WRIST to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PUSH, 0.82),
                cue = "30° incline, bar to upper chest, elbows at 45°.",
            ),
        )
        add(
            Exercise(
                id = "db_bench_press", name = "Dumbbell Bench Press", pattern = MovementPattern.HORIZONTAL_PUSH,
                roles = setOf(MAIN, SECONDARY, ACCESSORY), primary = setOf(CHEST, TRICEPS), secondary = setOf(FRONT_DELTS),
                equipment = setOf(DUMBBELL, BENCH), stress = mapOf(SHOULDER to MEDIUM, ELBOW to LOW, WRIST to LOW),
                loadType = LoadType.PER_HAND, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PUSH, 0.38),
                cue = "Let the bells sink deep, press up and slightly in.",
            ),
        )
        add(
            Exercise(
                id = "machine_chest_press", name = "Machine Chest Press", pattern = MovementPattern.HORIZONTAL_PUSH,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(CHEST), secondary = setOf(TRICEPS, FRONT_DELTS),
                equipment = setOf(MACHINE), stress = mapOf(SHOULDER to LOW, ELBOW to LOW),
                loadType = LoadType.MACHINE, strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PUSH, 0.9),
                cue = "Seat so handles meet mid-chest, smooth tempo.",
            ),
        )
        add(
            Exercise(
                id = "push_up", name = "Push-Up", pattern = MovementPattern.HORIZONTAL_PUSH,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(CHEST, TRICEPS), secondary = setOf(FRONT_DELTS, ABS),
                stress = mapOf(SHOULDER to MEDIUM, WRIST to MEDIUM, ELBOW to LOW),
                loadType = LoadType.BODYWEIGHT, bodyweightFraction = 0.65,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PUSH, 0.75),
                cue = "Plank tight, chest to the floor, elbows tucked.",
            ),
        )
        add(
            Exercise(
                id = "dip", name = "Dip", pattern = MovementPattern.HORIZONTAL_PUSH,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(CHEST, TRICEPS), secondary = setOf(FRONT_DELTS),
                equipment = setOf(DIP_STATION), stress = mapOf(SHOULDER to HIGH, ELBOW to MEDIUM),
                loadType = LoadType.BODYWEIGHT, bodyweightFraction = 0.92,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PUSH, 1.15),
                cue = "Slight forward lean, shoulders down, stop when shoulders reach elbows.",
            ),
        )
        // ───────────── Vertical push ─────────────
        add(
            Exercise(
                id = "overhead_press", name = "Overhead Press", pattern = MovementPattern.VERTICAL_PUSH,
                roles = setOf(MAIN, SECONDARY), primary = setOf(FRONT_DELTS, TRICEPS), secondary = setOf(SIDE_DELTS, U_BACK, ABS),
                equipment = setOf(BARBELL, SQUAT_RACK), stress = mapOf(SHOULDER to HIGH, LOWER_BACK to MEDIUM, ELBOW to MEDIUM, WRIST to MEDIUM),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.VERTICAL_PUSH, 1.0),
                cue = "Squeeze glutes, press the bar back over mid-foot, head through.",
            ),
        )
        add(
            Exercise(
                id = "db_shoulder_press", name = "Dumbbell Shoulder Press", pattern = MovementPattern.VERTICAL_PUSH,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(FRONT_DELTS, TRICEPS), secondary = setOf(SIDE_DELTS),
                equipment = setOf(DUMBBELL, BENCH), stress = mapOf(SHOULDER to MEDIUM, ELBOW to LOW),
                loadType = LoadType.PER_HAND, strengthLink = StrengthLink(MovementPattern.VERTICAL_PUSH, 0.4),
                cue = "Neutral-ish grip, press up without flaring the ribs.",
            ),
        )
        add(
            Exercise(
                id = "landmine_press", name = "Landmine Press", pattern = MovementPattern.VERTICAL_PUSH,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(FRONT_DELTS, CHEST), secondary = setOf(TRICEPS, ABS, OBLIQUES),
                equipment = setOf(BARBELL), stress = mapOf(SHOULDER to LOW, ELBOW to LOW),
                loadType = LoadType.BARBELL, unilateral = true,
                strengthLink = StrengthLink(MovementPattern.VERTICAL_PUSH, 0.6),
                cue = "Shoulder-friendly arc: press up and forward, reach at the top.",
            ),
        )
        add(
            Exercise(
                id = "lateral_raise", name = "Lateral Raise", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(SIDE_DELTS),
                equipment = setOf(DUMBBELL), stress = mapOf(SHOULDER to MEDIUM),
                loadType = LoadType.PER_HAND, cue = "Lead with the elbows, stop at shoulder height.",
            ),
        )
        // ───────────── Horizontal pull ─────────────
        add(
            Exercise(
                id = "barbell_row", name = "Barbell Row", pattern = MovementPattern.HORIZONTAL_PULL,
                roles = setOf(MAIN, SECONDARY), primary = setOf(U_BACK, LATS), secondary = setOf(REAR_DELTS, BICEPS, L_BACK, FOREARMS),
                equipment = setOf(BARBELL), stress = mapOf(LOWER_BACK to HIGH, ELBOW to LOW),
                loadType = LoadType.BARBELL, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PULL, 1.0),
                cue = "Hinge to 45°, pull to the belly button, pause.",
            ),
        )
        add(
            Exercise(
                id = "chest_supported_row", name = "Chest-Supported Row", pattern = MovementPattern.HORIZONTAL_PULL,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(U_BACK, LATS), secondary = setOf(REAR_DELTS, BICEPS),
                equipment = setOf(DUMBBELL, BENCH), stress = mapOf(ELBOW to LOW),
                loadType = LoadType.PER_HAND, strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PULL, 0.42),
                cue = "Chest on the incline bench, drive elbows back, squeeze shoulder blades.",
            ),
        )
        add(
            Exercise(
                id = "db_row", name = "One-Arm Dumbbell Row", pattern = MovementPattern.HORIZONTAL_PULL,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(LATS, U_BACK), secondary = setOf(REAR_DELTS, BICEPS, FOREARMS),
                equipment = setOf(DUMBBELL, BENCH), stress = mapOf(LOWER_BACK to LOW, ELBOW to LOW),
                loadType = LoadType.PER_HAND, unilateral = true,
                strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PULL, 0.5),
                cue = "Pull the dumbbell to your hip, not your armpit.",
            ),
        )
        add(
            Exercise(
                id = "seated_cable_row", name = "Seated Cable Row", pattern = MovementPattern.HORIZONTAL_PULL,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(U_BACK, LATS), secondary = setOf(BICEPS, REAR_DELTS),
                equipment = setOf(CABLE), stress = mapOf(LOWER_BACK to LOW, ELBOW to LOW),
                loadType = LoadType.MACHINE, strengthLink = StrengthLink(MovementPattern.HORIZONTAL_PULL, 0.95),
                cue = "Tall chest, pull handle to the ribs, control the return.",
            ),
        )
        add(
            Exercise(
                id = "face_pull", name = "Face Pull", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(REAR_DELTS, U_BACK),
                equipment = setOf(CABLE), stress = mapOf(SHOULDER to LOW),
                loadType = LoadType.MACHINE, cue = "Pull to eye level, thumbs back, elbows high.",
            ),
        )
        add(
            Exercise(
                id = "rear_delt_fly", name = "Rear Delt Fly", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(REAR_DELTS), secondary = setOf(U_BACK),
                equipment = setOf(DUMBBELL), stress = mapOf(SHOULDER to LOW),
                loadType = LoadType.PER_HAND, cue = "Hinge forward, sweep arms wide with soft elbows.",
            ),
        )
        // ───────────── Vertical pull ─────────────
        add(
            Exercise(
                id = "pull_up", name = "Pull-Up", pattern = MovementPattern.VERTICAL_PULL,
                roles = setOf(MAIN, SECONDARY), primary = setOf(LATS, U_BACK), secondary = setOf(BICEPS, FOREARMS, ABS),
                equipment = setOf(PULLUP_BAR), stress = mapOf(SHOULDER to MEDIUM, ELBOW to MEDIUM),
                loadType = LoadType.BODYWEIGHT, bodyweightFraction = 1.0, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.VERTICAL_PULL, 1.0),
                cue = "Dead hang, pull elbows to your back pockets, chin over the bar.",
            ),
        )
        add(
            Exercise(
                id = "chin_up", name = "Chin-Up", pattern = MovementPattern.VERTICAL_PULL,
                roles = setOf(MAIN, SECONDARY), primary = setOf(LATS, BICEPS), secondary = setOf(U_BACK, FOREARMS),
                equipment = setOf(PULLUP_BAR), stress = mapOf(SHOULDER to MEDIUM, ELBOW to MEDIUM),
                loadType = LoadType.BODYWEIGHT, bodyweightFraction = 1.0, systemicCost = SystemicCost.MEDIUM,
                strengthLink = StrengthLink(MovementPattern.VERTICAL_PULL, 1.05),
                cue = "Palms facing you, chest to the bar, full lockout below.",
            ),
        )
        add(
            Exercise(
                id = "lat_pulldown", name = "Lat Pulldown", pattern = MovementPattern.VERTICAL_PULL,
                roles = setOf(SECONDARY, ACCESSORY), primary = setOf(LATS), secondary = setOf(BICEPS, U_BACK),
                equipment = setOf(CABLE), stress = mapOf(SHOULDER to LOW, ELBOW to LOW),
                loadType = LoadType.MACHINE, strengthLink = StrengthLink(MovementPattern.VERTICAL_PULL, 0.8),
                cue = "Lean back slightly, pull bar to upper chest, elbows down and in.",
            ),
        )
        add(
            Exercise(
                id = "band_pulldown", name = "Banded Lat Pulldown", pattern = MovementPattern.VERTICAL_PULL,
                roles = setOf(ACCESSORY), primary = setOf(LATS), secondary = setOf(U_BACK, BICEPS),
                equipment = setOf(BANDS), stress = mapOf(SHOULDER to LOW),
                loadType = LoadType.TIMED, defaultReps = 15,
                cue = "Anchor high, drive elbows to your ribs, slow return.",
            ),
        )
        // ───────────── Arms ─────────────
        add(
            Exercise(
                id = "db_curl", name = "Dumbbell Curl", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(BICEPS), secondary = setOf(FOREARMS),
                equipment = setOf(DUMBBELL), stress = mapOf(ELBOW to LOW, WRIST to LOW),
                loadType = LoadType.PER_HAND, cue = "Elbows pinned, supinate as you curl.",
            ),
        )
        add(
            Exercise(
                id = "hammer_curl", name = "Hammer Curl", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(BICEPS, FOREARMS),
                equipment = setOf(DUMBBELL), stress = mapOf(ELBOW to LOW),
                loadType = LoadType.PER_HAND, cue = "Neutral grip, no swing.",
            ),
        )
        add(
            Exercise(
                id = "triceps_pushdown", name = "Triceps Pushdown", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(TRICEPS),
                equipment = setOf(CABLE), stress = mapOf(ELBOW to MEDIUM),
                loadType = LoadType.MACHINE, cue = "Elbows by your sides, full lockout.",
            ),
        )
        add(
            Exercise(
                id = "overhead_triceps_extension", name = "Overhead Triceps Extension", pattern = MovementPattern.ISOLATION,
                roles = setOf(ACCESSORY), primary = setOf(TRICEPS),
                equipment = setOf(DUMBBELL), stress = mapOf(ELBOW to MEDIUM, SHOULDER to MEDIUM),
                loadType = LoadType.PER_HAND, cue = "Elbows point up, deep stretch behind the head.",
            ),
        )
        // ───────────── Core & carries ─────────────
        add(
            Exercise(
                id = "farmer_carry", name = "Farmer Carry", pattern = MovementPattern.CARRY,
                roles = setOf(CORE, ACCESSORY), primary = setOf(FOREARMS, U_BACK), secondary = setOf(ABS, OBLIQUES, Muscle.GLUTES),
                equipment = setOf(DUMBBELL), stress = mapOf(WRIST to MEDIUM, LOWER_BACK to LOW),
                loadType = LoadType.PER_HAND, strengthLink = StrengthLink(MovementPattern.CARRY, 1.0),
                defaultDurationSec = 40,
                cue = "Heavy bells, tall posture, short quick steps.",
            ),
        )
        add(
            Exercise(
                id = "suitcase_carry", name = "Suitcase Carry", pattern = MovementPattern.CARRY,
                roles = setOf(CORE), primary = setOf(OBLIQUES, FOREARMS), secondary = setOf(ABS),
                equipment = setOf(DUMBBELL), stress = mapOf(WRIST to MEDIUM),
                loadType = LoadType.PER_HAND, unilateral = true, strengthLink = StrengthLink(MovementPattern.CARRY, 0.75),
                defaultDurationSec = 30,
                cue = "One bell, don't lean — fight the tilt.",
            ),
        )
        add(
            Exercise(
                id = "plank", name = "Plank", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(ABS), secondary = setOf(OBLIQUES, FRONT_DELTS),
                stress = mapOf(SHOULDER to LOW, LOWER_BACK to LOW), loadType = LoadType.TIMED, defaultDurationSec = 45,
                cue = "Squeeze glutes, ribs down, push the floor away.",
            ),
        )
        add(
            Exercise(
                id = "side_plank", name = "Side Plank", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(OBLIQUES), secondary = setOf(ABS, Muscle.GLUTES),
                stress = mapOf(SHOULDER to MEDIUM), loadType = LoadType.TIMED, unilateral = true, defaultDurationSec = 30,
                cue = "Stack the hips, straight line from head to heels.",
            ),
        )
        add(
            Exercise(
                id = "dead_bug", name = "Dead Bug", pattern = MovementPattern.CORE,
                roles = setOf(CORE, ACTIVATION), primary = setOf(ABS), secondary = setOf(OBLIQUES),
                loadType = LoadType.TIMED, defaultReps = 10,
                cue = "Low back glued to the floor, exhale as you extend.",
            ),
        )
        add(
            Exercise(
                id = "pallof_press", name = "Pallof Press", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(OBLIQUES, ABS),
                equipment = setOf(CABLE), loadType = LoadType.MACHINE, unilateral = true, defaultReps = 10,
                cue = "Press straight out and resist the rotation.",
            ),
        )
        add(
            Exercise(
                id = "hanging_knee_raise", name = "Hanging Knee Raise", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(ABS), secondary = setOf(FOREARMS),
                equipment = setOf(PULLUP_BAR), stress = mapOf(SHOULDER to MEDIUM),
                loadType = LoadType.TIMED, defaultReps = 12,
                cue = "Curl the pelvis up, no swinging.",
            ),
        )
        add(
            Exercise(
                id = "ab_wheel", name = "Ab Wheel Rollout", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(ABS), secondary = setOf(LATS, OBLIQUES),
                stress = mapOf(LOWER_BACK to MEDIUM, SHOULDER to MEDIUM), loadType = LoadType.TIMED, defaultReps = 8,
                cue = "Hollow body, roll only as far as you can stay tucked.",
            ),
        )
        add(
            Exercise(
                id = "cable_woodchop", name = "Cable Woodchop", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(OBLIQUES), secondary = setOf(ABS, Muscle.GLUTES),
                equipment = setOf(CABLE), stress = mapOf(LOWER_BACK to LOW), loadType = LoadType.MACHINE,
                unilateral = true, defaultReps = 10, cue = "Rotate from the hips, arms just guide the cable.",
            ),
        )
        add(
            Exercise(
                id = "copenhagen_plank", name = "Copenhagen Plank", pattern = MovementPattern.CORE,
                roles = setOf(CORE), primary = setOf(ADDUCTORS), secondary = setOf(OBLIQUES),
                equipment = setOf(BENCH), stress = mapOf(KNEE to MEDIUM, HIP to MEDIUM), loadType = LoadType.TIMED,
                unilateral = true, defaultDurationSec = 20, cue = "Top leg on the bench, lift hips into a straight line.",
            ),
        )
        // ───────────── Warm-up cardio ─────────────
        add(timed("bike_easy", "Easy Bike", WARMUP_CARDIO, setOf(QUADS, HAMSTRINGS), setOf(CARDIO_MACHINE), 300, "Easy spin, nasal breathing, cadence 80–90."))
        add(timed("rower_easy", "Easy Row", WARMUP_CARDIO, setOf(LATS, QUADS, HAMSTRINGS), setOf(CARDIO_MACHINE), 300, "Legs, body, arms — smooth and relaxed.", mapOf(LOWER_BACK to LOW)))
        add(timed("jump_rope", "Jump Rope", WARMUP_CARDIO, setOf(CALVES), emptySet(), 180, "Light bounces on the balls of your feet.", mapOf(ANKLE to MEDIUM, KNEE to LOW)))
        add(timed("march_in_place", "Brisk March", WARMUP_CARDIO, setOf(QUADS, CALVES), emptySet(), 240, "Drive the knees, swing the arms, raise the heart rate."))
        // ───────────── Mobility ─────────────
        add(timed("cat_cow", "Cat–Cow", MOBILITY, setOf(L_BACK, U_BACK), emptySet(), 45, "Move one vertebra at a time with the breath.", reps = 8))
        add(timed("worlds_greatest_stretch", "World's Greatest Stretch", MOBILITY, setOf(Muscle.GLUTES, HAMSTRINGS, U_BACK), emptySet(), 60, "Lunge, elbow to instep, rotate and reach to the sky.", mapOf(HIP to LOW), reps = 5, unilateral = true))
        add(timed("hip_90_90", "90/90 Hip Switch", MOBILITY, setOf(Muscle.GLUTES, ADDUCTORS), emptySet(), 60, "Sit tall, rotate the knees side to side.", mapOf(HIP to LOW, KNEE to LOW), reps = 8))
        add(timed("leg_swings", "Leg Swings", MOBILITY, setOf(HAMSTRINGS, ADDUCTORS), emptySet(), 45, "Front-to-back then side-to-side, growing range.", reps = 10, unilateral = true))
        add(timed("ankle_rocks", "Knee-to-Wall Ankle Rocks", MOBILITY, setOf(CALVES), emptySet(), 45, "Drive the knee past the toes, heel down.", mapOf(ANKLE to LOW), reps = 10, unilateral = true))
        add(timed("thoracic_rotation", "Open Book Rotation", MOBILITY, setOf(U_BACK, CHEST), emptySet(), 45, "Side-lying, open the top arm and follow it with your eyes.", reps = 8, unilateral = true))
        add(timed("arm_circles", "Arm Circles", MOBILITY, setOf(Muscle.FRONT_DELTS, SIDE_DELTS, REAR_DELTS), emptySet(), 40, "Small to big circles, both directions.", mapOf(SHOULDER to LOW)))
        add(timed("band_dislocates", "Band Pass-Throughs", MOBILITY, setOf(Muscle.FRONT_DELTS, CHEST), setOf(BANDS), 45, "Wide grip, straight arms over and back.", mapOf(SHOULDER to MEDIUM), reps = 10))
        add(timed("inchworm", "Inchworm", MOBILITY, setOf(HAMSTRINGS, ABS, CHEST), emptySet(), 60, "Walk the hands out to plank, walk the feet in.", mapOf(WRIST to LOW, SHOULDER to LOW), reps = 5))
        add(timed("deep_squat_hold", "Deep Squat Hold", MOBILITY, setOf(Muscle.GLUTES, ADDUCTORS, CALVES), emptySet(), 45, "Sit into the bottom, elbows prying the knees out.", mapOf(KNEE to MEDIUM, HIP to LOW)))
        add(timed("neck_cars", "Neck CARs", MOBILITY, setOf(U_BACK), emptySet(), 30, "Slow controlled circles, no forcing.", mapOf(NECK to LOW), reps = 3))
        // ───────────── Activation ─────────────
        add(timed("glute_bridge", "Glute Bridge", ACTIVATION, setOf(Muscle.GLUTES), emptySet(), 40, "Drive through the heels, squeeze at the top for two seconds.", reps = 12))
        add(timed("band_pull_apart", "Band Pull-Apart", ACTIVATION, setOf(REAR_DELTS, U_BACK), setOf(BANDS), 40, "Straight arms, pull the band to the chest.", mapOf(SHOULDER to LOW), reps = 15))
        add(timed("scap_push_up", "Scap Push-Up", ACTIVATION, setOf(Muscle.UPPER_BACK, Muscle.FRONT_DELTS), emptySet(), 40, "Arms locked, let the chest sink then push the floor away.", mapOf(WRIST to LOW, SHOULDER to LOW), reps = 10))
        add(timed("bodyweight_squat", "Bodyweight Squat", ACTIVATION, setOf(QUADS, Muscle.GLUTES), emptySet(), 40, "Smooth reps through full range.", mapOf(KNEE to LOW), reps = 10))
        add(timed("monster_walk", "Banded Monster Walk", ACTIVATION, setOf(Muscle.GLUTES), setOf(BANDS), 40, "Band above knees, small steps, stay low.", mapOf(KNEE to LOW), reps = 12))
        // ───────────── Stretches ─────────────
        add(timed("couch_stretch", "Couch Stretch", STRETCH, setOf(QUADS), emptySet(), 60, "Rear knee to the wall, squeeze the glute, tall torso.", mapOf(KNEE to MEDIUM), unilateral = true))
        add(timed("pigeon_stretch", "Pigeon Stretch", STRETCH, setOf(Muscle.GLUTES), emptySet(), 60, "Square the hips and melt forward.", mapOf(KNEE to MEDIUM, HIP to MEDIUM), unilateral = true))
        add(timed("figure_four", "Supine Figure-Four", STRETCH, setOf(Muscle.GLUTES), emptySet(), 60, "Lying on your back, pull the crossed leg in gently.", mapOf(HIP to LOW), unilateral = true))
        add(timed("hamstring_stretch", "Supine Hamstring Stretch", STRETCH, setOf(HAMSTRINGS, CALVES), emptySet(), 60, "Strap or band around the foot, straight knee.", unilateral = true))
        add(timed("adductor_rockback", "Adductor Rock-Back", STRETCH, setOf(ADDUCTORS), emptySet(), 45, "Kneel with one leg out wide, sit hips back.", mapOf(KNEE to LOW, HIP to LOW), unilateral = true))
        add(timed("calf_stretch", "Wall Calf Stretch", STRETCH, setOf(CALVES), emptySet(), 45, "Back heel down, knee straight then bent.", unilateral = true))
        add(timed("doorway_pec_stretch", "Doorway Pec Stretch", STRETCH, setOf(CHEST, Muscle.FRONT_DELTS), emptySet(), 45, "Forearm on the frame, step through gently.", mapOf(SHOULDER to LOW), unilateral = true))
        add(timed("lat_stretch", "Child's Pose Lat Reach", STRETCH, setOf(LATS, L_BACK), emptySet(), 60, "Walk the hands to one side, breathe into the ribs."))
        add(timed("cross_body_shoulder", "Cross-Body Shoulder Stretch", STRETCH, setOf(REAR_DELTS, U_BACK), emptySet(), 40, "Pull the arm across, keep the shoulder down.", mapOf(SHOULDER to LOW), unilateral = true))
        add(timed("triceps_stretch", "Overhead Triceps Stretch", STRETCH, setOf(TRICEPS), emptySet(), 40, "Elbow up, hand down the spine.", mapOf(SHOULDER to LOW), unilateral = true))
        add(timed("biceps_wall_stretch", "Biceps Wall Stretch", STRETCH, setOf(BICEPS, FOREARMS), emptySet(), 40, "Palm on the wall behind you, rotate away.", mapOf(SHOULDER to LOW), unilateral = true))
        add(timed("childs_pose", "Child's Pose", STRETCH, setOf(L_BACK), emptySet(), 60, "Knees wide, sink the hips to the heels."))
        add(timed("supine_twist", "Supine Twist", STRETCH, setOf(OBLIQUES, L_BACK), emptySet(), 60, "Knees fall to one side, shoulders stay heavy.", unilateral = true))
        add(timed("cobra_stretch", "Cobra", STRETCH, setOf(ABS), emptySet(), 40, "Hips heavy, lift the chest gently.", mapOf(LOWER_BACK to LOW)))
        // ───────────── Cool-down ─────────────
        add(timed("easy_walk", "Easy Walk", COOLDOWN_CARDIO, setOf(CALVES), emptySet(), 240, "Slow walk, let the heart rate settle."))
        add(timed("bike_flush", "Bike Flush", COOLDOWN_CARDIO, setOf(QUADS), setOf(CARDIO_MACHINE), 240, "Very light resistance, spin it out."))
        add(timed("box_breathing", "Box Breathing", BREATHING, setOf(ABS), emptySet(), 120, "In 4, hold 4, out 4, hold 4."))
        add(timed("crocodile_breathing", "Crocodile Breathing", BREATHING, setOf(ABS, L_BACK), emptySet(), 90, "Face down, breathe into your lower back and sides."))
    }

    private fun timed(
        id: String,
        name: String,
        role: ExerciseRole,
        muscles: Set<Muscle>,
        equipment: Set<Equipment>,
        seconds: Int,
        cue: String,
        stress: Map<BodyRegion, Stress> = emptyMap(),
        reps: Int? = null,
        unilateral: Boolean = false,
    ) = Exercise(
        id = id,
        name = name,
        pattern = when (role) {
            WARMUP_CARDIO, COOLDOWN_CARDIO -> MovementPattern.CARDIO
            else -> MovementPattern.MOBILITY
        },
        roles = setOf(role),
        primary = muscles,
        equipment = equipment,
        stress = stress,
        loadType = LoadType.TIMED,
        cue = cue,
        defaultDurationSec = seconds,
        defaultReps = reps,
        unilateral = unilateral,
    )

    private val byId: Map<String, Exercise> = all.associateBy { it.id }

    init {
        check(byId.size == all.size) { "Duplicate exercise ids in library" }
    }

    operator fun get(id: String): Exercise? = byId[id]

    fun require(id: String): Exercise = byId[id] ?: error("Unknown exercise id: $id")

    fun withRole(role: ExerciseRole): List<Exercise> = all.filter { role in it.roles }

    /** The lift every other exercise in a pattern is related to for strength scoring. */
    val referenceLift: Map<MovementPattern, String> = mapOf(
        MovementPattern.SQUAT to "back_squat",
        MovementPattern.HINGE to "deadlift",
        MovementPattern.LUNGE to "bulgarian_split_squat",
        MovementPattern.HORIZONTAL_PUSH to "bench_press",
        MovementPattern.VERTICAL_PUSH to "overhead_press",
        MovementPattern.HORIZONTAL_PULL to "barbell_row",
        MovementPattern.VERTICAL_PULL to "pull_up",
        MovementPattern.CARRY to "farmer_carry",
    )

    /** The best static stretch for each muscle, used to build cool-downs. */
    val stretchFor: Map<Muscle, String> = mapOf(
        QUADS to "couch_stretch",
        Muscle.GLUTES to "figure_four",
        HAMSTRINGS to "hamstring_stretch",
        ADDUCTORS to "adductor_rockback",
        CALVES to "calf_stretch",
        CHEST to "doorway_pec_stretch",
        Muscle.FRONT_DELTS to "doorway_pec_stretch",
        SIDE_DELTS to "cross_body_shoulder",
        REAR_DELTS to "cross_body_shoulder",
        LATS to "lat_stretch",
        U_BACK to "cross_body_shoulder",
        L_BACK to "childs_pose",
        TRICEPS to "triceps_stretch",
        BICEPS to "biceps_wall_stretch",
        FOREARMS to "biceps_wall_stretch",
        ABS to "cobra_stretch",
        OBLIQUES to "supine_twist",
    )
}
