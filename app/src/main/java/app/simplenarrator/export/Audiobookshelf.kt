package app.simplenarrator.export

import app.simplenarrator.util.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnknownServiceException
import java.util.concurrent.TimeUnit

data class AbsFolder(val id: String, val fullPath: String)

data class AbsLibrary(val id: String, val name: String, val mediaType: String, val folders: List<AbsFolder>)

/** Eine hochzuladende Datei; open() wird bei jedem Sendeversuch neu aufgerufen. */
class AbsUploadSource(val name: String, val length: Long, val open: () -> InputStream) {
    companion object {
        fun of(file: File) = AbsUploadSource(file.name, file.length()) { file.inputStream() }
    }
}

class AbsException(
    val reason: Reason,
    message: String,
    val httpCode: Int = 0,
    cause: Throwable? = null,
) : IOException(message, cause) {
    enum class Reason { INVALID_URL, CONNECTION, CLEARTEXT, UNAUTHORIZED, FORBIDDEN, NOT_FOUND, HTTP, BAD_RESPONSE }
}

/**
 * Client für einen Audiobookshelf-Server (https://api.audiobookshelf.org/, Quelltext advplyr/audiobookshelf).
 * Anmeldung mit dem API-Schlüssel des Benutzers als "Authorization: Bearer <token>".
 *
 * Hochladen: POST /api/upload als multipart/form-data mit den Feldern title, author, series, library (ID),
 * folder (ID) und den Dateien unter den Feldnamen "0", "1", … (wie die Weboberfläche; der Server nimmt
 * alle Dateifelder). Der Server legt sie unter <Ordner>/<Autor>/<Serie>/<Titel>/ ab; leere Teile entfallen.
 */
class Audiobookshelf(baseUrl: String, private val token: String, private val http: OkHttpClient) {
    private val base: HttpUrl? = normalizeBaseUrl(baseUrl)
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    private data class FolderDto(val id: String, val fullPath: String = "")

    @Serializable
    private data class LibraryDto(val id: String, val name: String = "", val mediaType: String = "book", val folders: List<FolderDto> = emptyList())

    /**
     * Verbindungstest mit Anmeldung: POST /api/authorize liefert serverSettings.version.
     * Fehlt die Version dort, kommt sie aus GET /status (serverVersion).
     */
    suspend fun ping(): String = withContext(Dispatchers.IO) {
        val auth = send(request("api/authorize").post(ByteArray(0).toRequestBody(null)).build()).use { readJson(it) }
        val fromAuth = ((auth as? JsonObject)?.get("serverSettings") as? JsonObject)?.get("version")?.jsonPrimitive?.contentOrNull
        if (!fromAuth.isNullOrBlank()) return@withContext fromAuth
        val status = send(request("status", authorized = false).get().build()).use { readJson(it) }
        (status as? JsonObject)?.get("serverVersion")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AbsException(AbsException.Reason.BAD_RESPONSE, "No server version in the response")
    }

