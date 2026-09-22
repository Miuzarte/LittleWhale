# 第 7 步记录: 无障碍文本与坐标 (2026-09-21 真机验过)

目标: **让模型不再用眼睛在截图上量坐标**。截图会被缩到 route 的像素预算 (1080x2400 → 536x1192), 40 px 的按钮在图上只有 20 px, 量完乘 2.01 回屏坐标, 误差跟着翻倍。这一步给出文字与**屏自己的坐标**, 并且能按名字直接按下

判据: 模型不看截图也能点对一个按钮, 而且它报出的坐标来自树 —— 已达成, 见末尾的模型回合

## 先跑的那个探针 (结论: 通了, 设计成立)

半小时的探针问一件事: **我们那块虚拟屏的窗口在不在 `AccessibilityService` 的窗口列表里**。答案分两半, 第一半是否定的, 而那正是关键:

| 问 | 答 |
| :-- | :-- |
| `AccessibilityService.getWindows()` | **只有默认屏**。建了 display 38 并在上面起了设置页, 这个列表仍然只有 display 0 的三个窗口 (`com.miui.home` / `com.android.systemui` / 我们自己) |
| `getWindowsOnAllDisplays()` (API 30+) | **有**。`windowsOnAllDisplays: 4 on [0, 38]`, 其中 `window display=38 ... pkg=com.android.settings bounds=[0,0,1080,2400]` |

**所以必须用 `getWindowsOnAllDisplays()`, 老的那个 API 永远看不到我们的屏** —— 这是这一步最容易踩空的地方, 光看 `windows` 会得出"无障碍读不到虚拟屏"的错误结论, 然后去写代价高一个量级的 `UiAutomation`

另外三件一起验掉的事:

- **坐标是那块屏自己的原点**, 不是全局: 树里 `设置` 标题在 `[73,169,253,289]`, 同一时刻截图上它在 `[36,84,126,143]` (除 2.0149), `WLAN` 在 `[204,920,363,985]` 对 `[101,457,180,489]`, 逐条对得上
- **事件会来**: 屏上的设置页一直在产生 `TYPE_WINDOW_CONTENT_CHANGED`, 事件流不区分主屏和虚拟屏
- **`ACTION_CLICK` 在虚拟屏上有效**: 对 `蓝牙` 那一行 `performAction(ACTION_CLICK)` 返回 `true` (落在可点祖先 `LinearLayout [34,1032,1046,1190]` 上), 页面真的翻到了蓝牙页

最后一条改变了设计: **按名字点击不需要坐标, 也不需要注入触摸**, 特权进程那一路完全用不上

## 落地的东西

```
AccessibilityService (app 进程, 系统绑定)
  → LwAccessibility.tree/tap: 按 displayId 拿窗口 → root → 遍历 / 定位 / 点
  → PrivilegedBridge "ui" / "tapText"  (同一进程, 直接调用)
  → 工具 lw_ui / lw_tap(text=…)
```

服务跑在 **app 自己的进程里** (它是我们 manifest 声明的), 所以取树这一路没有 binder、没有特权进程; 只有"注输入"仍然是特权进程的活, 而按名字点击用不到它

| 文件 | 干什么 |
| :-- | :-- |
| `channel/LwAccessibility.kt` | 服务本身 + 取树 + 按名字点, 输出 `UiNode` / `UiTree` / `UiTap` 三个数据类 |
| `channel/LwPermission.kt` | 特权侧写 `Settings.Secure`, 开启/关闭服务 (见下) |
| `channel/AccessibilitySetting.kt` | 设置页那个开关背后: 阻塞写放到自己的工作线程, 写完等系统真的绑定上再报状态 |
| `res/xml/lw_accessibility.xml` | `flagRetrieveInteractiveWindows` / `flagReportViewIds` / `flagIncludeNotImportantViews` / `canRetrieveWindowContent` |
| `AndroidManifest.xml` | 声明服务 (`BIND_ACCESSIBILITY_SERVICE` + `android.accessibilityservice` meta-data) |
| `ui/SettingsScreen.kt` | 「无障碍」一段: 开关 + 已开启/未开启 + 安全说明 + 兜底跳系统设置页 |
| `host-plugin/index.mjs` | `lw_ui`, 以及 `lw_tap` 的按名字分支 (`tapText`) |

