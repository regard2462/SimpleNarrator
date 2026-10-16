package app.simplenarrator.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Legt fertige Dateien unter Downloads/<subFolder>/ ab (ohne Speicher-Berechtigung ab Android 10). */
object MediaStoreSaver {
    fun saveToDownloads(context: Context, file: File, displayName: String, subFolder: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(displayName))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + subFolder)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("Could not create $displayName in Downloads")
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("Could not write $displayName")
            values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), subFolder).apply { mkdirs() }
            val dest = File(dir, displayName)
            file.copyTo(dest, overwrite = true)
            return Uri.fromFile(dest)
        }
    }

    private fun mimeOf(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
        "m4a", "m4b" -> "audio/mp4"; "mp3" -> "audio/mpeg"; "wav" -> "audio/wav"; "txt" -> "text/plain"
        "srt" -> "application/x-subrip"; "epub" -> "application/epub+zip"
        // Bei text/plain hängt Android ".txt" an; LRC bleibt deshalb ohne bekannten Typ
        "lrc" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(80).ifBlank { "Book" }
}
