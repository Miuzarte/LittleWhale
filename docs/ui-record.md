# 界面这一轮记录: 设置页边距 / 名字 / 过渡风格 / 状态栏 (2026-09-22 真机验过)

这一轮不是路线图上的一步, 是把已经能跑的骨架按 SFA 的界面重排一遍。改的都是看得见的那层, 没有动屏幕能力

判据: 设置页的分段与缩进和 SFA 一致, 名字与 SFA 的 `values-zh/strings.xml` 一致, 过渡风格有 AOSP 而没有 "无", 手动切深色时状态栏图标跟着变 —— 都在 192.168.1.103 上截图核过

## 缩进是在 LazyColumn 那一层给的, 不是每个选项自己给的

先把问题问清楚: SFA 那套里, 页面的左右缩进是谁给的? 两边都有现成代码, 而它们给的位置不同:

| 出处 | 缩进在哪 |
| :-- | :-- |
| miuix example `SettingsPage.kt` | **每个 Card 自己**: `Card(modifier = Modifier.padding(12.dp))`, 用的还是 Compose 自带的 `LazyColumn` |
| SFA `scaffolds/LazyColumn.kt` + `scaffolds/SectionSmallTitle.kt` | **列表这一层**: 脚手架的 `contentPadding` 里加上 `horizontalPadding = 12.dp`, Card 一个字的水平外边距都不写 |

结论: **照 SFA 那种**, 因为缩进放在一处才谈得上"统一", 而且那个脚手架顺带把别的也一次给全: 段间距 (`itemSpacing` + `Arrangement.spacedBy`)、首尾留白、横屏限宽 640dp 居中、`overScrollVertical`、`scrollEndHaptic`、点空白收键盘。逐个 Card 加 padding 的话, 这五件事都要各写一遍

Miuix 的 `Card` 自己 `insideMargin` 是 `PaddingValues(0.dp)`, **它不带内边距** —— 设置项 (`SwitchPreference` 这些) 才各自带 `BasicComponentDefaults.InsideMargin = PaddingValues(16.dp)`。所以 Card 的直接内容如果是设置项, 再套一层 `padding(16.dp)` 就是 32dp, 与别的段对不齐, 这一轮把「无障碍」与「网络」里那两处套错的拆掉了 (开关行直接放 Card, 只有纯文字与按钮那些内容留一层 `padding(UiSpacing.Large)`)

### SmallTitle 到 Card 的距离

Miuix 的 `SmallTitle` 默认 `insideMargin = PaddingValues(28.dp, 8.dp)` —— 左右 28dp、上下 8dp。SFA 的 `SectionSmallTitle` 把它改成 `(16.dp, 8.dp)`, 于是段标题的左边缘 = 12 (页面缩进) + 16 = 28dp, 正好落在 Card 里设置项标题的左边缘上 (12 + 16); 而它上下各 8dp, 就是标题到下面 Card 的距离

原来的排法两头不对称: 段与段之间是 `Spacer(24.dp)` + 标题自己的上边距, 而标题到它下面那张 Card 只有 8dp。改成 SFA 那套之后, 段与段的距离由 `itemSpacing = 12dp` 给 (一段一个 `item`), 标题上下的 8dp 归标题自己

## 设置项名字照 SFA

设置页那一套字符串是硬编码中文 (见 AGENTS「代码风格」), 但**名字**要跟 SFA 的 `app/src/main/res/values-zh/strings.xml` 对得上, 免得同一个东西两边两种叫法:

| SFA 的 key | SFA 的写法 | 原来这边写的 |
| :-- | :-- | :-- |
| `pref_title_monet` / `pref_summary_monet` | Monet 颜色 / 开启后使用 Monet 动态配色 | 动态取色 / 从壁纸或指定颜色派生整套配色 |
| `pref_title_monet_key_color` | Monet Key Color / 设置 Monet 强调色 | 取色 / 动态取色用来派生配色的种子颜色 |
| `pref_title_monet_palette_style` | Monet Palette Style / 设置 Monet 调色板风格 | 调色板风格 / 同一颗种子算出不同气质的一套颜色 |
| `pref_title_monet_color_spec` | Monet Color Spec / 设置 Monet 色彩规格 | 色彩规范 / Material 3 的两版色彩规范 |
| `pref_title_squircle` | 圆角矩形 / 在圆角 UI 元素上使用平滑的圆角矩形 | 圆角方屏 / 方形控件的圆角改成方圆形 |
| `pref_title_blur` | 模糊 / 启用顶栏和底栏的模糊效果 | 模糊 / 顶栏背后的模糊方式, 渐进模糊是上浓下淡 |
| `pref_title_transition_style` | 过渡风格 / 页面过渡动画样式 | 过渡样式 / 页面进入与退出时的动效 |
| `pref_title_enable_swipe_back` | 横滑返回 / 滑动已推入的页面以返回 | 侧滑返回 / 从屏幕左缘向右滑返回上一页 |

