package com.signalbot.desk

import com.signalbot.engine.model.Timeframe
import java.io.File
import java.util.Properties

/** The five charts the desk shows, shortest first. */
enum class Tf(val engine: Timeframe, val label: String) {
    M5(Timeframe.M5, "5m"), M15(Timeframe.M15, "15m"), H1(Timeframe.H1, "1H"), H4(Timeframe.H4, "4H"), D1(Timeframe.D1, "1D");

    companion object {
        fun of(t: Timeframe) = entries.first { it.engine == t }
    }
}

/** Your settings, kept in a small file in your home folder (.truetrade-desk). */
data class DeskSettings(
    val capital: Double = 100_000.0,
    val riskPct: Double = 1.0,
    val leverage: Double = 2.0,
    /** Timeframes whose entry signals alert you. */
    val alertTfs: Set<Tf> = setOf(Tf.D1),
    val trendFilter: Boolean = true,
    val takeProfit: Boolean = false,
    val stepAlerts: Boolean = false,
    val sound: Boolean = true,
) {
    companion object {
        val dir: File = File(System.getProperty("user.home"), ".truetrade-desk").apply { mkdirs() }
        private val file = File(dir, "settings.properties")

        fun load(): DeskSettings = runCatching {
            val p = Properties().apply { file.inputStream().use { load(it) } }
            val d = DeskSettings()
            DeskSettings(
                capital = p.getProperty("capital")?.toDoubleOrNull()?.takeIf { it > 0 } ?: d.capital,
                riskPct = p.getProperty("riskPct")?.toDoubleOrNull()?.takeIf { it > 0 && it <= 5 } ?: d.riskPct,
                leverage = p.getProperty("leverage")?.toDoubleOrNull()?.takeIf { it in 1.0..50.0 } ?: d.leverage,
                alertTfs = p.getProperty("alertTfs")?.split(',')?.mapNotNull { n -> Tf.entries.firstOrNull { it.name == n } }?.toSet() ?: d.alertTfs,
                trendFilter = p.getProperty("trendFilter")?.toBoolean() ?: d.trendFilter,
                takeProfit = p.getProperty("takeProfit")?.toBoolean() ?: d.takeProfit,
                stepAlerts = p.getProperty("stepAlerts")?.toBoolean() ?: d.stepAlerts,
                sound = p.getProperty("sound")?.toBoolean() ?: d.sound,
            )
        }.getOrDefault(DeskSettings())
    }

    fun save() {
        val p = Properties()
        p["capital"] = capital.toString(); p["riskPct"] = riskPct.toString(); p["leverage"] = leverage.toString()
        p["alertTfs"] = alertTfs.joinToString(",") { it.name }
        p["trendFilter"] = trendFilter.toString(); p["takeProfit"] = takeProfit.toString()
        p["stepAlerts"] = stepAlerts.toString(); p["sound"] = sound.toString()
        val tmp = File(dir, "settings.properties.tmp")
        tmp.outputStream().use { p.store(it, "TrueTrade Desk") }
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
}
