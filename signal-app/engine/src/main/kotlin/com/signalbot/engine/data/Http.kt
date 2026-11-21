package com.signalbot.engine.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class HttpResponse(
    val code: Int,
    val body: String,
    /** Seconds from the Retry-After header, if the server sent one. */
    val retryAfterSeconds: Long? = null,
)

/** Minimal HTTP GET, so tests can swap in canned responses and the engine needs no HTTP library. */
fun interface HttpGet {
    @Throws(IOException::class)
    fun get(url: String): HttpResponse
}

/** HttpURLConnection works on both desktop Java and Android, with no extra dependency. */
class UrlConnectionHttp(
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
) : HttpGet {
    override fun get(url: String): HttpResponse {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("User-Agent", "signal-app/1.0")
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val retryAfter = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
            return HttpResponse(code, body, retryAfter)
        } finally {
            conn.disconnect()
        }
    }
}
