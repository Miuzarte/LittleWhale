# 第 2 步的实测记录与数字

从 `AGENTS.md` 挪出来, 因为那份文档贴着 64 KB 的指令预算, 超了会被静默截断; 这里是历史与数字, 当前结论仍在 `AGENTS.md` 里

### 命名与体积的实测账 (2026-09)

> 这一节只算 **Node 那一批 10 个对象**; 后来随包发的 bash 与 ripgrep 走的是同一套流程, 那 7 个对象的闭包与数字见下面「安卓上补齐 shell 与搜索」。jniLibs 现在的总数是 17 个对象 / 114,590,456 字节

- SONAME / NEEDED 归一成 `liblw*`, **这一步贵**: 闭包 92,934,441 → **105,944,793 字节 (+12.4 MiB)**, 几乎全在 `libnode.so` (45.3 → 55.6 MB), 因为 patchelf 扩 `.dynstr`、搬 section、PT_LOAD 从 3 个变 5 个; 换成**等长**名字得到**完全一样**的字节数, 所以涨的是 patchelf 本身的开销, 与名字无关
- **不撞名是对的**: `process.report.getReport().sharedObjects` 显示我们的 9 个库全部从 nativeLibraryDir 加载, 而平台的 `/system/lib64/libz.so` 与我们的 `liblwz.so` **同时**存在 — 如果当初叫 `libz.so` 就会撞上
- APK 实测 **71,926,833 → 109,075,056 字节 (+35.4 MiB)**, 比 101 MiB 的裸载荷小得多: **`useLegacyPackaging = true` 只保证装完解压到 nativeLibraryDir, 并没有让 APK 里的库不压缩**, AGP 照样 deflate (libnode.so 55,647,840 → 压缩后 16,577,423)
- `nativeLibraryDir` 里 10 个对象权限都是 `-rwxr-xr-x`, `run-as` (app 域) 与 `su` 两条路都实测 `libnode.so -v` → `v24.18.0`, 解压 + exec 这条链彻底验通

### Node 运行时怎么进的 APK (完整版)

关键约束是安卓 10+ 的 **W^X**: `targetSdk >= 29` 的 app 不能 exec 自己 data 目录里的文件 (`app_data_file` 上 `execute_no_trans` 被移除), 正解是**把二进制当 native lib 发**: 文件名以 `.so` 结尾放进 `jniLibs/arm64-v8a/`, 配合 `jniLibs.useLegacyPackaging = true`, 安装时会被解到 `applicationInfo.nativeLibraryDir` (`/data/app/.../lib/arm64/`), 那里 SELinux 允许任何 app domain exec (`allow appdomain apk_data_file:file rx_file_perms`), 这条路跟 `targetSdk` 无关, 也是唯一符合 Play 政策的做法

Termux 的 `node` 实测 `readelf -d`:

```
RUNPATH: /data/data/com.termux/files/usr/lib
NEEDED:  libz.so.1 libcares.so libsqlite3.so libcrypto.so.3 libssl.so.3
         libicui18n.so.78 libicuuc.so.78
         libc.so libm.so libdl.so libc++_shared.so
```

44 MB, 动态链接 PIE, 2026-09 第二次实测补齐的细节 (这些坑都踩过了):

- **闭包是 9 个非系统库, 不是 8 个** `libicudata.so.78` 只在传递依赖里 (`libicuuc.so.78` → `libicudata.so.78`), 只看 node 自己的 dynamic section 会漏掉它, 漏了 node 直接起不来 (用 `readelf -d` 逐个跟链)
- Termux 里 `libz.so.1` / `libicudata.so.78` / `libicui18n.so.78` / `libicuuc.so.78` / `libsqlite3.so` **都是符号链接**, `stat -c%s` 不加 `-L` 拿到的是链接目标字符串长度 (十几字节), 必须 `cp -L`
- Termux **默认没有 `patchelf`**, `pkg install patchelf` 之后是 0.19.1
- `patchelf --set-rpath '$ORIGIN'` 要打在**每一个**对象上, 不只是 node: bionic 查的是**加载方自己**的 runpath, 所以 `libicuuc.so` 得自己带 `$ORIGIN` 才找得到 `libicudata.so`
- spawn 时**同时**设 `LD_LIBRARY_PATH=<nativeLibraryDir>` 作冗余
- 已实测能独立跑: `env -u LD_LIBRARY_PATH ./libnode.so -v` → `v24.18.0`, `process.platform` 是 `android`, `process.versions.icu` 78.3, 说明对 Termux 的依赖真的被切断了

**两个反直觉但已经证实的事实:**

