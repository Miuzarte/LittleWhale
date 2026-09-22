# 构建事实与依赖缺口 (从 AGENTS.md 拆出来, 动构建时才读)

这篇是 AGENTS.md 里"已确认的事实"中被拆出来的部分: **随 APK 发的二进制怎么落地、Termux 写死的路径、host 树的 pack 与 zip、被否的方案、dsh 的原生依赖缺口、工具链版本**。
拆的理由是 AGENTS.md 贴着指令预算 (超了会被静默截断), 而这些内容只在**动构建 / 升依赖 / 重打包**时才需要, 平时读它是纯开销

当前进度与"现在做什么"仍以 `AGENTS.md` 为准; 每一步的实测过程与踩坑在 `docs/step2-record.md` … `docs/step7-record.md`

### 运行时与随 APK 发的二进制怎么落地

关键约束是安卓 10+ 的 **W^X**: `targetSdk >= 29` 的 app 不能 exec 自己 data 目录里的文件 (`app_data_file` 上 `execute_no_trans` 被移除), 所以二进制**当 native lib 发**: 文件名以 `.so` 结尾放进 `jniLibs/arm64-v8a/`, 配合 `jniLibs.useLegacyPackaging = true`, 安装时会被解到 `applicationInfo.nativeLibraryDir` (`/data/app/.../lib/arm64/`), 那里 SELinux 允许任何 app domain exec (`allow appdomain apk_data_file:file rx_file_perms`) — 跟 `targetSdk` 无关, 也是唯一符合 Play 政策的做法

**这套办法现在装了三样东西** (都是 Termux 编的 bionic 二进制, 都做过同样的归一; 完整闭包与体积账在 `docs/step2-record.md`):

| 装什么 | 归一后的名字 | 为什么要发 |
| :-- | :-- | :-- |
| Node 24.18 | `libnode.so` + 9 个依赖库 | dsh 的 host 就是它 |
| bash 5.3 | `liblwbash.so` + `liblwandroidsupport/readline/ncursesw/iconv` | 安卓只有 mksh, 而 agent 写的是 bash |
| ripgrep 15.2 | `liblwrg.so` + `liblwpcre2.so` | dsh 的 grep / glob 工具要 `rg`, 而 `@vscode/ripgrep` 没有 android 变体 |

三件必须记住的事:

- **AGP 会静默丢掉带点的 so 名** (`libz.so.1` / `libcrypto.so.3` / `libicu*.so.78` 那种进不了 APK), 所以**必须**用 `patchelf --set-soname` 加 `--replace-needed` 把整套名字归一成不撞名的 `liblw*.so`, **不是可选优化**; 而且 `--set-rpath '$ORIGIN'` 要打在**每一个**对象上, 因为 bionic 查的是**加载方自己**的 runpath
- **16 KB 页对齐不用管** 这几个二进制每个 PT_LOAD 的 `p_align` 已经是 `0x4000`, 我们只是搬运现成的 ELF 不是链接
- **17 个对象 (114,590,456 字节) 不进 git** (`app/src/main/jniLibs/` 在 `.gitignore` 里), 所以**本机这份是唯一副本, 别删也别清 build 目录**; 正式形态是挂 GitHub Release 由 Gradle task 下载 (顺带解决"patchelf 是 Linux 工具、Windows 构建机上改不了 ELF"的矛盾), 现在暂缓 (见「现在做什么」)

**`lib*.so` 这个名字 PATH 认不出来, 所以有三个缝让部署方接上** (都在 `DshHost.kt` 里设):

| 变量 | 作用 |
| :-- | :-- |
| `DSH_BASH` | `bash-local` 用它当 `bash -c` 的可执行文件 (fork patch, 不设就还是 PATH 上的 `bash`) |
| `DSH_RG_PATH` | `tool-fs-search` 用它当 ripgrep 路径 (fork patch, 不设就走原来的 pkg sidecar / `@vscode/ripgrep`) |
| `BASH_ENV` | 指向 `filesDir/shell-env.sh`, 里面用函数把 `bash` / `rg` / `node` 三个名字接回 nativeLibraryDir, 否则模型在 shell 里敲 `rg` / `node` 是 command not found |

`SHELL` 也跟着指向随包发的 bash (拿不到时才退回 `/system/bin/sh`)

