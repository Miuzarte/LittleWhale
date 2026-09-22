package io.github.miuzarte.littlewhale.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 外观设置
 *
 * 字段与默认值都照 SFA 的主题设置页, 少的是它自己才有的东西 (悬浮底栏与液态玻璃) 与这边的模糊
 * (2026-09-22 去掉: 顶栏下面就是虚拟屏画面, 那块画面自己不透明, 糊了也没人看得见)
 * @property mode 跟随系统 / 浅色 / 深色
 * @property monet 是否从种子颜色派生整套配色
 * @property seed 种子颜色的下标, 0 是默认 (跟随壁纸)
 * @property palette Google 的调色板风格
 * @property spec Google 的色彩规范版本
 * @property squircle 方形控件的圆角是否用方圆形
 * @property transition 页面切换的动效, Miuix 默认或 AOSP
 * @property swipeBack 是否允许从边缘滑动返回
 */
data class ThemeSettings(
    val mode: Int = MODE_SYSTEM,
    val monet: Boolean = false,
    val seed: Int = 0,
    val palette: Int = 0,
    val spec: Int = 0,
    val squircle: Boolean = true,
    val transition: Int = TRANSITION_MIUIX,
    val swipeBack: Boolean = true,
) {
    companion object {
        const val MODE_SYSTEM = 0
        const val MODE_LIGHT = 1
        const val MODE_DARK = 2

        const val TRANSITION_MIUIX = 0

        // 下标 1 本来是 "无", 照 SFA 的选项表换成了 AOSP; 存着的旧值不改写, 于是选过 "无" 的人
        // 升级后拿到的是 AOSP 而不是没有动效
        const val TRANSITION_AOSP = 1
    }
}

/** 设置存在哪, 以及它现在的值, 值变了会重算主题 */
object ThemeStore {

    private const val FILE = "theme"
    private const val MODE = "mode"
    private const val MONET = "monet"
    private const val SEED = "seed"
    private const val PALETTE = "palette"
    private const val SPEC = "spec"
    private const val SQUIRCLE = "squircle"
    private const val TRANSITION = "transition"
    private const val SWIPE_BACK = "swipe_back"

    private var preferences: SharedPreferences? = null

    /** 当前设置, 未 [initialize] 过时是默认值 */
    var current: ThemeSettings by mutableStateOf(ThemeSettings())
        private set

    /** 从磁盘读一次, 之后每次改动都会写回去 */
    fun initialize(context: Context) {
        if (preferences != null) return
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        preferences = store
        val defaults = ThemeSettings()
        current = ThemeSettings(
            mode = store.getInt(MODE, defaults.mode),
            monet = store.getBoolean(MONET, defaults.monet),
            seed = store.getInt(SEED, defaults.seed),
            palette = store.getInt(PALETTE, defaults.palette),
            spec = store.getInt(SPEC, defaults.spec),
            squircle = store.getBoolean(SQUIRCLE, defaults.squircle),
            transition = store.getInt(TRANSITION, defaults.transition),
            swipeBack = store.getBoolean(SWIPE_BACK, defaults.swipeBack),
        )
    }

    /** 收下一份新设置, 先让界面用上再落盘 */
    fun update(settings: ThemeSettings) {
        current = settings
        preferences?.edit {
            putInt(MODE, settings.mode)
            putBoolean(MONET, settings.monet)
            putInt(SEED, settings.seed)
            putInt(PALETTE, settings.palette)
            putInt(SPEC, settings.spec)
            putBoolean(SQUIRCLE, settings.squircle)
            putInt(TRANSITION, settings.transition)
            putBoolean(SWIPE_BACK, settings.swipeBack)
        }
    }
}

/** 动态取色能选的种子颜色, 下标 0 的 "默认" 表示跟随壁纸 */
val MonetKeyColors: List<Pair<String, Color>> = listOf(
    "蓝色" to Color(0xFF3482FF),
    "绿色" to Color(0xFF36D167),
    "紫色" to Color(0xFF7C4DFF),
    "黄色" to Color(0xFFFFB21D),
    "橙色" to Color(0xFFFF5722),
    "粉色" to Color(0xFFE91E63),
    "青色" to Color(0xFF00BCD4),
)

val MonetKeyColorOptions: List<String> = listOf("默认") + MonetKeyColors.map { it.first }

/** 种子颜色下标对应的颜色, 默认那项没有颜色 */
fun monetKeyColorFor(index: Int): Color? =
    if (index <= 0) null else MonetKeyColors.getOrNull(index - 1)?.second

/** 把设置变成 Miuix 的主题控制器 */
fun ThemeSettings.controller(): ThemeController {
    val monetMode = when (mode.coerceIn(0, 2)) {
        ThemeSettings.MODE_LIGHT -> if (monet) ColorSchemeMode.MonetLight else ColorSchemeMode.Light
        ThemeSettings.MODE_DARK -> if (monet) ColorSchemeMode.MonetDark else ColorSchemeMode.Dark
        else -> if (monet) ColorSchemeMode.MonetSystem else ColorSchemeMode.System
    }
    if (!monet) return ThemeController(colorSchemeMode = monetMode)
    return ThemeController(
        colorSchemeMode = monetMode,
        keyColor = monetKeyColorFor(seed),
        paletteStyle = ThemePaletteStyle.entries.getOrNull(palette) ?: ThemePaletteStyle.Content,
        colorSpec = ThemeColorSpec.entries.getOrNull(spec) ?: ThemeColorSpec.Spec2021,
    )
}