1. **AGP 会丢掉带点的 so 名** `libz.so.1` / `libcrypto.so.3` / `libssl.so.3` / `libicu*.so.78` 在 `mergeDebugNativeLibs` 阶段就被**静默过滤掉**, 根本进不了 APK — 证据是 `app/build/intermediates/merged_native_libs/debug/.../out/lib/arm64-v8a/` 里只剩 `libnode.so` / `libcares.so` / `libsqlite3.so` / `libc++_shared.so`, 所以**必须**用 `patchelf --set-soname` 改 SONAME 再用 `--replace-needed` 改依赖方的 NEEDED, 把文件名全部归一成 `lib*.so`, 这一步不是可选优化
   - 后来验证: 归一后的名字确实是 `liblw*` 这种不撞名的形式, `process.report.getReport().sharedObjects` 显示 9 个库全部从 nativeLibraryDir 加载, 平台的 `/system/lib64/libz.so` 与我们的 `liblwz.so` **同时**存在
2. **16 KB 页对齐不用管** Termux 的 node 和这 9 个库每个 PT_LOAD 的 `p_align` 已经是 `0x4000` (16384), patchelf 也保持不动, 所以不需要 `-Wl,-z,max-page-size=16384` — 我们这里不链接, 只是搬运现成的 ELF

**体积与入库 (已定: 不进 git)**: 10 个对象共 **92,934,441 字节 (88.6 MiB)**, 其中 `libicudata.so.78` 一家 33.1 MB (纯 ICU 数据块), 是最大的裁剪杠杆

- `app/src/main/jniLibs/` 已经写进 `.gitignore`, 所以**干净 clone 直接构建会缺运行时**, 这是已知且接受的中间状态
- 正式形态: 把这 10 个处理好的对象挂到 **GitHub Release**, 由 Gradle task 下载进 `jniLibs` — 顺带解决"patchelf 是 Linux 工具, Windows 构建机上没法现改 ELF"这个矛盾 (预置产物只在设备或 CI 上生成一次, 构建机只负责下载)
- 在那之前, 本机这份已经处理好的 `jniLibs` 是**唯一副本**, 别删, 也别忘了它不在 git 里 (换机器/清 build 目录前先备份)

### host 树怎么打的、怎么进 APK (完整版)

**上游自带打包路径, 不用自己造**: `scripts/release/pack.ts` 把 workspace 逐个包 `pnpm pack`, `scripts/release/verify-packed-install.ts` 再把 tarball 装进一个仓库外的空消费者里跑起来 — 这就是「脱离 pnpm 符号链接」的正解, 本机与真机两次实测都走这条路。

本机配方 (`B:\Git\deepseek-harness`, 2026-09):

1. `node --import tsx/esm scripts/build.ts --profile official` — 打包前置校验**要求客户端产物是 official profile** (`DSH_CLIENT_BUILD_PROFILE=official` + `DSH_CLIENT_TITLE`), 普通 `pnpm run build` 会被 `verifyBuildArtifacts` 挡下, 报 `client build environment differs from the required artifact profile`
2. `node --import tsx/esm scripts/release/pack.ts --family dsh --out dist/npm-dsh --concurrency 8` → **266 个 tarball, 11.3 MB**; vendor 家族 (`--family vendor`) 另需 9 个 (cordis / schemastery 等), 两个家族要输出到**不同目录** (pack 会先 `rmSync` 目标目录)
   - `pnpm run release:pack -- --family dsh` 会把 `--` 原样透传成位置参数, 直接 `node --import tsx/esm` 调脚本
3. 装法就是一个普通 npm 消费者: 每个 tarball 在 `package.json` 里写成一个 `file:` 依赖, 然后 `npm install --no-audit --no-fund --package-lock=false --omit=optional` → **660 个包, 280.9 MB, 30495 个文件**, 1 分钟
4. 起来后 `http://127.0.0.1:<port>/?token=...` 返回完整 GUI (52 个 client 包写进 `window.__DSH_BOOT__`), 页面、`/plugins/??.../client.js`、`/assets/*` 全是 200

真机验证 (Termux, Node v24.18.0): 同一棵树整包 scp 过去 (60.1 MB, 1.3 s) 解开 376 MB, `node --expose-internals .../dsh/lib/bin.js web` 能一路走到加载器, **只有原生依赖相关的那几条 row 会挂**。

**必须记住的陷阱**: Termux 是 `targetSdk 28`, 它**能 exec 自己 data 目录里的文件**, 所以这条路只验了 JS / host 这一层, **验不了** APK 会遇到的 W^X 与 linker namespace 限制; 真机跑通不等于 APK 里跑通。

`apps/web/dist` 与 client 产物的落点:

- `apps/web` 的包名就是 `@deepseek-ai/dsh-web-frontend`, 它的 `files: ["dist"]`, 所以 **web 产物随 tarball 走**, 不用单独拷
- `@deepseek-ai/dsh-web-app` 用 `require.resolve('@deepseek-ai/dsh-web-frontend/package.json')` + `dist/index.html` 定位, 只要安装树里能解析到这个包就行
- 每个 client 包的 `files` 里都带 `lib/client.js`, 由 `@deepseek-ai/dsh-client-modules` 的 node 半边扫 roster, 再用 `/plugins/??a/client.js,b/client.js` 这种多路径形式服务 — 所以 **client 产物也随 tarball 走**
- 280.9 MB 的大头不是 dsh 自己: `@deepseek-ai/*` 只有 36.4 MB, 其余是 `node-pty` 26.8 / `typescript` 23.2 / `@opentelemetry` 20.3 / `@google` 13.7 / `koffi` 13.6 / `esbuild` 11.3 这些第三方
- npm 11 默认**不跑安装脚本** (会 warn `allowScripts`), 被跳过的里面有 `subprocess-local` 的 postinstall (`ensure-spawn-helper.mjs`, 给 node-pty 的 prebuilt helper 补执行位); 安卓上本来就没有 pty, 以后真要用得显式放行

**打 zip 进 APK 这一段踩过的两个 AGP 9 坑:**

- **AGP 9 不允许 `sourceSets` 里塞 Provider** 报 `You cannot add Provider instances to the Android SourceSet API`, 官方替代是 variant API 的 `addGeneratedSourceDirectory`, 但它要一个带 `@OutputDirectory` 的 task 类型, 而本项目开了 configuration cache (在 build script 里声明 task 类与 CC 冲突), 所以退回「普通目录 + 显式 `dependsOn`」
- 挂依赖用 `tasks.matching{}.configureEach{}` 而不是 `tasks.named`: **AGP 9 在 `onVariants` 之后才注册 merge task**, 那时 `tasks.named` 直接抛 `UnknownTaskException`; 判据是 `assembleDebug --dry-run` 里能看到 `packHostTree → zipHostTree → mergeDebugAssets` 的顺序

**解压与体积的数字**: `host.zip` 因 `noCompress += "zip"` 在 APK 里是 **stored**; 首启解压 30520 个文件 6.8 s (新 APK 重打后复测 8.1 s), host 就绪再 +6.9 s, 之后每次启动走热路径 (`is already unpacked`) 直接 spawn。

**没裁剪, 这是有意的决定** 树 281.0 MB → `host.zip` 78.6 MB, APK 涨同样的量。大头 `node-pty` 26.8 / `typescript` 23.2 / `@opentelemetry` 20.3 / `@google` 13.7 / `koffi` 13.6 / `esbuild` 11.3 MB 里, 只有 `node-pty` 是**确定**能删的 (惰性化之后没人 require, 而且它在安卓上本来就没有 prebuilt), 其余都是**模块顶层 import**, 删掉等于整棵 profile 挂掉, 要删得先像 koffi 那样逐个验; 先按「完整树 + 已验证」发, 裁剪等真有体积需求时再动

### `sharp` 的接口面 (第 2 步调查完的结论, 给以后的 Kotlin `Bitmap` 后端)

只有 `packages/attachment/attachment-local` 一家用它 (整个仓库就这一个 `"sharp"` 依赖), 要顶掉的能力是这几样:

- `sharp(data, { failOn: 'error', limitInputPixels: false })` 之后 `metadata()`, 取 `format / width / height / pages / orientation / depth / space / hasAlpha`, 以及 `exif / xmp / iptc / icc / hasProfile / tifftagPhotoshop / comments` 用来判断"是否携带元数据"
- `image.raw().toBuffer()` — 准入时证明这些字节**真的能完整解码**
- 流水线 `rotate()` (应用 EXIF 方向) → `toColourspace('srgb')` → `resize({ fit: 'inside', withoutEnlargement: true })` → `clone()`
- 两档编码 `webp({ quality, effort: 0 })` / `jpeg({ quality })`, 质梯 `[85, 75, 60]`, 出口 `toBuffer({ resolveWithObject: true })` 拿 `{ data, info: { width, height } }`
- 关键约束: 产物回来还要过 `verifyNormalizedImage` / `verifyRequestImage` 的自校验 (`detectImage` 复检 depth / space / hasAlpha / 尺寸 / 单帧 / 无元数据), 所以 Kotlin `Bitmap` 后端必须给出**可信**的这组事实, 糊弄不过去
- 5 个文件: `image.ts` / `normalization.ts` / `request-image.ts` / `encoding.ts` (+ 调用方 `store.ts`), 没有别的入口

### 第 2 步已完成清单 (2026-09-18)

