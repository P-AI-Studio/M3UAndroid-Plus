/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import java.net.URL

/** One input URL becomes one independent playlist/group. */
object BulkPlaylistBuilder {
    const val MAX_GROUPS = 100

    data class ParseResult(
        val urls: List<String>,
        val invalidCount: Int,
        val duplicateCount: Int,
        val error: String? = null
    )

    fun parse(rawText: String): ParseResult {
        val accepted = mutableListOf<String>()
        val seen = linkedSetOf<String>()
        var invalid = 0
        var duplicates = 0

        rawText.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            var candidate = rawLine.trim()
            if (candidate.isEmpty() || candidate.startsWith("#")) return@forEach
            candidate = candidate.removeSurrounding("<", ">")
                .removeSurrounding("\"", "\"")
                .removeSurrounding("'", "'")
                .replace(Regex("^\\d+\\s*[.)-]\\s*"), "")
                .trim()

            val valid = runCatching {
                val parsed = URL(candidate.substringBefore('|'))
                (parsed.protocol.equals("http", true) || parsed.protocol.equals("https", true)) &&
                    parsed.host.isNotBlank()
            }.getOrDefault(false)
            if (!valid) {
                invalid++
            } else if (!seen.add(candidate)) {
                duplicates++
            } else {
                accepted += candidate
            }
        }

        return when {
            accepted.isEmpty() -> ParseResult(
                emptyList(), invalid, duplicates,
                "No valid HTTP/HTTPS playlist links were found."
            )
            accepted.size > MAX_GROUPS -> ParseResult(
                accepted.take(MAX_GROUPS), invalid, duplicates,
                "A single import can contain at most $MAX_GROUPS independent groups."
            )
            else -> ParseResult(accepted, invalid, duplicates)
        }
    }

    fun groupTitle(index: Int): String = "Group ${index + 1}"

    fun safeDisplayUrl(raw: String): String = runCatching {
        val url = URL(raw.substringBefore('|'))
        "${url.protocol}://${url.host}${if (url.port > 0) ":\${url.port}" else ""}${url.path.take(48)}"
    }.getOrDefault("URL hidden")
}
