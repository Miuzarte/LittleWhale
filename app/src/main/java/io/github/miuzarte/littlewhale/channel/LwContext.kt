package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log

/**
 * The context the privileged service runs against
 *
 * A process started through app_process has no LoadedApk and therefore no package identity, so
 * this reports the app's own package name and application info over whatever context was
 * available. When the system context could be turned into a package context the base already
 * answers correctly and these overrides are only a restatement
 */
internal class LwContext(base: Context, private val packageName: String) : ContextWrapper(base) {

    private val applicationInfo: ApplicationInfo? = try {
        base.packageManager
            .getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
    } catch (error: PackageManager.NameNotFoundException) {
        Log.w(TAG, "no application info for $packageName", error)
        null
    }

    override fun getPackageName(): String = packageName

    override fun getOpPackageName(): String = packageName

    override fun getApplicationInfo(): ApplicationInfo = applicationInfo ?: super.getApplicationInfo()

    /** This is the outermost context, so it is its own application context */
    override fun getApplicationContext(): Context = this

    private companion object {
        const val TAG = "LwContext"
    }
}
