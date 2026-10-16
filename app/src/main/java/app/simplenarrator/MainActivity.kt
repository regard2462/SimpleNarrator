package app.simplenarrator

import android.Manifest
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.theme.AppTheme
import app.simplenarrator.audiobooks.ui.theme.isDark
import app.simplenarrator.input.Incoming
import app.simplenarrator.input.IncomingShare
import app.simplenarrator.tts.InstallVoiceDataActivity
import app.simplenarrator.ui.MainShell
import app.simplenarrator.ui.MainTab
import app.simplenarrator.util.AppLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as SimpleNarratorApp
    private val pendingImport = MutableStateFlow<Incoming?>(null)
    private val pendingRoute = MutableStateFlow<String?>(null)
    private val tabRequest = MutableStateFlow<MainTab?>(null)
    private val openPlayer = mutableIntStateOf(0)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Nach einem Sprachwechsel wird die Activity neu erzeugt; die Kanäle bekommen dann die neuen Namen.
        SimpleNarratorApp.createChannels(this)
        if (savedInstanceState == null && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val look by app.settings.flow.collectAsStateWithLifecycle()
            val dark = isDark(look.theme)
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            AppTheme(look.theme, look.palette, look.pureBlack) {
                CompositionLocalProvider(LocalApp provides app) {
                    MainShell(app.container, pendingImport, pendingRoute, tabRequest, openPlayer.intValue)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        i ?: return
        // Dateien („Öffnen mit“), geteilter Text, geteilte Links und Textauswahl „Vorlesen“
        IncomingShare.fromIntent(i)?.let { pendingImport.value = it; tabRequest.value = MainTab.BOOKS }
        if (i.getBooleanExtra(InstallVoiceDataActivity.EXTRA_OPEN_VOICES, false)) {
            tabRequest.value = MainTab.BOOKS
            pendingRoute.value = "settings"
        }
        if (i.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            tabRequest.value = MainTab.AUDIOBOOKS
            openPlayer.intValue++
        }
        if (i.getBooleanExtra(EXTRA_OPEN_READER, false)) tabRequest.value = MainTab.BOOKS
        if (i.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = i.getStringExtra(SearchManager.QUERY).orEmpty()
            lifecycleScope.launch {
                app.library.ready.first { it }
                val entry = app.library.search(query) ?: app.library.lastPlayed() ?: return@launch
                app.playback.play(entry.id)
                tabRequest.value = MainTab.AUDIOBOOKS
                openPlayer.intValue++
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.container.playback.connect()
        app.playback.connect()
    }

    override fun onStop() {
        app.library.flush()
        app.container.playback.release()
        app.playback.release()
        super.onStop()
    }

    companion object {
        /** Öffnet den Hörbuch-Player (aus der Benachrichtigung des Hörbuch-Bereichs). */
        const val EXTRA_OPEN_PLAYER = "app.simplenarrator.OPEN_PLAYER"
        /** Öffnet den Vorlese-Bereich (aus der Benachrichtigung beim Vorlesen). */
        const val EXTRA_OPEN_READER = "app.simplenarrator.OPEN_READER"
    }
}
