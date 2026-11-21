package io.github.genetzhs.nvrdeck

import android.app.DatePickerDialog
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.asImageBitmap
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val MOTION = Color(0xFFFFB020)
internal val TF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal fun ms(s: String) = runCatching { LocalDateTime.parse(s, TF).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrDefault(0L)
internal fun hms(t: Long) = java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

/** NVR = καταγραφικό με 2+ κανάλια XMeye στην ίδια διεύθυνση. */
private fun recorders(cams: List<Camera>) = cams.filter { it.proto == Proto.XMEYE }.groupBy { it.host }.values.filter { it.size > 1 }.toList()

internal class Day(val all: Map<Int, List<Dvr.RecFile>>, val motion: Map<Int, List<Dvr.RecFile>>)

@Composable
fun PlaybackScreen(data: StoreData) {
    val c = cine
    val ctx = LocalContext.current
    val recs = remember(data.cameras) { recorders(data.cameras) }
    if (recs.isEmpty()) {
        Column(Modifier.padding(20.dp)) {
            ScreenTitle(eyebrow = "Recorder", title = "Playback")
            Spacer(Modifier.height(14.dp))
            GlassCard(Modifier.fillMaxWidth()) { Text("Add an XMeye NVR from the Cameras tab to browse its recordings.", color = c.mutedStrong) }
        }
        return
    }
    val group = recs.first().sortedBy { it.channel }
    val cam = group.first()
    var date by remember { mutableStateOf(LocalDate.now()) }
    var day by remember { mutableStateOf<Result<Day>?>(null) }
    var cursor by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }
    var speed by remember { mutableIntStateOf(1) }
    var big by remember { mutableStateOf<Int?>(null) }
    var seekReq by remember { mutableLongStateOf(0L) }   // αλλάζει → νέα αναπαραγωγή όλων
    var motionOnly by remember { mutableStateOf(false) }
    var zoom by remember { mutableIntStateOf(0) }        // 0 = 24 ώρες, 1 = 6 ώρες, 2 = 1 ώρα
    val clip by ClipRequest.flow.collectAsState()
    var pending by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    LaunchedEffect(clip) { clip?.let { (ch, t) ->
        pending = ch to t; ClipRequest.flow.value = null
        val d = java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate(); if (d != date) date = d
    } }
    val day0 = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    val players = remember { mutableStateMapOf<Int, NvrPlayer>() }
    LaunchedEffect(date, cam.host) {
        day = null; cursor = 0; seekReq = 0
        players.values.forEach { it.release() }; players.clear()
        day = withContext(Dispatchers.IO) {
            runCatching {
                Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
                    val a = "$date 00:00:00"; val b = "$date 23:59:59"
                    // στο DVRIP τα κανάλια μετρούν από 0, στο RTSP από 1
                    Day(group.associate { it.channel to d.files(it.channel - 1, a, b, "*") },
                        group.associate { it.channel to d.files(it.channel - 1, a, b, "M") })
                }
            }
        }
    }
    val rawFiles = day?.getOrNull()
    // μόνο κίνηση: τα κανάλια «βλέπουν» μόνο τα τμήματα με κίνηση
    val files = if (motionOnly && rawFiles != null) Day(rawFiles.motion, rawFiles.motion) else rawFiles

    // αν κανένα κανάλι δεν έχει εγγραφή εκείνη τη στιγμή → η πρώτη εγγραφή μετά
    fun normalize(t: Long): Long {
        val f = files ?: return t
        val covers = f.all.values.flatten().any { ms(it.begin) <= t && t < ms(it.end) }
        if (covers) return t
        return f.all.values.flatten().map { ms(it.begin) }.filter { it > t }.minOrNull() ?: t
    }
    fun seek(t: Long) { val n = normalize(t); cursor = n; seekReq++ }

    // από ειδοποίηση: λίγα δευτερόλεπτα πριν το συμβάν, μεγάλο το κανάλι
    LaunchedEffect(files, pending) { val p = pending; if (files != null && p != null) { pending = null; big = p.first; zoom = 2; seek(p.second - 6_000) } }

    var showEvents by remember { mutableStateOf(false) }
    var showHealth by remember { mutableStateOf(false) }
    var health by remember { mutableStateOf<RecHealth?>(null) }
    var markIn by remember { mutableLongStateOf(0L) }
    var markOut by remember { mutableLongStateOf(0L) }
    var exporting by remember { mutableStateOf<Float?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(showHealth) { if (showHealth && health == null) health = withContext(Dispatchers.IO) { readHealth(group) } }
    if (showHealth) HealthSheet(health) { showHealth = false }
    if (showEvents && files != null) EventsSheet(group, files.all, files.motion, onPick = { ch, t -> showEvents = false; big = ch; zoom = 2; seek(t - 2000) }) { showEvents = false }

    // ρολόι: η πιο πρόσφατη ώρα από τα κανάλια που παίζουν
    LaunchedEffect(Unit) { while (true) { delay(250); players.values.maxOfOrNull { it.clock.get() }?.takeIf { it > 0 }?.let { cursor = it } } }
    // μόνο κίνηση: στο τέλος κάθε τμήματος, άλμα στο επόμενο
    LaunchedEffect(motionOnly, files) {
        while (motionOnly) {
            delay(500)
            val f = files ?: continue
            if (paused || players.isEmpty() || cursor == 0L) continue
            val shown = big?.let { listOf(it) } ?: group.map { it.channel }
            val segs = shown.flatMap { f.motion[it].orEmpty() }
            if (segs.none { ms(it.begin) - 8000 <= cursor && cursor < ms(it.end) }) {
                val next = segs.map { ms(it.begin) }.filter { it > cursor + 1000 }.minOrNull()
                if (next != null) seek(next) else { players.values.forEach { it.release() }; players.clear(); android.widget.Toast.makeText(ctx, "No more motion on this day", android.widget.Toast.LENGTH_SHORT).show() }
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(bottom = 92.dp)) {
        // ── κεφαλίδα: τίτλος + ημερομηνία ──
        Row(Modifier.padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Recorder")
                Text("Playback", style = TitleStyle, color = c.fg)
            }
            IconButton({ showHealth = true; health = null }) { Icon(Icons.Outlined.Storage, "Recorder health", tint = c.fg) }
            IconButton({ showEvents = true }, enabled = files != null) { Icon(Icons.Outlined.NotificationsActive, "Events", tint = c.fg) }
            Row(Modifier.clip(RoundedCornerShape(50)).background(c.glassStrong).border(1.dp, c.glassBorder, RoundedCornerShape(50))
                .clickable {
                    DatePickerDialog(ctx, { _, y, m, d -> date = LocalDate.of(y, m + 1, d) }, date.year, date.monthValue - 1, date.dayOfMonth)
                        .apply { datePicker.maxDate = System.currentTimeMillis() }.show()
                }.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CalendarMonth, null, tint = c.fg, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(when (date) { LocalDate.now() -> "Today"; LocalDate.now().minusDays(1) -> "Yesterday"; else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM")) },
                    color = c.fg, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.5.sp)
            }
        }

        // ── κανάλια ──
        val shown = if (big != null) group.filter { it.channel == big } else group
        val cols = if (shown.size <= 1) 1 else 2
        val rows = (shown.size + cols - 1) / cols
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (r in 0 until rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val inRow = shown.drop(r * cols).take(cols)
                    inRow.forEach { ch ->
                        ChannelView(ch, files, seekReq, cursor, paused, speed, players,
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp)).background(Color(0xFF05060A))
                                .pointerInput(ch.channel) { detectTapGestures(onDoubleTap = { big = if (big == null) ch.channel else null }) })
                    }
                    if (inRow.size < cols) Spacer(Modifier.weight(1f))
                }
            }
        }

        // ── έλεγχοι ──
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (cursor > 0) seek(cursor - 30_000) }, enabled = cursor > 0) { Icon(Icons.Outlined.Replay30, "Back 30 s", tint = c.fg) }
            FilledIconButton(onClick = { paused = !paused }, enabled = players.isNotEmpty(), colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent)) {
                Icon(if (paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, "Play/pause", tint = Color.White)
            }
            IconButton(onClick = { if (cursor > 0) seek(cursor + 30_000) }, enabled = cursor > 0) { Icon(Icons.Outlined.Forward30, "Forward 30 s", tint = c.fg) }
            Spacer(Modifier.width(6.dp))
            listOf(1, 2, 4).forEach { x -> Chip("${x}×", speed == x) { speed = x } }
            Chip("Motion", motionOnly) { motionOnly = !motionOnly; if (cursor > 0) seek(cursor) else rawFiles?.motion?.values?.flatten()?.minOfOrNull { ms(it.begin) }?.let { seek(it) } }
            Spacer(Modifier.weight(1f))
            Text(if (cursor > 0) hms(cursor) else "--:--:--", style = MonoMeta.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium), color = c.fg)
        }

        // ── εξαγωγή κλιπ: σημάδια αρχής/τέλους ──
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip(if (markIn > 0) "In ${hms(markIn).take(5)}" else "Mark in", markIn > 0) { if (cursor > 0) { markIn = cursor; if (markOut in 1..cursor) markOut = 0 } }
            Chip(if (markOut > 0) "Out ${hms(markOut).take(5)}" else "Mark out", markOut > 0) { if (cursor > markIn && markIn > 0) markOut = cursor }
            Spacer(Modifier.weight(1f))
            val chExp = big ?: group.first().channel
            val e = exporting
            if (e != null) { LinearProgressIndicator({ e }, Modifier.width(70.dp), color = c.accent, trackColor = c.inset); Spacer(Modifier.width(6.dp)) }
            TextButton({
                val camE = group.first { it.channel == chExp }
                val (a, b) = if (markIn > 0 && markOut > markIn) markIn to markOut else (cursor - 10_000) to (cursor + 20_000)
                exporting = 0f
                scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { exportClip(ctx, camE, files?.all?.get(chExp).orEmpty(), a, b) { p -> exporting = p } } }
                    exporting = null
                    android.widget.Toast.makeText(ctx, r.fold({ "Saved to Movies/NVRDeck: $it" }, { "Export failed: ${it.message}" }), android.widget.Toast.LENGTH_LONG).show()
                }
            }, enabled = cursor > 0 && e == null && true) {
                Icon(Icons.Outlined.Download, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp))
                Text(if (big != null || group.size == 1) "Export" else "Export ${group.first().name}", maxLines = 1)
            }
        }

        // ── χρονολόγιο ──
        Timeline(group, files, day, day0, cursor, zoom, markIn, markOut, onZoom = { zoom = (zoom + 1) % 3 }, onSeek = { seek(it) })
    }
}

