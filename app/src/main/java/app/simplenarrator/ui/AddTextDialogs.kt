package app.simplenarrator.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simplenarrator.R
import app.simplenarrator.input.IncomingShare
import app.simplenarrator.input.WebArticle
import java.text.NumberFormat

/** Ab dieser Länge kein Eingabefeld mehr, nur eine Vorschau (große Texte machen das Textfeld träge). */
private const val EDITABLE_LIMIT = 20_000

/**
 * Text einfügen und als Buch übernehmen. Ist initialText leer, wird der Inhalt der Zwischenablage vorgeschlagen.
 * onConfirm bekommt den Titel (leer heißt: TxtParser nimmt die erste Zeile) und den Text.
 */
@Composable
fun PasteTextDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String, text: String) -> Unit,
    initialTitle: String = "",
) {
    val clipboard = LocalClipboardManager.current
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    // Bewusst nicht rememberSaveable: sehr lange Texte sprengen sonst den gespeicherten Zustand der Activity
    var text by remember { mutableStateOf(initialText) }
    LaunchedEffect(Unit) {
        if (initialText.isBlank() && text.isBlank()) clipboard.getText()?.text?.takeIf { it.isNotBlank() }?.let { text = it }
    }
    val large = text.length > EDITABLE_LIMIT

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.in_paste_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text(stringResource(R.string.in_title_optional)) },
                    placeholder = { Text(stringResource(R.string.in_title_auto), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                if (large) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(stringResource(R.string.in_text_preview_only), style = MaterialTheme.typography.labelMedium)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text.take(600), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        label = { Text(stringResource(R.string.in_text_label)) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(
                        pluralStringResource(R.plurals.in_char_count, text.length, NumberFormat.getIntegerInstance().format(text.length)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (large) TextButton(onClick = { text = "" }) { Text(stringResource(R.string.in_clear)) }
                    else TextButton(onClick = { clipboard.getText()?.text?.let { text = it } }) {
                        Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.in_paste_from_clipboard))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(title.trim(), text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.in_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * Webadresse eingeben. Ist initialUrl leer und liegt eine Adresse in der Zwischenablage, wird sie vorgeschlagen.
 * onConfirm bekommt die vereinheitlichte Adresse (mit https://).
 */
@Composable
fun WebArticleDialog(
    initialUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (url: String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (initialUrl.isBlank() && url.isBlank()) IncomingShare.urlFrom(clipboard.getText()?.text)?.let { url = it }
    }
    fun submit() {
        val normalized = WebArticle.normalizeUrl(url)
        if (normalized == null) invalid = true else onConfirm(normalized)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.in_web_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url, onValueChange = { url = it; invalid = false },
                    label = { Text(stringResource(R.string.in_url_label)) },
                    placeholder = { Text("https://") },
                    singleLine = true,
                    isError = invalid,
                    supportingText = if (invalid) ({ Text(stringResource(R.string.in_url_invalid)) }) else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.in_url_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { submit() }, enabled = url.isNotBlank()) { Text(stringResource(R.string.in_load)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
