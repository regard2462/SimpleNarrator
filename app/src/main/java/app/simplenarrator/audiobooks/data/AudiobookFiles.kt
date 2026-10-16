package app.simplenarrator.audiobooks.data

import android.content.Context
import java.io.File

/**
 * Speicherorte des Hörbuch-Bereichs. Er liegt in einem eigenen Unterordner, damit er sich nicht mit den
 * Vorlese-Büchern (files/books/) mischt.
 */
object AudiobookFiles {
    const val PREFS = "audiobooks"

    fun dir(context: Context): File = File(context.filesDir, "audiobooks").apply { mkdirs() }
}
