package app.simplenarrator.audiobooks.ui.book

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Bookmark
import app.simplenarrator.audiobooks.data.Chapter
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.data.Status
import app.simplenarrator.audiobooks.ui.components.Cover
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.LocalBottomInset
import app.simplenarrator.audiobooks.ui.components.ThinProgress
import app.simplenarrator.audiobooks.ui.components.bookColor
import app.simplenarrator.audiobooks.ui.components.bookmarksLabel
import app.simplenarrator.audiobooks.ui.components.chapterTitle
import app.simplenarrator.audiobooks.ui.components.chaptersCount
import app.simplenarrator.audiobooks.ui.components.durationText
import app.simplenarrator.audiobooks.ui.components.percentText
import app.simplenarrator.audiobooks.ui.components.withLightness
import app.simplenarrator.audiobooks.util.Format
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookScreen(id: String, onBack: () -> Unit, onOpenPlayer: () -> Unit) {
    val app = LocalApp.current
    val entries by app.library.entries.collectAsStateWithLifecycle()
    val snap by app.playback.snapshot.collectAsStateWithLifecycle()
    val entry = entries.firstOrNull { it.id == id }
    if (entry == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var edit by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var files by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf(false) }
    var searchCover by remember { mutableStateOf(false) }
    var editBookmark by remember { mutableStateOf<Bookmark?>(null) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
            if (bytes != null) app.library.setCustomCoverBytes(id, bytes)
        }
    }

    val isCurrent = snap.bookId == id
    val position = if (isCurrent) entry.book.globalOf(snap.trackIndex, snap.trackPositionMs) else entry.positionMs
    val chapterIdx = entry.book.chapterAt(position)
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = bookColor(entry)
    val headerColor = if (dark) base.withLightness(0.24f, 0.5f) else base.withLightness(0.85f, 0.5f)
    val listState = rememberLazyListState()
    val showTitle by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 600 } }
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    fun play(from: Long? = null) {
        app.playback.play(id, from)
        onOpenPlayer()
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    if (showTitle) {
                        Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ab_back)) }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.ab_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.ab_edit_book)) }, onClick = { menu = false; edit = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ab_change_cover)) }, onClick = {
                                menu = false
                                pickCover.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ab_search_cover)) }, onClick = { menu = false; searchCover = true })
                            if (entry.state.customCover != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ab_reset_cover)) }, onClick = {
                                    menu = false
                                    app.library.clearCustomCover(id)
                                })
                            }
                            if (entry.webCover != null) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ab_remove_web_cover)) }, onClick = {
                                    menu = false
                                    app.library.dropWebCover(id)
                                })
                            }
                            if (entry.status == Status.FINISHED) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ab_mark_unfinished)) }, onClick = {
                                    menu = false
                                    app.library.markFinished(id, false)
                                })
                            } else {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ab_mark_finished)) }, onClick = {
                                    menu = false
                                    app.library.markFinished(id, true)
                                })
                            }
                            if (entry.status != Status.NEW) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ab_reset)) }, onClick = { menu = false; reset = true })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.ab_files)) }, onClick = { menu = false; files = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ab_remove_book)) }, onClick = { menu = false; remove = true })
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
    ) { _ ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item("header") { Header(entry, headerColor) }
            item("actions") {
                Actions(
                    entry = entry,
                    position = position,
                    playing = isCurrent && snap.playWhenReady,
                    onPlay = { play() },
                    onPause = { app.playback.pause() },
                )
            }
            item("tabs") {
                SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(chaptersCount(entry.book.chapters.size)) })
                    Tab(
                        selected = tab == 1,
                        onClick = { tab = 1 },
                        text = { Text(bookmarksLabel(entry.state.bookmarks.size)) },
                    )
                }
            }
            if (tab == 0) {
                itemsIndexed(entry.book.chapters, key = { i, _ -> "c$i" }) { i, ch ->
                    val state = when {
                        entry.status == Status.FINISHED -> ChapterState.DONE
                        i < chapterIdx -> ChapterState.DONE
                        i == chapterIdx && (position > 0 || isCurrent) -> ChapterState.CURRENT
                        else -> ChapterState.UPCOMING
                    }
                    val progress = if (ch.durationMs > 0) ((position - ch.startMs).toFloat() / ch.durationMs) else 0f
                    ChapterRow(i, ch, state, progress) { play(ch.startMs) }
                }
            } else {
                item("addBookmark") {
                    OutlinedButton(
                        onClick = { app.library.addBookmark(id, position) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    ) {
                        Icon(Icons.Rounded.BookmarkAdd, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ab_bookmark_here, Format.clock(position)))
                    }
                }
                if (entry.state.bookmarks.isEmpty()) {
                    item("noBookmarks") {
                        Text(
                            stringResource(R.string.ab_no_bookmarks),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(32.dp),
                        )
                    }
                }
                itemsIndexed(entry.state.bookmarks, key = { _, b -> "b${b.id}" }) { _, b ->
                    BookmarkRow(
                        entry, b,
                        onClick = { play(b.positionMs) },
                        onEdit = { editBookmark = b },
                        onDelete = { app.library.deleteBookmark(id, b.id) },
                    )
                }
            }
        }
    }

    if (edit) EditBookDialog(entry) { edit = false }
    if (reset) ConfirmResetDialog(entry) { reset = false }
    if (files) FilesDialog(entry) { files = false }
    if (remove) RemoveBookDialog(entry, onDismiss = { remove = false })
    if (searchCover) CoverSearchDialog(entry) { searchCover = false }
    editBookmark?.let { b -> BookmarkNoteDialog(b.note, onDismiss = { editBookmark = null }) { app.library.editBookmark(id, b.id, it) } }
}

