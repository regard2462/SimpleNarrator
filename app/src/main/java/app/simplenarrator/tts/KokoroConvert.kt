package app.simplenarrator.tts

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * Macht "kokoro-onnx"-Exporte (model.onnx + voices.npz, wie das deutsche Martin-Modell)
 * für sherpa-onnx nutzbar:
 *  1. voices.npz (Zip aus .npy-Arrays [510,1,256]) → voices.bin (rohe float32, little-endian)
 *  2. tokens.txt aus dem Standard-Kokoro-Vokabular schreiben
 *  3. fehlende Metadaten (sample_rate, n_speakers, style_dim, …) an die ONNX-Datei anhängen
 *     – protobuf erlaubt das Anhängen weiterer "metadata_props"-Einträge (Feld 14 von ModelProto),
 *     ohne die 300 MB neu zu schreiben.
 */
object KokoroConvert {
    const val SAMPLE_RATE = 24000
    private const val MARKER = ".sherpa-ready"

    /** Standard-Kokoro-Vokabular (misaki / kokoro-onnx config.json). Reihenfolge egal, IDs zählen. */
    private val VOCAB: List<Pair<String, Int>> = listOf(
        ";" to 1, ":" to 2, "," to 3, "." to 4, "!" to 5, "?" to 6, "—" to 9, "…" to 10,
        "\"" to 11, "(" to 12, ")" to 13, "“" to 14, "”" to 15, " " to 16, "\u0303" to 17, "ʣ" to 18,
        "ʥ" to 19, "ʦ" to 20, "ʨ" to 21, "ᵝ" to 22, "ꭧ" to 23, "A" to 24, "I" to 25, "O" to 31,
        "Q" to 33, "S" to 35, "T" to 36, "W" to 39, "Y" to 41, "ᵊ" to 42, "a" to 43, "b" to 44,
        "c" to 45, "d" to 46, "e" to 47, "f" to 48, "h" to 50, "i" to 51, "j" to 52, "k" to 53,
        "l" to 54, "m" to 55, "n" to 56, "o" to 57, "p" to 58, "q" to 59, "r" to 60, "s" to 61,
        "t" to 62, "u" to 63, "v" to 64, "w" to 65, "x" to 66, "y" to 67, "z" to 68, "ɑ" to 69,
        "ɐ" to 70, "ɒ" to 71, "æ" to 72, "β" to 75, "ɔ" to 76, "ɕ" to 77, "ç" to 78, "ɖ" to 80,
        "ð" to 81, "ʤ" to 82, "ə" to 83, "ɚ" to 85, "ɛ" to 86, "ɜ" to 87, "ɟ" to 90, "ɡ" to 92,
        "ɥ" to 99, "ɨ" to 101, "ɪ" to 102, "ʝ" to 103, "ɯ" to 110, "ɰ" to 111, "ŋ" to 112, "ɳ" to 113,
        "ɲ" to 114, "ɴ" to 115, "ø" to 116, "ɸ" to 118, "θ" to 119, "œ" to 120, "ɹ" to 123, "ɾ" to 125,
        "ɻ" to 126, "ʁ" to 128, "ɽ" to 129, "ʂ" to 130, "ʃ" to 131, "ʈ" to 132, "ʧ" to 133, "ʊ" to 135,
        "ʋ" to 136, "ʌ" to 138, "ɣ" to 139, "ɤ" to 140, "χ" to 142, "ʎ" to 143, "ʒ" to 147, "ʔ" to 148,
        "ˈ" to 156, "ˌ" to 157, "ː" to 158, "ʰ" to 162, "ʲ" to 164, "↓" to 169, "→" to 171, "↗" to 172,
        "↘" to 173, "ᵻ" to 177,
    )

    data class NpyArray(val name: String, val shape: List<Int>, val floats: FloatArray)

    /** Führt alle Schritte aus; liefert die Stimmennamen (Reihenfolge = Sprecher-ID). Idempotent. */
    fun prepare(dir: File, npzName: String, espeakVoice: String): List<String> {
        val model = File(dir, "model.onnx")
        val npz = File(dir, npzName)
        if (!model.isFile) error("model.onnx missing")
        if (!npz.isFile) error("$npzName missing")

        val arrays = readNpz(npz)
        if (arrays.isEmpty()) error("$npzName contains no arrays")
        val ref = arrays.first()
        // Erlaubt (510,1,256) oder (510,256); alle Stimmen müssen gleich geformt sein
        val dims = ref.shape.filter { it != 1 }
        if (dims.size != 2) error("Unexpected voice shape ${ref.shape}")
        val (maxLen, styleDim) = dims
        arrays.forEach { if (it.floats.size != maxLen * styleDim) error("Voice ${it.name}: wrong size") }

        val voicesBin = File(dir, "voices.bin")
        val tmp = File(dir, "voices.bin.part")
        tmp.outputStream().buffered().use { out ->
            val buf = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)
            for (a in arrays) {
                var i = 0
                while (i < a.floats.size) {
                    buf.clear()
                    val n = minOf(4096, a.floats.size - i)
                    for (k in 0 until n) buf.putFloat(a.floats[i + k])
                    out.write(buf.array(), 0, n * 4)
                    i += n
                }
            }
        }
        if (voicesBin.exists()) voicesBin.delete()
        tmp.renameTo(voicesBin)

