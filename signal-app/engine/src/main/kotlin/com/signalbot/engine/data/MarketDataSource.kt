package com.signalbot.engine.data

import com.signalbot.engine.model.Candle
import com.signalbot.engine.model.Timeframe
import java.io.IOException

/**
 * A source of candles. Results are oldest first and MAY include the candle that is still
 * forming; use [CandleLoader] to get closed candles only.
 */
interface MarketDataSource {
    val name: String

    /**
     * Up to [limit] (max 1000) candles ending at [endTimeMs] (inclusive, by open time),
     * or the most recent ones when [endTimeMs] is null.
     */
    fun klines(symbol: String, timeframe: Timeframe, limit: Int, endTimeMs: Long? = null): List<Candle>
}

class MarketDataException(
    message: String,
    /** True for problems worth retrying: timeouts, rate limits, server errors. */
    val retryable: Boolean,
    cause: Throwable? = null,
    /** How long the server asked us to wait (rate limits), if it said. */
    val retryAfterMs: Long? = null,
) : Exception(message, cause)

/**
 * Retries temporary failures with exponential backoff: 1 s, 2 s, 4 s...
 * A Retry-After from the server wins when it is longer.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 1_000,
    private val maxDelayMs: Long = 30_000,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    fun <T> run(block: () -> T): T {
        var attempt = 1
        while (true) {
            try {
                return block()
            } catch (e: Exception) {
                val failure = when (e) {
                    is MarketDataException -> e
                    is IOException -> MarketDataException("Network error: ${e.message}", retryable = true, cause = e)
                    else -> throw e
                }
                if (!failure.retryable || attempt >= maxAttempts) throw failure
                val backoff = (baseDelayMs shl (attempt - 1)).coerceAtMost(maxDelayMs)
                val serverWait = failure.retryAfterMs ?: 0
                sleeper(maxOf(backoff, serverWait).coerceAtMost(maxDelayMs))
                attempt++
            }
        }
    }
}

/**
 * Tries each source in order and returns the first success.
 * Binance first, Bybit as backup, so one exchange outage or regional block doesn't stop the scanner.
 */
class FallbackSource(private val sources: List<MarketDataSource>) : MarketDataSource {
    init { require(sources.isNotEmpty()) }

    override val name: String get() = sources.joinToString(" > ") { it.name }

    /** Name of the source that answered the last successful request. */
    @Volatile
    var lastUsed: String? = null
        private set

    override fun klines(symbol: String, timeframe: Timeframe, limit: Int, endTimeMs: Long?): List<Candle> {
        val errors = ArrayList<String>()
        var last: MarketDataException? = null
        for (source in sources) {
            try {
                val result = source.klines(symbol, timeframe, limit, endTimeMs)
                lastUsed = source.name
                return result
            } catch (e: MarketDataException) {
                errors += "${source.name}: ${e.message}"
                last = e
            }
        }
        throw MarketDataException(
            "All data sources failed for $symbol ${timeframe.label}: ${errors.joinToString("; ")}",
            retryable = last?.retryable ?: true,
            cause = last,
        )
    }
}

private val SYMBOL = Regex("^[A-Z0-9]{2,20}$")

internal fun checkRequest(symbol: String, limit: Int) {
    require(SYMBOL.matches(symbol)) { "Invalid symbol '$symbol' (expected e.g. BTCUSDT)" }
    require(limit in 1..1000) { "limit must be 1..1000" }
}
