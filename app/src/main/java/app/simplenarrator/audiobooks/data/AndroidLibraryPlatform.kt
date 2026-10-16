package app.simplenarrator.audiobooks.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import app.simplenarrator.audiobooks.scan.Covers
import app.simplenarrator.audiobooks.scan.Scanner

/** Bibliotheksordner über das Storage Access Framework. */
class AndroidLibraryPlatform(private val context: Context) : LibraryPlatform {

    override val covers = Covers(context)
    override val scanner = Scanner(context, covers)

    override fun deleteBookFiles(book: Book): Boolean {
        val resolver = context.contentResolver
        val tree = Uri.parse(book.root)
        val folder = folderDocument(tree, book)
        return if (folder != null) {
            DocumentsContract.deleteDocument(resolver, folder)
        } else {
            book.tracks.all { DocumentsContract.deleteDocument(resolver, Uri.parse(it.uri)) }
        }
    }

    /** Dokument des Buchordners, wenn das Buch einen eigenen Ordner hat und der Anbieter Pfade als Kennung nutzt. */
    private fun folderDocument(tree: Uri, book: Book): Uri? {
        if (book.path.isEmpty() || book.tracks.size == 1 && book.tracks[0].path == book.path.substringAfterLast('/')) return null
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        val firstTrackId = DocumentsContract.getDocumentId(Uri.parse(book.tracks.first().uri))
        val folderId = rootId.trimEnd('/') + "/" + book.path
        // Nur wenn die Kennungen wirklich Pfade sind (Gerätespeicher, SD-Karte), sonst Dateien einzeln löschen
        if (!firstTrackId.startsWith("$folderId/")) return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
    }

    override fun releaseRoot(uri: String) {
        val resolver = context.contentResolver
        val parsed = Uri.parse(uri)
        // Ordner mit Schreibrecht zum Löschen; ohne dieses Recht genügt das Leserecht
        runCatching {
            resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }.onFailure {
            runCatching { resolver.releasePersistableUriPermission(parsed, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }
}
