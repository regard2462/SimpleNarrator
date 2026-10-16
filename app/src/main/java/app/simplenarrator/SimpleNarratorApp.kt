package app.simplenarrator

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import app.simplenarrator.audiobooks.data.AndroidLibraryPlatform
import app.simplenarrator.audiobooks.data.AudiobookFiles
import app.simplenarrator.audiobooks.data.Library
import app.simplenarrator.audiobooks.data.Settings
import app.simplenarrator.audiobooks.data.Stats
import app.simplenarrator.audiobooks.online.OnlineCovers
import app.simplenarrator.audiobooks.playback.PlaybackConnection
import app.simplenarrator.audiobooks.playback.SleepTimer
import app.simplenarrator.data.BookRepository
import app.simplenarrator.data.SettingsStore
import app.simplenarrator.export.ExportWorker
import app.simplenarrator.playback.PlaybackController
import app.simplenarrator.playback.ReadAloudService
import app.simplenarrator.tts.ModelManager
import app.simplenarrator.tts.TtsManager
import app.simplenarrator.util.AppLanguage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SimpleNarratorApp : Application() {
    /** Vorlese-Bereich (EPUB, PDF, Text). */
    lateinit var container: AppContainer
        private set

    // Hörbuch-Bereich (übernommen aus Simple Audiobooks). Die Namen bleiben so, weil dessen Code sie erwartet.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings
        private set
    lateinit var library: Library
        private set
    lateinit var stats: Stats
        private set
    lateinit var playback: PlaybackConnection
        private set
    lateinit var onlineCovers: OnlineCovers
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        settings = Settings(this)
        SleepTimer.clock = { SystemClock.elapsedRealtime() }
        val audiobookDir = AudiobookFiles.dir(this)
        stats = Stats(audiobookDir, scope)
        library = Library(audiobookDir, settings, scope, AndroidLibraryPlatform(this))
        playback = PlaybackConnection(this)
        onlineCovers = OnlineCovers(
            library, settings, scope, "SimpleNarrator/${BuildConfig.VERSION_NAME} (Android audiobook player)",
        ).also { it.start() }
        createChannels(this)
    }

    companion object {
        /** Legt die Kanäle an oder benennt sie nach einem Sprachwechsel um. */
        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            val res = AppLanguage.wrap(context)
            nm.createNotificationChannel(NotificationChannel(ExportWorker.CHANNEL, res.getString(R.string.channel_export), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(ReadAloudService.CHANNEL, res.getString(R.string.channel_read_aloud), NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(app.simplenarrator.input.OcrImportWorker.CHANNEL, res.getString(R.string.ocr_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }
}

/** Einfache Abhängigkeits-Sammlung des Vorlese-Bereichs (kein DI-Framework nötig). */
class AppContainer(context: Context) {
    val settings = SettingsStore(context)
    val repo = BookRepository(context)
    val models = ModelManager(context)
    val tts = TtsManager(models, settings)
    val playback = PlaybackController(context)
    val audio = app.simplenarrator.tts.SentenceAudio(context, tts)
    val annotations = app.simplenarrator.data.AnnotationStore(repo)
    /** Gemeinsamer HTTP-Client für Webartikel, Gutenberg und Audiobookshelf. */
    val http: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    /** Texterkennung für gescannte PDFs. */
    val ocr = app.simplenarrator.input.Ocr(context, http)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init { scope.launch { repo.load() } }
}
