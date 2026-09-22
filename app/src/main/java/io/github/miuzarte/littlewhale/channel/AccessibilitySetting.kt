package io.github.miuzarte.littlewhale.channel

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors

/**
 * Turning the accessibility service on and off, from the app's own settings page
 *
 * Two things are true at once here and both are the reason this is not just a switch: the service
 * can only be enabled by writing a secure setting, which the privileged process does, and whether
 * it is on is not a setting at all but the fact that the system has bound it - so the state shown
 * is read from the service itself and can lag the request by a moment
 *
 * Writing a setting is blocking, so it happens on a thread of its own; the page watches [working]
 * and [lastError] rather than waiting
 */
object AccessibilitySetting {

    private const val TAG = "LwPermission"

    /** One call at a time, since they all write the same setting */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-permission").apply { isDaemon = true }
    }

    /** Whether a request is in flight */
    var working: Boolean by mutableStateOf(false)
        private set

    /** Why the last request failed, cleared by the next one that works */
    var lastError: String? by mutableStateOf(null)
        private set

    /**
     * Whether the device has the service bound right now
     *
     * Kept as state of its own rather than read through on every render: the service is bound and
     * unbound by the system on its own schedule, so this is refreshed after a change has had time
     * to settle, not the instant the setting is written
     */
    var enabled: Boolean by mutableStateOf(LwAccessibility.running)
        private set

    /** Read the device's answer again, which is all a page needs when it appears */
    fun refresh() {
        enabled = LwAccessibility.running
    }

    /**
     * Ask for the service to be on or off
     *
     * @param on what the switch was moved to
     * @param done called back on the worker thread once the setting has been written and given a
     *   moment to take effect
     */
    fun set(on: Boolean, done: (Boolean) -> Unit = {}) {
        if (working) return
        working = true
        worker.execute {
            val written = try {
                val service = PrivilegedChannel.ensure()
                    ?: throw IllegalStateException(
                        PrivilegedChannel.state().error ?: "the privileged channel is not available",
                    )
                if (service.setAccessibility(on)) {
                    lastError = null
                    true
                } else {
                    throw IllegalStateException("the privileged process could not write the setting")
                }
            } catch (error: Throwable) {
                lastError = error.message ?: error.javaClass.simpleName
                Log.w(TAG, "could not ${if (on) "enable" else "disable"} accessibility", error)
                false
            }
            settle(on)
            working = false
            done(written)
        }
    }

    /** Give the system the moment it takes to bind or unbind the service, then report the truth */
    private fun settle(on: Boolean) {
        var waited = 0L
        while (LwAccessibility.running != on && waited < SETTLE_BUDGET_MS) {
            Thread.sleep(SETTLE_STEP_MS)
            waited += SETTLE_STEP_MS
        }
        refresh()
        if (LwAccessibility.running != on) {
            lastError = "系统没有把服务绑上, 设置写进去了但没生效; 可能是别的应用把它改了回来," +
                " 也可能被 Android 13+ 的受限设置挡着, 那种情况在应用详情里允许一次就好"
        }
    }

    /** How long to wait for the binding to follow the setting, in steps so it is not a fixed sleep */
    private const val SETTLE_BUDGET_MS = 3_000L
    private const val SETTLE_STEP_MS = 200L
}
