package io.github.genetzhs.nvrdeck

import kotlinx.serialization.Serializable
import java.net.URLEncoder

@Serializable
enum class Proto(val label: String, val defaultPort: Int) {
    XMEYE("XMeye / NetSurveillance", 554),
    JOVISION("Jovision", 5544),
    HIKVISION("Hikvision", 554),
    DAHUA("Dahua", 554),
    CUSTOM("Generic RTSP (custom path)", 554),
}

@Serializable
data class Camera(
    val id: Long = System.nanoTime(),
    val name: String = "",
    val host: String = "",
    val port: Int = 554,
    val proto: Proto = Proto.XMEYE,
    val user: String = "admin",
    val pass: String = "",
    val channel: Int = 1,
    val mainStream: Boolean = false,
    val path: String = "",
    val enabled: Boolean = true,
    val group: String = "",
    val aiZone: String = "",
) {
    /** Διεύθυνση RTSP. Τα στοιχεία μπαίνουν στο userinfo (Digest/Basic από το Media3). */
    fun url(): String {
        val u = enc(user); val p = enc(pass)
        val auth = if (user.isEmpty()) "" else "$u:$p@"
        val base = "rtsp://$auth$host:$port"
        val ch = channel.coerceAtLeast(1)
        val m = mainStream
        return when (proto) {
            Proto.XMEYE -> "$base/user=$u&password=$p&channel=$ch&stream=${if (m) 0 else 1}.sdp?real_stream"
            Proto.JOVISION -> "$base/live${if (m) 0 else 1}.264"
            Proto.HIKVISION -> "$base/Streaming/Channels/${ch * 100 + if (m) 1 else 2}"
            Proto.DAHUA -> "$base/cam/realmonitor?channel=$ch&subtype=${if (m) 0 else 1}"
            Proto.CUSTOM -> "$base/${path.trimStart('/')}"
        }
    }

    fun safeUrl(): String = copy(pass = if (pass.isEmpty()) "" else "PASS").url().replace(":PASS@", ":••••@").replace("password=PASS", "password=••••")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

@Serializable
data class Settings(
    val layout: Int = 0,
    val mainId: Long = 0,
    val theme: Int = 0,
    val showClock: Boolean = true,
    val biometric: Boolean = true,
    val autoHd: Boolean = true,
    val alerts: Boolean = true,
    val group: String = "",
    val armed: Boolean = true,
    val widgetId: Long = 0,
    val aiAlerts: Boolean = false,
)

@Serializable
data class StoreData(
    val pinSalt: String = "",
    val pinHash: String = "",
    val settings: Settings = Settings(),
    val cameras: List<Camera> = emptyList(),
)

const val MAX_CH = 16

val LAYOUTS = listOf("Auto", "1", "2×2", "2×3", "1 + 4", "1 + 5", "3×3")

/** Ζώνη AI του κινητού: "R x0 y0 x1 y1" (περιοχή) ή "L x0 y0 x1 y1" (γραμμή), τιμές 0–1. */
data class AiZone(val line: Boolean, val a: Float, val b: Float, val c: Float, val d: Float) {
    fun enc() = "${if (line) "L" else "R"} $a $b $c $d"
    companion object { fun parse(s: String): AiZone? { val p = s.trim().split(" "); if (p.size != 5) return null
        val v = p.drop(1).map { it.toFloatOrNull() ?: return null }; return AiZone(p[0] == "L", v[0], v[1], v[2], v[3]) } }
}
object ClipRequest { val flow = kotlinx.coroutines.flow.MutableStateFlow<Pair<Int, Long>?>(null) }
