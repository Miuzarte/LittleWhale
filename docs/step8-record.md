# 第 8 步记录: 端侧 OCR

> 2026-09-22 起。方案在 `docs/step8-plan.md`, 本文记**实际做出来的东西、实测数字、踩的坑**
>
> 进度: **做完了** —— 模型在 NPU 上, 认字 (前后处理) / `lw_ocr` 工具 / `lw_tap(text=…)` 的
> OCR 分支 / 设置页那段都在真机上验过了

## 1. 现在有什么

`channel/LwOcr.kt` —— app 进程里的 OCR 引擎:

- assets 里两份钉死 shape 的 fp32 ONNX (`det.onnx` 1.7 MB / `rec.onnx` 4.4 MB) 加一份词表
  (`rec_dict.txt`), 模型首次用到才解到 `filesDir/ocr/`, 判据是 **APK 文件自己的时间戳**
  (`base.apk` 每次重装都会被重写) —— 早先用文件大小当判据, 而改 `ir_version` 这种改动**大小不变**,
  于是一直在用旧文件
- NPU 走 **ORT 的 QNN EP + `enable_htp_fp16_precision=1`**, 也就是让 QNN **在设备上**把图编成
  fp16 的 HTP 图; 失败就退到同一份 onnx 的 CPU session
- `recognize(bitmap)` 一条龙: 检测输入 (letterbox + BGR + 官方 mean/std) → det → 后处理 → 逐行
  裁剪 → 识别输入 (48 高、右边补 0、`(p/255-0.5)/0.5`) → rec → CTC 贪心解码, 返回的文字与坐标
  都是**那块屏自己的像素**

`channel/OcrDetect.kt` —— DB 概率图 → 文本行框 (阈值 / 8 连通 / 按行合并 / unclip), 纯算术,
`app/src/test/` 里有 7 个 JVM 单测盯着它 (造概率图, 看框落在哪), 所以分割的正确性不靠真机碰运气

`channel/OcrDecode.kt` —— CTC 贪心解码, 词表下标 0 是 blank、最后一个是空格 (PaddleOCR 的
`CTCLabelDecode` 是这么插的, 这也是模型输出 6906 类而词表只有 6904 个字的原因)

`tools/ocr/` —— 模型那条链路: `build-models.ps1` (取模型 → 钉 shape → 写 assets)、
`rewrite_onnx.py` (只钉 shape, 另两个算子重写默认关着)、`extract_dict.py` (词表)

APK 从 186.5 MB 涨到 **264.2 MB**, 其中约 70 MB 是 `libQnnHtpPrepare.so` (HTP 的图编译器, JIT
那条路要它), 剩下的才是 ORT + QNN 运行时 (见第 5 节)。

## 2. fp32 是死路, fp16 才是

第 8 步方案里那条"必须先写一个算子重写 pass"的结论**只对 fp32 成立**, 实际试下来是这样的:

| 步骤 | fp32 | fp16 (`--float_bitwidth 16`) |
| :-- | :-- | :-- |
| `qairt-converter` → DLC | 成功 | 成功 |
| `qnn-context-binary-generator` → HTP 图 | **失败**, `"HardSigmoid.0" generated: could not create op` | **成功, 一个算子都不用改** |
| 把 `HardSigmoid` 拆成 `Mul/Add/Clip` | 下一个失败变成 `q::QNN_Gelu` (`no properties registered`) → 再拆 GELU | 不需要 |
| `--preserve_io_datatype` 想让 I/O 保持 fp32 | —— | **失败**, 它插进去的 `Convert` 过不了 HTP |

也就是说 **HTP 的 elementwise / neuron / gelu 这一族只在 fp16 (以及量化) 下注册**, fp32 图里连
`Clip` 都造不出来。所以:

- 模型不用做任何算子重写, 只要钉死 shape
- QNN 那份的 I/O 是 **fp16**, 喂进去的图要自己转成半精度 (ORT 报 "Unexpected input data type"
  就是这个), 出来的也是半精度
- 顺带说明 `--float_bitwidth 16` 之后 DLC 里 `x` 已经是 `Float_16`, 不需要再折腾

**精度**: 同一张输入下 NPU 与 CPU 的输出 `maxDiff 5.3e-4 / meanDiff 1.2e-4` (检测头输出是 0..1 的
概率图), 就是 fp16 该有的量级。

