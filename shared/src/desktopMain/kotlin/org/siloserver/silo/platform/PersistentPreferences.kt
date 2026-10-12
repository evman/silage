package org.siloserver.silo.platform

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Where a preferences blob lives. Implementations must write atomically. */
interface BlobStore {
    fun read(): String?
    fun write(contents: String): Boolean
}

/**
 * SharedPreferences over a single JSON blob. Values are stored with a
 * one-letter type tag ({"s": ...}, {"l": ...}, {"b": ...}) so getLong and
 * getBoolean round-trip exactly.
 */
class PersistentPreferences(private val store: BlobStore) : SharedPreferences {
    private val lock = Any()
    private var values: Map<String, Any> = load()

    private fun load(): Map<String, Any> {
        val raw = store.read() ?: return emptyMap()
        return runCatching {
            Json.parseToJsonElement(raw).jsonObject.mapNotNull { (key, tagged) ->
                val obj = tagged.jsonObject
                val value: Any? = obj["s"]?.jsonPrimitive?.content
                    ?: obj["l"]?.jsonPrimitive?.longOrNull
                    ?: obj["b"]?.jsonPrimitive?.booleanOrNull
                value?.let { key to it }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun serialize(map: Map<String, Any>): String = JsonObject(
        map.mapValues { (_, v) ->
            when (v) {
                is String -> JsonObject(mapOf("s" to JsonPrimitive(v)))
                is Long -> JsonObject(mapOf("l" to JsonPrimitive(v)))
                is Boolean -> JsonObject(mapOf("b" to JsonPrimitive(v)))
                else -> error("unsupported preference type ${v::class}")
            }
        },
    ).toString()

    override val all: Map<String, Any?> get() = synchronized(lock) { values }
    override fun getString(key: String, defValue: String?): String? = synchronized(lock) { values[key] as? String ?: defValue }
    override fun getLong(key: String, defValue: Long): Long = synchronized(lock) { values[key] as? Long ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean): Boolean = synchronized(lock) { values[key] as? Boolean ?: defValue }
    override fun contains(key: String): Boolean = synchronized(lock) { key in values }
    override fun edit(): SharedPreferences.Editor = EditorImpl()

    private inner class EditorImpl : SharedPreferences.Editor {
        private val ops = mutableListOf<(MutableMap<String, Any>) -> Unit>()
        override fun putString(key: String, value: String?) = apply { ops += { m -> if (value == null) m.remove(key) else m[key] = value } }
        override fun putLong(key: String, value: Long) = apply { ops += { m -> m[key] = value } }
        override fun putBoolean(key: String, value: Boolean) = apply { ops += { m -> m[key] = value } }
        override fun remove(key: String) = apply { ops += { m -> m.remove(key) } }
        override fun clear() = apply { ops += { m -> m.clear() } }
        override fun commit(): Boolean = synchronized(lock) {
            val next = values.toMutableMap().also { m -> ops.forEach { it(m) } }
            if (!store.write(serialize(next))) return false
            values = next
            true
        }
        override fun apply() { commit() }
    }
}
