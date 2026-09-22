package io.github.miuzarte.littlewhale.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.miuzarte.littlewhale.R
import io.github.miuzarte.littlewhale.channel.AccessibilitySetting
import io.github.miuzarte.littlewhale.channel.ChannelSetting
import io.github.miuzarte.littlewhale.channel.LwOcr
import io.github.miuzarte.littlewhale.channel.RemoteBackend
import io.github.miuzarte.littlewhale.channel.RouteState
import io.github.miuzarte.littlewhale.channel.ScreenshotBudget
import io.github.miuzarte.littlewhale.constants.UiSpacing
import io.github.miuzarte.littlewhale.host.DshHost
import io.github.miuzarte.littlewhale.host.HostSettings
import io.github.miuzarte.littlewhale.scaffolds.ArrowSlider
import io.github.miuzarte.littlewhale.scaffolds.LazyColumn
import io.github.miuzarte.littlewhale.scaffolds.SectionSmallTitle
import io.github.miuzarte.littlewhale.theme.MonetKeyColorOptions
import io.github.miuzarte.littlewhale.theme.ThemeSettings
import io.github.miuzarte.littlewhale.theme.ThemeStore
import io.github.miuzarte.littlewhale.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.menu.OverlayIconDropdownMenu
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/**
 * 设置页的字符串都在 `strings.xml` 里 (en 与 zh 各一份), 名字以 `settings_` 开头, 措辞照 SFA 的
 * `values-zh/strings.xml` - 名字对齐了才不用记两套说法
 *
 * 这一页原来硬编码中文 (2026-09-21 定的"不做多语言"), 2026-09-22 改成跟着资源走
 */

/** 外观模式, 与 `ThemeSettings.mode` 的下标一一对应 (SFA 的 theme_follow_system / light / dark) */
private val themeModes = listOf(
    R.string.settings_theme_follow_system,
    R.string.settings_theme_light,
    R.string.settings_theme_dark,
)

/**
 * 过渡风格, 与 `ThemeSettings.transition` 的下标一一对应
 *
 * 就是 SFA 的 `pref_transition_style_miuix` 与 `pref_transition_style_aosp` 两项, 没有 "无": 页面
 * 切换要么是 Miuix 那种整页平移, 要么是平台那种缩小让位
 */
private val transitionStyles = listOf(
    R.string.settings_transition_miuix,
    R.string.settings_transition_aosp,
)

/** 调色板风格与色彩规范直接取 Miuix 的枚举名, 它们是 Google 那边的术语 */
private val paletteStyles = ThemePaletteStyle.entries.map { it.name }
private val colorSpecs = ThemeColorSpec.entries.map { it.name }

/**
 * 设置页
 *
 * 它自己是滚动的, 顶栏跟着滚; 缩进与段间距都由 [LazyColumn] 那个脚手架统一给, 一段一个 item,
 * Card 自己不写外边距 - 这是照 SFA 的排法, 免得每段各缩各的
 *
 * 外观那几项改完立刻作用到整个应用, 因为主题是 [LittleWhaleTheme] 外面那一层在管; 工作区与网络从
 * 原来的两个面板搬到这里, 平铺成两段, 它们要重启 host 才生效
 */
