package app.simplenarrator.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import app.simplenarrator.data.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Ein Treffer der Volltextsuche. */
data class SearchHit(val chapter: Int, val sentence: Int, val text: String, val start: Int, val length: Int)

/** Volltextsuche über alle Sätze eines Buchs, ohne Groß- und Kleinschreibung zu unterscheiden. */
fun searchBook(book: Book, query: String, limit: Int = 500): List<SearchHit> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    val hits = ArrayList<SearchHit>()
    book.chapters.forEachIndexed { ci, ch ->
        var si = 0
        for (p in ch.paragraphs) for (s in p.sentences) {
            val at = s.text.indexOf(q, ignoreCase = true)
            if (at >= 0) {
                hits += SearchHit(ci, si, s.text, at, q.length)
                if (hits.size >= limit) return hits
            }
            si++
        }
    }
    return hits
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(c: AppContainer, bookId: String, onBack: () -> Unit, onOpen: (chapter: Int, sentence: Int) -> Unit) {
    val books by c.repo.books.collectAsState()
    val book = books.firstOrNull { it.id == bookId } ?: return
    var query by rememberSaveable { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        delay(250)
        hits = withContext(Dispatchers.Default) { searchBook(book, query) }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text(stringResource(R.string.search_hint)) },
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                )
            },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (query.trim().length >= 2) {
                Text(
                    if (hits.isEmpty()) stringResource(R.string.search_none) else pluralStringResource(R.plurals.search_results, hits.size, hits.size),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(hits) { h ->
                    ListItem(
                        overlineContent = { Text(book.chapters[h.chapter].title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        headlineContent = { Text(snippet(h), maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif) },
                        modifier = Modifier.clickable { onOpen(h.chapter, h.sentence) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Ausschnitt rund um den Treffer, der Treffer fett. */
private fun snippet(h: SearchHit): AnnotatedString {
    val from = (h.start - 60).coerceAtLeast(0)
    val to = (h.start + h.length + 120).coerceAtMost(h.text.length)
    return buildAnnotatedString {
        if (from > 0) append("… ")
        append(h.text.substring(from, h.start))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(h.text.substring(h.start, h.start + h.length)) }
        append(h.text.substring(h.start + h.length, to))
        if (to < h.text.length) append(" …")
    }
}
