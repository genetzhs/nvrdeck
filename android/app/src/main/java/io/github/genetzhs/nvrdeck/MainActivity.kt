package io.github.genetzhs.nvrdeck

import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Close
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import android.app.Activity
import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val store = Store(app)
}

class MainActivity : FragmentActivity() {
    private fun handleClip(i: android.content.Intent?) {
        val ch = i?.getIntExtra("clip_ch", -1) ?: -1; val t = i?.getLongExtra("clip_t", 0L) ?: 0L
        if (ch > 0 && t > 0) { ClipRequest.flow.value = ch to t; i?.removeExtra("clip_ch") }
    }
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); handleClip(intent) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // εικόνα και κάτω από την εγκοπή της κάμερας σε οριζόντια θέση
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        enableEdgeToEdge()
        handleClip(intent)
        setContent {
            val vm: AppViewModel = viewModel()
            val data by vm.store.data.collectAsStateWithLifecycle()
            NvrDeckTheme(data.settings.theme) {
                Box(Modifier.fillMaxSize()) {
                    AmbientBackground()
                    var unlocked by rememberSaveable { mutableStateOf(false) }
                    if (!unlocked) LockScreen(vm.store) { unlocked = true }
                    else MainScreen(vm.store, data) { unlocked = false }
                }
            }
        }
    }
}

/** Πλήρης οθόνη: οριζόντια, χωρίς μπάρες συστήματος. Επαναφέρεται στο κλείσιμο. */
@Composable
fun ImmersiveLandscape() {
    val activity = LocalContext.current as Activity
    DisposableEffect(Unit) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val prev = activity.requestedOrientation
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = prev
        }
    }
}

// ───────────── Δακτυλικό αποτύπωμα ─────────────
private const val BIO = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK

fun biometricAvailable(a: FragmentActivity) =
    BiometricManager.from(a).canAuthenticate(BIO) == BiometricManager.BIOMETRIC_SUCCESS

fun askBiometric(a: FragmentActivity, onOk: () -> Unit) {
    val prompt = BiometricPrompt(a, ContextCompat.getMainExecutor(a), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock NVRDeck")
            .setSubtitle("Use your fingerprint")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BIO)
            .build()
    )
}

