package app.simplenarrator.input

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import app.simplenarrator.util.await
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/**
 * Texterkennung für PDF-Seiten ohne Textebene (gescannte Bücher), offline mit Tesseract.
 * Die Sprachdaten (tessdata_fast) lädt die App erst, wenn sie gebraucht werden, und legt sie unter
 * files/tesseract/tessdata/ ab.
 */
class Ocr(private val context: Context, private val http: OkHttpClient) {

    /** Sprachdaten, die die App anbietet: Tesseract-Kürzel und ungefähre Größe. */
    enum class Language(val code: String, val sizeMb: Float) { DEU("deu", 1.5f), ENG("eng", 4.1f) }

    /** Tesseract erwartet den Ordner über tessdata/. */
    private val root = File(context.filesDir, "tesseract")
    private val tessdata = File(root, "tessdata")

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<Set<Language>> = _installed

    private fun scan(): Set<Language> = Language.entries.filter { File(tessdata, "${it.code}.traineddata").let { f -> f.isFile && f.length() > 0 } }.toSet()

    suspend fun download(lang: Language, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        tessdata.mkdirs()
        val dest = File(tessdata, "${lang.code}.traineddata")
        val tmp = File(tessdata, "${lang.code}.part")
        val resp = http.newCall(Request.Builder().url("$BASE/${lang.code}.traineddata").build()).await()
        resp.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val body = it.body ?: throw IOException("Empty response")
            val total = body.contentLength()
            var done = 0L
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
        }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
        _installed.value = scan()
    }

    fun delete(lang: Language) {
        File(tessdata, "${lang.code}.traineddata").delete()
        _installed.value = scan()
    }

    /**
     * Erkennt den Text der Seiten [pages] (Index ab 0) eines PDFs. [onPage] meldet den Fortschritt.
     * Mit Deutsch und Englisch zugleich (deu+eng), wenn beide geladen sind.
     */
    suspend fun recognize(pdf: File, pages: List<Int>, onPage: suspend (done: Int, total: Int) -> Unit): Map<Int, String> =
        withContext(Dispatchers.Default) {
            val langs = _installed.value.sortedBy { it.ordinal }.joinToString("+") { it.code }
            if (langs.isEmpty()) throw IOException("No OCR language data")
            val result = HashMap<Int, String>()
            val tess = TessBaseAPI()
            try {
                if (!tess.init(root.absolutePath, langs)) throw IOException("Tesseract init failed")
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { renderer ->
                        pages.forEachIndexed { i, index ->
                            currentCoroutineContext().ensureActive()
                            if (index in 0 until renderer.pageCount) {
                                val bitmap = renderer.openPage(index).use { page ->
                                    // Etwa 220 dpi bei A4: genug für Tesseract, noch klein genug für den Speicher
                                    val scale = TARGET_WIDTH.toFloat() / page.width
                                    val w = TARGET_WIDTH
                                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                                    Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                                        bmp.eraseColor(Color.WHITE)
                                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    }
                                }
                                tess.setImage(bitmap)
                                result[index] = tess.utF8Text.orEmpty()
                                bitmap.recycle()
                            }
                            onPage(i + 1, pages.size)
                        }
                    }
                }
            } finally {
                tess.recycle()
            }
            result
        }

    companion object {
        private const val BASE = "https://github.com/tesseract-ocr/tessdata_fast/raw/main"
        private const val TARGET_WIDTH = 1800
    }
}
