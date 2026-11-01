package dev.gamspi.videogallery.player

import android.view.KeyEvent
import android.view.View
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.subs.LayerSource
import dev.gamspi.videogallery.BuildConfig
import dev.gamspi.videogallery.stream.StreamStallNotice
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val OVERLAY_HIDE_MS = 3_000L
private const val POLL_MS = 500L

private fun remoteKeyOf(keyCode: Int): RemoteKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.Center
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteKey.PlayPause
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.Right
    KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.Down
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> RemoteKey.Back
    else -> null
}

@Composable
fun PlayerScreen(onBack: () -> Unit) {
    val vm = hiltViewModel<PlayerViewModel>()
    val state by vm.state.collectAsState()
    val statusLine by vm.statusLine.collectAsState()
    val stallWarning by vm.stallWarning.collectAsState(initial = null)
    var stallDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(stallWarning) { stallDismissed = false }
    val layers by vm.layers.collectAsState()
    val remoteOptions by vm.remoteOptions.collectAsState()
    val subtitlePrefs by vm.subtitlePrefs.collectAsState()
    val embeddedSlots by vm.embeddedSlots.collectAsState()
    var embedded by remember { mutableStateOf(emptyList<TextTrack>()) }
    var embeddedTextA by remember { mutableStateOf<String?>(null) }
    var embeddedTextB by remember { mutableStateOf<String?>(null) }
    var subtitlePositionMs by remember { mutableLongStateOf(0L) }
    var lastPositionMs by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val textSelector = remember { DualTextTrackSelector(context) }
    val player = remember {
        ExoPlayer.Builder(context, DualTextRenderersFactory(
            context,
            { cues ->
                if (BuildConfig.DEBUG) Log.d("DualTextState", "A cues=${cues.size} position=$subtitlePositionMs")
                embeddedTextA = cues.mapNotNull { it.text }.joinToString("\n").ifBlank { null }
            },
            { cues ->
                if (BuildConfig.DEBUG) Log.d("DualTextState", "B cues=${cues.size} position=$subtitlePositionMs")
                embeddedTextB = cues.mapNotNull { it.text }.joinToString("\n").ifBlank { null }
            },
        ))
            .setTrackSelector(textSelector)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
                    .build(),
            )
            .build().apply {
                playWhenReady = false
            }
    }

    var playWhenReady by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var audio by remember { mutableStateOf(emptyList<AudioTrack>()) }
    var selected by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayTick by remember { mutableLongStateOf(0L) }
    var exited by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val showStallNotice = stallWarning != null && !stallDismissed && !menuOpen &&
        state.resumeOffer == null && state.error == null

    fun durationOrZero(): Long = player.duration.takeIf { it > 0 } ?: 0L

    fun exit() {
        if (exited) return
        exited = true
        vm.onPauseOrExit(player.currentPosition, durationOrZero())
        onBack()
    }

    fun seekTo(position: Long) {
        vm.onPlaybackSeek()
        player.seekTo(position)
    }

    fun selectAudio(t: AudioTrack) {
        val groups = player.currentTracks.groups
        val group = groups.getOrNull(t.group) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.index))
            .build()
    }

    DisposableEffect(player) {
        var initialAudioDone = false
        var started = false
        fun reportWaiting() = vm.onPlaybackWaiting(
            player.playbackState == Player.STATE_BUFFERING && player.playWhenReady &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                embedded = tracks.textTracks()
                vm.onTextTracks(embedded)
                val list = tracks.audioTracks()
                audio = list
                selected = tracks.selectedAudio()
                if (!initialAudioDone && list.isNotEmpty()) {
                    initialAudioDone = true
                    vm.chooseInitialAudio(list)?.let { selectAudio(it) }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING
                reportWaiting()
                if (playbackState == Player.STATE_ENDED) {
                    val d = durationOrZero()
                    vm.onPauseOrExit(d, d)
                    exit()
                    return
                }
                if (playbackState != Player.STATE_READY || started) return
                started = true
                val d = durationOrZero()
                durationMs = d
                if (d > 0) vm.onDuration(d)
                if (vm.state.value.resumeOffer == null &&
                    lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                ) {
                    player.play()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady2: Boolean, reason: Int) {
                playWhenReady = playWhenReady2
                reportWaiting()
            }

            override fun onPlayerError(error: PlaybackException) {
                vm.onPlaybackWaiting(false)
                lastPositionMs = player.currentPosition
                vm.onError(error.errorCodeName)
            }
        }
        player.addListener(listener)
        if (state.url.isNotEmpty()) {
            val factory = DefaultMediaSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setConnectTimeoutMs(30_000)
                    .setReadTimeoutMs(60_000),
            ).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))
            player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(state.url)))
            player.prepare()
        } else {
            vm.onError("EMPTY_URL")
        }
        onDispose {
            vm.onPlaybackWaiting(false)
            vm.onPauseOrExit(player.currentPosition, durationOrZero())
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                vm.onPlaybackWaiting(player.playbackState == Player.STATE_BUFFERING && player.playWhenReady)
            }
            if (event == Lifecycle.Event.ON_STOP) {
                vm.onPlaybackWaiting(false)
                vm.onPauseOrExit(player.currentPosition, durationOrZero())
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            val d = durationOrZero()
            durationMs = d
            if (player.isPlaying) vm.onProgress(positionMs, d)
            delay(POLL_MS)
        }
    }

    LaunchedEffect(embeddedSlots) {
        if (BuildConfig.DEBUG) Log.d("DualTextState", "slots=$embeddedSlots position=$subtitlePositionMs")
        textSelector.setTextSlots(embeddedSlots.first, embeddedSlots.second)
    }

    LaunchedEffect(embeddedSlots.first) { embeddedTextA = null }
    LaunchedEffect(embeddedSlots.second) { embeddedTextB = null }

    LaunchedEffect(player) {
        while (true) {
            subtitlePositionMs = player.currentPosition
            delay(100)
        }
    }

    LaunchedEffect(overlayTick, playWhenReady) {
        if (playWhenReady) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
        } else {
            overlayVisible = true
        }
    }

    DisposableEffect(playWhenReady) {
        view.keepScreenOn = playWhenReady
        onDispose { view.keepScreenOn = false }
    }

    // Return focus to the root when no dialog/menu owns it.
    LaunchedEffect(menuOpen, showStallNotice, state.resumeOffer, state.error) {
        if (!menuOpen && !showStallNotice && state.resumeOffer == null && state.error == null) {
            rootFocus.requestFocus()
        }
    }

    fun execute(cmd: PlayerCommand) {
        when (cmd) {
            PlayerCommand.TogglePlay -> if (player.playWhenReady) {
                player.pause()
                vm.onPauseOrExit(player.currentPosition, durationOrZero())
            } else {
                player.play()
            }
            is PlayerCommand.SeekTo -> seekTo(cmd.ms)
            PlayerCommand.OpenAudioMenu -> menuOpen = true
            PlayerCommand.CloseMenu -> menuOpen = false
            PlayerCommand.Exit -> exit()
        }
    }

    fun onBackKey() {
        when {
            state.error != null || state.resumeOffer != null -> exit()
            showStallNotice -> stallDismissed = true
            else -> execute(handleKey(RemoteKey.Back, player.currentPosition, durationOrZero(), menuOpen))
        }
    }

    // Back is consumed by the key handler; this only catches a Back that arrives without key focus.
    BackHandler { onBackKey() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val keyCode = ev.nativeKeyEvent.keyCode
                val isDown = ev.type == KeyEventType.KeyDown
                if ((keyCode == KeyEvent.KEYCODE_MEDIA_PLAY || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) &&
                    !menuOpen && state.resumeOffer == null && state.error == null
                ) {
                    overlayVisible = true
                    overlayTick++
                    if (isDown && ev.nativeKeyEvent.repeatCount == 0) {
                        if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                            player.play()
                        } else {
                            player.pause()
                            vm.onPauseOrExit(player.currentPosition, durationOrZero())
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                val key = remoteKeyOf(keyCode)
                if (key == RemoteKey.Back) {
                    // Consume both down and up so the system back handler does not pop a second time.
                    if (isDown && ev.nativeKeyEvent.repeatCount == 0) onBackKey()
                    return@onPreviewKeyEvent true
                }
                // Focused panels and dialogs own every key except Back.
                if (menuOpen || showStallNotice || state.resumeOffer != null || state.error != null) {
                    return@onPreviewKeyEvent false
                }
                if (key == null) return@onPreviewKeyEvent false
                overlayVisible = true
                overlayTick++
                if (!isDown) return@onPreviewKeyEvent true
                val repeats = ev.nativeKeyEvent.repeatCount > 0
                val seeks = key == RemoteKey.Left || key == RemoteKey.Right
                if (repeats && !seeks) return@onPreviewKeyEvent true
                execute(handleKey(key, player.currentPosition, durationOrZero(), menuOpen))
                true
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    this.player = player
                    subtitleView?.visibility = View.GONE
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { it.player = null },
        )

        val offer = state.resumeOffer
        val error = state.error
        when {
            error != null -> ErrorContent(
                error = error,
                focus = errorFocus,
                onRetry = {
                    vm.clearError()
                    player.prepare()
                    seekTo(lastPositionMs)
                },
                onBack = ::exit,
            )
            offer != null -> ResumeContent(
                offer = offer,
                durationMs = durationMs,
                onContinue = {
                    vm.dismissResume()
                    seekTo(offer)
                    player.play()
                },
                onRestart = {
                    vm.dismissResume()
                    seekTo(0)
                    player.play()
                },
            )
            else -> {
                SubtitleOverlay(
                    textA = if (layers[Layer.A]?.source is LayerSource.Embedded) embeddedTextA
                        else vm.textAt(Layer.A, subtitlePositionMs),
                    textB = if (layers[Layer.B]?.source is LayerSource.Embedded) embeddedTextB
                        else vm.textAt(Layer.B, subtitlePositionMs),
                    styleA = subtitlePrefs.styleA,
                    styleB = subtitlePrefs.styleB,
                    liftDp = if (overlayVisible || menuOpen) 170.dp else 0.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                if (overlayVisible || menuOpen) {
                    PlayerOverlay(
                        title = state.title,
                        positionMs = positionMs,
                        durationMs = durationMs,
                        playWhenReady = playWhenReady,
                        buffering = buffering,
                        statusLine = statusLine,
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
                if (menuOpen) {
                    TracksMenu(
                        audioTracks = audio,
                        selectedAudio = selected,
                        onAudioSelect = {
                            selectAudio(it)
                            vm.onAudioSelected(it)
                            menuOpen = false
                        },
                        embedded = embedded,
                        remoteOptions = remoteOptions,
                        layers = layers,
                        languageA = subtitlePrefs.languageA,
                        languageB = subtitlePrefs.languageB,
                        onSelect = vm::select,
                        onShift = vm::shiftOffset,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                if (showStallNotice) {
                    StreamStallNotice(
                        status = statusLine,
                        onContinue = { stallDismissed = true },
                        onChooseAnother = {
                            vm.chooseAnotherTorrent()
                            exit()
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun ResumeContent(
    offer: Long,
    durationMs: Long,
    onContinue: () -> Unit,
    onRestart: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onContinue, modifier = Modifier.focusRequester(focus)) {
                Text("Продолжить с ${formatTime(offer, durationMs)}")
            }
            Button(onClick = onRestart) { Text("Сначала") }
        }
    }
}

@Composable
private fun ErrorContent(
    error: String,
    focus: FocusRequester,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Не удалось воспроизвести", color = Color.White)
            Text(error, color = Color(0xFFBBBBBB))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) { Text("Повторить") }
                Button(onClick = onBack) { Text("Назад") }
            }
        }
    }
}
