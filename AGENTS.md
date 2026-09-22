# LittleWhale

把 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 搬到安卓上的项目, 用 Miuix 界面库, 自建虚拟屏来控制应用, 把屏幕能力做成 dsh 原生工具交给模型

**原来的八步计划与界面那一轮都已落地并在真机上验过**, 仓库公开在 `Miuzarte/LittleWhale` (`main` 是压缩后的单个提交, 开发历史在本地 `dev` 上)

## 文档地图

| 文档 | 放什么 |
| :-- | :-- |
| `AGENTS.md` (本文) | 现状、决策、约束、怎么操作 |
| `README.md` | 对外的门面: 能力、已知问题、构建、Credits |
| `docs/step2-record.md` … `docs/step8-record.md` | 每一步的清单、实测数字、踩坑过程 |
| `docs/ui-record.md` | 界面那一轮: 设置页缩进与名字、过渡风格、状态栏图标、截图那两条滑块 |
| `docs/host-build.md` | 构建事实: 随 APK 发的二进制、pack / zip、被否的方案、依赖缺口、工具链版本 (**平时不用读**) |
| `B:\Git\deepseek-harness\patch.md` | fork 相对上游的**全部**改动, 唯一权威 |
| `third_party/deepseek-harness/docs/` | dsh 自己的文档 (subsystems / cookbook / user) |

「第 N 步」的对应: 2 = dsh 上安卓, 3 = 特权通道, 4 = 自建虚拟屏, 5 = dsh 原生工具, 6 = 主屏与触摸刹车, 7 = 无障碍读屏, 8 = 端侧 OCR

## 仓库形态

dsh 以 **git submodule** 挂在 `third_party/deepseek-harness/`, 指向 `https://github.com/Miuzarte/deepseek-harness.git` (Miuzarte 的 fork, 不是上游)

**dsh 的构建产物不进 git**: submodule 里只有源码, `pnpm install` 与 `build:official` 都在**构建 APK 时**跑 (Gradle task), 产物直接喂给打包步骤, 这样 submodule 保持干净、能跟上游 rebase

构建机需要 Node + pnpm, 升级 dsh 就是 `git submodule update --remote` 之后重新构建。**pin 必须是 fork 上推过的提交**: 只活在 submodule 工作区里的改动, 克隆本仓的人看不见

## 架构

LittleWhale 自己是一台**远程 dsh 服务器 + 一个安卓控制端**, 两件事共用一个进程:

1. **dsh host** — APK 里的 Node 跑 dsh, 监听回环地址, 界面是它的 Web GUI (装进 WebView)
2. **安卓控制端** — Miuix 界面 + Shizuku / root 特权通道 + 自建虚拟屏, 把屏幕与输入能力做成 dsh 原生工具交给模型

**常驻方式是前台服务** (Node / host / WebView 都在里面), 否则一切后台就被系统收掉

**host 以「上游自带的 pack → 平铺 `npm install`」的形态进 APK**, 不用源码 + tsx —— 代价是改完 dsh 要重新 build + pack 才能进 APK, 没有"机内改源码立刻生效"

dsh 这个 fork 的定位也是**部署在远程服务器上, 从任意浏览器访问** (主题与字号存浏览器 `localStorage`, 手机与 PC 各一套), 移植时**不要把这些改动弄丢**

**监听地址没有写死 `127.0.0.1`**: 设置页「网络」的开关让 host 以 `--host 0.0.0.0 --allow-lan` 启动 (fork 加的第二个旗标, 安全默认一个字没变), 别的设备用浏览器打开 LAN URL; `DshHost.remoteUrl` 从就绪行的 `(LAN: …)` 后缀里取 URL, **不自己枚举网卡**, 这样显示的地址与 host 认的 browser-trust 栅栏是同一个

**安卓侧两个已知的坑**: `os.cpus().length` 返回 **0**, dsh 里任何按 CPU 数并行的地方都要能容忍 0; 随包发的 node 有一批**写死的 Termux 路径**, `OPENSSL_CONF` / `SHELL` / `TMPDIR` 三个少一个都起不来 (见 `docs/host-build.md`)

## 术语与参考仓库

- **dsh** — deepseek-harness, 被移植的对象, 以 submodule 挂在 `third_party/deepseek-harness/`, 是 **Miuzarte 的 fork** (不是上游)
- **SFA / ScrcpyForAndroid** — `B:\Git\ScrcpyForAndroid`, 同为 Miuzarte 的项目, **只当 Miuix 界面的参考**; 它的 scrcpy 层与 `new_display=` / `display_id=` / `scrcpy_%08x` socket / 控制报文表那套**全部作废**, 别再翻
- **MAA-Meow** — `B:\Git\MAA-Meow`, 自建虚拟屏 / 输入注入 / 读触摸 + Shizuku 与 root 双通道的**做法**参考 (**AGPL-3.0, 只看做法别抄代码**)
- **host / client 侧** — dsh 的术语, host 侧跑在 Node 里 (发构建产物 `lib/`), client 侧是浏览器产物 (每个 client 包的 `lib/client.js` + `@deepseek-ai/dsh-web-frontend/dist`)

| 路径 | 用途 |
| :-- | :-- |
| `third_party/deepseek-harness/` | **submodule**, 被移植的 dsh, 唯一允许改的第三方代码 |
| `B:\Git\deepseek-harness` | dsh fork 的开发克隆, 在 submodule 之外单独放一份方便比对和推分支 |
| `B:\Git\ScrcpyForAndroid` | Miuix 界面的参考 |
| `B:\Git\MAA-Meow` | 虚拟屏 / 注入 / 读触摸 + 双通道的参考 |

后两个仓库当**只读参考**用, 不要在里面改代码; dsh 不是参考而是**被移植的对象**

## 测试设备

一台随便用的真机, 小米 13, 已连接:

| 项 | 值 |
| :-- | :-- |
| 局域网 IP | `192.168.1.103` |
| adb | `adb connect 192.168.1.103:5555` |
| Termux ssh | `ssh -p 8022 192.168.1.103` (用户 `u0_a441`, 免密 key 已配好) |
| root | KernelSU, `su -c '...'` (`context=u:r:ksu:s0`) |
| 型号 / 代号 | `2211133C` / `fuxi` |
| Android | 16 (API 36) |
| ABI | `arm64-v8a` |
| 内存 | 11 GB |
| 已装 | Termux (含 clang, git, ssh, curl), Shizuku (`moe.shizuku.privileged.api`), KernelSU |

