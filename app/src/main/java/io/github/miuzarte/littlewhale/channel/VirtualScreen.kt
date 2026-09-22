package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.miuzarte.littlewhale.workspace.Workspace
import java.io.File
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A virtual screen that exists right now
 * @property displayId the system's id for it, which is what every other call names
 * @property width the screen's own width in its own pixels
 * @property height the screen's own height in its own pixels
 * @property dpi the screen's own density
 * @property label what the menu calls it, which is the name its creator gave it
 * @property acceptsControl whether tools may still act on it, which the user turns off from the
 *   menu when they want the model's hands off this screen. The user's own finger on the preview is
 *   never subject to it: pausing stops the model, not the person holding the phone
 */
data class ScreenState(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val dpi: Int,
    val label: String,
    val acceptsControl: Boolean = true,
) {
    /** Width over height, which is the shape a picture of it has to keep */
    val aspect: Float get() = width.toFloat() / height.toFloat()

    /** The short side over the long one, which is what a landscape screen shrinks its box to */
    val shape: String get() = "$width x $height"
}

/**
 * The virtual screens, from the app's side
 *
 * The screens are built in the privileged process and this is the app's whole handle on them:
 * every call here is one transaction, and they all go through a single worker thread, because the
 * first of them may have to start that process and because the order of a detach and an attach is
 * the state of the preview
 *
 * What the app contributes is the half the privileged side cannot have - the surface the frames
 * land on, which belongs to this process and dies with its window. Those two lifetimes are the
 * reason attaching is separate from selecting: a screen outlives the preview, and a screen that is
 * not selected keeps running, because whatever is on it is not the app's business to stop
 */
object VirtualScreen {

    private const val TAG = "LwScreen"

    /** Every screen the device is hosting, in the order they were made */
    var screens: List<ScreenState> by mutableStateOf(emptyList())
        private set

    /** The screen the preview is showing, null while none is selected */
    var selected: ScreenState? by mutableStateOf(null)
        private set

    /** Why the last attempt failed, cleared by the next one that works */
    var lastError: String? by mutableStateOf(null)
        private set

    /** Whether a call is in flight, which is what a caller waits on before asking again */
    var working: Boolean by mutableStateOf(false)
        private set

    /**
     * The surface each screen's preview draws into, by display id
     *
     * One per screen rather than one for the app: a preview that is being replaced reports its
     * surface going away at some point around the replacement, and with a single slot that report
     * could land on the screen that just took over
     */
    private val surfaces = mutableMapOf<Int, Surface?>()

    /** How many screens this app has asked for, which is only used to label the unnamed ones */
    private var created = 0

    /**
     * The screens that were closed lately, by display id, so a call on a dead id can say who
     * closed it. Bounded: only the most recent few are worth remembering, and an id that comes
     * back around would be a new screen anyway
     */
    private val closed = LinkedHashMap<Int, ClosedScreen>()

    /** Where a picture of the screen is left, which only the app's own directories can be */
    @Volatile
    private var pictures: File? = null

    /** The app, kept for the one thing here that needs to name a directory of its own */
    @Volatile
    private var application: Context? = null

