package app.simplenarrator.parser

import app.simplenarrator.data.Chapter
import app.simplenarrator.data.ParsedBook
import app.simplenarrator.data.RawParagraph
import java.io.File

/**
 * Reintext / Markdown: Absätze durch Leerzeilen, Kapitel an Überschriften.
 *
 * Für Bücher aus Project Gutenberg: Vorspann und Lizenz fallen weg (Titel und Autor kommen aus dem Vorspann),
 * ein gedrucktes Inhaltsverzeichnis wird übersprungen, und Kapitel werden auch an "CHAPTER I." mit Titel in der
 * nächsten Zeile, an alleinstehenden Ziffern ("IV.") und an Großbuchstaben-Zeilen erkannt, sobald sich das Muster
 * wiederholt. Ideen dafür stammen aus dem Chunker von cedar-server (MIT).
 */
object TxtParser {
    /** Datei-Import: Ohne Titel im Gutenberg-Vorspann bleibt er leer, BookRepository nimmt dann den Dateinamen. */
    fun parse(file: File): ParsedBook {
        val text = file.readText()
        val book = parseText(text)
        return if (gutenbergHeader(text).first != null) book else book.copy(title = "")
    }

    /**
     * Text aus der Zwischenablage oder aus einer anderen App. Ohne Titel wird die erste Zeile genommen,
     * wenn sie kurz ist (höchstens 80 Zeichen), sonst die ersten Wörter.
     */
    fun parseText(text: String, title: String = ""): ParsedBook {
        val (pgTitle, pgAuthor) = gutenbergHeader(text)
        val normalized = stripGutenberg(text.replace("\r\n", "\n").replace('\r', '\n'))
        // Absätze trennen normalerweise Leerzeilen. Kopierter Webtext hat oft nur einfache Zeilenumbrüche
        // zwischen langen Absätzen; dann zählt jede Zeile als Absatz. Hart umbrochener Text bleibt zusammen.
        val byBlank = normalized.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val lines = normalized.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val blocks = removePrintedToc(
            if (byBlank.size <= 1 && lines.size > 1 && lines.sumOf { it.length } / lines.size > 100) lines else byBlank,
        )
        val lang = TextPipeline.guessBookLanguage(normalized)
        val headings = detectChapters(blocks)

        val chapters = mutableListOf<Chapter>()
        var chapterTitle = FallbackTitles.beginning(lang)
        var current = mutableListOf<RawParagraph>()
        fun flush() {
            if (current.isNotEmpty()) chapters += TextPipeline.buildChapter(chapterTitle, current, lang)
            current = mutableListOf()
        }
        for ((i, b) in blocks.withIndex()) {
            when (headings[i]) {
                Heading.CHAPTER -> {
                    flush()
                    chapterTitle = b.lines().joinToString(" ") { it.trim() }.trimStart('#', ' ')
                }
                // Untertitel direkt nach "CHAPTER I." gehört zum Kapitelnamen
                Heading.SUBTITLE -> if (current.isEmpty() && headings[i - 1] == Heading.CHAPTER) chapterTitle += " " + b.trim()
                else current += RawParagraph(b, isHeading = true)
                null -> current += RawParagraph(b)
            }
            if (current.size >= 400) { flush(); chapterTitle = FallbackTitles.continued(lang, chapterTitle) }
        }
        flush()

        // Ersatztitel: erste Zeile, wenn kurz genug, sonst die ersten Wörter bis etwa 60 Zeichen
        fun fallbackTitle(): String {
            val first = lines.firstOrNull()?.trimStart('#', ' ')?.trim().orEmpty()
            if (first.length <= 80) return first
            val sb = StringBuilder()
            for (w in first.split(Regex("\\s+"))) {
                if (sb.isNotEmpty() && sb.length + w.length + 1 > 60) break
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(w)
            }
            return sb.toString().take(80).trimEnd(',', ';', ':', '-', ' ') + " …"
        }
        val bookTitle = title.trim().ifBlank { pgTitle ?: fallbackTitle() }
        return ParsedBook(bookTitle, pgAuthor.orEmpty(), null, chapters.filter { it.sentenceCount > 0 })
    }

    // ---------------------------------------------------------------- Project Gutenberg

    private val PG_START = Regex("""\*{3}\s*START OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK[^*\n]*\*{3}""", RegexOption.IGNORE_CASE)
    private val PG_END = Regex("""\*{3}\s*END OF (?:THE|THIS) PROJECT GUTENBERG E-?BOOK[^*\n]*\*{3}""", RegexOption.IGNORE_CASE)
    private val PG_PRODUCED = Regex("""^\s*(?:Produced by|E-text prepared by)\b[^\n]*(?:\n[^\n]+)*""", RegexOption.IGNORE_CASE)

