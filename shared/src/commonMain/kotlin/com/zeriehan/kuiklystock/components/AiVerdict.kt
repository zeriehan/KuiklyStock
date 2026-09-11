package com.zeriehan.kuiklystock.components

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*

/**
 * AI 结论（风险档 + 操作建议）的两个醒目"徽章按钮"，详情页 AI 卡与聊天 AI 气泡共用。
 *
 * 语义：风险 = "按该操作去做的风险"（操作风险），非整只股票的笼统风险。小标签分别为「操作建议」「操作风险」。
 */
internal data class AiVerdict(val risk: String, val action: String)

/**
 * 从 AI 文本中解析【AI观点】结论行 → (结论, 剥离结论行后的正文)。
 *
 * 模型给的格式经常飘，实测见过这些写法，都要能认出来：
 *   【AI观点】风险：中风险｜操作建议：持有     ← 规范写法
 *   【AI观点】高风险｜持有                    ← 省掉两个标签
 *   【AI观点】操作建议：卖出｜风险：低风险     ← 顺序反过来
 *   高风险｜持有                              ← 整行没写【AI观点】
 * 所以策略是：先找含【AI观点】的那一行（找不到就找"整行只由风险档和操作词构成"的行），
 * 再在这一行里宽松地取风险档和操作建议；两样都拿不到就当没有结论。
 * 解析不到（老缓存 / 模型压根没给）→ 返回 (null, 原文本)，调用方只显示正文、不崩。
 */
internal fun parseAiVerdict(text: String): Pair<AiVerdict?, String> {
    if (text.isBlank()) return null to text
    val lines = text.split("\n")
    // 每行起始偏移，用于最后精确剥行
    val starts = IntArray(lines.size)
    var acc = 0
    lines.forEachIndexed { i, l -> starts[i] = acc; acc += l.length + 1 }

    // 1) 优先含【AI观点】标记的行
    var lineIdx = lines.indexOfFirst { it.contains("AI观点") }
    // 2) 没有标记：退而找一整行就是结论的（严格匹配，避免误伤正文）
    if (lineIdx < 0) lineIdx = lines.indexOfFirst { VERDICT_ONLY_LINE.matches(it.trim()) }
    if (lineIdx < 0) return null to text

    val verdict = parseVerdictLine(lines[lineIdx]) ?: return null to text

    // 剥掉结论行整行（含其换行）
    val start = starts[lineIdx]
    val end = (start + lines[lineIdx].length + 1).coerceAtMost(text.length)
    val body = (text.substring(0, start).trimEnd() + "\n" + text.substring(end))
        .trimStart('\n').trim()
    return verdict to body
}

/** 在一行里宽松地取风险档 + 操作建议；任一取不到返回 null */
private fun parseVerdictLine(line: String): AiVerdict? {
    val l = line.replace('：', ':')
    val action = ACTION_HINTS.firstOrNull { l.contains(it) } ?: return null
    val risk = when {
        l.contains("高") -> "高风险"
        l.contains("低") -> "低风险"
        l.contains("中") -> "中风险"
        else -> return null
    }
    return AiVerdict(risk, normalizeAction(action))
}

private fun normalizeAction(raw: String): String = when {
    raw == "买入" || raw == "加仓" -> "买入"
    raw == "卖出" || raw == "减持" -> "卖出"
    else -> "持有"   // 持有 / 观望 都归为持有
}

/** 操作建议相关词（含模型可能用的近义词） */
private val ACTION_HINTS = listOf("买入", "加仓", "持有", "观望", "减持", "卖出")

/** 无【AI观点】标记时，允许"整行就是结论"的严格形式（风险档 + 操作词，可带标签与分隔符） */
private val VERDICT_ONLY_LINE = Regex(
    "^[-•*>】]?\\s*(?:操作风险|风险)?\\s*[:：]?\\s*(?:高|中|低)(?:\\s*风险)?" +
        "\\s*[｜|/,，、\\s]+\\s*(?:操作建议)?\\s*[:：]?\\s*(?:买入|加仓|持有|观望|减持|卖出)\\s*$" +
        "|" +
        "^[-•*>】]?\\s*(?:操作建议)?\\s*[:：]?\\s*(?:买入|加仓|持有|观望|减持|卖出)" +
        "\\s*[｜|/,，、\\s]+\\s*(?:操作风险|风险)?\\s*[:：]?\\s*(?:高|中|低)(?:\\s*风险)?\\s*$"
)

/** 在本容器内渲染一行「操作建议 + 操作风险」两个居中徽章 */
internal fun ViewContainer<*, *>.renderAiVerdictBadges(verdict: AiVerdict) {
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); justifyContentCenter()
            marginTop(8f)
        }
        val isBuy = verdict.action == "买入"
        val isSell = verdict.action == "卖出"
        val actionColor = if (isBuy) Color(0xFFE54D42) else if (isSell) Color(0xFF1ABE5B) else Color(0xFF8A8A8A)
        verdictBadge(verdict.action, actionColor, "操作建议")
        val riskColor = when (verdict.risk) {
            "低风险" -> Color(0xFF1ABE5B); "高风险" -> Color(0xFFE54D42); else -> Color(0xFFFF9800)
        }
        verdictBadge(verdict.risk, riskColor, "操作风险")
    }
}

/** 单个"徽章按钮"：实色胶囊 + 上小标签下大粗白字 */
private fun ViewContainer<*, *>.verdictBadge(value: String, color: Color, caption: String) {
    View {
        attr {
            flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
            marginRight(12f)
            height(46f)
            padding(left = 18f, right = 18f)
            borderRadius(10f)
            backgroundColor(color)
        }
        Text { attr { text(caption); fontSize(9f); color(Color(0xCCFFFFFF)) } }
        Text { attr { text(value); fontSize(17f); fontWeightSemisolid(); color(Color.WHITE); marginTop(1f) } }
    }
}