@Composable
fun SettingsScreen() {
    val navigator = LocalRootNavigator.current
    val context = LocalContext.current
    val settings = ThemeStore.current
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = navigator.pop) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                // 要重启 host 才生效的事都收在这里, 页面上就不必每个面板各放一个重启按钮
                actions = {
                    OverlayIconDropdownMenu(
                        entry = DropdownEntry(
                            items = listOf(
                                DropdownItem(
                                    text = stringResource(R.string.settings_restart_host),
                                    summary = stringResource(R.string.settings_restart_host_summary),
                                    onClick = { DshHost.restart(context) },
                                ),
                            ),
                        ),
                    ) {
                        Icon(
                            imageVector = MiuixIcons.More,
                            contentDescription = stringResource(R.string.settings_more),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = innerPadding,
            scrollBehavior = scrollBehavior,
        ) {
            item {
                SectionSmallTitle(stringResource(R.string.settings_section_appearance))
                TabRow(
                    tabs = themeModes.map { stringResource(it) },
                    selectedTabIndex = settings.mode.coerceIn(0, themeModes.lastIndex),
                    onTabSelected = { ThemeStore.update(settings.copy(mode = it)) },
                )
                Spacer(modifier = Modifier.height(UiSpacing.ContentVertical))
                Card {
                    SwitchPreference(
                        title = stringResource(R.string.settings_monet),
                        summary = stringResource(R.string.settings_monet_summary),
                        checked = settings.monet,
                        onCheckedChange = { ThemeStore.update(settings.copy(monet = it)) },
                    )
                    AnimatedVisibility(settings.monet) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.settings_monet_key_color),
                            summary = stringResource(R.string.settings_monet_key_color_summary),
                            items = MonetKeyColorOptions,
                            selectedIndex = settings.seed.coerceIn(0, MonetKeyColorOptions.lastIndex),
                            onSelectedIndexChange = { ThemeStore.update(settings.copy(seed = it)) },
                        )
                    }
                    AnimatedVisibility(settings.monet && settings.seed > 0) {
                        Column {
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_monet_palette_style),
                                summary = stringResource(R.string.settings_monet_palette_style_summary),
                                items = paletteStyles,
                                selectedIndex = settings.palette.coerceIn(0, paletteStyles.lastIndex),
                                onSelectedIndexChange = { ThemeStore.update(settings.copy(palette = it)) },
                            )
                            OverlayDropdownPreference(
                                title = stringResource(R.string.settings_monet_color_spec),
                                summary = stringResource(R.string.settings_monet_color_spec_summary),
                                items = colorSpecs,
                                selectedIndex = settings.spec.coerceIn(0, colorSpecs.lastIndex),
                                onSelectedIndexChange = { ThemeStore.update(settings.copy(spec = it)) },
                            )
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.settings_squircle),
                        summary = stringResource(R.string.settings_squircle_summary),
                        checked = settings.squircle,
                        onCheckedChange = { ThemeStore.update(settings.copy(squircle = it)) },
                    )
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_navigation))
                Card {
                    OverlayDropdownPreference(
                        title = stringResource(R.string.settings_transition),
                        summary = stringResource(R.string.settings_transition_summary),
                        items = transitionStyles.map { stringResource(it) },
                        selectedIndex = settings.transition.coerceIn(0, transitionStyles.lastIndex),
                        onSelectedIndexChange = { ThemeStore.update(settings.copy(transition = it)) },
                    )
                    SwitchPreference(
                        title = stringResource(R.string.settings_swipe_back),
                        summary = stringResource(R.string.settings_swipe_back_summary),
                        checked = settings.swipeBack,
                        onCheckedChange = { ThemeStore.update(settings.copy(swipeBack = it)) },
                    )
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_channel))
                Card {
                    ChannelItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_accessibility))
                Card {
                    AccessibilityItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_screenshot))
                Card {
                    ScreenshotItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_ocr))
                Card {
                    OcrItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_workspace))
                Card {
                    WorkspaceItems()
                }
            }

            item {
                SectionSmallTitle(stringResource(R.string.settings_section_network))
                Card {
                    NetworkItems()
                }
            }
        }
    }
}

/**
 * 特权通道: 动屏幕的能力都要先有它, 而它要么经 root 要么经 Shizuku
 *
 * 连接本身**不在这里发起**: 应用启动时 `DshHostService` 就在后台连了一次 (连一次要起一个
 * app_process, 是秒级的, 不该落在模型第一次截图上), 所以这一页通常进来就是"已连接"。按钮只在
 * 没连上时出现, 它是**重试** —— 真正的用处是"刚在 KernelSU 里给完授权, 不想重启应用"
 *
 * 只列状态, 不解释: 两条路各自在哪一档一眼能看完。**root 那行可能是"未检测"**, 这不是偷懒 ——
 * 有没有 root 在应用侧查不到 (KernelSU 对没在名单上的应用把 `su` 整个收走, 而不是拿出来问一句),
 * 所以"没授权"与"还没试过"在试之前是同一件事
 */
