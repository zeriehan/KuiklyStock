package com.zeriehan.kuiklystock.components.KRKLineChart

import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.StockColor

/**
 * 自研 K线蜡烛图（基于 Kuikly Canvas）。
 * 绘制蜡烛（涨红跌绿）+ 下方成交量柱。
 *
 * 注意：Kuikly Canvas 无 fillRect，矩形须用 beginPath → moveTo/lineTo → closePath → fillStyle → fill 描路径。
 * 数据驱动：bars 为 observable，赋值即重绘。
 *
 * 用法：
 *   KRKLineChart { bars = stockKLine }
 */
internal class KRKLineChart : ComposeView<ComposeAttr, ComposeEvent>() {

    var bars: List<KLineBar> by observable(emptyList())

    override fun createAttr() = ComposeAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Canvas(
                {
                    attr {
                        height(240f)
                    }
                }
            ) { c, w, h ->
                val bars = ctx.bars
                if (bars.isEmpty() || w <= 0f || h <= 0f) return@Canvas
                val pad = 8f
                val candleAreaH = h * 0.72f
                val volTop = h * 0.80f
                val volAreaH = h - volTop - pad
                val max = bars.maxOf { it.high }
                val min = bars.minOf { it.low }
                val range = (max - min).coerceAtLeast(0.01f)
                val n = bars.size
                val slot = w / n
                val cw = (slot * 0.6f).coerceAtLeast(1f)
                val maxVol = bars.maxOf { it.volume }.coerceAtLeast(0.01f)

                bars.forEachIndexed { i, bar ->
                    val cx = i * slot + slot / 2f
                    val color = if (bar.close >= bar.open) StockColor.UP else StockColor.DOWN

                    // 上下影线
                    val yHigh = pad + (1f - (bar.high - min) / range) * candleAreaH
                    val yLow = pad + (1f - (bar.low - min) / range) * candleAreaH
                    c.beginPath()
                    c.strokeStyle(color)
                    c.lineWidth(1f)
                    c.moveTo(cx, yHigh)
                    c.lineTo(cx, yLow)
                    c.stroke()

                    // 实体（矩形路径）
                    val yOpen = pad + (1f - (bar.open - min) / range) * candleAreaH
                    val yClose = pad + (1f - (bar.close - min) / range) * candleAreaH
                    val top = minOf(yOpen, yClose)
                    val bh = (maxOf(yClose, yOpen) - top).coerceAtLeast(1f)
                    c.beginPath()
                    c.moveTo(cx - cw / 2f, top)
                    c.lineTo(cx + cw / 2f, top)
                    c.lineTo(cx + cw / 2f, top + bh)
                    c.lineTo(cx - cw / 2f, top + bh)
                    c.closePath()
                    c.fillStyle(color)
                    c.fill()

                    // 成交量
                    val vh = (bar.volume / maxVol) * volAreaH
                    val vy = h - pad - vh
                    c.beginPath()
                    c.moveTo(cx - cw / 2f, vy)
                    c.lineTo(cx + cw / 2f, vy)
                    c.lineTo(cx + cw / 2f, h - pad)
                    c.lineTo(cx - cw / 2f, h - pad)
                    c.closePath()
                    c.fillStyle(color)
                    c.fill()
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.KRKLineChart(init: KRKLineChart.() -> Unit) {
    addChild(KRKLineChart(), init)
}
