package com.signalbot.app.data

import org.json.JSONArray
import org.json.JSONObject

/** How a line on the chart is drawn. */
enum class LineStyle { DOWNTREND, UPTREND, BROKEN, OLD }

/** A trendline in chart-window candle indexes (i can be negative: the anchor is left of the window). */
data class ChartLine(val i1: Int, val p1: Double, val i2: Int, val p2: Double, val endIndex: Int, val style: LineStyle) {
    fun at(i: Double): Double = p1 + (p2 - p1) * (i - i1) / (i2 - i1).toDouble()
}

enum class MarkerKind { BUY, SELL, EXIT_WIN, EXIT_LOSS, BREAK }

data class ChartMarker(val index: Int, val price: Double, val kind: MarkerKind)

/** A horizontal piece of a stop or target line, from candle [from] to [to] (inclusive). */
data class Segment(val from: Int, val to: Int, val price: Double)

/** Everything the candle chart draws. Candles are the newest ones, oldest first. */
data class ChartModel(
    val timeframe: String,
    val time: LongArray,
    val open: DoubleArray,
    val high: DoubleArray,
    val low: DoubleArray,
    val close: DoubleArray,
    val lines: List<ChartLine>,
    val levels: List<Double>,
    val markers: List<ChartMarker>,
    val stops: List<Segment>,
    val targets: List<Segment>,
) {
    val size: Int get() = close.size
    override fun equals(other: Any?) = other is ChartModel && other.timeframe == timeframe && other.time.contentEquals(time) &&
        other.close.contentEquals(close) && other.lines == lines && other.markers == markers
    override fun hashCode() = time.contentHashCode() * 31 + close.contentHashCode()
}

/** The trade the setup is in now. */
data class SetupTrade(
    val direction: String,
    val signalTimeMs: Long,
    val entry: Double,
    val stop: Double,
    val initialStop: Double,
    val target: Double?,
    val rNow: Double,
)

/** One asset's break-and-retest state after the newest closed candle. */
data class SetupView(
    val assetId: String,
    val name: String,
    val timeframe: String,
    val closeTimeMs: Long,
    val close: Double,
    /** One sentence: what the setup is doing now. */
    val headline: String,
    /** Her three entry questions and whether each is met, for the side closest to a trade. */
    val criteria: List<Pair<String, Boolean>>,
    /** Things to do at the next open: an entry (the signal), a stop move or an exit. */
    val actions: List<ActionView>,
    val trade: SetupTrade?,
    val chart: ChartModel?,
    val error: String?,
)

data class SetupResult(val timeMs: Long, val timeframe: String, val assets: List<SetupView>)

/** The on-phone backtest of the setup. */
data class SetupTestRow(val label: String, val trades: Int, val winRate: Double, val avgR: Double, val totalR: Double)

data class SetupTest(
    val timeMs: Long,
    val timeframe: String,
    val settingsText: String,
    val fromMs: Long,
    val toMs: Long,
    val overall: SetupTestRow,
    val profitFactor: Double,
    val maxDrawdownR: Double,
    val perAsset: List<SetupTestRow>,
    val notes: List<String>,
)

// ----------------------------------------------------------------------------- JSON

private fun Double.j(): Any = if (isNaN() || isInfinite()) JSONObject.NULL else this
private fun JSONObject.dd(k: String) = if (isNull(k)) Double.NaN else getDouble(k)
private fun JSONObject.dn(k: String): Double? = if (!has(k) || isNull(k)) null else getDouble(k)
private fun DoubleArray.j() = JSONArray().also { a -> forEach { a.put(it.j()) } }
private fun JSONArray.doubles() = DoubleArray(length()) { optDouble(it) }

fun ChartModel.toJson(): JSONObject = JSONObject()
    .put("tf", timeframe)
    .put("time", JSONArray().also { a -> time.forEach { a.put(it) } })
    .put("o", open.j()).put("h", high.j()).put("l", low.j()).put("c", close.j())
    .put("lines", JSONArray().also { a -> lines.forEach { a.put(JSONObject().put("i1", it.i1).put("p1", it.p1).put("i2", it.i2).put("p2", it.p2).put("end", it.endIndex).put("s", it.style.name)) } })
    .put("levels", JSONArray().also { a -> levels.forEach { a.put(it) } })
    .put("markers", JSONArray().also { a -> markers.forEach { a.put(JSONObject().put("i", it.index).put("p", it.price).put("k", it.kind.name)) } })
    .put("stops", segs(stops)).put("targets", segs(targets))

private fun segs(s: List<Segment>) = JSONArray().also { a -> s.forEach { a.put(JSONObject().put("f", it.from).put("t", it.to).put("p", it.price)) } }
private fun JSONArray.segs() = List(length()) { getJSONObject(it).let { o -> Segment(o.getInt("f"), o.getInt("t"), o.getDouble("p")) } }

