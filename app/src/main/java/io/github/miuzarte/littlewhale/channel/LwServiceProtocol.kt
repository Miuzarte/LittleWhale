package io.github.miuzarte.littlewhale.channel

import android.os.IBinder
import android.os.Parcel
import android.view.Surface

/**
 * What one key press produced
 *
 * @property accepted whether the device took both halves of the press
 * @property lastedMs how long the hold lasted before a real finger ended it, or [GESTURE_COMPLETED]
 *   when nothing interrupted it
 */
data class KeyOutcome(val accepted: Boolean, val lastedMs: Int)

/**
 * The one interface crossing between the app and the privileged process
 *
 * It is written out here rather than declared in AIDL, and that is not a style choice: enabling
 * the AIDL build feature makes this app compile Java, and AGP 9.4 answers any Java source by
 * routing the build through a legacy resource task whose dependency transform cannot run in this
 * project. Five hand-written transactions keep the app Kotlin-only, which keeps that task out of
 * the graph, and they never have to change because the interface does not either
 *
 * Every transaction is prefixed with the same interface token and, except for the one that ends
 * the process, answers with a parcel exception slot followed by its value
 */
object LwServiceProtocol {

    /** Binder interface name, which every transaction starts with */
    const val DESCRIPTOR = "io.github.miuzarte.littlewhale.channel.LwService"

    /** Release what the service holds and end the process */
    const val DESTROY = IBinder.FIRST_CALL_TRANSACTION

    /** Version of the interface, so the app can tell what it is talking to */
    const val VERSION = IBinder.FIRST_CALL_TRANSACTION + 1

    /** The uid the privileged process really runs as */
    const val UID = IBinder.FIRST_CALL_TRANSACTION + 2

    /** The privileged process's pid */
    const val PID = IBinder.FIRST_CALL_TRANSACTION + 3

    /** Raw `getevent -p` output as the privileged uid sees it */
    const val INPUT_DEVICES = IBinder.FIRST_CALL_TRANSACTION + 4

    /** Build the virtual screen, replacing any screen already there, and answer with its id */
    const val DISPLAY_CREATE = IBinder.FIRST_CALL_TRANSACTION + 5

    /** Point one of the virtual screens at another output surface, or at none */
    const val DISPLAY_SURFACE = IBinder.FIRST_CALL_TRANSACTION + 6

    /** Tear one virtual screen down */
    const val DISPLAY_RELEASE = IBinder.FIRST_CALL_TRANSACTION + 7

    /** A whole press and lift on the screen, in the screen's own coordinates */
    const val INPUT_TAP = IBinder.FIRST_CALL_TRANSACTION + 8

    /** One moment of a finger on the screen, so a gesture can follow the finger that made it */
    const val INPUT_TOUCH = IBinder.FIRST_CALL_TRANSACTION + 9

    /** A picture of the screen, written to a file the caller names */
    const val SCREENSHOT = IBinder.FIRST_CALL_TRANSACTION + 10

    /** A drag from one point to another, paced out over a duration */
    const val INPUT_SWIPE = IBinder.FIRST_CALL_TRANSACTION + 11

    /** Turn this app's accessibility service on or off, which is a secure setting to write */
    const val A11Y_SET = IBinder.FIRST_CALL_TRANSACTION + 12

    /** Start an activity on one of the virtual screens, which only a privileged uid may ask for */
    const val LAUNCH = IBinder.FIRST_CALL_TRANSACTION + 13

    /** What the phone's own touchscreen has seen lately, which is the brake's only sensor */
    const val TOUCH_STATE = IBinder.FIRST_CALL_TRANSACTION + 14

    /** A whole press and release of one key, which is how a button the platform handles is pressed */
    const val INPUT_KEY = IBinder.FIRST_CALL_TRANSACTION + 15

    /** A whole string typed as key presses, for a screen that reports no field to set it on */
    const val INPUT_TEXT = IBinder.FIRST_CALL_TRANSACTION + 16

    /**
     * Give one virtual screen another size
     *
     * A screen is made at one size and can be given another afterwards, which is the only way to
     * change the shape of what is on it: an app decides its layout from the display's own size, so
     * a game that only knows how to be landscape needs a landscape screen rather than a rotated
     * picture of a portrait one
     */
    const val DISPLAY_RESIZE = IBinder.FIRST_CALL_TRANSACTION + 17

