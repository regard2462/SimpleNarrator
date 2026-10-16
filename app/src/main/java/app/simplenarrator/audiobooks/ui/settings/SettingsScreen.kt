package app.simplenarrator.audiobooks.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.HideImage
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.BuildConfig
import app.simplenarrator.R
import app.simplenarrator.audiobooks.data.LibraryRoot
import app.simplenarrator.audiobooks.data.ThemeMode
import app.simplenarrator.audiobooks.ui.components.LocalApp
import app.simplenarrator.audiobooks.ui.components.LocalBottomInset
import app.simplenarrator.audiobooks.ui.components.rememberFolderPicker
import app.simplenarrator.audiobooks.ui.components.rootLabel
import app.simplenarrator.audiobooks.ui.components.rootPath
import app.simplenarrator.util.AppLanguage
import app.simplenarrator.audiobooks.util.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenStats: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val settings by app.settings.flow.collectAsStateWithLifecycle()
    val entries by app.library.entries.collectAsStateWithLifecycle()
    val states by app.library.states.collectAsStateWithLifecycle()
    val coverProgress by app.onlineCovers.progress.collectAsStateWithLifecycle()
    val hidden by app.library.hiddenEntries.collectAsStateWithLifecycle()
    val unreachable by app.library.unreachableRoots.collectAsStateWithLifecycle()
    val scan by app.library.scan.collectAsStateWithLifecycle()
    val addFolder = rememberFolderPicker()
    var removeRoot by remember { mutableStateOf<LibraryRoot?>(null) }
    var choice by remember { mutableStateOf<ChoiceDialog?>(null) }

    val importExport = rememberImportExport()

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.ab_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.ab_back)) } },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
            contentPadding = PaddingValues(bottom = LocalBottomInset.current + 24.dp),
        ) {
            item { Header(stringResource(R.string.ab_section_library)) }
            items(settings.roots, key = { it.uri }) { root ->
                val count = entries.count { it.book.root == root.uri }
                val bad = root.uri in unreachable
                val sub = when {
                    bad -> stringResource(R.string.ab_folder_unreachable)
                    root.single -> stringResource(R.string.ab_folder_type_single)
                    else -> pluralStringResource(R.plurals.ab_books, count, count)
                }
                SettingRow(
                    icon = Icons.Rounded.Folder,
                    title = rootLabel(root.uri),
                    subtitle = rootPath(root.uri).let { p -> if (p == rootLabel(root.uri)) sub else "$p · $sub" },
                    error = bad,
                    trailing = {
                        IconButton(onClick = { removeRoot = root }) { Icon(Icons.Rounded.Delete, stringResource(R.string.ab_remove_folder)) }
                    },
                )
            }
            item {
                SettingRow(Icons.Rounded.CreateNewFolder, stringResource(R.string.ab_add_folder), onClick = addFolder)
            }
            item {
                SettingRow(
                    Icons.Rounded.Refresh,
                    stringResource(R.string.ab_rescan),
                    subtitle = if (scan != null) stringResource(R.string.ab_scanning) else stringResource(R.string.ab_rescan_desc),
                    onClick = { app.library.rescan() },
                )
            }

            if (hidden.isNotEmpty()) {
                item { Header(stringResource(R.string.ab_section_removed)) }
                items(hidden, key = { "h" + it.id }) { e ->
                    SettingRow(
                        icon = Icons.Rounded.VisibilityOff,
                        title = e.title,
                        subtitle = e.author,
                        trailing = {
                            TextButton(onClick = { app.library.restoreBook(e.id) }) { Text(stringResource(R.string.ab_restore)) }
                        },
                    )
                }
            }

            item { Header(stringResource(R.string.ab_section_covers)) }
            item {
                val progress = coverProgress
                val desc = if (settings.webCovers && progress != null) {
                    stringResource(R.string.ab_web_covers_progress, progress.done, progress.total)
                } else {
                    stringResource(R.string.ab_web_covers_desc)
                }
                SwitchSetting(stringResource(R.string.ab_web_covers), desc, settings.webCovers) { on ->
                    app.settings.update { it.copy(webCovers = on) }
                }
            }
            val webCount = states.values.count { it.webCover != null }
            if (!settings.webCovers && webCount > 0) {
                item {
                    SettingRow(
                        Icons.Rounded.HideImage,
                        stringResource(R.string.ab_web_covers_clear),
                        pluralStringResource(R.plurals.ab_web_covers_count, webCount, webCount),
                    ) {
                        app.library.clearWebCovers()
                        Toast.makeText(context, R.string.ab_web_covers_cleared, Toast.LENGTH_SHORT).show()
                    }
                }
            }

            item { Header(stringResource(R.string.ab_section_playback)) }
            item {
                val opts = listOf(5, 10, 15, 20, 30, 45, 60)
                ValueRow(stringResource(R.string.ab_skip_back), stringResource(R.string.ab_seconds_n, settings.skipBackSec)) {
                    choice = ChoiceDialog(context.getString(R.string.ab_skip_back), opts.map { context.getString(R.string.ab_seconds_n, it) }, opts.indexOf(settings.skipBackSec)) { i ->
                        app.settings.update { it.copy(skipBackSec = opts[i]) }
                    }
                }
            }
            item {
                val opts = listOf(5, 10, 15, 20, 30, 45, 60, 90)
                ValueRow(stringResource(R.string.ab_skip_forward), stringResource(R.string.ab_seconds_n, settings.skipForwardSec)) {
                    choice = ChoiceDialog(context.getString(R.string.ab_skip_forward), opts.map { context.getString(R.string.ab_seconds_n, it) }, opts.indexOf(settings.skipForwardSec)) { i ->
                        app.settings.update { it.copy(skipForwardSec = opts[i]) }
                    }
                }
            }
            item {
                val opts = listOf(0, 2, 3, 5, 10, 15, 20, 30)
                fun label(v: Int) = if (v == 0) context.getString(R.string.ab_off) else context.getString(R.string.ab_seconds_n, v)
                ValueRow(stringResource(R.string.ab_resume_rewind), label(settings.resumeRewindSec), stringResource(R.string.ab_resume_rewind_desc)) {
                    choice = ChoiceDialog(context.getString(R.string.ab_resume_rewind), opts.map(::label), opts.indexOf(settings.resumeRewindSec)) { i ->
                        app.settings.update { it.copy(resumeRewindSec = opts[i]) }
                    }
                }
            }
            item {
                SwitchSetting(stringResource(R.string.ab_smart_rewind), stringResource(R.string.ab_smart_rewind_desc), settings.smartRewind, enabled = settings.resumeRewindSec > 0) { on ->
                    app.settings.update { it.copy(smartRewind = on) }
                }
            }
            item {
                val opts = listOf(0.75f, 0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.75f, 2.0f)
                ValueRow(stringResource(R.string.ab_default_speed), Format.speed(settings.defaultSpeed), stringResource(R.string.ab_default_speed_desc)) {
                    choice = ChoiceDialog(context.getString(R.string.ab_default_speed), opts.map { Format.speed(it) }, opts.indexOfFirst { kotlin.math.abs(it - settings.defaultSpeed) < 0.001f }) { i ->
                        app.settings.update { it.copy(defaultSpeed = opts[i]) }
                    }
                }
            }
            item {
                SwitchSetting(stringResource(R.string.ab_headset_chapters), stringResource(R.string.ab_headset_chapters_desc), settings.headsetChapters) { on ->
                    app.settings.update { it.copy(headsetChapters = on) }
                }
            }

            item { Header(stringResource(R.string.ab_sleep_timer)) }
            item {
                SwitchSetting(stringResource(R.string.ab_sleep_fade), stringResource(R.string.ab_sleep_fade_desc), settings.sleepFade) { on ->
                    app.settings.update { it.copy(sleepFade = on) }
                }
            }
            item {
                SwitchSetting(stringResource(R.string.ab_sleep_shake), stringResource(R.string.ab_sleep_shake_desc), settings.shakeToExtend) { on ->
                    app.settings.update { it.copy(shakeToExtend = on) }
                }
            }

            item { Header(stringResource(R.string.ab_section_appearance)) }
            item {
                val labels = listOf(R.string.ab_theme_system, R.string.ab_theme_light, R.string.ab_theme_dark).map { context.getString(it) }
                ValueRow(stringResource(R.string.ab_theme), labels[settings.theme.ordinal]) {
                    choice = ChoiceDialog(context.getString(R.string.ab_theme), labels, settings.theme.ordinal) { i ->
                        app.settings.update { it.copy(theme = ThemeMode.entries[i]) }
                    }
                }
            }
            item {
                val tags = AppLanguage.OPTIONS
                val labels = listOf(
                    context.getString(R.string.ab_language_system),
                    context.getString(R.string.ab_language_de),
                    context.getString(R.string.ab_language_en),
                )
                val current = tags.indexOf(AppLanguage.current(context)).coerceAtLeast(0)
                ValueRow(stringResource(R.string.ab_language), labels[current]) {
                    choice = ChoiceDialog(context.getString(R.string.ab_language), labels, current) { i ->
                        context.findActivity()?.let { AppLanguage.set(it, tags[i]) }
                    }
                }
            }
            item {
                PaletteRow(settings.palette) { p -> app.settings.update { it.copy(palette = p) } }
            }
            item {
                SwitchSetting(stringResource(R.string.ab_pure_black), stringResource(R.string.ab_pure_black_desc), settings.pureBlack) { on ->
                    app.settings.update { it.copy(pureBlack = on) }
                }
            }

            item { Header(stringResource(R.string.ab_section_data)) }
            item { SettingRow(Icons.Rounded.BarChart, stringResource(R.string.ab_statistics), stringResource(R.string.ab_statistics_desc), onClick = onOpenStats) }
            item {
                SettingRow(Icons.Rounded.Upload, stringResource(R.string.ab_backup_export), stringResource(R.string.ab_backup_export_desc)) {
                    importExport.export()
                }
            }
            item {
                SettingRow(Icons.Rounded.Download, stringResource(R.string.ab_backup_import), stringResource(R.string.ab_backup_import_desc)) {
                    importExport.import()
                }
            }

            item { Header(stringResource(R.string.ab_section_about)) }
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.ab_about_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(10.dp))
                        Text(stringResource(R.string.ab_about_licenses), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    removeRoot?.let { root ->
        AlertDialog(
            onDismissRequest = { removeRoot = null },
            title = { Text(stringResource(R.string.ab_remove_folder)) },
            text = { Text(stringResource(R.string.ab_remove_folder_text, rootLabel(root.uri))) },
            confirmButton = {
                TextButton(onClick = {
                    app.library.removeRoot(root.uri)
                    removeRoot = null
                }) { Text(stringResource(R.string.ab_remove)) }
            },
            dismissButton = { TextButton(onClick = { removeRoot = null }) { Text(stringResource(R.string.ab_cancel)) } },
        )
    }
    choice?.let { c ->
        AlertDialog(
            onDismissRequest = { choice = null },
            title = { Text(c.title) },
            text = {
                LazyColumn {
                    items(c.options.size) { i ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = i == c.selected, role = Role.RadioButton) {
                                    c.onSelect(i)
                                    choice = null
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = i == c.selected, onClick = null)
                            Spacer(Modifier.width(14.dp))
                            Text(c.options[i], style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choice = null }) { Text(stringResource(R.string.ab_cancel)) } },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private class ChoiceDialog(val title: String, val options: List<String>, val selected: Int, val onSelect: (Int) -> Unit)

@Composable
private fun Header(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 6.dp),
    )
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    error: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 24.dp, end = if (trailing != null) 8.dp else 24.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

@Composable
private fun ValueRow(title: String, value: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun SwitchSetting(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
