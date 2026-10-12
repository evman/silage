package dev.silage.player

import com.sun.jna.Pointer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** libmpv stand-in that creates a handle but refuses to initialize. */
private class RefusingLibMpv : LibMpv {
    var destroyed = false
    override fun mpv_client_api_version(): Long = 2L shl 16
    override fun mpv_create(): Pointer? = Pointer(0x1000)
    override fun mpv_initialize(ctx: Pointer): Int = -1
    override fun mpv_terminate_destroy(ctx: Pointer) { destroyed = true }
    override fun mpv_error_string(error: Int): String = "error"
    override fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int = 0
    override fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int = 0
    override fun mpv_get_property_string(ctx: Pointer, name: String): Pointer? = null
    override fun mpv_free(data: Pointer) = Unit
    override fun mpv_command(ctx: Pointer, args: Array<String>): Int = 0
    override fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int = 0
    override fun mpv_wait_event(ctx: Pointer, timeout: Double): MpvEvent = MpvEvent()
    override fun mpv_wakeup(ctx: Pointer) = Unit
}

class MpvPlayerInitTest {
    @Test fun failedInitializeDestroysTheHandle() {
        val lib = RefusingLibMpv()
        assertFailsWith<IllegalStateException> { MpvPlayer(lib = lib) }
        assertTrue(lib.destroyed, "mpv handle leaked after mpv_initialize failed")
    }
}