// ───────────── Κουμπιά ─────────────
/** Κύριο κουμπί: gradient χάπι, μικρή σμίκρυνση στο πάτημα (ανάδραση). */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: () -> Unit) {
    val c = cine
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier.scale(if (pressed) .97f else 1f).clip(RoundedCornerShape(50)).accentGradient(c)
            .clickable(interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        icon?.let { Icon(it, null, tint = Color.White, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val c = cine
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    Row(
        modifier.scale(if (pressed) .97f else 1f).clip(RoundedCornerShape(50)).background(c.glassStrong)
            .border(1.dp, c.glassBorder, RoundedCornerShape(50))
            .clickable(enabled = enabled, interactionSource = src, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = c.fg, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = if (enabled) c.fg else c.muted, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}

/** Επιλογή-χάπι (ενεργό = συμπαγές «pill», όπως στο Uptime). */
@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = cine
    Text(
        text,
        color = if (selected) c.onPill else c.mutedStrong,
        fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (selected) c.pill else c.glassStrong)
            .border(1.dp, if (selected) Color.Transparent else c.glassBorder, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ───────────── Κλείδωμα με PIN ─────────────
@Composable
fun LockScreen(store: Store, onUnlock: () -> Unit) {
    val c = cine
    val first = !store.hasPin
    val act = LocalContext.current as FragmentActivity
    val bio = !first && store.data.value.settings.biometric && biometricAvailable(act)
    LaunchedEffect(bio) { if (bio) askBiometric(act, onUnlock) }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Box(Modifier.fillMaxSize().systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 400.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            ScreenTitle(eyebrow = if (first) "First setup" else "Locked", title = "NVR", leading = { StatusDot(false, 6.dp) })
            GlassCard(Modifier.fillMaxWidth(), padding = 20.dp) {
                Text(if (first) "Set a PIN to protect the app" else "Enter your PIN", color = c.muted, fontSize = 14.sp)
                Spacer(Modifier.height(14.dp))
                PinField(pin, "PIN") { pin = it; err = "" }
                if (first) { Spacer(Modifier.height(10.dp)); PinField(pin2, "Repeat PIN") { pin2 = it; err = "" } }
                if (err.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(err, color = c.down, fontSize = 13.sp) }
                Spacer(Modifier.height(16.dp))
                PrimaryButton(if (first) "Save & sign in" else "Sign in", Modifier.fillMaxWidth()) {
                    if (first) when {
                        pin.length < 4 -> err = "PIN needs at least 4 digits"
                        pin != pin2 -> err = "PINs do not match"
                        else -> { store.setPin(pin); onUnlock() }
                    } else if (store.checkPin(pin)) onUnlock() else { err = "Wrong PIN"; pin = "" }
                }
                if (bio) {
                    Spacer(Modifier.height(10.dp))
                    GhostButton("Use fingerprint", Modifier.fillMaxWidth(), icon = Icons.Outlined.Fingerprint) { askBiometric(act, onUnlock) }
                }
            }
        }
    }
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = cine.accent, unfocusedBorderColor = cine.glassBorder,
    focusedContainerColor = cine.inset, unfocusedContainerColor = cine.inset,
    focusedLabelColor = cine.accent, unfocusedLabelColor = cine.muted, cursorColor = cine.accent,
)

@Composable
private fun PinField(v: String, label: String, on: (String) -> Unit) = OutlinedTextField(
    v, { s -> on(s.filter { it.isDigit() }.take(12)) }, label = { Text(label) }, singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = fieldColors(),
)

// ───────────── Κύρια οθόνη ─────────────
@Composable
fun MainScreen(store: Store, data: StoreData, onLock: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(if (data.cameras.isEmpty()) 1 else 0) }
    var solo by remember { mutableStateOf<Long?>(null) }
    val ctx = LocalContext.current
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && data.settings.alerts) askNotif.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    val latest by rememberUpdatedState(data)
    LaunchedEffect(Unit) { if (data.settings.aiAlerts) runCatching { AlertService.start(ctx) } }
    val clip by ClipRequest.flow.collectAsStateWithLifecycle()
    LaunchedEffect(clip) { if (clip != null) { solo = null; tab = 3 } }
    // Αυτόματη εύρεση καναλιών NVR που λείπουν: δοκιμάζει τα επόμενα κανάλια κάθε NVR και προσθέτει όσα δίνουν εικόνα
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3000)
        val nvrs = latest.cameras.filter { it.proto in listOf(Proto.XMEYE, Proto.HIKVISION, Proto.DAHUA) }
            .groupBy { Triple(it.host, it.port, it.proto) }.filter { it.value.size >= 2 }
        for ((_, chans) in nvrs) {
            val base = chans.maxBy { it.channel }
            val prefix = base.name.substringBeforeLast(" CH", base.name)
            var misses = 0
            var ch = base.channel + 1
            while (misses < 2 && ch <= 16 && latest.cameras.size < MAX_CH) {
                val cand = base.copy(id = System.nanoTime(), channel = ch, name = "$prefix CH$ch", group = base.group)
                if (probeCamera(ctx, cand.url()).isSuccess) {
                    if (latest.cameras.none { it.host == cand.host && it.port == cand.port && it.channel == ch })
                        store.update { it.copy(cameras = it.cameras + cand) }
                    misses = 0
                } else misses++
                ch++
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val notes = HealthBook.tick(latest.cameras.filter { it.enabled }, 60)
            if (latest.settings.alerts) notes.forEach { (t, b) -> notify(ctx, t, b) }
        }
    }

    solo?.let { id ->
        val idx = data.cameras.indexOfFirst { it.id == id }
        if (idx >= 0) {
            ImmersiveLandscape()
            BackHandler { solo = null }
            SoloView(data.cameras[idx], idx, data.settings) { solo = null }
            return
        } else solo = null
    }

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            when (tab) {
                0 -> LiveScreen(store, data) { solo = it }
                1 -> CamerasScreen(store, data)
                3 -> PlaybackScreen(data)
                else -> SettingsScreen(store, data, onLock)
            }
        }
        // απαλό σβήσιμο του περιεχομένου κάτω από τη μπάρα
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(110.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, cine.bg.copy(alpha = .92f)))))
        Dock(tab, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp)) { tab = it }
    }
}

