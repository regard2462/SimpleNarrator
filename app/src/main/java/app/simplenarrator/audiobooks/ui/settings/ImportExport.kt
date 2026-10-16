package app.simplenarrator.audiobooks.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Backup
import app.simplenarrator.audiobooks.data.BackupFile
import app.simplenarrator.audiobooks.data.SettingsBackup
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.util.AppLanguage
import app.simplenarrator.audiobooks.util.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Startet Export oder Import; beides öffnet die Dateiauswahl des Systems. */
class ImportExport(val export: () -> Unit, val import: () -> Unit)

/**
 * Export und Import von Hörständen, Lesezeichen, Statistik und Einstellungen. Beim Import zeigt ein Dialog, was die
 * Datei enthält, und lässt wählen, was übernommen wird.
 */
@Composable
fun rememberImportExport(): ImportExport {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<BackupFile?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val file = BackupFile(
                        exportedAt = System.currentTimeMillis(),
                        books = app.library.exportBooks(),
                        stats = app.stats.data.value,
                        settings = SettingsBackup.from(app.settings.value, AppLanguage.current(context)),
                    )
                    context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(Backup.encode(file).toByteArray()) } != null
                }.getOrDefault(false)
            }
            Toast.makeText(context, if (ok) R.string.ab_backup_saved else R.string.ab_backup_failed, Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }?.let(Backup::decode)
                }.getOrNull()
            }
            if (file == null) Toast.makeText(context, R.string.ab_backup_invalid, Toast.LENGTH_LONG).show() else opened = file
        }
    }

    opened?.let { file ->
        ImportDialog(file, onDismiss = { opened = null }) { books, stats, settings ->
            opened = null
            scope.launch {
                val imported = file.settings?.takeIf { settings }
                val language = imported?.language?.takeIf { it in AppLanguage.OPTIONS && it != AppLanguage.current(context) }
                // Meldungen schon in der Sprache, die gleich gilt
                val text = if (language != null) AppLanguage.contextFor(context, language) else context
                val messages = ArrayList<String>()
                if (books) {
                    val (done, waiting) = withContext(Dispatchers.IO) { app.library.importBooks(file.books) }
                    if (done > 0 || waiting == 0) messages += text.resources.getQuantityString(R.plurals.ab_backup_restored, done, done)
                    if (waiting > 0) messages += text.resources.getQuantityString(R.plurals.ab_import_waiting, waiting, waiting)
                }
                if (stats) file.stats?.let { app.stats.replace(Backup.mergeStats(app.stats.data.value, it)) }
                if (imported != null) {
                    app.settings.update { imported.applyTo(it) }
                    messages += text.getString(R.string.ab_import_settings_done)
                }
                // Einzeln, weil Android Hinweise nach zwei Zeilen abschneidet; sie erscheinen nacheinander
                messages.forEach { Toast.makeText(context.applicationContext, it, Toast.LENGTH_LONG).show() }
                // Zuletzt, weil ein Sprachwechsel die Seite neu aufbaut
                if (language != null) context.findActivity()?.let { AppLanguage.set(it, language) }
            }
        }
    }

    return remember(exportLauncher, importLauncher) {
        ImportExport(
            export = { exportLauncher.launch("simple-audiobooks-${LocalDate.now()}.json") },
            import = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
        )
    }
}

@Composable
private fun ImportDialog(file: BackupFile, onDismiss: () -> Unit, onImport: (books: Boolean, stats: Boolean, settings: Boolean) -> Unit) {
    val context = LocalContext.current
    val hasBooks = file.books.isNotEmpty()
    val hasStats = !file.stats?.days.isNullOrEmpty()
    val hasSettings = file.settings != null
    var books by remember { mutableStateOf(hasBooks) }
    var stats by remember { mutableStateOf(hasStats) }
    var settings by remember { mutableStateOf(hasSettings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ab_import_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.ab_import_from, Format.date(context, file.exportedAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (hasBooks) {
                    Choice(books, stringResource(R.string.ab_import_books), pluralStringResource(R.plurals.ab_books, file.books.size, file.books.size)) { books = it }
                }
                if (hasStats) Choice(stats, stringResource(R.string.ab_statistics), null) { stats = it }
                if (hasSettings) Choice(settings, stringResource(R.string.ab_settings), stringResource(R.string.ab_import_settings_desc)) { settings = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onImport(books, stats, settings) }, enabled = books || stats || settings) {
                Text(stringResource(R.string.ab_import_do))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ab_cancel)) } },
    )
}

@Composable
private fun Choice(checked: Boolean, title: String, subtitle: String?, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
