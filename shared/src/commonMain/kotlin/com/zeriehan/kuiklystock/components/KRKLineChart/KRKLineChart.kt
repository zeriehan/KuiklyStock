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
 * 绘制蜡烛（涨红跌绿）+ 下方成交量柱 + 底部日期；外层横向 Scroller 可滑动查看更多。
 * 注：Kuikly Canvas 的 fontSize 为 private，无法直接设字号；故"成交量"标签改由外部
 *     Text 叠加层（精确 8.4 = 标题14 的 3/5）绘制，日期用 Canvas 默认字号 fillText。
 *
 * 数据驱动：bars 为 observable，赋值即重绘；Canvas 宽度随 bars 数量响应式变化。
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
            // 外层横向滚动容器：内容宽度 = bars 数量 × 柱宽，超出视口即可滑动
            Scroller {
                attr {
                    flexDirectionRow()
                    height(240f)
                }
                Canvas(
                    {
                        attr {
                            height(240f)
                            width(ctx.bars.size * 11f + 16f)
                        }
                    }
                ) { c, w, h ->
                    val bars = ctx.bars
                    if (bars.isEmpty() || w <= 0f || h <= 0f) return@Canvas
                    val pad = 8f
                    val candleAreaH = h * 0.60f
                    val volTop = h * 0.70f
                    val volAreaH = h * 0.20f
                    val dateTop = h * 0.90f
                    val max = bars.maxOf { it.high }
                    val min = bars.minOf { it.low }
                    val range = (max - min).coerceAtLeast(0.01f)
                    val n = bars.size
                    val slot = w / n
                    val cw = (slot * 0.6f).coerceAtLeast(2f)
                    val maxVol = bars.maxOf { it.volume }.coerceAtLeast(0.01f)

                    // 分界线：K线区与成交量区之间
                    c.beginPath()
                    c.moveTo(pad, volTop)
                    c.lineTo(w - pad, volTop)
                    c.strokeStyle(Color(0xFFEEEEEE))
                    c.lineWidth(1f)
                    c.stroke()

                    // 文字字号：标题(14) 的 3/5 ≈ 8.4
                    c.font(8.4f)

                    // 成交量区左上角标签
                    c.fillStyle(Color(0xFF999999))
                    c.fillText("成交量", pad + 2f, volTop - 4f)

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
                        val vy = volTop + volAreaH - vh
                        c.beginPath()
                        c.moveTo(cx - cw / 2f, vy)
                        c.lineTo(cx + cw / 2f, vy)
                        c.lineTo(cx + cw / 2f, volTop + volAreaH)
                        c.lineTo(cx - cw / 2f, volTop + volAreaH)
                        c.closePath()
                        c.fillStyle(color)
                        c.fill()
                    }

                    // 底部日期（每 5 根标一次，避免拥挤；Canvas 默认字号）
                    c.fillStyle(Color(0xFF999999))
                    bars.forEachIndexed { i, bar ->
                        if (i % 5 == 0 || i == n - 1) {
                            val cx = i * slot + slot / 2f
                            c.fillText(bar.date, cx - 12f, dateTop + 11f)
                        }
                    }
                }
            }
        }
    }
}

internal fun ViewContainer<*, *>.KRKLineChart(init: KRKLineChart.() -> Unit) {
    addChild(KRKLineChart(), init)
}
