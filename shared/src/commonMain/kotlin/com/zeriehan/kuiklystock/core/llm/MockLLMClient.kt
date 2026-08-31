package com.zeriehan.kuiklystock.core.llm

import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.llm.ChatStore
import kotlin.math.abs

/**
 * 股票感知的 Mock 分析：根据量价与近期走势生成结构化、确定性的分析文本。
 *
 * 不依赖网络 / API Key，保证演示链路始终可用；接入真实 GLM-4-Flash 后整体替换即可，
 * 上层（详情页卡片、行情行右滑面板）无需改动。
 */
class MockLLMClient : LLMClient {
    override fun analyze(
        stock: Stock,
        kline: List<KLineBar>,
        callback: (String) -> Unit
    ) {
        callback(build(stock, kline))
    }

    override fun chat(
        stock: Stock,
        question: String,
        history: List<ChatStore.ChatMessage>,
        callback: (String) -> Unit,
    ) {
        callback(buildChat(stock, question))
    }

    private fun buildChat(stock: Stock, question: String): String {
        val up = stock.changePercent >= 0f
        val q = question.trim()
        val trendWord = if (stock.changePercent > 1.5f) "强势上行" else if (stock.changePercent < -1.5f) "弱势回调" else "区间震荡"
        return buildString {
            appendLine("【关于${stock.name}（${stock.code}）】")
            appendLine("")
            appendLine("你问的是：$q")
            appendLine("")
            appendLine("【简要回应】")
            appendLine(
                "${stock.name} 今日${if (up) "上涨" else "下跌"}${formatPercent(abs(stock.changePercent))}，现价 ${formatPrice(stock.price)}，" +
                    "当前呈${trendWord}格局，最高 ${formatPrice(stock.high)}、最低 ${formatPrice(stock.low)}。"
            )
            appendLine("")
            appendLine("【参考思路】")
            appendLine(
                if (q.contains("买") || q.contains("入") || q.contains("建仓"))
                    "若考虑介入，建议等待放量突破或回踩关键支撑确认后再分批，避免追高；严格设置止损。"
                else if (q.contains("卖") || q.contains("出") || q.contains("减"))
                    "若考虑兑现，可沿短期均线上方分批了结、锁定利润，保留底仓观察趋势延续性。"
                else
                    "可结合量能变化与板块联动进一步判断；当前方向尚不明朗时以观望为主。"
            )
            appendLine("")
            appendLine("（以上由大模型基于量价数据生成，仅供参考，不构成投资建议）")
        }.trimEnd()
    }

    private fun build(stock: Stock, kline: List<KLineBar>): String {
        val first = kline.firstOrNull()?.close ?: stock.price
        val last = kline.lastOrNull()?.close ?: stock.price
        val periodChg = if (first != 0f) (last - first) / first * 100f else 0f
        val up = stock.changePercent >= 0f
        val stance = when {
            stock.changePercent > 1.5f -> "强势上行"
            stock.changePercent < -1.5f -> "弱势回调"
            else -> "区间震荡"
        }
        val volDesc = if (stock.volume > 50f) "明显放大" else "温和"
        val trendWord = if (periodChg >= 0f) "震荡抬升" else "震荡回落"
        val suggest = when {
            stock.changePercent > 1.5f ->
                "短期情绪偏热，注意追高风险，可沿均线分批了结部分仓位、锁定利润"
            stock.changePercent < -1.5f ->
                "下跌中量能$volDesc，关注前低支撑有效性，激进者可小仓试错并严格止损"
            else ->
                "方向尚不明朗，建议以观望为主，等待放量突破或回踩确认后再做决策"
        }
        return buildString {
            appendLine("【${stock.name}（${stock.code}）AI 速览】")
            appendLine("")
            appendLine(
                "综合研判：${stock.name} 当前呈${stance}格局，今日${if (up) "上涨" else "下跌"}" +
                    "${formatPercent(abs(stock.changePercent))}，现价 ${formatPrice(stock.price)}。"
            )
            appendLine("")
            appendLine("【走势研判】")
            appendLine(
                "近 ${kline.size} 个周期价格${trendWord}，区间涨跌 ${formatPercent(periodChg)}。" +
                    "最高 ${formatPrice(stock.high)}、最低 ${formatPrice(stock.low)}，该波动区间为近期主要博弈带。"
            )
            appendLine("")
            appendLine("【量价分析】")
            appendLine(
                "今日成交量 ${formatPrice(stock.volume)} 万手，量能${volDesc}；" +
                    "价${if (up) "涨" else "跌"}量${if (stock.volume > 30f) "增" else "平"}，短期动能${if (up) "延续" else "趋弱"}。"
            )
            appendLine("")
            appendLine("【操作建议】")
            appendLine("$suggest。")
            appendLine("")
            appendLine("（以上由大模型基于量价数据生成，仅供参考，不构成投资建议）")
        }.trimEnd()
    }
}
