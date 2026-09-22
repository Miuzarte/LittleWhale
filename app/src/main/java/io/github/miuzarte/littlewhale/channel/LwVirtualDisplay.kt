package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.util.Log
import android.view.Surface

/**
 * The virtual screens the privileged process hosts
 *
 * They are built here rather than in the app for two reasons. The display flags that make one a
 * screen rather than a recording target are gated on CAPTURE_VIDEO_OUTPUT, which the app does not
 * hold; and what may later run on one - a launch, an injected tap, a capture - is decided against
 * the uid that created it, which is this process's
 *
 * The output surface belongs to the app and this class never touches its frames: the compositor
 * writes into that surface directly, so a preview needs no encoder, no socket and no native code
 * at all. Detaching is a first-class state, because the surface dies with the app's window while
 * the screen is meant to outlive it
 *
 * More than one screen can exist at a time, so every call names the one it means, and the name a
 * screen is created with has to be unique as well: it is the only thing that ties a screen to the
 * compositor's own id, which is what a screenshot is asked for by
 */
internal class LwVirtualDisplay(private val context: Context?) {

    /** The screens this process is hosting, by display id */
    private val screens = mutableMapOf<Int, Screen>()

    private var manager: DisplayManager? = null

    /** Counts up rather than following the map's size, so a name is never handed out twice */
    private var created = 0

    /** Build one more screen over the given surface, and answer with its display id */
    fun create(width: Int, height: Int, dpi: Int, surface: Surface?): Int {
        val displays = manager()
            ?: throw IllegalStateException("the privileged process has no context to ask for displays")
        created += 1
        val name = name(created)
        val created = try {
            displays.createVirtualDisplay(name, width, height, dpi, surface, flags())
        } catch (error: Throwable) {
            throw IllegalStateException("no ${width}x$height display at ${dpi}dpi: ${error.message}", error)
        }
        val id = created.display.displayId
        screens[id] = Screen(name, created)
        Log.i(TAG, "display $id is up as $name, ${width}x$height at ${dpi}dpi, surface=${surface != null}")
        return id
    }

    /**
     * Give one screen another size, and whatever is running on it another shape to lay out in
     *
     * The apps on it are not restarted: a size change reaches them as the configuration change a
     * rotation would be on a real display, so an app that reads its own shape re-lays out and one
     * that decided at startup keeps drawing what it decided. That is why the shape is worth getting
     * right when the screen is made, and why this is here anyway - a wrong guess is not a dead end
     *
     * The output surface is untouched and stays attached: the compositor draws the new size into
     * the same surface, which is why the app has to re-fix the buffer it handed over
     */
    fun resize(displayId: Int, width: Int, height: Int, dpi: Int) {
        val screen = screens[displayId]
            ?: throw IllegalStateException("there is no virtual screen $displayId")
        require(width > 0 && height > 0 && dpi > 0) {
            "a screen cannot be ${width}x$height at ${dpi}dpi"
        }
        try {
            screen.display.resize(width, height, dpi)
        } catch (error: Throwable) {
            throw IllegalStateException(
                "the device would not make display $displayId ${width}x$height at ${dpi}dpi:" +
                    " ${error.message}",
                error,
            )
        }
        Log.i(TAG, "display $displayId is now ${width}x$height at ${dpi}dpi")
    }

    /** Follow the preview: the same screen keeps running, it just draws somewhere else */
    fun setSurface(displayId: Int, surface: Surface?) {
        val screen = screens[displayId]
            ?: throw IllegalStateException("there is no virtual screen $displayId")
        screen.display.setSurface(surface)
        Log.i(TAG, "display $displayId surface=${surface != null}")
    }

    /** Give one screen back, and with it whatever the system started on it */
    fun release(displayId: Int) {
        val screen = screens.remove(displayId) ?: return
        Log.i(TAG, "releasing display $displayId (${screen.name})")
        screen.display.release()
    }

    /** Give every screen back, which is what ending this process does first */
    fun releaseAll() {
        screens.keys.toList().forEach { release(it) }
    }

    /** What the compositor calls the screen with this id, for a capture that has to find it again */
    fun nameOf(displayId: Int): String? = screens[displayId]?.name

    /**
     * A hardware display, or the system refuses it
     *
     * Every flag below the public ones is a plain bit from the platform's own list, and the set
     * matches what a screen has to be for activities to land on it: trusted, focused on its own,
     * unlocked on its own, and in a display group of its own so it never mirrors the phone
     */
    private fun flags(): Int {
        var flags = PUBLIC or OWN_CONTENT_ONLY or SUPPORTS_TOUCH or DESTROY_CONTENT_ON_REMOVAL
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or TRUSTED or OWN_DISPLAY_GROUP or ALWAYS_UNLOCKED or TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or OWN_FOCUS or DEVICE_DISPLAY_GROUP or STEAL_TOP_FOCUS_DISABLED
        }
        return flags
    }

    /**
     * A display manager this process can call
     *
     * The constructor is hidden, and the manager's own instance would answer for the system's
     * package rather than this app's; both matter to the service on the other end, which notes who
     * asked for a display
     */
    private fun manager(): DisplayManager? {
        manager?.let { return it }
        val base = context ?: return null
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(base).also { manager = it }
    }

    /** One hosted screen: what the compositor calls it, and the handle the platform gave back */
    private class Screen(val name: String, val display: VirtualDisplay)

    private companion object {
        const val TAG = "LwDisplay"

        /** The name `dumpsys display` shows for a screen, numbered because they share a prefix */
        fun name(index: Int): String = "LittleWhale $index"

        /** The virtual display flags, none of which the public SDK names */
        const val PUBLIC = 1 shl 0
        const val OWN_CONTENT_ONLY = 1 shl 3

        /** Makes the screen accept touch, which is also what an injected event is checked against */
        const val SUPPORTS_TOUCH = 1 shl 6

        /** Takes the screen's content down with the screen instead of leaking it */
        const val DESTROY_CONTENT_ON_REMOVAL = 1 shl 8

        /** Marks the screen as one the system may put a focused, trusted window on */
        const val TRUSTED = 1 shl 10
        const val OWN_DISPLAY_GROUP = 1 shl 11

        /** A separate screen is not the locked phone, so it has no lock of its own */
        const val ALWAYS_UNLOCKED = 1 shl 12
        const val TOUCH_FEEDBACK_DISABLED = 1 shl 13

        /** Lets the screen hold focus without stealing it from the phone */
        const val OWN_FOCUS = 1 shl 14
        const val DEVICE_DISPLAY_GROUP = 1 shl 15
        const val STEAL_TOP_FOCUS_DISABLED = 1 shl 16
    }
}
