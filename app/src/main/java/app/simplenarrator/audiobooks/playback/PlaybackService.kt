package app.simplenarrator.audiobooks.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import app.simplenarrator.util.AppLanguage
import app.simplenarrator.SimpleNarratorApp
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Book
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService(), AudiobookPlayer.Host {

    private val app get() = application as SimpleNarratorApp
    private val library get() = app.library
    private val settings get() = app.settings

    private lateinit var exo: ExoPlayer
    private lateinit var player: AudiobookPlayer
    private var session: MediaLibrarySession? = null
    private val scope = MainScope()

    private var bookId: String? = null
    private var lastChapter = -1
    private var pausedAt = 0L
    private var tickJob: Job? = null
    private var lastTick = 0L
    private var lastPersist = 0L
    private var loudness: LoudnessEnhancer? = null
    private var fading = false
    private var retries = 0
    private var playRequestedAt = 0L
    private var focusJob: Job? = null
    private var retryWindowStart = 0L
    private val shake by lazy { ShakeDetector(this) { onShake() } }

    override val skipBackMs: Long get() = settings.value.skipBackSec * 1000L
    override val skipForwardMs: Long get() = settings.value.skipForwardSec * 1000L

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player = AudiobookPlayer(exo, this)
        exo.addListener(listener)

        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_PLAYER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaLibrarySession.Builder(this, player, Callback())
            .setSessionActivity(open)
            .setCustomLayout(customLayout())
            .build()
        setMediaNotificationProvider(NotificationProvider(this))

        // Geschwindigkeit, Stille überspringen und Lautstärke des laufenden Buches anwenden
        scope.launch {
            combine(library.states, settings.flow) { states, s -> states to s }
                .collect { (states, s) ->
                    val id = bookId ?: return@collect
                    val st = states[id] ?: return@collect
                    val speed = if (st.speed > 0f) st.speed else s.defaultSpeed
                    if (exo.playbackParameters.speed != speed) exo.setPlaybackSpeed(speed)
                    if (exo.skipSilenceEnabled != st.skipSilence) exo.skipSilenceEnabled = st.skipSilence
                    applyBoost(st.boostDb)
                }
        }
        // Symbole der Spultasten an die eingestellten Sekunden anpassen
        scope.launch {
            settings.flow.map { it.skipBackSec to it.skipForwardSec }.distinctUntilChanged().collect {
                session?.setCustomLayout(customLayout())
            }
        }
        scope.launch {
            SleepTimer.state.collect { s ->
                if (s == null && fading) {
                    exo.volume = 1f
                    fading = false
                }
                updateShake()
                if (s != null) startTicking()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistNow()
        if (!exo.playWhenReady || exo.mediaItemCount == 0) {
            pauseAllPlayersAndStopSelf()
        }
    }

    override fun onDestroy() {
        persistNow()
        shake.stop()
        tickJob?.cancel()
        scope.cancel()
        loudness?.release()
        session?.run {
            release()
            session = null
        }
        exo.removeListener(listener)
        exo.release()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Buch laden

    private fun resolveBook(request: MediaIds.BookRequest): MediaSession.MediaItemsWithStartPosition? {
        val entry = library.entry(request.id) ?: return null
        val book = entry.book
        if (book.tracks.isEmpty()) return null
        persistNow()
        var start = request.startMs ?: run {
            val st = entry.state
            if (st.finished || entry.positionMs >= book.durationMs - 2_000) {
                library.updateState(book.id) { it.copy(finished = false, finishedAt = 0, positionMs = 0) }
                0L
            } else {
                val since = if (st.lastPlayedAt > 0) System.currentTimeMillis() - st.lastPlayedAt else 0L
                val back = Rewind.resumeRewindMs(since, settings.value.resumeRewindSec, settings.value.smartRewind)
                max(0, entry.positionMs - back)
            }
        }
        start = start.coerceIn(0, max(0, book.durationMs - 1))
        val (index, pos) = book.locate(start)
        pausedAt = 0
        val items = book.tracks.indices.map { trackItem(entry, it) }
        return MediaSession.MediaItemsWithStartPosition(items, index, pos)
    }

    private fun trackItem(entry: Entry, index: Int, chapterTitle: String? = null): MediaItem {
        val book = entry.book
        val track = book.tracks[index]
        val title = chapterTitle
            ?: book.chapters.firstOrNull { it.track == index }?.title
            ?: track.title
            ?: entry.title
        return MediaItem.Builder()
            .setMediaId(MediaIds.track(book.id, index))
            .setUri(track.uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(
                        if (title != entry.title) listOfNotNull(entry.title, entry.author).joinToString(" · ") else entry.author,
                    )
                    .setAlbumTitle(entry.title)
                    .setAlbumArtist(entry.author)
                    .setArtworkUri(entry.cover?.let { CoverProvider.uriFor(this, it) })
                    .setTrackNumber(index + 1)
                    .setTotalTrackCount(book.tracks.size)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK_CHAPTER)
                    .build(),
            )
            .build()
    }

    private fun browseItem(entry: Entry): MediaItem = MediaItem.Builder()
        .setMediaId(MediaIds.book(entry.id))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(entry.title)
                .setArtist(entry.author)
                .setArtworkUri(entry.cover?.let { CoverProvider.uriFor(this, it) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_AUDIO_BOOK)
                .build(),
        )
        .build()

    private fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_AUDIO_BOOKS)
                .build(),
        )
        .build()

    private fun lastPlayedRequest(): MediaIds.BookRequest? =
        library.lastPlayed()?.let { MediaIds.BookRequest(it.id, null) }

    // ------------------------------------------------------------------ Spulen

    private fun currentBook(): Book? = library.book(bookId)

    private fun currentGlobal(book: Book): Long = book.globalOf(exo.currentMediaItemIndex, exo.currentPosition)

    override fun ensureLoaded(): Boolean {
        if (exo.mediaItemCount > 0) {
            if (exo.playbackState == Player.STATE_IDLE) exo.prepare()
            return true
        }
        val req = lastPlayedRequest() ?: return false
        val resolved = resolveBook(req) ?: return false
        exo.setMediaItems(resolved.mediaItems, resolved.startIndex, resolved.startPositionMs)
        exo.prepare()
        bookId = req.id
        applyBookSettings()
        return true
    }

    override fun deferPlay(): Boolean {
        val appVisible = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        // 0 = kein Vordergrunddienst (FOREGROUND_SERVICE_TYPE_NONE ist seit API 35 veraltet)
        val foreground = Build.VERSION.SDK_INT >= 29 && foregroundServiceType != 0
        if (appVisible || foreground || Build.VERSION.SDK_INT < 29) return false
        // Android 15 verweigert den Audiofokus, solange der Dienst nicht im Vordergrund angekommen ist
        // ("blocked by hardening"). Deshalb erst die Benachrichtigung zeigen, dann kurz warten.
        beforeResume()
        session?.let { onUpdateNotification(it, true) }
        scope.launch {
            delay(FOREGROUND_DELAY_MS)
            playRequestedAt = SystemClock.elapsedRealtime()
            exo.play()
        }
        return true
    }

    override fun notePlayRequest() {
        playRequestedAt = SystemClock.elapsedRealtime()
    }

    /** Versucht den Start einige Male erneut; ein weiterer verweigerter Versuch meldet kein neues Ereignis. */
    private fun retryFocus() {
        if (focusJob?.isActive == true) return
        focusJob = scope.launch {
            repeat(4) {
                delay(FOREGROUND_DELAY_MS)
                if (exo.playWhenReady) return@launch
                exo.play()
                if (exo.playWhenReady) return@launch
            }
        }
    }

    override fun skipBy(deltaMs: Long) {
        if (exo.mediaItemCount == 0 && !ensureLoaded()) return
        val book = currentBook()
        if (book == null) {
            exo.seekTo(max(0, exo.currentPosition + deltaMs))
            return
        }
        val target = (currentGlobal(book) + deltaMs).coerceIn(0, max(0, book.durationMs - 500))
        seekGlobal(book, target)
    }

    private fun seekGlobal(book: Book, globalMs: Long) {
        val (i, p) = book.locate(globalMs)
        if (i == exo.currentMediaItemIndex) exo.seekTo(p) else exo.seekTo(i, p)
    }

    private fun chapterJump(forward: Boolean) {
        if (exo.mediaItemCount == 0 && !ensureLoaded()) return
        val book = currentBook() ?: return
        if (book.chapters.isEmpty()) return
        val g = currentGlobal(book)
        val idx = book.chapterAt(g)
        val target = if (forward) {
            book.chapters.getOrNull(idx + 1)?.startMs ?: return
        } else {
            val ch = book.chapters[idx]
            if (g - ch.startMs > 3_000 || idx == 0) ch.startMs else book.chapters[idx - 1].startMs
        }
        seekGlobal(book, target)
    }

    override fun headsetNext() = if (settings.value.headsetChapters) chapterJump(true) else skipBy(skipForwardMs)
    override fun headsetPrevious() = if (settings.value.headsetChapters) chapterJump(false) else skipBy(-skipBackMs)

    override fun beforeResume() {
        val p = pausedAt
        pausedAt = 0
        if (p == 0L || exo.mediaItemCount == 0) return
        val back = Rewind.resumeRewindMs(SystemClock.elapsedRealtime() - p, settings.value.resumeRewindSec, settings.value.smartRewind)
        if (back > 0) skipBy(-back)
    }

    // ------------------------------------------------------------------ Laufender Zustand

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val id = MediaIds.bookOfTrack(mediaItem?.mediaId)
            if (id != bookId) {
                bookId = id
                lastChapter = -1
                if (SleepTimer.state.value is SleepTimer.ChapterEnd) SleepTimer.cancel()
                applyBookSettings()
            }
            checkDuration()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                startTicking()
            } else {
                persistNow()
            }
            updateShake()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            val now = SystemClock.elapsedRealtime()
            if (playWhenReady) return
            // Direkt nach dem Start verweigerter Fokus: Android 15 kennt den Vordergrunddienst noch nicht.
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS && now - playRequestedAt < 1_500) {
                retryFocus()
                return
            }
            pausedAt = now
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) checkDuration()
            if (state == Player.STATE_ENDED) {
                val id = bookId ?: return
                library.markFinished(id, true)
                library.flush()
                if (SleepTimer.state.value is SleepTimer.ChapterEnd) SleepTimer.cancel()
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) return
            val book = currentBook() ?: return
            if (exo.playbackState == Player.STATE_ENDED) return
            library.savePosition(book.id, currentGlobal(book))
            library.saveSoon()
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            loudness?.release()
            loudness = null
            applyBookSettings()
        }

        override fun onPlayerError(error: PlaybackException) {
            persistNow()
            // Decoderfehler sind meist vorübergehend: an derselben Stelle neu vorbereiten.
            val decoder = error.errorCode in setOf(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            )
            val now = SystemClock.elapsedRealtime()
            if (now - retryWindowStart > 60_000) {
                retryWindowStart = now
                retries = 0
            }
            if (decoder && retries < 3) {
                retries++
                scope.launch {
                    delay(300)
                    exo.prepare()
                }
            }
        }
    }

    private fun applyBookSettings() {
        val id = bookId ?: return
        val st = library.state(id)
        val speed = if (st.speed > 0f) st.speed else settings.value.defaultSpeed
        exo.setPlaybackSpeed(speed)
        exo.skipSilenceEnabled = st.skipSilence
        applyBoost(st.boostDb)
    }

    private fun applyBoost(db: Int) {
        try {
            if (db <= 0) {
                loudness?.enabled = false
                return
            }
            val l = loudness ?: LoudnessEnhancer(exo.audioSessionId).also { loudness = it }
            l.setTargetGain(db * 100)
            l.enabled = true
        } catch (_: Exception) {
            loudness = null
        }
    }

    /** Übernimmt die vom Player gemessene Dateilänge, wenn sie von der gespeicherten abweicht. */
    private fun checkDuration() {
        val id = bookId ?: return
        val idx = exo.currentMediaItemIndex
        val d = exo.duration
        if (d != C.TIME_UNSET && d > 0) library.correctDuration(id, idx, d)
    }

    private fun startTicking() {
        if (tickJob?.isActive == true) return
        lastTick = SystemClock.elapsedRealtime()
        tickJob = scope.launch {
            while (isActive) {
                delay(nextDelay())
                tick()
                if (!exo.isPlaying && SleepTimer.state.value == null) break
            }
        }
    }

    private fun nextDelay(): Long {
        val timer = SleepTimer.state.value
        val book = currentBook()
        if (timer is SleepTimer.ChapterEnd && book != null && exo.isPlaying) {
            val left = (timer.chapterEndMs - currentGlobal(book)) / exo.playbackParameters.speed
            return left.toLong().coerceIn(50, 1000)
        }
        if (timer is SleepTimer.Countdown) return min(1000L, max(50L, timer.remaining()))
        return 1000
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick).coerceIn(0, 5_000)
        lastTick = now
        val book = currentBook()
        if (book != null && exo.isPlaying) {
            val g = currentGlobal(book)
            library.savePosition(book.id, g, dt)
            app.stats.add(dt)
            if (now - lastPersist > 15_000) persistNow()
            val ch = book.chapterAt(g)
            if (ch != lastChapter) {
                lastChapter = ch
                updateChapterMetadata(book, ch)
            }
        }
        handleSleepTimer(book)
    }

    private fun handleSleepTimer(book: Book?) {
        when (val t = SleepTimer.state.value) {
            is SleepTimer.Countdown -> {
                val left = t.remaining()
                if (left <= 0) {
                    exo.pause()
                    exo.volume = 1f
                    fading = false
                    SleepTimer.cancel()
                } else if (settings.value.sleepFade && left < FADE_MS && exo.isPlaying) {
                    fading = true
                    exo.volume = (left.toFloat() / FADE_MS).coerceIn(0.05f, 1f)
                } else if (fading) {
                    fading = false
                    exo.volume = 1f
                }
            }
            is SleepTimer.ChapterEnd -> {
                if (book == null || book.id != t.bookId) {
                    SleepTimer.cancel()
                } else if (currentGlobal(book) >= t.chapterEndMs - 120) {
                    exo.pause()
                    seekGlobal(book, min(t.chapterEndMs, max(0, book.durationMs - 500)))
                    SleepTimer.cancel()
                }
            }
            null -> Unit
        }
    }

    private fun updateShake() {
        val active = SleepTimer.state.value is SleepTimer.Countdown && settings.value.shakeToExtend && exo.isPlaying
        if (active) shake.start() else shake.stop()
    }

    private fun onShake() {
        if (!SleepTimer.restart()) return
        if (fading) {
            fading = false
            exo.volume = 1f
        }
        vibrate()
    }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        runCatching { v?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    /** Zeigt bei Dateien mit eingebetteten Kapiteln den Kapiteltitel in der Benachrichtigung. */
    private fun updateChapterMetadata(book: Book, chapterIndex: Int) {
        val ch = book.chapters.getOrNull(chapterIndex) ?: return
        val idx = exo.currentMediaItemIndex
        if (ch.track != idx || book.chapters.count { it.track == idx } < 2) return
        val entry = library.entry(book.id) ?: return
        val current = exo.currentMediaItem ?: return
        val title = ch.title ?: getString(R.string.ab_chapter_n, chapterIndex + 1)
        if (current.mediaMetadata.title?.toString() == title) return
        exo.replaceMediaItem(idx, trackItem(entry, idx, title))
    }

    private fun persistNow() {
        if (!::exo.isInitialized) return
        val book = currentBook()
        if (book != null && exo.mediaItemCount > 0 && exo.playbackState != Player.STATE_ENDED) {
            library.savePosition(book.id, currentGlobal(book))
        }
        library.flush()
        app.stats.flush()
        lastPersist = SystemClock.elapsedRealtime()
    }

    // ------------------------------------------------------------------ Tasten

    private fun customLayout(): List<CommandButton> {
        val s = settings.value
        return listOf(
            CommandButton.Builder(backIcon(s.skipBackSec))
                .setSessionCommand(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
                .setDisplayName(resources.getQuantityString(R.plurals.ab_skip_back_n, s.skipBackSec, s.skipBackSec))
                .build(),
            CommandButton.Builder(forwardIcon(s.skipForwardSec))
                .setSessionCommand(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
                .setDisplayName(resources.getQuantityString(R.plurals.ab_skip_forward_n, s.skipForwardSec, s.skipForwardSec))
                .build(),
        )
    }

    private fun backIcon(sec: Int) = when (sec) {
        5 -> CommandButton.ICON_SKIP_BACK_5
        10 -> CommandButton.ICON_SKIP_BACK_10
        15 -> CommandButton.ICON_SKIP_BACK_15
        30 -> CommandButton.ICON_SKIP_BACK_30
        else -> CommandButton.ICON_SKIP_BACK
    }

    private fun forwardIcon(sec: Int) = when (sec) {
        5 -> CommandButton.ICON_SKIP_FORWARD_5
        10 -> CommandButton.ICON_SKIP_FORWARD_10
        15 -> CommandButton.ICON_SKIP_FORWARD_15
        30 -> CommandButton.ICON_SKIP_FORWARD_30
        else -> CommandButton.ICON_SKIP_FORWARD
    }

    private inner class Callback : MediaLibrarySession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                .add(SessionCommand(ACTION_REWIND, Bundle.EMPTY))
                .add(SessionCommand(ACTION_FORWARD, Bundle.EMPTY))
                .build()
            val system = session.isMediaNotificationController(controller) ||
                session.isAutomotiveController(controller) ||
                session.isAutoCompanionController(controller)
            val playerCommands = if (system) {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                    .removeAll(
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_NEXT,
                        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                    ).build()
            } else {
                MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS
            }
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setAvailablePlayerCommands(playerCommands)
                .setCustomLayout(customLayout())
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_REWIND -> skipBy(-skipBackMs)
                ACTION_FORWARD -> skipBy(skipForwardMs)
                else -> return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val ev = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            val handled = when (ev.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
                KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> true
                else -> false
            }
            if (!handled) return false
            if (ev.action != KeyEvent.ACTION_DOWN || ev.repeatCount > 0) return true
            when (ev.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
                KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
                KeyEvent.KEYCODE_MEDIA_NEXT -> headsetNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> headsetPrevious()
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> skipBy(skipForwardMs)
                else -> skipBy(-skipBackMs)
            }
            return true
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val out = ArrayList<MediaItem>()
            for (item in mediaItems) {
                val req = MediaIds.parseBook(item.mediaId)
                if (req != null) {
                    resolveBook(req)?.let { out.addAll(it.mediaItems) }
                    continue
                }
                val id = MediaIds.bookOfTrack(item.mediaId)
                val idx = MediaIds.trackIndex(item.mediaId)
                val entry = library.entry(id)
                if (entry != null && idx != null && idx in entry.book.tracks.indices) out += trackItem(entry, idx)
            }
            return Futures.immediateFuture<MutableList<MediaItem>>(out)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val query = mediaItems.singleOrNull()?.requestMetadata?.searchQuery
            if (query != null) {
                val found = library.search(query) ?: library.lastPlayed()
                val resolved = found?.let { resolveBook(MediaIds.BookRequest(it.id, null)) }
                return if (resolved != null) Futures.immediateFuture(resolved)
                else Futures.immediateFailedFuture(UnsupportedOperationException())
            }
            val single = mediaItems.singleOrNull()?.let { MediaIds.parseBook(it.mediaId) }
            if (single != null) {
                val resolved = resolveBook(single)
                if (resolved != null) return Futures.immediateFuture(resolved)
                return Futures.immediateFailedFuture(UnsupportedOperationException())
            }
            return super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val req = lastPlayedRequest() ?: return Futures.immediateFailedFuture(UnsupportedOperationException())
            return resolveBook(req)?.let { Futures.immediateFuture(it) }
                ?: Futures.immediateFailedFuture(UnsupportedOperationException())
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // Die Systemoberfläche fragt nach dem zuletzt gehörten Titel für ihre Fortsetzen-Karte.
            val root = if (params?.isRecent == true) MediaIds.RESUME else MediaIds.ROOT
            return Futures.immediateFuture(LibraryResult.ofItem(folderItem(root, getString(R.string.app_name)), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String,
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val req = MediaIds.parseBook(mediaId)
            val entry = req?.let { library.entry(it.id) }
            return Futures.immediateFuture(
                if (entry != null) LibraryResult.ofItem(browseItem(entry), null)
                else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE),
            )
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val entries = library.currentEntries()
            val list: List<MediaItem> = when (parentId) {
                MediaIds.ROOT -> listOf(
                    folderItem(MediaIds.RECENT, getString(R.string.ab_auto_recent)),
                    folderItem(MediaIds.ALL, getString(R.string.ab_auto_all)),
                )
                MediaIds.RESUME -> listOfNotNull(library.lastPlayed()?.let(::browseItem))
                MediaIds.RECENT -> entries.filter { it.state.lastPlayedAt > 0 && !it.state.finished }
                    .sortedByDescending { it.state.lastPlayedAt }
                    .take(20)
                    .map(::browseItem)
                MediaIds.ALL -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title }).map(::browseItem)
                else -> return Futures.immediateFuture(LibraryResult.ofError(SessionError.ERROR_BAD_VALUE))
            }
            val from = (page * pageSize).coerceAtMost(list.size)
            val to = if (pageSize > 0) min(list.size, from + pageSize) else list.size
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list.subList(from, to)), params))
        }
    }

    companion object {
        const val ACTION_REWIND = "app.simplenarrator.audiobooks.action.REWIND"
        const val ACTION_FORWARD = "app.simplenarrator.audiobooks.action.FORWARD"
        private const val FADE_MS = 15_000L
        private const val FOREGROUND_DELAY_MS = 500L
    }
}
