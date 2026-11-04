package dev.silage.app.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import org.siloserver.silo.watchtogether.WatchPartyInvite
import org.siloserver.silo.watchtogether.parseWatchPartyInvite
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

/** The Watch Party invitation among the program's arguments (Windows and Linux pass a clicked `silo://` link this way). */
fun inviteLinkIn(args: Array<String>): String? = args.firstOrNull { arg ->
    arg.startsWith("silo://", ignoreCase = true) && parseWatchPartyInvite(arg) is WatchPartyInvite.Link
}

/**
 * Hands a clicked link to the Silage already running, over a loopback socket
 * whose port and secret are in a file only this user can normally read. Whoever
 * connects must know the secret, and only a valid party link is ever accepted;
 * joining still needs a press on the Watch Together page.
 */
class AppLinkChannel(private val file: Path) {
    /** Gives [link] to the running Silage; false when there's none to take it. */
    fun forward(link: String): Boolean = runCatching {
        val (port, secret) = Files.readString(file).trim().split(' ', limit = 2)
        Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
            socket.soTimeout = 2_000
            val out = socket.getOutputStream()
            out.write("$secret\n$link\n".toByteArray())
            out.flush()
            socket.getInputStream().read() == ACCEPTED
        }
    }.getOrDefault(false)

    /** Takes links from later launches for as long as the process runs. */
    fun listen(onLink: (WatchPartyInvite) -> Unit) {
        val server = runCatching { ServerSocket(0, 8, InetAddress.getLoopbackAddress()) }.getOrNull() ?: return
        val secret = UUID.randomUUID().toString()
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, "${server.localPort} $secret")
        }
        Thread({
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        it.soTimeout = 2_000
                        val lines = BufferedReader(InputStreamReader(it.getInputStream()))
                        if (lines.readLine() != secret) return@use
                        val invite = lines.readLine()?.take(MaxLinkLength)?.let(::parseWatchPartyInvite) as? WatchPartyInvite.Link ?: return@use
                        onLink(invite)
                        it.getOutputStream().write(ACCEPTED)
                    }
                }
            }
        }, "silage-app-links").apply { isDaemon = true; start() }
    }

    private companion object {
        const val ACCEPTED = 1
        const val MaxLinkLength = 4096
    }
}

/** An invitation waiting to be shown once a profile is open and no video is playing. */
object PendingInvite {
    val value = MutableStateFlow<WatchPartyInvite?>(null)
}
