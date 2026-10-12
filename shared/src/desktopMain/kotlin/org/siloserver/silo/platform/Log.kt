package org.siloserver.silo.platform

/** Stand-in for the android.util.Log calls in vendored persistence classes. */
object Log {
    fun e(tag: String, message: String): Int { System.err.println("E/$tag: $message"); return 0 }
    fun w(tag: String, message: String): Int { System.err.println("W/$tag: $message"); return 0 }
}