模糊那条的 summary 说的是"顶栏和底栏", 而这边没有底栏, **照抄了**: 一个东西两种说法比一句不精确更糟

**但模糊这一项同一天又整个去掉了** (见文末「模糊去掉了」), 所以那一行名字只活了几小时; 留在表里是因为它确实是这一轮做过的事。外观模式那一行本来就是 SFA 的 `theme_follow_system` / `theme_light` / `theme_dark`, 三个词早就是"跟随系统 / 浅色 / 深色", 没动

「无障碍」「工作区」「网络」三段是这边自己的, SFA 里没有对应项

## 过渡风格: 只有 Miuix 与 AOSP

`transitionStyles` 原来写的是 `listOf("Miuix 默认", "无")`, 现在按 SFA 的 `pref_transition_style_miuix` / `pref_transition_style_aosp` 改成两项, **"无" 这个选项去掉了**:

```kotlin
private val transitionStyles = listOf("Miuix", "AOSP")
```

- `Miuix` → `NavTransitions.MiuixDefault` (整页从尾缘平移进来)
- `AOSP` → `CrossActivityTransition`, 即平台那种"上层缩小让位"的手感

Miuix 0.9.4 的 `NavTransitions` 里**没有** AOSP 这个预设 (只有 `MiuixDefault` / `Modal` / `None`), 所以 AOSP 那一套是**搬代码**: SFA 把它 port 成了 `pages/CrossActivityTransition.kt` (281 行, 源头是 miuix example 的 `navigation/CrossActivityTransition.kt`, 那段注释留着), 这边原样搬到 `ui/CrossActivityTransition.kt`, 只改了包名与 import

顺带把 `NavDisplayEffects` 也跟着切, 与 SFA 的 `MainScreen` 一致:

```kotlin
cornerClipMode = if (crossActivity) NavCornerClipMode.All else NavCornerClipMode.Leading
```

**下标 1 的含义变了**: 原来是"无", 现在是 AOSP, 而 `theme` 那个 `SharedPreferences` 里存的就是下标, 老值不改写 —— 于是之前选过"无"的人升级后拿到的是 AOSP 而不是没有动效。这是自觉的取舍: 为一次性的选项迁移加一段读旧值改新值的代码, 比让人重选一次贵

验过: 选 AOSP 后 `theme.xml` 里 `transition=1`; 推入与返回时能抓到中间帧 (设置页只让开 96dp 并渐隐, 不是整页平移), 圆角裁剪在四角都生效

## 手动切深色时状态栏图标跟着走

症状: 外观模式钉成"深色"之后, 顶栏变深了而状态栏图标还是深的 —— 看不太清。原因是 `enableEdgeToEdge()` 那套默认跟的是**系统**主题, 与应用里手动钉的模式无关

修法照 SFA 的 `ui/SystemBars.kt`, 这边落在 `theme/SystemBars.kt`:

```kotlin
val isLight = colorScheme.background.luminance() >= 0.5f
SideEffect {
    val controller = WindowCompat.getInsetsController(w, w.decorView)
    controller.isAppearanceLightStatusBars = isLight
    controller.isAppearanceLightNavigationBars = isLight
}
```

两个要点: 读的是**当前渲染出来的** `colorScheme.background` 的亮度 (不是系统主题), 所以要放在 `MiuixTheme` 里面; 写的是 `isAppearanceLight*Bars`, 也就是"图标用深色还是浅色"。调用点就一句 `ApplySystemBarsAppearance(LocalActivity.current?.window)`, 在 `ui/AppNav.kt` 里紧贴着 `MiuixTheme` 打开

验过: 切"深色"后状态栏那串图标与时间变白, 切回"跟随系统"又变黑

## 主页顶栏下面那行字去掉了

`HostScreen` 原来在没有选中屏时打一行说明 ("还没有虚拟屏, 由 dsh 工具创建" / "没有选中虚拟屏"), 现在留空。有没有屏、选中哪一块, ⋮ 菜单里都写着, 页面上那行只是占位置

