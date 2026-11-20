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
    private val store by lazy { Store(this) }

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
        if (intent?.action == "disarm") {
            store.update { it.copy(settings = it.settings.copy(armed = false)) }
            NotificationManagerCompat.from(this).cancelAll()
        }
        if (threads.isEmpty()) launch()
        return START_STICKY
    }

    private fun launch() {
        val data = store.data.value
        val recorders = data.cameras.filter { it.proto == Proto.XMEYE }.groupBy { it.host }.values.filter { it.size > 1 }
        val detector = runCatching { Detector(this) }.getOrNull() ?: return
        for (group in recorders) threads += Thread { watch(group, detector) }.apply { isDaemon = true; start() }
        for (group in recorders) threads += Thread { healthLoop(group.sortedBy { it.channel }) }.apply { isDaemon = true; start() }
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
                        if (!store.data.value.settings.armed) continue
                        if (!a.start || !Regex("Motion|Detect|Human|Shape|Intru|Cross", RegexOption.IGNORE_CASE).containsMatchIn(a.event)) continue
                        val ch = a.channel + 1
                        val c = group.firstOrNull { it.channel == ch } ?: continue
                        if (System.currentTimeMillis() - (lastAlert[ch] ?: 0) < 120_000) continue
                        val jpg = runCatching { d.snapshot(a.channel) }.getOrNull() ?: continue
                        val bmp = BitmapFactory.decodeByteArray(jpg, 0, jpg.size) ?: continue
                        val hits = det.run(bmp)
                        val before = known[ch].orEmpty()
                        known[ch] = hits
                        val zone = AiZone.parse(store.data.value.cameras.firstOrNull { it.id == c.id }?.aiZone ?: "")
                        val fresh = hits.filter { h -> before.none { it.cls == h.cls && Detector.iou(it.box, h.box) > 0.5f } }
                        val new = when {
                            zone == null -> fresh.firstOrNull()
                            !zone.line -> fresh.firstOrNull { h -> val x = (h.box[0] + h.box[2]) / 2; val y = h.box[3]
                                x in minOf(zone.a, zone.c)..maxOf(zone.a, zone.c) && y in minOf(zone.b, zone.d)..maxOf(zone.b, zone.d) }
                            else -> {
                                // γραμμή: δεύτερο στιγμιότυπο και έλεγχος αν η διαδρομή του αντικειμένου τέμνει τη γραμμή
                                Thread.sleep(1500)
                                val j2 = runCatching { d.snapshot(a.channel) }.getOrNull()
                                val after = j2?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }?.let { det.run(it) }.orEmpty()
                                fun ctr(h: Detector.Hit) = Pair((h.box[0] + h.box[2]) / 2, h.box[3])
                                hits.firstOrNull { h -> val p = ctr(h)
                                    after.filter { it.cls == h.cls }.minByOrNull { val q = ctr(it); (q.first - p.first) * (q.first - p.first) + (q.second - p.second) * (q.second - p.second) }
                                        ?.let { crosses(p, ctr(it), zone) } == true }
                            }
                        } ?: continue
                        lastAlert[ch] = System.currentTimeMillis()
                        val what = Detector.label(new.cls) ?: "Object"
                        val open = Intent(this, MainActivity::class.java).putExtra("clip_ch", ch).putExtra("clip_t", System.currentTimeMillis()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        val pi = PendingIntent.getActivity(this, ch, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                        val dis = PendingIntent.getService(this, 99, Intent(this, AlertService::class.java).setAction("disarm"), PendingIntent.FLAG_IMMUTABLE)
                        val n: Notification = NotificationCompat.Builder(this, CH_AI).setSmallIcon(R.drawable.ic_stat_cam)
                            .setContentTitle("$what at ${c.name}").setContentText("Detected with ${(new.score * 100).toInt()}% confidence")
                            .setLargeIcon(bmp).setStyle(NotificationCompat.BigPictureStyle().bigPicture(bmp).bigLargeIcon(null as android.graphics.Bitmap?))
                            .addAction(0, "Disarm", dis).setContentIntent(pi).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
                        runCatching { NotificationManagerCompat.from(this).notify(1000 + ch, n) }
                    }
                }
            } catch (_: Exception) {
                // χαμένη σύνδεση (VPN εκτός κ.λπ.): νέα προσπάθεια σε λίγο
                Thread.sleep(backoff); backoff = (backoff * 2).coerceAtMost(120_000L)
            }
        }
    }

    /** Κάθε 10 λεπτά: ειδοποίηση αν κανάλι σταμάτησε να γράφει (> 30 λεπτά, ενώ έγραφε το τελευταίο 24ωρο). */
    private fun healthLoop(group: List<Camera>) {
        val warned = HashSet<String>()
        while (running) {
            val h = readHealth(group); val now = System.currentTimeMillis()
            if (h.err == null) h.chans.forEach { (name, _, last) ->
                val stale = last != null && now - last > 30 * 60_000 && now - last < 86_400_000
                if (stale && warned.add(name)) {
                    val n = NotificationCompat.Builder(this, CH_AI).setSmallIcon(R.drawable.ic_stat_cam).setContentTitle("$name stopped recording")
                        .setContentText("Last recording at ${hms(last!!).take(5)}.").setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()
                    runCatching { NotificationManagerCompat.from(this).notify(name.hashCode(), n) }
                }
                if (!stale) warned.remove(name)
            }
            repeat(600) { if (running) Thread.sleep(1000) }
        }
    }

    private fun crosses(p: Pair<Float, Float>, q: Pair<Float, Float>, z: AiZone): Boolean {
        fun side(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float) = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
        val s1 = side(p.first, p.second, z.a, z.b, z.c, z.d); val s2 = side(q.first, q.second, z.a, z.b, z.c, z.d)
        val s3 = side(z.a, z.b, p.first, p.second, q.first, q.second); val s4 = side(z.c, z.d, p.first, p.second, q.first, q.second)
        return s1 * s2 < 0 && s3 * s4 < 0
    }

    override fun onDestroy() { running = false; super.onDestroy() }
}
