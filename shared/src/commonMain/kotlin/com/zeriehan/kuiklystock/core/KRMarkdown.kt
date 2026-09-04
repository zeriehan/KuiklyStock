package com.zeriehan.kuiklystock.core

/**
 * KRMarkdown：轻量 Markdown 解析模型（纯逻辑、无 UI，供富文本渲染器消费）。
 *
 * Task02 发散性渲染：AI 回答输出 Markdown 后，需要渲染成「标题 / 列表 / 引用 /
 * 代码块 / 行内加粗斜体 / 可点股票」等结构化富文本，而不仅是纯文本直出。
 *
 * 覆盖语法（块级）：
 *   # / ##          标题（并容忍旧格式「【标题】」行、以及 “**标题**” 整行加粗当标题）
 *   - / * / 1.       无序 / 有序列表项（连续项归并成列表块，逐行有前缀）
 *   >               引用
 *   ``` 或 ~~~       围栏代码块
 *   空行 / 非以上    段落分隔（一个空行起的连续普通行是一个段落，块内保留 \n 换行）
 *
 * 行内：**加粗**、*斜体*、`行内代码`、股票代码/名称 -> [MdToken.StockRef]（渲染为可点）。
 *
 * 解析不抛异常：识别不了的写法一律降级为纯文本原样输出，保证任何模型输出都不会崩。
 */

/** 行内 token：一块富文本里的最小样式片段 */
sealed class MdToken {
    /** 普通文本 */
    class Plain(val text: String) : MdToken()
    /** **加粗** */
    class Bold(val text: String) : MdToken()
    /** *斜体* */
    class Italic(val text: String) : MdToken()
    /** `行内代码` */
    class Code(val text: String) : MdToken()
    /** 块内硬换行（单行内多个连续空格结尾或模型输出的行尾 `  \n`） */
    class Newline : MdToken()
    /** 可点击股票：模型输出代码或名称时识别（渲染层据此跳转/展示） */
    class StockRef(val code: String, val name: String) : MdToken()
}

/** 块类型 */
enum class MdBlockType { H1, H2, PARAGRAPH, UL_ITEM, OL_ITEM, QUOTE, CODE }

/** 一个内容块：类型 + 行内 token 序列 */
class MdBlock(
    val type: MdBlockType,
    val tokens: List<MdToken>,
    /** 列表项编号（OL_ITEM 的 "1." 序号）或列表前缀，由渲染器决定；代码块此处为原始文本行 */
    val raw: String = "",
) {
    /** 是否列表项（块渲染需要前缀符号） */
    val isListItem: Boolean get() = type == MdBlockType.UL_ITEM || type == MdBlockType.OL_ITEM
}

object KRMarkdown {

