package org.siloserver.silo.repository.port

import org.siloserver.silo.network.ApiResult

/**
 * Maps a network result to the outbox-resolution intent, shared by
 * [org.siloserver.silo.repository.PersonalDataRepository] (inline writes) and the
 * Android `SyncEngine` (outbox replay) so both classify failures identically.
 *
 * Retriable: no network, 5xx, 408, 429, and **401** — the auth interceptor
 * refreshes transparently before a result is returned, so a 401 that reaches
 * here means refresh could not complete; the write was never accepted and may
 * succeed once auth is restored. 403 and other 4xx are terminal (the request is
 * wrong/forbidden regardless of retry).
 */
fun ApiResult<*>.toWriteOutcome(): WriteOutcome = when (this) {
    is ApiResult.Success -> WriteOutcome.SYNCED
    is ApiResult.NetworkError -> WriteOutcome.RETRIABLE
    is ApiResult.Error -> if (code == 401 || code == 408 || code == 429 || code in 500..599) {
        WriteOutcome.RETRIABLE
    } else {
        WriteOutcome.TERMINAL
    }
}
