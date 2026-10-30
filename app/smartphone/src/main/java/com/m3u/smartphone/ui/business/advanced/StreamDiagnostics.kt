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
 * Bounded HTTP media probe. It does not start a decoder. ONLINE means the response looks like
 * media data and, for HLS, a referenced media segment was also reached.
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
    /**
     * Tests the playlist URL itself. A regular IPTV M3U and an HLS manifest both use
     * #EXTM3U, but a regular M3U is not a video segment: its first channel being offline
     * must not make the entire group appear offline.
     */
    fun probePlaylistSource(rawUrl: String, userAgent: String? = null): StreamDiagnosticResult {
        val started = System.nanoTime()
        val parsed = parseInput(rawUrl, userAgent)
            ?: return failed("(invalid source)", started, "The playlist link is invalid.")
        val url = parsed.url
        if (!url.protocol.equals("http", true) && !url.protocol.equals("https", true)) {
            return failed(safeEndpoint(url), started, "Only HTTP and HTTPS playlist links can be checked.")
        }

        return try {
            val sample = request(url, parsed.userAgent, parsed.referer, MAX_PLAYLIST_PROBE_BYTES)
            val endpoint = safeEndpoint(url)
            val finalEndpoint = safeEndpoint(sample.finalUrl)
            classifyStatus(sample.statusCode)?.let { outcome ->
                return StreamDiagnosticResult(
                    endpoint, sample.statusCode, elapsedMs(started), sample.contentType,
                    sample.contentLength, sample.rangeSupported, sample.body.isNotEmpty(),
                    sample.redirected, statusMessage(sample.statusCode, outcome), outcome,
                    false, finalEndpoint
                )
            }
            if (sample.statusCode !in 200..299 || sample.body.isEmpty()) {
                return StreamDiagnosticResult(
                    endpoint, sample.statusCode, elapsedMs(started), sample.contentType,
                    sample.contentLength, sample.rangeSupported, sample.body.isNotEmpty(),
                    sample.redirected, "Playlist source response was inconclusive (HTTP ${sample.statusCode}).",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }

            val text = sample.bodyText().trimStart('\uFEFF', ' ', '\r', '\n')
            if (looksLikeTextError(sample.contentType, sample.body)) {
                return StreamDiagnosticResult(
                    endpoint, sample.statusCode, elapsedMs(started), sample.contentType,
                    sample.contentLength, sample.rangeSupported, true, sample.redirected,
                    "The URL returned a web or text error page rather than a playlist.",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }

            val isHls = text.startsWith("#EXTM3U", ignoreCase = true) && (
                text.contains("#EXT-X-STREAM-INF", ignoreCase = true) ||
                    text.contains("#EXT-X-TARGETDURATION", ignoreCase = true) ||
                    text.contains("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) ||
                    text.contains("#EXT-X-PLAYLIST-TYPE", ignoreCase = true) ||
                    text.contains("#EXT-X-ENDLIST", ignoreCase = true)
                )
            if (text.startsWith("#EXTM3U", ignoreCase = true) && !isHls) {
                val entryCount = text.lineSequence().map(String::trim).count { line ->
                    if (line.isBlank() || line.startsWith("#")) false
                    else runCatching {
                        val candidate = URL(line.substringBefore('|').trim())
                        candidate.protocol.equals("http", true) || candidate.protocol.equals("https", true)
                    }.getOrDefault(false)
                }
                val outcome = if (entryCount > 0) StreamProbeOutcome.ONLINE else StreamProbeOutcome.INCONCLUSIVE
                return StreamDiagnosticResult(
                    endpoint, sample.statusCode, elapsedMs(started), sample.contentType,
                    sample.contentLength, sample.rangeSupported, true, sample.redirected,
                    if (entryCount > 0) "Playlist source loaded; found $entryCount HTTP stream entries in the response sample."
                    else "M3U header was present, but no HTTP stream entries were found in the response sample.",
                    outcome, false, finalEndpoint
                )
            }

            // HLS manifests and direct media URLs use the normal media probe so the first
            // media segment, rather than only the manifest URL, is checked.
            probe(rawUrl, userAgent)
        } catch (exception: Exception) {
            failed(
                safeEndpoint(url),
                started,
                "Playlist source check was inconclusive (${exception.javaClass.simpleName})."
            )
        }
    }

    fun probe(rawUrl: String, userAgent: String? = null): StreamDiagnosticResult {
        val started = System.nanoTime()
        val parsed = parseInput(rawUrl, userAgent)
            ?: return failed("(invalid link)", started, "The link is invalid.")

        val url = parsed.url
        if (!url.protocol.equals("http", true) && !url.protocol.equals("https", true)) {
            return failed(safeEndpoint(url), started, "Only HTTP and HTTPS links can be checked.")
        }

        return try {
            val initial = request(url, parsed.userAgent, parsed.referer, MAX_PROBE_BYTES)
            val endpoint = safeEndpoint(url)
            val finalEndpoint = safeEndpoint(initial.finalUrl)
            val status = initial.statusCode
            val type = initial.contentType

            classifyStatus(status)?.let { outcome ->
                return StreamDiagnosticResult(
                    endpoint, status, elapsedMs(started), type, initial.contentLength,
                    initial.rangeSupported, initial.body.isNotEmpty(), initial.redirected,
                    statusMessage(status, outcome), outcome, false, finalEndpoint
                )
            }

            if (status !in 200..299) {
                return StreamDiagnosticResult(
                    endpoint, status, elapsedMs(started), type, initial.contentLength,
                    initial.rangeSupported, initial.body.isNotEmpty(), initial.redirected,
                    "The server response was not conclusive (HTTP $status).",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }
            if (initial.body.isEmpty()) {
                return StreamDiagnosticResult(
                    endpoint, status, elapsedMs(started), type, initial.contentLength,
                    initial.rangeSupported, false, initial.redirected,
                    "The server responded but returned no readable bytes.",
                    StreamProbeOutcome.INCONCLUSIVE, false, finalEndpoint
                )
            }

            val preview = initial.bodyText().trimStart('\uFEFF', ' ', '\r', '\n')
            val isHls = preview.startsWith("#EXTM3U", ignoreCase = true) ||
                type?.contains("mpegurl", ignoreCase = true) == true ||
                type?.contains("vnd.apple.mpegurl", ignoreCase = true) == true

            if (isHls) {
                val segment = probeHlsMedia(
                    manifestUrl = initial.finalUrl,
                    manifest = initial.bodyText(),
                    userAgent = parsed.userAgent,
                    referer = parsed.referer,
                    depth = 0
                )
                val valid = segment.outcome == StreamProbeOutcome.ONLINE
                return StreamDiagnosticResult(
                    endpoint = endpoint,
                    statusCode = status,
                    elapsedMs = elapsedMs(started),
                    contentType = type,
                    contentLength = initial.contentLength,
                    rangeSupported = initial.rangeSupported || segment.rangeSupported,
                    firstByteReceived = valid,
                    redirect = initial.redirected || segment.redirected,
                    error = segment.error,
                    outcome = segment.outcome,
                    validatedSegment = valid,
                    finalEndpoint = finalEndpoint
                )
            }

            val media = looksLikeMedia(type, initial.body)
            val errorPage = looksLikeTextError(type, initial.body)
            val outcome = if (media && !errorPage) StreamProbeOutcome.ONLINE else StreamProbeOutcome.INCONCLUSIVE
            StreamDiagnosticResult(
                endpoint = endpoint,
                statusCode = status,
                elapsedMs = elapsedMs(started),
                contentType = type,
                contentLength = initial.contentLength,
                rangeSupported = initial.rangeSupported,
                firstByteReceived = true,
                redirect = initial.redirected,
                error = if (outcome == StreamProbeOutcome.ONLINE) null else
                    "The server returned bytes, but they could not be confirmed as media. Verify this channel in the player.",
                outcome = outcome,
                validatedSegment = false,
                finalEndpoint = finalEndpoint
            )
        } catch (exception: Exception) {
            val message = when (exception) {
                is java.net.SocketTimeoutException -> "Connection timed out; the result is inconclusive."
                is java.net.UnknownHostException -> "The server name could not be resolved; check the address or DNS."
                is javax.net.ssl.SSLException -> "Secure connection (TLS) failed; check the server certificate and device time."
                is java.net.ConnectException -> "The server refused the connection or is temporarily unavailable."
                else -> "Network check was inconclusive (${exception.javaClass.simpleName})."
            }
            failed(safeEndpoint(url), started, message)
        }
    }

    private data class ParsedInput(val url: URL, val userAgent: String?, val referer: String?)

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

    private data class HlsProbe(
        val outcome: StreamProbeOutcome,
        val rangeSupported: Boolean,
        val redirected: Boolean,
        val error: String?
    )

    private fun parseInput(raw: String, suppliedUserAgent: String?): ParsedInput? = runCatching {
        val parts = raw.trim().split('|', limit = 2)
        val url = URL(parts.first().trim())
        val options = parts.getOrNull(1).orEmpty()
        fun option(name: String): String? {
            val match = Regex("(?i)(?:^|[&;])${Regex.escape(name)}=([^&;]+)").find(options)
                ?: return null
            return URLDecoder.decode(match.groupValues[1], StandardCharsets.UTF_8.name())
        }
        ParsedInput(
            url,
            suppliedUserAgent?.takeIf(String::isNotBlank) ?: option("User-Agent") ?: option("ua"),
            option("Referer") ?: option("referrer")
        )
    }.getOrNull()

    private fun request(
        url: URL,
        userAgent: String?,
        referer: String?,
        maximumBytes: Int,
        allowRangeFallback: Boolean = true
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
                setRequestProperty("Accept", "*/*")
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("Connection", "close")
                userAgent?.takeIf(String::isNotBlank)?.let { setRequestProperty("User-Agent", it) }
                referer?.takeIf(String::isNotBlank)?.let { setRequestProperty("Referer", it) }
                setRequestProperty("Range", "bytes=0-${maximumBytes - 1}")
            }
            val status = connection.responseCode
            if (allowRangeFallback && status in RANGE_FALLBACK_STATUSES) {
                connection.disconnect()
                connection = null
                return request(url, userAgent, referer, maximumBytes, allowRangeFallback = false)
            }
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = input?.use { readLimited(it, maximumBytes) } ?: ByteArray(0)
            val range = status == HttpURLConnection.HTTP_PARTIAL ||
                connection.getHeaderField("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true
            val finalUrl = connection.url ?: url
            return HttpSample(
                finalUrl,
                status,
                connection.contentType,
                connection.getHeaderFieldLong("Content-Length", -1L).takeIf { it >= 0L },
                range,
                finalUrl.toExternalForm() != url.toExternalForm(),
                body
            )
        } finally {
            connection?.disconnect()
        }
    }

    private fun probeHlsMedia(
        manifestUrl: URL,
        manifest: String,
        userAgent: String?,
        referer: String?,
        depth: Int
    ): HlsProbe {
        val reference = findHlsMediaReference(manifest)
            ?: return HlsProbe(
                StreamProbeOutcome.INCONCLUSIVE, false, false,
                "HLS manifest loaded, but no variant or media segment was found."
            )
        val sample = request(URL(manifestUrl, reference), userAgent, referer, MAX_SEGMENT_BYTES)
        classifyStatus(sample.statusCode)?.let { outcome ->
            return HlsProbe(outcome, sample.rangeSupported, sample.redirected, statusMessage(sample.statusCode, outcome))
        }
        if (sample.statusCode !in 200..299 || sample.body.isEmpty()) {
            return HlsProbe(
                StreamProbeOutcome.INCONCLUSIVE, sample.rangeSupported, sample.redirected,
                "HLS media response was not conclusive (HTTP ${sample.statusCode})."
            )
        }

        val preview = sample.bodyText().trimStart('\uFEFF', ' ', '\r', '\n')
        val nested = preview.startsWith("#EXTM3U", ignoreCase = true) ||
            sample.contentType?.contains("mpegurl", ignoreCase = true) == true ||
            sample.contentType?.contains("vnd.apple.mpegurl", ignoreCase = true) == true
        if (nested) {
            if (depth >= MAX_HLS_NESTED_PLAYLISTS) {
                return HlsProbe(
                    StreamProbeOutcome.INCONCLUSIVE, sample.rangeSupported, sample.redirected,
                    "Nested HLS playlist depth exceeded; no media segment was confirmed."
                )
            }
            val nestedResult = probeHlsMedia(sample.finalUrl, sample.bodyText(), userAgent, referer, depth + 1)
            return nestedResult.copy(
                rangeSupported = sample.rangeSupported || nestedResult.rangeSupported,
                redirected = sample.redirected || nestedResult.redirected
            )
        }

        return if (looksLikeMedia(sample.contentType, sample.body) &&
            !looksLikeTextError(sample.contentType, sample.body)
        ) {
            HlsProbe(StreamProbeOutcome.ONLINE, sample.rangeSupported, sample.redirected, null)
        } else {
            HlsProbe(
                StreamProbeOutcome.INCONCLUSIVE, sample.rangeSupported, sample.redirected,
                "HLS manifest loaded, but its first referenced response was not confirmed as media."
            )
        }
    }

    private fun findHlsMediaReference(manifest: String): String? {
        var expectUri = false
        manifest.lineSequence().map(String::trim).filter(String::isNotEmpty).forEach { line ->
            if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ||
                line.startsWith("#EXTINF", ignoreCase = true) ||
                line.startsWith("#EXT-X-PART:", ignoreCase = true)
            ) {
                expectUri = true
            } else if (!line.startsWith("#")) {
                if (expectUri || line.contains('.') || line.contains('/')) return line
            }
        }
        return null
    }

    private fun classifyStatus(status: Int): StreamProbeOutcome? = when (status) {
        401, 403, 407, 429 -> StreamProbeOutcome.RESTRICTED
        404, 410 -> StreamProbeOutcome.OFFLINE
        else -> null
    }

    private fun statusMessage(status: Int, outcome: StreamProbeOutcome): String = when (outcome) {
        StreamProbeOutcome.RESTRICTED -> when (status) {
            429 -> "Provider rate-limited this request (HTTP 429)."
            407 -> "A proxy requires authentication (HTTP 407)."
            else -> "Provider restricted this request (HTTP $status)."
        }
        StreamProbeOutcome.OFFLINE -> "The server says this endpoint is unavailable (HTTP $status)."
        else -> "The server response is inconclusive (HTTP $status)."
    }

    private fun looksLikeMedia(contentType: String?, bytes: ByteArray): Boolean {
        val type = contentType.orEmpty().lowercase()
        if (type.startsWith("video/") || type.startsWith("audio/") ||
            type.contains("mp2t") || type.contains("mp4") || type.contains("mpeg") ||
            type.contains("vnd.apple") || type.contains("mpegurl")
        ) return true
        if (bytes.size >= 8) {
            if (bytes[0] == 0x47.toByte() ||
                (bytes.size > 188 && bytes[188] == 0x47.toByte()) ||
                (bytes.size > 376 && bytes[376] == 0x47.toByte())
            ) return true
            val box = String(bytes, 4, 4, StandardCharsets.US_ASCII)
            if (box == "ftyp" || box == "moof" || box == "styp") return true
            val signature = String(bytes, 0, 4, StandardCharsets.US_ASCII)
            if (signature == "ID3" || signature == "OggS" || signature == "FLV ") return true
        }
        return bytes.isNotEmpty() && printableRatio(bytes) < BINARY_PRINTABLE_RATIO
    }

    private fun looksLikeTextError(contentType: String?, bytes: ByteArray): Boolean {
        val type = contentType.orEmpty().lowercase()
        if (type.contains("text/html") || type.contains("application/json") ||
            type.contains("text/json") || type.contains("application/xml")
        ) return true
        val preview = String(bytes, 0, minOf(bytes.size, 512), StandardCharsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (preview.startsWith("<!doctype html", true) || preview.startsWith("<html", true) ||
            preview.startsWith("{") || preview.startsWith("[") || preview.startsWith("<?xml", true) ||
            preview.startsWith("error", true) || preview.startsWith("unauthorized", true) ||
            preview.startsWith("forbidden", true) || preview.startsWith("access denied", true)
        ) return true
        return type.startsWith("text/") && printableRatio(bytes) >= BINARY_PRINTABLE_RATIO
    }

    private fun printableRatio(bytes: ByteArray): Double {
        if (bytes.isEmpty()) return 1.0
        val size = minOf(bytes.size, 2048)
        val printable = bytes.take(size).count { byte ->
            val value = byte.toInt() and 0xff
            value == 9 || value == 10 || value == 13 || value in 32..126 || value >= 160
        }
        return printable.toDouble() / size
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

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L

    private fun failed(endpoint: String, started: Long, message: String) = StreamDiagnosticResult(
        endpoint = endpoint,
        statusCode = null,
        elapsedMs = elapsedMs(started),
        contentType = null,
        contentLength = null,
        rangeSupported = false,
        firstByteReceived = false,
        redirect = false,
        error = message,
        outcome = StreamProbeOutcome.INCONCLUSIVE,
        validatedSegment = false,
        finalEndpoint = endpoint
    )

    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 6_000
    private const val MAX_PROBE_BYTES = 8 * 1024
    private const val MAX_PLAYLIST_PROBE_BYTES = 64 * 1024
    private const val MAX_SEGMENT_BYTES = 4 * 1024
    private const val MAX_HLS_NESTED_PLAYLISTS = 2
    private const val BINARY_PRINTABLE_RATIO = 0.85
    private val RANGE_FALLBACK_STATUSES = setOf(400, 405, 416)
}
