package io.github.miuzarte.littlewhale.ui

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.constants.UiSpacing
import io.github.miuzarte.littlewhale.channel.PreviewControl
import io.github.miuzarte.littlewhale.channel.ScreenState
import io.github.miuzarte.littlewhale.channel.VirtualScreen
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostStatus
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Tune
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.menu.OverlayIconCascadingDropdownMenu
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 主页面: 虚拟屏的画面在上, dsh 的 Web GUI 在下
 *
 * 画面占高度而不是盖上去: WebView 量的是自己的视口, 一个盖在它上面的浮层会让 dsh 的布局被压在
 * 底下看不见的那半里缩不回来
 *
 * 顶栏末尾的级联菜单第一层就两项, 虚拟屏与设置; 虚拟屏那一项自己带着当前选中的是哪块屏, 展开
 * 才是屏的列表, 选中哪块看哪块, 再点一次同一块就是不看
 *
 * **有画面在看的时候顶栏整个让位** (2026-09-22): `SmallTopAppBar` 的高度是 `CollapsedHeight`
 * 52dp, 与标题长不长无关 (标题空着也一样), 而这一页的标题只是个应用名, 那 52dp 给会话更值。
 * 于是这时不放 topBar, 菜单按钮改成浮在画面右上角, 静一会儿自己淡出, 碰一下画面再出来 - 与手机
 * 上视频播放器的控件一个脾气。不看画面了 (收起或没选中) 就回到那条默认顶栏
 * @param modifier layout modifier from the caller.
 */
@Composable
fun HostScreen(modifier: Modifier = Modifier) {
    val navigator = LocalRootNavigator.current
    val selected = VirtualScreen.selected
    // 选中就是显示: 没有"收起了但还选着"这种状态, 取消选中就是不看了
    val preview = selected

    // 悬浮菜单: 画面一出现先亮一会儿, 之后每次碰画面 (或按它自己) 重新计时
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsTick by remember { mutableStateOf(0) }
    // 菜单展开期间按钮不能淡出: 淡出结束会把内容从树里真正拿掉, 而 popup 是挂在它下面的,
    // 于是菜单会跟着一起没 (2026-09-22 踩过)
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(preview?.displayId, controlsTick) {
        controlsVisible = true
        delay(CONTROLS_IDLE_MS)
        controlsVisible = false
    }
    val keepControls: () -> Unit = { controlsTick++ }
    val layoutDirection = LocalLayoutDirection.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            // 只有不看画面时才有顶栏, 收起画面 / 没选中屏都回到这一条
            if (preview == null) {
                SmallTopAppBar(
                    title = stringResource(R.string.app_name),
                    actions = {
                        MenuButton(
                            selected = selected,
                            onSettings = { navigator.push(Screen.Settings) },
                        )
                    },
                )
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
                preview?.let { screen ->
                    VirtualScreenPreview(
                        screen = screen,
                        modifier = Modifier.fillMaxWidth(),
                        onTouch = keepControls,
                    )
                }
                // 画面空着就是空着, 页面上不留一行字 - 有没有屏、选中哪一块, ⋮ 菜单里都写着
                // 只有出错时才说话, 否则失败会被静默吞掉
                if (selected == null) {
                    VirtualScreen.lastError?.let { reason ->
                        Text(
                            text = "虚拟屏: $reason",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                when (val status = DshHost.status) {
                    is HostStatus.Running -> HostWebView(url = status.url, modifier = Modifier.weight(1f))
                    else -> HostBootPanel(status = status, modifier = Modifier.weight(1f))
                }
            }
            if (preview != null) {
                FloatingMenuButton(
                    // 菜单开着就一直露着, 关掉之后重新计时
                    visible = controlsVisible || menuOpen,
                    selected = selected,
                    onSettings = { navigator.push(Screen.Settings) },
                    onMenuExpanded = { open ->
                        menuOpen = open
                        keepControls()
                    },
                    // 这一层不在 Column 里, 所以 Scaffold 那点内边距要自己补上, 否则按钮会落到
                    // 状态栏里去 (它按窗口算 TopEnd, 而窗口是 edge-to-edge 的)
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(
                            top = innerPadding.calculateTopPadding(),
                            end = innerPadding.calculateRightPadding(layoutDirection),
                        ),
                )
            }
        }
    }
}

/**
 * 顶栏上那个菜单按钮
 *
 * 顶栏版与悬浮版共用它, 免得两处的菜单对不上
 */
@Composable
private fun MenuButton(
    selected: ScreenState?,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Unspecified,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    OverlayIconCascadingDropdownMenu(
        entries = mainMenu(
            selected = selected,
            onSettings = onSettings,
        ),
        modifier = modifier,
        backgroundColor = backgroundColor,
        onExpandedChange = onExpandedChange,
    ) {
        Icon(
            imageVector = MiuixIcons.Tune,
            contentDescription = "菜单",
        )
    }
}

/**
 * 浮在画面右上角的那个菜单按钮
 *
 * 它背后是别人家的画面, 底色说不准, 所以给它一层半透明的表面色当底, 否则深色壁纸上会看不见。
 * 静一会儿自己淡出, 只是淡出而不是消失: 位置不变, 点了画面又回来
 * @param visible 现在该不该露着
 * @param onMenuExpanded 菜单开合时叫一声, 开着的时候调用方要让 [visible] 保持为真
 */
@Composable
private fun FloatingMenuButton(
    visible: Boolean,
    selected: ScreenState?,
    onSettings: () -> Unit,
    onMenuExpanded: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 用 AnimatedVisibility 而不是自己调 alpha: 淡出结束后内容会真的从树里拿掉, 于是藏起来的
    // 按钮不会继续吃掉右上角那一片的点击 (那一片属于画面, 手指该落在画面上)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        MenuButton(
            selected = selected,
            onSettings = onSettings,
            // 上边与右边给同样的距离, 看着才是正对着角放上去的 (上面那点距离是从状态栏下面算起)
            modifier = Modifier.padding(end = UiSpacing.Medium, top = UiSpacing.Medium),
            backgroundColor = colorScheme.surface.copy(alpha = 0.8f),
            onExpandedChange = onMenuExpanded,
        )
    }
}