**`libnode.so -v` 是个假阳性冒烟测试**: 它不初始化 crypto, 所以能打印 `v24.18.0`, 而同一个二进制跑 `-e` 会因为下面那三个写死的 Termux 路径静默 exit 13 — 验证运行时**必须**用一个真的求值表达式

### Termux 二进制里写死的路径 (真机实测, 三个环境变量少一个都起不来)

Termux 编出来的 node 有一批**写死的 Termux 路径** (`libnode.so` 里 19 处, `liblwcrypto.so` 11 处), 这是移植时最容易踩空的地方:

| 变量 | 不设会怎样 | 说明 |
| :-- | :-- | :-- |
| `OPENSSL_CONF` | **Node 在 bootstrap 阶段直接 abort, exit 13 且什么都不打印** | Termux 的 libcrypto 编译时写死 `OPENSSLDIR=/data/data/com.termux/files/usr/etc/tls`, app uid 既读不到也打不开, 指向任意 app 可读的**普通文件** (空文件就行) 即可 |
| `SHELL` | fallback 是不可达的 Termux `bash` 与 `/bin/sh` | 现在设成**随包发的 bash** (见上), 拿不到时才退回 `/system/bin/sh` |
| `TMPDIR` | V8 的 GC 临时文件 (`/tmp/__v8_gc__`) 落在写死的 Termux tmp 上 | 指向 app 可写目录 |

好消息 (已实测): CA 证书走的是**安卓系统证书库** (120 个根证书读得到), 时区也走安卓路径, 所以 TLS 与时间都活着, 设好 `OPENSSL_CONF` 后实测能完成 TLS 1.3 握手

残留噪音: 任何碰 `node:tls` 的代码仍会让 libssl 按默认路径再找一次配置并向 stderr 打一行 `OpenSSL configuration error ... fopen(/data/data/com.termux/files/usr/etc/tls/openssl.cnf)`, 无害, 不影响 `dsh web: <url>` 就绪行的 grep

**体积与命名的实测数字** (SONAME 归一的开销, APK 涨多少) 见 `docs/step2-record.md`

### host 产物怎么落地 (已实测: 上游自带 pack → 平铺安装)

走上游自带的 `scripts/release/pack.ts` (逐个 `pnpm pack`) 再当一个普通 npm 消费者 `npm install --omit=optional` 平铺装上 —— 这是「脱离 pnpm 符号链接」的正解, 已经由 `tools/pack-host.mjs` 自动化, 配方全文与踩坑在 `docs/step2-record.md`。要记住的四条:

- **打包必须用 `build:official`** 普通 `pnpm run build` 会被前置校验挡下 (`client build environment differs from the required artifact profile`)
- **启动要加 `--expose-internals`** (理由见「dsh 的原生依赖缺口」)
- dsh 与 vendor **两个家族要输出到不同目录** (pack 会先 `rmSync` 目标目录)
- `apps/web/dist` 与每个 client 包的 `lib/client.js` **都随 tarball 走**, 不用单独拷; npm 11 默认**不跑安装脚本**, `subprocess-local` 的 postinstall 被跳过 (那是给 pty helper 补执行位的, 安卓上本来就没有 pty)

### host 树怎么进 APK (已实测: 构建期打 zip + 首启解压)

`tools/push-host.mjs` 只是**开发时的快迭代路径** (改 dsh 不用重装 APK), 正式形态是构建时把整棵树塞进 `assets/`, app 首启解压到 `filesDir/host`, 由两个 Gradle task 承担:

| task | 作用 |
| :-- | :-- |
| `:app:packHostTree` | 清陈旧 `lib/` → `build:official` → pack 两个家族 → `npm install` 出平铺树, 落到 `app/build/host-tree` |
| `:app:zipHostTree` | 把树打成 `host.zip` + 写 `host-version.txt`, 落到 `app/build/generated/host-assets`, 该目录是 `main` 的 asset 源 |

两个 task 的输入都含 **submodule pin** 与它的 src 指纹, 所以只有 dsh 变了才重跑; `:app:merge<Variant>Assets` 依赖 `zipHostTree`, 正常 `assembleDebug` 会自动带上 (AGP 9 上挂这两个 task 踩的两个坑 — `sourceSets` 不收 Provider、merge task 要等 `onVariants` 之后才注册 — 记在 `docs/step2-record.md`)