/** Πλωτή «γυάλινη» μπάρα πλοήγησης. Το ενεργό στοιχείο γίνεται gradient χάπι με ετικέτα. */
@Composable
fun Dock(active: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = cine
    val items = listOf(Triple("Live", Icons.Outlined.Videocam, 0), Triple("Playback", Icons.Outlined.History, 3), Triple("Cameras", Icons.Outlined.GridView, 1), Triple("Settings", Icons.Outlined.Settings, 2))
    Row(
        modifier.clip(RoundedCornerShape(30.dp)).background(c.dock).border(1.dp, c.glassBorder, RoundedCornerShape(30.dp)).padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEach { (label, icon, i) ->
            val on = active == i
            Row(
                Modifier.height(46.dp).clip(RoundedCornerShape(50))
                    .then(if (on) Modifier.accentGradient(c) else Modifier)
                    .clickable { onSelect(i) }.padding(horizontal = if (on) 16.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, label, tint = if (on) Color.White else c.dockIcon, modifier = Modifier.size(21.dp))
                if (on) { Spacer(Modifier.width(7.dp)); Text(label, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
    }
}

// ───────────── Ζωντανή προβολή ─────────────
private data class Cell(val x: Float, val y: Float, val w: Float, val h: Float)

private fun layoutCells(kind: Int, n: Int): List<Cell> {
    fun grid(c: Int, r: Int) = (0 until c * r).map { Cell((it % c).toFloat() / c, (it / c).toFloat() / r, 1f / c, 1f / r) }
    fun big(g: Int, b: Int): List<Cell> {
        val u = 1f / g
        val v = mutableListOf(Cell(0f, 0f, u * b, u * b))
        for (r in 0 until g) for (c in 0 until g) if (!(r < b && c < b)) v += Cell(c * u, r * u, u, u)
        return v
    }
    return when (kind) {
        1 -> grid(1, 1)
        2 -> grid(2, 2)
        3 -> grid(2, 3)
        4 -> listOf(Cell(0f, 0f, 1f, .55f)) + (0 until 4).map { Cell((it % 2) * .5f, .55f + (it / 2) * .225f, .5f, .225f) }
        5 -> big(3, 2)
        6 -> grid(3, 3)
        else -> { // Auto: γεμίζει όλη την περιοχή, η τελευταία σειρά απλώνεται
            val m = n.coerceAtLeast(1); val cols = if (m <= 1) 1 else 2; val rows = (m + cols - 1) / cols
            (0 until rows).flatMap { r ->
                val inRow = if (r == rows - 1) m - cols * (rows - 1) else cols
                (0 until inRow).map { c -> Cell(c.toFloat() / inRow, r.toFloat() / rows, 1f / inRow, 1f / rows) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LiveScreen(store: Store, data: StoreData, onSolo: (Long) -> Unit) {
    val c = cine
    val s = data.settings
    val groups = data.cameras.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
    val cams = data.cameras.filter { it.enabled && (s.group.isEmpty() || it.group == s.group || s.group !in groups) }
    val ctx = LocalContext.current
    val views = remember { mutableMapOf<Long, androidx.media3.ui.PlayerView>() }
    // #10 κάθε λεπτό: μικρογραφία της κύριας κάμερας για το widget
    LaunchedEffect(cams.map { it.id }) {
        kotlinx.coroutines.delay(4000)
        while (true) {
            val m = cams.firstOrNull { it.id == s.mainId } ?: cams.firstOrNull()
            if (m != null) grab(views[m.id])?.let { updateWidget(ctx, it, m) }
            kotlinx.coroutines.delay(60_000)
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
            ScreenTitle(eyebrow = "${cams.size} cameras", title = "Live", leading = { StatusDot(cams.isNotEmpty(), 6.dp) }, right = {
                // μενού διάταξης (και ομάδων) δεξιά από τον τίτλο
                if (s.aiAlerts) {
                    val armed = s.armed
                    Row(Modifier.clip(RoundedCornerShape(50)).background(if (armed) c.up.copy(alpha = .16f) else c.glassStrong).border(1.dp, if (armed) c.up.copy(alpha = .4f) else c.glassBorder, RoundedCornerShape(50))
                        .clickable { store.update { it.copy(settings = it.settings.copy(armed = !armed)) } }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (armed) Icons.Outlined.Shield else Icons.Outlined.RemoveModerator, null, tint = if (armed) c.up else c.muted, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(if (armed) "Armed" else "Disarmed", color = c.fg, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                }
                var open by remember { mutableStateOf(false) }
                Box {
                    Row(Modifier.clip(RoundedCornerShape(50)).background(c.glassStrong).border(1.dp, c.glassBorder, RoundedCornerShape(50))
                        .clickable { open = true }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.GridView, null, tint = c.fg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(LAYOUTS.getOrElse(s.layout) { "Auto" } + if (s.group.isNotEmpty() && s.group in groups) " · ${s.group}" else "",
                            color = c.fg, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(open, { open = false }, containerColor = c.bgEnd, shape = RoundedCornerShape(16.dp)) {
                        Text("LAYOUT", color = c.muted, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                        LAYOUTS.forEachIndexed { i, name ->
                            DropdownMenuItem(text = { Text(name, color = if (s.layout == i) c.accent else c.fg, fontWeight = if (s.layout == i) FontWeight.SemiBold else FontWeight.Normal) },
                                trailingIcon = { if (s.layout == i) Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(16.dp)) },
                                onClick = { store.update { it.copy(settings = it.settings.copy(layout = i)) }; open = false })
                        }
                        if (groups.isNotEmpty()) {
                            HorizontalDivider(color = c.glassBorder)
                            Text("GROUP", color = c.muted, fontSize = 11.sp, letterSpacing = 1.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                            (listOf("") + groups).forEach { g ->
                                val sel = s.group == g
                                DropdownMenuItem(text = { Text(g.ifEmpty { "All cameras" }, color = if (sel) c.accent else c.fg) },
                                    trailingIcon = { if (sel) Icon(Icons.Filled.Check, null, tint = c.accent, modifier = Modifier.size(16.dp)) },
                                    onClick = { store.update { it.copy(settings = it.settings.copy(group = g)) }; open = false })
                            }
                        }
                    }
                }
            })
        }
        if (cams.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Eyebrow("No cameras")
                    Spacer(Modifier.height(6.dp))
                    Text("Add cameras or an NVR from the Cameras tab.", color = c.mutedStrong, fontSize = 14.sp)
                }
            }
            return
        }
        val main = cams.firstOrNull { it.id == s.mainId } ?: cams.first()
        val order = listOf(main) + cams.filter { it.id != main.id }
        val cells = layoutCells(s.layout, cams.size)
        val bigLayout = s.layout == 4 || s.layout == 5
        BoxWithConstraints(Modifier.fillMaxSize().padding(start = 10.dp, end = 10.dp, bottom = 96.dp)) {
            val W = maxWidth; val H = maxHeight
            order.zip(cells).forEach { (cam, cell) ->
                val idx = data.cameras.indexOfFirst { it.id == cam.id }
                key(cam.id) {
                    CameraTile(
                        cam, idx, s.showClock,
                        Modifier.offset(W * cell.x, H * cell.y).size(W * cell.w, H * cell.h).padding(3.dp)
                            .border(1.dp, c.glassBorder, RoundedCornerShape(14.dp))
                            .combinedClickable(
                                onClick = {
                                    if (bigLayout && cam.id != main.id) store.update { it.copy(settings = it.settings.copy(mainId = cam.id)) }
                                    else onSolo(cam.id)
                                },
                                onDoubleClick = { onSolo(cam.id) },
                            ),
                        fill = true,
                        onView = { views[cam.id] = it },
                    )
                }
            }
        }
    }
}

// ───────────── Κάμερες ─────────────
@Composable
fun CamerasScreen(store: Store, data: StoreData) {
    val c = cine
    var editing by remember { mutableStateOf<Camera?>(null) }
    var nvr by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Camera?>(null) }
    var qrText by remember { mutableStateOf<String?>(null) }
    val scan = rememberLauncherForActivityResult(ScanContract()) { r -> r.contents?.let { qrText = it } }
    qrText?.let { t -> QrImportDialog(t, free = MAX_CH - data.cameras.size, onDismiss = { qrText = null }) { list ->
        store.update { it.copy(cameras = it.cameras + list) }; qrText = null } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ScreenTitle(eyebrow = "Manage", title = "Cameras", right = {
                Text("${data.cameras.size}", style = MonoMeta.copy(fontSize = 15.sp), color = c.muted)
            })
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrimaryButton("Camera", icon = Icons.Filled.Add) { editing = Camera() }
                GhostButton("NVR", icon = Icons.Outlined.Storage) { nvr = true }
                GhostButton("QR", icon = Icons.Outlined.QrCodeScanner) { scan.launch(ScanOptions().setPrompt("Scan the code from NVRDeck on your computer").setBeepEnabled(false).setOrientationLocked(false).setDesiredBarcodeFormats(ScanOptions.QR_CODE)) }
            }
            Spacer(Modifier.height(6.dp))
        }
        itemsIndexed(data.cameras, key = { _, cam -> cam.id }) { i, cam ->
            GlassCard(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).clickable { editing = cam }, padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = .14f)), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = c.accent, style = MonoMeta.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(cam.name, color = c.fg, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
                        Text(
                            "${cam.host}:${cam.port}" + (if (cam.proto in listOf(Proto.XMEYE, Proto.HIKVISION, Proto.DAHUA) && cam.channel > 1) " · ch ${cam.channel}" else ""),
                            style = MonoMeta, color = c.muted, maxLines = 1,
                        )
                        val h = HealthBook.map[cam.id]
                        Text(listOfNotNull(cam.proto.label, cam.group.ifEmpty { null },
                            h?.takeIf { it.total > 0 }?.let { "${"%.0f".format(100.0 * it.online / it.total)}% up" },
                            h?.res?.ifEmpty { null }, h?.takeIf { it.reconnects > 0 }?.let { "${it.reconnects} reconnects" }).joinToString(" · "),
                            color = c.muted, fontSize = 11.5.sp, maxLines = 1)
                    }
                    Switch(
                        checked = cam.enabled,
                        onCheckedChange = { en -> store.update { d -> d.copy(cameras = d.cameras.map { if (it.id == cam.id) it.copy(enabled = en) else it }) } },
                        colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.inset, uncheckedBorderColor = c.glassBorder),
                        modifier = Modifier.scale(.85f),
                    )
                    IconButton(onClick = { deleting = cam }) { Icon(Icons.Outlined.Delete, "Delete", tint = c.down) }
                }
            }
        }
    }
    editing?.let { cam ->
        CameraEditor(cam, onDismiss = { editing = null }) { saved ->
            store.update { d -> d.copy(cameras = if (d.cameras.any { it.id == saved.id }) d.cameras.map { if (it.id == saved.id) saved else it } else d.cameras + saved) }
            editing = null
        }
    }
    if (nvr) NvrDialog(free = MAX_CH - data.cameras.size, onDismiss = { nvr = false }) { list ->
        store.update { it.copy(cameras = it.cameras + list) }; nvr = false
    }
    deleting?.let { cam ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = c.bgEnd, shape = RoundedCornerShape(22.dp),
            title = { Text("Delete camera", fontFamily = Geist, fontWeight = FontWeight.SemiBold) },
            text = { Text("Delete \"${cam.name}\"?", color = c.mutedStrong) },
            confirmButton = { TextButton(onClick = { store.update { d -> d.copy(cameras = d.cameras.filter { it.id != cam.id }) }; deleting = null }) { Text("Delete", color = c.down) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel", color = c.mutedStrong) } },
        )
    }
}

@Composable
private fun Field(label: String, v: String, hint: String = "", password: Boolean = false, number: Boolean = false, on: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        v, on, label = { Text(label) }, placeholder = { if (hint.isNotEmpty()) Text(hint, color = cine.muted.copy(alpha = .55f)) },
        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), colors = fieldColors(),
        visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = when { password -> KeyboardType.Password; number -> KeyboardType.Number; else -> KeyboardType.Uri }),
        trailingIcon = if (password) {{ IconButton(onClick = { show = !show }) { Icon(if (show) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, null, tint = cine.muted) } }} else null,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProtoPicker(value: Proto, options: List<Proto>, on: (Proto) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(value.label, {}, readOnly = true, label = { Text("Type") }, shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable))
        ExposedDropdownMenu(open, { open = false }, containerColor = cine.bgEnd) {
            options.forEach { p -> DropdownMenuItem(text = { Text(p.label) }, onClick = { on(p); open = false }) }
        }
    }
}

@Composable
private fun StreamPicker(main: Boolean, on: (Boolean) -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Chip("Sub (light)", !main) { on(false) }
    Chip("Main (HD)", main) { on(true) }
}

@Composable
private fun TestResult(testing: Boolean, result: Result<String>?) {
    val c = cine
    if (testing) Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = c.accent); Spacer(Modifier.width(8.dp))
        Text("Testing connection…", color = c.muted)
    } else result?.let {
        it.onSuccess { t -> Text("✓ $t", color = c.up) }.onFailure { e -> Text("✕ ${e.message}", color = c.down) }
    }
}

