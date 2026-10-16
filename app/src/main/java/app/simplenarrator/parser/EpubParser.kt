package app.simplenarrator.parser

import app.simplenarrator.data.Chapter
import app.simplenarrator.data.ParsedBook
import app.simplenarrator.data.RawParagraph
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/**
 * EPUB 2/3 ohne externe EPUB-Bibliothek: container.xml → OPF → Spine.
 * Kapiteltitel kommen aus nav.xhtml (EPUB 3) oder toc.ncx (EPUB 2), sonst aus der ersten Überschrift.
 * Zeigt das Inhaltsverzeichnis auf Sprungmarken innerhalb einer Datei, wird die Datei dort geteilt
 * (bei Project Gutenberg steht oft das ganze Buch in einer Datei). Vorspann und Lizenz von Gutenberg fallen weg.
 */
object EpubParser {
    private val BLOCK_TAGS = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "dd", "dt", "blockquote", "pre", "td", "th", "tr",
        "table", "ul", "ol", "dl", "section", "article", "aside", "header", "footer", "figure", "figcaption",
        "main", "body", "hr", "br", "address", "details", "summary",
    )
    private val SKIP_TAGS = setOf("script", "style", "head", "title", "svg", "math", "nav", "template", "img", "video", "audio")
    private val HEADING = Regex("h[1-6]")
    private val NOTE_MARK = Regex("^[\\d*†‡§\\[\\]()\\s]+$")
    /** Vorspann und Lizenztext von Project Gutenberg (neue und ältere Ausgaben). */
    private const val BOILERPLATE = ".pg-boilerplate, #pg-header, #pg-footer"

    fun parse(file: File, outDir: File): ParsedBook {
        ZipFile(file).use { zip ->
            fun read(path: String): String? = zip.getEntry(path)?.let { zip.getInputStream(it).bufferedReader().readText() }

            val container = Jsoup.parse(read("META-INF/container.xml") ?: error("EPUB without META-INF/container.xml"), "", Parser.xmlParser())
            val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("EPUB: package file (OPF) not found")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = Jsoup.parse(read(opfPath) ?: error("EPUB: package file (OPF) missing"), "", Parser.xmlParser())

            val title = opf.select("dc|title").firstOrNull()?.text()?.trim()
                ?: opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim() ?: ""
            val author = opf.select("dc|creator").firstOrNull()?.text()?.trim()
                ?: opf.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim() ?: ""

            data class Item(val href: String, val type: String, val props: String)
            val manifest = opf.select("manifest > item").associate {
                it.attr("id") to Item(it.attr("href"), it.attr("media-type"), it.attr("properties"))
            }
            val spine = opf.select("spine > itemref")
                .filter { !it.attr("linear").equals("no", ignoreCase = true) }
                .map { it.attr("idref") }

            // Cover
            val coverId = manifest.entries.firstOrNull { it.value.props.contains("cover-image") }?.key
                ?: opf.select("meta[name=cover]").firstOrNull()?.attr("content")
            val coverFile = coverId?.let { manifest[it] }?.let { item ->
                val path = resolvePath(opfDir, item.href)
                zip.getEntry(path)?.let { e ->
                    val f = File(outDir, "cover." + item.href.substringAfterLast('.', "jpg"))
                    zip.getInputStream(e).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    f.name
                }
            }

            // Inhaltsverzeichnis: Pfad → Titel, dazu Sprungmarken je Datei (Element-ID → Titel)
            val toc = mutableMapOf<String, String>()
            val anchors = mutableMapOf<String, MutableMap<String, String>>()
            fun addAnchor(path: String, href: String, label: String) {
                val id = URLDecoder.decode(href.substringAfter('#', ""), "UTF-8")
                if (id.isNotEmpty() && label.isNotEmpty()) anchors.getOrPut(path) { linkedMapOf() }.putIfAbsent(id, label)
            }
            val nav = manifest.entries.firstOrNull { it.value.props.contains("nav") }?.value
            if (nav != null) {
                val navPath = resolvePath(opfDir, nav.href)
                val navDir = navPath.substringBeforeLast('/', "")
                read(navPath)?.let { html ->
                    val doc = Jsoup.parse(html)
                    val tocNav = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") }
                    val links = tocNav?.select("a") ?: doc.select("nav a")
                    links.forEach { a ->
                        val href = a.attr("href")
                        if (href.isNotBlank() && !href.startsWith("#")) {
                            toc.putIfAbsent(resolvePath(navDir, href), a.text().trim())
                            // Nur das eigentliche Inhaltsverzeichnis teilt Dateien, keine Seitenliste
                            if (tocNav != null) addAnchor(resolvePath(navDir, href), href, a.text().trim())
                        }
                    }
                }
            }
            if (toc.isEmpty()) {
                val ncx = manifest.values.firstOrNull { it.type == "application/x-dtbncx+xml" }
                if (ncx != null) {
                    val ncxPath = resolvePath(opfDir, ncx.href)
                    val ncxDir = ncxPath.substringBeforeLast('/', "")
                    read(ncxPath)?.let { xml ->
                        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
                        doc.select("navPoint").forEach { np ->
                            val label = np.selectFirst("navLabel > text")?.text()?.trim() ?: return@forEach
                            val src = np.selectFirst("content")?.attr("src") ?: return@forEach
                            toc.putIfAbsent(resolvePath(ncxDir, src), label)
                            addAnchor(resolvePath(ncxDir, src), src, label)
                        }
                    }
                }
            }

            // Kapitel aus dem Spine
            val sample = StringBuilder()
            val rawChapters = mutableListOf<Pair<String?, List<RawParagraph>>>()
            for (id in spine) {
                val item = manifest[id] ?: continue
                if (!item.type.contains("html") && !item.type.contains("xml")) continue
                val path = resolvePath(opfDir, item.href)
                val html = read(path) ?: continue
                val body = Jsoup.parse(html).body()
                body.select(BOILERPLATE).remove()
                removePrintedToc(body)
                val marks = anchors[path].orEmpty().filterKeys { body.getElementById(it) != null }
                for ((mark, raws) in extractSections(body, marks.keys)) {
                    if (raws.isEmpty()) continue
                    val heading = mark?.let { marks[it] } ?: toc[path]
                        ?: body.select("h1, h2, h3").firstOrNull()?.text()?.trim()?.takeIf { it.isNotBlank() }
                    if (sample.length < 6000) raws.forEach { sample.append(it.text).append(' ') }
                    rawChapters += heading to raws
                }
            }

            val lang = TextPipeline.guessBookLanguage(sample.toString())
            var n = 0
            val chapters: List<Chapter> = rawChapters.mapNotNull { (heading, raws) ->
                n++
                val ch = TextPipeline.buildChapter(heading ?: FallbackTitles.section(lang, n), raws, lang)
                if (ch.sentenceCount == 0) null else ch
            }
            return ParsedBook(TextRepair.repair(title), TextRepair.repair(author), coverFile, chapters)
        }
    }

    /**
     * Läuft durch den DOM und schneidet an jeder Blockgrenze einen neuen Absatz ab.
     * Damit geht auch Text in <div>-Absätzen, direkt im <body> oder zwischen Blöcken nicht verloren.
     * An jedem Element, dessen ID in [marks] steht, beginnt ein neuer Abschnitt; der Text davor hat keine Marke.
     */
    private fun extractSections(body: Element, marks: Set<String>): List<Pair<String?, List<RawParagraph>>> {
        val sections = mutableListOf<Pair<String?, List<RawParagraph>>>()
        var mark: String? = null
        var out = mutableListOf<RawParagraph>()
        val sb = StringBuilder()
        var heading = false
        fun flush() {
            val t = sb.toString().replace(Regex("\\s+"), " ").trim()
            if (t.isNotEmpty()) out += RawParagraph(t, heading)
            sb.setLength(0)
            heading = false
        }
        fun walk(node: Node) {
            when (node) {
                is TextNode -> sb.append(node.wholeText)
                is Element -> {
                    val tag = node.normalName()
                    if (tag in SKIP_TAGS || node.hasAttr("hidden")) return
                    if (marks.isNotEmpty() && node.id() in marks) {
                        flush()
                        sections += mark to tidyBreaks(out)
                        mark = node.id()
                        out = mutableListOf()
                    }
                    val epubType = node.attr("epub:type")
                    if (epubType.contains("noteref") || epubType.contains("pagebreak")) return
                    if (tag == "sup" && NOTE_MARK.matches(node.text())) return // Fußnotenziffern
                    // Trennlinie zwischen Szenen: wird beim Vorlesen eine längere Pause (am Abschnittsrand fällt sie weg)
                    if (tag == "hr") {
                        flush()
                        if (out.isNotEmpty() && out.last().text != SCENE_BREAK) out += RawParagraph(SCENE_BREAK)
                        return
                    }
                    val block = tag in BLOCK_TAGS
                    if (block) flush()
                    if (HEADING.matches(tag)) heading = true
                    node.childNodes().forEach { walk(it) }
                    if (block) flush()
                }
            }
        }
        walk(body)
        flush()
        sections += mark to tidyBreaks(out)
        return sections
    }

    /** Text einer Trennlinie; [Segmenter.isSceneBreak] erkennt ihn wieder. */
    private const val SCENE_BREAK = "* * *"

    /** Trennlinien am Rand eines Abschnitts oder neben einer Überschrift trennen keine Szenen und fallen weg. */
    private fun tidyBreaks(out: List<RawParagraph>): List<RawParagraph> = out.filterIndexed { k, p ->
        p.text != SCENE_BREAK || (k > 0 && k < out.lastIndex && !out[k - 1].isHeading && !out[k + 1].isHeading &&
            out[k + 1].text != SCENE_BREAK)
    }

    private val TOC_TITLE = Regex("""^(?:table of )?contents\.?$|^inhalt(?:sverzeichnis)?\.?$""", RegexOption.IGNORE_CASE)
    private val TOC_CLASS = Regex("""^(?:toc|toc[-_].*|.*[-_]toc|contents?)$""", RegexOption.IGNORE_CASE)

    /** Anteil des Texts, der in Links auf Stellen im Buch steht (keine Webadressen). */
    private fun internalLinkShare(e: Element): Double {
        val text = e.text().trim()
        if (text.isEmpty()) return 0.0
        val linked = e.select("a[href]")
            .filter { a -> a.attr("href").let { !it.contains("://") && !it.startsWith("mailto:") } }
            .sumOf { it.text().trim().length }
        return linked.toDouble() / text.length
    }

    private fun tocEntry(e: Element): Boolean {
        val text = e.text().trim()
        if (text.isEmpty() || text.length > 200) return false
        val marked = (e.classNames() + e.id()).any { TOC_CLASS.matches(it) }
        return marked || internalLinkShare(e) >= 0.8
    }

    /**
     * Gedrucktes Inhaltsverzeichnis im Buchtext entfernen: mindestens drei aufeinanderfolgende Absätze, Listenpunkte
     * oder Tabellenzeilen, die fast nur aus Links ins Buch bestehen, samt einer Überschrift "Contents"/"Inhalt"
     * direkt davor. Die App hat ihr eigenes Kapitelverzeichnis; vorgelesen klänge es minutenlang "Chapter 1. Chapter 2."
     */
    private fun removePrintedToc(body: Element) {
        for (parent in body.allElements) {
            val kids = parent.children().toList()
            var i = 0
            while (i < kids.size) {
                if (!tocEntry(kids[i])) { i++; continue }
                var j = i
                while (j < kids.size && tocEntry(kids[j])) j++
                if (j - i >= 3) {
                    // Die Überschrift steht vor den Einträgen oder vor der Tabelle/Liste, die nur aus ihnen besteht
                    val runText = (i until j).sumOf { kids[it].text().trim().length }
                    var holder: Element = kids[i]
                    while (holder.previousElementSibling() == null && holder.parent() != null && holder.parent() !== body &&
                        holder.parent()!!.text().trim().length <= runText + 10
                    ) holder = holder.parent()!!
                    holder.previousElementSibling()?.takeIf { TOC_TITLE.matches(it.text().trim()) }?.remove()
                    for (k in i until j) kids[k].remove()
                }
                i = j
            }
        }
    }

    private fun resolvePath(baseDir: String, href: String): String {
        val clean = URLDecoder.decode(href.substringBefore('#').substringBefore('?'), "UTF-8")
        val full = if (baseDir.isEmpty() || clean.startsWith("/")) clean.removePrefix("/") else "$baseDir/$clean"
        val stack = ArrayDeque<String>()
        for (part in full.split('/')) when (part) {
            "", "." -> {}
            ".." -> stack.removeLastOrNull()
            else -> stack.addLast(part)
        }
        return stack.joinToString("/")
    }
}
