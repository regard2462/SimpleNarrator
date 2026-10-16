package app.simplenarrator.audiobooks.data

import app.simplenarrator.audiobooks.scan.BookScanner
import java.io.File

/** Ein gespeichertes Titelbild und seine Leitfarbe. */
data class SavedCover(val path: String, val color: Int?)

/** Speichert verkleinerte Titelbilder; Bildbearbeitung ist je Plattform eigen. */
interface CoverStore {
    val dir: File

    /** Verkleinert und speichert ein Bild als JPEG; null, wenn die Daten kein lesbares Bild sind. */
    fun saveBytes(name: String, bytes: ByteArray): SavedCover?

    /** Löscht Titelbilder, auf die kein Buch mehr verweist. */
    fun prune(keep: Set<String>) {
        dir.listFiles()?.forEach { f -> if (f.absolutePath !in keep) f.delete() }
    }
}

/** Was die Bibliothek von der Plattform braucht: Titelbilder, Einlesen, Löschen. */
interface LibraryPlatform {
    val covers: CoverStore
    val scanner: BookScanner

    /** Löscht die Dateien eines Buches; false, wenn der Speicher das verweigert. */
    fun deleteBookFiles(book: Book): Boolean

    /** Gibt einen entfernten Bibliotheksordner frei (Android: Zugriffsrecht zurückgeben). */
    fun releaseRoot(uri: String) {}
}