@Composable
fun CameraEditor(initial: Camera, onDismiss: () -> Unit, onSave: (Camera) -> Unit) {
    val k = cine
    var c by remember { mutableStateOf(initial) }
    var err by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    FormDialog(if (initial.name.isEmpty()) "New camera" else "Edit", "Camera", onDismiss) {
        Field("Name", c.name, "e.g. Yard") { c = c.copy(name = it) }
        Field("Group (optional)", c.group, "e.g. Outside") { c = c.copy(group = it) }
        ProtoPicker(c.proto, Proto.entries) { p -> c = c.copy(proto = p, port = if (c.port == c.proto.defaultPort) p.defaultPort else c.port) }
        Field("IP / Host", c.host, "e.g. 192.168.1.10") { c = c.copy(host = it.trim()) }
        Field("RTSP port", c.port.toString(), number = true) { c = c.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }
        if (c.proto in listOf(Proto.XMEYE, Proto.HIKVISION, Proto.DAHUA))
            Field("Channel (1 for a camera)", c.channel.toString(), number = true) { c = c.copy(channel = it.filter(Char::isDigit).take(2).toIntOrNull() ?: 1) }
        if (c.proto == Proto.CUSTOM) Field("Path", c.path, "live1.264") { c = c.copy(path = it) }
        Field("Username", c.user) { c = c.copy(user = it) }
        Field("Password", c.pass, password = true) { c = c.copy(pass = it) }
        StreamPicker(c.mainStream) { c = c.copy(mainStream = it) }
        Text(c.safeUrl(), style = MonoMeta.copy(fontSize = 10.5.sp), color = k.muted)
        TestResult(testing, result)
        if (err.isNotEmpty()) Text(err, color = k.down)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Test", enabled = !testing) {
                if (c.host.isBlank()) { err = "Required: IP / Host"; return@GhostButton }
                err = ""; testing = true; result = null
                scope.launch { result = probeCamera(ctx, c.url()); testing = false }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cancel", color = k.mutedStrong) }
            PrimaryButton("Save") {
                val miss = listOfNotNull("Name".takeIf { c.name.isBlank() }, "IP / Host".takeIf { c.host.isBlank() })
                if (miss.isNotEmpty()) err = "Required: ${miss.joinToString()}" else onSave(c.copy(name = c.name.trim()))
            }
        }
    }
}