## 3. 预编译 context binary 这条路**算不对** (未解)

开发机上 `qnn-context-binary-generator --htp_socs sm8550` 出来的 `*_qnn.bin` (det 2.27 MB /
rec 2.83 MB), 用 `gen_qnn_ctx_onnx_model.py` 包成 EPContext ONNX 之后:

- 加载成功, 建 session 成功 (`disable_cpu_ep_fallback=1` 也没拦), 执行不报错
- 但**输出是一张常数图**: 0.6665 铺满 409600 个像素
- **换一组完全不同的输入, 输出一模一样** (`npuSelfDiff = 0`) —— 图根本没吃输入
- 同一张输入在 CPU 上是 `0.0000..0.0020`, 而 CPU 上喂全零/全一/噪声也都是 ~0, 所以那个 0.6665
  既不是"零输入的结果"也不是任何合理的输出

排掉的: 包装 ONNX 的 wiring 是对的 (EPContext 节点 `inputs ['x'] outputs ['fetch_name_0']`, 与图
的 I/O 一致); 图本身有输入 (`graphInputs x QNN_DATATYPE_FLOAT_16 [1,3,640,640]`); logcat 里
没有任何 QNN/fastrpc 报错。

**没排掉的**: `--htp_socs` 这条 "offline cache" 路是不是本身就不对 (它用的是 x86 上的 HTP 模拟器
准备图)。下一轮可以试的两条: (a) 换成老式 `--config_file` + `dsp_arch v73` / `soc_model 43`;
(b) **让 ORT 自己导出** (`ep.context_enable=1` + `ep.context_file_path`), 它写出来的就是它自己能读
的 `*_ctx.onnx` + `*_qnn.bin`, 顺便还能把 70 MB 的 `libQnnHtpPrepare.so` 从 APK 里拿掉。那条路更
值得试, 因为它省的是体积, 而 (a) 只是把同样的图用另一种方式编出来。

**在那之前 JIT 是默认路径**, 代价是首次建 session 要等它编译 (实测 **2.2 s**), 之后就好了。

顺带两个包装 ONNX 时踩的坑 (留着, 因为走 (b) 时会再遇到):

- pip 的 `onnx` 包把 `ir_version` 盖成 14, 而 **ORT 1.26 只认到 13** (`Unsupported model IR version`)
- 它同时把 opset 盖成 28, ORT 只保证到 26 (`Opset 28 is under development`)

## 4. 实测数字 (小米 13 / SM8550 / HTP v73, debug 构建)

`burst` = `htp_performance_mode=burst`, 也就是 Inferencer 记过的那张"性能票":

| 路径 | det 执行 | rec 执行 | 建 session |
| :-- | :-- | :-- | :-- |
| **NPU + burst** | **11 ms** (总 15, 装箱 4) | **9 ms** | 2.2 s (JIT 编译) |
| NPU + `default` (不投票) | 28 ms | 25 ms | —— |
| CPU | 48 ms (总 52) | **6 ms** | 0.3 s |

- **性能票在这里同样是 2.5 倍的差** (11 vs 28 ms), 与 `goApp/PLAN.md` 的 P4 那张表 (execute
  15.8 → 5.5 ms) 是同一个现象: 不投票时 DSP 跑在 DCVS 默认低频档上。代码里默认就是 `burst`
- det 对 CPU 是 **4.4 倍**, 而 **rec 反而比 CPU 慢** (9 vs 6 ms): 一张 48x320 的图算 0.16 GMAC,
  单次调用的固定开销 (fastrpc 往返 + QNN execute) 就有 5-8 ms, 把计算量压没了。所以
  **整屏 OCR 的收益主要来自 det**; 要让 rec 也划算就得**批量**: 重导一个 batch 8/16 的 rec, 一次
  调用认 8-16 行
- 装箱 (float → fp16 + 写 direct buffer) 一开始占 57 ms, 原因是逐元素 `buffer.putShort` 撞直接
  缓冲区的边界检查 + `android.util.Half.toHalf` 在 debug 下不内联; 改成"先写 `ShortArray` 再整块
  `asShortBuffer().put`" + 自己做位型转换之后降到 **4 ms**
- 真实路径上装箱还会更便宜, 因为那时是边 resize 边写半精度, 不用先铺一份 float

## 5. 随 APK 发的东西

