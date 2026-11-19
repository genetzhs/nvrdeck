package io.github.genetzhs.nvrdeck

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import android.view.TextureView
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.ui.PlayerView
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ───── #11 Υγεία καναλιών (όσο η εφαρμογή είναι ανοιχτή) ─────
data class Health(val online: Long = 0, val total: Long = 0, val downSince: Long = 0, val alerted: Boolean = false,
                  val res: String = "", val reconnects: Int = 0, val live: Boolean = false)

object HealthBook {
    val map = mutableStateMapOf<Long, Health>()
    fun report(id: Long, live: Boolean, res: String? = null, reconnect: Boolean = false) {
        val h = map[id] ?: Health()
        map[id] = h.copy(live = live, res = res ?: h.res, reconnects = h.reconnects + if (reconnect) 1 else 0)
    }
    /** Κάθε δευτερόλεπτο. Επιστρέφει (τίτλος, κείμενο) για ειδοποιήσεις. */
    fun tick(cams: List<Camera>, alertAfterSec: Int): List<Pair<String, String>> {
        val now = System.currentTimeMillis()
        val out = mutableListOf<Pair<String, String>>()
        for (c in cams) {
            val h = map[c.id] ?: Health()
            var n = h.copy(total = h.total + 1, online = h.online + if (h.live) 1 else 0)
            if (h.live) {
                if (h.alerted) out += "${c.name} is back online" to "The camera is streaming again."
                n = n.copy(downSince = 0, alerted = false)
            } else {
                val since = if (h.downSince == 0L) now else h.downSince
                n = n.copy(downSince = since)
                if (!h.alerted && now - since >= alertAfterSec * 1000L && n.total > alertAfterSec) {
                    n = n.copy(alerted = true)
                    out += "${c.name} is offline" to "No video for $alertAfterSec s."
                }
            }
            map[c.id] = n
        }
        return out
    }
}

private const val CH = "alerts"

fun notify(ctx: Context, title: String, body: String) {
    val nm = ctx.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, "Camera alerts", NotificationManager.IMPORTANCE_DEFAULT))
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val pi = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    val n = NotificationCompat.Builder(ctx, CH).setSmallIcon(R.drawable.ic_stat_cam).setContentTitle(title).setContentText(body)
        .setContentIntent(pi).setAutoCancel(true).build()
    NotificationManagerCompat.from(ctx).notify(title.hashCode(), n)
}

// ───── #1 Στιγμιότυπο ─────
fun grab(view: PlayerView?): Bitmap? = (view?.videoSurfaceView as? TextureView)?.bitmap

fun saveSnapshot(ctx: Context, bmp: Bitmap, cam: Camera): Boolean = runCatching {
    val name = "${cam.name.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
    val v = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NVRDeck")
    }
    val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)!!
    ctx.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    true
}.getOrDefault(false)

// ───── #10 Widget: τελευταία εικόνα της κύριας κάμερας ─────
fun widgetFile(ctx: Context) = File(ctx.filesDir, "widget.jpg")

fun updateWidget(ctx: Context, bmp: Bitmap, cam: Camera) {
    runCatching {
        val small = Bitmap.createScaledBitmap(bmp, 480, (480f * bmp.height / bmp.width).toInt().coerceAtLeast(1), true)
        widgetFile(ctx).outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        ctx.getSharedPreferences("widget", 0).edit().putString("name", cam.name).putLong("at", System.currentTimeMillis()).apply()
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, CamWidget::class.java))
        if (ids.isNotEmpty()) CamWidget.render(ctx, mgr, ids)
    }
}

// ───── #6 Εισαγωγή από κρυπτογραφημένο QR του υπολογιστή ─────
@Serializable
private data class QrCam(val name: String = "", val host: String = "", val port: Int = 554, val proto: String = "XMEYE",
                         val user: String = "", val pass: String = "", val channel: Int = 1, val mainStream: Boolean = false,
                         val path: String = "", val enabled: Boolean = true, val group: String = "")
@Serializable
private data class QrPayload(val v: Int = 1, val cameras: List<QrCam> = emptyList())

const val QR_PREFIX = "NVRDECK1:"

fun decryptQr(text: String, pass: String): List<Camera> {
    require(text.startsWith(QR_PREFIX)) { "Not an NVRDeck code" }
    val b = Base64.getDecoder().decode(text.removePrefix(QR_PREFIX))
    val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        .generateSecret(PBEKeySpec(pass.toCharArray(), b.copyOfRange(0, 16), 200_000, 256)).encoded
    val plain = try {
        Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, b.copyOfRange(16, 28)))
            doFinal(b.copyOfRange(28, b.size))
        }
    } catch (e: Exception) { throw IllegalArgumentException("Wrong passphrase") }
    val p = Json { ignoreUnknownKeys = true }.decodeFromString<QrPayload>(String(plain))
    var t = System.nanoTime()
    return p.cameras.map {
        Camera(id = t++, name = it.name, host = it.host, port = it.port,
            proto = runCatching { Proto.valueOf(it.proto) }.getOrDefault(Proto.CUSTOM), user = it.user, pass = it.pass,
            channel = it.channel, mainStream = it.mainStream, path = it.path, enabled = it.enabled, group = it.group)
    }
}
