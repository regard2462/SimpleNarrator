package app.simplenarrator.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.simplenarrator.AppContainer
import app.simplenarrator.R
import app.simplenarrator.playback.Cue

private val SPEEDS = listOf(0.8f, 1.0f, 1.2f, 1.4f, 1.7f, 2.0f)

/** Steuerleiste unten – sichtbar, sobald etwas vorgelesen wird. */
@Composable
fun PlayerBar(c: AppContainer, onOpen: (Cue) -> Unit) {
    val s by c.playback.state.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loadingVoice by c.tts.loading.collectAsState()
    AnimatedVisibility(visible = s.active) {
        Surface(tonalElevation = 6.dp, shadowElevation = 8.dp) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { s.cue?.let(onOpen) }.padding(start = 8.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Serif, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            when {
                                loadingVoice != null -> stringResource(R.string.loading_voice, loadingVoice.orEmpty())
                                s.buffering -> stringResource(R.string.generating_speech)
                                else -> s.subtitle
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    var speedMenu by remember { mutableStateOf(false) }
                    AssistChip(onClick = { speedMenu = true }, label = { Text("${"%.1f".format(s.speed)}×") })
                    DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                        SPEEDS.forEach { sp -> DropdownMenuItem(text = { Text("${"%.1f".format(sp)}×") }, onClick = { c.playback.setSpeed(sp); speedMenu = false }) }
                    }
                    var sleepMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { sleepMenu = true }) { Icon(Icons.Default.Bedtime, stringResource(R.string.sleep_timer)) }
                    DropdownMenu(expanded = sleepMenu, onDismissRequest = { sleepMenu = false }) {
                        listOf(15, 30, 45, 60).forEach { m ->
                            DropdownMenuItem(text = { Text(pluralStringResource(R.plurals.sleep_minutes, m, m)) }, onClick = { c.playback.sleepTimer(m); sleepMenu = false })
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.sleep_chapter_end)) }, onClick = { c.playback.sleepTimer(-1); sleepMenu = false })
                        DropdownMenuItem(text = { Text(stringResource(R.string.sleep_off)) }, onClick = { c.playback.sleepTimer(0); sleepMenu = false })
                    }
                    // Gesprochenen Satz als Lesezeichen merken
                    IconButton(onClick = {
                        val cue = s.cue ?: return@IconButton
                        val book = c.repo.getBook(cue.bookId) ?: return@IconButton
                        scope.launch { c.annotations.mark(book, cue.chapter, cue.sentence) }
                        Toast.makeText(ctx, ctx.getString(R.string.remembered), Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.BookmarkAdd, stringResource(R.string.remember_sentence)) }
                    IconButton(onClick = { c.playback.stop() }) { Icon(Icons.Default.Close, stringResource(R.string.stop_reading)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { c.playback.prevChapter() }) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.previous_chapter)) }
                    IconButton(onClick = { c.playback.prevSentence() }) { Icon(Icons.Default.FastRewind, stringResource(R.string.previous_sentence)) }
                    FilledIconButton(onClick = { c.playback.toggle() }, modifier = Modifier.size(56.dp)) {
                        if (s.buffering && !s.isPlaying) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, stringResource(if (s.isPlaying) R.string.pause else R.string.play))
                    }
                    IconButton(onClick = { c.playback.nextSentence() }) { Icon(Icons.Default.FastForward, stringResource(R.string.next_sentence)) }
                    IconButton(onClick = { c.playback.nextChapter() }) { Icon(Icons.Default.SkipNext, stringResource(R.string.next_chapter)) }
                }
            }
        }
    }
}
