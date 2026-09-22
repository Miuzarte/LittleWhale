package io.github.miuzarte.littlewhale.channel

import android.os.Binder
import android.os.IBinder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Where the app waits for a privileged process to hand its binder back
 *
 * A connection is a token and a wait: the connector registers the token before it starts the
 * process, the provider completes the wait when the process calls in, and the token is removed at
 * that moment so it can never be claimed twice. Retiring a wait is also how a connection that
 * lost a race is dropped - the process still holding the old token is then refused by the
 * provider and exits on its own
 */
object LwBootstrapRegistry {

    /** Appended to the package name to form the provider authority */
    const val AUTHORITY_SUFFIX = ".lw.bootstrap"

    /** Provider method the privileged process calls */
    const val METHOD_ATTACH = "attachService"

    /** Bundle key: the one time token */
    const val KEY_TOKEN = "token"

    /** Bundle key: the service binder, app bound */
    const val KEY_SERVICE = "service_binder"

    /** Bundle key: this process's binder, returned so the privileged side can watch it */
    const val KEY_APP_BINDER = "app_binder"

    /** Bundle key: this process's pid, returned for diagnostics */
    const val KEY_APP_PID = "app_pid"

    private val pending = ConcurrentHashMap<String, BootstrapWait>()

    /**
     * The binder the privileged process links against to learn that the app died
     *
     * One instance for the whole process: it exists exactly as long as the thing it stands for
     */
    private val lifecycle = Binder()

    /** Start waiting for a process that was launched with this token */
    fun register(token: String): BootstrapWait =
        BootstrapWait().also { pending[token] = it }

    /** Give up on a token, which is what makes a late process's call get refused */
    fun unregister(token: String) {
        pending.remove(token)?.cancel()
    }

    /** Claim a token, handing the caller this process's lifecycle binder in return */
    fun attach(token: String, service: IBinder): IBinder? {
        val wait = pending.remove(token) ?: return null
        wait.complete(service)
        return lifecycle
    }
}

/**
 * One connection's rendezvous with the privileged process
 *
 * Waiting is sliced rather than waited out in one call because the connector also watches the
 * process, and a launch that died has to fail immediately instead of sitting out the timeout
 */
class BootstrapWait {

    private val latch = CountDownLatch(1)

    @Volatile
    private var binder: IBinder? = null

    @Volatile
    private var cancelled = false

    /** The privileged process handed its binder over */
    fun complete(service: IBinder) {
        binder = service
        latch.countDown()
    }

    /** Nobody is waiting for this connection any more */
    fun cancel() {
        cancelled = true
        latch.countDown()
    }

    /**
     * Wait up to `timeoutMs` for the binder
     * @param timeoutMs how long to block.
     * @returns the service binder, or null when it has not arrived, was cancelled, or already was
     */
    fun await(timeoutMs: Long): IBinder? {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return if (cancelled) null else binder
    }
}
