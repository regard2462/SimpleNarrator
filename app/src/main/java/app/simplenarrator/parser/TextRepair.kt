package app.simplenarrator.parser

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Text, der beim Erzeugen der Datei kaputtging, wieder lesbar machen, bevor er angezeigt und gesprochen wird.
 *
 * - Mojibake: UTF-8, das als Windows-1252 gelesen wurde ("donâ€™t", "Ã¤", "â€œHalloâ€"). Jede verdächtige
 *   Folge wird einzeln zurückübersetzt, so bleibt richtig kodierter Text daneben unberührt.
 * - Ligaturen aus PDFs und alten EPUBs (ﬁ, ﬂ, ﬀ, ﬃ, ﬄ, ﬅ, ﬆ) und das lange s (ſ) werden zu einzelnen Buchstaben;
 *   sonst liest die Stimme sie gar nicht oder als Sonderzeichen.
 */
object TextRepair {

    fun repair(s: String): String {
        var t = s
        if (MARKER.containsMatchIn(t)) t = fixMojibake(t)
        if (t.any { it in 'ﬀ'..'ﬆ' || it == 'ſ' }) {
            val sb = StringBuilder(t.length + 8)
            for (c in t) sb.append(LIGATURES[c] ?: c)
            t = sb.toString()
        }
        return t
    }

    private val LIGATURES = mapOf(
        'ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl",
        'ﬅ' to "st", 'ﬆ' to "st", 'ſ' to "s",
    )

    /** Zeichen, die als Windows-1252 für die Bytes 0x80 bis 0x9F stehen. */
    private const val CP1252_HIGH = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

    /** Ein Folgebyte (0x80 bis 0xBF), wie es als Windows-1252 oder Latin-1 erscheint. */
    private const val CONT = "[\u0080-¿€‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ]"

    /**
     * Zwei- und Drei-Byte-Folgen von UTF-8 in Windows-1252-Gestalt ("Ã¤", "â€™"). Drei Bytes nur mit "â" vorn
     * (Satzzeichen U+2000 bis U+2FFF, €): sonst würde etwa "é…“" in einem sonst heilen Text zu einem CJK-Zeichen.
     */
    private val SEQUENCE = Regex("[Â-ß]$CONT|â$CONT$CONT")

    /** Schneller Vortest: typische Mojibake-Anfänge. */
    private val MARKER = Regex("[ÂÃ][\u0080-¿€‚„…†‡‰Š‹Œ‘’“”•–—™š›œžŸ]|â€")

    private fun byteOf(c: Char): Int? = when {
        c in '\u0080'..'ÿ' -> c.code
        else -> CP1252_HIGH.indexOf(c).takeIf { it >= 0 }?.plus(0x80)
    }

    private fun fixMojibake(s: String): String {
        val fixed = SEQUENCE.replace(s) { m ->
            val bytes = m.value.map { byteOf(it) ?: return@replace m.value }.map { it.toByte() }.toByteArray()
            decodeStrict(bytes) ?: m.value
        }
        // Das schließende ” (E2 80 9D) verliert sein letztes Byte oft ganz, weil 0x9D in Windows-1252 fehlt
        return fixed.replace(Regex("â€(?=\\s|$|[.,;:!?)])"), "”")
    }

    private fun decodeStrict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) {
        null
    }
}
