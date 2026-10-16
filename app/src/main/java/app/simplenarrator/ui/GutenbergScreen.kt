package app.simplenarrator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simplenarrator.R
import app.simplenarrator.input.Gutenberg
import app.simplenarrator.input.GutenbergBook
import coil.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.text.NumberFormat

/**
 * Gemeinfreie Bücher von Project Gutenberg suchen und als EPUB in die Bibliothek holen.
 * defaultLanguages: Vorauswahl der Sprachfilter ("de", "en"; leer heißt alle Sprachen).
 * onDownloaded bekommt die heruntergeladene EPUB-Datei im Cache (z. B. für BookRepository.importFromUri(Uri.fromFile(file)));
 * die Datei wird danach gelöscht.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GutenbergScreen(
    http: OkHttpClient,
    defaultLanguages: List<String>,
    onBack: () -> Unit,
    onDownloaded: suspend (file: File, title: String) -> Unit,
) {
    val ctx = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val client = remember(http) { Gutenberg(http) }

    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    // Als Text gespeichert ("de,en"), damit rememberSaveable ihn sicher ablegen kann
    var langKey by rememberSaveable {
        mutableStateOf(defaultLanguages.map { it.lowercase() }.filter { it == "de" || it == "en" }.distinct().sorted().joinToString(","))
    }
    val langs = langKey.split(',').filter { it.isNotEmpty() }
    fun toggle(lang: String) {
        langKey = (if (lang in langs) langs - lang else langs + lang).sorted().joinToString(",")
    }

    val results = remember { mutableStateListOf<GutenbergBook>() }
    var page by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }
    var hasNext by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var moreJob by remember { mutableStateOf<Job?>(null) }

    var downloadingId by remember { mutableStateOf<Int?>(null) }
    var downloadProgress by remember { mutableFloatStateOf(0f) }
    val added = remember { mutableStateListOf<Int>() }
    val failed = remember { mutableStateMapOf<Int, String>() }

    // Neue Suche bei geänderter Eingabe oder geändertem Sprachfilter
    LaunchedEffect(submitted, langKey, reload) {
        moreJob?.cancel()
        results.clear(); page = 0; count = 0; hasNext = false; error = null; loading = true
        try {
            val p = client.search(submitted, langs, 1)
            results += p.results
            page = 1; count = p.count; hasNext = p.hasNext
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = inputErrorText(ctx, e)
        } finally {
            if (isActive) loading = false
        }
    }

    fun loadMore() {
        moreJob = scope.launch {
            loading = true; error = null
            try {
                val p = client.search(submitted, langs, page + 1)
                val known = results.map { it.id }.toHashSet()
                results += p.results.filter { it.id !in known }
                page = p.page; hasNext = p.hasNext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = inputErrorText(ctx, e)
            } finally {
                if (isActive) loading = false
            }
        }
    }

    fun download(book: GutenbergBook) {
        scope.launch {
            downloadingId = book.id; downloadProgress = 0f; failed.remove(book.id)
            val file = File(ctx.cacheDir, "gutenberg/pg${book.id}.epub")
            try {
                client.downloadEpub(book, file) { p -> downloadProgress = p }
                onDownloaded(file, book.title)
                added += book.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed[book.id] = inputErrorText(ctx, e)
            } finally {
                file.delete()
                downloadingId = null
            }
        }
    }

    fun search() {
        keyboard?.hide()
        if (query.trim() == submitted) reload++ else submitted = query.trim()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.in_gb_title), fontFamily = FontFamily.Serif) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.in_gb_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = ""; if (submitted.isNotEmpty()) submitted = "" }) {
                        Icon(Icons.Default.Clear, stringResource(R.string.in_gb_clear_search))
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = "de" in langs, onClick = { toggle("de") }, label = { Text(stringResource(R.string.in_gb_lang_de)) })
                FilterChip(selected = "en" in langs, onClick = { toggle("en") }, label = { Text(stringResource(R.string.in_gb_lang_en)) })
                FilterChip(selected = langs.isEmpty(), onClick = { langKey = "" }, label = { Text(stringResource(R.string.in_gb_lang_all)) })
            }
            Spacer(Modifier.height(4.dp))

            when {
                results.isEmpty() && loading -> CenterBox { CircularProgressIndicator() }
                results.isEmpty() && error != null -> CenterBox {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { reload++ }) { Text(stringResource(R.string.in_retry)) }
                }
                results.isEmpty() -> CenterBox {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.in_gb_empty), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.in_gb_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                else -> LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                    item(key = "header") {
                        Text(
                            when {
                                submitted.isEmpty() -> stringResource(R.string.in_gb_popular)
                                // Der Katalog von gutenberg.org nennt keine Gesamtzahl
                                count < 0 -> stringResource(R.string.in_gb_results_catalog)
                                else -> pluralStringResource(R.plurals.in_gb_results, count, NumberFormat.getIntegerInstance().format(count))
                            },
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(results, key = { it.id }) { book ->
                        GutenbergRow(
                            book = book,
                            downloading = downloadingId == book.id,
                            progress = if (downloadingId == book.id) downloadProgress else 0f,
                            added = book.id in added,
                            error = failed[book.id],
                            canDownload = downloadingId == null,
                            onDownload = { download(book) },
                        )
                        HorizontalDivider(Modifier.padding(start = 78.dp))
                    }
                    item(key = "footer") {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            when {
                                loading -> CircularProgressIndicator(Modifier.size(28.dp))
                                error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                                    OutlinedButton(onClick = { loadMore() }) { Text(stringResource(R.string.in_retry)) }
                                }
                                hasNext -> OutlinedButton(onClick = { loadMore() }) { Text(stringResource(R.string.in_gb_load_more)) }
                                else -> Text(stringResource(R.string.in_gb_source), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
private fun GutenbergRow(
    book: GutenbergBook,
    downloading: Boolean,
    progress: Float,
    added: Boolean,
    error: String?,
    canDownload: Boolean,
    onDownload: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp, 70.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            if (book.coverUrl != null) {
                AsyncImage(model = book.coverUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Serif, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val authors = book.authors.joinToString(", ") { a -> a.lifeSpan?.let { "${a.displayName} ($it)" } ?: a.displayName }
            if (authors.isNotBlank()) {
                Text(authors, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val meta = listOfNotNull(
                // Treffer aus dem Katalog von gutenberg.org haben keine Downloadzahl
                if (book.downloadCount > 0) pluralStringResource(R.plurals.in_gb_downloads, book.downloadCount, NumberFormat.getIntegerInstance().format(book.downloadCount)) else null,
                book.languages.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.uppercase() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                downloading -> LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                book.epubUrl == null -> Text(stringResource(R.string.in_gb_no_epub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when {
                downloading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                added -> Icon(Icons.Default.CheckCircle, stringResource(R.string.in_gb_added), tint = MaterialTheme.colorScheme.primary)
                else -> IconButton(onClick = onDownload, enabled = canDownload && book.epubUrl != null) {
                    Icon(Icons.Default.Download, stringResource(R.string.in_gb_download))
                }
            }
        }
    }
}
