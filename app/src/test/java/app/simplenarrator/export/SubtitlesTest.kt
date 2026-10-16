package app.simplenarrator.export

import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitlesTest {
    private val cues = listOf(
        TextCue("Erster Satz.", 0, 1250),
        TextCue("   ", 1250, 1300),
        TextCue("Zweiter\n\nSatz mit Leerzeile.", 1300, 3_725_456),
    )

    @Test
    fun srt() {
        assertEquals(
            "1\n00:00:00,000 --> 00:00:01,250\nErster Satz.\n\n" +
                "2\n00:00:01,300 --> 01:02:05,456\nZweiter\nSatz mit Leerzeile.\n\n",
            Subtitles.srt(cues),
        )
    }

    @Test
    fun lrc() {
        assertEquals(
            "[ti:Mein Buch (1)]\n[ar:Erika Muster]\n[00:00.00]Erster Satz.\n[00:01.30]Zweiter Satz mit Leerzeile.\n[62:05.45]\n",
            Subtitles.lrc(cues, "Mein Buch [1]", "Erika Muster"),
        )
    }

    @Test
    fun emptyInput() {
        assertEquals("", Subtitles.srt(emptyList()))
        assertEquals("[ti:T]\n", Subtitles.lrc(emptyList(), "T", ""))
    }

    @Test
    fun timeFormats() {
        assertEquals("00:00:00,000", Subtitles.srtTime(0))
        assertEquals("10:00:00,001", Subtitles.srtTime(36_000_001))
        assertEquals("[00:59.99]", Subtitles.lrcTime(59_999))
        assertEquals("[125:00.00]", Subtitles.lrcTime(7_500_000))
    }
}
