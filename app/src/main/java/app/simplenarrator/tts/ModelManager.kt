package app.simplenarrator.tts

import android.content.Context
import app.simplenarrator.R
import app.simplenarrator.util.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.util.concurrent.TimeUnit

/** Aufgelöste Dateien eines installierten Pakets (absolute Pfade). */
class ModelFiles(
    val model: String = "",
    val voices: String = "",
    val tokens: String = "",
    val espeakData: String = "",
    val lexicon: String = "",
    /** Nur Supertonic: duration_predictor, text_encoder, vector_estimator, vocoder, tts_json, unicode_indexer. */
    val supertonic: Map<String, String> = emptyMap(),
)

sealed class DownloadState {
    data object Idle : DownloadState()
    data class Running(val packId: String, val progress: Float, val label: String) : DownloadState()
    data class Failed(val packId: String, val message: String) : DownloadState()
}

/**
 * Verwaltet Modellpakete unter <external files>/models/. Externer App-Speicher, damit man
 * Pakete auch per USB/Dateimanager hineinkopieren kann (Android/data/<Paketname>/files/models/).
 */
class ModelManager(private val context: Context) {
    val modelsDir: File = File(context.getExternalFilesDir(null) ?: context.filesDir, "models").apply { mkdirs() }

    private val _installed = MutableStateFlow<Set<String>>(emptySet())
    val installed: StateFlow<Set<String>> = _installed

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    init { refresh() }

    fun refresh() {
        // Manuell hineinkopierte kokoro-onnx-Exporte (model.onnx + voices.npz) nachträglich aufbereiten
        VoicePacks.all.forEach { p -> runCatching { prepareIfNeeded(p) } }
        _installed.value = VoicePacks.all.filter { isInstalled(it) }.map { it.id }.toSet()
    }

    private fun prepareIfNeeded(pack: VoicePack) {
        val npzName = pack.convertNpz ?: return
        val d = packDir(pack)
        val ready = File(d, "voices.bin").isFile && File(d, "tokens.txt").isFile && File(d, ".sherpa-ready").isFile
        if (!ready && File(d, "model.onnx").isFile && File(d, npzName).isFile) KokoroConvert.prepare(d, npzName, pack.espeakLang)
    }

    fun packDir(pack: VoicePack) = File(modelsDir, pack.dirName)

    fun isInstalled(pack: VoicePack): Boolean = missingFiles(pack).isEmpty()

