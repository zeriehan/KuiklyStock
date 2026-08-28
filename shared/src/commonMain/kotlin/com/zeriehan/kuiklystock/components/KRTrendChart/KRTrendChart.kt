package com.zeriehan.kuiklystock.components.KRTrendChart

import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * 迷你走势折线图（自研，基于 Kuikly Canvas）。
 * 行情展开行迷你图 与 详情页大图 共用。
 *
 * 用法：
 *   KRTrendChart {
 *       points = stock.trend          // List<Float> 采样点
 *       color  = StockColor.UP        // 线条颜色（涨红跌绿）
 *   }
 */
internal class KRTrendChart : ComposeView<KRTrendChartAttr, ComposeEvent>() {

    var points: List<Float> by observable(emptyList())
    var color: Color by observable(Color(0xFFE54D42))

    override fun createAttr() = KRTrendChartAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Canvas(
                {
                    attr {
                        height(80f)
                    }
                }
            ) { c, w, h ->
                val data = ctx.points
                if (data.size < 2 || w <= 0f || h <= 0f) return@Canvas
                val pad = 6f
                val usableH = h - pad * 2f
                val min = data.minOrNull() ?: 0f
                val max = data.maxOrNull() ?: 1f
                val range = if (max - min == 0f) 1f else max - min
                val stepX = w / (data.size - 1)
                c.beginPath()
                c.strokeStyle(ctx.color)
                c.lineWidth(2.0f)
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
