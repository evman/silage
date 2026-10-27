package dev.silage.app.signin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.AccountSessionChangedException
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.DeviceSignInMachine
import org.siloserver.silo.repository.DeviceSignInState

/** A live sign-in code as the panel shows it. */
data class DeviceCode(
    /** Grouped 4+4 for reading, as the Android TV app shows it: `4821 7730`. */
    val userCode: String,
    /** `verification_uri_complete`, encoded into the QR. */
    val qrContent: String,
    /** `silo.example.com/activate`, the typed-URL form. */
    val activateText: String,
)

/** What the code panel's status line says. */
enum class DeviceSignInStatus {
    GettingCode, Waiting, Opened, NewCode, Unreachable, TooManyRequests,
    Paused, Denied, Failed, CouldntFinish, SignedIn,
}

data class DeviceSignInUi(
    /** Null while there is no live code. */
    val code: DeviceCode? = null,
    val status: DeviceSignInStatus = DeviceSignInStatus.GettingCode,
    /** The server has no device sign-in (or needs an update for it): only the password form shows. */
    val hidden: Boolean = false,
) {
    /** The panel offers "Show a new code": the machine waits for the person. */
    val canShowNewCode: Boolean get() = status in RESTARTABLE

    private companion object {
        val RESTARTABLE = setOf(DeviceSignInStatus.Paused, DeviceSignInStatus.Denied, DeviceSignInStatus.Failed, DeviceSignInStatus.CouldntFinish)
    }
}

sealed interface DeviceApprovalResult {
    /** The session is saved; [profileSelected] when the approver also chose a profile. */
    data class SignedIn(val profileSelected: Boolean) : DeviceApprovalResult
    data object CouldntFinish : DeviceApprovalResult
}

/**
 * Saves an approved device sign-in the way the Android TV app does: the
 * tokens (and the profile the approver chose, if any) replace the account
 * session atomically, but only while the sign-in context the code was started
 * under still holds, so a password sign-in that landed first is never
 * overwritten. Then [onSessionCommitted] (AuthRepository.onSessionCommitted)
 * refreshes the server's contract verdict, as every password sign-in does.
 */
