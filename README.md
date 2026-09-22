<!-- markdownlint-disable MD033 -->

# LittleWhale

把 [deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (dsh) 搬到安卓上

一台安卓设备同时当 dsh 的**主机**与**受控端**: APK 里跑 Node 与 dsh host, 界面就是 dsh 自己的 Web GUI (装在 WebView 里); 原生那一半是 Miuix 界面 + Shizuku / root 特权通道 + 自建虚拟屏, 把屏幕与输入能力做成 dsh 原生工具 (`lw_*`) 交给模型

不需要另一台电脑, 也不需要 Termux

屏幕那套工具要 **root (KernelSU / Magisk) 或 [Shizuku](https://github.com/RikkaApps/Shizuku) 之一**, 二选一

> [!WARNING]
> **审批是全部放行的, 而且这是有意的决定**: 模型要动主屏时 LittleWhale 自己在后台, 审批框没人点得到, 摆在那里只会让每次点击卡到超时。所以模型点屏幕不会问任何人, 唯一的刹车是**真人一碰屏幕就停手** —— 只管主屏, 而且只有软停 (手势中止 + 工具报错让模型收手), 没有硬停
>
> 这是给自愿把设备交给 agent 的人用的, 别装在别人也要用的机器上

## 能力

- **dsh 与它的 Web GUI 一起进 APK** —— Node 24 随包发, 前台服务常驻, 关掉界面也活着
- **工作区在你自己的存储里** —— `/sdcard/DSH/`, 文件管理器翻得到; dsh 自己的配置与凭据留在应用沙盒 (`filesDir/dsh-home`)
- **可选局域网访问** —— 打开后别的设备用浏览器就能连; 手机一套小字体、PC 一套大字体, 各存浏览器本地
- **自建虚拟屏** —— 想开几块开几块, 宽高与 dpi 随便给, 建完还能换形状 (`lw_screen_resize` / `lw_screen_rotate`); 预览是合成器直接画进 `SurfaceView` 的, 零 native, 不编码不解码
- **屏幕与输入是一套 dsh 原生工具** —— 列屏 / 建屏 / 换尺寸 / 关屏 / 点 / 拖 / 长按 / 按键 / 打字 / 启动应用 / 截图 / 读无障碍树 / 端侧 OCR / 列应用
- **主屏也看得到动得了** —— `displayId 0` 就是手机自己那块屏, 与虚拟屏走同一套调用; 一摸到真手指就当场停下并抬手
- **读屏两条路** —— 无障碍树 (文字 + 那块屏自己的坐标, 按名字点不需要坐标) 与端侧 OCR (PP-OCRv6 tiny 跑在 NPU 上, 25 行中英混排的设置页全读对)
- **截图在产生时按两条预算缩小** —— 像素与字节都在设置页调, 免得一整屏游戏画面把模型请求变成 `TRANSPORT`

## 已知问题

- **只在小米 13 (Android 16) 上验过**, 而且只编了 `arm64-v8a`
- **root 那条第一次要你手动授权** —— 应用侧查不出来有没有 root (没在名单上的 app 连 `su` 都看不见), 只能试; 而且试也不会弹框
- **侧载安装的 APK 开无障碍要额外的 app op** —— 应用会自己 best effort 处理, 但**每次重装 APK 都会把无障碍踢掉**, 要去设置页再拨一下那个开关
- **主屏的刹车只有软停** —— 抬手 1 秒之后就能再动手, 想更粘 (锁住到用户显式放开) 还没做
- **熄屏是静默失败** —— `screencap` 交的还是最后一帧, 注入的触摸唤不醒屏, 两个都不报错
- **设备上没有图片编码器** —— 所以只有 PNG 且不超预算的图能进模型, JPEG 读不了
- **双开的应用** (HyperOS 的「应用双开」= 另一个 Android user) 能控, 但跨 user 的能力跟着 ROM 走
- **第一次启动要等几分钟** —— host 树是 3 万个文件 / 281 MB, 首启解压, 而且别让设备息屏 (应用一进后台就被冻住, 解压跟着停在原地)
- 一块虚拟屏加一路预览 surface 到底占多少内存与 GPU **还没量过** (11 GB 设备上不紧张, 但要有数)

## 构建

需要 JDK 21, Android SDK (`compileSdk 37` / `build-tools 37.0.0`), NDK `29.0.14206865`, 以及 Node + pnpm

```bash
git clone --recursive https://github.com/Miuzarte/LittleWhale.git
cd LittleWhale
./gradlew assembleDebug
```

已克隆但没有拉取子模块:

```bash
git submodule update --init --recursive
```

`assembleDebug` 会顺带跑两个 Gradle task: 在 submodule 里 `pnpm install` + `build:official`, 再用上游自带的 pack 打出一棵平铺的 host 树, 压成 `assets/host.zip` 让 app 首启解压。两个 task 的输入含 submodule pin 与它的源码指纹, 所以只有 dsh 变了才重跑

**两样东西不在仓库里**, 缺了它们打出来的 APK 跑不起来:

| 缺什么 | 是什么 | 怎么拿 |
| :-- | :-- | :-- |
| `app/src/main/jniLibs/arm64-v8a/` (17 个 `.so`, 109 MiB) | Node 24 / bash 5.3 / ripgrep 15.2 的 Termux bionic 构建, 归一过 SONAME 与 runpath | 上游暂未发布, 见 [`docs/host-build.md`](docs/host-build.md) |
| `app/src/main/assets/ocr/` (5.9 MiB) | PP-OCRv6 tiny 的 det 与 rec, 钉死 shape 并转成 ONNX | `tools/ocr/build-models.ps1` (要 QAIRT SDK) |

为什么它们不进 git、随 APK 发的二进制为什么要当 `lib*.so`、以及踩过的坑都在 [`docs/host-build.md`](docs/host-build.md)

## 文档

| 文档 | 放什么 |
| :-- | :-- |
| [`AGENTS.md`](AGENTS.md) | 现状、决策、约束、怎么操作 —— 想知道某件事为什么是现在这样, 先看它 |
| [`docs/step2-record.md`](docs/step2-record.md) … [`step8-record.md`](docs/step8-record.md) | 每一步的清单、实测数字与踩坑过程 |
| [`docs/host-build.md`](docs/host-build.md) | 构建事实: 随 APK 发的二进制、pack / zip、被否的方案、依赖缺口、工具链版本 |

## Credits

- [deepseek-ai/deepseek-harness](https://github.com/deepseek-ai/deepseek-harness) (MIT) —— 被搬过来的东西本身。`third_party/deepseek-harness` 是[本仓的 fork](https://github.com/Miuzarte/deepseek-harness), 相对上游的全部改动记在它自己的 `patch.md` (惰性化原生依赖、`--allow-lan`、附件落盘的祖先 fsync 等)
- [compose-miuix-ui/miuix](https://github.com/compose-miuix-ui/miuix) —— 界面组件
- [Miuzarte/ScrcpyForAndroid](https://github.com/Miuzarte/ScrcpyForAndroid) (Apache-2.0) —— 界面视觉细节参考
- [Aliothmoon/MAA-Meow](https://github.com/Aliothmoon/MAA-Meow) (AGPL-3.0) —— 自建虚拟屏 / 输入注入 / 读触摸 / Shizuku 与 root 双通道的**做法**参考
- [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy) (Apache-2.0) —— 反射隐藏 API 的写法出处
- [RikkaApps/Shizuku](https://github.com/RikkaApps/Shizuku) —— 特权通道的后端
- [tiann/KernelSU](https://github.com/tiann/KernelSU) —— 开发机上用的 root 管理器
- [termux/termux-app](https://github.com/termux/termux-app) —— 随 APK 发的 Node / bash / ripgrep 都是在它的环境里编的 bionic 版本
- [PaddlePaddle/PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR) —— PP-OCRv6 tiny 的 det / rec 模型与字典
- [microsoft/onnxruntime](https://github.com/microsoft/onnxruntime) (含 QNN EP) 与 Qualcomm QAIRT / QNN —— OCR 在 Hexagon NPU 上跑; 从 AAR 带进来的那份 Qualcomm `LICENSE.pdf` / `NOTICE.txt` 保留在应用里
- [lovell/sharp](https://github.com/lovell/sharp) —— `image-backend/sharp/` 是照它的接口写的纯 JS 替身 (安卓上没有 libvips)

## License

[Apache License 2.0](LICENSE)

## Star History

<a href="https://www.star-history.com/?repos=Miuzarte%2FLittleWhale&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=Miuzarte/LittleWhale&type=date&theme=dark&legend=top-left" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=Miuzarte/LittleWhale&type=date&legend=top-left" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=Miuzarte/LittleWhale&type=date&legend=top-left" />
 </picture>
</a>
