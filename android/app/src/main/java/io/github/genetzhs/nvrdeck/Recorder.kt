package io.github.genetzhs.nvrdeck

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val RW = 22
private const val RH = 18

private data class ChCfg(val motion: JsonObject, val record: JsonObject, val snap: android.graphics.Bitmap?,
                         val region: List<Long>, val level: Int, val enable: Boolean, val toNet: Boolean, val mode: Int, val fromH: Int, val toH: Int)

private fun hx(e: JsonElement?) = e?.jsonPrimitive?.contentOrNull?.removePrefix("0x")?.toLongOrNull(16) ?: 0L

private fun load(cam: Camera): ChCfg = Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
    val idx = (cam.channel - 1).coerceAtLeast(0)
    val m = d.getConfig("Detect.MotionDetect.[$idx]").jsonObject
    val r = d.getConfig("Record.[$idx]").jsonObject
    val snap = runCatching { d.snapshot(idx).let { BitmapFactory.decodeByteArray(it, 0, it.size) } }.getOrNull()
    val reg = m["Region"]?.jsonArray
    val mask = hx(r["Mask"]?.jsonArray?.getOrNull(0)?.jsonArray?.getOrNull(0))
    val mode = when { r["RecordMode"]?.jsonPrimitive?.contentOrNull == "ClosedRecord" -> 2; mask != 0L && mask and 1L == 0L -> 1; else -> 0 }
    val ts = r["TimeSection"]?.jsonArray?.getOrNull(0)?.jsonArray?.getOrNull(0)?.jsonPrimitive?.contentOrNull ?: "1 00:00:00-24:00:00"
    val span = ts.substringAfter(' ')
    ChCfg(m, r, snap, (0 until RH).map { hx(reg?.getOrNull(it)) }, m["Level"]?.jsonPrimitive?.intOrNull ?: 3,
        m["Enable"]?.jsonPrimitive?.booleanOrNull ?: true, m["EventHandler"]?.jsonObject?.get("MsgtoNetEnable")?.jsonPrimitive?.booleanOrNull ?: false,
        mode, span.take(2).toIntOrNull() ?: 0, span.substringAfter('-').take(2).toIntOrNull() ?: 24)
}

private fun JsonObject.with(k: String, v: JsonElement) = JsonObject(toMutableMap().also { it[k] = v })

private fun save(cam: Camera, c: ChCfg) = Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
    val idx = (cam.channel - 1).coerceAtLeast(0)
    val oldReg = c.motion["Region"]?.jsonArray ?: JsonArray(emptyList())
    val reg = JsonArray(oldReg.mapIndexed { i, v -> if (i < RH) JsonPrimitive("0x%08X".format(c.region[i])) else v })
    val eh = (c.motion["EventHandler"]?.jsonObject ?: JsonObject(emptyMap())).with("MsgtoNetEnable", JsonPrimitive(c.toNet))
    val m = c.motion.with("Enable", JsonPrimitive(c.enable)).with("Level", JsonPrimitive(c.level.coerceIn(1, 6))).with("Region", reg).with("EventHandler", eh)
    d.setConfig("Detect.MotionDetect.[$idx]", m)
    val sec = "1 %02d:00:00-%02d:00:00".format(c.fromH.coerceIn(0, 23), c.toH.coerceIn(c.fromH + 1, 24))
    val mask = if (c.mode == 1) "0x00000006" else "0x00000007"
    fun days(src: JsonElement?, first: String, rest: String) = JsonArray((src?.jsonArray ?: JsonArray(emptyList())).map { day ->
        JsonArray(day.jsonArray.mapIndexed { k, _ -> JsonPrimitive(if (k == 0) first else rest) }) })
    val r = c.record.with("RecordMode", JsonPrimitive(if (c.mode == 2) "ClosedRecord" else "ConfigRecord"))
        .with("TimeSection", days(c.record["TimeSection"], sec, "0 00:00:00-00:00:00")).with("Mask", days(c.record["Mask"], mask, "0x00000000"))
    d.setConfig("Record.[$idx]", r)
}