**这是开发机上唯一的一台, 可以随便装东西、重启服务、改配置**, 但 Termux 是 `targetSdk 28`, 别把它升级成 Play 版本; `adb` 在 `B:\Software\AndroidSDK\platform-tools\adb.exe`

## 代码风格

照抄 SFA 的 `AGENTS.md`:

- 注释中英文都行, 中英文/数字之间留空格 (盘古之白), **不要用全角标点**, 用半角 `, . ( ) /`
- **注释里不用句号**, 该断句的地方用逗号, 句末直接结束
- 这条同样管本文档的正文, 不只是代码注释
- UI 字符串同时进 `res/values/strings.xml` (en) 与 `res/values-zh/strings.xml`, **设置页也不例外**; 设置页的键以 `settings_` 开头, 措辞照 SFA 的 `values-zh/strings.xml`, 两份文件**键与顺序都保持一致**, 占位符 (`%1$s`) 一一对上
- 不要用 `;` 把本该分行的语句挤在一行
- 改代码优先小步修改, 不要整文件重写
- 引用符号先 `import` 再用短名, 不要写全限定名
- 多出来的 import 不用手动清, 格式化器会处理

## 界面

**上面原生渲染虚拟屏预览, 下面 `weight(1f)` 的 WebView 渲染 dsh Web GUI, 会话界面不重写** —— 那界面本来就有 ~40 个 client 包 (`packages/client/ui-*`) 且用的是**内部协议** (Host 生成 descriptors + codecs, 不是公开 API), 原生重写等于永久追上游, 详见 `docs/subsystems/web-client.md`

几条要记住的:

- **画面在 `TopAppBar` 下面**而不是页面最顶 (那样会顶进状态栏 inset 里); 手势层贴在 `SurfaceView` 的 modifier 上, 这样 `size` 就是画面本身
- **在看画面时顶栏整条不画** (`SmallTopAppBar` 的 `CollapsedHeight = 52.dp`, 标题空着也一样高, 想省这 52dp 只能不画), 菜单按钮改成浮在画面右上角, 静 3 秒淡出, **菜单开着时不淡出**
- **缩进与段间距统一由脚手架的 `LazyColumn` 给** (`scaffolds/LazyColumn.kt`: 页面左右 12dp + `itemSpacing` 12dp + 横屏限宽 + overscroll + 滚到底触感), **Card 不写水平外边距**; Miuix 的 `Card` 不带内边距, 带内边距的是设置项自己 (16dp), 所以**别再套一层 `padding(16.dp)`** (那就是 32dp); 按钮一律 `fillMaxWidth()`
- 过渡风格只有 `Miuix` / `AOSP` 两项, **没有 "无"**; AOSP 那套手感是搬来的 `ui/CrossActivityTransition.kt` (Miuix 0.9.4 的 `NavTransitions` 里没这个预设), 选中时 `cornerClipMode` 跟着换成 `All`
- 系统栏图标深浅由 `theme/SystemBars.kt` 按**实际渲染出来的配色**定, 在 `MiuixTheme` 里调一次; **顶栏没有模糊也没有那个选项** (画面自己不透明, 糊了没人看得见)
- **设置页右上角有一个 ⋮**: 要重启 host 才生效的改动 (工作区授权 / 局域网开关) 全收在那一个菜单里, 以后加选项就是往那个 `items` 里再加一条; 注意 **material3 不是本项目的依赖** (只有 `material3-window-size-class`), 没有 `androidx.compose.material3.DropdownMenu` 可用
- **「截图」那段是两条预算, 都是滑块** (见 `channel/ScreenshotBudget.kt`): **像素**三档 (低 262144 = dsh 的 `imagePixelBudget: low`、默认 640000 = dsh 的缺省、高 1690000 = DeepSeek 那头的处理预算), **字节** 256 KiB~1 MiB 连续可滑 (吸附点 256/512/768/1024, 打字给到 4096)。两条给的都是 **app 这一半** (截图产生时缩到多少), 路由那一半 (`imagePixelBudget` / `imageMaxBytes`) 在 dsh 自己的 `settings.yaml` 里, **app 读不到也写不到** —— app 这一半超过路由那一半没用, 只会把注定被拒的图交出去 (超了要在 host 那边重编码, 而设备上没有编码器), 所以滑块上端就停在路由缺省那个数。滑块是搬来的 SFA `ArrowSlider` (`scaffolds/`, 点标题那一行可打字给精确值)
- **`AndroidView` 里的 WebView 必须显式设 `layoutParams`** (MATCH_PARENT / MATCH_PARENT), 否则它处在 `WRAP_CONTENT` 状态, **所有 viewport unit 都解析成 0** —— dsh 用 `100vh` / `100dvh` 量弹窗、菜单、设置页与目录选择器, 一塌就是空面板
- **虚拟屏预览放不进网页端**: 预览是合成器直接写进原生 `SurfaceView` 的, 浏览器拿不到那个 surface; dsh 的插件 (`ctx.slots` / `ctx.sidebarRightTabs`) 跑在浏览器 JS 里, **拿不到 Shizuku / root 通道**
- 远期点子: `ActivityOptions#setLaunchDisplayId()` 能把自己的 Activity 启到虚拟屏上, 让模型直接操作 dsh GUI

### 别做的事

- **不要用 Miuix 重写会话界面**
- **不要去跑 `scrcpy-server`, 也不要从 MAA-Meow 抄代码** (那是 AGPL), 只学做法; 反射隐藏 API 的包装若真要抄, 从上游 `Genymobile/scrcpy` 取 (Apache-2.0) 并保留 NOTICE —— 本仓的 LICENSE 也是 Apache-2.0, 那份 NOTICE 留得下
- 不要试图把 `SurfaceView` 塞进 WebView
- **不要把虚拟屏预览放进 `TopAppBar` 的 `bottomContent`**: 高度上完全一样, 只是让画面落进顶栏的 `clipToBounds` 与吞点击的那层 Layout, 还被绑上顶栏的滚动折叠
- 不要给 Card 里的设置项再套一层 `padding(16.dp)`
- 不要为了"让模型能操作屏幕"去写 MCP server —— 先写 dsh 原生工具 (见「dsh 工具怎么加」)

## 特权通道