    /** Hauptmodell: model.onnx, sonst die größte .onnx-Datei (Piper heißt etwa de_DE-thorsten-medium.onnx). */
    private fun mainOnnx(d: File): File? =
        File(d, "model.onnx").takeIf { it.isFile }
            ?: d.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }?.maxByOrNull { it.length() }

    private fun supertonicFiles(d: File): Map<String, File?> {
        val onnx = d.listFiles { f -> f.isFile && f.name.endsWith(".onnx") }.orEmpty()
        fun part(prefix: String) = onnx.firstOrNull { it.name.startsWith(prefix) }
        return mapOf(
            "duration_predictor" to part("duration_predictor"),
            "text_encoder" to part("text_encoder"),
            "vector_estimator" to part("vector_estimator"),
            "vocoder" to part("vocoder"),
            "tts_json" to File(d, "tts.json").takeIf { it.isFile },
            "unicode_indexer" to File(d, "unicode_indexer.bin").takeIf { it.isFile },
            "voice_style" to File(d, "voice.bin").takeIf { it.isFile },
        )
    }

    private fun missingFiles(pack: VoicePack): List<String> {
        val d = packDir(pack)
        if (pack.engine == Engine.SUPERTONIC) return supertonicFiles(d).filterValues { it == null }.keys.toList()
        val missing = mutableListOf<String>()
        if (mainOnnx(d) == null) missing += "*.onnx"
        if (!File(d, "tokens.txt").isFile) missing += "tokens.txt"
        if (pack.engine != Engine.VITS && !File(d, "voices.bin").isFile) missing += "voices.bin"
        return missing
    }

    /** espeak-ng-data aus dem eigenen Paket oder aus irgendeinem installierten Paket. */
    fun espeakDataDir(pack: VoicePack): File? {
        File(packDir(pack), "espeak-ng-data").takeIf { it.isDirectory }?.let { return it }
        modelsDir.listFiles()?.forEach { d -> File(d, "espeak-ng-data").takeIf { it.isDirectory }?.let { return it } }
        return null
    }

    /** Dateien eines installierten Pakets; [voicesOverride] ersetzt voices.bin (gemischte Kokoro-Stimmen). */
    fun resolve(pack: VoicePack, voicesOverride: File? = null): ModelFiles? {
        if (!isInstalled(pack)) return null
        val d = packDir(pack)
        if (pack.engine == Engine.SUPERTONIC) {
            val parts = supertonicFiles(d).mapValues { it.value!!.absolutePath }
            return ModelFiles(voices = parts.getValue("voice_style"), supertonic = parts)
        }
        val espeak = espeakDataDir(pack) ?: return null
        // Nur Lexika der eigenen Sprache (z. B. lexicon-us-en.txt); lexicon-zh.txt bräuchte zusätzlich dict/ (jieba)
        val lang = pack.langs.first()
        val lexicon = if (pack.engine != Engine.KOKORO) "" else
            d.listFiles { f -> f.name.startsWith("lexicon") && f.name.endsWith(".txt") && f.name.contains("-$lang") }
                ?.joinToString(",") { it.absolutePath }.orEmpty()
        return ModelFiles(
            model = mainOnnx(d)!!.absolutePath,
            voices = if (pack.engine == Engine.VITS) "" else (voicesOverride ?: File(d, "voices.bin")).absolutePath,
            tokens = File(d, "tokens.txt").absolutePath,
            espeakData = espeak.absolutePath,
            lexicon = lexicon,
        )
    }

    suspend fun download(pack: VoicePack) = withContext(Dispatchers.IO) {
        val res = AppLanguage.wrap(context)
        try {
            _download.value = DownloadState.Running(pack.id, 0f, res.getString(R.string.download_connecting))
            val dir = packDir(pack).apply { mkdirs() }
            when {
                pack.archiveUrl != null -> {
                    val tmp = File(context.cacheDir, "${pack.dirName}.tar.bz2")
                    fetch(pack.archiveUrl, tmp) { p -> _download.value = DownloadState.Running(pack.id, p * 0.85f, res.getString(R.string.download_running)) }
                    _download.value = DownloadState.Running(pack.id, 0.9f, res.getString(R.string.download_extracting))
                    extractTarBz2(tmp, modelsDir, pack.dirName)
                    tmp.delete()
                }
                pack.files.isNotEmpty() -> {
                    pack.files.forEachIndexed { i, f ->
                        val dest = File(dir, f.dest)
                        if (dest.isFile && dest.length() > 0) return@forEachIndexed // bereits vorhanden
                        fetch(f.url, dest) { p ->
                            _download.value = DownloadState.Running(pack.id, (i + p) / pack.files.size * 0.9f, res.getString(R.string.download_file, f.dest))
                        }
                    }
                    if (pack.convertNpz != null) {
                        _download.value = DownloadState.Running(pack.id, 0.95f, res.getString(R.string.download_preparing))
                        KokoroConvert.prepare(dir, pack.convertNpz, pack.espeakLang)
                    }
                }
                else -> error(res.getString(R.string.pack_manual_only, res.getString(pack.note)))
            }
            if (!isInstalled(pack)) error(res.getString(R.string.pack_incomplete, missingFiles(pack).joinToString(", ")))
            if (pack.engine != Engine.SUPERTONIC && espeakDataDir(pack) == null) error(res.getString(R.string.espeak_missing))
            _download.value = DownloadState.Idle
        } catch (e: Exception) {
            _download.value = DownloadState.Failed(pack.id, e.message ?: e.toString())
        } finally {
            refresh()
        }
    }

    fun delete(pack: VoicePack) { packDir(pack).deleteRecursively(); refresh() }

    private fun fetch(url: String, dest: File, onProgress: (Float) -> Unit) {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        if (!resp.isSuccessful) { resp.close(); error("HTTP ${resp.code} for $url") }
        val body = resp.body ?: error("Empty response from $url")
        val total = body.contentLength()
        var done = 0L
        val tmp = File(dest.absolutePath + ".part")
        body.byteStream().use { input ->
            tmp.outputStream().buffered().use { out ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    out.write(buf, 0, n); done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                }
            }
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    }

    private fun extractTarBz2(archive: File, targetRoot: File, expectedDir: String) {
        TarArchiveInputStream(BZip2CompressorInputStream(archive.inputStream().buffered())).use { tar ->
            var entry = tar.nextEntry
            while (entry != null) {
                val name = entry.name.removePrefix("./")
                // sherpa-onnx-Archive haben genau einen obersten Ordner, der nicht immer wie die Datei heißt.
                // Er wird durch <expectedDir> ersetzt; Dateien ohne Ordner landen direkt darin.
                val inner = if ('/' in name) name.substringAfter('/') else name
                if (inner.isEmpty()) { entry = tar.nextEntry; continue }
                val out = File(targetRoot, "$expectedDir/$inner")
                if (!out.canonicalPath.startsWith(targetRoot.canonicalPath)) error("Invalid path in archive: $name")
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().buffered().use { o -> tar.copyTo(o) }
                }
                entry = tar.nextEntry
            }
        }
    }
}
