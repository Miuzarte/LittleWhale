package io.github.miuzarte.littlewhale.channel

import android.content.AttributionSource
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.util.Log

/**
 * Hands the service binder to the app through the app's own ContentProvider
 *
 * Shizuku's user services are the obvious carrier and are exactly what this avoids: the route
 * here is a provider the app owns, whose reply bundle is how the app answers with a binder of its
 * own. That is the only direction the app can watch for the privileged process dying, and the
 * provider checks the caller's uid plus a one time token before it accepts anything, so exporting
 * it is not a way in for other apps
 *
 * The provider is reached through `IActivityManager.getContentProviderExternal`, not through
 * `ContentResolver`. That is not a preference: a process started by app_process has no
 * IApplicationThread, so AMS refuses it with "Unable to find app for caller ... when getting
 * content provider", while the external entry point exists for exactly this shape of caller. It
 * also means the call reaches the provider directly, so the provider still sees this process's
 * uid rather than the system's
 *
 * Everything hidden is reached reflectively rather than through hidden-API stubs, which keeps the
 * app compiling against the public SDK; a process started this way can reflect on the platform's
 * own classes, as the ActivityThread work above already relies on
 */
internal object LwBootstrapClient {

    private const val TAG = "LwBootstrap"

    /** The activity manager is registered under this name */
    private const val ACTIVITY_SERVICE = "activity"

    /**
     * The package name presented with the call
     *
     * Nothing on the app side reads it - the provider decides by uid and token - so this is the
     * identity the platform associates with a shell or root caller, which is what a call from
     * this process looks like
     */
    private const val CALLER_PACKAGE = "com.android.shell"

    /**
     * Deliver the service binder and collect the app's lifecycle binder
     * @param packageName the app's package, which names the provider authority.
     * @param userId the user the app runs as, which the provider has to be resolved in.
     * @param token the one time token the app is waiting on.
     * @param service the binder the app should talk to.
     * @returns the app's lifecycle binder, or null when the handshake did not complete.
     */
    fun attach(packageName: String, userId: Int, token: String, service: IBinder): IBinder? {
        val authority = packageName + LwBootstrapRegistry.AUTHORITY_SUFFIX
        val activityManager = activityManager()
        if (activityManager == null) {
            Log.e(TAG, "the activity manager is unreachable, so the app cannot be reached")
            return null
        }

        val holderToken = Binder()
        val holder = try {
            contentProviderExternal(activityManager, authority, userId, holderToken)
        } catch (error: Throwable) {
            Log.e(TAG, "could not resolve the app's provider $authority", error)
            null
        }
        if (holder == null) {
            Log.e(TAG, "the app's provider $authority is not there")
            return null
        }

        try {
            val provider = holderProvider(holder)
            if (provider == null) {
                Log.e(TAG, "the app's provider handle carries no provider")
                return null
            }
            val extras = Bundle().apply {
                putString(LwBootstrapRegistry.KEY_TOKEN, token)
                putBinder(LwBootstrapRegistry.KEY_SERVICE, service)
            }
            val reply = callProvider(provider, authority, extras)
            if (reply == null) {
                Log.e(TAG, "the provider returned nothing, it refused the token or the caller")
                return null
            }
            val lifecycle = reply.getBinder(LwBootstrapRegistry.KEY_APP_BINDER)
            if (lifecycle == null || !lifecycle.pingBinder()) {
                Log.e(TAG, "the app did not return a live lifecycle binder")
                return null
            }
            Log.i(
                TAG,
                "the app claimed the service binder, app pid "
                    + reply.getInt(LwBootstrapRegistry.KEY_APP_PID, 0),
            )
            return lifecycle
        } finally {
            // The handle exists only for as long as this call, and leaking it would keep the
            // app's provider counted as in use by a process that is about to serve binders
            releaseProviderExternal(activityManager, authority, holderToken)
        }
    }

    /** The activity manager, through the service manager and the interface stub */
    private fun activityManager(): Any? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, ACTIVITY_SERVICE) as? IBinder
        if (binder == null) {
            Log.e(TAG, "the activity service is not published")
            null
        } else {
            Class.forName("android.app.IActivityManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
        }
    } catch (error: Throwable) {
        Log.e(TAG, "could not reach the activity manager", error)
        null
    }

    /** A provider handle for a caller that is not an app process */
    private fun contentProviderExternal(
        activityManager: Any,
        authority: String,
        userId: Int,
        holderToken: IBinder,
    ): Any? = activityManager.javaClass.getMethod(
        "getContentProviderExternal",
        String::class.java,
        Integer.TYPE,
        IBinder::class.java,
        String::class.java,
    ).invoke(activityManager, authority, userId, holderToken, authority)

    /** The provider itself, out of the holder AMS answered with */
    private fun holderProvider(holder: Any): Any? = try {
        holder.javaClass.getField("provider").get(holder)
    } catch (error: Throwable) {
        Log.e(TAG, "could not read the provider out of its holder", error)
        null
    }

    /** One call, with the attribution source the platform requires of every caller */
    private fun callProvider(provider: Any, authority: String, extras: Bundle): Bundle? {
        val call = provider.javaClass.methods.firstOrNull {
            it.name == "call" && it.parameterTypes.firstOrNull() == AttributionSource::class.java
        }
        if (call == null) {
            Log.e(TAG, "the provider has no call(AttributionSource, ...) entry point")
            return null
        }
        val source = AttributionSource.Builder(Process.myUid())
            .setPackageName(CALLER_PACKAGE)
            .build()
        return call.invoke(provider, source, authority, LwBootstrapRegistry.METHOD_ATTACH, null, extras)
            as? Bundle
    }

    /** Give the handle back, which is best effort because the connection is already made */
    private fun releaseProviderExternal(
        activityManager: Any,
        authority: String,
        holderToken: IBinder,
    ) {
        runCatching {
            activityManager.javaClass.getMethod(
                "removeContentProviderExternal",
                String::class.java,
                IBinder::class.java,
            ).invoke(activityManager, authority, holderToken)
        }.onFailure { Log.w(TAG, "could not release the provider handle", it) }
    }
}
