/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

enum class StreamProbeOutcome {
    ONLINE,
    OFFLINE,
    RESTRICTED,
    INCONCLUSIVE
}

/**
 * Bounded, redirect-aware stream probe. An ONLINE result means the HTTP media endpoint
 * (and for HLS, one referenced segment) returned readable bytes. It is not a decoder test.
 */
data class StreamDiagnosticResult(
    val endpoint: String,
    val statusCode: Int?,
    val elapsedMs: Long,
    val contentType: String?,
    val contentLength: Long?,
    val rangeSupported: Boolean,
    val firstByteReceived: Boolean,
    val redirect: Boolean,
    val error: String?,
    val outcome: StreamProbeOutcome,
    val validatedSegment: Boolean = false,
    val finalEndpoint: String = endpoint
)

object StreamDiagnostics {
    fun probe(rawUrl: String, userAgent: String? = null): StreamDiagnosticResult {
        val started = System.nanoTime()
        val parsedInput = parseInput(rawUrl, userAgent)
            ?: return failed("(invalid link)", started, "The link is invalid.", StreamProbeOutcome.INCONCLUSIVE)

        val url = parsedInput.url
        val protocol = url.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            return failed(
                safeEndpoint(url),
                started,
                "This protocol needs a player-level test; HTTP checks do not apply.",
                StreamProbeOutcome.INCONCLUSIVE
            )
        }