@Composable
fun RecorderDialog(store: Store, group: List<Camera>, onClose: () -> Unit) {
    val c = cine
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var cam by remember { mutableStateOf(group.first()) }
    var cfg by remember { mutableStateOf<ChCfg?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(cam, reload) {
        cfg = null; err = null
        runCatching { withContext(Dispatchers.IO) { load(cam) } }.onSuccess { cfg = it }.onFailure { err = it.message }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(c.bg)) {
            Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                ScreenTitle("Recorder", "Settings", right = { TextButton(onClose) { Text("Close", color = c.accent) } })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Motion", "Recording", "Recorder").forEachIndexed { i, n -> FilterChip(tab == i, { tab = i }, { Text(n) }) }
                }
                if (tab < 2) Row(Modifier.horizontalScrollCompat(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { g -> FilterChip(cam.id == g.id, { cam = g }, { Text(g.name.ifEmpty { "Ch ${g.channel}" }) }) }
                }
                val e = cfg
                if (tab < 2 && e == null) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        if (err != null) Text("Could not read settings: $err", color = c.down)
                        else Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading settings from the recorder…", color = c.muted) }
                    }
                }
                if (tab == 0 && e != null) GlassCard(Modifier.fillMaxWidth()) {
                    SettingSwitch("Motion detection", "The recorder marks motion on the timeline", e.enable) { cfg = e.copy(enable = it) }
                    Spacer(Modifier.height(8.dp))
                    SettingSwitch("Send motion events to NVRDeck", "Needed for people & vehicle alerts", e.toNet) { cfg = e.copy(toNet = it) }
                    Spacer(Modifier.height(12.dp))
                    Text("Sensitivity: " + listOf("Lowest", "Lower", "Medium", "Higher", "High", "Highest")[e.level.coerceIn(1, 6) - 1], color = c.fg)
                    Slider(e.level.toFloat(), { cfg = e.copy(level = it.toInt()) }, valueRange = 1f..6f, steps = 4,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
                    Text("Detection area: tap or drag to paint. Starting on a painted cell erases. Leave out roads and trees.", color = c.muted, fontSize = 12.5.sp)
                    Spacer(Modifier.height(8.dp))
                    ZoneGrid(e.snap, e.region) { cfg = cfg?.copy(region = it) }
                    Row { TextButton({ cfg = e.copy(region = List(RH) { (1L shl RW) - 1 }) }) { Text("Select all") }; TextButton({ cfg = e.copy(region = List(RH) { 0L }) }) { Text("Clear") } }
                }
                if (tab == 0 && e != null) GlassCard(Modifier.fillMaxWidth()) {
                    val live = store.data.collectAsState().value.cameras.firstOrNull { it.id == cam.id } ?: cam
                    val z = AiZone.parse(live.aiZone)
                    fun put(nz: AiZone?) = store.update { d -> d.copy(cameras = d.cameras.map { if (it.id == cam.id) it.copy(aiZone = nz?.enc() ?: "") else it }) }
                    Eyebrow("Phone alert zone")
                    Spacer(Modifier.height(6.dp))
                    Text("Checked by NVRDeck, not the recorder. Area: alert only when a person or vehicle stands inside it. Line: alert only when one crosses it.", color = c.muted, fontSize = 12.5.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(z == null, { put(null) }, { Text("Whole picture") })
                        FilterChip(z?.line == false, { put(AiZone(false, .25f, .35f, .75f, .95f)) }, { Text("Area") })
                        FilterChip(z?.line == true, { put(AiZone(true, .1f, .6f, .9f, .6f)) }, { Text("Line") })
                    }
                    if (z != null) {
                        Spacer(Modifier.height(8.dp))
                        Text("Drag on the picture to draw the ${if (z.line) "line" else "area"}.", color = c.muted, fontSize = 12.5.sp)
                        Spacer(Modifier.height(6.dp))
                        ZoneDraw(e.snap, z) { put(it) }
                    }
                }
                if (tab == 1 && e != null) GlassCard(Modifier.fillMaxWidth()) {
                    Text("When this camera records to the recorder's disk", color = c.muted)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Always", "Motion only", "Off").forEachIndexed { i, n -> FilterChip(e.mode == i, { cfg = e.copy(mode = i) }, { Text(n) }) } }
                    if (e.mode != 2) {
                        Spacer(Modifier.height(10.dp))
                        Text("Every day %02d:00 – %02d:00".format(e.fromH, e.toH), color = c.fg)
                        RangeSlider(e.fromH.toFloat()..e.toH.toFloat(), { r -> val a = r.start.toInt(); cfg = e.copy(fromH = a, toH = r.endInclusive.toInt().coerceAtLeast(a + 1)) },
                            valueRange = 0f..24f, steps = 23, colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent))
                    }
                    Text("Motion only keeps more days on the disk, but nothing is saved between events.", color = c.muted, fontSize = 12.5.sp)
                }
                if (tab < 2 && e != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton({ reload++ }) { Text("Revert") }
                    Spacer(Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Button({
                        busy = true; msg = "Saving…"
                        scope.launch {
                            val r = runCatching { withContext(Dispatchers.IO) { save(cam, e); load(cam) } }
                            msg = r.fold({ n -> cfg = n; if (n.region == e.region && n.level == e.level && n.toNet == e.toNet && n.enable == e.enable) "Saved and verified on the recorder." else "Saved, but the recorder reports different values." }, { "Not saved: ${it.message}" })
                            busy = false
                        }
                    }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Save to recorder") }
                }
                if (tab == 2) RecorderTab(store, group.first(), busy, { busy = it }, { msg = it })
                if (msg.isNotEmpty()) Text(msg, color = c.muted)
            }
        }
    }
}

