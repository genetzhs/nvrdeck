package io.github.genetzhs.nvrdeck

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/** Ομιλία από το μικρόφωνο του κινητού προς την κάμερα (G.711 A-law, 8 kHz). */
object Talk {
    private fun alaw(s: Short): Byte {
        var pcm = s.toInt()
        val sign = if (pcm >= 0) 0xD5 else 0x55
        if (pcm < 0) pcm = -pcm - 1
        pcm = pcm.coerceAtMost(32767)
        val v = if (pcm < 256) pcm shr 4 else {
            var seg = 1; var t = pcm shr 8
            while (t > 1 && seg < 7) { t = t shr 1; seg++ }
            (seg shl 4) or ((pcm shr (seg + 3)) and 0x0F)
        }
        return (v xor sign).toByte()
    }

    @SuppressLint("MissingPermission")
    fun run(cam: Camera, stop: AtomicBoolean, status: (String) -> Unit) {
        try {
            Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
                d.talkStart(cam.channel - 1).use { t ->
                    val min = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 3200))
                    rec.startRecording(); status("Live")
                    val pcm = ShortArray(320)
                    try {
                        while (!stop.get()) {
                            var n = 0
                            while (n < 320) { val r = rec.read(pcm, n, 320 - n); if (r <= 0) break; n += r }
                            val pkt = ByteArray(8 + 320)
                            byteArrayOf(0, 0, 1, 0xFA.toByte(), 0x0E, 0x02, 0x40, 0x01).copyInto(pkt)
                            for (i in 0 until 320) pkt[8 + i] = alaw(pcm[i])
                            t.sendBin(1432, pkt)
                        }
                    } finally { rec.stop(); rec.release() }
                }
                d.talkStop()
            }
            status("")
        } catch (e: Exception) { status("Talk failed: ${e.message}") }
    }
}
