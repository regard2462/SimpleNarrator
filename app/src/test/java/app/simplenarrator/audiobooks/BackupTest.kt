package app.simplenarrator.audiobooks

import app.simplenarrator.audiobooks.data.AppSettings
import app.simplenarrator.audiobooks.data.Backup
import app.simplenarrator.audiobooks.data.BackupBook
import app.simplenarrator.audiobooks.data.BackupFile
import app.simplenarrator.audiobooks.data.Book
import app.simplenarrator.audiobooks.data.BookState
import app.simplenarrator.audiobooks.data.ColorPalette
import app.simplenarrator.audiobooks.data.LibraryRoot
import app.simplenarrator.audiobooks.data.SettingsBackup
import app.simplenarrator.audiobooks.data.SortOrder
import app.simplenarrator.audiobooks.data.StatsData
import app.simplenarrator.audiobooks.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private fun book(id: String, path: String, title: String, author: String? = null) =
        Book(id = id, root = "content://root", path = path, title = title, author = author, tracks = emptyList(), chapters = emptyList())

    @Test
    fun settingsSurviveExportAndImport() {
        val mine = AppSettings(
            skipBackSec = 20, skipForwardSec = 45, defaultSpeed = 1.25f, theme = ThemeMode.DARK, sort = SortOrder.TITLE,
            webCovers = false, headsetChapters = true, palette = ColorPalette.SAGE, pureBlack = true,
        )
        val file = BackupFile(exportedAt = 1L, books = emptyList(), settings = SettingsBackup.from(mine, "de"))
        val back = Backup.decode(Backup.encode(file))
        assertNotNull(back)
        assertEquals("de", back!!.settings!!.language)
        // Die Ordner des Zielgeräts bleiben
        val target = AppSettings(roots = listOf(LibraryRoot("content://hier", false)))
        val applied = back.settings!!.applyTo(target)
        assertEquals(target.roots, applied.roots)
        assertEquals(mine.copy(roots = target.roots), applied)
    }

    @Test
    fun paletteFromOlderExports() {
        // 1.2 kannte nur den Schalter für die Farben des Hintergrundbilds
        val target = AppSettings(palette = ColorPalette.PETROL)
        assertEquals(ColorPalette.WALLPAPER, SettingsBackup(dynamicColor = true).applyTo(target).palette)
        assertEquals(ColorPalette.PETROL, SettingsBackup(dynamicColor = false).applyTo(target).palette)
        assertEquals(ColorPalette.VIOLET, SettingsBackup(palette = "VIOLET").applyTo(target).palette)
        assertEquals(ColorPalette.PETROL, SettingsBackup(palette = "GIBTSNICHT").applyTo(target).palette)
    }

    @Test
    fun importLimitsNonsense() {
        val s = SettingsBackup(skipBackSec = 9999, skipForwardSec = 0, defaultSpeed = Float.NaN, sleepMinutes = -5, theme = "LILA", sort = "?")
            .applyTo(AppSettings())
        assertEquals(300, s.skipBackSec)
        assertEquals(1, s.skipForwardSec)
        assertEquals(1f, s.defaultSpeed)
        assertEquals(1, s.sleepMinutes)
        assertEquals(ThemeMode.SYSTEM, s.theme)
        assertEquals(SortOrder.RECENT, s.sort)
    }

    @Test
    fun readsOlderFilesAndRejectsOthers() {
        // Ältere Fassungen schrieben Version 1 ohne Einstellungen
        val old = """{"app":"SimpleAudiobooks","exportedAt":5,"books":[{"id":"a","path":"Buch","title":"Buch","state":{"positionMs":1234}}],"stats":{"days":{"2026-10-01":60000}}}"""
        val f = Backup.decode(old)
        assertNotNull(f)
        assertNull(f!!.settings)
        assertEquals(1234L, f.books.single().state.positionMs)
        assertNull(Backup.decode("{}"))
        assertNull(Backup.decode("kein json"))
        assertNull(Backup.decode("""{"exportedAt":1}"""))
    }

    @Test
    fun matchesByIdPathAndTitleAndKeepsTheRest() {
        val books = listOf(book("id1", "A", "Erstes"), book("id2", "Autor/B", "Zweites", "Max Muster"), book("id3", "C", "Drittes"))
        val items = listOf(
            BackupBook("id1", "anders", "egal", state = BookState(positionMs = 1)),
            BackupBook("alt2", "Autor/B", "Zweites", state = BookState(positionMs = 2)),
            BackupBook("alt3", "woanders", "drittes", state = BookState(positionMs = 3, finished = true)),
            BackupBook("alt4", "fehlt", "Fehlt noch", state = BookState(positionMs = 4)),
        )
        val m = Backup.match(books, items) { BookState() }
        assertEquals(1L, m.states["id1"]!!.positionMs)
        assertEquals(2L, m.states["id2"]!!.positionMs)
        assertTrue(m.states["id3"]!!.finished)
        assertEquals("content://root\nC", m.states["id3"]!!.pathHint)
        assertEquals(listOf("alt4"), m.unmatched.map { it.id })
        // Später eingelesen: der wartende Eintrag findet sein Buch
        val later = Backup.match(books + book("id4", "fehlt", "Fehlt noch"), m.unmatched) { BookState() }
        assertEquals(4L, later.states["id4"]!!.positionMs)
        assertTrue(later.unmatched.isEmpty())
    }

    @Test
    fun keepsCoversOfThisDevice() {
        val here = BookState(customCover = "/hier/c.jpg", customColor = 7, webCover = "/hier/w.jpg", webQuery = "q", webTriedAt = 9)
        val m = Backup.match(listOf(book("id", "p", "T")), listOf(BackupBook("id", "p", "T", state = BookState(customCover = "/fremd/x.jpg", positionMs = 5)))) { here }
        val s = m.states["id"]!!
        assertEquals(5L, s.positionMs)
        assertEquals("/hier/c.jpg", s.customCover)
        assertEquals(7, s.customColor)
        assertEquals("/hier/w.jpg", s.webCover)
        assertFalse(s.webBlocked)
    }

    @Test
    fun statsTakeTheLargerDay() {
        val merged = Backup.mergeStats(StatsData(mapOf("a" to 5L, "b" to 9L)), StatsData(mapOf("b" to 7L, "c" to 1L)))
        assertEquals(mapOf("a" to 5L, "b" to 9L, "c" to 1L), merged.days)
    }
}
