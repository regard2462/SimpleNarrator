package app.simplenarrator.audiobooks.scan

import app.simplenarrator.audiobooks.data.AudiobookFiles
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import app.simplenarrator.audiobooks.data.CoverStore
import app.simplenarrator.audiobooks.data.SavedCover
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max

/** Speichert verkleinerte Titelbilder und bestimmt ihre Leitfarbe. */
class Covers(private val context: Context) : CoverStore {

    override val dir: File = File(AudiobookFiles.dir(context), "covers").apply { mkdirs() }

    override fun saveBytes(name: String, bytes: ByteArray): SavedCover? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return store(name, bmp)
    }

    private fun store(name: String, source: Bitmap): SavedCover? {
        val longest = max(source.width, source.height)
        val bmp = if (longest > MAX_SIDE) {
            val f = MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(source, (source.width * f).toInt().coerceAtLeast(1), (source.height * f).toInt().coerceAtLeast(1), true)
        } else source
        val file = File(dir, "$name.jpg")
        val tmp = File(dir, "$name.tmp")
        FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) return null
        }
        val color = dominantColor(bmp)
        if (bmp !== source) bmp.recycle()
        source.recycle()
        return SavedCover(file.absolutePath, color)
    }

    private fun sampleSize(w: Int, h: Int): Int {
        var s = 1
        while (max(w, h) / (s * 2) >= MAX_SIDE) s *= 2
        return s
    }

    companion object {
        private const val MAX_SIDE = 1024

        /** Leuchtende, häufige Farbe eines Bildes; bei Graustufen der Durchschnitt. */
        fun dominantColor(bmp: Bitmap): Int? {
            val small = Bitmap.createScaledBitmap(bmp, 32, 32, true)
            val bins = Array(12) { DoubleArray(4) } // Gewicht, r, g, b
            var ar = 0.0
            var ag = 0.0
            var ab = 0.0
            var n = 0
            val hsv = FloatArray(3)
            for (y in 0 until small.height) for (x in 0 until small.width) {
                val c = small.getPixel(x, y)
                if (Color.alpha(c) < 128) continue
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                ar += r; ag += g; ab += b; n++
                Color.RGBToHSV(r, g, b, hsv)
                val s = hsv[1]
                val v = hsv[2]
                if (s < 0.22f || v < 0.18f) continue
                val w = s * (1f - abs(v - 0.65f))
                val bin = bins[((hsv[0] / 30f).toInt()).coerceIn(0, 11)]
                bin[0] += w.toDouble(); bin[1] += r * w.toDouble(); bin[2] += g * w.toDouble(); bin[3] += b * w.toDouble()
            }
            if (small !== bmp) small.recycle()
            if (n == 0) return null
            val best = bins.maxByOrNull { it[0] }!!
            return if (best[0] > 0.6) {
                Color.rgb((best[1] / best[0]).toInt(), (best[2] / best[0]).toInt(), (best[3] / best[0]).toInt())
            } else {
                Color.rgb((ar / n).toInt(), (ag / n).toInt(), (ab / n).toInt())
            }
        }
    }
}
