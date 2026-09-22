# 第 3 步记录: Shizuku + root 特权通道

2026-09-20 在小米 13 (`192.168.1.103`) 上做完并验过, 本文放已完成工作的清单、实测数字、踩坑过程, `AGENTS.md` 只留结论和下一步

## 这一步到底做了什么

一句话: **app 进程能拉起一个跑在 root / shell 身份下的进程, 通过 binder 调它, 并把这个能力做成一个 dsh 原生工具交给模型**

链路 (从模型往回看):

```
模型 → dsh 工具 lw_probe (Node host, app uid)
        ↓ registry 里的 loopback TCP, 带一次性 token
      app 进程 (PrivilegedBridge 收请求, PrivilegedChannel 转 binder)
        ↓ binder
      特权进程 (uid 0 或 2000, 由 liblauncher.so 经 app_process 起)
        ↓ 直接读 /dev/input, 跑 getevent -p
```

三条缝按 MAA-Meow 的**形状**接, 代码是自己写的:

| 缝 | 文件 | 干什么 |
| :-- | :-- | :-- |
| `ProcessSpawner` | `channel/ProcessSpawner.kt` | 把 launcher 调用串包成命令并拉起: `SuSpawner` (libsu, 后台化) / `ShizukuSpawner` (`IShizukuService.newProcess`, 前台 exec) |
| `RemoteServiceConnectorBackend` | `channel/RemoteServiceConnectorBackend.kt` | launcher 命令行 + token 等待 + `linkToDeath`, 两个后端只差一个 spawner 和后缀 |
| `RemoteAccessPermissionBackend` | `channel/RemoteAccessPermissionBackend.kt` | 用之前先问能不能用: root 只能「试」, Shizuku 有真的权限要问 |

其余:

- `native/launcher.c` + `native/CMakeLists.txt` → **`liblauncher.so`** (cmake 编出来的可执行文件当 native lib 发, 理由同 Node)
- `channel/LwPrivilegedService.kt` — 特权进程里的 `Binder`, `onTransact` 手写 5 个事务 (**没有 AIDL**, 理由见下)
- `channel/LwPrivilegedProcess.kt` / `LwContext.kt` / `LwServiceStarter.kt` — 反射起 `ActivityThread` + system context, 建服务, 交 binder
- `channel/LwBootstrapProvider.kt` / `LwBootstrapRegistry.kt` — app 侧收 binder 的 ContentProvider, 校验 `callingUid == SHELL_UID || ROOT_UID` + 一次性 token
- `channel/PrivilegedBridge.kt` — 给 host 用的 loopback socket 服务 (JSON 行协议)
- `host-plugin/` — dsh 侧插件 (`lw_probe`), 由 `tools/pack-host.mjs` 拷进 host 树的 `node_modules/littlewhale-channel/`
- `host/PluginOverlay.kt` — 每次启动 host 写一份 `--patch` 覆盖层, 指向上面那个插件

## 真机验证 (2026-09-20)

日志 (tag 各自独立, 一次连接的全部证据):

```
LwBridge  : listening on 127.0.0.1:37617
DshHost   : dsh web: http://127.0.0.1:3080/?token=… (LAN: http://192.168.1.103:3080/?token=…)
LwConnector: starting io.github.miuzarte.littlewhale:lw_root as root
LwLauncher : launcher start: uid=0 process=io.github.miuzarte.littlewhale:lw_root
LwLauncher : exec /system/bin/app_process with CLASSPATH=/data/app/…/base.apk as uid 0
LwProcess  : created io.github.miuzarte.littlewhale.channel.LwPrivilegedService as uid 0
LwConnector: io.github.miuzarte.littlewhale:lw_root is up over root
LwChannel  : root connected
```

探针回答 (从开发机经 `adb forward` 打给 app 的 socket, 请求体就是插件发的那一行):

