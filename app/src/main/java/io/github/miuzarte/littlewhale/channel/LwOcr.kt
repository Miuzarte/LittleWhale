package io.github.miuzarte.littlewhale.channel

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.system.Os
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 端侧 OCR 的引擎: PP-OCRv6 tiny 的 det + rec, 跑在 ONNX Runtime 上
 *
 * 两条路用的是**同一个模型的两份产物**: `*_qnn.onnx` 是 QNN 预编译好的 context binary
 * (EPContext 节点把它整份嵌在 onnx 里, 由 `tools/ocr/build-models.ps1` 在开发机上生成),
 * `*_cpu.onnx` 是钉死 shape 的普通 onnx。NPU 与 CPU 因此共用一套前后处理, 分歧只在
 * `SessionOptions` 上
 *
 * 三件必须记住的事:
 *
 * - **QNN 那几支 .so 得随 APK 发**, 设备 `/vendor/lib64` 里虽然有, 但 Android 16 的 linker
 *   配置不给 app namespace 看那个目录, `public.libraries.txt` 里也没有 `libQnn*`。
 *   `libQnnHtpV73Stub.so` 的 `DT_NEEDED` 里有 `libcdsprpc.so`, 所以 manifest 必须声明
 *   `<uses-native-library android:name="libcdsprpc.so">`, 不声明就是**没有任何日志地失败**
 * - **`ADSP_LIBRARY_PATH` 要在建 session 之前设**, 指向 `nativeLibraryDir` (skel 在那里),
 *   后面再挂上厂商那两个目录当兜底
 * - **模型的 I/O 是 fp16**, 不是 fp32: QNN 的 fp32 路径连 `Clip` / `Erf` 这类算子都造不出来,
 *   而 `--preserve_io_datatype` 想让 I/O 保持 fp32 时插进去的 `Convert` 同样过不了 HTP。
 *   所以喂进去的图要自己转成半精度, 出来的也是半精度
 */
object LwOcr {

    private const val TAG = "LwOcr"

    /** 解出来的模型放哪, 与 host 树一个套路: assets 里是打包产物, 首次用到才落到沙盒 */
    private const val DIR = "ocr"

    /** assets 里的两份模型, 都是钉死 shape 的 fp32 onnx */
    private val ASSETS = listOf("det.onnx", "rec.onnx")

    /** 识别词表: 直接从 assets 读, 不进沙盒 (很小, 而且读一次就缓存) */
    private const val DICT = "rec_dict.txt"

    /** 一次最多报这么多行, 免得把模型的上下文冲掉 */
    private const val MAX_LINES = 60

    /** 认出来的字低于这个置信度就不要 (PaddleOCR 的 `drop_score` 是 0.5, 屏幕小字放宽一点) */
    private const val MIN_TEXT_SCORE = 0.35f

    /** 屏上小于这个边长的框不要 */
    private const val MIN_SCREEN_SIDE = 8

    /** 单个字至少要这么确定才留 (低于它的单字基本都是图标) */
    private const val SINGLE_CHARACTER_SCORE = 0.75f

    // 官方 inference.yml 的 NormalizeImage: scale 1/255, mean/std 按 **BGR** 的顺序给
    private const val DET_MEAN_B = 0.485f
    private const val DET_MEAN_G = 0.456f
    private const val DET_MEAN_R = 0.406f
    private const val DET_STD_B = 0.229f
    private const val DET_STD_G = 0.224f
    private const val DET_STD_R = 0.225f

    /** 补边补的是"归一化之后的黑", 与 PaddleOCR 先补 0 再归一化等价 */
    private const val BLACK_B = -DET_MEAN_B / DET_STD_B
    private const val BLACK_G = -DET_MEAN_G / DET_STD_G
    private const val BLACK_R = -DET_MEAN_R / DET_STD_R

    /** 记着这批模型是从哪一版 APK 里解出来的 */
    private const val STAMP = ".stamp"

