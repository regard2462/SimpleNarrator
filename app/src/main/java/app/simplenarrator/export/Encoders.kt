package app.simplenarrator.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import androidx.annotation.StringRes
import app.simplenarrator.R
import de.sciss.jump3r.mp3.BitStream
import de.sciss.jump3r.mp3.GainAnalysis
import de.sciss.jump3r.mp3.GetAudio
import de.sciss.jump3r.mp3.ID3Tag
import de.sciss.jump3r.mp3.Lame
import de.sciss.jump3r.mp3.MPEGMode
import de.sciss.jump3r.mp3.Parse
import de.sciss.jump3r.mp3.Presets
import de.sciss.jump3r.mp3.Quantize
import de.sciss.jump3r.mp3.QuantizePVT
import de.sciss.jump3r.mp3.Reservoir
import de.sciss.jump3r.mp3.Takehiro
import de.sciss.jump3r.mp3.VBRTag
import de.sciss.jump3r.mp3.Version
import de.sciss.jump3r.mpg.Common
import de.sciss.jump3r.mpg.Interface
import de.sciss.jump3r.mpg.MPGLib
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

enum class ExportFormat(val ext: String, @StringRes val label: Int, val mime: String) {
    MP3("mp3", R.string.export_format_mp3, "audio/mpeg"),
    M4A("m4a", R.string.export_format_m4a, "audio/mp4"),
    /** Wie M4A, immer eine Datei fürs ganze Buch mit Kapitelmarken. */
    M4B("m4b", R.string.export_format_m4b, "audio/mp4"),
    WAV("wav", R.string.export_format_wav, "audio/wav"),
}