@Composable
private fun ChannelView(ch: Camera, files: Day?, seekReq: Long, cursor: Long, paused: Boolean, speed: Int,
                        players: MutableMap<Int, NvrPlayer>, modifier: Modifier) {
    val c = cine
    var surface by remember { mutableStateOf<Surface?>(null) }
    var player by remember { mutableStateOf<NvrPlayer?>(null) }
    var status by remember { mutableStateOf("") }
    var shownT by remember { mutableLongStateOf(0L) }
    val list = files?.all?.get(ch.channel).orEmpty()

    fun startAt(t: Long) {
        player?.release(); players.remove(ch.channel); player = null
        val s = surface ?: return
        val f = list.firstOrNull { ms(it.begin) <= t && t < ms(it.end) }
        if (f == null) { status = "No recording at this time"; return }
        player = NvrPlayer(ch, f, t, s).also { it.paused.set(paused); it.speed.set(speed); it.start(); players[ch.channel] = it }
    }
    // νέα μετάβαση
    LaunchedEffect(seekReq, surface) { if (seekReq > 0 && surface != null) startAt(cursor) }
    LaunchedEffect(paused) { player?.paused?.set(paused) }
    LaunchedEffect(speed) { player?.speed?.set(speed) }
    // κατάσταση + συνέχεια στο επόμενο αρχείο / έναρξη όταν η ώρα μπει σε εγγραφή
    LaunchedEffect(player, list) {
        while (true) {
            delay(400)
            val p = player
            if (p != null) {
                status = p.status; shownT = p.clock.get()
                if (p.ended) {
                    val end = ms(p.file.end)
                    val next = list.firstOrNull { val b = ms(it.begin); b >= end - 5000 && b <= end + 10_000 }
                    if (next != null) startAt(ms(next.begin)) else { p.release(); players.remove(ch.channel); player = null; status = "No recording at this time" }
                }
            } else if (seekReq > 0 && cursor > 0 && players.isNotEmpty() && list.any { ms(it.begin) <= cursor && cursor + 3000 < ms(it.end) }) {
                startAt(cursor)
            }
        }
    }
    DisposableEffect(Unit) { onDispose { player?.release(); players.remove(ch.channel) } }

    Box(modifier) {
        AndroidView(factory = { cx ->
            TextureView(cx).apply {
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { surface = Surface(st) }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { player?.release(); surface = null; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
            }
        }, modifier = Modifier.fillMaxSize())
        Text(if (shownT > 0) "${ch.name}  ·  ${hms(shownT)}" else ch.name, color = Color.White, fontSize = 10.5.sp, fontFamily = Geist,
            modifier = Modifier.padding(5.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 7.dp, vertical = 2.dp))
        val msg = when { files == null -> "Loading…"; seekReq == 0L -> ""; else -> status }
        if (msg.isNotEmpty()) Text(msg, color = c.muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.Center))
    }
}

