package io.github.genetzhs.nvrdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat

/**
 * Υπηρεσία παρασκηνίου: μένει συνδεδεμένη στο NVR, ακούει τα δικά του συμβάντα κίνησης,
 * παίρνει στιγμιότυπο και ειδοποιεί μόνο για ανθρώπους/οχήματα σε νέα θέση.
 */
class AlertService : Service() {
    @Volatile private var running = true
    private val threads = mutableListOf<Thread>()

    companion object {
        private const val CH_FG = "service"
        private const val CH_AI = "people"
        fun start(ctx: Context) {
            val i = Intent(ctx, AlertService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, AlertService::class.java)) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CH_FG, "Background alerts", NotificationManager.IMPORTANCE_MIN))
            nm.createNotificationChannel(NotificationChannel(CH_AI, "People & vehicles", NotificationManager.IMPORTANCE_HIGH))
        }
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CH_FG).setSmallIcon(R.drawable.ic_stat_cam)
            .setContentTitle("Watching for people and vehicles").setContentText("NVRDeck is connected to your recorder")
            .setContentIntent(pi).setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        ServiceCompat.startForeground(this, 7, n, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (threads.isEmpty()) launch()
        return START_STICKY
    }

    private fun launch() {
        val data = Store(this).data.value
        val recorders = data.cameras.filter { it.proto == Proto.XMEYE }.groupBy { it.host }.values.filter { it.size > 1 }
        val detector = runCatching { Detector(this) }.getOrNull() ?: return
        for (group in recorders) threads += Thread { watch(group, detector) }.apply { isDaemon = true; start() }
    }

    private fun watch(group: List<Camera>, det: Detector) {
        val cam = group.first()
        val known = HashMap<Int, List<Detector.Hit>>()  // ό,τι «στέκεται» σε κάθε κανάλι
        val lastAlert = HashMap<Int, Long>()
        var backoff = 5_000L
        while (running) {
            try {
                Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
                    d.subscribeAlarms()
                    backoff = 5_000L
                    var ka = System.currentTimeMillis()
                    // αρχική εικόνα κάθε καναλιού: ό,τι υπάρχει ήδη (π.χ. παρκαρισμένα) δεν ειδοποιεί
                    for (c in group) runCatching { val j = d.snapshot(c.channel - 1); BitmapFactory.decodeByteArray(j, 0, j.size)?.let { known[c.channel] = det.run(it) } }
                    while (running) {
                        if (System.currentTimeMillis() - ka > 20_000) { d.keepAlive(); ka = System.currentTimeMillis() }
                        val a = d.nextAlarm() ?: continue
                        if (!a.start || !a.event.contains("Motion", true)) continue
                        val ch = a.channel + 1
                        val c = group.firstOrNull { it.channel == ch } ?: continue
                        if (System.currentTimeMillis() - (lastAlert[ch] ?: 0) < 120_000) continue
                        val jpg = runCatching { d.snapshot(a.channel) }.getOrNull() ?: continue
                        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: continue
                        val hits = det.run(bmp)
                        val before = known[ch].orEmpty()
                        known[ch] = hits
                        val new = hits.firstOrNull { h -> before.none { it.cls == h.cls && Detector.iou(it.box, h.box) > 0.5f } } ?: continue
                        lastAlert[ch] = System.currentTimeMillis()
                        val what = Detector.label(new.cls) ?: "Object"
                        val pi = PendingIntent.getActivity(this, ch, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                        val n: Notification = NotificationCompat.Builder(this, CH_AI).setSmallIcon(R.drawable.ic_stat_cam)
                            .setContentTitle("$what at ${c.name}").setContentText("Detected with ${(new.score * 100).toInt()}% confidence")
                            .setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
                            .setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
                        runCatching { NotificationManagerCompat.from(this).notify(1000 + ch, n) }
                    }
                }
            } catch (_: Exception) {
                // χαμένη σύνδεση (VPN εκτός κ.λπ.): νέα προσπάθεια σε λίγο
                Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}