**版本戳是 app 与 APK 之间的契约** `host-version.txt` 里 `version=` 是**树内容**的 sha256 前 32 位 (路径 + 大小 + 内容, 不含时间戳, 同样的字节重打一次不会让已装设备白解压一遍), `entries=` 是文件数。`host/HostInstaller.kt` 拿 assets 里的戳跟 `filesDir/host/host-version.txt` 比: 一致且 `node_modules/@deepseek-ai/dsh/lib/bin.js` 在就什么都不做 (启动是热的), 否则删掉整棵树重解压, **解压完最后才写戳** (解压到一半被杀掉不会留下"看起来完整"的树); 没有戳 asset 时直接判失败并显示原因, 不静默空跑

解压跑在前台服务的 supervisor 线程里 (先 `ensure` 再 spawn), 进度进 `HostStatus.Installing(done, total)` 由启动面板显示, 因为 30520 个文件 / 281 MB 不能在主线程做。`tools/push-host.mjs` 推完树会**把 assets 里的版本号写进 `filesDir/host/host-version.txt`**, 于是快迭代推上去的树不会被 APK 里的旧树盖掉。**体积没裁是有意的决定**, 实测数字 (解压耗时、APK 涨多少) 见 `docs/step2-record.md`

### 不采用的方案 (已排除, 别回头试)

| 方案 | 为什么不行 |
| :-- | :-- |
| `nodejs-mobile` | 只有 Node 18 (已 EOL), 不满足版本要求; 官方 FAQ 还明说 `child_process.spawn/fork` 在安卓上有权限问题 — 对一个要 shell out 的 agent CLI 是致命的 |
| Bun | 官方 Linux arm64 二进制不是 PIE, 安卓内核直接拒绝执行 |
| Deno / QuickJS / LLRT / workerd | 不是 Node 兼容运行时, `child_process` + 长驻本地 server + POSIX 文件系统这套全都不成立 |
| 官方 Node 二进制 | 上游**不发布**安卓构建 (`BUILDING.md` 写明 Android is not a supported platform) |
| `targetSdk <= 28` | Termux 自己的做法, 能用但是死路 (Play 上不了, 新安卓版本随时收紧), 只当调试捷径 |
| `/system/bin/linker64 <path>` | 可行 (`termux-exec` 的 `system_linker_exec`), 但静态链接的二进制不行, `/proc/self/exe` 会变成 linker64, 留作 `nativeLibraryDir` 走不通时的兜底 |

### dsh 的原生依赖缺口 (真机实测, 不是两个是六个)

在小米 13 上用打包安装的树起 `dsh web`, 加载器 152 条 entry 里**挂掉的只有 `subprocess` 和 `sandbox` 两条**, 都是被模块顶层的原生 import 拖死的:

| 依赖 | 谁在**模块顶层**引用 | 处理 |
| :-- | :-- | :-- |
| `koffi` | `subprocess-local` 的 `spawn-runner.ts → linux-execve.ts` 与 `sandbox-local → dsh-sandbox-windows-acl → ffi.ts` | 惰性化, 安卓走 Node 自己的 `child_process.spawn` |
| `node-pty` | `subprocess-local/src/index.ts:15` (prebuild 只有 darwin / linux / win32) | 同上, 持久终端在安卓上暂不可用 |
| `sharp` | `attachment-local` 的 `image.ts` / `normalization.ts` / `request-image.ts` | 树里换成了 `image-backend/sharp/` 的纯 JS 替身, 见「dsh 工具」 |
| `node-addon-require-builtin` | `vendor/loader/src/internal.ts` 的 `requireInternal()` | **不用管**: `--expose-internals` 让它走 `require(id)` 分支 (不加就是整个进程退出) |
| `node-addon-system/flock` | `session-persistence-jsonl/src/lease.ts:34` | 安卓上 `loadBinding()` 抛 `ERR_FLOCK_UNSUPPORTED_PLATFORM`, 按 dsh 给浏览器 worker 的做法降级成"无跨进程排他" |
| `node-addon-system/landlock-run` | `sandbox-local/src/profiles.ts:7` | 惰性, `probe()` 直接 `unusable`, dsh 设计上就容忍 |

**三条必须记住的:**

