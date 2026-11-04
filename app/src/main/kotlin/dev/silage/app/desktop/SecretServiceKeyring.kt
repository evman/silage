package dev.silage.app.desktop

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.Variant
import org.siloserver.silo.platform.KeyringAccess
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The sign-in in the desktop keyring (KWallet, GNOME Keyring) over the
 * freedesktop Secret Service API, on the same D-Bus library as Silage's media
 * keys and notifications. A separate keyring library once pulled in an
 * incompatible D-Bus version and silently disabled the keyring (0.2.0–0.5.0);
 * one library, plus a test against a real keyring, keeps that from recurring.
 */
class SecretServiceKeyring private constructor(
    private val connect: () -> DBusConnection,
    private val promptTimeoutMs: Long,
) : KeyringAccess {
    override fun get(): String? = session { bus, service, session ->
        val found = service.SearchItems(ATTRIBUTES)
        val item = found.unlocked.firstOrNull() ?: found.locked.firstOrNull()?.also { unlock(bus, service, it) } ?: return@session null
        val secret = bus.getRemoteObject(BUS_NAME, item.path, Item::class.java).GetSecret(session)
        String(secret.value, Charsets.UTF_8)
    }

    override fun set(value: String) = session { bus, service, session ->
        val collection = service.ReadAlias("default").takeIf { it.path != "/" }
            ?: error("No default keyring (wallet) exists")
        unlock(bus, service, collection)
        val properties = mapOf<String, Variant<*>>(
            "org.freedesktop.Secret.Item.Label" to Variant(LABEL),
            "org.freedesktop.Secret.Item.Attributes" to Variant(ATTRIBUTES, "a{ss}"),
        )
        val created = bus.getRemoteObject(BUS_NAME, collection.path, Collection::class.java)
            .CreateItem(properties, Secret(session, ByteArray(0), value.toByteArray(Charsets.UTF_8), "text/plain; charset=utf8"), true)
        prompt(bus, created.prompt)
    }

    /** How many entries hold Silage's sign-in (tests: replacing must not duplicate). */
    internal fun count(): Int = session { _, service, _ -> service.SearchItems(ATTRIBUTES).let { it.unlocked.size + it.locked.size } }

    /** Locks every collection, as a session lock or a closed wallet would (tests). */
    internal fun lockAll() = session { bus, service, _ ->
        val collections = bus.getRemoteObject(BUS_NAME, SERVICE_PATH, org.freedesktop.dbus.interfaces.Properties::class.java)
            .Get<List<DBusPath>>(SERVICE_INTERFACE, "Collections")
        prompt(bus, service.Lock(collections).prompt)
    }

    private fun <T> session(block: (DBusConnection, Service, DBusPath) -> T): T = connect().use { bus ->
        val service = bus.getRemoteObject(BUS_NAME, SERVICE_PATH, Service::class.java)
        // "plain": the secret travels only over this user's private session bus.
        val opened = service.OpenSession("plain", Variant(""))
        try { block(bus, service, opened.result) }
        finally { runCatching { bus.getRemoteObject(BUS_NAME, opened.result.path, Session::class.java).Close() } }
    }

    private fun unlock(bus: DBusConnection, service: Service, target: DBusPath) {
        val result = service.Unlock(listOf(target))
        if (target.path in result.unlocked.map { it.path }) return
        prompt(bus, result.prompt)
    }

    /** Shows the keyring's own prompt (a wallet password, say) and waits for the answer. */
    private fun prompt(bus: DBusConnection, path: DBusPath) {
        if (path.path == "/") return
        val answer = ArrayBlockingQueue<Boolean>(1)
        val remote = bus.getRemoteObject(BUS_NAME, path.path, Prompt::class.java)
        bus.addSigHandler(Prompt.Completed::class.java, remote) { answer.offer(it.dismissed) }.use {
            remote.Prompt("")
            val dismissed = answer.poll(promptTimeoutMs, TimeUnit.MILLISECONDS)
            if (dismissed == null) { runCatching { remote.Dismiss() }; error("The keyring did not answer") }
            if (dismissed) error("The keyring prompt was dismissed")
        }
    }

    companion object {
        private const val BUS_NAME = "org.freedesktop.secrets"
        private const val SERVICE_PATH = "/org/freedesktop/secrets"
        private const val SERVICE_INTERFACE = "org.freedesktop.Secret.Service"
        private const val LABEL = "Silage sign-in"
        private val ATTRIBUTES = mapOf("service" to KeyringAccess.SERVICE, "account" to KeyringAccess.ACCOUNT)

        /**
         * The session bus's Secret Service, or null when none answers (no
         * keyring installed or running). Why it's unavailable goes to [log].
         */
        fun system(
            connect: () -> DBusConnection = { DBusConnectionBuilder.forSessionBus().withShared(false).build() },
            promptTimeoutMs: Long = 120_000,
            log: (String) -> Unit = {},
        ): SecretServiceKeyring? = try {
            SecretServiceKeyring(connect, promptTimeoutMs).also { it.session { _, _, _ -> } }
        } catch (e: Exception) {
            log("Silage: the desktop keyring (Secret Service) is unavailable: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    @DBusInterfaceName("org.freedesktop.Secret.Service")
    @JvmSuppressWildcards
    internal interface Service : DBusInterface {
        fun OpenSession(algorithm: String, input: Variant<*>): Opened
        fun SearchItems(attributes: Map<String, String>): Found
        fun Unlock(objects: List<DBusPath>): Unlocked
        fun Lock(objects: List<DBusPath>): Unlocked
        fun ReadAlias(name: String): DBusPath
    }

    @DBusInterfaceName("org.freedesktop.Secret.Collection")
    @JvmSuppressWildcards
    internal interface Collection : DBusInterface {
        fun CreateItem(properties: Map<String, Variant<*>>, secret: Secret, replace: Boolean): Created
    }

    @DBusInterfaceName("org.freedesktop.Secret.Item")
    internal interface Item : DBusInterface {
        fun GetSecret(session: DBusPath): Secret
    }

    @DBusInterfaceName("org.freedesktop.Secret.Session")
    internal interface Session : DBusInterface {
        fun Close()
    }

    @DBusInterfaceName("org.freedesktop.Secret.Prompt")
    internal interface Prompt : DBusInterface {
        fun Prompt(windowId: String)
        fun Dismiss()
        class Completed(path: String, val dismissed: Boolean, val result: Variant<*>) : DBusSignal(path, dismissed, result)
    }

    /** (oayays): the session, encryption parameters (none for "plain"), the value, its content type. */
    class Secret(
        @field:Position(0) @JvmField val session: DBusPath,
        @field:Position(1) @JvmField val parameters: ByteArray,
        @field:Position(2) @JvmField val value: ByteArray,
        @field:Position(3) @JvmField val contentType: String,
    ) : Struct()

    class Opened(@field:Position(0) @JvmField val output: Variant<*>, @field:Position(1) @JvmField val result: DBusPath) : Tuple()

    @JvmSuppressWildcards
    class Found(@field:Position(0) @JvmField val unlocked: List<DBusPath>, @field:Position(1) @JvmField val locked: List<DBusPath>) : Tuple()

    @JvmSuppressWildcards
    class Unlocked(@field:Position(0) @JvmField val unlocked: List<DBusPath>, @field:Position(1) @JvmField val prompt: DBusPath) : Tuple()

    class Created(@field:Position(0) @JvmField val item: DBusPath, @field:Position(1) @JvmField val prompt: DBusPath) : Tuple()
}