链路: **模型 → dsh 工具 → Node host → app 的 loopback 桥 → binder → 特权进程 (uid 0 / 2000)**, 全部细节在 `docs/step3-record.md`。要记住的:

- `native/launcher.c` → **`liblauncher.so`** (cmake 出的可执行文件当 native lib 发), setenv `CLASSPATH` 之后 fork + exec `app_process`, **身份不动** (su 给 root, Shizuku 给 shell)
- **不用 Shizuku 的 user service**, 直接经 AIDL binder 调 `IShizukuService.newProcess()` (API 13 里 `Shizuku.newProcess` 是 private 且准备移除)
- **回传 binder 必须经 app 自己的 ContentProvider, 且要用 `IActivityManager.getContentProviderExternal` 拿** —— `ContentResolver.call` 走不通 (`app_process` 起的进程没有 `IApplicationThread`, AMS 直接 `SecurityException`); 副作用正好是要的: provider 里 `Binder.getCallingUid()` 看到的是 0 / 2000 而不是 1000。provider 校验 uid 加一次性 token
- `channel/LwPrivilegedService.kt` 是**手写的 `Binder`** (没有 AIDL), 因为 AIDL 会生成 Java, 而 Java 编译会把 AGP 9.4.0 那条坏掉的资源管线拉进图里
- `channel/PrivilegedBridge.kt` 是给 host 用的 **loopback TCP** (临时端口 + 随机 token, 由 `DshHost` 用 `LW_CHANNEL_ENDPOINT` / `LW_CHANNEL_TOKEN` 传过去); 协议是**一连接一请求一行 JSON**, 方法都在 `dispatch()` 里
- **抽象缝是三条不是两条**: `RemoteAccessPermissionBackend` / `ProcessSpawner` / `RemoteServiceConnectorBackend`
- **「有没有 root」在 app 侧查不到** (KernelSU 对没在名单上的 app 把 `su` 整个收走), 只能试, **而且试也不会弹出任何框**: 授权只能**用户在 root 管理器里手动给一次**。设置页「特权通道」段只列状态加一个**只在没连上时出现的**「连接」按钮 (定位是**重试**, 不是入口), **root 那行在试过之前是"未检测"** (`RouteState.granted` 因此是 `Boolean?`)
- **通道在应用启动时就连** (`DshHostService.onCreate` 里一个后台线程跑 `PrivilegedChannel.ensure()`): 连一次要起一个 `app_process`, 是秒级的, 不做这件事那笔账会落在**模型第一次截图**上。所有问系统的调用都在自己的工作线程上 (第一次连接可能等到人点完框, 主线程上等就是 ANR)
- 依赖: `dev.rikka.shizuku:api:13.1.5` + `:provider:13.1.5`, root 用 `com.github.topjohnwu.libsu:core:6.0.0` (在 jitpack 上, 所以 `settings.gradle.kts` 里有 jitpack 源)

## 虚拟屏

**「预览零 native」成立**: 屏的输出 surface 就是 `SurfaceView` 的 surface, 合成器直接把画面写进去, 不编码不解码、不开 socket、没有 native。代价是它跟着窗口走, 所以 **detach 是一等状态** (屏继续活着), 而**截图不依赖预览** (走 `screencap`, 无头也能拍)

链路: 菜单 → `VirtualScreen.create()` → 特权进程 `DisplayManager.createVirtualDisplay` (隐藏 flag `TRUSTED` / `OWN_FOCUS` / `OWN_DISPLAY_GROUP`, 要 `CAPTURE_VIDEO_OUTPUT`) → 屏的输出面由 app 交进去 → 预览上的手指经 `injectInputEvent` + `setDisplayId` 注回那块屏

要记住的:

- **buffer 要等于屏的尺寸** (`holder.setFixedSize(w, h)`): 合成器不缩放, buffer 小一圈就是**裁掉左上角**; 视图再按屏的宽高比撑高, 缩放是 surface 的事。盒子高度 = `min(宽度 * 0.75, 宽度 * 屏高 / 屏宽)` (竖屏最多 4:3, 横屏按短边收)
- **换屏要换 SurfaceView** (`key(displayId)`): 复用同一个 surface 时旧 buffer 留着上一块屏的最后一帧, 而**空屏不产生新帧把它顶掉**
- **`screencap -d` 要 compositor 的 64 位 id, 不是逻辑 displayId**, 只能按**屏名**从 `dumpsys SurfaceFlinger --display-id` 里找 (所以屏名唯一: `LittleWhale 1` / `2`), 而且它是无符号 64 位, **别进整数**
- 屏的 `ownerUid` 是 **0** 而 `ownerPackageName` 是我们的包名, `canHostTasks` 报 `false`, 但 `am start --display <id>` 照样起 activity 并正常渲染
- **触摸要排队**: 手比跨进程快, 同步注入会让 move 超过它所属的 down 被平台丢掉, 用一条单线程队列串起来
- **一块屏可以被换成别的形状**: `lw_screen_resize` 与它的别名 `lw_screen_rotate` (宽高对调) → `DISPLAY_RESIZE` → `VirtualDisplay.resize`。**尺寸就是应用拿到的那份配置** —— 只会横屏的游戏要的是一块横屏的屏, 转画面是转不出来的; 应用**不会因为屏换了形状就重排** (跟着屏走的铺满, 声明了方向的原样留一条带子居中), 所以"建屏时就把形状定对"对锁方向的应用是真要紧的。两个实现细节: 预览要按新尺寸重新 `setFixedSize` (buffer 不跟着换就挨裁); **换完尺寸再拍的第一张图是换之前那一帧**, 所以 app 侧按 PNG 宽高重拍到对上为止 (实测第二次就对)
- **⋮ 级联菜单**第一层是 `虚拟屏` (二层: 屏列表单选 / 暂停与继续接受控制 / 关闭这块屏) / `虚拟屏触摸控制` / `设置`; **没有"新建"** —— 建屏是 dsh 工具的事, 桥的 `create` 带 `name` / `width` / `height` / `dpi` (重名自动补序号), 界面只负责看和关。设置页用 `miuix-nav` 推入 (`NavKey` 必须 `@Serializable`)
- **屏上的操作有两道闸, 都在用户手里**: ⋮ 菜单里的「暂停接受控制」让 `lw_tap` / `lw_swipe` / `lw_tap(text=…)` / `lw_launch` 一律回一条点名是哪块屏的错, 而**看的不拦** (`lw_screenshot` / `lw_ui` / `lw_screen` 照旧); 一级菜单里的「虚拟屏触摸控制」**默认不选中**, 关着时手指滑过预览一个触摸事件都不会送到屏上 (故意放一级菜单而不是设置页: 它是"现在这块画面能不能摸")

