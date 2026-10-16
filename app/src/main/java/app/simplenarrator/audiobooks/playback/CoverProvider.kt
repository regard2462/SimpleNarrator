package app.simplenarrator.audiobooks.playback

import app.simplenarrator.audiobooks.data.AudiobookFiles
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/**
 * Gibt Titelbilder schreibgeschützt frei, damit Systemsteuerung, Sperrbildschirm und Android Auto sie
 * laden können. Ausgeliefert werden nur JPEG-Dateien aus dem Ordner der Titelbilder, sonst nichts.
 */
class CoverProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File {
        val name = uri.lastPathSegment ?: throw FileNotFoundException()
        if (uri.pathSegments.size != 1 || !name.endsWith(".jpg") || name.contains('/') || name.startsWith(".")) {
            throw FileNotFoundException()
        }
        val dir = File(AudiobookFiles.dir(context!!), "covers")
        val f = File(dir, name)
        if (f.parentFile?.canonicalPath != dir.canonicalPath || !f.isFile) throw FileNotFoundException()
        return f
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("nur lesen")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "image/jpeg"

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val row: Array<Any?> = cols.map<String, Any?> { c ->
            when (c) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray()
        return MatrixCursor(cols, 1).apply { addRow(row) }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun uriFor(context: Context, path: String): Uri = Uri.Builder()
            .scheme("content")
            .authority(context.packageName + ".covers")
            .appendPath(File(path).name)
            .build()
    }
}
