package io.github.miuzarte.littlewhale.channel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后处理是纯算术, 所以它在 JVM 上就能验: 造几张概率图, 看框落在哪
 *
 * 真机那一步只负责证明"图是真的", 分割对不对由这里管
 */
class OcrDetectTest {

    private val width = 64
    private val height = 64

    private fun canvas(fill: Float = 0.05f) = FloatArray(width * height) { fill }

    private fun paint(prob: FloatArray, left: Int, top: Int, right: Int, bottom: Int, value: Float) {
        for (y in top..bottom) {
            for (x in left..right) prob[y * width + x] = value
        }
    }

    private fun lines(prob: FloatArray) = OcrDetect.lines(prob, width, height)

    @Test
    fun `one bar becomes one box over it`() {
        val prob = canvas()
        paint(prob, 10, 10, 29, 15, 0.9f)
        val found = lines(prob)
        assertEquals(1, found.size)
        val box = found.first()
        assertEquals(20f, box.centerX, 1.5f)
        assertEquals(13f, box.centerY, 1.5f)
        assertEquals(0.9f, box.score, 0.01f)
    }

    @Test
    fun `two pieces of one line join`() {
        val prob = canvas()
        paint(prob, 10, 10, 19, 15, 0.9f)
        paint(prob, 22, 10, 31, 15, 0.9f)
        val found = lines(prob)
        assertEquals(1, found.size)
        assertEquals(21f, found.first().centerX, 1.5f)
    }

    @Test
    fun `two rows stay apart and come back top first`() {
        val prob = canvas()
        paint(prob, 10, 40, 19, 45, 0.9f)
        paint(prob, 10, 10, 19, 15, 0.9f)
        val found = lines(prob)
        assertEquals(2, found.size)
        assertTrue("the upper row must come first", found[0].centerY < found[1].centerY)
    }

    @Test
    fun `two far apart columns stay apart`() {
        val prob = canvas()
        paint(prob, 4, 10, 13, 15, 0.9f)
        paint(prob, 40, 10, 49, 15, 0.9f)
        val found = lines(prob)
        assertEquals(2, found.size)
        assertEquals(9f, found[0].centerX, 1.5f)
        assertEquals(45f, found[1].centerX, 1.5f)
    }

    @Test
    fun `a weak blob is dropped`() {
        val prob = canvas()
        paint(prob, 10, 10, 29, 15, 0.3f)
        assertEquals(0, lines(prob).size)
    }

    @Test
    fun `a sliver is dropped`() {
        val prob = canvas()
        paint(prob, 10, 10, 29, 11, 0.9f)
        assertEquals(0, lines(prob).size)
    }

    @Test
    fun `the box is unclipped past the ink`() {
        val prob = canvas()
        paint(prob, 20, 20, 39, 29, 0.9f)
        val box = lines(prob).first()
        // 20x10 的墨迹, unclip 1.4 之后每边扩 20*10*1.4/(2*30) = 4.667
        assertEquals(15.33f, box.left, 0.1f)
        assertEquals(44.67f, box.right, 0.1f)
        assertEquals(29.33f, box.width, 0.2f)
    }
}
