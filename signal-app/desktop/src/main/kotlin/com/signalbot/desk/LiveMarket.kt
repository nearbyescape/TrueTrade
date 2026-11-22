package com.signalbot.desk

import com.signalbot.engine.data.CandleLoader
import com.signalbot.engine.data.Exchange
import com.signalbot.engine.data.RetryPolicy
import com.signalbot.engine.data.UrlConnectionHttp
import com.signalbot.engine.model.Asset
import com.signalbot.engine.model.Candle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap

/** One asset on one timeframe: closed candles (what the strategy reads) and the candle still forming. */
class Book(val asset: Asset, val tf: Tf) {
    @Volatile var closed: List<Candle> = emptyList()
    @Volatile var forming: Candle? = null
    @Volatile var source: String = ""
    @Volatile var error: String? = null
}

enum class Feed { CONNECTING, LIVE, POLLING, OFFLINE }

/**
 * Live futures prices for every asset and timeframe. History comes once over REST (Binance, Bybit as
 * backup); after that Binance's public WebSocket pushes every candle update. If the socket drops it
 * reconnects, and fills any gap over REST first, so the strategy never sees a missing candle.
 * Assets that only loaded from Bybit are polled instead.
 */
class LiveMarket(private val assets: List<Asset>, private val scope: CoroutineScope) {
    val books = ConcurrentHashMap<Pair<String, Tf>, Book>()
    /** Bumped on every price update (the UI throttles its redraws). */
    val tick = MutableStateFlow(0L)
    val feed = MutableStateFlow(Feed.CONNECTING)
    val lastUpdate = MutableStateFlow(0L)
    /** Called on a background thread whenever a candle closes. */
    var onClose: (Book) -> Unit = {}

    private val http = UrlConnectionHttp(connectTimeoutMs = 10_000, readTimeoutMs = 20_000)
    private val retry = RetryPolicy()
    private val historyCount = mapOf(Tf.M5 to 1000, Tf.M15 to 1000, Tf.H1 to 1000, Tf.H4 to 1000, Tf.D1 to 700)

    fun book(id: String, tf: Tf) = books.getOrPut(id to tf) { Book(assets.first { it.id == id }, tf) }

    /** Loads history for everything, then goes live. Returns when history is in. */
    suspend fun start(progress: (String) -> Unit) {
        val gate = Semaphore(4)
        val jobs = assets.flatMap { a -> Tf.entries.map { tf -> a to tf } }.map { (a, tf) ->
            scope.launch(Dispatchers.IO) { gate.withPermit { progress("Loading ${a.displayName} ${tf.label}"); load(book(a.id, tf)) } }
        }
        jobs.forEach { it.join() }
        assets.flatMap { a -> Tf.entries.map { book(a.id, it) } }.forEach { b -> if (b.closed.isNotEmpty()) onClose(b) }
        scope.launch(Dispatchers.IO) { socketLoop() }
        scope.launch(Dispatchers.IO) { pollLoop() }
    }

    private fun load(b: Book) {
        val errors = ArrayList<String>()
        for (route in b.asset.routes) {
            try {
                val c = CandleLoader(route.source(http, retry)).closed(route.symbol, b.tf.engine, historyCount.getValue(b.tf))
                if (c.isEmpty()) continue
                b.closed = c; b.source = route.exchange.name; b.error = null
                return
            } catch (e: Exception) {
                errors += "${route.exchange}: ${e.message}"
            }
        }
        b.error = errors.joinToString("; ").ifEmpty { "No data" }
    }

    /** Fetches closed candles newer than the last one we hold (after a disconnect or a missed message). */
    private fun catchUp(b: Book) {
        val route = b.asset.routes.firstOrNull { it.exchange.name == b.source } ?: return
        val last = b.closed.lastOrNull() ?: return load(b)
        runCatching {
            val fresh = CandleLoader(route.source(http, retry)).since(route.symbol, b.tf.engine, last.openTime)
            if (fresh.isNotEmpty()) { b.closed = (b.closed + fresh).takeLast(historyCount.getValue(b.tf) + 200); onClose(b) }
        }
    }

    // ------------------------------------------------------------------------------- WebSocket (Binance)

