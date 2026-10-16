package app.simplenarrator.testutil

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Kleiner HTTP/1.1-Server für Tests auf 127.0.0.1 (zufälliger Port). com.sun.net.httpserver steht beim
 * Kompilieren der Android-Unit-Tests nicht zur Verfügung (android.jar als Klassenpfad), deshalb ein eigener
 * auf java.net.ServerSocket. Eine Anfrage pro Verbindung, Antwort mit "Connection: close".
 */
class TinyHttpServer(private val handler: (Request) -> Response) : Closeable {
    class Request(
        val method: String,
        val path: String,
        val query: String?,
        /** Kopfzeilen mit kleingeschriebenen Namen. */
        val headers: Map<String, String>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]
        fun queryParams(): Map<String, String> = query.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            java.net.URLDecoder.decode(k, "UTF-8") to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    class Response(
        val code: Int = 200,
        val body: ByteArray = ByteArray(0),
        val contentType: String? = "application/json",
        val headers: Map<String, String> = emptyMap(),
    ) {
        constructor(code: Int, text: String, contentType: String? = "application/json; charset=utf-8", headers: Map<String, String> = emptyMap()) :
            this(code, text.toByteArray(), contentType, headers)
    }

    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val requests = CopyOnWriteArrayList<Request>()
    val baseUrl: String get() = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true, name = "tiny-http-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: SocketException) { break }
                thread(isDaemon = true, name = "tiny-http-conn") { serve(socket) }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use { s ->
        val input = s.getInputStream().buffered()
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", true) == true -> readChunked(input)
            headers["content-length"] != null -> readExactly(input, headers.getValue("content-length").toInt())
            else -> ByteArray(0)
        }
        val parts = requestLine.split(' ')
        val target = parts.getOrElse(1) { "/" }
        val req = Request(parts[0], target.substringBefore('?'), target.substringAfter('?', "").ifEmpty { null }, headers, body)
        requests += req
        val resp = try { handler(req) } catch (e: Throwable) { Response(500, (e.message ?: e.toString()), "text/plain") }
        val out = s.getOutputStream()
        val head = StringBuilder("HTTP/1.1 ${resp.code} X\r\n")
        resp.contentType?.let { head.append("Content-Type: $it\r\n") }
        resp.headers.forEach { (k, v) -> head.append("$k: $v\r\n") }
        head.append("Content-Length: ${resp.body.size}\r\nConnection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        out.write(resp.body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) return buf.toString("ISO-8859-1").trimEnd('\r')
            buf.write(b)
        }
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) break
            off += r
        }
        return out
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: break
            if (size == 0) { readLine(input); break }
            out.write(readExactly(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    override fun close() = server.close()
}
