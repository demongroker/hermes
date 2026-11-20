package app.hermes.updater.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared design tokens — navy + gold.
 *
 * The same hexes are the reference for Vela, VideoDroid and JellyAmp; change them here first.
 * Gold is the accent, never a text colour on a light surface (contrast fails) — the light
 * scheme uses a darkened gold for anything that carries text or an icon.
 */
object HermesPalette {
  // surfaces
  val NavyBase = Color(0xFF0B1220)
  val NavyElevated = Color(0xFF111A2B)
  val NavyCard = Color(0xFF22314F)
  val NavyOutline = Color(0xFF2A3A57) // 1 dp card edge (dark)

  // accent
  val Gold = Color(0xFFD4AF37)
  val GoldPressed = Color(0xFFB8962E)
  val GoldOnLight = Color(0xFF7A5F14)
  val GoldContainerDark = Color(0xFF3A2E10)
  val GoldContainerLight = Color(0xFFF5E7BE)

  // text
  val TextOnNavy = Color(0xFFE6EDF7)
  val TextMutedOnNavy = Color(0xFFA9B6CC)
  val TextOnLight = Color(0xFF101828)
  val TextMutedOnLight = Color(0xFF48566E)

  // semantics — identical in every app
  val Success = Color(0xFF3FB950)
  val Warning = Color(0xFFD29922)
  val Danger = Color(0xFFFF7469)
  val SuccessOnLight = Color(0xFF1A7F37)
  val WarningOnLight = Color(0xFF9A6700)
  val DangerOnLight = Color(0xFFB3261E)
}

/** Semantic colours that Material's scheme has no slot for. */
data class HermesTones(
  val success: Color,
  val warning: Color,
  val danger: Color,
  val muted: Color,
)

private val DarkTones = HermesTones(
  success = HermesPalette.Success,
  warning = HermesPalette.Warning,
  danger = HermesPalette.Danger,
  muted = HermesPalette.TextMutedOnNavy,
)

private val LightTones = HermesTones(
  success = HermesPalette.SuccessOnLight,
  warning = HermesPalette.WarningOnLight,
  danger = HermesPalette.DangerOnLight,
  muted = HermesPalette.TextMutedOnLight,
)

val LocalHermesTones = staticCompositionLocalOf { DarkTones }

/**
 * 1 dp edge on every card. Dark = navy.outline `#2A3A57`; light = `#C7CFDB`.
 * Reads the scheme's `outline` slot so the token lives in one place per mode.
 */
val hermesCardBorder: BorderStroke
  @Composable get() = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

private val DarkScheme = darkColorScheme(
  primary = HermesPalette.Gold,
  onPrimary = HermesPalette.NavyBase,
  primaryContainer = HermesPalette.GoldContainerDark,
  onPrimaryContainer = Color(0xFFF0DFA8),
  secondary = Color(0xFF8FA3C8),
  onSecondary = HermesPalette.NavyBase,
  background = HermesPalette.NavyBase,
  onBackground = HermesPalette.TextOnNavy,
  surface = HermesPalette.NavyElevated,
  onSurface = HermesPalette.TextOnNavy,
  surfaceVariant = HermesPalette.NavyCard,
  onSurfaceVariant = HermesPalette.TextMutedOnNavy,
  error = HermesPalette.Danger,
  onError = HermesPalette.NavyBase,
  outline = HermesPalette.NavyOutline,
)

private val LightScheme = lightColorScheme(
  primary = HermesPalette.GoldOnLight,
  onPrimary = Color.White,
  primaryContainer = HermesPalette.GoldContainerLight,
  onPrimaryContainer = HermesPalette.GoldContainerDark,
  secondary = Color(0xFF41547A),
  onSecondary = Color.White,
  background = Color(0xFFF7F8FA),
  onBackground = HermesPalette.TextOnLight,
  surface = Color.White,
  onSurface = HermesPalette.TextOnLight,
  surfaceVariant = Color(0xFFEEF1F6),
  onSurfaceVariant = HermesPalette.TextMutedOnLight,
  error = HermesPalette.DangerOnLight,
  onError = Color.White,
  outline = Color(0xFFC7CFDB),
)

private val HermesShapes = Shapes(
  extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
  small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
  medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
  large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
  extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
)

private fun style(size: Int, weight: FontWeight = FontWeight.Normal, spacing: Double = 0.0) =
  TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = (size * 1.35).sp,
    letterSpacing = spacing.sp,
  )

private val HermesTypography = Typography(
  headlineMedium = style(28, FontWeight.SemiBold, (-0.2)),
  headlineSmall = style(24, FontWeight.SemiBold),
  titleMedium = style(18, FontWeight.SemiBold),
  titleSmall = style(16, FontWeight.SemiBold),
  bodyLarge = style(16),
  bodyMedium = style(14),
  bodySmall = style(12),
  labelLarge = style(14, FontWeight.SemiBold, 0.1),
  labelMedium = style(12, FontWeight.Medium, 0.2),
  labelSmall = style(11, FontWeight.Medium, 0.3),
)

@Composable
fun HermesTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  content: @Composable () -> Unit,
) {
  CompositionLocalProvider(LocalHermesTones provides if (darkTheme) DarkTones else LightTones) {
    MaterialTheme(
      colorScheme = if (darkTheme) DarkScheme else LightScheme,
      typography = HermesTypography,
      shapes = HermesShapes,
      content = content,
    )
  }
}