    /**
     * The Android users this device has
     *
     * An app installed for more than one user is more than one copy of itself: the same package and
     * the same activity, with a user and a set of data of their own, which is what a cloned app
     * (双开, a parallel space) is. Naming one is how a launch picks between them, and the list is a
     * privileged question - `cmd user list` refuses this app's uid the way `am` does
     */
    const val USERS = IBinder.FIRST_CALL_TRANSACTION + 18

    /**
     * The packages installed for one user
     *
     * The other half of the same question [USERS] answers: which of the things that can be started
     * exist for a given user, a cloned app being the case that makes the user part matter. It is
     * asked of the privileged process for the same reason `pm list users` is - the command refuses
     * an app uid, and an app cannot see another user's packages at all
     */
    const val PACKAGES = IBinder.FIRST_CALL_TRANSACTION + 19

    /** The three moments [INPUT_TOUCH] can report */
    const val TOUCH_DOWN = 0
    const val TOUCH_MOVE = 1
    const val TOUCH_UP = 2

    /**
     * The phone's own screen, which every acting call can name like any other
     *
     * It is the one screen that exists without being created: id zero is the display the phone
     * itself shows, it belongs to the person holding it rather than to this app, and it can be
     * looked at and driven but never released
     */
    const val MAIN_DISPLAY = 0

    /**
     * What [LwServiceProxy.tap] and [LwServiceProxy.swipe] answer when the whole gesture went out
     *
     * Anything else is how many milliseconds it lasted before a real finger took the screen back,
     * so the caller can say what part of the gesture happened instead of only that it did not
     */
    const val GESTURE_COMPLETED = -1

    /**
     * The platform's own long press threshold (`ViewConfiguration.getLongPressTimeout`)
     *
     * It is the shortest hold that means anything to an app: a press held at least this long is the
     * long press a widget or the policy is waiting for, and one held for less is the press it is
     * not. Longer holds are about how long a finger stays down rather than about that flag
     */
    const val LONG_PRESS_MS = 500L

    /**
     * No hold lasts longer than this, whatever a caller asks for
     *
     * The gesture queue is blocked for the whole of one, so this is the difference between a tool
     * call and a tool call that looks like it hung
     */
    const val MAX_HOLD_MS = 10_000L

    /** The app's side of the protocol over any binder, local or remote */
    fun proxy(binder: IBinder): LwServiceProxy = LwServiceProxy(binder)
}

/**
 * Calls the privileged service
 *
 * The same code path covers both directions it is used in: across the process boundary from the
 * app, and in-process from the starter, because a local binder's transact runs its own onTransact
 */
class LwServiceProxy(private val remote: IBinder) {

    /** Whether the process behind this binder is still there */
    val isAlive: Boolean get() = remote.pingBinder()

    fun destroy() {
        transact(LwServiceProtocol.DESTROY) { }
    }

    fun version(): String = transact(LwServiceProtocol.VERSION) { readString().orEmpty() }

    fun uid(): Int = transact(LwServiceProtocol.UID) { readInt() }

    fun pid(): Int = transact(LwServiceProtocol.PID) { readInt() }

    fun inputDevices(): String = transact(LwServiceProtocol.INPUT_DEVICES) { readString().orEmpty() }

    /**
     * Ask for a virtual screen of this size, drawn into this surface if there is one
     *
     * Every call makes a new screen and answers with its id: more than one can exist at a time, and
     * a screen is given back by name rather than replaced. The surface is the app's own - a
     * `SurfaceView`'s - and the privileged side only ever hands it to the compositor, which is what
     * keeps the preview free of encoders and native code
     */
    fun createDisplay(width: Int, height: Int, dpi: Int, surface: Surface?): Int = transact(
        code = LwServiceProtocol.DISPLAY_CREATE,
        write = {
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
            writeSurface(surface)
        },
        read = { readInt() },
    )

    /** Move one screen to another output surface, which the app does whenever the preview moves */
    fun setDisplaySurface(displayId: Int, surface: Surface?): Unit = transact(
        code = LwServiceProtocol.DISPLAY_SURFACE,
        write = {
            writeInt(displayId)
            writeSurface(surface)
        },
        read = { },
    )

    /** Give one screen back, which also takes its content down with it */
    fun releaseDisplay(displayId: Int): Unit = transact(
        code = LwServiceProtocol.DISPLAY_RELEASE,
        write = { writeInt(displayId) },
        read = { },
    )

