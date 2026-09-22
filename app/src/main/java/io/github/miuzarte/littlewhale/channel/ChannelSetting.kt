package io.github.miuzarte.littlewhale.channel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * One way of reaching a privileged process, and where it stands right now
 *
 * @property backend which route this is
 * @property available whether the route exists on this device at all, which for root is always
 *   true: nothing an app can read says whether a root manager would answer it
 * @property granted `true` when the permission is in place, `false` when it was asked for and was
 *   not given, and `null` when nothing has asked yet - only root can be `null`, because Shizuku
 *   answers the question without being tried
 */
data class RouteState(
    val backend: RemoteBackend,
    val available: Boolean,
    val granted: Boolean?,
)

/**
 * The privileged channel, for the settings page
 *
 * Step 3 deliberately left this without a UI - the tools were the only consumers, and `lw_probe`
 * was the only way to see whether the channel was up. What that costs is the first run: a fresh
 * install shows nothing but tools failing, and nothing on the phone says which of root and Shizuku
 * is missing or where to allow it. Hence this: the state of the channel, what each route is still
 * waiting for, and one button that tries
 *
 * Everything that asks the system runs on a thread of its own. The first connection is where
 * KernelSU puts its prompt up, and Shizuku puts its own dialog up, so the try can take a minute -
 * which on a main thread is an ANR rather than a wait
 */
object ChannelSetting {

    private const val TAG = "LwChannel"

    /** One call at a time, since they all ask the same two backends */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-channel-ui").apply { isDaemon = true }
    }

    /** Whether a connection attempt is in flight, including however long a prompt stays up */
    var working: Boolean by mutableStateOf(false)
        private set

    /**
     * What the channel is, or null before it has been read
     *
     * Not read at construction: that would be a binder round trip on whichever thread happened to
     * touch this first, which is the thread drawing the first frame
     */
    var state: ChannelState? by mutableStateOf(null)
        private set

    /** Both routes in the order the channel tries them, root first */
    var routes: List<RouteState> by mutableStateOf(emptyList())
        private set

    /** Read the state once, without connecting anything and without any prompt */
    fun refresh() {
        worker.execute { read() }
    }

    /**
     * Try to connect, which is also how a permission is asked for on the route that needs one
     *
     * @param done called back on the worker thread once the state has been read again
     */
    fun connect(done: () -> Unit = {}) {
        if (working) return
        working = true
        worker.execute {
            try {
                PrivilegedChannel.ensure()
            } catch (error: Throwable) {
                Log.w(TAG, "connecting the privileged channel failed", error)
            }
            // A failure is not lost by not being kept here: the channel remembers why, and
            // [read] puts that reason straight into the state
            read()
            working = false
            done()
        }
    }

    /** Ask both routes where they stand, on the worker thread */
    private fun read() {
        state = PrivilegedChannel.state()
        routes = listOf(
            RouteState(
                backend = RootAccessBackend.backend,
                available = RootAccessBackend.isAvailable(),
                granted = RootAccessBackend.rootState(),
            ),
            RouteState(
                backend = ShizukuAccessBackend.backend,
                available = ShizukuAccessBackend.isAvailable(),
                granted = ShizukuAccessBackend.isGranted(),
            ),
        )
    }
}
