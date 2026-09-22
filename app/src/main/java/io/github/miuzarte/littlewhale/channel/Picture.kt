package io.github.miuzarte.littlewhale.channel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import kotlin.math.sqrt

/**
 * A captured picture, made small enough for the model to take
 *
 * A model route declares how many pixels one image may have - 640,000 for the DeepSeek route this
 * deployment configures - and a picture above that has to be re-encoded before it is sent, because
 * an 1080x2400 screen is four times the budget on its own. On a desktop that re-encode is libvips'
 * job through sharp, and there is no libvips on this device, so the work has to happen where the
 * picture is made: what the model receives is then already inside the budget and passes through the
 * attachment store byte for byte
 *
 * The full-size capture is kept beside the scaled one rather than replaced, because a person opening
 * the workspace wants the screen as it was, and only the model needs the smaller copy
 */
object Picture {

    private const val TAG = "LwPicture"

    /**
     * The pixel budget of the route these tools feed
     *
     * It is the `imagePixelBudget` the phone's own `settings.yaml` declares for `llm-deepseek`. A
     * screenshot scaled to it needs nothing else done to it on the way to the model; one left
     * bigger would be refused, and nothing here could shrink it afterwards
     */
    const val DEFAULT_MAX_PIXELS = 640_000

    /**
     * How many encoded bytes one picture may have on its way to the model
     *
     * It is dsh's own per-image `imageMaxBytes`, whose default is exactly this; the phone's
     * `settings.yaml` sets the same number for this route. The pixel budget is not enough on its
     * own: a screen full of artwork compresses far worse than a flat settings page, so a picture
     * that is exactly at the pixel budget can still weigh twice what the request accepts - and the
     * host answers that by re-encoding the image, which this device has no encoder for, so the
     * whole model request dies instead (measured: a 1192x536 PNG of 1285209 bytes became
     * `TRANSPORT`, retried five times, and every later turn failed with it)
     */
    const val DEFAULT_MAX_BYTES = 1_048_576

    /** Suffix on the scaled copy, so the full-size capture stays next to it */
    private const val SMALL = ".model"

    /** How many times the picture is encoded again while it is still over the byte budget */
    private const val BYTE_ATTEMPTS = 4

    /** Close enough to the byte budget: one more round would only add a few pixels */
    private const val GROW_MARGIN = 1.05

    /** No side goes below this while shrinking, which is far under any real byte overflow */
    private const val MIN_SIDE = 16

    /** A picture to hand over, and what a point on it means on the screen */
    data class Fitted(val file: File, val width: Int, val height: Int, val scale: Float)