    /**
     * Give one screen another size, with whatever is on it left running
     *
     * The shape a display has is what an app lays itself out for, so this is the only way to put a
     * landscape-only app on a screen that was made portrait: nothing about the picture can be
     * rotated into one. A screen can be resized as often as the caller likes, and the surface it is
     * drawing into stays attached throughout - the app has to re-fix the buffer it handed over for
     * the new size, because the compositor draws at the screen's size and does not scale it
     */
    fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int): Unit = transact(
        code = LwServiceProtocol.DISPLAY_RESIZE,
        write = {
            writeInt(displayId)
            writeInt(width)
            writeInt(height)
            writeInt(dpi)
        },
        read = { },
    )

    /**
     * The users this device has, in the platform's own words
     *
     * Asked of the privileged process for the same reason every other question here is: the command
     * that answers it is one this app's uid is refused
     */
    fun users(): String = transact(LwServiceProtocol.USERS) { readString().orEmpty() }

    /**
     * The packages installed for one user, in the platform's own words
     *
     * @param userId the Android user, zero being the person holding the phone.
     */
    fun packages(userId: Int): String = transact(
        code = LwServiceProtocol.PACKAGES,
        write = { writeInt(userId) },
        read = { readString().orEmpty() },
    )

    /**
     * Press and lift at one point
     *
     * @param holdMs how long the finger stays down. A hold at or past [LONG_PRESS_MS] is what a long
     *   press is, and a longer one is a press that is kept down - a voice message being recorded, a
     *   drag that has to be started by holding - so the duration is the caller's to choose
     * @param brake whether a real finger appearing on the glass ends the press where it is, which
     *   only the phone's own screen asks for: a screen of ours has nobody to interrupt it
     * @returns [GESTURE_COMPLETED], or how many milliseconds the press lasted before somebody's
     *   finger took the screen back
     */
    fun tap(displayId: Int, x: Float, y: Float, holdMs: Long, brake: Boolean): Int = transact(
        code = LwServiceProtocol.INPUT_TAP,
        write = {
            writeInt(displayId)
            writeFloat(x)
            writeFloat(y)
            writeLong(holdMs)
            writeInt(if (brake) 1 else 0)
        },
        read = { readInt() },
    )

    /** One moment of a finger, which is what makes a drag feel like a drag */
    fun touch(displayId: Int, action: Int, x: Float, y: Float): Unit = transact(
        code = LwServiceProtocol.INPUT_TOUCH,
        write = {
            writeInt(displayId)
            writeInt(action)
            writeFloat(x)
            writeFloat(y)
        },
        read = { },
    )

    /**
     * Drag from one point to another over the given duration
     *
     * One transaction rather than a caller's worth of `touch` calls: the pacing decides whether the
     * platform reads a gesture or a jump, and a round trip per step would make that pacing depend
     * on how busy the binder is
     *
     * @param brake whether a real finger appearing on the glass ends the drag where it is, which
     *   only the phone's own screen asks for: a screen of ours has nobody to interrupt it, and the
     *   user's brakes on those are the menu's own
     * @returns [LwServiceProtocol.GESTURE_COMPLETED], or how many milliseconds it lasted before a
     *   finger arrived
     */
    fun swipe(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        brake: Boolean,
    ): Int = transact(
        code = LwServiceProtocol.INPUT_SWIPE,
        write = {
            writeInt(displayId)
            writeFloat(fromX)
            writeFloat(fromY)
            writeFloat(toX)
            writeFloat(toY)
            writeLong(durationMs)
            writeInt(if (brake) 1 else 0)
        },
        read = { readInt() },
    )

    /**
     * Press and release one key
     *
     * A key is not a touch and cannot be one: BACK, HOME and the volume keys are handled by the
     * platform rather than drawn by an app, so no tree has them and no coordinate can reach them.
     * The `input` command would, and it is refused for this app's uid exactly like `am` is, which
     * leaves this transaction as the only way to press one
     *
     * @param holdMs how long the key is held down before it is released. What a widget or the policy
     *   reads as a long press is the flag on the release rather than the time between the two
     *   events, so a hold at or past [LONG_PRESS_MS] is lifted with `FLAG_LONG_PRESS`, which is what
     *   the platform's own `input keyevent --longpress` does
     * @param brake whether a real finger on the glass ends the hold where it is, which only the
     *   phone's own screen asks for
     * @returns whether the device took both halves of the press, and how long the hold lasted, or
     *   [GESTURE_COMPLETED] when it was not interrupted
     */
    fun key(displayId: Int, keyCode: Int, holdMs: Long, brake: Boolean): KeyOutcome = transact(
        code = LwServiceProtocol.INPUT_KEY,
        write = {
            writeInt(displayId)
            writeInt(keyCode)
            writeLong(holdMs)
            writeInt(if (brake) 1 else 0)
        },
        read = { KeyOutcome(readInt() == 1, readInt()) },
    )

    /**
     * Type a string as key presses
     *
     * This is the narrow half of typing and exists for one case: a screen with no field in its tree
     * to set the text on, so the only thing left to do is press the keys a virtual keyboard would
     * have pressed. What that keyboard can produce is all it can produce
     *
     * @returns how many characters were delivered, or -1 when this device's keyboard cannot
     *   produce the text at all (Chinese, an emoji)
     */
    fun text(displayId: Int, text: String): Int = transact(
        code = LwServiceProtocol.INPUT_TEXT,
        write = {
            writeInt(displayId)
            writeString(text)
        },
        read = { readInt() },
    )

    /**
     * What the phone's own touchscreen has seen lately
     *
     * Asked over the channel rather than read here because the nodes belong to the `input` group,
     * and asked once per acting call rather than subscribed to, because a caller that is about to
     * touch the phone wants to know now
     */
    fun touchState(): TouchState = transact(LwServiceProtocol.TOUCH_STATE) {
        val watching = readInt() == 1
        val down = readInt() == 1
        val age = readInt()
        val events = readInt()
        val path = readString().orEmpty()
        val error = readString()
        TouchState(watching, down, age, events, path, error)
    }

    /**
     * Ask for a picture of the screen at a path this side can read
     *
     * The file is written by the privileged process, so the caller has to be able to read what
     * that uid leaves behind - which holds for the app's own directories and nothing else
     */
    fun screenshot(displayId: Int, path: String): Boolean = transact(
        code = LwServiceProtocol.SCREENSHOT,
        write = {
            writeInt(displayId)
            writeString(path)
        },
        read = { readInt() == 1 },
    )

    /**
     * Turn this app's accessibility service on or off
     *
     * One more thing the app cannot do for itself, and the only reason the user does not have to
     * find the service in system settings: it is a secure setting, and this process holds the
     * permission to write one
     */
    fun setAccessibility(enabled: Boolean): Boolean = transact(
        code = LwServiceProtocol.A11Y_SET,
        write = { writeInt(if (enabled) 1 else 0) },
        read = { readInt() == 1 },
    )

    /**
     * Start an activity on one screen, answering with the command's exit code and its output
     *
     * This is the `am` command, run where it is allowed to run. It calls itself the shell's package
     * name, so an app uid is refused it outright - measured, not assumed - and that is the whole
     * reason starting an app for a screen has to come through here
     *
     * @param userId the Android user to start it for. Zero is the person holding the phone; any
     *   other is a user of their own, which is the only thing that tells a cloned app from the one
     *   it was cloned from: same package, same activity, different data
     */
    fun launch(
        displayId: Int,
        userId: Int,
        packageName: String,
        component: String,
        timeoutMs: Long,
    ): Pair<Int, String> = transact(
        code = LwServiceProtocol.LAUNCH,
        write = {
            writeInt(displayId)
            writeInt(userId)
            writeString(packageName)
            writeString(component)
            writeLong(timeoutMs)
        },
        read = {
            val output = readString().orEmpty()
            val code = readInt()
            code to output
        },
    )

    /**
     * One transaction, with both parcels handled in exactly one place
     *
     * A dead process makes transact throw, and a service that failed writes its exception into the
     * reply, which readException rethrows on this side - so both are the caller's to catch
     */
    private fun <T> transact(code: Int, write: Parcel.() -> Unit = { }, read: Parcel.() -> T): T {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(LwServiceProtocol.DESCRIPTOR)
            data.write()
            remote.transact(code, data, reply, 0)
            reply.readException()
            return reply.read()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

/**
 * Write a surface, or the fact that there is none
 *
 * It is spelled out rather than left to `writeTypedObject` because the value is legitimately
 * absent: a screen outlives the preview that shows it, and detaching has to be sayable
 */
private fun Parcel.writeSurface(surface: Surface?) {
    if (surface == null) {
        writeInt(0)
        return
    }
    writeInt(1)
    surface.writeToParcel(this, 0)
}

/** The other half of [writeSurface] */
internal fun Parcel.readSurface(): Surface? = if (readInt() == 0) null else Surface.CREATOR.createFromParcel(this)
