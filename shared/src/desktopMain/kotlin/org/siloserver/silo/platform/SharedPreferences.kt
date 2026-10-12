package org.siloserver.silo.platform

/**
 * The subset of android.content.SharedPreferences that vendored upstream
 * persistence classes use. Same names and semantics, so those files compile
 * with only their import swapped (see scripts/vendor-shared.sh).
 */
interface SharedPreferences {
    val all: Map<String, Any?>
    fun getString(key: String, defValue: String?): String?
    fun getLong(key: String, defValue: Long): Long
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putLong(key: String, value: Long): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}
