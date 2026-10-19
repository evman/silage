package dev.silage.app.playback

import dev.silage.app.XdgPaths
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.network.SiloJson
import java.nio.file.Files
import java.time.Instant

/**
 * The last playback's server plan and mpv log, in $XDG_STATE_HOME/silage,
 * for diagnosing playback problems. Credentials are stripped.
 */
object PlaybackLog {
    val planFile get() = XdgPaths.state.resolve("last-playback.json")
    val mpvLogFile get() = XdgPaths.state.resolve("mpv.log")

    private val pretty = Json { prettyPrint = true }


    fun describe(decision: PlaybackDecisionResponseV3): String {
        val json = SiloJson.encodeToJsonElement(PlaybackDecisionResponseV3.serializer(), decision).jsonObject
        val redacted = strip(json)
        return dev.silage.app.SecretRedaction.text(pretty.encodeToString(JsonObject.serializer(), redacted))
    }

    private fun strip(o: JsonObject): JsonObject = JsonObject(o.mapValues { (k, v) ->
        when {
            k.contains("header", ignoreCase = true) && v is JsonObject -> JsonObject(v.mapValues { JsonPrimitive("…") })
            v is JsonObject -> strip(v)
            v is kotlinx.serialization.json.JsonArray -> kotlinx.serialization.json.JsonArray(v.map { if (it is JsonObject) strip(it) else it })
            else -> v
        }
    })

    val failureFile get() = XdgPaths.state.resolve("playback-error.log")

    /** URLs without their query (signed tokens), then the usual secret redaction. */
    fun redactLog(lines: List<String>): List<String> = lines.map { line ->
        dev.silage.app.SecretRedaction.text(line.replace(Regex("""(https?://[^\s?"']+)\?[^\s"']*"""), "$1?…"))
    }

    /** mpv's last warnings and errors for a playback that failed. */
    fun recordFailure(error: String?, log: List<String>) {
        runCatching {
            Files.createDirectories(failureFile.parent)
            // The plan of the playback that failed, before the next attempt replaces last-playback.json.
            val plan = runCatching { Files.readString(planFile) }.getOrDefault("")
            Files.writeString(failureFile, "// ${Instant.now()} mpv: ${error ?: "unknown error"}\n" + redactLog(log).joinToString("\n") +
                "\n\n// Its playback plan:\n" + plan + "\n")
        }
    }

    fun record(decision: PlaybackDecisionResponseV3) {
        runCatching {
            Files.createDirectories(planFile.parent)
            Files.writeString(planFile, "// ${Instant.now()}\n" + describe(decision))
        }
    }
}
