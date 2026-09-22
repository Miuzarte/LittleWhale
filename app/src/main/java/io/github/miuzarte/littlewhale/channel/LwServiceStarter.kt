package io.github.miuzarte.littlewhale.channel

import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import android.util.Log

/**
 * main() of the privileged process
 *
 * app_process turns the class named on its command line into a process, and everything the
 * channel needs has to exist before this returns: the service object, the handshake that gives
 * its binder to the app, and a death recipient on the app's lifecycle binder so the process ends
 * when the app does. The main looper then runs forever, which is what keeps the process alive for
 * the app to call into
 *
 * `@JvmStatic` matters here: app_process looks the entry point up as a static `main(String[])`
 */
object LwServiceStarter {

    private const val TAG = "LwStarter"

    /**
     * Held on purpose: a binder proxy that becomes garbage stops delivering its death
     * notification, and then the process outlives the app that owns it
     */
    private var appLifecycleBinder: IBinder? = null
    private var appDeathRecipient: IBinder.DeathRecipient? = null

    @JvmStatic
    fun main(args: Array<String>) {
        Log.i(TAG, "starter entered as uid ${Process.myUid()}")
        // app_process does not prepare one, and the service answers on it
        if (Looper.getMainLooper() == null) Looper.prepareMainLooper()

        val started = LwPrivilegedProcess.start(args)
        if (started == null) {
            Log.e(TAG, "the service could not be created, exiting")
            System.exit(1)
            return
        }

        val lifecycle = LwBootstrapClient.attach(
            started.packageName,
            started.userId,
            started.token,
            started.service,
        )
        if (lifecycle == null) {
            Log.e(TAG, "the service binder did not reach the app, exiting")
            System.exit(1)
            return
        }

        val recipient = IBinder.DeathRecipient {
            Log.i(TAG, "the app died, releasing the service")
            started.destroy()
            System.exit(0)
        }
        try {
            lifecycle.linkToDeath(recipient, 0)
        } catch (error: RemoteException) {
            Log.e(TAG, "the app lifecycle binder is already dead, exiting", error)
            System.exit(1)
            return
        }
        appLifecycleBinder = lifecycle
        appDeathRecipient = recipient

        Log.i(TAG, "service ready as uid ${Process.myUid()}")
        Looper.loop()
    }
}