@Composable
private fun Modifier.horizontalScrollCompat() = this.then(Modifier.horizontalScroll(rememberScrollState()))

@Composable
private fun ZoneGrid(snap: android.graphics.Bitmap?, region: List<Long>, onChange: (List<Long>) -> Unit) {
    val c = cine
    val cur by rememberUpdatedState(region)
    var paint by remember { mutableStateOf(true) }
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        snap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            fun cell(o: Offset) = Pair((o.x / size.width * RW).toInt().coerceIn(0, RW - 1), (o.y / size.height * RH).toInt().coerceIn(0, RH - 1))
            fun set(o: Offset, v: Boolean) { val (x, y) = cell(o); val l = cur.toMutableList(); l[y] = if (v) l[y] or (1L shl x) else l[y] and (1L shl x).inv(); if (l != cur) onChange(l) }
            detectTapGestures { o -> val (x, y) = cell(o); set(o, cur[y] shr x and 1L == 0L) }
        }.pointerInput(Unit) {
            fun cell(o: Offset) = Pair((o.x / size.width * RW).toInt().coerceIn(0, RW - 1), (o.y / size.height * RH).toInt().coerceIn(0, RH - 1))
            fun set(o: Offset, v: Boolean) { val (x, y) = cell(o); val l = cur.toMutableList(); l[y] = if (v) l[y] or (1L shl x) else l[y] and (1L shl x).inv(); if (l != cur) onChange(l) }
            detectDragGestures(onDragStart = { o -> val (x, y) = cell(o); paint = cur[y] shr x and 1L == 0L; set(o, paint) }) { ch, _ -> set(ch.position, paint) }
        }) {
            val cw = size.width / RW; val chh = size.height / RH
            for (y in 0 until RH) for (x in 0 until RW) {
                val tl = Offset(x * cw, y * chh)
                if (region[y] shr x and 1L == 1L) drawRect(c.accent.copy(alpha = .32f), tl, Size(cw, chh))
                drawRect(Color.White.copy(alpha = .10f), tl, Size(cw, chh), style = androidx.compose.ui.graphics.drawscope.Stroke(1f))
            }
        }
    }
}

