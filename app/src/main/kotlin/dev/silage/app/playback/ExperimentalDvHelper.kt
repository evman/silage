package dev.silage.app.playback

import dev.silage.player.PlayerState
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val DvHelperExecutable = "/usr/libexec/silage-dv-helper/silage-dv-helper"
internal val DvHelperProfiles = setOf(5, 7, 8)
internal val LocalDvHelperAuthorization = androidx.compose.runtime.staticCompositionLocalOf<DvHelperAuthorization?> { null }
internal fun dvHelperRequested(enabled: Boolean = false, backend: String? = System.getenv("SILAGE_EXPERIMENTAL_DV"),
    osName: String = System.getProperty("os.name")) = osName.startsWith("Linux") && when (backend) {
        "1", "helper" -> true
        null -> enabled
        else -> false
    }

/** Portable builds authorize their bundled setup only when the installed payload differs. */
internal fun dvHelperProgram(bundled: File? = System.getProperty("silage.dv.helper.dir")?.let(::File),
    installed: File = File(DvHelperExecutable).parentFile,
    policy: File = File("/usr/share/polkit-1/actions/dev.silage.dv-helper.policy")): String {
    if (bundled != null) {
        val matches = listOf("silage-dv-helper", "kms-snapshot").all { name ->
            val payload = File(bundled, name)
            val target = File(installed, name)
            payload.isFile && target.isFile && target.canExecute() && payload.readBytes().contentEquals(target.readBytes())
        } && policy.isFile && policy.readBytes().contentEquals(File(bundled, "dev.silage.dv-helper.policy").readBytes())
        return File(if (matches) installed else bundled, if (matches) "silage-dv-helper" else "setup-helper").absolutePath
    }
    return if (File(installed, "silage-dv-helper").canExecute()) File(installed, "silage-dv-helper").absolutePath
    else "/usr/local/libexec/silage-dv-helper/silage-dv-helper"
}

internal interface DvHelperConnection : AutoCloseable {
    suspend fun ready()
    suspend fun enable(screen: String)
    fun renew()
    suspend fun release()
}

/** Root-owned optional component; the app itself always runs as the desktop user. */
internal class DvHelperProcess(private val process: Process) : DvHelperConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val events = Channel<String>(Channel.BUFFERED)
    private val writer = process.outputStream.bufferedWriter()
    private val lock = Any()
    private var closed = false

    init {
        scope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        val message = Json.parseToJsonElement(line).jsonObject
                        check(message["version"]?.jsonPrimitive?.content == "1") { "Unsupported DV helper version" }
                        events.send(message.getValue("event").jsonPrimitive.content)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                events.close(e)
            } finally { events.close() }
        }
    }

    private suspend fun await(event: String, timeoutMs: Long = 8_000) = withTimeout(timeoutMs) {
        check(events.receive() == event) { "DV helper did not acknowledge $event" }
    }

    private fun command(command: String) = synchronized(lock) {
        check(!closed) { "DV helper connection is closed" }
        writer.write(command)
        writer.newLine()
        writer.flush()
    }

    override suspend fun ready() = await("ready", 120_000)
    override suspend fun enable(screen: String) {
        require(Regex("DP-[1-9][0-9]?").matches(screen))
        command("enable $screen")
        await("active")
    }
    override fun renew() = command("renew")
    override suspend fun release() {
        command("release")
        await("restored")
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            // EOF restores even if the application is killed before reading an acknowledgement.
            runCatching { writer.close() }
        }
        scope.launch {
            try {
                if (!process.waitFor(8, TimeUnit.SECONDS)) process.destroy()
            } finally {
                runCatching { process.inputStream.close() }
                scope.cancel()
            }
        }
    }

    companion object {
        fun start(): DvHelperProcess {
            val program = dvHelperProgram()
            check(File(program).canExecute()) { "Bundled DV helper is unavailable" }
            val builder = ProcessBuilder("/usr/bin/pkexec", "--disable-internal-agent", program)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            // Avoid loading desktop capture shims into the authorization program.
            builder.environment().remove("LD_PRELOAD")
            builder.environment().remove("LD_LIBRARY_PATH")
            return DvHelperProcess(builder.start())
        }
    }
}

internal enum class DvHelperStatus { Idle, Authorizing, Active, Unavailable, Closed }

