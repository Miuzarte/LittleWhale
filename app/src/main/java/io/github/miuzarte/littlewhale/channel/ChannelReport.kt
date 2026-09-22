package io.github.miuzarte.littlewhale.channel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Shapes what the channel found into the JSON the dsh tools read
 *
 * The app side formats rather than the privileged side, so the privileged process only ever
 * answers narrow typed questions and every judgement about what a device is lives in one place
 * that can be tested without a device
 */
object ChannelReport {

    /** `UserInfo{999:XSpace:801010} running`, of which the id, the name and the state are kept */
    private val USER = Regex("""UserInfo\{(\d+):([^:]*):[0-9a-fA-F]+\}\s*(\S*)""")

    /** Just what the channel is, with the fields that are unknown left out rather than nulled */
    fun status(state: ChannelState): JsonObject = buildJsonObject {
        put("connected", state.connected)
        if (state.backend != null) put("backend", state.backend)
        if (state.uid != null) put("uid", state.uid)
        if (state.pid != null) put("pid", state.pid)
        if (state.version != null) put("version", state.version)
        if (state.error != null) put("error", state.error)
    }

    /** The channel plus what only the privileged uid could read */
    fun probe(probe: ChannelProbe): JsonObject = buildJsonObject {
        put("channel", status(probe.state))
        put("touch", touch(probe.touch))
        put("users", users(probe.users))
        val raw = probe.inputDevices
        if (raw == null) {
            putJsonObject("input") {
                put("available", false)
                put("reason", "the privileged process could not read them")
            }
            return@buildJsonObject
        }
        val devices = InputDevices.parse(raw)
        val touchscreen = InputDevices.touchscreen(raw)
        putJsonObject("input") {
            put("available", true)
            put("deviceCount", devices.size)
            put(
                "touchscreen",
                if (touchscreen == null) JsonNull else device(touchscreen, primary = true),
            )
            put("devices", buildJsonArray { devices.forEach { add(device(it, it == touchscreen)) } })
        }
    }

    /**
     * What the phone's own glass has seen lately
     *
     * This is a fact about the device rather than about any one screen, and it is the brake: every
     * event counted here came from a real finger, because an injected one never reaches the
     * kernel's input nodes. `available` is the part that matters - with no watch there is nothing
     * standing between the model and the phone in somebody's hand, so acting on it is refused
     */
    fun touch(state: TouchState?): JsonObject = buildJsonObject {
        put("available", state?.watching == true)
        put("userTouching", state?.driving() == true)
        put("down", state?.down ?: false)
        put("msSinceLastTouch", state?.msSinceLastTouch ?: -1)
        put("events", state?.events ?: 0)
        put("path", state?.path.orEmpty())
        put("error", state?.error.orEmpty())
    }

    /**
     * The Android users this device has
     *
     * A second user is what a cloned app is: the same package installed twice, with a user and a
     * set of data each, so this list is the whole of what tells them apart. Read out of the
     * platform's own line - `UserInfo{999:XSpace:801010} running` - and handed on as it is, because
     * what a user is *called* is the device's business rather than ours
     */
    private fun users(raw: String?): JsonArray = buildJsonArray {
        (raw ?: return@buildJsonArray).lineSequence().forEach { line ->
            val fields = USER.find(line)?.groupValues ?: return@forEach
            val id = fields[1].toIntOrNull() ?: return@forEach
            add(
                buildJsonObject {
                    put("id", id)
                    put("name", fields[2])
                    put("running", fields[3] == "running")
                },
            )
        }
    }

    /** One device, with the ranges as pairs because that is how a mapper consumes them */
    private fun device(device: InputDevice, primary: Boolean): JsonObject = buildJsonObject {
        put("path", device.path)
        put("name", device.name)
        putJsonArray("x") { add(JsonPrimitive(device.xMin)); add(JsonPrimitive(device.xMax)) }
        putJsonArray("y") { add(JsonPrimitive(device.yMin)); add(JsonPrimitive(device.yMax)) }
        put("protocolB", device.protocolB)
        put("direct", device.direct)
        put("touchKey", device.touchKey)
        if (primary) put("primary", true)
    }
}
