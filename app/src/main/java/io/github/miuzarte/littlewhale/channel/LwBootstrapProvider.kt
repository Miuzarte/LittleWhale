package io.github.miuzarte.littlewhale.channel

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log

/**
 * ContentProvider the privileged process calls to hand its service binder over
 *
 * A shell or root process cannot be given a binder by any other means: it can neither bind to a
 * service of this app nor be handed one, and Shizuku's own user service machinery is what this
 * step deliberately avoids. A provider the app owns is callable from either identity, and the
 * reply bundle is the one channel that runs the other way, which is what lets the app watch for
 * the privileged process dying
 *
 * The provider is exported because the caller is not this app, and it is guarded by the caller's
 * uid plus a token that only exists while a connection is in flight
 */
class LwBootstrapProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != LwBootstrapRegistry.METHOD_ATTACH || extras == null) {
            return super.call(method, arg, extras)
        }
        val callingUid = Binder.getCallingUid()
        // Only the two identities a real channel runs as, so no other app can attach one
        if (callingUid != Process.SHELL_UID && callingUid != Process.ROOT_UID) {
            Log.w(TAG, "refusing to attach a service for uid=$callingUid")
            return null
        }
        val token = extras.getString(LwBootstrapRegistry.KEY_TOKEN)
        val service = extras.getBinder(LwBootstrapRegistry.KEY_SERVICE)
        if (token == null || service == null) {
            Log.w(TAG, "the bootstrap call carries no token or no service binder")
            return null
        }
        val appBinder = LwBootstrapRegistry.attach(token, service)
        if (appBinder == null) {
            // Either the token was never registered here or it was already claimed, and a stale
            // privileged process must not be able to attach itself to a newer connection
            Log.w(TAG, "no connection is waiting on that token (caller uid=$callingUid)")
            return null
        }
        Log.i(TAG, "attached the privileged service from uid=$callingUid")
        return Bundle().apply {
            putBinder(LwBootstrapRegistry.KEY_APP_BINDER, appBinder)
            putInt(LwBootstrapRegistry.KEY_APP_PID, Process.myPid())
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val TAG = "LwBootstrap"
    }
}
