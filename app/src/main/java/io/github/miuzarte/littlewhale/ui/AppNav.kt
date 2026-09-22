package io.github.miuzarte.littlewhale.ui

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import top.yukonga.miuix.kmp.nav.core.NavCornerClipMode
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.transition.NavSwipeDirection
import top.yukonga.miuix.kmp.nav.transition.NavTransitions
import top.yukonga.miuix.kmp.squircle.LocalSquircleEnabled
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import io.github.miuzarte.littlewhale.theme.ApplySystemBarsAppearance
import io.github.miuzarte.littlewhale.theme.ThemeSettings
import io.github.miuzarte.littlewhale.theme.ThemeStore
import io.github.miuzarte.littlewhale.theme.controller
import kotlinx.serialization.Serializable

/** 应用里有哪几页, 可序列化是因为导航要把返回栈存下来 */
@Serializable
sealed interface Screen : NavKey {
    @Serializable
    data object Home : Screen

    @Serializable
    data object Settings : Screen
}

/** 谁想换页就问它, 页面自己不持有返回栈 */
class RootNavigator(
    val push: (Screen) -> Unit,
    val pop: () -> Unit,
)

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator> {
    error("No RootNavigator provided")
}

/**
 * 整个应用: 主题 + 两页之间的导航
 *
 * 外观设置在这里落到 Miuix 上, 所以设置页改完立刻生效, 不需要重启; 导航用的是 miuix-nav,
 * 页面切换的动效与侧滑返回也跟着设置走
 */
@Composable
fun LittleWhaleApp() {
    val settings = ThemeStore.current
    val backStack = rememberNavBackStack<Screen>(Screen.Home)
    val navigator = remember(backStack) {
        RootNavigator(
            push = { backStack.add(it) },
            pop = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        )
    }
    val controller = remember(
        settings.mode,
        settings.monet,
        settings.seed,
        settings.palette,
        settings.spec,
    ) {
        settings.controller()
    }
    val swipe = if (settings.swipeBack) NavSwipeDirection.LeftToRight else NavSwipeDirection.None
    val crossActivity = settings.transition == ThemeSettings.TRANSITION_AOSP
    val transition = if (crossActivity) CrossActivityTransition else NavTransitions.MiuixDefault

    MiuixTheme(controller = controller) {
        // 系统栏图标要跟着实际渲染出来的配色, 手动钉成深色时也得跟着变
        ApplySystemBarsAppearance(LocalActivity.current?.window)
        val cornerRadius = rememberNavSystemCornerRadius()
        val backdrop = colorScheme.surface
        val effects = remember(cornerRadius, backdrop, crossActivity) {
            NavDisplayEffects(
                enableCornerClip = true,
                cornerClipRadius = cornerRadius,
                // 跨 activity 那一套四角都收, Miuix 默认只收上边缘那两个角
                cornerClipMode = if (crossActivity) NavCornerClipMode.All else NavCornerClipMode.Leading,
                dimAmount = 0.5f,
                blockInputDuringTransition = true,
                backdropColor = backdrop,
            )
        }
        CompositionLocalProvider(
            LocalRootNavigator provides navigator,
            LocalSquircleEnabled provides settings.squircle,
        ) {
            NavDisplay(
                backStack = backStack,
                onBack = navigator.pop,
                transition = transition,
                effects = effects,
            ) {
                entry<Screen.Home>(swipeDismiss = swipe) { HostScreen() }
                entry<Screen.Settings>(swipeDismiss = swipe) { SettingsScreen() }
            }
        }
    }
}
