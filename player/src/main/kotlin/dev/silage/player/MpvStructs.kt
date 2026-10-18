package dev.silage.player

import com.sun.jna.Pointer
import com.sun.jna.Structure

@Structure.FieldOrder("event_id", "error", "reply_userdata", "data")
class MpvEvent : Structure() {
    @JvmField var event_id: Int = 0
    @JvmField var error: Int = 0
    @JvmField var reply_userdata: Long = 0
    @JvmField var data: Pointer? = null
}

@Structure.FieldOrder("name", "format", "data")
class MpvEventProperty(p: Pointer) : Structure(p) {
    @JvmField var name: String? = null
    @JvmField var format: Int = 0
    @JvmField var data: Pointer? = null
    init { read() }
}

@Structure.FieldOrder("reason", "error")
class MpvEventEndFile(p: Pointer) : Structure(p) {
    @JvmField var reason: Int = 0
    @JvmField var error: Int = 0
    init { read() }
}

@Structure.FieldOrder("prefix", "level", "text", "log_level")
class MpvEventLogMessage(p: Pointer) : Structure(p) {
    @JvmField var prefix: String? = null
    @JvmField var level: String? = null
    @JvmField var text: String? = null
    @JvmField var log_level: Int = 0
    init { read() }
}

@Structure.FieldOrder("num_args", "args")
class MpvEventClientMessage(p: Pointer) : Structure(p) {
    @JvmField var num_args: Int = 0
    @JvmField var args: Pointer? = null
    init { read() }
    fun strings(): List<String> = args?.getStringArray(0, num_args)?.toList() ?: emptyList()
}
