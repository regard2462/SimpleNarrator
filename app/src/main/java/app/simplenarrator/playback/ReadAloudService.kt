package app.simplenarrator.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import app.simplenarrator.MainActivity
import app.simplenarrator.R
import app.simplenarrator.SimpleNarratorApp
import app.simplenarrator.audiobooks.playback.ShakeDetector
import app.simplenarrator.data.AppSettings
import app.simplenarrator.data.Book
import app.simplenarrator.data.Position
import app.simplenarrator.tts.TtsManager
import app.simplenarrator.util.AppLanguage
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Meldungen des Vorlesedienstes an die Oberfläche (gleicher Prozess). */
object ReadAloudEvents {
    /** Vorlesen ist gescheitert, weil keine Stimme installiert ist. */
    val noVoice = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
}

/**
 * Vorlese-Dienst: lässt Sätze vorausschauend (LOOKAHEAD) über [app.simplenarrator.tts.SentenceAudio] erzeugen
 * und hängt sie als MediaItems an ExoPlayer. Die MediaSession liefert Benachrichtigung, Sperrbildschirm,
 * Bluetooth-Tasten und Android Auto ohne Zusatzcode.
 */
@OptIn(UnstableApi::class)
class ReadAloudService : MediaSessionService() {
    companion object {
        private const val TAG = "ReadAloudService"
        private const val LOOKAHEAD = 6      // Sätze im Voraus
        private const val KEEP_BEHIND = 4    // Sätze rückwärts behalten (für "zurück")
        const val CHANNEL = "read_aloud"
        private const val NOTIFICATION_ID = 2002
        /** Extras eines Satzes: angehängte Stille in ms (für die Wort-Hervorhebung). */
        const val EXTRA_PAUSE_MS = "pauseMs"
        private const val FADE_MS = 30_000L
        private const val REWIND_AFTER_MS = 5 * 60_000L
    }

    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var synthJob: Job? = null
    private var sleepJob: Job? = null
    private var sleepAtChapterEnd = false
    private var sleepMinutes = 0
    private var sleepEndsAt = 0L
    private var pausedAt = 0L

    private var book: Book? = null
    private var cursor: Position? = null          // nächster zu erzeugender Satz
    private var currentChapter = -1
    private var lastSavedAt = 0L
    private var s = AppSettings()

    private val container get() = (application as SimpleNarratorApp).container
    private val shake by lazy { ShakeDetector(this) { onShake() } }

