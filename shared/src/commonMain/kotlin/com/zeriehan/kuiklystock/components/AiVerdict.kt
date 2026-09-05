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
 * 兼容轻微格式差异；解析不到(老缓存/模型没给) → 返回 (null, 原文本)，调用方只显示正文、不崩。
 */
internal fun parseAiVerdict(text: String): Pair<AiVerdict?, String> {
    if (text.isBlank()) return null to text
    val riskRegex = Regex("风险[：:](\\s*[高中低]\\s*(?:风险)?)")
    val actionRegex = Regex("操作建议?[：:](\\s*(?:买入|加仓|持有|观望|减持|卖出))")
    val riskM = riskRegex.find(text)
    val actionM = actionRegex.find(text)
    if (riskM == null || actionM == null) return null to text
    val rawRisk = riskM.groupValues[1].trim()
    val rawAction = actionM.groupValues[1].trim()
    val risk = when {
        rawRisk.contains("高") -> "高风险"
        rawRisk.contains("低") -> "低风险"
        else -> "中风险"
    }
    val action = when {
        rawAction.contains("买入") || rawAction.contains("加仓") -> "买入"
        rawAction.contains("卖出") || rawAction.contains("减持") -> "卖出"
        else -> "持有"
    }
    // 剥整条【AI观点】结论行（行首→行尾含换行）
    val lineStart = text.lastIndexOf('\n', riskM.range.first).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', actionM.range.last).let { if (it < 0) text.length else it + 1 }
    val body = (text.substring(0, lineStart).trimEnd() + "\n" + text.substring(lineEnd)).trimStart('\n').trim()
    return AiVerdict(risk, action) to body
}

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