    /** 空实现可读时的空表 */
    fun parse(md: String): List<MdBlock> {
        if (md.isBlank()) return emptyList()
        val rawLines = md.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val lines = rawLines.toMutableList()
        val out = ArrayList<MdBlock>()

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // 1) 围栏代码块
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val fence = trimmed.take(3)
                val sb = StringBuilder()
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    if (lines[j].trim().startsWith(fence)) { closed = true; break }
                    sb.append(lines[j]).append('\n')
                    j++
                }
                out.add(MdBlock(MdBlockType.CODE, emptyList(), raw = sb.toString().trimEnd('\n')))
                i = if (closed) j + 1 else lines.size
                continue
            }

            // 2) 空行：跳过（分隔）
            if (trimmed.isEmpty()) { i++; continue }

            // 3) 引用行：可能连续多行
            if (trimmed.startsWith(">")) {
                val sb = StringBuilder()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    var body = lines[i].trimStart().drop(1).trimStart()
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(body)
                    i++
                }
                out.add(MdBlock(MdBlockType.QUOTE, parseInline(sb.toString())))
                continue
            }

            // 4) 标题
            if (trimmed.startsWith("### ")) { out.add(blockH(3, inlineAfter(trimmed, "### "))); i++; continue }
            if (trimmed.startsWith("## "))  { out.add(blockH(2, inlineAfter(trimmed, "## ")));  i++; continue }
            if (trimmed.startsWith("# "))   { out.add(blockH(1, inlineAfter(trimmed, "# ")));   i++; continue }
            // 4b) 兼容旧格式：【标题】行（整行被【】包裹、较短）
            if (trimmed.length in 2..40 && trimmed.startsWith("【") && trimmed.endsWith("】")) {
                val inner = trimmed.substring(1, trimmed.length - 1).trim()
                out.add(blockH(1, parseInline(inner))); i++; continue
            }

            // 5) 无序列表项：连续项归并
            if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
                val items = ArrayList<List<MdToken>>()
                while (i < lines.size) {
                    val t = lines[i].trim()
                    if (t.startsWith("- ") || t.startsWith("* ")) {
                        items.add(parseInline(t.drop(2)))
                        i++
                    } else break
                }
                items.forEach { out.add(MdBlock(MdBlockType.UL_ITEM, it)) }
                continue
            }

            // 6) 有序列表项
            val ol = Regex("^\\s*(\\d+)[.、)]\\s+").find(line)
            if (ol != null) {
                val items = ArrayList<Pair<String, List<MdToken>>>()
                var num = ol.groupValues[1].toIntOrNull() ?: 1
                while (i < lines.size) {
                    val m = Regex("^\\s*(\\d+)[.、)]\\s+").find(lines[i])
                    if (m != null) {
                        items.add(num.toString() to parseInline(lines[i].drop(m.value.length)))
                        num++
                        i++
                    } else break
                }
                items.forEach { (n, toks) -> out.add(MdBlock(MdBlockType.OL_ITEM, toks, raw = n)) }
                continue
            }

            // 7) 普通段落：收集直到空行 / 另一种块开头
            val sb = StringBuilder()
            while (i < lines.size) {
                val l = lines[i]
                val tt = l.trim()
                if (tt.isEmpty()) break
                if (tt.startsWith("#") || tt.startsWith(">") || tt.startsWith("- ") ||
                    tt.startsWith("* ") || tt.startsWith("```") || tt.startsWith("~~~") ||
                    Regex("^\\d+[.、)]\\s+").containsMatchIn(tt) ||
                    (tt.length in 2..40 && tt.startsWith("【") && tt.endsWith("】"))
                ) break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(l.trim())
                i++
            }
            if (sb.isNotEmpty()) {
                out.add(MdBlock(MdBlockType.PARAGRAPH, parseInline(sb.toString())))
            } else { i++ }
        }
        return out
    }

    private fun blockH(level: Int, toks: List<MdToken>): MdBlock {
        val t = if (level <= 1) MdBlockType.H1 else if (level == 2) MdBlockType.H2 else MdBlockType.H2
        return MdBlock(t, toks)
    }

    private fun inlineAfter(line: String, prefix: String): List<MdToken> =
        parseInline(line.removePrefix(prefix))

    /** 行内解析：把「**加粗**、*斜体*、`代码`、股票代码/名称」切成 token 序列；支持 \n 换行 token。 */
    fun parseInline(text: String): List<MdToken> {
        val toks = ArrayList<MdToken>()
        val buf = StringBuilder()
        var i = 0
        val n = text.length

        fun flushPlain() { if (buf.isNotEmpty()) { toks.add(MdToken.Plain(buf.toString())); buf.clear() } }

        while (i < n) {
            val c = text[i]
            // 块内换行：换行符 -> Newline token（分隔成两个 Plain 段由渲染层折行）
            if (c == '\n') { flushPlain(); toks.add(MdToken.Newline()); i++; continue }

            // 行内代码 `...`
            if (c == '`') {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flushPlain()
                    toks.add(MdToken.Code(text.substring(i + 1, end)))
                    i = end + 1; continue
                }
            }

            // **加粗**
            if (c == '*' && i + 1 < n && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end > i + 1) {
                    val inner = text.substring(i + 2, end)
                    flushPlain()
                    toks.add(MdToken.Bold(inner))
                    i = end + 2; continue
                }
            }

            // 股票识别：仅当当前位置是数字(可能代码)或汉字(可能名称开头)时才尝试扫池
            val canBeStock = (c.isDigit() && c != ' ') ||
                (c in '\u4e00'..'\u9fff')
            if (canBeStock) {
                val st = tryStockAt(text, i)
                if (st != null) {
                    flushPlain()
                    toks.add(MdToken.StockRef(st.first, st.second))
                    i += st.third; continue
                }
            }

            // *斜体*（单星号、两侧有内容）
            if (c == '*' && (i == 0 || text[i - 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end > i + 1) {
                    // 确保不是 **（双星闭合）的开头一半
                    if (!(end + 1 < n && text[end + 1] == '*')) {
                        val inner = text.substring(i + 1, end)
                        if (inner.isNotBlank() && !inner.contains('*')) {
                            flushPlain(); toks.add(MdToken.Italic(inner)); i = end + 1; continue
                        }
                    }
                }
            }

            buf.append(c)
            i++
        }
        flushPlain()
        return toks
    }

    /** 尝试在 [start] 位置识别一个行情池内股票：优先代码(6位数字)，其次名称(≥2字)。返回 (code,name,consumedLen) */
    private fun tryStockAt(text: String, start: Int): Triple<String, String, Int>? {
        val cands = StockData.getQuotes().filter { !it.isIndex }
        if (cands.isEmpty()) return null
        // 名称/代码都可能，取最长的先匹配（避免“平安”先匹配到而把“平安银行”拆开）
        var best: Triple<String, String, Int>? = null
        for (s in cands) {
            // 代码命中：且两侧是数字/字母边界，避免匹配到更长数字串中间
            if (s.code.length >= 4) {
                val c = s.code
                if (regionMatches(text, start, c)) {
                    // 边界检查
                    val beforeOk = start == 0 || !(text[start - 1].isDigit())
                    val afterIdx = start + c.length
                    val afterOk = afterIdx >= n(text) || !(text[afterIdx].isDigit())
                    if (beforeOk && afterOk) {
                        val len = c.length
                        if (best == null || len > best.third) best = Triple(s.code, s.name, len)
                    }
                }
            }
            // 名称命中
            if (s.name.length >= 2) {
                val nm = s.name
                if (regionMatches(text, start, nm)) {
                    if (best == null || nm.length > best.third) best = Triple(s.code, nm, nm.length)
                }
            }
        }
        return best
    }

    private fun regionMatches(s: String, start: Int, sub: String): Boolean =
        start + sub.length <= s.length && s.regionMatches(start, sub, 0, sub.length)

    private fun n(s: String): Int = s.length
}
