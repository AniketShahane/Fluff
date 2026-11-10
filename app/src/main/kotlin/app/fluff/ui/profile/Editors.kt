package app.fluff.ui.profile

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.fluff.core.model.ActivityHabit
import app.fluff.core.model.ActivityType
import app.fluff.core.model.BodyRegion
import app.fluff.core.model.Equipment
import app.fluff.core.model.Experience
import app.fluff.core.model.Limitation
import app.fluff.core.model.Severity
import app.fluff.core.model.Sex
import app.fluff.core.model.TrainingGoal
import app.fluff.core.model.WeightUnit
import app.fluff.ui.components.Eyebrow
import app.fluff.ui.components.Pill
import app.fluff.ui.components.Stepper
import app.fluff.ui.theme.Fluff
import kotlin.math.roundToInt

/** A large selectable card with a title and blurb; the building block of most pickers. */
@Composable
fun ChoiceCard(title: String, blurb: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, leading: String? = null) {
    val bg by animateColorAsState(if (selected) Fluff.Lime.copy(alpha = 0.14f) else Fluff.Card, label = "choice")
    val border by animateColorAsState(if (selected) Fluff.Lime else Color.Transparent, label = "choiceBorder")
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(bg).border(1.5.dp, border, RoundedCornerShape(20.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { Text(leading, style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (blurb != null) Text(blurb, style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        }
        if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = Fluff.Lime)
    }
}

@Composable
fun GoalPicker(value: TrainingGoal, onChange: (TrainingGoal) -> Unit) {
    val emoji = mapOf(TrainingGoal.STRENGTH to "🏋️", TrainingGoal.HYPERTROPHY to "💪", TrainingGoal.GENERAL to "🌿", TrainingGoal.ATHLETIC to "⚡")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TrainingGoal.entries.forEach { g -> ChoiceCard(g.label, g.blurb, g == value, { onChange(g) }, leading = emoji[g]) }
    }
}

@Composable
fun ExperiencePicker(value: Experience, onChange: (Experience) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Experience.entries.forEach { e -> ChoiceCard(e.label, e.blurb, e == value, { onChange(e) }) }
    }
}

@Composable
fun ScheduleEditor(days: Int, minutes: Int, onDays: (Int) -> Unit, onMinutes: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Stepper("$days", "days per week", { onDays((days - 1).coerceAtLeast(1)) }, { onDays((days + 1).coerceAtMost(7)) })
        Spacer(Modifier.height(20.dp))
        Stepper("$minutes", "minutes per session", { onMinutes((minutes - 5).coerceAtLeast(20)) }, { onMinutes((minutes + 5).coerceAtMost(120)) })
        Spacer(Modifier.height(10.dp))
        Text(
            when {
                days <= 2 -> "Full-body sessions so every muscle gets work."
                days <= 4 -> "Upper and lower days, alternating."
                else -> "Push, pull and legs rotation."
            },
            style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EquipmentEditor(value: Set<Equipment>, onChange: (Set<Equipment>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceCard("Full gym", "Barbells, racks, cables and machines", value == Equipment.FULL_GYM, { onChange(Equipment.FULL_GYM) }, leading = "🏟️")
        ChoiceCard("Home dumbbells", "Dumbbells, a bench, bands, pull-up bar", value == Equipment.HOME_DUMBBELLS, { onChange(Equipment.HOME_DUMBBELLS) }, leading = "🏠")
        ChoiceCard("Bodyweight", "Just you and maybe some bands", value == Equipment.BODYWEIGHT_ONLY, { onChange(Equipment.BODYWEIGHT_ONLY) }, leading = "🤸")
        Eyebrow("Or fine-tune", Modifier.padding(top = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Equipment.entries.forEach { e ->
                Pill(e.label, selected = e in value, onClick = { onChange(if (e in value) value - e else value + e) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActivitiesEditor(value: List<ActivityHabit>, onChange: (List<ActivityHabit>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActivityType.SELECTABLE.forEach { t ->
                val on = value.any { it.type == t }
                Pill("${t.emoji} ${t.label}", selected = on, onClick = {
                    onChange(if (on) value.filterNot { it.type == t } else value + ActivityHabit(t, 2))
                })
            }
        }
        value.forEach { h ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("${h.type.emoji} ${h.type.label}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Stepper("${h.sessionsPerWeek}×", "per week",
                    { onChange(value.map { if (it.type == h.type) it.copy(sessionsPerWeek = (it.sessionsPerWeek - 1).coerceAtLeast(1)) else it }) },
                    { onChange(value.map { if (it.type == h.type) it.copy(sessionsPerWeek = (it.sessionsPerWeek + 1).coerceAtMost(14)) else it }) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LimitationsEditor(value: List<Limitation>, onChange: (List<Limitation>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tap any area that's sore or injured. Fluff avoids loading it.", style = MaterialTheme.typography.bodyMedium, color = Fluff.TextDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BodyRegion.entries.forEach { r ->
                val lim = value.firstOrNull { it.region == r }
                Pill(
                    r.label + (lim?.let { " · ${it.severity.label}" } ?: ""),
                    selected = lim != null,
                    onClick = {
                        // Cycle: none → mild → moderate → severe → none.
                        val next = when (lim?.severity) { null -> Severity.MILD; Severity.MILD -> Severity.MODERATE; Severity.MODERATE -> Severity.SEVERE; Severity.SEVERE -> null }
                        onChange(value.filterNot { it.region == r } + listOfNotNull(next?.let { Limitation(r, it) }))
                    },
                )
            }
        }
        if (value.isNotEmpty()) Text("Tap again to change severity: mild, moderate, severe, off.", style = MaterialTheme.typography.bodySmall, color = Fluff.TextFaint)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BodyEditor(
    sex: Sex, bodyWeightKg: Double, unit: WeightUnit, birthYear: Int?,
    onSex: (Sex) -> Unit, onWeight: (Double) -> Unit, onUnit: (WeightUnit) -> Unit, onBirthYear: (Int?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { WeightUnit.entries.forEach { u -> Pill(u.label, selected = u == unit, onClick = { onUnit(u) }) } }
        val shown = unit.fromKg(bodyWeightKg).roundToInt()
        Stepper("$shown", "bodyweight (${unit.label})", { onWeight(unit.toKg((shown - 1).toDouble()).coerceIn(30.0, 300.0)) }, { onWeight(unit.toKg((shown + 1).toDouble()).coerceIn(30.0, 300.0)) })
        Stepper(birthYear?.toString() ?: "–", "birth year",
            { onBirthYear((birthYear ?: 1995) - 1) }, { onBirthYear(((birthYear ?: 1995) + 1).coerceAtMost(java.time.Year.now().value - 13)) })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Eyebrow("Strength standards")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Sex.entries.forEach { s -> Pill(s.label, selected = s == sex, onClick = { onSex(s) }) } }
        }
    }
}
