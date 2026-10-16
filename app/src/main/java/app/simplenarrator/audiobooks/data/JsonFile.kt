package app.simplenarrator.audiobooks.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
}

/**
 * Liest und schreibt eine JSON-Datei atomar: erst in eine Nebendatei, dann umbenennen. So bleibt nach einem Absturz
 * beim Schreiben immer der vorige vollständige Stand.
 */
class JsonFile<T>(private val file: File, private val serializer: KSerializer<T>) {

    fun read(): T? = try {
        if (file.isFile) AppJson.decodeFromString(serializer, file.readText()) else null
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun write(value: T) {
        val bytes = AppJson.encodeToString(serializer, value).encodeToByteArray()
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".new")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
