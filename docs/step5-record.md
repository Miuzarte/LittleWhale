# 第 5 步记录: 屏幕能力做成 dsh 原生工具 (2026-09-21 真机验过)

## 这一步要什么

模型能**列屏 / 建屏 / 关屏 / 点 / 拖 / 截图**, 而且截图它**真的看得到**。判据是模型自己调一次, 而不是我在 host 里 curl 一次

不做的事: 不写 MCP (理由见 AGENTS.md「dsh 工具怎么加」); `lw_launch` 这一步先没做 (`am start --display` 从 adb shell 一定行, 从 app uid **当天没验**, 见文末补记 —— 补做的结论是 app uid 根本不行)

## 工具面

插件还是那一个 (`host-plugin/index.mjs`), 现在有七个工具, 每个都是一次桥调用:

| 工具 | 桥方法 | 参数 | 说明 |
| :-- | :-- | :-- | :-- |
| `lw_probe` | `probe` | — | 通道活着吗, 以什么 uid 跑 |
| `lw_screen` | `screen` | — | 列出所有屏: displayId / 名字 / 尺寸 / dpi / 是不是预览里那块 |
| `lw_screen_create` | `create` | `name` `width` `height` `dpi` (都可省) | 建一块屏, 返回 displayId, 重名自动补序号 |
| `lw_screen_release` | `release` | `displayId` | 关掉一块屏, **必须带 id** |
| `lw_tap` | `tap` | `displayId` `x` `y` | 点一下, 返回时设备已经收下 |
| `lw_swipe` | `swipe` | `displayId` `fromX/fromY/toX/toY` `durationMs` | 拖一次, 一次事务 |
| `lw_screenshot` | `screenshot` | `displayId` (可选 `maxPixels`) | 拍一张, 落到工作区 |

## 链路

```
模型 → lw_tap 工具 (host 进程里的 ESM 插件)
  → 127.0.0.1:<临时端口> 一行 JSON (token 校验)
  → PrivilegedBridge.dispatch("tap")
  → VirtualScreen.tap(screen, x, y)
  → 单线程触摸队列 submit + get  (阻塞到设备回答)
  → LwServiceProxy.tap → binder → 特权进程 LwInput.tap
  → MotionEvent + setSource(TOUCHSCREEN) + setDisplayId + injectInputEvent(ASYNC)
```

桥的协议没变 (一连接一请求一行 JSON), 只多了 `tap` / `swipe` 两个方法; 而 **`release` / `tap` / `swipe` / `screenshot` 全都必须带 `displayId`**, 没有"默认打选中的那块"这条退路

## 四条决定

**1. 每个动作都显式带 displayId** 选中的那块是用户随时能改的, 而模型手里的坐标是它自己在某一块屏上量出来的: 拿"当前选中"当默认值, 就是让用户在模型出招的间隙换屏, 把它点到别处去。`release` 早就是这个规矩, 这一步把它推给所有动作

**2. `tap` / `swipe` 阻塞, 预览的触摸不阻塞** 预览是跟着手指走的, 一帧等一次往返就会掉下一帧, 所以它只往队列里丢; 工具不一样, 它返回之后模型立刻会截图看结果, 报"已排队"等于让模型自己猜发生了什么。于是同一个队列, 两种等法: 队列保顺序, `.get()` 保"做完了"

**3. swipe 是一次事务, 在特权侧按时长分步** 12 步均分 `durationMs`, 每步之间 sleep。不这么做的话, 一批同一毫秒的 move 在平台看来是"跳", fling / 滚动 / 阻尼全都读错速度; 而如果让 app 侧一步一步调 binder, 步长就取决于 binder 忙不忙, 手势快慢会随负载漂移

**4. 截图先落在 cache, 再由 app 拷进工作区** 特权进程写 app 的 cache 是两个 uid 都能命名的地方; 而模型的文件工具只在**工作区**里解析路径, 留在 cache 里就是"能告诉它路径、它永远打不开"。最终路径 `<工作区>/screenshots/screen-<displayId>.png`, 被下一次拍同一块屏覆盖

## 图片到模型: 为什么现在通了

这一步开工前发现的路障: **任何图片进模型都要过 `attachment-local`, 而它两个入口 (`detectImage` / `normalizeImage` / `request-image`) 全都要 `sharp`**, 而 pack 用的是 `npm install --omit=optional` —— 连 `@img/sharp-linux-arm64` 平台包都没进树

真机复现 (设备上直接跑那份树):

```
require sharp: FAILED  Could not load the "sharp" module using the android-arm64 runtime
@img contents: colour
```

两条一起解决:

**(a) 截图在产生时就缩到 route 的像素预算** 手机的 `settings.yaml` 里 `llm-deepseek.models[].imagePixelBudget` 是 **640000**, 而 1080x2400 是 2,592,000 —— 超了, 于是"请求图片"那一环必须重编码, 也就是必须有编码器。既然这台设备上没有编码器, 就把缩放搬到**产生图片的地方**: `Picture.fit()` 用 `BitmapFactory` + `createScaledBitmap` + `compress(PNG)`, 缩到预算之内。缩完的图在两条链上都是 **pass-through** (持久化 ≤ 4.19M 像素, 请求 ≤ 640k 像素), 一个像素都不用重编码

**(b) host 树里的 `sharp` 换成纯 JS 替身** 光有 (a) 还不够: 连 pass-through 也先要 `detectImage` 报出"格式 / 宽高 / 位深 / 色彩空间 / 有没有元数据", 而那一步就要 `sharp`。`image-backend/sharp/` 是那个替身, 只做两件事: 从 PNG 头读事实 (`format/width/height/depth/space/hasAlpha/pages`), 以及用 `zlib.inflateSync` + 反过滤**真的解一遍像素**当作"这些字节是完整的"那个证明。终端编码 (`webp` / `jpeg` / `toBuffer`) **一律明确报错**, 不假装能编码

替身由 `tools/pack-host.mjs` 在 `npm install` 之后覆盖到 `node_modules/sharp` (和插件同一套做法), 所以改的就是树, 不是 fork

**验**: 在本机把 fork 的 `sharp` 临时换成替身, 让模型 `read_image` 那张 536x1192 的截图 —— 图到了模型那里, 它把设置页描述对了 (`设置` / 搜索框 / 缪纱特 / WLAN 连着 OpenWrt_C210 / 蓝牙已开启); 而 1080x2400 那张失败, 报的是 `TRANSPORT: DeepSeek API stream from https://api.deepseek.com failed` (它在"请求图片"那一环要重编码, 替身按设计拒绝了)