代码: `channel/LwVirtualDisplay.kt` / `LwInput.kt` / `LwCapture.kt` (特权侧), `channel/VirtualScreen.kt` / `VirtualScreenPreview.kt`; 给 host 的桥是 `screen` / `screenshot` / `create` / `resize` / `release`

## dsh 工具

工具全在 `host-plugin/index.mjs` 一个插件里, 每个一次桥调用: `lw_probe` / `lw_screen` / `lw_screen_create` / `lw_screen_resize` / `lw_screen_rotate` / `lw_screen_release` / `lw_tap` / `lw_swipe` / `lw_screenshot` / `lw_launch` / `lw_key` / `lw_type` / `lw_ui` / `lw_ocr` / `lw_apps`

**每个动作都显式带 `displayId`**, 没有"默认打选中的那块" —— 选中的是用户随时能改的, 而模型手里的坐标是它在某一块屏上量出来的。**用户没说用哪块屏就用虚拟屏**: `displayId 0` 是别人手里那台手机, 动它就是把它从人手里拿走, 而那块屏自己的形状是能给的 (`resize` / `rotate`), 主屏的不行 —— 这条写在 `DISPLAY_ID` 那个共用参数与 `lw_screen` 的描述里

要记住的:

- **双开的应用是另一个 Android user, 不是一个新包名**: HyperOS 的双开 = `user 999` (名字 `XSpace`), 包名 / APK / 启动组件与原版**一模一样**, 只有 userId 不同 —— 所以 `lw_launch` 有 `user` (`am start --user 999`), 那个数字来自 `lw_probe` 里多问的一句 `pm list users` (app uid 问不了这条命令, 它是特权侧的活)
- **列应用只能这么列**: `lw_apps(user?, query?)` 给"能启动什么" (名字 + 包名), `lw_launch` 也认名字 (对上不止一个就什么都不起)。**模型的 bash 列不出来**: `pm list packages` 不带 `--user` 要跨 user, 而 `INTERACT_ACROSS_USERS_FULL` 是 signature 权限; 带上 `--user 0` 又只看得见它自己 (Android 11 包可见性) —— 所以名单来自 app 侧 `queryIntentActivities` 与 manifest 里那行 MAIN/LAUNCHER 的 `<queries>` (窄声明), **每个 user 装了哪些**来自特权侧 `pm list packages --user N`
- **`lw_launch` 只能走特权进程**: `am` 以 `com.android.shell` 自居, app uid 调它一律被拒 (模型的 bash 也是 app uid), 所以是特权进程里 `ProcessBuilder("/system/bin/am", …)`; **包名先解析成组件再起** (`cmd package resolve-activity --brief -c LAUNCHER <pkg>` → `am start -n <component>`), 因为 `am start -p` 那条路带 `MATCH_DEFAULT_ONLY`, 而 Flutter / Unity 的 manifest 不写 `CATEGORY_DEFAULT`。不走 `startActivity` + `setLaunchDisplayId` 是因为撞 BAL
- **按键与打字是另外两条路**: `input keyevent` / `input text` 撞的是与 `am` 同一堵墙 (INJECT_EVENTS), 而 BACK / HOME / 音量这类**平台自己处理**的键不在任何屏的树里 —— 所以按键走特权进程 (`INPUT_KEY`), **键传名字不传编号**, 表从 `android/keycodes.h` 生成 (`tools/gen-keycodes.mjs`): 名字不认识可以拒, 编号不认识就是**另一个键** (5 是打电话, 26 是电源键); `HOME` / `POWER` / `SLEEP` / `SOFT_SLEEP` 只在主屏放行 (我们的屏没有 launcher, 发完 HOME 那块屏 `state OFF` 而截图照旧交旧帧)。**打字优先走无障碍**: `ACTION_SET_TEXT` 写焦点字段的文本, 中文与 emoji 都行, 也不需要 IME; 整块屏没有任何字段时才退回按键 (`INPUT_TEXT`, 只有 ASCII, 中文直接拒), 两条路用 `via: field|keys` 分开
- **`tap` / `swipe` / 按住都阻塞到设备收下为止** (队列保顺序, `.get()` 保"做完了"), 而预览的手指仍然只往队列里丢: 工具返回后模型马上会截图看结果, 所以"已排队"对它没用
- **`swipe` 是一次事务**: 特权侧按 `durationMs` 均分 12 步, **每一步至少睡一帧 (16 ms)** —— 一批同毫秒的 move 在平台看来是跳, 分帧读输入的应用 (Unity 那种) 会把"按下又抬起"当成**一次点击**; 分步放到 app 侧又会让手势快慢随 binder 负载漂移
- **按住多久是一个参数, 不再是一个布尔** (`lw_tap(hold=…)` / `lw_key(hold=…)`): 值是**字符串**, 认 `"1s"` / `"500ms"` / `"1.5s"` / 裸数字 (按秒), 也认 `short` (600ms) / `medium` (1.5s) / `long` (3s); 上限 10s。**三个名字都压在平台自己的长按阈值 (500ms) 之上那一小段**, 因为那才是分界线, 真要用 `"8s"` 写出来 (8 秒的电源键在很多机器上是硬重启, 不该有一个 `long` 随手就能碰到)。设备侧: 长按 = 按住那么久 + UP 带 `FLAG_LONG_PRESS`; 只差一点点的 (500-650ms) 补到 650ms, 因为平台的检测器就在那一刻跑; `lw_tap(text=…)` 带 hold 时优先用节点的 `ACTION_LONG_CLICK`, 树里没有就用**按住的手指**落在它的矩形上。**按住也算动手, 所以也归刹车管** (真手指一来当场抬手)
- **截图落 `<工作区>/screenshots/screen-<id>.png`** (特权进程先写 app 的 cache, app 再拷进工作区): 模型的文件工具只在工作区里解析路径, 留在 cache 里就是能告诉它路径、它永远打不开
- **截图在产生时同时缩到两个预算** (像素与字节都按设置页那两条): **只按像素缩不够** —— 一整屏游戏画面在 536x1192 就能压到 1.29 MB → host 要重新编码 → 设备上没编码器 → **整个模型请求变成 `TRANSPORT`, 重试 5 次后本轮失败, 而那张图留在上下文里, 之后每轮都再失败一次**。`Picture.fit` 会对同一个画面编码到装得下为止 (猜一版 → 量真实字节 → 往预算内放大回去, 最多 4 轮); **换 JPEG 走不通** —— 附件库入库要全量解码当证明, 而设备上的 `sharp` 替身只解 PNG (629 字节的真 JPEG 也直接 `INVALID_IMAGE`)
- **截图的描述里写明了它会很小** (1080x2400 可能只有 536x1192), 所以"读屏用 `lw_ui` / `lw_ocr`, 截图只用来'像人一样看一眼'"这句话进了 `lw_screenshot` 的描述
- **host 树里的 `sharp` 是 `image-backend/sharp/` 那个纯 JS 替身** (`pack-host.mjs` 在 `npm install` 之后覆盖上去): 它从 PNG 头读事实, 用 `zlib` 真解一遍像素当"字节完整"的证明, **终端编码一律明确报错** —— 所以**只有 PNG 且不超预算的图能进模型**, JPEG 与大图还读不了
- 验证用 `tools/lw-bridge.ps1` (单次桥调用) 与 `tools/lw-device-turn.ps1` (run-as 起一次 headless turn, 用设备自己的树和凭据); 但**读图不能在 run-as 里验** —— 它不给 app 的 mount namespace, 而且打不开 `/data/user/0` 的祖先目录

