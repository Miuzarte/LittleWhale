package io.github.miuzarte.littlewhale.channel

/**
 * One input device as `getevent -p` describes it
 *
 * Only the fields that matter for turning a coordinate on the preview into a coordinate the
 * kernel will accept are kept: the ranges, and the structural hints that say which of several
 * nodes on the same panel is the real touchscreen
 */
data class InputDevice(
    val path: String,
    val name: String,
    val xMin: Int,
    val xMax: Int,
    val yMin: Int,
    val yMax: Int,
    /** Position plus tracking id, i.e. multitouch protocol B, which the primary panel speaks */
    val protocolB: Boolean,
    /** The panel reports absolute coordinates, i.e. it is a touchscreen and not a mouse or pad */
    val direct: Boolean,
    /** BTN_TOUCH, which accessory nodes under the same panel tend to lack */
    val touchKey: Boolean,
) {
    val multiTouch: Boolean get() = protocolB

    val width: Long get() = (xMax - xMin).toLong()

    val height: Long get() = (yMax - yMin).toLong()
}

/**
 * Reads the devices out of `getevent -p`
 *
 * The app cannot read `/dev/input` and the privileged shell domain is refused by SELinux too, so
 * this text is the only inexpensive way to learn the touchscreen's ranges without an ioctl
 * through JNI. Which node is the touchscreen is decided by structure rather than by name: the
 * same panel often registers several nodes, and only the real one carries protocol B, absolute
 * coordinates and BTN_TOUCH
 */
object InputDevices {

    /** ABS codes this cares about, from input-event-codes.h */
    private const val ABS_X = 0x00
    private const val ABS_Y = 0x01
    private const val ABS_MT_POSITION_X = 0x35
    private const val ABS_MT_POSITION_Y = 0x36
    private const val ABS_MT_TRACKING_ID = 0x39

    /** A stylus reports a larger range than the panel, so it has to be ruled out first */
    private const val BTN_TOOL_PEN = 0x140
    private const val BTN_TOUCH = 0x14a

    private val DEVICE = Regex("""^add device \d+:\s*(\S+)""")
    private val NAME = Regex("""^\s*name:\s*"(.*)"\s*$""")
    private val SECTION = Regex("""^\s*([A-Z]{2,3})\s*\(\d{4}\):(.*)$""")
    private val ABS_ENTRY = Regex("""([0-9a-fA-F]{4})\s*:\s*value\s+-?\d+,\s*min\s+(-?\d+),\s*max\s+(-?\d+)""")
    private val HEX = Regex("""\b[0-9a-fA-F]{4}\b""")

    private const val SECTION_ABS = "ABS"
    private const val SECTION_KEY = "KEY"
    private const val SECTION_PROPS = "PROPS"

    /** Every device the output describes, whether or not it is a touchscreen */
    fun parse(output: String): List<InputDevice> {
        val devices = mutableListOf<InputDevice>()
        var path: String? = null
        var name = ""
        var section: String? = null
        val abs = mutableMapOf<Int, IntArray>()
        val keys = mutableSetOf<Int>()
        var direct = false

        fun flush() {
            val current = path ?: return
            build(current, name, abs, keys, direct)?.let(devices::add)
            abs.clear()
            keys.clear()
            direct = false
        }

        for (line in output.lineSequence()) {
            val header = DEVICE.find(line.trim())
            if (header != null) {
                flush()
                path = header.groupValues[1]
                name = ""
                section = null
                continue
            }
            if (path == null) continue

            val nameMatch = NAME.find(line)
            if (nameMatch != null) {
                name = nameMatch.groupValues[1]
                section = null
                continue
            }

            val trimmed = line.trim()
            if (trimmed.startsWith("events:")) {
                section = null
                continue
            }
            if (trimmed.startsWith("input props:")) {
                section = SECTION_PROPS
                continue
            }

            // A section header carries its first entry, later entries are indented lines
            val match = SECTION.find(line)
            val content = if (match != null) {
                section = match.groupValues[1]
                match.groupValues[2]
            } else {
                line
            }

            when (section) {
                SECTION_ABS -> ABS_ENTRY.find(content)?.let {
                    abs[it.groupValues[1].toInt(16)] =
                        intArrayOf(it.groupValues[2].toInt(), it.groupValues[3].toInt())
                }

                SECTION_KEY -> HEX.findAll(content).forEach { keys += it.value.toInt(16) }

                SECTION_PROPS -> if (content.contains("INPUT_PROP_DIRECT")) direct = true
            }
        }
        flush()
        return devices
    }

    /**
     * The touchscreen, or null when the output describes none
     *
     * Ranked by how unmistakably each device is one, and only then by size, because a fingerprint
     * node under the same panel reports the same range. The comparator runs worst to best, which
     * is what `maxWithOrNull` picks from
     */
    fun touchscreen(output: String): InputDevice? = parse(output).maxWithOrNull(RANKING)

    private val RANKING: Comparator<InputDevice> = compareBy<InputDevice> { it.protocolB }
        .thenBy { it.direct }
        .thenBy { it.touchKey }
        .thenBy { it.width * it.height }

    /** Turn one parsed block into a device, or null when it is not a coordinate reporting one */
    private fun build(
        path: String,
        name: String,
        abs: Map<Int, IntArray>,
        keys: Set<Int>,
        direct: Boolean,
    ): InputDevice? {
        if (BTN_TOOL_PEN in keys) return null
        val x = abs[ABS_MT_POSITION_X] ?: abs[ABS_X] ?: return null
        val y = abs[ABS_MT_POSITION_Y] ?: abs[ABS_Y] ?: return null
        val multiTouch = ABS_MT_POSITION_X in abs && ABS_MT_POSITION_Y in abs
        return InputDevice(            path = path,
            name = name,
            xMin = x[0],
            xMax = x[1],
            yMin = y[0],
            yMax = y[1],
            protocolB = multiTouch && ABS_MT_TRACKING_ID in abs,
            direct = direct,
            touchKey = BTN_TOUCH in keys,
        )
    }
}
