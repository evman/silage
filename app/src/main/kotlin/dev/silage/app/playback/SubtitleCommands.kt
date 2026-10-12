package dev.silage.app.playback

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SubtitleCommands {
    fun add(load: SubtitleLoad): Array<String> =
        listOfNotNull("sub-add", load.url, if (load.select) "select" else "auto", load.title, load.language).toTypedArray()

    /** The mpv subtitle id of the built-in track at FFmpeg stream [index], from `track-list`. */
    fun sidForStream(trackList: String, index: Int): Int? = runCatching {
        Json.parseToJsonElement(trackList).jsonArray.map { it.jsonObject }.firstOrNull {
            it["type"]?.jsonPrimitive?.contentOrNull == "sub" && it["ff-index"]?.jsonPrimitive?.intOrNull == index
        }?.get("id")?.jsonPrimitive?.intOrNull
    }.getOrNull()
}
