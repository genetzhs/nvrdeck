package io.github.genetzhs.nvrdeck

import android.content.Context
import android.net.Uri
import android.view.LayoutInflater
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

@OptIn(UnstableApi::class)
fun buildPlayer(ctx: Context, url: String): ExoPlayer {
    val load = DefaultLoadControl.Builder().setBufferDurationsMs(500, 2000, 250, 500).build()
    return ExoPlayer.Builder(ctx).setLoadControl(load).build().apply {
        volume = 0f
        setMediaSource(
            RtspMediaSource.Factory().setForceUseRtpTcp(true).setTimeoutMs(8000)
                .createMediaSource(MediaItem.fromUri(Uri.parse(url)))
        )
        prepare()
        playWhenReady = true
    }
}

fun friendlyError(e: Throwable?): String {
    val m = generateSequence(e) { it.cause }.joinToString(" ") { it.message ?: "" }.lowercase()
    return when {
        "401" in m || "unauthorized" in m || "auth" in m -> "Wrong username or password"
        "404" in m || "not found" in m -> "Wrong stream path"
        "refused" in m -> "Port is closed"
        "timeout" in m || "timed out" in m || "unreachable" in m || "no route" in m -> "Camera not responding (is the VPN on?)"
        else -> "No signal"
    }
}

/** Δοκιμή σύνδεσης: περιμένει το πρώτο frame ή σφάλμα (μέγ. 10s). */
suspend fun probeCamera(ctx: Context, url: String): Result<String> {
    val player = buildPlayer(ctx, url)
    return try {
        withTimeoutOrNull(10_000) {
            suspendCancellableCoroutine<Result<String>> { cont ->
                player.addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        val f = player.videoFormat
                        if (cont.isActive) cont.resume(Result.success("OK – ${f?.sampleMimeType?.substringAfter('/') ?: ""} ${f?.width}×${f?.height}"))
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (cont.isActive) cont.resume(Result.failure(Exception(friendlyError(error))))
                    }
                })
            }
        } ?: Result.failure(Exception("Camera not responding (is the VPN on?)"))
    } finally {
        player.release()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun CameraTile(cam: Camera, index: Int, showClock: Boolean, modifier: Modifier = Modifier, fill: Boolean = false, cornerDp: androidx.compose.ui.unit.Dp = 14.dp, hd: Boolean = false, onView: ((PlayerView) -> Unit)? = null, onFrame: ((Int, Int) -> Unit)? = null) {
    val ctx = LocalContext.current
    var status by remember(cam) { mutableStateOf("Connecting…") }
    var live by remember(cam) { mutableStateOf(false) }
    var lastFrame by remember(cam) { mutableLongStateOf(0L) }
    var retry by remember(cam) { mutableIntStateOf(0) }
    val player = remember(cam, retry, hd) { buildPlayer(ctx, (if (hd) cam.copy(mainStream = true) else cam).url()) }
    LaunchedEffect(cam.id, retry) { if (retry > 0) HealthBook.report(cam.id, false, reconnect = true) }

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { live = isPlaying; if (isPlaying) status = ""; HealthBook.report(cam.id, isPlaying) }
            override fun onRenderedFirstFrame() { live = true; status = ""; lastFrame = System.currentTimeMillis(); player.videoFormat?.let { f -> HealthBook.report(cam.id, true, "${f.width}×${f.height}"); onFrame?.invoke(f.width, f.height) } }
            override fun onPlayerError(error: PlaybackException) { live = false; status = friendlyError(error) + " – reconnecting"; HealthBook.report(cam.id, false) }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    // αυτόματη επανασύνδεση + ρολόι
    LaunchedEffect(player) {
        while (true) {
            delay(1000)
            if (player.isPlaying) lastFrame = System.currentTimeMillis()
            if (player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) {
                delay(3000); retry++; break
            }
        }
    }

    Box(modifier.clip(RoundedCornerShape(cornerDp)).background(Color(0xFF05060A))) {
        AndroidView(
            factory = { c ->
                (LayoutInflater.from(c).inflate(R.layout.player_view, null) as PlayerView).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            update = { onView?.invoke(it); it.player = player; it.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.padding(5.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (live) Ok else Bad))
            Spacer(Modifier.width(5.dp))
            Text("CH${index + 1}  ${cam.name}", color = Color.White, fontSize = 10.sp, fontFamily = Geist, fontWeight = FontWeight.Medium, maxLines = 1)
        }
        if (!live) {
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                if (status.startsWith("Connect")) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.Gray)
                Text(status, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            }
        }
        if (showClock && lastFrame > 0) {
            val stale = System.currentTimeMillis() - lastFrame > 5000 || !live
            val ts = remember(lastFrame) { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(lastFrame)) }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = .55f)).padding(horizontal = 6.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(4.dp).clip(CircleShape).background(if (stale) Bad else Ok))
                Spacer(Modifier.width(4.dp))
                Text(
                    if (stale) "FROZEN $ts" else "LIVE $ts",
                    color = if (stale) Bad else Color.White.copy(alpha = .9f), fontSize = 8.5.sp, fontFamily = GeistMono, letterSpacing = .3.sp,
                )
            }
        }
    }
}