**已知限制** (要有数): 替身只认 PNG (颜色类型 0/2/4/6, 8 或 16 位, 非隔行); JPEG / WebP / GIF / 调色板 PNG 读不了; **大于 route 预算的图会失败** —— 也就是说"用户自己塞一张大图进来问"这条路还没通, 要么补一个 Kotlin `Bitmap` 后端, 要么就此打住

## 实测数字 (小米 13, 2026-09-21)

| 项 | 数字 |
| :-- | :-- |
| `screen` 往返 | 中位 **11.9 ms** (min 9.8 / max 18.6, 20 次) |
| `tap` 往返 | 中位 **54.0 ms** (其中 40 ms 是刻意按住, 10 次) |
| `screenshot` 往返 | 中位 **388.5 ms** (min 375 / max 411, 5 次) |
| 截图缩放 | 1080x2400 / 176,582 B → **536x1192 / 79,744 B**, 倍率 **2.0149** (638,912 像素) |
| 工具调用 | 模型自己走完 `lw_screen` → `lw_screenshot` → `lw_tap(540,963)` → `lw_swipe(540,2000→540,800, 300ms)` → `lw_screenshot`, 点开的是设置里的 WLAN 页, 拖是滑到了列表底部 |

## 踩的坑

- **`VirtualScreen` 的私有 `touch()` 忘了加 displayId**: 公开的三个方法都加了参数, 里面那个还是老签名, 报错只说 `ARGUMENT_TYPE_MISMATCH ... actual type is 'Int', but 'Float' was expected`, 得自己去找行号
- **本地 spike 覆盖层要指向 `app/build/host-tree`, 不是 `build/host-tree`**: 后者是 `pack-host.mjs --out` 手工跑出来的老树, 里面没有插件目录, 报 `ERR_MODULE_NOT_FOUND`
- **`run-as` 跑 headless 有两个环境限制**, 都不是代码的错但会看成 bug: 它给 app 的 uid 却**不给 app 的 mount namespace**, 于是 `/storage/emulated/0` 不可达 (`cd: Permission denied`); 而即使把 session 目录设在沙盒里, 文件工具读那张图仍会 `EACCES: permission denied, open '/data/user/0'` —— 读路径上有人要 open 文件的祖先目录, 而 `/data/user/0` 是 0711 (可穿过不可打开)。**所以设备侧的 turn 只适合验证"作用于屏幕"的调用, 验证读图要在 app 自己的 host 里**
- **`TMPDIR` 要指向 `/data/user/0/<pkg>/cache`**, 不是 `files/cache`: `spill-local` 会 `mkdtemp` 在 `$TMPDIR` 下, 目录不存在直接让整棵 profile 起不来

## 真机验证清单

桥 (不经模型, `tools/lw-bridge.ps1`):

- `create 微信` → `{"created":true,"displayId":36,"label":"微信"}`; `screen` 列出 1 块
- `am start --display 36 -n com.android.settings/.Settings` → `screenshot` 拍到的是**那块屏**的设置页 (画面已确认)
- `tap 540,963` → 再拍, 页面变成 WLAN; `swipe 540,2000→540,800` → 列表滑到底
- 四条拒绝: 不带 `displayId` (`this call has to name the displayId it means`)、`displayId 9999` (`no screen with displayId 9999, ask screen for the ones that exist`)、`swipe` 缺 `fromY` (`fromY has to be a number`)、`screenshot` 不带 id (同第一条)
- `release 36` → 列表回到 `count 0`

模型 (设备自己的 host 树与 home, `tools/lw-device-turn.ps1`):

- `lw_screen` 被模型调用并原样报回 JSON (插件确实被设备上那份树加载; `--dump-config` 里也看得到 `id: littlewhale-channel` → `files/host/node_modules/littlewhale-channel/index.mjs`)
- 本机 headless 对**手机上的桥** (adb forward + `LW_CHANNEL_ENDPOINT`): 模型连调五个工具, 结果与上手点的一致
- 本机 headless + 替身 `sharp`: `read_image` 那张缩过的截图, 模型描述正确 (见上)

## 补记: `lw_launch` (2026-09-21 补做, 真机验过)

这一步留下的第一条尾巴当天就补掉了, 而它留下的那个"还没验"的问题答案是**不行**:

```
run-as <app> /system/bin/am start --display 44 -n com.android.settings/.Settings
→ SecurityException: Permission Denial: package=com.android.shell does not belong to uid=10326
    at ActivityTaskManagerService.assertPackageMatchesCallingUid
```

**`am` 以 `com.android.shell` 的身份自居**, 所以 app uid 调它一律被拒 (另一个角度: `am get-current-user` 要 `INTERACT_ACROSS_USERS`)。模型的 bash 与插件的 Node **都是 app uid**, 所以"让模型在 shell 里敲 `am start --display`"这条路根本不通 —— 这不是"够不够用"的取舍, 是走不通

同一个命令在特权 uid 下完全正常, 而且包名形式还能顺带报出解析结果:

```
su -c 'am start -W --display 44 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p com.android.settings'
→ Status: ok   LaunchState: COLD   Activity: com.android.settings/.MiuiSettings
```

于是做法就是**特权进程里跑一句 `am`**, 与 `runGetevent` / `LwPermission` 同一个模子:

- 新事务 `LAUNCH = +13` → `LwServiceProxy.launch(displayId, package, component, timeoutMs)`, 回 `(exitCode, output)` (先写字符串再写 int, 两边顺序对上)
- `channel/LwLaunch.kt`: `ProcessBuilder("/system/bin/am", "start", "-W", "--display", <id>, …)`, 收 stdout+stderr, 超时杀掉 —— **`-W` 是等"起完了"而不是等"请求收到了"**, 所以时间预算按冷启动给 (默认 20 s)
- 目标两种: 给 `component` 就用 `-n`; 只给 `package` 就走 `-a MAIN -c LAUNCHER -p <pkg>`, 由平台自己解析, 输出里带着它选了哪个 activity
- 工具 `lw_launch(displayId, package | component)`, 恰好给一个; `started` 由 `am` 自己的 `Status: ok` 与退出码共同判定, **`am` 说了什么原样带给模型**

不选另一条路 (app 自己 `startActivity` + `ActivityOptions.setLaunchDisplayId`): 代码量差不多, 但要撞 BAL (后台启动 activity) 限制 —— 用户在前台看 GUI 时能起, 屏关着或 app 退到后台就被静默拦掉, 而"无头驱动一块屏"正是这个项目的核心用法

