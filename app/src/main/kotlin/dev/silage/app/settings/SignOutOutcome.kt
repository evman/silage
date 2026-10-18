package dev.silage.app.settings

/** Navigate away only after local authority was actually removed. */
internal suspend fun signOutAccount(logout: suspend () -> Unit, hasSession: suspend () -> Boolean): Boolean {
    try { logout() }
    catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (_: Exception) { /* Network failure may follow successful local cleanup. */ }
    return !hasSession()
}
