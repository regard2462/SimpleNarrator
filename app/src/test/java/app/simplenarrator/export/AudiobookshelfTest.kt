package app.simplenarrator.export

import app.simplenarrator.testutil.TinyHttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Random

/** Audiobookshelf-Client gegen einen lokalen Testserver: Anmeldung, Bibliotheken, Multipart-Upload, Fehler. */
class AudiobookshelfTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val token = "abs-test-token-123"

    private val authorizeJson = """{"user":{"id":"usr_1","username":"julia","type":"admin","permissions":{"upload":true}},
        "userDefaultLibraryId":"lib_1","serverSettings":{"id":"server-settings","scannerFindCovers":false,"version":"2.17.2","language":"de"},
        "Source":"docker"}"""

    private val librariesJson = """{"libraries":[
        {"id":"lib_1","name":"Hörbücher","folders":[{"id":"fol_1","fullPath":"/audiobooks","libraryId":"lib_1","addedAt":1650462940610},
            {"id":"fol_2","fullPath":"/audiobooks2","libraryId":"lib_1","addedAt":1650462940611}],
         "displayOrder":1,"icon":"audiobookshelf","mediaType":"book","provider":"audible","settings":{"coverAspectRatio":1}},
        {"id":"lib_2","name":"Podcasts","folders":[{"id":"fol_3","fullPath":"/podcasts","libraryId":"lib_2"}],
         "displayOrder":2,"icon":"podcast","mediaType":"podcast","provider":"itunes"}]}"""

    /** Ein Teil aus multipart/form-data. */
    private data class Part(val name: String, val filename: String?, val contentType: String?, val data: ByteArray)

    /** Einfacher Multipart-Leser für die Prüfung (Bytes 1:1 über ISO-8859-1). */
    private fun parseMultipart(contentType: String, body: ByteArray): List<Part> {
        val boundary = contentType.substringAfter("boundary=").trim('"')
        val text = String(body, Charsets.ISO_8859_1)
        return text.split("--$boundary").drop(1).filterNot { it.startsWith("--") }.map { raw ->
            val chunk = raw.removePrefix("\r\n")
            val headerEnd = chunk.indexOf("\r\n\r\n")
            val headers = String(chunk.substring(0, headerEnd).toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
            val data = chunk.substring(headerEnd + 4).removeSuffix("\r\n").toByteArray(Charsets.ISO_8859_1)
            val disposition = headers.lines().first { it.startsWith("Content-Disposition", true) }
            val name = Regex("name=\"([^\"]*)\"").find(disposition)!!.groupValues[1]
            val filename = Regex("filename=\"([^\"]*)\"").find(disposition)?.groupValues?.get(1)
            val type = headers.lines().firstOrNull { it.startsWith("Content-Type", true) }?.substringAfter(':')?.trim()
            Part(name, filename, type, data)
        }
    }

    private fun absServer(onUpload: (TinyHttpServer.Request) -> TinyHttpServer.Response = { TinyHttpServer.Response(200, "OK", "text/plain") }) =
        TinyHttpServer { req ->
            val path = req.path.removePrefix("/abs")
            when {
                path == "/status" -> TinyHttpServer.Response(200, """{"app":"audiobookshelf","serverVersion":"2.30.0","isInit":true,"language":"de-de"}""")
                req.header("authorization") != "Bearer $token" -> TinyHttpServer.Response(401, "Unauthorized", "text/plain")
                path == "/api/authorize" && req.method == "POST" -> TinyHttpServer.Response(200, authorizeJson)
                path == "/api/libraries" && req.method == "GET" -> TinyHttpServer.Response(200, librariesJson)
                path == "/api/upload" && req.method == "POST" -> onUpload(req)
                else -> TinyHttpServer.Response(404, "Not Found", "text/plain")
            }
        }

    @Test
    fun pingSendsBearerAndReturnsVersion() = runBlocking {
        absServer().use { s ->
            assertEquals("2.17.2", Audiobookshelf(s.baseUrl, token, OkHttpClient()).ping())
            val req = s.requests.single()
            assertEquals("POST", req.method)
            assertEquals("/api/authorize", req.path)
            assertEquals("Bearer $token", req.header("authorization"))
        }
    }

    @Test
    fun pingFallsBackToStatusAndKeepsSubpath() = runBlocking {
        TinyHttpServer { req ->
            when (req.path) {
                "/abs/api/authorize" -> TinyHttpServer.Response(200, """{"user":{"id":"usr_1"}}""")
                "/abs/status" -> TinyHttpServer.Response(200, """{"app":"audiobookshelf","serverVersion":"2.30.0","isInit":true}""")
                else -> TinyHttpServer.Response(404, "Not Found", "text/plain")
            }
        }.use { s ->
            assertEquals("2.30.0", Audiobookshelf(s.baseUrl + "/abs/", token, OkHttpClient()).ping())
            assertEquals(listOf("/abs/api/authorize", "/abs/status"), s.requests.map { it.path })
            assertNull(s.requests[1].header("authorization"))
        }
    }

    @Test
    fun wrongTokenIsUnauthorized() = runBlocking {
        absServer().use { s ->
            try {
                Audiobookshelf(s.baseUrl, "falsch", OkHttpClient()).ping()
                fail("expected AbsException")
            } catch (e: AbsException) {
                assertEquals(AbsException.Reason.UNAUTHORIZED, e.reason)
                assertEquals(401, e.httpCode)
            }
        }
    }

    @Test
    fun librariesOnlyBooksWithFolders() = runBlocking {
        absServer().use { s ->
            val libs = Audiobookshelf(s.baseUrl, token, OkHttpClient()).libraries()
            assertEquals(
                listOf(AbsLibrary("lib_1", "Hörbücher", "book", listOf(AbsFolder("fol_1", "/audiobooks"), AbsFolder("fol_2", "/audiobooks2")))),
                libs,
            )
            assertEquals("Bearer $token", s.requests.single().header("authorization"))
        }
    }

    @Test
    fun notFoundAndNoJson() = runBlocking {
        TinyHttpServer { req ->
            if (req.path == "/api/libraries") TinyHttpServer.Response(200, "<html><body>Router login</body></html>", "text/html")
            else TinyHttpServer.Response(404, "Cannot POST /api/authorize", "text/html")
        }.use { s ->
            val abs = Audiobookshelf(s.baseUrl, token, OkHttpClient())
            try {
                abs.ping(); fail("expected AbsException")
            } catch (e: AbsException) {
                assertEquals(AbsException.Reason.NOT_FOUND, e.reason)
                assertTrue(e.message!!.contains("404"))
            }
            try {
                abs.libraries(); fail("expected AbsException")
            } catch (e: AbsException) {
                assertEquals(AbsException.Reason.BAD_RESPONSE, e.reason)
            }
        }
    }

    @Test
    fun connectionRefused() = runBlocking {
        val s = absServer()
        val url = s.baseUrl
        s.close()
        try {
            Audiobookshelf(url, token, OkHttpClient()).libraries(); fail("expected AbsException")
        } catch (e: AbsException) {
            assertEquals(AbsException.Reason.CONNECTION, e.reason)
        }
    }

    @Test
    fun invalidUrl() = runBlocking {
        try {
            Audiobookshelf("ftp://nas/abs", token, OkHttpClient()).ping(); fail("expected AbsException")
        } catch (e: AbsException) {
            assertEquals(AbsException.Reason.INVALID_URL, e.reason)
        }
    }

    @Test
    fun uploadSendsMultipartFieldsAndFiles() = runBlocking {
        val f1 = tmp.newFile("01 - Anfang.m4a").apply { writeBytes(ByteArray(250_000).also { Random(7).nextBytes(it) }) }
        val f2 = tmp.newFile("02 - Kapitel Ü.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val f3 = tmp.newFile("Kapitelliste.txt").apply { writeText("00:00:00  Anfang\n") }
        var parts: List<Part> = emptyList()
        var contentType = ""
        absServer { req ->
            contentType = req.header("content-type").orEmpty()
            parts = parseMultipart(contentType, req.body)
            TinyHttpServer.Response(200, "OK", "text/plain")
        }.use { s ->
            val progress = mutableListOf<Float>()
            Audiobookshelf(s.baseUrl, token, OkHttpClient())
                .upload("lib_1", "fol_1", "Mein Buch", "Anna Muster", listOf(f1, f2, f3)) { progress += it }
            val req = s.requests.single()
            assertEquals("POST", req.method)
            assertEquals("/api/upload", req.path)
            assertEquals("Bearer $token", req.header("authorization"))
            assertTrue(contentType, contentType.startsWith("multipart/form-data"))

            val fields = parts.filter { it.filename == null }.associate { it.name to String(it.data, Charsets.UTF_8) }
            assertEquals(mapOf("title" to "Mein Buch", "author" to "Anna Muster", "series" to "", "library" to "lib_1", "folder" to "fol_1"), fields)

            val files = parts.filter { it.filename != null }
            assertEquals(listOf("0", "1", "2"), files.map { it.name })
            assertEquals(listOf("01 - Anfang.m4a", "02 - Kapitel Ü.m4a", "Kapitelliste.txt"), files.map { it.filename })
            assertEquals(listOf("audio/mp4", "audio/mp4", "text/plain"), files.map { it.contentType })
            assertArrayEquals(f1.readBytes(), files[0].data)
            assertArrayEquals(f2.readBytes(), files[1].data)
            assertArrayEquals(f3.readBytes(), files[2].data)

            assertEquals(1f, progress.last())
            assertEquals(progress.sorted(), progress)
            assertTrue(progress.size > 2)
        }
    }

    @Test
    fun uploadWithoutPermission() = runBlocking {
        val f = tmp.newFile("a.mp3").apply { writeBytes(byteArrayOf(9, 9)) }
        absServer { TinyHttpServer.Response(403, "Forbidden", "text/plain") }.use { s ->
            try {
                Audiobookshelf(s.baseUrl, token, OkHttpClient()).upload("lib_1", "fol_1", "T", "A", listOf(f)) {}
                fail("expected AbsException")
            } catch (e: AbsException) {
                assertEquals(AbsException.Reason.FORBIDDEN, e.reason)
                assertTrue(e.message!!.contains("Forbidden"))
            }
        }
    }

    @Test
    fun baseUrlNormalization() {
        assertEquals("https://abs.example.com/", Audiobookshelf.normalizeBaseUrl("abs.example.com")?.toString())
        assertEquals("http://192.168.1.5:13378/", Audiobookshelf.normalizeBaseUrl(" 192.168.1.5:13378/ ")?.toString())
        assertEquals("http://nas.local:13378/", Audiobookshelf.normalizeBaseUrl("nas.local:13378")?.toString())
        assertEquals("https://example.com/abs", Audiobookshelf.normalizeBaseUrl("https://example.com/abs/")?.toString())
        assertNull(Audiobookshelf.normalizeBaseUrl("ftp://example.com"))
        assertNull(Audiobookshelf.normalizeBaseUrl("  "))
    }

    @Test
    fun uploadSourceFromFile() {
        val f = File(tmp.root, "x.m4b").apply { writeBytes(ByteArray(10)) }
        val src = AbsUploadSource.of(f)
        assertEquals("x.m4b", src.name)
        assertEquals(10L, src.length)
        assertEquals("audio/mp4", Audiobookshelf.mimeOf(src.name).toString())
    }
}
