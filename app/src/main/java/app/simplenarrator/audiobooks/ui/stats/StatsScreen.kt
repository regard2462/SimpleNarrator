package app.simplenarrator.audiobooks.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.Status
import app.simplenarrator.audiobooks.ui.components.Cover
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.LocalBottomInset
import app.simplenarrator.audiobooks.ui.components.ThinProgress
import app.simplenarrator.audiobooks.ui.components.durationText
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

private const val DAYS = 14

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val stats by app.stats.data.collectAsStateWithLifecycle()
    val entries by app.library.entries.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val days = remember(stats, today) { (DAYS - 1 downTo 0).map { today.minusDays(it.toLong()) } }
    val values = remember(stats, days) { days.map { stats.days[it.toString()] ?: 0L } }
    val week = values.takeLast(7).sum()
    val total = stats.days.values.sum()
    val top = remember(entries) { entries.filter { it.state.listenedMs > 60_000 }.sortedByDescending { it.state.listenedMs }.take(5) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ab_statistics)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ab_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomInset.current + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(stringResource(R.string.ab_stats_today), durationText(values.last()), Modifier.weight(1f))
                    StatTile(stringResource(R.string.ab_stats_week), durationText(week), Modifier.weight(1f))
                    StatTile(stringResource(R.string.ab_stats_total), durationText(total), Modifier.weight(1f))
                }
            }
            item { DayChart(days, values) }
            item {
                val finished = entries.count { it.status == Status.FINISHED }
                val started = entries.count { it.status == Status.STARTED }
                val fresh = entries.count { it.status == Status.NEW }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(stringResource(R.string.ab_filter_finished), finished.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.ab_filter_started), started.toString(), Modifier.weight(1f))
                    StatTile(stringResource(R.string.ab_filter_new), fresh.toString(), Modifier.weight(1f))
                }
            }
            if (top.isNotEmpty()) {
                item {
                    Text(
                        stringResource(R.string.ab_stats_most_listened),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                val max = top.first().state.listenedMs.toFloat()
                items(top, key = { it.id }) { e ->
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Cover(e, Modifier.size(48.dp), RoundedCornerShape(10.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                            ThinProgress(e.state.listenedMs / max, Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(durationText(e.state.listenedMs), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.ab_stats_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Säulen je Tag; Antippen zeigt den Wert des Tages, voreingestellt ist heute. */
@Composable
private fun DayChart(days: List<LocalDate>, values: List<Long>) {
    val context = LocalContext.current
    var selected by remember(days) { mutableIntStateOf(days.lastIndex) }
    val barColor = MaterialTheme.colorScheme.primary
    val selectedColor = MaterialTheme.colorScheme.primary
    val dimColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(60_000L).toFloat()
    val locale = Locale.getDefault()

    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(18.dp)) {
            Text(pluralStringResource(R.plurals.ab_stats_last_days, DAYS, DAYS), style = MaterialTheme.typography.titleSmall)
            val d = days[selected]
            val dayName = d.dayOfWeek.getDisplayName(TextStyle.FULL, locale)
            Text(
                "$dayName, ${app.simplenarrator.audiobooks.util.Format.date(context, d.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli())}: ${durationText(values[selected])}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .pointerInput(days) {
                        detectTapGestures { pos ->
                            val slot = size.width / days.size.toFloat()
                            selected = (pos.x / slot).toInt().coerceIn(0, days.lastIndex)
                        }
                    },
            ) {
                val slot = size.width / days.size
                val gap = 2.dp.toPx()
                val barW = (slot - gap * 3).coerceAtLeast(4f)
                // Grundlinie
                drawLine(gridColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                values.forEachIndexed { i, v ->
                    val h = if (v <= 0) 0f else (v / max * size.height).coerceAtLeast(3.dp.toPx())
                    if (h <= 0f) return@forEachIndexed
                    val left = i * slot + (slot - barW) / 2
                    val r = 4.dp.toPx().coerceAtMost(barW / 2)
                    val path = Path().apply {
                        addRoundRect(
                            RoundRect(
                                left = left, top = size.height - h, right = left + barW, bottom = size.height,
                                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                            ),
                        )
                    }
                    drawPath(path, if (i == selected) selectedColor else if (selected == days.lastIndex) barColor else dimColor)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth()) {
                days.forEachIndexed { i, day ->
                    Text(
                        day.dayOfWeek.getDisplayName(TextStyle.NARROW, locale),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (i == selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (i == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
