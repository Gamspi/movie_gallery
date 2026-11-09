package dev.gamspi.videogallery.player

import android.view.KeyEvent
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dev.gamspi.videogallery.history.PlaybackMeta
import dev.gamspi.videogallery.subs.Layer
import dev.gamspi.videogallery.BuildConfig
import dev.gamspi.videogallery.stream.StreamStallNotice
import dev.gamspi.videogallery.ui.components.PopcornCatLoader
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import dev.gamspi.videogallery.ui.components.AccentButton
import dev.gamspi.videogallery.ui.components.CatMascot
import dev.gamspi.videogallery.ui.components.CatPose

private const val OVERLAY_HIDE_MS = 3_000L
private const val POLL_MS = 500L
private const val END_EXIT_MS = 5_000L
private const val NEXT_COUNTDOWN_S = 10

private fun remoteKeyOf(keyCode: Int): RemoteKey? = when (keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RemoteKey.Center
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> RemoteKey.PlayPause
    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> RemoteKey.Left
    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> RemoteKey.Right
    KeyEvent.KEYCODE_DPAD_UP -> RemoteKey.Up
    KeyEvent.KEYCODE_DPAD_DOWN -> RemoteKey.Down
    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> RemoteKey.Back
    else -> null
}

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onPlayEpisode: (title: String, meta: PlaybackMeta) -> Unit = { _, _ -> },
    onPickRelease: (PlaybackMeta) -> Unit = {},
) {
    val vm = hiltViewModel<PlayerViewModel>()
    val state by vm.state.collectAsState()
    val terminalStreamError by vm.terminalStreamError.collectAsState(initial = null)
    val playbackError = terminalStreamError ?: state.error
    val statusLine by vm.statusLine.collectAsState()
    val stallWarning by vm.stallWarning.collectAsState(initial = null)
    var stallDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(stallWarning) { stallDismissed = false }
    val layers by vm.layers.collectAsState()
    val translatedEmbedded by vm.translatedEmbedded.collectAsState()
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
    // The screen owns one MediaItem; renderer errors may temporarily clear currentTracks.
    var lastAudioGroups by remember(player) { mutableStateOf(emptyList<Tracks.Group>()) }
    var lastAudio by remember(player) { mutableStateOf(emptyList<AudioTrack>()) }
    var lastSelectedAudio by remember(player) { mutableStateOf<Pair<Int, Int>?>(null) }
    var failedAudioSelection by remember(player) { mutableStateOf<Pair<Int, Int>?>(null) }
    var playerFocus by remember { mutableStateOf(PlayerFocus.None) }
    var menuSection by remember { mutableStateOf<PlayerMenuSection?>(null) }
    val menuOpen = playerFocus == PlayerFocus.MenuSections || playerFocus == PlayerFocus.MenuSection
    // Focus requests wait for the composition that makes their target focusable.
    var pendingFocus by remember { mutableStateOf<FocusRequester?>(null) }
    val trackFocus = remember { FocusRequester() }
    val playButtonFocus = remember { FocusRequester() }
    val settingsButtonFocus = remember { FocusRequester() }
    var recoveryAudioOpen by remember { mutableStateOf(false) }
    var overlayVisible by remember { mutableStateOf(true) }
    var overlayTick by remember { mutableLongStateOf(0L) }
    var exited by remember { mutableStateOf(false) }
    // Playback reached the end: the end screen owns focus until exit.
    var ended by remember { mutableStateOf(false) }
    val rootFocus = remember { FocusRequester() }
    val errorFocus = remember { FocusRequester() }
    val recoveryOptions = if (state.failure?.canChooseAudio == true) {
        failedAudioSelection?.let { audioRecoveryOptions(audio, it) }.orEmpty()
    } else audioRecoveryOptions(audio, selected)
    val showStallNotice = stallWarning != null && !stallDismissed && !menuOpen &&
        state.resumeOffer == null && playbackError == null && !ended
    val dialogOpen = showStallNotice || state.resumeOffer != null || playbackError != null || ended ||
        recoveryAudioOpen

    fun durationOrZero(): Long = player.duration.takeIf { it > 0 } ?: 0L

    LaunchedEffect(terminalStreamError) {
        if (terminalStreamError != null) {
            vm.onPauseOrExit(player.currentPosition, durationOrZero())
            player.pause()
            player.stop()
            recoveryAudioOpen = false
        }
    }

    LaunchedEffect(playbackError) {
        if (playbackError != null) {
            player.pause()
            playerFocus = PlayerFocus.None
            menuSection = null
        }
        recoveryAudioOpen = false
    }

    // The stall notice takes focus from the panel; the menu hides the notice instead.
    LaunchedEffect(showStallNotice) {
        if (showStallNotice && (playerFocus == PlayerFocus.Buttons || playerFocus == PlayerFocus.Track)) {
            playerFocus = PlayerFocus.None
        }
    }

    LaunchedEffect(pendingFocus) {
        pendingFocus?.let {
            it.requestFocus()
            pendingFocus = null
        }
    }

    fun exit() {
        if (exited) return
        exited = true
        vm.onPauseOrExit(player.currentPosition, durationOrZero())
        onBack()
    }

    // The follow-up is decided once, when playback ends (history and release hold are settled by then).
    val nextStep = remember(ended) { if (ended) vm.nextStep() else null }

    fun goNext(step: NextStep) {
        if (exited) return
        exited = true
        when (step) {
            is NextStep.PlayFile -> onPlayEpisode(state.title, vm.metaFor(step.ref))
            is NextStep.PickRelease -> onPickRelease(vm.metaFor(step.ref))
            NextStep.SeriesDone -> onBack()
        }
    }

    fun seekTo(position: Long) {
        vm.onPlaybackSeek()
        player.seekTo(position)
    }

    fun selectAudio(t: AudioTrack, allowCached: Boolean = false): Boolean {
        val groups = player.currentTracks.groups
        val group = if (allowCached) lastAudioGroups.getOrNull(t.group) ?: groups.getOrNull(t.group)
            else groups.getOrNull(t.group)
        if (group == null || t.index !in 0 until group.length) return false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, t.index))
            .build()
        lastSelectedAudio = t.group to t.index
        return true
    }

    val preparationScope = rememberCoroutineScope()
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
                val currentSelection = tracks.selectedAudio()
                if (list.isNotEmpty()) {
                    lastAudioGroups = tracks.groups.toList()
                    lastAudio = list
                    currentSelection?.let { lastSelectedAudio = it }
                }
                if (list.isNotEmpty() || vm.state.value.failure?.canChooseAudio != true) {
                    audio = list
                    selected = currentSelection
                }
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
                    playerFocus = PlayerFocus.None
                    menuSection = null
                    recoveryAudioOpen = false
                    ended = true
                    return
                }
                if (playbackState != Player.STATE_READY || started) return
                started = true
                val d = durationOrZero()
                durationMs = d
                if (d > 0) vm.onDuration(d)?.let { seekTo(it) }
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
                val failure = error.toPlayerFailure()
                failedAudioSelection = null
                if (failure.canChooseAudio) {
                    val current = player.currentTracks
                    val currentAudio = current.audioTracks()
                    if (currentAudio.isNotEmpty()) {
                        lastAudioGroups = current.groups.toList()
                        lastAudio = currentAudio
                        current.selectedAudio()?.let { lastSelectedAudio = it }
                    }
                    val failedFormat = (error as? ExoPlaybackException)?.rendererFormat
                    val failedSelection = lastSelectedAudio?.takeIf { (groupIndex, trackIndex) ->
                        failedFormat == null || lastAudioGroups.getOrNull(groupIndex)
                            ?.takeIf { trackIndex in 0 until it.length }?.getTrackFormat(trackIndex) == failedFormat
                    } ?: if (failedFormat == null) null else lastAudioGroups.withIndex()
                        .firstNotNullOfOrNull { (groupIndex, group) ->
                            (0 until group.length).firstOrNull { group.getTrackFormat(it) == failedFormat }
                                ?.let { groupIndex to it }
                        }
                    audio = lastAudio
                    failedAudioSelection = failedSelection ?: lastSelectedAudio
                    selected = failedAudioSelection
                }
                vm.onError(failure)
            }
        }
        player.addListener(listener)
        val preparation = preparationScope.launch {
            vm.prepareCloudResume()
            if (state.url.isNotEmpty()) {
                val factory = DefaultMediaSourceFactory(
                    DefaultHttpDataSource.Factory()
                        .setConnectTimeoutMs(30_000)
                        .setReadTimeoutMs(60_000),
                ).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))
                player.setMediaSource(factory.createMediaSource(MediaItem.fromUri(state.url)))
                player.prepare()
            } else {
                vm.onError(PlayerFailure("Не указан адрес видео. Выберите другой источник."))
            }
        }
        onDispose {
            preparation.cancel()
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
    // Embedded cues feed the translation of the other layer.
    LaunchedEffect(embeddedTextA) { vm.onEmbeddedText(Layer.A, embeddedTextA) }
    LaunchedEffect(embeddedTextB) { vm.onEmbeddedText(Layer.B, embeddedTextB) }

    LaunchedEffect(player) {
        while (true) {
            subtitlePositionMs = player.currentPosition
            delay(100)
        }
    }

    // Pausing shows the panel; an explicit hide while paused (Back) still sticks.
    LaunchedEffect(playWhenReady) {
        if (!playWhenReady) overlayVisible = true
    }

    // While playing, the panel hides after idle even with focus on the track or buttons.
    LaunchedEffect(overlayTick, playWhenReady, playerFocus, dialogOpen) {
        if (shouldAutoHidePanel(playWhenReady, playerFocus, dialogOpen)) {
            delay(OVERLAY_HIDE_MS)
            overlayVisible = false
            if (playerFocus == PlayerFocus.Track || playerFocus == PlayerFocus.Buttons) {
                playerFocus = PlayerFocus.None
            }
        }
    }

    DisposableEffect(playWhenReady) {
        view.keepScreenOn = playWhenReady
        onDispose { view.keepScreenOn = false }
    }

    // Return focus to the root when no dialog, button or menu owns it.
    LaunchedEffect(playerFocus, showStallNotice, state.resumeOffer, playbackError, ended) {
        if (playerFocus == PlayerFocus.None && !showStallNotice && state.resumeOffer == null && playbackError == null &&
            !ended
        ) {
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
            PlayerCommand.FocusTrack -> {
                overlayVisible = true
                playerFocus = PlayerFocus.Track
                pendingFocus = trackFocus
            }
            PlayerCommand.FocusButtons -> {
                overlayVisible = true
                playerFocus = PlayerFocus.Buttons
                pendingFocus = playButtonFocus
            }
            PlayerCommand.HidePanel -> {
                overlayVisible = false
                playerFocus = PlayerFocus.None
                rootFocus.requestFocus()
            }
            // Section is chosen by the menu item itself (onOpenSection).
            PlayerCommand.OpenMenuSection -> playerFocus = PlayerFocus.MenuSection
            PlayerCommand.BackToSections -> {
                menuSection = null
                playerFocus = PlayerFocus.MenuSections
            }
            PlayerCommand.CloseMenu -> {
                menuSection = null
                playerFocus = PlayerFocus.Buttons
                pendingFocus = settingsButtonFocus
            }
            PlayerCommand.Exit -> exit()
            PlayerCommand.PassToFocus -> Unit
        }
    }

    fun currentFocus() = playerFocus

    fun onBackKey() {
        when {
            ended -> exit()
            recoveryAudioOpen -> recoveryAudioOpen = false
            playbackError != null || state.resumeOffer != null -> exit()
            showStallNotice -> stallDismissed = true
            else -> execute(
                handleKey(RemoteKey.Back, player.currentPosition, durationOrZero(), currentFocus(), overlayVisible),
            )
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
                    state.resumeOffer == null && playbackError == null && !ended
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
                // Dialogs own every key except Back; buttons and menu get theirs via PassToFocus.
                if (ended || showStallNotice || state.resumeOffer != null || playbackError != null) {
                    return@onPreviewKeyEvent false
                }
                if (key == null) return@onPreviewKeyEvent false
                val cmd = handleKey(key, player.currentPosition, durationOrZero(), currentFocus(), overlayVisible)
                // Any press restarts the idle timer, including keys the buttons handle.
                if (isDown) overlayTick++
                if (cmd == PlayerCommand.PassToFocus) return@onPreviewKeyEvent false
                if (!isDown) return@onPreviewKeyEvent true
                if (cmd != PlayerCommand.HidePanel) overlayVisible = true
                val repeats = ev.nativeKeyEvent.repeatCount > 0
                val seeks = key == RemoteKey.Left || key == RemoteKey.Right
                if (repeats && !seeks) return@onPreviewKeyEvent true
                execute(cmd)
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
        val error = playbackError
        when {
            ended -> EndContent(next = nextStep, onExit = ::exit, onNext = ::goNext)
            error != null && recoveryAudioOpen && terminalStreamError == null -> AudioRecoveryContent(
                tracks = recoveryOptions,
                onSelect = { track ->
                    if (terminalStreamError == null && (track.group to track.index) != failedAudioSelection &&
                        selectAudio(track, allowCached = true)
                    ) {
                        vm.onAudioSelected(track)
                        vm.clearError()
                        failedAudioSelection = null
                        recoveryAudioOpen = false
                        player.prepare()
                        seekTo(lastPositionMs)
                        if (vm.state.value.resumeOffer == null &&
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                        ) player.play()
                    }
                },
                onBack = { recoveryAudioOpen = false },
            )
            error != null -> ErrorContent(
                error = error,
                focus = errorFocus,
                onRetry = if (terminalStreamError != null || state.failure?.retryable != true) null else ({
                    vm.clearError()
                    player.prepare()
                    seekTo(lastPositionMs)
                    if (vm.state.value.resumeOffer == null &&
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                    ) player.play()
                }),
                onChooseAudio = if (terminalStreamError == null && state.failure?.canChooseAudio == true && recoveryOptions.isNotEmpty())
                    ({ recoveryAudioOpen = true }) else null,
                backLabel = if (terminalStreamError != null) "Выбрать другую раздачу" else "Назад",
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
                    // layers and translatedEmbedded are read here so their changes recompose the overlay.
                    textA = layers.let {
                        vm.overlayText(Layer.A, subtitlePositionMs, embeddedTextA, translatedEmbedded[Layer.A])
                    },
                    textB = layers.let {
                        vm.overlayText(Layer.B, subtitlePositionMs, embeddedTextB, translatedEmbedded[Layer.B])
                    },
                    styleA = subtitlePrefs.styleA,
                    styleB = subtitlePrefs.styleB,
                    liftDp = if (overlayVisible || playerFocus != PlayerFocus.None) 200.dp else 0.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
                val panelsShown = overlayVisible || playerFocus != PlayerFocus.None
                if (buffering) {
                    PopcornCatLoader(
                        size = 96.dp,
                        // Below the top bar while it is shown.
                        modifier = Modifier.align(Alignment.TopEnd).padding(
                            start = 32.dp, end = 32.dp, bottom = 32.dp, top = if (panelsShown) 112.dp else 32.dp,
                        ),
                    )
                }
                if (panelsShown) {
                    PlayerTopBar(
                        heading = playerHeading(vm.meta.movieTitle, vm.meta.movieYear, state.title),
                        positionMs = { positionMs },
                        durationMs = durationMs,
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                    PlayerOverlay(
                        title = state.title,
                        positionMs = { positionMs },
                        durationMs = durationMs,
                        playWhenReady = playWhenReady,
                        buffering = buffering,
                        trackFocused = playerFocus == PlayerFocus.Track,
                        buttonsFocused = playerFocus == PlayerFocus.Buttons,
                        onTogglePlay = { execute(PlayerCommand.TogglePlay) },
                        onOpenMenu = {
                            menuSection = null
                            playerFocus = PlayerFocus.MenuSections
                        },
                        trackFocus = trackFocus,
                        playButtonFocus = playButtonFocus,
                        settingsButtonFocus = settingsButtonFocus,
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
                            execute(PlayerCommand.CloseMenu)
                        },
                        embedded = embedded,
                        remoteOptions = remoteOptions,
                        layers = layers,
                        languageA = subtitlePrefs.languageA,
                        languageB = subtitlePrefs.languageB,
                        translations = remember(layers) { Layer.entries.associateWith(vm::translationOptions) },
                        onSelect = vm::select,
                        onShift = vm::shiftOffset,
                        scaleA = subtitlePrefs.styleA.scalePercent,
                        scaleB = subtitlePrefs.styleB.scalePercent,
                        onScale = vm::changeSubtitleScale,
                        section = menuSection,
                        onOpenSection = {
                            menuSection = it
                            execute(PlayerCommand.OpenMenuSection)
                        },
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
            AccentButton(onClick = onContinue, modifier = Modifier.focusRequester(focus)) {
                Text("Продолжить с ${formatTime(offer, durationMs)}")
            }
            AccentButton(onClick = onRestart) { Text("Сначала") }
        }
    }
}

@Composable
private fun EndContent(next: NextStep?, onExit: () -> Unit, onNext: (NextStep) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val exitNow by rememberUpdatedState(onExit)
    val nextNow by rememberUpdatedState(onNext)
    if (next != null && next != NextStep.SeriesDone) {
        var left by remember { mutableIntStateOf(NEXT_COUNTDOWN_S) }
        LaunchedEffect(Unit) {
            while (left > 0) {
                delay(1_000)
                left--
            }
            nextNow(next)
        }
        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                CatMascot(CatPose.Wave, size = 160.dp, message = "Следующая серия через $left с")
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccentButton(onClick = { nextNow(next) }, modifier = Modifier.focusRequester(focus)) {
                        Text("Смотреть сейчас")
                    }
                    AccentButton(onClick = onExit) { Text("Отмена") }
                }
            }
        }
        return
    }
    LaunchedEffect(Unit) {
        delay(END_EXIT_MS)
        exitNow()
    }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CatMascot(CatPose.Wave, size = 160.dp, message = if (next == NextStep.SeriesDone) "Сериал закончился" else "Фильм закончился")
            AccentButton(onClick = onExit, modifier = Modifier.focusRequester(focus)) { Text("Выйти") }
        }
    }
}