```kotlin
implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0")
implementation("com.qualcomm.qti:qnn-runtime:2.46.0")
```

- **`libQnnHtp.so` 的 `DT_NEEDED` 只有 libc/libm/libdl/liblog**, 不依赖 `libQnnHtpPrepare.so` 也
  不依赖 `libQnnSystem.so` (自己用 python 读的 `.dynamic`); 依赖 `libcdsprpc.so` 的是
  `libQnnHtpV73Stub.so`, 所以 manifest 里那条 `<uses-native-library name="libcdsprpc.so">` 是必须的
- 裁掉了 V68/V69/V75/V79/V81 的 skel+stub 与 `libQnnGpu*` / `libQnnDsp*`
  (`packaging { jniLibs { excludes } }` 在 AAR 带的 .so 上**确实生效**, 实测 8 个变 4 个);
  `libQnnHtpPrepare.so` **必须留** —— JIT 要用它
- **设备 `/vendor/lib64` 里那套 QNN 库 app 用不了**: `public.libraries.txt` 里没有 `libQnn*`, 而
  Android 16 的 `/linkerconfig/ld.config.txt` 里 default namespace 也不含 `/vendor/lib64`

## 6. 认字: 实测与踩的坑

真实读数 (小米 13, 系统设置主页, 1080x2400):

```
25 lines read on displayId 0 (1081ms to capture, 101ms to find the text, 11ms per line, on NPU)
  [59,29,185,91]   "19:40"           score 0.96
  [59,284,267,410] "设置"            score 1.00
  [70,475,503,545] "Q搜索系统设置项"  score 0.95
  [191,1035,375,1107] "WLAN"         score 1.00
  [654,1044,1007,1104] "OpenWrt_C210 >" score 0.97
  [193,1198,309,1266] "蓝牙"          score 0.98
  [822,1197,1011,1263] "已开启>"      score 0.90
  ... 移动网络 / 设备互联 / 个人热点 / 已关闭> / VPN / 更多连接 / 系统个性化 / 锁屏
```

**全对**, 包括中英混排、`已开启>` 这种行尾箭头、以及状态栏的时钟。稳定态 `capture 330ms +
det 23ms + rec 10ms × N` —— 25 行约 0.6 秒。

三个把结果搞错的坑, 都值得记:

1. **解码用的是输入形状而不是输出形状**: 我拿 `rec.inputInfo` 的 `[1,3,48,320]` 当"步数, 类别数"
   去解码, 而输出是 `[1,40,6906]` —— 结果是垃圾。判据是**输出**的 shape
2. **rec 的导出本来就是 softmax 之后的概率** (实测每行加起来 1.0), 我又套了一层 softmax, 于是所有
   置信度都落到 1e-4 那个量级, 被 0.35 那道闸**全部滤掉**, 表现为"检出 34 个框但一行都没认出来"
3. **图标会被认成单个汉字** (`C` / `8` / `心` / `A`, 分数 0.5-0.7): 单字且低于 0.75 的一律丢掉,
   真的有意义的单字 (列表序号) 分数在 0.9 以上, 留得住

调这些的时候**在开发机上用 python 跑了一遍同样的流水线** (`build/ocr-spike/pipeline.py`, 与 Kotlin
同一套参数), 一次就读出了 `19:25` / `WLAN` / `OpenWrt_C210 >` —— 于是"到底是模型不对还是我的代码
不对"这个问题当场就分清了, 不用每次等装机。这条值得当成方法留下来

### 熄屏是**静默失败** (2026-09-22 实测踩到)

一次模型回合里 `lw_ocr` 读到了设置页、`lw_tap(text="蓝牙")` 也报"已按下 (243,1250)", 但屏幕没动。
重放同一个坐标**又成功了**。原因: 屏幕上那一刻已经睡着 (`mWakefulness=Dozing`), 而

- **`screencap` 在熄屏时交的还是它最后画的那一帧**, 于是读屏读到的是一个已经不在屏幕上的界面
- **注入的触摸唤不醒屏**, 所以那一下点在了空气里

两个都不会报错。所以桥的 `ocr` 与 `tap` 答案里多了一个 `screen: on|dozing|off`, 工具那侧看到不是
`on` 就直接说清楚 (分别是"这些字可能已经不在屏幕上"与"这次点击送到了没人听的屏上, 先发 WAKEUP"),
`lw_tap(text=…)` 的读屏分支则**直接不点**