```json
{"ok":true,"result":{
  "channel":{"connected":true,"backend":"root","uid":0,"pid":2597,"version":"1"},
  "input":{"available":true,"deviceCount":1,
    "touchscreen":{"path":"/dev/input/event7","name":"fts","x":[0,10799],"y":[0,23999],
                   "protocolB":true,"direct":true,"touchKey":true,"primary":true},
    "devices":[…]}}}
```

**模型那一步也真跑了**: 在手机 GUI 里发「调用 lw_probe 工具, 把它返回的内容原样贴出来」, 界面里出现 `工具调用 lw_probe · {}`, 模型回帖里原样贴出

```
privileged channel: root as root (pid 2597, service v1)
input devices: 1
touchscreen: /dev/input/event7 "fts" x=[0,10799] y=[0,23999] protocolB=true direct=true btnTouch=true
```

和完整 JSON (uid 0), 用量 17.5K tok / 3 秒 —— 所以「模型 → 工具 → 特权进程 → root 专属数据 → 模型」整条是通的

触摸量程与面板一致: 1080×2400 的屏, ABS_MT_POSITION_X 0..10799, Y 0..23999

## 踩坑 (按重要性排序)

### 1 ContentResolver.call 从 app_process 起的进程里会被 AMS 拒

第一版握手用 `context.contentResolver.call(uri, …)`, 报:

```
java.lang.SecurityException: Unable to find app for caller
android.app.IApplicationThread$Stub$Proxy@d946dc9 (pid=654) when getting content provider
io.github.miuzarte.littlewhale.lw.bootstrap
  at com.android.server.am.ContentProviderHelper.getContentProviderImpl(ContentProviderHelper.java:199)
```

app_process 起的进程**没有 IApplicationThread**, 在 AMS 眼里不是一个 app 进程, 所以 `getContentProvider` 这条路对它关着 —— 这就是 MAA-Meow 为什么要绕

**正解是 `IActivityManager.getContentProviderExternal`**: 它是为「不是 app 进程的调用方」准备的口子, 返回 `ContentProviderHolder`, 拿里面的 `provider` 直接 `call()`。副作用正好是我们想要的: **调用直达 provider**, 所以 provider 里 `Binder.getCallingUid()` 看到的是特权进程自己的 uid (0 / 2000), 而不是系统的 1000

实现全反射 (没有 hidden-api stub 模块): `ServiceManager.getService("activity")` → `IActivityManager$Stub.asInterface` → `getContentProviderExternal(authority, userId, token, tag)` → `holder.provider` → 按第一个参数类型找 `call(AttributionSource, …)` → `removeContentProviderExternal`。app_process 起的进程**能反射平台的隐藏成员**: 上面 `ActivityThread` 那套反射已经证明这一点 (没用 `setHiddenApiExemptions`)

### 2 KernelSU 会把 `su` 对没授权的 app 藏起来

原来 `RootAccessBackend.isAvailable()` 是查 `/system/bin/su` 存在 —— 在 app 的 uid 下**永远为假**:

```
$ adb shell "run-as io.github.miuzarte.littlewhale sh -c 'ls -l /system/bin/su'"
ls: /system/bin/su: No such file or directory
```

同一个文件在 shell uid 下好好的, 所以这是 root manager 的隐藏行为, 不是文件不在

结论: **「有没有 root」这件事 app 侧没有任何东西可以查**, 只能试。现在的实现是 `isAvailable()` 恒真、`isGranted()` 用 libsu 的 `Shell.isAppGrantedRoot()` (只读缓存不弹框)、`requestPermission()` 用 `Shell.getShell().isRoot` (这一步才会让 root manager 弹框 / 或直接失败)

首次授权需要**用户在 KernelSU 里给 LittleWhale 开一次** (这台设备上 sucompat 没开, 所以 app 自己的尝试不会弹框)。授权之后本轮所有验证都过了

### 3 `--patch` 必须排在 web app 自己的旗标前面

第一版命令行是 `web --no-open --port 3080 … --patch <file>`, dsh 直接:

```
DshHost : error: unknown option '--patch'
```

原因是 `web` 这个子命令带 `passThroughOptions()`: 遇到第一个它不认识的选项之后, **后面全部当成 web app 的参数**传下去, 而 web app 不认识 `--patch`。所以正确顺序是 `web --patch <file> --no-open --port 3080 …` (`apps/cli/src/args.ts` 里 `web` 自己声明了 `--patch`)

### 4 AGP 9.4.0 的资源管线在这个项目里跑不了 (踩得最久)

`processDebugResources` 从 AGP 9.4.0 起会走到 `AarResourcesCompilerTransform`, 它拿到的输入目录里**只有 `jars/classes.jar`, 没有 `AndroidManifest.xml`**, 于是:

```
> Execution failed for AarResourcesCompilerTransform: …\transformed\miuix-preference.
   > …\transformed\miuix-preference\AndroidManifest.xml
```

查过的东西 (都不成立, 记下来省得再查):

- 不是 zip 读不了: 同一个 JDK 里 `ZipInputStream` 能把那个 AAR 的 5 个条目全读出来, `tar -tf` 也对
- 不是 `AarExtractor` 的问题: 直接 new 一个 `AarExtractor().extract(aar, out)` 跑, 输出**完整** (manifest / res 之外的东西 / R.txt / jars 全在)
- 不是名字过滤: 反编译 `ZipEntryUtils.isValidZipEntryName` 后它的判据只有「不含 `:`」「没有 `..` 段」「没有控制字符」, 那 5 个条目全部通过
- 不是变换缓存被污染: `transforms` 与 `build-cache-1` 全删后重跑, 现象一模一样
- 不是某一个新配置项: 去掉 libsu/shizuku 依赖、去掉 281 MB 的 assets 源目录、去掉 cmake/ndk/abiFilters、关掉 aidl, 逐个试都不成立
- 也不是 Miuix 独有: 失败列表里 androidx 的 `ui-geometry` / `runtime-annotation` 等一并在内
- 同一个 `caches/<ver>/transforms/<hash>/transformed/<name>` 目录被 `AarTransform` / `ExtractNavigationXmlTransform` 同时用, 删掉它之后报错会变成「Input file for navigation xml transform must be a directory aar extracted to」—— 链条本身是共享目录, 真正错在哪一层没能从外面看清

**改用 AGP 9.2.1 (MAA-Meow 在用的版本) 后一切正常**, 所以本项目的 AGP 从 9.4.0 退到 9.2.1。SFA 用 9.4.0 没事, 但 SFA 的 Miuix 是源码 composite build, 不吃这些 AAR

顺带的两条:

- **Gradle wrapper 从 9.6.0 升到 9.6.1**: 9.6.0 的 `caches/9.6.0/workerMain` 坏了 (目录里只剩 `workerMain.lock`, 重建时 Windows 不让它删掉自己正持有的锁文件), 于是**任何需要 Worker API 的任务**都失败 (`compileDebugJavaWithJavac` 就是)。删掉整个 `caches/9.6.0` 也没用, 因为新建时同样会删自己不成功; 9.6.1 的这份缓存是好的
- 这也解释了为什么以前没事: **这个 app 之前一行 Java 都没有**, `compileDebugJavaWithJavac` 是 NO-SOURCE, 从来不碰那个 worker 缓存

### 5 顺手把 app 变成纯 Kotlin (没有 AIDL)

AIDL 会生成 Java, 于是 app 就有 Java 要编译 —— 而 Java 编译要 worker、也会把上面那条资源管线拉进图里。与其在这些互相牵连的开关里绕, 不如**手写这 5 个事务**: `LwServiceProtocol.kt` 里一个 `Binder.onTransact` + 一个 `LwServiceProxy`, 走 `Parcel` + 接口 token, 和 AIDL 生成的东西是同一套协议。等于少一个 build feature, 也少一层生成代码

