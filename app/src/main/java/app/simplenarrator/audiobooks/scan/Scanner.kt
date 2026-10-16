package app.simplenarrator.audiobooks.scan

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.simplenarrator.audiobooks.data.LibraryRoot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.FileChannel

/** Liest Bibliotheksordner über das Storage Access Framework; das Bauen der Bücher übernimmt [BookScanner]. */
class Scanner(private val context: Context, covers: Covers) : BookScanner(covers) {

    private val resolver = context.contentResolver

    // ---------------------------------------------------------------- Ordnerbaum

    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    override fun listTree(root: LibraryRoot): FsDir? = try {
        val tree = Uri.parse(root.uri)
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val name = displayName(DocumentsContract.buildDocumentUriUsingTree(tree, rootId))
            ?: rootId.substringAfterLast(':').substringAfterLast('/')
        listDir(tree, rootId, name, 0)
    } catch (_: Exception) {
        null
    }

    private fun listDir(tree: Uri, docId: String, name: String, depth: Int): FsDir {
        val files = ArrayList<FsFile>()
        val dirs = ArrayList<FsDir>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cursor = resolver.query(uri, projection, null, null, null) ?: throw IOException("Ordner nicht lesbar")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val n = c.getString(1) ?: continue
                if (n.startsWith(".")) continue
                val mime = c.getString(2) ?: ""
                if (mime == Document.MIME_TYPE_DIR) {
                    if (depth < 10) dirs += listDir(tree, id, n, depth + 1)
                } else {
                    files += FsFile(id, n, if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4))
                }
            }
        }
        return FsDir(docId, name, files, dirs)
    }

    private fun displayName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (_: Exception) {
        null
    }

    override fun fileUri(root: LibraryRoot, file: FsFile): String =
        DocumentsContract.buildDocumentUriUsingTree(Uri.parse(root.uri), file.id).toString()

    // ---------------------------------------------------------------- Dateien untersuchen

    override fun metadata(uri: String): Pair<Long, Tags> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(uri))
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            duration to Tags(
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE),
                album = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                albumArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                composer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_WRITER),
                year = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4),
            )
        } catch (_: Exception) {
            0L to Tags()
        } finally {
            runCatching { r.release() }
        }
    }

    override fun <R> withChannel(uri: String, block: (FileChannel) -> R): R? {
        val pfd = resolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> block(input.channel) }
    }

    override fun fallbackDuration(uri: String): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(context, Uri.parse(uri), null)
            (0 until ex.trackCount).maxOfOrNull { i ->
                val f = ex.getTrackFormat(i)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) / 1000 else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            ex.release()
        }
    }

    override fun embeddedPicture(uri: String): ByteArray? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, Uri.parse(uri))
            r.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    override fun readBytes(root: LibraryRoot, file: FsFile, maxBytes: Int): ByteArray? {
        if (file.size > maxBytes) return null
        return try {
            resolver.openInputStream(Uri.parse(fileUri(root, file)))?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null
                }
                out.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }
}
