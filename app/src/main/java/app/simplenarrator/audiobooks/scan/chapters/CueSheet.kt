package app.simplenarrator.audiobooks.scan.chapters

import app.simplenarrator.audiobooks.data.Mark

/** Liest Kapitelmarken aus einer Cue-Datei, gruppiert nach der Audiodatei, auf die sie sich beziehen. */
object CueSheet {

    private val BOM = 0xFEFF.toChar().toString()

    /** Schlüssel ist der Dateiname aus der FILE-Zeile (ohne Pfad); "" falls keiner angegeben ist. */
    fun parse(text: String): Map<String, List<Mark>> {
        val out = LinkedHashMap<String, MutableList<Mark>>()
        var file = ""
        var title: String? = null
        var inTrack = false
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix(BOM)
            val upper = line.uppercase()
            when {
                upper.startsWith("FILE ") -> {
                    file = unquote(line.substring(5).trim()).let { name ->
                        // Dateityp am Ende entfernen ("datei.mp3" MP3)
                        if (line.substring(5).trim().startsWith('"')) name else name.substringBeforeLast(' ')
                    }.replace('\\', '/').substringAfterLast('/')
                    inTrack = false
                }
                upper.startsWith("TRACK ") -> {
                    inTrack = true
                    title = null
                }
                upper.startsWith("TITLE ") && inTrack -> title = unquote(line.substring(6).trim())
                upper.startsWith("INDEX 01 ") && inTrack -> {
                    val ms = parseFrames(line.substring(9).trim()) ?: continue
                    out.getOrPut(file) { ArrayList() } += Mark(title.orEmpty(), ms)
                }
            }
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.startMs }.distinctBy { it.startMs } }
    }

    private fun unquote(s: String): String {
        if (s.startsWith('"')) {
            val end = s.indexOf('"', 1)
            return if (end > 0) s.substring(1, end) else s.substring(1)
        }
        return s
    }

    /** "mm:ss:ff" mit 75 Rahmen je Sekunde. */
    internal fun parseFrames(s: String): Long? {
        val p = s.split(':')
        if (p.size != 3) return null
        val m = p[0].toLongOrNull() ?: return null
        val sec = p[1].toLongOrNull() ?: return null
        val f = p[2].toLongOrNull() ?: return null
        return m * 60_000 + sec * 1000 + f * 1000 / 75
    }
}
