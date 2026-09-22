# 第 8 步方案: 端侧 OCR

> 2026-09-22 调研完成。目标: 给**引擎自绘界面**兜底 —— Unity / Cocos / 画布这类整屏只有一块
> `SurfaceView` 的应用, 无障碍树里没有值得动手的节点 (第 7 步实测过), 于是"屏幕上写着什么、
> 写在哪"只能从**像素**里读出来
>
> 本文是**方案**不是记录。实测数字与探针结论都在下面标了出处, 动手后另写 `step8-record.md`

## 0. 一句话结论

**用 PaddleOCR 的 PP-OCR 官方 ONNX 模型 + ONNX Runtime 的 QNN EP, 跑在 app 进程里, NPU 优先、CPU 兜底**;
模型走 assets 随 APK 发, NPU 那份是**在开发机上用 QAIRT 2.46 预编译好的 context binary**;
设置页显示"计算单元是 NPU 还是 CPU"靠 `session.disable_cpu_ep_fallback=1` 建 session 成不成来判, 不猜

**但有一个必须先做的坎**: PP-OCR 的 ONNX 导出里有 `HardSigmoid` 与 `Erf`, 这两个**不是 QNN 算子**,
HTP 编译到一半会失败 (本机已复现)。所以要加一个**转换前的 ONNX 重写 pass**, 见第 3 节

## 1. 为什么是这条路: 先把被否的排掉

