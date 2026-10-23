package dev.silage.app.playback

import dev.silage.player.MpvPlayer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A sound output mpv can play to: its `audio-device` name and what Windows calls it. */
data class AudioDevice(val name: String, val description: String)

object AudioDevices {
    /** mpv's own entry for "whatever the system uses". */
    const val Auto = "auto"

    /**
     * Parses mpv's `audio-device-list` (JSON). Keeps one entry per device: on Windows
     * only WASAPI's (mpv's default output there), elsewhere every output's.
     */
    fun parse(json: String?, osName: String = System.getProperty("os.name").orEmpty()): List<AudioDevice> = runCatching {
        Json.parseToJsonElement(json ?: "[]").jsonArray.mapNotNull { e ->
            val o = e.jsonObject
            val name = o["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (name == Auto) return@mapNotNull null
            if (osName.startsWith("Windows") && !name.startsWith("wasapi/")) return@mapNotNull null
            AudioDevice(name, o["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: name)
        }.distinctBy { it.name }
    }.getOrDefault(emptyList())

    /** The outputs connected now, from a short-lived mpv that plays nothing. */
    fun list(): List<AudioDevice> = runCatching {
        MpvPlayer(mapOf("vo" to "null", "force-window" to "no")).use { parse(it.getProperty("audio-device-list")) }
    }.getOrDefault(emptyList())
}
