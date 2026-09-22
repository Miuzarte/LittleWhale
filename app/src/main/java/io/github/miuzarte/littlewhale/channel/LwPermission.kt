package io.github.miuzarte.littlewhale.channel

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * The settings only a privileged uid may write
 *
 * The app cannot turn its own accessibility service on: that switch is a secure setting, and
 * writing one takes a permission this app does not hold and cannot ask for at runtime. Both uids
 * the channel can produce do hold it, so this is one more thing the privileged process is for -
 * and the reason the user never has to find the page in system settings that hides it
 *
 * The write goes through the `settings` command rather than through this process's own
 * ContentResolver, and that is not a shortcut. A process started by app_process has no
 * IApplicationThread, so asking for the settings provider from here is refused with "Unable to
 * find app for caller" - the same wall the app's own provider had to be reached around. The
 * command works because it reaches the provider the other way, through an external token
 *
 * Writing the setting is not quite the whole job: Android 13 lets an app that no store installed
 * hold its own accessibility service back, which is this app's case, and a component that is
 * already in the list but not bound does not come back from a write that changes nothing. Both of
 * those are handled here rather than left for the user to work out
 */
internal class LwPermission(private val context: Context?) {

    /**
     * Add or take this app's accessibility service out of the enabled list
     *
     * The list is a colon separated set of flattened component names shared with every other app on
     * the device, so this edits it rather than replacing it, and it compares components rather than
     * strings: the same component can be spelled two ways, and something else may have written
     * either spelling
     */
    fun setAccessibility(enabled: Boolean): Boolean {
        val context = context ?: run {
            Log.w(TAG, "no context, so the app's own component name is unknown")
            return false
        }
        val ours = ComponentName(context, LwAccessibility::class.java)
        val stored = get(ENABLED_SERVICES) ?: return false
        val listed = stored.split(':')
            .filter { it.isNotBlank() }
            .mapNotNull { ComponentName.unflattenFromString(it) }
        val others = listed.filter { it != ours }
        val alreadyListed = listed.any { it == ours }

        // The master switch is what the system reads first; enabling the component without it
        // leaves the service off with no sign of why, so it goes in before the list does
        if (enabled && !put(ACCESSIBILITY_ENABLED, "1")) return false
        if (enabled) allowRestrictedSettings(context.packageName)

        // A component that is in the list but not bound - which is what a force stop leaves behind -
        // is not brought back by writing the same list again, since the value did not change and the
        // observer has nothing to act on. Taking it out first makes the change real, and the pause
        // is for the system to see it; this is the "restart the service" gkd does before enabling
        if (enabled && alreadyListed) {
            if (!put(ENABLED_SERVICES, others.joinToString(":") { it.flattenToString() })) return false
            Thread.sleep(REBIND_GAP_MS)
        }

        val next = (if (enabled) others + ours else others)
            .joinToString(":") { it.flattenToString() }
        if (!put(ENABLED_SERVICES, next)) return false

        // Read it back, because an exit code says the command ran rather than that the device kept
        // the value, and a listener that rewrites the list is exactly the case that has to be seen
        val written = get(ENABLED_SERVICES) ?: return false
        val kept = written.split(':').any { ComponentName.unflattenFromString(it) == ours }
        if (kept != enabled) {
            Log.w(TAG, "the device kept $written, which is not what was written")
            return false
        }
        Log.i(TAG, "accessibility ${if (enabled) "enabled" else "disabled"}: $written")
        return true
    }

    /**
     * Let this app past Android 13's restricted settings
     *
     * An accessibility service of an app no store installed cannot be turned on, and this app is
     * installed by adb or by `pm install` - its installer package is null, which is exactly the
     * restricted case. What comes between the setting and the service binding is an app op, and
     * either uid this process can run as may set one. Best effort in both directions: the op does
     * not exist before Android 13, and nothing here is what makes the write above fail
     */
    private fun allowRestrictedSettings(packageName: String) {
        val result = run(CMD, "appops", "set", packageName, RESTRICTED_SETTINGS, "allow") ?: return
        val (code, output) = result
        if (code == 0) {
            Log.i(TAG, "allowed $RESTRICTED_SETTINGS for $packageName")
        } else {
            Log.d(TAG, "$RESTRICTED_SETTINGS was not allowed: $output")
        }
    }

    /** One secure setting, with the command's way of saying "unset" normalised away */
    private fun get(key: String): String? {
        val (code, output) = run(SETTINGS, "get", "secure", key) ?: return null
        if (code != 0) return null
        val value = output.trim()
        return if (value == "null") "" else value
    }

    /** Write one secure setting, answering whether the command succeeded */
    private fun put(key: String, value: String): Boolean {
        val result = run(SETTINGS, "put", "secure", key, value) ?: return false
        val (code, output) = result
        if (code != 0) {
            Log.w(TAG, "settings put secure $key was refused: $output")
            return false
        }
        return true
    }

    /** Run one command, answering with its exit code and what it printed */
    private fun run(vararg command: String): Pair<Int, String>? {
        val process = try {
            ProcessBuilder(command.toList()).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            Log.w(TAG, "could not start ${command.joinToString(" ")}", error)
            return null
        }
        val output = try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Throwable) {
            Log.w(TAG, "could not read what ${command.joinToString(" ")} printed", error)
            ""
        }
        if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "${command.joinToString(" ")} did not finish, killing it")
            process.destroy()
            return null
        }
        return process.exitValue() to output
    }

    private companion object {
        const val TAG = "LwPermission"

        /** The platform's own way of writing a secure setting without an app process */
        const val SETTINGS = "/system/bin/settings"

        /** And its way of setting an app op, which is a different command */
        const val CMD = "/system/bin/cmd"

        /** Android 13's gate on turning on the accessibility service of an app no store installed */
        const val RESTRICTED_SETTINGS = "ACCESS_RESTRICTED_SETTINGS"

        /** How long the system is given to notice a component left the list before it goes back in */
        const val REBIND_GAP_MS = 800L

        const val ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        const val ACCESSIBILITY_ENABLED = Settings.Secure.ACCESSIBILITY_ENABLED

        /** A settings command is quick, but it starts a process and can wait on the provider */
        const val TIMEOUT_SECONDS = 5L
    }
}
