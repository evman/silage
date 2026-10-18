package dev.silage.app.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.apiv2.MembershipV2Api
import org.siloserver.silo.repository.port.PersonalWrite
import org.siloserver.silo.repository.port.PersonalWriteHandle
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchlist, favorites and watched state for one item, straight against
 * Silo's v2 endpoints. Upstream routes these through Android's Room-backed
 * outbox, which Silage doesn't have (its stubs made every write fail), so
 * these are plain foreground requests pinned to the current sign-in.
 */
class ItemActions(
    private val membership: MembershipV2Api,
    private val personal: PersonalDataApi,
    private val tokens: TokenManager,
) {
    private val ops = AtomicLong()

    /** Null when it couldn't be read (the toggle stays hidden). */
    suspend fun inWatchlist(itemId: String): Boolean? = membership.watchlist(itemId).present()
    suspend fun isFavorite(itemId: String): Boolean? = membership.favorite(itemId).present()

    suspend fun setWatchlist(itemId: String, present: Boolean): Boolean =
        (if (present) membership.addToWatchlist(itemId) else membership.removeFromWatchlist(itemId)) is ApiResult.Success

    suspend fun setFavorite(itemId: String, present: Boolean): Boolean =
        (if (present) membership.addFavorite(itemId) else membership.removeFavorite(itemId)) is ApiResult.Success

    suspend fun setWatched(itemId: String, watched: Boolean): Boolean {
        val scope = tokens.snapshotCurrentScope() ?: return false
        val handle = PersonalWriteHandle(ops.incrementAndGet(), scope, PersonalWrite.Watched(itemId, watched))
        return personal.writePersonal(handle) is ApiResult.Success
    }

    private fun ApiResult<*>.present(): Boolean? = (this as? ApiResult.Success)?.let { it.data != null }
}

/**
 * An on/off setting saved on the server: flips at once when pressed and
 * flips back (with [failed] set) if the server doesn't accept it.
 * Presses while a write is in flight are ignored.
 */
class OptimisticToggle(initial: Boolean?, private val write: suspend (Boolean) -> Boolean) {
    var value by mutableStateOf(initial); private set
    var busy by mutableStateOf(false); private set
    var failed by mutableStateOf(false); private set

    /** A value read from the server (ignored mid-write, so it can't undo a press). */
    fun load(read: Boolean?) { if (!busy) value = read }

    /** Returns whether the server accepted the change. */
    suspend fun toggle(): Boolean {
        val current = value ?: return false
        if (busy) return false
        busy = true
        failed = false
        value = !current
        val ok = runCatching { write(!current) }.getOrDefault(false)
        if (!ok) { value = current; failed = true }
        busy = false
        return ok
    }
}