/**
 * 顶栏菜单
 *
 * 屏是单选: 选中哪块看哪块, 选中的那块再点一次就取消选中 (这就是收起画面, 不再单设一个开关),
 * 而所有屏都还在跑 - 取消选中只是不看了
 */
@Composable
private fun mainMenu(
    selected: ScreenState?,
    onSettings: () -> Unit,
): List<DropdownEntry> {
    val context = LocalContext.current
    val screens = VirtualScreen.screens
    return listOf(
        DropdownEntry(
            items = listOf(
                DropdownItem(
                    text = "虚拟屏",
                    summary = selected?.label,
                    children = buildList {
                        if (screens.isEmpty()) {
                            add(DropdownItem(text = "还没有已创建的虚拟屏", enabled = false))
                        }
                        screens.forEach { screen ->
                            add(
                                DropdownItem(
                                    text = screen.label,
                                    summary = screen.shape,
                                    selected = screen.displayId == selected?.displayId,
                                    onClick = { VirtualScreen.toggle(screen) },
                                ),
                            )
                        }
                        // 手动的刹车: 停的是模型的下一步动作, 不是这块屏也不是预览, 用户自己的手指
                        // 照样能碰画面
                        add(
                            DropdownItem(
                                text = if (selected?.acceptsControl == false) {
                                    "继续接受控制"
                                } else {
                                    "暂停接受控制"
                                },
                                summary = selected?.label,
                                enabled = selected != null,
                                onClick = {
                                    selected?.let { VirtualScreen.setAcceptsControl(it, !it.acceptsControl) }
                                },
                            ),
                        )
                        // 建屏不在这里: 一块屏叫什么、多大, 是工具在造它的时候决定的, 界面只负责看和关
                        // byUser = true: 之后模型再动这个 id, 桥要能说出"这是用户关的"
                        add(
                            DropdownItem(
                                text = "释放虚拟屏",
                                summary = selected?.label,
                                enabled = selected != null,
                                onClick = { selected?.let { VirtualScreen.release(it, byUser = true) } },
                            ),
                        )
                    },
                ),
                // 这个开关放在一级菜单而不是设置页: 它是"现在这块画面能不能摸", 边看边切才对
                DropdownItem(
                    text = "虚拟屏触摸控制",
                    summary = "禁用以避免误操作",
                    selected = PreviewControl.allowed,
                    onClick = { PreviewControl.set(context, !PreviewControl.allowed) },
                ),
                DropdownItem(
                    text = "设置",
                    onClick = onSettings,
                ),
            ),
        ),
    )
}

