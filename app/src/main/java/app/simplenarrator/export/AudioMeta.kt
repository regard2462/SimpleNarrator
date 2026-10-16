package app.simplenarrator.export

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Kapitelmarke in einer Audiodatei, Zeiten in Millisekunden ab Dateianfang. */
data class ChapterMark(val title: String, val startMs: Long, val endMs: Long)

/**
 * Metadaten für exportierte Hörbücher. Album = Buchtitel, Genre "Audiobook".
 * cover sind die rohen Bildbytes (JPEG oder PNG); coverMime darf fehlen, dann wird am Dateikopf erkannt.
 * Bei einer Datei je Kapitel kann title der Kapiteltitel sein und album den Buchtitel tragen.
 */
class AudioMeta(
    val title: String,
    val author: String,
    val cover: ByteArray? = null,
    val coverMime: String? = null,
    val album: String = title,
) {
    val genre: String get() = GENRE

    /** "image/jpeg" oder "image/png", sonst null (andere Formate kennen MP4 und viele Player nicht). */
    internal fun coverType(): String? {
        val c = cover ?: return null
        if (c.isEmpty()) return null
        val sniffed = when {
            c.size >= 3 && c[0] == 0xFF.toByte() && c[1] == 0xD8.toByte() && c[2] == 0xFF.toByte() -> JPEG
            c.size >= 8 && c[0] == 0x89.toByte() && c[1] == 'P'.code.toByte() && c[2] == 'N'.code.toByte() && c[3] == 'G'.code.toByte() -> PNG
            else -> null
        }
        val declared = when (coverMime?.lowercase()?.trim()) {
            "image/jpeg", "image/jpg" -> JPEG
            "image/png" -> PNG
            else -> null
        }
        // Die Bytes entscheiden; die Angabe zählt nur, wenn der Kopf unbekannt ist
        return sniffed ?: declared
    }

    companion object {
        const val GENRE = "Audiobook"
        const val JPEG = "image/jpeg"
        const val PNG = "image/png"
    }
}

/** Gemeinsame Dateihilfen der Nachbearbeitung. */
internal object TagFiles {
    /** Temporäre Datei neben dem Ziel, damit das Umbenennen auf demselben Dateisystem bleibt. */
    fun tempFor(target: File): File {
        val dir = target.absoluteFile.parentFile ?: throw IOException("No parent directory for $target")
        return File(dir, target.name + ".part")
    }

    /** Ersetzt target durch tmp; atomar, wo das Dateisystem es kann. */
    fun replace(tmp: File, target: File) {
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