## 主屏 (displayId 0) 与触摸刹车

**`displayId 0` 就是手机自己那块屏**: 看与动都能指它, 与虚拟屏走同一套调用。它**不进 `screens` 列表** (不是我们建的, 没有预览也没有暂停), 尺寸每次现读 (跟着旋转变), 截图走不带 `-d` 的 `screencap`, `release 0` 有一句专门的拒绝理由

**刹车管的是人, 而且只管主屏**: 特权进程 (`channel/LwTouchWatch.kt`) 直读触摸屏的 evdev 节点, 摸到玻璃就算数。**注入的事件不进 `/dev/input`** (注入口在 input reader 之后), 这是"真手指"与"模型的手"能被分开的地基。app 侧动手前问一次 (手指按着或 1000 ms 内有触摸就拒), 特权侧拖动中每 8 ms 问一次 (250 ms 窗口), 真手指一来**当场抬手**并回一句 "cut short after N ms"

- **监视不起来就不许动主屏**: `watching == false` 时 `requireUserNotDriving` 直接拒 (理由带设备原话); 看的不拦, 虚拟屏也不受影响。这是自觉的取舍 —— 审批全放行下没有别的兜底
- **虚拟屏不设这道闸**: 人在手机上摸的时候模型照样可以画别的屏, 那两道闸仍是菜单里的「暂停接受控制」与「虚拟屏触摸控制」。判据不去看触摸落在哪, 所以"用户在自己的 GUI 里打字"与"用户在抢屏幕"靠那 1 s 窗口区分
- **只有软停**: 手势中止 + 动作被拒 + 工具报错, 由模型收手; 硬停 (`exec.agent.cancel({kind:'hook'})`) 没做。抬手 1 s 之后就能再动手, 想更粘 (锁住到用户显式放开) 是另一个决定

没有真手指也能测: `tools/lw-fake-touch.sh` 往 evdev 节点写事件伪造一只 (`sendevent` 进的是 input core, 所以监视看得见)。**伪造的手指会被系统当真**, 挑一个被点到也无所谓的界面

## 无障碍读屏

链路: `LwAccessibility` (**跑在 app 进程里**, 系统绑定的服务) → 取树 / 定位 / 点 → `PrivilegedBridge` 的 `ui` / `tapText` (同进程直接调) → 工具 `lw_ui` / `lw_tap(text=…)`。**取树没有 binder, 也没有特权进程**

- **`getWindows()` 只返回默认屏**, 虚拟屏不在里面, 必须用 `getWindowsOnAllDisplays()` (API 30+) —— 只看前者会以为"无障碍读不到虚拟屏", 然后去写贵一个量级的 `UiAutomation`
- **坐标是那块屏自己的原点**; 事件会从虚拟屏来; **`ACTION_CLICK` 在虚拟屏上有效**, 所以按名字点击不需要坐标也不需要注入触摸
- **`getBoundsInScreen(Rect)` 不是表达式**, 返回 `Unit`, 得先 `val rect = Rect()` 再传, 否则是编译期的 `ARGUMENT_TYPE_MISMATCH`
- 只留"值得说的"节点 (有 text/desc 或 可点/可滚/可输入/可勾选), 每个节点还算出**它的可点祖先**当 `target` (所以能打出 `at [...] press row [...]`); 上限 400 节点 / 40 层。**引擎自绘的界面不是空树而是"没用的树"** (Phigros 只有一个全屏 `SurfaceView`), 判据是"有没有值得动手的节点"; Flutter 的语义树**读得到**
- **匹配从窄到宽** (text 全等 → desc 全等 → text 包含 → desc 包含), 所以 `lw_tap(text="返回")` 能命中只有 `desc="返回"` 的返回键
- **一行里的标题和副标题是两个节点一个行**, 候选按"按下去按到哪个节点"去重; 去重后仍不止一个就**什么都不按**, 把候选连矩形返回 (**最多 12 条**, 工具那句"多少个"是上限不是总数)
- `ACTION_CLICK` 返回 `false` 时退回在 target 中心注一次真触摸 (`via: "finger"`), 因为少数自绘控件不吃无障碍动作; 树已经说了东西在哪, 这次注入不是猜
- **开启服务只能由特权进程写 `Settings.Secure`**, 而 `Settings.Secure` 在特权进程里**走不通** (`app_process` 没有 `IApplicationThread`) —— 正解是 `ProcessBuilder("/system/bin/settings", "get"/"put", ...)`。写入必须**读出来改** (设备上还有别人的无障碍服务)、开启时**先**写 `accessibility_enabled=1` 再写组件列表、写完**读回来核对**
- **光写设置不够, 还有两件事**: 组件**已经在列表里但没被绑上**时 (强停之后就是这样), 把同样的值再写一遍不一定能让系统重新评估, 所以**先摘掉、停 800ms、再放回**; 而侧载安装的应用 (`installerPackageName=null`) 在 Android 13 起**不许开无障碍**, 那道闸是一个 app op —— 开启时顺手 `cmd appops set <pkg> ACCESS_RESTRICTED_SETTINGS allow` (best effort, 失败只记日志), 否则服务可能起不来而没有任何提示
- **重装 APK 会把我们踢出 `enabled_accessibility_services`**, 所以设置页那个开关不是可选项, 而且**开关自己就会经特权通道把服务打开**; 「开没开」不是设置而是"系统有没有绑上", 状态从 `LwAccessibility.running` 读, 写完等最多 3s 再报真实状态