**只有 `VirtualScreen.lastError` 还留着一行**: 建屏 / 关屏失败时不给任何提示, 会把失败静默吞掉, 那不是"留空", 是丢信息

### 虚拟屏要不要放进 TopAppBar 的 `bottomContent`

问过这个问题, 读过源码之后**不放**, 理由是"没好处, 有代价":

- **高度上完全一样**: `Scaffold` 用 `topBarPlaceable.height` 算内容区高度 (`Scaffold.kt` 的 `availableHeight` 与 `innerPadding`), 而 `SmallTopAppBarLayout` 的 `layoutHeight = contentTop + bottomContentPlaceable.height` (`TopAppBar.kt:1082`)。所以画面在下方的 `Column` 里还是挂在 `bottomContent` 里, WebView 拿到的高度一模一样
- **代价一**: 那层 Layout 带 `.background(color)` `.clipToBounds()` 和一句吞点击的 `pointerInput` (`TopAppBar.kt:1021-1035`), 画面进去就归它们管
- 手感上还有一条: 进了顶栏就等于跟着顶栏的滚动折叠一起动, 而画面本来与顶栏无关

(当时还有一条"模糊 pass 会盖住整块画面"的代价, 那个 pass 后来连根删了, 见下)

## 设置页里的按钮改成占满宽度

Card 里的按钮 (`复制 URL` / `重启 host` ×2 / `授予所有文件访问` / `打开系统无障碍设置`) 都加了 `Modifier.fillMaxWidth()`。SFA 的 Card 里按钮与输入框也是 `fillMaxWidth`, 只有需求说的那两个的话, 同一页里就会一半满宽一半包内容

同时把这几段里的 `8.dp` / `4.dp` 换成了 `UiSpacing.Medium` / `UiSpacing.Small`

## 这一轮新增 / 改动的文件

| 文件 | 干什么 |
| :-- | :-- |
| `constants/UiSpacing.kt` | 新增。间距刻度, 只留了用得到的那几档 (`Small` / `Medium` / `PageItem` / `Large` / `PageHorizontal` / `PageVertical` / `ContentVertical`) |
| `scaffolds/LazyColumn.kt` | 新增。页面级滚动列表: 缩进、段间距、横屏限宽、overscroll、滚动到底触感。SFA 那个版本给底部栏留位的 `bottomInnerPadding` 没搬 (这边没有底部栏) |
| `scaffolds/SectionSmallTitle.kt` | 新增。段标题, 16dp / 8dp 的内边距 |
| `ui/CrossActivityTransition.kt` | 新增。AOSP 那套过渡, 从 SFA 原样搬来 |
| `theme/SystemBars.kt` | 新增。系统栏图标跟应用主题 |
| `ui/SettingsScreen.kt` | 一段一个 `item`, 名字照 SFA, 过渡风格两项, 按钮满宽, 缩进交给脚手架 |
| `ui/AppNav.kt` | AOSP 过渡 + `cornerClipMode` + 调一次 `ApplySystemBarsAppearance` |
| `ui/HostScreen.kt` | 顶栏下面那行占位文字去掉 |
| `theme/ThemeSettings.kt` | `TRANSITION_NONE` → `TRANSITION_AOSP` |

## 模糊去掉了 (同一天, 用户定)

用户的原话: **"顶栏因为要放预览所以模糊实际上没必要"**。顶栏下面就是虚拟屏那块画面, 而画面自己是不透明的 —— 顶栏糊的是自己背后那点东西, 而它背后恰恰是不透明画面, 于是这个选项从生到死都没有过用处

而且它本来就是坏的: `BlurredBar` 那条路要 `Modifier.layerBackdrop(backdrop)` 挂在内容上, 由它把内容录进 `GraphicsLayer`, 模糊才有东西可采样 (`LayerBackdrop.drawBackdrop` 在 `layerCoordinates == null` 时直接 return)。`HostScreen` 与 `SettingsScreen` 都没挂, 所以选"高斯模糊"时顶栏只是**半透明的表面色** —— 实测截图上, 顶栏后面的文字清清楚楚透出来, 一点没糊。本来修的方案是 SFA 那种一行 (`Box(modifier = if (blurActive) Modifier.layerBackdrop(backdrop) else Modifier)`), 要权衡的是把 WebView 每帧录进 `GraphicsLayer` 的代价; 现在不用权衡了, 连根删

