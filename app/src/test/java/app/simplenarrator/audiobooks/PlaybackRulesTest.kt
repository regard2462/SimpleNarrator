package app.simplenarrator.audiobooks

import app.simplenarrator.audiobooks.data.AppJson
import app.simplenarrator.audiobooks.data.Book
import app.simplenarrator.audiobooks.data.BookState
import app.simplenarrator.audiobooks.data.Entry
import app.simplenarrator.audiobooks.data.Status
import app.simplenarrator.audiobooks.data.Track
import app.simplenarrator.audiobooks.playback.MediaIds
import app.simplenarrator.audiobooks.playback.Rewind
import app.simplenarrator.audiobooks.scan.BookBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackRulesTest {

    private fun book(vararg durations: Long): Book {
        val tracks = durations.mapIndexed { i, d -> Track("u$i", "$i.mp3", d) }
        return Book("b", "root", "path", "Titel", tracks = tracks, chapters = BookBuilder.chapters(tracks))
    }

    @Test
    fun global_positions_across_files() {
        val b = book(10_000, 20_000, 5_000)
        assertEquals(35_000L, b.durationMs)
        assertEquals(15_000L, b.globalOf(1, 5_000))
        assertEquals(1 to 5_000L, b.locate(15_000))
        assertEquals(1 to 0L, b.locate(10_000))
        assertEquals(0 to 9_999L, b.locate(9_999))
        assertEquals(2 to 5_000L, b.locate(99_000))
        assertEquals(0 to 0L, b.locate(-5))
        assertEquals(1, b.chapterAt(10_000))
        assertEquals(0, b.chapterAt(9_999))
        assertEquals(2, b.chapterAt(35_000))
    }

    @Test
    fun book_survives_json_roundtrip() {
        val b = book(10_000, 20_000)
        val json = AppJson.encodeToString(Book.serializer(), b)
        val back = AppJson.decodeFromString(Book.serializer(), json)
        assertEquals(b, back)
        assertEquals(30_000L, back.durationMs)
        assertEquals(10_000L, back.trackStarts[1])
    }

    @Test
    fun entry_status_and_progress() {
        val b = book(10_000, 30_000)
        assertEquals(Status.NEW, Entry(b, BookState()).status)
        val started = Entry(b, BookState(positionMs = 10_000, lastPlayedAt = 1))
        assertEquals(Status.STARTED, started.status)
        assertEquals(0.25f, started.progress, 0.0001f)
        assertEquals(30_000L, started.remainingMs)
        assertEquals(Status.FINISHED, Entry(b, BookState(finished = true)).status)
        assertEquals("Neu", Entry(b, BookState(title = "Neu")).title)
        assertEquals("Titel", Entry(b, BookState(title = " ")).title)
    }

    @Test
    fun resume_rewind() {
        assertEquals(0L, Rewind.resumeRewindMs(2_000, 5, true))
        assertEquals(0L, Rewind.resumeRewindMs(600_000, 0, true))
        assertEquals(5_000L, Rewind.resumeRewindMs(30_000, 5, false))
        assertEquals(2_500L, Rewind.resumeRewindMs(30_000, 5, true))
        assertEquals(5_000L, Rewind.resumeRewindMs(5 * 60_000, 5, true))
        assertEquals(10_000L, Rewind.resumeRewindMs(30 * 60_000, 5, true))
        assertEquals(15_000L, Rewind.resumeRewindMs(5 * 3_600_000L, 5, true))
        assertEquals(60_000L, Rewind.resumeRewindMs(5 * 3_600_000L, 30, true))
    }

    @Test
    fun media_ids() {
        assertEquals(MediaIds.BookRequest("abc", null), MediaIds.parseBook(MediaIds.book("abc")))
        assertEquals(MediaIds.BookRequest("abc", 1234L), MediaIds.parseBook(MediaIds.book("abc", 1234)))
        assertNull(MediaIds.parseBook("track:abc:1"))
        assertEquals("abc", MediaIds.bookOfTrack(MediaIds.track("abc", 7)))
        assertEquals(7, MediaIds.trackIndex(MediaIds.track("abc", 7)))
        assertNull(MediaIds.bookOfTrack("book:abc"))
    }
}