@Composable
private fun ChannelItems() {
    val state = ChannelSetting.state
    val routes = ChannelSetting.routes
    val working = ChannelSetting.working
    LaunchedEffect(Unit) { ChannelSetting.refresh() }
    // fillMaxWidth 是必要的: 这一段的子项全是纯文字, 撑不满宽度, 而 Miuix 的 Card 是包着内容的 ——
    // 不填满, 卡片就比这一页别的卡片窄一截 (设置项自己会撑开, 所以别处不用管)
    Column(modifier = Modifier.fillMaxWidth().padding(UiSpacing.Large)) {
        Text(
            text = when {
                state == null -> stringResource(R.string.settings_channel_reading)
                state.connected -> stringResource(
                    R.string.settings_channel_connected,
                    state.backend.orEmpty(),
                    state.uid ?: -1,
                )

                else -> stringResource(
                    R.string.settings_channel_disconnected,
                    state.error.orEmpty(),
                )
            },
        )
        routes.forEach { route ->
            Text(
                text = stringResource(
                    R.string.settings_channel_route,
                    route.backend.label,
                    stringResource(routeState(route)),
                ),
                modifier = Modifier.padding(top = UiSpacing.Medium),
            )
        }
        if (state?.connected != true) {
            Button(
                onClick = { ChannelSetting.connect() },
                enabled = !working,
                modifier = Modifier.fillMaxWidth().padding(top = UiSpacing.Medium),
            ) {
                Text(
                    text = stringResource(
                        if (working) R.string.settings_channel_connecting else R.string.settings_channel_connect,
                    ),
                )
            }
        }
    }
}

/** 一条路在哪一档, 只有 root 会有"未检测": 别的那两个问题不用试就能答 */
private fun routeState(route: RouteState): Int = when (route.backend) {
    RemoteBackend.ROOT -> when (route.granted) {
        true -> R.string.settings_channel_state_allowed
        false -> R.string.settings_channel_state_denied
        null -> R.string.settings_channel_state_unknown
    }

    RemoteBackend.SHIZUKU -> when {
        !route.available -> R.string.settings_channel_state_stopped
        route.granted == true -> R.string.settings_channel_state_allowed
        else -> R.string.settings_channel_state_denied
    }
}

/** 无障碍读屏: 模型按名字操作, 而不是在缩过的截图上量坐标 */
@Composable
private fun AccessibilityItems() {
    val context = LocalContext.current
    val working = AccessibilitySetting.working
    val error = AccessibilitySetting.lastError
    LaunchedEffect(Unit) { AccessibilitySetting.refresh() }
    SwitchPreference(
        title = stringResource(R.string.settings_accessibility),
        summary = stringResource(R.string.settings_accessibility_summary),
        checked = AccessibilitySetting.enabled,
        enabled = !working,
        onCheckedChange = { AccessibilitySetting.set(it) },
    )
    // 开关自己就会经特权通道把服务打开, 这一条是写不进去时的兜底 (系统那页里能手动打开它)
    ArrowPreference(
        title = stringResource(R.string.settings_accessibility_manual),
        summary = error ?: stringResource(R.string.settings_accessibility_manual_summary),
        onClick = {
            context.startActivity(accessibilitySettingsIntent())
        },
    )
}

/** 系统那页, 万一特权通道写不进去还有一个能手动开的地方 */
private fun accessibilitySettingsIntent(): Intent =
    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * 一屏画面交给模型之前要过的两条预算: 先按**像素**缩到一个尺寸, 再按**字节**缩到装得下
 *
 * 两条都用滑块而不是下拉, 但形状不一样, 因为它们要对的数字性质不一样:
 *
 * - **像素那条是三档**, 滑块就只有三个位置。那三个数分别对应 dsh 的 `low` / 缺省 / DeepSeek 那头
 *   处理图片的预算, 中间的值没有对应的预算可言, 而越过那条路由的预算会让整个模型请求失败 (设备上
 *   编不了图) —— 所以打字也只会落到最接近的一档
 * - **字节那条是连续的**, 中间那几个吸附点把 256 KiB 到 1 MiB 三等分。它是连续的是因为要对齐的数字
 *   在 `settings.yaml` 里, 那个数可以是任何一个, 而滑块只负责给常见的几个, 真要对上就点标题那一行打字
 *
 * **一句话分清楚两半账**: 这两条给的都是 **app 这一半** —— 截图在产生时缩到多少, 而路由那一半
 * (`imagePixelBudget` / `imageMaxBytes`) 在 dsh 自己的 `settings.yaml` 里, app 读不到也写不到。app 这一半
 * 高于路由那一半不但没用, 还会把注定被拒的图交出去, 所以**两条滑块的上端都停在路由缺省那个数**
 * (像素那条的"高"档是唯一的例外, 见上), 而**打字**能给的更宽 —— 那是给"路由也一起调高了"的人留的口子
 *
 * 两条都写着那句"超了会失败": 这不是吓唬, 是这台设备上真实的下场 (见 `Picture` 的注释)
 */