## 端侧 OCR

方案在 `docs/step8-plan.md`, 实测数字在 `docs/step8-record.md`:

- **fp16 是前提, 而且模型一个算子都不用改**: fp32 图上 HTP 连 `HardSigmoid` / `Clip` 都造不出来, 而 `--float_bitwidth 16` 之后**原样就过**, 所以 `tools/ocr/rewrite_onnx.py` 只做"钉死 shape"这一件事; 代价是 QNN 那份 I/O 是 **fp16**
- **NPU 走 ORT 的 QNN EP 在设备上 JIT 编译** (`enable_htp_fp16_precision=1`)。开发机上预编译的 context binary **能加载能执行不报错但输出是一张常数图**, 别走那条; 代价是 `libQnnHtpPrepare.so` (70 MB) 必须随 APK 发, 首次建 session 2.2 s
- **性能票别关**: `htp_performance_mode="burst"` 与不投票差 **2.5 倍**; 它**跟着 session 走**, 重建 session 就回得去
- **rec 的每次调用有 5-8 ms 固定开销**, 所以 25 行要 250-400 ms —— 该做的是**批量重导** (batch 8/16) 而不是量化 (int8 治不了固定开销); det 那边 48 → 11 ms, 上 NPU 是值的
- **工具**: `lw_ocr(displayId)` 回每行 `[框] "文字" score 中心`; `lw_tap(text=…, source: auto|a11y|ocr)` 的 `auto` 先问无障碍树, **树里没有这个名字**才退到读屏 (树说 `ambiguous` 时不退, 读像素只会是第三个猜测); 读屏按名字点时的落点是**框中间 60% 里随机取的**, 不是正中心 (每次都落同一个像素本身就是模式)。**别再用"Flutter 需要 OCR"当理由**: 实测 Flutter 一直向无障碍暴露语义树
- **熄屏是静默失败, 这条最阴**: 熄屏时 `screencap` 交的还是**最后一帧** (读屏读到一个已经不在的界面), 而注入的触摸**唤不醒屏** (点进空气里), 两个都不报错。所以 `ocr` / `tap` 的答案里带了 `screen: on|dozing|off`, 工具那侧看到不是 `on` 就直说, 读屏点击则直接不点

设置页那段「OCR」显示计算单元 (`NPU (HTP) · SM8550`) / 说明 / 上次耗时 + 一个**自检**按钮 (加载并预热)

## 审批 (全部放行)

**我们注册一个 auto-allow answerer, 屏幕操作直接 `allowed-once`**, 就这么一行:

```js
ctx.on('approval/request', (_request, _next) => Promise.resolve('allowed-once'), { prepend: true })
```

理由: 要控制主屏时 LittleWhale 自己在后台, 用户看不到也点不了审批框, 走 `ask` 的结果不是"更安全", 而是**每次点击都卡在那直到超时/取消**。**`prepend: true` 是必须的**: 覆盖层是最后挂上去的, 不加就是链尾, 而浏览器侧那个 `ui-approval` 面板只要有人开着网页端就会先答 —— 于是"全放行"静默变成"每次都问人"。**审批面板就在 app 内的 WebView 里**, 所以 `prepend` 等于放弃了"人在的时候问人"这条路 (要恢复就把 `prepend: true` 去掉)

要记住的两个:

- **`ApprovalOutcome` 封闭且 fail-closed**: 只有 `allowed-once / rejected / cancelled / unavailable`, answerer 缺失 / 不拥有该请求 / 抛异常 / 返回不合规统统变 `unavailable`, 而消费者**只要不是 `allowed-once` 就拒绝**。所以 **`danger-full-access` 这个预设名有歧义**, 它是"没人回答 → 全拒绝"而**不是全放行**。而策略为 `never` 时服务在派发之前就 `return 'rejected'`, 连 prepend 的 answerer 都不会被叫到 —— 这一行的定位是**保险**: 一旦会话换成带 `ask` 的预设, 它才是"问了没人答 → 拒绝"与"直接放行"之间的那一步
- **放行了就必须有别的刹车**: **审计照记** (`approval/asked` / `decided` 是 log-only, 不进模型 transcript, 但进会话日志, 别为了"反正都放行"就绕过 `ctx.approval`, 那样连审计都没了); **人是唯一且只能靠触摸刹车** (见「主屏与触摸刹车」); **主屏操作尤其危险**, 因为虚拟屏内的操作至少被那个 display 关着

## dsh 工具怎么加

**先写 dsh 原生工具, 不要先写 MCP**: dsh 仓库里只有 `packages/mcp/mcp-client` —— 它是 MCP **客户端**, 自己写 server 等于新造一套协议再设法接进来; 而原生工具直接就有审批 / UI 呈现 / PTC 模式

**加载路径是 `--patch` 覆盖层**: 插件源在 `host-plugin/index.mjs` (纯 ESM, 用 `@deepseek-ai/dsh-tools` 的 `defineTool`), 由 `tools/pack-host.mjs` 装进 host 树的 `node_modules/littlewhale-channel`, app 每次启动 host 时写一份 overlay 指过去 (`host/PluginOverlay.kt`) —— profile 目录因此仍是用户自己的

一个插件就是一个 ESM 模块, 导出 `name` / `inject` / `apply`:

```js
export const name = 'littlewhale-channel'
export const inject = ['tools']            // 等 tools 服务就绪再 apply
export function apply(ctx) {
  ctx.tools.register(defineTool({
    name: 'lw_probe',
    description: '...',                    // 模型看到的描述
    parameters: {},                        // 空对象 = 无参数
    output: {
      schema: { type: 'string' },
      render: (_args, value) => [{ type: 'text', text: value }],
    },
    async execute() { return '...' },
  }))
}
```

