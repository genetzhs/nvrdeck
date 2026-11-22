package io.github.genetzhs.nvrdeck

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.serialization.json.*
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** XMeye / NetSurveillance «DVRIP» (θύρα 34567). Ίδια υλοποίηση με την έκδοση του υπολογιστή. */
class Dvr private constructor(private val host: String, private val port: Int, private var s: Socket) : AutoCloseable {
    private var session = 0
    private var seq = 0
    private val inp = DataInputStream(BufferedInputStream(s.getInputStream()))
    private val out: OutputStream = s.getOutputStream()
    private val alarmQueue = java.util.concurrent.ConcurrentLinkedQueue<Alarm>()

    data class RecFile(val channel: Int, val begin: String, val end: String, val name: String, val sizeKb: Long)

    companion object {
        const val PORT = 34567
        fun sofiaHash(p: String): String {
            val d = MessageDigest.getInstance("MD5").digest(p.toByteArray())
            return (0 until 8).map { i ->
                val n = ((d[2 * i].toInt() and 0xFF) + (d[2 * i + 1].toInt() and 0xFF)) % 62
                when { n < 10 -> '0' + n; n < 36 -> 'A' + (n - 10); else -> 'a' + (n - 36) }
            }.joinToString("")
        }
        private fun open(host: String, port: Int) = Socket().apply { connect(InetSocketAddress(host, port), 5000); soTimeout = 10000 }

        fun connect(host: String, user: String, pass: String, port: Int = PORT): Dvr {
            val d = Dvr(host, port, open(host, port))
            val r = d.call(1000, buildJsonObject {
                put("EncryptType", "MD5"); put("LoginType", "DVRIP-Web"); put("PassWord", sofiaHash(pass)); put("UserName", user)
            })
            when (r["Ret"]?.jsonPrimitive?.intOrNull) {
                100, 515 -> {}
                203, 205 -> { d.close(); throw Exception("Wrong username or password") }
                else -> { d.close(); throw Exception("Login refused") }
            }
            d.session = r["SessionID"]?.jsonPrimitive?.content?.removePrefix("0x")?.toLongOrNull(16)?.toInt() ?: 0
            return d
        }
    }

    private fun sid() = "0x%08X".format(session)

    private fun send(msg: Int, body: JsonObject) {
        val data = body.toString().toByteArray() + byteArrayOf(0x0a, 0)
        val b = ByteBuffer.allocate(20 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xFF.toByte()).put(0).put(0).put(0).putInt(session).putInt(seq++).put(0).put(0).putShort(msg.toShort()).putInt(data.size).put(data)
        out.write(b.array()); out.flush()
    }

    private fun recv(): Pair<Int, ByteArray> {
        val h = ByteArray(20); inp.readFully(h)
        require(h[0] == 0xFF.toByte()) { "bad packet" }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val msg = bb.getShort(14).toInt() and 0xFFFF
        val len = bb.getInt(16)
        require(len in 0 until (16 shl 20)) { "packet too big" }
        val body = ByteArray(len); inp.readFully(body)
        return msg to body
    }

    private fun json(b: ByteArray): JsonObject {
        val end = b.indexOfLast { it == '}'.code.toByte() } + 1
        return runCatching { Json.parseToJsonElement(String(b, 0, end)).jsonObject }.getOrDefault(JsonObject(emptyMap()))
    }

    private fun call(msg: Int, body: JsonObject): JsonObject {
        send(msg, body)
        while (true) { val (m, b) = recv(); if (m == msg + 1) return json(b) }
    }

    /** Εγγραφές καναλιού (όπως στο RTSP) για χρονικό διάστημα. event: "*" όλες, "M" κίνηση. */
    fun files(channel: Int, begin: String, end: String, event: String): List<RecFile> {
        val all = mutableListOf<RecFile>()
        var from = begin
        repeat(400) {
            val r = call(1440, buildJsonObject {
                put("Name", "OPFileQuery"); put("SessionID", sid())
                putJsonObject("OPFileQuery") {
                    put("BeginTime", from); put("EndTime", end); put("Channel", channel)
                    put("DriverTypeMask", "0x0000FFFF"); put("Event", event); put("Type", "h264")
                }
            })
            val list = (r["OPFileQuery"] as? JsonArray).orEmpty()
            for (f in list) {
                val o = f.jsonObject
                all += RecFile(channel, o["BeginTime"]?.jsonPrimitive?.content ?: "", o["EndTime"]?.jsonPrimitive?.content ?: "",
                    o["FileName"]?.jsonPrimitive?.content ?: "", o["FileLength"]?.jsonPrimitive?.content?.removePrefix("0x")?.toLongOrNull(16) ?: 0)
            }
            if (list.size < 64) return all.distinctBy { it.name }
            val last = all.last().end
            if (last == from) return all.distinctBy { it.name }
            from = last
        }
        return all.distinctBy { it.name }
    }