@Composable
private fun RecorderTab(store: Store, cam: Camera, busy: Boolean, setBusy: (Boolean) -> Unit, say: (String) -> Unit) {
    val c = cine
    val scope = rememberCoroutineScope()
    var time by remember { mutableStateOf("") }
    var old by remember { mutableStateOf("") }; var new1 by remember { mutableStateOf("") }; var new2 by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    fun io(block: Dvr.() -> String) { setBusy(true); scope.launch { say(runCatching { withContext(Dispatchers.IO) { Dvr.connect(cam.host, cam.user, cam.pass).use { it.block() } } }.getOrElse { "Error: ${it.message}" }); setBusy(false) } }
    GlassCard(Modifier.fillMaxWidth()) {
        Eyebrow("Clock")
        Spacer(Modifier.height(6.dp))
        Text(if (time.isEmpty()) "Read the recorder's clock to compare it with the phone." else "Recorder $time", color = c.fg)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ io { getTime().also { time = it }.let { "Phone ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}" } } }, enabled = !busy) { Text("Read clock") }
            Button({ io { setTime(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))); time = getTime(); "Clock synced to the phone." } }, enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Sync to phone") }
        }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Eyebrow("Password")
        Spacer(Modifier.height(6.dp))
        Text("For user \"${cam.user}\". NVRDeck updates its saved password for all channels.", color = c.muted, fontSize = 12.5.sp)
        listOf(Triple("Current password", old) { v: String -> old = v }, Triple("New password", new1) { v: String -> new1 = v }, Triple("Repeat new password", new2) { v: String -> new2 = v }).forEach { (l, v, f) ->
            OutlinedTextField(v, f, label = { Text(l) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Button({
            when {
                new1.length < 6 -> say("Use at least 6 characters.")
                new1 != new2 -> say("The new passwords don't match.")
                old != cam.pass -> say("The current password is not the one NVRDeck has saved.")
                else -> { val np = new1; setBusy(true); scope.launch {
                    val r = runCatching { withContext(Dispatchers.IO) { Dvr.connect(cam.host, cam.user, old).use { it.changePassword(cam.user, old, np) } } }
                    if (r.isSuccess) { store.update { d -> d.copy(cameras = d.cameras.map { if (it.host == cam.host) it.copy(pass = np) else it }) }; say("Password changed on the recorder.") }
                    else say("Not changed: ${r.exceptionOrNull()?.message}")
                    old = ""; new1 = ""; new2 = ""; setBusy(false) } }
            }
        }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Change password") }
    }
    GlassCard(Modifier.fillMaxWidth()) {
        Eyebrow("Restart")
        Spacer(Modifier.height(6.dp))
        Text("All cameras go dark for about a minute.", color = c.muted, fontSize = 12.5.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedButton({ confirm = true }, enabled = !busy) { Text("Restart recorder…", color = c.down) }
    }
    if (confirm) AlertDialog({ confirm = false }, title = { Text("Restart the recorder?") }, text = { Text("Cameras come back in about a minute.") },
        confirmButton = { TextButton({ confirm = false; io { reboot(); "Restarting…" } }) { Text("Restart", color = c.down) } },
        dismissButton = { TextButton({ confirm = false }) { Text("Cancel") } })
}

@Composable
private fun ZoneDraw(snap: android.graphics.Bitmap?, z: AiZone, onChange: (AiZone) -> Unit) {
    val c = cine
    val cur by rememberUpdatedState(z)
    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        snap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        var start by remember { mutableStateOf(Offset.Zero) }
        Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
            fun n(o: Offset) = Offset((o.x / size.width).coerceIn(0f, 1f), (o.y / size.height).coerceIn(0f, 1f))
            detectDragGestures(onDragStart = { start = n(it) }) { ch, _ -> val e = n(ch.position); onChange(cur.copy(a = start.x, b = start.y, c = e.x, d = e.y)) }
        }) {
            val p1 = Offset(z.a * size.width, z.b * size.height); val p2 = Offset(z.c * size.width, z.d * size.height)
            if (z.line) {
                drawLine(c.down, p1, p2, strokeWidth = 6f)
                drawCircle(c.down, 9f, p1); drawCircle(c.down, 9f, p2)
            } else {
                val tl = Offset(minOf(p1.x, p2.x), minOf(p1.y, p2.y)); val sz = Size(kotlin.math.abs(p2.x - p1.x), kotlin.math.abs(p2.y - p1.y))
                drawRect(c.down.copy(alpha = .22f), tl, sz)
                drawRect(c.down, tl, sz, style = androidx.compose.ui.graphics.drawscope.Stroke(4f))
            }
        }
    }
}