## 补记: 按键与打字 (`lw_key` / `lw_type`, 2026-09-22)

模型要按 BACK / HOME / 音量这种**平台自己处理**的键, 而它们不在任何屏幕的树里: `lw_ui` 看不到, `lw_tap` 也无处可点。shell 那条路与 `am` 撞的是同一堵墙:

```
run-as io.github.miuzarte.littlewhale /system/bin/input keyevent 0
→ java.lang.SecurityException: Injecting input events requires the caller (or the source of the
  instrumentation, if any) to have the INJECT_EVENTS permission.
```

于是做成新事务 `INPUT_KEY = +15` (VERSION `"6"` → `"7"`):

- **`LwInput.key`**: 造一个 `KeyEvent` (deviceId 用 `KeyCharacterMap.VIRTUAL_KEYBOARD`, source 是 `SOURCE_KEYBOARD`), `setDisplayId` 之后 `injectInputEvent`, 先 DOWN 后 UP, **两半共用一个 `downTime`** (平台按它把 UP 配回 DOWN); 长按 = 按住 ~600 ms **并让 UP 带 `FLAG_LONG_PRESS`**, 与 `input keyevent --longpress` 同一个语义 (控件与 policy 读的是那个 flag, 不是两半之间的时间)
- **传名字而不是编号**: 表从 `android/keycodes.h` 生成 (`tools/gen-keycodes.mjs`, 338 个名字, 实测 `BACK=4` `HOME=3` `APP_SWITCH=187` `ENTER=66` `DPAD_DOWN=20` `POWER=26` `WAKEUP=224` `ASSIST=219`)。理由是错的编号不是错误而是**另一个键** —— 5 是打电话, 26 是电源键, 27 是相机; 名字不认识可以拒, 编号不认识就直接按下去了。名字接受 `KEYCODE_` / `AKEYCODE_` 前缀与任意大小写, 数字按同一张表校验, 名字与编号给全或都不给都会被拒
- **`longPress` 是独立的布尔, 不叫 `holdMs`**: "按住多久"不是调用方该关心的事, 而"是不是长按"是平台自己的概念 —— **2026-09-22 改了, 只对了一半**: flag 那一半对 (UP 上的 `FLAG_LONG_PRESS` 仍是判据), 但"按住多久"确实是调用方能关心的事 (语音要按住不放, 电源键按多久是不同的行为), 现在是一个 `hold` 字符串参数, 见末尾的补记
- **整屏的键在别人的屏上被拒**: `HOME` / `POWER` / `SLEEP` / `SOFT_SLEEP` 的意思是"离开这块屏"或"把这块屏关掉", 而我们的屏没有 launcher 也没有系统 UI —— 实测往虚拟屏发一个 HOME 之后那块屏 `state OFF`, 而 `screencap` 照样把**上一帧**交出来, 模型会看着一张冻结的图以为屏上还是那样。所以它们只在主屏放行

### 打字 (`lw_type`)

打字也是两条路, 而**先走哪条是关键**: 主路是**无障碍的 `ACTION_SET_TEXT`** —— 先在一块屏上找到字段 (有焦点的那个, 否则全屏唯一一个可输入的, 再否则什么都不做、把候选列出来), 再把文本写进去。它比按键强在三处: **中文 / emoji 都行** (按键只能产出虚拟键盘映射里的字符, `input text` 也是这个限制), **不需要 IME 在场** (屏在后台、没人在看也照样写进去), **不依赖焦点窗口**。只有整块屏**一个字段都没有**时才报 `via: keys` 退回按键 (新事务 `INPUT_TEXT = +16`)

其余几条:

- 默认**在光标处插入** (`textSelectionStart/End` 给的插入点, 拿不到就接在末尾), `replace: true` 才整段覆盖 —— "打字"的语义是接着写, 覆盖得说出口
- 写完**把光标移到新文本之后**, 再 `refresh` 读回字段实际的样子: 字段可能自己加工输入 (格式化、截断、掩码), 交回来的以读回的为准
- **密码字段不回读内容**, 只说写进去多少个字符
- 一次最多 4096 字符 (再多就不是"打字"而是搬文件, 一个 parcel 也不该那么长)

## 实测 (192.168.1.103, 服务 v7 / v8)

| 验的东西 | 结果 |
| :-- | :-- |
| 主屏按 BACK | 进 WLAN 子页后按 BACK, 树回到设置主页 |
| 名字的写法 | `BACK` / `KEYCODE_BACK` / `keycode_home` / `home` 都认, 回话里带规范化后的名字与编号 |
| 编号 | `code: 26` 认成 `POWER`, `code: 999` 被拒 |
| 坏请求 | 不存在的名字给出候选清单; 名字与编号同时给、或都不给, 都被拒 |
| 只作用于指名的屏 | BACK 打在 display 73 上, 那块屏翻页, 主屏的 `topResumedActivity` 一个字没变 (logcat 里 `WindowManager: Attempting to move non-focused display 73 to top because a key is targeting it`) |
| 主屏的刹车照样管它 | 真手指按着时 `key(0, BACK)` 被拒, 同一时刻 display 73 上的 `DPAD_DOWN` 正常 |
| 整屏的键 | 虚拟屏上 `HOME` / `POWER` 被拒 (理由点名它是我们自己的屏), 主屏上 `HOME` 正常 |
| 长按 | `longPress: true` 被接受并注入 (UP 带 `FLAG_LONG_PRESS`) |
| 打字: 中文进字段 | 设置搜索框里写 "蓝牙" → `via: field`, `written: 2`, 字段读出 `蓝牙`, 搜索结果随即出现在树上 |
| 打字: 续写与覆盖 | 再写 "开关" → 字段变 `蓝牙开关`; `replace: true` 写 "wlan" → 字段变 `wlan` |
| 打字: 没有字段的屏 | 设置主页 (只有一列行, 没有可输入节点) → `via: keys`, `written: 4`; 同一块屏上写中文被拒 ("the keyboard cannot produce 蓝牙") |
| 打字只落在指名的屏 | 在 display 74 的搜索框写 "显示" → 那块屏的字段与结果都变了, 主屏 (LittleWhale) 一个字没动 |
| 打字也受刹车管 | 真手指按着时 display 0 的 `type` 被拒, 同一刻 display 74 上的正常写入 |

三个坑:

- **表是按行折行的, 解析不能按行**: 第一版把一整行当一条 (`DPAD_CENTER=23 VOLUME_UP=24 …` 里只有第一对解得出来), 于是 `BACK` 成了"Android 没有这个键" —— 现在按空白切分
- **`KeyEvent` 没有 `recycle()`**: 它不是 `MotionEvent` 那种池化对象, 方法在 SDK 里是隐藏的, 编译期就报 `UNRESOLVED_REFERENCE`
- **刚 `create` 完立刻 `screenshot` 可能回 "the device wrote no picture"**: `dumpsys SurfaceFlinger --display-id` 里那块屏要过几秒才登记上, 重试一次就有 —— 与按键无关, 是截图那条路的一个时间差

## 留下的

- **非 PNG / 超大图**: 见上面「已知限制」, 要么补 Kotlin `Bitmap` 后端, 要么维持现状
- **第 6 步那个刹车**: 现在这些工具注进去的事件**没有任何拦截**, 而审批是全部放行的, 所以第 6 步是唯一的刹车, 别再往后拖

## 补记: 按住多久变成一个参数 (2026-09-22 真机验过)

第 5 步把长按做成一个布尔, 理由是"平台读的是 UP 上那个 flag, 不是两半之间的时间"。这话对一半: flag 确实是判据, 但**按住多久是调用方能关心的事** —— 微信语音要按住不放, 电源键按 1 秒与按 3 秒是不同的行为, 而"长按"这个词在 500ms 与 3s 之间没有任何区分。所以 `longPress: boolean` 换成了 `hold`:

- **值是字符串**: 认 `"1s"` / `"500ms"` / `"1.5s"`, 认裸数字 (按秒, `hold: "1"` 与 `"1s"` 一样), 也认三个名字 —— `short` 600ms / `medium` 1.5s / `long` 3s。上限 10s (手势队列整段时间都被占着), 解析不出来与超上限都给一句能读懂的理由
- **名字为什么从 600ms 起**: 平台自己的长按阈值是 `ViewConfiguration.getLongPressTimeout()` = **500ms**, 那才是分界线, 再往长只是"多按了一会儿"。而 8 秒的电源键在很多机器上是**硬件级强制重启**, 不该有一个 `long` 随手就够得着 —— 真要就给 `"8s"`, 写出来是故意的
- **设备侧**: `holdMs` 换成原来那个布尔的位置 (`INPUT_KEY` / `INPUT_TAP` 都带), 长按 = 按住那么久 + UP 带 `FLAG_LONG_PRESS`; **500-650ms 之间会补到 650ms**, 因为平台的检测器就在那一刻跑, 抬手快了 (主线程一忙) 就会把长按变回普通点击, 而调用方永远不会知道
- **`lw_tap` 也能按住**: `lw_tap(text=…, hold=…)` 先试节点的 `ACTION_LONG_CLICK` (要 `isLongClickable` 的祖先), 树里没有就退回**按住的手指**落在那个节点的矩形上 —— 实测启动器图标与设置搜索框都不是 long-clickable, 所以两次都走的兜底, 效果是图标的长按菜单 (小窗 / 编辑图标 / 信息 / 移除)
- **按住也归刹车管**: 主屏上的一次按住按 8ms 切片盯真手指, 来了就当场抬手, 回一句 "the press … was cut short after N ms"; `lw_key` 的按住同理

实测 (小米 13 / Android 16, 服务 v8 → v9):

| 验的东西 | 结果 |
| :-- | :-- |
| 解析 | `hold: "2 minutes"` → `hold has to be a duration like "1s" or "500ms", or one of short / medium / long`; `hold: "20s"` → 超上限那句 |
| 按住真的按住了 | `lw_tap(hold="short")` 落在一个启动器图标上, 600ms 后图标的长按菜单出现 (截图确认) |
| 按键的时长 | `lw_key(key="WAKEUP")` 往返 210ms; `hold: "1"` 往返 1196ms, 回话 `long-pressed … held for 1s` |
| 按名字的长按 | `lw_tap(text="微信", hold="short")` → `pressed TextView "微信" … by a touch at its centre … held for 600ms` (ACTION_LONG_CLICK 被拒 → 兜底, 菜单真的出现) |
| 刹车管按住 | 3s 的 tap 在第 681ms 被一只能伪造的真手指截断并抬手; 3s 的 key 按住在第 651ms 同样被截断 |
| 不带 hold 的一切照旧 | `lw_tap` 仍回 `holdMs: 0` 且 `longPress: false`, `lw_key` 仍是一次普通按键 |

顺带修掉一个**老 bug**: 插件的 `lw_tap` 原来按 `outcome === 'clicked'` 判断"按到了没有", 而兜底那一支的 outcome 仍是 `unclicked` (只是 `clicked` 已经是真) —— 于是它会**再退到读屏**, 报告说"没按到", 屏幕上可能因此按第二次。现在判据是 `clicked` (`namedTapReport` 里同样)

## 补记: `read_image` 曾经对任何图都失败 (2026-09-22 真机定位并修掉)

手机上的一轮会话报上来的: 截图工具照常出图, 但 `read_image` 读**任何** PNG 都是同一句错, 三次调用三次一样:

```
Error: EACCES: permission denied, open '/data/user/0'
```

要紧的是**报错路径不是那个文件**, 而是 `/data/user/0` 这层目录; 把文件 `su` 拷到 app 自己的家目录、或是换成工作区里的相对路径, 报的还是一模一样的错 —— 说明它跟被读的文件无关, 是在解析/落盘的某一步踩到了 `/data/user/0`。

**定位**: 根因不在工具也不在 `sharp` 替身, 而在**附件落盘的持久化台阶**上。设备上直接把 store 拉出来跑 (见下), 栈是

```
attachments.saveImage → commitPreparedImageFile → publishImmutableObject
  → stageImmutableObject → ensureDurableHome(DSH_HOME)
  → ensureDurableDirectory(home, '/') → syncDirectory('/data/user/0')
  → open(dir, O_RDONLY) → EACCES
```

`ensureDurableDirectory` 为了让新建的目录项落盘, 会从 DSH_HOME **一路 fsync 到文件系统根**, 而安卓上 `/data/user/0` 是 `drwxrwx--x` (0711, 上面那级 `/data/user` 是 `r-x--x--x`): 应用能穿过、**不能读**, 而 `open(dir, O_RDONLY)` 要的正是读权限。桌面平台上这条链每一级都可读, 所以上游遇不到 —— 这是纯安卓的形状。

