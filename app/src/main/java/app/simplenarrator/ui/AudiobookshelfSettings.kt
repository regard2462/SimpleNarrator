package app.simplenarrator.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import app.simplenarrator.R
import app.simplenarrator.export.AbsConfig
import app.simplenarrator.export.AbsFolder
import app.simplenarrator.export.AbsLibrary
import app.simplenarrator.export.AbsSettings
import app.simplenarrator.export.AbsUploadSource
import app.simplenarrator.export.Audiobookshelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File

private sealed interface TestState {
    data object Idle : TestState
    data object Running : TestState
    data class Ok(val version: String) : TestState
    data class Failed(val message: String) : TestState
}

/**
 * Abschnitt für die Einstellungen: Serveradresse, API-Schlüssel, Verbindungstest, Bibliothek und Ordner.
 * Speichert jede Änderung sofort im eigenen DataStore "audiobookshelf".
 */
@Composable
fun AudiobookshelfSettings(http: OkHttpClient, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { AbsSettings(ctx) }
    val scope = rememberCoroutineScope()

    var ready by remember { mutableStateOf(false) }
    var config by remember { mutableStateOf(AbsConfig()) }
    var showToken by remember { mutableStateOf(false) }
    var test by remember { mutableStateOf<TestState>(TestState.Idle) }
    var libraries by remember { mutableStateOf<List<AbsLibrary>?>(null) }
    var libraryMenu by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        config = store.current()
        ready = true
    }

    fun save(n: AbsConfig) {
        config = n
        scope.launch { store.update { n } }
    }

    fun select(lib: AbsLibrary, folder: AbsFolder?) = save(
        config.copy(libraryId = lib.id, libraryName = lib.name, folderId = folder?.id.orEmpty(), folderPath = folder?.fullPath.orEmpty())
    )

    fun runTest(openLibraryMenu: Boolean = false) {
        scope.launch {
            test = TestState.Running
            try {
                val abs = Audiobookshelf(config.url, config.token, http)
                val version = abs.ping()
                val libs = abs.libraries()
                libraries = libs
                test = TestState.Ok(version)
                // Gespeicherte Auswahl behalten, wenn es sie noch gibt, sonst die erste Bibliothek vorschlagen
                val lib = libs.firstOrNull { it.id == config.libraryId } ?: libs.firstOrNull()
                if (lib != null) select(lib, lib.folders.firstOrNull { it.id == config.folderId } ?: lib.folders.firstOrNull())
                if (openLibraryMenu && libs.isNotEmpty()) libraryMenu = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                test = TestState.Failed(inputErrorText(ctx, e))
            }
        }
    }

    Column(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.in_abs_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.in_abs_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = config.url,
            onValueChange = { save(config.copy(url = it.trim())); libraries = null; test = TestState.Idle },
            label = { Text(stringResource(R.string.in_abs_url)) },
            placeholder = { Text(stringResource(R.string.in_abs_url_placeholder)) },
            singleLine = true,
            enabled = ready,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = config.token,
            onValueChange = { save(config.copy(token = it.trim())); libraries = null; test = TestState.Idle },
            label = { Text(stringResource(R.string.in_abs_token)) },
            singleLine = true,
            enabled = ready,
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (showToken) R.string.in_abs_hide_token else R.string.in_abs_show_token),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { runTest() }, enabled = ready && config.hasServer && test != TestState.Running) {
                Text(stringResource(R.string.in_abs_test))
            }
            Spacer(Modifier.width(12.dp))
            if (test == TestState.Running) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        when (val t = test) {
            TestState.Running -> Text(stringResource(R.string.in_abs_testing), style = MaterialTheme.typography.bodySmall)
            is TestState.Ok -> Text(stringResource(R.string.in_abs_connected, t.version), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            is TestState.Failed -> Text(t.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            TestState.Idle -> {}
        }
        if (libraries?.isEmpty() == true) {
            Text(stringResource(R.string.in_abs_no_libraries), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))

        // Bibliothek
        SelectRow(
            label = stringResource(R.string.in_abs_library),
            value = config.libraryName.ifBlank { stringResource(R.string.in_abs_choose) },
            enabled = ready && config.hasServer && test != TestState.Running,
            onClick = { if (libraries == null) runTest(openLibraryMenu = true) else libraryMenu = true },
        ) {
            DropdownMenu(expanded = libraryMenu, onDismissRequest = { libraryMenu = false }) {
                libraries.orEmpty().forEach { lib ->
                    DropdownMenuItem(text = { Text(lib.name) }, onClick = {
                        libraryMenu = false
                        select(lib, lib.folders.firstOrNull())
                    })
                }
            }
        }
        // Ordner der gewählten Bibliothek
        val folders = libraries?.firstOrNull { it.id == config.libraryId }?.folders
        SelectRow(
            label = stringResource(R.string.in_abs_folder),
            value = config.folderPath.ifBlank { stringResource(R.string.in_abs_choose) },
            enabled = ready && config.libraryId.isNotBlank() && test != TestState.Running,
            onClick = { if (folders == null) runTest() else folderMenu = true },
        ) {
            DropdownMenu(expanded = folderMenu, onDismissRequest = { folderMenu = false }) {
                folders.orEmpty().forEach { f ->
                    DropdownMenuItem(text = { Text(f.fullPath) }, onClick = {
                        folderMenu = false
                        save(config.copy(folderId = f.id, folderPath = f.fullPath))
                    })
                }
            }
        }
    }
}

