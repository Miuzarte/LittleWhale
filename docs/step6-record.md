# 第 6 步记录: 主屏控制与触摸刹车 (2026-09-22 真机验过)

这一篇既是设计也是记录, 原来的设计单 `docs/step6-plan.md` 已经并进来

## 做了什么

两件事, 一件是能力, 一件是配它的刹车:

- **lw 工具能指主屏**: `displayId 0` 就是手机自己那块屏, 看 (`lw_screenshot` / `lw_ui`) 与动 (`lw_tap` / `lw_swipe` / `lw_tap(text=…)` / `lw_launch`) 都认它, 和虚拟屏走同一套调用
- **真手指的刹车**: 人在用手机的时候, 指主屏的动手调用一律拒掉, 正在拖的**当场停下并抬手**, 工具回一句点名"用户在用手机"的错

只管主屏: 虚拟屏那两道闸 (菜单里的「暂停接受控制」与「虚拟屏触摸控制」) 一个字没改, 人在手机上摸的时候模型照样可以画 display 69

## 地基: 注入的事件不进 `/dev/input`

整套东西都建在一条实测过的事实上: **`InputManager.injectInputEvent` 的注入口在 kernel input reader 之后**, 所以模型自己的点击永远不会出现在触摸屏的 evdev 节点上, 那个节点上的每一个事件都是人的手指

真机上验法 (两边同一次调用): 主屏连点几次, `probe` 里 `touch.events` **一直是 0**, `msSinceLastTouch` 一直是 `-1`:

```
--- before ---
{"available":true,"userTouching":false,"down":false,"msSinceLastTouch":-1,"events":0,"path":"/dev/input/event7"}
--- tap at 540,947 ---
{"ok":true,"result":{"tapped":true,"displayId":0,"x":540.0,"y":947.0}}
--- after ---
{"available":true,"userTouching":false,"down":false,"msSinceLastTouch":-1,"events":0,"path":"/dev/input/event7"}
```

## 特权侧: 直读 evdev, 不跑 `getevent`

`channel/LwTouchWatch.kt`, 起一条 `lw-touch` 线程阻塞读 `/dev/input/eventN`:

- **为什么不读 `getevent -lt` 的输出**: 子进程写管道会缓冲, 一行要到缓冲区满才出来 —— 一个晚一秒知道有人摸屏幕的刹车不算刹车。阻塞在 evdev 节点上一次 `read` 不花任何代价, 摸到玻璃才有返回
- **结构按字长走**: `struct input_event` 是 timeval + `u16 type` + `u16 code` + `s32 value`, 64 位进程 24 字节 (字段从 16 起), 32 位 16 字节 (从 8 起), 用 `Process.is64Bit()` 选; 一次 `read` 可能短读, 所以填满一条记录再解析
- **哪些事件算手指**: `EV_KEY BTN_TOUCH` (按下与否), `EV_ABS ABS_MT_TRACKING_ID` (`-1` 是这一槽空了, 别的值是手指), 以及 `ABS_MT_POSITION_X/Y` 与 `ABS_X/Y` (动了); `EV_SYN` 与 `ABS_MT_SLOT` 不算, 它们单独出现说明不了什么
- **起停**: 懒起 (第一次有人问), 节点从 `getevent -p` 的解析结果里挑 (与 `lw_probe` 同一个判据); 读失败就记住原因, 5 s 后才重试; 节点是 `fts` 时实测 `/dev/input/event7`, 量程 `x 0..10799 y 0..23999`
- **不 EVIOCGRAB**: 只是多一个读者, 事件照样进系统

`channel/LwGetevent.kt` 是原来 `LwPrivilegedService.runGetevent` 挪出来的, 现在 `INPUT_DEVICES` 与触摸监视两个调用方共用

## 两个窗口, 两个问题

| 什么时候问 | 窗口 | 在哪 | 为什么是这个数 |
| :-- | :-- | :-- | :-- |
| 动手**之前** | 手指按着, 或 1000 ms 内有触摸 | app 侧 `TouchState.driving()` | 长到"刚抬手的瞬间模型不能抢", 短到"用户往这个 app 的 GUI 里打完字"不算在跟模型抢屏幕 |
| 拖动**当中** | 手指按着, 或 250 ms 内有触摸, 每 8 ms 查一次 | 特权侧 `LwInput.interrupted()` | 拖到一半被打断要的是"立刻", 抬手之后的宽限由 app 侧那一层管 |

