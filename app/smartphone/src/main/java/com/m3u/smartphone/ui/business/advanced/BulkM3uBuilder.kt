/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import java.net.URL

/** Parses one HTTP(S) stream URL per line and creates a numbered M3U playlist. */
object BulkM3uBuilder {
    const val MAX_LINKS = 100

    data class ParseResult(
        val urls: List<String>,
        val invalidCount: Int,
        val duplicateCount: Int,
        val error: String? = null
    )

    fun parse(rawText: String): ParseResult {
        val valid = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        var invalid = 0
        var duplicates = 0

        rawText.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            var candidate = rawLine.trim()
            if (candidate.isEmpty() || candidate.startsWith("#")) return@forEach
            candidate = candidate.removeSurrounding("<", ">").removeSurrounding("\"", "\"").removeSurrounding("'", "'")
            candidate = candidate.replace(Regex("^\\d+\\s*[.)-]\\s*"), "").trim()
            val accepted = runCatching {
                val parsed = URL(candidate)
                (parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) &&
                    parsed.host.isNotBlank()
            }.getOrDefault(false)
            if (!accepted) {
                invalid++
            } else if (!seen.add(candidate)) {
                duplicates++
            } else {
                valid += candidate
            }
        }

        return when {
            valid.isEmpty() -> ParseResult(emptyList(), invalid, duplicates, "No valid HTTP/HTTPS links were found.")
            valid.size > MAX_LINKS -> ParseResult(
                valid.take(MAX_LINKS), invalid, duplicates,
                "A batch can contain at most $MAX_LINKS unique links. Split the list into smaller batches."
            )
            else -> ParseResult(valid, invalid, duplicates)
        }
    }

    fun toM3u(urls: List<String>): String = buildString {
        appendLine("#EXTM3U")
        urls.forEachIndexed { index, url ->
            appendLine("#EXTINF:-1,${index + 1}")
            appendLine(url)
        }
    }
}
