package dev.silage.player

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/** Direct mapping of the libmpv client API (client.h) functions Silage uses. */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_error_string(error: Int): String
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_free(data: Pointer)
    /** JNA passes String[] as a NULL-terminated char**. */
    fun mpv_command(ctx: Pointer, args: Array<String>): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEvent
    fun mpv_wakeup(ctx: Pointer)

    companion object {
        const val FORMAT_FLAG = 3
        const val FORMAT_DOUBLE = 5

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_CLIENT_MESSAGE = 16
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        /** glibc's LC_NUMERIC category. */
        private const val LC_NUMERIC = 1

        private interface LibC : Library {
            fun setlocale(category: Int, locale: String): Pointer?
        }

        /**
         * Requires client API ≥ 2.0 (mpv ≥ 0.35). mpv_create returns NULL
         * unless LC_NUMERIC is "C", and the JVM sets it from the user's
         * environment, so reset it first (number parsing only).
         */
        fun load(): LibMpv {
            Native.load("c", LibC::class.java).setlocale(LC_NUMERIC, "C")
            val lib = Native.load("mpv", LibMpv::class.java)
            val major = lib.mpv_client_api_version() ushr 16
            check(major >= 2) { "libmpv client API $major is too old; install mpv ≥ 0.38" }
            return lib
        }
    }
}
