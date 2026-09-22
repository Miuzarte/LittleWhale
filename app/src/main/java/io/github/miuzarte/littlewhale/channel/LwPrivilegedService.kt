package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.os.Binder
import android.os.Parcel
import android.os.Process
import android.util.Log

/**
 * The service the privileged process hosts
 *
 * It is a Binder itself rather than an AIDL stub, and it runs as whatever uid the channel gave
 * it, which is the fact the whole channel exists to establish. Everything here is deliberately
 * something the app process cannot do for itself: report the privileged uid, read the input
 * devices, which needs the `input` group the app does not have, host the virtual screen, whose
 * flags are gated on a permission the app does not hold, and watch the phone's own glass for the
 * finger that has the last word over everything else here
 *
 * The service is created by [LwServiceStarter] through app_process rather than by the platform,
 * so it has no Context of its own; the one it gets may be null and each use has to tolerate that
 */
class LwPrivilegedService(private val context: Context?) : Binder() {

    /** The screen this process hosts, which outlives every connection the app makes to it */
    private val display = LwVirtualDisplay(context)

    /** The phone's own glass, which is the brake: what it sees is a person, never an injection */
    private val touch = LwTouchWatch()

    /** Touch aimed at a screen, which only works from a uid the platform trusts to inject */
    private val input = LwInput(context, touch)

    /** Pictures of a screen, which the app cannot take once its own preview is gone */
    private val capture = LwCapture()

    /** The settings the app cannot write for itself, of which there is one so far */
    private val permission = LwPermission(context)

    /** Starting an app, which the app cannot ask the activity manager for at all */
    private val launch = LwLaunch()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
        when (code) {
            LwServiceProtocol.DESTROY -> {
                data.enforceInterface(LwServiceProtocol.DESCRIPTOR)
                // The app is waiting on this transaction, so ending the process inside it would
                // turn a clean release into a dead binder; the reply goes first, the exit follows
                reply?.writeNoException()
                exitAfterReply()
                true
            }

            LwServiceProtocol.VERSION -> answering(data, reply) { writeString(VERSION) }

            LwServiceProtocol.UID -> answering(data, reply) { writeInt(Process.myUid()) }

            LwServiceProtocol.PID -> answering(data, reply) { writeInt(Process.myPid()) }

            LwServiceProtocol.INPUT_DEVICES -> answering(data, reply) { writeString(readInputDevices()) }

            LwServiceProtocol.TOUCH_STATE -> answering(data, reply) {
                val state = touch.state()
                writeInt(if (state.watching) 1 else 0)
                writeInt(if (state.down) 1 else 0)
                writeInt(state.msSinceLastTouch)
                writeInt(state.events)
                writeString(state.path)
                writeString(state.error)
            }

            LwServiceProtocol.DISPLAY_CREATE -> answering(data, reply) {
                val width = data.readInt()
                val height = data.readInt()
                val dpi = data.readInt()
                val surface = data.readSurface()
                writeInt(display.create(width, height, dpi, surface))
            }

            LwServiceProtocol.DISPLAY_SURFACE -> answering(data, reply) {
                val displayId = data.readInt()
                display.setSurface(displayId, data.readSurface())
            }

            LwServiceProtocol.DISPLAY_RESIZE -> answering(data, reply) {
                val displayId = data.readInt()
                val width = data.readInt()
                val height = data.readInt()
                val dpi = data.readInt()
                display.resize(displayId, width, height, dpi)
            }

            LwServiceProtocol.DISPLAY_RELEASE -> answering(data, reply) {
                val displayId = data.readInt()
                display.release(displayId)
                // A screen that went away takes the capturer's idea of its compositor id with it
                capture.forget()
            }

            LwServiceProtocol.INPUT_TAP -> answering(data, reply) {
                val displayId = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                val holdMs = data.readLong()
                val brake = data.readInt() == 1
                writeInt(input.tap(displayId, x, y, holdMs, brake))
            }

            LwServiceProtocol.INPUT_TOUCH -> answering(data, reply) {
                val displayId = data.readInt()
                val action = data.readInt()
                val x = data.readFloat()
                val y = data.readFloat()
                input.touch(displayId, action, x, y)
            }

            LwServiceProtocol.INPUT_SWIPE -> answering(data, reply) {
                val displayId = data.readInt()
                val fromX = data.readFloat()
                val fromY = data.readFloat()
                val toX = data.readFloat()
                val toY = data.readFloat()
                val durationMs = data.readLong()
                val brake = data.readInt() == 1
                writeInt(input.swipe(displayId, fromX, fromY, toX, toY, durationMs, brake))
            }

            LwServiceProtocol.INPUT_KEY -> answering(data, reply) {
                val displayId = data.readInt()
                val keyCode = data.readInt()
                val holdMs = data.readLong()
                val brake = data.readInt() == 1
                val outcome = input.key(displayId, keyCode, holdMs, brake)
                writeInt(if (outcome.accepted) 1 else 0)
                writeInt(outcome.lastedMs)
            }

            LwServiceProtocol.INPUT_TEXT -> answering(data, reply) {
                val displayId = data.readInt()
                val text = data.readString().orEmpty()
                writeInt(input.text(displayId, text))
            }

            LwServiceProtocol.SCREENSHOT -> answering(data, reply) {
                val displayId = data.readInt()
                val path = data.readString().orEmpty()
                // The phone's own screen is the one display with no name of ours to look up, and
                // naming no display at all is how `screencap` is asked for it
                val written = if (displayId == LwServiceProtocol.MAIN_DISPLAY) {
                    capture.capturePrimary(path)
                } else {
                    val name = display.nameOf(displayId)
                    name != null && capture.capture(name, path)
                }
                writeInt(if (written) 1 else 0)
            }

            LwServiceProtocol.A11Y_SET -> answering(data, reply) {
                val enabled = data.readInt() == 1
                writeInt(if (permission.setAccessibility(enabled)) 1 else 0)
            }

            LwServiceProtocol.LAUNCH -> answering(data, reply) {
                val displayId = data.readInt()
                val userId = data.readInt()
                val packageName = data.readString().orEmpty()
                val component = data.readString().orEmpty()
                val timeoutMs = data.readLong()
                val result = launch.start(displayId, userId, packageName, component, timeoutMs)
                writeString(result.output)
                writeInt(result.code)
            }

            LwServiceProtocol.USERS -> answering(data, reply) { writeString(launch.users()) }

            LwServiceProtocol.PACKAGES -> answering(data, reply) {
                writeString(launch.packages(data.readInt()))
            }

            else -> super.onTransact(code, data, reply, flags)
        }