删掉的东西 (没有留死代码):

| 删了什么 | 说明 |
| :-- | :-- |
| 设置页的「模糊」一行 + `blurModes` | 顶栏没有模糊了 |
| `theme/Blur.kt` **整个文件** | `BlurredBar` / `rememberBlurBackdrop` / `LocalEnableBlur` / `LocalBlurMode` 全在这里, 没有别的用途 |
| `ThemeSettings.blur` 字段 + `BLUR_*` 三个常量 + 存储键 | 存过的 `blur` 键留在 `SharedPreferences` 里不再读写, 无害 |
| 两个页面顶栏外面的 `BlurredBar` 包壳 | 现在是素 `TopAppBar` / `SmallTopAppBar`, 颜色用默认 (`colorScheme.surface`) |
| `AppNav` 里那两个 CompositionLocal 的 provide | 只剩 `LocalRootNavigator` 与 `LocalSquircleEnabled` |
| **`miuix-blur` 依赖** (gradle 与版本目录两处) | 全项目只有 `theme/Blur.kt` 用它, 而别的 Miuix 模块都不依赖它 (查过 POM), 所以依赖图里真的没有了 |

**副作用是 `minSdk` 松绑了**: 那条"用了 `miuix-blur` 就必须 >= 33"的约束随之消失, `minSdk = 33` 从"被顶上去的"变成"选的值" (无障碍取树要 API 30+, 33 以下没验过)。**没有顺手往下放** —— 放多少是产品决定, 注释里写清楚了现状

验过: 设置页的界面树里 `text="模糊"` 查不到了 (外观那一段只剩 Monet 颜色与圆角矩形), 两个页面的顶栏照旧, 深色下状态栏图标照旧

## 有画面在看的时候, 顶栏整个让位 (2026-09-22, 用户定)

起因是用户觉得顶栏那个标题白占地方。先排掉一个看起来更省事的做法: **把标题设成空字符串** —— 不行, `SmallTopAppBarLayout` 的高度是 `layoutHeight = contentTop + bottomContent`, 而 `contentTop = maxOf(collapsedHeight, ...)`, `TopAppBarDefaults.CollapsedHeight = 52.dp`, 也就是**标题空不空都一样高**, 那 52dp 是白给的 (标题本来也不额外占高度)。要让位就得整条顶栏不放

于是: **有画面在看时 `topBar` 整个为空**, 菜单按钮改成浮在画面右上角; 不看画面了 (收起, 或没选中屏) 就回到原来的 `SmallTopAppBar` (标题 + 菜单)

### 悬浮按钮怎么定位的

三层叠出来, 说清楚免得下次又调半天:

1. `Box` + `Modifier.align(Alignment.TopEnd)`: Scaffold 的 content 是 `bodyContentPlaceable.place(0, 0)` (`Scaffold.kt:351`), 也就是**整个窗口** (edge-to-edge), 所以这一层的 TopEnd 是屏幕的右上角, 不是状态栏下面
2. `Modifier.padding(top = innerPadding.calculateTopPadding(), end = innerPadding.calculateRightPadding(layoutDirection))`: 把状态栏那点高度补回来。没有 topBar 时 `innerPadding.top` 就是状态栏 inset (`Scaffold.kt:328-332`)
3. 按钮自己再留一点: **上下与右边给同样的 8dp** (`UiSpacing.Medium`), 实测屏幕上就是上 23px / 右 23px, 对称

第一版漏了第 2 层, 按钮落到状态栏里去了 —— 白底压在白色状态栏上, 看着像"根本没画出来", 而日志又明明说它 compose 了。查法是临时把底色刷成红色看图, 一眼就看见它其实在状态栏的高度上

### 碰一下画面就回来 (自动隐藏)

`AnimatedVisibility` + `fadeIn/fadeOut`, 静 3 秒淡出 (`CONTROLS_IDLE_MS = 3000`), 淡出**结束之后内容真的从树里拿掉**, 所以藏起来的按钮不会继续吃右上角那一片的点击 —— 那一片属于画面, 手指该落在画面上 (这也是不用"只把 alpha 调到 0"的原因: 那样按钮还在, 会一直挡着)

"碰画面"这件事有两处要接, 缺一个就有一半区域点不动:

- 画面**两边是黑边** (屏是竖的 1080x2400, 按宽度比缩完只占中间一条), 黑边上的手下面那层手势收不到, 所以 `VirtualScreenPreview` 最外层 Box 上挂一个只看不消费的 `awaitFirstDown(requireUnconsumed = false)`
- 画面**自己**上的手要给外面知道, 但落在 `AndroidView` (interop 的 SurfaceView) 上的事件外层收不到 —— 所以画面那层手势里也要叫一声

两处都接上之后, 点画面中间、点两边的黑边都能把按钮叫回来 (实测)

### 菜单开着的时候不能淡出

`AnimatedVisibility` 淡出会把内容移出树, 而 `OverlayCascadingListPopup` 是挂在按钮下面的, 于是**菜单会跟着按钮一起消失** (3 秒后自己没了)。所以按钮的可见性是 `controlsVisible || menuOpen`: 菜单开着就一直露着, 关掉再重新计时

## 菜单与预览的几处定稿 (2026-09-22, 用户定)

- **二级菜单里不再有"收起画面/展开画面"**: 选中哪块看哪块, **再点一次同一块就是不看** (取消选中), 那个总开关多余。于是 `expanded` 这个状态整个删掉了, "选中" 就等于 "在显示"
- **新增「暂停接受控制」/「继续接受控制」**, 位置在「关闭这块虚拟屏」上方, 作用对象与关闭一样是**当前选中的那块** (summary 写着名字)。新造的屏默认接受控制
  - 它停的是**模型的手**: `tap` / `swipe` / `tapText` / `launch` 一律拒绝, 错误信息点名是哪块屏并让模型转告用户 (`... tell them to resume it from the phone's menu (虚拟屏 -> <名字> -> 继续接受控制)`)
  - **看的不拦**: `lw_screenshot` / `lw_ui` / `lw_screen` 照旧, 这样模型至少能说清"我在看着这块屏, 但你把我停了"
  - **用户自己的手指不拦**: 预览上的触摸走的是另一条路 (`press`/`move`/`release`), 暂停不碰它 —— 停的是模型, 不是拿着手机的人
  - 排队里的活儿也停: `tap`/`swipe` 压进单线程触摸队列, 检查在队列块**里面**再做一次, 否则排在后面的那条会在用户按下暂停之后才执行
- **一级菜单里新增「虚拟屏触摸控制」, 默认不选中** (`PreviewControl`, 存在 `littlewhale` 那个偏好文件里), 挨着 `虚拟屏` 那一项、在 `设置` 上面。关着时画面只看不动, 手指滑过只把悬浮按钮叫出来, 不发 `press`/`move`/`release` —— 手机就摆在手边, 碰一下就打乱模型正在做的事
  - 一开始它是放在设置页的「虚拟屏」一段里的 (一个 SwitchPreference), 用户说那样切起来不方便, 当天就挪到一级菜单了, 设置页那一段也跟着删掉 —— **它是"现在这块画面能不能摸", 得边看边切**
  - 选中状态用 `DropdownItem.selected`: 开着时整行是强调色并带一个对勾, summary 也跟着换 ("手指在预览画面上滑动就是操作那块屏" / "画面只看不动, 免得误触打乱模型正在做的事")

## 工具那边怎么知道"这块屏是人关的还是模型关的" (2026-09-22)

用户从菜单上关掉一块屏之后, 模型下一步动那个 id 只得到一句 `no screen with displayId N`, 看不出这是**人的决定** —— 它可能当自己写错了 id, 再试一次, 或者干脆换一块屏继续干。做成两条独立的理由 (`VirtualScreen.missingScreenReason`):

| 情况 | 模型看到的 |
| :-- | :-- |
| 用户在菜单里关的 | `the user closed the virtual screen "X" (displayId N) from the phone's menu, so it is gone - stop and ask them what they want before acting again` |
| 模型 (或另一个会话) 用 `lw_screen_release` 关的 | `... was closed by an agent calling lw_screen_release rather than by the user, and screens are shared, so another session may be the one that closed it - the id is gone either way, do not make a replacement without asking` |
| 从来没存在过 (或忘掉了) | `no screen with displayId N, ask screen for the ones that exist` |

怎么知道的: `VirtualScreen.release(screen, byUser)` 记下**谁关的**, 菜单传 `byUser = true`, 桥 (`lw_screen_release`) 走默认的 false, 记在一条只留最近 8 条的 `LinkedHashMap` 里 (**一个 id 回头会被新屏重用, 记太久反而会说错**)。四条会动手的调用 (`tap` / `swipe` / `tapText` / `launch`) 加上 `screenshot` / `ui` / `release` 都走 `namedScreen`, 所以一处改动全都拿得到

