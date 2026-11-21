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

    fun isEnabled(assetId: String): Boolean = p.getBoolean("asset_$assetId", true)
    fun setEnabled(assetId: String, on: Boolean) { p.edit().putBoolean("asset_$assetId", on).apply() }
}

/** Last scan, the journal of every leg action, and which signals were already notified. Plain JSON files. */
class Store(context: Context) {
    private val dir = context.filesDir
    private val scanFile = File(dir, "last_scan.json")
    private val journalFile = File(dir, "journal.json")
    private val notifiedFile = File(dir, "notified.json")

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
