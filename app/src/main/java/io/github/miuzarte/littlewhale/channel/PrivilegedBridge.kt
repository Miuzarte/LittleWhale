package io.github.miuzarte.littlewhale.channel

import android.graphics.Rect
import android.util.Log
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/** What the host needs to reach the channel, handed to it through the environment */
data class ChannelEndpoint(val address: String, val token: String)

/**
 * The socket the dsh tools reach this process through
 *
 * The tools run in the Node host, a separate process with the same uid, so a loopback socket is
 * the shortest path between them: no service to bind, no binder to wrap, and the port is only
 * reachable from this device. It binds port 0, which makes the kernel pick an ephemeral port, and
 * reports that endpoint to the host along with a token - an app on the same device could open
 * this port, and the token is what it cannot guess
 *
 * The protocol is one request per connection: a line of JSON in, a line of JSON out, close. That
 * is all a tool call needs and it leaves no framing state to get wrong
 *
 * The privileged channel lives on this side rather than in the host because binders are an app
 * process capability: Node can hold a socket, it cannot hold an IBinder
 */
object PrivilegedBridge {

    private const val TAG = "LwBridge"

    /** One caller at a time is all this ever has, so the backlog only has to absorb a race */
    private const val BACKLOG = 4

    /** The address the host is told to connect to, spelled the way it will use it */
    private const val LOOPBACK = "127.0.0.1"

    /** A caller that connects and then goes silent must not hold the only accept loop */
    private const val REQUEST_TIMEOUT_MS = 120_000

    private var server: ServerSocket? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var current: ChannelEndpoint? = null

