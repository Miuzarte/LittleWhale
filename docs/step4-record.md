# 第 4 步记录: 自建虚拟屏 (2026-09-21 真机验过)

## 这一步要什么

一张系统级的第二块屏 + 一个能看能摸的预览 + 能拍下来 + 能注进去, 落到 AGENTS.md 那个布局 (预览在上, WebView 在下), 判据是**出画面 + 能控制**

不做的事: 不跑 `scrcpy-server`, 不用它的控制协议 (消费者在本进程, 理由见 AGENTS.md「scrcpy 的取舍」)

## 链路

```
菜单「虚拟屏」→ VirtualScreen.start()          (app 进程)
  → PrivilegedChannel.ensure()                (root 通道, 冷启动约 0.75s)
  → DISPLAY_CREATE(width, height, dpi, surface)
      → LwVirtualDisplay: DisplayManager(context).createVirtualDisplay(...)
          → system_server: DisplayManagerService
  ← displayId
SurfaceView 的 surface → DISPLAY_SURFACE → VirtualDisplay.setSurface(surface)
      → SurfaceFlinger 把这块屏合成进那个 surface (就是预览, 零拷贝)
预览上的手指 → INPUT_TOUCH(down/move/up) → MotionEvent + setDisplayId + injectInputEvent
截图 → SCREENSHOT(path) → screencap -d <compositor id> -p <path>
```

## 「零 native」验成了

这是开工前最不确定的一件事, 结论是**成立**: 屏的输出 surface 就是 `SurfaceView` 的 surface, 系统把画面直接合进那块 buffer, 我们既不编码也不解码、不写一行 native、不开 socket

三条证据:

1. 预览里出现的是**缩小的真实设置页** (截图见下), 不是镜像、不是录屏回放
2. 全程没有 `MediaCodec` / `MediaProjection` / `EGL`, 代码里也没有 native 调用, 图片路径只有这一条
3. `dumpsys display` 里那块屏的 `type VIRTUAL`、`owner io.github.miuzarte.littlewhale (uid 0)`, 而画面确实出现在 app 的窗口里

代价是**它跟着窗口走**: 退出 app / 锁屏后 `SurfaceView` 的 surface 会销毁, 那时屏就成了"没有输出面的屏"。所以 detach 是一等状态 (见下), 而截图**不依赖预览** (走 `screencap`), 无头也能拍

## 屏怎么造

在**特权进程**里造, 不在 app 里, 两个理由:

- 让它成为一块能放 activity 的屏, 靠的是 `TRUSTED` / `OWN_FOCUS` 这些 flag, 而它们要 `CAPTURE_VIDEO_OUTPUT` (app 没有, shell 有, root 有)
- 以后谁能往这块屏上放东西、谁能在上面注事件, 是拿**造屏的 uid** 去判的

用的还是公开 API: `android.hardware.display.DisplayManager` 的隐藏构造函数 + `createVirtualDisplay(name, w, h, dpi, surface, flags)` (MAA-Meow 的 `createNewVirtualDisplay` 就是这个形状)

flag 表 (公开 SDK 只给了前两个, 其余是平台自己的位):

| 位 | 名字 | 为什么 |
| :-- | :-- | :-- |
| 0 | `PUBLIC` | 别的进程也能用这块屏 |
| 3 | `OWN_CONTENT_ONLY` | 不镜像别的屏的内容 |
| 6 | `SUPPORTS_TOUCH` | 接受触摸, 注入的事件也要它 |
| 8 | `DESTROY_CONTENT_ON_REMOVAL` | 屏没了内容跟着走, 不放任泄漏 |
| 10 | `TRUSTED` | 允许系统在这块屏上放可信的 (可获焦) 窗口 |
| 11 | `OWN_DISPLAY_GROUP` | 自己的 display group, 因此**不会镜像手机主屏** |
| 12 | `ALWAYS_UNLOCKED` | 独立屏没有自己的锁 |
| 13 | `TOUCH_FEEDBACK_DISABLED` | 不画触摸反馈圆点 |
| 14 | `OWN_FOCUS` | 能自己持有焦点, 又不抢手机的 |
| 15 | `DEVICE_DISPLAY_GROUP` | 34+ 一起给上 (见下, 单给会有系统警告) |
| 16 | `STEAL_TOP_FOCUS_DISABLED` | 不抢顶层焦点 |

实测落下来的 `DisplayDeviceInfo`:

```
DisplayDeviceInfo{"LittleWhale": uniqueId="virtual:io.github.miuzarte.littlewhale,0,LittleWhale,6",
  1080 x 2400, density 450, touch VIRTUAL, rotation 0, type VIRTUAL,
  owner io.github.miuzarte.littlewhale (uid 0),
  FLAG_SECURE, FLAG_OWN_CONTENT_ONLY, FLAG_DESTROY_CONTENT_ON_REMOVAL, FLAG_TRUSTED,
  FLAG_OWN_DISPLAY_GROUP, FLAG_ALWAYS_UNLOCKED, FLAG_TOUCH_FEEDBACK_DISABLED,
  FLAG_OWN_FOCUS, FLAG_STEAL_TOP_FOCUS_DISABLED, canHostTasks false}
```

两点值得记下来:

- **`ownerPackageName` 是我们的包名, `ownerUid` 是 0** app_process 起的进程没有 IApplicationThread, 系统按 uid 认人, 所以"这块屏是我的、但 uid 是 root"
- **`canHostTasks false`, 但 activity 照样能在上面起** `am start --display 12` 起了设置页并正常渲染、正常收事件, 所以这个字段不是"能不能放 activity"的开关 (至少在小米这台上是)

`DEVICE_DISPLAY_GROUP` 单独给会有系统警告, 无害:

```
DisplayManagerService: Display created with VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP set,
  but no virtual device. The display will not be added to a device display group.
```

## 预览

`ui/VirtualScreenPreview.kt`: 外层黑底 `Box`, 内层 `SurfaceView` 按屏的宽高比自己撑高 (`fillMaxHeight().aspectRatio(w/h)`)

**为什么必须按宽高比**: 合成器把整块屏塞进给它的那个 buffer, surface 的宽高比和屏不一致就是拉伸 (安卓那些录屏预览教程里最常见的糊法)。黑边是 `Box` 的底色, 所以"居中 + letterbox"是免费的

`SurfaceHolder.Callback` 三个回调都接到门面上:

| 回调 | 动作 |
| :-- | :-- |
| `surfaceCreated` / `surfaceChanged` | `VirtualScreen.attach(holder.surface)` |
| `surfaceDestroyed` | `VirtualScreen.attach(null)` |

`attach(null)` 之后屏**继续活着**, 只是没有输出面, 这正是"用户把预览收起来 / app 退到后台, 模型还在操作"要的语义

## 触摸

两层, 都在特权进程里做, 因为 `INJECT_EVENTS` app 也没有:

1. **按屏的坐标** (`LwInput`): `MotionEvent.obtain(downTime, eventTime, action, x, y, 0)` → `setSource(SOURCE_TOUCHSCREEN)` → `InputEvent.setDisplayId(displayId)` (反射) → `InputManager.injectInputEvent(event, MODE_ASYNC)`
2. **按手指的坐标** (`VirtualScreenPreview`): 手势层贴在 `SurfaceView` 上, 一次 `awaitEachGesture` 覆盖 down/move/up, 坐标 `* (屏宽 / view 宽)`

两个设计点:

- **用 `MODE_ASYNC`** 等结果会把线程卡到被操作的应用答复为止, 那是手势最赔不起的东西
- **触摸走一条单线程队列** (`VirtualScreen.touches`): 手指产生事件比跨进程快, 直接同步注入会让 move 超过它所属的 down, 平台就会把它当孤儿丢掉; 排队之后顺序天然是对的, 而且调用方 (主线程) 只是入队, 不等

`tap` 是 down + 40ms + up 的原子操作, 给没有手势的调用方 (第 5 步的工具) 用; 拖动是 down/move*/up 的流, 每帧一次跨进程

## 截图

**走 `screencap`, 不走 PixelCopy** 判据是: PixelCopy 只能拍 `SurfaceView` 上的内容, 所以预览一收起来 (或 app 在后台) 就拍不了, 而屏是给模型看的, 那时候往往正是它在后台干活

`screencap -d <id>` 里的 id **不是逻辑 displayId**:

```
screencap -d 9 ...
Failed to take take screenshot. Display Id '9' is not valid.
```

它要的是 compositor 的那串 64 位 id, 只能从 `dumpsys SurfaceFlinger --display-id` 里按**名字**找:

```
Display 4630947006070067843 (HWC display 0): port=131 pnpId=QCM displayName=""
Display 11529215048785374281 (Virtual display): displayName="LittleWhale"
```

