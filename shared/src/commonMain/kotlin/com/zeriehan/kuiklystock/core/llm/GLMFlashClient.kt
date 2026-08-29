package com.zeriehan.kuiklystock.core.llm

import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.formatPrice
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 智谱 GLM Flash 系列（免费额度）真实接入点。
 *
 * 调用链路：
 * 1. shared 侧拼好中文分析 prompt（[buildPrompt]）；
 * 2. 经 `BridgeModule.llmAnalyze` 下发到宿主；
 * 3. 宿主 `KRBridgeModule` 用 HttpURLConnection 调
 *    `POST https://open.bigmodel.cn/api/paas/v4/chat/completions`
 *    （Authorization: Bearer，Key 由 local.properties 经 BuildConfig 注入）；
 * 4. 宿主把 `choices[0].message.content` 以 `{ "text": "..." }` 回调回来。
 *
 * 具体模型不在此处指定：宿主侧维护候选链（glm-4.7-flash → glm-4.5-flash → glm-4-flash），
 * 免费池限流（1305）时自动降级，跨端只关心"给我一段分析文本"。
 *
 * 任何失败（未配 Key / 全部模型限流 / 网络异常 / 解析失败）都表现为 text 为空串，
 * 此时自动回退 [fallback]，保证上层（详情页卡片、行情行右滑面板）始终有内容，
 * 不会卡在"AI 分析中…"。
 */
class GLMFlashClient(private val fallback: LLMClient) : LLMClient {

    override fun analyze(
        stock: Stock,
        kline: List<KLineBar>,
        callback: (String) -> Unit
    ) {
        val prompt = buildPrompt(stock, kline)
        try {
            Utils.currentBridgeModule().llmAnalyze(prompt) { resp ->
                val text = resp?.optString("text") ?: ""
                if (text.isBlank()) {
                    fallback.analyze(stock, kline, callback)
                } else {
                    callback(text)
                }
            }
        } catch (e: Throwable) {
            // 桥不可用（极端情况如页面已销毁）→ 回退 Mock，保证不卡死在"分析中"
            fallback.analyze(stock, kline, callback)
        }
    }

    /**
     * 构造证券分析师口吻的中文 prompt。要求纯文本、用【】分段，避免 Markdown 语法
     * （本工程无 Markdown 渲染组件，AI 文本直接用 Text 直出）。
     */
    private fun buildPrompt(stock: Stock, kline: List<KLineBar>): String {
        val closes = kline.map { formatPrice(it.close) }
        val periodDesc = when {
            kline.size <= 12 -> "近 ${kline.size} 个周期"
            else -> "近 ${kline.size} 个交易日"
        }
        return buildString {
            appendLine("你是一名资深证券分析师。请基于以下股票量价数据，给出简洁、专业、客观的分析。")
            appendLine("严格要求：仅输出纯文本，不要使用任何 Markdown 符号（如 #、** 等），用【】标注段落标题。")
            appendLine("总字数控制在 300 字以内。")
            appendLine()
            appendLine("股票：${stock.name}（${stock.code}）")
            appendLine("现价：${formatPrice(stock.price)}  涨跌幅：${formatPrice(stock.changePercent)}%")
            appendLine("最高：${formatPrice(stock.high)}  最低：${formatPrice(stock.low)}")
            appendLine("成交量：${formatPrice(stock.volume)} 万手")
            appendLine("${periodDesc}收盘价序列（由远及近）：${closes.joinToString(", ")}")
            appendLine()
            appendLine("请按以下四部分输出：")
            appendLine("【综合研判】当前多空格局与今日表现")
            appendLine("【走势研判】价格趋势与关键波动区间")
            appendLine("【量价分析】成交量与价格配合关系")
            appendLine("【操作建议】给出风险提示与参考操作（强调仅供参考、不构成投资建议）")
        }.trimEnd()
    }
}
