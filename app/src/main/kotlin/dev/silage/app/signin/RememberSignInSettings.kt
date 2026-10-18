package dev.silage.app.signin

import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.platform.SharedPreferences
import org.siloserver.silo.platform.RememberedSessionStore

/** Consent belongs to one saved server/account slot, not other servers. */
class RememberSignInSettings(private val prefs: SharedPreferences, private val registry: ServerRegistry) {
    val enabled: Boolean get() = registry.activeServerId.value?.let {
        prefs.getBoolean("$it.${RememberedSessionStore.REMEMBER}", false)
    } ?: false

    fun setEnabled(enabled: Boolean): Boolean {
        val server = registry.activeServerId.value ?: return false
        // Persist filtering changes without clearing the in-memory token manager.
        return prefs.edit().putBoolean("$server.${RememberedSessionStore.REMEMBER}", enabled).commit()
    }
}
