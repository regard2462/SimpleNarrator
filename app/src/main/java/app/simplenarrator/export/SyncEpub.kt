package app.simplenarrator.export

import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ein Kapitel für das Mitlese-EPUB. Jede innere Liste ist ein Absatz, jeder TextCue ein Satz mit Start und Ende
 * innerhalb von [audio]. Mehrere Kapitel dürfen dieselbe Datei nutzen (ganzes Buch in einer Datei);
 * sie wird dann nur einmal ins EPUB gelegt.
 */
class SyncChapter(
    val title: String,
    val paragraphs: List<List<TextCue>>,
    val audio: File,
    val audioMime: String,
)

/**
 * EPUB 3 mit Media Overlays: Leseprogramme wie Thorium spielen den Ton ab und heben den gerade
 * gesprochenen Satz hervor.
 *
 * Aufbau: mimetype (zuerst, unkomprimiert), META-INF/container.xml, OEBPS/content.opf, nav.xhtml, style.css,
 * je Kapitel chapX.xhtml (jeder Satz in <span id="cX-sY">) und chapX.smil, Tondateien unter OEBPS/audio/.
 * Ton und Titelbild werden unkomprimiert gespeichert (bereits komprimiert, so können Leser direkt darin springen).
 *
 * Ist der erste Absatz eines Kapitels gleich dem Kapiteltitel (vorgelesene Überschrift), wird er zur h1
 * und dort mit hervorgehoben, statt den Titel doppelt zu zeigen.
 */
object SyncEpub {
    const val ACTIVE_CLASS = "-epub-media-overlay-active"

    /** Überschrift des Inhaltsverzeichnisses; Inhalt des Buchs, daher in der Buchsprache. */
    private fun tocTitle(language: String) = if (language.lowercase(Locale.ROOT).startsWith("de")) "Inhalt" else "Contents"

    private const val CSS = """body { line-height: 1.5; }
h1 { font-size: 1.4em; margin: 1em 0; }
p { margin: 0 0 0.8em 0; }
.$ACTIVE_CLASS { background-color: #ffe066; color: #000000; border-radius: 0.15em; }
"""

    private class Sentence(val id: String, val cue: TextCue)

    /**
     * Schreibt das EPUB nach [out] (über eine temporäre Datei, dann in einem Schritt ersetzt).
     * [identifier] ergibt eine feste urn:uuid (z. B. die Buch-ID der App), damit Leser einen erneuten Export
     * als dasselbe Buch erkennen; ohne Angabe entsteht eine zufällige.
     */
    fun write(
        out: File,
        meta: AudioMeta,
        language: String,
        chapters: List<SyncChapter>,
        identifier: String? = null,
        modifiedMillis: Long = System.currentTimeMillis(),
    ) {
        require(chapters.isNotEmpty()) { "No chapters" }
        val lang = language.ifBlank { "en" }
        val uuid = identifier?.let { UUID.nameUUIDFromBytes(it.toByteArray(Charsets.UTF_8)) } ?: UUID.randomUUID()
        val modified = Instant.ofEpochSecond(modifiedMillis / 1000).toString()

        // Tondateien einmalig ablegen
        val audioHref = LinkedHashMap<String, Pair<String, SyncChapter>>()
        for (c in chapters) {
            val key = c.audio.canonicalPath
            if (key !in audioHref) audioHref[key] = "audio/a${audioHref.size + 1}.${audioExt(c)}" to c
        }

        val coverType = meta.coverType()
        val coverHref = coverType?.let { if (it == AudioMeta.PNG) "cover.png" else "cover.jpg" }

        val chapterDocs = chapters.mapIndexed { i, c -> buildChapter(i + 1, c, lang, audioHref.getValue(c.audio.canonicalPath).first) }

        val tmp = TagFiles.tempFor(out)
        try {
            ZipOutputStream(tmp.outputStream().buffered(1 shl 16)).use { zip ->
                zip.putStored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII), modifiedMillis)
                zip.putText("META-INF/container.xml", CONTAINER, modifiedMillis)
                zip.putText("OEBPS/content.opf", opf(meta, lang, uuid, modified, chapterDocs, audioHref, coverType, coverHref), modifiedMillis)
                zip.putText("OEBPS/nav.xhtml", nav(lang, chapterDocs), modifiedMillis)
                zip.putText("OEBPS/style.css", CSS, modifiedMillis)
                for (d in chapterDocs) {
                    zip.putText("OEBPS/${d.xhtmlHref}", d.xhtml, modifiedMillis)
                    if (d.smil != null) zip.putText("OEBPS/${d.smilHref}", d.smil, modifiedMillis)
                }
                if (coverHref != null) zip.putStored("OEBPS/$coverHref", meta.cover!!, modifiedMillis)
                for ((key, v) in audioHref) {
                    // Ton nur aufnehmen, wenn ein Overlay ihn nutzt
                    if (chapterDocs.any { it.smil != null && it.audioHref == v.first }) zip.putStoredFile("OEBPS/${v.first}", File(key), modifiedMillis)
                }
            }
            TagFiles.replace(tmp, out)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private class ChapterDoc(
        val n: Int, val title: String, val xhtml: String, val smil: String?, val durationMs: Long, val audioHref: String,
    ) {
        val xhtmlHref get() = "chap$n.xhtml"
        val smilHref get() = "chap$n.smil"
    }

