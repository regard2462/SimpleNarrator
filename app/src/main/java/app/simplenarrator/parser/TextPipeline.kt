package app.simplenarrator.parser

import app.simplenarrator.data.Chapter
import app.simplenarrator.data.Paragraph
import app.simplenarrator.data.RawParagraph
import app.simplenarrator.data.Sentence

object TextPipeline {
    /** Buchweite Grundsprache aus einer Textprobe. */
    fun guessBookLanguage(sample: String): String = LanguageDetector.detect(sample.take(6000)) ?: "de"

    fun buildChapter(title: String, raw: List<RawParagraph>, defaultLang: String): Chapter {
        var lastLang = defaultLang
        val paragraphs = raw.mapNotNull { rp ->
            val text = TextCleaner.normalize(rp.text)
            if (text.isBlank()) return@mapNotNull null
            val lang = LanguageDetector.detect(text) ?: lastLang
            lastLang = lang
            val sentences = Segmenter.split(text, lang).map { Sentence(it, lang) }
            if (sentences.isEmpty()) null else Paragraph(sentences, rp.isHeading)
        }
        return Chapter(TextRepair.repair(title).ifBlank { FallbackTitles.section(defaultLang) }, paragraphs)
    }
}
