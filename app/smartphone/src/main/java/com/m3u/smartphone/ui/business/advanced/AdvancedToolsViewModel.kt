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
        val running: Boolean = false,
        val results: List<BatchItem> = emptyList(),
        val message: String? = null
    )

    private val _batchState = MutableStateFlow(BatchState())
    val batchState: StateFlow<BatchState> = _batchState.asStateFlow()
    private var batchJob: Job? = null

    fun searchChannels(query: String): Flow<PagingData<Channel>> {
        val normalized = query.trim()
        if (normalized.isBlank()) return flowOf(PagingData.empty<Channel>())
        return Pager(
            config = PagingConfig(pageSize = 40, initialLoadSize = 80, enablePlaceholders = false)
        ) {
            channelRepository.search(normalized)
        }.flow.cachedIn(viewModelScope)
    }

    fun programmes(epgUrl: String, tvgId: String): Flow<PagingData<Programme>> =
        programmeRepository.pagingProgrammes(epgUrl, tvgId.trim())
            .cachedIn(viewModelScope)

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

    fun startBatchDiagnostics(playlistUrl: String, concurrency: Int = 2, limit: Int = 20) {
        batchJob?.cancel()
        batchJob = viewModelScope.launch {
            val safeConcurrency = concurrency.coerceIn(1, 3)
            val safeLimit = limit.coerceIn(1, 20)
            _batchState.value = BatchState(running = true, message = "Loading a small sample of channels…")
            val channels = runCatching {
                withContext(Dispatchers.IO) {
                    channelRepository.getFirstByPlaylistUrl(playlistUrl, safeLimit)
                }
            }.getOrElse {
                _batchState.value = BatchState(message = "Could not read channels: ${it.message ?: "database error"}")
                return@launch
            }
            if (channels.isEmpty()) {
                _batchState.value = BatchState(message = "This playlist has no channels to test.")
                return@launch
            }
            _batchState.value = BatchState(total = channels.size, running = true, message = "Testing with $safeConcurrency small HTTP request(s) at a time…")
            val semaphore = Semaphore(safeConcurrency)
            try {
                coroutineScope {
                    channels.map { channel ->
                        async(Dispatchers.IO) {
                            semaphore.withPermit {
                                delay(120)
                                val result = StreamDiagnostics.probe(channel.url)
                                _batchState.update { state ->
                                    state.copy(
                                        completed = state.completed + 1,
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
                _batchState.update { it.copy(running = false, message = "Completed ${it.completed} of ${it.total} connection checks. Preview a channel in the player to verify the actual picture.") }
            } catch (cancelled: CancellationException) {
                _batchState.update { it.copy(running = false, message = "Batch check stopped.") }
                throw cancelled
            } catch (exception: Exception) {
                _batchState.update { it.copy(running = false, message = "Batch check failed: ${exception.message ?: exception.javaClass.simpleName}") }
            }
        }
    }

    fun cancelBatchDiagnostics() {
        batchJob?.cancel()
        batchJob = null
    }

    fun diagnoseStream(url: String) {
        viewModelScope.launch {
            _diagnosticRunning.value = true
            _diagnostic.value = null
            val result = withContext(Dispatchers.IO) { StreamDiagnostics.probe(url) }
            _diagnostic.value = result
            _diagnosticRunning.value = false
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