@Composable
fun NvrDialog(free: Int, onDismiss: () -> Unit, onAdd: (List<Camera>) -> Unit) {
    val k = cine
    var name by remember { mutableStateOf("NVR") }
    var c by remember { mutableStateOf(Camera(port = 554)) }
    var first by remember { mutableStateOf("1") }
    var count by remember { mutableStateOf(minOf(8, free).coerceAtLeast(1).toString()) }
    var err by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Result<String>?>(null) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    FormDialog("NVR", "Add", onDismiss) {
        Text("Each NVR channel becomes a separate camera.", fontSize = 13.sp, color = k.muted)
        Field("Name", name) { name = it }
        ProtoPicker(c.proto, listOf(Proto.XMEYE, Proto.HIKVISION, Proto.DAHUA)) { c = c.copy(proto = it) }
        Field("IP / Host", c.host, "e.g. 192.168.1.50") { c = c.copy(host = it.trim()) }
        Field("RTSP port", c.port.toString(), number = true) { c = c.copy(port = it.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }
        Field("Username", c.user) { c = c.copy(user = it) }
        Field("Password", c.pass, password = true) { c = c.copy(pass = it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Field("From channel", first, number = true) { first = it.filter(Char::isDigit).take(2) } }
            Box(Modifier.weight(1f)) { Field("Count", count, number = true) { count = it.filter(Char::isDigit).take(2) } }
        }
        StreamPicker(c.mainStream) { c = c.copy(mainStream = it) }
        TestResult(testing, result)
        if (err.isNotEmpty()) Text(err, color = k.down)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GhostButton("Test", enabled = !testing) {
                if (c.host.isBlank()) { err = "Required: IP / Host"; return@GhostButton }
                err = ""; testing = true; result = null
                scope.launch { result = probeCamera(ctx, c.copy(channel = first.toIntOrNull() ?: 1).url()); testing = false }
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Cancel", color = k.mutedStrong) }
            PrimaryButton("Add") {
                val f = first.toIntOrNull() ?: 1
                val n = (count.toIntOrNull() ?: 0).coerceAtMost(free)
                when {
                    c.host.isBlank() -> err = "Required: IP / Host"
                    n <= 0 -> err = "No free slots"
                    else -> onAdd((0 until n).map { j -> c.copy(id = System.nanoTime() + j, channel = f + j, name = "${name.ifBlank { "NVR" }} CH${f + j}") })
                }
            }
        }
    }
}

@Composable
private fun FormDialog(title: String, eyebrow: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = cine
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxWidth(.94f).widthIn(max = 520.dp).imePadding().clip(RoundedCornerShape(28.dp))
                .background(c.bgEnd).border(1.dp, c.glassBorder, RoundedCornerShape(28.dp))
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow(eyebrow)
                Text(title, color = c.fg, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.6).sp)
                content()
            }
        }
    }
}