拖动的等待被切成 8 ms 一片, 所以中断的延迟不超过一片: 一个 6 s 的拖动是 12 步、每步 500 ms, 整步睡过去就会让模型多拖半秒。一片也不改变步数与总时长, 手感与第 4 步一样

**监视不起来就不许动主屏**: `requireUserNotDriving` 在 `watching == false` 时直接拒 (理由带设备给的原话)。看的不拦, 虚拟屏不受影响。这是自觉的取舍 —— 没有刹车的主屏控制, 在审批全放行的前提下没有别的兜底

## 协议 (VERSION `"5"` → `"6"`)

- **`TOUCH_STATE = +14`**: 4 个 int (`watching` / `down` / `msSinceLastTouch` / `events`) + 2 个 string (`path` / `error`), 一次事务
- **`INPUT_SWIPE` 多一个 `brake` 位** (1 = 有真手指进来就停), **返回值从 `Unit` 变成 `Int`**: `-1` = 走完, 否则 = 停在走了多少毫秒
- **`SCREENSHOT` 对 display 0 走不带 `-d` 的 `screencap`**: 主屏没有我们的屏名可查, 而"不说 display"就是它; 别的屏仍按屏名去 `dumpsys SurfaceFlinger --display-id` 里找 compositor 的 64 位 id
- **`MAIN_DISPLAY = 0`** 落在 `LwServiceProtocol` 里, 因为它是工具面的一部分 (`displayId 0` 是写给模型看的)

## app 侧 (`VirtualScreen` / `PrivilegedBridge`)

- **`mainScreen()` 每次现读** `DisplayManager` 的 `getRealMetrics` (拿不到就退到 `Resources.getSystem()`): 主屏的尺寸跟着旋转变, 而坐标是 caller 在那个空间里量的; 它**不进 `screens` 列表** —— 不是我们建的, 没有预览、没有暂停、没有释放
- **`namedScreen` 先认 0** 再查我们的列表, 于是 `ui` / `tap` / `swipe` / `tapText` / `launch` / `screenshot` 六条路自动都认主屏
- **`release 0` 给专门的拒绝理由** ("是主屏不是虚拟屏, 没什么可释放也不能关"), 而不是走查找得到一句"没有这个 id"
- **刹车点是四个**: `VirtualScreen.tap` / `swipe` (提交前后各问一次, 因为队里可能排了一会儿) 与桥里的 `tapText` / `launch` (无障碍点按与 `am start` 都不经过手势队列)
- **`probe` 与 `screen` 都带上 `touch`** (`available` / `userTouching` / `down` / `msSinceLastTouch` / `events` / `path` / `error`), 形状只写在 `ChannelReport.touch` 一处

## 工具侧 (`host-plugin/index.mjs`)

- `DISPLAY_ID` 的说明开头就讲 `0` 是手机自己的屏, 以及"用户在用的时候指它的调用会被拒、拖动会被截断"
- `lw_screen` 把主屏与虚拟屏并列列出来 (`- displayId 0 "主屏" 1080x2400 at 450dpi, the phone's own screen: always there, no preview, and the person holding the phone is the brake on it`), 末尾一行 `brakeLine` 说刹车现在什么状态 (没起来 / 用户正按着 / 刚放下 / 手机是空闲的)
- 拒绝与截断都是**工具错误**而不是普通结果, 消息里带"停手、说明自己在做什么、问用户", 这是"别再往下做"的信号
- 只读工具不受影响, `lw_ui` / `lw_screenshot` 在用户按着手机时照常返回

## 实测 (2026-09-22, 192.168.1.103, root uid 0, 服务 v6)