/** One authorization per application, including heartbeats while browsing between movies. */
internal class DvHelperAuthorization(
    private val connect: () -> DvHelperConnection = { DvHelperProcess.start() },
    private val pollMs: Long = 1_000,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val commands = Mutex()
    private var closed = false
    private var connection: DvHelperConnection? = null
    private var player: ExperimentalDvHelper? = null
    private var active = false
    private val mutableStatus = MutableStateFlow(DvHelperStatus.Idle)
    val status = mutableStatus.asStateFlow()

    private fun updateStatus(value: DvHelperStatus) = synchronized(lock) {
        if (!closed) {
            mutableStatus.value = value
            player?.updateStatus(value)
        }
    }

    private suspend fun release() {
        if (active) {
            connection?.release()
            active = false
        }
    }

    fun attach(session: ExperimentalDvHelper) = runBlocking {
        commands.withLock {
            check(player == null) { "A DV playback session is already attached" }
            synchronized(lock) {
                player = session
                session.updateStatus(mutableStatus.value)
            }
        }
    }

    fun detach(session: ExperimentalDvHelper) = runBlocking {
        commands.withLock {
            if (player === session) {
                synchronized(lock) { player = null }
                try {
                    release()
                    if (status.value == DvHelperStatus.Active) updateStatus(DvHelperStatus.Idle)
                } catch (_: Exception) {
                    // EOF is the restoration fallback. Never prompt again during this app run.
                    updateStatus(DvHelperStatus.Unavailable)
                    synchronized(lock) { connection?.close() }
                    scope.cancel()
                }
            }
        }
    }

    init {
        scope.launch {
            var helper: DvHelperConnection? = null
            try {
                updateStatus(DvHelperStatus.Authorizing)
                val opened = connect()
                helper = opened
                synchronized(lock) {
                    if (closed) { opened.close(); return@launch }
                    connection = opened
                }
                opened.ready()
                updateStatus(DvHelperStatus.Idle)
                while (currentCoroutineContext().isActive) {
                    opened.renew()
                    commands.withLock {
                        // Authentication may have taken time: inspect the current renderer now.
                        val current = player
                        val requested = current?.eligible() == true
                        if (requested && !active) {
                            opened.enable(current!!.screen)
                            active = true
                        } else if (!requested && active) {
                            release()
                        }
                        updateStatus(if (active) DvHelperStatus.Active else DvHelperStatus.Idle)
                    }
                    delay(pollMs)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateStatus(DvHelperStatus.Unavailable)
                System.err.println("Silage: experimental DV authorization unavailable; continuing ordinary HDR playback")
            } finally {
                synchronized(lock) { if (connection === helper) connection = null }
                runCatching { helper?.close() }
            }
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            runCatching { connection?.close() }
            connection = null
            mutableStatus.value = DvHelperStatus.Closed
            player?.updateStatus(DvHelperStatus.Closed)
        }
        scope.cancel()
    }
}

/** Per-player signal lease; closing playback restores HDR without ending app authorization. */
internal class ExperimentalDvHelper(
    internal val screen: String,
    private val state: StateFlow<PlayerState>,
    private val property: (String) -> String?,
    connect: () -> DvHelperConnection = { DvHelperProcess.start() },
    pollMs: Long = 1_000,
    authorization: DvHelperAuthorization? = null,
) : AutoCloseable {
    private val ownsAuthorization = authorization == null
    private val owner = authorization ?: DvHelperAuthorization(connect, pollMs)
    private val mutableStatus = MutableStateFlow(DvHelperStatus.Idle)
    val status = mutableStatus.asStateFlow()

    init { owner.attach(this) }

    internal fun eligible() = status.value != DvHelperStatus.Closed && state.value.loaded &&
        experimentalDvEligible(screen, property, DvHelperProfiles)

    internal fun updateStatus(value: DvHelperStatus) {
        if (status.value != DvHelperStatus.Closed) mutableStatus.value = value
    }

    fun description(): String = when (status.value) {
        DvHelperStatus.Authorizing -> "Waiting for administrator authorization"
        DvHelperStatus.Active -> "Signal active (experimental)"
        DvHelperStatus.Unavailable -> "Unavailable · using normal HDR"
        DvHelperStatus.Closed -> "Off"
        DvHelperStatus.Idle -> experimentalDvIneligibleReason(screen, property, DvHelperProfiles) ?: "Ready"
    }

    override fun close() {
        if (status.value == DvHelperStatus.Closed) return
        mutableStatus.value = DvHelperStatus.Closed
        if (ownsAuthorization) owner.close() else owner.detach(this)
    }
}
