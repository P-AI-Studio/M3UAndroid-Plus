/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Programme
import com.m3u.data.service.MediaCommand
import com.m3u.smartphone.ui.common.helper.LocalHelper
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private enum class AdvancedTab(val label: String) {
    SEARCH("Global search"),
    BULK("Bulk channels"),
    BULK_GROUPS("Bulk groups"),
    LAYOUT("Home layout"),
    EPG("EPG guide"),
    DIAGNOSTICS("Connection test"),
    PARENTAL("Parental PIN"),
    SERVICES("Groups")
}

@Composable
internal fun AdvancedToolsRoute(
    navigateToChannel: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: String = "SEARCH",
    viewModel: AdvancedToolsViewModel = hiltViewModel()
) {
    var selectedTab by remember(initialTab) {
        mutableStateOf(AdvancedTab.entries.firstOrNull { it.name == initialTab } ?: AdvancedTab.SEARCH)
    }
    Column(modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text("Advanced tools", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Search, bulk import, EPG, low-impact stream checks and service health",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ScrollableTabRow(selectedTabIndex = AdvancedTab.entries.indexOf(selectedTab)) {
            AdvancedTab.entries.forEach { tab ->
                androidx.compose.material3.Tab(
                    selected = selectedTab == tab,
                    onClick = { selectedTab = tab },
                    text = { Text(tab.label) }
                )
            }
        }
        when (selectedTab) {
            AdvancedTab.SEARCH -> GlobalSearchPane(viewModel, navigateToChannel)
            AdvancedTab.BULK -> BulkLinksPane(viewModel)
            AdvancedTab.BULK_GROUPS -> BulkGroupsPane(viewModel)
            AdvancedTab.LAYOUT -> HomeLayoutPane(viewModel)
            AdvancedTab.EPG -> EpgPane(viewModel)
            AdvancedTab.DIAGNOSTICS -> DiagnosticsPane(viewModel, navigateToChannel)
            AdvancedTab.PARENTAL -> ParentalPane()
            AdvancedTab.SERVICES -> ServicesPane(viewModel)
        }
    }
}