    /** A context for anything that needs system services, null when the platform gave none */
    fun context(): Context? = context

    /** Release whatever is held and end the process, which is what the app's destroy asks for */
    fun destroy() {
        Log.i(TAG, "exiting")
        touch.stop()
        // Screens are the one thing the system keeps alive after this process is gone, and a
        // display nobody releases stays in `dumpsys display` until the device reboots
        display.releaseAll()
        Process.killProcess(Process.myPid())
    }

    /** Answer one transaction: check the token, then write the value into the reply parcel */
    private inline fun answering(data: Parcel, reply: Parcel?, write: Parcel.() -> Unit): Boolean {
        data.enforceInterface(LwServiceProtocol.DESCRIPTOR)
        if (reply == null) return true
        return try {
            reply.writeNoException()
            reply.write()
            true
        } catch (error: Exception) {
            // A refused display has to reach the caller with the reason the device gave, and an
            // empty reply would only read as garbage on that side; this is also the only place the
            // failure is visible, since the app's request thread only sees the rethrown exception
            Log.w(TAG, "a transaction failed", error)
            reply.setDataPosition(0)
            reply.writeException(error)
            true
        }
    }

    /** Give the framework a moment to flush the reply, then end the process */
    private fun exitAfterReply() {
        Thread({
            Thread.sleep(EXIT_DELAY_MS)
            destroy()
        }, "lw-service-exit").apply { isDaemon = true }.start()
    }

    private companion object {
        const val TAG = "LwService"

        /** Bumped whenever the protocol changes in a way the app has to know about */
        const val VERSION = "12"

        /** Long enough for the reply to leave the process, short enough to look immediate */
        const val EXIT_DELAY_MS = 100L
    }
}
