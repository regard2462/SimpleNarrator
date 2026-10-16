package app.simplenarrator.input

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/** Was eine andere App geteilt oder zum Öffnen übergeben hat. */
sealed class Incoming {
    /** Datei (content:// oder file://), weiter mit BookRepository.importFromUri. */
    data class FileUri(val uri: String) : Incoming()

    /** Freier Text, weiter mit TxtParser.parseText (subject als Titelvorschlag). */
    data class Text(val text: String, val subject: String?) : Incoming()

    /** Webseite, weiter mit WebArticle (subject ist meist der Seitentitel). */
    data class Url(val url: String, val subject: String?) : Incoming()
}

object IncomingShare {
    /** Text neben der Adresse, der noch als Titel durchgeht (viele Browser teilen "Titel https://…"). */
    private const val MAX_TITLE_LEN = 200
    private const val MAX_TITLE_LINES = 3

    private val URL = Regex("(?i)\\bhttps?://[^\\s<>\"'«»“”„‘’]+")
    private val BARE_DOMAIN = Regex("(?i)^www\\.[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$")
    private val TRAILING = ".,;:!?'\"»›”’)]}"
    private val TITLE_TRIM = charArrayOf(' ', '\n', '\r', '\t', '-', '–', '—', '|', ':', '·', '•', '"', '„', '“', '”', '«', '»', '(', ')', '[', ']', '.', ',', ';')

    /**
     * Reine Entscheidung ohne Android-Klassen:
     * - streamUri (EXTRA_STREAM) → Datei
     * - dataUri (ACTION_VIEW): http(s) → Url, sonst Datei
     * - Text, der nur aus einer Adresse besteht oder aus einer Adresse mit kurzem Titel → Url
     * - jeder andere Text, auch längerer Text mit Adresse darin → Text
     * Liefert null, wenn nichts Brauchbares übergeben wurde.
     */
    fun classify(text: String?, subject: String?, streamUri: String?, dataUri: String?): Incoming? {
        val subj = subject?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        streamUri?.trim()?.takeIf { it.isNotEmpty() }?.let { return Incoming.FileUri(it) }
        dataUri?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
            return if (isWebUrl(d)) Incoming.Url(d, subj) else Incoming.FileUri(d)
        }
        val t = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null

        val matches = URL.findAll(t).toList()
        if (matches.size == 1) {
            val m = matches[0]
            val url = trimUrl(m.value)
            val rest = (t.substring(0, m.range.first) + " " + t.substring(m.range.first + url.length))
                .trim(*TITLE_TRIM)
            if (rest.length <= MAX_TITLE_LEN && rest.lines().size <= MAX_TITLE_LINES) {
                val title = subj ?: rest.replace(Regex("\\s+"), " ").takeIf { it.isNotEmpty() }
                return Incoming.Url(url, title?.takeUnless { it == url })
            }
        }
        if (matches.isEmpty() && BARE_DOMAIN.matches(t)) return Incoming.Url("https://" + trimUrl(t), subj)
        return Incoming.Text(t, subj)
    }

    /** Webadresse aus einem Text, wenn der Text im Wesentlichen nur eine Adresse ist (z. B. Zwischenablage). */
    fun urlFrom(text: String?): String? = (classify(text, null, null, null) as? Incoming.Url)?.url

    private fun isWebUrl(s: String) = s.startsWith("http://", ignoreCase = true) || s.startsWith("https://", ignoreCase = true)

    /** Satzzeichen am Ende abschneiden; ")" bleibt, wenn die Adresse selbst "(" enthält (Wikipedia). */
    private fun trimUrl(raw: String): String {
        var u = raw
        while (u.isNotEmpty() && u.last() in TRAILING) {
            if (u.last() == ')' && u.count { it == '(' } >= u.count { it == ')' }) break
            u = u.dropLast(1)
        }
        return u
    }

    // ------------------------------------------------------------ Android-Teil

    /**
     * Intent lesen: ACTION_SEND (EXTRA_STREAM, EXTRA_TEXT, EXTRA_SUBJECT, ersatzweise clipData),
     * ACTION_VIEW (Daten-URI) und ACTION_PROCESS_TEXT (Textauswahl "Vorlesen").
     */
    fun fromIntent(intent: Intent?): Incoming? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                val clip = intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: clip?.uri
                classify(
                    text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString() ?: clip?.text?.toString(),
                    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: intent.getStringExtra(Intent.EXTRA_TITLE),
                    streamUri = stream?.toString(),
                    dataUri = null,
                )
            }
            Intent.ACTION_VIEW -> classify(null, null, null, intent.dataString)
            Intent.ACTION_PROCESS_TEXT -> classify(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString(), null, null, null)
            else -> null
        }
    }
}