@Composable
private fun ErrorContent(
    error: String,
    focus: FocusRequester,
    onRetry: (() -> Unit)?,
    onChooseAudio: (() -> Unit)?,
    backLabel: String,
    onBack: () -> Unit,
) {
    LaunchedEffect(onRetry == null, onChooseAudio == null) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CatMascot(CatPose.Sad, size = 100.dp)
            Text("Не удалось воспроизвести", color = Color.White)
            Text(error, color = Color(0xFFBBBBBB))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (onChooseAudio != null) {
                    AccentButton(onClick = onChooseAudio, modifier = Modifier.focusRequester(focus)) { Text("Другая аудиодорожка") }
                }
                if (onRetry != null) {
                    AccentButton(onClick = onRetry, modifier = if (onChooseAudio == null) Modifier.focusRequester(focus) else Modifier) { Text("Повторить") }
                }
                AccentButton(onClick = onBack, modifier = if (onRetry == null && onChooseAudio == null) Modifier.focusRequester(focus) else Modifier) {
                    Text(backLabel)
                }
            }
        }
    }
}

@Composable
private fun AudioRecoveryContent(
    tracks: List<AudioTrack>,
    onSelect: (AudioTrack) -> Unit,
    onBack: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    val scroll = rememberScrollState()
    LaunchedEffect(Unit) { focus.requestFocus() }
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Выберите другую аудиодорожку", color = Color.White)
            tracks.forEachIndexed { index, track ->
                AccentButton(onClick = { onSelect(track) }, modifier = if (index == 0) Modifier.focusRequester(focus) else Modifier) {
                    Text(track.displayName(index + 1))
                }
            }
            AccentButton(onClick = onBack, modifier = if (tracks.isEmpty()) Modifier.focusRequester(focus) else Modifier) { Text("Назад") }
        }
    }
}