@Composable
private fun ScreenshotItems() {
    val context = LocalContext.current
    val levels = ScreenshotBudget.levels
    val last = levels.lastIndex
    val levelNames = levels.map { stringResource(it.first, it.second) }
    ArrowSlider(
        title = stringResource(R.string.settings_screenshot_budget),
        summary = stringResource(R.string.settings_screenshot_budget_summary),
        value = ScreenshotBudget.index.toFloat(),
        onValueChange = { ScreenshotBudget.set(context, it.roundToInt().coerceIn(0, last)) },
        valueRange = 0f..last.toFloat(),
        // 三档 = 端点之间只有一个离散点, 这是 Compose 那个 steps 的算法
        steps = (levels.size - 2).coerceAtLeast(0),
        showKeyPoints = true,
        keyPoints = levels.indices.map { it.toFloat() },
        displayFormatter = { levelNames[it.roundToInt().coerceIn(0, last)] },
        // 打字给的是像素数, 但只落到最接近的一档, 所以对话框里把那三档列出来
        inputSummary = levelNames.joinToString(" / "),
        inputLabel = "px",
        inputInitialValue = ScreenshotBudget.pixels.toString(),
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = levels.first().second.toFloat()..levels.last().second.toFloat(),
        onInputConfirm = { raw ->
            val typed = raw.toIntOrNull() ?: 0
            ScreenshotBudget.set(
                context,
                levels.indices.minBy { abs(levels[it].second - typed) },
            )
        },
    )
    ArrowSlider(
        title = stringResource(R.string.settings_screenshot_bytes),
        summary = stringResource(R.string.settings_screenshot_bytes_summary, ScreenshotBudget.bytes),
        value = ScreenshotBudget.kib,
        onValueChange = { ScreenshotBudget.setBytes(context, it.roundToInt()) },
        valueRange = ScreenshotBudget.byteRange,
        // 连续滑动: 吸附交给 keyPoints 与 magnetThreshold, 设了 steps 反而滑不到中间的值
        steps = 0,
        showKeyPoints = true,
        keyPoints = ScreenshotBudget.byteKeyPoints,
        magnetThreshold = ScreenshotBudget.BYTE_MAGNET,
        unit = "KiB",
        // 打字能给的比滑块宽: 滑块上的每个值都该是能过那条路由的, 而打字是配另一条路由用的
        inputSummary = stringResource(
            R.string.settings_screenshot_bytes_dialog,
            ScreenshotBudget.byteRange.endInclusive.toInt(),
            ScreenshotBudget.byteInputRange.endInclusive.toInt(),
        ),
        inputLabel = "KiB",
        inputFilter = { text -> text.filter(Char::isDigit) },
        inputValueRange = ScreenshotBudget.byteInputRange,
        onInputConfirm = { raw ->
            raw.toIntOrNull()?.let { ScreenshotBudget.setBytes(context, it) }
        },
    )
}

/**
 * 端侧 OCR: 自绘界面上的字只能从像素里读
 *
 * 状态是**懒加载**来的: 模型要等第一次用到才建 session (NPU 那边第一次还要现场编译图, 约 2 秒),
 * 所以这里平时显示"未加载", 那个按钮既是自检也是预热
 */