        return try {
            val initial = request(url, parsedInput.userAgent, parsedInput.referer, MAX_PROBE_BYTES)
            val endpoint = safeEndpoint(url)
            val finalEndpoint = safeEndpoint(initial.finalUrl)
            val elapsed = elapsedMs(started)
            val contentType = initial.contentType
            val isHls = initial.bodyText().trimStart('\uFEFF', ' ', '\r', '\n')
                .startsWith("#EXTM3U", ignoreCase = true) ||
                contentType?.contains("mpegurl", ignoreCase = true) == true ||
                contentType?.contains("vnd.apple.mpegurl", ignoreCase = true) == true

            if (initial.statusCode in 401..403 || initial.statusCode == 407 || initial.statusCode == 429) {
                return StreamDiagnosticResult(
                    endpoint, initial.statusCode, elapsed, contentType, initial.contentLength,
                    initial.rangeSupported, initial.body.isNotEmpty(), initial.redirected,
                    "The server restricted this request (authentication, rate limit or provider policy).",
                    StreamProbeOutcome.RESTRICTED, false, finalEndpoint
                )
            }
            if (initial.statusCode !in 200..299) {
                val outcome = when (initial.statusCode) {
                    404, 410 -> StreamProbeOutcome.OFFLINE
                    in 500..599 -> StreamProbeOutcome.OFFLINE
                    else -> StreamProbeOutcome.INCONCLUSIVE
                }
                return StreamDiagnosticResult(
                    endpoint, initial.statusCode, elapsed, contentType, initial.contentLength,
                    initial.rangeSupported, initial.body.isNotEmpty(), initial.redirected,
                    "Server returned HTTP ${initial.statusCode}.", outcome, false, finalEndpoint
                )
            }
            if (initial.body.isEmpty()) {
                return StreamDiagnosticResult(
                    endpoint, initial.statusCode, elapsed, contentType, initial.contentLength,
                    initial.rangeSupported, false, initial.redirected,
                    "The server answered but returned no readable bytes.",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }

            if (isHls) {
                val segment = probeHlsMedia(
                    manifestUrl = initial.finalUrl,
                    manifest = initial.bodyText(),
                    userAgent = parsedInput.userAgent,
                    referer = parsedInput.referer,
                    depth = 0
                )
                val segmentReadable = segment.outcome == StreamProbeOutcome.ONLINE
                return StreamDiagnosticResult(
                    endpoint = endpoint,
                    statusCode = initial.statusCode,
                    elapsedMs = elapsedMs(started),
                    contentType = contentType,
                    contentLength = initial.contentLength,
                    rangeSupported = initial.rangeSupported || segment.rangeSupported,
                    firstByteReceived = segmentReadable,
                    redirect = initial.redirected || segment.redirected,
                    error = segment.error,
                    outcome = segment.outcome,
                    validatedSegment = segmentReadable,
                    finalEndpoint = finalEndpoint
                )
            }

            val looksLikeHtml = contentType?.contains("text/html", ignoreCase = true) == true ||
                initial.bodyText().trimStart().startsWith("<!doctype html", ignoreCase = true) ||
                initial.bodyText().trimStart().startsWith("<html", ignoreCase = true)
            val bodyPreview = initial.bodyText().trimStart()
            val looksLikeStructuredError = contentType?.let {
                it.contains("json", ignoreCase = true) ||
                    it.contains("xml", ignoreCase = true)
            } == true || bodyPreview.startsWith("{") || bodyPreview.startsWith("[") ||
                bodyPreview.startsWith("<?xml", ignoreCase = true)
            if (looksLikeStructuredError) {
                return StreamDiagnosticResult(
                    endpoint, initial.statusCode, elapsedMs(started), contentType, initial.contentLength,
                    initial.rangeSupported, true, initial.redirected,
                    "The server returned structured text rather than a confirmed media stream.",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }

            if (looksLikeHtml) {
                return StreamDiagnosticResult(
                    endpoint, initial.statusCode, elapsedMs(started), contentType, initial.contentLength,
                    initial.rangeSupported, true, initial.redirected,
                    "The server returned a web page rather than media bytes (possibly a sign-in or block page).",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }

            StreamDiagnosticResult(
                endpoint = endpoint,
                statusCode = initial.statusCode,
                elapsedMs = elapsedMs(started),
                contentType = contentType,
                contentLength = initial.contentLength,
                rangeSupported = initial.rangeSupported,
                firstByteReceived = true,
                redirect = initial.redirected,
                error = null,
                outcome = StreamProbeOutcome.ONLINE,
                validatedSegment = false,
                finalEndpoint = finalEndpoint
            )
        } catch (exception: Exception) {
            val message = when (exception) {
                is java.net.SocketTimeoutException -> "Connection timed out."
                is java.net.UnknownHostException -> "The server name could not be resolved."
                is javax.net.ssl.SSLException -> "Secure connection (TLS) failed."
                is java.net.ConnectException -> "The server refused or could not accept the connection."
                else -> "Network check failed (${exception.javaClass.simpleName})."
            }
            failed(safeEndpoint(url), started, message, StreamProbeOutcome.OFFLINE)
        }
    }

    private data class ParsedInput(
        val url: URL,
        val userAgent: String?,
        val referer: String?
    )

    private data class HttpSample(
        val finalUrl: URL,
        val statusCode: Int,
        val contentType: String?,
        val contentLength: Long?,
        val rangeSupported: Boolean,
        val redirected: Boolean,
        val body: ByteArray
    ) {
        fun bodyText(): String = String(body, StandardCharsets.UTF_8)
    }

    private fun parseInput(raw: String, suppliedUserAgent: String?): ParsedInput? = runCatching {
        val parts = raw.trim().split('|', limit = 2)
        val url = URL(parts.first().trim())
        val options = parts.getOrNull(1).orEmpty()
        fun option(name: String): String? {
            val match = Regex("(?i)(?:^|[&;])${Regex.escape(name)}=([^&;]+)")
                .find(options) ?: return null
            return URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8.name())
        }
        ParsedInput(
            url = url,
            userAgent = suppliedUserAgent?.takeIf(String::isNotBlank)
                ?: option("User-Agent") ?: option("ua"),
            referer = option("Referer") ?: option("referrer")
        )
    }.getOrNull()

    private fun request(
        url: URL,
        userAgent: String?,
        referer: String?,
        maximumBytes: Int
    ): HttpSample {
        var connection: HttpURLConnection? = null
        try {
            connection = (url.openConnection() as? HttpURLConnection)
                ?: throw IllegalArgumentException("HTTP connection unavailable")
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Range", "bytes=0-${maximumBytes - 1}")
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
                userAgent?.takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                referer?.takeIf(String::isNotBlank)?.let { setRequestProperty("Referer", it) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.use { readLimited(it, maximumBytes) } ?: ByteArray(0)
            val range = status == HttpURLConnection.HTTP_PARTIAL ||
                connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            val finalUrl = connection.url ?: url
            return HttpSample(
                finalUrl = finalUrl,
                statusCode = status,
                contentType = connection.contentType,
                contentLength = connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0L },
                rangeSupported = range,
                redirected = finalUrl.toExternalForm() != url.toExternalForm(),
                body = body
            )
        } finally {
            connection?.disconnect()
        }
    }

    private data class HlsSegmentProbe(
        val outcome: StreamProbeOutcome,
        val statusCode: Int?,
        val rangeSupported: Boolean,
        val redirected: Boolean,
        val error: String?
    )

    /**
     * Follows at most two nested HLS playlists (master -> variant -> media segment).
     * This prevents a reachable master/variant manifest from being reported as a playable segment.
     */
    private fun probeHlsMedia(
        manifestUrl: URL,
        manifest: String,
        userAgent: String?,
        referer: String?,
        depth: Int
    ): HlsSegmentProbe {
        val reference = findHlsMediaReference(manifest)
            ?: return HlsSegmentProbe(
                StreamProbeOutcome.INCONCLUSIVE, null, false, false,
                "HLS manifest was reachable, but no media URI was found."
            )
        val sample = request(
            URL(manifestUrl, reference),
            userAgent,
            referer,
            MAX_SEGMENT_BYTES
        )
        val status = sample.statusCode
        if (status in 401..403 || status == 407 || status == 429) {
            return HlsSegmentProbe(
                StreamProbeOutcome.RESTRICTED, status, sample.rangeSupported, sample.redirected,
                "HLS media request was restricted (HTTP $status)."
            )
        }
        if (status == 404 || status == 410 || status in 500..599) {
            return HlsSegmentProbe(
                StreamProbeOutcome.OFFLINE, status, sample.rangeSupported, sample.redirected,
                "HLS media request failed (HTTP $status)."
            )
        }
        if (status !in 200..299 || sample.body.isEmpty()) {
            return HlsSegmentProbe(
                StreamProbeOutcome.INCONCLUSIVE, status, sample.rangeSupported, sample.redirected,
                "HLS media response was not confirmed (HTTP $status)."
            )
        }

        val preview = sample.bodyText().trimStart('\uFEFF', ' ', '\r', '\n')
        val nestedManifest = preview.startsWith("#EXTM3U", ignoreCase = true) ||
            sample.contentType?.contains("mpegurl", ignoreCase = true) == true ||
            sample.contentType?.contains("vnd.apple.mpegurl", ignoreCase = true) == true
        if (nestedManifest) {
            if (depth >= MAX_HLS_NESTED_PLAYLISTS) {
                return HlsSegmentProbe(
                    StreamProbeOutcome.INCONCLUSIVE, status, sample.rangeSupported, sample.redirected,
                    "Too many nested HLS playlists; the media segment was not confirmed."
                )
            }
            val nested = probeHlsMedia(
                manifestUrl = sample.finalUrl,
                manifest = sample.bodyText(),
                userAgent = userAgent,
                referer = referer,
                depth = depth + 1
            )
            return nested.copy(
                rangeSupported = sample.rangeSupported || nested.rangeSupported,
                redirected = sample.redirected || nested.redirected
            )
        }

        val htmlOrStructured = sample.contentType?.let {
            it.contains("text/html", ignoreCase = true) ||
                it.contains("json", ignoreCase = true) ||
                it.contains("xml", ignoreCase = true)
        } == true || preview.startsWith("{") || preview.startsWith("[") ||
            preview.startsWith("<html", ignoreCase = true)
        if (htmlOrStructured) {
            return HlsSegmentProbe(
                StreamProbeOutcome.INCONCLUSIVE, status, sample.rangeSupported, sample.redirected,
                "The HLS media URL returned a web or structured error page."
            )
        }
        return HlsSegmentProbe(
            StreamProbeOutcome.ONLINE, status, sample.rangeSupported, sample.redirected, null
        )
    }

    private fun findHlsMediaReference(manifest: String): String? {
        val lines = manifest.lineSequence().map(String::trim)
            .filter(String::isNotEmpty).toList()
        var expectUri = false
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                line.startsWith("#EXTINF", ignoreCase = true)) {
                expectUri = true
                continue
            }
            if (!line.startsWith("#")) {
                if (expectUri || line.contains('/') || line.contains('.')) return line
            }
        }
        return null
    }

    private fun readLimited(input: java.io.InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 8 * 1024))
        val buffer = ByteArray(4 * 1024)
        while (output.size() < maximumBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maximumBytes - output.size()))
            if (read < 0) break
            if (read > 0) output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun safeEndpoint(url: URL): String = buildString {
        append(url.protocol.lowercase()).append("://").append(url.host)
        if (url.port > 0) append(':').append(url.port)
    }

    private fun elapsedMs(started: Long): Long =
        (System.nanoTime() - started) / 1_000_000L

    private fun failed(
        endpoint: String,
        started: Long,
        message: String,
        outcome: StreamProbeOutcome
    ) = StreamDiagnosticResult(
        endpoint = endpoint,
        statusCode = null,
        elapsedMs = elapsedMs(started),
        contentType = null,
        contentLength = null,
        rangeSupported = false,
        firstByteReceived = false,
        redirect = false,
        error = message,
        outcome = outcome,
        validatedSegment = false,
        finalEndpoint = endpoint
    )

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_PROBE_BYTES = 8 * 1024
    private const val MAX_SEGMENT_BYTES = 4 * 1024
    private const val MAX_HLS_NESTED_PLAYLISTS = 2
}