- 这些都是**模块顶层**的 import, 所以"这个功能先不启用"救不了: 只要那条 row 在 profile 里, import 就会执行, 整个 profile 树跟着一起挂 — 只能改 import 形态 (惰性 / 平台化) 或者换掉包
- **运行期替身包 (stub) 只能救一部分**: `dsh-win32-process` 在模块顶层断言 struct 尺寸, 假 koffi 一读属性就露馅 (`STARTUPINFOW layout mismatch`), 所以 `subprocess` / `sandbox` 必须靠 **dsh 侧的惰性化补丁**, 光换包不行, 这正是 submodule 存在的意义
- **`.node` 文件放在 app data 目录里大概率 dlopen 不了** (linker namespace 只认系统库和 APK 自己的 `nativeLibraryDir`), 所以「给安卓编一个 koffi / node-pty」即使编出来也要解决加载路径, 惰性化 + 降级才是近期正解

**补丁的落地情况**: 17 个文件 (8 src + 9 测试) 的提交 `87a93da723` 在 `Miuzarte/deepseek-harness` 的 `master` 上, **逐文件改法表在 `patch.md` 第 4 节** (唯一权威); 验证: `tsc -b tsconfig.host.json` 0 错, 产物里 eager 的 `koffi` / `node-pty` / `sharp` 共 0 处, 相关 2368 个测试通过, web profile 回归能起 (53 条 entry)

**打包前必须清 `lib/` 这个坑实测踩到了**: tsdown 是 `clean: false`, 改完源码后**旧 chunk 会留在 `lib/`**, 而各包 `files` 正好 glob `lib/runner-*.js` / `lib/types-*.js`, 于是带 eager `import koffi` 的陈旧 chunk 会被一起装进 tarball, **所以 pack 之前要先删掉 `packages/*/*/lib` / `apps/*/lib` / `vendor/*/lib` / `native/system/packages/*/lib`** (现在由 `tools/pack-host.mjs` 负责)

**留一条顶层 `@deepseek-ai/dsh-win32-process` import 是安全的**: 那个包在 import 阶段已经不碰原生, 硬要惰性化反而会让 `probeWindowsJob` 在 `createRequire` 失败时静默降级, 不值得; 惰性访问器里的 `/* v8 ignore next */` 是为了仓库的 per-file 100% 分支覆盖门, 别删

## 工具链与版本

本机已装好, 不用再折腾:

| 项 | 版本 |
| :-- | :-- |
| Android Studio | 2026.1 (`AI-261.26222.65.2614.16204760`) |
| SDK | `B:\Software\AndroidSDK`, platforms 到 `android-37.0` |
| build-tools | 到 `37.0.0` |
| NDK | `29.0.14206865` (另有 28.2 / 25.2) |
| JDK | 21.0.2 |

SFA 用的组合 (照抄它最省事, Miuix 那边也验证过): **AGP 9.4.0** (当前 stable) + **Kotlin 2.4.20** + **Gradle 9.7.1** + `compileSdk 37` / `targetSdk 37` + **JVM target 21** + `buildToolsVersion 37.0.0` + `ndkVersion 29.0.14206865`

Miuix 走 **Maven Central**, 不搞 composite build: 坐标 `top.yukonga.miuix.kmp:miuix-{ui,preference,icons,nav,squircle}`, 当前 `0.9.4` (stable 是 `0.9.3`)

**Miuix 各模块的 minSdk 不一样, 这是踩过的坑:**

| 模块 | minSdk |
| :-- | :-- |
| `miuix-blur` | **33** |
| 其余 (`ui` / `core` / `nav` / `preference` / `icons` / `squircle` / `shader`) | 24 |

所以**只要依赖里出现 `miuix-blur`, app 的 `minSdk` 就必须 >= 33**, 否则 manifest merger 会直接失败:

```
uses-sdk:minSdkVersion 26 cannot be smaller than version 33 declared in library
[top.yukonga.miuix.kmp:miuix-blur-android:0.9.4]
```

这也解释了 SFA 为什么把毛玻璃底栏 gate 在 Android 13+ (`RuntimeShader`)

**本项目的 `miuix-blur` 依赖已于 2026-09-22 去掉** (顶栏那个模糊选项一并删了: 顶栏下面就是虚拟屏画面, 那块画面自己不透明, 糊了也没人看得见) —— 于是这条 minSdk 约束**不再存在**, `minSdk = 33` 从"被顶上去的"变成"选的值", 往下放是另一个决定 (无障碍取树那条路要 API 30+, 而且 33 以下没验过)

`compileSdk` / `targetSdk` 用 **37**, `minSdk` 用 **33**