/**
 * The dsh GUI itself, which is a normal browser page served from loopback
 *
 * The host authenticates a browser by answering the token URL with a redirect that sets an
 * HttpOnly cookie, so the WebView has to keep that cookie or every later request comes back
 * unauthorized, which is an empty body and therefore a blank page
 *
 * What a browser is expected to do beyond rendering - pick a file, save a download - needs an
 * activity behind it, so those two requests are routed out of the page here
 * @param url the token-carrying URL the host printed once its tree settled.
 * @param modifier layout modifier from the caller.
 */
@Composable
private fun HostWebView(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // A file input can only be answered by an activity, so the page's request parks here until
    // the picker it launched comes back
    var pending by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val answer = pending ?: return@rememberLauncherForActivityResult
        pending = null
        // The platform helper reads either form of result the picker can return, a single data
        // URI or the clip data of a multiple selection
        answer.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            CookieManager.getInstance().setAcceptCookie(true)
            WebView(viewContext).apply {
                settings.javaScriptEnabled = true
                // dsh keeps theme and font size in localStorage, which is also how a phone and
                // a desktop browser reach the same server with different appearance settings
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                // Compose sizes an AndroidView through the modifier and leaves layoutParams at
                // WRAP_CONTENT, and a WebView in that state resolves every viewport unit to 0,
                // which collapses the dialogs, menus and directory picker dsh measures in vh,
                // the visible size in Compose has nothing to do with it, so this has to be set
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                // The page is the whole product surface, so its own failures need somewhere to
                // show up: status codes, load errors, and browser console lines all go to logcat
                webViewClient = object : WebViewClient() {
                    // Only the host's own pages belong in this view. Anything else - a mail or
                    // app scheme in a rendered link - would leave through the platform, which
                    // answers with an "open with" chooser over the GUI, so a non-page scheme is
                    // refused here and named in the log instead
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val target = request.url
                        if (target.scheme == "http" || target.scheme == "https") return false
                        Log.w(WEB_TAG, "blocked navigation to $target")
                        return true
                    }

                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        Log.i(WEB_TAG, "finished $finishedUrl")
                        // A page that loads without any HTTP or console error can still be blank,
                        // so the shell state itself is asked for and logged
                        view.evaluateJavascript(SHELL_PROBE) { result ->
                            Log.i(WEB_TAG, "shell $result")
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        Log.w(WEB_TAG, "http ${errorResponse.statusCode} for ${request.url}")
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        Log.w(WEB_TAG, "error ${error.errorCode} ${error.description} for ${request.url}")
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i(WEB_TAG, "console ${message.messageLevel()} ${message.message()}")
                        return true
                    }

                    // A popup cannot become a second view here, and leaving the request to
                    // Chromium is what can put a platform chooser over the GUI, so the attempt
                    // is refused and logged rather than answered
                    override fun onCreateWindow(
                        view: WebView,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?,
                    ): Boolean {
                        Log.w(WEB_TAG, "blocked popup dialog=$isDialog gesture=$isUserGesture")
                        return false
                    }

                    // dsh attaches files through a plain file input, and the chooser it needs is
                    // an activity the page cannot open itself
                    override fun onShowFileChooser(
                        view: WebView,
                        callback: ValueCallback<Array<Uri>>,
                        params: FileChooserParams,
                    ): Boolean {
                        Log.i(WEB_TAG, "file chooser ${params.acceptTypes.joinToString()}")
                        // A second request supersedes the first, and every callback has to be
                        // answered exactly once
                        pending?.onReceiveValue(null)
                        pending = callback
                        return try {
                            picker.launch(params.createIntent())
                            true
                        } catch (error: ActivityNotFoundException) {
                            Log.w(WEB_TAG, "no picker for ${params.acceptTypes.joinToString()}: ${error.message}")
                            pending = null
                            callback.onReceiveValue(null)
                            true
                        }
                    }
                }
                setDownloadListener { downloadUrl, _, contentDisposition, mimeType, _ ->
                    startDownload(context, downloadUrl, contentDisposition, mimeType)
                }
                loadUrl(url)
            }
        },
        update = { view -> if (view.url != url) view.loadUrl(url) },
    )
}