    /** 认出来的计算单元, 设置页显示它 */
    enum class Backend(val label: String) {
        UNLOADED("未加载"),
        NPU("NPU"),
        CPU("CPU"),
        MISSING("模型缺失"),
    }

    /** 设置页读的 Compose 状态, 与 `ScreenshotBudget` 一个路子 */
    var backend: Backend by mutableStateOf(Backend.UNLOADED)
        private set

    /** 最近一次探测/识别的耗时与说明, 设置页显示它 */
    var note: String by mutableStateOf("")
        private set

    private val lock = Any()

    /** 进程里活着的那个 context, `MainActivity` 起第一帧之前挂上来, 与 `VirtualScreen` 一个样子 */
    private var application: Context? = null

    private var prepared: File? = null

    /**
     * 走哪条路
     *
     * - `jit` (默认): QNN 在设备上把钉死 shape 的 fp32 onnx 编成 fp16 的 HTP 图, **这是唯一
     *   算得对的一条**。开发机上用 `qnn-context-binary-generator --htp_socs sm8550` 预编译出来的
     *   context binary 能加载、能执行、不报错, 但输出是一张常数图 (0.6665 铺满全图), 换一组完全
     *   不同的输入结果一模一样 (实测 `npuSelfDiff = 0`), 也就是图根本没吃输入
     * - `cpu`: 只跑 CPU, 用来做对照
     */
    private var mode = "jit"

    /** 当前这次加载用的是哪一档性能票, 换档要重建 session */
    private var perfMode = "burst"

    private var env: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null

    /** 两个模型各自的输入名与形状, 建 session 时从模型里读出来, 不写死 */
    private var detInput: String = ""
    private var detShape: LongArray = longArrayOf()
    private var detOutput: String = ""

    /** 词表 + blank + 空格, 第一次识别时读出来就一直用 */
    private var characters: List<String>? = null

    /** fp16 的输出按位型查表换成 float: 一帧要换 123 万个, 位运算那位函数太慢 */
    private val halves = FloatArray(1 shl 16) { halfToFloat(it.toShort()) }

    fun attach(context: Context) {
        application = context.applicationContext
    }

    private fun app(): Context = application ?: error("LwOcr.attach 还没被调用")

    /**
     * 把 assets 里的模型解到沙盒里
     *
     * 判据是 **APK 的 `lastUpdateTime`**, 不是文件大小: 模型改一个字节大小往往不变 (改 ir_version
     * 就是这样, 占位长度一样), 而重装 APK 必然换这个时间戳, 也就必然重解一次
     */
    private fun prepare(context: Context): File? {
        prepared?.let { return it }
        val dir = File(context.filesDir, DIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        // 判据是 APK 文件自己的时间戳, 不是文件大小也不是 `lastUpdateTime` (`adb install -r`
        // 装同一个 versionCode 时那个字段不一定会动, 实测就没动)。模型改一个字节大小往往不变
        // (改 ir_version 就是这样), 而 `base.apk` 每次重装都会被重写
        val stamp = File(context.packageCodePath).lastModified()
        val stampFile = File(dir, STAMP)
        if (stampFile.takeIf { it.exists() }?.readText()?.trim() == stamp.toString()) {
            prepared = dir
            return dir
        }
        for (name in ASSETS) {
            val target = File(dir, name)
            try {
                context.assets.open("$DIR/$name").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "could not unpack $name: ${e.message}")
                backend = Backend.MISSING
                note = "assets 里没有 $name, 先跑 tools/ocr/build-models.ps1"
                return null
            }
        }
        stampFile.writeText(stamp.toString())
        prepared = dir
        return dir
    }

