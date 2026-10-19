package org.siloserver.silo.platform

import kotlinx.serialization.json.*

/** Apply the same per-server Remember me consent to the Linux keyring/file backend. */
class LinuxSessionStore(private val secure: BlobStore) : BlobStore {
    override fun read(): String? {
        val raw = secure.read() ?: return null
        val values = runCatching { Json.parseToJsonElement(raw).jsonObject.toMutableMap() }.getOrNull() ?: return null
        // Older Linux releases always saved sessions. Keep those existing sign-ins
        // and make their previously implicit choice visible in the new checkbox.
        val servers = values.keys.filterNot(::isPublic).map { it.substringBeforeLast('.', "") }.filter { it.isNotEmpty() }.toSet()
        var migrated = false
        for (server in servers) {
            val key = "$server.${RememberedSessionStore.REMEMBER}"
            if (key !in values) {
                values[key] = buildJsonObject { put("b", true) }
                migrated = true
            }
        }
        val filtered = retained(values)
        if (migrated || filtered != raw) secure.write(filtered)
        return filtered
    }

    override fun write(contents: String): Boolean = secure.write(retained(Json.parseToJsonElement(contents).jsonObject))

    private fun retained(values: Map<String, JsonElement>): String = JsonObject(values.filterKeys { key ->
        isPublic(key) || values["${key.substringBeforeLast('.', "")}.${RememberedSessionStore.REMEMBER}"]
            ?.jsonObject?.get("b")?.jsonPrimitive?.booleanOrNull == true
    }).toString()

    private fun isPublic(key: String): Boolean = key == "server_registry.v1" || key == "server_registry.migrated.v1" ||
        key == "server_url" || key.endsWith(".${RememberedSessionStore.REMEMBER}")
}
