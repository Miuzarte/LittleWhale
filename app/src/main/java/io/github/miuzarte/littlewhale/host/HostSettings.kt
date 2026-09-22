package io.github.miuzarte.littlewhale.host

import android.content.Context
import android.content.SharedPreferences

/**
 * The choices that outlive one host process
 *
 * The LAN switch is read when the host spawns, so a change reaches the running host only through
 * a restart - which is what the panel that owns the switch says
 */
object HostSettings {
    /** Preference file name, one file for the whole app */
    private const val STORE = "littlewhale"

    /** Key of the switch that serves the GUI to the local network */
    private const val LAN_ACCESS = "lan-access"

    /**
     * Whether the host binds every interface so another device can open the GUI
     * @param context context whose preferences are read.
     */
    fun lanAccess(context: Context): Boolean = preferences(context).getBoolean(LAN_ACCESS, false)

    /**
     * Persist the LAN choice for the next host start
     * @param context context whose preferences are written.
     * @param enabled the choice to remember.
     */
    fun setLanAccess(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(LAN_ACCESS, enabled).apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