suspend fun commitDeviceApproval(
    tokens: TokenManager,
    onSessionCommitted: suspend () -> Unit,
    response: DeviceLoginPollResponse,
    expected: AccountSessionExpectation,
): DeviceApprovalResult {
    val access = response.accessToken
    val refresh = response.refreshToken
    if (access.isNullOrBlank() || refresh.isNullOrBlank()) return DeviceApprovalResult.CouldntFinish
    val profileId = response.profileId?.takeIf { it.isNotBlank() }
    try {
        tokens.replaceAccountSession(
            accessToken = access,
            refreshToken = refresh,
            expiresIn = response.expiresIn ?: 0L,
            profileId = profileId,
            profileToken = response.profileToken?.takeIf { profileId != null && it.isNotBlank() },
            expectedIdentity = expected,
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: AccountSessionChangedException) {
        return DeviceApprovalResult.CouldntFinish
    } catch (_: Exception) {
        // A failed write may still have committed (the store wrote, cleanup failed).
        val committed = runCatching { tokens.getAccessToken() == access && tokens.getRefreshToken() == refresh }.getOrDefault(false)
        if (!committed) return DeviceApprovalResult.CouldntFinish
        onSessionCommitted()
        return DeviceApprovalResult.SignedIn(profileId != null)
    }
    onSessionCommitted()
    if (tokens.captureAccountSessionExpectation()?.generation != expected.generation + 1) return DeviceApprovalResult.CouldntFinish
    return DeviceApprovalResult.SignedIn(profileId != null)
}

/**
 * Drives the shared [DeviceSignInMachine] for the sign-in screen's code panel,
 * the way the Android TV app's sign-in screen does: a code (renewed in place
 * when it expires) is shown beside the password form, the person approves it
 * on a phone or browser at `<server>/activate`, and the approval's tokens are
 * saved through [commit] under the sign-in context captured when the code was
 * started. Whichever of password and code finishes first wins: the screen
 * calls [stop] when it leaves or a password sign-in succeeds, which ends
 * polling and withdraws the code on the server so it can't be approved later.
 *
 * [scope] is the screen's; [cancelScope] outlives it, for the best-effort
 * withdraw call on leaving. Call everything from one thread (the UI's).
 */
class DeviceSignInPresenter(
    private val scope: CoroutineScope,
    private val cancelScope: CoroutineScope,
    private val newMachine: suspend (serverUrl: String) -> DeviceSignInMachine,
    private val captureExpectation: suspend () -> AccountSessionExpectation?,
    private val commit: suspend (DeviceLoginPollResponse, AccountSessionExpectation) -> DeviceApprovalResult,
    private val onSignedIn: (profileSelected: Boolean) -> Unit,
    private val newCodeNoticeMs: Long = 5_000L,
) {
    private val _ui = MutableStateFlow(DeviceSignInUi())
    val ui: StateFlow<DeviceSignInUi> = _ui.asStateFlow()

    private var machine: DeviceSignInMachine? = null
    private var job: Job? = null
    private var observer: Job? = null
    private var noticeJob: Job? = null
    private var lastDeviceCode: String? = null
    private var stopped = false

    /** Get a code and poll it until it is approved or needs the person. */
    fun start() {
        if (stopped) return
        job?.cancel()
        job = scope.launch {
            val expected = runCatching { captureExpectation() }.getOrNull()
            if (expected == null) {
                // No sign-in context to bind an approval to: offer to try again.
                _ui.value = DeviceSignInUi(status = DeviceSignInStatus.Failed)
                return@launch
            }
            val current = machine?.takeIf { it.serverUrl == expected.serverUrl }
                ?: newMachine(expected.serverUrl).also { created ->
                    machine = created
                    observe(created)
                }
            current.run()
            val terminal = current.state.value
            if (terminal is DeviceSignInState.Approved) approve(terminal.response, expected)
        }
    }

    /** "Show a new code": withdraw the old code and start over. */
    fun showNewCode() {
        if (stopped) return
        job?.cancel()
        machine?.abandon(cancelScope)
        machine?.restart()
        noticeJob?.cancel()
        lastDeviceCode = null
        _ui.value = DeviceSignInUi()
        start()
    }

    /** The screen left, or a password sign-in won: stop polling and withdraw the code. */
    fun stop() {
        if (stopped) return
        stopped = true
        job?.cancel()
        noticeJob?.cancel()
        observer?.cancel()
        machine?.abandon(cancelScope)
    }

    private suspend fun approve(response: DeviceLoginPollResponse, expected: AccountSessionExpectation) {
        val result = try { commit(response, expected) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { DeviceApprovalResult.CouldntFinish }
        when (result) {
            is DeviceApprovalResult.SignedIn -> {
                _ui.value = DeviceSignInUi(status = DeviceSignInStatus.SignedIn)
                onSignedIn(result.profileSelected)
            }
            DeviceApprovalResult.CouldntFinish -> _ui.value = DeviceSignInUi(status = DeviceSignInStatus.CouldntFinish)
        }
    }

    private fun observe(current: DeviceSignInMachine) {
        observer?.cancel()
        observer = scope.launch { current.state.collect(::publish) }
    }

    private fun publish(state: DeviceSignInState) {
        // The approval's outcome is published by approve, never before the session is saved.
        if (state is DeviceSignInState.Approved) return
        val previous = _ui.value
        if (previous.status == DeviceSignInStatus.SignedIn || previous.status == DeviceSignInStatus.CouldntFinish) return
        _ui.value = when (state) {
            DeviceSignInState.GettingCode -> DeviceSignInUi(status = DeviceSignInStatus.GettingCode)
            is DeviceSignInState.ShowingCode -> {
                val renewedNow = state.renewed && lastDeviceCode != state.session.deviceCode
                lastDeviceCode = state.session.deviceCode
                if (renewedNow) announceNewCode()
                val status = when {
                    state.opened -> DeviceSignInStatus.Opened
                    renewedNow || (previous.status == DeviceSignInStatus.NewCode && noticeJob?.isActive == true) -> DeviceSignInStatus.NewCode
                    else -> DeviceSignInStatus.Waiting
                }
                DeviceSignInUi(state.session.display(), status)
            }
            is DeviceSignInState.Unreachable -> DeviceSignInUi(state.session?.display(), DeviceSignInStatus.Unreachable)
            is DeviceSignInState.TooManyRequests -> DeviceSignInUi(state.session?.display(), DeviceSignInStatus.TooManyRequests)
            DeviceSignInState.Paused -> DeviceSignInUi(status = DeviceSignInStatus.Paused)
            DeviceSignInState.Denied -> DeviceSignInUi(status = DeviceSignInStatus.Denied)
            DeviceSignInState.Failed -> DeviceSignInUi(status = DeviceSignInStatus.Failed)
            DeviceSignInState.NoDeviceSignIn, DeviceSignInState.UpdateRequired ->
                DeviceSignInUi(status = DeviceSignInStatus.Failed, hidden = true)
            is DeviceSignInState.Approved -> previous
        }
    }

    /** "New code" shows briefly, then the status reads "Waiting" again. */
    private fun announceNewCode() {
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(newCodeNoticeMs)
            _ui.update { if (it.status == DeviceSignInStatus.NewCode) it.copy(status = DeviceSignInStatus.Waiting) else it }
        }
    }

    private fun DeviceLoginStartResponse.display() = DeviceCode(
        userCode = DeviceCodeFormat.display(userCode),
        qrContent = verificationUriComplete,
        activateText = DeviceCodeFormat.activateText(verificationUri, verificationUriComplete),
    )
}
