package app.simplenarrator.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.simplenarrator.AppContainer
import app.simplenarrator.BuildConfig
import app.simplenarrator.R
import app.simplenarrator.audiobooks.ui.settings.PaletteRow
import app.simplenarrator.audiobooks.data.ThemeMode
import app.simplenarrator.audiobooks.ui.components.LocalApp
import android.widget.Toast
import app.simplenarrator.data.AppSettings
import app.simplenarrator.speech.RuleError
import app.simplenarrator.speech.SpeechRules
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import app.simplenarrator.tts.Blend
import app.simplenarrator.tts.Blends
import app.simplenarrator.tts.DownloadState
import app.simplenarrator.tts.Engine
import app.simplenarrator.tts.VoiceInfo
import app.simplenarrator.tts.VoicePack
import app.simplenarrator.tts.VoicePacks
import app.simplenarrator.util.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Anzeigename einer Stimme; ohne eigenen Namen „Stimme 3“. */
@Composable
fun voiceName(v: VoiceInfo): String = v.name.ifBlank { stringResource(R.string.voice_number, v.sid + 1) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(c: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val app = LocalApp.current
    val settings by c.settings.flow.collectAsState(initial = AppSettings())
    val look by app.settings.flow.collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsState()
    val download by c.models.download.collectAsState()
    fun save(block: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(block) }

    var lexicon by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.lexicon) { if (lexicon == null) lexicon = settings.lexicon }
    val importRules = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) { runCatching { ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() }.getOrNull() }
            val imported = text?.let { SpeechRules.importRules(it) }.orEmpty()
            val count = SpeechRules.parse(imported).size
            if (count == 0) Toast.makeText(ctx, ctx.getString(R.string.sp_rules_import_empty), Toast.LENGTH_LONG).show()
            else {
                lexicon = listOfNotNull(lexicon?.takeIf { it.isNotBlank() }, imported).joinToString("\n")
                Toast.makeText(ctx, ctx.resources.getQuantityString(R.plurals.sp_rules_imported, count, count), Toast.LENGTH_LONG).show()
            }
        }
    }
    var cacheSize by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { cacheSize = withContext(Dispatchers.IO) { c.audio.sizeBytes() } }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings), fontFamily = FontFamily.Serif) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
        )
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {

            // ------------------------------------------------------------ App
            LanguageRow(ctx)
            Spacer(Modifier.height(8.dp))
            ChoiceRow(
                stringResource(R.string.ab_theme),
                listOf(R.string.ab_theme_system, R.string.ab_theme_light, R.string.ab_theme_dark).map { stringResource(it) },
                look.theme.ordinal,
            ) { i -> app.settings.update { it.copy(theme = ThemeMode.entries[i]) } }
            PaletteRow(look.palette, textInset = 0.dp, rowInset = 0.dp) { p ->
                app.settings.update { it.copy(palette = p) }
            }
            SwitchRow(stringResource(R.string.ab_pure_black), stringResource(R.string.ab_pure_black_desc), look.pureBlack) { on ->
                app.settings.update { it.copy(pureBlack = on) }
            }

            // ------------------------------------------------------------ Stimmen
            Section(stringResource(R.string.voices))
            Text(stringResource(R.string.models_location, "Android/data/${ctx.packageName}/files/models/"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            listOf("de" to R.string.voices_german, "en" to R.string.voices_english).forEach { (lang, label) ->
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                VoicePacks.forLang(lang).sortedByDescending { it.recommended }.forEach { pack ->
                    val selected = if (lang == "de") settings.packDe == pack.id else settings.packEn == pack.id
                    val sid = if (lang == "de") settings.sidDe else settings.sidEn
                    val blends = Blends.parse(settings.blends).filter { it.packId == pack.id }
                    PackCard(
                        pack = pack, installed = pack.id in installed, selected = selected, sid = sid, download = download,
                        blends = blends, blendId = if (lang == "de") settings.blendDe else settings.blendEn,
                        onDownload = { scope.launch { c.models.download(pack) } },
                        onDelete = { c.models.delete(pack) },
                        onSelect = { save { if (lang == "de") it.copy(packDe = pack.id, sidDe = 0, blendDe = "") else it.copy(packEn = pack.id, sidEn = 0, blendEn = "") } },
                        onVoice = { v -> save { if (lang == "de") it.copy(sidDe = v, blendDe = "") else it.copy(sidEn = v, blendEn = "") } },
                        onBlend = { b -> save { if (lang == "de") it.copy(sidDe = b.slot, blendDe = b.id) else it.copy(sidEn = b.slot, blendEn = b.id) } },
                        onCreateBlend = { name, a, b, w ->
                            scope.launch {
                                runCatching { withContext(Dispatchers.IO) { Blends.create(c.models, pack, name, a, b, w) } }
                                    .onSuccess { blend ->
                                        c.settings.update { s ->
                                            val list = Blends.encode(Blends.parse(s.blends) + blend)
                                            if (lang == "de") s.copy(blends = list, sidDe = blend.slot, blendDe = blend.id) else s.copy(blends = list, sidEn = blend.slot, blendEn = blend.id)
                                        }
                                    }
                                    .onFailure { Toast.makeText(ctx, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
                            }
                        },
                        onDeleteBlend = { blend ->
                            scope.launch {
                                withContext(Dispatchers.IO) { Blends.delete(c.models, pack, blend) }
                                c.settings.update { s ->
                                    s.copy(
                                        blends = Blends.encode(Blends.parse(s.blends).filterNot { it.id == blend.id }),
                                        blendDe = if (s.blendDe == blend.id) "" else s.blendDe,
                                        blendEn = if (s.blendEn == blend.id) "" else s.blendEn,
                                    )
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }

            // ------------------------------------------------------------ Wörtliche Rede
            Section(stringResource(R.string.dialogue_title))
            SwitchRow(stringResource(R.string.dialogue_switch), stringResource(R.string.dialogue_hint), settings.dialogueVoice) { v -> save { it.copy(dialogueVoice = v) } }
            if (settings.dialogueVoice) {
                listOf("de" to R.string.voices_german, "en" to R.string.voices_english).forEach { (lang, label) ->
                    DialogueVoiceRow(stringResource(label), lang, settings, installed) { packId, sid ->
                        save { if (lang == "de") it.copy(dialoguePackDe = packId, dialogueSidDe = sid) else it.copy(dialoguePackEn = packId, dialogueSidEn = sid) }
                    }
                }
            }

            // ------------------------------------------------------------ Vorlesen
            Section(stringResource(R.string.section_reading))
            Text(stringResource(R.string.voice_speed, "%.2f".format(settings.voiceSpeed)))
            Slider(value = settings.voiceSpeed, onValueChange = { v -> save { it.copy(voiceSpeed = (v * 20).roundToInt() / 20f) } }, valueRange = 0.7f..1.5f)
            Hint(stringResource(R.string.voice_speed_hint))
            Spacer(Modifier.height(12.dp))
            SwitchRow(stringResource(R.string.normalize_title), stringResource(R.string.normalize_hint), settings.normalizeText) { v -> save { it.copy(normalizeText = v) } }
            SwitchRow(stringResource(R.string.auto_lang_title), stringResource(R.string.auto_lang_hint), settings.autoLang) { v -> save { it.copy(autoLang = v) } }
            SwitchRow(stringResource(R.string.word_highlight_title), stringResource(R.string.word_highlight_hint), settings.wordHighlight) { v -> save { it.copy(wordHighlight = v) } }
            SwitchRow(stringResource(R.string.stop_after_title), stringResource(R.string.stop_after_hint), settings.stopAfterSentence) { v -> save { it.copy(stopAfterSentence = v) } }
            SwitchRow(stringResource(R.string.rewind_title), stringResource(R.string.rewind_hint), settings.rewindAfterPause) { v -> save { it.copy(rewindAfterPause = v) } }
            SwitchRow(stringResource(R.string.sleep_fade_title), stringResource(R.string.sleep_fade_hint), settings.sleepFade) { v -> save { it.copy(sleepFade = v) } }
            SwitchRow(stringResource(R.string.shake_title), stringResource(R.string.shake_hint), settings.shakeToExtend) { v -> save { it.copy(shakeToExtend = v) } }
            SwitchRow(stringResource(R.string.keep_both_title), stringResource(R.string.keep_both_hint), settings.keepBothLoaded) { v -> save { it.copy(keepBothLoaded = v) } }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.threads, settings.threads))
            Slider(value = settings.threads.toFloat(), onValueChange = { v -> save { it.copy(threads = v.roundToInt()) } }, valueRange = 1f..6f, steps = 4)

            // ------------------------------------------------------------ Pausen
            Section(stringResource(R.string.pauses_title))
            Hint(stringResource(R.string.pauses_hint))
            Text(stringResource(R.string.pause_sentence, settings.sentencePauseMs))
            Slider(value = settings.sentencePauseMs.toFloat(), onValueChange = { v -> save { it.copy(sentencePauseMs = (v / 50).roundToInt() * 50) } }, valueRange = 0f..1500f)
            Text(stringResource(R.string.pause_paragraph, settings.paragraphPauseMs))
            Slider(value = settings.paragraphPauseMs.toFloat(), onValueChange = { v -> save { it.copy(paragraphPauseMs = (v / 50).roundToInt() * 50) } }, valueRange = 0f..3000f)
            Text(stringResource(R.string.pause_chapter, settings.chapterPauseMs))
            Slider(value = settings.chapterPauseMs.toFloat(), onValueChange = { v -> save { it.copy(chapterPauseMs = (v / 100).roundToInt() * 100) } }, valueRange = 0f..5000f)

            // ------------------------------------------------------------ Zwischenspeicher
            Section(stringResource(R.string.cache_title))
            SwitchRow(stringResource(R.string.cache_switch), stringResource(R.string.cache_hint), settings.cacheAudio) { v -> save { it.copy(cacheAudio = v) } }
            if (settings.cacheAudio) {
                Text(stringResource(R.string.cache_limit, settings.cacheLimitMb))
                Slider(value = settings.cacheLimitMb.toFloat(), onValueChange = { v -> save { it.copy(cacheLimitMb = (v / 50).roundToInt() * 50) } }, valueRange = 100f..2000f)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.cache_used, Formatter.formatShortFileSize(ctx, cacheSize)), modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { c.audio.clearAll() }; cacheSize = 0 } }) { Text(stringResource(R.string.cache_clear)) }
            }

            // ------------------------------------------------------------ Aussprache
            Section(stringResource(R.string.lexicon_title))
            Hint(stringResource(R.string.sp_rules_help))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = lexicon ?: "", onValueChange = { lexicon = it }, modifier = Modifier.fillMaxWidth().height(180.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
            LexiconErrors(lexicon.orEmpty())
            Row {
                TextButton(onClick = { save { it.copy(lexicon = lexicon ?: "") } }) { Text(stringResource(R.string.lexicon_save)) }
                TextButton(onClick = { importRules.launch(arrayOf("text/*", "application/octet-stream")) }) { Text(stringResource(R.string.lexicon_import)) }
            }

            // ------------------------------------------------------------ Export
            Section(stringResource(R.string.export_title))
            Text(stringResource(R.string.export_bitrate, settings.exportBitrate))
            Slider(value = settings.exportBitrate.toFloat(), onValueChange = { v -> save { it.copy(exportBitrate = (v / 16).roundToInt() * 16) } }, valueRange = 32f..128f, steps = 5)
            Spacer(Modifier.height(8.dp))
            AudiobookshelfSettings(c.http)

            // ------------------------------------------------------------ Texterkennung
            Section(stringResource(R.string.ocr_settings_title))
            Hint(stringResource(R.string.ocr_settings_hint))
            OcrLanguages(c)

            // ------------------------------------------------------------ Systemstimme
            Section(stringResource(R.string.sp_system_tts_title))
            Hint(stringResource(R.string.sp_system_tts_hint))
            SystemTtsRow()

            HorizontalDivider(Modifier.padding(vertical = 16.dp))
            Text(stringResource(R.string.app_name) + " " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SupportRow()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 16.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Seite zum Unterstützen der App bei Buy Me a Coffee. */
private const val SUPPORT_URL = "https://buymeacoffee.com/regard2462"

/** Zeile mit einer gezeichneten Kaffeetasse auf gelbem Grund; öffnet die Seite im Browser. */
@Composable
private fun SupportRow() {
    val uri = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().clickable { runCatching { uri.openUri(SUPPORT_URL) } }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(36.dp)) {
            val dark = Color(0xFF1A1A1A)
            drawCircle(Color(0xFFFFDD00))
            val w = size.width
            // Tasse
            drawRoundRect(dark, topLeft = Offset(w * 0.26f, w * 0.42f), size = Size(w * 0.38f, w * 0.32f), cornerRadius = CornerRadius(w * 0.08f))
            // Henkel
            drawArc(dark, startAngle = -90f, sweepAngle = 180f, useCenter = false, topLeft = Offset(w * 0.56f, w * 0.47f),
                size = Size(w * 0.18f, w * 0.18f), style = Stroke(width = w * 0.07f, cap = StrokeCap.Round))
            // Dampf
            drawLine(dark, Offset(w * 0.38f, w * 0.24f), Offset(w * 0.38f, w * 0.34f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
            drawLine(dark, Offset(w * 0.50f, w * 0.22f), Offset(w * 0.50f, w * 0.34f), strokeWidth = w * 0.06f, cap = StrokeCap.Round)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.support_title), style = MaterialTheme.typography.titleSmall)
            Hint(stringResource(R.string.support_hint))
        }
    }
}

/** Sprache der Oberfläche: wie das System, Deutsch oder Englisch. */
@Composable
private fun LanguageRow(ctx: Context) {
    val tags = AppLanguage.OPTIONS
    val labels = listOf(stringResource(R.string.language_system), stringResource(R.string.language_de), stringResource(R.string.language_en))
    val current = tags.indexOf(AppLanguage.current(ctx)).coerceAtLeast(0)
    Column {
        ChoiceRow(stringResource(R.string.app_language), labels, current) { i -> ctx.findActivity()?.let { AppLanguage.set(it, tags[i]) } }
        Hint(stringResource(R.string.app_language_hint))
    }
}

/** Zeile mit Titel links und Auswahlknopf rechts. */
@Composable
private fun ChoiceRow(title: String, labels: List<String>, current: Int, onChoose: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(labels.getOrElse(current) { "" }) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                labels.forEachIndexed { i, label -> DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; onChoose(i) }) }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Auswahl der Stimme für wörtliche Rede: automatisch oder eine installierte Stimme dieser Sprache. */
@Composable
private fun DialogueVoiceRow(label: String, lang: String, s: AppSettings, installed: Set<String>, onChoose: (String, Int) -> Unit) {
    val options = VoicePacks.forLang(lang).filter { it.id in installed }.flatMap { p -> p.voices.map { p to it } }
    val auto = stringResource(R.string.dialogue_auto)
    val chosenPack = if (lang == "de") s.dialoguePackDe else s.dialoguePackEn
    val chosenSid = if (lang == "de") s.dialogueSidDe else s.dialogueSidEn
    val current = options.firstOrNull { it.first.id == chosenPack && it.second.sid == chosenSid }
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { menu = true }) {
                Text(current?.let { "${it.first.name} · ${voiceName(it.second)}" } ?: auto, maxLines = 1)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(auto) }, onClick = { menu = false; onChoose("", 0) })
                options.forEach { (p, v) ->
                    DropdownMenuItem(text = { Text("${p.name} · ${voiceName(v)}") }, onClick = { menu = false; onChoose(p.id, v.sid) })
                }
            }
        }
    }
}

