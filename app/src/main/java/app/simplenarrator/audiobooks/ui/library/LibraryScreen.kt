package app.simplenarrator.audiobooks.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.audiobooks.data.Filter
import app.simplenarrator.audiobooks.data.matches
import app.simplenarrator.audiobooks.data.matchesQuery
import app.simplenarrator.audiobooks.data.sortEntries
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.data.SortOrder
import app.simplenarrator.audiobooks.data.Status
import app.simplenarrator.audiobooks.playback.PlayerSnapshot
import app.simplenarrator.audiobooks.scan.NaturalOrder
import app.simplenarrator.audiobooks.ui.book.BookActionSheet
import app.simplenarrator.audiobooks.ui.components.Cover
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.LocalBottomInset
import app.simplenarrator.audiobooks.ui.components.Segment
import app.simplenarrator.audiobooks.ui.components.SegmentedPicker
import app.simplenarrator.audiobooks.ui.components.ThinProgress
import app.simplenarrator.audiobooks.ui.components.bookColor
import app.simplenarrator.audiobooks.ui.components.chapterTitle
import app.simplenarrator.audiobooks.ui.components.durationText
import app.simplenarrator.audiobooks.ui.components.percentText
import app.simplenarrator.audiobooks.ui.components.rememberFolderPicker
import app.simplenarrator.audiobooks.ui.settings.rememberImportExport
import app.simplenarrator.audiobooks.ui.components.rootLabel
import app.simplenarrator.audiobooks.ui.components.statusLine
import app.simplenarrator.audiobooks.ui.components.withLightness

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenBook: (String) -> Unit, onOpenSettings: () -> Unit) {
    val app = LocalApp.current
    val entries by app.library.entries.collectAsStateWithLifecycle()
    val ready by app.library.ready.collectAsStateWithLifecycle()
    val scan by app.library.scan.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val unreachable by app.library.unreachableRoots.collectAsStateWithLifecycle()
    val snap by app.playback.snapshot.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    var userRefresh by remember { mutableStateOf(false) }
    val addFolder = rememberFolderPicker()
    val importExport = rememberImportExport()
    val bottom = LocalBottomInset.current

    LaunchedEffect(scan) { if (scan == null) userRefresh = false }

    val counts = remember(entries, settings.hideFinished) {
        Filter.entries.associateWith { f -> entries.count { it.matches(f, false) } }
    }
    val visible = remember(entries, query, filter, settings.sort, settings.hideFinished) {
        sortEntries(entries.filter { it.matches(filter, settings.hideFinished) && it.matchesQuery(query) }, settings.sort)
    }
    val continueList = remember(entries) {
        entries.filter { it.status == Status.STARTED }.sortedByDescending { it.state.lastPlayedAt }.take(12)
    }

    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            if (searching) {
                SearchBar(query, { query = it }) {
                    searching = false
                    query = ""
                }
            } else {
                TopAppBar(
                    title = { Text(stringResource(R.string.ab_library)) },
                    actions = {
                        if (settings.roots.isNotEmpty()) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Rounded.Search, stringResource(R.string.ab_search))
                            }
                            SortMenu(settings.sort, settings.hideFinished, settings.grid)
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, stringResource(R.string.ab_settings)) }
                    },
                    scrollBehavior = scrollBehavior,
                    colors = TopAppBarDefaults.topAppBarColors(scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
                )
            }
        },
    ) { padding ->
        when {
            !ready -> Box(Modifier.fillMaxSize())
            settings.roots.isEmpty() -> Welcome(Modifier.padding(padding), addFolder, importExport.import)
            else -> PullToRefreshBox(
                isRefreshing = userRefresh && scan != null,
                onRefresh = {
                    userRefresh = true
                    app.library.rescan()
                },
                modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            ) {
                LazyVerticalGrid(
                    columns = if (settings.grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = bottom + 24.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(if (settings.grid) 20.dp else 4.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    scan?.let { s -> full("scan") { ScanBanner(s.done, s.total, s.rootIndex, s.rootCount) } }
                    if (unreachable.isNotEmpty()) {
                        full("unreachable") { UnreachableBanner(unreachable.map(::rootLabel), onOpenSettings) }
                    }
                    if (!searching && continueList.isNotEmpty() && filter == Filter.ALL) {
                        full("continue") {
                            ContinueSection(continueList, snap, onOpenBook) { e ->
                                if (snap.bookId == e.id && snap.playWhenReady) app.playback.pause() else app.playback.play(e.id)
                            }
                        }
                    }
                    full("filters") {
                        FilterRow(filter, counts) { filter = it }
                    }
                    if (visible.isEmpty()) {
                        full("empty") {
                            EmptyHint(
                                when {
                                    scan != null && entries.isEmpty() -> stringResource(R.string.ab_scanning_first)
                                    entries.isEmpty() -> stringResource(R.string.ab_no_books_found)
                                    query.isNotBlank() -> stringResource(R.string.ab_no_search_results)
                                    else -> stringResource(R.string.ab_no_books_filter)
                                },
                                showSettings = entries.isEmpty() && scan == null,
                                onOpenSettings = onOpenSettings,
                            )
                        }
                    }
                    items(visible, key = { it.id }) { e ->
                        if (settings.grid) {
                            BookTile(e, onClick = { onOpenBook(e.id) }, onLongClick = { sheetFor = e.id })
                        } else {
                            BookRow(e, onClick = { onOpenBook(e.id) }, onLongClick = { sheetFor = e.id })
                        }
                    }
                }
            }
        }
    }

    val sheetEntry = entries.firstOrNull { it.id == sheetFor }
    if (sheetEntry != null) {
        BookActionSheet(
            sheetEntry,
            onDismiss = { sheetFor = null },
            onOpen = { onOpenBook(sheetEntry.id) },
            onPlay = { app.playback.play(sheetEntry.id) },
        )
    }
}

private fun LazyGridScope.full(key: String, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = key) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ab_back)) }
        },
        title = {
            TextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(stringResource(R.string.ab_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        actions = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQuery("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.ab_clear)) }
            }
        },
    )
}