模型那边的说明也一起补齐了 (工具描述是模型唯一会读的东西):

- `DISPLAY_ID` 这个**共享参数**的描述里写清: 报错说屏没了或被暂停, 那是人的操作, 要停下来问用户, **不要重试 id, 也不要自己换一块屏接着干** (共享参数一次覆盖所有动手的工具)
- `lw_screen` 里写明两种情况 (没了 / 被暂停), 以及"没了"要动那个 id 才会知道原因
- `lw_screen_release` 里写明**屏是跨会话共享的**, 不是自己为这个任务建的屏就别关, 先问用户

**做不到、也不做的一件事**: 桥是回环 TCP, 上面没有会话身份, 所以第二条只能说"某个会话关的", 说不出是哪一个。**用户 2026-09-22 定: 就这样够了, 不用把 agent / session id 顺着请求带进桥里** —— "是模型关的而不是用户关的"这句话本身已经够模型判断该不该停下来问了

## 怎么验的

- 设备 `192.168.1.103:5555`, `.\gradlew.bat :app:assembleDebug` → `adb install -r -t` → `am start -S -W`
- 截图逐张核: 设置页分段与缩进、深色下的状态栏、AOSP 选中的落盘值 (`theme.xml`)、按钮宽度、顶栏下的留空、去掉模糊之后的外观, 以及悬浮菜单的四个状态 (刚出现 / 静置 3 秒后淡出 / 碰画面回来 / 菜单开着不淡出)
- 暂停/继续那条用桥直接验 (`tools/lw-bridge.ps1`, 不走模型): 暂停后 `tap` / `swipe` / `tapText` / `launch` 四个都回同一条错误, `screenshot` 照旧成功, `screen` 里 `acceptsControl` 变 `false`; 继续之后又是 `true`
- 预览那个开关也用桥验: **关着时**在预览上点两下, 虚拟屏的界面树一个字都没变 (`lw_ui` 前后同样 9476 字符); **打开后**同样点一下, 树就变了 (翻到了别的页) —— 两个方向都成立
- 悬浮按钮的边距是用像素量出来的 (扫截图右上角那块的非黑像素包围盒: 上 23px / 右 23px), 不是拿眼睛看的
- 界面树用 `adb shell uiautomator dump` 取 (它走 UiAutomation, 不需要我们的无障碍服务), 用来看有没有那两行占位文字、按钮行的实际矩形、模糊那一行还在不在。**但画面在看的时候这个 dump 只有 3373 字节、一个 text/desc 节点都没有**, 那种时候只能靠截图与日志 (`LwHome` 那几行临时日志, 查完删掉了)
- 收尾: 外观模式 / 过渡风格都恢复默认, 设备上 `theme.xml` 是 `mode=0 / transition=0` (那个 `blur` 键已经不再写); 虚拟屏全部释放; `svc power stayon` **没动** (用户自己开着 15)

## 第二轮 (2026-09-22): 「截图」那一档与收成一个 ⋮

两件小事, 都是用户定的:

**「截图」段 = 截图预算**. 桥原来写死 `Picture.DEFAULT_MAX_PIXELS` (640000), 现在由 `channel/ScreenshotBudget.kt` 给, 三档都摆在设置页那一行下拉里:

| 档 | 像素 | 来由 |
| :-- | :-- | :-- |
| 低 | 262144 | dsh 的 `imagePixelBudget: low` (512x512) |
| 默认 | 640000 | dsh 自己的缺省 |
| 高 | 1690000 | DeepSeek 那头处理图片的预算 (约 1300x1300) |

**为什么不做"自动跟着 dsh 配置走"** (那是路线图里原来写的那条): app 读不到每个模型各自的 `imagePixelBudget`, 插件那侧也不知道当前会话走哪条 route, 硬凑出来的只会是一个猜出来的数。于是改成用户在设置页选, 并把**那一档不能超过路由允许的预算**这件事写进设置项的说明里 (超了要在 host 那边重新编码, 而设备上没有编码器, `read_image` 会直接失败) —— 是个看得见的取舍, 不是暗坑

实测 (1080x2400 的主屏, 同一张屏): 低档 → 图 343x763 (`scale 3.148688`), 默认档 → 536x1192 (`scale 2.0149255`), 与 `sqrt(budget / 2580000)` 算出来的对得上

