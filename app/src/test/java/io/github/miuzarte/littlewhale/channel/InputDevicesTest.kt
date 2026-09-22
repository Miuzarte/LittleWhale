package io.github.miuzarte.littlewhale.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser runs against output captured from the test device, because the format is what
 * toolbox's getevent prints and nothing else produces it
 *
 * What it has to get right is which node is the touchscreen: the same panel registers several,
 * and picking the wrong one means every coordinate is mapped against the wrong range without
 * anything failing loudly
 */
class InputDevicesTest {

    /** Written exactly as `getevent -p` wrote it, indentation and trailing spaces included */
    private val kalama = """
        add device 1: /dev/input/event9
          name:     "kalama-mtp-snd-card Button Jack"
          events:
            KEY (0001): 00e2  0101  0102  0103  0104  0105 
          input props:
            <none>
        add device 2: /dev/input/event7
          name:     "fts"
          events:
            KEY (0001): 0011  0012  0018  001a  001b  001f  0021  0026 
                        002c  002e  002f  0032  003b  003c  003d  003e 
                        003f  0067  0069  006a  006c  008f  0096  0145 
                        014a  0152  0162 
            ABS (0003): 002f  : value 0, min 0, max 9, fuzz 0, flat 0, resolution 0
                        0030  : value 0, min 0, max 10800, fuzz 0, flat 0, resolution 0
                        0031  : value 0, min 0, max 24000, fuzz 0, flat 0, resolution 0
                        0035  : value 0, min 0, max 10799, fuzz 0, flat 0, resolution 0
                        0036  : value 0, min 0, max 23999, fuzz 0, flat 0, resolution 0
                        0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
                        003b  : value 0, min 0, max 127, fuzz 0, flat 0, resolution 0
          input props:
            INPUT_PROP_DIRECT
        add device 3: /dev/input/event2
          name:     "qcom-hv-haptics"
          events:
            FF  (0015): 0050  0051  0052 
          input props:
            <none>
    """.trimIndent()

    @Test
    fun `finds the touchscreen and its ranges`() {
        val touch = InputDevices.touchscreen(kalama)
        assertEquals("/dev/input/event7", touch?.path)
        assertEquals("fts", touch?.name)
        assertEquals(0, touch?.xMin)
        assertEquals(10799, touch?.xMax)
        assertEquals(0, touch?.yMin)
        assertEquals(23999, touch?.yMax)
    }

    @Test
    fun `recognises what makes it the touchscreen`() {
        val touch = InputDevices.touchscreen(kalama)
        // Protocol B plus absolute coordinates, and BTN_TOUCH arriving through a KEY section
        // whose entries run over several lines
        assertTrue("protocol B", touch?.protocolB == true)
        assertTrue("INPUT_PROP_DIRECT", touch?.direct == true)
        assertTrue("BTN_TOUCH", touch?.touchKey == true)
    }

    @Test
    fun `parses the device count from the section headers`() {
        val devices = InputDevices.parse(kalama)
        // Only the devices that report coordinates, so the two button and haptics nodes are gone
        assertEquals(1, devices.size)
    }

    @Test
    fun `ignores a stylus, whose range is wider than the panel`() {
        val stylus = """
            add device 1: /dev/input/event4
              name:     "stylus"
              events:
                KEY (0001): 0140 
                ABS (0003): 0035  : value 0, min 0, max 20000, fuzz 0, flat 0, resolution 0
                            0036  : value 0, min 0, max 30000, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()
        assertNull(InputDevices.touchscreen(stylus))
    }

    @Test
    fun `prefers protocol B over a larger legacy node`() {
        val both = """
            add device 1: /dev/input/event5
              name:     "legacy panel"
              events:
                ABS (0003): 0000  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
                            0001  : value 0, min 0, max 32767, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
            add device 2: /dev/input/event6
              name:     "panel"
              events:
                ABS (0003): 0035  : value 0, min 0, max 1079, fuzz 0, flat 0, resolution 0
                            0036  : value 0, min 0, max 2399, fuzz 0, flat 0, resolution 0
                            0039  : value 0, min 0, max 65535, fuzz 0, flat 0, resolution 0
              input props:
                INPUT_PROP_DIRECT
        """.trimIndent()
        assertEquals("/dev/input/event6", InputDevices.touchscreen(both)?.path)
    }

    @Test
    fun `reports nothing when the output describes no panel`() {
        assertNull(InputDevices.touchscreen("add device 1: /dev/input/event1\n  name: \"power\"\n"))
        assertNull(InputDevices.touchscreen(""))
    }
}