@Composable
private fun PackCard(
    pack: VoicePack, installed: Boolean, selected: Boolean, sid: Int, download: DownloadState,
    blends: List<Blend>, blendId: String,
    onDownload: () -> Unit, onDelete: () -> Unit, onSelect: () -> Unit, onVoice: (Int) -> Unit,
    onBlend: (Blend) -> Unit, onCreateBlend: (String, Int, Int, Float) -> Unit, onDeleteBlend: (Blend) -> Unit,
) {
    var blendDialog by remember { mutableStateOf(false) }
    val running = download as? DownloadState.Running
    val failed = download as? DownloadState.Failed
    if (blendDialog) BlendDialog(pack, onDismiss = { blendDialog = false }, onCreate = onCreateBlend)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pack.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (pack.recommended) AssistChip(onClick = {}, label = { Text(stringResource(R.string.recommended)) })
            }
            Text(pluralStringResource(R.plurals.pack_summary, pack.voices.size, pack.sizeMb, pack.voices.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(pack.note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            when {
                running?.packId == pack.id -> {
                    LinearProgressIndicator(progress = { running.progress }, modifier = Modifier.fillMaxWidth())
                    Text(running.label, style = MaterialTheme.typography.bodySmall)
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    if (selected) {
                        var menu by remember { mutableStateOf(false) }
                        val voice = pack.voices.firstOrNull { it.sid == sid } ?: VoiceInfo("", sid)
                        val blend = blends.firstOrNull { it.id == blendId }
                        Box {
                            OutlinedButton(onClick = { menu = true }) {
                                Text(stringResource(R.string.voice_label, blend?.let { stringResource(R.string.blend_label, it.name) } ?: voiceName(voice)))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                pack.voices.forEach { v -> DropdownMenuItem(text = { Text(voiceName(v)) }, onClick = { onVoice(v.sid); menu = false }) }
                                blends.forEach { b ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.blend_label, b.name)) },
                                        trailingIcon = { IconButton(onClick = { menu = false; onDeleteBlend(b) }) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) } },
                                        onClick = { onBlend(b); menu = false },
                                    )
                                }
                            }
                        }
                        if (pack.engine == Engine.KOKORO && pack.voices.size > 1) {
                            TextButton(onClick = { blendDialog = true }) { Text(stringResource(R.string.blend_create)) }
                        }
                    } else Button(onClick = onSelect) { Text(stringResource(R.string.use)) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
                }
                else -> {
                    if (failed?.packId == pack.id) Text(failed.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onDownload, enabled = running == null && (pack.archiveUrl != null || pack.files.isNotEmpty())) {
                        Text(stringResource(if (pack.archiveUrl == null && pack.files.isEmpty()) R.string.install_manually else R.string.download))
                    }
                }
            }
        }
    }
}


