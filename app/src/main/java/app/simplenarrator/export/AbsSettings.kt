package app.simplenarrator.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import java.io.FileNotFoundException

/** Eigener kleiner Speicher, getrennt von den App-Einstellungen ("settings"). */
private val Context.absDataStore by preferencesDataStore("audiobookshelf")

data class AbsConfig(
    val url: String = "",
    val token: String = "",
    val libraryId: String = "",
    val folderId: String = "",
    /** Nur zur Anzeige, damit der Upload-Dialog das Ziel ohne Serverabfrage nennen kann. */
    val libraryName: String = "",
    val folderPath: String = "",
) {
    val hasServer: Boolean get() = url.isNotBlank() && token.isNotBlank()
    val isComplete: Boolean get() = hasServer && libraryId.isNotBlank() && folderId.isNotBlank()

    fun client(http: OkHttpClient) = Audiobookshelf(url, token, http)
}

class AbsSettings(context: Context) {
    private val store = context.applicationContext.absDataStore

    private object K {
        val URL = stringPreferencesKey("url")
        val TOKEN = stringPreferencesKey("token")
        val LIBRARY = stringPreferencesKey("library_id")
        val FOLDER = stringPreferencesKey("folder_id")
        val LIBRARY_NAME = stringPreferencesKey("library_name")
        val FOLDER_PATH = stringPreferencesKey("folder_path")
    }

    val flow: Flow<AbsConfig> = store.data.map { p ->
        AbsConfig(
            url = p[K.URL].orEmpty(),
            token = p[K.TOKEN].orEmpty(),
            libraryId = p[K.LIBRARY].orEmpty(),
            folderId = p[K.FOLDER].orEmpty(),
            libraryName = p[K.LIBRARY_NAME].orEmpty(),
            folderPath = p[K.FOLDER_PATH].orEmpty(),
        )
    }

    suspend fun current(): AbsConfig = flow.first()

    suspend fun update(block: (AbsConfig) -> AbsConfig) {
        store.edit { p ->
            val n = block(
                AbsConfig(
                    p[K.URL].orEmpty(), p[K.TOKEN].orEmpty(), p[K.LIBRARY].orEmpty(),
                    p[K.FOLDER].orEmpty(), p[K.LIBRARY_NAME].orEmpty(), p[K.FOLDER_PATH].orEmpty(),
                )
            )
            p[K.URL] = n.url; p[K.TOKEN] = n.token
            p[K.LIBRARY] = n.libraryId; p[K.FOLDER] = n.folderId
            p[K.LIBRARY_NAME] = n.libraryName; p[K.FOLDER_PATH] = n.folderPath
        }
    }
}

/** Upload-Quelle aus einer content://-Adresse (z. B. die vom Export in Downloads abgelegten Dateien). */
fun AbsUploadSource.Companion.fromUri(context: Context, uri: Uri): AbsUploadSource {
    val resolver = context.applicationContext.contentResolver
    var name: String? = null
    var size = -1L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            name = c.getString(0)
            if (!c.isNull(1)) size = c.getLong(1)
        }
    }
    if (size < 0) size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
    return AbsUploadSource(name ?: uri.lastPathSegment ?: "audio", size) {
        resolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    }
}
