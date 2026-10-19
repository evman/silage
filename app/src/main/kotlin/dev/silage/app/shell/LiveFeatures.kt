package dev.silage.app.shell

import dev.silage.app.notifications.DesktopToast
import dev.silage.app.notifications.freshArrivals
import dev.silage.app.notifications.text
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.repository.NotificationsRepository
import org.siloserver.silo.repository.ProfileRepository

/**
 * Server features that live for the whole signed-in session rather than one
 * page: whether Requests is available, and the notification inbox with its
 * realtime connection. Started by the first root page; follows profile
 * switches (the inbox and request permissions are per profile).
 */
class LiveFeatures(
    private val requests: RequestsFeatureStore,
    private val notifications: NotificationsRepository,
    private val profiles: ProfileRepository,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> log("live features", t) })
    private var started = false
    private var realtime: Job? = null
    /** The inbox as of the last refresh, to tell new arrivals from old rows; null until one lands. */
    @Volatile private var baseline: List<NotificationRow>? = null

    /** Whether the Silage window is in front; toasts only show when it isn't. Set by the window. */
    @Volatile var appInForeground: () -> Boolean = { true }

    private val _openInbox = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    /** Fires when a toast is clicked: the window should come forward on the inbox. */
    val openInbox: SharedFlow<Unit> = _openInbox.asSharedFlow()

    private val toast = DesktopToast { _openInbox.tryEmit(Unit) }

    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { requests.refresh() }
        scope.launch { notifications.loadCapability() }
        scope.launch { refreshInbox() }
        realtime = notifications.connectRealtime(scope)
        // A profile switch clears the inbox (NotificationsRepository resets
        // itself); reconnect with the new profile's ticket and reread.
        scope.launch {
            notifications.resetSignals.collect {
                baseline = null
                notifications.loadCapability()
                synchronized(this@LiveFeatures) { realtime?.cancel(); realtime = notifications.connectRealtime(scope) }
                refreshInbox()
            }
        }
        scope.launch {
            profiles.profileSwitches.collect {
                requests.reset()
                requests.refresh()
            }
        }
        scope.launch {
            notifications.rows.collect { rows ->
                val previous = baseline ?: return@collect
                baseline = rows
                if (appInForeground()) return@collect
                val fresh = freshArrivals(previous, rows)
                when (fresh.size) {
                    0 -> Unit
                    1 -> fresh.first().text().let { toast.show(it.title, it.body) }
                    else -> toast.show("${fresh.size} new notifications", fresh.first().text().title)
                }
            }
        }
    }

    private suspend fun refreshInbox() {
        notifications.refresh()
        baseline = notifications.rows.value
    }

    override fun close() { scope.cancel(); toast.close() }

    /** Re-reads the inbox and the Requests switch (e.g. when the inbox page opens). */
    fun refresh() {
        scope.launch { refreshInbox() }
        scope.launch { requests.refresh() }
    }
}
