package io.github.genetzhs.nvrdeck

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Αναπαραγωγή ενός καναλιού του NVR σε Surface, ενώ κατεβαίνει:
 * DVRIP → καρέ → MediaCodec (υλικό) → Surface, με ρυθμό ανάλογα με την ταχύτητα.
 */
class NvrPlayer(private val cam: Camera, val file: Dvr.RecFile, private val atMs: Long, private val surface: Surface) {
    val stop = AtomicBoolean(false)
    val paused = AtomicBoolean(false)
    val speed = AtomicInteger(1)
    /** ώρα (epoch ms) του καρέ που φαίνεται· 0 = τίποτα ακόμη */
    val clock = AtomicLong(0)
    @Volatile var status = "Loading…"
    @Volatile var ended = false

    fun start() = Thread {
        var codec: MediaCodec? = null
        try {
            val parser = FrameParser()
            val startStr = java.time.Instant.ofEpochMilli(atMs).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            var wallStart = 0L; var mediaStart = 0L
            Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
                d.streamFrom(file, startStr) { chunk ->
                    if (stop.get()) return@streamFrom false
                    for (fr in parser.push(chunk)) {
                        if (codec == null) {
                            // περιμένουμε I-frame κοντά στη ζητούμενη στιγμή (−2 s)
                            if (!fr.key || fr.timeMs < atMs - 2000) { status = "Seeking…"; continue }
                            val mime = if (parser.hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
                            val fmt = MediaFormat.createVideoFormat(mime, parser.width.coerceAtLeast(16), parser.height.coerceAtLeast(16))
                            codec = MediaCodec.createDecoderByType(mime).apply { configure(fmt, surface, null, 0); start() }
                            status = ""
                        }
                        val c = codec!!
                        // είσοδος (ο πρώτος I-frame περιέχει SPS/PPS)
                        while (!stop.get()) {
                            val ii = c.dequeueInputBuffer(10_000)
                            if (ii >= 0) {
                                val ib = c.getInputBuffer(ii)!!
                                ib.clear()
                                if (fr.data.size > ib.capacity()) { c.queueInputBuffer(ii, 0, 0, fr.timeMs * 1000, 0); break }
                                ib.put(fr.data)
                                c.queueInputBuffer(ii, 0, fr.data.size, fr.timeMs * 1000, if (fr.key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                                break
                            }
                            drain(c, { wallStart }, { mediaStart }) { w, m -> wallStart = w; mediaStart = m }
                        }
                        drain(c, { wallStart }, { mediaStart }) { w, m -> wallStart = w; mediaStart = m }
                    }
                    !stop.get()
                }
            }
            // ό,τι έμεινε στον αποκωδικοποιητή
            codec?.let { c -> repeat(30) { drain(c, { wallStart }, { mediaStart }) { w, m -> wallStart = w; mediaStart = m } } }
            if (!stop.get()) { status = "End of recording"; ended = true }
        } catch (e: Exception) {
            if (!stop.get()) status = "Recorder error: ${e.message ?: "?"}"
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }
        }
    }.apply { isDaemon = true; start() }

    private val info = MediaCodec.BufferInfo()

    /** Βγάζει καρέ στην οθόνη με τον σωστό ρυθμό (ανάλογα με παύση/ταχύτητα). */
    private fun drain(c: MediaCodec, ws: () -> Long, ms: () -> Long, set: (Long, Long) -> Unit) {
        val oi = c.dequeueOutputBuffer(info, 0)
        if (oi < 0) return
        val pts = info.presentationTimeUs / 1000
        while (paused.get() && !stop.get()) { Thread.sleep(50); set(0, 0) }
        var w0 = ws(); var m0 = ms()
        if (w0 == 0L) { w0 = System.currentTimeMillis(); m0 = pts; set(w0, m0) }
        val due = w0 + (pts - m0) / speed.get().coerceAtLeast(1)
        val wait = due - System.currentTimeMillis()
        if (wait in 1..2000) Thread.sleep(wait) else if (wait > 2000) set(System.currentTimeMillis(), pts)
        c.releaseOutputBuffer(oi, true)
        clock.set(pts)
    }

    fun release() { stop.set(true) }
}