名字是我们造屏时给的 (`LittleWhale`), 所以这条对应关系是我们自己控制住的; 找到后缓存, 拍失败就丢掉重新找 (屏重建会换 id)

## 界面

顶栏从"一排文字按钮"改成**一个 ⋮ 菜单** (`OverlayIconDropdownMenu` + `MiuixIcons.More`), 四项: 虚拟屏 开/关, 展开/收起画面, 工作区, 网络。理由是三个动作已经把标题挤成 `Litt...`, 而这一步还要再加两个

布局 (相对 AGENTS.md 那张草图有一处改动):

```
Scaffold
├── SmallTopAppBar (标题 + ⋮)
└── Column
    ├── VirtualScreenPreview   高 = 窗口的 38%, 黑底, 里面按屏的宽高比撑高
    └── WebView                weight(1f)
```

改动点: 预览放在 **TopAppBar 下面**而不是上面。草图把预览画在最顶, 但那样它会顶到状态栏里 (edge-to-edge 的 inset 是 Scaffold 的 topBar 在处理), 而这一步真正要保的是"预览在上、会话在下"和"不挡住会话" —— 后者靠 Column 里 WebView 的 `weight(1f)` 自动满足, 比往页面注入 CSS 干净 (WebView 看不见外部 overlay, 但看得见自己变小)

`VirtualScreen.current` 为空但正在申请 / 申请失败时, 预览位置会让给一行状态文字, 只有这时候用户才知道"点了没反应"是在等还是失败了

## 文件

| 文件 | 干什么 |
| :-- | :-- |
| `channel/LwVirtualDisplay.kt` | 特权侧: 造屏 / 换 surface / 释放, flag 表 |
| `channel/LwInput.kt` | 特权侧: down/move/up + tap, 反射注事件 |
| `channel/LwCapture.kt` | 特权侧: `screencap` + compositor id 解析 |
| `channel/VirtualScreen.kt` | app 侧门面: 状态 (Compose state)、触摸队列、截图 |
| `ui/VirtualScreenPreview.kt` | 预览 + 手势层 |
| `ui/HostScreen.kt` | ⋮ 菜单、布局、状态行 |

协议 (`LwServiceProtocol`, `VERSION` 从 `1` 升到 `2`) 新增 6 个事务:

`DISPLAY_CREATE` / `DISPLAY_SURFACE` / `DISPLAY_RELEASE` / `INPUT_TAP` / `INPUT_TOUCH` / `SCREENSHOT`

`INPUT_TOUCH` 的动作是 `0` down / `1` move / `2` up。`answering` 现在会把服务侧的异常写回 reply (`reply.setDataPosition(0); reply.writeException(e)`, 就是 AIDL 生成代码里那段), 所以"设备拒绝了什么"能带着原因回到 app

给 host 的桥 (`PrivilegedBridge`) 多了两个方法: `screen` (状态) 与 `screenshot` (落一个 PNG 并回答路径), 这是第 5 步工具要用的形状

## 踩的坑

1. **`dumpsys` 少写一个参数**: 写成 `dumpsys --display-id`, 出来的是 usage 文本, 于是永远"找不到那块屏"。日志里带上原始输出才一眼看出来
2. **compositor id 是无符号 64 位**, 虚拟屏的 id 高位全是 1: `"11529215048785374281".toLongOrNull()` 直接 `null` (超 `Long.MAX_VALUE`)。修法是**全程当数字串传**, 不进整数
3. **`Parcel.writeException` 收的是 `Exception` 不是 `Throwable`**, 写成 `Throwable` 编译期就报 `ARGUMENT_TYPE_MISMATCH`
4. **预览分支在面板打开时不渲染** (`return@Scaffold` 在 `Column` 之前), 所以造屏那一刻 surface 是 `null`, 40ms 后才 attach —— 这顺带把 detach/attach 这条路径也验了
5. **`SurfaceView` 的 `Surface` 每次回调不一定是同一个对象**, 所以别用 `===` 去重, 直接照发 (一次预览生命周期也就两三次事务)
6. **重装 app 会把旧屏带走**: 特权进程 `linkToDeath` 到 app 的 lifecycle binder, 死时 `LwStarter` → `destroy()` → `display.release()`。实测重装后 `dumpsys display` 从 2 块回到 1 块, 没有留下僵尸屏
7. `input -d <id>` (adb 侧) 用的**是逻辑 displayId**, `screencap -d` 用的是 compositor id, 两个不是一回事 —— 前者被用来做过一次预验证 (确认注事件这条路在安卓 16 上对虚拟屏成立), 后者才是代码里的路

