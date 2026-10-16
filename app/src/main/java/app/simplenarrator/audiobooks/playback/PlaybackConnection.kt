package app.simplenarrator.audiobooks.playback

import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import app.simplenarrator.SimpleNarratorApp
import app.simplenarrator.audiobooks.data.Book
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/** Was die Oberfläche über die laufende Wiedergabe wissen muss. */
data class PlayerSnapshot(
    val bookId: String? = null,
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val buffering: Boolean = false,
    val trackIndex: Int = 0,
    val trackPositionMs: Long = 0,
    val speed: Float = 1f,
    val error: String? = null,
)

/** Verbindung der Oberfläche zum Wiedergabedienst über einen MediaController. */
class PlaybackConnection(private val app: SimpleNarratorApp) {

    private var future: ListenableFuture<MediaController>? = null
    private val controllerFlow = MutableStateFlow<MediaController?>(null)
    private val _snapshot = MutableStateFlow(PlayerSnapshot())
    val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()
    private var ticker: Job? = null

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            update(player)
            if (player.isPlaying) startTicker() else ticker?.cancel()
        }

        override fun onPlayerError(error: PlaybackException) {
            _snapshot.value = _snapshot.value.copy(error = error.errorCodeName)
        }
    }

    fun connect() {
        if (future != null) return
        val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
        val f = MediaController.Builder(app, token).buildAsync()
        future = f
        f.addListener({
            val c = runCatching { f.get() }.getOrNull() ?: run {
                future = null
                return@addListener
            }
            c.addListener(listener)
            controllerFlow.value = c
            update(c)
            if (c.isPlaying) startTicker()
        }, ContextCompat.getMainExecutor(app))
    }

    fun release() {
        ticker?.cancel()
        controllerFlow.value?.removeListener(listener)
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controllerFlow.value = null
    }

    private fun update(p: Player) {
        val id = MediaIds.bookOfTrack(p.currentMediaItem?.mediaId)
        _snapshot.value = PlayerSnapshot(
            bookId = id,
            isPlaying = p.isPlaying,
            playWhenReady = p.playWhenReady,
            buffering = p.playbackState == Player.STATE_BUFFERING,
            trackIndex = p.currentMediaItemIndex,
            trackPositionMs = max(0, p.currentPosition),
            speed = p.playbackParameters.speed,
            error = p.playerError?.errorCodeName,
        )
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = app.scope.launch {
            while (isActive) {
                controllerFlow.value?.let(::update)
                delay(250)
            }
        }
    }

    private fun withController(block: (MediaController) -> Unit) {
        controllerFlow.value?.let {
            block(it)
            return
        }
        connect()
        app.scope.launch { block(controllerFlow.filterNotNull().first()) }
    }

    // ------------------------------------------------------------------ Befehle

    /** Spielt ein Buch ab dem gespeicherten Stand oder ab [fromMs]. */
    fun play(bookId: String, fromMs: Long? = null) = withController { c ->
        val loaded = MediaIds.bookOfTrack(c.currentMediaItem?.mediaId)
        if (loaded == bookId && c.playbackState != Player.STATE_ENDED && c.playbackState != Player.STATE_IDLE) {
            if (fromMs != null) seekIn(c, bookId, fromMs)
            c.play()
            return@withController
        }
        c.setMediaItem(MediaItem.Builder().setMediaId(MediaIds.book(bookId, fromMs)).build())
        c.prepare()
        c.play()
    }

    fun togglePlay(fallbackBookId: String?) = withController { c ->
        when {
            c.mediaItemCount == 0 || c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_ENDED ->
                fallbackBookId?.let { play(it) }
            c.playWhenReady -> c.pause()
            else -> c.play()
        }
    }

    fun pause() = withController { it.pause() }

    /** Beendet die Wiedergabe, wenn genau dieses Buch geladen ist. */
    fun unload(bookId: String) {
        val c = controllerFlow.value ?: return
        if (MediaIds.bookOfTrack(c.currentMediaItem?.mediaId) != bookId) return
        c.stop()
        c.clearMediaItems()
        update(c)
    }
    fun skipBack() = withController { it.seekBack() }
    fun skipForward() = withController { it.seekForward() }

    /** Springt zu einer Gesamtposition. Ist das Buch nicht geladen, wird nur der Stand gesetzt. */
    fun seekTo(bookId: String, globalMs: Long) {
        val c = controllerFlow.value
        if (c != null && MediaIds.bookOfTrack(c.currentMediaItem?.mediaId) == bookId) {
            seekIn(c, bookId, globalMs)
        } else {
            app.library.updateState(bookId) { it.copy(positionMs = globalMs, finished = false) }
        }
    }

    private fun seekIn(c: MediaController, bookId: String, globalMs: Long) {
        val book = app.library.book(bookId) ?: return
        val (i, p) = book.locate(globalMs)
        if (i == c.currentMediaItemIndex) c.seekTo(p) else c.seekTo(i, p)
        update(c)
    }

    fun nextChapter(book: Book, globalMs: Long) {
        val idx = book.chapterAt(globalMs)
        val target = book.chapters.getOrNull(idx + 1)?.startMs ?: return
        seekTo(book.id, target)
    }

    fun previousChapter(book: Book, globalMs: Long) {
        if (book.chapters.isEmpty()) return
        val idx = book.chapterAt(globalMs)
        val ch = book.chapters[idx]
        val target = if (globalMs - ch.startMs > 3_000 || idx == 0) ch.startMs else book.chapters[idx - 1].startMs
        seekTo(book.id, target)
    }

    fun clearError() {
        _snapshot.value = _snapshot.value.copy(error = null)
    }

    /** Nach einem Fehler an derselben Stelle neu laden und weiterspielen. */
    fun retry() = withController { c ->
        clearError()
        c.prepare()
        c.play()
    }

    /** Aktuelle Gesamtposition des geladenen Buches. */
    fun globalPosition(book: Book, snap: PlayerSnapshot = _snapshot.value): Long =
        if (snap.bookId == book.id) book.globalOf(snap.trackIndex, snap.trackPositionMs) else app.library.state(book.id).positionMs
}