    /** Bibliotheken für Hörbücher (mediaType "book"); Podcast-Bibliotheken fallen weg. */
    suspend fun libraries(): List<AbsLibrary> = withContext(Dispatchers.IO) {
        val root = send(request("api/libraries").get().build()).use { readJson(it) }
        // Neuere Server: {"libraries":[…]}, sehr alte: direkt ein Array
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root["libraries"] as? JsonArray
            else -> null
        } ?: throw AbsException(AbsException.Reason.BAD_RESPONSE, "No library list in the response")
        val list = try {
            json.decodeFromJsonElement(ListSerializer(LibraryDto.serializer()), array)
        } catch (e: SerializationException) {
            throw AbsException(AbsException.Reason.BAD_RESPONSE, "Unexpected library list", cause = e)
        } catch (e: IllegalArgumentException) {
            throw AbsException(AbsException.Reason.BAD_RESPONSE, "Unexpected library list", cause = e)
        }
        list.filter { it.mediaType == "book" }
            .map { l -> AbsLibrary(l.id, l.name, l.mediaType, l.folders.map { AbsFolder(it.id, it.fullPath) }) }
    }

    /** Dateien als ein Buch hochladen; onProgress bekommt 0..1 über alle Dateien. */
    suspend fun upload(
        libraryId: String,
        folderId: String,
        title: String,
        author: String,
        files: List<File>,
        series: String = "",
        onProgress: (Float) -> Unit,
    ) = uploadSources(libraryId, folderId, title, author, files.map { AbsUploadSource.of(it) }, series, onProgress)

    /** Wie upload, aber für beliebige Quellen (z. B. content://-Adressen aus dem Downloads-Ordner). */
    suspend fun uploadSources(
        libraryId: String,
        folderId: String,
        title: String,
        author: String,
        sources: List<AbsUploadSource>,
        series: String = "",
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        if (sources.isEmpty()) throw IllegalArgumentException("No files to upload")
        val total = sources.sumOf { it.length.coerceAtLeast(0) }.coerceAtLeast(1)
        val sent = LongArray(sources.size)
        var lastPermille = -1
        fun report() {
            val permille = (sent.sum() * 1000 / total).toInt().coerceIn(0, 1000)
            if (permille != lastPermille) { lastPermille = permille; onProgress(permille / 1000f) }
        }
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("title", title.trim())
            .addFormDataPart("author", author.trim())
            .addFormDataPart("series", series.trim())
            .addFormDataPart("library", libraryId)
            .addFormDataPart("folder", folderId)
        sources.forEachIndexed { i, src ->
            val body = SourceBody(src, mimeOf(src.name),
                onStart = { if (i == 0) sent.fill(0) else sent[i] = 0; report() },
                onBytes = { n -> sent[i] += n; report() })
            form.addFormDataPart(i.toString(), src.name, body)
        }
        // Große Hörbücher: großzügige Zeitgrenzen, der Server verschiebt die Dateien erst nach dem Empfang
        val client = http.newBuilder().writeTimeout(2, TimeUnit.MINUTES).readTimeout(10, TimeUnit.MINUTES).build()
        send(request("api/upload").post(form.build()).build(), client).close()
        onProgress(1f)
    }

    private fun request(path: String, authorized: Boolean = true): Request.Builder {
        val url = (base ?: throw AbsException(AbsException.Reason.INVALID_URL, "Invalid server address"))
            .newBuilder().addPathSegments(path).build()
        return Request.Builder().url(url).apply {
            header("Accept", "application/json")
            if (authorized) header("Authorization", "Bearer ${token.trim()}")
        }
    }

    /** Ausführen und Fehler einordnen: 401, 403, 404, Verbindung, unverschlüsseltes HTTP. */
    private suspend fun send(request: Request, client: OkHttpClient = http): Response {
        val resp = try {
            client.newCall(request).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnknownServiceException) {
            // Android blockiert http:// ohne Freigabe in der Netzwerkkonfiguration
            throw AbsException(AbsException.Reason.CLEARTEXT, e.message ?: "Cleartext HTTP not permitted", cause = e)
        } catch (e: IOException) {
            throw AbsException(AbsException.Reason.CONNECTION, e.message ?: e.javaClass.simpleName, cause = e)
        }
        if (resp.isSuccessful) return resp
        val detail = resp.use { r -> runCatching { r.body?.string() }.getOrNull().orEmpty() }
            .replace(Regex("\\s+"), " ").trim().take(200)
        val path = request.url.encodedPath
        val msg = "HTTP ${resp.code} for $path" + if (detail.isNotEmpty() && !detail.startsWith("<")) ": $detail" else ""
        throw when (resp.code) {
            401 -> AbsException(AbsException.Reason.UNAUTHORIZED, msg, 401)
            403 -> AbsException(AbsException.Reason.FORBIDDEN, msg, 403)
            404 -> AbsException(AbsException.Reason.NOT_FOUND, msg, 404)
            else -> AbsException(AbsException.Reason.HTTP, msg, resp.code)
        }
    }

    private fun readJson(resp: Response): JsonElement {
        val text = resp.body?.string().orEmpty()
        return try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw AbsException(AbsException.Reason.BAD_RESPONSE, "The server did not answer with JSON (no Audiobookshelf server at this address?)", cause = e)
        } catch (e: IllegalArgumentException) {
            throw AbsException(AbsException.Reason.BAD_RESPONSE, "The server did not answer with JSON (no Audiobookshelf server at this address?)", cause = e)
        }
    }

    /** Datei-Teil, der beim Schreiben den Fortschritt meldet. */
    private class SourceBody(
        private val src: AbsUploadSource,
        private val type: MediaType,
        private val onStart: () -> Unit,
        private val onBytes: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType(): MediaType = type
        override fun contentLength(): Long = src.length
        override fun writeTo(sink: BufferedSink) {
            onStart()
            src.open().source().use { input ->
                while (true) {
                    val n = input.read(sink.buffer, 64L * 1024)
                    if (n == -1L) break
                    sink.emit()
                    onBytes(n)
                }
            }
        }
    }

    companion object {
        /**
         * Serveradresse vereinheitlichen. Ohne Schema: http:// für IP-Adressen, "localhost" und Adressen mit Port
         * (typisch im Heimnetz, z. B. 192.168.1.5:13378), sonst https://. Ein Pfad (Unterverzeichnis) bleibt erhalten.
         */
        fun normalizeBaseUrl(input: String): HttpUrl? {
            val s = input.trim().trimEnd('/')
            if (s.isEmpty()) return null
            val withScheme = when {
                s.startsWith("http://", true) || s.startsWith("https://", true) -> s
                s.contains("://") -> return null
                else -> {
                    val hostPart = s.substringBefore('/')
                    val local = hostPart.contains(':') || hostPart.startsWith("localhost") ||
                        Regex("^\\d{1,3}(\\.\\d{1,3}){3}$").matches(hostPart)
                    (if (local) "http://" else "https://") + s
                }
            }
            return withScheme.toHttpUrlOrNull()
        }

        internal fun mimeOf(name: String): MediaType = when (name.substringAfterLast('.', "").lowercase()) {
            "m4a", "m4b" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "txt" -> "text/plain"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "epub" -> "application/epub+zip"
            "pdf" -> "application/pdf"
            else -> "application/octet-stream"
        }.toMediaType()
    }
}