## 实测数字

| 项 | 值 |
| :-- | :-- |
| 点菜单 → 屏就绪 | ≈ 0.8s (其中 ≈ 0.75s 是特权进程冷启动, 造屏本身 ≈ 15ms) |
| 造屏回来的 displayId | 8 → 9 → 10 → 11 → 12 (每次重建都换, 不回收) |
| 点屏到 surface attach | 50ms 内 |
| 一次 tap / 一帧 move | 一次 binder 往返 (队列串行) |
| 截图 | 1080x2400 PNG, 空屏 15,580 B, 设置页 176,582 B |
| 预览盒子 (竖屏屏) | 高 = 0.75 * 宽, 1080 宽的窗口里是 1080x810 (实测黑盒 267..1076 行) |
| 预览 buffer | 屏的尺寸 (1080x2400), 约 10.4 MB / 块, BLAST 三缓冲约 31 MB |
| 内存 | 没量 (AGENTS.md 里那条待定问题还在) |

验收动作 (都在小米 13 上做过): 菜单里起屏 → `am start --display 12` 把设置页放上去 → 预览里看到缩小版的设置页 → 在预览上点一下 (设置页跳进了「锁屏」) → 在预览上拖一下 (列表滚动) → 桥的 `screenshot` 得到 176 KB 的 PNG

## 第二轮 (2026-09-21 晚: 预览修好、多屏、菜单与设置页)

第一轮的预览其实是**坏的**, 只是当时没看出来: 画面**被裁到只剩左上角**, 也就是把 1080x2400 的屏 1:1 画进一个 387x858 的 buffer。判据是像素级的: 设置页的搜索框占位文字正好断在 387px 处, 账号那行的 "账号、云" 也正好断在那里, 垂直方向则停在 858px 那一行的位置

**根因**: 合成器把屏画进给它的那个 buffer, **不缩放**; 而 `SurfaceView` 的 buffer 默认等于视图大小, 于是比屏小就是裁切

**修法**: `holder.setFixedSize(屏宽, 屏高)` 让 buffer 等于屏的尺寸, 画进去就是 1:1, 再由合成器把这个 buffer 缩放进视图 —— 这正是 surface 本来的职责。改完预览里出现的是**完整的**设置页

代价记一下: buffer 变成 1080x2400x4 ≈ 10.4 MB, BLAST 三缓冲约 31 MB, 换来的是零 native 且不裁切

**预览高度规则** (按用户要求): 盒子高度 = `min(宽度 * 0.75, 宽度 * 屏高 / 屏宽)`

| 屏 | 盒子 | 画面 |
| :-- | :-- | :-- |
| 竖屏 1080x2400 (比 0.45) | 宽 1080, 高 **810** (= 0.75 * 1080, 即 4:3) | 按 0.45 撑高 → 364x810, 左右留黑边 |
| 横屏 (比如 2400x1080) | 宽 1080, 高 1080 * 0.45 = 486 | 撑满盒子 |

实测像素: 竖屏那块屏的黑盒正好是 **267..1076 行 = 810 行**

**多虚拟屏**: 一块屏一个 displayId, 协议里 `DISPLAY_SURFACE` / `DISPLAY_RELEASE` 都带 id, `DISPLAY_CREATE` 每次**新建**一块 (不再替换)。菜单里是单选: 选中哪块看哪块, 再点同一块就是取消选中 (屏继续跑, 只是不看了)

两个只有多屏才会暴露的坑:

1. **屏名必须唯一** 截图靠"屏名 → compositor id"这条对应关系找人, 而 `createVirtualDisplay` 的名字就是 `dumpsys SurfaceFlinger --display-id` 里的 `displayName`, 所以屏是 `LittleWhale 1` / `LittleWhale 2` 这样编号的
2. **换屏必须换 SurfaceView** 复用同一个 surface 时, 旧 buffer 里还留着上一块屏的最后一帧, 而**空屏不会产生新帧把它顶掉**, 于是切到一块空屏后画面还是上一块屏的 —— 用 `key(displayId)` 让每块屏有自己的 SurfaceView 才对

