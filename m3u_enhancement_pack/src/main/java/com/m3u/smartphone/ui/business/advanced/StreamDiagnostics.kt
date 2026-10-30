/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import java.net.HttpURLConnection
import java.net.URL

/** A tiny HTTP probe. It reads at most one response byte and never starts a video decoder. */
data class StreamDiagnosticResult(
    val endpoint: String,
    val statusCode: Int?,
    val elapsedMs: Long,
    val contentType: String?,
    val contentLength: Long?,
    val rangeSupported: Boolean,
    val firstByteReceived: Boolean,
    val redirect: Boolean,
    val error: String?
)

object StreamDiagnostics {
    fun probe(rawUrl: String): StreamDiagnosticResult {
        val parsed = runCatching { URL(rawUrl.trim()) }.getOrNull()
            ?: return failed("Invalid URL")
        val protocol = parsed.protocol.lowercase()
        if (protocol != "http" && protocol != "https") return failed("Only HTTP and HTTPS URLs are supported")
        if (parsed.host.isBlank()) return failed("URL host is missing")

        // Do not expose credentials, path tokens, or query parameters in the result UI.
        val endpoint = buildString {
            append(protocol).append("://").append(parsed.host)
            if (parsed.port > 0) append(":").append(parsed.port)
        }
        val started = System.nanoTime()
        var connection: HttpURLConnection? = null
        return try {
            connection = (parsed.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Range", "bytes=0-0")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Connection", "close")
            }
            val status = connection.responseCode
            val rangeHeader = connection.getHeaderField("Accept-Ranges")
            val rangeSupported = status == HttpURLConnection.HTTP_PARTIAL ||
                rangeHeader?.contains("bytes", ignoreCase = true) == true
            val firstByteReceived = if (status in 200..299) {
                runCatching { connection.inputStream.use { it.read() != -1 } }.getOrDefault(false)
            } else {
                false
            }
            val elapsed = (System.nanoTime() - started) / 1_000_000L
            StreamDiagnosticResult(
                endpoint = endpoint,
                statusCode = status,
                elapsedMs = elapsed,
                contentType = connection.contentType,
                contentLength = connection.getHeaderFieldLong("Content-Length", -1L)
                    .takeIf { it >= 0L },
                rangeSupported = rangeSupported,
                firstByteReceived = firstByteReceived,
                redirect = status in 300..399,
                error = when {
                    status >= 400 -> "Server returned HTTP $status"
                    status in 200..299 && !firstByteReceived -> "Server responded but returned no readable media byte"
                    else -> null
                }
            )
        } catch (exception: Exception) {
            StreamDiagnosticResult(
                endpoint = endpoint,
                statusCode = null,
                elapsedMs = (System.nanoTime() - started) / 1_000_000L,
                contentType = null,
                contentLength = null,
                rangeSupported = false,
                firstByteReceived = false,
                redirect = false,
                error = exception.message?.take(240) ?: exception.javaClass.simpleName
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun failed(message: String) = StreamDiagnosticResult(
        endpoint = "(not sent)", statusCode = null, elapsedMs = 0L,
        contentType = null, contentLength = null, rangeSupported = false,
        firstByteReceived = false, redirect = false, error = message
    )

    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val READ_TIMEOUT_MS = 2_500
}
