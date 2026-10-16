package app.simplenarrator.tts

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Eine gemischte Kokoro-Stimme: Gewicht [weightA] von Stimme [a] und der Rest von [b]. Die Mischung liegt als
 * eigene voices-Datei im Paketordner (blends/<id>.bin), in der der Platz [slot] durch die Mischung ersetzt ist.
 */
@Serializable
data class Blend(val id: String, val name: String, val packId: String, val a: Int, val b: Int, val weightA: Float, val slot: Int = 0)

object Blends {
    private val json = Json { ignoreUnknownKeys = true }

    /** Bytes je Sprecher in einer Kokoro-voices.bin: float32 [510, 1, 256]. */
    private const val KOKORO_SPEAKER_BYTES = 510L * 256L * 4L

    fun parse(raw: String): List<Blend> = if (raw.isBlank()) emptyList() else runCatching { json.decodeFromString<List<Blend>>(raw) }.getOrDefault(emptyList())

    fun encode(list: List<Blend>): String = json.encodeToString(list)

    fun file(models: ModelManager, pack: VoicePack, blend: Blend): File = File(models.packDir(pack), "blends/${blend.id}.bin")

    /**
     * Legt die Datei einer neuen Mischung an. Die Mischung ersetzt den Platz von Stimme [a]; ausgewählt wird sie
     * deshalb mit der Sprecher-ID [a] und der eigenen voices-Datei.
     */
    fun create(models: ModelManager, pack: VoicePack, name: String, a: Int, b: Int, weightA: Float): Blend {
        val voices = File(models.packDir(pack), "voices.bin")
        val size = voices.length()
        if (size == 0L || size % KOKORO_SPEAKER_BYTES != 0L) throw IOException("Unexpected voices.bin size $size")
        val blend = Blend(UUID.randomUUID().toString().take(8), name.trim().ifBlank { "$a+$b" }, pack.id, a, b, weightA, slot = a)
        val out = file(models, pack, blend).apply { parentFile?.mkdirs() }
        VoiceBlend.blend(voices, (size / KOKORO_SPEAKER_BYTES).toInt(), a, b, weightA, blend.slot, out)
        return blend
    }

    fun delete(models: ModelManager, pack: VoicePack, blend: Blend) {
        file(models, pack, blend).delete()
    }
}
