package app.fluff.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fluff.R
import app.fluff.core.readiness.ReadinessBand

/** Fluff is dark-first: a calm night canvas where the readiness colour does the talking. */
object Fluff {
    val Night = Color(0xFF0B0D12)
    val Ink = Color(0xFF12151C)
    val Card = Color(0xFF171B24)
    val CardHigh = Color(0xFF1F2430)
    val Line = Color(0xFF2A3040)
    val Text = Color(0xFFF2F4F8)
    val TextDim = Color(0xFFA3AAB9)
    val TextFaint = Color(0xFF6B7385)

    val Lime = Color(0xFFC8F560)
    val Mint = Color(0xFF5BE49B)
    val Amber = Color(0xFFF5C84B)
    val Coral = Color(0xFFFF9A55)
    val Rose = Color(0xFFFF6B8B)
    val Violet = Color(0xFF9D8CFF)
    val Sky = Color(0xFF5CC8FF)

    fun band(b: ReadinessBand): Color = when (b) {
        ReadinessBand.PRIME -> Mint
        ReadinessBand.READY -> Lime
        ReadinessBand.STEADY -> Amber
        ReadinessBand.LOW -> Coral
        ReadinessBand.RECOVER -> Rose
    }

    /** A second, cooler stop for gradients on the readiness colour. */
    fun bandDeep(b: ReadinessBand): Color = when (b) {
        ReadinessBand.PRIME -> Color(0xFF1FB5A0)
        ReadinessBand.READY -> Color(0xFF4CC97A)
        ReadinessBand.STEADY -> Color(0xFFF08A3C)
        ReadinessBand.LOW -> Color(0xFFE85A5A)
        ReadinessBand.RECOVER -> Color(0xFF9D5CFF)
    }

    /** Emphasised easing used for entrances, per Material motion. */
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> bouncy() = spring<T>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
    fun <T> gentle() = spring<T>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessLow)
}

@Immutable
data class FluffType(
    /** The readiness score: huge, condensed, tabular. */
    val hero: TextStyle,
    /** Stat values (weights, minutes, scores). */
    val number: TextStyle,
    val numberSmall: TextStyle,
    /** Small all-caps section labels. */
    val eyebrow: TextStyle,
    /** Live counters (rest timer, elapsed time) that must not jitter as digits change. */
    val timer: TextStyle,
)

val LocalFluffType = staticCompositionLocalOf<FluffType> { error("FluffTheme missing") }

/*
 * Type system
 * - Inter for everything you read: designed for screens, with tabular figures and an optical-size
 *   axis (text cut for body copy, display cut for headings).
 * - Archivo for everything you glance at: condensed heavy numbers and confident headings, so a
 *   weight or a score reads from arm's length mid-set.
 * Both are variable fonts bundled with the app (SIL Open Font License, see assets/licenses).
 */
private fun inter(weight: Int, opsz: Float) = Font(
    R.font.inter,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.Setting("opsz", opsz)),
)

private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
)

/** Inter's text cut, for body copy and labels. */
internal val InterText = FontFamily(inter(400, 14f), inter(500, 14f), inter(600, 14f), inter(700, 14f))

/** Inter's display cut: tighter spacing and finer details for titles. */
private val InterDisplay = FontFamily(inter(500, 28f), inter(600, 28f), inter(700, 28f), inter(800, 28f))

/** Archivo at normal width for headings. */
private val ArchivoHeading = FontFamily(archivo(600, 100f), archivo(700, 100f), archivo(800, 100f))

/** Archivo semi-condensed for numbers and the hero score. */
internal val ArchivoNumeric = FontFamily(archivo(600, 82f), archivo(700, 82f), archivo(800, 82f), archivo(900, 82f))

private const val TABULAR = "tnum"

private val typography = Typography(
    displayLarge = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 58.sp, letterSpacing = (-1.2).sp, fontFeatureSettings = TABULAR),
    displayMedium = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.Black, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-0.8).sp, fontFeatureSettings = TABULAR),
    displaySmall = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.ExtraBold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.5).sp, fontFeatureSettings = TABULAR),
    headlineLarge = TextStyle(fontFamily = ArchivoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp),
    headlineMedium = TextStyle(fontFamily = ArchivoHeading, fontWeight = FontWeight.ExtraBold, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp),
    headlineSmall = TextStyle(fontFamily = ArchivoHeading, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontFamily = InterDisplay, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.15).sp),
    titleSmall = TextStyle(fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = (-0.05).sp),
    bodyLarge = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = (-0.1).sp),
    bodyMedium = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = (-0.05).sp),
    bodySmall = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = InterText, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, fontFeatureSettings = TABULAR),
    labelMedium = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp, fontFeatureSettings = TABULAR),
    labelSmall = TextStyle(fontFamily = InterText, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.2.sp, fontFeatureSettings = TABULAR),
)

private val fluffType = FluffType(
    hero = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.Black, fontSize = 76.sp, lineHeight = 76.sp, letterSpacing = (-2).sp, fontFeatureSettings = TABULAR),
    number = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.4).sp, fontFeatureSettings = TABULAR),
    numberSmall = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.Bold, fontSize = 19.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp, fontFeatureSettings = TABULAR),
    eyebrow = TextStyle(fontFamily = ArchivoHeading, fontWeight = FontWeight.Bold, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.3.sp),
    timer = TextStyle(fontFamily = ArchivoNumeric, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 64.sp, letterSpacing = (-1).sp, fontFeatureSettings = TABULAR),
)

private val scheme = darkColorScheme(
    primary = Fluff.Lime,
    onPrimary = Color(0xFF15200A),
    primaryContainer = Color(0xFF2C3A12),
    onPrimaryContainer = Fluff.Lime,
    secondary = Fluff.Violet,
    onSecondary = Color(0xFF17123A),
    secondaryContainer = Color(0xFF2A2550),
    onSecondaryContainer = Color(0xFFDCD5FF),
    tertiary = Fluff.Sky,
    onTertiary = Color(0xFF00283A),
    background = Fluff.Night,
    onBackground = Fluff.Text,
    surface = Fluff.Night,
    onSurface = Fluff.Text,
    surfaceVariant = Fluff.Card,
    onSurfaceVariant = Fluff.TextDim,
    surfaceContainerLowest = Fluff.Night,
    surfaceContainerLow = Fluff.Ink,
    surfaceContainer = Fluff.Card,
    surfaceContainerHigh = Fluff.CardHigh,
    surfaceContainerHighest = Color(0xFF282E3C),
    outline = Fluff.Line,
    outlineVariant = Color(0xFF222733),
    error = Fluff.Rose,
    onError = Color(0xFF3A0010),
    inverseSurface = Fluff.Text,
    inverseOnSurface = Fluff.Night,
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun FluffTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalFluffType provides fluffType) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes) {
            // Every screen sits on the night canvas with light text, even outside a Surface.
            androidx.compose.material3.Surface(color = Fluff.Night, contentColor = Fluff.Text, content = content)
        }
    }
}

object FluffTheme {
    val type: FluffType @Composable get() = LocalFluffType.current
}
