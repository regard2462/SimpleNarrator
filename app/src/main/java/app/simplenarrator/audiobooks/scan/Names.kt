package app.simplenarrator.audiobooks.scan

/** Regeln für Titel, Autoren und Dateinamen. */
object Names {
    private val AUDIO = setOf("mp3", "m4a", "m4b", "aac", "ogg", "oga", "opus", "flac", "wav", "mka", "weba", "webm", "amr", "3gp")
    private val IMAGES = setOf("jpg", "jpeg", "png", "webp")

    fun ext(name: String): String = name.substringAfterLast('.', "").lowercase()
    fun isAudio(name: String): Boolean = ext(name) in AUDIO && !name.startsWith(".")
    fun isImage(name: String): Boolean = ext(name) in IMAGES && !name.startsWith(".")
    fun isCue(name: String): Boolean = ext(name) == "cue"
    fun isSingleFileBookFormat(name: String): Boolean = ext(name) == "m4b"

    private val DISC = Regex("""^(?:cd|disc|disk|dvd|seite|side)\s*[-_.#]?\s*\d+\b.*$|^\d{1,3}$""", RegexOption.IGNORE_CASE)

    /** Ordnernamen wie "CD 1", "Disc2", "CD3 - Titel" oder "01" stehen für Teile eines Buches. */
    fun isDiscFolder(name: String): Boolean = DISC.matches(name.trim())

    private val LEADING_NUMBER = Regex("""^\s*\d{1,4}(?:\s*[-_.)\]]\s*|\s+)""")
    private val ONLY_NUMBER = Regex("""^\s*\d{1,4}\s*$""")

    /** Lesbarer Titel aus einem Dateinamen; null, wenn nur eine Nummer übrig bleibt. */
    fun titleFromFileName(fileName: String): String? {
        var s = fileName.substringAfterLast('/')
        if (s.contains('.')) s = s.substringBeforeLast('.')
        s = s.replace('_', ' ').trim()
        if (ONLY_NUMBER.matches(s)) return null
        val stripped = s.replaceFirst(LEADING_NUMBER, "").trim()
        val result = if (stripped.isEmpty()) s else stripped
        return result.takeIf { it.isNotBlank() && !ONLY_NUMBER.matches(it) }
    }

    /** Titel eines Buches aus einem Ordner- oder Dateinamen. */
    fun titleFromName(name: String): String {
        var s = name
        if (isAudio(s)) s = s.substringBeforeLast('.')
        return s.replace('_', ' ').trim().ifEmpty { name }
    }

    private val DISC_SUFFIX = Regex(
        """\s*(?:[-,:]\s*|\(|\[)?\s*(?:cd|disc|disk|teil|part)\s*[-_.#]?\s*\d+(?:\s*(?:/|von|of)\s*\d+)?\s*[)\]]?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Entfernt Angaben wie " (CD 1)", " - Disc 2" oder " [CD 3/8]" am Ende eines Album-Tags. */
    fun stripDiscSuffix(album: String): String = album.replace(DISC_SUFFIX, "").trim().ifEmpty { album.trim() }

    /** "Autor - Titel" aufteilen. Liefert null, wenn kein Trenner vorhanden ist. */
    fun splitAuthorTitle(name: String): Pair<String, String>? {
        val idx = name.indexOf(" - ")
        if (idx <= 0) return null
        val a = name.substring(0, idx).trim()
        val t = name.substring(idx + 3).trim()
        if (a.isEmpty() || t.isEmpty()) return null
        // Eine Zahl links ist eher eine Nummer als ein Autor.
        if (a.all { it.isDigit() }) return null
        return a to t
    }

    /** Jahreszahl aus einem Tag; Standardwerte leerer Felder (1904, 1970) und Unsinn ergeben null. */
    fun plausibleYear(raw: String?): String? {
        val y = raw?.trim()?.take(4)?.toIntOrNull() ?: return null
        if (y == 1904 || y == 1970 || y < 1000 || y > java.time.Year.now().value + 1) return null
        return y.toString()
    }

    /** Häufigster nicht leerer Wert, bei Gleichstand der zuerst gesehene. */
    fun mostCommon(values: List<String?>): String? {
        val counts = LinkedHashMap<String, Int>()
        for (v in values) {
            val t = v?.trim()
            if (!t.isNullOrEmpty()) counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.maxByOrNull { it.value }?.key
    }
}