// ───────────── Ρυθμίσεις ─────────────
@Composable
fun SettingsScreen(store: Store, data: StoreData, onLock: () -> Unit) {
    val c = cine
    val s = data.settings
    var newPin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ScreenTitle(eyebrow = "Apply", title = "Settings")
        GlassCard(Modifier.fillMaxWidth()) {
            Eyebrow("Theme")
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                THEMES.forEachIndexed { i, t -> Chip(t, s.theme == i) { store.update { it.copy(settings = it.settings.copy(theme = i)) } } }
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Eyebrow("Display")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Clock with seconds", color = c.fg, fontWeight = FontWeight.Medium)
                    Text("LIVE badge on the video", color = c.muted, fontSize = 12.5.sp)
                }
                Switch(s.showClock, { v -> store.update { it.copy(settings = it.settings.copy(showClock = v)) } },
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.inset, uncheckedBorderColor = c.glassBorder))
            }
        }
        GlassCard(Modifier.fillMaxWidth()) {
            Eyebrow("Behaviour")
            Spacer(Modifier.height(6.dp))
            SettingSwitch("HD in full screen", "Grid uses the light stream, full screen the main one", s.autoHd) { v -> store.update { it.copy(settings = it.settings.copy(autoHd = v)) } }
            Spacer(Modifier.height(8.dp))
            SettingSwitch("Offline alerts", "Notify when a camera has no video for 60 s (while the app is open)", s.alerts) { v -> store.update { it.copy(settings = it.settings.copy(alerts = v)) } }
            Spacer(Modifier.height(8.dp))
            val sctx = LocalContext.current
            SettingSwitch("People & vehicle alerts", "Runs in the background: the recorder's motion events are checked on the phone, and you get a photo only for people or vehicles", s.aiAlerts) { v ->
                store.update { it.copy(settings = it.settings.copy(aiAlerts = v)) }
                if (v) AlertService.start(sctx) else AlertService.stop(sctx)
                // εξαίρεση από την εξοικονόμηση μπαταρίας, αλλιώς το σύστημα σταματά την υπηρεσία
                val pm = sctx.getSystemService(android.os.PowerManager::class.java)
                if (v && !pm.isIgnoringBatteryOptimizations(sctx.packageName)) runCatching {
                    sctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${sctx.packageName}")))
                }
            }
            if (s.aiAlerts) {
                Spacer(Modifier.height(8.dp))
                SettingSwitch("Photo in alerts", "On: the phone checks a photo for people/vehicles (~100 KB per event). Off: only the cameras' own people/vehicle events, no photo, almost no data", s.alertPhoto) { v ->
                    store.update { it.copy(settings = it.settings.copy(alertPhoto = v)) }
                }
            }
        }
        val recs = remember(data.cameras) { data.cameras.filter { it.proto == Proto.XMEYE }.groupBy { it.host }.values.filter { it.size > 1 }.map { g -> g.sortedBy { it.channel } } }
        var recOpen by remember { mutableStateOf(false) }
        if (recs.isNotEmpty()) GlassCard(Modifier.fillMaxWidth()) {
            Eyebrow("Recorder")
            Spacer(Modifier.height(6.dp))
            Text("Motion zones, sensitivity, recording schedule, clock, password, restart", color = c.muted, fontSize = 12.5.sp)
            Spacer(Modifier.height(8.dp))
            Button({ recOpen = true }, colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Recorder settings") }
        }
        if (recOpen) RecorderDialog(store, recs.first()) { recOpen = false }
        GlassCard(Modifier.fillMaxWidth()) {
            Eyebrow("Security")
            Spacer(Modifier.height(6.dp))
            val act = LocalContext.current as FragmentActivity
            val canBio = remember { biometricAvailable(act) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fingerprint unlock", color = c.fg, fontWeight = FontWeight.Medium)
                    Text(if (canBio) "PIN stays as a fallback" else "No fingerprint enrolled on this phone", color = c.muted, fontSize = 12.5.sp)
                }
                Switch(s.biometric && canBio, { v -> store.update { it.copy(settings = it.settings.copy(biometric = v)) } }, enabled = canBio,
                    colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.inset, uncheckedBorderColor = c.glassBorder))
            }
            Spacer(Modifier.height(10.dp))
            PinField(newPin, "New PIN") { newPin = it; msg = "" }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("Change PIN") {
                    if (newPin.length < 4) msg = "PIN needs at least 4 digits" else { store.setPin(newPin); newPin = ""; msg = "PIN changed" }
                }
                PrimaryButton("Lock", icon = Icons.Outlined.Lock, onClick = onLock)
            }
            if (msg.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(msg, fontSize = 13.sp, color = c.muted) }
        }
        GlassCard(Modifier.fillMaxWidth(), accent = true) {
            Eyebrow("Remote access", color = c.accent)
            Spacer(Modifier.height(6.dp))
            Text("Turn on your home VPN first. Cameras use their local IPs (e.g. 192.168.1.x).",
                fontSize = 13.5.sp, color = c.mutedStrong)
        }
    }
}


