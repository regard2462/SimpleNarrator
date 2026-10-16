package app.simplenarrator.audiobooks.ui.player

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.playback.SleepTimer
import app.simplenarrator.audiobooks.ui.book.BookmarkNoteDialog
import app.simplenarrator.audiobooks.ui.components.Cover
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.SeekBar
import app.simplenarrator.audiobooks.ui.components.SkipIcon
import app.simplenarrator.audiobooks.ui.components.bookColor
import app.simplenarrator.audiobooks.ui.components.chapterTitle
import app.simplenarrator.audiobooks.ui.components.durationText
import app.simplenarrator.audiobooks.ui.components.percentText
import app.simplenarrator.audiobooks.ui.components.withLightness
import app.simplenarrator.audiobooks.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

enum class PlayerSheet { SPEED, SLEEP, CHAPTERS, BOOKMARKS }

@Composable
fun PlayerScreen(entry: Entry, onClose: () -> Unit, onOpenBook: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val snap by app.playback.snapshot.collectAsStateWithLifecycle()
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val sleep by SleepTimer.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onClose)

    val book = entry.book
    val loaded = snap.bookId == entry.id
    val position = if (loaded) book.globalOf(snap.trackIndex, snap.trackPositionMs) else entry.positionMs
    val chIdx = book.chapterAt(position).coerceAtLeast(0)
    val chapter = book.chapters.getOrNull(chIdx)
    val chStart = chapter?.startMs ?: 0L
    val chLen = ((chapter?.endMs ?: book.durationMs) - chStart).coerceAtLeast(1)
    val speed = entry.state.speed.takeIf { it > 0f } ?: settings.defaultSpeed
    val playing = loaded && snap.playWhenReady
    var dragging by remember { mutableStateOf<Float?>(null) }
    var sheet by remember { mutableStateOf<PlayerSheet?>(null) }
    var noteFor by remember { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val offsetY = remember { Animatable(0f) }
    val closeDistance = with(LocalDensity.current) { 140.dp.toPx() }

    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val base = bookColor(entry)
    val topColor = if (dark) base.withLightness(0.25f, 0.55f) else base.withLightness(0.83f, 0.55f)
    val surface = MaterialTheme.colorScheme.surface

    Box(
        Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offsetY.value.roundToInt().coerceAtLeast(0)) }
            .background(surface)
            .background(Brush.verticalGradient(0f to topColor, 0.8f to surface))
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { d -> scope.launch { offsetY.snapTo((offsetY.value + d).coerceAtLeast(0f)) } },
                onDragStopped = { v ->
                    if (offsetY.value > closeDistance || v > 2500f) onClose() else offsetY.animateTo(0f)
                },
            ),
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.ab_close), Modifier.size(32.dp))
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.ab_now_playing).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(entry.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onOpenBook) {
                        Icon(Icons.AutoMirrored.Rounded.MenuBook, stringResource(R.string.ab_details))
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                    Cover(
                        entry,
                        Modifier.widthIn(max = 400.dp).fillMaxWidth().aspectRatio(1f, matchHeightConstraintsFirst = true),
                        RoundedCornerShape(26.dp),
                        elevation = 24.dp,
                    )
                }

                if (book.chapters.size > 1) {
                    Text(
                        stringResource(R.string.ab_chapter_of, chIdx + 1, book.chapters.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    if (book.chapters.size > 1) chapterTitle(chapter, chIdx) else entry.title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val sub = if (book.chapters.size > 1) listOfNotNull(entry.title, entry.author) else listOfNotNull(entry.author)
                if (sub.isNotEmpty()) {
                    Text(
                        sub.joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(18.dp))

                val fraction = dragging ?: ((position - chStart).toFloat() / chLen).coerceIn(0f, 1f)
                SeekBar(
                    fraction = fraction,
                    onChange = { dragging = it },
                    onCommit = { f ->
                        app.playback.seekTo(entry.id, chStart + (f * chLen).toLong())
                        dragging = null
                    },
                    description = stringResource(R.string.ab_position_in_chapter),
                )
                Row(Modifier.fillMaxWidth()) {
                    val elapsed = (fraction * chLen).toLong()
                    Text(Format.clock(elapsed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text("−" + Format.clock(chLen - elapsed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                val bookFraction = if (book.durationMs > 0) position.toFloat() / book.durationMs else 0f
                val remainingReal = ((book.durationMs - position) / speed).toLong()
                val bookLine = buildString {
                    append(stringResource(R.string.ab_book_progress, percentText(bookFraction), durationText(remainingReal)))
                    if (speed != 1f) append(" ").append(stringResource(R.string.ab_at_speed, Format.speed(speed)))
                }
                Text(bookLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                if (loaded && snap.error != null) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.ab_playback_error), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { app.playback.retry() }) { Text(stringResource(R.string.ab_retry)) }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { app.playback.previousChapter(book, position) }, enabled = book.chapters.size > 1) {
                        Icon(Icons.Rounded.SkipPrevious, stringResource(R.string.ab_previous_chapter), Modifier.size(30.dp))
                    }
                    IconButton(onClick = { if (loaded) app.playback.skipBack() else app.playback.seekTo(entry.id, (position - settings.skipBackSec * 1000L).coerceAtLeast(0)) }, modifier = Modifier.size(64.dp)) {
                        SkipIcon(settings.skipBackSec, forward = false, size = 38.dp, contentDescription = pluralStringResource(R.plurals.ab_skip_back_n, settings.skipBackSec, settings.skipBackSec))
                    }
                    PlayButton(playing, loaded && snap.buffering && snap.playWhenReady) {
                        if (playing) app.playback.pause() else app.playback.play(entry.id)
                    }
                    IconButton(onClick = { if (loaded) app.playback.skipForward() else app.playback.seekTo(entry.id, (position + settings.skipForwardSec * 1000L).coerceAtMost(book.durationMs)) }, modifier = Modifier.size(64.dp)) {
                        SkipIcon(settings.skipForwardSec, forward = true, size = 38.dp, contentDescription = pluralStringResource(R.plurals.ab_skip_forward_n, settings.skipForwardSec, settings.skipForwardSec))
                    }
                    IconButton(onClick = { app.playback.nextChapter(book, position) }, enabled = chIdx < book.chapters.size - 1) {
                        Icon(Icons.Rounded.SkipNext, stringResource(R.string.ab_next_chapter), Modifier.size(30.dp))
                    }
                }

                Spacer(Modifier.height(18.dp))
                val sleepLabel = sleepLabel(sleep)
                Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    PlayerAction(Icons.Rounded.Speed, Format.speed(speed), highlighted = speed != 1f) { sheet = PlayerSheet.SPEED }
                    PlayerAction(Icons.Rounded.Bedtime, sleepLabel ?: stringResource(R.string.ab_sleep_timer), highlighted = sleep != null) {
                        sheet = PlayerSheet.SLEEP
                    }
                    PlayerAction(
                        Icons.Rounded.BookmarkAdd,
                        stringResource(R.string.ab_bookmark),
                        onLongClick = { sheet = PlayerSheet.BOOKMARKS },
                    ) {
                        val bm = app.library.addBookmark(entry.id, position)
                        scope.launch {
                            snackbar.currentSnackbarData?.dismiss()
                            val r = snackbar.showSnackbar(
                                message = context.getString(R.string.ab_bookmark_added, Format.clock(position)),
                                actionLabel = context.getString(R.string.ab_add_note),
                                withDismissAction = false,
                            )
                            if (r == SnackbarResult.ActionPerformed) noteFor = bm.id
                        }
                    }
                    PlayerAction(Icons.AutoMirrored.Rounded.FormatListBulleted, stringResource(R.string.ab_chapters)) { sheet = PlayerSheet.CHAPTERS }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
    }

    when (sheet) {
        PlayerSheet.SPEED -> SpeedSheet(entry, speed) { sheet = null }
        PlayerSheet.SLEEP -> SleepSheet(entry, position, loaded) { sheet = null }
        PlayerSheet.CHAPTERS -> ChaptersSheet(entry, position, startTab = 0) { sheet = null }
        PlayerSheet.BOOKMARKS -> ChaptersSheet(entry, position, startTab = 1) { sheet = null }
        null -> Unit
    }
    noteFor?.let { id ->
        val bm = entry.state.bookmarks.firstOrNull { it.id == id }
        if (bm == null) {
            noteFor = null
        } else {
            BookmarkNoteDialog(bm.note, onDismiss = { noteFor = null }) { app.library.editBookmark(entry.id, id, it) }
        }
    }
}

@Composable
fun sleepLabel(state: SleepTimer.State?): String? {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(state) {
        while (state is SleepTimer.Countdown) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    return when (state) {
        is SleepTimer.Countdown -> Format.clock(state.remaining(now) + 999)
        is SleepTimer.ChapterEnd -> stringResource(R.string.ab_sleep_chapter_end_short)
        null -> null
    }
}

@Composable
private fun PlayButton(playing: Boolean, buffering: Boolean, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shadowElevation = 6.dp,
            modifier = Modifier.size(84.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    stringResource(if (playing) R.string.ab_pause else R.string.ab_play),
                    Modifier.size(46.dp),
                )
            }
        }
        if (buffering) {
            CircularProgressIndicator(Modifier.size(92.dp), strokeWidth = 3.dp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerAction(
    icon: ImageVector,
    label: String,
    highlighted: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .widthIn(min = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .background(if (highlighted) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent)
                .padding(8.dp),
        ) {
            CompositionLocalProvider(LocalContentColor provides color) { Icon(icon, null, Modifier.size(24.dp)) }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
    }
}