@Composable
private fun SelectRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit, menu: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = onClick, enabled = enabled, modifier = Modifier.widthIn(max = 240.dp)) {
                Text(value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            menu()
        }
    }
}

private sealed interface UploadState {
    data object Idle : UploadState
    data class Running(val progress: Float) : UploadState
    data object Done : UploadState
    data class Failed(val message: String) : UploadState
}

/** Upload-Dialog für fertige Dateien (z. B. aus dem Export). Titel und Autor lassen sich vorher ändern. */
@Composable
fun AudiobookshelfUploadDialog(
    http: OkHttpClient,
    files: List<File>,
    title: String,
    author: String,
    onDismiss: () -> Unit,
) {
    val sources = remember(files) { files.filter { it.isFile }.map { AbsUploadSource.of(it) } }
    AudiobookshelfUploadSourcesDialog(http, sources, title, author, onDismiss)
}

/**
 * Wie AudiobookshelfUploadDialog, aber für beliebige Quellen, etwa die content://-Adressen,
 * die der Export im Downloads-Ordner anlegt (AbsUploadSource.fromUri).
 * Schließen des Dialogs bricht einen laufenden Upload ab.
 */
@Composable
fun AudiobookshelfUploadSourcesDialog(
    http: OkHttpClient,
    sources: List<AbsUploadSource>,
    title: String,
    author: String,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { AbsSettings(ctx) }
    val config by store.flow.collectAsState(initial = null)
    var bookTitle by rememberSaveable { mutableStateOf(title) }
    var bookAuthor by rememberSaveable { mutableStateOf(author) }
    var state by remember { mutableStateOf<UploadState>(UploadState.Idle) }
    var job by remember { mutableStateOf<Job?>(null) }
    val running = state is UploadState.Running
    val cfg = config
    val ready = cfg != null && cfg.isComplete && sources.isNotEmpty()

    fun start(c: AbsConfig) {
        job = scope.launch {
            state = UploadState.Running(0f)
            try {
                c.client(http).uploadSources(c.libraryId, c.folderId, bookTitle.trim(), bookAuthor.trim(), sources) { p ->
                    state = UploadState.Running(p)
                }
                state = UploadState.Done
            } catch (e: CancellationException) {
                state = UploadState.Idle
                throw e
            } catch (e: Exception) {
                state = UploadState.Failed(inputErrorText(ctx, e))
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = !running, dismissOnBackPress = !running),
        title = { Text(stringResource(R.string.in_abs_upload_title)) },
        text = {
            when {
                cfg == null -> CircularProgressIndicator()
                !cfg.isComplete -> Text(stringResource(R.string.in_abs_not_configured))
                sources.isEmpty() -> Text(stringResource(R.string.in_abs_no_files))
                else -> Column {
                    val editable = state is UploadState.Idle || state is UploadState.Failed
                    OutlinedTextField(
                        value = bookTitle, onValueChange = { bookTitle = it }, enabled = editable, singleLine = true,
                        label = { Text(stringResource(R.string.in_abs_book_title)) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = bookAuthor, onValueChange = { bookAuthor = it }, enabled = editable, singleLine = true,
                        label = { Text(stringResource(R.string.in_abs_author)) }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    val bytes = sources.sumOf { it.length.coerceAtLeast(0) }
                    Text(
                        pluralStringResource(R.plurals.in_abs_files, sources.size, sources.size, Formatter.formatShortFileSize(ctx, bytes)),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        stringResource(R.string.in_abs_target, cfg.libraryName.ifBlank { cfg.libraryId }, cfg.folderPath.ifBlank { cfg.folderId }),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    when (val s = state) {
                        is UploadState.Running -> {
                            LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.in_abs_uploading, (s.progress * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                        }
                        UploadState.Done -> Text(stringResource(R.string.in_abs_done), color = MaterialTheme.colorScheme.primary)
                        is UploadState.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
                        UploadState.Idle -> {}
                    }
                }
            }
        },
        confirmButton = {
            when {
                !ready || state == UploadState.Done -> TextButton(onClick = onDismiss) { Text(stringResource(R.string.in_close)) }
                running -> TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.cancel)) }
                else -> TextButton(onClick = { cfg?.let { start(it) } }, enabled = bookTitle.isNotBlank()) {
                    Text(stringResource(if (state is UploadState.Failed) R.string.in_retry else R.string.in_abs_upload))
                }
            }
        },
        dismissButton = {
            if (ready && !running && state != UploadState.Done) TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
