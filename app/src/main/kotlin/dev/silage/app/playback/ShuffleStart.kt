package dev.silage.app.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.koin.core.Koin
import org.siloserver.silo.model.shuffle.ShuffleScopeKind
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.ShufflesV2Api

/**
 * Starts a server shuffle of [kind] [id] and hands the first pick and the
 * shuffle id to [onStart]. Null while the server doesn't offer shuffle for
 * this kind of scope (older server, or the profile isn't allowed), so the
 * button stays hidden.
 */
@Composable
fun rememberShuffleStart(koin: Koin, kind: ShuffleScopeKind, id: String, onStart: (contentId: String, shuffleId: String) -> Unit): (() -> Unit)? {
    val api = remember { koin.get<ShufflesV2Api>() }
    var supported by remember(kind, id) { mutableStateOf(false) }
    LaunchedEffect(kind, id) { supported = (api.capability() as? ApiResult.Success)?.data?.supports(kind) == true }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    if (!supported) return null
    return {
        if (!busy) {
            busy = true
            scope.launch {
                (api.create(kind, id) as? ApiResult.Success)?.data?.let { onStart(it.current.contentId, it.id) }
                busy = false
            }
        }
    }
}