/** Zwei Kokoro-Stimmen mischen: Stimme A, Stimme B, Anteil von A, Name. */
@Composable
private fun BlendDialog(pack: VoicePack, onDismiss: () -> Unit, onCreate: (String, Int, Int, Float) -> Unit) {
    var a by remember { mutableStateOf(pack.voices.first()) }
    var b by remember { mutableStateOf(pack.voices[1]) }
    var weight by remember { mutableStateOf(0.5f) }
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.blend_create)) },
        text = {
            Column {
                Text(stringResource(R.string.blend_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                VoicePick(stringResource(R.string.blend_voice_a), pack.voices, a) { a = it }
                VoicePick(stringResource(R.string.blend_voice_b), pack.voices, b) { b = it }
                Text(stringResource(R.string.blend_weight, (weight * 100).roundToInt(), 100 - (weight * 100).roundToInt()))
                Slider(value = weight, onValueChange = { weight = (it * 20).roundToInt() / 20f }, valueRange = 0.05f..0.95f)
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(stringResource(R.string.blend_name)) })
            }
        },
        confirmButton = {
            TextButton(enabled = a.sid != b.sid, onClick = {
                onDismiss()
                onCreate(name.ifBlank { "${a.name}+${b.name}" }, a.sid, b.sid, weight)
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun VoicePick(label: String, voices: List<VoiceInfo>, current: VoiceInfo, onPick: (VoiceInfo) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { menu = true }) { Text(voiceName(current)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                voices.forEach { v -> DropdownMenuItem(text = { Text(voiceName(v)) }, onClick = { onPick(v); menu = false }) }
            }
        }
    }
}

/** Sprachdaten der Texterkennung laden oder löschen. */
@Composable
private fun OcrLanguages(c: AppContainer) {
    val installed by c.ocr.installed.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var busy by remember { mutableStateOf<app.simplenarrator.input.Ocr.Language?>(null) }
    app.simplenarrator.input.Ocr.Language.entries.forEach { lang ->
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (lang == app.simplenarrator.input.Ocr.Language.DEU) R.string.voices_german else R.string.voices_english))
                Text("%.1f MB".format(lang.sizeMb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                busy == lang -> LinearProgressIndicator(modifier = Modifier.width(80.dp))
                lang in installed -> TextButton(onClick = { c.ocr.delete(lang) }) { Text(stringResource(R.string.delete)) }
                else -> TextButton(onClick = {
                    busy = lang
                    scope.launch {
                        runCatching { c.ocr.download(lang) }.onFailure { Toast.makeText(ctx, it.message ?: it.javaClass.simpleName, Toast.LENGTH_LONG).show() }
                        busy = null
                    }
                }) { Text(stringResource(R.string.download)) }
            }
        }
    }
}

/** Einstellungen zur System-Sprachausgabe. */
@Composable
private fun SystemTtsRow() {
    val ctx = LocalContext.current
    TextButton(onClick = {
        runCatching { ctx.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }) { Text(stringResource(R.string.sp_system_tts_open)) }
}

/** Fehlerhafte Regeln unter dem Eingabefeld anzeigen. */
@Composable
private fun LexiconErrors(raw: String) {
    val errors = remember(raw) { SpeechRules.parse(raw).errors }
    errors.take(5).forEach { e ->
        val res = when (e.kind) {
            RuleError.Kind.MISSING_SEPARATOR -> R.string.sp_rule_error_missing_separator
            RuleError.Kind.EMPTY_PATTERN -> R.string.sp_rule_error_empty_pattern
            RuleError.Kind.INVALID_REGEX -> R.string.sp_rule_error_invalid_regex
            RuleError.Kind.INVALID_REPLACEMENT -> R.string.sp_rule_error_invalid_replacement
        }
        Text(stringResource(res, e.line, e.detail), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