| 路 | 判据 |
| :-- | :-- |
| **ML Kit 中文 (bundled)** | 模型真在 AAR 里, 无 GMS 也能跑 (实测 AAR 的 manifest 没有 `uses-library`), 但**闭源**, 且它的条款写明 API 会"不时联系 Google 服务器"并上报 metrics —— 与"全离线"直接冲突 (Ente 也是因为这个否掉的)。留作**对照基准**, 不作交付 |
| **Tesseract** | `chi_sim` 只有 2.35 MB 很诱人, 但中文 UI 短文本是它的弱项, 没有 NPU 路, 而且它那一整套版面分析是按文档页设计的。准确率地板, 不是选项 |
| **LiteRT `CompiledModel` + NPU** | Google 官方文档确实列了 SM8550, 但 `Accelerator.NPU` 走 **Play AI Packs / Play Feature Delivery**, 侧载 APK 拿不到 dispatch 库, 且社区实测它比 delegate 慢 (LiteRT issue #7858: 29ms vs 5ms)。**侧载这个前提直接否掉它** |
| **LiteRT + `qnn-litert-delegate`** | 是普通 Maven 依赖, 也有 `checkCapability()` 这个最好的能力探测 API, 是**像样的第二选择**。但它要求模型是 TFLite, 而 PP-OCR 的官方发布是 ONNX / Paddle —— 选它就要自己转 TFLite 并接受精度损失, 没有理由为此绕一圈 |
| **SNPE** | 设备上 `libSNPE.so` 甚至是 public library (能按名字 dlopen), 但 Maven 上**没有** SNPE 运行时, 再分发的条款只在 SDK 里, 且 Qualcomm 自己的新工具链全指向 QNN |
| **ExecuTorch Qualcomm backend** | 能用 (`get_soc_to_arch_map()` 里 SM8550 → V73), 但**没有 Maven 产物、没有 Java/Kotlin API**, 文档流程是把 libs push 到 `/data/local/tmp` 跑命令行。要自己写 JNI, 不划算 |
| **RapidOCR 的 Android 仓库** | 最后一次发版是 2023-02, 模型还是 PP-OCRv2/v3 时代。它的**模型与文档**可以当参考, 仓库不要用 |
| **各家国产离线 OCR SDK** | 都要按包名做授权激活, 不能随 APK 再分发 |

## 2. 模型: PP-OCRv6 tiny (首选) / PP-OCRv5 mobile (备选)

PaddleOCR 仓库与权重都是 **Apache-2.0**, 官方在 HuggingFace 上直接发 ONNX 导出, 不再需要 Paddle2ONNX 这一跳:

| 模型 | det | rec | 输入 | 备注 |
| :-- | :-- | :-- | :-- | :-- |
| **PP-OCRv6_tiny** (首选) | 1.70 MB | 4.26 MB | `[1,3,H,W]` / `[1,3,48,W]` | det 242 节点 / rec 219 节点, opset 14 / 11 |
| PP-OCRv5_mobile (备选) | 4.60 MB | 15.77 MB | 同上 | det 526 / rec 1019 节点, opset 11 / 7, 动态算子和 `Shape/Slice` 多得多 |
| PP-OCRv6_small | 9.42 MB | 20.18 MB | 同上 | 准确率更高, 体积也更大 |

**为什么首选 v6 tiny**: PaddleOCR 自己的准确率表里有一列 **Screen** (他们的多场景自测), `v6_small 79.7` /
`v6_tiny 71.2` / `v5_server 68.1` / **`v5_mobile 57.6`** —— 我们认的就是屏幕上的字, 这一列比 W-Avg 有意义得多,
而 tiny 的 6 MB 比 v5_mobile 的 20 MB 还小

两个模型都是**动态 shape**, QNN **不支持动态 shape** (官方原话: dynamic shapes must be fixed to a specific value),
所以转换时要钉死: det 按 `1,3,640,640` (或 960x960), rec 按 `1,3,48,320`。长文本行按 PaddleOCR 的老办法
**等比缩到 320 宽再 pad**, 而不是分块

rec 的词表在模型仓库的 `inference.yml` 的 `PostProcess` 段里, **要一起发**, 解码时用

**中文不需要额外处理**: 中英数字一套模型, 词表 18385 (v5) / 6906 (v6 tiny) 字符, CTC 贪心解码即可

## 3. NPU 这条路真正的坎: 算子表 (本机实测)

### 已复现的事实

在开发机上用 **QAIRT 2.46.0.260424** (就是 `B:\Git\Inferencer\goApp\tmp\qairt-2.46.0.260424.zip`, 1.74 GB, 本地已有)
跑通了前两步:

```powershell
# 1. ONNX -> DLC, 两个模型都成功 (det 1.82 MB / rec 4.30 MB 的 dlc)
$env:PYTHONPATH='<qairt>\lib\python'; $env:PYTHONIOENCODING='utf-8'   # 不设就是 GBK 下的 UnicodeEncodeError
python <qairt>\bin\x86_64-windows-msvc\qairt-converter -i v6\det.onnx -o det.dlc `
       --source_model_input_shape x 1,3,640,640 --source_model_input_layout x NCHW
# rec 用 -s x 1,3,48,320

# 2. DLC -> HTP offline cache (sm8550)
qnn-context-binary-generator.exe --backend QnnHtp.dll --dlc_path det.dlc `
       --binary_file det_v73.bin --output_dir out --htp_socs sm8550
```

第三步**失败**, 报错精确到算子:

```
[ ERROR ] <E> "HardSigmoid.0" generated: could not create op
[ ERROR ] <E> Failed to finalize graph (id: 1) with err 1002
```

### 为什么

SDK 自带的算子表 (`docs/QAIRT-Docs/QNN/OpDef/SupportedOps.html`, 主表在 `MasterOpDef.html`) 里:

- `HardSwish` / `Gelu` / `Tanh` / `Sigmoid` **是** QNN 算子, HTP 列都是 YES
- `HardSigmoid` 出现 **0 次**, `Erf` 出现 **0 次** —— 它们**根本不是 QNN 算子**

而两个 PP-OCR 模型都大量用它们 (v6 det: `HardSigmoid` x13 + `Erf` x13, v5 det: `HardSigmoid` x34, v5 rec: x30):
PPLCNetV3 的激活被 Paddle 导出成了 `HardSigmoid` + `Mul` 的分解形式, 而 QNN 只认融合好的 `HardSwish`;
`Erf` 是 GELU 的分解形式 (QNN 只认 onnx >= 1.15 的融合 `Gelu`)。ONNX 前端会把它们转成某种 IR,
但 HTP 的 graph builder 造不出对应 op

### 所以动手第一件事: 写一个转换前的 ONNX 重写 pass

`tools/rewrite-ocr-onnx.mjs` (或 python), 做两件事:

1. `HardSigmoid(x, alpha=1/6, beta=0.5)` → `x * (1/6) + 0.5` 再 `Clip(0, 1)`
   (`Clip` 在 QNN 里映射到 `ElementWiseNeuron`, HTP 支持; `Mul`/`Add` 更不用说)
2. `Erf` 的 GELU 分解 (`x * 0.5 * (1 + erf(x/sqrt(2)))`) → 单个 `Gelu` 算子 (把 opset 提到 20),
   或退到 tanh 近似 (QNN 有 `Tanh`)
3. 顺便把输入钉成静态 shape, 把 `Shape/Slice/Reshape` 那一路常量折叠掉

**这一步做完才谈得上"能不能上 NPU"**, 也是第 7 节验证清单的第 1 条

### 别的走法 (如果重写救不回来)

- **先量化再转**: Qualcomm AI Hub 社区那份 PP-OCRv5 是 **int8** (det 156/156、rec 219/219 全在 NPU 上,
  SM8750 上 1.23 / 0.99 ms), 说明 int8 这条路是被验证过的。int8 量化会改变算子形态
  (激活会被吸收进 conv), 很可能绕开 `HardSigmoid`。代价是要一份校准数据与 `qairt-quantizer` 的流程
- **`--htp_socs` 换成老式 `--config_file` + `dsp_arch v73`**: 本机这次走的是 offline cache 那条路
  (它在 x86 上模拟 HTP 做 graph prepare), 换一条路值得试一次, 但**报错来自 HTP 的 graph builder 本身**,
  所以大概率是同一个结论

## 4. 运行时: ONNX Runtime + QNN EP

### 为什么必须自带 QNN 库 (实测)

设备 `/vendor/lib64` 里 `libQnnHtp.so` / `libQnnHtpV73Stub.so` / `libQnnSystem.so` / `libQnnHtpPrepare.so` 都在,
`/vendor/lib/rfsa/adsp/libQnnHtpV73Skel.so` 也在, **但 app 一个都 dlopen 不到**:

- `/vendor/etc/public.libraries.txt` (13 条) 里有 `libcdsprpc.so` / `libadsprpc.so` / `libSNPE.so`, **没有 `libQnn*`**
- Android 16 的 linker 配置搬到了 `/linkerconfig/ld.config.txt`, default namespace 的
  `search.paths` 只有 `/system/lib64` + `/system_ext/lib64`, `permitted.paths` 不含 `/vendor/lib64`

所以 QNN 那几支 .so **必须随 APK 发** (顺带也就没有"vendor 版本与我们的 stub/skel 对不上"的问题)

### 再分发是允许的

`com.qualcomm.qti:qnn-runtime` 在 **Maven Central** 上 (2.26.0 → 2.50.0), AAR 里带 `LICENSE.pdf` + `NOTICE.txt`,
授权条款: 可以 **以目标码形式、作为你自己应用的一部分** 分发, 免版税, 但**不许单独分发**这份 SDK。
所以: 随 APK 发可以, 别把 libs 单独放出去; 应用里保留它那份 LICENSE/NOTICE

### 版本与两代 artifact

| | 坐标 | 本机验证 |
| :-- | :-- | :-- |
| **首选 (保守)** | `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0` + `com.qualcomm.qti:qnn-runtime:2.46.0` | **是** —— `B:\Git\Inferencer` 在这台小米 13 上建过 session 并真跑出检测框, 组合与 `ADSP_LIBRARY_PATH` 的写法都在它的 `androidApp/PLAN.md` 里 |
| 以后可换 | `com.microsoft.onnxruntime:onnxruntime-android` + `com.qualcomm.qti:onnxruntime-android-qnn:2.6.0` (Qualcomm 的 plugin EP) + `qnn-runtime:2.50.0` | 否。新路线是官方方向 (legacy 那条注释里写着以后不再维护), 但一次没在本机验过, **不在第 8 步里做** |

**选首选的理由是证据而不是新**: 同一个 ORT 版本、同一个 QNN 版本、同一台设备已经把
`CreateDevice → SetupBackend → Session successfully initialized` 打出来过, 连那次踩的坑
(必须声明 `uses-native-library libcdsprpc.so`、`ADSP_LIBRARY_PATH` 要在建 session 前设) 都有现成的

### 三个必须的构建项

```kotlin
// 1. manifest: 不声明这个, QNN 的 QnnDevice_create 会因为 libcdsprpc.so 解析不到而失败,
//    而且没有任何 fastrpc/dsp 日志 (第 8 步最容易卡住的地方)
<application>
    <uses-native-library android:name="libcdsprpc.so" android:required="false" />
</application>

// 2. 真实落盘: QNN 的 skel 要按文件路径加载, 不能留在 APK 里
//    (本仓为了 libnode.so 已经是 true, 不用改)
packaging { jniLibs { useLegacyPackaging = true } }

// 3. 建 session 前设一次 (进程级)
Os.setenv("ADSP_LIBRARY_PATH", nativeLibraryDir + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp", true)
```

### CPU 兜底是白送的

同一个 ORT, 不带 QNN EP 的 `SessionOptions` 就是 CPU (XNNPACK), 调的是同一份 ONNX 模型、同一套
前后处理代码。所以"NPU 不可用就退 CPU"不需要第二套运行时, 只需要两个 `SessionOptions`

## 5. 架构: 跑在哪、怎么给模型用

### 跑在 app 进程里

`channel/LwOcr.kt` 与 `LwAccessibility` 同级, **不进特权进程** —— OCR 不需要 root, 而模型库、assets、
Bitmap 都在 app 这边。特权进程那边**一行都不用改**

### 截图不能走模型的像素预算

`lw_screenshot` 会把图缩到设置页那一档 (默认 640000 px, 1080x2400 → 536x1192), 对**看的**模型是对的,
对 OCR 是错的: 12sp 的字缩一半就认不出来了, 而且 DB 检测在原始分辨率上才有意义。
所以 OCR 用**原始分辨率**的截图, 它自己那条路去拿 (特权侧 `screencap` 不带 `-p` 缩放或 app 侧
`AccessibilityService.takeScreenshot`), 返回的坐标也**永远是那块屏自己的像素**

### 前后处理 (都要自己写)

- **det**: 输入钉死到 640x640 (等比 + pad), 输出概率图 → 阈值 0.3 → **连通域** → 按垂直重叠合并成行 →
  轴对齐矩形 → 按 box 均分置信度过滤 (`box_thresh 0.6`) → 按 `unclip_ratio 1.5` 外扩
  **不引 OpenCV**: UI 截图的文字行是水平且互不重叠的, 连通域 + 行合并就够了, 为这个拉 30 MB 的 OpenCV 不值
- **rec**: 按行裁剪 → 等比缩到 48 高、宽不超过 320 → pad 到 320 → CTC 贪心解码 (去 blank、去重复)
- **坐标**: det 的框要按 letterbox 的 scale/pad 逆变换回屏幕像素

### 工具面

- **`lw_ocr(displayId, [region], [minScore])`** —— 与其它工具一样**必须显式带 displayId** (主屏是 0),
  返回行列表 `[x1,y1,x2,y2] 文字 置信度`, 顺带一行说明计算单元与耗时。行数设上限 (40 左右),
  排序左上到右下, 免得把模型的上下文冲掉
- **`lw_tap(text=…)` 加一个 `source` 参数** (`auto` / `a11y` / `ocr`, 默认 `auto`):
  `auto` 先用无障碍树, 树里没有值得动手的节点时才落到 OCR。这样引擎自绘的界面里那句
  `lw_tap(text="开始游戏")` 才有意义, 而**回答里要写清走的是哪条路** (`via: a11y|ocr`),
  与 `lw_type` 的 `via: field|keys` 一个道理
- `lw_ui` 不动。两条路并存: 有树用树 (快且不需要先截图), 没树用 OCR

## 6. 设置页要显示的 OCR 状态

新起一段「OCR」, 一个只读的状态行 (写法照 `channel/ScreenshotBudget.kt` / `PreviewControl`: 偏好 + Compose 状态):

| 显示 | 怎么来的 |
| :-- | :-- |
| **计算单元**: `NPU (HTP v73) · SM8550` / `CPU` / `未加载` | `Build.SOC_MODEL` 拿型号; **是不是真在 NPU 上靠 `session.disable_cpu_ep_fallback = "1"` 建 session 成不成** —— 这个开关的语义就是"只要有算子落不到 QNN EP 上就让建 session 失败", 所以它是判据不是猜。失败就换一条不带 QNN EP 的 `SessionOptions` 重建, 状态记 CPU |
| **模型**: `PP-OCRv6 tiny (det 4.26MB / rec 1.70MB)` / `缺失` | assets 解出来之后核对文件大小与 md5 |
| **上次耗时**: `det 12ms + rec 14x6ms` | 每次 OCR 记一次, 方便判断是不是退到 CPU 了 |

再考虑加一个「自检」按钮: 拿一张随 APK 发的小截图跑一遍, 把耗时打出来。**这是让状态可信的关键** ——
"可用"如果没有一条能重复的路径证明, 就只是个乐观的猜测

**注意 NPU 的初始化是有代价的**: 第一次建 session 要做 HTP graph prepare。走**预编译 context binary**
这条路就是为了避开它 (sherpa-onnx / Ultralytics 都是这么发的), 所以设置页那个"未加载 → 已加载"的
转变应该发生在**用户点开 OCR 或者工具第一次被调用时**, 不要在 `MainActivity.onCreate` 里同步做

## 7. 交付: 库与模型怎么进 APK

### 库: 只留这一个 SoC 架构

`qnn-runtime` 的 AAR 里带 V68/V69/V73/V75/V79/V81 六套 skel + stub, 还有 85 MB 的
`libQnnHtpPrepare.so`。**这些绝大多数不要**:

| 要发 (arm64, 未压缩) | 大小 | 为什么 |
| :-- | :-- | :-- |
| `libQnnHtpV73Skel.so` | 9.36 MB | DSP 侧, 我们的 HTP 是 v73 |
| `libQnnHtpV73Stub.so` | 0.55 MB | 与 skel 成对 |
| `libQnnHtp.so` | 2.40 MB | host 侧后端 |
| `libQnnSystem.so` | 2.47 MB | `libQnnHtp.so` 的 DT_NEEDED |
| `libonnxruntime.so` | ~20-27 MB | ORT 本体 (带 QNN EP) |
| `libonnxruntime_providers_qnn.so` | 3.53 MB | QNN EP |
| **不要发** `libQnnHtpPrepare.so` | 85 MB | **只用于在设备上现场编译图**, 我们发的是预编译 context binary |
| **不要发** V68/V69/V75/V79/V81 的 skel/stub | ~70 MB | 不是我们的架构 |

即 **+45 MB 左右 (未压缩)**, 相对现在 186.5 MB 的 APK 是能接受的, 但要用
`packaging { jniLibs { excludes += ... } }` 真的裁掉, 而 **AGP 9 到底裁不裁 AAR 里带的 .so 要在第一次构建时实测**
(本仓还有一条"AGP 会丢带点的 so 名"的旧账, 见 `docs/host-build.md`)

### 别的架构怎么办

- **v73 之外**: 退 CPU (同一份 ONNX), 或者按架构多发几套 ctx binary。`qnn-context-binary-generator`
  的 `--htp_socs` 支持多 SoC, ORT 2.48+ 的 Flexible Context Binary 也支持 `htp_arch` 传多个 ——
  但**一次只做 v73**, 别的等真有机子再说
- **模型**: ctx binary 与 fp32 ONNX 都发 (NPU 用前者, CPU 兜底用后者), 解到 `filesDir/ocr/` 下,
  与 host 树那套"assets → 首次解包"同一个套路。转换脚本 (`tools/build-ocr-models.mjs`) 与
  QAIRT 下载地址一起写进 `docs/host-build.md`, 顺手记上"模型与 ctx binary 都是构建产物还是入库"的决定

## 8. 验证清单 (按风险从高到低, 每步都要在 192.168.1.103 上过)

1. **ONNX 重写 pass 之后 HTP 能编译过** —— 本机 `qnn-context-binary-generator` 出 `*_v73.bin` 且无 ERROR。
   这一步不过, 后面全都不用谈, 直接走第 3 节的量化备选
2. **设备上 ORT 建得起 QNN session**: logcat 里 `CreateDevice succeed` / `Session successfully initialized`,
   且 `disable_cpu_ep_fallback=1` 没有把它打回 CPU
3. **CPU 兜底路也能建**: 不带 QNN EP 的 session 拿到的文字与 NPU 那份**逐字相同** (差几个字符就先记下来)
4. **准确率**: 一张中文设置页 + 一张引擎自绘的界面 (Phigros / 某个 Unity 游戏), 人工核对行数与文字
5. **延迟**: det + N 行 rec 的总耗时, NPU 与 CPU 各一组; 顺便记第一次建 session 的耗时
6. **坐标闭环**: OCR 报出的框中心 `lw_tap` 下去, 真的点到那一行 (这是整件事的意义所在)
7. **设置页状态**: 拔掉/换掉模型文件、强制 CPU 两条路上显示的文案都对
8. **APK 增量与安装**: 裁库之后实际涨了多少, 装完 `useLegacyPackaging` 有没有把 skel 真的解到
   `nativeLibraryDir` (`ls` 一下)
9. **回归**: `lw_ui` / `lw_tap(text=)` 在**有**无障碍树的界面上仍然走树, 没有变慢

## 9. 还没定 / 已知的坑

- **量化不做还是做**: fp16 的 ctx binary 已经够快的话就不做 int8 (int8 要校准数据与另一套流程);
  但 v6 是 fp32 导出的, 上 HTP 前可能有精度/兼容问题, 见第 3 节的备选
- **`Erf` 的近似**: 如果只能退到 tanh 近似, 识别率掉多少要实测
- **动态宽度的 rec**: 钉死 320 宽之后, 长文本行只能缩不能分块, 小字的行会糊
- **`AccessibilityService.takeScreenshot` 这条更快的截图路**没验过 (API 30+, 按 displayId, 但可能被 secure window 挡住),
  第一版先用特权侧 `screencap`
- **多屏**: OCR 与其它工具一样按 displayId 指名, 主屏是 0; 但**主屏的触摸刹车**要不要也管 OCR?
  现在的判断是**不管** —— OCR 是"看", 与 `lw_screenshot` 同类
