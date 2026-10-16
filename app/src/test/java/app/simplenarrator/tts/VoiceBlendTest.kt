package app.simplenarrator.tts

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceBlendTest {
    @get:Rule
    val tmp = TemporaryFolder()

    // 4 Sprecher × [10, 1, 8] float32 statt [510, 1, 256]
    private val speakers = 4
    private val perSpeaker = 10 * 8

    private fun value(s: Int, i: Int) = s * 1000f + i * 0.5f

    private fun voices(): File {
        val b = ByteBuffer.allocate(speakers * perSpeaker * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (s in 0 until speakers) for (i in 0 until perSpeaker) b.putFloat(value(s, i))
        return tmp.newFile("voices.bin").apply { writeBytes(b.array()) }
    }

    private fun floats(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.remaining() / 4) { b.getFloat(it * 4) }
    }

    @Test
    fun blendIntoSlot() {
        val src = voices()
        val out = File(tmp.root, "blend.bin")
        VoiceBlend.blend(src, speakers, a = 1, b = 3, weightA = 0.25f, slot = 2, out = out)
        assertEquals(src.length(), out.length())
        val v = floats(out)
        val orig = floats(src)
        for (s in 0 until speakers) for (i in 0 until perSpeaker) {
            val idx = s * perSpeaker + i
            if (s == 2) assertEquals(0.25f * value(1, i) + 0.75f * value(3, i), v[idx], 1e-3f)
            else assertEquals(orig[idx], v[idx], 0f)
        }
    }

    @Test
    fun inPlaceAndSlotEqualsSource() {
        val src = voices()
        val before = src.readBytes()
        VoiceBlend.blend(src, speakers, a = 0, b = 1, weightA = 1f, slot = 0, out = src)
        assertArrayEquals(before, src.readBytes())   // 1·A + 0·B auf A selbst ändert nichts
        VoiceBlend.blend(src, speakers, a = 0, b = 1, weightA = 0.5f, slot = 0, out = src)
        val v = floats(src)
        assertEquals(0.5f * value(0, 3) + 0.5f * value(1, 3), v[3], 1e-3f)
        assertEquals(value(1, 3), v[perSpeaker + 3], 0f)
    }

    @Test
    fun wrongSpeakerCount() {
        val src = voices()
        try {
            VoiceBlend.blend(src, 3, 0, 1, 0.5f, 2, File(tmp.root, "x.bin")); fail()
        } catch (e: IOException) {
            // 4 × 80 Floats lassen sich nicht auf 3 Sprecher aufteilen
        }
        try {
            VoiceBlend.blend(src, speakers, 0, 4, 0.5f, 2, File(tmp.root, "x.bin")); fail()
        } catch (e: IllegalArgumentException) {
            // Index außerhalb
        }
    }
}