桥上是 `ui` / `tapText` 两个方法 (`displayId` 照旧必填, 与所有动作一致)

### 取树的口径

- 只留"值得说的"节点: 有 `text` / `contentDescription`, 或 可点 / 可滚 / 可输入 / 可勾选; 其余只当容器, 继续往下走
- 每个节点都算出**它的可点祖先** (`target`), 因为标签自己通常不可点, 真正吃点击的是它所在那一行 —— `lw_ui` 因此能打 `at [204,920,363,985] press row [34,874,1046,1032]`
- 上限 400 个节点 / 40 层, 到了就截断并说明; 工具那头再截到 200 行
- 同一块屏上有多个窗口时, 优先取 `TYPE_APPLICATION && isActive` 的那个
- 取树前 `root.refresh()`, 让两次连续调用说同一件事

### 按名字点的匹配与拒绝

匹配从窄到宽, 取第一个有命中的档: `text` 完全相同 → `contentDescription` 完全相同 → `text` 包含 → `contentDescription` 包含 (都不分大小写)。所以 `lw_tap(text="返回")` 也能命中那个只有 `desc="返回"` 的返回键

两条刻意不猜的规矩:

- 一行里的标题和副标题是**两个节点一个行**, 所以候选按"按下去会按到哪个节点"去重, 去重后只剩一个才动手
- 去重后仍不止一个就**什么都不按**, 把候选连矩形一起返回。模型实测确实撞上过这个: 在 WLAN 页上 `text="WLAN"` 同时命中标题栏与那一行, 它没有猜, 而是照工具说的改用矩形里的一个点 —— 这正是期望行为, 提示词里因此写明了"要么换个只出现一次的名字, 要么用它给的矩形里的一点"

`ACTION_CLICK` 返回 `false` 时退回**在 target 中心注入一次真触摸** (`via: "finger"`), 因为少数自绘控件不吃无障碍动作; 树已经说了那个东西在哪, 所以这次注入不是猜坐标。**带 `hold` 的按名字点击** (2026-09-22) 先试 `ACTION_LONG_CLICK` (要 `isLongClickable` 的祖先), 被拒就走同一个兜底, 只是按住那么久 —— 实测启动器图标与设置的搜索框都不是 long-clickable, 两次都是兜底那一支起的作用

## 开启与关闭: 一个只有特权 uid 能写的设置

服务是**选择加入**的, 而且必须能在应用内开关 (它读的是整台设备, 不只是我们那块屏)。设置页那一段是开关 + 状态 + 一句安全说明 + 兜底跳系统页

写 `Settings.Secure` 需要一个 app 拿不到也申请不到的权限, 所以由特权进程代写。**但第一版在这里踩了个熟坑**:

```
java.lang.SecurityException: Unable to find app for caller
  android.app.IApplicationThread$Stub$Proxy (pid=...) when getting content provider settings
```

`app_process` 起的进程没有 `IApplicationThread`, 于是 `Settings.Secure` 经自己的 `ContentResolver` 去要 `settings` provider 时被 AMS 拒了 —— 与第 3 步回传 binder 时撞的是同一堵墙。正解是**走 `/system/bin/settings` 命令**: 它本来就是为了在没有 app 身份的地方写设置而存在的, 自己会用 external token 那条路拿 provider

所以 `LwPermission` 的执行方式是 `ProcessBuilder("/system/bin/settings", "get"/"put", "secure", key, value…)`, 与同文件里 `runGetevent` 一个路子 (超时 + 收 stdout)。三条实现上的注意:

- **不要整个替换那个列表**, 要读出来改: 设备上本来就开着 `com.omarea.vtools/.AccessibilitySceneMode`, 覆盖会把别人的服务关掉; 比较时用 `ComponentName.unflattenFromString` 比组件而不是比字符串, 同一个组件有两种拼法
- 开启时**同时**写 `accessibility_enabled=1`, 只加组件名服务不会起来, 而且没有任何提示
- **写完读回来核对** (`kept != enabled` 就报失败): 退出码只说明命令跑了, 不说明设备留下了那个值