    /**
     * Everything that changes which screen draws where, one at a time and in order
     *
     * A single thread is what makes a switch safe: detaching the old screen and attaching the new
     * one are two transactions, and a preview that came and went between them would otherwise be
     * able to leave the surface on a screen nobody is looking at
     */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-screen").apply { isDaemon = true }
    }

    /** Touch, queued on a thread of its own so a gesture never waits for a screen switch */
    private val touches = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lw-screen-touch").apply { isDaemon = true }
    }

    /** Build the one thing here that needs a context: somewhere to write a captured picture */
    fun initialize(context: Context) {
        application = context.applicationContext
        pictures = File(context.applicationContext.cacheDir, PICTURE_NAME)
    }

    /**
     * Ask for one more screen and show it, blocking
     *
     * Blocking because nobody in the app calls this: a screen is made by whatever is driving the
     * device - a tool, over the bridge - and that caller wants the screen it just asked for. The
     * app's own UI only lists them and gives them back
     *
     * @param name what its creator wants it called, numbered if that name is taken, and only
     *   falling back to a generated one when nothing was said
     * @param width the screen's own size, or 0 to take this device's
     * @param height the screen's own size, or 0 to take this device's
     * @param dpi the screen's density, or 0 to take this device's
     */
    fun create(name: String? = null, width: Int = 0, height: Int = 0, dpi: Int = 0): ScreenState? {
        val metrics = Resources.getSystem().displayMetrics
        val pixelsWide = if (width > 0) width else metrics.widthPixels
        val pixelsHigh = if (height > 0) height else metrics.heightPixels
        val density = if (dpi > 0) dpi else metrics.densityDpi
        Log.i(TAG, "asking for a ${pixelsWide}x$pixelsHigh screen at ${density}dpi named ${name ?: "(none)"}")
        return try {
            val id = requireService().createDisplay(pixelsWide, pixelsHigh, density, null)
            created += 1
            val screen = ScreenState(id, pixelsWide, pixelsHigh, density, label(name))
            screens = screens + screen
            selected = screen
            lastError = null
            screen
        } catch (problem: Throwable) {
            report("create", problem)
            null
        }
    }

    /**
     * Give one screen another shape, with whatever is running on it left running
     *
     * Blocking, because the caller is a tool that will look at the result next: an app on the screen
     * is re-laid out rather than restarted, and the size it now has is what the next picture and the
     * next coordinates have to be read against
     *
     * @param width the screen's new width, or 0 to keep the one it has
     * @param height the screen's new height, or 0 to keep the one it has
     * @param dpi the screen's new density, or 0 to keep the one it has
     * @param swap trade width for height instead of naming a size, which is the same change said
     *   the way a caller thinks about it: the density does not move
     */
    fun resize(
        screen: ScreenState,
        width: Int = 0,
        height: Int = 0,
        dpi: Int = 0,
        swap: Boolean = false,
    ): ScreenState? {
        // 暂停是"别碰这块屏", 换尺寸也在碰它: 用户刚说过别动, 底下的布局不该在他手底下换掉
        requireAcceptsControl(screen)
        val pixelsWide = if (swap) screen.height else if (width > 0) width else screen.width
        val pixelsHigh = if (swap) screen.width else if (height > 0) height else screen.height
        val density = if (dpi > 0) dpi else screen.dpi
        requireSize(pixelsWide, "width")
        requireSize(pixelsHigh, "height")
        return try {
            requireService().resizeDisplay(screen.displayId, pixelsWide, pixelsHigh, density)
            val resized = screen.copy(width = pixelsWide, height = pixelsHigh, dpi = density)
            screens = screens.map { if (it.displayId == screen.displayId) resized else it }
            if (selected?.displayId == screen.displayId) selected = resized
            lastError = null
            resized
        } catch (problem: Throwable) {
            report("resize", problem)
            null
        }
    }

    /** Refuse a size that is a typo rather than a screen, before the device tries to allocate it */
    private fun requireSize(pixels: Int, side: String) {
        if (pixels in MIN_DISPLAY_SIDE..MAX_DISPLAY_SIDE) return
        throw IllegalArgumentException(
            "$side of $pixels is not a screen: a side is between $MIN_DISPLAY_SIDE and" +
                " $MAX_DISPLAY_SIDE pixels",
        )
    }

    /** What to call a screen: its given name, made unique, or a generated one if it has none */
    private fun label(name: String?): String {
        val wanted = name?.trim().orEmpty().ifEmpty { "虚拟屏 $created" }
        var candidate = wanted
        var suffix = 2
        while (screens.any { it.label == candidate }) {
            candidate = "$wanted $suffix"
            suffix += 1
        }
        return candidate
    }

    /** Show one screen, or stop showing the one that is already showing */
    fun toggle(screen: ScreenState) {
        select(if (selected?.displayId == screen.displayId) null else screen)
    }

    /**
     * Let tools act on one screen again, or stop them
     *
     * A screen accepts control from the moment it is made; this is the user's brake on the model,
     * for when they want to take over by hand or stop what it is doing. It does not touch the
     * screen, does not close it and does not stop the preview - it only makes every acting call
     * refuse until it is turned back on
     */
    fun setAcceptsControl(screen: ScreenState, accepts: Boolean) {
        screens = screens.map {
            if (it.displayId == screen.displayId) it.copy(acceptsControl = accepts) else it
        }
        // 选中的那个是列表里的一份旧拷贝, 不跟着换菜单里就还是旧状态
        if (selected?.displayId == screen.displayId) {
            selected = screens.firstOrNull { it.displayId == screen.displayId }
        }
    }

    /**
     * Refuse an action while the user has this screen paused
     *
     * Reads the current list rather than the caller's copy: a call that was queued before the user
     * pressed pause has to see the pause too
     */
    fun requireAcceptsControl(screen: ScreenState) {
        val current = screens.firstOrNull { it.displayId == screen.displayId } ?: return
        if (current.acceptsControl) return
        throw IllegalStateException(
            "the user paused control of the virtual screen \"${current.label}\"" +
                " (displayId ${current.displayId}), so nothing may act on it" +
                " - tell them to resume it from the phone's menu" +
                " (虚拟屏 -> ${current.label} -> 继续接受控制)",
        )
    }

    /**
     * The phone's own screen, which every acting call can name like any other
     *
     * Read out of the display manager on each call rather than remembered, because its size turns
     * with the device and the coordinates a caller sends have to be in the space it has right now.
     * It is deliberately never one of [screens]: it is not ours to preview, to pause or to give
     * back, and it exists whether or not this app is running
     */
    @Suppress("DEPRECATION")
    fun mainScreen(): ScreenState {
        val metrics = DisplayMetrics()
        application
            ?.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.getRealMetrics(metrics)
        // An app that has not been initialized, or a device with no display manager, falls back to
        // the same metrics a screen of ours would be made with - both name this phone's own screen
        if (metrics.widthPixels <= 0 || metrics.heightPixels <= 0) {
            val system = Resources.getSystem().displayMetrics
            return ScreenState(MAIN_DISPLAY, system.widthPixels, system.heightPixels, system.densityDpi, MAIN_LABEL)
        }
        return ScreenState(MAIN_DISPLAY, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi, MAIN_LABEL)
    }

    /**
     * What the phone's own glass has seen lately, null when nothing can say
     *
     * Null is not "nobody is touching": it means the brake cannot be read at all, which for
     * anything aimed at the phone's own screen is a reason to refuse rather than to try
     */
    fun userTouch(): TouchState? = try {
        requireService().touchState()
    } catch (problem: Throwable) {
        Log.w(TAG, "the touch watch could not be read", problem)
        null
    }

    /**
     * Whether the phone counts as being in somebody's hands
     *
     * The policy is the state's own - see [TouchState.driving] - and this is only here so callers
     * that already hold a screen have one thing to ask
     */
    fun userIsDriving(touch: TouchState?): Boolean = touch?.driving() == true

    /**
     * Refuse an action because a real finger owns the phone's own screen right now
     *
     * Only the phone's own screen is braked here. A screen of ours is somewhere else entirely -
     * hands on the phone while the model draws on display 69 are not a conflict - and the brakes
     * for those are the ones the menu already gives the user: pause, and the preview's own switch
     */
    fun requireUserNotDriving(screen: ScreenState) {
        if (screen.displayId != MAIN_DISPLAY) return
        val touch = userTouch()
        if (touch == null || !touch.watching) {
            throw IllegalStateException(
                "the phone's own screen (displayId $MAIN_DISPLAY) cannot be driven while nothing" +
                    " on this device is watching it for a real finger" +
                    " (${touch?.error ?: "the touch watch could not be read"}) - that watch is the" +
                    " only thing that stops the model when a person picks the phone up, so this" +
                    " call was refused; the virtual screens are not affected",
            )
        }
        if (userIsDriving(touch)) throw IllegalStateException(drivingReason(touch))
    }

    /** Why the phone was refused, worded so a model reads it as a stop rather than as a hiccup */
    private fun drivingReason(touch: TouchState): String {
        val how = if (touch.down) {
            "a real finger is on the screen right now"
        } else {
            "a real finger touched it ${touch.msSinceLastTouch} ms ago"
        }
        return "the user is using the phone's own screen (displayId $MAIN_DISPLAY): $how, so this" +
            " call was not delivered - stop driving the main screen, say what you were doing, and" +
            " ask the user what they want instead of retrying; they will tell you when it is free"
    }

    /** Show a screen, or none, moving the preview's surface with it */
    private fun select(screen: ScreenState?) {
        val previous = selected
        if (previous?.displayId == screen?.displayId) return
        selected = screen
        val surface = screen?.let { surfaces[it.displayId] }
        worker.execute {
            try {
                val service = requireService()
                // The old one is let go first: two screens drawing into one buffer would leave the
                // picture on whichever the compositor happened to finish last
                if (previous != null) service.setDisplaySurface(previous.displayId, null)
                if (screen != null) service.setDisplaySurface(screen.displayId, surface)
                lastError = null
            } catch (problem: Throwable) {
                report("select", problem)
            }
        }
    }

    /**
     * Give one screen back, and with it whatever the system put on it
     * @param screen the screen to close.
     * @param byUser true when the phone's own menu asked for this, false when a tool did, which is
     *   what lets a later call on this id say who closed it
     */
    fun release(screen: ScreenState, byUser: Boolean = false) {
        screens = screens.filterNot { it.displayId == screen.displayId }
        surfaces.remove(screen.displayId)
        if (selected?.displayId == screen.displayId) selected = null
        remember(screen, byUser)
        working = true
        worker.execute {
            try {
                requireService().releaseDisplay(screen.displayId)
                lastError = null
            } catch (problem: Throwable) {
                report("release", problem)
            } finally {
                working = false
            }
        }
    }

    /**
     * Why one displayId points at nothing right now
     *
     * The three cases are told apart on purpose, because what to do about them differs:
     *
     * - it never existed (or was forgotten): a bad id, so go and list the screens
     * - the user closed it from the phone: a person's decision, so stop and ask them
     * - an agent closed it through `lw_screen_release`: screens are shared, so another session may
     *   be the one that did it, and making a replacement is the user's call rather than the model's
     */
    fun missingScreenReason(displayId: Int): String {
        val gone = closed[displayId]
        return when {
            gone == null ->
                "no screen with displayId $displayId, ask screen for the ones that exist"

            gone.byUser ->
                "the user closed the virtual screen \"${gone.label}\" (displayId $displayId) from" +
                    " the phone's menu, so it is gone - stop and ask them what they want before" +
                    " acting again (lw_screen lists the ones that are left)"

            else ->
                "the virtual screen \"${gone.label}\" (displayId $displayId) was closed by an" +
                    " agent calling lw_screen_release rather than by the user, and screens are" +
                    " shared, so another session may be the one that closed it - the id is gone" +
                    " either way, do not make a replacement without asking" +
                    " (lw_screen lists the ones that are left)"
        }
    }

    /** Remember one closed screen, keeping only the last few, for the sentence above */
    private fun remember(screen: ScreenState, byUser: Boolean) {
        closed[screen.displayId] = ClosedScreen(screen.label, byUser)
        while (closed.size > CLOSED_REMEMBERED) {
            closed.remove(closed.keys.first())
        }
    }

    /**
     * Follow the preview's surface for one screen
     *
     * A screen with no surface is a normal state rather than a broken one: the app goes to the
     * background, its window is torn down, and the screen has to keep running without it
     */
    fun attach(screen: ScreenState, surface: Surface?) {
        surfaces[screen.displayId] = surface
        // Only the screen the preview is on is worth telling the device about; the other one is
        // either on its way out or waiting for its turn, and it gets told when its turn comes
        if (selected?.displayId != screen.displayId) return
        worker.execute {
            try {
                requireService().setDisplaySurface(screen.displayId, surface)
                lastError = null
            } catch (problem: Throwable) {
                // A screen that was released while its preview was going away is not worth a line
                Log.w(TAG, "attach to ${screen.displayId} failed", problem)
            }
        }
    }

    /** A finger touched the screen, at a point in the screen's own coordinates */
    fun press(screen: ScreenState, x: Float, y: Float) = touch(screen.displayId, LwServiceProtocol.TOUCH_DOWN, x, y)

    /** A finger moved across the screen */
    fun move(screen: ScreenState, x: Float, y: Float) = touch(screen.displayId, LwServiceProtocol.TOUCH_MOVE, x, y)

    /** A finger left the screen */
    fun release(screen: ScreenState, x: Float, y: Float) = touch(screen.displayId, LwServiceProtocol.TOUCH_UP, x, y)

    /**
     * Press and lift at one point, waiting until the device has taken it
     *
     * Blocking, unlike the preview's own touches: the caller here is a tool that will look at the
     * screen the moment this returns, and "queued" would be a lie it could not tell apart from
     * "done". The screen is named by the caller rather than taken from the selection, which is the
     * user's to change at any moment
     *
     * A press that is held is the one gesture here that can be taken away while it is happening on
     * the phone's own screen: a real finger arriving lifts it where it stands, and the answer says
     * how long it lasted, because a caller that thinks its press was held reads what follows as the
     * result of something that never finished
     *
     * @param screen the screen to touch.
     * @param x the point in the screen's own pixels.
     * @param y the point in the screen's own pixels.
     * @param holdMs how long the finger stays down, [LONG_PRESS_MS][LwServiceProtocol.LONG_PRESS_MS]
     *   or more being what a long press is.
     */
    fun tap(screen: ScreenState, x: Float, y: Float, holdMs: Long = 0L) {
        requireAcceptsControl(screen)
        requireUserNotDriving(screen)
        val brake = screen.displayId == MAIN_DISPLAY
        gesture(screen, "tap") { service ->
            val lasted = service.tap(screen.displayId, x, y, holdMs, brake)
            if (lasted != LwServiceProtocol.GESTURE_COMPLETED) {
                throw IllegalStateException(interruptedReason("press", lasted, holdMs))
            }
        }
    }

    /**
     * Drag from one point to another, waiting until the finger has been lifted again
     *
     * A drag on the phone's own screen is the one gesture that can be taken away half way through,
     * and the answer says how far it got, because a caller that thinks its gesture happened reads
     * the next screenshot as the result of something that never finished
     */
    fun swipe(screen: ScreenState, fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long) {
        requireAcceptsControl(screen)
        requireUserNotDriving(screen)
        val brake = screen.displayId == MAIN_DISPLAY
        gesture(screen, "swipe") { service ->
            val lasted = service.swipe(screen.displayId, fromX, fromY, toX, toY, durationMs, brake)
            if (lasted != LwServiceProtocol.GESTURE_COMPLETED) {
                throw IllegalStateException(interruptedReason("drag", lasted, durationMs))
            }
        }
    }

    /** Why a gesture stopped in the middle, which is the brake working rather than a failure */
    private fun interruptedReason(what: String, lasted: Int, askedMs: Long): String =
        "the $what on the phone's own screen (displayId $MAIN_DISPLAY) was cut short after $lasted" +
            " ms of the $askedMs ms asked for: the user put a finger on the screen, so it was" +
            " released there - the phone is theirs again, stop and ask the user what they want" +
            " before acting on it again"

    /**
     * Press one key on a screen, waiting until the device has taken it
     *
     * Pressing a key is acting on a screen like any other call here, so it goes through the same
     * queue and past the same brakes: the menu's pause on a screen of ours, and a real finger on
     * the phone's own screen, because a person holding the phone outranks the model there whatever
     * it is doing
     *
     * @param holdMs how long the key stays down, [LONG_PRESS_MS][LwServiceProtocol.LONG_PRESS_MS] or
     *   more being what the platform reads as a long press. A hold on the phone's own screen is
     *   watched like every other one: a real finger lifts the key where it stands
     */
    fun key(screen: ScreenState, keyCode: Int, holdMs: Long) {
        requireAcceptsControl(screen)
        requireUserNotDriving(screen)
        if (screen.displayId != MAIN_DISPLAY && keyCode in KeyCodes.WHOLE_SCREEN) {
            throw IllegalStateException(
                "the key ${KeyCodes.nameOf(keyCode)} is about a whole screen rather than about" +
                    " what is on it, and \"${screen.label}\" (displayId ${screen.displayId}) is a" +
                    " screen of ours: it has no launcher and no system UI, so this key does nothing" +
                    " useful there and can leave the display asleep with its last picture still" +
                    " coming out of lw_screenshot - press it on the phone's own screen (displayId" +
                    " 0), or give this screen back with lw_screen_release and make a new one",
            )
        }
        val brake = screen.displayId == MAIN_DISPLAY
        gesture(screen, "key") { service ->
            val outcome = service.key(screen.displayId, keyCode, holdMs, brake)
            if (!outcome.accepted) {
                throw IllegalStateException(
                    "the device refused the key press on displayId ${screen.displayId}" +
                        " (\"${screen.label}\") - the screen may have gone or the key may not be" +
                        " one it can take",
                )
            }
            if (outcome.lastedMs != LwServiceProtocol.GESTURE_COMPLETED) {
                throw IllegalStateException(interruptedReason("hold", outcome.lastedMs, holdMs))
            }
        }
    }

    /**
     * Type a string as key presses, answering how many characters went through
     *
     * The narrow half of typing: it is here for a screen that reports no field, because then the
     * only thing left is the keys a keyboard would have pressed. It goes through the touch queue
     * with everything else, so it cannot land in the middle of a gesture, and it is braked the same
     * way
     */
    fun text(screen: ScreenState, text: String): Int {
        requireAcceptsControl(screen)
        requireUserNotDriving(screen)
        var typed = NOTHING_TYPED
        gesture(screen, "type") { service -> typed = service.text(screen.displayId, text) }
        return typed
    }

    /**
     * Run one gesture on the touch queue and wait for the device to answer
     *
     * The queue is what keeps a gesture in order against the preview's own fingers, and waiting on
     * it is what lets a caller report what happened instead of hoping. The pause is asked again
     * inside the queued block: whatever is behind this one in the queue would otherwise still run
     * after the user pressed pause, and the same goes for the user's own hand on the phone
     */
    private fun gesture(screen: ScreenState, name: String, block: (LwServiceProxy) -> Unit) {
        try {
            touches.submit {
                requireAcceptsControl(screen)
                requireUserNotDriving(screen)
                block(requireService())
            }.get(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            lastError = null
        } catch (problem: Throwable) {
            // The queue hands back what the transaction threw, which is the message worth keeping
            val cause = problem.cause ?: problem
            report(name, cause)
            throw IllegalStateException(lastError ?: "$name failed", cause)
        }
    }

    /**
     * Capture one screen and leave the picture where the model and the user can both open it
     *
     * Blocking, because its callers are the ones that want the bytes: a tool call, or a check. The
     * privileged side writes into the app's cache, which is a directory both uids can name, and the
     * app then copies it into the workspace - the host's file tools resolve paths inside the
     * workspace and nowhere else, so a picture left in the cache would be a path the model could be
     * told about but never open
     * @param screen the screen to capture, named rather than taken from the selection.
     * @param maxPixels the pixel budget the picture is scaled to fit.
     * @param maxBytes the byte budget the picture is scaled to fit.
     * @returns the picture to hand over, or null with [lastError] set.
     */
    fun screenshot(
        screen: ScreenState,
        maxPixels: Int = Picture.DEFAULT_MAX_PIXELS,
        maxBytes: Int = Picture.DEFAULT_MAX_BYTES,
    ): Picture.Fitted? {
        val staging = pictures ?: run {
            lastError = "no directory to write a picture into"
            return null
        }
        return try {
            val stale = captureSettled(screen, staging)
            if (stale != null) {
                lastError = stale
                return null
            }
            val picture = File(screens(), "screen-${screen.displayId}.png")
            staging.copyTo(picture, overwrite = true)
            lastError = null
            // The scaling happens here rather than at the far end: once a picture is stored nothing
            // on this device can re-encode it, so what the model will read has to be the right size
            // by the time it is handed over
            Picture.fit(picture, maxPixels, maxBytes)
        } catch (problem: Throwable) {
            report("screenshot", problem)
            null
        }
    }

    /**
     * Take a picture of the screen, and keep taking it while the device hands back the old frame
     *
     * A screen that has just been given another size has not drawn a frame at that size yet, and
     * `screencap` answers with the last frame there was - the size before, without saying so. A
     * caller that resized a screen and then looked would measure every coordinate on a picture of
     * the screen as it used to be, so what came back is checked against the size the screen has and
     * the capture is taken again until the two agree. The budget is short because the wait is one
     * frame: measured on the device, the second attempt in a row is already the right size
     *
     * @returns why the picture never matched, or null once one did
     */
    private fun captureSettled(screen: ScreenState, staging: File): String? {
        var last = "nothing"
        repeat(CAPTURE_ATTEMPTS) { attempt ->
            if (!requireService().screenshot(screen.displayId, staging.absolutePath)) {
                return "the device wrote no picture"
            }
            val size = Picture.sizeOf(staging) ?: return null
            if (size.first == screen.width && size.second == screen.height) return null
            last = "${size.first}x${size.second}"
            Log.i(TAG, "display ${screen.displayId} is ${screen.width}x${screen.height} but the" +
                " capture came back $last, taking it again")
            if (attempt < CAPTURE_ATTEMPTS - 1) Thread.sleep(CAPTURE_SETTLE_MS)
        }
        return "the device is still handing back a $last picture of a ${screen.width}x" +
            "${screen.height} screen, which is the frame from before it changed size: take it again"
    }

    /** Where captured pictures are left: inside the workspace, under a directory of our own */
    private fun screens(): File {
        val context = application ?: throw IllegalStateException("the app has not been initialized")
        return File(Workspace.resolve(context).directory, SCREENSHOTS).apply { mkdirs() }
    }

    /**
     * Whether one screen is awake, because two things go wrong when it is not
     *
     * A sleeping display keeps showing the last frame it drew, so `screencap` hands back a picture
     * of an interface that is no longer on the screen - reading it reports text that cannot be
     * pressed. And an injected touch does not wake a display, so the press lands nowhere. Both are
     * silent, which is why the answer carries this rather than a caller having to guess
     */
    fun displayPower(displayId: Int): String {
        val context = application ?: throw IllegalStateException("the app has not been initialized")
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: return "gone"
        return when (display.state) {
            Display.STATE_OFF -> "off"
            Display.STATE_DOZE, Display.STATE_DOZE_SUSPEND -> "dozing"
            Display.STATE_UNKNOWN -> "unknown"
            else -> "on"
        }
    }

    /**
     * One screen at its own resolution, for OCR
     *
     * Two differences from [screenshot], both deliberate: this one is **not** scaled to the pixel
     * budget (that budget exists so the model can read a picture at all, and halving a 12sp glyph
     * makes it unreadable), and nothing is left in the workspace - OCR wants pixels, not a file
     * for the model to open
     */
    fun capture(screen: ScreenState): Bitmap? {
        val staging = pictures ?: run {
            lastError = "no directory to write a picture into"
            return null
        }
        return try {
            val stale = captureSettled(screen, staging)
            if (stale != null) {
                lastError = stale
                return null
            }
            lastError = null
            BitmapFactory.decodeFile(staging.absolutePath)
        } catch (problem: Throwable) {
            report("capture", problem)
            null
        }
    }

    /**
     * Queue one moment of a finger
     *
     * Asynchronous, because its caller is the preview following a finger of its own: a frame that
     * waited for a round trip would drop the next one, and the queue is what keeps the order right
     * against whatever else is touching the same screen
     */
    private fun touch(displayId: Int, action: Int, x: Float, y: Float) {
        touches.execute {
            try {
                requireService().touch(displayId, action, x, y)
            } catch (problem: Throwable) {
                // A touch that failed is not worth a line in the UI: the finger is still moving,
                // and the next call will say the same thing
                Log.w(TAG, "touch $action at $x,$y failed", problem)
            }
        }
    }

    /** The channel, connected if it was not, or the reason there is none */
    private fun requireService(): LwServiceProxy = PrivilegedChannel.ensure()
        ?: throw IllegalStateException(PrivilegedChannel.state().error ?: "no privileged process")

    private fun report(name: String, problem: Throwable) {
        Log.w(TAG, "$name failed", problem)
        lastError = problem.message ?: problem.javaClass.simpleName
    }

    /** The name a captured picture is written under, in the app's own cache */
    private const val PICTURE_NAME = "screen.png"

    /** Where in the workspace pictures are kept, which the model can read and the user can open */
    private const val SCREENSHOTS = "screenshots"

    /** A gesture is one transaction, so this only has to outlast the longest swipe asked for */
    private const val GESTURE_TIMEOUT_MS = 30_000L

    /** How many closed screens are remembered for [missingScreenReason] */
    private const val CLOSED_REMEMBERED = 8

    /** The phone's own screen, which is nobody's to create, to preview or to give back */
    private const val MAIN_DISPLAY = LwServiceProtocol.MAIN_DISPLAY

    /** What the phone's own screen is called, which is how a tool's answer shows it */
    private const val MAIN_LABEL = "主屏"

    /** What typing by key answers when the keyboard cannot produce the text */
    private const val NOTHING_TYPED = -1

    /**
     * What a screen's side is allowed to be
     *
     * A side is a number a caller types, so this is a guard against a wrong one rather than a
     * statement about displays: nothing is smaller than a few dozen pixels, and the largest phone
     * or tablet panel is far below the ceiling, while a surface at a made-up size is a picture
     * nobody can see and memory the device has to find
     */
    private const val MIN_DISPLAY_SIDE = 64
    private const val MAX_DISPLAY_SIDE = 8192

    /** How many times a capture is taken again while it is still the frame from before a resize */
    private const val CAPTURE_ATTEMPTS = 5

    /** How long to let the screen draw at its new size before looking again */
    private const val CAPTURE_SETTLE_MS = 120L
}

/** A screen that is gone, and whether the person holding the phone closed it */
private data class ClosedScreen(val label: String, val byUser: Boolean)
