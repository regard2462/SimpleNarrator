package app.simplenarrator.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/** Stand innerhalb des aktuellen Satzes, für die Wort-Hervorhebung. */
data class SentenceProgress(val positionMs: Long = 0, val durationMs: Long = 0, val pauseMs: Int = 0) {
    /** Anteil des gesprochenen Teils (ohne die angehängte Pause), 0..1. */
    val fraction: Float get() {
        val spoken = durationMs - pauseMs - TAIL_MS
        return if (spoken <= 0) 0f else (positionMs.toFloat() / spoken).coerceIn(0f, 1f)
    }
    private companion object { const val TAIL_MS = 120 }
}

data class PlaybackUiState(
    val active: Boolean = false,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val cue: Cue? = null,
    val speed: Float = 1f,
    val title: String = "",
    val subtitle: String = "",
)

/** UI-Seite: verbindet sich mit dem ReadAloudService und spiegelt dessen Zustand als StateFlow. */
class PlaybackController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var controller: MediaController? = null
    /** Befehle, die vor dem Verbindungsaufbau kommen (z. B. Play direkt nach App-Start). */
    private val pending = mutableListOf<(MediaController) -> Unit>()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state

    private val _progress = MutableStateFlow(SentenceProgress())
    val progress: StateFlow<SentenceProgress> = _progress
    private var ticker: Job? = null

    fun connect() {
        if (controller != null) return
        scope.launch {
            val token = SessionToken(context, ComponentName(context, ReadAloudService::class.java))
            val c = MediaController.Builder(context, token).buildAsync().await()
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    publish(c)
                    if (player.isPlaying) startTicker(c) else { ticker?.cancel(); tick(c) }
                }
            })
            publish(c)
            pending.forEach { it(c) }
            pending.clear()
        }
    }

    private fun startTicker(c: MediaController) {
        if (ticker?.isActive == true) return
        ticker = scope.launch { while (isActive) { tick(c); delay(60) } }
    }

    private fun tick(c: MediaController) {
        val pause = c.currentMediaItem?.mediaMetadata?.extras?.getInt(ReadAloudService.EXTRA_PAUSE_MS) ?: 0
        val dur = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        _progress.value = SentenceProgress(c.currentPosition, dur, pause)
    }

    fun release() {
        ticker?.cancel()
        controller?.release()
        controller = null
    }

    private fun publish(c: MediaController) {
        _state.value = PlaybackUiState(
            active = c.mediaItemCount > 0,
            isPlaying = c.isPlaying,
            buffering = c.playWhenReady && (c.playbackState == Player.STATE_BUFFERING || c.playbackState == Player.STATE_ENDED),
            cue = Cue.parse(c.currentMediaItem?.mediaId),
            speed = c.playbackParameters.speed,
            title = c.mediaMetadata.artist?.toString().orEmpty(),
            subtitle = c.mediaMetadata.title?.toString().orEmpty(),
        )
    }

    fun readFrom(bookId: String, chapter: Int, sentence: Int) = send(
        Commands.READ_FROM,
        bundleOf(Commands.ARG_BOOK to bookId, Commands.ARG_CHAPTER to chapter, Commands.ARG_SENTENCE to sentence),
    )

    fun toggle() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun nextSentence() = controller?.seekToNextMediaItem()
    fun prevSentence() = controller?.seekToPreviousMediaItem()
    fun nextChapter() = send(Commands.NEXT_CHAPTER)
    fun prevChapter() = send(Commands.PREV_CHAPTER)
    fun setSpeed(speed: Float) = controller?.setPlaybackSpeed(speed)
    fun sleepTimer(minutes: Int) = send(Commands.SLEEP_TIMER, bundleOf(Commands.ARG_MINUTES to minutes))
    fun stop() = send(Commands.STOP)

    private fun send(action: String, args: Bundle = Bundle.EMPTY) {
        val c = controller
        if (c != null) c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        else pending += { it.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args) }
    }
}