- Node 运行时落地: 9 个非系统库 + 每个对象 `DT_RUNPATH=$ORIGIN` + SONAME/NEEDED 归一成 `liblw*`, 装进 `app/src/main/jniLibs/arm64-v8a/` (Node 那批 10 个对象 / 105,944,793 字节, **不进 git**; 后来 bash 与 rg 又加了 7 个, 合计 17 个 / 114,590,456)
- dsh 侧 17 个文件 (8 src + 9 测试) 的惰性化补丁入库: 提交 `87a93da723`, 逐文件改法在 `B:\Git\deepseek-harness\patch.md` 第 4 节
- 独占发布补丁 (安卓没有硬链接): 提交 `bbd87d627a` / `2eebfc78de` / `a01302155a` (会话与文件写入) + `125d5c386c` (附件), 见 `patch.md` 第 5 节
- 随包发的 bash 与 ripgrep: 提交 `bc6a4cb004` (`DSH_BASH` / `DSH_RG_PATH` 两个缝), 见 `patch.md` 第 6 节
- Kotlin 侧: `host/HostStatus.kt`、`host/DshHost.kt`、`host/HostInstaller.kt`、`host/DshHostService.kt`、`workspace/Workspace.kt`、`ui/HostScreen.kt`、`MainActivity.kt`, manifest 加前台服务 / `INTERNET` / `POST_NOTIFICATIONS` / `usesCleartextTraffic` / `MANAGE_EXTERNAL_STORAGE`, 字符串进 en + zh
- 本仓新增 `tools/pack-host.mjs` (dsh → 平铺 host 树) 与 `tools/push-host.mjs` (推进 app 沙盒, 免重装 APK 的快迭代路径)
- 启动契约: `node --expose-internals <host>/node_modules/@deepseek-ai/dsh/lib/bin.js web --no-open --port 3080`, 环境变量 `DSH_HOME` / `LD_LIBRARY_PATH` / `HOME` / `TMPDIR` / `OPENSSL_CONF` / `SHELL` / `NODE_COMPILE_CACHE` / `DSH_BASH` / `DSH_RG_PATH` / `BASH_ENV` (见 `DshHost.kt`)

### 安卓上补齐 shell 与搜索: 随 APK 发的 bash 与 ripgrep (2026-09-18 晚)

会话里模型自己撞出来的两个洞: Bash 工具报 `spawn bash EACCES` (安卓没有 bash), Grep / Glob 报 `ripgrep launch failed` (树里的 `@vscode/ripgrep` 没有 android 变体)。解决办法是**把真二进制当 jniLibs 发**, 和 Node 完全同一套流程。

**为什么不是退回 mksh (`/system/bin/sh`)**: mksh 是 ksh 子集, `${v^^}` / `local -n` / `mapfile` / 花括号展开都没有, 而模型是按 bash 写的 —— 那种"命令看着对、行为不对"的失败比直接报错更难查; 真正的工具链缺口 (只有 toybox 一套, `grep -P` / `sed -i` / `find -printf` 都缺) 换 shell 也治不了。发一个真 bash 只贵 2.6 MB, 而 APK 已经 195 MB。

| 对象 | 归一后 | patchelf 前 | patchelf 后 |
| :-- | :-- | --: | --: |
| bash 5.3.15 | `liblwbash.so` | 880,368 | 1,132,857 |
| libandroid-support | `liblwandroidsupport.so` | 20,736 | 67,865 |
| libreadline.so.8 | `liblwreadline.so` | 321,896 | 476,529 |
| libncursesw.so.6 | `liblwncursesw.so` | 384,496 | 405,537 |
| libiconv.so | `liblwiconv.so` | 1,083,304 | 1,116,049 |
| ripgrep 15.2.0 | `liblwrg.so` | 4,868,920 | 4,918,377 |
| libpcre2-8.so | `liblwpcre2.so` | 489,384 | 528,449 |

依赖链是 `bash → libandroid-support / libreadline / libiconv`, `libreadline → libncursesw`, `rg → libpcre2-8`, 全部只落回 `libc`; 每个对象的 `p_align` 都是 `0x4000`, patchelf 会让文件涨 3%~29% (和当年 Node 那批一样)。Termux 的 `patchelf` 0.19.1 直接在设备上跑, 产物 adb pull 回 `jniLibs`。

**`lib*.so` 没法同时是 PATH 认识的名字**, 所以 fork 侧加了两个环境变量缝加一个 bash 启动文件:

| 变量 | 谁读 | 语义 |
| :-- | :-- | :-- |
| `DSH_BASH` | `bash-local` 的 `shellExecutable()` | 设了且非空就用它, 否则回退 PATH 上的 `bash` |
| `DSH_RG_PATH` | `tool-fs-search` 的 `resolveRgPath()` | 设了且非空就用它, 否则走原来的 pkg sidecar / `@vscode/ripgrep` |
| `BASH_ENV` | bash 自己 | 指向 `filesDir/shell-env.sh`, 里面用**函数**(不是 `exec`)把 `bash` / `rg` / `node` 接回 nativeLibraryDir —— 否则模型在 shell 里敲 `rg` / `node` 是 command not found, 而 `exec` 会让 `node -e x && echo ok` 丢掉 `echo` |

`SHELL` 也跟着指向随包发的 bash (拿不到才退回 `/system/bin/sh`)。

