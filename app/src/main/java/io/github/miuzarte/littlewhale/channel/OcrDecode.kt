package io.github.miuzarte.littlewhale.channel

/** 一行认出来的字, 以及它有多确定 */
data class OcrText(val text: String, val score: Float)

/**
 * CTC 贪心解码
 *
 * 模型每一步输出 `classes` 个 logits, 解码就是**逐步取最大, 丢掉 blank, 合并重复**。识别头是纯 CTC
 * (PP-OCRv5/v6 的 NRTR 头只在训练时用, 推理时被导掉了), 所以没有字典树也没有语言模型可加
 *
 * 字符表的下标 0 是 CTC 的 blank (PaddleOCR 的 `CTCLabelDecode` 插进去的), 最后一个是空格
 * (`use_space_char`), 词表本身在中间 —— 这也是模型输出 6906 类而词表只有 6904 个字的原因
 */
object OcrDecode {

    /** 词表 + 两头的 blank 与空格, 交给 [greedy] 的就该是这张表 */
    fun table(characters: List<String>): List<String> = buildList {
        add(BLANK)
        addAll(characters)
        add(" ")
    }

    const val BLANK = ""

    /**
     * [logits] 是 `[steps, classes]` 拉平的输出
     *
     * **这份导出已经是 softmax 之后的概率了** (实测每一行加起来是 1.0), 所以置信度直接取那一步的
     * 最大值; 再套一次 softmax 会把它压到 1e-4 那个量级, 然后被任何阈值统统滤掉 —— 这个坑踩过
     *
     * 置信度取被留下的那些步的平均 —— 与 PaddleOCR 的口径一致, 重复步与 blank 不计入
     */
    fun greedy(logits: FloatArray, steps: Int, classes: Int, table: List<String>): OcrText {
        require(logits.size >= steps * classes) { "logits are ${logits.size}, expected ${steps * classes}" }
        val text = StringBuilder()
        var total = 0f
        var kept = 0
        var previous = -1
        for (step in 0 until steps) {
            val base = step * classes
            var best = base
            for (index in 1 until classes) {
                if (logits[base + index] > logits[best]) best = base + index
            }
            val index = best - base
            if (index == 0 || index == previous) {
                previous = index
                continue
            }
            previous = index
            if (index < table.size) text.append(table[index])
            total += if (logits[best] > 1f) 1f else logits[best]
            kept++
        }
        return OcrText(text.toString(), if (kept == 0) 0f else total / kept)
    }
}