    /** Κατεβάζει την εγγραφή (ακατέργαστο ρεύμα XMeye) στο `dst`. */
    fun download(f: RecFile, dst: File, progress: (Long) -> Unit) {
        fun pb(action: String) = buildJsonObject {
            put("Name", "OPPlayBack"); put("SessionID", sid())
            putJsonObject("OPPlayBack") {
                put("Action", action); put("StartTime", f.begin); put("EndTime", f.end)
                putJsonObject("Parameter") {
                    put("FileName", f.name); put("PlayMode", "ByName"); put("TransMode", "TCP"); put("Value", 0); put("StreamType", 0)
                    put("IntelligentPlayBackEvent", ""); put("IntelligentPlayBackSpeed", 0)
                }
            }
        }
        // δεύτερη σύνδεση με το ίδιο session: εκεί έρχονται τα δεδομένα
        val data = Dvr(host, port, open(host, port)).also { it.session = session }
        data.use { dc ->
            ok(dc.call(1424, pb("Claim")), "download claim")
            ok(call(1420, pb("DownloadStart")), "download start")
            var total = 0L
            dst.outputStream().buffered().use { o ->
                while (true) {
                    val (m, b) = dc.recv()
                    if (m != 1426) continue
                    if (b.isEmpty()) break
                    o.write(b); total += b.size; progress(total)
                }
            }
            runCatching { send(1420, pb("DownloadStop")) }
        }
    }

    /**
     * Αναπαραγωγή από συγκεκριμένη στιγμή μέσα σε αρχείο (ByName + StartTime): το καταγραφικό ξεκινά ~εκεί.
     * Τα δεδομένα δίνονται στο `sink` (false = διακοπή).
     */
    fun streamFrom(f: RecFile, start: String, sink: (ByteArray) -> Boolean) {
        fun pb(action: String) = buildJsonObject {
            put("Name", "OPPlayBack"); put("SessionID", sid())
            putJsonObject("OPPlayBack") {
                put("Action", action); put("StartTime", start); put("EndTime", f.end)
                putJsonObject("Parameter") {
                    put("FileName", f.name); put("PlayMode", "ByName"); put("TransMode", "TCP"); put("Value", 0); put("StreamType", 0)
                    put("IntelligentPlayBackEvent", ""); put("IntelligentPlayBackSpeed", 0)
                }
            }
        }
        val data = Dvr(host, port, open(host, port)).also { it.session = session }
        data.use { dc ->
            ok(dc.call(1424, pb("Claim")), "playback claim")
            ok(call(1420, pb("DownloadStart")), "playback start")
            while (true) {
                val (m, b) = dc.recv()
                if (m != 1426) continue
                if (b.isEmpty() || !sink(b)) break
            }
            runCatching { send(1420, pb("DownloadStop")) }
        }
    }

    // ── Συμβάντα (κίνηση) και στιγμιότυπα ──
    data class Alarm(val channel: Int, val event: String, val start: Boolean)

    fun subscribeAlarms() { call(1500, buildJsonObject { put("Name", ""); put("SessionID", sid()) }) }
    fun keepAlive() = send(1006, buildJsonObject { put("Name", "KeepAlive"); put("SessionID", sid()) })

    /** Επόμενο συμβάν ή null αν δεν ήρθε τίποτα μέσα στο χρονικό όριο. */
    fun nextAlarm(): Alarm? {
        alarmQueue.poll()?.let { return it }
        val (m, b) = try { recv() } catch (_: java.net.SocketTimeoutException) { return null }
        if (m != 1504) return null
        return alarmOf(b)
    }

