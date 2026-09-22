package io.github.miuzarte.littlewhale.channel

import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.FileInputStream

/**
 * What the phone's own touchscreen has seen lately
 *
 * @property watching whether anything is reading the touchscreen at all, which is what a caller
 *   that means to drive the phone's own screen has to check first
 * @property down whether a finger is on the glass right now
 * @property msSinceLastTouch how long ago the last touch arrived, -1 when none has been seen
 * @property events how many touch events the watch has seen since it started, which is how an idle
 *   screen is told apart from a watch that never began
 * @property path the node being read, empty when there is none
 * @property error why there is no watch, null while there is one
 */
data class TouchState(
    val watching: Boolean,
    val down: Boolean,
    val msSinceLastTouch: Int,
    val events: Int,
    val path: String,
    val error: String?,
) {
    /**
     * Whether the phone counts as being in somebody's hands right now
     *
     * A finger down is the whole answer, and a finger that has just left still counts for a
     * moment: the model taking the screen back the instant the glass looks free is how a person
     * ends up fighting it for a row of their own settings
     *
     * A watch that is not running answers false rather than true, because this says what the
     * glass has seen and not whether anything may be done with it - a caller that has to know
     * reads [watching] for itself
     */
    fun driving(windowMs: Int = USER_TOUCH_MS): Boolean =
        watching && (down || msSinceLastTouch in 0 until windowMs)

    companion object {
        /**
         * How long after a real finger leaves the glass the phone is still the user's
         *
         * Long enough that a tap's own lift cannot be followed by the model acting on what the
         * person was about to press, short enough that typing a message into the app's own GUI is
         * not read as somebody fighting the model for the screen
         */
        const val USER_TOUCH_MS = 1_000
    }
}

/**
 * The phone's own touchscreen, watched for real fingers
 *
 * This is the brake's only sensor, and it has to live on this side of the channel: `/dev/input` is
 * the `input` group's, which the app is not in, while this process was started as root or shell
 * for exactly this kind of reading
 *
 * What makes the signal mean "a person" rather than "somebody touched the screen" is where
 * injection happens: `InputManager.injectInputEvent` enters the pipeline after the kernel's input
 * reader, so the model's own taps never appear on this node. That was measured before anything was
 * built on it, and every event read here is somebody's finger
 *
 * The node is read here rather than by running `getevent`: a child process writing lines into a
 * pipe buffers them, and a brake that hears about a finger a second late is not a brake. A read
 * blocked on an evdev node costs nothing until the glass is touched
 *
 * Nothing here decides when the phone counts as the user's - it reports what happened and each
 * caller picks its own window, because a drag being interrupted and a call being refused are two
 * different questions
 */
internal class LwTouchWatch {

    private val monitor = Any()
    private var reader: Thread? = null
    private var stream: FileInputStream? = null

    @Volatile
    private var node: String? = null

    @Volatile
    private var failure: String? = null

    @Volatile
    private var failedAt = 0L

    /** When the last real touch arrived, zero while none has */
    @Volatile
    private var lastTouch = 0L

    /** Whether a finger is down, from the panel's own touch key or tracking id */
    @Volatile
    private var touching = false

    @Volatile
    private var seen = 0

    /** Start watching if nothing is, then say what has been seen so far */
    fun state(): TouchState {
        ensure()
        val age = lastTouch
        return TouchState(
            watching = reader?.isAlive == true,
            down = touching,
            msSinceLastTouch = if (age == 0L) -1 else (SystemClock.elapsedRealtime() - age).toInt(),
            events = seen,
            path = node.orEmpty(),
            error = failure,
        )
    }

    /** Stop reading, which ending the process does on its way out */
    fun stop() {
        synchronized(monitor) {
            runCatching { stream?.close() }
            stream = null
            reader = null
        }
    }

