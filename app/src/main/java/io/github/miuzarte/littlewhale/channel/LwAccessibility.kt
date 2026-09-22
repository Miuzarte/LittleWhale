package io.github.miuzarte.littlewhale.channel

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * One node of a screen, as much of it as a caller can use
 *
 * Only nodes that say something or do something are kept at all: a tree is mostly layout
 * containers, and a caller reading every one of them learns less than it pays for
 */
data class UiNode(
    val depth: Int,
    val className: String,
    val text: String,
    val description: String,
    val viewId: String,
    val clickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val bounds: Rect,
    /** Where a tap has to land for this node to be the thing that gets pressed, null when nothing at or above it is clickable */
    val target: Rect?,
)

/** What one screen answered about itself */
data class UiTree(
    val packageName: String,
    val nodes: List<UiNode>,
    val truncated: Boolean,
    val error: String?,
)

/** What asking for a node by name did, with the candidates when the name was not one thing */
data class UiTap(
    val outcome: String,
    val matches: List<UiNode>,
    val via: String,
    val target: Rect?,
    val error: String?,
)

/**
 * What typing into a screen produced
 *
 * @property outcome `typed`, `ambiguous` (more than one field and none of them focused), `none`
 *   (nothing on that screen takes text), `unavailable` or `failed`
 * @property field the node the text went into, when one was found
 * @property matches the fields to choose from when the screen showed several
 * @property text what the field reads afterwards, empty for a field that hides what is in it
 * @property password whether the field hides what is in it
 * @property error why nothing was typed, null when something was
 */
data class UiTyping(
    val outcome: String,
    val field: UiNode?,
    val matches: List<UiNode>,
    val text: String,
    val password: Boolean,
    val error: String?,
)

/**
 * What the device says is on a screen, read through an accessibility service
 *
 * This is the one part of the channel that runs in the app's own process: the service is declared
 * in this app's manifest, so the system delivers its callbacks here, and reading a tree needs
 * neither a binder nor the privileged process - only injecting touch does
 *
 * What it is for: text, and coordinates in the screen's own pixels. A caller that can name a thing
 * does not have to measure it off a picture that was scaled to fit a token budget, which is where
 * the error in the picture-first approach comes from - and naming it can also be acted on directly,
 * through the node's own click action, without a coordinate ever being guessed
 *
 * The service reads the whole device, which is not a property of this class but of what an
 * accessibility service is, so it stays opt in and every caller here is made to name the one screen
 * it means
 */