    /** Nur das Werk: ohne Vorspann bis "*** START OF …" und ohne Lizenz ab "*** END OF …". */
    internal fun stripGutenberg(text: String): String {
        val start = PG_START.find(text) ?: return text
        var body = text.substring(start.range.last + 1)
        PG_END.find(body)?.let { body = body.substring(0, it.range.first) }
        return body.replaceFirst(PG_PRODUCED, "").trim()
    }

    /** Titel und Autor aus dem Vorspann ("Title: …", "Author: …"), null ohne Gutenberg-Vorspann. */
    internal fun gutenbergHeader(text: String): Pair<String?, String?> {
        val start = PG_START.find(text) ?: return null to null
        val head = text.substring(0, start.range.first)
        fun field(name: String) = Regex("""^$name:\s*(.+)$""", setOf(RegexOption.MULTILINE, RegexOption.IGNORE_CASE))
            .find(head)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        return field("Title") to field("Author")
    }

    // ---------------------------------------------------------------- Kapitel

    enum class Heading { CHAPTER, SUBTITLE }

    private val MARKDOWN = Regex("""^#{1,3}\s+\S.*$""")

    /** Eindeutige Kapitelwörter, mit oder ohne Nummer. */
    private val EXPLICIT = Regex(
        """^(?:kapitel|chapter|prolog|prologue|epilog|epilogue|vorwort|nachwort|einleitung|preface|foreword|""" +
            """introduction|afterword|conclusion|appendix|anhang|teil|part)\b.{0,70}$""",
        RegexOption.IGNORE_CASE,
    )

    /** Mehrdeutige Wörter ("Buch", "Brief", "Act") zählen nur mit Nummer dahinter. */
    private val NUMBERED = Regex(
        """^(?:book|buch|letter|brief|act|akt|scene|szene|volume|band|canto|stave|gesang)\s+""" +
            """(?:[IVXLCDM]+|\d{1,4}|the\s+\w+|\w+(?:te[rns]?|st|nd|rd|th))\b.{0,70}$""",
        RegexOption.IGNORE_CASE,
    )
    private val NUMERAL = Regex("""^(?:[IVXLCDM]{1,8}|\d{1,3})\.?$""")
    private val NUM_TITLE = Regex("""^(?:[IVXLCDM]{1,8}|\d{1,3})\.\s+["'“‘„»(]?\p{Lu}.{0,60}$""")
    private val ALL_CAPS = Regex("""^[^\p{Ll}]{2,60}$""")

    private fun firstLine(b: String) = b.lineSequence().first().trim()

    /** Kurzer Block aus höchstens drei Zeilen, der als Überschrift in Frage kommt. */
    private fun short(b: String) = b.length <= 120 && b.lines().size <= 3

    private fun romanOrInt(s: String): Int? {
        val t = s.trim().trimEnd('.').uppercase()
        t.toIntOrNull()?.let { return it }
        val v = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        if (t.isEmpty() || t.any { it !in v }) return null
        var total = 0
        for (j in t.indices) {
            val x = v.getValue(t[j])
            total += if (j + 1 < t.length && v.getValue(t[j + 1]) > x) -x else x
        }
        return total
    }

    /**
     * Blockindex → Überschrift. Ausdrückliche Kapitelwörter und Markdown zählen immer; alleinstehende Ziffern
     * ab zwei Treffern, "IV. Titel" ab drei mit aufsteigenden Nummern, Großbuchstaben-Zeilen ab zwei (in einem
     * Buch mit Kapiteln als Untertitel) oder drei. Wäre am Ende ein großer Teil des Texts "Überschrift", war es
     * eine Liste: dann zählen nur die sicheren Treffer.
     */
    internal fun detectChapters(blocks: List<String>): Map<Int, Heading> {
        val sure = mutableMapOf<Int, Heading>()
        val numeral = mutableListOf<Int>()
        val numTitle = mutableListOf<Pair<Int, Int>>()
        val caps = mutableListOf<Int>()
        for ((i, b) in blocks.withIndex()) {
            if (!short(b)) continue
            val first = firstLine(b)
            when {
                MARKDOWN.matches(b) -> sure[i] = Heading.CHAPTER
                // Mehrzeilig nur als "CHAPTER I." mit Titel darunter, sonst wäre es ein kurzer Absatz
                (b.lines().size == 1 || first.length <= 30) && first.length <= 90 &&
                    (EXPLICIT.matches(first) || NUMBERED.matches(first)) -> sure[i] = Heading.CHAPTER
                b.lines().size == 1 && NUMERAL.matches(first) -> numeral += i
                b.lines().size == 1 && NUM_TITLE.matches(first) -> romanOrInt(first.substringBefore('.'))?.let { numTitle += i to it }
                b.lines().size == 1 && ALL_CAPS.matches(first) && first.count { it.isLetter() } >= maxOf(2, first.length / 2) -> caps += i
            }
        }
        val out = sure.toMutableMap()
        if (numeral.size >= 2) numeral.forEach { out[it] = Heading.CHAPTER }
        var sequence = false
        if (numTitle.size >= 3) {
            val values = numTitle.map { it.second }
            sequence = values.zipWithNext().count { (a, b) -> b >= a } >= 0.8 * (values.size - 1)
            if (sequence) numTitle.forEach { out[it.first] = Heading.CHAPTER }
        }
        val chaptered = sure.isNotEmpty() || numeral.size >= 2 || sequence
        if (caps.size >= (if (chaptered) 2 else 3)) caps.forEach { out[it] = if (chaptered) Heading.SUBTITLE else Heading.CHAPTER }
        val tooMany = out.size > 0.55 * blocks.size || out.size > maxOf(400.0, 0.08 * blocks.size)
        return if (tooMany) sure else out
    }