设置页那段代码有一个不显然的地方: **"开没开"不是设置, 是系统有没有把服务绑上**, 所以状态从服务本身读 (`LwAccessibility.running`), 写完设置后按 200 ms 一步最多等 3 s 再报真实状态, 等不到就明说没生效

## 实测

| 项 | 结果 |
| :-- | :-- |
| 树 | display 40 的设置主页 41 个节点, 首页/子页都完整, `id=title` / `id=text_right` 这些 MIUI 的 view id 也在 |
| `tapText "WLAN"` (主页, 唯一) | `outcome=clicked via=ancestor`, 页面真的翻到 WLAN 页 |
| `tapText "不存在的名字"` | `outcome=none`, 一个字都没动 |
| `tapText "WLAN"` (已在 WLAN 页) | `outcome=ambiguous`, 列出 `action_bar_title_expand` 与 `title` 两个候选, 不按 |
| 设置页开关开启 | 由 UI 触发, `enabled_accessibility_services` 变成两个组件 (原来的保留), 服务随即连上, 界面显示「已开启」 |
| 模型回合 1 | 只调 `lw_ui` / `lw_tap`, 从 WLAN 子页按 `返回` 回主页, 再按 `蓝牙` 进蓝牙页, 报出开关是开的 —— **全程没有截图, 没有任何坐标** |
| 模型回合 2 | 要求它逐字引用 `lw_ui` 输出, 确认模型看到的就是上面那种行 |

`lw_ui` 实际给模型看的样子:

```
displayId 40 "ui" 1080x2400, com.android.settings, 56 readable controls. The rectangles are in the screen's own pixels, the same ones lw_tap and lw_swipe use.
        FrameLayout id=action_bar_container [clickable] at [0,0,1080,317]
            Button desc="返回" id=up [clickable] at [56,22,191,135]
            ImageView desc="扫描二维码" [clickable] at [945,39,1024,118]
                TextView "WLAN" id=action_bar_title_expand at [73,169,350,289] press row [0,0,1080,317]
                RecyclerView id=recycler_view [scrollable] at [0,317,1080,2400]
```

## 踩到的坑

- **`getBoundsInScreen(Rect)` 在 Kotlin 里不是表达式**: `getBoundsInScreen(Rect())` 返回 `Unit`, 写成 `${boundsOf(node.getBoundsInScreen(Rect()))}` 会把 `Unit` 塞进去, 编译期 `ARGUMENT_TYPE_MISMATCH`。要先 `val rect = Rect()` 再传
- **`am start --display <id> -n …` 在 PowerShell 里要小心**: `$id` 为空时参数会串成 `--display -n …`, 报 `NumberFormatException: For input string: "-n"`; 解析桥的应答别用 `ConvertFrom-Json` 整行转, 先 `-replace '^answer:\s+'`
- **重装 APK 会把我们的组件从 `enabled_accessibility_services` 里去掉** (系统对更新的应用清一次无障碍授权), 而且清了之后不一定自动重绑 —— 这正是设置页那个开关必须存在的第二个理由; 开发期每次 `adb install` 之后都要重新开一次
- **`am start -S` 之后服务不会自己回来**: 写一次 setting (哪怕写成同样的值) 就能让系统重绑, 但装了应用之后列表里已经没有我们, 所以直接重开即可

## 补记: 自绘界面与 Flutter (2026-09-22 真机验过)

计划里"空树界面"那条路径真跑了, 而结果推翻了一个假设:

- **引擎自绘的界面不是"空树", 是"一棵没用的树"**: Phigros (Unity) 在虚拟屏上只有 **1 个节点** —— 全屏 `SurfaceView`, 有 `desc="Game view"`, 不可点、没有文字。原来的判据是 `nodes.length === 0` 才说"读不到", 于是模型会看到一行看起来能按的控件, 白花几个回合。**判据改成"有没有值得动手的节点"** (有文字 / 可点 / 可滚 / 可输入 / 可勾选), 一个都没有才说 `draws its own picture`, 并把那些没用的节点一句话带过
- **Flutter 的树是读得到的**: `com.miuzarte.grad_proj` 在虚拟屏上给出 7 个节点, 带 `contentDescription` 与矩形 (`设备列表` / `刷新` / `点击获取设备列表` / `获取设备列表` / `设备` `扫描` `设置` 三个 tab), 够模型按名字操作。原因不神秘: Flutter 一直在向无障碍服务暴露语义树, 否则 TalkBack 也用不了它 —— 所以「Flutter 需要 OCR 兜底」这个说法是错的, **真正需要兜底的是 Unity / Cocos / 画布这类引擎自绘的界面**