四个原本的 Java 文件 (`LwContext` / `LwPrivilegedProcess` / `LwBootstrapClient` / `LwServiceStarter`) 一并改成 Kotlin, 其中 `LwServiceStarter` 用 `@JvmStatic fun main`, 因为 app_process 是按静态 `main(String[])` 找入口的

### 6 单元测试抓到一个真 bug

`InputDevices` 选触摸屏的排序比较器写反了: `maxWithOrNull` 配 `compareByDescending` 选出来的是**最难看的那个**。设备上只有一个候选所以没暴露, fixture 里放两块屏的测试直接挂了 (7 个测试里 1 个失败)。改成 `compareBy` + `maxWithOrNull` 后 7/7 过

顺带说明: `:app:testDebugUnitTest` 在 AGP 9.4.0 下根本跑不起来 (要过那条坏掉的资源管线), 在 9.2.1 下正常

## 数字与事实

| 项 | 值 |
| :-- | :-- |
| APK (debug) | **195,389,377 字节** (上一轮 195,181,350, +208 KB: launcher + 一个插件包 + libsu/shizuku) |
| `liblauncher.so` | cmake 编出来的可执行文件, `OUTPUT_NAME liblauncher` + `SUFFIX .so`, 与 `libnode.so` 一起被 `useLegacyPackaging` 解到 `nativeLibraryDir` |
| 连接耗时 | root 路线约 1 秒 (日志时间戳 01:26:35.831 → 01:26:36.590) |
| 工具调用 | 模型一轮 2 步, 17.5K tok, 3 秒 |
| 触摸屏 | `/dev/input/event7` `fts`, x 0..10799, y 0..23999, protocol B + INPUT_PROP_DIRECT + BTN_TOUCH |
| Shizuku | 用户用 root 启动后, Shizuku 路线也走到 `launcher start: uid=0`, 说明 spawner 那半是通的 |

## 这一轮故意没做

- **安装引导 / 后端切换 UI / 可用性状态机** —— 按 AGENTS.md 第 3 步的范围砍掉, 等第 5-6 步真需要时再补
- 目前没有 UI 能看到通道状态, 只能看 logcat (`LwChannel` / `LwConnector` / `LwLauncher` / `LwProcess` / `LwService` / `LwBootstrap`) 或者调一次 `lw_probe` —— **2026-09-22 补上了**, 见末尾的补记

## 留给下一步的事

- `lw_probe` 目前只证明通道, 第 5 步要把它换成一族真正的屏幕工具 (点击 / 输入 / 截图)
- 桥只有一个方法表 (`ping` / `status` / `connect` / `probe`), 加屏幕方法时照 `ChannelReport` 的样子扩
- 特权进程里 `destroy()` 现在就是 `killProcess`; 第 4 步造出虚拟屏之后, 释放要排在退出前面
- 自建虚拟屏 (第 4 步) 的第一件事仍是验「预览能不能零 native」

## 补记: 通道状态与「连接」进了设置页 (2026-09-22 真机验过)

第 3 步把状态留给 `lw_probe` 是有代价的, 代价在**第一次装完**那一刻: 应用里没有任何地方说清是 root 没授权还是 Shizuku 没起来, 模型那边只会报"通道不可用"。所以设置页在「无障碍」前面加了一节「特权通道」(`channel/ChannelSetting.kt`), **只列状态, 不写解释** —— 三行字加一个按钮:

```
未连接 (no privileged process)
root: 未检测
shizuku: 已授权
[连接]
```

- **状态行**: `PrivilegedChannel.state()` 的 `已连接: root, uid 0` 或 `未连接 (理由)` —— 理由就是通道自己记的那句 (英文, 它是通道层的话, `lw_probe` 里也是它)
- **两条路各自的档位** (读的是两个后端, 不是猜的): root 三档 —— 已授权 / 未授权 / **未检测**, Shizuku 三档 —— 已授权 / 未授权 / 未运行
- **「连接」按钮** = `PrivilegedChannel.ensure()`: **它是唯一会去试一次的地方**

