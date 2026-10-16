package app.simplenarrator.audiobooks.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.simplenarrator.R

/** Lesbarer Name eines Bibliotheksordners aus seiner Baum-URI. */
fun rootLabel(uri: String): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
    val path = id.substringAfter(':', "")
    path.substringAfterLast('/').ifEmpty { id.substringBefore(':') }
}.getOrDefault(uri)

/** Vollständiger Pfad für die Anzeige in den Einstellungen. */
fun rootPath(uri: String): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(Uri.parse(uri))
    val volume = id.substringBefore(':')
    val path = id.substringAfter(':', "")
    val vol = if (volume == "primary") "" else "$volume/"
    "$vol$path".ifEmpty { volume }
}.getOrDefault(uri)

/** Fragt nach der Art des Ordners und öffnet dann die Ordnerauswahl. Liefert die Startfunktion. */
@Composable
fun rememberFolderPicker(): () -> Unit {
    val app = LocalApp.current
    val context = LocalContext.current
    var askType by remember { mutableStateOf(false) }
    var single by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            app.library.addRoot(uri.toString(), single)
        }
    }
    if (askType) {
        AlertDialog(
            onDismissRequest = { askType = false },
            title = { Text(stringResource(R.string.ab_folder_type_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FolderTypeOption(
                        Icons.AutoMirrored.Rounded.LibraryBooks,
                        stringResource(R.string.ab_folder_type_collection),
                        stringResource(R.string.ab_folder_type_collection_desc),
                    ) {
                        single = false
                        askType = false
                        launcher.launch(null)
                    }
                    FolderTypeOption(
                        Icons.Rounded.Album,
                        stringResource(R.string.ab_folder_type_single),
                        stringResource(R.string.ab_folder_type_single_desc),
                    ) {
                        single = true
                        askType = false
                        launcher.launch(null)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { askType = false }) { Text(stringResource(R.string.ab_cancel)) } },
        )
    }
    return remember { { askType = true } }
}

@Composable
private fun FolderTypeOption(icon: ImageVector, title: String, desc: String, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
