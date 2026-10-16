package app.simplenarrator.parser

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import app.simplenarrator.data.Chapter
import app.simplenarrator.data.ParsedBook
import app.simplenarrator.data.RawParagraph
import java.io.File

/**
 * PDF → Text mit PDFBox. Kapitel aus den PDF-Lesezeichen (Outline); ohne Lesezeichen
 * werden Blöcke von 20 Seiten gebildet. Kopf-/Fußzeilen und Seitenzahlen werden entfernt.
 * Gescannte PDFs ohne Textebene liefern nichts – dafür wäre OCR nötig (siehe README).
 */
object PdfParser {
    private const val PAGES_PER_BLOCK = 20
    private const val MIN_LETTERS = 25

    /** Text je Seite, so wie er in der Textebene des PDFs steht. */
    fun pageTexts(context: Context, file: File): List<String> {
        PDFBoxResourceLoader.init(context.applicationContext)
        return PDDocument.load(file).use { doc -> pageTexts(doc) }
    }

    private fun pageTexts(doc: PDDocument): List<String> {
        val stripper = PDFTextStripper()
        return (1..doc.numberOfPages).map { p ->
            stripper.startPage = p
            stripper.endPage = p
            stripper.getText(doc)
        }
    }

    /** Seiten ohne nennenswerte Textebene (Index ab 0): gescannt, ein Fall für die Texterkennung. */
    fun scannedPages(texts: List<String>): List<Int> = texts.indices.filter { i -> texts[i].count { it.isLetter() } < MIN_LETTERS }

    /** Lohnt die Texterkennung? Ab einem Drittel gescannter Seiten fragt die App nach. */
    fun mostlyScanned(texts: List<String>): Boolean {
        val scanned = scannedPages(texts).size
        return scanned > 0 && scanned * 3 >= texts.size
    }

    /** [replacements]: erkannter Text für gescannte Seiten (Index ab 0) aus der Texterkennung. */
    fun parse(context: Context, file: File, replacements: Map<Int, String> = emptyMap()): ParsedBook {
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(file).use { doc ->
            val pageTexts = pageTexts(doc).mapIndexed { i, t -> replacements[i] ?: t }
            val cleaned = TextCleaner.cleanPdfPages(pageTexts)
            val info = doc.documentInformation
            val title = info?.title?.trim().orEmpty()
            val author = info?.author?.trim().orEmpty()
            val lang = TextPipeline.guessBookLanguage(cleaned.joinToString(" ").take(6000))

            val ranges = outlineRanges(doc, lang)
            val chapters = if (ranges.isNotEmpty()) {
                ranges.map { (t, r) -> TextPipeline.buildChapter(t, paragraphsOf(cleaned.slice(r)), lang) }
            } else {
                (0 until cleaned.size step PAGES_PER_BLOCK).map { start ->
                    val end = minOf(start + PAGES_PER_BLOCK, cleaned.size)
                    TextPipeline.buildChapter(FallbackTitles.pages(lang, start + 1, end), paragraphsOf(cleaned.subList(start, end)), lang)
                }
            }.filter { it.sentenceCount > 0 }
            return ParsedBook(title, author, null, chapters)
        }
    }

    /** Seiten zu Absätzen; ein Absatz darf über die Seitengrenze laufen. */
    internal fun paragraphsOf(pages: List<String>): List<RawParagraph> {
        val sb = StringBuilder()
        for (page in pages) {
            if (page.isBlank()) continue
            val text = page.trim()
            // Silbentrennung über den Seitenwechsel: "Ge-" | "schichte" → "Geschichte"
            if (sb.length > 1 && sb.last() == '-' && sb[sb.length - 2].isLetter() && text.first().isLowerCase()) {
                sb.setLength(sb.length - 1)
                sb.append(text)
                continue
            }
            if (sb.isNotEmpty()) sb.append(if (sb.last() in ".!?:\"»“”") "\n\n" else " ")
            sb.append(text)
        }
        return sb.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }.map { RawParagraph(it) }
    }

    private fun outlineRanges(doc: PDDocument, lang: String): List<Pair<String, IntRange>> = runCatching {
        val outline = doc.documentCatalog.documentOutline ?: return emptyList()
        val starts = mutableListOf<Pair<String, Int>>()
        var item = outline.firstChild
        while (item != null) {
            val page = runCatching { item.findDestinationPage(doc) }.getOrNull()
            val idx = page?.let { p -> doc.pages.indexOf(p) } ?: -1
            val t = item.title?.trim().orEmpty()
            if (idx >= 0 && t.isNotEmpty()) starts += t to idx
            item = item.nextSibling
        }
        if (starts.size < 2) return emptyList()
        val sorted = starts.sortedBy { it.second }
        val result = mutableListOf<Pair<String, IntRange>>()
        if (sorted.first().second > 0) result += FallbackTitles.beginning(lang) to (0 until sorted.first().second)
        for (i in sorted.indices) {
            val end = if (i + 1 < sorted.size) sorted[i + 1].second else doc.numberOfPages
            if (end > sorted[i].second) result += sorted[i].first to (sorted[i].second until end)
        }
        result
    }.getOrDefault(emptyList())
}