    // ---------------------------------------------------------------- gedrucktes Inhaltsverzeichnis

    private val TOC_MARKER = Regex(
        """^(?:(?:table of )?contents|inhalt|inhaltsverzeichnis|(?:list of )?illustrations|""" +
            """verzeichnis der abbildungen|abbildungen)[.:]?$""",
        RegexOption.IGNORE_CASE,
    )
    private val ILLUSTRATIONS = Regex("""^(?:list of )?illustrations|^(?:verzeichnis der )?abbildungen""", RegexOption.IGNORE_CASE)
    private val ENTRY_WORD = Regex(
        """\b(?:chapter|chap|letter|part|book|kapitel|teil|buch|brief)\b[.\s]*(?=[ivxlcdm]+\b|\d)""",
        RegexOption.IGNORE_CASE,
    )
    private val DOT_LEADER = Regex("""(?:\.\s?){3,}\s*\d{1,4}\b""")
    private val PAGE_REF = Regex("""^\S.{0,78}\s(?:[ivxlcdm]{2,8}|\d{1,3})\.?$""", RegexOption.IGNORE_CASE)

    private fun entryLike(line: String, illustrations: Boolean): Boolean {
        val l = line.trim()
        if (l.isEmpty() || l.length > 90) return false
        return EXPLICIT.matches(l) || NUMBERED.matches(l) || NUMERAL.matches(l) || NUM_TITLE.matches(l) ||
            ENTRY_WORD.containsMatchIn(l) || DOT_LEADER.containsMatchIn(l) || PAGE_REF.matches(l) || illustrations
    }

    /** Ein Block aus Einträgen: alle oder fast alle Zeilen sehen aus wie Verzeichniszeilen. */
    private fun entryBlock(b: String, illustrations: Boolean): Int {
        val ls = b.lines().filter { it.isNotBlank() }
        val hits = ls.count { entryLike(it, illustrations) }
        return if (ls.isNotEmpty() && hits >= maxOf(1, (ls.size * 7 + 9) / 10)) hits else 0
    }

    private fun norm(s: String) = s.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Ein ausdrücklich überschriebenes Inhalts- oder Abbildungsverzeichnis am Buchanfang entfernen. Vorsichtig:
     * Nur nach einer Zeile "Contents"/"Inhalt", nur Blöcke aus Verzeichniszeilen, und Schluss, sobald ein Block
     * einen schon gesehenen Eintrag wiederholt (das ist die erste echte Kapitelüberschrift) oder Fließtext kommt.
     * Eine Zeile "Contents" ohne Einträge danach bleibt stehen.
     */
    internal fun removePrintedToc(blocks: List<String>): List<String> {
        val out = blocks.toMutableList()
        var i = 0
        while (i < minOf(out.size, 150)) {
            val marker = out[i].trim()
            if (!(out[i].lines().size == 1 && TOC_MARKER.matches(marker))) { i++; continue }
            val illustrations = ILLUSTRATIONS.containsMatchIn(marker)
            var end = i
            var entries = 0
            var seen = ""
            var gap = 0
            var confirmed = false
            var j = i + 1
            while (j < minOf(out.size, i + 400)) {
                val b = out[j]
                val n = norm(b)
                if (entries > 0 && n.length >= 6 && seen.contains(n)) { confirmed = true; break }
                if (b.lines().size == 1 && TOC_MARKER.matches(b.trim())) { end = j; seen += " $n"; j++; continue }
                val hits = entryBlock(b, illustrations && b.length <= 90)
                if (hits > 0) {
                    entries += hits; end = j; gap = 0; seen += " $n"
                } else {
                    gap++
                    val nextIsEntry = out.getOrNull(j + 1)?.let { entryBlock(it, false) > 0 } ?: false
                    if (b.length > 120 || gap > 2 || !nextIsEntry) break
                    seen += " $n"
                }
                j++
            }
            // Drei Einträge reichen immer; zwei, wenn der Buchtext danach einen davon wiederholt
            if (entries >= 3 || (entries >= 2 && confirmed)) {
                repeat(end - i + 1) { out.removeAt(i) }
            } else i++
        }
        return out
    }
}