    /**
     * 打开一份模型
     *
     * [qnn] 为真时挂 QNN EP, 并把 `session.disable_cpu_ep_fallback` 打开 —— 这个开关的语义
     * 正是"有一个算子落不到 QNN EP 上就让建 session 失败", 所以建得起来就等于整张图真在 NPU 上,
     * 不需要另找证据
     */
    private fun open(context: Context, file: File, qnn: Boolean, jit: Boolean = false, perf: String): OrtSession {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        if (qnn) {
            // fastrpc 按路径找 skel, 所以要在建 session 之前把目录指好
            runCatching {
                Os.setenv(
                    "ADSP_LIBRARY_PATH",
                    "$nativeLibDir;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp",
                    true,
                )
            }.onFailure { Log.w(TAG, "ADSP_LIBRARY_PATH: ${it.message}") }
        }
        val environment = env ?: OrtEnvironment.getEnvironment().also { env = it }
        val options = OrtSession.SessionOptions()
        if (qnn) {
            val qnnOptions = mutableMapOf(
                "backend_path" to "libQnnHtp.so",
                // 性能票: 不下发时 DSP 跑在 DCVS 默认 (低频) 档上, `burst` 是投给它的最高电压角
                // (Inferencer 那边实测同一张图 execute 15.8ms -> 5.5ms)
                "htp_performance_mode" to perf,
            )
            if (jit) {
                // 让 QNN 自己在设备上把这张图编译成 fp16 的 HTP 图, I/O 仍是 fp32, 所以不用自己
                // 装箱, 代价是 libQnnHtpPrepare.so 要随 APK 发, 首次建 session 也要等它编译
                qnnOptions["enable_htp_fp16_precision"] = "1"
            }
            options.addQnn(qnnOptions)
            if (!jit) {
                options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
            }
        }
        return environment.createSession(file.absolutePath, options)
    }

    /**
     * 把两份模型都加载起来, 顺带定下计算单元
     *
     * 先试 NPU: 建 session 抛异常 (skel 对不上、HTP 上跑不了、被别的进程占满) 就退到 CPU,
     * 而退到 CPU 之后**不会**再回头试 NPU —— 这一层没有重试的意义, 设备状态没变结果就不变
     */
    fun load(force: String? = null, perf: String = "burst"): Backend = synchronized(lock) {
        if ((force != null && force != mode) || perf != perfMode) {
            detSession?.let { runCatching { it.close() } }
            recSession?.let { runCatching { it.close() } }
            detSession = null
            recSession = null
            backend = Backend.UNLOADED
            mode = force ?: mode
            perfMode = perf
        }
        if (backend == Backend.NPU || backend == Backend.CPU) return backend
        val context = app()
        val dir = prepare(context) ?: return backend
        val started = System.currentTimeMillis()
        val detFile = File(dir, "det.onnx")
        val recFile = File(dir, "rec.onnx")

        try {
            // 只用 CPU 是从这里跳出去, NPU 那条路就是把同一份 onnx 交给 QNN 在设备上编译
            if (mode == "cpu") throw IllegalStateException("forced CPU")
            detSession = open(context, detFile, qnn = true, jit = true, perf = perfMode)
            recSession = open(context, recFile, qnn = true, jit = true, perf = perfMode)
            backend = Backend.NPU
        } catch (e: Throwable) {
            Log.w(TAG, "QNN session ($mode) failed, falling back to CPU: ${e.message}")
            detSession?.let { runCatching { it.close() } }
            detSession = null
            recSession = null
            detSession = open(context, detFile, qnn = false, perf = perfMode)
            recSession = open(context, recFile, qnn = false, perf = perfMode)
            backend = Backend.CPU
            mode = "cpu"
        }

        detInput = detSession!!.inputNames.first()
        detShape = (detSession!!.inputInfo[detInput]!!.info as TensorInfo).shape
        detOutput = detSession!!.outputNames.first()
        val cost = System.currentTimeMillis() - started
        note = "建 session ${cost}ms"
        Log.i(TAG, "backend=${backend.label} input=$detInput ${detShape.toList()} load=${cost}ms")
        backend
    }

