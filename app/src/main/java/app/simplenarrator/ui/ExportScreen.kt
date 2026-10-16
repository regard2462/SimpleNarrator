package app.simplenarrator.ui

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.data.AppSettings
import app.simplenarrator.export.AbsUploadSource
import app.simplenarrator.export.ExportFormat
import app.simplenarrator.export.ExportWorker
import app.simplenarrator.export.fromUri
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ganzes Buch oder Kapitelbereich als Hörbuch erzeugen (läuft im Hintergrund weiter): MP3, M4A, M4B oder WAV,
 * eine Datei pro Kapitel oder eine fürs ganze Buch mit Kapitelmarken, auf Wunsch mit Untertiteln und Mitlese-EPUB.
 * Danach lässt sich der Ordner zu den Hörbüchern hinzufügen oder zu Audiobookshelf hochladen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(c: AppContainer, bookId: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val app = LocalApp.current
    val scope = rememberCoroutineScope()
    val switchTab = LocalTabSwitcher.current
    val books by c.repo.books.collectAsState()
    val settings by c.settings.flow.collectAsState(initial = null)
    val book = books.firstOrNull { it.id == bookId } ?: return
    val s = settings ?: return
    val n = book.chapters.size
    var range by remember { mutableStateOf(0f..(n - 1).toFloat()) }
    var format by remember { mutableStateOf(runCatching { ExportFormat.valueOf(s.exportFormat) }.getOrDefault(ExportFormat.MP3)) }
    var single by remember { mutableStateOf(s.exportSingle) }
    var subtitles by remember { mutableStateOf(s.exportSubtitles) }
    var syncEpub by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf<List<Uri>?>(null) }
    fun persist(block: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(block) }

    val infos by WorkManager.getInstance(ctx).getWorkInfosForUniqueWorkFlow(ExportWorker.workName(bookId)).collectAsState(initial = emptyList())
    val info: WorkInfo? = infos.firstOrNull()
    val running = info?.state == WorkInfo.State.RUNNING || info?.state == WorkInfo.State.ENQUEUED
    val succeeded = info?.state == WorkInfo.State.SUCCEEDED
    val label = when (info?.state) {
        WorkInfo.State.RUNNING -> info.progress.getString(ExportWorker.LABEL) ?: stringResource(R.string.export_running)
        WorkInfo.State.ENQUEUED -> stringResource(R.string.export_waiting)
        WorkInfo.State.SUCCEEDED -> info.outputData.getString(ExportWorker.LABEL) ?: stringResource(R.string.export_finished)
        WorkInfo.State.FAILED -> stringResource(R.string.export_error, info.outputData.getString(ExportWorker.LABEL) ?: stringResource(R.string.error_unknown))
        WorkInfo.State.CANCELLED -> stringResource(R.string.export_cancelled)
        else -> null
    }
    val pct = if (info?.state == WorkInfo.State.RUNNING) info.progress.getInt(ExportWorker.PROGRESS, 0) else null

    // Hörbuch-Bibliothek: ist der Exportordner schon darin?
    val roots by app.settings.flow.collectAsStateWithLifecycle()
    val exportInLibrary = roots.roots.any { Uri.decode(it.uri).contains("Download/${ExportWorker.FOLDER}") || Uri.decode(it.uri).endsWith("primary:Download") }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            app.library.addRoot(uri.toString(), false)
            switchTab(MainTab.AUDIOBOOKS)
        }
    }
    LaunchedEffect(succeeded, exportInLibrary) { if (succeeded && exportInLibrary) app.library.rescan() }

    val from = range.start.roundToInt(); val to = range.endInclusive.roundToInt()
    val sentences = (from..to).sumOf { book.chapters[it].sentenceCount }
    val estMinutes = (sentences * 3.2 / 60).roundToInt().coerceAtLeast(1)   // grob: Ø 3,2 s Sprache pro Satz

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.export_title), fontFamily = FontFamily.Serif) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text(book.title, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Serif)
            Spacer(Modifier.height(16.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.export_chapter_range, from + 1, to + 1), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (from != 0 || to != n - 1) AssistChip(onClick = { range = 0f..(n - 1).toFloat() }, label = { Text(stringResource(R.string.export_whole_book)) })
            }
            if (n > 1) RangeSlider(value = range, onValueChange = { range = it }, valueRange = 0f..(n - 1).toFloat(), steps = (n - 2).coerceAtLeast(0), enabled = !running)
            Text(pluralStringResource(R.plurals.sentence_count, sentences, sentences) + ", " + pluralStringResource(R.plurals.export_estimate, estMinutes, estMinutes),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.export_format), style = MaterialTheme.typography.titleSmall)
            ExportFormat.entries.forEach { f ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = format == f, enabled = !running, role = Role.RadioButton) { format = f; persist { it.copy(exportFormat = f.name) } }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = format == f, onClick = null, enabled = !running)
                    Text(stringResource(f.label))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.export_split), style = MaterialTheme.typography.titleSmall)
            val forcedSingle = format == ExportFormat.M4B
            listOf(false to R.string.export_split_chapters, true to R.string.export_split_single).forEach { (one, labelRes) ->
                Row(
                    Modifier.fillMaxWidth()
                        .selectable(selected = (single || forcedSingle) == one, enabled = !running && !forcedSingle, role = Role.RadioButton) {
                            single = one; persist { it.copy(exportSingle = one) }
                        }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = (single || forcedSingle) == one, onClick = null, enabled = !running && !forcedSingle)
                    Text(stringResource(labelRes))
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.export_extras), style = MaterialTheme.typography.titleSmall)
            ToggleRow(stringResource(R.string.export_subtitles_title), stringResource(R.string.export_subtitles_hint), subtitles, !running) {
                subtitles = it; persist { s -> s.copy(exportSubtitles = it) }
            }
            ToggleRow(stringResource(R.string.export_sync_title), stringResource(R.string.export_sync_hint), syncEpub, !running && format != ExportFormat.WAV) { syncEpub = it }

            Spacer(Modifier.height(24.dp))
            if (running) {
                LinearProgressIndicator(progress = { (pct ?: 0) / 100f }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(label ?: "", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { ExportWorker.cancel(ctx, bookId) }) { Text(stringResource(R.string.cancel)) }
            } else {
                Button(
                    onClick = { ExportWorker.enqueue(ctx, bookId, from, to, format, single, subtitles, syncEpub && format != ExportFormat.WAV) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.export_start)) }
                if (label != null) { Spacer(Modifier.height(12.dp)); Text(label, style = MaterialTheme.typography.bodyMedium) }
            }

            if (succeeded) {
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                if (exportInLibrary) {
                    Text(stringResource(R.string.export_in_audiobooks), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(onClick = { switchTab(MainTab.AUDIOBOOKS) }) {
                        Icon(Icons.Default.Headphones, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.export_open_audiobooks))
                    }
                } else {
                    FilledTonalButton(onClick = {
                        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/${ExportWorker.FOLDER}")
                        pickFolder.launch(initial)
                    }) { Icon(Icons.Default.Headphones, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.export_to_audiobooks)) }
                    Text(stringResource(R.string.export_to_audiobooks_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    val f = ExportWorker.resultFile(ctx, bookId)
                    upload = if (f.isFile) f.readLines().filter { it.isNotBlank() }.map(Uri::parse) else emptyList()
                }) { Icon(Icons.Default.CloudUpload, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.in_abs_upload_title)) }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.export_hint, ExportWorker.FOLDER),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    upload?.let { uris ->
        AudiobookshelfUploadSourcesDialog(c.http, uris.map { AbsUploadSource.fromUri(ctx, it) }, book.title, book.author) { upload = null }
    }
}

@Composable
private fun ToggleRow(title: String, hint: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