    /** Start listening, returning what the host has to be told, or null when it could not bind */
    fun start(): ChannelEndpoint? {
        current?.let { if (running) return it }
        val listener = try {
            // Loopback and an ephemeral port: nothing outside this device can reach it, and there
            // is no port number to keep in sync between the app and the host. The address is named
            // as an IPv4 literal on purpose - the loopback interface also answers as ::1, and the
            // host is told where to connect through a single string, so it has to be unambiguous
            ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))
        } catch (error: IOException) {
            Log.e(TAG, "could not open the channel socket", error)
            return null
        }
        val endpoint = ChannelEndpoint(
            address = "${listener.inetAddress.hostAddress}:${listener.localPort}",
            token = UUID.randomUUID().toString(),
        )
        server = listener
        current = endpoint
        running = true
        worker = Thread({ accept(listener) }, "lw-bridge").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "listening on ${endpoint.address}")
        return endpoint
    }

    /** Stop listening; the host's next tool call finds nothing and says so */
    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        worker = null
        current = null
    }

    /** One accept loop for the process's lifetime, one request at a time on it */
    private fun accept(listener: ServerSocket) {
        while (running) {
            val client = try {
                listener.accept()
            } catch (error: IOException) {
                // Closing the listener is how stop() ends this loop, so only a real failure is news
                if (running) Log.w(TAG, "accept failed", error)
                return
            }
            try {
                serve(client)
            } catch (error: Throwable) {
                Log.w(TAG, "a channel request failed", error)
            } finally {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = REQUEST_TIMEOUT_MS
        val request = client.getInputStream().bufferedReader().readLine() ?: return
        val response = respond(request)
        client.getOutputStream().write((response + "\n").toByteArray())
        client.getOutputStream().flush()
    }

    private fun respond(request: String): String {
        val parsed = try {
            Json.parseToJsonElement(request).jsonObject
        } catch (error: Throwable) {
            return failure("the request is not a JSON object: ${error.message}")
        }
        // Any app on the device could open this port, so the token is the guard rather than the
        // socket's location
        val presented = parsed["token"]?.jsonPrimitive?.contentOrNull
        if (presented == null || presented != current?.token) {
            Log.w(TAG, "refusing a request that did not present the channel token")
            return failure("the request did not present the channel token")
        }
        val method = parsed["method"]?.jsonPrimitive?.contentOrNull
            ?: return failure("the request names no method")
        return try {
            buildJsonObject {
                put("ok", true)
                put("result", dispatch(method, parsed))
            }.toString()
        } catch (error: Throwable) {
            Log.w(TAG, "the $method request failed", error)
            failure(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Everything a tool can ask for
     *
     * The request object travels along because a screen is made by its caller: the name it is to
     * be shown under, and the size it is to have, are both decided out there rather than here
     */
    private fun dispatch(method: String, request: JsonObject) = when (method) {
        "ping" -> buildJsonObject { put("pong", true) }
        "status" -> ChannelReport.status(PrivilegedChannel.state())
        "connect" -> {
            PrivilegedChannel.ensure()
            ChannelReport.status(PrivilegedChannel.state())
        }

        "probe" -> ChannelReport.probe(PrivilegedChannel.probe())

        // What one screen says about itself: the text on it, and where that text is in the
        // screen's own pixels. Read here in the app's own process rather than over the channel,
        // because the accessibility service belongs to this app
        "ui" -> {
            val screen = namedScreen(request)
            val tree = LwAccessibility.tree(screen.displayId)
            buildJsonObject {
                put("enabled", LwAccessibility.running)
                put("displayId", screen.displayId)
                put("label", screen.label)
                put("width", screen.width)
                put("height", screen.height)
                put("package", tree.packageName)
                put("truncated", tree.truncated)
                put("error", tree.error.orEmpty())
                put("nodes", buildJsonArray { tree.nodes.forEach { add(nodeJson(it)) } })
            }
        }

        // Press the thing a screen calls by that name, with no coordinate spent on it
        "tapText" -> {
            val screen = namedScreen(request)
            // 暂停要在这里也拦一次: 按名字走的是无障碍动作, 不经过 VirtualScreen 那条手势队列
            VirtualScreen.requireAcceptsControl(screen)
            // 主屏的刹车同理: 无障碍点按也是动手, 而且它连坐标都不需要
            VirtualScreen.requireUserNotDriving(screen)
            val name = request["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("tapText has to name the text it means")
            val holdMs = hold(request)
            val hit = LwAccessibility.tap(screen.displayId, name, holdMs)
            var via = hit.via
            var clicked = hit.outcome == "clicked"
            if (hit.outcome == "unclicked") {
                // A view that draws itself may take a touch but not an accessibility action. The
                // tree already said where the thing is, so the finger is the fallback rather than
                // a coordinate the caller had to guess
                val target = hit.target
                if (target != null) {
                    VirtualScreen.tap(screen, target.exactCenterX(), target.exactCenterY(), holdMs)
                    clicked = true
                    via = "finger"
                }
            }
            buildJsonObject {
                put("outcome", hit.outcome)
                put("clicked", clicked)
                put("via", via)
                put("displayId", screen.displayId)
                put("holdMs", holdMs)
                put("long", holdMs >= LwServiceProtocol.LONG_PRESS_MS)
                put("error", hit.error.orEmpty())
                put("matches", buildJsonArray { hit.matches.forEach { add(nodeJson(it)) } })
            }
        }

        // A screen is made here rather than in the app's UI, so a tool says what it wants: a name
        // to be known by, and a size when this device's own is not the right shape
        "create" -> {
            val screen = VirtualScreen.create(
                name = request["name"]?.jsonPrimitive?.contentOrNull,
                width = request["width"]?.jsonPrimitive?.intOrNull ?: 0,
                height = request["height"]?.jsonPrimitive?.intOrNull ?: 0,
                dpi = request["dpi"]?.jsonPrimitive?.intOrNull ?: 0,
            )
            buildJsonObject {
                put("created", screen != null)
                put("displayId", screen?.displayId ?: -1)
                put("label", screen?.label ?: "")
                put("error", VirtualScreen.lastError ?: "")
            }
        }

        // The screens' own state and a picture of one, which is the pair a tool needs to see what
        // it is about to touch. The phone's own screen is listed beside them rather than among
        // them: it can be named by every call, but it is nobody's to give back
        "screen" -> buildJsonObject {
            val primary = VirtualScreen.mainScreen()
            put("count", VirtualScreen.screens.size)
            put("selected", VirtualScreen.selected?.displayId ?: -1)
            putJsonObject("primary") {
                put("displayId", primary.displayId)
                put("label", primary.label)
                put("width", primary.width)
                put("height", primary.height)
                put("dpi", primary.dpi)
            }
            // 主屏操作唯一的刹车, 让模型自己也能先看一眼再决定要不要动手
            put("touch", ChannelReport.touch(VirtualScreen.userTouch()))
            put(
                "screens",
                buildJsonArray {
                    VirtualScreen.screens.forEach { screen ->
                        add(
                            buildJsonObject {
                                put("displayId", screen.displayId)
                                put("label", screen.label)
                                put("width", screen.width)
                                put("height", screen.height)
                                put("dpi", screen.dpi)
                                // 用户手里的刹车: 停着的屏一律拒绝动手的调用, 但看还是看得见
                                put("acceptsControl", screen.acceptsControl)
                            },
                        )
                    }
                },
            )
            put("error", VirtualScreen.lastError ?: "")
        }

        // Give one screen back, by id and only by id
        //
        // It deliberately does not fall back to the selected screen: which one is selected is the
        // user's to change at any moment, so a caller that named nothing could close a screen it
        // never meant to touch - the one the user had just switched to
        "release" -> {
            val displayId = request["displayId"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("release has to name the displayId it means")
            if (displayId == LwServiceProtocol.MAIN_DISPLAY) {
                // 主屏不在这张表里, 走下面的查找只会得到一句"没有这个 id", 而这里能说清为什么
                buildJsonObject {
                    put("released", false)
                    put("displayId", displayId)
                    put(
                        "error",
                        "displayId $displayId is the phone's own screen rather than a virtual one:" +
                            " there is nothing here to release, and it cannot be closed",
                    )
                }
            } else {
                val screen = VirtualScreen.screens.firstOrNull { it.displayId == displayId }
                if (screen != null) {
                    VirtualScreen.release(screen)
                } else {
                    Log.w(TAG, "asked to release display $displayId, which is not one of ours")
                }
                buildJsonObject {
                    put("released", screen != null)
                    put("displayId", displayId)
                    put("error", if (screen == null) VirtualScreen.missingScreenReason(displayId) else "")
                }
            }
        }

        // One key, named the way Android names keys rather than numbered by whoever asks
        //
        // Some buttons are the platform's rather than any app's - BACK, HOME, the volume keys - so
        // they are on no tree and at no coordinate, and `input keyevent` is refused for this app's
        // uid exactly like `am` is. That leaves this, and it is why the key is checked against the
        // platform's own list here rather than taken on trust from the caller
        "key" -> {
            val screen = namedScreen(request)
            val keyCode = namedKey(request)
            val holdMs = hold(request)
            VirtualScreen.key(screen, keyCode, holdMs)
            buildJsonObject {
                put("pressed", true)
                put("key", KeyCodes.nameOf(keyCode))
                put("code", keyCode)
                put("holdMs", holdMs)
                put("longPress", holdMs >= LwServiceProtocol.LONG_PRESS_MS)
                put("displayId", screen.displayId)
                put("label", screen.label)
            }
        }

        // What a screen is to be made to say, into the field it is showing
        //
        // The field is written through the platform's own action where the screen reports one,
        // which is what makes Chinese and emoji work and what makes an IME irrelevant; key presses
        // are what is left when it reports none. Which of the two happened is in the answer, because
        // a caller reading its own text back has to know whether it landed in a field or went
        // wherever focus happened to be
        "type" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            VirtualScreen.requireUserNotDriving(screen)
            val text = request["text"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("this call has to say what to type")
            if (text.length > MAX_TYPED_CHARS) {
                throw IllegalArgumentException(
                    "that is ${text.length} characters, and this call takes at most $MAX_TYPED_CHARS",
                )
            }
            val replace = request["replace"]?.jsonPrimitive?.booleanOrNull == true
            val field = LwAccessibility.type(screen.displayId, text, replace)
            when (field.outcome) {
                "typed" -> typingJson(screen, "field", text.length, field.text, field.password, field.field)

                // Two fields and no focus is the one case where nothing sensible can be chosen, and
                // it is the caller that knows which one it meant
                "ambiguous" -> throw IllegalStateException(
                    "displayId ${screen.displayId} (\"${screen.label}\") shows more than one text" +
                        " field and none of them has focus, so nothing was typed - tap the one you" +
                        " mean with lw_tap and call this again, or tap it and type into it: " +
                        field.matches.joinToString("; ") { node -> fieldSentence(node) },
                )

                "failed" -> throw IllegalStateException(
                    "the field on displayId ${screen.displayId} (\"${screen.label}\") did not take" +
                        " the text: ${field.error ?: "no reason given"}",
                )

                else -> {
                    // Nothing to set: the text goes in as keys, which is all such a screen can be
                    // given, and the keyboard decides whether it can produce it at all
                    if (replace) {
                        throw IllegalStateException(
                            "displayId ${screen.displayId} (\"${screen.label}\") reports no text" +
                                " field, so there is nothing to replace - type into it as keys, or" +
                                " tap the field you meant first (${field.error ?: "no field found"})",
                        )
                    }
                    val typed = VirtualScreen.text(screen, text)
                    if (typed < 0) {
                        throw IllegalStateException(
                            "displayId ${screen.displayId} (\"${screen.label}\") reports no text" +
                                " field, and this device's keyboard cannot produce \"$text\" - it" +
                                " types what a virtual keyboard has, which is letters, digits and" +
                                " its punctuation and no Chinese",
                        )
                    }
                    typingJson(screen, "keys", typed, "", false, null)
                }
            }
        }

        // Another shape for a screen that already exists
        //
        // The shape of a display is what an app lays itself out for, so this is what puts a
        // landscape-only app on a screen the size it wants; the swap flag is the same change asked
        // for as a quarter turn, which is how a caller thinks about it and saves it from having to
        // read the current size back first
        "resize" -> {
            val displayId = request["displayId"]?.jsonPrimitive?.intOrNull
                ?: throw IllegalArgumentException("resize has to name the displayId it means")
            if (displayId == LwServiceProtocol.MAIN_DISPLAY) {
                // 主屏不是我们的屏: 它是用户手里那块, 尺寸跟着他的设备与旋转走
                buildJsonObject {
                    put("resized", false)
                    put("displayId", displayId)
                    put(
                        "error",
                        "displayId $displayId is the phone's own screen rather than a virtual one:" +
                            " its size belongs to the device and to how the user is holding it, so" +
                            " there is nothing here to resize",
                    )
                }
            } else {
                val screen = namedScreen(request)
                val swap = request["swap"]?.jsonPrimitive?.booleanOrNull ?: false
                val resized = VirtualScreen.resize(
                    screen = screen,
                    width = request["width"]?.jsonPrimitive?.intOrNull ?: 0,
                    height = request["height"]?.jsonPrimitive?.intOrNull ?: 0,
                    dpi = request["dpi"]?.jsonPrimitive?.intOrNull ?: 0,
                    swap = swap,
                )
                buildJsonObject {
                    put("resized", resized != null)
                    put("displayId", displayId)
                    put("label", screen.label)
                    put("swapped", swap)
                    put("width", resized?.width ?: screen.width)
                    put("height", resized?.height ?: screen.height)
                    put("dpi", resized?.dpi ?: screen.dpi)
                    put("error", if (resized == null) VirtualScreen.lastError.orEmpty() else "")
                }
            }
        }

        // Which apps can be started. The two halves come from the two sides that can answer them:
        // what a person can launch is the app's own question (the launcher intent, made visible by
        // the manifest's <queries> declaration), and which of those exist for a user is the
        // privileged side's, since a cloned app is only a package in the other user's list
        //
        // The app's own list is already its own user's, so that user needs no listing; for any other
        // user the launcher's apps are the ones this app can name, and a label is the same string in
        // either copy - the clone is the same APK - so the names found here serve both
        "apps" -> {
            val userId = (request["user"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val launchable = LwApps.launchable()
            val listing = if (userId == LwApps.ownUser) {
                null
            } else {
                val service = PrivilegedChannel.ensure()
                    ?: throw IllegalStateException(
                        PrivilegedChannel.state().error
                            ?: "the privileged channel is not available",
                    )
                service.packages(userId)
            }
            val installed = listing?.let { installedPackages(it) }
            // "this user has none of them" and "nobody could tell us" look the same in the list, so
            // an empty answer with something to say about it is reported as the failure it is
            val unreadable = listing != null && installed?.isEmpty() == true && listing.isNotBlank()
            val apps = installed?.let { names -> launchable.filter { it.packageName in names } }
                ?: launchable
            val query = request["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val matching = if (query.isEmpty()) apps else apps.filter { app ->
                app.label.contains(query, ignoreCase = true) ||
                    app.packageName.contains(query, ignoreCase = true)
            }
            buildJsonObject {
                put("user", userId)
                put("launchable", apps.size)
                put("matched", matching.size)
                put("query", query)
                put("truncated", matching.size > MAX_APPS)
                putJsonArray("apps") {
                    matching.take(MAX_APPS).forEach { app ->
                        add(
                            buildJsonObject {
                                put("package", app.packageName)
                                put("label", app.label)
                                put("component", app.component)
                            },
                        )
                    }
                }
                put(
                    "error",
                    when {
                        launchable.isEmpty() -> NO_LAUNCHER_APPS
                        unreadable -> "the privileged side could not list user $userId's packages: " +
                            listing.orEmpty().lineSequence()
                                .firstOrNull { it.isNotBlank() }
                                .orEmpty()

                        else -> ""
                    },
                )
            }
        }

        // Start an app onto one screen
        //
        // Nothing here can do this from the app's side: `am` calls itself the shell's package and
        // is refused an app uid, so the one place it works is the privileged process - which is
        // also why this is a transaction rather than a shell command a caller could run
        "launch" -> {
            val screen = namedScreen(request)
            VirtualScreen.requireAcceptsControl(screen)
            VirtualScreen.requireUserNotDriving(screen)
            val asked = request["package"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val component = request["component"]?.jsonPrimitive?.contentOrNull.orEmpty()
            // 手里往往只有一个名字 ("设置" / "哔哩哔哩"), 而 am 要的是包名: 能唯一对上就用它, 一个都
            // 对不上就按原样交下去 (那是调用方自己知道的包名), 对上不止一个就什么都不起
            val packageName = if (component.isBlank()) resolvePackage(asked) else asked
            // 双开的那份与原版同名同组件, 只有 userId 不同, 所以"起哪一个"就是这一个数字
            val userId = (request["user"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val timeoutMs = (request["timeoutMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_LAUNCH_MS)
                .coerceIn(MIN_LAUNCH_MS, MAX_LAUNCH_MS)
            val service = PrivilegedChannel.ensure()
                ?: throw IllegalStateException(
                    PrivilegedChannel.state().error ?: "the privileged channel is not available",
                )
            val (code, output) = service.launch(
                displayId = screen.displayId,
                userId = userId,
                packageName = packageName,
                component = component,
                timeoutMs = timeoutMs,
            )
            buildJsonObject {
                put("displayId", screen.displayId)
                put("label", screen.label)
                put("package", packageName)
                put("asked", if (packageName == asked) "" else asked)
                put("component", component)
                put("user", userId)
                put("code", code)
                put("started", code == 0 && output.contains(STARTED_MARK))
                put("output", output.trim())
            }
        }

        // A picture of one screen, named for the same reason every other gesture here names its
        // screen: only the caller knows which one it is about to look at
        "screenshot" -> {
            val screen = namedScreen(request)
            // 预算由设置页那两档给, 调用方说了就用调用方的: 图必须在模型那条路由的预算之内, 超了
            // 要在 host 那边重编码, 而设备上没有编码器
            val maxPixels = request["maxPixels"]?.jsonPrimitive?.intOrNull ?: ScreenshotBudget.pixels
            val maxBytes = request["maxBytes"]?.jsonPrimitive?.intOrNull ?: ScreenshotBudget.bytes
            val shot = VirtualScreen.screenshot(screen, maxPixels, maxBytes)
            buildJsonObject {
                put("displayId", screen.displayId)
                put("label", screen.label)
                put("width", screen.width)
                put("height", screen.height)
                put("path", shot?.file?.absolutePath ?: "")
                put("bytes", shot?.file?.length() ?: 0L)
                put("picture", buildJsonObject {
                    put("width", shot?.width ?: 0)
                    put("height", shot?.height ?: 0)
                    put("scale", shot?.scale ?: 0f)
                })
                put("error", VirtualScreen.lastError ?: "")
            }
        }

        // Touch, aimed at the screen the request names rather than at whatever the user happens to
        // be looking at; the answer says what was delivered, since the tool that asked cannot see
        // the screen for itself
        "tap" -> {
            val screen = namedScreen(request)
            val x = number(request, "x")
            val y = number(request, "y")
            val holdMs = hold(request)
            VirtualScreen.tap(screen, x, y, holdMs)
            buildJsonObject {
                put("tapped", true)
                put("displayId", screen.displayId)
                put("screen", VirtualScreen.displayPower(screen.displayId))
                put("x", x)
                put("y", y)
                put("holdMs", holdMs)
                put("longPress", holdMs >= LwServiceProtocol.LONG_PRESS_MS)
            }
        }

        "swipe" -> {
            val screen = namedScreen(request)
            val fromX = number(request, "fromX")
            val fromY = number(request, "fromY")
            val toX = number(request, "toX")
            val toY = number(request, "toY")
            val durationMs = request["durationMs"]?.jsonPrimitive?.longOrNull ?: DEFAULT_SWIPE_MS
            VirtualScreen.swipe(screen, fromX, fromY, toX, toY, durationMs)
            buildJsonObject {
                put("swiped", true)
                put("displayId", screen.displayId)
                put("durationMs", durationMs)
            }
        }

        // 端侧 OCR: 现在只到"加载 + 跑一遍看耗时", 认字那条路还没接。它读的是 assets 里的模型,
        // 与特权进程无关, 挂在这条桥上只是因为这里是 host 唯一能说话的地方
        // 端侧 OCR: 认这块屏上写着什么。与 `screenshot` 一样属于"看", 所以不碰主屏那道触摸刹车,
        // 也不用管虚拟屏那两道闸 —— 它不改变任何东西
        "ocr" -> {
            val screen = namedScreen(request)
            val maxLines = request["maxLines"]?.jsonPrimitive?.intOrNull ?: 0
            val captured = System.nanoTime()
            val bitmap = VirtualScreen.capture(screen)
            val captureMs = (System.nanoTime() - captured) / 1_000_000
            if (bitmap == null) {
                buildJsonObject {
                    put("displayId", screen.displayId)
                    put("lines", buildJsonArray {})
                    put("error", VirtualScreen.lastError ?: "capture failed")
                }
            } else {
                try {
                    val minScore = request["minScore"]?.jsonPrimitive?.floatOrNull ?: Float.NaN
                    val outcome = if (minScore.isNaN()) {
                        if (maxLines > 0) {
                            LwOcr.recognize(bitmap, maxLines, captureMs = captureMs)
                        } else {
                            LwOcr.recognize(bitmap, captureMs = captureMs)
                        }
                    } else {
                        LwOcr.recognize(bitmap, maxLines.coerceAtLeast(1), minScore, captureMs)
                    }
                    buildJsonObject {
                        put("displayId", screen.displayId)
                        put("label", screen.label)
                        put("screen", VirtualScreen.displayPower(screen.displayId))
                        put("width", bitmap.width)
                        put("height", bitmap.height)
                        put("backend", outcome.backend)
                        put("captureMs", captureMs)
                        put("detMs", outcome.detMs)
                        put("recMs", outcome.recMs)
                        put("boxes", LwOcr.lastBoxes)
                        put("error", outcome.error ?: "")
                        put("lines", buildJsonArray {
                            for (line in outcome.lines) {
                                add(buildJsonObject {
                                    put("text", line.text)
                                    put("score", line.score.toDouble())
                                    put("box", buildJsonArray {
                                        add(line.left)
                                        add(line.top)
                                        add(line.right)
                                        add(line.bottom)
                                    })
                                    put("center", buildJsonArray {
                                        add(line.centerX)
                                        add(line.centerY)
                                    })
                                })
                            }
                        })
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }

        // 同一条路的诊断版: 拿合成输入跑两个模型, 报计算单元、耗时, 以及 NPU 与 CPU 的输出差
        "ocrProbe" -> LwOcr.probe(
            rounds = request["rounds"]?.jsonPrimitive?.intOrNull ?: 3,
            force = request["force"]?.jsonPrimitive?.contentOrNull,
            compare = request["compare"]?.jsonPrimitive?.booleanOrNull ?: false,
            perf = request["perf"]?.jsonPrimitive?.contentOrNull ?: "burst",
        )

        else -> throw IllegalArgumentException("unknown method $method")
    }

    /**
     * The screen one request names
     *
     * A request that names no screen is refused rather than aimed at the selection, because every
     * call here changes something on a screen and the selection is the user's to change. An id
     * that points at nothing gets the reason the app remembers, so a screen the user closed from
     * the phone reads as the user's decision rather than as a bad id
     *
     * Display zero is the phone's own screen, which is the one display that is always there: it is
     * resolved out of the display manager rather than looked for in a list of ours, because it is
     * not ours
     */
    private fun namedScreen(request: JsonObject): ScreenState {
        val displayId = request["displayId"]?.jsonPrimitive?.intOrNull
            ?: throw IllegalArgumentException("this call has to name the displayId it means")
        if (displayId == LwServiceProtocol.MAIN_DISPLAY) return VirtualScreen.mainScreen()
        return VirtualScreen.screens.firstOrNull { it.displayId == displayId }
            ?: throw IllegalArgumentException(VirtualScreen.missingScreenReason(displayId))
    }

    /**
     * The key one request names
     *
     * A name and a number are both accepted, because a caller may have either: the name is the
     * readable one, and the one whose mistakes can be refused instead of acted on, while the number
     * is what an Android reference is full of. What is not accepted is a name Android does not have
     * or a number it does not define, and the refusal says what to use instead - keycode 5 is a
     * call and 26 is the power key, so an unchecked number is a wrong action rather than an error
     */
    private fun namedKey(request: JsonObject): Int {
        val name = request["key"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val code = request["code"]?.jsonPrimitive?.intOrNull
        if (name.isNotEmpty() && code != null) {
            throw IllegalArgumentException("this call names the key either by key or by code, not both")
        }
        if (name.isEmpty() && code == null) {
            throw IllegalArgumentException(
                "this call has to name the key it means, by name (\"BACK\") or by code (4)",
            )
        }
        if (code != null) {
            if (!KeyCodes.known(code)) {
                throw IllegalArgumentException("this platform has no key with code $code")
            }
            return code
        }
        return KeyCodes.resolve(name) ?: throw IllegalArgumentException(
            "Android has no key called \"$name\" - name it the way Android does, for example" +
                " ${KeyCodes.COMMON.joinToString(", ")}",
        )
    }

    /** One coordinate out of a request */
    private fun number(request: JsonObject, key: String): Float =
        request[key]?.jsonPrimitive?.floatOrNull
            ?: throw IllegalArgumentException("$key has to be a number")

    /**
     * The package names in what `pm list packages` printed
     *
     * One `package:<name>` line per package, which is the command's own shape; anything else it
     * printed (an error, a warning) is not a package and is dropped rather than mistaken for one
     */
    private fun installedPackages(listing: String): Set<String> = listing.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(PACKAGE_MARK) }
        .map { it.removePrefix(PACKAGE_MARK).trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    /**
     * The package a name means, when that name is not a package
     *
     * A caller usually has the name a person uses rather than the package, so the same name
     * matching is done here as pressing by name does: an exact package first, then an exact label,
     * then a label or package containing it. One match is used, none is handed back unchanged (it
     * may be a package no launcher icon points at), and more than one is refused with the choices,
     * because starting the wrong app is worse than not starting one
     */
    private fun resolvePackage(asked: String): String {
        val name = asked.trim()
        if (name.isEmpty()) return asked
        val matches = try {
            LwApps.resolve(name)
        } catch (problem: Throwable) {
            Log.w(TAG, "could not look up \"$name\" among the launcher's apps", problem)
            return asked
        }
        return when (matches.size) {
            0 -> asked
            1 -> matches.first().packageName
            else -> throw IllegalArgumentException(
                "\"$name\" reaches ${matches.size} apps, so nothing was started: " +
                    matches.take(MAX_CANDIDATES).joinToString(", ") {
                        "${it.label} (${it.packageName})"
                    } + " - name the one you mean by package",
            )
        }
    }

    /**
     * How long a press is to be held, which the caller may leave out
     *
     * Clamped here as well as in the tool, because this side does not know who is asking: the
     * gesture queue is blocked for the whole of a hold, so a hold nobody meant is a call that looks
     * like it hung
     */
    private fun hold(request: JsonObject): Long =
        (request["holdMs"]?.jsonPrimitive?.longOrNull ?: 0L)
            .coerceIn(0L, LwServiceProtocol.MAX_HOLD_MS)

    /**
     * What typing did, in the shape a tool reads it
     *
     * @param via `field` when the platform wrote the text into a field, `keys` when it went in as
     *   key presses because the screen reported no field
     * @param written how many characters went in, which is the caller's own text when a field took
     *   it and what the keyboard could produce when keys did
     * @param reading what the field says afterwards, empty when there was none or it hides it
     */
    private fun typingJson(
        screen: ScreenState,
        via: String,
        written: Int,
        reading: String,
        password: Boolean,
        field: UiNode?,
    ) = buildJsonObject {
        put("displayId", screen.displayId)
        put("label", screen.label)
        put("via", via)
        put("written", written)
        put("text", reading)
        put("password", password)
        put("field", buildJsonObject {
            if (field == null) return@buildJsonObject
            put("className", field.className)
            put("text", field.text)
            put("description", field.description)
            put("viewId", field.viewId)
            put("bounds", rectJson(field.bounds))
        })
    }

    /** One field, said the way a caller can find it again on the screen */
    private fun fieldSentence(node: UiNode): String {
        val said = node.text.ifEmpty { node.description }
        val name = if (said.isEmpty()) node.className else "${node.className} \"$said\""
        return "$name at [${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}]"
    }

    /** One node of a screen, in the shape a tool reads it */
    private fun nodeJson(node: UiNode) = buildJsonObject {
        put("className", node.className)
        put("text", node.text)
        put("description", node.description)
        put("viewId", node.viewId)
        put("depth", node.depth)
        put("clickable", node.clickable)
        put("scrollable", node.scrollable)
        put("editable", node.editable)
        put("checkable", node.checkable)
        put("checked", node.checked)
        put("bounds", rectJson(node.bounds))
        node.target?.let { put("target", rectJson(it)) }
    }

    /** A rectangle as four numbers, in the order a caller reads them: left, top, right, bottom */
    private fun rectJson(rect: Rect) = buildJsonArray {
        add(rect.left)
        add(rect.top)
        add(rect.right)
        add(rect.bottom)
    }

    /** How long a swipe takes when the caller does not say, which is a deliberate drag */
    private const val DEFAULT_SWIPE_MS = 300L

    /** More text than this in one call is a caller that has lost track, and one parcel to prove it */
    private const val MAX_TYPED_CHARS = 4_096

    /** What `am start -W` prints when the activity did come up, which is am's own verdict */
    private const val STARTED_MARK = "Status: ok"

    /** A cold start on a busy device is slow, but it is not this slow */
    private const val DEFAULT_LAUNCH_MS = 20_000L
    private const val MIN_LAUNCH_MS = 1_000L
    private const val MAX_LAUNCH_MS = 60_000L

    /** More apps than this in one answer is a listing nobody reads, so it is cut and said so */
    private const val MAX_APPS = 400

    /** How many of an ambiguous name's candidates are named back, matching pressing by name */
    private const val MAX_CANDIDATES = 12

    /** One line of `pm list packages` looks like this, and nothing else it prints does */
    private const val PACKAGE_MARK = "package:"

    /** Said when the launcher query came back with nothing at all, which means it could not be asked */
    private const val NO_LAUNCHER_APPS =
        "the launcher query came back empty, which means this app could not ask the package" +
            " manager rather than that the device has nothing to start"

    private fun failure(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()
}
