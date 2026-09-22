package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import java.lang.reflect.Method

/**
 * Touch, aimed at a screen
 *
 * Both halves of this are privileged: a screen of ours lives in this process and an injected event
 * has to name it, and INJECT_EVENTS is a permission the app does not hold. The coordinates are the
 * screen's own, so everything above this - a finger on the preview, a tool call - only has to
 * scale its own box into the screen and never has to know how the screen is built
 *
 * A gesture is a sequence rather than one call, because the platform matches the moves to the
 * press that started them by their down time: the press is remembered here and reused until the
 * finger is lifted
 *
 * A drag can also be interrupted, by the one thing that outranks this process: the person holding
 * the phone. That is what [watch] is for, and it only ever applies to the phone's own screen,
 * because a screen of ours has nobody standing over it
 */
internal class LwInput(private val context: Context?, private val watch: LwTouchWatch) {

    private var manager: InputManager? = null
    private var setDisplayIdMethod: Method? = null
    private var injectMethod: Method? = null

    /** What this device's virtual keyboard can produce, which is what typing by key is limited to */
    private var keyboard: KeyCharacterMap? = null

    /** The press a move or a lift belongs to, absent while no finger is down */
    private var downTime = 0L
    private var down = false

    /**
     * Press and lift at one point, holding it down for as long as the caller asked
     *
     * The hold is the whole of what a long press is: the platform's own detector fires once the
     * finger has been down for its threshold, and everything past that is "kept down" - which is
     * what a voice message being recorded or a finger waiting for a drag to start needs. Nothing is
     * special cased for a long hold, because the platform already reads the time
     *
     * With [brake] on - which is what the phone's own screen asks for - the wait is sliced so that
     * a real finger arriving during it ends the press there instead of leaving a held finger on
     * somebody else's screen for the rest of the hold
     *
     * @returns [LwServiceProtocol.GESTURE_COMPLETED], or how many milliseconds the press lasted
     *   before a finger arrived
     */
    fun tap(displayId: Int, x: Float, y: Float, holdMs: Long, brake: Boolean): Int {
        val started = SystemClock.uptimeMillis()
        touch(displayId, LwServiceProtocol.TOUCH_DOWN, x, y)
        var left = heldFor(holdMs).coerceAtLeast(TAP_HOLD_MS)
        while (left > 0) {
            val slice = left.coerceAtMost(BRAKE_SLICE_MS)
            if (brake && interrupted(slice)) {
                touch(displayId, LwServiceProtocol.TOUCH_UP, x, y)
                Log.i(TAG, "the press on display $displayId ended at $x,$y, a real finger arrived")
                return (SystemClock.uptimeMillis() - started).toInt()
            }
            left -= slice
        }
        touch(displayId, LwServiceProtocol.TOUCH_UP, x, y)
        return LwServiceProtocol.GESTURE_COMPLETED
    }

    /**
     * Drag a finger from one point to another over the requested duration
     *
     * The moves are paced out rather than sent in one burst: a gesture the platform receives as
     * five points in the same millisecond is a jump, and everything that reads velocity - flings,
     * scrolling, a drag handle - reads it wrong. The last move lands on the target and the lift
     * follows at the same point, so the finger stops before it is released
     *
     * With [brake] on - which is what the phone's own screen asks for - every slice of the wait
     * between two moves is a chance for a real finger to arrive, and one that does ends the drag
     * where it stands: the finger is lifted at the point it had reached, because a hand that stops
     * a gesture must not leave a press hanging on the screen
     *
     * @returns [LwServiceProtocol.GESTURE_COMPLETED], or how many milliseconds the drag lasted before
     *   somebody's finger took the screen back
     */
    fun swipe(
        displayId: Int,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        durationMs: Long,
        brake: Boolean,
    ): Int {
        val started = SystemClock.uptimeMillis()
        touch(displayId, LwServiceProtocol.TOUCH_DOWN, fromX, fromY)
        val stepMs = (durationMs / SWIPE_STEPS).coerceAtLeast(MIN_STEP_MS)
        var x = fromX
        var y = fromY
        for (step in 1..SWIPE_STEPS) {
            // **每一步都要等**, 两条路都一样: 同一毫秒里发出的一串 MOVE 在分发器看来会被合并成一次
            // 跳, 而按帧读输入的引擎 (Unity 就是) 在那一帧里只看到按下加抬起 —— 手势就成了点击, 实测
            // 干员列表里 0.407% 的像素动了一下 (按下反馈) 而平台自己的 input swipe 动了 82%。原来只有
            // 主屏 (brake) 那条路会等, 虚拟屏是硬突发的, 所以这个坑只在游戏里露出来
            if (brake) {
                if (interrupted(stepMs)) {
                    touch(displayId, LwServiceProtocol.TOUCH_UP, x, y)
                    Log.i(TAG, "the drag on display $displayId ended at $x,$y, a real finger arrived")
                    return (SystemClock.uptimeMillis() - started).toInt()
                }
            } else {
                SystemClock.sleep(stepMs)
            }
            val progress = step.toFloat() / SWIPE_STEPS
            x = fromX + (toX - fromX) * progress
            y = fromY + (toY - fromY) * progress
            touch(displayId, LwServiceProtocol.TOUCH_MOVE, x, y)
        }
        touch(displayId, LwServiceProtocol.TOUCH_UP, toX, toY)
        return LwServiceProtocol.GESTURE_COMPLETED
    }