/** Roh-PCM (16-bit mono) → AAC in MP4-Container über die Android-Hardware/Software-Codecs. */
object AacEncoder {
    fun encode(pcm: File, sampleRate: Int, out: File, bitrate: Int = 64_000) {
        if (pcm.length() < 2) error("No audio data to encode")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var muxerStarted = false
        val info = MediaCodec.BufferInfo()
        val buf = ByteArray(8192)
        var eosSent = false
        var ptsUs = 0L
        val bytesPerSec = sampleRate * 2L
        pcm.inputStream().buffered().use { input ->
            while (true) {
                if (!eosSent) {
                    val idx = codec.dequeueInputBuffer(10_000)
                    if (idx >= 0) {
                        val ib = codec.getInputBuffer(idx)!!
                        ib.clear()
                        val n = input.read(buf, 0, minOf(buf.size, ib.capacity()))
                        if (n <= 0) {
                            codec.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM); eosSent = true
                        } else {
                            ib.put(buf, 0, n)
                            codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                            ptsUs += n * 1_000_000L / bytesPerSec
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat); muxer.start(); muxerStarted = true
                } else if (outIdx >= 0) {
                    val ob = codec.getOutputBuffer(outIdx)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        }
        codec.stop(); codec.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }
}

/**
 * MP3 über jump3r (Java-Port von LAME 3.98, rein JVM: läuft ohne NDK, ist aber langsamer als nativ).
 *
 * Die LAME-Bausteine werden direkt verdrahtet und das Roh-PCM blockweise gelesen, ohne Zwischen-WAV.
 * jump3rs eigene Hüllen taugen unter Android nicht: lowlevel.LameEncoder braucht javax.sound.sampled.AudioFormat,
 * das es dort nicht gibt, und Main ist eine Kommandozeile mit Dateipfaden. Die Pakete mp3 und mpg nutzen nur
 * java.io und java.util.
 *
 * Konstante Bitrate, Mono. Der erste Rahmen ist LAMEs Info-Tag (Rahmenzahl, Encoder-Verzögerung für
 * lückenlose Wiedergabe); er wird am Ende eingesetzt. Ein ID3-Tag schreibt danach [Id3Tag].
 */
object Mp3Encoder {
    /** LAME-Qualität 0 (gründlich) bis 9 (schnell); 5 ist LAMEs mittlere Stufe und genügt für Sprache. */
    private const val QUALITY = 5
    /** Proben pro Aufruf; ein Vielfaches der Rahmenlänge (576 bzw. 1152). */
    private const val CHUNK = 1152 * 16

    fun encode(pcm: File, sampleRate: Int, out: File, bitrateKbps: Int) {
        if (pcm.length() < 2) error("No audio data to encode")
        val lame = Lame()
        val vbr = VBRTag()
        wire(lame, vbr)
        val gfp = lame.lame_init() ?: error("MP3 encoder could not be initialised")
        gfp.num_channels = 1
        gfp.in_samplerate = sampleRate
        gfp.mode = MPEGMode.MONO
        gfp.brate = bitrateKbps
        gfp.quality = QUALITY
        gfp.findReplayGain = false
        gfp.write_id3tag_automatic = false
        if (lame.lame_init_params(gfp) < 0) error("MP3 encoder does not support $sampleRate Hz / $bitrateKbps kbit/s")

        val mp3buf = ByteArray(Lame.LAME_MAXMP3BUFFER)
        val raw = ByteArray(CHUNK * 2)
        val samples = IntArray(CHUNK)
        try {
            out.outputStream().buffered(1 shl 16).use { o ->
                pcm.inputStream().buffered(1 shl 16).use { input ->
                    var eof = false
                    while (!eof) {
                        // Puffer ganz füllen (read liefert auch weniger), dann einen Block kodieren
                        var fill = 0
                        while (fill < raw.size) {
                            val n = input.read(raw, fill, raw.size - fill)
                            if (n < 0) { eof = true; break }
                            fill += n
                        }
                        val count = fill / 2   // ein ungerades Restbyte am Dateiende fällt weg
                        if (count == 0) break
                        for (i in 0 until count) {
                            // 16 Bit little endian in die obere Hälfte eines Int, so erwartet es LAME
                            samples[i] = ((raw[2 * i + 1].toInt() shl 8) or (raw[2 * i].toInt() and 0xFF)) shl 16
                        }
                        val bytes = lame.lame_encode_buffer_int(gfp, samples, samples, count, mp3buf, 0, mp3buf.size)
                        if (bytes < 0) throw IOException("MP3 encoder error $bytes")
                        o.write(mp3buf, 0, bytes)
                    }
                }
                val bytes = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.size)
                if (bytes < 0) throw IOException("MP3 encoder error $bytes")
                o.write(mp3buf, 0, bytes)
            }
            // Info-Tag in den dafür freigehaltenen ersten Rahmen schreiben
            val tagLen = vbr.getLameTagFrame(gfp, mp3buf)
            if (tagLen in 1..mp3buf.size) RandomAccessFile(out, "rw").use { it.seek(0); it.write(mp3buf, 0, tagLen) }
        } finally {
            lame.lame_close(gfp)
        }
        if (out.length() == 0L) error("MP3 encoder did not produce a file")
    }

    /** Verdrahtung der LAME-Module wie in jump3rs LameEncoder, ohne dessen javax.sound-Teile. */
    private fun wire(lame: Lame, vbr: VBRTag) {
        val ga = GainAnalysis()
        val bs = BitStream()
        val p = Presets()
        val qupvt = QuantizePVT()
        val qu = Quantize()
        val ver = Version()
        val id3 = ID3Tag()
        val rv = Reservoir()
        val tak = Takehiro()
        val parse = Parse()
        val gaud = GetAudio()
        val mpg = MPGLib()
        val intf = Interface()
        val common = Common()
        lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg)
        bs.setModules(ga, mpg, ver, vbr)
        id3.setModules(bs, ver)
        p.setModules(lame)
        qu.setModules(bs, rv, qupvt, tak)
        qupvt.setModules(tak, rv, lame.enc.psy)
        rv.setModules(bs)
        tak.setModules(qupvt)
        vbr.setModules(lame, bs, ver)
        gaud.setModules(parse, mpg)
        parse.setModules(ver, id3, p)
        mpg.setModules(intf, common)
        intf.setModules(vbr, common)
    }
}
