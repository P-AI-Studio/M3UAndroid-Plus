/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.m3u.smartphone.ui.business.advanced

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.work.WorkManager
import com.m3u.data.database.model.Channel
import com.m3u.data.database.model.Playlist
import com.m3u.data.database.model.Programme
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.repository.programme.ProgrammeRepository
import com.m3u.data.worker.SubscriptionWorker
import com.m3u.data.parser.xtream.XtreamInput
import com.m3u.data.parser.xtream.XtreamParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class AdvancedToolsViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val playlistRepository: PlaylistRepository,
    private val programmeRepository: ProgrammeRepository,
    private val workManager: WorkManager,
    @ApplicationContext private val context: Context
) : ViewModel() {
    val playlists: Flow<Map<Playlist, Int>> = playlistRepository.observeAllCounts()
    val epgPlaylists: Flow<List<Playlist>> = playlistRepository.observeAllEpgs()

    private val _operationMessage = MutableStateFlow<String?>(null)
    val operationMessage: StateFlow<String?> = _operationMessage.asStateFlow()

    private val _diagnostic = MutableStateFlow<StreamDiagnosticResult?>(null)
    val diagnostic: StateFlow<StreamDiagnosticResult?> = _diagnostic.asStateFlow()

    private val _diagnosticRunning = MutableStateFlow(false)
    val diagnosticRunning: StateFlow<Boolean> = _diagnosticRunning.asStateFlow()

    data class BatchItem(
        val channelId: Int,
        val title: String,
        val category: String,
        val result: StreamDiagnosticResult
    )

    data class BatchState(
        val total: Int = 0,
        val completed: Int = 0,
        val online: Int = 0,
        val offline: Int = 0,
        val restricted: Int = 0,
        val inconclusive: Int = 0,
        val running: Boolean = false,
        val results: List<BatchItem> = emptyList(),
        val message: String? = null
    )

    private val _batchState = MutableStateFlow(BatchState())
    val batchState: StateFlow<BatchState> = _batchState.asStateFlow()
    private var batchJob: Job? = null

    private val _sourceDiagnostics = MutableStateFlow<Map<String, StreamDiagnosticResult>>(emptyMap())
    val sourceDiagnostics: StateFlow<Map<String, StreamDiagnosticResult>> = _sourceDiagnostics.asStateFlow()

    private val _sourceTestsRunning = MutableStateFlow<Set<String>>(emptySet())
    val sourceTestsRunning: StateFlow<Set<String>> = _sourceTestsRunning.asStateFlow()

    private val playlistOrderStore = PlaylistOrderStore(context)
    val playlistOrder: StateFlow<List<String>> = playlistOrderStore.order

    fun reorderPlaylists(urls: List<String>) {
        playlistOrderStore.saveOrder(urls)
    }

    fun orderedPlaylists(playlists: Map<Playlist, Int>): Map<Playlist, Int> =
        playlistOrderStore.orderPlaylists(playlists, playlistOrderStore.order.value)

    fun searchChannels(query: String): Flow<PagingData<Channel>> {
        val normalized = query.trim()
        if (normalized.isBlank()) return flowOf(PagingData.empty<Channel>())
        return flow {
            delay(250)
            emitAll(
                Pager(
                    config = PagingConfig(pageSize = 40, initialLoadSize = 80, enablePlaceholders = false)
                ) {
                    channelRepository.search(normalized)
                }.flow
            )
        }.cachedIn(viewModelScope)
    }

    fun programmes(epgUrl: String, tvgId: String): Flow<PagingData<Programme>> =
        programmeRepository.pagingProgrammes(epgUrl, tvgId.trim())
            .cachedIn(viewModelScope)

    fun channelsForPlaylist(playlistUrl: String): Flow<List<Channel>> = flow {
        emit(withContext(Dispatchers.IO) {
            channelRepository.getByPlaylistUrl(playlistUrl)
        })
    }

    fun importBulkLinks(title: String, rawLinks: String) {
        viewModelScope.launch {
            val parsed = BulkM3uBuilder.parse(rawLinks)
            if (parsed.error != null) {
                _operationMessage.value = parsed.error
                return@launch
            }
            val safeTitle = title.trim().ifBlank { "Bulk imported links" }.take(80)
            runCatching {
                val directory = File(context.filesDir, "bulk-playlists").apply {
                    if (!exists() && !mkdirs()) error("Could not create private import directory")
                }
                val playlistFile = File(directory, "bulk-${System.currentTimeMillis()}.m3u")
                playlistFile.writeText(BulkM3uBuilder.toM3u(parsed.urls), Charsets.UTF_8)
                // The file lives in the app-private files directory and is read by the existing subscription worker.
                SubscriptionWorker.m3u(workManager, safeTitle, Uri.fromFile(playlistFile).toString())
                _operationMessage.value = buildString {
                    append("Queued ${parsed.urls.size} numbered links for import.")
                    if (parsed.invalidCount > 0) append(" Skipped ${parsed.invalidCount} invalid lines.")
                    if (parsed.duplicateCount > 0) append(" Removed ${parsed.duplicateCount} duplicates.")
                }
            }.onFailure {
                _operationMessage.value = "Bulk import could not be queued: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun importBulkPlaylists(rawLinks: String) {
        viewModelScope.launch {
            val parsed = BulkPlaylistBuilder.parse(rawLinks)
            if (parsed.error != null) {
                _operationMessage.value = parsed.error
                return@launch
            }
            var queued = 0
            var failed = 0
            parsed.urls.forEachIndexed { index, url ->
                val title = BulkPlaylistBuilder.groupTitle(index)
                val result = runCatching {
                    val xtream = XtreamInput.decodeFromPlaylistUrlOrNull(url)
                    if (xtream != null) {
                        SubscriptionWorker.xtream(
                            workManager = workManager,
                            title = title,
                            url = url,
                            basicUrl = xtream.basicUrl,
                            username = xtream.username,
                            password = xtream.password
                        )
                    } else {
                        SubscriptionWorker.m3u(workManager, title, url)
                    }
                }
                if (result.isSuccess) queued++ else failed++
            }
            _operationMessage.value = buildString {
                append("Queued $queued independent numbered groups.")
                if (parsed.invalidCount > 0) append(" Skipped ${parsed.invalidCount} invalid lines.")
                if (parsed.duplicateCount > 0) append(" Removed ${parsed.duplicateCount} duplicate links.")
                if (failed > 0) append(" Failed to queue $failed groups.")
            }
        }
    }

    fun startBatchDiagnostics(playlistUrl: String, concurrency: Int = 2) {
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            val safeConcurrency = concurrency.coerceIn(1, 3)
            _batchState.value = BatchState(running = true, message = "Counting every channel in this playlist…")
            val playlist = runCatching { playlistRepository.get(playlistUrl) }.getOrNull()
            val total = runCatching {
                withContext(Dispatchers.IO) { channelRepository.getCountByPlaylistUrl(playlistUrl) }
            }.getOrElse {
                _batchState.value = BatchState(message = "Could not count playlist channels: database read failed.")
                return@launch
            }
            if (total == 0) {
                _batchState.value = BatchState(message = "This playlist has no channels to test.")
                return@launch
            }

            _batchState.value = BatchState(
                total = total,
                running = true,
                message = "Testing all $total channels in bounded pages with $safeConcurrency concurrent checks…"
            )
            val semaphore = Semaphore(safeConcurrency)
            val pageSize = 100
            var lastChannelId = 0
            try {
                while (_batchState.value.completed < total) {
                    val page = withContext(Dispatchers.IO) {
                        channelRepository.getPageByPlaylistUrl(playlistUrl, pageSize, lastChannelId)
                    }
                    if (page.isEmpty()) break
                    coroutineScope {
                        page.map { channel ->
                            async(Dispatchers.IO) {
                                semaphore.withPermit {
                                    val firstAttempt = StreamDiagnostics.probe(
                                        rawUrl = channel.url,
                                        userAgent = playlist?.userAgent
                                    )
                                    val result = if (
                                        firstAttempt.outcome == StreamProbeOutcome.INCONCLUSIVE ||
                                        firstAttempt.statusCode == null ||
                                        firstAttempt.statusCode in 500..599
                                    ) {
                                        // A single low-rate retry separates transient provider/network failures
                                        // from a consistently unavailable stream without flooding the server.
                                        delay(500)
                                        StreamDiagnostics.probe(
                                            rawUrl = channel.url,
                                            userAgent = playlist?.userAgent
                                        )
                                    } else {
                                        firstAttempt
                                    }
                                    _batchState.update { state ->
                                        state.copy(
                                            completed = state.completed + 1,
                                            online = state.online + if (result.outcome == StreamProbeOutcome.ONLINE) 1 else 0,
                                            offline = state.offline + if (result.outcome == StreamProbeOutcome.OFFLINE) 1 else 0,
                                            restricted = state.restricted + if (result.outcome == StreamProbeOutcome.RESTRICTED) 1 else 0,
                                            inconclusive = state.inconclusive + if (result.outcome == StreamProbeOutcome.INCONCLUSIVE) 1 else 0,
                                            results = state.results + BatchItem(
                                                channelId = channel.id,
                                                title = channel.title,
                                                category = channel.category,
                                                result = result
                                            )
                                        )
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                    lastChannelId = page.last().id
                }
                _batchState.update { state ->
                    state.copy(
                        running = false,
                        message = if (state.completed == total) {
                            "Finished every channel: ${state.completed}/$total checked. ONLINE means the media endpoint (and one HLS segment when applicable) answered; it is not a decoder guarantee."
                        } else {
                            "The playlist changed during the test. ${state.completed} of $total channels were checked; run it again for the latest list."
                        }
                    )
                }
            } catch (cancelled: CancellationException) {
                _batchState.update { it.copy(running = false, message = "Test stopped after ${it.completed} of $total channels.") }
                throw cancelled
            } catch (exception: Exception) {
                _batchState.update {
                    it.copy(
                        running = false,
                        message = "Test interrupted after ${it.completed} of $total channels: ${exception.javaClass.simpleName}"
                    )
                }
            }
        }
    }

    fun categoriesForPlaylist(playlistUrl: String): Flow<List<String>> =
        playlistRepository.observeCategoriesByPlaylistUrlIgnoreHidden(playlistUrl, "")

    fun setPinnedCategories(playlistUrl: String, categories: List<String>) {
        viewModelScope.launch {
            playlistRepository.reorderPinnedCategories(playlistUrl, categories.distinct())
        }
    }

    fun testPlaylistSource(playlistUrl: String) {
        if (playlistUrl in _sourceTestsRunning.value) return
        _sourceTestsRunning.update { it + playlistUrl }
        viewModelScope.launch {
            try {
                val playlist = withContext(Dispatchers.IO) { playlistRepository.get(playlistUrl) }
                val result = if (playlist?.source == com.m3u.data.database.model.DataSource.Xtream) {
                    val input = XtreamInput.decodeFromPlaylistUrlOrNull(playlistUrl)
                        ?: error("Invalid Xtream source URL")
                    withContext(Dispatchers.IO) {
                        try {
                            val info = xtreamParser.getInfo(input)
                            val accountStatus = info.userInfo.status.orEmpty()
                            val active = accountStatus.equals("Active", ignoreCase = true) ||
                                accountStatus.equals("Enabled", ignoreCase = true)
                            val sessionInfo = buildString {
                                append("Xtream account status: ")
                                append(accountStatus.ifBlank { "not provided" })
                                val activeConnections = info.userInfo.activeCons
                                val maxConnections = info.userInfo.maxConnections
                                if (!activeConnections.isNullOrBlank() || !maxConnections.isNullOrBlank()) {
                                    append(" · sessions ")
                                    append(activeConnections ?: "?")
                                    append("/")
                                    append(maxConnections ?: "?")
                                }
                            }
                            StreamDiagnosticResult(
                                endpoint = input.basicUrl,
                                statusCode = 200,
                                elapsedMs = 0L,
                                contentType = "application/json",
                                contentLength = null,
                                rangeSupported = false,
                                firstByteReceived = true,
                                redirect = false,
                                error = sessionInfo,
                                outcome = if (active) StreamProbeOutcome.ONLINE else StreamProbeOutcome.RESTRICTED
                            )
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (exception: Exception) {
                            StreamDiagnosticResult(
                                endpoint = input.basicUrl,
                                statusCode = null,
                                elapsedMs = 0L,
                                contentType = null,
                                contentLength = null,
                                rangeSupported = false,
                                firstByteReceived = false,
                                redirect = false,
                                error = "Xtream account check failed: ${exception.javaClass.simpleName}",
                                outcome = StreamProbeOutcome.OFFLINE
                            )
                        }
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        StreamDiagnostics.probe(playlistUrl, playlist?.userAgent)
                    }
                }
                _sourceDiagnostics.update { it + (playlistUrl to result) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                _sourceDiagnostics.update {
                    it + (playlistUrl to StreamDiagnosticResult(
                        endpoint = "(hidden)",
                        statusCode = null,
                        elapsedMs = 0L,
                        contentType = null,
                        contentLength = null,
                        rangeSupported = false,
                        firstByteReceived = false,
                        redirect = false,
                        error = "Connection test failed: ${exception.javaClass.simpleName}",
                        outcome = StreamProbeOutcome.INCONCLUSIVE
                    ))
                }
            } finally {
                _sourceTestsRunning.update { it - playlistUrl }
            }
        }
    }

    fun refreshPlaylist(url: String) {
        viewModelScope.launch {
            _operationMessage.value = "Refreshing playlist…"
            runCatching { playlistRepository.refresh(url) }
                .onSuccess { _operationMessage.value = "Refresh request completed." }
                .onFailure { _operationMessage.value = "Refresh failed: ${it.message ?: "unknown error"}" }
        }
    }

    fun removePlaylist(url: String) {
        viewModelScope.launch {
            _operationMessage.value = "Removing playlist…"
            runCatching { playlistRepository.unsubscribe(url) }
                .onSuccess { _operationMessage.value = "Playlist removed." }
                .onFailure { _operationMessage.value = "Remove failed: ${it.message ?: "unknown error"}" }
        }
    }
}