@Composable
private fun SortMenu(current: SortOrder, hideFinished: Boolean, grid: Boolean) {
    val app = LocalApp.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Rounded.Sort, stringResource(R.string.ab_sort)) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                stringResource(R.string.ab_sort_by),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            val options = listOf(
                SortOrder.RECENT to R.string.ab_sort_recent,
                SortOrder.TITLE to R.string.ab_sort_title,
                SortOrder.AUTHOR to R.string.ab_sort_author,
                SortOrder.ADDED to R.string.ab_sort_added,
                SortOrder.LENGTH to R.string.ab_sort_length,
                SortOrder.PROGRESS to R.string.ab_sort_progress,
            )
            for ((order, label) in options) {
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { RadioButton(selected = current == order, onClick = null) },
                    onClick = {
                        app.settings.update { it.copy(sort = order) }
                        open = false
                    },
                )
            }
            HorizontalDivider()
            Text(
                stringResource(R.string.ab_view),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ab_view_grid)) },
                leadingIcon = { Icon(Icons.Rounded.GridView, null) },
                trailingIcon = { if (grid) Icon(Icons.Rounded.Check, null) },
                onClick = {
                    app.settings.update { it.copy(grid = true) }
                    open = false
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ab_view_list)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ViewList, null) },
                trailingIcon = { if (!grid) Icon(Icons.Rounded.Check, null) },
                onClick = {
                    app.settings.update { it.copy(grid = false) }
                    open = false
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ab_hide_finished)) },
                leadingIcon = { Checkbox(checked = hideFinished, onCheckedChange = null) },
                onClick = { app.settings.update { it.copy(hideFinished = !it.hideFinished) } },
            )
        }
    }
}