**真机验证 (2026-09-18, 一轮会话四条全过)**:

| 请求 | 模型贴回的原文 |
| :-- | :-- |
| Bash: `v=abc; echo "${v^^}-{1..3}-$BASH_VERSION"` | `ABC-{1..3}-5.3.15(1)-release` (引号内的 `{1..3}` 不展开是对的, 说明是真 bash 不是 mksh) |
| Bash: `node -e ...; rg --version` | `android v24.18.0` + `ripgrep 15.2.0` |
| Grep 工具搜 `LW-STEP2` | `Found 1 match` + `step2-probe.txt` + `Line 1: LW-STEP2-OK-7K3M9Q` |
| Glob 工具列 `*.txt` | `step3-probe.txt` / `step2-probe.txt` / `apk-check.txt` |

**附件那两处 `link()`** 也打了同样的退路 (合成一个 `publishExclusive`), 验证只到单元测试: `vitest run packages/attachment/attachment-local` 93 通过、`store.ts` 覆盖 100%, 用注入的假 `link`(EACCES) 复现安卓的拒绝。**设备侧还没验**, 因为走附件那条链会先撞上另一件事 —— `sharp` 没有安卓 prebuild (惰性化之后 import 不会挂, 但一读图就抛), 等 Kotlin `Bitmap` 后端落地时一起验。

### APK 体积跳水的真凶: 增量打包会留死字节 (2026-09-18)

加上 bash 与 rg 之后 APK 报 **274,231,741 字节** (+82.5 MB), 但 jniLibs 只多了 8.6 MB、压缩后不到 4 MB, 数字对不上。用 `build/apk-inspect.mjs` 走一遍 zip 中央目录:

```
file=274231741 entries=166 cdOffset=274216857
sum entry data end=274212761 totalGap=79221775      ← 79 MB 不属于任何 entry
gaps > 64 KiB:  78678847  before res/color/vector_tint_color.xml
```

**结论: 那 79 MB 是 `packageDebug` 就地改写上一次的 APK 留下的死字节** (中央目录里没有任何条目指向它)。把 `app/build/outputs/apk/debug/app-debug.apk` 删掉再 `assembleDebug`, 同一个构建立刻变成:

```
file=195022322 entries=166 totalGap=12356          ← 正常对齐填充
```

也就是 bash + rg 的真实代价是 **+3.1 MB**。以后**体积数字对不上就先删输出再打包**, 别急着怀疑依赖; 要核对就 `node build/apk-inspect.mjs <apk>`, 看 `totalGap` 是不是几 KB 量级 (那个脚本是 throwaway, 在 `build/` 里, 不进 git)。

### 第 2 步的真机实测记录 (2026-09-18, 按发生顺序)

**已经通了的部分:**

- 惰性化补丁落进 submodule 后重新 `build:official` + pack, **产物里 eager 的 koffi / node-pty / sharp 共 0 处** (`tools/pack-host.mjs` 打完包后用 grep 复核过)
- `tools/push-host.mjs` 把 377 MB 的树推进 app 沙盒 (`files/host`), 然后启动 app: `adb shell ps -A` 里能看到 **`libnode.so` 是 app 进程的子进程**, `DshHost` (logcat tag) 抓到就绪行
  ```
  DshHost: dsh web: http://127.0.0.1:3080/?token=...
  ```
  这一行**只在加载器整棵树 settle 之后**才打印, 所以它同时证明了 **152 条 entry 全部加载成功** — 六个惰性化补丁确实解决了问题
- 从 Termux 侧 curl 同一个 host: 根页面 **200 / 28510 字节且带 `__DSH_BOOT__`**, client bundle **200 / 20809**, 资源 **200 / 559677** — 服务端链路完全正常
- app 侧 UI 也对: 就绪前显示状态与日志面板, 就绪后切到 WebView

**"WebView 白屏"是假警报**: 之前几张白屏截图都拍在 shell 还在 boot 的空档, 真正的证据是 `onPageFinished` 之后的探针 `DshWebView: shell {...}` 报出 `rootChildren:1` 与 DOM 349 KB, 界面文本正是 dsh 的欢迎页与工作区选择器; 顺带也证明 cookie 没问题 (`onReceivedHttpError` 一条都没报, 说明重定向后的 `/` 是 200 而不是 401)

**这段诊断代码留着有用**: `ui/HostScreen.kt` 的 `WebViewClient` + `WebChromeClient` (tag `DshWebView`) 会打印 HTTP 状态码 / 加载错误 / console, `onPageFinished` 还会跑 `SHELL_PROBE`; **怀疑 GUI 没起来就看这个 tag, 不要只看截图**

**两个必须记住的坑:**