**为什么只有图片中招**: 别的写盘路径不上这条"到根"的台阶 (会话持久化与 `storage-json` 只 fsync 文件自己那一级, 而那级在 DSH_HOME 里, 读得开), 只有附件存储会 `ensureDurableHome`。所以症状看起来"只有 `read_image` 坏了", 其实坏的是一个共享的落盘助手。

**修法** (fork 侧 `patch.md` 第 8 节): `syncDirectory()` 遇到**打不开的目录**就跳过 —— 那些层属于平台 (安装时就建好、早落盘了), 而这个进程**能**打开的那些层照样 fsync; 判据是 `EACCES`/`EPERM` 错误码, 不是平台判断, 所以别的平台行为一个字节都不变。

**怎么验的** (没有每次等装机): 设备上有一份**它自己的 host 树**, 而且能拿到 **app 自己的 node** (`<nativeLibraryDir>/libnode.so`, `--version` 直接可跑), 于是可以 `run-as <pkg>` + app 的 uid 直接调用那个 store:

```sh
run-as <pkg> env OPENSSL_CONF=<files>/openssl.cnf DSH_HOME=<files>/dsh-home \
  <lib>/libnode.so verify.mjs file://<host>/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js <png>
```

| 时刻 | 结果 |
| :-- | :-- |
| 修复前 | `saveImageFile` 抛 `EACCES: permission denied, open '/data/user/0'`, 栈与上面一致 |
| 修复后 | 同一个 191482 字节的 PNG 落盘成功, 拿到 `sha256:2a9428…` 的引用, 读回来字节数一致 |

**加上一次真的模型回合** (400x300 的渐变图, 122480 字节): `read_image` 回 `<path>…</path> / image/png image, 400x300 px, 122480 bytes`, 图进了 `attachments/v1/objects/bc/bcf0…`, 而模型**真的看见了**: "A smooth multi-color diagonal gradient…" —— 从工具一直到模型那边都通了。

两条要记住的:

- **1080x2400 那张读不了** (259 万像素, 超过截图预算 64 万): 模型那边报 `TRANSPORT: DeepSeek API stream failed`, 换成 400x300 就好了。这与本记录里那条"只有 PNG 且不超预算的图能进模型"是一回事 —— 超预算要重新编码, 而设备上没有编码器。**截图工具出的图本来就在预算内** (1080x2400 → 536x1192), 所以正常那条路不受影响
- **`tools/lw-device-turn.ps1` 仍然读不了 `/storage/...` 上的图**: 它走 `run-as`, 拿不到 app 的 mount namespace, `realpath('/storage/emulated/0/DSH/...')` 直接 `EACCES` (报错长得像上面那条, 但位置是 realpath、路径是共享存储)。**app 自己进程里没这个问题** —— 用户那轮会话能一路读到字节, 是在落盘那步才挂的, 这就是证据。所以要端到端验读图, 图得放在 **app 自己的数据目录**里 (那次 400x300 就是这么验的)

## 补记: `lw_screen_resize` / `lw_screen_rotate`, 以及"默认用虚拟屏" (2026-09-22 真机验过)

用户要的两件事: 一个换尺寸的工具 + 一个 `rotate` 便捷别名 (宽高对调), 以及**工具描述里把"用户没说用哪块屏时优先虚拟屏"写明白**, 顺带提醒游戏那种要横屏的最好建屏时就定好

工具面从 12 个到 14 个:

| 工具 | 参数 | 说明 |
| :-- | :-- | :-- |
| `lw_screen_resize` | `displayId` + `width?` / `height?` / `dpi?` | 给已有的屏换尺寸, 没给的维度保持原值 |
| `lw_screen_rotate` | `displayId` + `dpi?` | 就是 `lw_screen_resize` 的 `swap: true`, 宽高对调、dpi 不动 |

桥那侧**一个方法** `resize` (`swap` 只是它的一个字段), 所以两个工具是同一条路。几处拒绝各有各的话:

| 调用 | 回答 |
| :-- | :-- |
| `resize` / `rotate` 指 `displayId 0` | `nothing was resized: displayId 0 is the phone's own screen … its size belongs to the device and to how the user is holding it` |
| `width=5` | `THREW: width of 5 is not a screen: a side is between 64 and 8192 pixels` |
| 一个维度都没给 | `THREW: lw_screen_resize needs at least one of width, height or dpi to change` |
| 指向一个不存在的 id | `THREW: no screen with displayId 777, ask screen for the ones that exist` |

**屏幕形状的两件事不在工具那一层**, 在 `docs/step4-record.md` 的补记里 (换尺寸后第一张图是老帧 → app 侧按 PNG 宽高重拍; 锁方向的应用不会被重排而是留一条带子)。工具描述按那两条实测写: "跟着屏走的会重排并铺满, 锁方向的原样留一条带子", 没有一句"换了尺寸就会重排"

顺带修的一个**静默错答**: `LwCapture` 只看文件长度判断"设备写图了没有", 而 `screencap` 失败时**什么都不写** —— 上一次那张图还在的话, 一次失败的拍摄会把**上一块屏的画面**当成这次的结果交出去。现在拍摄前先把目标文件删掉 (`fresh(path)`), 于是"没写"就真的是空的, 报的是"设备没写图"而不是"这是你的屏"

描述上另外三处措辞 (都在插件里, 是模型唯一会读的东西):

- `DISPLAY_ID` (每个动作工具共用的那个参数) 与 `lw_screen`: **用户没说用哪块屏就用虚拟屏** —— displayId 0 是别人手里那台手机, 动它就是把它从人手里拿走, 手指在玻璃上时还会被直接拒; 要就去 `lw_screen_create` 造一块, 不要因为"它已经在那儿"就用 0
- `lw_screen_create` / `lw_launch`: 已经知道应用要什么形状就在这里给 —— 横屏应用给 `width > height`, 锁方向的应用在形状不对的屏上只会留一条带子
- `lw_screen` / `lw_screen_create`: 形状不是终局, 建完还能 `resize` / `rotate`

## 补记: 双开的应用 (`lw_launch` 的 `user`, 2026-09-22 真机验过)

用户问"能不能操作双开的那个 b 站"。**能, 而且双开不是另装一个包, 是同一个包装在另一个 Android user 里**:

```
$ pm list users
	UserInfo{0:謬紗特:4c13} running
	UserInfo{999:XSpace:801010} running          # HyperOS 的「应用双开」= user 999, 名字 XSpace
$ pm list packages -f --user 0   | grep tv.danmaku.bili   → 同一行, 同一个 base.apk
$ pm list packages -f --user 999 | grep tv.danmaku.bili   → 同一行, 同一个 base.apk
$ ps -A | grep bili → u0_a289 8948 tv.danmaku.bili / u999_a289 785 tv.danmaku.bili
```

包名、APK、启动组件**三者都一样**, 只有 userId 不同 (数据目录 `/data/user/999/…`), 所以"起哪一个"就是 `am` 的那个 `--user`:

```sh
am start -W --display <id> --user 999 -n tv.danmaku.bili/.MainActivityV2
```

不需要额外权限 (root 与 shell 都行), `--display` 与 `--user` 可以一起用; 组件解析 `cmd package resolve-activity --user 999` 与 user 0 返回同一个组件。`monkey -p` 没有 `--user`, 起的是 u0 那份; app uid 仍然整条被拒 (与 `--user` 无关), 所以这件事只能留在特权进程里

落地的改动就是**把 userId 透传下去**: 协议 `LAUNCH` 多一个 `int` (服务版本 `10` → `11`), `LwLaunch.start()` / `base()` / `launcherOf()` 都带上它, 桥的 `launch` 收 `user` 并回给工具, `lw_launch` 多一个 `user` 参数。**`user` 从哪来**: `lw_probe` 现在多问一句 `pm list users` (又一个 app uid 问不了的命令), 答案里会多一行

```
users: 0 (謬紗特), 999 (XSpace) - an app installed for two of these is two copies with their own
data, so name the second one as lw_launch's user to start the copy rather than the original
```

只有**多于一个 user** 时才打这一行 (一个 user 是所有手机出厂的样子, 每次都说就是噪音), 而这一行正是模型缺的那个数字

### 跨 user 能不能真的操作到, 三件事都验了

把 `tv.danmaku.bili` 以 `user: 999` 起在一块虚拟屏 (displayId 89) 上, `dumpsys activity` 确认落在 `Display #89`: `Task{… A=99910289:tv.danmaku.bili U=999 …}` / `app=ProcessRecord{… 785:tv.danmaku.bili/u999a289}`。然后

| 那条路 | 结果 |
| :-- | :-- |
| 无障碍读树 (`lw_ui`) | **读得到**: `displayId 89 "shape-test" 1080x2400, tv.danmaku.bili, 92 readable controls` —— 双开那份自己的资料页 (昵称 / 硬币 / 关注) 逐条列出来。无障碍服务跑在 user 0, 树却是 user 999 的窗口 |
| 按名字点击 (`lw_tap(text="动态")`) | **按得到**: `pressed TextView id=following_dec "动态" … by a touch at its centre, because it does not take an accessibility action` —— 跨 user 的真触摸平台也收下了, 截图确认那个标签选中了 |
| 截图 (`lw_screenshot`) | 正常出图 (u999 在前台时拍到的就是它的画面) |

**一个要记住的观感问题**: 点完立刻拍, 可能拍到**半张画** (左边画完、右边还是白的) —— 那次 `clone-tap.png` 就是这样, 十几秒后再拍就完整了。`captureSettled` 只看尺寸, 判不出"这一帧画完没有", 所以这属于"改完界面再看一眼"的常识, 不是能自动判的东西

### 还没验的

- user 999 处于 quiet mode / 未启动 / 锁定时 `--user 999` 会怎样 (两种状态现在都是 `RUNNING_UNLOCKED`)
- 已经在别的屏上跑着的那个任务, 再 `--user 999 --display <新屏>` 起, 只会得到 `Warning: Activity not started, it has been delivered to currently running top-most instance` (任务留在原屏) —— 要挪屏得先 `am force-stop --user 999 <pkg>`, 这条没有额外处理, 设备原话就那样

## 补记: 截图还要缩到"字节"预算, 不然整个模型请求会变成一个 TRANSPORT (2026-09-23 真机定位并修掉)

用户报上来的一段 `TRANSPORT`, 完整原话:

```
重试延迟：8324毫秒
失败原因：DeepSeek API stream from https://api.deepseek.com failed
本轮运行失败 … TRANSPORT
```

**它不是网络问题**。查的是设备上那条会话 (`session-12c38311…`, 在虚拟屏上玩明日方舟), 把 `session.v3.jsonl.zstd` 一帧一帧解开看的:

| 线索 | 数字 |
| :-- | :-- |
| 每次失败尝试的耗时 | **85 ms** (0.088 / 0.083 / 0.084 / 0.098 / 0.083 / 0.085 / 0.069 s) —— 连一次上传都来不及, 根本没出网 |
| 报错措辞 | `API **stream from** … failed`; fetch 失败是另一句 `API **request to** … failed` |
| 上下文里的图 | 00:09 那四张 672KB / 115KB / 693KB / 752KB **都 < 1 MiB**, 请求全部成功; 00:10 最后一张 **1,285,209 字节** 进来, 紧接的 step 15 开始失败 (turn2 六次 + turn3 六次) |
| 路由预算 (`DSH_HOME/settings.yaml`) | `imagePixelBudget: 640000`, **`imageMaxBytes: 1048576`** |
| 那张图 | 1192x536 = **638,912 像素** (像素刚好合格), **1,285,209 字节** (超 1 MiB 的 22%) |
| 重试 | `["normal",5,["EMPTY_RESPONSE","RATE_LIMIT","SERVER","TIMEOUT","TRANSPORT"],500,10000,0.1]` → 8324ms 是最后一次 (8000±10%) |

链路 (代码在 `attachment-local/src/request-image.ts` 的 `createRequestImage`): **只有"尺寸没被缩"且"字节 ≤ maxBytes"才原样交出去**, 否则走 sharp 的质量阶梯重编码。设备上的 `sharp` 是 `image-backend/sharp/` 那个纯 JS 替身, 它的终端编码**故意必抛**。那个异常不是 `LlmError`, 于是被 `llm-deepseek/src/adapter.ts:517` 的兜底包成 `TRANSPORT`(网络错) —— **真话被丢掉, 而且 `TRANSPORT` 在可重试集合里, 白重试 5 次**; 那张图还在上下文里, 所以之后每一轮都再失败一次。

设备上按 app 的 uid 复现 (拿真的那张图、真的策略过一遍同一个函数):

```
uid=10326 png=1285209B 1192x536 policy={"maxPixels":640000,"maxBytes":1048576}
THREW - this device has no WebP encoder: the image is fine, but it has to arrive already
        inside the size the model request accepts, because nothing here can re-encode it
```