        writeTokens(File(dir, "tokens.txt"))

        val marker = File(dir, MARKER)
        if (!marker.exists()) {
            appendOnnxMetadata(
                model, linkedMapOf(
                    "model_type" to "kokoro",
                    "version" to "1",
                    "sample_rate" to SAMPLE_RATE.toString(),
                    "n_speakers" to arrays.size.toString(),
                    "has_espeak" to "1",
                    "voice" to espeakVoice,
                    "style_dim" to "$maxLen,1,$styleDim",
                    "speaker_names" to arrays.joinToString(",") { it.name },
                    "comment" to "metadata appended by Simple Narrator for sherpa-onnx",
                )
            )
            marker.writeText(arrays.joinToString("\n") { it.name })
        }
        return arrays.map { it.name }
    }

    fun writeTokens(out: File) {
        out.bufferedWriter(Charsets.UTF_8).use { w ->
            for ((sym, id) in VOCAB) {
                // sherpa-onnx: Zeile mit nur einer Zahl = Leerzeichen-Token
                if (sym == " ") w.write("$id\n") else w.write("$sym $id\n")
            }
        }
    }

    // ------------------------------------------------------------------ .npz / .npy

    fun readNpz(npz: File): List<NpyArray> = ZipFile(npz).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".npy") }
            .sortedBy { it.name }
            .map { e -> DataInputStream(zip.getInputStream(e).buffered()).use { readNpy(e.name.removeSuffix(".npy"), it) } }
            .toList()
    }

    private fun readNpy(name: String, din: DataInputStream): NpyArray {
        val magic = ByteArray(6); din.readFully(magic)
        if (magic[0] != 0x93.toByte() || String(magic, 1, 5, Charsets.US_ASCII) != "NUMPY") error("$name: not an .npy file")
        val major = din.readUnsignedByte(); din.readUnsignedByte() // minor
        val headerLen = if (major == 1) {
            val b = ByteArray(2); din.readFully(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        } else {
            val b = ByteArray(4); din.readFully(b); ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val header = ByteArray(headerLen).also { din.readFully(it) }.toString(Charsets.ISO_8859_1)
        val descr = Regex("'descr'\\s*:\\s*'([^']+)'").find(header)?.groupValues?.get(1) ?: error("$name: descr missing")
        if (Regex("'fortran_order'\\s*:\\s*True").containsMatchIn(header)) error("$name: Fortran order not supported")
        val shape = Regex("'shape'\\s*:\\s*\\(([^)]*)\\)").find(header)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.map { it.toInt() } ?: error("$name: shape missing")
        val count = shape.fold(1) { a, b -> a * b }
        val order = if (descr.startsWith(">")) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val width = when (descr.drop(1)) { "f4" -> 4; "f2" -> 2; "f8" -> 8; else -> error("$name: dtype $descr not supported") }
        val raw = ByteArray(count * width).also { din.readFully(it) }
        val bb = ByteBuffer.wrap(raw).order(order)
        val floats = FloatArray(count)
        when (width) {
            4 -> bb.asFloatBuffer().get(floats)
            8 -> for (i in 0 until count) floats[i] = bb.getDouble(i * 8).toFloat()
            2 -> for (i in 0 until count) floats[i] = halfToFloat(bb.getShort(i * 2).toInt() and 0xFFFF)
        }
        return NpyArray(name, shape, floats)
    }

    private fun halfToFloat(h: Int): Float {
        val sign = (h ushr 15) and 1
        val exp = (h ushr 10) and 0x1F
        val mant = h and 0x3FF
        val bits = when (exp) {
            0 -> if (mant == 0) sign shl 31 else { // subnormal
                var m = mant; var e = -1
                do { e++; m = m shl 1 } while (m and 0x400 == 0)
                (sign shl 31) or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            0x1F -> (sign shl 31) or 0x7F800000 or (mant shl 13)
            else -> (sign shl 31) or ((exp - 15 + 127) shl 23) or (mant shl 13)
        }
        return java.lang.Float.intBitsToFloat(bits)
    }

    // ------------------------------------------------------------------ ONNX-Metadaten (protobuf)

    /** Hängt metadata_props (ModelProto Feld 14, StringStringEntryProto key=1/value=2) an die Datei an. */
    fun appendOnnxMetadata(onnx: File, props: Map<String, String>) {
        val out = ByteArrayOutputStream()
        for ((k, v) in props) {
            val entry = ByteArrayOutputStream()
            writeBytesField(entry, 1, k.toByteArray(Charsets.UTF_8))
            writeBytesField(entry, 2, v.toByteArray(Charsets.UTF_8))
            writeBytesField(out, 14, entry.toByteArray())
        }
        FileOutputStream(onnx, true).use { it.write(out.toByteArray()) }
    }

    private fun writeBytesField(o: OutputStream, field: Int, bytes: ByteArray) {
        writeVarint(o, (field shl 3) or 2)
        writeVarint(o, bytes.size)
        o.write(bytes)
    }

    private fun writeVarint(o: OutputStream, value: Int) {
        var v = value
        while (v and 0x7F.inv() != 0) { o.write((v and 0x7F) or 0x80); v = v ushr 7 }
        o.write(v)
    }
}