    private suspend fun socketLoop() {
        var backoff = 2_000L
        while (scope.isActive) {
            val live = books.values.filter { it.source == Exchange.BINANCE.name }
            if (live.isEmpty()) { feed.value = Feed.POLLING; delay(30_000); continue }
            val streams = live.joinToString("/") { "${it.asset.routes.first().symbol.lowercase()}@kline_${it.tf.engine.binanceCode}" }
            val done = kotlinx.coroutines.CompletableDeferred<Unit>()
            try {
                val ws = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build().newWebSocketBuilder()
                    .buildAsync(URI("wss://fstream.binance.com/stream?streams=$streams"), Listener(done)).join()
                feed.value = Feed.LIVE
                backoff = 2_000L
                live.forEach { catchUp(it) } // anything that closed while we were connecting
                // Binance closes connections after 24 hours; reconnect a little before.
                val until = System.currentTimeMillis() + 23 * 3_600_000L
                while (!done.isCompleted && System.currentTimeMillis() < until) delay(1_000)
                runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye") }
            } catch (e: Exception) {
                // fall through to reconnect
            }
            feed.value = Feed.OFFLINE
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
            feed.value = Feed.CONNECTING
        }
    }

    private inner class Listener(val done: kotlinx.coroutines.CompletableDeferred<Unit>) : WebSocket.Listener {
        private val buf = StringBuilder()
        override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) { handle(buf.toString()); buf.setLength(0) }
            ws.request(1)
            return null
        }
        override fun onClose(ws: WebSocket, code: Int, reason: String?): CompletionStage<*>? { done.complete(Unit); return null }
        override fun onError(ws: WebSocket, error: Throwable) { done.complete(Unit) }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val symbolToId = assets.associate { it.routes.first().symbol.lowercase() to it.id }
    private val codeToTf = Tf.entries.associateBy { it.engine.binanceCode }

    private fun handle(text: String) {
        val k = runCatching {
            val root = json.parseToJsonElement(text).jsonObject
            val stream = root["stream"]?.jsonPrimitive?.content ?: return
            val (sym, rest) = stream.split("@kline_").takeIf { it.size == 2 }?.let { it[0] to it[1] } ?: return
            val id = symbolToId[sym] ?: return
            val tf = codeToTf[rest] ?: return
            Triple(id, tf, root.getValue("data").jsonObject.getValue("k").jsonObject)
        }.getOrNull() ?: return
        val (id, tf, o) = k
        fun d(key: String) = o.getValue(key).jsonPrimitive.content.toDouble()
        val c = Candle(o.getValue("t").jsonPrimitive.long, d("o"), d("h"), d("l"), d("c"), d("v"), o.getValue("T").jsonPrimitive.long)
        val closed = o.getValue("x").jsonPrimitive.boolean
        val b = book(id, tf)
        if (closed) {
            val last = b.closed.lastOrNull()
            when {
                last == null || c.openTime > last.openTime + tf.engine.millis -> { b.forming = null; catchUp(b) } // gap: refill
                c.openTime > last.openTime -> { b.closed = (b.closed + c).takeLast(historyCount.getValue(tf) + 200); b.forming = null; onClose(b) }
            }
        } else {
            b.forming = c
        }
        lastUpdate.value = System.currentTimeMillis()
        tick.value = tick.value + 1
    }

    // ------------------------------------------------------------------------------- polling (Bybit-only assets)

    private suspend fun pollLoop() {
        while (scope.isActive) {
            delay(15_000)
            for (b in books.values.filter { it.source.isNotEmpty() && it.source != Exchange.BINANCE.name }) {
                val route = b.asset.routes.firstOrNull { it.exchange.name == b.source } ?: continue
                runCatching {
                    val rows = route.source(http, retry).klines(route.symbol, b.tf.engine, 3, null)
                    val now = System.currentTimeMillis()
                    rows.lastOrNull { !it.isClosedAt(now) }?.let { b.forming = it }
                    catchUp(b)
                    lastUpdate.value = now
                    tick.value = tick.value + 1
                }
            }
            // Books that failed to load at all get another try.
            books.values.filter { it.closed.isEmpty() }.forEach { b -> load(b); if (b.closed.isNotEmpty()) onClose(b) }
        }
    }
}