**修法 (app 侧)**: `Picture.fit` 现在同时按两个预算缩 —— 像素按设置页那一档, **字节硬编码 1 MiB** (dsh 的 `imageMaxBytes` 缺省, 这台设备也正好是 1 MiB)。PNG 保持不变 (它是设备唯一能端到端读回的格式), 装不下就把画面编码小一点再来一次: 先按面积比猜一版, 量出**真实字节数**之后再按余量往预算内放大回去 (最多 4 轮), 最后把选中的那一版写回文件。实测一块铺满竖屏的游戏画面 (原图 5.05 MB, 截屏 2,356,715 字节):

```
LwPicture: screen-102.png came out 459897 bytes at 357x795, under 1048576: trying 536x1192
LwPicture: scaled screen-102.png from 1080x2400 (2356715 bytes) to 536x1192 (852835 bytes)
```

**为什么不是"PNG 超了就换 JPEG"**: 换不了。附件库入库时 (`attachment-local/src/image.ts` 的 `detectImage`) 会对每张图做一次**全量解码**当"字节完整"的证明, 而纯 JS 替身只解 PNG。设备上拿一张 629 字节的真 JPEG 试过:

```
FAILED INVALID_IMAGE Unsupported or malformed image data.
    at detectImage (…/dsh-attachment-local/lib/index.js:203:9)
```

也就是说 JPEG 截图**连库都进不去**, `read_image` 会直接失败 —— 要让 JPEG 走得通, 得先有一个能在设备上跑的 JPEG 解码器, 那是另一个工程。

**验证 (都在设备上)**:

| 那张图 | 走 `readRequestImageFile` 的结果 |
| :-- | :-- |
| **App 这次产出的截图** (536x1192, 852,835 字节) | `OK 原样交出 852835B image/png 536x1192 (改过没: false)` |
| 用户那次卡住的原图 (1192x536, 1,285,209 字节) | `THREW - this device has no WebP encoder` |

再加一次**真会话** (`tools/lw-device-turn.ps1`, 图放在 app 自己的目录里让 run-as 读得到): 模型 `read_image` 那张 852 KB 的图, 原文回

> The picture is a phone screenshot of an image viewer showing an anime-style illustration - a red-haired figure holding a sword against a futuristic cityscape - with the date "2026年9月23日 0:56" at the top and 发送/编辑/详情 buttons at the bottom.

**还留着的缺口**: 这条修的是**截图**这条路。用户自己往工作区里放一张 > 1 MiB 的图再让模型 `read_image`, 仍然会在同一处炸 (app 管不到别人的文件), 而且**报的还是 `TRANSPORT`**。要堵它得选一个: 把这张图压到 1 MiB 以内 / 抬 `settings.yaml` 的 `imageMaxBytes` (API 是否收得下未验) / 在 fork 侧把图片准备失败包成自己的 code 并移出可重试集合 (那样至少能看见"没有编码器"这句真话)


## 补记 (2026-09-23): 列应用 (`lw_apps`) 与按名字启动

起因是模型想自己找应用, 在 bash 里跑了 `pm list packages`, 被拒。查下来是**两堵墙叠在一起**, 而且都不该由模型的 bash 去翻

### 两堵墙 (都在 192.168.1.103 上量的)

| 谁 | 命令 | 结果 |
| :-- | :-- | :-- |
| **app uid** (模型的 bash 就是这个身份) | `pm list packages` | 自己的包 + `SecurityException: Permission Denial: runListPackages from pm command asks to run as user 999 but is calling from uid u0a326; this requires android.permission.INTERACT_ACROSS_USERS_FULL` |
| app uid | `pm list packages --user 0` / `cmd package list packages --user 0` | 权限那关过了, 但**只看见它自己** |
| app uid | `su -c "pm list packages -3"` | **148 个** ✓ (KernelSU 已经给过授权) |
| **shell uid 2000** (Shizuku 那条路的身份) | `pm list packages -3` | **148 个** ✓ |
| 特权侧 | `pm list packages --user 999` | **2 个**: `tv.danmaku.bili` / `me.iacn.biliroaming` |
| 特权侧 | `cmd package query-activities --brief -a MAIN -c LAUNCHER` | 能跑, 但吐 **445 行** resolver dump (`--brief` 在这个子命令上不生效), 不好用 |

- **权限那堵**: `pm` / `cmd package` 都是 shell 的前端, 不带 `--user` 时会问跨 user (这台机器上是 XSpace 那个 999), 而 `INTERACT_ACROSS_USERS_FULL` 是 **signature 权限, 普通应用拿不到, `pm grant` 也给不了**
- **可见性那堵**: 显式带 `--user 0` 就绕开第一条, 但 Android 11 起应用默认只看得见自己 —— 那时 manifest 的 `<queries>` 里**只声明了 Shizuku**

**为什么不就让它 `su`**: 那句 `su -c` 在**只有 Shizuku、没有 root** 的设备上直接没有, 而通道在 shell 身份下照样列得出来; 而且它只给包名, 模型还得自己去拼 `resolve-activity`

### 做法: app 侧与特权侧各出一半

| 那一半 | 在哪 | 给什么 |
| :-- | :-- | :-- |
| **能启动什么** | app 侧 `channel/LwApps.kt` (`queryIntentActivities(MAIN+LAUNCHER)`) | 包名 + **名字** (手机语言里那个) + 启动组件 |
| **可见性** | `AndroidManifest.xml` 的 `<queries><intent>` (MAIN/LAUNCHER) | **窄声明, 不申请 `QUERY_ALL_PACKAGES`** —— "人能启动的东西"正好就是它 |
| **某个 user 装了哪些** | 特权侧 `LwLaunch.packages(userId)` → `pm list packages --user N` | 双开那份只有这里看得见 |

- 名单**不带 `-3`**: 设置 / 相机 / 时钟都是系统应用, 而模型最常要开的正是它们; 该留下哪些由 app 侧那份"有 launcher 图标"的名单决定
- 桥 (`apps`) 把两半拼起来: **同一个 user** 直接用 app 侧那份 (app 跑在哪个 user, 那份就是哪个 user 的), **别的 user** 用特权侧那份去**过滤**它 —— 名字跨 user 复用是安全的, 双开那份是同一个 APK
- 协议: `PACKAGES = FIRST_CALL_TRANSACTION + 19`, `VERSION` 从 `11` 抬到 `12`
- 工具: `lw_apps(user?, query?)` (query 按名字或包名子串过滤, 所以一个名字只要一次调用), 而 **`lw_launch` 的 `package` 从这天起也认名字**: 精确包名 → 精确名字 → 包含, 一个都对不上就按原样当包名交下去 (有 launcher 图标之外的包名也能用), 对上不止一个就**什么都不起**并把候选报回来

