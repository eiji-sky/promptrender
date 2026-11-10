package com.htmlrenderstudio.render

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.File

class EncoderException(message: String) : Exception(message)

/**
 * Encoder H.264 -> MP4 berbasis MediaCodec + MediaMuxer.
 *
 * Frame masuk lewat buffer YUV420 (bukan Surface) sehingga presentation timestamp kita tentukan sendiri
 * dan deterministik (frame i = i * 1_000_000 / fps mikrodetik), tidak bergantung pada kecepatan render.
 * Satu Bitmap dipakai ulang oleh pemanggil; encoder tidak menyimpan frame di memori.
 * Audio belum didukung.
 */
class VideoEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val outFile: File,
) {
    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val STALL_TIMEOUT_MS = 15_000L
    }

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxerStarted = false
    private var lastPtsUs = 0L
    private var released = false
    private val info = MediaCodec.BufferInfo()
    private val argbRow = IntArray(width)

    fun start() {
        val format = MediaFormat.createVideoFormat(MIME, width, height)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)

        val name = MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)
        val c = try {
            if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createEncoderByType(MIME)
        } catch (e: Exception) {
            throw EncoderException("Perangkat ini tidak punya encoder H.264: ${e.message}")
        }

        try {
            val caps = c.codecInfo.getCapabilitiesForType(MIME).videoCapabilities
            if (!caps.isSizeSupported(width, height)) {
                throw EncoderException("Encoder perangkat tidak mendukung ukuran ${width}x$height. Coba resolusi lebih rendah.")
            }
            if (!caps.areSizeAndRateSupported(width, height, fps.toDouble())) {
                throw EncoderException("Encoder perangkat tidak mendukung ${width}x$height pada $fps FPS. Coba FPS atau resolusi lebih rendah.")
            }
            val raw = (width.toDouble() * height * fps * 0.12).coerceIn(2_000_000.0, 40_000_000.0).toInt()
            format.setInteger(MediaFormat.KEY_BIT_RATE, caps.bitrateRange.clamp(raw))
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()
        } catch (e: Exception) {
            c.release()
            throw if (e is EncoderException) e else EncoderException("Gagal menyiapkan encoder: ${e.message}")
        }
        codec = c
        muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Memasukkan satu frame. Dipanggil berurutan dengan [ptsUs] naik. */
    fun encode(bitmap: Bitmap, ptsUs: Long) {
        val c = codec ?: throw EncoderException("Encoder belum dimulai.")
        val deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS
        while (true) {
            drain(endOfStream = false)
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val image = c.getInputImage(idx) ?: throw EncoderException("Encoder tidak menyediakan buffer gambar.")
                fillImage(bitmap, image)
                c.queueInputBuffer(idx, 0, width * height * 3 / 2, ptsUs, 0)
                lastPtsUs = ptsUs
                return
            }
            if (SystemClock.elapsedRealtime() > deadline) throw EncoderException("Encoder macet (tidak menerima frame).")
        }
    }

    /** Mengirim EOS, menguras sisa output, lalu menutup file MP4. */
    fun finish() {
        val c = codec ?: return
        val deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS
        while (true) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                c.queueInputBuffer(idx, 0, 0, lastPtsUs + 1, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                break
            }
            drain(endOfStream = false)
            if (SystemClock.elapsedRealtime() > deadline) throw EncoderException("Encoder macet saat finalisasi.")
        }
        drain(endOfStream = true)
        if (!muxerStarted) throw EncoderException("Tidak ada data video yang dihasilkan.")
        muxer?.stop()
        release()
    }

    fun release() {
        if (released) return
        released = true
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { if (muxerStarted) muxer?.stop() }
        runCatching { muxer?.release() }
        codec = null
        muxer = null
    }

    private fun drain(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        val deadline = SystemClock.elapsedRealtime() + STALL_TIMEOUT_MS
        while (true) {
            val idx = c.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (SystemClock.elapsedRealtime() > deadline) throw EncoderException("Encoder tidak mengirim akhir stream.")
                }
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) throw EncoderException("Format output berubah di tengah encoding.")
                    track = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                idx >= 0 -> {
                    val buf = c.getOutputBuffer(idx)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (buf != null && info.size > 0 && muxerStarted) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        m.writeSampleData(track, buf, info)
                    }
                    c.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** ARGB_8888 -> YUV420 (BT.601 limited range), menghormati rowStride dan pixelStride tiap plane. */
    private fun fillImage(bmp: Bitmap, image: Image) {
        val planes = image.planes
        val yBuf = planes[0].buffer
        val uBuf = planes[1].buffer
        val vBuf = planes[2].buffer
        val yRowStride = planes[0].rowStride
        val uvRowStride = planes[1].rowStride
        val uvPixStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixStride = planes[2].pixelStride

        for (y in 0 until height) {
            bmp.getPixels(argbRow, 0, width, 0, y, width, 1)
            val yBase = y * yRowStride
            val evenRow = (y and 1) == 0
            val uBase = (y shr 1) * uvRowStride
            val vBase = (y shr 1) * vRowStride
            for (x in 0 until width) {
                val p = argbRow[x]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                yBuf.put(yBase + x, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (evenRow && (x and 1) == 0) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    uBuf.put(uBase + (x shr 1) * uvPixStride, u.toByte())
                    vBuf.put(vBase + (x shr 1) * vPixStride, v.toByte())
                }
            }
        }
    }
}
