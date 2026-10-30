package com.m3u.smartphone.ui.business.channel

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.m3u.business.channel.ChannelViewModel
import com.m3u.core.foundation.Contracts
import com.m3u.data.database.model.isSeries
import com.m3u.data.repository.channel.ChannelRepository
import com.m3u.data.repository.playlist.PlaylistRepository
import com.m3u.data.service.MediaCommand
import com.m3u.smartphone.ui.business.advanced.ParentalPinStore
import com.m3u.smartphone.ui.common.helper.Helper
import com.m3u.smartphone.ui.common.internal.Toolkit
import com.m3u.smartphone.ui.material.components.Background
import com.m3u.smartphone.ui.material.model.LocalHazeState
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class PlayerActivity : ComponentActivity() {
    private val viewModel: ChannelViewModel by viewModels()
    private val helper: Helper = Helper(this)
    private val parentalStore by lazy { ParentalPinStore(this) }
    private var playbackAuthorized = true
    private var pendingShortcutIntent: Intent? = null

    companion object {
        // FIXME: the property is worked only when activity has one instance at most.
        var isInPipMode: Boolean = false
            private set
    }

    @Inject
    lateinit var channelRepository: ChannelRepository

    @Inject
    lateinit var playlistRepository: PlaylistRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        playbackAuthorized = !parentalStore.playbackLockEnabled()
        if (playbackAuthorized) {
            handleIntent(intent)
        } else {
            // A caller may have selected a stream before opening the PlayerActivity.
            // Pause immediately and resume only after the PIN gate succeeds.
            viewModel.pauseOrContinue(false)
        }
        setContent {
            var authorized by remember { mutableStateOf(playbackAuthorized) }
            Toolkit(
                helper = helper,
                alwaysUseDarkTheme = true
            ) {
                if (authorized) {
                    val hazeState = remember { HazeState() }
                    androidx.compose.runtime.CompositionLocalProvider(LocalHazeState provides hazeState) {
                        Background(color = androidx.compose.ui.graphics.Color.Black) {
                            ChannelRoute(viewModel = viewModel)
                        }
                    }
                } else {
                    PlaybackPinGate(
                        onUnlock = { pin ->
                            val valid = parentalStore.verifyPin(pin)
                            if (valid) {
                                playbackAuthorized = true
                                authorized = true
                                viewModel.pauseOrContinue(true)
                                pendingShortcutIntent?.let(::handleIntent)
                                pendingShortcutIntent = null
                            }
                            valid
                        },
                        onCancel = { finish() }
                    )
                }
            }
        }
        addOnPictureInPictureModeChangedListener {
            isInPipMode = it.isInPictureInPictureMode
            if (!it.isInPictureInPictureMode && lifecycle.currentState !in arrayOf(
                    Lifecycle.State.RESUMED,
                    Lifecycle.State.STARTED
                )
            ) {
                viewModel.destroy()
            }
        }
    }

    private fun playFromShortcuts(channelId: Int) {
        lifecycleScope.launch {
            val channel = channelRepository.get(channelId) ?: return@launch
            val playlist = playlistRepository.get(channel.playlistUrl)
            when {
                // series can not be played from shortcuts
                playlist?.isSeries == true -> {}
                else -> helper.play(MediaCommand.Common(channel.id))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (playbackAuthorized) handleIntent(intent)
        else pendingShortcutIntent = intent
    }

    private fun handleIntent(intent: Intent) {
        val shortcutChannelId = intent
            .getIntExtra(Contracts.PLAYER_SHORTCUT_CHANNEL_ID, -1)
            .takeIf { it != -1 }
        val recently = intent
            .getBooleanExtra(Contracts.PLAYER_SHORTCUT_CHANNEL_RECENTLY, false)

        shortcutChannelId?.let { playFromShortcuts(it) }
        if (recently) {
            lifecycleScope.launch {
                val channel = channelRepository.getPlayedRecently() ?: return@launch
                playFromShortcuts(channel.id)
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        helper.applyConfiguration()
    }

    override fun onResume() {
        super.onResume()
        viewModel.pauseOrContinue(playbackAuthorized)
    }

    override fun onPause() {
        super.onPause()
        if (!isInPictureInPictureMode) {
            viewModel.pauseOrContinue(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.destroy()
    }
}

@Composable
private fun PlaybackPinGate(
    onUnlock: (String) -> Boolean,
    onCancel: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Protected playback") },
        text = {
            Column {
                Text("Enter the parental PIN to start video playback.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(8); error = false },
                    singleLine = true,
                    label = { Text("PIN") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (error) {
                    Text("Incorrect PIN or temporary lockout.", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = { error = !onUnlock(pin); if (!error) pin = "" }) {
                Text("Unlock player")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
