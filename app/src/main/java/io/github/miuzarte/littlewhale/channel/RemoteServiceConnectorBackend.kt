package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * One way of reaching a privileged process
 *
 * A channel is a pair: something that can start the process ([ProcessSpawner]) and something that
 * turns a started process into a live binder for the app. The three seams are this interface, the
 * spawner, and the permission backend, and the app only ever sees the binder they produce
 */
interface RemoteServiceConnectorBackend {

    val backend: RemoteBackend

    /** Worst case time from starting a process to holding its binder, for callers to budget on */
    val worstCaseConnectMs: Long

    fun connect(callbacks: Callbacks)

    /** Release the process behind this binder, if it is still there */
    fun disconnect(currentBinder: IBinder?)

    interface Callbacks {
        fun onConnected(backend: RemoteBackend, binder: IBinder)

        fun onDisconnected(backend: RemoteBackend)

        fun onError(backend: RemoteBackend, error: Throwable)
    }
}

/**
 * Everything both channels share: the launcher command, the token wait, and watching the process
 * once it is up
 *
 * Only the spawner differs, so a connector is this class plus a name and a suffix
 */
abstract class ProcessServiceConnectorBackend(
    private val spawner: ProcessSpawner,
    context: Context,
) : RemoteServiceConnectorBackend {

    /** Distinguishes this channel's process from the other one's, and is what `pidof` matches */
    protected abstract val processNameSuffix: String

    protected open val spawnTimeoutMs: Long = DEFAULT_SPAWN_TIMEOUT_MS

    // The spawn call and the cleanup after a timeout happen outside the wait, so they get margin
    override val worstCaseConnectMs: Long get() = spawnTimeoutMs + SPAWN_OVERHEAD_MS

    private val application: Context = context.applicationContext

    private val lock = Any()

    /** The connection currently in flight, so a newer one can retire it */
    private var pending: String? = null

    /**
     * Start the process and report its binder through `callbacks`
     *
     * Returns immediately - the wait happens on a worker thread, because it takes seconds and
     * whatever asked for the channel is not going to hold a main thread for that
     */
    override fun connect(callbacks: RemoteServiceConnectorBackend.Callbacks) {
        val token = UUID.randomUUID().toString()
        val wait = LwBootstrapRegistry.register(token)
        val processName = application.packageName + ":" + processNameSuffix
        synchronized(lock) {
            // A newer connection replaces the older one, whose token stops being claimable, so
            // the process it started is refused by the provider and exits instead of attaching
            pending?.let(LwBootstrapRegistry::unregister)
            pending = token
        }

        Thread({
            var spawned = false
            try {
                val command = buildStartCommand(processName, token)
                Log.i(TAG, "starting $processName as ${backend.label}")
                val handle = spawner.spawn(command)
                spawned = true
                val binder = awaitBinder(wait, handle)
                    ?: throw IllegalStateException(
                        "$processName did not hand back a binder within ${spawnTimeoutMs}ms",
                    )
                synchronized(lock) { if (pending == token) pending = null }
                reportConnected(processName, binder, callbacks)
            } catch (error: Throwable) {
                synchronized(lock) { if (pending == token) pending = null }
                LwBootstrapRegistry.unregister(token)
                // A process that got as far as being started but never called in has to be
                // cleaned up here: it has no reason of its own to exit
                if (spawned) spawner.killResidual(processName)
                Log.e(TAG, "$processName could not be reached over ${backend.label}", error)
                callbacks.onError(backend, error)
            }
        }, "lw-connect-${backend.label}").apply { isDaemon = true }.start()
    }

    override fun disconnect(currentBinder: IBinder?) {
        if (currentBinder == null) return
        // The same call the process makes on itself when the app dies, so both exits take one path
        runCatching { LwServiceProtocol.proxy(currentBinder).destroy() }
            .onFailure { Log.w(TAG, "could not ask the ${backend.label} service to exit", it) }
    }

    /** The launcher command, with everything the starter needs and nothing it can guess */
    private fun buildStartCommand(processName: String, token: String): String {
        val launcher = File(application.applicationInfo.nativeLibraryDir, LAUNCHER)
        check(launcher.isFile) { "the launcher is missing at ${launcher.absolutePath}" }
        val invocation = buildString {
            append(shellQuote(launcher.absolutePath))
            append(" --apk=").append(shellQuote(application.applicationInfo.sourceDir))
            append(" --process-name=").append(shellQuote(processName))
            append(" --starter-class=").append(shellQuote(STARTER_CLASS))
            append(" --token=").append(shellQuote(token))
            append(" --package=").append(shellQuote(application.packageName))
            append(" --class=").append(shellQuote(SERVICE_CLASS))
            // The user id, not the uid: it is what a package context is created for
            append(" --user-id=").append(Process.myUid() / USER_ID_DIVISOR)
            // Carried so `ps` and the starter's own log lines name the service they belong to
            append(" --debug-name=").append(shellQuote(processName))
        }
        return spawner.wrapCommand(launcher.absolutePath, invocation)
    }

    /**
     * Wait for the binder, failing as soon as the process does
     *
     * Both outcomes are real: a process that cannot start exits and has to be reported at once,
     * and one that is alive but silent has to be given the whole budget
     */
    private fun awaitBinder(wait: BootstrapWait, handle: SpawnHandle?): IBinder? {
        val deadline = System.nanoTime() + spawnTimeoutMs * NANOS_PER_MILLI
        while (true) {
            val remaining = (deadline - System.nanoTime()) / NANOS_PER_MILLI
            if (remaining <= 0) return null
            wait.await(minOf(remaining, ALIVE_POLL_MS))?.let { return it }
            if (handle != null && !handle.isAlive()) throw ProcessExitedException(handle.exitCode())
        }
    }

    /** Watch the process through its binder, then let the caller use it */
    private fun reportConnected(
        processName: String,
        binder: IBinder,
        callbacks: RemoteServiceConnectorBackend.Callbacks,
    ) {
        try {
            binder.linkToDeath({
                Log.w(TAG, "$processName is gone")
                callbacks.onDisconnected(backend)
            }, 0)
        } catch (error: RemoteException) {
            Log.w(TAG, "$processName was already gone when its binder arrived", error)
            callbacks.onError(backend, error)
            return
        }
        Log.i(TAG, "$processName is up over ${backend.label}")
        callbacks.onConnected(backend, binder)
    }

    private companion object {
        const val TAG = "LwConnector"

        /** Shipped as a native library so the app can execute it, see the CMakeLists */
        const val LAUNCHER = "liblauncher.so"

        const val STARTER_CLASS = "io.github.miuzarte.littlewhale.channel.LwServiceStarter"
        const val SERVICE_CLASS = "io.github.miuzarte.littlewhale.channel.LwPrivilegedService"

        /** Android derives the user id from the uid this way, and it is not going to change */
        const val USER_ID_DIVISOR = 100_000

        /** Starting a process, waiting for it and cleaning up after a failure all take time */
        const val SPAWN_OVERHEAD_MS = 3_000L

        /** How often a launch in flight is checked for having died, on the channels that can see it */
        const val ALIVE_POLL_MS = 250L

        /**
         * su cannot report the process, so the whole budget is spent before giving up; a slow
         * first su grant from KernelSU is the case this is sized for
         */
        const val DEFAULT_SPAWN_TIMEOUT_MS = 15_000L

        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** root: the channel the device is expected to have, and the one that needs no app installed */
class RootServiceConnector(context: Context) :
    ProcessServiceConnectorBackend(SuSpawner, context) {

    override val backend = RemoteBackend.ROOT

    override val processNameSuffix = "lw_root"
}

/**
 * Shizuku: the launcher starts as the shell identity, or as root when Shizuku itself was started
 * that way
 *
 * Its timeout is shorter because Shizuku tracks the process, so a launch that dies is visible
 * within one poll rather than only at the deadline
 */
class ShizukuServiceConnector(context: Context) :
    ProcessServiceConnectorBackend(ShizukuSpawner, context) {

    override val backend = RemoteBackend.SHIZUKU

    override val processNameSuffix = "lw_shizuku"

    override val spawnTimeoutMs = 10_000L
}