    /**
     * 让两个模型各跑一遍, 回一份能看的数字
     *
     * 输入是合成的: det 喂一张渐变, rec 喂一条假的文本行。这里要看的是**耗时与计算单元**,
     * 不是认得准不准 —— 认字那条路还没接
     */
    fun probe(rounds: Int, force: String?, compare: Boolean, perf: String): JsonObject {
        val unit = load(force, perf)
        if (unit != Backend.NPU && unit != Backend.CPU) {
            return buildJsonObject {
                put("backend", unit.label)
                put("loaded", false)
                put("error", note)
            }
        }
        val environment = env!!
        val det = detSession!!
        val detType = (det.inputInfo[detInput]!!.info as TensorInfo).type
        val detWidth = detShape[detShape.size - 1].toInt()
        // 确定性噪声, 不用渐变: 整张图一个方向的渐变会让检测头输出一张几乎恒定的概率图, 那样
        // 两个后端的差就只剩"常数差多少", 说明不了精度
        val detFloats = FloatArray(detShape.fold(1L) { a, b -> a * b }.toInt()) { noise(it) }
        val rec = recSession!!
        val recInput = rec.inputNames.first()
        val recShape = (rec.inputInfo[recInput]!!.info as TensorInfo).shape
        val recType = (rec.inputInfo[recInput]!!.info as TensorInfo).type
        val recHeight = recShape[recShape.size - 2].toInt()
        val recWidth = recShape[recShape.size - 1].toInt()
        val recFloats = FloatArray(recShape.fold(1L) { a, b -> a * b }.toInt()) {
            recPixel(it, recWidth, recHeight)
        }

        val detPack = time(rounds) { tensor(environment, detShape, detType) { detFloats[it] }.close() }
        val detMs = time(rounds) { run(det, detInput, detShape, detType, detFloats) }
        val recPack = time(rounds) { tensor(environment, recShape, recType) { recFloats[it] }.close() }
        val recMs = time(rounds) { run(rec, recInput, recShape, recType, recFloats) }

        // 同一份输入在两个后端上各跑一次, 差多少就是 fp16 与 QNN 一起带来的代价。只在 NPU 上做,
        // 因为 CPU 那份必须**当场**另建一个 session 才能对比
        var maxDiff = 0f
        var meanDiff = 0f
        var npuRange = ""
        var cpuRange = ""
        var npuSelfDiff = 0f
        var head = ""
        if (compare && unit == Backend.NPU) {
            val cpuDet = open(app(), File(prepare(app())!!, "det.onnx"), qnn = false, perf = "default")
            try {
                val onNpu = run(det, detInput, detShape, detType, detFloats)
                val onCpu = run(cpuDet, detInput, detShape, OnnxJavaType.FLOAT, detFloats)
                var sum = 0.0
                for (i in onNpu.indices) {
                    val d = kotlin.math.abs(onNpu[i] - onCpu[i])
                    if (d > maxDiff) maxDiff = d
                    sum += d
                }
                meanDiff = (sum / onNpu.size).toFloat()
                npuRange = describe(onNpu)
                cpuRange = describe(onCpu)
                // 换一组完全不同的输入再跑一次 NPU: 两次差为 0 就说明图根本不吃这个输入
                val zeros = FloatArray(detFloats.size)
                val onZeros = run(det, detInput, detShape, detType, zeros)
                npuSelfDiff = maxAbs(onNpu, onZeros)
                head = (0 until 8).joinToString(" ") {
                    "%04x".format(java.lang.Float.floatToIntBits(onNpu[it]) ushr 16)
                }
            } finally {
                runCatching { cpuDet.close() }
            }
        }

        note = "det ${detMs}ms (装箱 ${detPack}ms) / rec ${recMs}ms (装箱 ${recPack}ms)"
        return buildJsonObject {
            put("backend", unit.label)
            put("loaded", true)
            put("detMs", detMs)
            put("detPackMs", detPack)
            put("recMs", recMs)
            put("recPackMs", recPack)
            put("rounds", rounds)
            put("perf", perfMode)
            put("detShape", buildJsonArray { detShape.forEach { add(it) } })
            put("recShape", buildJsonArray { recShape.forEach { add(it) } })
            put("soc", Build.SOC_MODEL ?: "")
            put("abi", Build.SUPPORTED_ABIS.firstOrNull() ?: "")
            if (compare && unit == Backend.NPU) {
                put("maxDiff", maxDiff.toDouble())
                put("meanDiff", meanDiff.toDouble())
                put("npuRange", npuRange)
                put("cpuRange", cpuRange)
                put("npuSelfDiff", npuSelfDiff.toDouble())
                put("head", head)
            }
        }
    }

