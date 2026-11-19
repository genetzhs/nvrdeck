package io.github.genetzhs.nvrdeck

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ───── Γραμματοσειρές (Geist / Geist Mono, όπως στο Uptime v2) ─────
val Geist = FontFamily(
    Font(R.font.geist_light, FontWeight.Light),
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold),
)
val GeistMono = FontFamily(
    Font(R.font.geist_mono, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium),
)

// ───── Cinematic tokens ─────
data class Cine(
    val dark: Boolean,
    val bg: Color, val bgEnd: Color,
    val glass: Color, val glassStrong: Color, val glassBorder: Color,
    val dock: Color, val dockIcon: Color, val inset: Color,
    val fg: Color, val muted: Color, val mutedStrong: Color,
    val pill: Color, val onPill: Color,
    val accent: Color, val gradStart: Color, val gradEnd: Color, val cyan: Color,
    val up: Color, val down: Color,
    val glowA: Color, val glowB: Color, val glowK: Float,
)

private val CineDark = Cine(
    dark = true,
    bg = Color(0xFF06070B), bgEnd = Color(0xFF0D0E15),
    glass = Color(0x09FFFFFF), glassStrong = Color(0x0DFFFFFF), glassBorder = Color(0x14FFFFFF),
    dock = Color(0xEB14151F), dockIcon = Color(0xFF9CA1B8), inset = Color(0x59000000),
    fg = Color(0xFFF3F4F8), muted = Color(0xFF8B90A6), mutedStrong = Color(0xFFC9CCDB),
    pill = Color(0xFFF3F4F8), onPill = Color(0xFF06070B),
    accent = Color(0xFF8B7CFF), gradStart = Color(0xFF9C8FFF), gradEnd = Color(0xFF6A5CFF), cyan = Color(0xFF5EE6FF),
    up = Color(0xFF3EF0A6), down = Color(0xFFFF5C7A),
    glowA = Color(0xFF8B7CFF), glowB = Color(0xFF5EE6FF), glowK = 1f,
)
private val CineLight = Cine(
    dark = false,
    bg = Color(0xFFF4F5FB), bgEnd = Color(0xFFFFFFFF),
    glass = Color(0xFFFFFFFF), glassStrong = Color(0x0A141632), glassBorder = Color(0x14141632),
    dock = Color(0xF5FFFFFF), dockIcon = Color(0xFF6A7088), inset = Color(0x0F141632),
    fg = Color(0xFF12131C), muted = Color(0xFF6A7088), mutedStrong = Color(0xFF3F4459),
    pill = Color(0xFF12131C), onPill = Color(0xFFFFFFFF),
    accent = Color(0xFF5B4BF5), gradStart = Color(0xFF7B6CFF), gradEnd = Color(0xFF5B4BF5), cyan = Color(0xFF0BA5C7),
    up = Color(0xFF10B981), down = Color(0xFFE11D48),
    glowA = Color(0xFF7B6CFF), glowB = Color(0xFF0BA5C7), glowK = .45f,
)
private val CineGreen = Cine(
    dark = false,
    bg = Color(0xFFEDF5F0), bgEnd = Color(0xFFFAFDFB),
    glass = Color(0xFFFAFDFB), glassStrong = Color(0x0A14281C), glassBorder = Color(0x1A14281C),
    dock = Color(0xF5FAFDFB), dockIcon = Color(0xFF587060), inset = Color(0x0F14281C),
    fg = Color(0xFF14281C), muted = Color(0xFF587060), mutedStrong = Color(0xFF2E4A38),
    pill = Color(0xFF14281C), onPill = Color(0xFFFFFFFF),
    accent = Color(0xFF1C8C54), gradStart = Color(0xFF2FB46E), gradEnd = Color(0xFF1C8C54), cyan = Color(0xFF0E9F8A),
    up = Color(0xFF10B981), down = Color(0xFFE11D48),
    glowA = Color(0xFF2FB46E), glowB = Color(0xFF0E9F8A), glowK = .5f,
)

val THEMES = listOf("Cinematic", "Light", "Green")

val LocalCine = staticCompositionLocalOf { CineDark }
val cine: Cine @Composable get() = LocalCine.current

// συμβατότητα με τον υπόλοιπο κώδικα
val Ok: Color @Composable get() = cine.up
val Bad: Color @Composable get() = cine.down

