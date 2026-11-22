package com.signalbot.desk

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * "Night watch": a palette for screens that stay on for hours. Warm graphite instead of black, paper
 * instead of white, and long/short colours kept soft so the chart never glares. Saffron is only for
 * things to act on; everything else stays quiet.
 */
object Desk {
    val Base = Color(0xFF15181C)       // window background
    val Panel = Color(0xFF1B1F24)      // side panels, cards
    val Raised = Color(0xFF232830)     // hover, selected rows
    val Hairline = Color(0xFF2C323A)   // dividers, grid, empty slots
    val Paper = Color(0xFFD9D4CA)      // primary text
    val Dim = Color(0xFF8B8F94)        // secondary text
    val Faint = Color(0xFF5E636A)      // tertiary text, axis labels
    val Saffron = Color(0xFFE3A857)    // signals, selection, actions
    val Long = Color(0xFF6DB89B)       // up candles, longs, uptrend lines
    val Short = Color(0xFFD27C86)      // down candles, shorts, downtrend lines
    val Level = Color(0xFFBFA85E)      // support / resistance
    val Warn = Color(0xFFE0C36A)

    fun dir(d: String) = if (d == "LONG") Long else Short
}

private fun font(name: String, w: FontWeight) = Font("fonts/$name.ttf", w)

val Grotesk = FontFamily(font("grotesk_medium", FontWeight.Medium), font("grotesk_semibold", FontWeight.SemiBold), font("grotesk_bold", FontWeight.Bold))
val Manrope = FontFamily(
    font("manrope_regular", FontWeight.Normal), font("manrope_medium", FontWeight.Medium),
    font("manrope_semibold", FontWeight.SemiBold), font("manrope_bold", FontWeight.Bold),
)

/** Type scale for a desk at arm's length. Every number uses tabular digits so live prices don't jitter. */
object T {
    private const val TNUM = "tnum"
    val Title = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em)
    val Name = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 19.sp)
    val Price = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 19.sp, fontFeatureSettings = TNUM)
    val BigPrice = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, fontFeatureSettings = TNUM, letterSpacing = (-0.02).em)
    val Figure = TextStyle(fontFamily = Grotesk, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM)
    val Body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp)
    val Strong = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 19.sp)
    val Small = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM)
    val Tiny = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = TNUM)
}

@Composable
fun DeskTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Desk.Saffron, onPrimary = Desk.Base, background = Desk.Base, onBackground = Desk.Paper,
            surface = Desk.Panel, onSurface = Desk.Paper, surfaceVariant = Desk.Raised, onSurfaceVariant = Desk.Dim,
            outline = Desk.Hairline, outlineVariant = Desk.Hairline, error = Desk.Short,
        ),
        content = content,
    )
}