**开发期的坑**: pnpm 的 `node_modules` 是隔离的, `@deepseek-ai/dsh-tools` 只 link 在 `apps/cli/node_modules` 下, 所以**临时插件文件要放在 `apps/cli/` 里面** (放仓库外或仓库根都解析不到), 用完删掉

**本机验证 (不需要真机)**, 用 fork 的开发克隆:

```sh
cd B:\Git\deepseek-harness
node --import tsx/esm apps/cli/src/bin.ts --profile web --dump-config --patch <overlay.yml>
node --import tsx/esm apps/cli/src/bin.ts --profile headless --patch <overlay.yml> "Call the lw_probe tool and report exactly what it returns."
```

**可以参考的文档 (都有中文版)**: `docs/cookbook/extension-cookbook.zh.md`, `docs/cookbook/adding-a-tool.zh.md`, `docs/user/develop/basic/tool.zh.md`, `docs/cordis-primer.zh.md`

## 工作区与存储

工作区在共享存储里, dsh 自己的配置在 app 沙盒里:

| 用途 | 位置 | 谁需要访问 |
| :-- | :-- | :-- |
| `$DSH_HOME` (配置 / 凭据 / 会话 / profiles) | app 沙盒 `filesDir/dsh-home` | 只有 dsh 自己 |
| 工作区根 | `/sdcard/DSH/`, 没拿到所有文件访问权限时是 `/sdcard/Android/media/<pkg>/DSH` | dsh + 用户 + 文件管理器 |
| 单次会话的工作目录 | 工作区根下的子目录 | 同上 |
| `完全权限` 会话 | 整个 `/sdcard` | 用户在会话里显式启用 |

配置里**含 `credentials` 和 `settings`**, 不该落在任何别的 app 都能读的地方; 而工作区是用户要拿文件管理器翻的, 必须可见 —— `resolveDshHome` 本来就把这两件事分开, 所以直接映射: **`DSH_HOME=<filesDir>/dsh-home`, 工作区是 host 的 cwd 也是它的 home**

### 目录授权 (不走 SAF)

**SAF 用不了, 这是查证后的结论不是取舍**: dsh 的工作区是**真实路径** (目录选择器从 `homedir()` 起逐层列真目录, 会话的 `cwd` 就是 `workspace.path`), 而 SAF 只给 app 一个 `content://` 树, 只有本进程的 `ContentResolver` 能用 —— host 是 **Node 子进程**, 拿不到任何路径权限。所以只有 **`MANAGE_EXTERNAL_STORAGE` (所有文件访问权限)** 一条路

`host/Workspace.kt` 按顺序解析, **每个候选都先写一个探针文件证明可写** (权限可能被撤销, 能列目录不等于能写):

| 顺序 | 目录 | 条件 |
| :-- | :-- | :-- |
| 1 | `/sdcard/DSH` | 拿到所有文件访问权限 |
| 2 | `/sdcard/Android/media/<pkg>/DSH` | 无需任何权限, 文件管理器也看得见 |
| 3 | `filesDir/DSH` | 兜底, 沙盒, 别的应用读不到 |

- 权限只能跳系统设置页 (`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`), 不能弹窗申请; UI 入口是设置页「工作区」段的「授予所有文件访问」, 改完在 ⋮ 里重启 host 生效 (`DshHost.restart` 会等旧进程真的退出再拉起, 否则端口没释放)。测试时也可以 `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` 免去手点, 实测这一条就足以让 `isExternalStorageManager()` 变 true
- **`HOME` 指向工作区**: GUI 的「选择工作区」从 host 的 home 开始列, 所以开屏就在工作区里; dsh 自己的状态仍在 `DSH_HOME` (优先级: 显式配置 > `DSH_HOME` > `~/.dsh`)。进程 cwd 也是工作区
- `完全权限` 是**会话级**开关: 没启用时会话的文件工具与 shell 被限制在工作区根内, 启用后才放开到整个 `/sdcard`; 这个限制落在 dsh 自己的 fs/sandbox 策略上, **不是安卓层面强制的** (安卓给了权限就是全给), 别误解成"系统级隔离"

### `/sdcard` 是 FUSE

`/sdcard` (以及 `/storage/emulated/0`) 是 FUSE 挂的, 不是真 POSIX 文件系统:

- **没有 inotify**, 原生 `fs.watch` 不工作, dsh 有兜底 (`watchFile` 轮询与 chokidar 轮询模式), 所以是**降级为轮询**而不是坏掉, 但大仓库下开销明显
- **symlink / hardlink 受限**, `chmod` 语义不完整, `stat` 里的权限位不可信, 任何依赖符号链接或权限位的逻辑要能容忍失败
- 随机小 I/O 慢, 大量小文件 (比如 `node_modules`) 会很痛
- 安卓 11+ `/sdcard/Android/data/<pkg>/` 别的 app 与 adb 都访问不到 —— 所以我们用 `/sdcard/DSH/` 这种顶层目录

`$DSH_HOME` 在 `filesDir` 里是**真 ext4/f2fs**, 所以配置那部分完全不受这些限制, `credentials` 的文件监听照常工作

## 构建与验证

**提交时直接跳过签名, 不要为这个去解锁 key**: 全局 git config 开了 `commit.gpgsign` / `tag.gpgsign` 且 `gpg.format=ssh`, 用的 key 带 passphrase, 而开发机上没有 ssh-agent, 所以非交互提交必然失败; 本仓库已经在本地 config 里关掉了 (`git config commit.gpgsign false` / `tag.gpgsign false`), **新克隆要再跑一次, 或者单次用 `git commit --no-gpg-sign`**

### 常用命令

| 动作 | 命令 |
| :-- | :-- |
| 快速编译检查 | `.\gradlew.bat :app:compileDebugKotlin` |
| 出 APK (**提交前必跑**) | `.\gradlew.bat :app:assembleDebug` |
| 装到设备 | `.\gradlew.bat :app:installDebug` |
| 看当前引擎解析出的版本 | `.\gradlew.bat :app:dependencies --configuration debugRuntimeClasspath` |
| 拉日志 | `adb logcat --pid=$(adb shell pidof -s io.github.miuzarte.littlewhale)` |

真机: `adb connect 192.168.1.103:5555`; submodule: `git submodule update --init --recursive` (新克隆后) / `git submodule update --remote third_party/deepseek-harness` (升级 dsh)