> SFA 用的是 `includeBuild("submodule/miuix")` 源码 composite build (子模块 `https://github.com/compose-miuix-ui/miuix.git`), 那个只在对 Miuix 本身打补丁时才需要, 新项目直接吃 Central 上的产物, 但**别改 Miuix 库本身**这条 SFA 的规矩照抄

### 项目当前实际状态 (已实测可用)

Android Studio 生成的那套默认版本全都太旧, 已经改过; 现在的实际值是:

| 项 | 项目实际值 | 说明 |
| :-- | :-- | :-- |
| AGP | `9.2.1` | **从 9.4.0 退回来的**, 见下; MAA-Meow 也是 9.2.1 |
| Kotlin | `2.4.20` | **必须显式抬上来**, 见下 |
| Gradle wrapper | `9.6.1` | 9.6.0 的 worker 缓存在这台机器上坏了 (见下), 不能用 |
| Compose BOM | `2026.09.00` | 生成的是 `2026.02.01` |
| Miuix | `0.9.4` | 生成时没有, 手动加的 |
| JVM target | `21` | 生成的是 `11` |
| Gradle daemon JDK | `25` | `gradle/gradle-daemon-jvm.properties` 里 `toolchainVersion=25`; Android Studio 自带 JBR 就是 25.0.3 |

**AGP 别升回 9.4.0** 它的 `processDebugResources` 会走 `AarResourcesCompilerTransform`, 而它拿到的 AAR 解包目录里**只有 `jars/classes.jar`, 没有 `AndroidManifest.xml`**, 于是整个 `assembleDebug` 卡在 `transformed/miuix-preference`; 试过并排除的原因 (zip 读取、`AarExtractor`、变换缓存污染、libsu/shizuku、281 MB assets、cmake/ndk/abiFilters、aidl、Miuix 独有) 全记在 `docs/step3-record.md`, **换 9.2.1 就正常**

**Gradle wrapper 也别退到 9.6.0** 那台机器上 `caches/9.6.0/workerMain` 坏了 (重建时 Windows 不让它删掉自己正持有的锁), 于是**任何要 Worker API 的任务都失败**, 删掉整个 `caches/9.6.0` 也没用; 9.6.1 的这份缓存是好的

**Kotlin 版本是个坑, 别退回去** AGP 9 用内建 Kotlin (不 apply `kotlin-android`), 默认是 **2.3.21**; 而 Miuix 0.9.4 是按 Kotlin 2.4.10 编的, 生成出来的 `libs.versions.toml` 里写 `kotlin = "2.2.10"` 会把 app 的 `kotlin-stdlib` 钉在 2.2.10, 跟内建 Kotlin 打架, 解决办法 (照抄 MAA-Meow) 是在根 `build.gradle.kts` 里把 Kotlin 插件以 `apply false` 声明一遍, 让 2.4.20 留在 build classpath 上:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
```

`app/build.gradle.kts` 里对应地 apply compose + serialization 插件 (**不 apply** `kotlin-android`)

Miuix 的实际包名是 `top.yukonga.miuix.kmp.theme` / `.basic` 等 (不是 SFA AGENTS.md 里写的 `top.yukonga.miuix.kmp.*` 的简写形式), 从 AAR 里核出来的关键 API:

- `MiuixTheme(controller = ThemeController(...))`, 也有 `MiuixTheme(colors = ...)` 重载
- `ThemeController(colorSchemeMode = ColorSchemeMode.System, ...)`, 枚举是 `System/Light/Dark/MonetSystem/MonetLight/MonetDark`
- 还有 `ThemeColorSpec` (`Spec2021`/`Spec2025`), `ThemePaletteStyle` (`TonalSpot`/`Neutral`/`Vibrant`/`Expressive`/`Rainbow`/`FruitSalad`/`Monochrome`/`Fidelity`/`Content`)
- 组件在 `.basic`: `Scaffold`, `SmallTopAppBar(title = String, ...)`, `Text(text = String, ...)`, `Card`, `Button`, `TextField`, `SearchBar`, `TabRow`, `NavigationBar`, `SnackbarHost`, `BreadcrumbBar` ← **面包屑在这里, 0.9.3 没有, 这就是要用 0.9.4 的原因**
- 模板生成的 `ui/theme/Color.kt` 和 `Type.kt` 是纯 Material3 的, 已经删掉; `Theme.kt` 改成了包 Miuix 的