**⋮ 收掉两个「重启 host」按钮**. 工作区那段与网络那段各有一个, 现在合成设置页右上角一个 `OverlayIconDropdownMenu`, 里面一条 `重启 host`; 页面上那两处改成一句"改完在右上角 ⋮ 里重启 host"。**用 Miuix 的而不是 material3 的**: material3 不是本项目的依赖 (只有 `material3-window-size-class`), 而 Miuix 的 `DropdownEntry` / `DropdownItem` 与主页那个 ⋮ 是同一套, `top.yukonga.miuix.kmp.icon.extended.More` 那个图标要单独 import (光 import `MiuixIcons` 不够)

实测: ⋮ 点开显示「重启 host / 让工作区与网络这两段的改动生效」; 点它之后 logcat 里旧 host 的 output reader 被 close, 新 host 打出一条**新 token** 的就绪行, `lw_probe` 重新连上 (新 pid) —— 重启是真生效的, 不是只换了个地方放按钮


## 第三轮 (2026-09-22): 「网络」那两个按钮与无障碍开关

- **「网络」段的两个按钮**: `分享` 占 1/4, `复制` 占 3/4 (`Modifier.weight(1f)` / `weight(3f)`, 中间 8dp), **都用 Miuix `Button` 的默认配色** —— `ButtonDefaults.buttonColors()` 本来就是 `secondaryVariant`, 强调色是 `buttonColorsPrimary()` 那一支, 这里不强调
- 顺序改成**地址在前, 按钮在后**: 拨完开关先看到 `在别的设备上打开` 与那串带 token 的 URL, 两个按钮才作用在看得见的东西上, 说明放最后。整段仍只套**一层** `padding(UiSpacing.Large)` (套两层就是 32dp)
- `分享` 是 `ACTION_SEND` + `text/plain` 交给别的应用 (原来那里是 `TODO()`, 一点就崩); URL 为 null (host 没带 LAN 起) 时两个按钮都 disabled
- **「无障碍」那段**: 开关自己就会经特权通道把服务打开 (做法与实测见 `docs/step7-record.md` 末尾的补记), 下面那条改成兜底 `在系统设置里打开` / `自动开启不成功时再用这一条`, 出错时 summary 换成真实原因

## 第四轮 (2026-09-22): 设置页的字符串搬进 strings.xml

2026-09-21 定的是"设置页那一套硬编码中文, 不做多语言", **这一轮作废**: 设置页的文本全部进了资源, 与 HostScreen 同一套写法 (`stringResource(R.string.…)`), 键以 `settings_` 开头, 措辞照 SFA 的 `values-zh/strings.xml`。`app/src/main/java/.../ui/SettingsScreen.kt` 里现在**一个字符串字面量都没有** (注释除外)

几条做法:

- **两份文件键与顺序都保持一致** (便于逐条对照), 占位符 (`%1$s` / `%1$d`) 也必须一一对上 —— 这两条是脚本查的, 不是靠眼睛
- 那三个列表 (外观模式 / 过渡风格 / 截图预算) 原来是非 Composable 的 `val`, 现在存**资源 id**, 在 Composable 里 `map { stringResource(it) }`
- **截图预算的三档标签**连带改了: `ScreenshotBudget.levels` 从 `Pair<String, Int>` 变成 `Pair<Int, Int>` (资源 id 与像素数), 数字由 `%1$d` 传进去, 免得标签里的数字与数据两处各写一遍
- 非 Composable 的地方 (`copyRemoteUrl` / `shareRemoteUrl`) 用 `context.getString(…)`
- **留在代码里的**: `LwOcr.backend.label` / `LwOcr.note` / `AccessibilitySetting.lastError` —— 前者是模型那一侧的数据 (桥也把它们交给工具), 后者是运行时诊断文本, 都不是这一页的文案
- `a11y_description` 由用户改成"以读屏换掉一部分端侧 OCR"的说法, 并挪到 `app_name` 后面; **en 那句跟着改并挪到同一位置**

验证: 设备上按 `cmd locale set-app-locales <pkg> --user 0 --locales en-US` 挂了一次**按应用的语言**, 重启应用后整页是英文 (Settings / Appearance / Follow system / Monet colors / Squircle corners / Navigation / Transition style / Swipe back / Accessibility / Open it in system settings…), 中文那套与改动前一模一样; 看完 **把那个按应用的语言清掉** (`set-app-locales <pkg> --user 0`, 不带 `--locales` 就是空列表, `get-app-locales` 回来是 `[]`)

