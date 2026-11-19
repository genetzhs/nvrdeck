package io.github.genetzhs.nvrdeck

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

private fun fmt(t: Long) = Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).format(TF)

/** Ακατέργαστο ρεύμα από τη στιγμή `from` έως `to` (ή μέχρι το πρώτο I-frame αν onlyKey). */
private fun pullRaw(cam: Camera, f: Dvr.RecFile, from: Long, to: Long, dst: File, onlyKey: Boolean, progress: (Long) -> Unit = {}): Boolean {
    val parser = FrameParser()
    var gotKey = false; var frames = 0
    dst.outputStream().buffered().use { o ->
        Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
            d.streamFrom(f, fmt(from)) { chunk ->
                o.write(chunk)
                var go = true
                for (fr in parser.push(chunk)) {
                    if (fr.key) gotKey = true
                    if (gotKey) frames++
                    if (onlyKey && gotKey && frames >= 2) go = false
                    if (!onlyKey && fr.timeMs > 0) { if (fr.timeMs > to) go = false else progress(fr.timeMs) }
                }
                go
            }
        }
    }
    return gotKey
}

private fun recFor(files: List<Dvr.RecFile>, t: Long) = files.firstOrNull { ms(it.begin) <= t && t < ms(it.end) }

/** Μικρογραφία της εγγραφής καναλιού τη στιγμή t (πρώτο I-frame από εκεί). */
fun grabFrame(ctx: Context, cam: Camera, files: List<Dvr.RecFile>, t: Long): Bitmap? = runCatching {
    val f = recFor(files, t) ?: return null
    val raw = File(ctx.cacheDir, "th_${cam.channel}_${Thread.currentThread().id}.raw"); val mp4 = File(raw.path + ".mp4")
    try {
        if (!pullRaw(cam, f, t, t, raw, onlyKey = true)) return null
        remuxToMp4(raw, mp4)
        val mmr = MediaMetadataRetriever()
        try { mmr.setDataSource(mp4.path); mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { Bitmap.createScaledBitmap(it, 320, 180, true) } } finally { mmr.release() }
    } finally { raw.delete(); mp4.delete() }
}.getOrNull()

object Thumbs {
    private val cache = object : LinkedHashMap<String, Bitmap?>(64, .75f, true) { override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap?>) = size > 80 }
    private val tags = HashMap<String, List<Int>>()
    private val gate = Semaphore(2)
    fun key(ch: Int, t: Long) = "$ch/${t / 60_000}"
    fun cached(ch: Int, t: Long) = synchronized(cache) { cache[key(ch, t)] }
    fun tagsOf(ch: Int, t: Long) = synchronized(tags) { tags[key(ch, t)] }
    suspend fun get(ctx: Context, cam: Camera, files: List<Dvr.RecFile>, t: Long, detect: Boolean = false): Bitmap? {
        val k = key(cam.channel, t)
        synchronized(cache) { if (cache.containsKey(k) && (!detect || tags.containsKey(k))) return cache[k] }
        return gate.withPermit { withContext(Dispatchers.IO) {
            val b = synchronized(cache) { cache[k] } ?: grabFrame(ctx, cam, files, t)
            synchronized(cache) { cache[k] = b }
            if (detect && b != null) { val h = runCatching { Detector.shared(ctx).run(b, 0.4f).map { it.cls }.distinct() }.getOrDefault(emptyList()); synchronized(tags) { tags[k] = h } }
            b
        } }
    }
}

/** Εξαγωγή διαστήματος σε MP4 στις Ταινίες/NVRDeck. Επιστρέφει το όνομα του αρχείου. */
fun exportClip(ctx: Context, cam: Camera, files: List<Dvr.RecFile>, from: Long, to: Long, progress: (Float) -> Unit): String {
    val parts = files.filter { ms(it.end) > from && ms(it.begin) < to }.sortedBy { it.begin }
    require(parts.isNotEmpty()) { "No recording in this range" }
    val raw = File(ctx.cacheDir, "export.raw"); val mp4 = File(ctx.cacheDir, "export.mp4")
    raw.outputStream().use { all ->
        for (f in parts) {
            val tmp = File(ctx.cacheDir, "export_part.raw")
            pullRaw(cam, f, maxOf(from, ms(f.begin)), to, tmp, onlyKey = false) { t -> progress(((t - from).toFloat() / (to - from)).coerceIn(0f, .95f)) }
            tmp.inputStream().use { it.copyTo(all) }; tmp.delete()
        }
    }
    remuxToMp4(raw, mp4); raw.delete()
    val name = "${cam.name.replace(Regex("[^A-Za-z0-9_-]+"), "_")}_${Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))}.mp4"
    if (Build.VERSION.SDK_INT >= 29) {
        val cv = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4"); put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NVRDeck") }
        val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: error("Could not create the file")
        ctx.contentResolver.openOutputStream(uri)!!.use { o -> mp4.inputStream().use { it.copyTo(o) } }
    } else {
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "NVRDeck").apply { mkdirs() }
        mp4.copyTo(File(dir, name), overwrite = true)
    }
    mp4.delete(); progress(1f)
    return name
}

// ── Υγεία καταγραφικού ──
data class RecHealth(val disks: Int, val totalMb: Long, val freeMb: Long, val chans: List<Triple<String, Long?, Long?>>, val err: String? = null)

