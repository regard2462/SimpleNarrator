package app.simplenarrator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import app.simplenarrator.data.AppSettings
import app.simplenarrator.data.Annotation
import app.simplenarrator.data.Paragraph
import app.simplenarrator.data.ReaderFont
import app.simplenarrator.data.ReaderTheme
import app.simplenarrator.playback.ReadAloudEvents
import app.simplenarrator.playback.SentenceProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Farben der Leseansicht. */
private data class ReaderColors(val bg: Color, val text: Color, val muted: Color, val sentence: Color, val word: Color, val mark: Color)

@Composable
private fun readerColors(theme: ReaderTheme): ReaderColors = when (theme) {
    ReaderTheme.APP -> MaterialTheme.colorScheme.let {
        ReaderColors(it.background, it.onBackground, it.onSurfaceVariant, it.tertiaryContainer, it.tertiary.copy(alpha = 0.45f), it.primary)
    }
    ReaderTheme.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1C1B1F), Color(0xFF6B6B70), Color(0xFFFFE8B0), Color(0xFFF2BE4C), Color(0xFF35507A))
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF5B4636), Color(0xFF8B7560), Color(0xFFE8D3A6), Color(0xFFD5A95E), Color(0xFF8A5A2B))
    ReaderTheme.DARK -> ReaderColors(Color(0xFF1E1F22), Color(0xFFDADADA), Color(0xFF9A9AA0), Color(0xFF4A3F1F), Color(0xFF85692A), Color(0xFFAFC6F5))
    ReaderTheme.BLACK -> ReaderColors(Color(0xFF000000), Color(0xFFC9C9C9), Color(0xFF8A8A8A), Color(0xFF3A3214), Color(0xFF6E5B1F), Color(0xFFAFC6F5))
}

private fun fontOf(f: ReaderFont) = when (f) {
    ReaderFont.SERIF -> FontFamily.Serif
    ReaderFont.SANS -> FontFamily.SansSerif
    ReaderFont.MONO -> FontFamily.Monospace
}

/** Satz-Position für Sprungziele (Suche, Lesezeichen). */
data class SentenceRef(val chapter: Int, val sentence: Int)

