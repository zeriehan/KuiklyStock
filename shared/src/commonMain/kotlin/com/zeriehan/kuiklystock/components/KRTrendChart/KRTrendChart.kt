package com.zeriehan.kuiklystock.components.KRTrendChart

import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.TimeSharingPoint

/**
 * 迷你走势折线图（自研，基于 Kuikly Canvas）。高度可配。
 *
 * 兼容两种数据源：
 * - [realPoints]: List<TimeSharingPoint>（真实分时点，卡片据此显示真实走势，拉不到自动回退本地生成的分时）
 * - [points]: List<Float> 采样点（如 Stock.trend 的兜底）
 * 给定 realPoints 时自动取其 price 序列绘制，否则用 points。
 *
 * 用法：
 *   KRTrendChart {
 *       realPoints = StockData.getIntraday(stock)  // 真实分时
 *       color      = StockColor.of(stock.changePercent)
 *       chartHeight = 32f                            // 紧凑小图（默认 80）
 *   }
 */
internal class KRTrendChart : ComposeView<KRTrendChartAttr, ComposeEvent>() {

    var points: List<Float> by observable(emptyList())
    var realPoints: List<TimeSharingPoint> by observable(emptyList())
    var color: Color by observable(Color(0xFFE54D42))
    var chartHeight: Float by observable(80f)

    override fun createAttr() = KRTrendChartAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        // 真实分时优先：给到 realPoints 就用它，否则用 points
        val data: List<Float> = if (ctx.realPoints.isNotEmpty()) ctx.realPoints.map { it.price } else ctx.points
        val h = ctx.chartHeight
        return {
            Canvas(
                {
                    attr { height(h) }
                }
            ) { c, w, ch ->
                if (data.size < 2 || w <= 0f || ch <= 0f) return@Canvas
                val pad = 3f
                val usableH = ch - pad * 2f
                val min = data.minOrNull() ?: 0f
                val max = data.maxOrNull() ?: 1f
                val range = if (max - min == 0f) 1f else max - min
                val stepX = w / (data.size - 1)
                c.beginPath()
                c.strokeStyle(ctx.color)
                // 紧凑小图用稍细的线
                c.lineWidth(if (ch < 40f) 1.5f else 2.0f)
                c.lineCapRound()
                data.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = pad + (1f - (v - min) / range) * usableH
                    if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
                }
                c.stroke()
            }
        }
    }
}

internal class KRTrendChartAttr : ComposeAttr()

internal fun ViewContainer<*, *>.KRTrendChart(init: KRTrendChart.() -> Unit) {
    addChild(KRTrendChart(), init)
}

