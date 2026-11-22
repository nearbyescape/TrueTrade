package com.signalbot.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.signalbot.app.R

/**
 * Midnight and saffron: a deep navy night for checking at dawn, saffron for anything you act on,
 * mint for longs and coral for shorts.
 */
object Palette {
    val Night = Color(0xFF0D1326)     // background
    val Card = Color(0xFF161F3A)      // raised surfaces
    val Raised = Color(0xFF202B4D)    // pressed / selected surfaces
    val Line = Color(0xFF2C3A5E)      // hairlines, empty leg slots, ladder track
    val Ink = Color(0xFFF4F6FB)       // primary text
    val Muted = Color(0xFF9AA6C2)     // secondary text
    val Saffron = Color(0xFFF2A541)   // actions, selection
    val Long = Color(0xFF4FD1A5)      // mint
    val Short = Color(0xFFFF6B7A)     // coral
    val Warning = Color(0xFFFFD166)

    fun direction(dir: String) = if (dir == "LONG") Long else Short
}

/** Space Grotesk for headings, asset names and the big numbers. */
val Display = FontFamily(
    Font(R.font.grotesk_medium, FontWeight.Medium),
    Font(R.font.grotesk_semibold, FontWeight.SemiBold),
    Font(R.font.grotesk_bold, FontWeight.Bold),
)

/** Manrope for reading. Figures use tabular digits so prices and stops line up in columns. */
val Sans = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

private const val TNUM = "tnum"

object Type {
    /** The morning sentence: "Two orders for today's open." */
    val Headline = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp, letterSpacing = (-0.02).em)
    val Title = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em)
    /** Asset names. */
    val Name = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = (-0.01).em)
    val Figure = TextStyle(fontFamily = Display, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM)
    val BigFigure = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 52.sp, lineHeight = 56.sp, letterSpacing = (-0.03).em, fontFeatureSettings = TNUM)
    val Body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val BodyStrong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 22.sp)
    val Small = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp, fontFeatureSettings = TNUM)
    val Tiny = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = TNUM)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Saffron,
        onPrimary = Palette.Night,
        secondary = Palette.Long,
        background = Palette.Night,
        onBackground = Palette.Ink,
        surface = Palette.Night,
        onSurface = Palette.Ink,
        surfaceVariant = Palette.Card,
        onSurfaceVariant = Palette.Muted,
        surfaceContainer = Palette.Card,
        outline = Palette.Line,
        outlineVariant = Palette.Line,
        error = Palette.Short,
    )
    val typography = Typography(
        headlineSmall = Type.Title,
        titleMedium = Type.Name,
        bodyLarge = Type.Body,
        bodyMedium = Type.Body,
        bodySmall = Type.Small,
        labelLarge = Type.BodyStrong,
        labelMedium = Type.Small,
        labelSmall = Type.Tiny,
    )
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