@Composable
fun NvrDeckTheme(theme: Int, content: @Composable () -> Unit) {
    val c = when (theme) { 1 -> CineLight; 2 -> CineGreen; else -> CineDark }
    val scheme = (if (c.dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = Color.White,
        background = c.bg, surface = c.bgEnd, surfaceVariant = c.glassStrong,
        surfaceContainer = c.bgEnd, surfaceContainerHigh = c.bgEnd, surfaceContainerHighest = c.bgEnd,
        onBackground = c.fg, onSurface = c.fg, onSurfaceVariant = c.muted,
        outline = c.glassBorder, outlineVariant = c.glassBorder, error = c.down,
        secondaryContainer = c.accent.copy(alpha = .18f), onSecondaryContainer = c.fg,
    )
    val base = Typography()
    val typo = Typography(
        bodyLarge = base.bodyLarge.copy(fontFamily = Geist), bodyMedium = base.bodyMedium.copy(fontFamily = Geist),
        bodySmall = base.bodySmall.copy(fontFamily = Geist), labelLarge = base.labelLarge.copy(fontFamily = Geist, fontWeight = FontWeight.Medium),
        labelMedium = base.labelMedium.copy(fontFamily = Geist), labelSmall = base.labelSmall.copy(fontFamily = Geist),
        titleLarge = base.titleLarge.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.copy(fontFamily = Geist, fontWeight = FontWeight.SemiBold),
    )
    CompositionLocalProvider(LocalCine provides c) {
        MaterialTheme(colorScheme = scheme, typography = typo, content = content)
    }
}

// ───── Δομικά στοιχεία ─────

/** Μεγάλες απαλές λάμψεις πίσω από το περιεχόμενο (στατικές – εξοικονόμηση μπαταρίας σε εφαρμογή βίντεο). */
@Composable
fun AmbientBackground(modifier: Modifier = Modifier) {
    val c = cine
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(c.bg, c.bgEnd)))
        val r1 = size.minDimension * .95f
        drawCircle(Brush.radialGradient(listOf(c.glowA.copy(alpha = .30f * c.glowK), c.glowA.copy(alpha = .13f * c.glowK), Color.Transparent),
            center = Offset(size.width * .1f, size.height * .06f), radius = r1), r1, Offset(size.width * .1f, size.height * .06f))
        val r2 = size.minDimension * .8f
        drawCircle(Brush.radialGradient(listOf(c.glowB.copy(alpha = .18f * c.glowK), c.glowB.copy(alpha = .07f * c.glowK), Color.Transparent),
            center = Offset(size.width * .95f, size.height * .55f), radius = r2), r2, Offset(size.width * .95f, size.height * .55f))
    }
}

/** Διάφανη «γυάλινη» κάρτα με περίγραμμα 1dp. */
@Composable
fun GlassCard(modifier: Modifier = Modifier, radius: Dp = 22.dp, padding: Dp = 16.dp, accent: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = cine
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.clip(shape)
            .background(c.glass)
            .then(if (accent) Modifier.background(Brush.linearGradient(listOf(c.accent.copy(alpha = .20f), c.accent.copy(alpha = .03f)))) else Modifier)
            .border(1.dp, if (accent) c.accent.copy(alpha = .28f) else c.glassBorder, shape)
            .padding(padding),
        content = content,
    )
}

/** Μικρή κεφαλαία ετικέτα με αραιά γράμματα. */
@Composable
fun Eyebrow(text: String, color: Color = cine.muted, leading: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        leading?.invoke()
        Text(text.uppercase(), color = color, fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 1.7.sp, maxLines = 1)
    }
}

/** Eyebrow + μεγάλος τίτλος οθόνης. */
@Composable
fun ScreenTitle(eyebrow: String, title: String, leading: (@Composable () -> Unit)? = null, right: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow(eyebrow, leading = leading)
            Text(title, color = cine.fg, style = TitleStyle, maxLines = 1)
        }
        right?.invoke()
    }
}

val TitleStyle = TextStyle(fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, letterSpacing = (-1.5).sp, lineHeight = 38.sp)
val MonoMeta = TextStyle(fontFamily = GeistMono, fontSize = 12.sp)

@Composable
fun StatusDot(on: Boolean, size: Dp = 7.dp) {
    val col = if (on) cine.up else cine.down
    Box(contentAlignment = Alignment.Center) {
        Box(Modifier.size(size * 2.2f).clip(CircleShape).background(col.copy(alpha = .22f)))
        Box(Modifier.size(size).clip(CircleShape).background(col))
    }
}

/** Gradient «χάπι» για το κύριο κουμπί. */
fun Modifier.accentGradient(c: Cine) = background(Brush.linearGradient(listOf(c.gradStart, c.gradEnd)))
