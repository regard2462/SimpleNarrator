package app.simplenarrator.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import app.simplenarrator.audiobooks.ui.AppRoot
import app.simplenarrator.input.Incoming
import kotlinx.coroutines.flow.MutableStateFlow

/** Die beiden Bereiche der App. */
enum class MainTab { BOOKS, AUDIOBOOKS }

/** Wechselt den Bereich, etwa nach einem Export direkt zu den Hörbüchern. */
val LocalTabSwitcher = staticCompositionLocalOf<(MainTab) -> Unit> { {} }

/**
 * Rahmen der App: unten die Leiste „Bücher“ (Vorlesen) und „Hörbücher“ (Player). Die Leiste ist nur auf
 * den Startseiten beider Bereiche sichtbar. Der Zustand eines Bereichs (Navigation, Scrollstand) bleibt
 * beim Wechsel erhalten.
 */
@Composable
fun MainShell(c: AppContainer, pendingImport: MutableStateFlow<Incoming?>, pendingRoute: MutableStateFlow<String?>, tabRequest: MutableStateFlow<MainTab?>, openPlayerSignal: Int) {
    var tab by rememberSaveable { mutableStateOf(MainTab.BOOKS) }
    val requested by tabRequest.collectAsState()
    LaunchedEffect(requested) { requested?.let { tab = it; tabRequest.value = null } }

    var booksAtRoot by remember { mutableStateOf(true) }
    var audioAtRoot by remember { mutableStateOf(true) }
    val showBar = if (tab == MainTab.BOOKS) booksAtRoot else audioAtRoot
    BackHandler(enabled = tab == MainTab.AUDIOBOOKS && audioAtRoot) { tab = MainTab.BOOKS }

    val holder = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).then(if (showBar) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)) {
            CompositionLocalProvider(LocalTabSwitcher provides { tab = it }) { holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    MainTab.BOOKS -> AppNav(c, pendingImport, pendingRoute, onAtRootChanged = { booksAtRoot = it })
                    MainTab.AUDIOBOOKS -> AppRoot(openPlayerSignal, bottomBarShown = showBar, onAtRootChanged = { audioAtRoot = it })
                }
            } }
        }
        if (showBar) {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == MainTab.BOOKS, onClick = { tab = MainTab.BOOKS },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                    label = { Text(stringResource(R.string.tab_books)) },
                )
                NavigationBarItem(
                    selected = tab == MainTab.AUDIOBOOKS, onClick = { tab = MainTab.AUDIOBOOKS },
                    icon = { Icon(Icons.Default.Headphones, null) },
                    label = { Text(stringResource(R.string.tab_audiobooks)) },
                )
            }
        }
    }
}
