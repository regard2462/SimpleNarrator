package app.simplenarrator.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.navArgument
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import app.simplenarrator.data.Book
import app.simplenarrator.data.BookRepository
import app.simplenarrator.input.OcrImportWorker
import app.simplenarrator.input.Incoming
import app.simplenarrator.input.WebArticle
import app.simplenarrator.parser.TxtParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Vorlese-Bereich: Bibliothek, Leseansicht, Export und Einstellungen. */
@Composable
fun AppNav(c: AppContainer, pendingImport: MutableStateFlow<Incoming?>, pendingRoute: MutableStateFlow<String?>, onAtRootChanged: (Boolean) -> Unit) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var importing by remember { mutableStateOf(false) }
    var scannedPdf by remember { mutableStateOf<BookRepository.ScannedPdfException?>(null) }

    val entry by nav.currentBackStackEntryAsState()
    val atRoot = (entry?.destination?.route ?: "library") == "library"
    LaunchedEffect(atRoot) { onAtRootChanged(atRoot) }

    val importBook: (Uri) -> Unit = { uri ->
        scope.launch {
            importing = true
            runCatching { c.repo.importFromUri(uri) }
                .onSuccess { nav.navigate("reader/${it.id}") }
                .onFailure {
                    if (it is BookRepository.ScannedPdfException) scannedPdf = it
                    else snackbar.showSnackbar(ctx.getString(R.string.import_failed, it.message ?: it.javaClass.simpleName))
                }
            importing = false
        }
    }
    var pasteDialog by remember { mutableStateOf<Incoming.Text?>(null) }
    var webDialog by remember { mutableStateOf<String?>(null) }

    /** Fertig aufbereitetes Buch (eingefügter Text, Webartikel) speichern und öffnen. */
    fun importParsed(block: suspend () -> Book) {
        scope.launch {
            importing = true
            runCatching { block() }
                .onSuccess { nav.navigate("reader/${it.id}") }
                .onFailure { snackbar.showSnackbar(ctx.getString(R.string.import_failed, inputErrorText(ctx, it))) }
            importing = false
        }
    }

    val incoming by pendingImport.collectAsState()
    LaunchedEffect(incoming) {
        val i = incoming ?: return@LaunchedEffect
        pendingImport.value = null
        when (i) {
            is Incoming.FileUri -> importBook(Uri.parse(i.uri))
            is Incoming.Text -> pasteDialog = i
            is Incoming.Url -> webDialog = i.url
        }
    }
    val route by pendingRoute.collectAsState()
    LaunchedEffect(route) { route?.let { pendingRoute.value = null; nav.navigate(it) { launchSingleTop = true } } }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { PlayerBar(c, onOpen = { cue -> nav.navigate("reader/${cue.bookId}") }) },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = Modifier.padding(padding)) {
            composable("library") {
                LibraryScreen(
                    c = c, importing = importing, onImport = importBook,
                    onOpen = { nav.navigate("reader/$it") },
                    onExport = { nav.navigate("export/$it") },
                    onSettings = { nav.navigate("settings") },
                    onPaste = { pasteDialog = Incoming.Text("", null) },
                    onWeb = { webDialog = "" },
                    onGutenberg = { nav.navigate("gutenberg") },
                )
            }
            composable("gutenberg") {
                val langs = remember { listOf(java.util.Locale.getDefault().language).filter { it == "de" || it == "en" }.ifEmpty { listOf("en") } }
                GutenbergScreen(c.http, langs, onBack = { nav.popBackStack() }) { file, _ ->
                    val book = c.repo.importFromUri(Uri.fromFile(file))
                    nav.navigate("reader/${book.id}") { popUpTo("library") }
                }
            }
            composable(
                "reader/{id}?ch={ch}&s={s}",
                arguments = listOf(
                    navArgument("ch") { type = NavType.IntType; defaultValue = -1 },
                    navArgument("s") { type = NavType.IntType; defaultValue = -1 },
                ),
            ) { e ->
                val id = e.arguments?.getString("id").orEmpty()
                val ch = e.arguments?.getInt("ch") ?: -1
                val s = e.arguments?.getInt("s") ?: -1
                ReaderScreen(
                    c, id, target = if (ch >= 0 && s >= 0) SentenceRef(ch, s) else null,
                    onBack = { nav.popBackStack() },
                    onExport = { nav.navigate("export/$id") },
                    onSearch = { nav.navigate("search/$id") },
                    onOpenSettings = { nav.navigate("settings") },
                )
            }
            composable("search/{id}") { e ->
                val id = e.arguments?.getString("id").orEmpty()
                SearchScreen(c, id, onBack = { nav.popBackStack() }) { ch, s ->
                    // Leseansicht mit Sprungziel ersetzen (Suche und alte Leseansicht vom Stapel)
                    nav.navigate("reader/$id?ch=$ch&s=$s") { popUpTo("reader/{id}?ch={ch}&s={s}") { inclusive = true } }
                }
            }
            composable("export/{id}") { e ->
                ExportScreen(c, e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
            }
            composable("settings") { SettingsScreen(c, onBack = { nav.popBackStack() }) }
        }
    }

    scannedPdf?.let { s ->
        val ocrLangs by c.ocr.installed.collectAsState()
        AlertDialog(
            onDismissRequest = { scannedPdf = null; c.repo.discardStaged(s.stagingId) },
            title = { Text(stringResource(R.string.ocr_ask_title)) },
            text = {
                Text(stringResource(R.string.ocr_ask_text, s.scanned, s.pages) +
                    if (ocrLangs.isEmpty()) "\n\n" + stringResource(R.string.ocr_ask_download) else "")
            },
            confirmButton = {
                TextButton(onClick = {
                    scannedPdf = null
                    OcrImportWorker.enqueue(ctx, s.stagingId, s.displayName)
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.ocr_started)) }
                }) { Text(stringResource(R.string.ocr_start)) }
            },
            dismissButton = {
                if (s.scanned < s.pages) TextButton(onClick = {
                    scannedPdf = null
                    importParsed { c.repo.finishPdfImport(s.stagingId, emptyMap()) }
                }) { Text(stringResource(R.string.ocr_skip)) }
                else TextButton(onClick = { scannedPdf = null; c.repo.discardStaged(s.stagingId) }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    pasteDialog?.let { p ->
        PasteTextDialog(p.text, onDismiss = { pasteDialog = null }, initialTitle = p.subject.orEmpty(), onConfirm = { title, text ->
            pasteDialog = null
            importParsed { c.repo.importParsed(TxtParser.parseText(text, title), "text", fallbackTitle = title.ifBlank { ctx.getString(R.string.in_paste_title) }) }
        })
    }
    webDialog?.let { url ->
        WebArticleDialog(url, onDismiss = { webDialog = null }, onConfirm = { u ->
            webDialog = null
            importParsed {
                val a = WebArticle.fetchArticle(u, c.http)
                c.repo.importParsed(WebArticle.toBook(a), "web", a.siteName, a.url, a.imageUrl?.let { WebArticle.fetchImage(it, c.http) })
            }
        })
    }
}