    private fun maxAbs(a: FloatArray, b: FloatArray): Float {
        var max = 0f
        for (i in a.indices) {
            val d = kotlin.math.abs(a[i] - b[i])
            if (d > max) max = d
        }
        return max
    }

    /** min / max / mean, 用来判断两个后端的输出是不是在同一量级上 */
    private fun describe(values: FloatArray): String {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var sum = 0.0
        for (v in values) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        return "%.4f..%.4f mean %.4f".format(min, max, sum / values.size)
    }

    /** 确定性噪声 (一个 32 位线性同余), 每次探测喂进去的都是同一张图 */
    private fun noise(i: Int): Float {
        var x = i * 1103515245 + 12345
        x = x xor (x ushr 16)
        x *= 2654435761.toInt()
        return ((x ushr 8) and 0xFF) / 255f
    }

    /** 一行认出来的东西, 坐标是**那块屏自己的像素** */
    data class Line(
        val text: String,
        val score: Float,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** 一次识别: 认出来的行, 加上这次是谁算的、各花了多久 */
    data class Outcome(
        val lines: List<Line>,
        val backend: String,
        val captureMs: Long,
        val detMs: Long,
        val recMs: Long,
        val error: String? = null,
    )

    /**
     * 认一张图
     *
     * 与 `lw_screenshot` 那条路的区别是**这里不缩图**: 模型的像素预算是给"看的模型"定的
     * (默认 640000, 1080x2400 缩成 536x1192), 而 12sp 的字缩一半就认不出来了。所以传进来的
     * 图应当是全分辨率的, 返回的坐标也就是那块屏自己的像素, 可以原样喂给 `lw_tap`
     */
    fun recognize(bitmap: Bitmap, maxLines: Int = MAX_LINES, minScore: Float = MIN_TEXT_SCORE, captureMs: Long = 0): Outcome {
        val unit = load()
        if (unit != Backend.NPU && unit != Backend.CPU) {
            return Outcome(emptyList(), unit.label, 0, 0, 0, note)
        }
        return try {
            val det = detSession!!
            val detWidth = detShape[detShape.size - 1].toInt()
            val detHeight = detShape[detShape.size - 2].toInt()
            val detType = (det.inputInfo[detInput]!!.info as TensorInfo).type
            lastBoxes = 0

            // 等比放进模型的输入里, 四周补"归一化之后的黑", 与 PaddleOCR 的 resize 一致
            val scale = minOf(detWidth.toFloat() / bitmap.width, detHeight.toFloat() / bitmap.height)
            val scaledWidth = (bitmap.width * scale).roundToInt().coerceIn(1, detWidth)
            val scaledHeight = (bitmap.height * scale).roundToInt().coerceIn(1, detHeight)
            val padX = (detWidth - scaledWidth) / 2
            val padY = (detHeight - scaledHeight) / 2

            val detStarted = System.nanoTime()
            val planes = detectionInput(bitmap, detWidth, detHeight, scaledWidth, scaledHeight, padX, padY)
            val probability = run(det, detInput, detShape, detType, planes)
            val detMs = (System.nanoTime() - detStarted) / 1_000_000

            val boxes = OcrDetect.lines(probability, detWidth, detHeight)
            lastBoxes = boxes.size
            val rec = recSession!!
            val recInput = rec.inputNames.first()
            val recShape = (rec.inputInfo[recInput]!!.info as TensorInfo).shape
            val recType = (rec.inputInfo[recInput]!!.info as TensorInfo).type
            val recHeight = recShape[recShape.size - 2].toInt()
            val recWidth = recShape[recShape.size - 1].toInt()
            // 解码要的是**输出**的形状 (步数, 类别数), 不是输入那个 [1,3,48,320]
            val recOutputShape = (rec.outputInfo[rec.outputNames.first()]!!.info as TensorInfo).shape
            val steps = recOutputShape[recOutputShape.size - 2].toInt()
            val classes = recOutputShape[recOutputShape.size - 1].toInt()
            val characterTable = table()

            val recStarted = System.nanoTime()
            val lines = ArrayList<Line>(boxes.size)
            var attempts = 0
            for (box in boxes) {
                if (lines.size >= maxLines) break
                val rect = toScreen(box, scale, padX.toFloat(), padY.toFloat(), bitmap) ?: continue
                val pixels = resized(bitmap, rect, recHeight, recWidth)
                val logits = run(
                    rec,
                    recInput,
                    recShape,
                    recType,
                    recognitionInput(pixels.first, pixels.second, recHeight, recWidth),
                )
                attempts++
                val decoded = OcrDecode.greedy(logits, steps, classes, characterTable)
                if (decoded.text.isBlank() || decoded.score < minScore) continue
                // 图标会被认成单个字, 分数也低 (实测 C / 8 / 心 / A 都在 0.5-0.7), 丢掉;
                // 真的有意义的单字 (列表序号之类) 分数在 0.9 以上, 留得住
                if (decoded.text.length == 1 && decoded.score < SINGLE_CHARACTER_SCORE) continue
                lines.add(Line(decoded.text, decoded.score, rect.left, rect.top, rect.right, rect.bottom))
            }
            val recMs = if (attempts == 0) 0L else (System.nanoTime() - recStarted) / 1_000_000 / attempts
            note = "det ${detMs}ms / rec ${recMs}ms × ${lines.size}"
            Outcome(lines, unit.label, captureMs, detMs, recMs)
        } catch (problem: Throwable) {
            Log.w(TAG, "recognize failed: ${problem.message}")
            note = "识别失败: ${problem.message}"
            Outcome(emptyList(), unit.label, captureMs, 0, 0, problem.message ?: problem.toString())
        }
    }

    /**
     * 三通道的检测输入
     *
     * **通道顺序是 BGR**: 官方 `inference.yml` 的 `DecodeImage` 写着 `img_mode: BGR`, 而
     * `NormalizeImage` 的 mean/std 也是按那个顺序给的 —— 弄反了不会报错, 只是认得差一点
     */
    private fun detectionInput(
        bitmap: Bitmap,
        width: Int,
        height: Int,
        scaledWidth: Int,
        scaledHeight: Int,
        padX: Int,
        padY: Int,
    ): FloatArray {
        val plane = width * height
        val out = FloatArray(3 * plane)
        java.util.Arrays.fill(out, 0, plane, BLACK_B)
        java.util.Arrays.fill(out, plane, 2 * plane, BLACK_G)
        java.util.Arrays.fill(out, 2 * plane, 3 * plane, BLACK_R)
        val scaled = if (scaledWidth == bitmap.width && scaledHeight == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        }
        val pixels = IntArray(scaledWidth * scaledHeight)
        scaled.getPixels(pixels, 0, scaledWidth, 0, 0, scaledWidth, scaledHeight)
        if (scaled !== bitmap) scaled.recycle()
        for (y in 0 until scaledHeight) {
            val row = (y + padY) * width + padX
            for (x in 0 until scaledWidth) {
                val pixel = pixels[y * scaledWidth + x]
                val at = row + x
                out[at] = ((pixel and 0xFF) / 255f - DET_MEAN_B) / DET_STD_B
                out[plane + at] = (((pixel shr 8) and 0xFF) / 255f - DET_MEAN_G) / DET_STD_G
                out[2 * plane + at] = (((pixel shr 16) and 0xFF) / 255f - DET_MEAN_R) / DET_STD_R
            }
        }
        return out
    }

    /** 按行裁剪、等比缩到识别模型的高度, 返回像素与**有内容的宽度** (右边补 0 是归一化之后的 0) */
    private fun resized(bitmap: Bitmap, rect: Rect, height: Int, width: Int): Pair<IntArray, Int> {
        val box = Bitmap.createBitmap(bitmap, rect.left, rect.top, rect.width(), rect.height())
        val content = ceil(height.toDouble() * box.width / box.height).toInt().coerceIn(1, width)
        val scaled = Bitmap.createScaledBitmap(box, content, height, true)
        val pixels = IntArray(content * height)
        scaled.getPixels(pixels, 0, content, 0, 0, content, height)
        if (scaled !== box) scaled.recycle()
        box.recycle()
        return pixels to content
    }

    private fun recognitionInput(pixels: IntArray, contentWidth: Int, height: Int, width: Int): FloatArray {
        val plane = width * height
        val out = FloatArray(3 * plane)
        for (y in 0 until height) {
            for (x in 0 until contentWidth) {
                val pixel = pixels[y * contentWidth + x]
                val at = y * width + x
                out[at] = (pixel and 0xFF) / 127.5f - 1f
                out[plane + at] = ((pixel shr 8) and 0xFF) / 127.5f - 1f
                out[2 * plane + at] = ((pixel shr 16) and 0xFF) / 127.5f - 1f
            }
        }
        return out
    }

    /** 概率图坐标 → 屏坐标, 越界的裁掉, 太小的丢掉 */
    private fun toScreen(box: OcrBox, scale: Float, padX: Float, padY: Float, bitmap: Bitmap): Rect? {
        val rect = Rect(
            ((box.left - padX) / scale).roundToInt().coerceIn(0, bitmap.width - 1),
            ((box.top - padY) / scale).roundToInt().coerceIn(0, bitmap.height - 1),
            ((box.right - padX) / scale).roundToInt().coerceIn(1, bitmap.width),
            ((box.bottom - padY) / scale).roundToInt().coerceIn(1, bitmap.height),
        )
        if (rect.width() < MIN_SCREEN_SIDE || rect.height() < MIN_SCREEN_SIDE) return null
        return rect
    }

    /** 上一次检测出多少个框, 诊断用 */
    var lastBoxes: Int = 0
        private set

    /** 词表 + blank + 空格, 只在第一次识别时读一遍 */
    private fun table(): List<String> {
        characters?.let { return it }
        val loaded = try {
            app().assets.open("$DIR/$DICT").bufferedReader().use { it.readLines() }
        } catch (problem: Throwable) {
            Log.w(TAG, "no dictionary: ${problem.message}")
            emptyList()
        }
        val built = OcrDecode.table(loaded)
        characters = built
        return built
    }

    /** 一条假的文本行: 白底上按 20x12 的格子铺黑条, 只为了让卷积有东西可算 */
    private fun recPixel(i: Int, width: Int, height: Int): Float {
        val x = i % width
        val y = (i / width) % height
        return if (y % 12 < 8 && x % 20 < 14) 0.05f else 0.95f
    }

    /** 跑一遍, 把输出搬成 float, 不看内容 */
    private fun run(
        session: OrtSession,
        inputName: String,
        shape: LongArray,
        type: OnnxJavaType,
        floats: FloatArray,
    ): FloatArray {
        tensor(env!!, shape, type) { floats[it] }.use { t ->
            session.run(mapOf(inputName to t)).use { out ->
                return readFloats(out.get(session.outputNames.first()).get() as OnnxTensor)
            }
        }
    }

    private fun readFloats(t: OnnxTensor): FloatArray {
        val info = t.info as TensorInfo
        val size = info.shape.fold(1L) { a, b -> a * b }.toInt()
        val result = FloatArray(size)
        if (info.type == OnnxJavaType.FLOAT16) {
            val buffer = t.shortBuffer
            for (i in 0 until size) result[i] = halves[buffer.get(i).toInt() and 0xFFFF]
        } else {
            val buffer = t.floatBuffer
            for (i in 0 until size) result[i] = buffer.get(i)
        }
        return result
    }

    private fun halfToFloat(value: Short): Float {
        val bits = value.toInt() and 0xFFFF
        val sign = (bits and 0x8000) shl 16
        val exponent = (bits ushr 10) and 0x1F
        val mantissa = bits and 0x3FF
        val out = when {
            exponent == 0 && mantissa == 0 -> sign
            exponent == 0 -> {
                // 非规格数: 靠移位把它规格化
                var m = mantissa
                var e = -1
                while (m and 0x400 == 0) {
                    m = m shl 1
                    e++
                }
                sign or ((127 - 15 - e) shl 23) or ((m and 0x3FF) shl 13)
            }
            exponent == 0x1F -> sign or 0x7F800000 or (mantissa shl 13)
            else -> sign or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
        }
        return java.lang.Float.intBitsToFloat(out)
    }

    private fun time(rounds: Int, block: () -> Unit): Long {
        block()
        val started = System.nanoTime()
        repeat(rounds) { block() }
        return (System.nanoTime() - started) / 1_000_000 / rounds
    }

    /**
     * 一个按模型声明的类型装箱的输入
     *
     * QNN 那份的 I/O 是 fp16, CPU 那份是 fp32, 而两条路共用这一段, 所以类型从 session 里读,
     * 不写死 —— 写死哪一边都会在另一边报 "Unexpected input data type"
     *
     * 转换是**按批**做的: 先写进一个 `ShortArray` 再整块拷进 direct buffer。逐元素 `putShort`
     * 走的是直接缓冲区的边界检查, 一帧 640x640 就是这么从 10 ms 涨到 57 ms 的 (实测)
     */
    private inline fun tensor(
        environment: OrtEnvironment,
        shape: LongArray,
        type: OnnxJavaType,
        value: (Int) -> Float,
    ): OnnxTensor {
        val size = shape.fold(1L) { a, b -> a * b }.toInt()
        val half = type == OnnxJavaType.FLOAT16
        val buffer = ByteBuffer.allocateDirect(size * if (half) 2 else 4).order(ByteOrder.nativeOrder())
        if (half) {
            val staging = ShortArray(size)
            for (i in 0 until size) staging[i] = halfOf(value(i))
            buffer.asShortBuffer().put(staging)
        } else {
            val staging = FloatArray(size)
            for (i in 0 until size) staging[i] = value(i)
            buffer.asFloatBuffer().put(staging)
        }
        buffer.rewind()
        return OnnxTensor.createTensor(environment, buffer, shape, type)
    }

    /**
     * fp32 到 fp16 位型的转换, 与 `android.util.Half.toHalf` 同义
     *
     * 自己写是因为那个方法在 debug 构建里不会被内联, 而这条路一帧要调 123 万次
     */
    private fun halfOf(value: Float): Short {
        val bits = java.lang.Float.floatToIntBits(value)
        val sign = (bits ushr 16) and 0x8000
        val exponent = (bits ushr 23) and 0xFF
        val mantissa = bits and 0x7FFFFF
        // 指数全 1: 无穷或 NaN, 保持非零尾数以免 NaN 变无穷
        if (exponent == 0xFF) {
            return (sign or 0x7C00 or (if (mantissa != 0) 0x200 else 0)).toShort()
        }
        val shifted = exponent - 127 + 15
        if (shifted >= 0x1F) return (sign or 0x7C00).toShort()
        if (shifted <= 0) {
            if (shifted < -10) return sign.toShort()
            val subnormal = (mantissa or 0x800000) shr (1 - shifted)
            // 四舍五入到最近
            return (sign or ((subnormal + 0x1000) shr 13)).toShort()
        }
        return (sign or (shifted shl 10) or ((mantissa + 0x1000) shr 13)).toShort()
    }
}