    /** A picture's own pixel size, without decoding it, or null when it cannot be read */
    fun sizeOf(source: File): Pair<Int, Int>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        return if (width > 0 && height > 0) width to height else null
    }

    /**
     * Scale a picture down until it fits both the pixel budget and the byte budget
     *
     * A picture already inside both budgets is handed back untouched, so the caller's file is the
     * one it gets. A picture that cannot be read is handed back too: a path to something unreadable
     * says more than a path to nothing
     *
     * The second budget is what makes this load bearing. The host re-encodes any picture that
     * arrives over `imageMaxBytes`, and this device has no encoder at all, so an over-weight
     * picture does not arrive degraded - it does not arrive, and it takes every later request with
     * it. PNG stays the format (the one the device can read back end to end); when one is too
     * heavy, the picture is made smaller and encoded again, and each round uses the size the
     * encoder actually produced rather than an estimate, because how well a screen compresses is
     * a property of what is on it
     *
     * @param source the captured picture.
     * @param maxPixels the largest number of pixels the picture may have.
     * @param maxBytes the largest number of encoded bytes the picture may have.
     * @returns the picture to hand over, its pixel width, and the factor its coordinates need to be
     *   multiplied by to name points on the screen again.
     */
    fun fit(
        source: File,
        maxPixels: Int,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): Fitted {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        val screenWidth = bounds.outWidth
        val screenHeight = bounds.outHeight
        if (screenWidth <= 0 || screenHeight <= 0) {
            Log.w(TAG, "could not read ${source.name}, handing it over as it is")
            return Fitted(source, 0, 0, 1f)
        }
        val budget = maxPixels.coerceAtLeast(1)
        val byteBudget = maxBytes.coerceAtLeast(1)
        val pixels = screenWidth.toLong() * screenHeight
        if (pixels <= budget && source.length() <= byteBudget) {
            Log.i(
                TAG,
                "${source.name} is ${screenWidth}x$screenHeight at ${source.length()} bytes," +
                    " already inside $budget pixels and $byteBudget bytes",
            )
            return Fitted(source, screenWidth, screenHeight, 1f)
        }
        val decoded = BitmapFactory.decodeFile(source.absolutePath) ?: run {
            Log.w(TAG, "could not decode ${source.name}, handing it over as it is")
            return Fitted(source, screenWidth, screenHeight, 1f)
        }
        val target = File(source.parentFile, source.nameWithoutExtension + SMALL + ".png")
        // 像素预算那一版是上限: 下面只在它以内来回找, 不越过它
        val widest = (screenWidth * shrink(pixels, budget.toLong())).toInt().coerceAtLeast(1)
        val highest = (screenHeight * shrink(pixels, budget.toLong())).toInt().coerceAtLeast(1)
        var width = widest
        var height = highest
        // 字节超了就按面积比先估一次 (估偏保守没关系: 缩过头之后还会再放回来)
        if (source.length() > byteBudget) {
            val guess = shrink(source.length().toLong(), byteBudget.toLong())
            width = (width * guess).toInt().coerceAtLeast(1)
            height = (height * guess).toInt().coerceAtLeast(1)
        }
        return try {
            // 装得下的最大一版: 缩下去之后再按实际余量放回来, 因为"这张屏压得有多好"只有编完才知道
            var fitting: IntArray? = null
            var growing = false
            var attempt = 0
            while (attempt < BYTE_ATTEMPTS) {
                val written = encode(decoded, target, width, height)
                attempt += 1
                if (written <= byteBudget) {
                    fitting = intArrayOf(width, height, written.toInt())
                    val room = grow(written, byteBudget.toLong())
                    if (room < GROW_MARGIN) break
                    val next = resized(width, height, room, widest, highest)
                    if (next[0] == width && next[1] == height) break
                    Log.i(
                        TAG,
                        "${source.name} came out $written bytes at ${width}x$height, under" +
                            " $byteBudget: trying ${next[0]}x${next[1]}",
                    )
                    width = next[0]
                    height = next[1]
                    growing = true
                } else {
                    // 放大反而超了: 上一版就是答案, 它还在 fitting 里
                    if (growing || width <= MIN_SIDE || height <= MIN_SIDE) break
                    val next = resized(width, height, shrink(written, byteBudget.toLong()), widest, highest)
                    if (next[0] == width && next[1] == height) break
                    Log.i(
                        TAG,
                        "${source.name} came out $written bytes at ${width}x$height, over" +
                            " $byteBudget: trying ${next[0]}x${next[1]}",
                    )
                    width = next[0]
                    height = next[1]
                }
            }
            val chosen = fitting
            if (chosen == null) {
                Log.w(
                    TAG,
                    "${target.name} is over $byteBudget after $attempt tries at ${width}x$height:" +
                        " the model request will refuse it",
                )
                Fitted(target, width, height, screenWidth.toFloat() / width)
            } else {
                // 最后一轮可能是那次放大的失败尝试, 所以选中哪一版就把它写回去
                if (chosen[0] != width || chosen[1] != height) {
                    encode(decoded, target, chosen[0], chosen[1])
                }
                Log.i(
                    TAG,
                    "scaled ${source.name} from ${screenWidth}x$screenHeight (${source.length()}" +
                        " bytes) to ${chosen[0]}x${chosen[1]} (${chosen[2]} bytes)",
                )
                Fitted(target, chosen[0], chosen[1], screenWidth.toFloat() / chosen[0])
            }
        } catch (problem: Throwable) {
            Log.w(TAG, "could not write ${target.name}", problem)
            Fitted(source, screenWidth, screenHeight, 1f)
        } finally {
            decoded.recycle()
        }
    }

    /** Encode one size of the picture into the file the caller gets, answering how many bytes */
    private fun encode(source: Bitmap, target: File, width: Int, height: Int): Long {
        val scaled = if (width == source.width && height == source.height) {
            source
        } else {
            Bitmap.createScaledBitmap(source, width, height, true)
        }
        try {
            target.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            if (scaled !== source) scaled.recycle()
        }
        return target.length()
    }

    /** One side's share of an area change, never past the pixel budget's own version */
    private fun resized(width: Int, height: Int, factor: Double, widest: Int, highest: Int): IntArray =
        intArrayOf(
            (width * factor).toInt().coerceIn(1, widest),
            (height * factor).toInt().coerceIn(1, highest),
        )

    /**
     * The factor that brings one area down to a budget
     *
     * PNG's size follows the pixels rather than the sides, so a side changes by the square root of
     * the area ratio - and every round here measures the encoded bytes rather than trusting the
     * guess, because how well a screen compresses is a property of what is on it
     */
    private fun shrink(area: Long, budget: Long): Double =
        sqrt(budget.toDouble() / area.toDouble()).coerceAtMost(1.0)

    /** The same factor the other way: how much room is left under the byte budget */
    private fun grow(written: Long, budget: Long): Double =
        sqrt(budget.toDouble() / written.toDouble())
}
