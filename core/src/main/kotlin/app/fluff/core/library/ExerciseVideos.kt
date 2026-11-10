package app.fluff.core.library

import java.net.URLEncoder

/** A short YouTube form demo for one exercise. */
data class FormVideo(val youtubeId: String, val title: String, val channel: String) {
    val watchUrl: String get() = "https://www.youtube.com/shorts/$youtubeId"
}

/**
 * Hand-picked YouTube Shorts showing each strength and core exercise. Every ID was checked
 * against YouTube's oEmbed endpoint (2026-11-10): it exists, is embeddable, and the title and
 * channel below are what YouTube returned. Warm-up, mobility and stretch drills fall back to a
 * YouTube search, so a missing or removed video never leaves the user without a reference.
 */
object ExerciseVideos {

    private val videos: Map<String, FormVideo> = mapOf(
        "back_squat" to FormVideo("ZaSetOZFo-k", "How To Do A Barbell Back Squat", "The Physical Therapy and Wellness Channel"),
        "front_squat" to FormVideo("r6Z_h_WAX5o", "How To Do A Barbell Front Squat", "The Physical Therapy and Wellness Channel"),
        "goblet_squat" to FormVideo("qRDrhRgkjsg", "How To Do a Goblet Squat With Correct Form", "Fit Father Project"),
        "leg_press" to FormVideo("EotSw18oR9w", "The PERFECT Leg Press", "Andrew Kwong (DeltaBolic)"),
        "leg_extension" to FormVideo("CX-1zi9C-xc", "How to Do Leg Extensions Correctly", "1moRep"),
        "deadlift" to FormVideo("ZaTM37cfiDs", "How To Deadlift With Perfect Technique (Step By Step)", "Jeff Nippard"),
        "trap_bar_deadlift" to FormVideo("kpyCkyVIxjI", "How to Do a Trap Bar Deadlift", "Phil Daru"),
        "romanian_deadlift" to FormVideo("LrCDwGBIbY4", "How to Do a Romanian Deadlift", "Duke Health and Fitness Center"),
        "db_romanian_deadlift" to FormVideo("6i7bdN3b94I", "How To Do a Dumbbell Romanian Deadlift", "Peak Functional"),
        "bench_press" to FormVideo("Yd-YhigoY2g", "How To Do A Bench Press", "The Physical Therapy and Wellness Channel"),
        "lat_pulldown" to FormVideo("lmatGLlm1qs", "How to Do Lat Pulldown", "Andrew Kwong (DeltaBolic)"),
        "hip_thrust" to FormVideo("RBK2ILhl58c", "Barbell Hip Thrust - Under 60s How-to", "Chris Schaum"),
        "leg_curl" to FormVideo("d6sg829PgNs", "Lying Leg Curl Machine Tutorial", "KevTheTrainer"),
        "back_extension" to FormVideo("NtJ2K4rf_TE", "Hyperextension Glute and Hamstring Focus", "AMR Physiotherapy"),
        "bulgarian_split_squat" to FormVideo("A3ctWjao8cc", "The PERFECT Bulgarian Split Squat (5 Steps)", "Jeremy Ethier"),
        "reverse_lunge" to FormVideo("ufjvjxrGyFM", "How to Perform a Reverse Lunge (Proper Form)", "DEYOFIT"),
        "walking_lunge" to FormVideo("5smjqy11Ibc", "Walking Lunge: How to Do It with Proper Form", "NeuStrength"),
        "step_up" to FormVideo("9day6RhW8XA", "How To Perform Step Ups", "Move With Us"),
        "single_leg_rdl" to FormVideo("P6LVXX5iv5Q", "Supported Single-Leg Romanian Deadlift (RDL)", "Girls Gone Strong"),
        "kettlebell_swing" to FormVideo("Zx1SBOIdkQg", "How to do a Kettlebell Swing", "Move With Us"),
        "calf_raise" to FormVideo("hzugds_d2Hk", "How To Do Calf Raises | Correct Form", "Calixpert"),
        "incline_bench_press" to FormVideo("I3WjUUi_IOY", "How To Do The Incline Bench Press", "Mind Pump TV"),
        "db_bench_press" to FormVideo("SidmT09GXz8", "Flat Dumbbell Bench Press - Under 60s How-to", "Chris Schaum"),
        "machine_chest_press" to FormVideo("JsQd_KYl4w8", "How to - Machine Chest Press", "Jeremy Sry"),
        "push_up" to FormVideo("A-2re9hs990", "How to Do a Perfect Push-Up: Tutorial", "Nick Tumminello"),
        "dip" to FormVideo("zTLMDenPbJ0", "How to Do Dips Correctly", "Shredded Health & Performance"),
        "overhead_press" to FormVideo("4LBVP2Oe7fg", "The PERFECT Barbell Overhead Press", "Andrew Kwong (DeltaBolic)"),
        "db_shoulder_press" to FormVideo("k6tzKisR3NY", "The PERFECT Dumbbell Shoulder Press", "Andrew Kwong (DeltaBolic)"),
        "barbell_row" to FormVideo("Nqh7q3zDCoQ", "The PERFECT Barbell Row (5 Steps)", "Jeremy Ethier"),
        "landmine_press" to FormVideo("ZRMaaKBtyvY", "Standing Landmine Shoulder Press", "Mark Ottobre"),
        "lateral_raise" to FormVideo("lMYs7FY8os4", "The PERFECT Lateral Raise", "Andrew Kwong (DeltaBolic)"),
        "chest_supported_row" to FormVideo("2ByilQ4NaAs", "The PERFECT Chest-Supported Dumbbell Rows", "Andrew Kwong (DeltaBolic)"),
        "db_row" to FormVideo("Z1Je4hIcCz0", "How to Master the Single-Arm Dumbbell Row", "Life Fitness Australia"),
        "seated_cable_row" to FormVideo("wAU8VdcRAMQ", "The PERFECT Cable Row", "Andrew Kwong (DeltaBolic)"),
        "rear_delt_fly" to FormVideo("LsT-bR_zxLo", "The PERFECT Dumbbell Rear Delt Fly", "Andrew Kwong (DeltaBolic)"),
        "pull_up" to FormVideo("ZPG8OsHKXLw", "The PERFECT Pull-Up (5 Steps)", "Jeremy Ethier"),
        "chin_up" to FormVideo("ykPUIEj4-W0", "How To Do Chin-Ups Properly (Step-by-Step Tutorial)", "John Goodman"),
        "band_pulldown" to FormVideo("nmY4MTSFln8", "Lat Pulldown with Resistance Band", "Digg Deep Fitness & Nutrition"),
        "face_pull" to FormVideo("yj1w-EPj9tI", "Face Pulls: The BEST Upper Back Exercise Tutorial", "Clench Fitness"),
        "db_curl" to FormVideo("MKWBV29S6c0", "PERFECT Bicep Curl Form Tips", "Andrew Kwong (DeltaBolic)"),
        "hammer_curl" to FormVideo("3YYRmJ3RBj0", "How to Do Hammer Curls (Proper Form Guide)", "Patrick Frost"),
        "triceps_pushdown" to FormVideo("-PqzEk57xiw", "The PERFECT Triceps Pushdown", "Andrew Kwong (DeltaBolic)"),
        "overhead_triceps_extension" to FormVideo("b_r_LW4HEcM", "The PERFECT Overhead DB Tricep Extension", "Andrew Kwong (DeltaBolic)"),
        "farmer_carry" to FormVideo("XPYXSwaXzCo", "Step-by-Step Farmer's Walk Checklist", "SET FOR SET"),
        "suitcase_carry" to FormVideo("UFV61B5kwW8", "How to PROPERLY Do A Suitcase Carry", "Colossus Fitness"),
        "plank" to FormVideo("nVIeqNLGLa0", "Form Review: How to Do a Proper Plank", "FitnessBlender"),
        "side_plank" to FormVideo("sH5PiIUjDW8", "Side Plank Correct Technique", "VIGEO"),
        "dead_bug" to FormVideo("XcYtWYMz39w", "How to Do the Dead Bug Exercise (Correct Form for Beginners)", "Teddy Blackston"),
        "pallof_press" to FormVideo("fMAx57Oct1I", "How To Do a Pallof Press", "Peak Functional"),
        "hanging_knee_raise" to FormVideo("IHOFHNWn0_M", "How To Do Hanging Knee Raises | Correct Form", "Calixpert"),
        "ab_wheel" to FormVideo("esgE7M-YSyE", "Ab Wheel Rollout Exercise", "PTLDN"),
        "cable_woodchop" to FormVideo("umKKKlD6fhk", "How to do a High to Low Cable Woodchopper", "Central Fitness Online"),
        "copenhagen_plank" to FormVideo("DJk2fmMiUCk", "Copenhagen Plank Progression: Beginner to Advanced", "Mobelity Fitness"),
    )

    operator fun get(exerciseId: String): FormVideo? = videos[exerciseId]

    val all: Map<String, FormVideo> get() = videos

    /** A YouTube search for a form demo, for exercises without a curated video. */
    fun searchUrl(exerciseName: String): String =
        "https://www.youtube.com/results?search_query=" + URLEncoder.encode("$exerciseName form shorts", "UTF-8")
}