模型回合确认过两种输出: 49 号屏回的是上面那句 `draws its own picture ... use lw_screenshot and coordinates`, 50 号屏回的是那 7 个控件。

## 补记: `lw_launch` 的包名形式 (2026-09-22 真机验过)

第一版按 `am start -a MAIN -c LAUNCHER -p <pkg>` 起包, 对设置这类应用没问题, 但**对被测的两个第三方应用都失败**:

```
Error: Activity not started, unable to resolve Intent { act=MAIN cat=[LAUNCHER] pkg=… }
```

原因是 `am` 起的是**隐式 intent**, 而隐式匹配带 `MATCH_DEFAULT_ONLY`, 这两个应用的 launcher filter 都只有 `MAIN` + `LAUNCHER` 而**没有 `CATEGORY_DEFAULT`** (Flutter 与 Unity 的 manifest 模板都这样), 于是从桌面点得开、从 `am` 点不开。改成**先问平台要一个 activity, 再按组件起**:

```
cmd package resolve-activity --brief -c android.intent.category.LAUNCHER <pkg>   → pkg/.MainActivity
am start -W --display <id> -n <that component>
```

解析器打两行 (一行 header, 一行组件), 取带 `/` 的那一行; 解析不出来时退回原来那条 `-a MAIN -c LAUNCHER -p`, 好让调用方仍然看到设备自己给的理由。实测两个应用都起来了 (`Status: ok`, `Activity:` 报出解析结果), 设置那条路不受影响

## 补记: 开关自己就能开启无障碍 (2026-09-22 真机验过)

设置页那个开关**本来就经特权通道写 `Settings.Secure` 把服务打开** (第 7 步就是这么做的), 但有两件事会让它看起来"只能自己去系统设置里点":

- **组件在列表里而系统没绑上时, 把同样的值再写一遍不一定有用** —— 值没变, 观察者就没有可动作的东西。照 gkd 的做法: 开启时如果组件**已经在列表里**, 先摘掉、停 800ms、再放回, 让列表真的变一次
- **侧载的应用在 Android 13+ 不许开无障碍** —— 这台设备上 `installerPackageName=null` (adb 与 `pm install` 装的都这样), `cmd appops get <pkg> ACCESS_RESTRICTED_SETTINGS` 报 `default; rejectTime=…`, 说明确实有东西检查过它。开启时顺手 `cmd appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` (best effort, 失败只记一行日志)

写入顺序也换成 gkd 那个: **先写 `accessibility_enabled=1`, 再写组件列表** (系统先读那个总开关)。设置页那条「在系统设置里打开」留着, 但改成兜底 (summary 是 `自动开启不成功时再用这一条`, 出错时换成真实原因), 不再像是必经之路

真机实测 (小米 13 / Android 16): 装完 APK 后 `enabled_accessibility_services` 只剩 Scene 那一个 (我们的被系统踢掉了), 在设置页拨一下开关 → 列表里出现我们的组件、`accessibility_enabled=1`、`dumpsys accessibility` 的 `Bound services` 里出现 `Service[label=LittleWhale …]`, 开关随即停在打开的样子, **全程没有离开设置页**。logcat 里两行挨着: `LwPermission: allowed ACCESS_RESTRICTED_SETTINGS for io.github.miuzarte.littlewhale` 与 `LwPermission: accessibility enabled: …:io.github.miuzarte.littlewhale/…LwAccessibility`

## 还没做的

- **「顺手可做」截图预算跟着配置走**: 工具那侧仍没读 `$DSH_HOME/settings.yaml`, app 里的 `Picture.DEFAULT_MAX_PIXELS = 640_000` 还是抄来的常数 (桥已经收 `maxPixels`, 只差工具传)
- 主屏的树不主动给 (只返回被点名那块屏), 这是有意的; 以后真需要主屏再单独开
- **answerer 已经写了** (见 AGENTS「审批行为」), 但它只在会话的 approval 策略是 `ask` 时才被叫到
