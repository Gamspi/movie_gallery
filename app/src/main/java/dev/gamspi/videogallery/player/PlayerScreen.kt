package dev.gamspi.videogallery.player

import android.view.KeyEvent
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
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
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
    var lastPositionMs by remember { mutableLongStateOf(0L) }
    val context = LocalContext.current
    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
                    .build(),
            )
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
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

    fun durationOrZero(): Long = player.duration.takeIf { it > 0 } ?: 0L

    fun exit() {
        if (exited) return
        exited = true
        vm.onPauseOrExit(player.currentPosition, durationOrZero())
        onBack()
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
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
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
            }

            override fun onPlayerError(error: PlaybackException) {
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
            vm.onPauseOrExit(player.currentPosition, durationOrZero())
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
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
    LaunchedEffect(menuOpen, state.resumeOffer, state.error) {
        if (!menuOpen && state.resumeOffer == null && state.error == null) rootFocus.requestFocus()
    }

    fun execute(cmd: PlayerCommand) {
        when (cmd) {
            PlayerCommand.TogglePlay -> if (player.playWhenReady) {
                player.pause()
                vm.onPauseOrExit(player.currentPosition, durationOrZero())
            } else {
                player.play()
            }
            is PlayerCommand.SeekTo -> player.seekTo(cmd.ms)
            PlayerCommand.OpenAudioMenu -> if (audio.isNotEmpty()) menuOpen = true
            PlayerCommand.CloseMenu -> menuOpen = false
            PlayerCommand.Exit -> exit()
        }
    }

    fun onBackKey() {
        when {
            state.error != null || state.resumeOffer != null -> exit()
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
                // Menu, resume dialog and error screen own every key except Back.
                if (menuOpen || state.resumeOffer != null || state.error != null) {
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
                    player.seekTo(lastPositionMs)
                },
                onBack = ::exit,
            )
            offer != null -> ResumeContent(
                offer = offer,
                durationMs = durationMs,
                onContinue = {
                    vm.dismissResume()
                    player.seekTo(offer)
                    player.play()
                },
                onRestart = {
                    vm.dismissResume()
                    player.seekTo(0)
                    player.play()
                },
            )
            else -> {
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
                    AudioMenu(
                        tracks = audio,
                        selected = selected,
                        onSelect = {
                            selectAudio(it)
                            vm.onAudioSelected(it)
                            menuOpen = false
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
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
