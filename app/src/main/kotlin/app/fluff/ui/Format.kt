package app.fluff.ui

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.platform.LocalContext
import app.fluff.AppContainer
import app.fluff.FluffApp
import app.fluff.core.model.WeightUnit
import app.fluff.core.progress.Loads
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Builds a ViewModel from the app's container, scoped to the current navigation entry. */
@Composable
inline fun <reified VM : ViewModel> fluffViewModel(key: String? = null, crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as FluffApp).container
    return viewModel(key = key) { create(container) }
}

object Fmt {
    fun weight(kg: Double?, unit: WeightUnit): String = kg?.let { Loads.format(it, unit) } ?: "–"
    // Non-breaking space: a number and its unit never wrap apart ("150" / "kg").
    fun weightWithUnit(kg: Double?, unit: WeightUnit): String = kg?.let { "${Loads.format(it, unit)}\u00A0${unit.label}" } ?: "Bodyweight"

    fun bigWeight(kg: Double, unit: WeightUnit): String {
        val v = unit.fromKg(kg)
        return when {
            v >= 100_000 -> "${(v / 1000).roundToInt()}k"
            v >= 1_000 -> "%.1fk".format(Locale.US, v / 1000).replace(".0k", "k")
            else -> "%,d".format(Locale.US, v.roundToInt())
        }
    }

    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    fun duration(sec: Int): String = when {
        sec < 60 -> "${sec}s"
        sec % 60 == 0 -> "${sec / 60} min"
        else -> "${sec / 60}:${"%02d".format(sec % 60)}"
    }

    fun minutes(min: Int): String = if (min >= 60) "${min / 60}h ${min % 60}m".replace(" 0m", "") else "$min\u00A0min"

    private val dayMonth = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy")
    fun day(d: LocalDate): String = d.format(dayMonth)
    fun month(d: LocalDate): String = d.format(monthYear)
    fun weekday(d: LocalDate): String = d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())

    fun relativeDay(d: LocalDate, today: LocalDate): String = when (Duration.between(d.atStartOfDay(), today.atStartOfDay()).toDays()) {
        0L -> "Today"
        1L -> "Yesterday"
        in 2..6 -> d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
        else -> day(d)
    }

    fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
        in 4..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        in 17..21 -> "Good evening"
        else -> "Late one"
    }

    fun rpe(r: Double): String = if (r % 1.0 == 0.0) r.toInt().toString() else r.toString()
}