| 验的东西 | 结果 |
| :-- | :-- |
| 监视起来了 | `probe`: `available true`, `path /dev/input/event7` ("fts"), `events 0` |
| 主屏进列表 | `screen`: `primary {displayId 0, "主屏", 1080x2400, 450dpi}` |
| 主屏截图 | `screencap -p` 落在 `<工作区>/screenshots/screen-0.png`, 缩放副本 536x1192, scale 2.0149; 拉出来看就是手机当前画面 |
| 主屏读树 | `ui(0)`: package `com.android.settings`, `truncated false` |
| 主屏起应用 | `launch(0, com.android.settings)`: `started true`, `am` 报 `MiuiSettings` `Status: ok` |
| 主屏按坐标 | `tap(0, 540, 947)` → `tapped true`, 屏上没变化 (坐标选在卡片之间的空当) |
| 主屏按名字 | `tapText(0, "WLAN")` → `outcome clicked`, `via ancestor`, 屏上翻到 WLAN 页 |
| 主屏拖动 | `swipe(0, 540,1800 → 540,900, 400ms)` → `swiped true`, 截图确认列表滚了 |
| 注入不进 evdev | 上面这些注入之后 `events` 仍是 0 |
| 手指按着时 | `tap` / `swipe` / `tapText` / `launch` **四个都拒** ("the user is using the phone's own screen (displayId 0): a real finger is on the screen right now…"); `screenshot` 照常成功; `release 0` 回"是主屏不是虚拟屏" |
| 拖到一半被打断 | 6 s 的拖动在第 **2091 ms** 停下: `the drag on the phone's own screen (displayId 0) was cut short after 2091 ms of the 6000 ms asked for…`, 手指停在当时的位置 |
| 虚拟屏不受影响 | 同一个真手指按着的时候, display 70 的 `launch` / `swipe` / `tap` 全部成功 |
| 抬手之后 | `down false`, 计数不再涨, 调用恢复正常 |

## 没有真手指的时候怎么测

**往 evdev 节点写事件就能伪造一只真手指**: `sendevent` 是内核 input core 的注入口, 写进去的事件会送到那个节点的**所有读者**, 所以监视看得见; 而 `injectInputEvent` 走的是上面那条路, 看不见。脚本收在 `tools/lw-fake-touch.sh` (节点与量程从 `lw_probe` 读, 这台设备是 `event7`, `0..10799 x 0..23999`):

```sh
su -c 'sh /data/local/tmp/lw-fake-touch.sh down'   # 手指按住
su -c 'sh /data/local/tmp/lw-fake-touch.sh up'     # 抬手
```

两条要记住的:

- **桥的往返要 3~4 s** (`lw-bridge.ps1` 每次都推一遍 helper、读 host 环境、开 forward), 所以 1000 ms 的宽限**没法用一次 `tap` 来测** —— 上一个动作到下一个动作之间窗口早过了。要验宽限得压在设备侧 (两次调用之间 `sleep`), 或者干脆只验"按住"这一半
- **伪造的手指会被系统当真**: 它一直按着的时候列表在它底下滚动, 抬手时系统按"点在那个位置"处理 —— 实测把手机带到了「系统更新」页。这是测法的副作用, 不是刹车的问题, 但测的时候要挑一个就算被点到也无所谓的界面

## 没做的 (自觉的边界)

- **只有软停**: 手势中止 + 动作被拒 + 工具报错, 回合继续, 由模型收手。硬停 (`exec.agent.cancel({ kind: 'hook', reason })`, API 现成) 没做, 因为软停的失败模式只是"它又试了一次", 而那次还会被拒
- **不自动恢复, 也不粘**: 抬手 1 s 之后模型就能再动手。想更粘 (人一碰就锁住主屏直到用户显式放开) 是另一个决定, 也要一并想清楚用户在哪里放开
- **主屏没有开关**: 虚拟屏有「暂停接受控制」, 主屏没有对应的一行 (它不在菜单里), 刹车就是人的手指本身。要一个开关的话得先定它在哪
- **不给触摸做坐标过滤**: 主屏的判据是"玻璃上有没有真手指", 不去算它落在哪个窗口。副作用是"用户往这个 app 自己的 GUI 里打字"和"用户在跟模型抢屏幕"在监视看来一样, 靠 1 s 的窗口区分
- **不做"给注入事件打标记"那条退路**: 地基 (注入不进 evdev) 验过了, 用不上
