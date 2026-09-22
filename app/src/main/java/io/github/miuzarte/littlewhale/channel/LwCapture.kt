package io.github.miuzarte.littlewhale.channel

import android.util.Log
import java.io.File

/**
 * A picture of a screen
 *
 * It is taken by the platform's own `screencap`, which is what makes it work whether or not
 * anybody is looking: the app's own options - copying the preview's surface - only exist while
 * that preview does, and a screen is meant to run with the app in the background
 *
 * `screencap -d` names a display the way the compositor does, by its physical id, and the only
 * thing that ties a screen of ours to that id is the name it was created with. The compositor's
 * list is therefore what the id is read out of, once per name. The phone's own screen needs none
 * of that: it is what `screencap` captures when it is told nothing, and the screenshot that
 * follows is a plain file the caller can read
 */
internal class LwCapture {

    /** The compositor's ids, by the name the screen was created with */
    private val known = mutableMapOf<String, String>()

    /** Capture one of our screens into a file, answering whether the device wrote one */
    fun capture(name: String, path: String): Boolean {
        val target = known[name] ?: resolve(name) ?: return false
        return written(run(SCREENCAP, "-d", target, "-p", fresh(path)), path, "display $target") {
            // The screen may have been rebuilt under a new id, so the next call looks again
            known.remove(name)
        }
    }

    /**
     * Capture the phone's own screen
     *
     * The default display is the one display whose compositor id is not worth looking up: `-d`
     * names a mode the phone happens to be in, while saying nothing is the phone as it is now
     */
    fun capturePrimary(path: String): Boolean =
        written(run(SCREENCAP, "-p", fresh(path)), path, "the phone's own screen")

    /** Forget every id, which is what a screen going away makes necessary */
    fun forget() {
        known.clear()
    }

    /**
     * Clear what an earlier capture left at this path, and answer the path
     *
     * `screencap` writes nothing at all when it fails, so without this the previous screen's
     * picture would be read back as this call's answer - and a stale screen is worse than no
     * screen, because nothing about the file says it is old
     */
    private fun fresh(path: String): String {
        File(path).delete()
        return path
    }

    /** Say whether a capture left a picture behind, and clean up when it did not */
    private fun written(outcome: String, path: String, what: String, forget: () -> Unit = {}): Boolean {
        val size = File(path).length()
        if (size <= 0L) {
            Log.w(TAG, "the device wrote no picture for $what: $outcome")
            forget()
            return false
        }
        Log.i(TAG, "captured $what into $path, $size bytes")
        return true
    }

    /** Read one screen's compositor id out of the list the compositor prints, by name */
    private fun resolve(name: String): String? {
        val listing = run(TOOLBOX, "SurfaceFlinger", "--display-id")
        // The id stays digits on purpose: it is an unsigned 64 bit number, so a signed Long would
        // turn virtual displays' ids - which all have the top bit set - into negatives
        val id = listing.lineSequence()
            .firstOrNull { it.contains("displayName=\"$name\"") }
            ?.let { PHYSICAL_ID.find(it)?.groupValues?.get(1) }
        if (id == null) {
            Log.w(TAG, "no display named $name in the compositor's list: ${listing.take(200)}")
            return null
        }
        known[name] = id
        return id
    }

    /** Run a toolbox command and read what it said, empty when it could not start at all */
    private fun run(vararg command: String): String {
        val process = try {
            ProcessBuilder(*command).redirectErrorStream(true).start()
        } catch (error: Exception) {
            Log.w(TAG, "could not run ${command.first()}", error)
            return ""
        }
        return try {
            process.inputStream.bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            Log.w(TAG, "could not read what ${command.first()} said", error)
            ""
        }
    }

    private companion object {
        const val TAG = "LwCapture"

        const val TOOLBOX = "/system/bin/dumpsys"
        const val SCREENCAP = "/system/bin/screencap"

        /** `Display 11529215049018179454 (Virtual display): displayName="LittleWhale 1"` */
        val PHYSICAL_ID = Regex("""Display\s+(\d+)""")
    }
}
