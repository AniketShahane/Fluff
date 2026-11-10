package app.fluff.core

import app.fluff.core.Fixtures.input
import app.fluff.core.Fixtures.today
import app.fluff.core.ai.AiCoachContract
import app.fluff.core.ai.AiExercise
import app.fluff.core.ai.AiPlan
import app.fluff.core.ai.AiSet
import app.fluff.core.ai.PlanValidator
import app.fluff.core.ai.ValidationResult
import app.fluff.core.model.BlockType
import app.fluff.core.model.BodyRegion
import app.fluff.core.model.Limitation
import app.fluff.core.model.PlanAdjustments
import app.fluff.core.model.PlanSource
import app.fluff.core.model.SetKind
import app.fluff.core.model.Severity
import app.fluff.core.model.UserProfile
import app.fluff.core.planner.WorkoutPlanner
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class AiContractTest {
    private val profile = UserProfile(limitations = listOf(Limitation(BodyRegion.SHOULDER, Severity.MODERATE)), onboarded = true)
    private val inp = input(profile, readinessScore = 60)
    private val baseline = WorkoutPlanner().plan(inp)
    private val validator = PlanValidator(profile, inp.readiness, inp.history, today, baseline)

    private fun set(kind: String = "working", reps: Int = 8, dur: Int = 0, rpe: Double = 8.0) = AiSet(kind, reps, dur, rpe, 90)

    @Test fun schemaEnumeratesOnlyAllowedExercises() {
        val ids = AiCoachContract.allowedExercises(profile).map { it.id }
        assertThat(ids).doesNotContain("bench_press")
        val schema = AiCoachContract.outputSchema(ids)
        val enum = schema["properties"]!!.jsonObject["main"]!!.jsonObject["items"]!!.jsonObject["properties"]!!.jsonObject["exercise_id"]!!.jsonObject["enum"]!!.jsonArray
        assertThat(enum.map { it.jsonPrimitive.content }).containsExactlyElementsIn(ids)
        assertThat(schema["additionalProperties"]!!.jsonPrimitive.content).isEqualTo("false")
    }

    @Test fun contextIsValidJsonWithKeyFields() {
        val ctx = AiCoachContract.buildContext(today, profile, inp.readiness, inp.freshness, inp.history, emptyList(), baseline, PlanAdjustments(), Fixtures.zone)
        val obj = AiCoachContract.json.parseToJsonElement(ctx).jsonObject
        assertThat(obj.keys).containsAtLeast("readiness", "athlete", "allowed_exercise_ids", "baseline_plan", "muscle_freshness")
        assertThat(ctx).contains("moderate shoulder")
    }

    @Test fun parsesAndValidatesAPlan() {
        val raw = """
        {"title":"Lower body","focus":"LOWER","rationale":["Readiness is steady."],"coach_note":"Smooth reps today.",
         "warmup":[{"exercise_id":"bike_easy","sets":[{"kind":"warmup","reps":0,"duration_sec":240,"rpe":0,"rest_sec":0}],"note":""}],
         "main":[
           {"exercise_id":"leg_curl","sets":[{"kind":"working","reps":12,"duration_sec":0,"rpe":8,"rest_sec":60}],"note":""},
           {"exercise_id":"back_squat","sets":[{"kind":"working","reps":5,"duration_sec":0,"rpe":9.5,"rest_sec":180},{"kind":"working","reps":5,"duration_sec":0,"rpe":8,"rest_sec":180}],"note":"Brace"},
           {"exercise_id":"bench_press","sets":[{"kind":"working","reps":5,"duration_sec":0,"rpe":8,"rest_sec":180}],"note":""},
           {"exercise_id":"made_up","sets":[{"kind":"working","reps":5,"duration_sec":0,"rpe":8,"rest_sec":180}],"note":""},
           {"exercise_id":"plank","sets":[{"kind":"working","reps":0,"duration_sec":45,"rpe":0,"rest_sec":60}],"note":""}
         ],
         "cooldown":[]}
        """.trimIndent()
        val ai = AiCoachContract.json.decodeFromString(AiPlan.serializer(), raw)
        val result = validator.validate(ai) as ValidationResult.Valid
        val plan = result.plan
        assertThat(plan.source).isEqualTo(PlanSource.AI)
        val ids = plan.mainExercises.map { it.exerciseId }
        assertThat(ids).containsExactly("back_squat", "leg_curl", "plank").inOrder() // compound first, unsafe/unknown dropped
        val squatSets = plan.mainExercises.first().sets
        assertThat(squatSets.first().kind).isEqualTo(SetKind.WARMUP) // ramp added
        squatSets.mapNotNull { it.rpe }.forEach { assertThat(it).isAtMost(inp.readiness.prescription.rpeCap) }
        assertThat(squatSets.last().weightKg).isNotNull()
        val plank = plan.mainExercises.last().sets.single()
        assertThat(plank.durationSec).isEqualTo(45)
        assertThat(plank.reps).isNull()
        assertThat(plan.block(BlockType.COOLDOWN)!!.exercises).isNotEmpty() // fell back to engine cool-down
        assertThat(result.warnings.joinToString()).contains("Bench Press")
        assertThat(result.warnings.joinToString()).contains("made_up")
    }

    @Test fun rejectsEmptyMain() {
        val ai = AiPlan("x", "LOWER", emptyList(), "", emptyList(), listOf(AiExercise("bench_press", listOf(set()), "")), emptyList())
        assertThat(validator.validate(ai)).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test fun rejectsAbsurdVolume() {
        val ai = AiPlan("x", "LOWER", emptyList(), "", emptyList(), List(6) { i -> AiExercise(listOf("back_squat", "deadlift", "leg_press", "leg_curl", "leg_extension", "calf_raise")[i], List(8) { set() }, "") }, emptyList())
        assertThat(validator.validate(ai)).isInstanceOf(ValidationResult.Invalid::class.java)
    }
}