@Composable
private fun Header(entry: Entry, headerColor: Color) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(headerColor, MaterialTheme.colorScheme.surface))),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(top = top + 64.dp, start = 24.dp, end = 24.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Cover(entry, Modifier.size(220.dp), RoundedCornerShape(20.dp), elevation = 18.dp)
            Spacer(Modifier.height(22.dp))
            Text(entry.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (!entry.author.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(entry.author, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (!entry.narrator.isNullOrBlank()) {
                Text(
                    stringResource(R.string.ab_read_by, entry.narrator),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            val meta = listOfNotNull(
                durationText(entry.durationMs),
                chaptersCount(entry.book.chapters.size),
                entry.book.year,
            ).joinToString("  ·  ")
            Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Actions(entry: Entry, position: Long, playing: Boolean, onPlay: () -> Unit, onPause: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        val progress = if (entry.durationMs > 0) position.toFloat() / entry.durationMs else 0f
        when (entry.status) {
            Status.STARTED -> {
                ThinProgress(progress, Modifier.fillMaxWidth(), height = 6.dp)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(percentText(progress), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        stringResource(R.string.ab_remaining, durationText(entry.durationMs - position)),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Status.FINISHED -> Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                val date = entry.state.finishedAt.takeIf { it > 0 }?.let { Format.date(context, it) }
                Text(
                    if (date != null) stringResource(R.string.ab_finished_on, date) else stringResource(R.string.ab_status_finished),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Status.NEW -> Unit
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = if (playing) onPause else onPlay,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(18.dp),
        ) {
            val (icon, label) = when {
                playing -> Icons.Rounded.Pause to R.string.ab_pause
                entry.status == Status.STARTED -> Icons.Rounded.PlayArrow to R.string.ab_resume
                entry.status == Status.FINISHED -> Icons.Rounded.Replay to R.string.ab_listen_again
                else -> Icons.Rounded.PlayArrow to R.string.ab_play
            }
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.titleMedium)
        }
    }
}

enum class ChapterState { DONE, CURRENT, UPCOMING }

@Composable
fun ChapterRow(index: Int, chapter: Chapter, state: ChapterState, progress: Float, onClick: () -> Unit) {
    val current = state == ChapterState.CURRENT
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(if (current) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else Color.Transparent)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            when (state) {
                ChapterState.DONE -> Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                ChapterState.CURRENT -> Icon(Icons.Rounded.GraphicEq, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                ChapterState.UPCOMING -> Text(
                    "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                chapterTitle(chapter, index),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                color = if (state == ChapterState.DONE) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (current) {
                Spacer(Modifier.height(6.dp))
                ThinProgress(progress, Modifier.fillMaxWidth(), height = 3.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(Format.clock(chapter.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun BookmarkRow(entry: Entry, b: Bookmark, onClick: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val chIdx = entry.book.chapterAt(b.positionMs)
    val chapter = entry.book.chapters.getOrNull(chIdx)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Bookmark, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                b.note.ifBlank { chapterTitle(chapter, chIdx) },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = buildList {
                if (b.note.isNotBlank()) add(chapterTitle(chapter, chIdx))
                add(Format.clock(b.positionMs))
                add(Format.date(context, b.createdAt))
            }.joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.ab_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ab_edit_note)) },
                    leadingIcon = { Icon(Icons.Rounded.Edit, null) },
                    onClick = { menu = false; onEdit() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ab_delete)) },
                    leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}

@Composable
fun BookmarkNoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ab_bookmark_note)) },
        text = {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text(stringResource(R.string.ab_bookmark_note_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text)
                onDismiss()
            }) { Text(stringResource(R.string.ab_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ab_cancel)) } },
    )
}