@Composable
fun SettingSwitch(title: String, sub: String, v: Boolean, on: (Boolean) -> Unit) {
    val c = cine
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = c.fg, fontWeight = FontWeight.Medium)
            Text(sub, color = c.muted, fontSize = 12.5.sp)
        }
        Switch(v, on, colors = SwitchDefaults.colors(checkedTrackColor = c.accent, uncheckedTrackColor = c.inset, uncheckedBorderColor = c.glassBorder))
    }
}

/** Πλήρης προβολή: HD ροή, zoom με τσίμπημα, στιγμιότυπο. Μονό πάτημα κλείνει (όταν δεν υπάρχει zoom). */
@Composable
fun SoloView(cam: Camera, idx: Int, s: Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var off by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var view by remember { mutableStateOf<androidx.media3.ui.PlayerView?>(null) }
    var msg by remember { mutableStateOf("") }
    var res by remember { mutableStateOf("") }
    var hdFailed by remember(cam.id) { mutableStateOf(false) }
    val wantHd = s.autoHd && !cam.mainStream && !hdFailed
    // αν η HD ροή δεν δώσει εικόνα σε 10s, επιστροφή στην ελαφριά
    LaunchedEffect(wantHd) { if (wantHd) { kotlinx.coroutines.delay(10_000); if (res.isEmpty()) { hdFailed = true; msg = "HD unavailable – using the light stream" } } }
    LaunchedEffect(msg) { if (msg.isNotEmpty()) { kotlinx.coroutines.delay(2000); msg = "" } }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val wPx = constraints.maxWidth.toFloat(); val hPx = constraints.maxHeight.toFloat()
        var sound by remember { mutableStateOf(false) }
        var hasAudio by remember { mutableStateOf(false) }
        CameraTile(cam, idx, s.showClock,
            Modifier.fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        val mx = wPx * (scale - 1) / 2; val my = hPx * (scale - 1) / 2
                        off = androidx.compose.ui.geometry.Offset((off.x + pan.x).coerceIn(-mx, mx), (off.y + pan.y).coerceIn(-my, my))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (scale <= 1.01f) onClose() },
                        onDoubleTap = { if (scale > 1f) { scale = 1f; off = androidx.compose.ui.geometry.Offset.Zero } else scale = 2.5f },
                    )
                }
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = off.x; translationY = off.y },
            cornerDp = 0.dp, fill = true, hd = wantHd, onView = { view = it }, onFrame = { w, h -> res = "${w}×${h}" }, sound = sound, onAudio = { hasAudio = it })
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp)
            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                val b = grab(view)
                msg = if (b != null && saveSnapshot(ctx, b, cam)) "Saved to Pictures/NVRDeck" else "No image yet"
            }) { Icon(Icons.Outlined.PhotoCamera, "Snapshot", tint = Color.White) }
            if (scale > 1.01f) TextButton(onClick = { scale = 1f; off = androidx.compose.ui.geometry.Offset.Zero }) {
                Text("${"%.1f".format(scale)}×  reset", color = Color.White, fontSize = 12.sp)
            }
            if (hasAudio) IconButton(onClick = { sound = !sound }) { Icon(if (sound) Icons.Outlined.VolumeUp else Icons.Outlined.VolumeOff, "Sound", tint = if (sound) cine.accent else Color.White) }
            if (cam.proto == Proto.XMEYE) {
                val tctx = LocalContext.current
                var talking by remember { mutableStateOf<java.util.concurrent.atomic.AtomicBoolean?>(null) }
                val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (!ok) msg = "Microphone permission is needed to talk" }
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (talking != null) cine.accent else Color.Transparent)
                    .pointerInput(cam.id) {
                        detectTapGestures(onPress = {
                            if (androidx.core.content.ContextCompat.checkSelfPermission(tctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                askMic.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures
                            }
                            val f = java.util.concurrent.atomic.AtomicBoolean(false); talking = f; msg = "Connecting…"
                            Thread { Talk.run(cam, f) { s -> msg = s } }.start()
                            tryAwaitRelease()
                            f.set(true); talking = null
                        })
                    }.padding(12.dp)) { Icon(Icons.Outlined.Mic, "Hold to talk", tint = Color.White) }
            }
            Text(res.ifEmpty { "…" }, color = Color.White.copy(alpha = .7f), fontSize = 11.sp, fontFamily = GeistMono, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
        }
        if (msg.isNotEmpty()) Text(msg, color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 16.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
fun QrImportDialog(text: String, free: Int, onDismiss: () -> Unit, onAdd: (List<Camera>) -> Unit) {
    val k = cine
    var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = k.bgEnd, shape = RoundedCornerShape(22.dp),
        title = { Text("Import from computer", fontFamily = Geist, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the passphrase you chose in NVRDeck on the computer.", color = k.mutedStrong, fontSize = 13.5.sp)
                OutlinedTextField(pass, { pass = it; err = "" }, label = { Text("Passphrase") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (err.isNotEmpty()) Text(err, color = k.down)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                val r = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { runCatching { decryptQr(text.trim(), pass) } }
                busy = false
                r.onSuccess { list -> if (list.size > free) err = "Only $free free slots (code has ${list.size})" else onAdd(list) }
                 .onFailure { err = it.message ?: "Could not read code" }
            }
        }) { Text("Import", color = k.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = k.mutedStrong) } },
    )
}
