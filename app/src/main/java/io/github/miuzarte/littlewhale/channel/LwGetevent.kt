package io.github.miuzarte.littlewhale.channel

import android.util.Log
import java.util.concurrent.TimeUnit

/**
 * Raw `getevent -p` output
 *
 * The app cannot read `/dev/input` at all - the nodes are the `input` group's and SELinux refuses
 * the shell domain too - so this is only ever run from the privileged process. Both things that
 * need it live there: the probe that reports what the device has, and the touch watch, which needs
 * to know which node is the touchscreen before it reads one
 *
 * The output is returned as text rather than parsed here, because [InputDevices] is the one place
 * that decides what a device is, and it can be tested without a device
 */
internal fun readInputDevices(): String {
    val process = try {
        ProcessBuilder(GETEVENT, "-p").redirectErrorStream(true).start()
    } catch (error: Exception) {
        Log.w(TAG, "could not start $GETEVENT", error)
        return ""
    }
    val output = try {
        process.inputStream.bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        Log.w(TAG, "could not read what $GETEVENT said", error)
        ""
    }
    if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        Log.w(TAG, "$GETEVENT did not finish, killing it")
        process.destroy()
    }
    return output
}

private const val TAG = "LwGetevent"

/** toolbox reading every device is quick, but it can hang on a wedged driver */
private const val GETEVENT = "/system/bin/getevent"

private const val TIMEOUT_SECONDS = 5L