### 验证 (设备上)

| 调用 | 结果 |
| :-- | :-- |
| 桥 `apps` (无参) | `user=0 launchable=148 matched=148 error=''` |
| 桥 `apps {"query":"设置"}` | **1 个**: 设置 - `com.android.settings` |
| 桥 `apps {"query":"bili"}` | **3 个**: 哔哩哔哩 / 哔哩漫游 / 明日方舟 (b 服) —— query 是子串过滤 |
| 桥 `apps {"user":999}` | **2 个**: 哔哩哔哩 - `tv.danmaku.bili`, 哔哩漫游 - `me.iacn.biliroaming`, **名字是从 user 0 那份继承的** |
| 桥 `launch {"package":"设置"}` (虚拟屏 104) | `package=com.android.settings asked=设置 started=true`, 起来的是 `com.android.settings/.MainSettings` |
| 工具 `lw_launch {"package":"哔哩哔哩"}` (虚拟屏 105) | `launched tv.danmaku.bili (from "哔哩哔哩") on displayId 105 "apps-probe"` |
| 工具 `lw_launch {"package":"哔哩"}` | `THREW: "哔哩" reaches 2 apps, so nothing was started: 哔哩哔哩 (tv.danmaku.bili), 哔哩漫游 (me.iacn.biliroaming) - name the one you mean by package` |
| 工具 `lw_apps {"query":"不存在的应用"}` | `no app for user 0 matches … (of 148 that can be started)` |

那两块探针屏 (104/105) 用完就 `lw_screen_release` 了

### 已知边界 (没做, 也不打算做)

- 名字只在**有 launcher 图标**的应用里找; 找不到时 `lw_launch` 按包名原样交下去, 所以直接给包名这条路一直通
- 双开那份要是**原版没装** (只有 clone 有), 桥里那次过滤会把它漏掉 —— 双开的语义本来就是"原版还在", 所以没为它再找第二套名字来源
- 反方向不会漏: app 侧那 148 个就是"能启动"的全部, 特权侧只回答"这个 user 有没有"

### 顺带改了 `lw_screenshot` 的描述

截图会被两条预算压小 (1080x2400 可能只有 536x1192, 内容多时更小), 所以描述里现在写明: **要读屏就用 `lw_ui` (无障碍树, 文字与位置都是精确的) 或 `lw_ocr` (在屏幕自己的像素上跑, 不缩放)**, 截图是"像人一样看一眼"用的


## 补记 (2026-09-23): `lw_swipe` 在游戏里被当成了一次点击

症状是用户报的: **点击与返回键在游戏里都正常** (好友 / 干员 / 终端都点得进去), **同一套 swipe 在系统「设置」列表上也能滚动**, 但在游戏 (明日方舟 / Unity) 里拖拽毫无反应 —— 所以一开始怀疑是引擎丢了注入的 MOVE

### 量出来的差别

同一块虚拟屏 (2400x1080, 明日方舟停在干员列表, 列表在最左端), 同一段手势 (1800,540 → 600,540, 300 ms), 前后各拍一张 `/sdcard/DSH/screenshots/screen-106.png`, 用本机的 `build/pngdiff.mjs` (没进 git, 拿 `zlib` 自己解 PNG) 对比:

| 谁发的这段手势 | 画面变化 |
| :-- | :-- |
| **我们的 `lw_swipe`** | **0.407%** 像素 (bbox 是一条 `[523,165,1915,495]` 的细带, 只有按下反馈) |
| **平台自己的 `adb shell input -d 106 swipe …`** | **82.372%** 像素 (bbox 是整屏, 列表整块动了) |

所以引擎没丢注入的事件 —— 是我们发出去的那串东西在它眼里不是一个手势。反方向 (600,540 → 1800,540) 那次只有 0.219%, 但那个方向本来就只能把已在最左的列表往回推, **是假阴性**, 不能拿它当判据

### 原因: 等一等的那句写在刹车里了

`LwInput.swipe` 原来是:

```kotlin
for (step in 1..SWIPE_STEPS) {
    if (brake && interrupted(stepMs)) { ... }   // <- 只有 brake=true 才会等
    ...
    touch(displayId, LwServiceProtocol.TOUCH_MOVE, x, y)
}
```

而 **`brake` 只对主屏为真** (虚拟屏不设刹车, 见第 6 步)。于是虚拟屏上 DOWN + 12 个 MOVE + UP 全在 **1 毫秒**内发出, `eventTime` 几乎相同 —— **`durationMs` 完全没生效** (拖 300 ms 与拖 2 s 发出去的东西一模一样)。分发器把这一串同毫秒的 MOVE 合并成一次跳, 而 Unity 按帧读输入: 那一帧里它只看到按下又抬起, **就是一个点击**

Android 自己的滚动视图 (设置页、Compose 列表) 只取最终位置, 所以这个坑在系统界面上一直藏着 —— 用户"设置页能滚"的观察是对的, 只是它证明不了这条手势是好的

### 修法

**每一步都等, 两条路一样**: `brake` 那条仍然走 `interrupted(stepMs)` (切片等 + 盯真手指, 行为不变), 非 `brake` 那条 `SystemClock.sleep(stepMs)`。另外 `MIN_STEP_MS` 从 **4 ms 提到 16 ms (一帧)**: 比一帧还短的步长会落进上一帧, 应用看不到"手指在动"

平台自己的 `input swipe` 做的是同一件事 (`Input.sendSwipe` 每步 `sleep(16)`, 而且每条事件用 `INJECT_INPUT_EVENT_MODE_WAIT_FOR_FINISH` 等它落地) —— 这就是它能拖而我们不能的原因。两条路都按 `durationMs / 12` 走, 低于一帧的步长按一帧算

### 这次的验证边界 (要说清)

用户说"装好就行, 不用再测了", 所以**修完只装了 APK, 没有在游戏里复测**。要复测: 建一块 2400x1080 的屏 → `lw_launch` 明日方舟到能拖的界面 (干员列表) → `lw_swipe` **往左**拖 → 前后两张 `/sdcard/DSH/screenshots/screen-<id>.png` 用 `node build/pngdiff.mjs` 对比。**修好的话是几十个百分点, 不是零点几个**