## 第五轮 (2026-09-23): 截图的两条预算, 两条滑块

用户要的是"字节预算也做成设置项", 并且觉得**尺寸那条也能改成带吸附点的滑块**, 交互更好。于是「截图」段从一档下拉变成**两条 `ArrowSlider`**:

| 那条 | 形状 | 值 |
| :-- | :-- | :-- |
| 截图预算 (像素) | 离散三档, 吸附点就在三个位置上 | 低 262144 / 默认 640000 / 高 1690000 |
| 截图字节预算 | **连续**滑动, 吸附点 256 / 512 / 768 / 1024 KiB | 上端 1 MiB, 打字可给到 4096 KiB |

组件是**从 SFA 搬的**: `scaffolds/ArrowSlider.kt` 与 `scaffolds/SuperTextField.kt` (只改包名与 import), 外加 `ui/Haptic.kt` 里两个短名 (`contextClick` / `confirm`)。搬它而不是用 Miuix 自带的 `SliderPreference` (0.9.4 有, 而且它的箭头是 `showArrow = onClick != null`), 是因为 SFA 那个多一条**点标题那一行打字给精确值**的路 —— 而字节预算存在的意义就是"对上 `settings.yaml` 里的数字", 那个数可以是任何一个

### 字节预算为什么上端停在 1 MiB

这是这一轮唯一被用户追问过的地方。**两条预算给的都是 app 这一半** —— 截图产生时缩到多少, 写进 `littlewhale.xml`; 路由那一半在 dsh 自己的 `settings.yaml` 里 (这台是 `imagePixelBudget: 640000` / `imageMaxBytes: 1048576`), **app 读不到也写不到**。所以:

| 滑块的值 | 实际作用 |
| :-- | :-- |
| 低于路由 | 有用, 更保守, 代价只是模型看得更糊 |
| 等于路由 (1024 KiB) | 就是默认这一档 |
| 高于路由 | **没有任何好处**, 只会把注定被拒的图交给 host (超了要重新编码, 而设备上没有编码器) |

所以滑块**滑不出去**, 而打字能到 4096 KiB —— 那是给"路由也一起调高了"的人留的口子, 对话框的说明里写着这件事。第一版的说明写成"要对齐 DSH 那条路由的 imageMaxBytes", 被用户读成"这个滑块就是那个设置", 已改成"应用这一半 / 路由那一半"的说法

### 三个坑

- **点滑块轨道会弹出对话框, 不是跳值**: Miuix 的 `Slider` 只挂了 `draggable` (`Slider.kt:203`), 点一下它不消费, 事件落到 `ArrowPreference` 那层的 `onClick` 上 —— 整行都是"点开输入框"的热区。**拖动是正常的**, 吸附也是准的 (实测随手一拖落盘成 `screenshot-bytes=2097152`, 正好是 2048 KiB 那个吸附点)。要改成"点轨道直接跳值"就得给滑块包一层自己的 `detectTapGestures`
- **AAPT2 对 en 字符串里的撇号报 `Invalid unicode escape sequence`**: 那两句里的 `app's` / `slider's` 让 `mergeDebugResources` 直接失败, 报错信息完全没提撇号 (改写措辞解决, 没去 escape)
- **存量值可以停在滑块够不到的地方**: 用户试出来那个 2048 KiB 大于新的上端, 于是滑块顶到最右而右边文字照实写 `2048 KiB`。**没有在 `initialize` 里静默夹到范围内** —— 打字本来就能给到 4096, 那种值是有人故意配的; 但这一轮验完把它**拖回** 1024 KiB 了 (那是个注定被拒的值)

### 实测

- 拖动落盘: `shared_prefs/littlewhale.xml` 里 `screenshot-bytes=1048576`, 界面显示 `1024 KiB`; 三种吸附点都吸得住
- 对话框: 标题那行点开 → `EditText` 预填当前 KiB 数 → `确定` 落盘; 打进去超出范围的值**不写盘也不关框** (范围校验真的在起作用)
- `lw_type` 默认是**插入到光标处** (`replace` 才覆盖), 所以往预填的 `2048` 上打字会变成 `20481024` —— 这不是 bug, 是那条工具的契约 (它本来就给模型一个 `replace` 参数), 顺带把上面那条范围校验验了
- 收尾: 设备上那两条是 `screenshot-budget=1` (默认档) 与 `screenshot-bytes=1048576`