@Composable
private fun Timeline(group: List<Camera>, files: Day?, day: Result<Day>?, day0: Long, cursor: Long, zoom: Int, markIn: Long, markOut: Long, onZoom: () -> Unit, onSeek: (Long) -> Unit) {
    val c = cine
    val span = when (zoom) { 1 -> 6 * 3_600_000L; 2 -> 3_600_000L; else -> 86_400_000L }
    var drag by remember { mutableStateOf<Long?>(null) }
    // ορατό παράθυρο γύρω από την τρέχουσα θέση — παγωμένο όσο κρατάει το drag
    var dragVs by remember { mutableLongStateOf(0L) }
    val baseVs = if (zoom == 0 || cursor == 0L) day0 else (cursor - span / 2).coerceIn(day0, day0 + 86_400_000L - span)
    val vs = if (drag != null && dragVs > 0) dragVs else baseVs
    val vsCur by rememberUpdatedState(vs)
    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 10.dp), padding = 10.dp, radius = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(when { day == null -> "Loading recordings…"; day.isFailure -> "Recorder not reachable (is the VPN on?)"; cursor == 0L -> "Tap or drag on the bar to play"; else -> "Drag to move in time" },
                color = if (day?.isFailure == true) c.down else c.muted, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
            Box(Modifier.size(8.dp).background(c.accent, RoundedCornerShape(2.dp))); Text(" rec  ", color = c.muted, fontSize = 10.5.sp)
            Box(Modifier.size(8.dp).background(MOTION, RoundedCornerShape(2.dp))); Text(" motion  ", color = c.muted, fontSize = 10.5.sp)
            Text(when (zoom) { 1 -> "6 h"; 2 -> "1 h"; else -> "24 h" }, color = c.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onZoom).background(c.accent.copy(alpha = .12f)).padding(horizontal = 9.dp, vertical = 3.dp))
        }
        Spacer(Modifier.height(8.dp))
        val rowH = 13.dp
        val accent = c.accent; val track = c.inset; val fg = c.fg; val muted = c.muted
        Canvas(Modifier.fillMaxWidth().height(rowH * group.size + 4.dp * group.size + 18.dp)
            .pointerInput(span) {
                fun t(x: Float) = vsCur + (x / size.width * span).toLong().coerceIn(0, span - 1)
                detectTapGestures { onSeek(t(it.x)) }
            }
            .pointerInput(span) {
                fun t(x: Float) = vsCur + (x / size.width * span).toLong().coerceIn(0, span - 1)
                detectDragGestures(onDragStart = { dragVs = vsCur }, onDragEnd = { drag?.let(onSeek); drag = null; dragVs = 0 }, onDragCancel = { drag = null; dragVs = 0 }) { ch, _ -> drag = t(ch.position.x) }
            }) {
            val w = size.width
            fun x(t: Long) = ((t - vs).toFloat() / span * w)
            val rh = rowH.toPx(); val gap = 4.dp.toPx()
            group.forEachIndexed { k, cam ->
                val y = k * (rh + gap)
                drawRoundRect(track, Offset(0f, y), Size(w, rh), CornerRadius(4f))
                files?.all?.get(cam.channel)?.forEach { f ->
                    val a = x(ms(f.begin)); val b = x(ms(f.end)); if (b < 0 || a > w) return@forEach
                    drawRect(accent.copy(alpha = .8f), Offset(a.coerceAtLeast(0f), y + 2), Size((b - a).coerceAtLeast(1.5f), rh - 4))
                }
                files?.motion?.get(cam.channel)?.forEach { f ->
                    val a = x(ms(f.begin)); val b = x(ms(f.end)); if (b < 0 || a > w) return@forEach
                    drawRect(MOTION, Offset(a.coerceAtLeast(0f), y + 2), Size((b - a).coerceAtLeast(1.5f), rh - 4))
                }
            }
            val base = group.size * (rh + gap)
            // κλίμακα
            val step = when (zoom) { 1 -> 3_600_000L; 2 -> 600_000L; else -> 4 * 3_600_000L }
            var t = ((vs + step - 1) / step) * step
            while (t <= vs + span) {
                val xx = x(t)
                drawLine(muted, Offset(xx, base), Offset(xx, base + 4), 1.5f)
                t += step
            }
            if (markIn > 0) { val a = x(markIn); val b = if (markOut > markIn) x(markOut) else a + 2f; drawRect(fg.copy(alpha = .14f), Offset(a, 0f), Size((b - a).coerceAtLeast(2f), base)) }
            val playhead = drag ?: cursor
            if (playhead > 0) {
                val px = x(playhead)
                drawLine(if (drag != null) accent else fg, Offset(px, 0f), Offset(px, base), 4f)
            }
        }
        // ετικέτες ωρών
        Row(Modifier.fillMaxWidth()) {
            val n = when (zoom) { 1 -> 6; 2 -> 6; else -> 6 }
            for (i in 0..n) {
                val tt = vs + span * i / n
                Text(java.time.Instant.ofEpochMilli(tt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm")),
                    color = c.muted, fontSize = 9.5.sp, style = MonoMeta, modifier = Modifier.weight(1f))
            }
        }
        drag?.let { dt ->
            val ctx = LocalContext.current
            val cam = group.firstOrNull { cm -> files?.all?.get(cm.channel)?.any { ms(it.begin) <= dt && dt < ms(it.end) } == true }
            var th by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
            LaunchedEffect(dt / 60_000, cam?.channel) { th = cam?.let { cm -> Thumbs.cached(cm.channel, dt) }; if (cam != null && th == null) { delay(350); th = Thumbs.get(ctx, cam, files?.all?.get(cam.channel).orEmpty(), dt) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                th?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.width(128.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))); Spacer(Modifier.width(8.dp)) }
                Text("→ ${hms(dt)}", color = c.accent, style = MonoMeta.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium))
            }
        }
    }
}
