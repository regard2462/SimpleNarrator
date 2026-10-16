package app.simplenarrator.playback

/** Eigene Session-Befehle zwischen UI (MediaController) und ReadAloudService. */
object Commands {
    const val READ_FROM = "app.simplenarrator.READ_FROM"
    const val NEXT_CHAPTER = "app.simplenarrator.NEXT_CHAPTER"
    const val PREV_CHAPTER = "app.simplenarrator.PREV_CHAPTER"
    const val SLEEP_TIMER = "app.simplenarrator.SLEEP_TIMER"
    const val STOP = "app.simplenarrator.STOP"

    const val ARG_BOOK = "book"
    const val ARG_CHAPTER = "chapter"
    const val ARG_SENTENCE = "sentence"
    const val ARG_MINUTES = "minutes"   // >0 Minuten, -1 = Kapitelende, 0 = aus
}

/** Position eines Satzes, kodiert als MediaItem-ID "bookId|kapitel|satz". */
data class Cue(val bookId: String, val chapter: Int, val sentence: Int) {
    fun toMediaId() = "$bookId|$chapter|$sentence"

    companion object {
        fun parse(id: String?): Cue? {
            val parts = id?.split('|') ?: return null
            if (parts.size != 3) return null
            return Cue(parts[0], parts[1].toIntOrNull() ?: return null, parts[2].toIntOrNull() ?: return null)
        }
    }
}