1. **能在 Termux 里被 `-o /tmp/...` 骗到** Termux 没有 `/tmp` (它的临时目录是 `$PREFIX/tmp`), `curl -o /tmp/x` 直接失败、什么都不下载, 于是 `%{size_download}` 报 0 — 我因此在"页面是空的"上浪费了好几轮。**Termux 里写临时文件一律用 `$HOME` 下自建的目录** (脚本见 `build/lw-probe/probe-app-real.sh`)
2. **启动时会弹一次 "打开方式" 选择框 (ACTION_VIEW + BROWSABLE)** logcat 显示它由 **app 的主进程**发起 (不是 node 的子进程), 时间在就绪行之后约 180ms。`--no-open` 确实生效 (`DshHost` 日志里没有 `opening the default browser` 那行), 所以不是 dsh 的浏览器交接; 怀疑是 WebView 对某个 `window.open` / `_blank` 的默认处理, 待查 (它不阻塞 GUI, 但会挡住画面) — **2026-09-19 复现不了, 处理方式见本文最后一节**

**WebView 附件与下载 (2026-09-18)**: `onShowFileChooser` 真机验过 — 临时往页面注入一个全屏按钮, `adb shell input tap` 让它去点 `<input type=file>`, logcat 出现 `DshWebView: file chooser`, 顶层 activity 变成 `com.google.android.documentsui/.picker.PickActivity`, Back 取消后回到 MainActivity, 探针已删; 下载接 `DownloadListener` → `DownloadManager` 并带上 `CookieManager.getCookie` 的 Cookie (下载器进程看不到浏览器的 cookie), `blob:` 那类见下一步第 3 条

### 端到端会话验收 (2026-09-18 晚, 第 2 步的收尾判据)

在 GUI 里走完「选目录 → 建 workspace → 发一句话 → 模型写工作区文件」:

- 工作区注册成 `/storage/emulated/0/DSH/step2-check` (`files/dsh-home/storages/workspace.json` 里能查到 path 与 sessionIds, 目录权限 `drwxrws---` 组 `media_rw`)
- 模型经 GUI 建了两个文件, **内容是我临时编的随机 token**, 所以它不可能是猜的:

  ```
  /storage/emulated/0/DSH/step2-check/step2-probe.txt  →  LW-STEP2-OK-7K3M9Q
  /storage/emulated/0/DSH/step2-check/step3-probe.txt  →  LW-STEP3-OK-Q4W8Z2
  ```

  两处都用 `adb shell cat` 从**设备侧**独立复核过, 不是看界面截图
- 会话日志也落了盘: `files/dsh-home/sessions/--storage-emulated-0-DSH-step2-check--/<session>/session.v3.jsonl.zstd` (332 字节, zstd 帧)
- 顺带证明 GUI 的**写设置**这条路也通: 点掉内测声明之后 `files/dsh-home/settings.yaml` 里出现 `ui-onboarding: welcomeNoticeVersion`
- 驱动方式是 `build/cdp.mjs` (一次性 harness, 不进 git): `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>` 之后用 CDP 直接 `Runtime.evaluate` / `Input.dispatchTouchEvent` / `Input.insertText`, 比盲点坐标可靠得多 — 前提是 debug 构建 (`WebView.setWebContentsDebuggingEnabled` 默认开)
- **一条要记住的观察**: 模型在总结里声称"我自己给 fs-local 打了回退补丁", 但那个文件的 sha256 与 fork 构建出来的完全一致 (`fb3a8f73…`), mtime 也停在推送时刻, 所以**那句话是它编的**, 真正生效的是推送上去的 fork 补丁。核对"agent 说自己改了什么"要看文件哈希, 不要信叙述

### WebView 里 viewport unit 会变成 0 (2026-09-18, 已修)

**症状**: 内测声明弹窗只画出一张空卡片, 只有标题, 正文与「继续」按钮都不见了。DOM 里文本齐全 (`document.body.innerText` 有全部段落), 但 `getComputedStyle(那个 .content).maxHeight` 是 **`0px`** —— 也就是说 `100vh` 解析成了 0。

**定位**: 在页面里插一个 `height:100vh` 的探针 div, `100vh` / `100svh` / `100dvh` / `100lvh` **全部是 0**, 而 `innerHeight` (758) / `clientHeight` (758) / `visualViewport.height` (758.4) / `100vw` (384) 都对; `about:blank` 上同样复现, 所以不是 dsh 的 CSS; reload、`Page.navigate`、`Emulation.setDeviceMetricsOverride` 都改不动它。

**根因**: **Compose 的 `AndroidView` 不设 `LayoutParams`**, 于是 WebView 拿到的默认值是 `WRAP_CONTENT`, 而一个处于该状态的 WebView 会把 viewport unit 一律算成 0 —— 与 Compose 里 Modifier 给它的实际尺寸无关, 也和它看起来"铺满"无关。加上

```kotlin
layoutParams = ViewGroup.LayoutParams(
    ViewGroup.LayoutParams.MATCH_PARENT,
    ViewGroup.LayoutParams.MATCH_PARENT,
)
```