fun chartFromJson(o: JSONObject) = ChartModel(
    o.getString("tf"),
    o.getJSONArray("time").let { a -> LongArray(a.length()) { a.getLong(it) } },
    o.getJSONArray("o").doubles(), o.getJSONArray("h").doubles(), o.getJSONArray("l").doubles(), o.getJSONArray("c").doubles(),
    o.getJSONArray("lines").let { a -> List(a.length()) { a.getJSONObject(it).let { l ->
        ChartLine(l.getInt("i1"), l.getDouble("p1"), l.getInt("i2"), l.getDouble("p2"), l.getInt("end"), LineStyle.valueOf(l.getString("s"))) } } },
    o.getJSONArray("levels").let { a -> List(a.length()) { a.getDouble(it) } },
    o.getJSONArray("markers").let { a -> List(a.length()) { a.getJSONObject(it).let { m ->
        ChartMarker(m.getInt("i"), m.getDouble("p"), MarkerKind.valueOf(m.getString("k"))) } } },
    o.getJSONArray("stops").segs(), o.getJSONArray("targets").segs(),
)

private fun SetupTrade.toJson() = JSONObject().put("dir", direction).put("t", signalTimeMs).put("entry", entry.j()).put("stop", stop.j())
    .put("stop0", initialStop.j()).put("target", target?.j() ?: JSONObject.NULL).put("r", rNow.j())

private fun tradeFromJson(o: JSONObject) = SetupTrade(o.getString("dir"), o.getLong("t"), o.dd("entry"), o.dd("stop"), o.dd("stop0"), o.dn("target"), o.dd("r"))

fun SetupResult.toJson(): JSONObject = JSONObject().put("timeMs", timeMs).put("tf", timeframe).put("assets", JSONArray().also { arr ->
    for (a in assets) arr.put(JSONObject()
        .put("id", a.assetId).put("name", a.name).put("tf", a.timeframe).put("closeTimeMs", a.closeTimeMs).put("close", a.close.j())
        .put("headline", a.headline)
        .put("criteria", JSONArray().also { c -> a.criteria.forEach { c.put(JSONObject().put("t", it.first).put("ok", it.second)) } })
        .put("actions", JSONArray().also { x -> a.actions.forEach { x.put(it.toJson()) } })
        .put("trade", a.trade?.toJson() ?: JSONObject.NULL)
        .put("chart", a.chart?.toJson() ?: JSONObject.NULL)
        .put("error", a.error ?: JSONObject.NULL))
})

fun setupResultFromJson(o: JSONObject): SetupResult {
    val arr = o.getJSONArray("assets")
    return SetupResult(o.getLong("timeMs"), o.getString("tf"), List(arr.length()) { i ->
        val a = arr.getJSONObject(i)
        SetupView(
            a.getString("id"), a.getString("name"), a.getString("tf"), a.getLong("closeTimeMs"), a.dd("close"), a.getString("headline"),
            a.getJSONArray("criteria").let { c -> List(c.length()) { c.getJSONObject(it).let { x -> x.getString("t") to x.getBoolean("ok") } } },
            a.getJSONArray("actions").let { x -> List(x.length()) { actionFromJson(x.getJSONObject(it)) } },
            if (a.isNull("trade")) null else tradeFromJson(a.getJSONObject("trade")),
            if (a.isNull("chart")) null else chartFromJson(a.getJSONObject("chart")),
            if (a.isNull("error")) null else a.getString("error"),
        )
    })
}

private fun SetupTestRow.toJson() = JSONObject().put("label", label).put("n", trades).put("win", winRate.j()).put("avg", avgR.j()).put("total", totalR.j())
private fun rowFromJson(o: JSONObject) = SetupTestRow(o.getString("label"), o.getInt("n"), o.dd("win"), o.dd("avg"), o.dd("total"))

fun SetupTest.toJson(): JSONObject = JSONObject().put("timeMs", timeMs).put("tf", timeframe).put("settings", settingsText)
    .put("from", fromMs).put("to", toMs).put("overall", overall.toJson()).put("pf", profitFactor.j()).put("dd", maxDrawdownR.j())
    .put("perAsset", JSONArray().also { a -> perAsset.forEach { a.put(it.toJson()) } })
    .put("notes", JSONArray(notes))

fun setupTestFromJson(o: JSONObject) = SetupTest(
    o.getLong("timeMs"), o.getString("tf"), o.getString("settings"), o.getLong("from"), o.getLong("to"),
    rowFromJson(o.getJSONObject("overall")), o.dd("pf"), o.dd("dd"),
    o.getJSONArray("perAsset").let { a -> List(a.length()) { rowFromJson(a.getJSONObject(it)) } },
    o.getJSONArray("notes").let { a -> List(a.length()) { a.getString(it) } },
)
