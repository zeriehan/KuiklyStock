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
        onDelta: ((String) -> Unit)?,
    ) {
        val prompt = buildChatPrompt(stock, question, history, freeMode)
        // 流式会话 id：shared 轮询宿主缓存用它定位；带时间戳避免并发/重进冲突
        val sid = "llm_${System.currentTimeMillis()}"
        var finalized = false
        fun finish(text: String) {
            if (finalized) return
            finalized = true
            if (text.isBlank()) {
                // 真实链路全部失败/未配 Key → 回退 Mock（Mock 也会回调最终文本）
                fallback.chat(stock, question, history, callback, freeMode, onDelta)
            } else {
                callback(text)
            }
        }
        try {
            // 走 AIJobCenter（常驻根页面桥）：退出聊天页后 AI 仍在"后台"生成
            AIJobCenter.sendPrompt(prompt, stream = true, sid = sid) { resp ->
                if (resp == null) {
                    finish("")
                    return@sendPrompt
                }
                // 收尾：宿主流式结束会回调一次 {type:"done", text}；兼容旧宿主无 type 也当完成。
                // 真实逐字增量由下方 pumpStream 轮询宿主缓存驱动（桥单次回调不透传多次）。
                finish(resp.optString("text"))
            }
            // 轮询泵：绑常驻根页定时器，周期性拉宿主累计文本喂给 onDelta → 聊天气泡"逐字蹦出"。
            // finished=true（生成结束）自动停；每段 onDelta 幂等，配合 done 回调收敛不重复落库。
            if (onDelta != null) {
                AIJobCenter.pumpStream(sid, POLL_INTERVAL_MS) { text, finished ->
                    onDelta(text)
                    if (finished) {
                        // 轮询已看到结束；done 回调可能还没到，这里不主动 finish，等 done 兜底（保重 finalized 单次）
                    }
                }
            }
        } catch (e: Throwable) {
            // 桥不可用（极端情况如页面已销毁）→ 回退 Mock，保证不卡死在"分析中"
            finish("")
        }
    }

    companion object {
        /** 流式轮询间隔：160ms，约 6~7 次/秒，观感为逐字蹦出且不刷爆 UI */
        private const val POLL_INTERVAL_MS = 160
    }

    /**
     * 构造多轮问答 prompt。
     *
     * 数据上下文来自 [StockData]（真实行情优先、演示数据兜底），因此：
     * - 个股模式：附上实时价/涨跌幅/量 + 近 10 日收盘价序列，让 AI 能谈"趋势"而非只谈单点价格；
     * - 自由模式（[freeMode]）：不绑个股，改为附三大指数作为大盘参照；
     * - 明确告知数据来源，避免 AI 把演示数据当成真实行情来断言。
     * 要求模型用 Markdown（# 标题 / **加粗** / - 列表 / 提及股票带代码），供 KRMarkdown 富文本渲染。
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
        val srcDesc = if (StockData.isReal()) "实时行情·腾讯/新浪" else "本地演示数据"
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
            if (freeMode) {
                // 引导模型优先提及界面能实时展示行情卡片的池内股票（Task02：提及股→末尾附迷你走势卡片可点跳详情）
                val pool = StockData.getQuotes().filter { !it.isIndex }.take(18)
                if (pool.isNotEmpty()) {
                    appendLine("如需谈及个股，请优先从下方这些可实时查看行情详情的股票中选择，并写全称（方便用户点卡片看走势）：")
                    appendLine(pool.joinToString("、") { it.name })
                    appendLine()
                }
            }
            appendLine("输出格式要求：请用标准 Markdown 组织回答，让内容层次清晰（App 会将其渲染成富文本）：")
            appendLine("- 用 # 或 ## 作为段落/要点标题（每个要点一个标题），正文作为标题下方普通段落；")
            appendLine("- 关键结论可用 **加粗**；并列项可用 - 无序列表；引用强调用 > 引用；")
            appendLine("- 提及具体股票时，请同时写出股票名与 6 位代码，如“贵州茅台(600519)”，不要只用简称；")
            appendLine("- 全文用空行分隔不同段落，避免一大段；总字数控制在 300 字以内，避免过长。")
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
            appendLine("数据来源：${if (StockData.isReal()) "实时行情（腾讯/新浪）" else "本地演示数据"}")
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
