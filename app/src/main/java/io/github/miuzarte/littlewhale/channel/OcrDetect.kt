package io.github.miuzarte.littlewhale.channel

/** 一块文本行, 坐标是**它被量出来时的那个坐标系**里的 (像素图坐标或屏坐标, 由调用方决定) */
data class OcrBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

/**
 * DB 的概率图 → 文本行框
 *
 * 三件事, 与 PaddleOCR 的 `DBPostProcess` 同一套思路, 但只做**轴对齐**矩形:
 *
 * 1. 按阈值二值化, 8 连通求连通域 (DB 预测的是"缩小过的文字区域", 一个字连成一块)
 * 2. 每块算平均置信度, 低于 `BOX_THRESHOLD` 的丢掉
 * 3. 把属于同一行的块并起来, 再按 `UNCLIP_RATIO` 外扩
 *
 * 不做多边形与外接旋转框: 屏幕上的文字是水平的, 轴对齐就够, 而且少了整套几何
 *
 * 四个常数来自模型仓库 `inference.yml` 的 `PostProcess`, **v6 与 v5 的默认值不一样**
 * (v5 是 0.3 / 0.6 / 1.5), 换模型要跟着换
 */
object OcrDetect {

    /** 概率图二值化的阈值 */
    const val THRESHOLD = 0.2f

    /** 一块区域的平均置信度低于它就丢掉 */
    const val BOX_THRESHOLD = 0.4f

    /** 外扩比例, 补偿 DB 输出的"缩小的文字区域" */
    const val UNCLIP_RATIO = 1.4f

    /** 同一行里相邻两块的横向空隙不会超过行高的一半, 超过就当成两段 */
    private const val JOIN_GAP = 0.5f

    /** 同一行的两块必须在垂直方向上有这么多重叠 (按矮的那个算) */
    private const val JOIN_OVERLAP = 0.5f

    /** 比这个还小的区域当成噪点, 单位是概率图的像素 */
    private const val MIN_SIDE = 3

    private class Region(
        var left: Int,
        var top: Int,
        var right: Int,
        var bottom: Int,
        var pixels: Int,
        var weight: Float,
    ) {
        val score: Float get() = if (pixels == 0) 0f else weight / pixels
        val width: Int get() = right - left + 1
        val height: Int get() = bottom - top + 1
    }

    /**
     * [prob] 是 `[1, 1, height, width]` 拉平后的概率图, 返回值按先上后下、再左到右排好
     */
    fun lines(prob: FloatArray, width: Int, height: Int): List<OcrBox> {
        require(prob.size >= width * height) { "probability map is ${prob.size}, expected ${width * height}" }
        val regions = regions(prob, width, height)
        return merge(regions)
            .map { unclip(it) }
            .sortedWith(compareBy({ it.top }, { it.left }))
    }

    private fun regions(prob: FloatArray, width: Int, height: Int): MutableList<Region> {
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val found = mutableListOf<Region>()
        for (start in 0 until width * height) {
            if (seen[start] || prob[start] <= THRESHOLD) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var left = start % width
            var right = left
            var upper = start / width
            var lower = upper
            var pixels = 0
            var weight = 0f
            while (top > 0) {
                val at = stack[--top]
                val x = at % width
                val y = at / width
                pixels++
                weight += prob[at]
                if (x < left) left = x
                if (x > right) right = x
                if (y < upper) upper = y
                if (y > lower) lower = y
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val next = ny * width + nx
                        if (seen[next] || prob[next] <= THRESHOLD) continue
                        seen[next] = true
                        stack[top++] = next
                    }
                }
            }
            if (right - left + 1 < MIN_SIDE || lower - upper + 1 < MIN_SIDE) continue
            val region = Region(left, upper, right, lower, pixels, weight)
            if (region.score < BOX_THRESHOLD) continue
            found.add(region)
        }
        return found
    }

    /** 把断开的字并回一行: 反复取两块, 只要它们垂直重叠够、横向空隙小就合起来, 直到合不动 */
    private fun merge(input: List<Region>): List<Region> {
        val pending = input.toMutableList()
        var joined = true
        while (joined) {
            joined = false
            for (i in 0 until pending.size) {
                for (j in i + 1 until pending.size) {
                    if (!joinable(pending[i], pending[j])) continue
                    val a = pending[i]
                    val b = pending[j]
                    pending[i] = Region(
                        minOf(a.left, b.left),
                        minOf(a.top, b.top),
                        maxOf(a.right, b.right),
                        maxOf(a.bottom, b.bottom),
                        a.pixels + b.pixels,
                        a.weight + b.weight,
                    )
                    pending.removeAt(j)
                    joined = true
                    break
                }
                if (joined) break
            }
        }
        return pending
    }

    private fun joinable(a: Region, b: Region): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top) + 1
        val shorter = minOf(a.height, b.height)
        if (overlap < JOIN_OVERLAP * shorter) return false
        val gap = if (a.right < b.left) b.left - a.right - 1 else if (b.right < a.left) a.left - b.right - 1 else 0
        return gap <= JOIN_GAP * shorter
    }

    /** DB 的输出是"缩小过的文字区域", 按面积与周长之比往外扩一圈 */
    private fun unclip(region: Region): OcrBox {
        val w = region.width.toFloat()
        val h = region.height.toFloat()
        val distance = w * h * UNCLIP_RATIO / (2f * (w + h))
        return OcrBox(
            region.left - distance,
            region.top - distance,
            region.right + 1f + distance,
            region.bottom + 1f + distance,
            region.score,
        )
    }
}
