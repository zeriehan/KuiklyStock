package com.zeriehan.kuiklystock.core.llm

import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.formatPrice
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

/**
 * 智谱 GLM Flash 系列（免费额度）真实接入点。
 *
 * 调用链路：
 * 1. shared 侧拼好中文分析 prompt（[buildPrompt]）；
 * 2. 经 [AIJobCenter] 下发到宿主 `BridgeModule.llmAnalyze`；
 *    ⚠️ 必须走 AIJobCenter（常驻根页面桥），不能用"当前页"的桥 ——
 *    否则退出聊天页/详情页后回调会随页面销毁而丢失（AI 不再回复）；
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
            // 走 AIJobCenter（常驻根页面桥）：页面关闭后请求与回调依然有效
            AIJobCenter.sendPrompt(prompt) { resp ->
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

    override fun chat(
        stock: Stock,
        question: String,
        history: List<ChatStore.ChatMessage>,
        callback: (String) -> Unit,
        freeMode: Boolean,
    ) {
        val prompt = buildChatPrompt(stock, question, history, freeMode)
        try {
            // 走 AIJobCenter（常驻根页面桥）：退出聊天页后 AI 仍会在"后台"回复
            AIJobCenter.sendPrompt(prompt) { resp ->
                val text = resp?.optString("text") ?: ""
                if (text.isBlank()) {
                    fallback.chat(stock, question, history, callback, freeMode)
                } else {
                    callback(text)
                }
            }
        } catch (e: Throwable) {
            fallback.chat(stock, question, history, callback, freeMode)
        }
    }

    /**
     * 构造多轮问答 prompt。
     *
     * 数据上下文来自 [StockData]（真实行情优先、演示数据兜底），因此：
     * - 个股模式：附上实时价/涨跌幅/量 + 近 10 日收盘价序列，让 AI 能谈"趋势"而非只谈单点价格；
     * - 自由模式（[freeMode]）：不绑个股，改为附三大指数作为大盘参照；
     * - 明确告知数据来源，避免 AI 把演示数据当成真实行情来断言。
     * 仍要求纯文本、用【】分段，避免 Markdown。
     */
    private fun buildChatPrompt(
        stock: Stock,
        question: String,
        history: List<ChatStore.ChatMessage>,
        freeMode: Boolean,
    ): String {
        val recent = history.takeLast(6).joinToString("\n") { msg ->
            val who = if (msg.role == "user") "用户" else "AI"
            "$who：${msg.text}"
        }
        val srcDesc = if (StockData.isReal()) "实时行情·东方财富" else "本地演示数据"
        // 数据上下文：自由模式给大盘参照，个股模式给实时量价 + 近期 K 线
        val dataCtx = if (freeMode) {
            val indices = listOf("000001", "399001", "399006").mapNotNull { c ->
                StockData.getQuotes().firstOrNull { it.code == c }
            }
            buildString {
                appendLine("==== 大盘参照（$srcDesc）====")
                if (indices.isEmpty()) {
                    appendLine("（暂无行情数据）")
                } else {
                    indices.forEach { s ->
                        val d = if (s.changePercent >= 0f) "涨" else "跌"
                        appendLine("${s.name}：${formatPrice(s.price)}（今日$d${formatPrice(kotlin.math.abs(s.changePercent))}%）")
                    }
                }
                appendLine("====================")
            }
        } else {
            val kline = StockData.getKLine(stock, "日", 10)
            val closes = kline.map { formatPrice(it.close) }
            buildString {
                appendLine("==== 个股数据（$srcDesc）====")
                appendLine("股票：${stock.name}（${stock.code}）")
                appendLine("现价：${formatPrice(stock.price)}  涨跌幅：${formatPrice(stock.changePercent)}%")
                appendLine("最高：${formatPrice(stock.high)}  最低：${formatPrice(stock.low)}  成交量：${formatPrice(stock.volume)} 万手")
                appendLine("近 ${kline.size} 个交易日收盘价（由远及近）：${closes.joinToString(", ")}")
                appendLine("====================")
            }
        }
        return buildString {
            if (freeMode) {
                appendLine("你是一名资深证券分析师，正在和用户进行自由的财经问答。请专业、客观、审慎地作答。")
                appendLine("用户可能问大盘、宏观、行业或任意个股；缺少实时数据时基于公开常识谨慎作答，并提示以实时行情为准。")
            } else {
                appendLine("你是一名资深证券分析师，正在和用户聊一只股票。请基于股票信息与对话历史，专业、客观地回答用户的问题。")
            }
            appendLine("严格要求：仅输出纯文本，不要使用任何 Markdown 符号（如 #、** 等），用【】标注段落标题，字数控制在 300 字以内。")
            appendLine()
            append(dataCtx)
            appendLine()
            if (recent.isNotBlank()) {
                appendLine("==== 对话历史 ====")
                appendLine(recent)
                appendLine("====================")
                appendLine()
            }
            appendLine("用户当前问题：$question")
            appendLine()
            appendLine("请直接回答用户问题；若涉及操作，务必强调仅供参考、不构成投资建议。")
        }.trimEnd()
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
            appendLine("数据来源：${if (StockData.isReal()) "实时行情（东方财富）" else "本地演示数据"}")
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