class LwAccessibility : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        val windows = try {
            windowsOnAllDisplays
        } catch (error: Throwable) {
            null
        }
        val displays = windows?.let { (0 until it.size()).map { index -> it.keyAt(index) } }
        Log.i(TAG, "connected, displays $displays")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        // Only at debug level: this fires constantly and nothing here consumes it yet
        Log.d(TAG, "event ${AccessibilityEvent.eventTypeToString(type)} ${event.packageName}")
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        Log.i(TAG, "unbound")
        return super.onUnbind(intent)
    }

    companion object {

        private const val TAG = "LwA11y"

        /** A tree can be a WebView or a long list, so both of these are hard ceilings */
        private const val MAX_NODES = 400
        private const val MAX_DEPTH = 40

        /** How far up from a node to look for the thing that actually takes the click */
        private const val MAX_CLICKABLE_STEPS = 12

        /** How many candidates an ambiguous name is worth listing */
        private const val MAX_CANDIDATES = 12

        /** The system owns the service's lifetime, so this is the app's only handle on it */
        @Volatile
        private var instance: LwAccessibility? = null

        /** Whether the device has the service on, which is the first thing a caller has to be told */
        val running: Boolean get() = instance != null

        /**
         * Everything one screen contains that is worth saying
         *
         * The bounds that come back are the screen's own pixels, which is not obvious: an
         * accessibility node is reported in the coordinates of the display its window is on, and
         * this was checked against a picture of the same screen before anything was built on it
         */
        fun tree(displayId: Int): UiTree {
            if (instance == null) return UiTree("", emptyList(), false, NOT_ENABLED)
            val window = windowOn(displayId)
                ?: return UiTree("", emptyList(), false, "no window is on display $displayId")
            val root = window.root
                ?: return UiTree("", emptyList(), false, "display $displayId has no readable window content")
            // A tree is a snapshot from the moment the window was asked; refreshing asks the app
            // that owns it what it looks like now, which is what makes two calls in a row agree
            try {
                root.refresh()
            } catch (error: Throwable) {
                Log.d(TAG, "could not refresh the tree", error)
            }
            val gathered = gather(root)
            return UiTree(
                packageName = root.packageName?.toString().orEmpty(),
                nodes = gathered.list.map { it.ui },
                truncated = gathered.truncated,
                error = null,
            )
        }

        /**
         * Act on the node one screen calls by that name
         *
         * The matching is deliberately narrow to wide - the same text, then the same description,
         * then a text that contains it, then a description that does: a caller that says exactly
         * what it read off the tree gets exactly that node, and a caller that says part of a name
         * still lands somewhere sane
         *
         * Several nodes can share one row (a title and the summary beside it are two nodes with one
         * click target), so candidates that would all press the same thing count as one match. When
         * the name really does reach more than one thing, nothing is pressed and the candidates go
         * back: guessing which button a caller meant is how a screen ends up somewhere nobody asked
         *
         * @param holdMs how long the press is meant to be held. The platform's long click is its own
         *   action, so a hold past its threshold asks for that action on the closest ancestor which
         *   takes one - and a press held longer than a long click is not something an accessibility
         *   action can express, which is what the caller's finger fallback is for
         */
        fun tap(displayId: Int, name: String, holdMs: Long = 0L): UiTap {
            if (instance == null) return UiTap("unavailable", emptyList(), "", null, NOT_ENABLED)
            if (name.isBlank()) return UiTap("none", emptyList(), "", null, "no name was given")
            val window = windowOn(displayId)
                ?: return UiTap("none", emptyList(), "", null, "no window is on display $displayId")
            val root = window.root
                ?: return UiTap("none", emptyList(), "", null, "display $displayId has no readable window content")
            val gathered = gather(root)
            val matches = match(gathered, name)
            if (matches.isEmpty()) {
                return UiTap("none", emptyList(), "", null, "nothing on display $displayId says \"$name\"")
            }
            val distinct = matches.distinctBy { keyOf(it) }
            if (distinct.size > 1) {
                return UiTap("ambiguous", distinct.take(MAX_CANDIDATES).map { it.ui }, "", null, null)
            }
            val hit = distinct.first()
            val long = holdMs >= LwServiceProtocol.LONG_PRESS_MS
            val target = (if (long) longClickTarget(hit.node) else null) ?: hit.target ?: hit.node
            val bounds = Rect()
            target.getBoundsInScreen(bounds)
            val action = if (long) {
                AccessibilityNodeInfo.ACTION_LONG_CLICK
            } else {
                AccessibilityNodeInfo.ACTION_CLICK
            }
            val clicked = try {
                target.performAction(action)
            } catch (error: Throwable) {
                return UiTap("none", listOf(hit.ui), "", bounds, "the click action failed: ${error.message}")
            }
            if (!clicked) {
                // Some views are drawn by hand and take a touch but not an accessibility action; the
                // caller is told which, and where, so it can fall back to a finger
                return UiTap("unclicked", listOf(hit.ui), "", bounds, null)
            }
            return UiTap("clicked", listOf(hit.ui), if (target === hit.node) "self" else "ancestor", bounds, null)
        }

        /**
         * Put text into the field one screen is showing
         *
         * The field is found rather than guessed at: the focused one when the app has put focus in
         * one, otherwise the only editable thing on the screen, otherwise nothing is typed and the
         * candidates come back - the same judgement [tap] makes about a name that reaches more than
         * one control
         *
         * Setting the text is the platform's own action rather than a stream of key presses, which
         * is what makes this work for what no keyboard can produce (Chinese, an emoji) and for a
         * screen with no IME in front of it: the text lands in the field while the phone is showing
         * something else entirely
         */
        fun type(displayId: Int, input: String, replace: Boolean): UiTyping {
            if (instance == null) {
                return UiTyping("unavailable", null, emptyList(), "", false, NOT_ENABLED)
            }
            if (input.isEmpty()) {
                return UiTyping("none", null, emptyList(), "", false, "no text was given")
            }
            val window = windowOn(displayId) ?: return UiTyping(
                "none", null, emptyList(), "", false, "no window is on display $displayId",
            )
            val root = window.root ?: return UiTyping(
                "none", null, emptyList(), "", false, "display $displayId has no readable window content",
            )
            val fields = gather(root).list.filter { it.ui.editable }
            if (fields.isEmpty()) {
                return UiTyping("none", null, emptyList(), "", false, "nothing on display $displayId takes text")
            }
            val target = fields.firstOrNull { it.node.isFocused }
                ?: fields.singleOrNull()
                ?: return UiTyping(
                    "ambiguous", null, fields.take(MAX_CANDIDATES).map { it.ui }, "", false, null,
                )
            val current = try {
                target.node.text?.toString().orEmpty()
            } catch (error: Throwable) {
                ""
            }
            val wanted = if (replace) input else insert(current, target.node, input)
            val request = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, wanted)
            }
            val accepted = try {
                target.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, request)
            } catch (error: Throwable) {
                return UiTyping(
                    "failed", target.ui, emptyList(), "", false, "the field refused the text: ${error.message}",
                )
            }
            if (!accepted) {
                return UiTyping("failed", target.ui, emptyList(), "", false, "the field did not take the text")
            }
            // The cursor goes after what was just written, because that is where a person's cursor
            // would be and so where the next thing typed belongs
            moveCursor(target.node, wanted.length)
            val hidden = target.node.isPassword
            return UiTyping("typed", target.ui, emptyList(), if (hidden) "" else readBack(target.node, wanted), hidden, null)
        }

        /** Where typing goes: the cursor, when the field says where it is, otherwise the end */
        private fun insert(current: String, node: AccessibilityNodeInfo, input: String): String {
            val at = try {
                val start = node.textSelectionStart
                val end = node.textSelectionEnd
                if (start in 0..end && end <= current.length) end else current.length
            } catch (error: Throwable) {
                current.length
            }
            return current.substring(0, at) + input + current.substring(at)
        }

        /**
         * Put the cursor after the text
         *
         * A field may refuse this and none of them need it: the text is already in place, and a
         * cursor left where it was only decides where the next character goes
         */
        private fun moveCursor(node: AccessibilityNodeInfo, at: Int) {
            val request = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, at)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, at)
            }
            runCatching { node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, request) }
        }

        /** What the field says now, which is not always what was written into it */
        private fun readBack(node: AccessibilityNodeInfo, fallback: String): String = try {
            node.refresh()
            node.text?.toString() ?: fallback
        } catch (error: Throwable) {
            fallback
        }

        /** The window a caller means, preferring the one an app is actually showing there */
        private fun windowOn(displayId: Int): AccessibilityWindowInfo? {
            val all = try {
                instance?.windowsOnAllDisplays
            } catch (error: Throwable) {
                Log.w(TAG, "could not read the windows", error)
                null
            } ?: return null
            val onDisplay = (0 until all.size())
                .flatMap { all.valueAt(it) }
                .filter { it.displayId == displayId }
            return onDisplay.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
                ?: onDisplay.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?: onDisplay.firstOrNull()
        }

        /** One node, the record a caller reads, and the thing that takes a click for it */
        private class Found(
            val node: AccessibilityNodeInfo,
            val ui: UiNode,
            val target: AccessibilityNodeInfo?,
        )

        /** What a walk of one tree produced */
        private class Gathered(val list: List<Found>, val truncated: Boolean)

        private fun gather(root: AccessibilityNodeInfo): Gathered {
            val found = mutableListOf<Found>()
            var truncated = false

            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (found.size >= MAX_NODES || depth > MAX_DEPTH) {
                    truncated = true
                    return
                }
                val record = describe(node, depth)
                if (record != null) {
                    val target = clickTarget(node)
                    val targetBounds = target?.let { clickable ->
                        Rect().also { clickable.getBoundsInScreen(it) }
                    }
                    found += Found(node, record.copy(target = targetBounds), target)
                }
                val children = try {
                    node.childCount
                } catch (error: Throwable) {
                    return
                }
                for (index in 0 until children) {
                    val child = try {
                        node.getChild(index)
                    } catch (error: Throwable) {
                        null
                    } ?: continue
                    visit(child, depth + 1)
                }
            }

            visit(root, 0)
            return Gathered(found, truncated)
        }

        /** What one node is, or null when it is only a container with nothing to say */
        private fun describe(node: AccessibilityNodeInfo, depth: Int): UiNode? {
            val bounds = Rect()
            return try {
                node.getBoundsInScreen(bounds)
                val record = UiNode(
                    depth = depth,
                    className = node.className?.toString()?.substringAfterLast('.').orEmpty(),
                    text = node.text?.toString()?.trim().orEmpty(),
                    description = node.contentDescription?.toString()?.trim().orEmpty(),
                    viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty(),
                    clickable = node.isClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    checkable = node.isCheckable,
                    checked = node.isChecked,
                    bounds = Rect(bounds),
                    target = null,
                )
                val interesting = record.text.isNotEmpty() ||
                    record.description.isNotEmpty() ||
                    record.clickable ||
                    record.scrollable ||
                    record.editable ||
                    record.checkable
                if (interesting) record else null
            } catch (error: Throwable) {
                null
            }
        }

        /** The node itself when it takes a click, else the closest ancestor that does */
        private fun clickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps < MAX_CLICKABLE_STEPS) {
                if (current.isClickable) return current
                current = try {
                    current.parent
                } catch (error: Throwable) {
                    null
                }
                steps++
            }
            return null
        }

        /** The node itself when it takes a long click, else the closest ancestor that does */
        private fun longClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            var current: AccessibilityNodeInfo? = node
            var steps = 0
            while (current != null && steps < MAX_CLICKABLE_STEPS) {
                if (current.isLongClickable) return current
                current = try {
                    current.parent
                } catch (error: Throwable) {
                    null
                }
                steps++
            }
            return null
        }

        /** The nodes a name reaches, through successively looser readings of that name */
        private fun match(gathered: Gathered, name: String): List<Found> {
            val list = gathered.list
            val passes: List<(Found) -> Boolean> = listOf(
                { it.ui.text.equals(name, ignoreCase = true) },
                { it.ui.description.equals(name, ignoreCase = true) },
                { it.ui.text.contains(name, ignoreCase = true) },
                { it.ui.description.contains(name, ignoreCase = true) },
            )
            passes.forEach { pass ->
                val hits = list.filter(pass)
                if (hits.isNotEmpty()) return hits
            }
            return emptyList()
        }

        /** What makes two candidates the same press rather than two different ones */
        private fun keyOf(found: Found): String {
            val rect = found.ui.target ?: found.ui.bounds
            return "${found.target?.className}|${rect.left},${rect.top},${rect.right},${rect.bottom}"
        }

        /** Said the same way everywhere, because every caller runs into it */
        private const val NOT_ENABLED =
            "the accessibility service is off, so the device will not report what is on a screen"
    }
}