@Composable
private fun Welcome(modifier: Modifier, onAdd: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(128.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Headphones, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.ab_welcome_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ab_welcome_text),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onAdd, contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
            Icon(Icons.Rounded.CreateNewFolder, null)
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.ab_choose_folder))
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onImport) { Text(stringResource(R.string.ab_welcome_import)) }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.ab_welcome_privacy),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ScanBanner(done: Int, total: Int, rootIndex: Int, rootCount: Int) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ab_scanning), style = MaterialTheme.typography.titleSmall)
                val detail = if (total > 0) pluralStringResource(R.plurals.ab_scan_progress, total, done, total) else stringResource(R.string.ab_scan_listing)
                val folder = if (rootCount > 1) " · " + stringResource(R.string.ab_scan_folder, rootIndex + 1, rootCount) else ""
                Text(detail + folder, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun UnreachableBanner(names: List<String>, onOpenSettings: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        onClick = onOpenSettings,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WarningAmber, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(14.dp))
            Text(
                stringResource(R.string.ab_unreachable, names.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ContinueSection(
    list: List<Entry>,
    snap: PlayerSnapshot,
    onOpenBook: (String) -> Unit,
    onPlayPause: (Entry) -> Unit,
) {
    Column(Modifier.padding(bottom = 8.dp)) {
        SectionLabel(stringResource(R.string.ab_continue_listening))
        if (list.size == 1) {
            ContinueCard(list[0], snap, Modifier.fillMaxWidth(), onOpenBook, onPlayPause)
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.bleed(16.dp),
            ) {
                items(list, key = { it.id }) { e ->
                    ContinueCard(e, snap, Modifier.width(300.dp), onOpenBook, onPlayPause)
                }
            }
        }
    }
}

/** Lässt ein Element seitlich über den Innenabstand der Liste hinausragen. */
private fun Modifier.bleed(horizontal: androidx.compose.ui.unit.Dp) = layout { measurable, constraints ->
    val extra = (horizontal * 2).roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra),
    )
    layout(placeable.width - extra, placeable.height) { placeable.place(-extra / 2, 0) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
    )
}

@Composable
private fun ContinueCard(
    e: Entry,
    snap: PlayerSnapshot,
    modifier: Modifier,
    onOpenBook: (String) -> Unit,
    onPlayPause: (Entry) -> Unit,
) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = bookColor(e)
    val container = if (dark) base.withLightness(0.2f, 0.45f) else base.withLightness(0.9f, 0.5f)
    val playing = snap.bookId == e.id && snap.playWhenReady
    val position = if (snap.bookId == e.id) e.book.globalOf(snap.trackIndex, snap.trackPositionMs) else e.positionMs
    val chIndex = e.book.chapterAt(position)
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = container,
        contentColor = MaterialTheme.colorScheme.onSurface,
        onClick = { onOpenBook(e.id) },
        modifier = modifier,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(e, Modifier.size(84.dp), RoundedCornerShape(14.dp), elevation = 4.dp)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    chapterTitle(e.book.chapters.getOrNull(chIndex), chIndex),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(e.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                val progress = if (e.durationMs > 0) position.toFloat() / e.durationMs else 0f
                ThinProgress(progress, Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.ab_remaining, durationText(e.durationMs - position)) + " · " + percentText(progress),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FilledIconButton(
                onClick = { onPlayPause(e) },
                modifier = Modifier.size(52.dp),
                colors = IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.ab_pause else R.string.ab_play),
                    Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun FilterRow(filter: Filter, counts: Map<Filter, Int>, onFilter: (Filter) -> Unit) {
    val order = listOf(Filter.ALL, Filter.STARTED, Filter.NEW, Filter.FINISHED)
    val labels = listOf(R.string.ab_filter_all, R.string.ab_filter_started, R.string.ab_filter_new, R.string.ab_filter_finished)
    SegmentedPicker(
        segments = order.mapIndexed { i, f -> Segment(stringResource(labels[i]), (counts[f] ?: 0).toString()) },
        selected = order.indexOf(filter),
        onSelect = { onFilter(order[it]) },
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun EmptyHint(text: String, showSettings: Boolean, onOpenSettings: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (showSettings) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onOpenSettings) { Text(stringResource(R.string.ab_manage_folders)) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(e: Entry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(bottom = 4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(e, Modifier.fillMaxSize(), RoundedCornerShape(16.dp))
            when (e.status) {
                Status.FINISHED -> Badge(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                    Icon(Icons.Rounded.Check, stringResource(R.string.ab_status_finished), Modifier.size(16.dp), tint = Color.White)
                }
                Status.NEW -> Unit
                Status.STARTED -> Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f))))
                        .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 10.dp),
                ) {
                    ThinProgress(e.progress, Modifier.fillMaxWidth(), color = Color.White, track = Color.White.copy(alpha = 0.35f))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            e.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        if (!e.author.isNullOrBlank()) {
            Text(
                e.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        Text(
            statusLine(e),
            style = MaterialTheme.typography.labelSmall,
            color = if (e.status == Status.STARTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun Badge(modifier: Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(e: Entry, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Cover(e, Modifier.size(68.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!e.author.isNullOrBlank()) {
                Text(
                    e.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (e.status == Status.STARTED) {
                    ThinProgress(e.progress, Modifier.widthIn(max = 120.dp).weight(1f, fill = false).width(96.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Text(
                    statusLine(e),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (e.status == Status.STARTED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (e.status == Status.FINISHED) {
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