两个**先写错、后来按用户的话改掉**的地方, 都值得记:

1. **root 的状态只能"试", 不能"查"**。KernelSU 早就把 `su` 这个**检测点**去掉了: 没在它名单上的应用拿到的不是"要不要授权"的提问, 而是"找不到 su" —— 所以"没授权"与"还没试过"在试之前是**同一件事**。第一版把这句含糊过去了 (按过连接才显示已授权, 没说为什么), 现在 `RouteState.granted` 是 `Boolean?`, `null` 就是"未检测" (`RootAccessBackend.rootState()` 直接读 `Shell.isAppGrantedRoot()`, 它在 libsu 跑过 `su` 之前就是 null)。**这也是为什么 root 那行要点一下连接才有答案**, 而不是这一节偷懒
2. **按「连接」不会弹出任何框** (对 root 而言)。第一版写着"KernelSU 与 Shizuku 会把各自的框弹出来", 是错的: 会弹框的只有 Shizuku 那条 (它自己的对话框)。root 的授权只能**在 KernelSU 管理器里手动给一次**

另外一条实现上的: **所有问系统的调用都在自己的工作线程上** —— 第一次连接可能等人点完框 (Shizuku 那条自己就等 60s), 放在主线程上就是 ANR

实测 (小米 13, 装完之后没连过任何东西的状态):

| 时刻 | 界面 |
| :-- | :-- |
| 刚进设置页 | `未连接 (no privileged process)` / `root: 未检测` / `shizuku: 已授权` |
| 按一下「连接」 | `已连接: root, uid 0` / `root: 已授权` / `shizuku: 已授权` |

三件事值得记: 装完 APK 之后通道**是"未连接"而不是坏掉** (没人问过它), 所以这一页第一次看到的就是 `未连接`; 这台设备**两条路都通** (root 已授权、Shizuku 也在跑并已允许), 而 `PrivilegedChannel` 先试 root 所以连着的是 root; 底层英文的理由句直接摆在中文界面里是有意的 —— 它是设备的原话, 与 `lw_probe` 看到的是同一句

### 连接改到启动时做, 按钮退成"重试" (2026-09-22, 用户问出来的)

用户问"单独放个连接按钮有什么特别的用处吗, 直接在启动应用的时候开一个协程来做可以吗" —— 答案是可以, 而且应该: **连一次要起一个 `app_process`** (JVM 起来 + 等 binder, 秒级), 不做这件事那笔账会落在**模型第一次截图**上, 而那正是它最不该出现的地方。所以 `DshHostService.onCreate` 里多了一个后台线程 (`lw-channel-warmup`) 跑一次 `PrivilegedChannel.ensure()`, 与同文件里 `onDestroy` 那条 `lw-channel-close` 同一个写法

它可能弹的东西只有两样, 而且都落在"用户刚打开应用"这个答得上来的时刻: `su` (root 管理器自己决定是弹框还是什么都不做) 与 Shizuku 自己的对话框

**按钮留着, 但只在没连上时出现**, 定位从"入口"变成"重试" —— 真正的用处是"刚在 KernelSU 里给完授权, 不想重启应用"。实测 (杀掉特权进程制造断线):

| 步骤 | 界面 |
| :-- | :-- |
| `kill` 掉 `…:lw_root`, 重新进设置页 | `未连接 (the root process disconnected)` / `root: 已授权` / `shizuku: 已授权` + **按钮出现** |
| 按一下按钮 | `已连接: root, uid 0` (`ps` 里 `lw_root` 回来了), **按钮消失** |

一个踩到的小坑: **这一页的状态是一次性的** (`LaunchedEffect(Unit)` 里 refresh 一次), 所以进程在页面开着的时候死掉, 页面上还是"已连接" —— 要退回上一页再进来才会重读。按钮本来就只在这一页上, 影响有限, 记在这里免得下次以为是状态读错了