    override fun attachBaseContext(base: Context) {
        // Texte der Medien-Benachrichtigung in der App-Sprache (vor Android 13)
        super.attachBaseContext(AppLanguage.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_READER, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Der Hörbuch-Dienst nutzt die Standard-ID und Benachrichtigung 1001; beides muss hier abweichen.
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(NOTIFICATION_ID)
                .setChannelId(CHANNEL)
                .setChannelName(R.string.channel_read_aloud)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_app) }
        )
        session = MediaSession.Builder(this, player)
            .setId("read-aloud")
            .setCallback(SessionCallback())
            .setSessionActivity(openApp)
            .build()

        scope.launch {
            container.settings.flow.collect {
                s = it
                // Lernmodus: nach jedem Satz anhalten
                player.pauseAtEndOfMediaItems = it.stopAfterSentence
                updateShake()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        synthJob?.cancel(); sleepJob?.cancel(); scope.cancel()
        shake.stop()
        session?.run { player.release(); release() }
        session = null
        container.audio.clearTemp()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Steuerung

    private fun startReading(bookId: String, chapter: Int, sentence: Int) {
        val b = container.repo.getBook(bookId) ?: run { Log.w(TAG, "Buch $bookId nicht gefunden"); return }
        synthJob?.cancel()
        player.stop()
        player.clearMediaItems()
        container.audio.clearTemp()
        book = b
        currentChapter = chapter
        pausedAt = 0L
        cursor = Position(chapter.coerceIn(0, b.chapters.lastIndex), sentence.coerceAtLeast(0))
        player.volume = 1f
        player.playWhenReady = true
        synthJob = scope.launch { produceLoop() }
    }

    private fun stopReading() {
        synthJob?.cancel(); synthJob = null
        cancelSleep()
        Cue.parse(player.currentMediaItem?.mediaId)?.let { saveProgress(it, force = true) }
        player.stop()
        player.clearMediaItems()
        book = null
        cursor = null
        container.audio.clearTemp()
    }

    private fun jumpChapter(delta: Int) {
        val b = book ?: return
        val cue = Cue.parse(player.currentMediaItem?.mediaId) ?: cursor?.let { Cue(b.id, it.chapter, it.sentence) } ?: return
        val target = (cue.chapter + delta).coerceIn(0, b.chapters.lastIndex)
        startReading(b.id, target, 0)
    }

    // ---------------------------------------------------------------- Schlaf-Timer

    private fun cancelSleep() {
        sleepJob?.cancel(); sleepJob = null
        sleepAtChapterEnd = false
        sleepEndsAt = 0L
        player.volume = 1f
        updateShake()
    }

    private fun setSleepTimer(minutes: Int) {
        cancelSleep()
        sleepMinutes = minutes
        when {
            minutes > 0 -> {
                sleepEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
                sleepJob = scope.launch { sleepLoop() }
            }
            minutes == -1 -> sleepAtChapterEnd = true
        }
        updateShake()
    }

    /** Läuft bis zum Ende; in den letzten 30 Sekunden wird die Stimme leiser (abschaltbar). */
    private suspend fun sleepLoop() {
        while (scope.isActive) {
            val left = sleepEndsAt - SystemClock.elapsedRealtime()
            if (left <= 0) {
                player.pause()
                player.volume = 1f
                sleepEndsAt = 0L
                updateShake()
                return
            }
            player.volume = if (s.sleepFade && left < FADE_MS) (left.toFloat() / FADE_MS).coerceIn(0.05f, 1f) else 1f
            delay(250)
        }
    }

    private fun updateShake() {
        if (s.shakeToExtend && sleepEndsAt > 0) shake.start() else shake.stop()
    }

    /** Schütteln verlängert einen laufenden Timer um seine ursprüngliche Dauer. */
    private fun onShake() {
        if (sleepEndsAt <= 0 || sleepMinutes <= 0) return
        sleepEndsAt = SystemClock.elapsedRealtime() + sleepMinutes * 60_000L
        player.volume = 1f
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

    // ---------------------------------------------------------------- Synthese-Pipeline

    private suspend fun produceLoop() {
        while (scope.isActive) {
            val b = book ?: return
            val pos = cursor ?: return
            val queued = player.mediaItemCount - player.currentMediaItemIndex - 1
            if (player.mediaItemCount > 0 && queued >= LOOKAHEAD) { delay(250); continue }

            val chapter = b.chapters.getOrNull(pos.chapter) ?: return   // Buchende
            if (chapter.sentenceAt(pos.sentence) == null) { cursor = Position(pos.chapter + 1, 0); continue }

            val clip = try {
                container.audio.clip(b, pos.chapter, pos.sentence, s)
            } catch (e: TtsManager.NoModelException) {
                Log.e(TAG, "Kein Modell", e)
                ReadAloudEvents.noVoice.tryEmit(Unit)
                stopReading()
                return
            } catch (e: Exception) {
                Log.e(TAG, "Synthese fehlgeschlagen in Kapitel ${pos.chapter}, Satz ${pos.sentence}", e)
                cursor = Position(pos.chapter, pos.sentence + 1); continue
            }

            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(clip.file))
                .setMediaId(Cue(b.id, pos.chapter, pos.sentence).toMediaId())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(chapter.title)
                        .setArtist(b.title)
                        .setAlbumTitle(b.author.ifBlank { null })
                        .setArtworkUri(container.repo.coverFile(b)?.let { Uri.fromFile(it) })
                        .setExtras(Bundle().apply { putInt(EXTRA_PAUSE_MS, clip.pauseMs) })
                        .build()
                )
                .build()
            player.addMediaItem(item)
            cursor = Position(pos.chapter, pos.sentence + 1)

            when (player.playbackState) {
                Player.STATE_IDLE -> player.prepare()
                Player.STATE_ENDED -> {              // Puffer war leer: hinten weitermachen
                    player.seekTo(player.mediaItemCount - 1, 0)
                    player.prepare()
                }
            }
        }
    }

    // ---------------------------------------------------------------- Player-Ereignisse

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val cue = Cue.parse(mediaItem?.mediaId) ?: return
            if (cue.chapter != currentChapter) {
                currentChapter = cue.chapter
                if (sleepAtChapterEnd) { sleepAtChapterEnd = false; player.pause() }
                saveProgress(cue, force = true)
            } else saveProgress(cue, force = false)

            // alte Sätze aus der Playlist nehmen; im Zwischenspeicher bleiben die Dateien, sonst weg damit
            val idx = player.currentMediaItemIndex
            if (idx > KEEP_BEHIND) {
                val n = idx - KEEP_BEHIND
                if (!s.cacheAudio) for (i in 0 until n) {
                    player.getMediaItemAt(i).localConfiguration?.uri?.path?.let { java.io.File(it).delete() }
                }
                player.removeMediaItems(0, n)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                if (player.playbackState == Player.STATE_READY) pausedAt = SystemClock.elapsedRealtime()
                Cue.parse(player.currentMediaItem?.mediaId)?.let { saveProgress(it, force = true) }
            } else {
                // Nach längerer Pause einen Satz zurück, damit der Faden wieder da ist
                val paused = pausedAt
                pausedAt = 0L
                if (s.rewindAfterPause && !s.stopAfterSentence && paused > 0 &&
                    SystemClock.elapsedRealtime() - paused > REWIND_AFTER_MS && player.currentMediaItemIndex > 0
                ) player.seekTo(player.currentMediaItemIndex - 1, 0)
            }
        }
    }

    private fun saveProgress(cue: Cue, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastSavedAt < 4000) return
        lastSavedAt = now
        scope.launch { container.repo.saveProgress(cue.bookId, cue.chapter, cue.sentence) }
    }

    // ---------------------------------------------------------------- Session-Callback

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(Commands.READ_FROM, Bundle.EMPTY))
                .add(SessionCommand(Commands.NEXT_CHAPTER, Bundle.EMPTY))
                .add(SessionCommand(Commands.PREV_CHAPTER, Bundle.EMPTY))
                .add(SessionCommand(Commands.SLEEP_TIMER, Bundle.EMPTY))
                .add(SessionCommand(Commands.STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession, controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand, args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                Commands.READ_FROM -> args.getString(Commands.ARG_BOOK)?.let {
                    startReading(it, args.getInt(Commands.ARG_CHAPTER, 0), args.getInt(Commands.ARG_SENTENCE, 0))
                }
                Commands.NEXT_CHAPTER -> jumpChapter(+1)
                Commands.PREV_CHAPTER -> jumpChapter(-1)
                Commands.SLEEP_TIMER -> setSleepTimer(args.getInt(Commands.ARG_MINUTES, 0))
                Commands.STOP -> stopReading()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