fun readHealth(group: List<Camera>): RecHealth = runCatching {
    val cam = group.first(); val now = System.currentTimeMillis()
    Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
        val (n, tot, free) = d.storage()
        val ch = group.map { c ->
            val idx = c.channel - 1
            val oldest = runCatching { d.files(idx, fmt(now - 120L * 86_400_000), fmt(now), "*").firstOrNull()?.let { ms(it.begin) } }.getOrNull()
            val latest = runCatching { d.files(idx, fmt(now - 6 * 3_600_000L), fmt(now), "*").maxOfOrNull { ms(it.end) } }.getOrNull()
                ?: runCatching { d.files(idx, fmt(now - 3 * 86_400_000L), fmt(now), "*").maxOfOrNull { ms(it.end) } }.getOrNull()
            Triple(c.name, oldest, latest)
        }
        RecHealth(n, tot, free, ch)
    }
}.getOrElse { RecHealth(0, 0, 0, emptyList(), it.message ?: "unreachable") }

@Composable
fun HealthSheet(h: RecHealth?, onClose: () -> Unit) {
    val c = cine
    AlertDialog(onClose, confirmButton = { TextButton(onClose) { Text("Close") } }, containerColor = c.bgEnd,
        title = { Text("Recorder health") }, text = {
            if (h == null) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Checking…") }
            else if (h.err != null) Text("Could not check: ${h.err}", color = c.down)
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val used = if (h.totalMb > 0) 1f - h.freeMb.toFloat() / h.totalMb else 0f
                Text("${h.disks} disk${if (h.disks == 1) "" else "s"} · %.1f TB · %d%% full".format(h.totalMb / 1_048_576f, (used * 100).toInt()), color = c.fg)
                LinearProgressIndicator({ used }, Modifier.fillMaxWidth(), color = if (used > .97f) MOTIONC else c.accent, trackColor = c.inset)
                if (used > .97f) Text("Full: the oldest recordings are being overwritten.", color = c.muted, fontSize = 12.sp)
                val now = System.currentTimeMillis()
                h.chans.forEach { (name, old, last) ->
                    val stale = last == null || now - last > 30 * 60_000
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(!stale, 5.dp); Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(name, color = c.fg, fontWeight = FontWeight.Medium)
                            Text((if (old != null) "Kept since ${java.time.Instant.ofEpochMilli(old).atZone(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))}" else "No recordings") +
                                (if (last != null) " · last ${hms(last).take(5)}" else ""), color = c.muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        })
}
private val MOTIONC = Color(0xFFFFB020)

// ── Λίστα συμβάντων ──
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventsSheet(group: List<Camera>, all: Map<Int, List<Dvr.RecFile>>, motion: Map<Int, List<Dvr.RecFile>>, onPick: (Int, Long) -> Unit, onClose: () -> Unit) {
    val c = cine
    var filter by remember { mutableIntStateOf(0) } // 0 όλα, 1 άνθρωποι, 2 οχήματα
    val events = remember(motion) {
        motion.flatMap { (ch, l) -> l.map { ch to ms(it.begin) } }.sortedByDescending { it.second }
            // συγχώνευση συνεχόμενων αρχείων του ίδιου καναλιού (< 1 λεπτό)
            .fold(mutableListOf<Pair<Int, Long>>()) { acc, e -> if (acc.lastOrNull()?.let { it.first == e.first && it.second - e.second < 60_000 } == true) acc else acc.also { it += e } }
    }
    var tick by remember { mutableIntStateOf(0) }
    ModalBottomSheet(onClose, containerColor = c.bgEnd) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Events", style = MaterialTheme.typography.titleLarge, color = c.fg, modifier = Modifier.weight(1f))
                Text("${events.size}", color = c.muted)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("All", "People", "Vehicles").forEachIndexed { i, n -> FilterChip(filter == i, { filter = i }, { Text(n) }) } }
            if (filter != 0) Text("People and vehicles are found on the phone as thumbnails load.", color = c.muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            if (events.isEmpty()) Text("No motion recorded on this day.", color = c.muted, modifier = Modifier.padding(vertical = 24.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(events, key = { "${it.first}/${it.second}" }) { (ch, t) ->
                    val cam = group.firstOrNull { it.channel == ch } ?: return@items
                    val tg = remember(tick) { Thumbs.tagsOf(ch, t + 2000) }
                    val people = tg?.contains(0) == true; val veh = tg?.any { it in listOf(1, 2, 3, 5, 7) } == true
                    if (filter == 1 && tg != null && !people) return@items
                    if (filter == 2 && tg != null && !veh) return@items
                    EventRow(cam, all[ch].orEmpty(), t, tg, { tick++ }) { onPick(ch, t) }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EventRow(cam: Camera, files: List<Dvr.RecFile>, t: Long, tags: List<Int>?, onTags: () -> Unit, onClick: () -> Unit) {
    val c = cine
    val ctx = LocalContext.current
    var bmp by remember { mutableStateOf(Thumbs.cached(cam.channel, t + 2000)) }
    LaunchedEffect(t) { if (bmp == null || tags == null) { bmp = Thumbs.get(ctx, cam, files, t + 2000, detect = true); onTags() } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.glass).clickable(onClick = onClick).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(9.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } ?: CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(hms(t), color = c.fg, style = MonoMeta.copy(fontSize = 15.sp))
            Text(cam.name, color = c.muted, fontSize = 12.5.sp)
            val labels = tags.orEmpty().mapNotNull { Detector.label(it) }.distinct()
            if (labels.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                labels.forEach { Text(it, color = c.accent, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.accent.copy(alpha = .14f)).padding(horizontal = 7.dp, vertical = 1.dp)) }
            }
        }
    }
}