/**
 * Lesen + Vorlesen: Tipp auf einen Satz startet das Vorlesen genau dort, langes Drücken öffnet das Satzmenü
 * (Lesezeichen, Notiz, Kopieren, Teilen). Der gesprochene Satz und auf Wunsch das gesprochene Wort sind
 * hervorgehoben, die Ansicht folgt automatisch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    c: AppContainer, bookId: String, target: SentenceRef?,
    onBack: () -> Unit, onExport: () -> Unit, onSearch: () -> Unit, onOpenSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val books by c.repo.books.collectAsState()
    val progress by c.repo.progress.collectAsState()
    val pb by c.playback.state.collectAsState()
    val settings by c.settings.flow.collectAsState(initial = AppSettings())
    val installed by c.models.installed.collectAsState()
    val book = books.firstOrNull { it.id == bookId }
    if (book == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(stringResource(R.string.book_not_found)) }
        return
    }
    LaunchedEffect(bookId) { c.annotations.load(bookId) }
    val annotations by remember(bookId) { c.annotations.flow(bookId) }.collectAsState(initial = emptyList())

    var chapterIdx by remember(bookId) { mutableIntStateOf(target?.chapter ?: progress[bookId]?.chapter ?: 0) }
    val cue = pb.cue?.takeIf { it.bookId == bookId }
    LaunchedEffect(cue?.chapter) { cue?.let { chapterIdx = it.chapter } }
    val chapter = book.chapters[chapterIdx.coerceIn(0, book.chapters.lastIndex)]
    val current = cue?.takeIf { it.chapter == chapterIdx }?.sentence ?: -1
    val starts = remember(chapter) {
        IntArray(chapter.paragraphs.size).also { arr ->
            var acc = 0
            chapter.paragraphs.forEachIndexed { i, p -> arr[i] = acc; acc += p.sentences.size }
        }
    }
    // EPUB-Kapitel beginnen oft mit ihrer Überschrift; dann nicht zusätzlich den Titel groß darüber setzen.
    val titleInText = remember(chapter) {
        chapter.paragraphs.firstOrNull()?.takeIf { it.isHeading }?.sentences?.joinToString(" ") { it.text }
            ?.trim()?.equals(chapter.title.trim(), ignoreCase = true) == true
    }
    val marks = remember(annotations, chapterIdx) { annotations.filter { it.chapter == chapterIdx }.associateBy { it.sentence } }
    val listState = rememberLazyListState()
    var flash by remember { mutableIntStateOf(-1) }
    var showChapters by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showLook by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sentenceMenu by remember { mutableStateOf<Int?>(null) }
    var noteFor by remember { mutableStateOf<Int?>(null) }
    var noVoice by remember { mutableStateOf(false) }
    val colors = readerColors(settings.readerTheme)

    LaunchedEffect(Unit) { ReadAloudEvents.noVoice.collect { noVoice = true } }

    fun readFrom(ch: Int, s: Int) {
        if (installed.isEmpty()) noVoice = true else c.playback.readFrom(bookId, ch, s)
    }

    fun scrollTo(sentence: Int, animate: Boolean) = scope.launch {
        val pi = starts.indexOfLast { it <= sentence }.coerceAtLeast(0)
        if (animate) listState.animateScrollToItem(pi + 1, scrollOffset = -220) else listState.scrollToItem(pi + 1, scrollOffset = -220)
    }

    // Sprungziel aus Suche oder Lesezeichen: hinscrollen und kurz aufleuchten lassen
    LaunchedEffect(target, chapterIdx) {
        if (target != null && target.chapter == chapterIdx) {
            scrollTo(target.sentence, animate = false)
            flash = target.sentence
            delay(2200)
            flash = -1
        }
    }
    // Die Ansicht folgt dem Vorlesen (Absatz des aktuellen Satzes nach oben scrollen).
    LaunchedEffect(current, chapterIdx) {
        if (current >= 0) scrollTo(current, animate = true)
        else if (target == null) {
            val p = progress[bookId]
            if (p != null && p.chapter == chapterIdx && p.sentence > 0) scrollTo(p.sentence, animate = false)
        }
    }

    Scaffold(
        containerColor = colors.bg,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg, titleContentColor = colors.text, actionIconContentColor = colors.text, navigationIconContentColor = colors.text),
                title = { Text(book.title, fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = {
                    IconButton(onClick = {
                        val p = progress[bookId]
                        val (ch, s) = if (cue != null) chapterIdx to current.coerceAtLeast(0)
                        else if (p != null && p.chapter == chapterIdx) chapterIdx to p.sentence else chapterIdx to 0
                        readFrom(ch, s)
                    }) { Icon(Icons.Default.PlayArrow, stringResource(R.string.read_aloud)) }
                    IconButton(onClick = { showChapters = true }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.chapters)) }
                    IconButton(onClick = { showBookmarks = true }) { Icon(Icons.Default.Bookmark, stringResource(R.string.bookmarks)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, stringResource(R.string.more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.search_in_book)) }, leadingIcon = { Icon(Icons.Default.Search, null) }, onClick = { menu = false; onSearch() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.reader_look)) }, leadingIcon = { Icon(Icons.Default.FormatSize, null) }, onClick = { menu = false; showLook = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.export_as_audiobook)) }, leadingIcon = { Icon(Icons.Default.Download, null) }, onClick = { menu = false; onExport() })
                        }
                    }
                },
            )
        },
    ) { padding ->
        val body = TextStyle(
            fontFamily = fontOf(settings.readerFont), fontSize = settings.readerFontSize.sp,
            lineHeight = (settings.readerFontSize * settings.readerLineHeight).sp,
        )
        val heading = body.copy(fontSize = (settings.readerFontSize * 1.3f).sp, lineHeight = (settings.readerFontSize * 1.75f).sp, fontWeight = FontWeight.SemiBold)
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = settings.readerMargin.dp, vertical = 16.dp),
        ) {
            item(key = "title") {
                Column(Modifier.padding(bottom = 18.dp)) {
                    Text(stringResource(R.string.chapter_of, chapterIdx + 1, book.chapters.size), style = MaterialTheme.typography.labelMedium, color = colors.muted)
                    if (!titleInText) Text(chapter.title, style = heading.copy(fontSize = (settings.readerFontSize * 1.4f).sp), color = colors.text)
                }
            }
            itemsIndexed(chapter.paragraphs, key = { i, _ -> i }) { i, p ->
                val start = starts[i]
                val inPara = current >= start && current < start + p.sentences.size
                ParagraphText(
                    p = p, startIndex = start, current = current, flash = flash,
                    progress = if (inPara && settings.wordHighlight && pb.isPlaying) c.playback.progress.collectAsState().value else null,
                    marks = marks, colors = colors, style = if (p.isHeading) heading else body,
                    onTap = { sentence -> readFrom(chapterIdx, sentence) },
                    onLongPress = { sentence -> sentenceMenu = sentence },
                )
            }
            item(key = "end") { Text(stringResource(R.string.end_of_chapter), Modifier.padding(vertical = 40.dp), style = MaterialTheme.typography.labelMedium, color = colors.muted) }
        }
    }

    if (showChapters) {
        ModalBottomSheet(onDismissRequest = { showChapters = false }) {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                itemsIndexed(book.chapters) { i, ch ->
                    ListItem(
                        headlineContent = { Text(ch.title, fontWeight = if (i == chapterIdx) FontWeight.Bold else FontWeight.Normal) },
                        supportingContent = { Text(pluralStringResource(R.plurals.sentence_count, ch.sentenceCount, ch.sentenceCount)) },
                        modifier = Modifier.clickable { chapterIdx = i; showChapters = false },
                    )
                }
            }
        }
    }

    if (showBookmarks) {
        BookmarksSheet(
            list = annotations,
            chapterTitle = { book.chapters.getOrNull(it)?.title.orEmpty() },
            onDismiss = { showBookmarks = false },
            onOpen = { a -> showBookmarks = false; chapterIdx = a.chapter; scope.launch { delay(50); scrollTo(a.sentence, false); flash = a.sentence; delay(2200); flash = -1 } },
            onDelete = { a -> scope.launch { c.annotations.delete(bookId, a.id) } },
            onShare = {
                val text = c.annotations.asText(book, annotations) { ch -> ctx.getString(R.string.chapter_of, ch + 1, book.chapters.size) }
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            },
        )
    }

    sentenceMenu?.let { s ->
        val text = chapter.sentenceAt(s)?.text.orEmpty()
        val mark = marks[s]
        ModalBottomSheet(onDismissRequest = { sentenceMenu = null }) {
            Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                MenuRow(Icons.Default.PlayArrow, stringResource(R.string.read_from_here)) { sentenceMenu = null; readFrom(chapterIdx, s) }
                MenuRow(if (mark != null) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, stringResource(if (mark != null) R.string.bookmark_remove else R.string.bookmark_add)) {
                    sentenceMenu = null; scope.launch { c.annotations.toggle(book, chapterIdx, s) }
                }
                MenuRow(Icons.Default.EditNote, stringResource(if (mark?.note?.isNotBlank() == true) R.string.note_edit else R.string.note_add)) { sentenceMenu = null; noteFor = s }
                MenuRow(Icons.Default.ContentCopy, stringResource(R.string.copy_sentence)) {
                    sentenceMenu = null
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(book.title, text))
                }
                MenuRow(Icons.Default.Share, stringResource(R.string.share_sentence)) {
                    sentenceMenu = null
                    val quote = "„$text“\n${book.title}" + if (book.author.isNotBlank()) ", ${book.author}" else ""
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, quote), null))
                }
            }
        }
    }

    noteFor?.let { s ->
        var note by remember(s) { mutableStateOf(marks[s]?.note.orEmpty()) }
        AlertDialog(
            onDismissRequest = { noteFor = null },
            title = { Text(stringResource(R.string.note_title)) },
            text = { OutlinedTextField(value = note, onValueChange = { note = it }, modifier = Modifier.fillMaxWidth().height(160.dp)) },
            confirmButton = { TextButton(onClick = { noteFor = null; scope.launch { c.annotations.setNote(book, chapterIdx, s, note) } }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { noteFor = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }

    if (showLook) LookSheet(settings, onDismiss = { showLook = false }) { block -> scope.launch { c.settings.update(block) } }

    if (noVoice) {
        AlertDialog(
            onDismissRequest = { noVoice = false },
            title = { Text(stringResource(R.string.no_voice_title)) },
            text = { Text(stringResource(R.string.no_voice_installed)) },
            confirmButton = { TextButton(onClick = { noVoice = false; onOpenSettings() }) { Text(stringResource(R.string.open_settings)) } },
            dismissButton = { TextButton(onClick = { noVoice = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    list: List<Annotation>, chapterTitle: (Int) -> String,
    onDismiss: () -> Unit, onOpen: (Annotation) -> Unit, onDelete: (Annotation) -> Unit, onShare: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.bookmarks), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) TextButton(onClick = onShare) { Icon(Icons.Default.Share, null); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.share_all)) }
        }
        if (list.isEmpty()) {
            Text(stringResource(R.string.bookmarks_empty), Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
            itemsIndexed(list, key = { _, a -> a.id }) { _, a ->
                ListItem(
                    overlineContent = { Text(chapterTitle(a.chapter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    headlineContent = { Text(a.excerpt, maxLines = 3, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Serif) },
                    supportingContent = if (a.note.isNotBlank()) ({ Text(a.note, color = MaterialTheme.colorScheme.primary) }) else null,
                    trailingContent = { IconButton(onClick = { onDelete(a) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } },
                    colors = ListItemDefaults.colors(),
                    modifier = Modifier.clickable { onOpen(a) },
                )
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookSheet(s: AppSettings, onDismiss: () -> Unit, save: ((AppSettings) -> AppSettings) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.reader_look), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.reader_font), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ReaderFont.SERIF to R.string.font_serif, ReaderFont.SANS to R.string.font_sans, ReaderFont.MONO to R.string.font_mono).forEach { (f, label) ->
                    FilterChip(selected = s.readerFont == f, onClick = { save { it.copy(readerFont = f) } }, label = { Text(stringResource(label), fontFamily = fontOf(f)) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reader_size, s.readerFontSize))
            Slider(value = s.readerFontSize.toFloat(), onValueChange = { v -> save { it.copy(readerFontSize = v.roundToInt()) } }, valueRange = 14f..30f, steps = 15)
            Text(stringResource(R.string.reader_line_height, "%.2f".format(s.readerLineHeight)))
            Slider(value = s.readerLineHeight, onValueChange = { v -> save { it.copy(readerLineHeight = (v * 20).roundToInt() / 20f) } }, valueRange = 1.2f..2.2f)
            Text(stringResource(R.string.reader_margin, s.readerMargin))
            Slider(value = s.readerMargin.toFloat(), onValueChange = { v -> save { it.copy(readerMargin = (v / 2).roundToInt() * 2) } }, valueRange = 8f..48f)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.reader_colors), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                listOf(
                    ReaderTheme.APP to R.string.reader_theme_app, ReaderTheme.LIGHT to R.string.reader_theme_light,
                    ReaderTheme.SEPIA to R.string.reader_theme_sepia, ReaderTheme.DARK to R.string.reader_theme_dark,
                    ReaderTheme.BLACK to R.string.reader_theme_black,
                ).forEach { (t, label) ->
                    val col = readerColors(t)
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { save { it.copy(readerTheme = t) } }) {
                        Box(
                            Modifier.size(40.dp).background(col.bg, CircleShape)
                                .then(if (s.readerTheme == t) Modifier.background(col.sentence.copy(alpha = 0.6f), CircleShape) else Modifier),
                            contentAlignment = Alignment.Center,
                        ) { Text("Aa", color = col.text, fontFamily = FontFamily.Serif) }
                        Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.word_highlight_title))
                    Text(stringResource(R.string.word_highlight_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = s.wordHighlight, onCheckedChange = { v -> save { it.copy(wordHighlight = v) } })
            }
        }
    }
}

/** Wortgrenzen eines Satzes und ihr geschätzter Anteil an der Sprechdauer (Länge plus Pausen an Satzzeichen). */
internal fun wordAt(text: String, fraction: Float): IntRange? {
    val words = Regex("""\S+""").findAll(text).toList()
    if (words.isEmpty()) return null
    val weights = words.map { w ->
        val v = w.value
        val pause = when (v.lastOrNull()) { ',', ';', ':' -> 3; '.', '!', '?', '…' -> 5; else -> 0 }
        v.count { it.isLetterOrDigit() }.coerceAtLeast(1) + 1 + pause
    }
    val total = weights.sum().toFloat()
    var acc = 0f
    words.forEachIndexed { i, w ->
        acc += weights[i]
        if (acc / total >= fraction) return w.range
    }
    return words.last().range
}

