package com.signalbot.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Your settings. Stored on the phone only. */
class AppSettings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var capital: Double
        get() = p.getString("capital", "100000")!!.toDoubleOrNull() ?: 100_000.0
        set(v) { p.edit().putString("capital", v.toString()).apply() }

    /** % of capital risked per leg. Four legs per asset can be open at once. */
    var riskPerLegPct: Double
        get() = p.getString("riskPerLeg", "0.5")!!.toDoubleOrNull() ?: 0.5
        set(v) { p.edit().putString("riskPerLeg", v.toString()).apply() }

    var leverage: Double
        get() = p.getString("leverage", "2")!!.toDoubleOrNull() ?: 2.0
        set(v) { p.edit().putString("leverage", v.toString()).apply() }

    // ---- Her break-and-retest setup

    /** Chart timeframe the setup trades on: "1d" (default, the only one that held up in testing), "4h" or "1h". */
    var setupTimeframe: String
        get() = p.getString("setupTf", "1d")!!.takeIf { it in listOf("1d", "4h", "1h") } ?: "1d"
        set(v) { p.edit().putString("setupTf", v).apply() }

    /** true = take profit at the nearest support/resistance; false = trail the stop along new swings. */
    var setupTarget: Boolean
        get() = p.getBoolean("setupTarget", false)
        set(v) { p.edit().putBoolean("setupTarget", v).apply() }

    /** Only trade in the direction of the daily trend (top-down). */
    var setupTrend: Boolean
        get() = p.getBoolean("setupTrend", true)
        set(v) { p.edit().putBoolean("setupTrend", v).apply() }

    /** % of capital risked per break-and-retest trade. She suggests 1-2%. */
    var setupRiskPct: Double
        get() = p.getString("setupRisk", "1")!!.toDoubleOrNull() ?: 1.0
        set(v) { p.edit().putString("setupRisk", v.toString()).apply() }

    /** Also notify the break and the retest, not only the entry. */
    var setupStepAlerts: Boolean
        get() = p.getBoolean("setupSteps", false)
        set(v) { p.edit().putBoolean("setupSteps", v).apply() }

    fun isEnabled(assetId: String): Boolean = p.getBoolean("asset_$assetId", true)
    fun setEnabled(assetId: String, on: Boolean) { p.edit().putBoolean("asset_$assetId", on).apply() }
}

/** Last scan, the journal of every leg action, and which signals were already notified. Plain JSON files. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val scanFile = File(dir, "last_scan.json")
    private val journalFile = File(dir, "journal.json")
    private val notifiedFile = File(dir, "notified.json")
    private val setupFile = File(dir, "setup_scan.json")
    private val setupTestFile = File(dir, "setup_test.json")

    @Synchronized
    fun saveSetup(r: SetupResult) = writeAtomic(setupFile, r.toJson().toString())

    @Synchronized
    fun lastSetup(): SetupResult? = runCatching { setupResultFromJson(JSONObject(setupFile.readText())) }.getOrNull()

    @Synchronized
    fun saveSetupTest(t: SetupTest) = writeAtomic(setupTestFile, t.toJson().toString())

    @Synchronized
    fun lastSetupTest(): SetupTest? = runCatching { setupTestFromJson(JSONObject(setupTestFile.readText())) }.getOrNull()

    @Synchronized
    fun saveScan(r: ScanResult) = writeAtomic(scanFile, r.toJson().toString())

    @Synchronized
    fun lastScan(): ScanResult? = runCatching { scanFromJson(JSONObject(scanFile.readText())) }.getOrNull()

    /** Adds new actions to the journal (newest first, deduplicated, capped at 1,000). */
    @Synchronized
    fun addToJournal(actions: List<ActionView>) {
        if (actions.isEmpty()) return
        val existing = journal()
        val keys = existing.map { it.key }.toHashSet()
        val merged = (actions.filter { it.key !in keys } + existing).sortedByDescending { it.timeMs }.take(1_000)
        writeAtomic(journalFile, JSONArray().apply { merged.forEach { put(it.toJson()) } }.toString())
    }

    @Synchronized
    fun journal(): List<ActionView> = runCatching {
        val a = JSONArray(journalFile.readText())
        List(a.length()) { actionFromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun notifiedKeys(): Set<String> = runCatching {
        val a = JSONArray(notifiedFile.readText())
        List(a.length()) { a.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    @Synchronized
    fun markNotified(keys: Collection<String>) {
        val all = (notifiedFile.takeIf { it.exists() }?.let { notifiedKeys() } ?: emptySet()) + keys
        writeAtomic(notifiedFile, JSONArray(all.toList().takeLast(2_000)).toString())
    }

    /** Write to a temp file then rename, so a crash never leaves a half-written file. */
    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }
}
