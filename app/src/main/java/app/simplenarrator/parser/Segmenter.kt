package app.simplenarrator.parser

import app.simplenarrator.speech.Normalizer
import java.text.BreakIterator
import java.util.Locale

/**
 * Zerlegt Absätze in Sätze; sehr lange werden an Kommas geteilt (Kokoro klingt bei > ~300 Zeichen
 * schlechter und hat ein Token-Limit).
 *
 * Kurze Sätze bleiben einzeln. Früher wurden Sätze unter 24 Zeichen mit dem nächsten zusammengelegt; dann
 * landeten zwei kurze Repliken verschiedener Figuren in einem Stück, und die Pause dazwischen verschwand.
 * Zusammen bleibt dagegen wörtliche Rede mit ihrem Begleitsatz („Lass mich rein!“ unterbrach ich.), sonst
 * käme vor "unterbrach ich" eine volle Satzpause.
 */
object Segmenter {
    private const val MAX_LEN = 280

    fun split(text: String, lang: String): List<String> {
        val locale = if (lang == "en") Locale.ENGLISH else Locale.GERMAN
        val it = BreakIterator.getSentenceInstance(locale)
        it.setText(text)
        val raw = mutableListOf<String>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val s = text.substring(start, end).trim()
            if (s.isNotEmpty()) raw += s
            start = end
            end = it.next()
        }
        val merged = mutableListOf<String>()
        for (s in raw) {
            when {
                // BreakIterator trennt vor schließenden Anführungszeichen wie „…“ (“ gilt in Java als
                // öffnend) und vor Kommas nach Anführungszeichen: „Hallo!“, sagte er. → ohne Leerzeichen anhängen
                merged.isNotEmpty() && continuesPrevious(s) -> {
                    // Steht hinter dem schließenden Zeichen ein neuer Satz („Kommst du?“ „Gleich.“), bekommt der
                    // vorige Satz nur das Anführungszeichen; ein Begleitsatz bleibt dagegen dran.
                    val m = CLOSER_THEN_TEXT.matchEntire(s)
                    val prev = merged.last()
                    if (m != null && prev.trimEnd().lastOrNull()?.let { it in ".!?…" } == true &&
                        m.groupValues[2].first().let { it in OPENERS || it.isUpperCase() } &&
                        !isSpeechTag(prev + m.groupValues[1], m.groupValues[2], lang)
                    ) {
                        merged[merged.lastIndex] = prev + m.groupValues[1]
                        merged += m.groupValues[2]
                    } else merged[merged.lastIndex] = prev + s
                }
                // "am 3." | "Mai kam er" oder "Herrn Dr." | "Müller": kein echtes Satzende
                merged.isNotEmpty() && Normalizer.endsWithNonFinalPeriod(merged.last()) ->
                    merged[merged.lastIndex] = merged.last() + " " + s
                merged.isNotEmpty() && isSpeechTag(merged.last(), s, lang) -> merged[merged.lastIndex] = merged.last() + " " + s
                else -> merged += s
            }
        }
        // Stücke mit Buchstaben, die für einen eigenen Satz zu kurz sind ("I...", "A!"), gehen nicht verloren:
        // Sie kommen vor das nächste Stück, am Absatzende an das vorige.
        val kept = mutableListOf<String>()
        var carry = ""
        for (s in merged) {
            val t = if (carry.isEmpty()) s else "$carry $s"
            if (!hasSpeakableContent(t) && !isSceneBreak(t) && t.any { it.isLetter() }) { carry = t; continue }
            carry = ""
            kept += t
        }
        if (carry.isNotEmpty()) {
            if (kept.isNotEmpty() && !isSceneBreak(kept.last())) kept[kept.lastIndex] = kept.last() + " " + carry else kept += carry
        }
        return kept.flatMap { splitLong(it) }.filter { hasSpeakableContent(it) || isSceneBreak(it) }
    }

    /**
     * Trennzeile zwischen Szenen ("* * *", "———", "⁂"): keine Buchstaben und Ziffern, aber mindestens drei
     * Zeichen oder ein einzelnes Schmuckzeichen. Sie bleibt als eigener Satz stehen und wird beim Vorlesen
     * zu einer längeren Pause.
     */
    fun isSceneBreak(text: String): Boolean {
        if (text.any { it.isLetterOrDigit() }) return false
        val marks = text.count { !it.isWhitespace() }
        return marks >= 3 || (marks == 1 && text.trim() in SINGLE_BREAKS)
    }

    private val SINGLE_BREAKS = setOf("⁂", "❦", "❧", "✻", "※", "§")

    private const val CLOSERS = "“”\"»«’‘'›‹"
    private const val OPENERS = "„“\"»«‚‘'›‹"
    private val CLOSER_THEN_TEXT = Regex("""^([“”"»«’‘'›‹]+)\s+(\S.*)$""", RegexOption.DOT_MATCHES_ALL)
    private const val TRAILING = ",;:.!?)"

    private fun continuesPrevious(s: String): Boolean {
        val first = s[0]
        if (first in TRAILING) return true
        if (first !in CLOSERS) return false
        val second = s.getOrNull(1) ?: return true
        return second in TRAILING || second == ' ' || second.isLowerCase()
    }

    /** Endet mit Ausrufe- oder Fragezeichen oder Auslassungspunkten, gefolgt von schließenden Anführungszeichen. */
    private val SPEECH_END = Regex("[!?…]+[“”\"»«’‘'›‹]*$")
    private val QUOTE_END = Regex("[!?…,.][“”\"»«’‘'›‹]+$")

    /** Englische Verben, die einen Begleitsatz einleiten ("I interrupted", "John cried"). */
    private val SPEECH_VERBS_EN = setOf(
        "said", "says", "asked", "asks", "cried", "shouted", "yelled", "whispered", "replied", "answered",
        "interrupted", "called", "muttered", "murmured", "exclaimed", "added", "continued", "began", "screamed",
        "laughed", "sighed", "snapped", "demanded", "insisted", "protested", "repeated", "gasped", "breathed",
        "growled", "roared", "hissed", "pleaded", "begged", "inquired", "enquired", "suggested", "admitted",
        "agreed", "explained", "observed", "remarked", "returned", "rejoined", "retorted", "responded",
        "wondered", "thought", "told", "warned", "urged", "announced", "declared", "stammered", "stuttered",
        "sobbed", "shrieked", "groaned", "moaned", "grumbled", "chuckled", "giggled", "snorted", "sneered",
        "mumbled", "whimpered", "bellowed", "barked", "ventured", "offered", "corrected", "conceded",
        "confessed", "objected", "commented", "noted",
    )

    /**
     * Ist [next] der Begleitsatz zur wörtlichen Rede am Ende von [prev]? Nach "!", "?" oder "…" (mit oder
     * ohne Anführungszeichen) trennt BreakIterator auch vor einem kleingeschriebenen Wort; das gehört aber
     * zum selben Satz („Halt!“ rief er.). Im Englischen beginnt der Begleitsatz oft groß ("Stop!" John
     * cried. / "Let me in!" I interrupted.); dann entscheidet ein Sprechverb an zweiter oder dritter Stelle.
     */
    private fun isSpeechTag(prev: String, next: String, lang: String): Boolean {
        val first = next.firstOrNull { it.isLetter() } ?: return false
        if (next.first() != first) return false
        if (first.isLowerCase()) return SPEECH_END.containsMatchIn(prev) || QUOTE_END.containsMatchIn(prev)
        if (lang != "en" || !QUOTE_END.containsMatchIn(prev)) return false
        val words = next.split(' ').take(3).map { w -> w.trim { !it.isLetter() }.lowercase() }
        return words.drop(1).any { it in SPEECH_VERBS_EN }
    }

    private fun splitLong(s: String): List<String> {
        if (s.length <= MAX_LEN) return listOf(s)
        val candidates = listOf(", ", "; ", ": ", " – ", " — ").map { s.lastIndexOf(it, MAX_LEN) }.filter { it > MAX_LEN / 3 }
        val cut = if (candidates.isNotEmpty()) candidates.max() + 1
        else s.lastIndexOf(' ', MAX_LEN).takeIf { it > 0 } ?: MAX_LEN
        val head = s.substring(0, cut).trim()
        val tail = s.substring(cut).trim()
        return listOf(head) + splitLong(tail)
    }

    /** Filtert reine Zahlenzeilen (Seitenzahlen) und Satzzeichen ohne Text. */
    private fun hasSpeakableContent(s: String): Boolean = s.any { it.isLetter() } && s.count { it.isLetterOrDigit() } >= 2
}