    private fun buildChapter(n: Int, c: SyncChapter, lang: String, audioHref: String): ChapterDoc {
        // Sätze durchnummerieren, leere auslassen
        var counter = 0
        val paras: List<List<Sentence>> = c.paragraphs.map { p ->
            p.filter { it.text.isNotBlank() }.map { Sentence("c$n-s${++counter}", it) }
        }.filter { it.isNotEmpty() }

        val first = paras.firstOrNull()
        val titleSpoken = first != null && norm(first.joinToString(" ") { it.cue.text }) == norm(c.title)
        val body = StringBuilder()
        if (titleSpoken) {
            body.append("<h1>").append(spans(first!!)).append("</h1>\n")
        } else {
            body.append("<h1>").append(xml(c.title.trim())).append("</h1>\n")
        }
        for (p in if (titleSpoken) paras.drop(1) else paras) body.append("<p>").append(spans(p)).append("</p>\n")

        val xhtml = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${xml(lang)}" lang="${xml(lang)}">
<head>
<meta charset="UTF-8"/>
<title>${xml(c.title.trim())}</title>
<link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
<section epub:type="chapter">
$body</section>
</body>
</html>
"""
        // Overlay: nur Sätze mit echter Dauer
        val pars = StringBuilder()
        var duration = 0L
        for (s in paras.flatten()) {
            val begin = s.cue.startMs.coerceAtLeast(0)
            val end = s.cue.endMs
            if (end <= begin) continue
            duration += end - begin
            pars.append("<par id=\"${s.id}-par\"><text src=\"chap$n.xhtml#${s.id}\"/>")
                .append("<audio src=\"").append(xml(audioHref)).append("\" clipBegin=\"").append(clock(begin))
                .append("\" clipEnd=\"").append(clock(end)).append("\"/></par>\n")
        }
        val smil = if (pars.isEmpty()) null else """<?xml version="1.0" encoding="UTF-8"?>
<smil xmlns="http://www.w3.org/ns/SMIL" xmlns:epub="http://www.idpf.org/2007/ops" version="3.0">
<body>
<seq id="seq$n" epub:textref="chap$n.xhtml" epub:type="chapter">
$pars</seq>
</body>
</smil>
"""
        return ChapterDoc(n, c.title.trim(), xhtml, smil, duration, audioHref)
    }

    private fun spans(p: List<Sentence>) = p.joinToString(" ") { "<span id=\"${it.id}\">${xml(it.cue.text.trim())}</span>" }

    private fun opf(
        meta: AudioMeta, lang: String, uuid: UUID, modified: String, docs: List<ChapterDoc>,
        audio: Map<String, Pair<String, SyncChapter>>, coverType: String?, coverHref: String?,
    ): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="bookid" xml:lang="${xml(lang)}">
<metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
<dc:identifier id="bookid">urn:uuid:$uuid</dc:identifier>
<dc:title>${xml(meta.title.trim().ifEmpty { "Untitled" })}</dc:title>
""")
        if (meta.author.isNotBlank()) sb.append("<dc:creator>${xml(meta.author.trim())}</dc:creator>\n")
        sb.append("<dc:language>${xml(lang)}</dc:language>\n")
        sb.append("<meta property=\"dcterms:modified\">$modified</meta>\n")
        var total = 0L
        for (d in docs) if (d.smil != null) {
            sb.append("<meta property=\"media:duration\" refines=\"#mo${d.n}\">${duration(d.durationMs)}</meta>\n")
            total += d.durationMs
        }
        if (docs.any { it.smil != null }) {
            sb.append("<meta property=\"media:duration\">${duration(total)}</meta>\n")
            sb.append("<meta property=\"media:active-class\">$ACTIVE_CLASS</meta>\n")
        }
        if (coverHref != null) sb.append("<meta name=\"cover\" content=\"cover-image\"/>\n")
        sb.append("</metadata>\n<manifest>\n")
        sb.append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
        sb.append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
        if (coverHref != null) sb.append("<item id=\"cover-image\" href=\"$coverHref\" media-type=\"$coverType\" properties=\"cover-image\"/>\n")
        for (d in docs) {
            val mo = if (d.smil != null) " media-overlay=\"mo${d.n}\"" else ""
            sb.append("<item id=\"c${d.n}\" href=\"${d.xhtmlHref}\" media-type=\"application/xhtml+xml\"$mo/>\n")
            if (d.smil != null) sb.append("<item id=\"mo${d.n}\" href=\"${d.smilHref}\" media-type=\"application/smil+xml\"/>\n")
        }
        var a = 0
        for ((_, v) in audio) {
            if (docs.none { it.smil != null && it.audioHref == v.first }) continue
            sb.append("<item id=\"a${++a}\" href=\"${xml(v.first)}\" media-type=\"${xml(v.second.audioMime)}\"/>\n")
        }
        sb.append("</manifest>\n<spine>\n")
        for (d in docs) sb.append("<itemref idref=\"c${d.n}\"/>\n")
        sb.append("</spine>\n</package>\n")
        return sb.toString()
    }

    private fun nav(lang: String, docs: List<ChapterDoc>): String {
        val toc = tocTitle(lang)
        val items = docs.joinToString("\n") { "<li><a href=\"${it.xhtmlHref}\">${xml(it.title.ifEmpty { "${it.n}" })}</a></li>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops" xml:lang="${xml(lang)}" lang="${xml(lang)}">
<head>
<meta charset="UTF-8"/>
<title>${xml(toc)}</title>
</head>
<body>
<nav epub:type="toc" id="toc">
<h1>${xml(toc)}</h1>
<ol>
$items
</ol>
</nav>
</body>
</html>
"""
    }

    private const val CONTAINER = """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
<rootfiles>
<rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
</rootfiles>
</container>
"""

    private fun audioExt(c: SyncChapter): String = when (c.audioMime.lowercase(Locale.ROOT)) {
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        else -> c.audio.extension.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
    }

    /** SMIL-Zeitangabe in Sekunden, z. B. "12.345s". */
    internal fun clock(ms: Long): String = String.format(Locale.ROOT, "%d.%03ds", ms / 1000, ms % 1000)

    /** Volle Uhrzeitangabe für media:duration, z. B. "1:02:03.456". */
    internal fun duration(ms: Long): String =
        String.format(Locale.ROOT, "%d:%02d:%02d.%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000)

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)

    /** Maskiert für XML-Text und -Attribute und entfernt in XML verbotene Zeichen. */
    internal fun xml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '&' -> sb.append("&amp;")
                c == '<' -> sb.append("&lt;")
                c == '>' -> sb.append("&gt;")
                c == '"' -> sb.append("&quot;")
                c == '\'' -> sb.append("&apos;")
                c == '\t' || c == '\n' || c == '\r' -> sb.append(c)
                c < ' ' || c == '￾' || c == '￿' -> {}
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> { sb.append(c).append(s[i + 1]); i++ }
                Character.isSurrogate(c) -> {}
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------ Zip

    private fun ZipOutputStream.putText(name: String, text: String, time: Long) {
        val e = ZipEntry(name)
        e.method = ZipEntry.DEFLATED
        e.time = time
        putNextEntry(e)
        write(text.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun ZipOutputStream.putStored(name: String, data: ByteArray, time: Long) {
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.time = time
        e.size = data.size.toLong()
        e.compressedSize = data.size.toLong()
        e.crc = CRC32().apply { update(data) }.value
        putNextEntry(e)
        write(data)
        closeEntry()
    }

    /** Große Datei unkomprimiert: Prüfsumme in einem ersten Durchgang, dann kopieren. */
    private fun ZipOutputStream.putStoredFile(name: String, file: File, time: Long) {
        val crc = CRC32()
        val buf = ByteArray(1 shl 16)
        file.inputStream().use { input ->
            while (true) { val n = input.read(buf); if (n <= 0) break; crc.update(buf, 0, n) }
        }
        val e = ZipEntry(name)
        e.method = ZipEntry.STORED
        e.time = time
        e.size = file.length()
        e.compressedSize = file.length()
        e.crc = crc.value
        putNextEntry(e)
        file.inputStream().use { it.copyTo(this as OutputStream, buf.size) }
        closeEntry()
    }
}
