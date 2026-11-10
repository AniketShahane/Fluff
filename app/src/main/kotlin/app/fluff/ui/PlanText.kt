package app.fluff.ui

import app.fluff.core.importer.FitbodExercises
import app.fluff.core.library.ExerciseLibrary
import app.fluff.core.model.PlannedExercise
import app.fluff.core.model.SetKind
import app.fluff.core.model.WeightUnit

object PlanText {
    fun name(id: String): String = ExerciseLibrary[id]?.name
        ?: id.removePrefix(FitbodExercises.HISTORY_ONLY_PREFIX).takeIf { it != id }
        ?: id.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** "3 × 8 · 60 kg · RPE 8", "+2 ramp", or "2 × 30s" for drills. */
    fun summary(pe: PlannedExercise, unit: WeightUnit): String {
        val work = pe.sets.filter { it.kind != SetKind.WARMUP }
        val ramps = pe.sets.size - work.size
        val main = work.ifEmpty { pe.sets }
        if (main.isEmpty()) return ""
        val first = main.first()
        val count = main.size
        val dose = when {
            first.durationSec != null -> Fmt.duration(first.durationSec!!)
            first.reps != null -> {
                val reps = main.mapNotNull { it.reps }.distinct()
                if (reps.size == 1) "${reps[0]}" else "${reps.min()}–${reps.max()}"
            }
            else -> ""
        }
        val parts = mutableListOf(if (count > 1) "$count × $dose" else dose)
        val top = main.mapNotNull { it.weightKg }.maxOrNull()
        if (top != null && top > 0) parts += Fmt.weightWithUnit(top, unit)
        main.mapNotNull { it.rpe }.maxOrNull()?.let { parts += "RPE ${Fmt.rpe(it)}" }
        if (ramps > 0) parts += "+$ramps ramp"
        return parts.filter { it.isNotBlank() }.joinToString(" · ")
    }
}
