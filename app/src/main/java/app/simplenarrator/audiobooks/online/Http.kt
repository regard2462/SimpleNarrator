package app.simplenarrator.audiobooks.online

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Ein Katalog hat abgelehnt oder ist überlastet; später erneut versuchen. */
class BusyException(host: String, code: Int) : IOException("$host antwortet $code")

/**
 * Schlanker HTTP-Abruf mit Abstand je Host, damit die Kataloge nicht mit Anfragen überhäuft werden.
 * Apple erlaubt etwa 20 Suchen pro Minute, deshalb dort gut drei Sekunden Abstand.
 */
class Http(private val userAgent: String) {

    private val mutex = Mutex()
    private val last = HashMap<String, Long>()

    private fun gapFor(host: String): Long = when {
        host == "itunes.apple.com" -> 3_200
        host.startsWith("api.audible.") -> 1_000
        host == "openlibrary.org" -> 1_000
        else -> 150
    }

    private suspend fun waitTurn(host: String) {
        val wait = mutex.withLock {
            val now = System.currentTimeMillis()
            val next = maxOf(now, (last[host] ?: 0L) + gapFor(host))
            last[host] = next
            next - now
        }
        if (wait > 0) delay(wait)
    }

    /**
     * Lädt eine Adresse. Liefert null bei 404 und ähnlichen endgültigen Antworten, wirft [IOException]
     * ohne Netz und [BusyException] bei 429 oder 5xx.
     */
    suspend fun get(url: String, maxBytes: Int = 6 shl 20, accept: String = "*/*"): ByteArray? = withContext(Dispatchers.IO) {
        val u = URL(url)
        waitTurn(u.host)
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", userAgent)
            c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code == 429 || code >= 500) throw BusyException(u.host, code)
            if (code !in 200..299) return@withContext null
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) throw IOException("Antwort zu groß")
                }
            }
            out.toByteArray()
        } finally {
            c.disconnect()
        }
    }

    suspend fun text(url: String): String? = get(url, maxBytes = 2 shl 20, accept = "application/json")?.decodeToString()
}