**顶栏菜单**: 从"一排文字"改成 `OverlayIconCascadingDropdownMenu`, 第一层两项 —— `虚拟屏` (自己带当前选中的是哪块) 与 `设置`; `虚拟屏` 的第二层是: 展开/收起画面, 屏列表 (带 √ 与尺寸), 关闭这块虚拟屏 (新建那一项见下一轮, 后来删了)

**设置页**: 用 `miuix-nav` 的 `NavDisplay` + `rememberNavBackStack` 做导航 (Home / Settings 两页, `NavKey` 必须 `@Serializable`, 否则启动就崩), 页面本身照 SFA 的主题设置页搬:

| 段 | 内容 |
| :-- | :-- |
| 外观 | 跟随系统/浅色/深色 (TabRow), 动态取色 + 取色 + 调色板风格 + 色彩规范, 圆角方屏, 模糊 (无/高斯/渐进) |
| 导航 | 过渡样式 (Miuix 默认/无), 侧滑返回 |
| 工作区 | 原来那个面板平铺进来: 路径、类型、授予所有文件访问、重启 host |
| 网络 | 原来是面板: 开放给局域网、LAN URL、复制 URL、重启 host |

默认值照抄 SFA: 跟随系统、不开动态取色、圆角方屏开、模糊无、过渡 Miuix 默认、侧滑返回开。改完立刻生效 (主题在导航外面那一层), 存在 SharedPreferences 里, 重启还在 (实测切深色 → 杀进程重启仍是深色)

**顺手升的**: Miuix `0.9.4-rc01` → `0.9.4` (2026-09-20 发的正式版), 没有需要改代码的地方

## 第三轮 (2026-09-21 夜: 建屏交给工具, 由工具起名)

用户定的: **界面不提供"新建虚拟屏"** —— 一块屏叫什么、多大, 是造它的那一方决定的, 而人要给自定义分辨率还得在 UI 上打字, 不值当。界面留着**看** (二级菜单里单选, 带 √ 与尺寸) 和**关** (关闭这块虚拟屏)

两条能力:

- `VirtualScreen.create(name, width, height, dpi)`: 名字留空才退回自动编号 (`虚拟屏 n`), **重名自动补序号** (`微信` → `微信 2`); 尺寸给 0 就是本机 (1080x2400@450), 所以工具能造一块 1280x720@240 的横屏屏
- 桥多了 `create` (可选 `name` / `width` / `height` / `dpi`) 与 `release` (**必须给 `displayId`**)

`release` **不接受"不给 id 就关选中的那块"**: 选中哪块是用户随时能改的 (二级菜单里点一下就行), 所以不带 id 的调用可能关掉一块它根本没说到的屏 —— 正好是用户刚切过去的那块。宁可让它报错 (`release has to name the displayId it means`), 也不猜

两个细节:

- **`create` 是阻塞的**, 因为它的调用方只有工具 (在桥的工作线程上); 以前那次异步是为了不卡 UI, 现在界面不建屏了
- **屏的显示名与 compositor 名是两回事**: 截图靠"compositor 名 → id"找人, 所以 privileged 侧的名字仍是内部唯一的 `LittleWhale 1` / `2` (工具给的名字里可能有引号, 塞进 `displayName="…"` 会把解析弄坏), 工具给的名字只用作界面与工具之间的称呼

实测 (经桥调用): 连建四块 —— `微信` → 标签 `微信`; 再来一块 `微信` → `微信 2`; 不带名字 → `虚拟屏 3`; `横屏 720p` 1280x720@240 → 标签原样。菜单里四项都列出来, 横屏那块选中时预览盒子按 720/1280 收成约 608px 高。`release` 按 id 关掉 `微信`, 关掉选中的 `横屏 720p`, 列表剩两块且 `selected` 变 `-1`; 不带 id 则报错、id 不存在则 `released:false` 并说明是哪一块

## 补记: 屏可以被改成别的形状 (`resize` / `rotate`, 2026-09-22 真机验过)

第三轮那句「屏固定 1080x2400@450, 不跟随手机旋转」里的**固定尺寸**不再成立 (不跟随手机旋转仍然成立, 那是主屏的事): 虚拟屏现在可以随时换尺寸, 因为一块屏的**尺寸就是应用拿到的那份配置** —— 一个只会横屏的游戏要的是一块横屏的屏, 光把画面转 90 度是转不出横屏布局的