    private fun alarmOf(b: ByteArray): Alarm? {
        val a = json(b)["AlarmInfo"]?.jsonObject ?: return null
        return Alarm(a["Channel"]?.jsonPrimitive?.intOrNull ?: 0, a["Event"]?.jsonPrimitive?.content ?: "", a["Status"]?.jsonPrimitive?.content == "Start")
    }

    /** JPEG από κανάλι (0-based), μέσω του καταγραφικού. */
    fun snapshot(channel: Int): ByteArray {
        send(1560, buildJsonObject { put("Name", "OPSNAP"); put("SessionID", sid()); putJsonObject("OPSNAP") { put("Channel", channel) } })
        repeat(50) {
            val (m, b) = recv()
            // τυχόν πακέτα συναγερμού που φτάνουν meanwhile: στην ουρά, δεν μετράνε ως απάντηση του snapshot
            if (m == 1504) { runCatching { alarmOf(b)?.let { alarmQueue.add(it) } }; return@repeat }
            if (m == 1561) {
                for (i in 0 until b.size - 1) if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD8.toByte()) return b.copyOfRange(i, b.size)
                throw Exception("Snapshot refused")
            }
        }
        throw Exception("No snapshot")
    }

    fun sendBin(msg: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(20 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(0xFF.toByte()).put(0).put(0).put(0).putInt(session).putInt(seq++).put(0).put(0).putShort(msg.toShort()).putInt(data.size).put(data)
        out.write(b.array()); out.flush()
    }
    private fun talkBody(action: String, ch: Int?) = buildJsonObject { put("Name", "OPTalk"); put("SessionID", sid()); putJsonObject("OPTalk") {
        put("Action", action); if (ch != null) put("Channel", ch)
        putJsonObject("AudioFormat") { put("BitRate", 128); put("EncodeType", "G711_ALAW"); put("SampleBit", 8); put("SampleRate", 8000) } } }
    /** Ομιλία: Start εδώ, Claim σε δεύτερη σύνδεση που επιστρέφεται για αποστολή ήχου (μήνυμα 1432). */
    fun talkStart(ch: Int?): Dvr {
        ok(call(1430, talkBody("Start", ch)), "talk")
        val d = Dvr(host, port, open(host, port)).also { it.session = session }
        try { d.ok(d.call(1434, talkBody("Claim", ch)), "talk claim") }
        catch (e: Exception) { runCatching { d.close() }; throw e }
        return d
    }
    fun talkStop() = runCatching { send(1430, buildJsonObject { put("Name", "OPTalk"); put("SessionID", sid()); putJsonObject("OPTalk") { put("Action", "Stop") } }) }

    private fun ok(r: JsonObject, what: String) { val ret = r["Ret"]?.jsonPrimitive?.intOrNull; if (ret != 100) throw Exception("$what refused ($ret)") }
    fun getConfig(name: String): JsonElement { val r = call(1042, buildJsonObject { put("Name", name); put("SessionID", sid()) }); ok(r, name); return r[name] ?: JsonNull }
    fun setConfig(name: String, v: JsonElement) { ok(call(1040, buildJsonObject { put("Name", name); put("SessionID", sid()); put(name, v) }), name) }
    fun getTime(): String = call(1452, buildJsonObject { put("Name", "OPTimeQuery"); put("SessionID", sid()) })["OPTimeQuery"]?.jsonPrimitive?.contentOrNull ?: ""
    fun setTime(t: String) { ok(call(1450, buildJsonObject { put("Name", "OPTimeSetting"); put("SessionID", sid()); put("OPTimeSetting", t) }), "time") }
    fun changePassword(user: String, old: String, new: String) { ok(call(1488, buildJsonObject { put("EncryptType", "MD5"); put("NewPassWord", sofiaHash(new)); put("PassWord", sofiaHash(old)); put("SessionID", sid()); put("UserName", user) }), "password") }
    fun reboot() = send(1450, buildJsonObject { put("Name", "OPMachine"); put("SessionID", sid()); putJsonObject("OPMachine") { put("Action", "Reboot") } })

    /** Αριθμός καναλιών βίντεο του καταγραφικού (SystemInfo) — το χρησιμοποιεί το αυτόματο σκαν. */
    fun channels(): Int {
        val r = call(1020, buildJsonObject { put("Name", "SystemInfo"); put("SessionID", sid()) })
        fun n(k: String) = r[k]?.jsonPrimitive?.intOrNull ?: 0
        return maxOf(n("VideoInChannel"), n("DigChannel"))
    }

    /** Δίσκοι: (πλήθος, σύνολο MB, ελεύθερα MB). */
    fun storage(): Triple<Int, Long, Long> {
        val r = call(1020, buildJsonObject { put("Name", "StorageInfo"); put("SessionID", sid()) })
        val disks = r["StorageInfo"]?.jsonArray ?: JsonArray(emptyList())
        var tot = 0L; var free = 0L
        fun hx(e: JsonElement?) = e?.jsonPrimitive?.contentOrNull?.removePrefix("0x")?.toLongOrNull(16) ?: 0L
        disks.forEach { d -> d.jsonObject["Partition"]?.jsonArray?.forEach { p -> tot += hx(p.jsonObject["TotalSpace"]); free += hx(p.jsonObject["RemainSpace"]) } }
        return Triple(disks.size, tot, free)
    }

    override fun close() { runCatching { s.close() } }
}

