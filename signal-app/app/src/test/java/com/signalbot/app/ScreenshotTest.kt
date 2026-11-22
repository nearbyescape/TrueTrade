package com.signalbot.app

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.signalbot.app.data.ActionView
import com.signalbot.app.data.AssetView
import com.signalbot.app.data.OpenLegView
import com.signalbot.app.data.PaperStats
import com.signalbot.app.data.ScanResult
import com.signalbot.app.data.SetupResult
import com.signalbot.app.data.SetupTest
import com.signalbot.app.data.SetupTestRow
import com.signalbot.app.data.SetupView
import com.signalbot.app.data.MarkerKind
import com.signalbot.app.data.LineStyle
import com.signalbot.app.scan.SetupAnalysis
import com.signalbot.app.ui.ChartContent
import com.signalbot.app.ui.SetupRecordSection
import com.signalbot.app.ui.SetupSettingsSection
import com.signalbot.app.ui.SetupSettingsState
import com.signalbot.app.ui.SetupTodaySection
import com.signalbot.app.ui.JournalContent
import com.signalbot.app.ui.OrderTicket
import com.signalbot.app.ui.PositionRow
import com.signalbot.app.ui.RecordContent
import com.signalbot.app.ui.SettingsContent
import com.signalbot.app.ui.SettingsState
import com.signalbot.app.ui.TodayContent
import com.signalbot.app.ui.theme.AppTheme
import com.signalbot.app.ui.theme.Palette
import com.signalbot.engine.model.Assets
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId
import kotlin.math.sin

