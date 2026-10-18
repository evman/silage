package dev.silage.app.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt

/** A monitor in desktop (logical) coordinates. */
data class Output(val name: String, val x: Int, val y: Int, val width: Int, val height: Int, val hdr: Boolean)

/**
 * Where mpv draws. HDR can only reach the screen from a native Wayland
 * surface, and the Silage window is an XWayland one, so an HDR monitor gets
 * mpv's own fullscreen window; anything else embeds in the Silage window.
 */
sealed interface VideoOutput {
    data class Embedded(val wid: Long) : VideoOutput
    data class OwnWindow(val screen: String?) : VideoOutput

    fun mpvOptions(osName: String = System.getProperty("os.name")): Map<String, String> {
        if (osName.startsWith("Windows", ignoreCase = true)) {
            // mpv expects a 32-bit HWND value, ignoring the high bits.
            // Until Windows HDR detection is implemented, explicitly tone-map to SDR.
            return common + mapOf("gpu-api" to "d3d11", "gpu-context" to "d3d11",
                "hwdec" to "auto-safe", "target-colorspace-hint" to "no") + when (this) {
                is Embedded -> mapOf("wid" to (wid and 0xffffffffL).toString(),
                    "input-vo-keyboard" to "no", "input-cursor-passthrough" to "yes")
                is OwnWindow -> mapOf("fullscreen" to "yes", "title" to "Silage")
            }
        }
        return common + when (this) {
        // wid is an X11 window: skip the Wayland contexts mpv would try first.
        is Embedded -> mapOf("wid" to wid.toString(), "gpu-context" to "x11vk", "input-vo-keyboard" to "no", "input-cursor-passthrough" to "yes")
        is OwnWindow -> listOfNotNull(
            "fullscreen" to "yes",
            "gpu-context" to "waylandvk,x11vk",
            "wayland-app-id" to "silage",
            "title" to "Silage",
            screen?.let { "fs-screen-name" to it },
        ).toMap()
        }
    }

    companion object {
        private val common = mapOf(
            // First, so the explicit scaler settings below refine it.
            "profile" to "high-quality",
            "vo" to "gpu-next",
            // 1080p films are upscaled to 4K by mpv: use its sharpest scaler, and deband.
            "scale" to "ewa_lanczossharp",
            "cscale" to "ewa_lanczossharp",
            "deband" to "yes",
            "hwdec" to "vaapi,auto-safe",
            "target-colorspace-hint" to "yes",
            "force-window" to "yes",
            "cache" to "yes",
            "demuxer-max-bytes" to "1024MiB",
            "demuxer-max-back-bytes" to "256MiB",
            "demuxer-readahead-secs" to "300",
        )

        /** [override]: "on" always uses mpv's own window, "off" never does (SILAGE_HDR). */
        fun choose(wid: Long?, under: Output?, override: String?): VideoOutput = when {
            override == "on" -> OwnWindow(under?.name)
            override == "off" -> wid?.let(::Embedded) ?: OwnWindow(under?.name)
            under?.hdr == true -> OwnWindow(under.name)
            wid != null -> Embedded(wid)
            else -> OwnWindow(under?.name)
        }
    }
}

/** Reads KDE Plasma's ~/.config/kwinoutputconfig.json. */
object KwinOutputs {
    /**
     * The outputs of the setup whose positions match the connected screens'
     * origins (falling back to the last setup); empty when unreadable.
     */
    fun parse(json: String, screenOrigins: Set<Pair<Int, Int>>): List<Output> = runCatching {
        val sections = Json.parseToJsonElement(json).jsonArray.map { it.jsonObject }
        fun section(name: String) = sections.first { it["name"]?.jsonPrimitive?.content == name }["data"]!!.jsonArray
        val outputs = section("outputs").map { it.jsonObject }
        val setups = section("setups").map { s -> s.jsonObject["outputs"]!!.jsonArray.map { it.jsonObject } }
        fun origin(o: JsonObject) = o["position"]!!.jsonObject.let { it.int("x") to it.int("y") }
        val setup = setups.firstOrNull { s -> s.filter { it.bool("enabled") }.map(::origin).toSet() == screenOrigins }
            ?: setups.last()
        setup.filter { it.bool("enabled") }.map { entry ->
            val out = outputs[entry.int("outputIndex")]
            val mode = out["mode"]!!.jsonObject
            val scale = out["scale"]?.jsonPrimitive?.doubleOrNull?.takeIf { it > 0 } ?: 1.0
            val (x, y) = origin(entry)
            Output(
                name = out["connectorName"]!!.jsonPrimitive.content,
                x = x, y = y,
                width = (mode.int("width") / scale).roundToInt(),
                height = (mode.int("height") / scale).roundToInt(),
                hdr = out.bool("highDynamicRange"),
            )
        }
    }.getOrDefault(emptyList())

    fun outputAt(outputs: List<Output>, x: Int, y: Int): Output? =
        outputs.firstOrNull { x >= it.x && y >= it.y && x < it.x + it.width && y < it.y + it.height }

    private fun JsonObject.int(key: String) = this[key]!!.jsonPrimitive.intOrNull!!
    private fun JsonObject.bool(key: String) = this[key]?.jsonPrimitive?.booleanOrNull ?: false
}