@Composable
private fun OcrItems() {
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    val backend = LwOcr.backend
    // label 与 note 来自模型那一侧 (桥也把它们交给工具), 所以它们不是这一页的字符串, 只有那一圈
    // 说明文字跟着资源走
    val unit = if (backend == LwOcr.Backend.NPU) {
        "${backend.label} (${Build.SOC_MODEL})"
    } else {
        backend.label
    }
    Column(modifier = Modifier.padding(UiSpacing.Large)) {
        Text(text = stringResource(R.string.settings_ocr_backend, unit))
        if (LwOcr.note.isNotEmpty()) {
            Text(text = LwOcr.note, modifier = Modifier.padding(top = UiSpacing.Medium))
        }
        Button(
            onClick = {
                working = true
                scope.launch {
                    withContext(Dispatchers.IO) { LwOcr.probe(3, null, false, "burst") }
                    working = false
                }
            },
            enabled = !working,
            modifier = Modifier.fillMaxWidth().padding(top = UiSpacing.Medium),
        ) {
            Text(
                text = stringResource(
                    if (working) R.string.settings_ocr_probe_busy else R.string.settings_ocr_probe,
                ),
            )
        }
    }
}

/** 工作区落在哪, 以及怎么改它 */
@Composable
private fun WorkspaceItems() {
    val context = LocalContext.current
    val root = DshHost.workspace
    val granted = Workspace.isManaging()
    val kind = when (root?.kind) {
        Workspace.Kind.Shared -> stringResource(R.string.settings_workspace_kind_shared)
        Workspace.Kind.Media -> stringResource(R.string.settings_workspace_kind_media)
        Workspace.Kind.Sandbox -> stringResource(R.string.settings_workspace_kind_sandbox)
        null -> stringResource(R.string.settings_workspace_kind_none)
    }
    // 路径是数据不是文案, 拿不到就不占一行
    val path = root?.directory?.absolutePath
    ArrowPreference(
        title = stringResource(R.string.settings_workspace_grant),
        summary = stringResource(R.string.settings_workspace_grant_summary, kind) +
            (path?.let { "\n$it" } ?: ""),
        enabled = !granted,
        onClick = {
            Workspace.requestAllFilesAccess(context)
        },
    )
}

/** 别的设备怎么打开这个 GUI */
@Composable
private fun NetworkItems() {
    val context = LocalContext.current
    var lan by remember { mutableStateOf(HostSettings.lanAccess(context)) }
    val remote = DshHost.remoteUrl
    SwitchPreference(
        title = stringResource(R.string.settings_lan),
        summary = stringResource(R.string.settings_lan_summary),
        checked = lan,
        onCheckedChange = {
            lan = it
            HostSettings.setLanAccess(context, it)
        },
    )
    // URL 与两个按钮不是设置项, 没有自带的内边距, 所以自己套上卡片里别的内容那一圈 16dp,
    // 否则它们会贴着卡片边 (设置项自己带 16dp, 纯文字与按钮不带)
    Column(modifier = Modifier.padding(UiSpacing.Large)) {
        remote?.let { Text(text = it) }
        // 分享只占四分之一, 剩下都给复制: 复制是常用的那个, 分享是偶尔发给另一台设备
        Row(
            modifier = Modifier.padding(top = UiSpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(UiSpacing.Medium),
        ) {
            Button(
                onClick = { remote?.let { shareRemoteUrl(context, it) } },
                enabled = remote != null,
                modifier = Modifier.weight(1f),
            ) {
                Text(text = stringResource(R.string.settings_share))
            }
            Button(
                onClick = { remote?.let { copyRemoteUrl(context, it) } },
                enabled = remote != null,
                modifier = Modifier.weight(3f),
            ) {
                Text(text = stringResource(R.string.settings_copy))
            }
        }
    }
}

/** 把 LAN URL 放进剪贴板, 它带着 host 的 token, 没人会手敲 */
private fun copyRemoteUrl(context: Context, url: String) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("dsh", url))
    Toast.makeText(context, context.getString(R.string.settings_url_copied), Toast.LENGTH_SHORT).show()
}

/** 交给别的应用, 发给另一台设备比手抄一串 token 靠谱 */
private fun shareRemoteUrl(context: Context, url: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, url)
    val chooser = Intent.createChooser(send, context.getString(R.string.settings_share_chooser))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}