    /** Bring the watch up unless it already is or just failed */
    private fun ensure() {
        synchronized(monitor) {
            if (reader?.isAlive == true) return
            // A device that will not give this process the node will not start doing so a moment
            // later, but a screen that was just turned on might, so the attempt is repeated slowly
            val recent = failure != null && SystemClock.elapsedRealtime() - failedAt < RETRY_MS
            if (recent) return
            begin()
        }
    }

    /** Find the touchscreen and read it, recording why when either step is refused */
    private fun begin() {
        val device = InputDevices.touchscreen(readInputDevices())
        if (device == null) {
            fail("no touchscreen was recognised among the input devices")
            return
        }
        val opened = try {
            FileInputStream(device.path)
        } catch (error: Throwable) {
            fail("${device.path} could not be read: ${error.message}")
            return
        }
        stream = opened
        node = device.path
        failure = null
        reader = Thread({ read(opened) }, "lw-touch").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "watching ${device.path} \"${device.name}\"")
    }

    /**
     * Read the node until it goes away
     *
     * One `input_event` at a time, in the layout the kernel writes for this process's own word
     * size: a timeval, then the type, the code and the value. A read can come back short, so the
     * record is filled before it is looked at rather than parsed from whatever turned up
     */
    private fun read(source: FileInputStream) {
        val record = ByteArray(if (Process.is64Bit()) RECORD_64 else RECORD_32)
        val header = record.size - FIELDS
        var filled = 0
        try {
            while (true) {
                val read = source.read(record, filled, record.size - filled)
                if (read < 0) break
                filled += read
                if (filled < record.size) continue
                filled = 0
                consider(record, header)
            }
        } catch (error: Throwable) {
            fail("reading $node stopped: ${error.message}")
        }
    }

    /** One event: whether a finger arrived, left, or moved */
    private fun consider(record: ByteArray, header: Int) {
        val type = field(record, header, 2)
        val code = field(record, header + 2, 2)
        when {
            type == EV_KEY && code == BTN_TOUCH -> {
                touching = field(record, header + 4, 4) != 0
                touched()
            }

            type == EV_ABS && code == ABS_MT_TRACKING_ID -> {
                // -1 is the panel saying this slot is now empty, any other value is a finger
                touching = field(record, header + 4, 4) != -1
                touched()
            }

            // Coordinates without a tracking id are protocol A, where the touch key is the only
            // statement about a finger being there
            type == EV_ABS && (code == ABS_MT_POSITION_X || code == ABS_MT_POSITION_Y ||
                code == ABS_X || code == ABS_Y) -> touched()
        }
    }

    /** A real finger did something */
    private fun touched() {
        lastTouch = SystemClock.elapsedRealtime()
        seen += 1
    }

    /** Remember a watch that could not start, and say so once */
    private fun fail(reason: String) {
        if (failure == reason) return
        failure = reason
        failedAt = SystemClock.elapsedRealtime()
        Log.w(TAG, "no touch watch: $reason")
    }

    /** One little endian field of an `input_event`, which is how the kernel writes them */
    private fun field(record: ByteArray, at: Int, width: Int): Int {
        var value = 0
        for (byte in 0 until width) {
            value = value or ((record[at + byte].toInt() and 0xFF) shl (byte * 8))
        }
        return value
    }

    private companion object {
        const val TAG = "LwTouch"

        /** `struct input_event` on a 64 bit process: two longs of timeval, then the event */
        const val RECORD_64 = 24

        /** The same on a 32 bit one, where a timeval is two ints */
        const val RECORD_32 = 16

        /** Type, code and value, whatever the timeval in front of them is worth */
        const val FIELDS = 8

        /** How long a watch that could not start waits before it tries again */
        const val RETRY_MS = 5_000L

        const val EV_KEY = 0x01
        const val EV_ABS = 0x03
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val ABS_MT_POSITION_X = 0x35
        const val ABS_MT_POSITION_Y = 0x36
        const val ABS_MT_TRACKING_ID = 0x39
        const val BTN_TOUCH = 0x14a
    }
}
