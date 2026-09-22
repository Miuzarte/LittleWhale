package io.github.miuzarte.littlewhale.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.viewinterop.AndroidView
import io.github.miuzarte.littlewhale.channel.PreviewControl
import io.github.miuzarte.littlewhale.channel.ScreenState
import io.github.miuzarte.littlewhale.channel.VirtualScreen

/**
 * The virtual screen, as a picture that can be touched
 *
 * The frames are not copied here: the screen's output surface *is* this view's surface, so the
 * compositor draws straight into it and every pixel arrives exactly once
 *
 * Two things are the app's to get right. The buffer is asked to be the screen's own size, because
 * the compositor draws at that size and does not scale it into whatever buffer it is handed - a
 * smaller one is a crop of the top left corner. The view is then sized to the screen's shape, and
 * the compositor scales the buffer into it, which is a surface's normal job
 *
 * A finger on the picture is a finger on the screen: the view is the screen scaled down, so its
 * coordinates are the screen's coordinates scaled up, and the events are handed to the privileged
 * process because that is where they can be injected
 * @param screen the screen to picture and to touch, which fixes the shape.
 * @param modifier layout modifier from the caller, which fixes the width.
 * @param onTouch 有人碰画面 (含两边的黑边) 时叫一声, 手指照样往下走 - 悬浮菜单拿它重新计时
 */
@Composable
fun VirtualScreenPreview(
    screen: ScreenState,
    modifier: Modifier = Modifier,
    onTouch: () -> Unit = {},
) {
    BoxWithConstraints(modifier = modifier) {
        // 竖屏内容的画面比宽度高得多, 直接按比例给高度会把会话挤没, 所以最多给到宽度的四分之三,
        // 也就是盒子最大 4:3; 横屏内容的短边本来就在四分之三以内, 于是宽度撑满、高度按短边收
        val boxHeight = minOf(maxWidth * PORTRAIT_LIMIT, maxWidth * (screen.height.toFloat() / screen.width))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(boxHeight)
                .background(Color.Black)
                // 报"有人碰了画面"的是这一层而不是画面自己: 画面按宽度比缩完只占中间一条, 两边
                // 是黑边, 而手指落在黑边上时下面那层手势根本不在命中路径里。这一层只看不消费
                // (requireUnconsumed = false), 所以黑边上的手不算数、画面上的手照旧往下走
                .pointerInput(screen.displayId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        onTouch()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // 每块屏一个自己的 SurfaceView: 换屏时旧 buffer 里还留着上一块屏的最后一帧, 而空屏不会
            // 产生新帧把它顶掉, 于是复用同一个 surface 的话会一直显示上一块屏的画面
            key(screen.displayId) {
                val view = remember { mutableStateOf<SurfaceView?>(null) }
                // 尺寸也在键里: 屏可以被改成另一个尺寸 (resize / rotate), 而 SurfaceView 的 buffer
                // 是定死的, 不改就按老尺寸裁画面 —— 合成器画的是屏的尺寸, 它不缩放
                LaunchedEffect(screen.displayId, screen.width, screen.height) {
                    val holder = view.value?.holder ?: return@LaunchedEffect
                    holder.setFixedSize(screen.width, screen.height)
                    VirtualScreen.attach(screen, holder.surface)
                }
                AndroidView(
                    modifier = Modifier
                        .fillMaxHeight()
                        .aspectRatio(screen.aspect)
                        // 尺寸同样是键: 下面按 size 反算的那两个比例是这块屏当前的宽高比, 换了尺寸
                        // 还留着旧的, 手指点下去换算出来的坐标就是错的
                        .pointerInput(screen.displayId, screen.width, screen.height, PreviewControl.allowed) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // 画面上的手要让外面知道: 外面的那一层收不到落在 AndroidView 上
                                // 的事件 (interop 的 view 自己吃掉了), 所以这里也得叫一声。这一声与
                                // "算不算操作屏" 无关, 它只是把悬浮按钮叫出来
                                onTouch()
                                // 默认不让预览控制屏幕: 手滑过画面就打乱模型正在做的事, 所以除了
                                // 上面那一句, 下面 press/move/release 一个都不发
                                if (!PreviewControl.allowed) return@awaitEachGesture
                                val scaleX = screen.width.toFloat() / size.width
                                val scaleY = screen.height.toFloat() / size.height
                                VirtualScreen.press(screen, down.position.x * scaleX, down.position.y * scaleY)
                                down.consume()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (change.changedToUp()) {
                                        VirtualScreen.release(screen, change.position.x * scaleX, change.position.y * scaleY)
                                        change.consume()
                                        break
                                    }
                                    if (change.positionChanged()) {
                                        VirtualScreen.move(screen, change.position.x * scaleX, change.position.y * scaleY)
                                        change.consume()
                                    }
                                }
                            }
                        },
                    factory = { context ->
                        SurfaceView(context).also { view.value = it }.apply {
                            holder.setFixedSize(screen.width, screen.height)
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    VirtualScreen.attach(screen, holder.surface)
                                }

                                // The picture is the same surface at a new size, but the screen has
                                // to be told where that surface is at least once after it exists
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    VirtualScreen.attach(screen, holder.surface)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    VirtualScreen.attach(screen, null)
                                }
                            })
                        }
                    },
                )
            }
        }
    }
}

/** 竖屏画面最多占的高度, 相对可用宽度 */
private const val PORTRAIT_LIMIT = 0.75f
