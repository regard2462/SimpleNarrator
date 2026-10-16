package app.simplenarrator.audiobooks.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Bookmark
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.playback.SleepTimer
import app.simplenarrator.audiobooks.ui.book.BookmarkNoteDialog
import app.simplenarrator.audiobooks.ui.book.BookmarkRow
import app.simplenarrator.audiobooks.ui.book.ChapterRow
import app.simplenarrator.audiobooks.ui.book.ChapterState
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.SeekBar
import app.simplenarrator.audiobooks.ui.components.Segment
import app.simplenarrator.audiobooks.ui.components.SegmentedPicker
import app.simplenarrator.audiobooks.ui.components.bookmarksLabel
import app.simplenarrator.audiobooks.ui.components.chaptersCount
import app.simplenarrator.audiobooks.util.Format
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f)
private val BOOST_STEPS = listOf(0, 3, 6, 9)
private val SLEEP_MINUTES = listOf(5, 10, 15, 20, 30, 45, 60, 90)

private fun roundSpeed(v: Float): Float = ((v * 20f).roundToInt() / 20f).coerceIn(0.5f, 3f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSheet(entry: Entry, speed: Float, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.ab_speed), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(onClick = { app.library.setSpeed(entry.id, roundSpeed(speed - 0.05f)) }) {
                    Icon(Icons.Rounded.Remove, stringResource(R.string.ab_slower))
                }
                Text(Format.speed(speed), style = MaterialTheme.typography.displaySmall)
                FilledTonalIconButton(onClick = { app.library.setSpeed(entry.id, roundSpeed(speed + 0.05f)) }) {
                    Icon(Icons.Rounded.Add, stringResource(R.string.ab_faster))
                }
            }
            Spacer(Modifier.height(8.dp))
            SeekBar(
                fraction = (speed - 0.5f) / 2.5f,
                onChange = { f -> app.library.setSpeed(entry.id, roundSpeed(0.5f + f * 2.5f)) },
                onCommit = { f -> app.library.setSpeed(entry.id, roundSpeed(0.5f + f * 2.5f)) },
                description = stringResource(R.string.ab_speed),
            )
            Spacer(Modifier.height(10.dp))
            ChoiceTiles(
                labels = SPEED_PRESETS.map { Format.speed(it) },
                selected = SPEED_PRESETS.indexOfFirst { kotlin.math.abs(it - speed) < 0.001f },
                columns = SPEED_PRESETS.size,
            ) { app.library.setSpeed(entry.id, SPEED_PRESETS[it]) }
            if (kotlin.math.abs(settings.defaultSpeed - speed) > 0.001f) {
                TextButton(onClick = { app.settings.update { it.copy(defaultSpeed = speed) } }) {
                    Text(stringResource(R.string.ab_speed_as_default))
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchRow(
                title = stringResource(R.string.ab_skip_silence),
                subtitle = stringResource(R.string.ab_skip_silence_desc),
                checked = entry.state.skipSilence,
            ) { app.library.setSkipSilence(entry.id, it) }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ab_volume_boost), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            val off = stringResource(R.string.ab_off)
            SegmentedPicker(
                segments = BOOST_STEPS.map { Segment(if (it == 0) off else "+$it dB") },
                selected = BOOST_STEPS.indexOf(entry.state.boostDb).coerceAtLeast(0),
                onSelect = { app.library.setBoost(entry.id, BOOST_STEPS[it]) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SleepSheet(entry: Entry, position: Long, loaded: Boolean, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val state by SleepTimer.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 20.dp)) {
            Text(stringResource(R.string.ab_sleep_timer), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            val s = state
            if (s != null) {
                Text(
                    when (s) {
                        is SleepTimer.Countdown -> sleepLabel(s).orEmpty()
                        is SleepTimer.ChapterEnd -> stringResource(R.string.ab_sleep_chapter_end)
                    },
                    style = MaterialTheme.typography.displayMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.ab_sleep_running),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (s is SleepTimer.Countdown) {
                        OutlinedButton(onClick = { SleepTimer.extend(5 * 60_000L) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.ab_plus_5_min))
                        }
                    }
                    Button(onClick = { SleepTimer.cancel() }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ab_sleep_stop))
                    }
                }
            } else {
                Text(
                    stringResource(R.string.ab_sleep_pause_after),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                val minuteLabels = SLEEP_MINUTES.map { stringResource(R.string.ab_minutes_short, it) }
                ChoiceTiles(labels = minuteLabels, selected = -1, columns = 4) { i ->
                    SleepTimer.startMinutes(SLEEP_MINUTES[i])
                    app.settings.update { it.copy(sleepMinutes = SLEEP_MINUTES[i]) }
                    onDismiss()
                }
                val chIdx = entry.book.chapterAt(position)
                val chapter = entry.book.chapters.getOrNull(chIdx)
                if (chapter != null && entry.book.chapters.size > 1 && loaded) {
                    Spacer(Modifier.height(8.dp))
                    FilledTonalButton(
                        onClick = {
                            SleepTimer.startChapterEnd(entry.id, chIdx, chapter.endMs)
                            onDismiss()
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Icon(Icons.Rounded.Bedtime, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.ab_sleep_chapter_end))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SwitchRow(stringResource(R.string.ab_sleep_fade), stringResource(R.string.ab_sleep_fade_desc), settings.sleepFade) { on ->
                app.settings.update { it.copy(sleepFade = on) }
            }
            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.ab_sleep_shake), stringResource(R.string.ab_sleep_shake_desc), settings.shakeToExtend) { on ->
                app.settings.update { it.copy(shakeToExtend = on) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(entry: Entry, position: Long, startTab: Int, onDismiss: () -> Unit) {
    val app = LocalApp.current
    var tab by remember { mutableIntStateOf(startTab) }
    var editBookmark by remember { mutableStateOf<Bookmark?>(null) }
    val chIdx = entry.book.chapterAt(position)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (chIdx - 2).coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.85f)) {
            SecondaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(chaptersCount(entry.book.chapters.size)) })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(bookmarksLabel(entry.state.bookmarks.size)) },
                )
            }
            if (tab == 0) {
                LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                    itemsIndexed(entry.book.chapters, key = { i, _ -> i }) { i, ch ->
                        val state = when {
                            i < chIdx -> ChapterState.DONE
                            i == chIdx -> ChapterState.CURRENT
                            else -> ChapterState.UPCOMING
                        }
                        val progress = if (ch.durationMs > 0) (position - ch.startMs).toFloat() / ch.durationMs else 0f
                        ChapterRow(i, ch, state, progress) {
                            app.playback.play(entry.id, ch.startMs)
                            onDismiss()
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    item {
                        OutlinedButton(
                            onClick = { app.library.addBookmark(entry.id, position) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            Icon(Icons.Rounded.BookmarkAdd, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ab_bookmark_here, Format.clock(position)))
                        }
                    }
                    if (entry.state.bookmarks.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.ab_no_bookmarks),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                    itemsIndexed(entry.state.bookmarks, key = { _, b -> b.id }) { _, b ->
                        BookmarkRow(
                            entry, b,
                            onClick = {
                                app.playback.play(entry.id, b.positionMs)
                                onDismiss()
                            },
                            onEdit = { editBookmark = b },
                            onDelete = { app.library.deleteBookmark(entry.id, b.id) },
                        )
                    }
                }
            }
        }
    }
    editBookmark?.let { b ->
        BookmarkNoteDialog(b.note, onDismiss = { editBookmark = null }) { app.library.editBookmark(entry.id, b.id, it) }
    }
}

/** Gleich breite Auswahlfelder in festen Spalten. */
@Composable
fun ChoiceTiles(labels: List<String>, selected: Int, columns: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.indices.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                for (i in row) {
                    val active = i == selected
                    Surface(
                        onClick = { onSelect(i) },
                        shape = RoundedCornerShape(14.dp),
                        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f).height(46.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(labels[i], style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
