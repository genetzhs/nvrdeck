package io.github.genetzhs.nvrdeck

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Αναγνώριση ανθρώπων/οχημάτων με YOLOX-nano (Apache 2.0), στο κινητό. Ίδια λογική με τον υπολογιστή. */
class Detector(ctx: Context) : AutoCloseable {
    data class Hit(val cls: Int, val score: Float, val box: FloatArray)

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(ctx.assets.open("yolox_nano.onnx").use { it.readBytes() }, OrtSession.SessionOptions())
    private val input = session.inputNames.first()

    companion object {
        @Volatile private var inst: Detector? = null
        fun shared(ctx: Context): Detector = inst ?: synchronized(this) { inst ?: Detector(ctx.applicationContext).also { inst = it } }
        const val SIZE = 416
        fun label(c: Int) = when (c) { 0 -> "Person"; 1 -> "Bicycle"; 2 -> "Car"; 3 -> "Motorcycle"; 5 -> "Bus"; 7 -> "Truck"; else -> null }
        fun iou(a: FloatArray, b: FloatArray): Float {
            val ix = max(0f, min(a[2], b[2]) - max(a[0], b[0])); val iy = max(0f, min(a[3], b[3]) - max(a[1], b[1]))
            val i = ix * iy; val u = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - i
            return if (u <= 0f) 0f else i / u
        }
    }

    fun run(bmp: Bitmap, minScore: Float = 0.45f): List<Hit> {
        val w = bmp.width; val h = bmp.height
        val r = min(SIZE.toFloat() / w, SIZE.toFloat() / h)
        val nw = (w * r).toInt(); val nh = (h * r).toInt()
        val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        val px = IntArray(nw * nh); scaled.getPixels(px, 0, nw, 0, 0, nw, nh)
        val buf = FloatBuffer.allocate(3 * SIZE * SIZE)
        val plane = SIZE * SIZE
        for (i in 0 until 3 * plane) buf.put(i, 114f)
        for (y in 0 until nh) for (x in 0 until nw) {
            val p = px[y * nw + x]; val o = y * SIZE + x
            buf.put(o, (p and 0xFF).toFloat())                 // B
            buf.put(plane + o, ((p shr 8) and 0xFF).toFloat()) // G
            buf.put(2 * plane + o, ((p shr 16) and 0xFF).toFloat()) // R
        }
        val hits = mutableListOf<Hit>()
        OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { t ->
            session.run(mapOf(input to t)).use { res ->
                @Suppress("UNCHECKED_CAST")
                val out = (res[0].value as Array<Array<FloatArray>>)[0]
                var i = 0
                for (st in intArrayOf(8, 16, 32)) {
                    val g = SIZE / st
                    for (gy in 0 until g) for (gx in 0 until g) {
                        if (i >= out.size) break
                        val row = out[i++]
                        val obj = row[4]
                        if (obj < minScore * 0.5f) continue
                        var bc = 0; var bs = 0f
                        for (c in 0 until 80) if (row[5 + c] > bs) { bs = row[5 + c]; bc = c }
                        val score = obj * bs
                        if (score < minScore || label(bc) == null) continue
                        val cx = (row[0] + gx) * st; val cy = (row[1] + gy) * st
                        val bw = exp(row[2]) * st; val bh = exp(row[3]) * st
                        val sw = w * r; val sh = h * r
                        hits += Hit(bc, score, floatArrayOf(((cx - bw / 2) / sw).coerceIn(0f, 1f), ((cy - bh / 2) / sh).coerceIn(0f, 1f),
                            ((cx + bw / 2) / sw).coerceIn(0f, 1f), ((cy + bh / 2) / sh).coerceIn(0f, 1f)))
                    }
                }
            }
        }
        // NMS
        val best = mutableListOf<Hit>()
        for (hh in hits.sortedByDescending { it.score }) if (best.none { iou(it.box, hh.box) > 0.45f }) best += hh
        return best
    }

    override fun close() { runCatching { session.close() } }
}
