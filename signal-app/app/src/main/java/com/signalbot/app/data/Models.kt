package com.signalbot.app.data

import org.json.JSONArray
import org.json.JSONObject

/** Something to do at the next open for one leg: ENTER (with size) or EXIT. */
data class ActionView(
    val assetId: String,
    val assetName: String,
    val leg: Int,
    val action: String,
    val direction: String,
    val close: Double,
    val stop: Double,
    /** Daily close time the decision was made at (epoch ms). */
    val timeMs: Long,
    val reasons: List<String>,
    val sizeText: String?,
) {
    /** Unique per decision, so the same signal never notifies twice. */
    val key: String get() = "$assetId|$leg|$action|$direction|$timeMs"
}

/** A leg in a trade, with the stop to use now. */
data class OpenLegView(
    val assetId: String,
    val leg: Int,
    val direction: String,
    val sinceMs: Long,
    val entryClose: Double,
    val stop: Double,
    val rNow: Double,
)

/** The strategy's own simulated record on this asset (paper trading, closed legs only). */
data class PaperStats(val days: Int, val trades: Int, val winRate: Double, val avgR: Double, val totalR: Double)

data class AssetView(
    val assetId: String,
    val name: String,
    val source: String,
    val closeTimeMs: Long,
    val close: Double,
    val actions: List<ActionView>,
    val open: List<OpenLegView>,
    val paper: PaperStats?,
    val error: String?,
)

data class ScanResult(val timeMs: Long, val assets: List<AssetView>)

// ----------------------------------------------------------------------------- JSON (org.json, built into Android)

private fun JSONObject.d(k: String) = if (isNull(k)) Double.NaN else getDouble(k)
private fun Double.safe(): Any = if (isNaN() || isInfinite()) JSONObject.NULL else this

fun ActionView.toJson(): JSONObject = JSONObject()
    .put("assetId", assetId).put("assetName", assetName).put("leg", leg).put("action", action).put("direction", direction)
    .put("close", close.safe()).put("stop", stop.safe()).put("timeMs", timeMs)
    .put("reasons", JSONArray(reasons)).put("sizeText", sizeText ?: JSONObject.NULL)

fun actionFromJson(o: JSONObject) = ActionView(
    o.getString("assetId"), o.getString("assetName"), o.getInt("leg"), o.getString("action"), o.getString("direction"),
    o.d("close"), o.d("stop"), o.getLong("timeMs"),
    o.getJSONArray("reasons").let { a -> List(a.length()) { a.getString(it) } },
    if (o.isNull("sizeText")) null else o.getString("sizeText"),
)

private fun OpenLegView.toJson(): JSONObject = JSONObject()
    .put("assetId", assetId).put("leg", leg).put("direction", direction).put("sinceMs", sinceMs)
    .put("entryClose", entryClose.safe()).put("stop", stop.safe()).put("rNow", rNow.safe())

private fun openFromJson(o: JSONObject) = OpenLegView(
    o.getString("assetId"), o.getInt("leg"), o.getString("direction"), o.getLong("sinceMs"),
    o.d("entryClose"), o.d("stop"), o.d("rNow"),
)

private fun PaperStats.toJson(): JSONObject = JSONObject()
    .put("days", days).put("trades", trades).put("winRate", winRate.safe()).put("avgR", avgR.safe()).put("totalR", totalR.safe())

private fun paperFromJson(o: JSONObject) = PaperStats(o.getInt("days"), o.getInt("trades"), o.d("winRate"), o.d("avgR"), o.d("totalR"))

fun ScanResult.toJson(): JSONObject = JSONObject().put("timeMs", timeMs).put("assets", JSONArray().apply {
    for (a in assets) put(JSONObject()
        .put("assetId", a.assetId).put("name", a.name).put("source", a.source).put("closeTimeMs", a.closeTimeMs)
        .put("close", a.close.safe())
        .put("actions", JSONArray().apply { a.actions.forEach { put(it.toJson()) } })
        .put("open", JSONArray().apply { a.open.forEach { put(it.toJson()) } })
        .put("paper", a.paper?.toJson() ?: JSONObject.NULL)
        .put("error", a.error ?: JSONObject.NULL))
})

fun scanFromJson(o: JSONObject): ScanResult {
    val arr = o.getJSONArray("assets")
    return ScanResult(o.getLong("timeMs"), List(arr.length()) { i ->
        val a = arr.getJSONObject(i)
        AssetView(
            a.getString("assetId"), a.getString("name"), a.getString("source"), a.getLong("closeTimeMs"), a.d("close"),
            a.getJSONArray("actions").let { x -> List(x.length()) { actionFromJson(x.getJSONObject(it)) } },
            a.getJSONArray("open").let { x -> List(x.length()) { openFromJson(x.getJSONObject(it)) } },
            if (a.isNull("paper")) null else paperFromJson(a.getJSONObject("paper")),
            if (a.isNull("error")) null else a.getString("error"),
        )
    })
}
