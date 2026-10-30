/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import android.content.Context
import android.content.SharedPreferences
import com.m3u.data.database.model.Playlist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists the user's home playlist order without requiring a destructive Room migration.
 * Playlist URLs are stored locally only; no server credentials are uploaded.
 */
class PlaylistOrderStore(context: Context) {
    private val preferences = context.applicationContext
        .getSharedPreferences("m3u_plus_home_layout", Context.MODE_PRIVATE)

    private val mutableOrder = MutableStateFlow(readOrder())
    val order: StateFlow<List<String>> = mutableOrder.asStateFlow()

    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == KEY_ORDER) mutableOrder.value = readOrder()
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun saveOrder(urls: List<String>) {
        val normalized = urls.filter(String::isNotBlank).distinct()
        preferences.edit().putString(KEY_ORDER, normalized.joinToString("\n")).apply()
        mutableOrder.value = normalized
    }

    fun orderPlaylists(playlists: Map<Playlist, Int>, order: List<String>): Map<Playlist, Int> {
        val positions = order.withIndex().associate { it.value to it.index }
        return playlists.entries
            .sortedWith(
                compareBy<Map.Entry<Playlist, Int>> { positions[it.key.url] ?: Int.MAX_VALUE }
                    .thenBy { it.key.title.lowercase() }
            )
            .associate { it.key to it.value }
    }

    private fun readOrder(): List<String> =
        preferences.getString(KEY_ORDER, null)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            ?.toList()
            .orEmpty()

    private companion object {
        const val KEY_ORDER = "playlist_order_v1"
    }
}