@Composable
private fun ParagraphText(
    p: Paragraph, startIndex: Int, current: Int, flash: Int, progress: SentenceProgress?,
    marks: Map<Int, Annotation>, colors: ReaderColors, style: TextStyle,
    onTap: (Int) -> Unit, onLongPress: (Int) -> Unit,
) {
    val wordRange = if (progress != null && current >= startIndex) p.sentences.getOrNull(current - startIndex)?.let { wordAt(it.text, progress.fraction) } else null
    val text = remember(p, current, flash, wordRange, marks, colors) {
        buildAnnotatedString {
            p.sentences.forEachIndexed { i, s ->
                val idx = startIndex + i
                val mark = marks[idx]
                val base = when {
                    idx == current -> SpanStyle(background = colors.sentence)
                    idx == flash -> SpanStyle(background = colors.sentence.copy(alpha = 0.7f))
                    else -> SpanStyle()
                }
                val decorated = if (mark != null) base.merge(SpanStyle(textDecoration = TextDecoration.Underline, color = colors.mark)) else base
                withStyle(decorated) {
                    if (idx == current && wordRange != null) {
                        append(s.text.substring(0, wordRange.first))
                        withStyle(SpanStyle(background = colors.word)) { append(s.text.substring(wordRange.first, wordRange.last + 1)) }
                        append(s.text.substring(wordRange.last + 1))
                    } else append(s.text)
                }
                if (i < p.sentences.lastIndex) append(" ")
            }
        }
    }
    // Zeichen-Offset, an dem jeder Satz beginnt → Tipp-Position → Satz
    val offsets = remember(p) {
        IntArray(p.sentences.size).also { arr ->
            var acc = 0
            p.sentences.forEachIndexed { i, s -> arr[i] = acc; acc += s.text.length + 1 }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    fun sentenceAt(pos: androidx.compose.ui.geometry.Offset): Int? {
        val l = layout ?: return null
        val off = l.getOffsetForPosition(pos)
        return startIndex + offsets.indexOfLast { it <= off }.coerceIn(0, p.sentences.lastIndex)
    }
    Text(
        text = text,
        style = style,
        color = colors.text,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 680.dp)
            .padding(bottom = 14.dp)
            .pointerInput(p, startIndex) {
                detectTapGestures(
                    onTap = { pos -> sentenceAt(pos)?.let(tap) },
                    onLongPress = { pos -> sentenceAt(pos)?.let(longPress) },
                )
            },
    )
}