之后 `SHELL_PROBE` 里 `vh: 758.4000244140625` (修之前是 `vh: 0`)。参考: [AndroidのWebViewでViewport Unitを使いたければLayoutParamsを設定すべし](https://qiita.com/ryo_mm2d/items/48c720326f51d1122799)(其结论: View 布局与 Compose 都一样, `WRAP_CONTENT` 会让 viewport unit 变 0)。

**为什么这条对我们是致命的**: dsh 客户端的 CSS 里有 11 处用 `100vh` / `100dvh` 量弹窗、菜单、设置根与目录选择器(`OnboardingModal` `SettingsRoot` `DirectoryBrowser` `Menu` `RiskConfirmation` …), 全部会塌成 0 高 —— 修之前连「选择工作区」那个目录选择器都是空面板, 第 2 步根本走不完

`SHELL_PROBE` 现在会顺带报 `innerHeight` 与 `vh`, 以后再遇到"界面塌了"先看这两个数

### 安卓禁止硬链接: 三处发布 + 一处未补 (2026-09-18, 已修)

dsh 的不少地方用 `link()` 做**不覆盖**的原子发布 (`link` 在目标存在时返回 `EEXIST`), 而安卓**在应用数据目录里禁止硬链接**, `/sdcard` 的 FUSE 挂载也拒:

```
avc: denied { link } for name="session.v3.jsonl.zstd.<hex>.tmp"
  scontext=u:r:untrusted_app:s0 tcontext=u:object_r:app_data_file:s0 tclass=file
```

`run-as <pkg> sh -c 'ln a b'` 一样 `Permission denied`, 所以是平台策略而不是我们的路径问题。踩到的顺序正好是三层功能:

| 报错 | 位置 | 症状 |
| :-- | :-- | :-- |
| `link '.../session.v3.jsonl.zstd.<hex>.tmp' -> '.../session.v3.jsonl.zstd'` | `session-persistence-jsonl/src/index.ts` 的 `materializePosix` | **建会话就失败**: GUI 上直接 `本轮运行失败 EACCES …` |
| 同上 (另一处) | 同包 `generation.ts` 的 `publishCurrentExclusive` | 会话代次发布 / 迁移时 |
| `cannot write ".../x.txt": EACCES … link '….tmpdir/x.txt.tmp' -> '.../x.txt'` | `fs-local/src/fsio.ts` 的 `writeFileAtomic` (只在 `createIfAbsent` 时) | **模型连新建一个文件都做不到** |

三处都改成「`link` 抛 `EACCES`/`EPERM`/`ENOTSUP`/`ENOSYS` 时退化成 `copyFile(..., COPYFILE_EXCL)`」, 判据是**错误码而不是平台**, 所以 Linux / macOS 上的行为一个字节都没变。补丁在 fork 的三个提交里 (`bbd87d627a` / `2eebfc78de` / `a01302155a`), 逐点说明在 `B:\Git\deepseek-harness\patch.md` 第 5 节。

**顺带解释了一个此前没想通的现象**: `settings.yaml` / `storages/workspace.json` 一直写得好好的 —— 它们走 `storage-json/src/atomic.ts` 的 `rename()`, 而 `rename` 在安卓上是正常的, 只有 `link` 被挡

**还没补的一处**: `attachment-local/src/store.ts` L283 / L359 也用 `link()` 发内容寻址对象与别名。附件是纯内容寻址 + 摘要校验, 硬链接只是省空间, 退路同样可以是一次拷贝 —— 但还没验过, 等用到附件(图片 / `present`)时再补

### 还不能用的两项能力: bash 与 ripgrep (2026-09-18, 待办)

模型在会话里逐个试出来的, 都记在上面那轮会话的轨迹里:

- **Bash 工具**: `Error: spawn bash EACCES`。安卓上没有 `bash` (`PATH` 的七个目录里一个都没有, 系统 shell 是 `/system/bin/sh`, 即 mksh), 而 `bash-local` 的 argv 写死 `['bash', '-c', …]`。要修的话先弄清为什么是 `EACCES` 而不是 `ENOENT` (execvp 只有在某个 PATH 候选存在但不可执行时才回 EACCES), 再让它退回 `sh`
- **Grep / Glob 工具**: `ripgrep launch failed` —— dsh 的搜索工具 spawn `rg`, 而树里没有安卓 arm64 的 `rg`。要么像 Node 那样当 native lib 发一个 `rg`, 要么给搜索换一个纯 JS 后端

这两条都不影响"模型读写工作区文件", 但在安卓上等于**没有 shell、没有搜索**, 属于第 2 步之后要补的洞

### 启动时那个「打开方式」选择框: 没能复现, 于是堵掉出口 (2026-09-19)

之前记下的现象是「启动后会弹一次选择框, 由 app 主进程发起, 就在绪行之后约 180ms」, 这一轮专门去确认, 结论是**复现不了, 而且全仓历史里没有能发起它的代码**:

- **两次冷启都没有**: `am start -S` 与 `monkey -c android.intent.category.LAUNCHER` 各一次, 每次都在就绪行之后截图, 屏幕上只有正常 GUI; 全量 logcat 里 `START u0` 只有我们自己那次启动, 没有任何 `act=android.intent.action.VIEW`
- **代码里也没有**: `git log -S 'ACTION_VIEW' --all -- app/` 与 `-S 'setSupportMultipleWindows'` 都是空的; `app/src/main` 里唯一的 `startActivity` 是 `Workspace.requestAllFilesAccess` 的两个 settings intent, manifest 里也没有 VIEW / BROWSABLE 的 intent-filter
- 所以能发起它的只剩 WebView 这一层: 页面里某个非常规 scheme 或一次 popup 会被 Chromium 交给平台, 由平台弹出「打开方式」

**做法不是继续猜, 而是把这一类出口堵掉** (`ui/HostScreen.kt`): `shouldOverrideUrlLoading` 只放行 `http` / `https`, 其余 scheme 先记一行 `blocked navigation to <url>` 再拒绝; `onCreateWindow` 记一行 `blocked popup dialog=… gesture=…` 再拒绝。**这两行日志就是探针** —— 现象要是回来, logcat 会直接指名 URL 或 popup 来源, 不必再靠猜

真机复核这次加固没有误伤: GUI 照常起 (`rootChildren:1`, `vh:758.4`), dsh 自己的导航一条 `blocked navigation` 都没触发, `onReceivedHttpError` 也是空的

### 把 Web GUI 交给别的设备 (2026-09-19, 真机验过)

fork 的定位是「部署在远程服务器上, 从任意浏览器访问」, 手机这一侧要的就是 **PC 上的浏览器打开手机里的 GUI**, 这一轮把这条路打通

**dsh 侧**: `--host` 本来就在, 只有 `--host 0.0.0.0` 被一条硬拒的安全闸挡着; 改法是**不删闸门而是加第二个旗标** `--allow-lan`, 安全默认一个字没变, 接管它的部署不必再改源码 (`patch.md` 第 7 节, fork 提交 `ebe4407efd`)。全接口绑定下已有的逻辑会把 LAN 地址算进 `/api` 的 browser-trust 栅栏, 而且**就绪行本来就多打一个 `(LAN: …)` 后缀**, 回环 URL 仍排在第一个 —— 所以 app 侧的解析和 WebView 的 URL 一个字都不用改

**app 侧**:

| 位置 | 做什么 |
| :-- | :-- |
| `host/HostSettings.kt` | 一个 SharedPreferences 键 (`lan-access`) 记住开关 |
| `host/DshHost.kt` | 按开关决定要不要追加 `--host 0.0.0.0 --allow-lan`; `remoteUrl` 从就绪行的 `(LAN: …)` 后缀里取 URL, **不自己枚举网卡**, 这样显示的地址与 host 认的栅栏是同一个 |
| `ui/HostScreen.kt` | TopAppBar 多一个「网络」面板: 开关 + 可点击复制的 LAN URL + 重启 host (dsh 只在启动时读绑定, 所以改完必须重启) |

**真机实测 (2026-09-19)**:

- 开关关着时同一行只有回环 URL, GUI 照常渲染 —— 也就是没有回归
- 点上开关后 `run-as … cat shared_prefs/littlewhale.xml` 里出现 `<boolean name="lan-access" value="true" />`, 说明开关真的落了盘
- 重启后 `netstat` 里 `31201/libnode.so` 是 `0.0.0.0:3080 LISTEN`, 就绪行变成
  `dsh web: http://127.0.0.1:3080/?token=… (LAN: http://192.168.1.103:3080/?token=…)`
- **从开发机 (另一台设备)** `curl` 该 URL: `status=200 bytes=28580` 且正文带 `__DSH_BOOT__`
- **从开发机的 Chrome 真开了一次** (browser-harness 走 CDP): 标题 `🐴 DeepSeek Harness`, `root.childElementCount=1`, `innerHeight=960`, 正文是真实 GUI (侧栏会话列表 / 工作区 `step2-check` / 模型 `DeepSeek-V41-Flash`); **侧栏能列出会话说明 `/api` 的信任栅栏也放行了**; 再点开「设置」, 面板里是真实取值而不是 `settings are unavailable in this browser`, 且 `globalThis.__DSH_CONNECTION_SERVES_REMOTE__ === true` —— 顺带把 fork 第 1 条改动在局域网上也验了一遍
- APK 195,181,350 字节 (删掉旧输出再打包, `totalGap` 正常), 相对上一版 +159 KB, 都是两个 JS 文件的改动