**改完 Kotlin 先跑 `:app:compileDebugKotlin`, 别跳**: AGP 9 + 内建 Kotlin 的编译错误信息有时候只说 "UNRESOLVED_IMPORT", 真正的文件行号在它上面几行

**但 `compileDebugKotlin` 通过不代表能打包**: 它**不跑 manifest merger**, 所以 `minSdk` 冲突、manifest 问题这类错误完全看不见 —— **每次改完依赖或 `minSdk`/`targetSdk`, 都要跑一次 `assembleDebug`**

### 迭代循环 (改 dsh / 改 Kotlin 各一条路, 照抄即可)

改 dsh 源码之后 (在 fork 开发克隆 `B:\Git\deepseek-harness` 里改, submodule 只接受同步过来的结果):

```powershell
cd B:\Git\LittleWhale
# 1. 生成补丁并同步进 submodule (取的是 fork 工作区里还没提交的改动, 已 commit 的就 diff <pin>..HEAD)
git -C B:\Git\deepseek-harness diff > build\lw-dsh-patches.diff
git -C third_party\deepseek-harness apply (Resolve-Path build\lw-dsh-patches.diff).Path
# 2. 出平铺 host 树 (pack 两个家族 + npm install, 约 5 分钟; 已经顺带做了 build)
node tools\pack-host.mjs --dsh third_party\deepseek-harness --out build\host-tree
# 3. 推进 app 沙盒, 重启 app, 看两个 tag
node tools\push-host.mjs build\host-tree
adb shell am start -S -W -n io.github.miuzarte.littlewhale/.MainActivity
adb logcat -d -s DshHost -s DshWebView
```

改 Kotlin 之后: `.\gradlew.bat :app:assembleDebug` → `adb install -r app\build\outputs\apk\debug\app-debug.apk` → 上面第 3 步的启动与看日志

**判据**: `DshHost: dsh web: http://…?token=…` = 加载器整棵树起来了; `DshWebView: shell {...}` 里 `rootChildren` 非 0 = GUI 渲染出来了 (那一行还带 `vh`, 界面"塌了"先看它)

**装完 APK 之后别让设备息屏**: host 树换了版本时首启要解压 3 万个文件, 而 app 一进后台就被系统冻住 (`ps` 里是 `do_freezer_trap`), 解压跟着停在原地**看着像卡死** —— 点亮屏把它拉回前台就会接着解压完

**动 `svc power stayon` 之前先把它读出来, 收尾时写回原值**: 那个开关就是用户自己的「充电时保持亮屏」(全局设置 `stay_on_while_plugged_in`), 无条件写 `false` 会把用户的设置**关掉**而没有任何提示。所以 `settings get global stay_on_while_plugged_in` 记下数字, 验证做完写回原值, **不要默认写 0**

**adb 上出现第二个设备时, 每条命令都要指名设备**: 不带 `-s` 会以 `more than one device/emulator` 失败, 包括 `tools/lw-bridge.ps1` 这种内部调 adb 的脚本; 省事的办法是当前 shell 里 `$env:ANDROID_SERIAL='192.168.1.103:5555'`, 子进程会继承

**每次 `adb install -r` 都会把我们踢出 `enabled_accessibility_services`**: 装完要么在设置页拨一下那个开关, 要么 `settings put secure enabled_accessibility_services <原值>:<我们的组件>` —— **同样要读出来改**, 设备上还有别人的服务

### adb 安装失败时怎么装 (termux / root 兜底)

小米 / HyperOS 上 `gradlew installDebug` 或 `adb install` 会失败 (典型原因是设备上弹了安装确认框而没人点, `INSTALL_FAILED_USER_RESTRICTED`, 或 MIUI 的"USB 安装"开关没开); 本机这台设备上**普通 `adb install -r` 其实是通的**, 下面这条是失败时的兜底, 已实测可用:

```sh
adb push app/build/outputs/apk/debug/app-debug.apk /data/local/tmp/lw.apk
adb shell "su -c 'pm install -r /data/local/tmp/lw.apk'"   # root 的 pm 绕过 adb 安装那套授权交互
adb shell "pm list packages | grep littlewhale"            # 确认
adb shell "su -c 'rm -f /data/local/tmp/lw.apk'"           # 清理
```

经 termux ssh (`ssh -p 8022 192.168.1.103`) 走 `su -c 'pm install -r ...'` 效果一样; `pm` 在 `/system/bin/pm`, root 下可用

## 还能做

第 8 步 (端侧 OCR) 留了三件, 按值得做的顺序: **rec 批量重导** (25 行现在 250-400 ms, 最大的一块) / **试 ORT 自己导出 ctx** (成了能拿掉 70 MB 的 `libQnnHtpPrepare.so`) / **det 换非正方形输入**; 量化排最后

不阻塞的尾巴:

1. **导出走页面内的 `blob:`**, 不经过 `DownloadListener`, 要接得加一座 JS 桥 (`addJavascriptInterface` 或 `WebViewCompat.addWebMessageListener`)
2. **jniLibs 挂 Release**: 那 17 个对象是 Termux 编的, 现在只在本机有一份, 正式形态是挂 GitHub Release 由 Gradle task 下载
3. **体积裁剪** (可选): 只有 `node-pty` 确定能删; 工具链那头考虑塞个 busybox (toybox 缺 `grep -P` / `sed -i` / `find -printf`), 但注意 PATH 顺序

还没验 / 没定的:

- 文件管理器能不能浏览 `/sdcard/DSH` 与 `Android/media/<pkg>/DSH`, 以及工作区没有 inotify 时轮询的实际开销 (必要时给工作区再开个"本地缓存目录")
- dsh 的 fs 工具有没有 fsync / rename-based 原子写, 在 FUSE 上语义可能不同
- 一块虚拟屏加一路预览 surface 到底占多少内存与 GPU (11 GB 设备上不紧张, 但要有数)
- 要不要把 `activity.allow` 换成更粘的刹车 (抬手 1 s 之后就能再动手), 以及那时用户在哪放开

> 本文档只留**现状、决策、约束、怎么操作**。每一步的清单 / 实测数字 / 踩坑在 `docs/step2-record.md` … `docs/step8-record.md`, 构建与依赖的旧账在 `docs/host-build.md`, fork 侧改动的唯一权威是 `B:\Git\deepseek-harness\patch.md`