链路只多一格: `VirtualScreen.resize(screen, width, height, dpi, swap)` → `DISPLAY_RESIZE` → `LwVirtualDisplay.resize` → `VirtualDisplay.resize(w, h, dpi)`。app 侧的 `ScreenState` 同时换掉 (它才是 `lw_screen` 报出去、坐标空间依据的那个尺寸), 预览那边把 `setFixedSize` 重新走一遍 —— **buffer 必须跟着换**, 否则就是老尺寸那块 buffer 挨裁

两个语义:

- **`swap` 就是"转四分之一圈"**: 屏只有形状、没有自己的朝向, 所以"顺时针 / 逆时针"是同一件事, 工具那侧做成 `lw_screen_rotate` 这个便捷别名 (宽高对调, dpi 不动)
- **尺寸有上下限** (`64..8192`): 这是防打错的闸, 不是关于屏幕的论断; 一个 5 像素的屏谁也看不见, 而一块编出来的尺寸要设备真去分配 buffer

### 两个真机测量出来的事实

**一、换尺寸之后再拍图, 拍到的是换之前那一帧。** `resize` 是异步落地的: 返回时 DisplayInfo 已经是新尺寸 (`dumpsys display` 里 viewport / `DisplayDeviceInfo` / `DisplayInfo` 三处全是 2400x1080), 但屏上还没有新尺寸的帧, `screencap` 交的还是上一帧 —— **不带任何提示**, 于是工具会报 "screen 2400x1080, picture 536x1192, 乘 2.01", 模型拿到的却是一张竖屏图, 坐标全错

修法在 app 侧 (`VirtualScreen.captureSettled`): 拍完读一次 PNG 的宽高 (`Picture.sizeOf`), 与屏现在的尺寸不符就等 120ms 再拍, 最多 5 次。实测第二次就是对的 (第一次 23:45:52.293 交竖图, 第二次 23:45:52.706 交横图, 相差 413ms), 而尺寸本来就对时只多一次 `inJustDecodeBounds` 的读头。`lw_screenshot` 与 OCR 那条 `capture()` 走的是同一个助手

**二、应用不会因为屏换了形状就重新排版 —— 它按自己声明的方向走。** 同一块 2400x1080 的屏上:

| 应用 | 结果 |
| :-- | :-- |
| `com.microsoft.emmx` (Edge, 不锁方向) | 铺满整块横屏, 工具栏横过来, 重新排版 |
| `com.android.settings` (设置, 声明竖屏) | **竖屏那一版原样留着, 居中一条, 左右两条黑边** |

所以"建屏时先把形状定对"对**锁方向**的应用 (多数系统应用与不少游戏) 是真要紧的: 形状不对它不是重排, 而是留一条带子在中间。`lw_screen_resize` / `lw_screen_rotate` / `lw_launch` 的描述里说的都是这一句 (先说"跟着屏走的会重排并铺满", 再说"锁方向的会留一条带子"), 没写"换尺寸就会重排"

## 故意没做

- **应用自己往屏上放东西** 现在放内容是 `am start --display <id>`, 由特权侧执行 (root); 哪块屏放哪个应用是第 5 步工具的事, 也顺手决定要不要给 UI 做一个应用选择器
- **录屏 / 音频 / 旋转同步** 屏固定 1080x2400@450, 不跟随手机旋转
- **前台工作暂停** (第 6 步): 现在**没有任何刹车**, 注进去的事件直接生效, 这是审批全放行那条决定的配套前提, 别拖太久
- **PixelCopy** 见上, 截图走 `screencap`
- **平铺模式 / 多屏同看** 一次只看一块屏, 第二层的下拉菜单也没有把"每块屏一个尺寸"做成可选项 (想要别的分辨率得改 `VirtualScreen.create`)

## 下一步 (第 5 步)

把这些做成 dsh 原生工具交给模型, 走已经开好的桥 (`screen` / `screenshot` 已在, 还差 `tap` / `swipe` / `launch`):

- 工具面最小集: `lw_screen` (状态) / `lw_screenshot` (图) / `lw_tap` / `lw_swipe` / `lw_launch`
- 截图要能被模型"看到": 现在落在 `cacheDir/screen.png`, 工具得把它当成图片附件交回去 (dsh 的 `present` / attachment 那条路, 第 2 步记过它的接口面)
- 坐标别让模型猜: 第 7 步的无障碍映射才是正解, 在那之前截图 + 提示里给上分辨率
