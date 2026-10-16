package app.simplenarrator.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import app.simplenarrator.data.Book
import kotlinx.coroutines.launch

private val MIMES = arrayOf("application/epub+zip", "application/pdf", "text/plain", "text/markdown", "application/octet-stream")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    c: AppContainer, importing: Boolean,
    onImport: (Uri) -> Unit, onOpen: (String) -> Unit, onExport: (String) -> Unit, onSettings: () -> Unit,
    onPaste: () -> Unit = {}, onWeb: () -> Unit = {}, onGutenberg: () -> Unit = {},
) {
    var addMenu by remember { mutableStateOf(false) }
    val books by c.repo.books.collectAsState()
    val progress by c.repo.progress.collectAsState()
    val installed by c.models.installed.collectAsState()
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(onImport) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), fontFamily = FontFamily.Serif) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, stringResource(R.string.settings)) } },
            )
        },
        floatingActionButton = {
            Box {
                ExtendedFloatingActionButton(
                    onClick = { if (!importing) addMenu = true },
                    icon = { if (importing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Add, null) },
                    text = { Text(stringResource(if (importing) R.string.importing else R.string.add_book)) },
                )
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_add_file)) }, leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                        onClick = { addMenu = false; picker.launch(MIMES) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_add_paste)) }, leadingIcon = { Icon(Icons.Default.ContentPaste, null) },
                        onClick = { addMenu = false; onPaste() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_add_web)) }, leadingIcon = { Icon(Icons.Default.Language, null) },
                        onClick = { addMenu = false; onWeb() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.in_add_gutenberg)) }, leadingIcon = { Icon(Icons.Default.LocalLibrary, null) },
                        onClick = { addMenu = false; onGutenberg() })
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OcrBanner()
            if (installed.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp).clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer).clickable(onClick = onSettings).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Download, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.no_voice_banner), color = MaterialTheme.colorScheme.onTertiaryContainer)
                }
            }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.library_empty), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.library_empty_hint),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                    items(books, key = { it.id }) { book ->
                        val p = progress[book.id]
                        val done = p?.let { pr -> book.chapters.take(pr.chapter).sumOf { it.sentenceCount } + pr.sentence } ?: 0
                        BookRow(
                            book = book, cover = c.repo.coverFile(book)?.absolutePath,
                            fraction = if (book.totalSentences == 0) 0f else done.toFloat() / book.totalSentences,
                            chapterLabel = p?.let { stringResource(R.string.chapter_of, it.chapter + 1, book.chapters.size) }
                                ?: pluralStringResource(R.plurals.chapter_count, book.chapters.size, book.chapters.size),
                            onOpen = { onOpen(book.id) }, onExport = { onExport(book.id) },
                            onDelete = { scope.launch { c.repo.delete(book.id) } },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, cover: String?, fraction: Float, chapterLabel: String, onOpen: () -> Unit, onExport: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp, 80.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            if (cover != null) AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(book.sourceType.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.weight(1f))
                Text(chapterLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.export_as_audiobook)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text(stringResource(R.string.remove_from_library)) }, leadingIcon = { Icon(Icons.Default.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
