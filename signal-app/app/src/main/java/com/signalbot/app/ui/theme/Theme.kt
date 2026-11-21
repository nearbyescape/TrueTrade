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
 * 7Teenlab palette, tuned for a quick check at dawn: forest greens, cream text, muga-silk gold for
 * anything you act on, tea-leaf green for longs and rhododendron for shorts.
 */
object Palette {
    val ForestNight = Color(0xFF0F241D)   // background
    val Canopy = Color(0xFF17362C)        // raised surfaces
    val Understory = Color(0xFF1F4537)    // pressed / selected surfaces
    val Moss = Color(0xFF2E5244)          // hairlines, empty leg slots, ladder track
    val Cream = Color(0xFFEFE6D0)         // primary text
    val Lichen = Color(0xFFA8B7A4)        // secondary text
    val Gold = Color(0xFFD6B25E)          // actions, selection
    val Long = Color(0xFF8CCB8F)          // tea leaf
    val Short = Color(0xFFE2667A)         // rhododendron
    val Warning = Color(0xFFE9A54B)

    fun direction(dir: String) = if (dir == "LONG") Long else Short
}

val Serif = FontFamily(
    Font(R.font.cormorant_medium, FontWeight.Medium),
    Font(R.font.cormorant_semibold, FontWeight.SemiBold),
)

/** Plex Sans figures are all the same width, so prices and stops line up in columns. */
val Sans = FontFamily(
    Font(R.font.plex_regular, FontWeight.Normal),
    Font(R.font.plex_medium, FontWeight.Medium),
    Font(R.font.plex_semibold, FontWeight.SemiBold),
)

object Type {
    /** The morning sentence: "Two orders for today's open." */
    val Headline = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 36.sp, lineHeight = 38.sp, letterSpacing = (-0.01).em)
    val Title = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 30.sp)
    /** Asset names. */
    val Name = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp)
    val Figure = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp)
    val BigFigure = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 52.sp, lineHeight = 54.sp)
    val Body = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val BodyStrong = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp)
    val Small = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val Tiny = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Palette.Gold,
        onPrimary = Palette.ForestNight,
        secondary = Palette.Long,
        background = Palette.ForestNight,
        onBackground = Palette.Cream,
        surface = Palette.ForestNight,
        onSurface = Palette.Cream,
        surfaceVariant = Palette.Canopy,
        onSurfaceVariant = Palette.Lichen,
        surfaceContainer = Palette.Canopy,
        outline = Palette.Moss,
        outlineVariant = Palette.Moss,
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
