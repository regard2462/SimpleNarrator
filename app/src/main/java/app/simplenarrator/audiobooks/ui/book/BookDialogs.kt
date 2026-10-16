package app.simplenarrator.audiobooks.ui.book

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RestartAlt
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.data.Status
import app.simplenarrator.audiobooks.ui.components.Cover
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.durationText
import app.simplenarrator.audiobooks.ui.components.rootLabel
import app.simplenarrator.audiobooks.ui.components.statusLine
import kotlinx.coroutines.launch

@Composable
fun EditBookDialog(entry: Entry, onDismiss: () -> Unit) {
    val app = LocalApp.current
    var title by rememberSaveable { mutableStateOf(entry.title) }
    var author by rememberSaveable { mutableStateOf(entry.author.orEmpty()) }
    var narrator by rememberSaveable { mutableStateOf(entry.narrator.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ab_edit_book)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.ab_field_title)) }, singleLine = true)
                OutlinedTextField(author, { author = it }, label = { Text(stringResource(R.string.ab_field_author)) }, singleLine = true)
                OutlinedTextField(narrator, { narrator = it }, label = { Text(stringResource(R.string.ab_field_narrator)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                app.library.editMeta(entry.id, title, author, narrator)
                onDismiss()
            }) { Text(stringResource(R.string.ab_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ab_cancel)) } },
    )
}

@Composable
fun ConfirmResetDialog(entry: Entry, onDismiss: () -> Unit) {
    val app = LocalApp.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ab_reset_title)) },
        text = { Text(stringResource(R.string.ab_reset_text, entry.title)) },
        confirmButton = {
            TextButton(onClick = {
                app.library.resetProgress(entry.id)
                onDismiss()
            }) { Text(stringResource(R.string.ab_reset)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ab_cancel)) } },
    )
}

/** Fragt, ob ein Buch nur aus der Bibliothek verschwinden oder auch vom Gerät gelöscht werden soll. */
@Composable
fun RemoveBookDialog(entry: Entry, onDismiss: () -> Unit, onRemoved: () -> Unit = {}) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleteFiles by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val root = remember(entry.book.root) { Uri.parse(entry.book.root) }
    fun canWrite() = context.contentResolver.persistedUriPermissions.any { it.uri == root && it.isWritePermission }

    fun delete() {
        busy = true
        scope.launch {
            val ok = app.library.deleteBookFiles(entry.id)
            busy = false
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.ab_remove_book_deleted, entry.title) else context.getString(R.string.ab_remove_book_delete_failed),
                Toast.LENGTH_LONG,
            ).show()
            onDismiss()
            if (ok) onRemoved()
        }
    }

    // Ordner aus älteren Versionen sind nur lesend freigegeben: Schreibzugriff für genau diesen Ordner anfragen.
    val grant = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        if (uri.toString() != root.toString()) {
            Toast.makeText(context, context.getString(R.string.ab_remove_book_wrong_folder, rootLabel(root.toString())), Toast.LENGTH_LONG).show()
            return@rememberLauncherForActivityResult
        }
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        delete()
    }
    val needsGrant = deleteFiles && !canWrite()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.ab_remove_book_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (deleteFiles) R.string.ab_remove_book_text_delete else R.string.ab_remove_book_text, entry.title))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { deleteFiles = !deleteFiles }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = deleteFiles, onCheckedChange = { deleteFiles = it })
                    Text(stringResource(R.string.ab_remove_book_delete_files), style = MaterialTheme.typography.bodyMedium)
                }
                if (needsGrant) {
                    Text(
                        stringResource(R.string.ab_remove_book_needs_grant, rootLabel(root.toString())),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    app.playback.unload(entry.id)
                    if (!deleteFiles) {
                        app.library.hideBook(entry.id)
                        Toast.makeText(context, context.getString(R.string.ab_remove_book_done, entry.title), Toast.LENGTH_SHORT).show()
                        onDismiss()
                        onRemoved()
                    } else if (needsGrant) {
                        val initial = runCatching {
                            DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
                        }.getOrNull()
                        grant.launch(initial)
                    } else {
                        delete()
                    }
                },
            ) {
                Text(
                    stringResource(if (needsGrant) R.string.ab_remove_book_grant else if (deleteFiles) R.string.ab_delete else R.string.ab_remove),
                    color = if (deleteFiles) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.ab_cancel)) } },
    )
}

@Composable
fun FilesDialog(entry: Entry, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ab_files)) },
        text = {
            LazyColumn {
                item {
                    Text(
                        entry.book.path.ifEmpty { "/" },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                itemsIndexed(entry.book.tracks) { i, t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(32.dp))
                        Text(t.path, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(durationText(t.durationMs), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ab_close)) } },
    )
}

/** Aktionen für ein Buch nach langem Drücken in der Bibliothek. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookActionSheet(entry: Entry, onDismiss: () -> Unit, onOpen: () -> Unit, onPlay: () -> Unit) {
    val app = LocalApp.current
    var edit by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var searchCover by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(entry, Modifier.size(56.dp), RoundedCornerShape(10.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(entry.author, statusLine(entry)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            val playLabel = when (entry.status) {
                Status.NEW -> R.string.ab_play
                Status.STARTED -> R.string.ab_resume
                Status.FINISHED -> R.string.ab_listen_again
            }
            SheetAction(Icons.Rounded.PlayArrow, stringResource(playLabel)) { onPlay(); onDismiss() }
            SheetAction(Icons.AutoMirrored.Rounded.MenuBook, stringResource(R.string.ab_details)) { onOpen(); onDismiss() }
            if (entry.status == Status.FINISHED) {
                SheetAction(Icons.Rounded.RadioButtonUnchecked, stringResource(R.string.ab_mark_unfinished)) {
                    app.library.markFinished(entry.id, false); onDismiss()
                }
            } else {
                SheetAction(Icons.Rounded.CheckCircle, stringResource(R.string.ab_mark_finished)) {
                    app.library.markFinished(entry.id, true); onDismiss()
                }
            }
            if (entry.status != Status.NEW) {
                SheetAction(Icons.Rounded.RestartAlt, stringResource(R.string.ab_reset)) { reset = true }
            }
            SheetAction(Icons.Rounded.Edit, stringResource(R.string.ab_edit_book)) { edit = true }
            SheetAction(Icons.Rounded.ImageSearch, stringResource(R.string.ab_search_cover)) { searchCover = true }
            SheetAction(Icons.Rounded.DeleteOutline, stringResource(R.string.ab_remove_book)) { remove = true }
        }
    }
    if (remove) RemoveBookDialog(entry, onDismiss = { remove = false; onDismiss() })
    if (edit) EditBookDialog(entry) { edit = false; onDismiss() }
    if (reset) ConfirmResetDialog(entry) { reset = false; onDismiss() }
    if (searchCover) CoverSearchDialog(entry) { searchCover = false; onDismiss() }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Icon(icon, null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        modifier = Modifier.clickableRow(onClick),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.padding(horizontal = 8.dp)).then(
        Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick),
    )
