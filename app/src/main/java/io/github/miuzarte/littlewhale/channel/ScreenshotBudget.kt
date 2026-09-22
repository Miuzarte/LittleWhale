package io.github.miuzarte.littlewhale.channel

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.miuzarte.littlewhale.R

/**
 * 截图缩到多少像素、多少字节以内再交给模型
 *
 * 上面那条是**形状**, 下面那条是**重量**, 两个都要过: 一张图能过像素那一关却过不了字节那一关
 * (一整屏游戏画面在 536x1192 就能压到 1.29 MB), 而两张都超了都只有一个下场 —— 见下面两段的注释
 *
 * 三档像素的数字不是随手定的: **低**是 dsh 的 `imagePixelBudget: low` (512x512), **默认**是 dsh 自己的
 * 缺省 640000, **高**是 DeepSeek 那头处理图片的预算 (约 1300x1300)
 *
 * 给模型的那张图必须落在这条路由允许的预算**之内**: 超了要在 host 那边重新编码, 而设备上没有编码器
 * (host 树里那份 `sharp` 是纯 JS 替身, 终端编码一律报错), `read_image` 会直接失败。所以调高之前先看
 * dsh 那条路由的 `imagePixelBudget` 有没有跟着调高; 调低总是安全的, 代价只是模型看得更糊
 *
 * 字节那一条同理, 对齐的是路由的 `imageMaxBytes` (缺省就是这里的默认值 1 MiB), 但它是**连着滑**的:
 * 这个数没有几个"档"可言, 能滑到一位不差对上 `settings.yaml` 里的数字才要紧, 所以中间那些值只是
 * **吸附点**, 不是全部可选值
 *
 * **两半账要说清**: 这个对象给的是 **app 这一半** —— 截图在产生时缩到多少。路由那一半 (那条
 * `imageMaxBytes`) 在 dsh 自己的 `settings.yaml` 里, app 读不到也写不到它。app 这一半高于路由那一半
 * 没有任何好处 (只会把注定被拒的图交出去), 所以滑块的上端就停在路由缺省那个数, 而**打字**能给的
 * 范围更宽 —— 那是给"路由也一起调高了"的人留的口子
 */
object ScreenshotBudget {

    /** 与 [io.github.miuzarte.littlewhale.host.HostSettings] 同一个偏好文件, 全 app 一个 */
    private const val STORE = "littlewhale"

    private const val KEY = "screenshot-budget"

    private const val BYTE_KEY = "screenshot-bytes"

    /** 默认档的下标, 也就是没选过时用的那一档 */
    private const val DEFAULT = 1

    /** 一 KiB 是多少字节, 偏好的单位是字节, 滑块的单位是 KiB */
    private const val KIB = 1024

    /** 每一档的名字 (字符串资源) 与它允许的像素数, 下标就是存进偏好的值 */
    val levels: List<Pair<Int, Int>> = listOf(
        R.string.settings_budget_low to 262_144,
        R.string.settings_budget_default to Picture.DEFAULT_MAX_PIXELS,
        R.string.settings_budget_high to 1_690_000,
    )

    /** 当前档, 设置页读它所以是 Compose 状态 */
    var index: Int by mutableStateOf(DEFAULT)
        private set

    /** 这一档是多少像素, 截图那条路读它 */
    val pixels: Int get() = levels[index.coerceIn(0, levels.lastIndex)].second

    /** 字节预算能滑到的两端 (KiB): 上端就停在路由缺省的那个数 (1 MiB) */
    val byteRange: ClosedFloatingPointRange<Float> = 256f..1024f

    /**
     * 字节预算的吸附点 (KiB)
     *
     * 三等分, 都在滑块那条线上: 512 KiB 看个大概, 768 KiB 是两者的中间, 1024 KiB 就是路由缺省
     */
    val byteKeyPoints: List<Float> = listOf(256f, 512f, 768f, 1024f)

    /**
     * 打字能给的字节数 (KiB), 比滑块的上限宽
     *
     * 滑块滑不出去而打字能给: 滑块能到的每个值都该是能过那条路由的, 而打字是"我知道自己在配一条
     * 别的路由"的动作 —— 先在 `settings.yaml` 里把 `imageMaxBytes` 调上去, 再点标题那一行给精确值
     */
    val byteInputRange: ClosedFloatingPointRange<Float> = 256f..4096f

    /** 离吸附点多近算吸住, 占整条 range 的比例 (与 Miuix 的 `magnetThreshold` 一个算法) */
    const val BYTE_MAGNET = 0.03f

    /** 当前字节预算, 单位是字节, 和 `imageMaxBytes` 一样 */
    var bytes: Int by mutableStateOf(Picture.DEFAULT_MAX_BYTES)
        private set

    /** 滑块现在在哪 (KiB) */
    val kib: Float get() = bytes.toFloat() / KIB

    /** 从磁盘读一次, 在第一帧之前调, 免得先按默认值画一遍再跳 */
    fun initialize(context: Context) {
        val stored = preferences(context)
        index = stored.getInt(KEY, DEFAULT)
        bytes = stored.getInt(BYTE_KEY, Picture.DEFAULT_MAX_BYTES)
    }

    /** 收下新的像素档, 先让界面用上再落盘 */
    fun set(context: Context, value: Int) {
        index = value
        preferences(context).edit().putInt(KEY, value).apply()
    }

    /** 收下新的字节预算, 参数是 KiB (滑块的单位), 落盘的是字节 (路由的单位) */
    fun setBytes(context: Context, kib: Int) {
        bytes = kib * KIB
        preferences(context).edit().putInt(BYTE_KEY, bytes).apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(STORE, Context.MODE_PRIVATE)
}