    /**
     * Wait out one step, answering whether a real finger turned up during it
     *
     * Sliced rather than slept in one go: two seconds of drag is twelve steps of a sixth of a
     * second each, and waiting a whole step to notice a hand on the screen would let the model
     * keep dragging for most of it. A gesture that cannot be watched is ended as well - a brake
     * nobody can read is not a brake
     */
    private fun interrupted(ms: Long): Boolean {
        var left = ms
        while (left > 0) {
            val slice = left.coerceAtMost(BRAKE_SLICE_MS)
            SystemClock.sleep(slice)
            left -= slice
            val state = watch.state()
            if (!state.watching) return true
            if (state.down || state.msSinceLastTouch in 0 until BRAKE_MS) return true
        }
        return false
    }

    /**
     * Press and release one key, holding it down for as long as the caller asked
     *
     * A key is not a finger: the platform matches the release to its press by the down time the two
     * events carry, and what a widget or the policy reads as a long press is the flag on the
     * release rather than the time between them - so a hold past the platform's own threshold is
     * lifted with that flag, which is what the platform's own `input keyevent --longpress` does.
     * The hold itself is real as well: it is how long the key stays down
     *
     * With [brake] on the hold is watched, and a real finger on the glass lifts the key where it
     * stands: the person holding the phone outranks whatever is being held down
     *
     * @returns whether the device took both halves of the press, and how long the hold lasted or
     *   [LwServiceProtocol.GESTURE_COMPLETED]
     */
    fun key(displayId: Int, keyCode: Int, holdMs: Long, brake: Boolean): KeyOutcome {
        val downTime = SystemClock.uptimeMillis()
        if (!sendKey(displayId, KeyEvent.ACTION_DOWN, keyCode, downTime, downTime, 0)) {
            return KeyOutcome(accepted = false, lastedMs = LwServiceProtocol.GESTURE_COMPLETED)
        }
        val started = SystemClock.uptimeMillis()
        var left = heldFor(holdMs).coerceAtLeast(KEY_HOLD_MS)
        var lasted = LwServiceProtocol.GESTURE_COMPLETED
        while (left > 0) {
            val slice = left.coerceAtMost(BRAKE_SLICE_MS)
            if (brake && interrupted(slice)) {
                lasted = (SystemClock.uptimeMillis() - started).toInt()
                Log.i(TAG, "the hold on key $keyCode ended after $lasted ms, a real finger arrived")
                left = 0
            } else {
                left -= slice
            }
        }
        val flags = if (holdMs >= LwServiceProtocol.LONG_PRESS_MS) KeyEvent.FLAG_LONG_PRESS else 0
        val accepted = sendKey(
            displayId,
            KeyEvent.ACTION_UP,
            keyCode,
            downTime,
            SystemClock.uptimeMillis(),
            flags,
        )
        return KeyOutcome(accepted, lasted)
    }

