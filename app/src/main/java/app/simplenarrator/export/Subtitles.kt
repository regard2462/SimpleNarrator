package app.simplenarrator.export

import java.util.Locale

/** Ein Textstück (meist ein Satz) mit Zeiten in Millisekunden ab Beginn der zugehörigen Audiodatei. */
data class TextCue(val text: String, val startMs: Long, val endMs: Long)

/**
 * Begleitdateien zum Mitlesen: SRT (Untertitel, z. B. VLC) und LRC (Liedtext-Format, viele Musik- und Hörbuchplayer).
 * Zeilenende ist \n. Leere Texte werden übersprungen.
 */
object Subtitles {

    fun srt(cues: List<TextCue>): String {
        val sb = StringBuilder()
        var index = 0
        for (c in cues) {
            // Eine Leerzeile beendet in SRT den Eintrag, daher Leerzeilen im Text entfernen
            val text = c.text.replace("\r\n", "\n").replace('\r', '\n')
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (text.isEmpty()) continue
            index++
            val start = c.startMs.coerceAtLeast(0)
            val end = c.endMs.coerceAtLeast(start)
            sb.append(index).append('\n')
            sb.append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
            sb.append(text).append("\n\n")
        }
        return sb.toString()
    }

    /** LRC mit [ti:] und [ar:]; nach dem letzten Eintrag folgt eine leere Zeile zu dessen Ende. */
    fun lrc(cues: List<TextCue>, title: String, author: String): String {
        val sb = StringBuilder()
        if (title.isNotBlank()) sb.append("[ti:").append(tagValue(title)).append("]\n")
        if (author.isNotBlank()) sb.append("[ar:").append(tagValue(author)).append("]\n")
        var lastEnd = -1L
        for (c in cues) {
            val text = c.text.replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val start = c.startMs.coerceAtLeast(0)
            sb.append(lrcTime(start)).append(text).append('\n')
            lastEnd = c.endMs.coerceAtLeast(start)
        }
        if (lastEnd >= 0) sb.append(lrcTime(lastEnd)).append('\n')
        return sb.toString()
    }

    /** hh:mm:ss,mmm */
    fun srtTime(ms: Long): String = String.format(
        Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000, (ms / 60_000) % 60, (ms / 1000) % 60, ms % 1000,
    )

    /** [mm:ss.xx], Minuten auch über 99 hinaus. */
    fun lrcTime(ms: Long): String = String.format(
        Locale.ROOT, "[%02d:%02d.%02d]", ms / 60_000, (ms / 1000) % 60, (ms % 1000) / 10,
    )

    private fun tagValue(s: String) = s.replace(Regex("\\s+"), " ").trim().replace('[', '(').replace(']', ')')
}
