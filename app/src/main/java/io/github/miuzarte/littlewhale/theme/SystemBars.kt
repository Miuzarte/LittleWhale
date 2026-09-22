package io.github.miuzarte.littlewhale.theme

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 让系统栏的图标跟着应用**实际**的主题走, 照抄 SFA 的 `ui/SystemBars.kt`
 *
 * 外观模式可以手动钉成深色或浅色, 与系统主题无关, 而状态栏图标默认跟的是系统主题 - 于是手动切到
 * 深色时, 状态栏还是给深色图标, 压在深色顶栏上就看不清了。所以这里读的是**当前渲染出来的**背景色
 *
 * 要放在 MiuixTheme 里面, [colorScheme] 才是生效中的那套配色
 * @param window 活动窗口, 传 null 就什么都不做
 */
@Composable
fun ApplySystemBarsAppearance(window: Window?) {
    val isLight = colorScheme.background.luminance() >= 0.5f
    SideEffect {
        val w = window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(w, w.decorView)
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight
    }
}