/**
 * Μετατρέπει ακατέργαστο ρεύμα XMeye σε MP4 (χωρίς επανακωδικοποίηση).
 * Καρέ: 0x000001FC (I, 16 bytes κεφαλίδα) / FD (P, 8 bytes). Ήχος FA και πληροφορίες F9 παραλείπονται.
 */
fun remuxToMp4(src: File, dst: File) {
    val inp = DataInputStream(BufferedInputStream(src.inputStream(), 1 shl 16))
    var muxer: MediaMuxer? = null
    var track = -1
    var fps = 15
    var hevc = false
    var n = 0L
    val buf = ByteBuffer.allocateDirect((8 shl 20) + (1 shl 16))
    fun le32(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
    try {
        val h = ByteArray(16)
        while (true) {
            // συγχρονισμός στο 00 00 01 Fx
            inp.readFully(h, 0, 4)
            while (!(h[0].toInt() == 0 && h[1].toInt() == 0 && h[2].toInt() == 1 && (h[3].toInt() and 0xFF) in 0xF9..0xFD)) {
                h[0] = h[1]; h[1] = h[2]; h[2] = h[3]; h[3] = inp.readByte()
            }
            val t = h[3].toInt() and 0xFF
            val len: Int = when (t) {
                0xFC -> { inp.readFully(h, 4, 12); fps = (h[5].toInt() and 0xFF).coerceIn(1, 60); hevc = (h[4].toInt() and 0xFF) in intArrayOf(3, 0x12, 0x13); le32(h, 12) }
                0xFD -> { inp.readFully(h, 4, 4); le32(h, 4) }
                else -> { inp.readFully(h, 4, 4); (h[6].toInt() and 0xFF) or ((h[7].toInt() and 0xFF) shl 8) }
            }
            if (len < 0 || len > (8 shl 20)) continue
            val payload = ByteArray(len); inp.readFully(payload)
            if (t != 0xFC && t != 0xFD) continue
            val nals = splitNals(payload)
            if (muxer == null) {
                if (t != 0xFC) continue
                val w = (h[6].toInt() and 0xFF) * 8; val hh = (h[7].toInt() and 0xFF) * 8
                val fmt = MediaFormat.createVideoFormat(if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC, w, hh)
                val sc = byteArrayOf(0, 0, 0, 1)
                if (hevc) {
                    val ps = nals.filter { nalType(it, true) in 32..34 }
                    fmt.setByteBuffer("csd-0", ByteBuffer.wrap(ps.fold(ByteArray(0)) { a, x -> a + sc + x }))
                } else {
                    nals.firstOrNull { nalType(it, false) == 7 }?.let { fmt.setByteBuffer("csd-0", ByteBuffer.wrap(sc + it)) }
                    nals.firstOrNull { nalType(it, false) == 8 }?.let { fmt.setByteBuffer("csd-1", ByteBuffer.wrap(sc + it)) }
                }
                muxer = MediaMuxer(dst.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { m -> track = m.addTrack(fmt); m.start() }
            }
            // AVCC: μήκος 4 bytes + NAL, χωρίς τα σύνολα παραμέτρων
            buf.clear()
            for (nal in nals) {
                val ty = nalType(nal, hevc)
                if (if (hevc) ty in 32..34 else ty == 7 || ty == 8 || ty == 9) continue
                if (buf.remaining() < nal.size + 4) break
                buf.putInt(nal.size).put(nal)
            }
            if (buf.position() == 0) continue
            buf.flip()
            val info = MediaCodec.BufferInfo().apply {
                set(0, buf.limit(), n * 1_000_000L / fps, if (t == 0xFC) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            }
            muxer!!.writeSampleData(track, buf, info)
            n++
        }
    } catch (_: EOFException) {
    } finally {
        inp.close()
        runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
    }
    require(n > 0) { "No video in recording" }
}

private fun nalType(n: ByteArray, hevc: Boolean) = if (n.isEmpty()) -1 else if (hevc) (n[0].toInt() shr 1) and 0x3F else n[0].toInt() and 0x1F

/** Χωρίζει Annex-B σε NAL units (χωρίς start codes). */
private fun splitNals(b: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Pair<Int, Int>>() // (θέση start code, μήκος start code)
    var i = 0
    while (i + 3 <= b.size) {
        if (b[i].toInt() == 0 && b[i + 1].toInt() == 0) {
            if (b[i + 2].toInt() == 1) { starts += i to 3; i += 3; continue }
            if (i + 4 <= b.size && b[i + 2].toInt() == 0 && b[i + 3].toInt() == 1) { starts += i to 4; i += 4; continue }
        }
        i++
    }
    if (starts.isEmpty()) return listOf(b)
    return starts.mapIndexed { k, (p, l) ->
        val e = if (k + 1 < starts.size) starts[k + 1].first else b.size
        b.copyOfRange(p + l, e)
    }.filter { it.isNotEmpty() }
}

@Suppress("unused") private fun InputStream.skipFully(n: Long) { var r = n; while (r > 0) { val s = skip(r); if (s <= 0) { read(); r-- } else r -= s } }


/** Σταδιακή ανάγνωση καρέ XMeye: δίνουμε κομμάτια, παίρνουμε (Annex-B καρέ, I-frame, χρονοσφραγίδα ms). */
class FrameParser {
    private var buf = ByteArray(0)
    var fps = 15; var hevc = false; var width = 0; var height = 0
    private var base = 0L; private var n = 0L

    data class Frame(val data: ByteArray, val key: Boolean, val timeMs: Long)

    fun push(chunk: ByteArray): List<Frame> {
        buf += chunk
        val out = mutableListOf<Frame>()
        var i = 0
        val b = buf
        fun le32(o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
        while (i + 16 <= b.size) {
            if (!(b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 && (b[i + 3].toInt() and 0xFF) in 0xF9..0xFD)) { i++; continue }
            val t = b[i + 3].toInt() and 0xFF
            val hdr: Int; val len: Int
            when (t) {
                0xFC -> { hdr = 16; len = le32(i + 12) }
                0xFD -> { hdr = 8; len = le32(i + 4) }
                else -> { hdr = 8; len = (b[i + 6].toInt() and 0xFF) or ((b[i + 7].toInt() and 0xFF) shl 8) }
            }
            if (len < 0 || len > (8 shl 20)) { i++; continue }
            if (i + hdr + len > b.size) break
            if (t == 0xFC) {
                fps = (b[i + 5].toInt() and 0xFF).coerceIn(1, 60)
                val ty = b[i + 4].toInt() and 0xFF
                hevc = ty == 3 || ty == 0x12 || ty == 0x13
                width = (b[i + 6].toInt() and 0xFF) * 8; height = (b[i + 7].toInt() and 0xFF) * 8
                val dt = le32(i + 8).toLong() and 0xFFFFFFFFL
                base = java.time.LocalDateTime.of((2000 + (dt shr 26)).toInt(), ((dt shr 22) and 15).toInt().coerceIn(1, 12), ((dt shr 17) and 31).toInt().coerceIn(1, 31),
                    ((dt shr 12) and 31).toInt().coerceIn(0, 23), ((dt shr 6) and 63).toInt().coerceIn(0, 59), (dt and 63).toInt().coerceIn(0, 59))
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                n = 0
            }
            if (t == 0xFC || t == 0xFD) {
                if (base > 0) out += Frame(b.copyOfRange(i + hdr, i + hdr + len), t == 0xFC, base + n * 1000 / fps)
                n++
            }
            i += hdr + len
        }
        buf = b.copyOfRange(i, b.size)
        return out
    }
}
