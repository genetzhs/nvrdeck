package io.github.genetzhs.nvrdeck

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Κανάλια XMeye ομαδοποιημένα ανά καταγραφικό. */
private fun recorders(cams: List<Camera>) = cams.filter { it.proto == Proto.XMEYE }.groupBy { it.host }.values.toList()

@Composable
fun PlaybackScreen(data: StoreData) {
    val c = cine
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val recs = remember(data.cameras) { recorders(data.cameras) }
    var ri by remember { mutableIntStateOf(0) }
    var day by remember { mutableIntStateOf(0) }
    var motion by remember { mutableStateOf(false) }
    val group = recs.getOrNull(ri.coerceAtMost(recs.size - 1).coerceAtLeast(0)).orEmpty()
    var ch by remember { mutableIntStateOf(group.firstOrNull()?.channel ?: 1) }
    if (group.isNotEmpty() && group.none { it.channel == ch }) ch = group.first().channel
    var files by remember { mutableStateOf<Result<List<Dvr.RecFile>>?>(null) }
    var job by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var err by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<File?>(null) }
    val date = LocalDate.now().plusDays(day.toLong())

    LaunchedEffect(ri, ch, day, motion, group.size) {
        files = null
        val cam = group.firstOrNull() ?: return@LaunchedEffect
        files = withContext(Dispatchers.IO) {
            // στο DVRIP τα κανάλια μετρούν από 0, στο RTSP από 1
            runCatching { Dvr.connect(cam.host, cam.user, cam.pass).use { it.files((ch - 1).coerceAtLeast(0), "$date 00:00:00", "$date 23:59:59", if (motion) "M" else "*") } }
        }
    }

    playing?.let { f -> VideoPlayer(f) { playing = null }; return }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp)) {
            ScreenTitle(eyebrow = "Recorder", title = "Playback")
        }
        if (recs.isEmpty()) {
            GlassCard(Modifier.padding(20.dp).fillMaxWidth()) {
                Eyebrow("No recorder")
                Spacer(Modifier.height(6.dp))
                Text("Add an XMeye NVR from the Cameras tab to browse its recordings.", color = c.mutedStrong, fontSize = 14.sp)
            }
            return
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (recs.size > 1) recs.indices.forEach { k -> Chip("Recorder ${k + 1}", ri == k) { ri = k } }
            group.forEach { cam -> Chip(cam.name, ch == cam.channel) { ch = cam.channel } }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { day-- }) { Icon(Icons.Outlined.ChevronLeft, "Previous day", tint = c.fg) }
            Text(when (day) { 0 -> "Today"; -1 -> "Yesterday"; else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM")) },
                color = c.fg, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            IconButton(onClick = { day++ }, enabled = day < 0) { Icon(Icons.Outlined.ChevronRight, "Next day", tint = if (day < 0) c.fg else c.muted) }
            Spacer(Modifier.weight(1f))
            Chip("All", !motion) { motion = false }
            Spacer(Modifier.width(6.dp))
            Chip("Motion", motion) { motion = true }
        }
        job?.let {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Fetching $it…", color = c.mutedStrong, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.inset)
            }
        }
        if (err.isNotEmpty()) Text(err, color = c.down, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        when (val f = files) {
            null -> Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = c.accent) }
            else -> f.onFailure { e ->
                Text("Recorder not reachable: ${e.message ?: "error"} (is the VPN on?)", color = c.down, modifier = Modifier.padding(20.dp))
            }.onSuccess { list ->
                if (list.isEmpty()) Text("No recordings for this day.", color = c.muted, modifier = Modifier.padding(20.dp))
                LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 130.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.reversed(), key = { it.name }) { r ->
                        val cam = group.first()
                        GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(enabled = job == null) {
                            val label = r.begin.substring(11.coerceAtMost(r.begin.length))
                            job = label; progress = 0f; err = ""
                            scope.launch {
                                val out = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val dir = File(ctx.cacheDir, "playback").apply { mkdirs() }
                                        // κρατάμε μόνο τις 5 πιο πρόσφατες
                                        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(5)?.forEach { it.delete() }
                                        val mp4 = File(dir, r.name.hashCode().toUInt().toString() + ".mp4")
                                        if (!mp4.exists()) {
                                            val raw = File(dir, "dl.raw")
                                            Dvr.connect(cam.host, cam.user, cam.pass).use { d ->
                                                d.download(r, raw) { b -> progress = if (r.sizeKb > 0) (b / 1024f / r.sizeKb).coerceIn(0f, 1f) else 0f }
                                            }
                                            val tmp = File(dir, "tmp.mp4")
                                            remuxToMp4(raw, tmp); raw.delete(); tmp.renameTo(mp4)
                                        }
                                        mp4
                                    }
                                }
                                job = null
                                out.onSuccess { playing = it }.onFailure { err = "Could not play: ${it.message}" }
                            }
                        }, padding = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Outlined.PlayArrow, null, tint = c.accent)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("${r.begin.takeLast(8)} – ${r.end.takeLast(8)}", color = c.fg, style = MonoMeta.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                                    Text("%.0f MB".format(r.sizeKb / 1024.0), color = c.muted, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(f: File, onClose: () -> Unit) {
    val ctx = LocalContext.current
    ImmersiveLandscape()
    BackHandler(onBack = onClose)
    val player = remember(f) { ExoPlayer.Builder(ctx).build().apply { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f))); prepare(); playWhenReady = true } }
    DisposableEffect(player) { onDispose { player.release() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowFastForwardButton(true); setShowRewindButton(true) } }, modifier = Modifier.fillMaxSize())
    }
}