### 工具面

- **`lw_ocr(displayId)`** → 每行 `[框] "文字" score 中心`, 坐标就是 `lw_tap` 用的那套
- **`lw_tap(text=…, source: auto|a11y|ocr)`**: `auto` (默认) 先问无障碍树, 树里**没有这个名字**才
  退到读屏; 树的答案是 `ambiguous` (有两个同名控件) 时**不**退 —— 读像素只会是第三个猜测
- 读屏按名字点的时候, 落点是**框中间 60% 里随机取的**, 不是正中心: 一行的正中心常常是里面的
  label 而不是整行, 而每次都落在同一个像素上本身就是个能被认出来的模式 (实测 `蓝牙` 那行
  `[193,1198,309,1266]` 点到了 `243,1250` 而不是中心的 `251,1232`)

真机回合 (模型自己走): 调 `lw_ocr` 拿到 25 行 → 调 `lw_tap(text="蓝牙", source=auto)` → 无障碍服务
当时是关的, 它答"读不到树", 于是退到读屏, 按文本命中 `蓝牙` 并落在随机点上

### 设置页

「OCR」一段显示计算单元 (`NPU (HTP) · SM8550` / `CPU` / `未加载` / `模型缺失`)、一行说明、上次耗时,
以及一个**自检**按钮 (加载模型并各跑一遍, 顺带预热 —— NPU 第一次建 session 要 2.2 秒)

## 7. 还没做

1. **rec 批量**: 重导 batch 8/16 的 rec, 让 N 行只要一次调用 —— 现在 rec 每次调用有 5-8 ms 固定
   开销, 25 行就是 250-400 ms, 这是整条路里最大的一块 (第 4 节)
2. **体积**: 试 ORT 自己导出 ctx (`ep.context_enable=1`, 第 3 节), 成了就把 70 MB 的 Prepare 拿掉
3. **det 的输入形状**: 现在硬塞进 640x640, 竖屏 1080x2400 只用到中间 288 宽 (45%), 字被缩到
   1/3.75; 换成非正方形 (比如 480x1088) 能让屏幕上的字在模型眼里大 1.6 倍, 值得试
4. **量化**: 端到端数字出来之前不做; 现在瓶颈是每行的固定开销, int8 治不了它 (批量才治)

## 8. 复现命令

```powershell
# 模型 (只需要 python + onnx + pyyaml)
pwsh -File tools/ocr/build-models.ps1

# 单测: 后处理是纯算术, 在 JVM 上验
.\gradlew.bat :app:testDebugUnitTest --tests '*OcrDetectTest*'

# 装机 (app 必须先起来一次, 桥的 context 是 MainActivity 挂上去的)
.\gradlew.bat :app:assembleDebug
adb install -r -t app\build\outputs\apk\debug\app-debug.apk
adb shell am start -S -W -n io.github.miuzarte.littlewhale/.MainActivity
$env:ANDROID_SERIAL='192.168.1.103:5555'

# 认字 (主屏)
pwsh -File tools/lw-bridge.ps1 -Method ocr -Params '{"displayId":0}'
# 诊断: 合成输入跑两个模型, 报计算单元 / 耗时 / NPU 与 CPU 的输出差
pwsh -File tools/lw-bridge.ps1 -Method ocrProbe -Params '{"rounds":20,"compare":true}'
pwsh -File tools/lw-bridge.ps1 -Method ocrProbe -Params '{"rounds":20,"perf":"default"}'  # 性能票
pwsh -File tools/lw-bridge.ps1 -Method ocrProbe -Params '{"rounds":20,"force":"cpu"}'     # CPU 对照

# 让模型自己走一遍
pwsh -File tools/lw-device-turn.ps1 -Task "Call lw_ocr on displayId 0, then press the line whose text is 蓝牙."

# 开发机上的参照实现 (与 Kotlin 同一套参数, 用来分清"模型不对"与"我的代码不对")
python build/ocr-spike/pipeline.py --image build/ocr-spike/shot.png
```

第 3 节那条 ctx 路的原始命令留在 `docs/step8-plan.md` 与 `build/ocr-spike/` (gitignored) 里,
QAIRT 2.46 整个包也解在那里 (约 1.1 GB, 免得重下重解)。