/**
 * Renders each screen with realistic sample data to PNG files (build/screenshots), so the design can be
 * reviewed from the cloud build. Run with: ./gradlew :app:testDebugUnitTest
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w393dp-h873dp-xxhdpi", application = Application::class)
class ScreenshotTest {
    private val ist = ZoneId.of("Asia/Kolkata")
    private val now = 1_763_771_400_000L // Sat 22 Nov 2025, 05:40 IST
    private val close = 1_763_769_600_000L // the 00:00 UTC daily close

    private fun shot(name: String, content: @Composable () -> Unit) =
        captureRoboImage("build/screenshots/$name.png") {
            AppTheme { Surface(Modifier.fillMaxSize(), color = Palette.Night) { content() } }
        }

    private fun closes(start: Double, drift: Double, n: Int = 120) =
        List(n) { start * (1 + drift * it + 0.03 * sin(it / 6.0)) }

    private val btcBuy = ActionView("BTC", "Bitcoin", 30, "ENTER", "LONG", 91_250.5, 86_904.0, close,
        listOf("Daily close 91,250.5 broke the 30-day closing high of 90,880.0", "Trend: above the 200-day EMA (84,112.3)",
            "Stop 86,904.0: the middle of the 30-day closing range. It moves with the range's middle, only in your favour"),
        "Size 0.1150 units (10,494), margin 5,247, risk 500")
    private val solExit = ActionView("SOL", "Solana", 20, "EXIT", "SHORT", 141.3, 139.85, close,
        listOf("Daily close 141.30 is above the 20-day leg's stop 139.85: close this leg at the next open"), null)

    private val scan = ScanResult(now, listOf(
        AssetView("BTC", "Bitcoin", "BINANCE perp BTCUSDT", close, 91_250.5, listOf(btcBuy),
            listOf(OpenLegView("BTC", 60, "LONG", close - 9 * 86_400_000L, 86_420.0, 83_950.0, 1.9),
                OpenLegView("BTC", 90, "LONG", close - 21 * 86_400_000L, 81_300.0, 78_640.0, 3.4)),
            PaperStats(180, 23, 0.48, 0.71, 16.3), null, closes(70_000.0, 0.0025)),
        AssetView("ETH", "Ethereum", "BINANCE perp ETHUSDT", close, 1_642.2, emptyList(),
            listOf(OpenLegView("ETH", 20, "SHORT", close - 12 * 86_400_000L, 2_125.0, 1_880.4, 2.6),
                OpenLegView("ETH", 30, "SHORT", close - 12 * 86_400_000L, 2_125.0, 1_944.0, 2.6),
                OpenLegView("ETH", 60, "SHORT", close - 30 * 86_400_000L, 2_610.0, 2_302.0, 3.1)),
            PaperStats(180, 19, 0.42, 0.55, 10.4), null, closes(2_900.0, -0.0035)),
        AssetView("SOL", "Solana", "BINANCE perp SOLUSDT", close, 141.3, listOf(solExit),
            listOf(OpenLegView("SOL", 30, "SHORT", close - 15 * 86_400_000L, 152.4, 146.2, 0.9)),
            PaperStats(180, 21, 0.38, -0.12, -2.5), null, closes(180.0, -0.0015)),
        AssetView("GOLD", "Gold", "BINANCE perp XAUUSDT", close, 4_112.6, emptyList(), emptyList(),
            PaperStats(180, 9, 0.33, -0.31, -2.8), null, closes(3_900.0, 0.0004)),
        AssetView("SILVER", "Silver", "", 0, Double.NaN, emptyList(), emptyList(), null, "Binance and Bybit did not answer", emptyList()),
    ))

    @Test fun today() = shot("1_today") { TodayContent(scan, scanning = false, message = null, onCheck = {}, nowMs = now, zone = ist) }

    @Test fun todayQuiet() = shot("2_today_quiet") {
        val quiet = scan.copy(assets = scan.assets.map { it.copy(actions = emptyList()) }.take(4))
        TodayContent(quiet, scanning = false, message = null, onCheck = {}, nowMs = now, zone = ist)
    }

    @Test fun positionExpanded() = shot("3_position_detail") {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 28.dp)) {
            OrderTicket(btcBuy)
            PositionRow(scan.assets[1], initiallyExpanded = true)
        }
    }

    @Test fun record() = shot("4_record") { RecordContent(scan) }

    @Test fun journal() = shot("5_journal") {
        val entries = listOf(btcBuy, solExit,
            solExit.copy(action = "ENTER", leg = 30, close = 152.4, stop = 161.0, timeMs = close - 15 * 86_400_000L),
            btcBuy.copy(leg = 60, close = 86_420.0, stop = 80_100.0, timeMs = close - 9 * 86_400_000L))
        JournalContent(entries, zone = ist)
    }

    @Test fun settings() = shot("6_settings") {
        SettingsContent(
            SettingsState("100000", "0.5", "2", Assets.DEFAULT.associate { it.id to (it.id != "GOLD") }),
            Assets.DEFAULT, onSave = { it }, onToggle = { _, _ -> }, onBatterySettings = {},
        )
    }

    // ------------------------------------------------------------------ break and retest

    /** Daily candles from a seeded random walk with swings, so trendlines, breaks and trades appear. */
    private fun candles(seed: Int, n: Int = 520): com.signalbot.engine.model.Series {
        val rnd = kotlin.random.Random(seed)
        var p = 60_000.0
        var drift = 0.0
        val day = 86_400_000L
        val start = close - n * day
        return com.signalbot.engine.model.Series(List(n) { i ->
            if (i % 40 == 0) drift = (rnd.nextDouble() - 0.45) * 0.012
            val o = p
            val c = o * (1 + drift + 0.022 * (rnd.nextDouble() - 0.5) + 0.01 * sin(i / 7.0))
            val h = maxOf(o, c) * (1 + 0.012 * rnd.nextDouble())
            val l = minOf(o, c) * (1 - 0.012 * rnd.nextDouble())
            p = c
            com.signalbot.engine.model.Candle(start + i * day, o, h, l, c, 1000.0, start + (i + 1) * day - 1)
        })
    }

    /** First seed whose chart window has a trade and a live trendline: a realistic, busy chart. */
    private val chartView: SetupView by lazy {
        val tf = com.signalbot.engine.model.Timeframe.D1
        val cfg = com.signalbot.engine.strategy.BreakRetestPresets.forTimeframe(tf)
        (1..300).asSequence().map { seed ->
            val s = candles(seed)
            SetupAnalysis.build("BTC", "Bitcoin", tf, s, s, cfg, useTrend = true,
                sizing = { _, _ -> com.signalbot.app.scan.Sizing("Size 0.2300 units (21,000), margin 10,500, risk 1,000", 0.23) })
        }.firstOrNull { v ->
            val c = v.chart!!
            c.markers.count { it.kind == MarkerKind.BUY || it.kind == MarkerKind.SELL } >= 2 &&
                c.lines.any { it.style != LineStyle.OLD } && c.levels.isNotEmpty()
        } ?: SetupAnalysis.build("BTC", "Bitcoin", tf, candles(1), candles(1), cfg, useTrend = true)
    }

    @Test fun chart() = shot("7_chart") {
        ChartContent(Assets.DEFAULT, "BTC", "1d", "1d", chartView, loading = false, onAsset = {}, onTimeframe = {}, onRefresh = {})
    }

    private val brEntry = ActionView("ETH", "Ethereum", 0, "ENTER", "LONG", 3_412.6, 3_268.0, close,
        listOf("Buy at the next open. Broke the downtrend line, pulled back to it and the candle closed above it. Stop 3,268.0, " +
            "trail it under each new swing", "Line from 12 Oct (3,980.2) through 2 Nov (3,610.4), 3 touches"),
        "Size 6.9000 units (23,547), margin 11,774, risk 1,000", target = 3_655.0, quantity = 6.9)

    @Test fun orderDetails() = shot("11_order_details") {
        com.signalbot.app.ui.OrderDetailsContent(brEntry, onClose = {})
    }

    @Test fun setupToday() = shot("8_setup_today") {
        val views = listOf(
            SetupView("BTC", "Bitcoin", "1d", close, 91_250.5, "In a long since 14 Nov. Stop 88,410.0.",
                SetupAnalysis.CRITERIA.map { it to true }, emptyList(), null, null, null),
            SetupView("ETH", "Ethereum", "1d", close, 3_412.6, "Buy at the next open. All three checks passed.",
                SetupAnalysis.CRITERIA.map { it to true }, listOf(brEntry), null, null, null),
            SetupView("SOL", "Solana", "1d", close, 141.3, "Broke the uptrend line. Waiting for price to pull back and retest it.",
                SetupAnalysis.CRITERIA.mapIndexed { i, t -> t to (i == 0) }, emptyList(), null, null, null),
            SetupView("SILVER", "Silver", "1d", close, 31.2, "Watching the downtrend line near 32.40. No break yet.",
                SetupAnalysis.CRITERIA.map { it to false }, emptyList(), null, null, null),
        )
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SetupTodaySection(SetupResult(now, "1d", views)) {}
        }
    }

    @Test fun setupRecord() = shot("9_setup_record") {
        val test = SetupTest(now, "1d", "1d candles, trailing stop, daily trend filter on", close - 1460L * 86_400_000L, close,
            SetupTestRow("All assets", 71, 0.31, 0.34, 24.1), 1.58, 11.8,
            listOf(SetupTestRow("Bitcoin", 22, 0.32, 0.41, 9.0), SetupTestRow("Ethereum", 19, 0.26, 0.52, 9.9),
                SetupTestRow("Solana", 21, 0.33, 0.12, 2.5), SetupTestRow("Gold", 4, 0.25, -0.3, -1.2), SetupTestRow("Silver", 5, 0.4, 0.78, 3.9)),
            listOf("Gold: only 351 candles of history on Binance futures"))
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SetupRecordSection(test, running = false, progress = null, onRun = {})
        }
    }

    @Test fun setupSettings() = shot("10_setup_settings") {
        androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 8.dp)) {
            SetupSettingsSection(SetupSettingsState("1d", target = false, trend = true, risk = "1", steps = false)) { it }
        }
    }
}