@Composable
private fun GlobalSearchPane(
    viewModel: AdvancedToolsViewModel,
    navigateToChannel: () -> Unit
) {
    val helper = LocalHelper.current
    val context = LocalContext.current
    val pinStore = remember(context) { ParentalPinStore(context) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var pinEntry by remember { mutableStateOf("") }
    var unlocked by remember { mutableStateOf(!pinStore.hasPin()) }
    var pinPrompt by remember { mutableStateOf(false) }
    val lockedCategories = remember { pinStore.lockedCategories() }
    val results = remember(query) { viewModel.searchChannels(query) }.collectAsLazyPagingItems()

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search all channels") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (!unlocked && lockedCategories.isNotEmpty()) {
            Text("Some categories are PIN-protected.", color = MaterialTheme.colorScheme.secondary)
            if (pinPrompt) {
                OutlinedTextField(pinEntry, { pinEntry = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") })
                Button(onClick = {
                    unlocked = pinStore.verifyPin(pinEntry)
                    pinPrompt = !unlocked
                    pinEntry = ""
                }) { Text("Unlock protected search results") }
            } else {
                Button(onClick = { pinPrompt = true }) { Text("Enter parental PIN") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results.itemCount) { index ->
                val channel = results[index] ?: return@items
                if (!unlocked && channel.category.trim().lowercase(Locale.ROOT) in lockedCategories) return@items
                ChannelResultCard(channel) {
                    scope.launch {
                        helper.play(MediaCommand.Common(channel.id))
                        navigateToChannel()
                    }
                }
            }
            if (results.loadState.append.endOfPaginationReached && results.itemCount == 0) {
                item { Text("No matching channels. Add or refresh a playlist first.") }
            }
        }
    }
}

@Composable
private fun ChannelResultCard(channel: Channel, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(channel.title, style = MaterialTheme.typography.titleMedium)
            Text(channel.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BulkLinksPane(viewModel: AdvancedToolsViewModel) {
    var title by remember { mutableStateOf("Bulk imported links") }
    var rawLinks by remember { mutableStateOf("") }
    val message by viewModel.operationMessage.collectAsStateWithLifecycle()
    val parsed = remember(rawLinks) { BulkM3uBuilder.parse(rawLinks) }

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Paste one stream URL per line. Each accepted URL becomes channel 1, 2, 3… automatically.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = title,
            onValueChange = { title = it.take(80) },
            label = { Text("Playlist name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = rawLinks,
            onValueChange = { rawLinks = it },
            label = { Text("Paste up to 100 HTTP/HTTPS links") },
            minLines = 5,
            maxLines = 9,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Valid unique: ${parsed.urls.size} · Duplicates: ${parsed.duplicateCount} · Invalid lines: ${parsed.invalidCount}",
            style = MaterialTheme.typography.bodySmall
        )
        parsed.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.importBulkLinks(title, rawLinks) },
            enabled = parsed.urls.isNotEmpty() && parsed.error == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Import ${parsed.urls.size} numbered links") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Text("Preview", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(parsed.urls.take(20).withIndex().toList()) { indexed ->
                Text("${indexed.index + 1}. ${safeDisplayUrl(indexed.value)}", style = MaterialTheme.typography.bodySmall)
            }
            if (parsed.urls.size > 20) item { Text("…and ${parsed.urls.size - 20} more") }
        }
    }
}

private fun safeDisplayUrl(raw: String): String = runCatching {
    val url = java.net.URL(raw)
    "${url.protocol}://${url.host}${if (url.port > 0) ":${url.port}" else ""}${url.path.take(36)}"
}.getOrDefault("URL hidden")

@Composable
private fun BulkGroupsPane(viewModel: AdvancedToolsViewModel) {
    var rawLinks by remember { mutableStateOf("") }
    val message by viewModel.operationMessage.collectAsStateWithLifecycle()
    val parsed = remember(rawLinks) { BulkPlaylistBuilder.parse(rawLinks) }

    Column(
        Modifier.fillMaxSize().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "One complete playlist URL per line. Every line becomes a separate group with its own channels.",
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = rawLinks,
            onValueChange = { rawLinks = it },
            label = { Text("Paste up to 100 playlist links") },
            minLines = 5,
            maxLines = 9,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Groups: ${parsed.urls.size} · Duplicates: ${parsed.duplicateCount} · Invalid lines: ${parsed.invalidCount}",
            style = MaterialTheme.typography.bodySmall
        )
        parsed.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = { viewModel.importBulkPlaylists(rawLinks) },
            enabled = parsed.urls.isNotEmpty() && parsed.error == null,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Import ${parsed.urls.size} separate groups") }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Text("Preview", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            items(parsed.urls.withIndex().toList()) { indexed ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(BulkPlaylistBuilder.groupTitle(indexed.index), style = MaterialTheme.typography.titleSmall)
                        Text(BulkPlaylistBuilder.safeDisplayUrl(indexed.value), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeLayoutPane(viewModel: AdvancedToolsViewModel) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyMap())
    val savedGroupOrder by viewModel.playlistOrder.collectAsStateWithLifecycle()
    val liveGroupUrls = remember(playlists) { playlists.keys.map { it.url } }
    val orderedGroupUrls = remember(liveGroupUrls, savedGroupOrder) {
        savedGroupOrder.filter { it in liveGroupUrls } + liveGroupUrls.filterNot { it in savedGroupOrder }
    }
    var selectedUrl by remember(playlists) {
        mutableStateOf(playlists.keys.firstOrNull()?.url.orEmpty())
    }
    var menuExpanded by remember { mutableStateOf(false) }
    val selected = playlists.keys.firstOrNull { it.url == selectedUrl }
    val categories by remember(selectedUrl) {
        viewModel.categoriesForPlaylist(selectedUrl)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var pinnedOrder by remember(selectedUrl) {
        mutableStateOf(selected?.pinnedCategories.orEmpty())
    }

    LaunchedEffect(selectedUrl, selected?.pinnedCategories, categories) {
        pinnedOrder = selected?.pinnedCategories.orEmpty().filter { it in categories }
    }

    Column(
        Modifier.fillMaxSize().padding(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Choose which categories appear first on the home screen. Pinned categories follow the order below.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(selected?.title ?: "Choose a group")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            playlists.forEach { (playlist, count) ->
                DropdownMenuItem(
                    text = { Text("${playlist.title} ($count)") },
                    onClick = {
                        selectedUrl = playlist.url
                        menuExpanded = false
                    }
                )
            }
        }
        Text("Pinned categories", style = MaterialTheme.typography.titleSmall)
        if (pinnedOrder.isEmpty()) {
            Text("No pinned categories yet. Pin one from the list below.", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item { Text("Home group order", style = MaterialTheme.typography.titleSmall) }
            items(orderedGroupUrls, key = { "group-$it" }) { groupUrl ->
                val group = playlists.keys.firstOrNull { it.url == groupUrl }
                val count = group?.let { playlists[it] } ?: 0
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(group?.title ?: "Playlist")
                            Text("$count channels", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = {
                                val index = orderedGroupUrls.indexOf(groupUrl)
                                if (index > 0) {
                                    val updated = orderedGroupUrls.toMutableList()
                                    val previous = updated[index - 1]
                                    updated[index - 1] = groupUrl
                                    updated[index] = previous
                                    viewModel.reorderPlaylists(updated)
                                }
                            },
                            enabled = orderedGroupUrls.indexOf(groupUrl) > 0
                        ) { Text("↑") }
                        Button(
                            onClick = {
                                val index = orderedGroupUrls.indexOf(groupUrl)
                                if (index >= 0 && index < orderedGroupUrls.lastIndex) {
                                    val updated = orderedGroupUrls.toMutableList()
                                    val next = updated[index + 1]
                                    updated[index + 1] = groupUrl
                                    updated[index] = next
                                    viewModel.reorderPlaylists(updated)
                                }
                            },
                            enabled = orderedGroupUrls.indexOf(groupUrl) in 0 until orderedGroupUrls.lastIndex
                        ) { Text("↓") }
                    }
                }
            }
            item { Text("Pinned categories", style = MaterialTheme.typography.titleSmall) }
            items(pinnedOrder, key = { "pinned-$it" }) { category ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(category, modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                val index = pinnedOrder.indexOf(category)
                                if (index > 0) {
                                    val updated = pinnedOrder.toMutableList()
                                    val previous = updated[index - 1]
                                    updated[index - 1] = category
                                    updated[index] = previous
                                    pinnedOrder = updated
                                    viewModel.setPinnedCategories(selectedUrl, updated)
                                }
                            },
                            enabled = pinnedOrder.indexOf(category) > 0
                        ) { Text("↑") }
                        Button(
                            onClick = {
                                val index = pinnedOrder.indexOf(category)
                                if (index >= 0 && index < pinnedOrder.lastIndex) {
                                    val updated = pinnedOrder.toMutableList()
                                    val next = updated[index + 1]
                                    updated[index + 1] = category
                                    updated[index] = next
                                    pinnedOrder = updated
                                    viewModel.setPinnedCategories(selectedUrl, updated)
                                }
                            },
                            enabled = pinnedOrder.indexOf(category) in 0 until pinnedOrder.lastIndex
                        ) { Text("↓") }
                        Button(onClick = {
                            val updated = pinnedOrder - category
                            pinnedOrder = updated
                            viewModel.setPinnedCategories(selectedUrl, updated)
                        }) { Text("Unpin") }
                    }
                }
            }
            item { Text("All categories", style = MaterialTheme.typography.titleSmall) }
            items(categories.filterNot { it in pinnedOrder }, key = { "available-$it" }) { category ->
                Card(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Text(category, modifier = Modifier.weight(1f))
                        Button(onClick = {
                            val updated = pinnedOrder + category
                            pinnedOrder = updated
                            viewModel.setPinnedCategories(selectedUrl, updated)
                        }) { Text("Pin to top") }
                    }
                }
            }
        }
        Text("Unpinned categories keep their normal order.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EpgPane(viewModel: AdvancedToolsViewModel) {
    val epgs by viewModel.epgPlaylists.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyMap())
    var epgUrl by remember(epgs) { mutableStateOf(epgs.firstOrNull()?.url.orEmpty()) }
    var epgMenuExpanded by remember { mutableStateOf(false) }
    var playlistUrl by remember(playlists) { mutableStateOf(playlists.keys.firstOrNull()?.url.orEmpty()) }
    var playlistMenuExpanded by remember { mutableStateOf(false) }
    val sourcePlaylist = playlists.keys.firstOrNull { it.url == playlistUrl }
    val channels by remember(playlistUrl) {
        viewModel.channelsForPlaylist(playlistUrl)
    }.collectAsStateWithLifecycle(initialValue = emptyList())
    var channelSearch by remember { mutableStateOf("") }
    var channelMenuExpanded by remember { mutableStateOf(false) }
    var relationId by remember { mutableStateOf("") }
    var selectedChannelTitle by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Choose a playlist and select one of its channels. The EPG ID is filled automatically when the playlist contains one.",
            style = MaterialTheme.typography.bodySmall
        )
        Button(onClick = { playlistMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(sourcePlaylist?.title ?: "Choose channel group")
        }
        DropdownMenu(expanded = playlistMenuExpanded, onDismissRequest = { playlistMenuExpanded = false }) {
            playlists.forEach { (playlist, count) ->
                DropdownMenuItem(
                    text = { Text("${playlist.title} ($count channels)") },
                    onClick = {
                        playlistUrl = playlist.url
                        playlistMenuExpanded = false
                        selectedChannelTitle = ""
                        relationId = ""
                    }
                )
            }
        }
        OutlinedTextField(
            value = channelSearch,
            onValueChange = { channelSearch = it; channelMenuExpanded = true },
            label = { Text("Find a channel") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = { channelMenuExpanded = true },
            enabled = channels.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (selectedChannelTitle.isBlank()) "Select channel" else selectedChannelTitle) }
        DropdownMenu(expanded = channelMenuExpanded, onDismissRequest = { channelMenuExpanded = false }) {
            channels.asSequence()
                .filter { channelSearch.isBlank() || it.title.contains(channelSearch, ignoreCase = true) || it.category.contains(channelSearch, ignoreCase = true) }
                .take(35)
                .forEach { channel ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(channel.title)
                                if (channel.category.isNotBlank()) {
                                    Text(channel.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        },
                        onClick = {
                            selectedChannelTitle = channel.title
                            relationId = channel.relationId.orEmpty()
                            channelSearch = channel.title
                            channelMenuExpanded = false
                        }
                    )
                }
        }
        if (selectedChannelTitle.isNotBlank() && relationId.isBlank()) {
            Text(
                "This channel has no saved EPG ID. Enter it manually below if your provider documents one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        OutlinedTextField(
            value = relationId,
            onValueChange = { relationId = it },
            label = { Text("Channel TVG / EPG ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { epgMenuExpanded = true }, modifier = Modifier.fillMaxWidth(), enabled = epgs.isNotEmpty()) {
            val selectedEpg = epgs.firstOrNull { it.url == epgUrl }
            Text(selectedEpg?.title ?: "Choose EPG source")
        }
        DropdownMenu(expanded = epgMenuExpanded, onDismissRequest = { epgMenuExpanded = false }) {
            epgs.forEach { epg ->
                DropdownMenuItem(
                    text = { Text(epg.title) },
                    onClick = { epgUrl = epg.url; epgMenuExpanded = false }
                )
            }
        }
        if (epgs.isEmpty()) {
            Text("No EPG source is configured. Add one from Settings → Playlist Management → EPG.")
        }
        if (epgUrl.isNotBlank() && relationId.isNotBlank()) {
            val programmes = remember(epgUrl, relationId) { viewModel.programmes(epgUrl, relationId) }
                .collectAsLazyPagingItems()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(programmes.itemCount) { index ->
                    val programme = programmes[index] ?: return@items
                    ProgrammeCard(programme)
                }
            }
        }
    }
}

@Composable
private fun ProgrammeCard(programme: Programme) {
    val timeFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp)) {
            Text(programme.title, style = MaterialTheme.typography.titleSmall)
            Text(
                "${timeFormat.format(Date(programme.start))} – ${timeFormat.format(Date(programme.end))}",
                style = MaterialTheme.typography.bodySmall
            )
            if (programme.description.isNotBlank()) {
                Text(programme.description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DiagnosticsPane(
    viewModel: AdvancedToolsViewModel,
    navigateToChannel: () -> Unit
) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyMap())
    val batch by viewModel.batchState.collectAsStateWithLifecycle()
    val helper = LocalHelper.current
    val scope = rememberCoroutineScope()
    var selectedUrl by remember(playlists) { mutableStateOf(playlists.keys.firstOrNull()?.url.orEmpty()) }
    var selectorExpanded by remember { mutableStateOf(false) }
    var concurrency by remember { mutableStateOf(3) }
    val selectedPlaylist = playlists.entries.firstOrNull { it.key.url == selectedUrl }

    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Checks every channel in the selected playlist. Channels are loaded in small pages to keep memory use stable.", style = MaterialTheme.typography.bodySmall)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(selectedPlaylist?.key?.title ?: "Choose a playlist", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { selectorExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Choose playlist") }
                DropdownMenu(expanded = selectorExpanded, onDismissRequest = { selectorExpanded = false }) {
                    playlists.forEach { (playlist, count) ->
                        DropdownMenuItem(
                            text = { Text("${playlist.title} ($count)") },
                            onClick = { selectedUrl = playlist.url; selectorExpanded = false }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (1..5).forEach { amount ->
                        Button(onClick = { concurrency = amount }, enabled = concurrency != amount) { Text("$amount") }
                    }
                }
                Text("Concurrent checks: $concurrency (maximum 5). Every channel is included.", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.startBatchDiagnostics(selectedUrl, concurrency) },
                        enabled = selectedUrl.isNotBlank() && !batch.running
                    ) { Text(if (batch.running) "Testing…" else "Test all channels") }
                    if (batch.running) {
                        Button(onClick = viewModel::cancelBatchDiagnostics) { Text("Stop") }
                    }
                }
            }
        }
        if (batch.total > 0) {
            val percent = if (batch.total == 0) 0 else (batch.completed * 100 / batch.total)
            Text(
                "Progress: ${batch.completed}/${batch.total} · $percent%",
                style = MaterialTheme.typography.labelLarge
            )
            androidx.compose.material3.LinearProgressIndicator(
                progress = { if (batch.total == 0) 0f else batch.completed.toFloat() / batch.total },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Online: ${batch.online} · Offline: ${batch.offline} · Restricted: ${batch.restricted} · Check manually: ${batch.inconclusive}",
                style = MaterialTheme.typography.bodySmall
            )
        }
        batch.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
        Text("Results · data reachability is not the same as successful video decoding", style = MaterialTheme.typography.titleSmall)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(batch.results, key = { it.channelId }) { item ->
                val success = item.result.outcome == StreamProbeOutcome.ONLINE
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            "${item.result.outcome.name} · HTTP ${item.result.statusCode ?: "—"} · ${item.result.elapsedMs} ms · ${item.result.error ?: if (item.result.validatedSegment) "HLS segment verified" else "Media endpoint answered"}",
                            color = if (success) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("Type: ${item.result.contentType ?: "unknown"} · Byte ranges: ${if (item.result.rangeSupported) "yes" else "not confirmed"}", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = {
                            scope.launch {
                                helper.play(MediaCommand.Common(item.channelId))
                                navigateToChannel()
                            }
                        }) { Text("Open in player to verify picture") }
                    }
                }
            }
        }
        Text(
            "The test follows redirects, uses the playlist User-Agent, and for HLS verifies a media segment. It does not start video decoders; use Open in player to confirm actual picture and sound.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ParentalPane() {
    val context = LocalContext.current
    val store = remember(context) { ParentalPinStore(context) }
    var pin by remember { mutableStateOf("") }
    var verify by remember { mutableStateOf("") }
    var categoriesText by remember { mutableStateOf(store.lockedCategories().joinToString(", ")) }
    var unlocked by remember { mutableStateOf(!store.hasPin()) }
    var playbackLock by remember(context) { mutableStateOf(store.playbackLockEnabled()) }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!store.hasPin()) {
            Text("Create a 4–8 digit PIN. The PIN is stored as a salted PBKDF2 hash, not as plain text.")
            OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(8) }, label = { Text("New PIN") })
            Button(onClick = {
                val saved = store.setPin(pin)
                message = if (saved) "PIN saved." else "Use 4–8 digits."
                unlocked = saved
                pin = ""
            }) { Text("Set PIN") }
        } else if (!unlocked) {
            Text("Enter your PIN to change protected categories.")
            OutlinedTextField(verify, { verify = it.filter(Char::isDigit).take(8) }, label = { Text("PIN") })
            Button(onClick = {
                unlocked = store.verifyPin(verify)
                message = if (unlocked) "Unlocked." else "Incorrect PIN."
                verify = ""
            }) { Text("Unlock") }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Require PIN before playback", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "The Player screen pauses the stream until the PIN is verified.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = playbackLock,
                    onCheckedChange = {
                        playbackLock = it
                        store.setPlaybackLockEnabled(it)
                        message = if (it) "Playback PIN is enabled." else "Playback PIN is disabled."
                    }
                )
            }
            Text("Categories hidden from global search until the PIN is entered:")
            OutlinedTextField(
                categoriesText,
                { categoriesText = it },
                label = { Text("Comma-separated category names") },
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                store.setLockedCategories(categoriesText.split(',').map(String::trim).filter(String::isNotBlank).toSet())
                message = "Protected search categories saved."
            }) { Text("Save protected categories") }
            Button(onClick = { unlocked = false; message = "Protected search is locked." }) { Text("Lock now") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        Text(
            "This version protects category visibility in Advanced global search only. It does not yet prevent playback from every home-screen, shortcut, Android TV remote or direct-intent entry point. Do not rely on it as a complete household lock until a shared player authorization gate is added.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun ServicesPane(viewModel: AdvancedToolsViewModel) {
    val playlists by viewModel.playlists.collectAsStateWithLifecycle(initialValue = emptyMap())
    val message by viewModel.operationMessage.collectAsStateWithLifecycle()
    val sourceResults by viewModel.sourceDiagnostics.collectAsStateWithLifecycle()
    val sourceRunning by viewModel.sourceTestsRunning.collectAsStateWithLifecycle()
    val batch by viewModel.batchState.collectAsStateWithLifecycle()
    var pendingRemoval by remember { mutableStateOf<com.m3u.data.database.model.Playlist?>(null) }
    if (pendingRemoval != null) {
        val selected = pendingRemoval!!
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove playlist?") },
            text = { Text("This removes ${selected.title} and its locally stored channel data from this app.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.removePlaylist(selected.url)
                    pendingRemoval = null
                }) { Text("Remove") }
            },
            dismissButton = {
                Button(onClick = { pendingRemoval = null }) { Text("Cancel") }
            }
        )
    }
    Column(Modifier.fillMaxSize().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Configured sources", style = MaterialTheme.typography.titleMedium)
        message?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(playlists.entries.toList(), key = { it.key.url }) { entry ->
                val playlist = entry.key
                val channelCount = entry.value
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(playlist.title, style = MaterialTheme.typography.titleMedium)
                        Text("${playlist.source.value.uppercase()} · $channelCount channels", style = MaterialTheme.typography.bodySmall)
                        Text("EPG sources: ${playlist.epgUrls.size}", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.testPlaylistSource(playlist.url) },
                                enabled = playlist.url !in sourceRunning
                            ) { Text(if (playlist.url in sourceRunning) "Testing link…" else "Test link") }
                            Button(onClick = { viewModel.startBatchDiagnostics(playlist.url, concurrency = 3) }) {
                                Text(if (batch.running && batch.playlistUrl == playlist.url) "Testing channels…" else "Test all channels")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { viewModel.refreshPlaylist(playlist.url) }) { Text("Refresh") }
                            Button(onClick = { pendingRemoval = playlist }) { Text("Remove") }
                        }
                        if (batch.playlistUrl == playlist.url && batch.total > 0) {
                            val percent = (batch.completed * 100 / batch.total).coerceIn(0, 100)
                            Text("Channel test: ${batch.completed}/${batch.total} · $percent% · Online ${batch.online} · Offline ${batch.offline} · Restricted ${batch.restricted} · Review ${batch.inconclusive}", style = MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { batch.completed.toFloat() / batch.total.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth()
                            )
                            batch.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (batch.running) {
                                Button(onClick = viewModel::cancelBatchDiagnostics) { Text("Stop test") }
                            }
                        }
                        sourceResults[playlist.url]?.let { result ->
                            Text(
                                "${result.outcome.name} · HTTP ${result.statusCode ?: "—"} · ${result.elapsedMs} ms${result.error?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                                color = when (result.outcome) {
                                    StreamProbeOutcome.ONLINE -> MaterialTheme.colorScheme.primary
                                    StreamProbeOutcome.OFFLINE -> MaterialTheme.colorScheme.error
                                    StreamProbeOutcome.RESTRICTED -> MaterialTheme.colorScheme.tertiary
                                    StreamProbeOutcome.INCONCLUSIVE -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
            if (playlists.isEmpty()) item { Text("No playlists configured yet.") }
        }
    }
}