/**
 * Hand an http(s) download to the system downloader
 *
 * The host authorises a browser with a cookie that the downloader's own process cannot see, so
 * the header travels with the request
 *
 * A `blob:` download never reaches this listener, because the bytes only exist inside the page;
 * those need the page itself to hand them over and are not supported yet
 * @param context context whose download service is used.
 * @param url the URL the page asked to save.
 * @param contentDisposition the page's disposition header, which often carries the file name.
 * @param mimeType the page's content type, absent when the page did not name one.
 */
private fun startDownload(context: Context, url: String, contentDisposition: String?, mimeType: String?) {
    if (!url.startsWith("http")) {
        Log.w(WEB_TAG, "unsupported download $url")
        return
    }
    val request = DownloadManager.Request(Uri.parse(url))
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(
            Environment.DIRECTORY_DOWNLOADS,
            URLUtil.guessFileName(url, contentDisposition, mimeType),
        )
    if (!mimeType.isNullOrEmpty()) request.setMimeType(mimeType)
    CookieManager.getInstance().getCookie(url)?.let { request.addRequestHeader("Cookie", it) }
    context.getSystemService(DownloadManager::class.java).enqueue(request)
    Log.i(WEB_TAG, "download enqueued for $url")
}

/** 悬浮菜单静多久就淡出, 与手机上播放器控件一个量级 */
private const val CONTROLS_IDLE_MS = 3000L

/** Logcat tag for what the embedded browser reports */
private const val WEB_TAG = "DshWebView"

/** Shell facts worth logging after a load: boot globals, DOM size, and whatever text rendered */
private const val SHELL_PROBE = """
(function () {
  var root = document.getElementById('root')
  var probe = document.createElement('div')
  probe.style.cssText = 'position:absolute;left:-1px;width:1px;height:100vh'
  document.body.appendChild(probe)
  var vh = probe.getBoundingClientRect().height
  probe.remove()
  return JSON.stringify({
    ready: typeof globalThis.__DSH_BOOT_READY__,
    boot: typeof globalThis.__DSH_BOOT__,
    loader: typeof globalThis.__ModuleLoader__,
    rootChildren: root ? root.childElementCount : -1,
    htmlLength: document.documentElement.outerHTML.length,
    innerHeight: innerHeight,
    vh: vh,
    text: (document.body.innerText || '').slice(0, 80)
  })
})()
"""

/**
 * What the host is doing while there is no page to show yet
 * @param status the state to describe.
 * @param modifier layout modifier from the caller.
 */
@Composable
private fun HostBootPanel(status: HostStatus, modifier: Modifier = Modifier) {
    val headline = when (status) {
        is HostStatus.Idle -> stringResource(R.string.host_status_idle)
        is HostStatus.Installing -> stringResource(R.string.host_status_installing, status.done, status.total)
        is HostStatus.Starting -> stringResource(R.string.host_status_starting)
        is HostStatus.Failed -> stringResource(R.string.host_status_failed, status.reason)
        is HostStatus.Running -> stringResource(R.string.host_status_running)
    }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(text = headline)
            }
        }
        items(DshHost.log) { line ->
            Text(text = line, modifier = Modifier.fillMaxWidth())
        }
    }
}