    /**
     * Type a string as key presses
     *
     * This is what is left for a screen that reports no field to set the text on - an engine drawn
     * interface with a hidden field behind it, say - and it only produces what a keyboard can: the
     * events come from the virtual keyboard's own map, so letters, digits and its punctuation work
     * and anything else, Chinese above all, does not
     *
     * @returns how many characters were delivered, or [NOT_TYPABLE] when this device's keyboard
     *   cannot produce them
     */
    fun text(displayId: Int, text: String): Int {
        val map = keyboard
            ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).also { keyboard = it }
        val events = map.getEvents(text.toCharArray()) ?: return NOT_TYPABLE
        events.forEach { event ->
            event.source = InputDevice.SOURCE_KEYBOARD
            aim(event, displayId)
            if (!inject(event)) {
                Log.w(TAG, "the device refused a key while typing on display $displayId")
                return NOT_TYPABLE
            }
        }
        return text.length
    }

    /** One half of a key press, aimed at a screen the same way a touch is */
    private fun sendKey(
        displayId: Int,
        action: Int,
        keyCode: Int,
        downTime: Long,
        eventTime: Long,
        flags: Int,
    ): Boolean {
        // The device id is the virtual keyboard's on purpose: the event has to say it came from a
        // keyboard nobody owns, which is the closest thing to "the platform itself pressed this"
        val event = KeyEvent(
            downTime,
            eventTime,
            action,
            keyCode,
            NO_REPEAT,
            NO_META,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            NO_SCANCODE,
            flags,
            InputDevice.SOURCE_KEYBOARD,
        )
        // A key event is not pooled the way a motion event is, so there is nothing to give back
        aim(event, displayId)
        return if (inject(event)) {
            true
        } else {
            Log.w(TAG, "the device refused key $keyCode on display $displayId")
            false
        }
    }

    /**
     * How long to hold something the caller asked to be held for a long press
     *
     * A hold that only just reaches the platform's threshold is a race rather than a long press:
     * the detector runs on the main thread at exactly that moment, so a lift that beats it turns
     * the long press back into a plain press and the caller is never told. Everything that asks for
     * a long press at all is therefore given the margin, which is short enough not to be felt
     */
    private fun heldFor(holdMs: Long): Long =
        if (holdMs in LwServiceProtocol.LONG_PRESS_MS until LONG_PRESS_SAFE_MS) {
            LONG_PRESS_SAFE_MS
        } else {
            holdMs
        }

    /** One moment of a finger, in the screen's own coordinates */
    fun touch(displayId: Int, action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        when (action) {
            LwServiceProtocol.TOUCH_DOWN -> {
                downTime = now
                down = true
                send(displayId, MotionEvent.ACTION_DOWN, x, y, now)
            }

            LwServiceProtocol.TOUCH_MOVE -> if (down) send(displayId, MotionEvent.ACTION_MOVE, x, y, now)

            LwServiceProtocol.TOUCH_UP -> {
                // A lift that never saw a press would be delivered as a stray event, which some
                // apps read as a tap of their own
                if (down) send(displayId, MotionEvent.ACTION_UP, x, y, now)
                down = false
            }

            else -> throw IllegalArgumentException("unknown touch action $action")
        }
    }

    /** Ask the device to deliver one event as if it had come from the screen itself */
    private fun send(displayId: Int, action: Int, x: Float, y: Float, eventTime: Long) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            // An event with no source is refused by the dispatcher before it ever reaches a window
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            aim(event, displayId)
            if (!inject(event)) {
                Log.w(TAG, "the device refused $action at $x,$y on display $displayId")
            }
        } finally {
            event.recycle()
        }
    }

    /** Point an event at a display, which is the hidden half of injecting onto a second screen */    private fun aim(event: InputEvent, displayId: Int) {
        val method = setDisplayIdMethod
            ?: InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { setDisplayIdMethod = it }
        method.invoke(event, displayId)
    }

    private fun inject(event: InputEvent): Boolean {
        val method = injectMethod
            ?: InputManager::class.java
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
                .also { injectMethod = it }
        // Waiting for a result would hold this thread until the app under the finger answered,
        // which is the one thing a gesture cannot afford
        return method.invoke(manager(), event, MODE_ASYNC) as Boolean
    }

    private fun manager(): InputManager {
        manager?.let { return it }
        val base = context
            ?: throw IllegalStateException("the privileged process has no context to reach the input manager")
        val service = base.getSystemService(InputManager::class.java)
            ?: throw IllegalStateException("this device has no input manager")
        return service.also { manager = it }
    }

    private companion object {
        const val TAG = "LwInput"

        /** `InputManager.INJECT_INPUT_EVENT_MODE_ASYNC`, which is a plain int in the platform */
        const val MODE_ASYNC = 0

        /** Long enough to read as a press, short enough that a tap still feels immediate */
        const val TAP_HOLD_MS = 40L

        /** How many points a drag is broken into, which is what gives it a speed */
        const val SWIPE_STEPS = 12

        /**
         * One 60 Hz frame, which is the shortest a step may be
         *
         * A step shorter than a frame can land in the same frame as the one before it, and then the
         * app never sees the finger move: it sees a press, a jump and a lift. Four milliseconds was
         * the old floor and it was too short, because nothing paced the steps at all
         */
        const val MIN_STEP_MS = 16L

        /** How finely a step is watched for the user's hand, which is what the brake's latency is */
        const val BRAKE_SLICE_MS = 8L

        /** How recently a real finger has to have moved for a drag to be ended by it */
        const val BRAKE_MS = 250

        /** A key press is short: the platform does not read velocity into one */
        const val KEY_HOLD_MS = 40L

        /** What a long press is held for when the caller asked for one that only just reaches it */
        const val LONG_PRESS_SAFE_MS = 650L

        const val NO_REPEAT = 0
        const val NO_META = 0
        const val NO_SCANCODE = 0

        /** What typing answers when the keyboard cannot produce the text at all */
        const val NOT_TYPABLE = -1
    }
}
