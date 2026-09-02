package com.zeriehan.kuiklystock.components.KRMiniTimeSharing

import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.TimeSharingPoint
import com.zeriehan.kuiklystock.core.formatPrice

/**
 * 迷你分时图（行情展开行 Page1 专用）。
 * - 价格线（涨红跌绿，相对昨收） + 均价黄线 + 昨收虚线基准；
 * - 左上角显示最新价（带颜色）；
 * - 点击出现十字光标：竖线 + 横线，底部标时间、右侧标价格。
 *
 * 用法：
 *   KRMiniTimeSharing {
 *       points  = StockData.getIntraday(stock)  // 当天分时（休市则取最近交易日）
 *       refPrice = (stock.price - stock.change)        // 昨收基准
 *       color   = StockColor.of(stock.changePercent)
 *   }
 */
internal class KRMiniTimeSharing : ComposeView<KRMiniTimeSharingAttr, ComposeEvent>() {

    var points: List<TimeSharingPoint> by observable(emptyList())
    var refPrice: Float by observable(0f)
    var color: Color by observable(Color(0xFFE54D42))

    private var crossX: Float by observable(-1f)
    private var crossY: Float by observable(-1f)
    private var crossActive: Boolean by observable(false)

    override fun createAttr() = KRMiniTimeSharingAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Canvas(
                {
                    attr { height(122f) }
                    event {
                        click { param -> ctx.setCross(param.x, param.y) }
                    }
                }
            ) { c, w, h ->
                val pts = ctx.points
                if (pts.size < 2 || w <= 0f || h <= 0f) return@Canvas
                val ref = ctx.refPrice
                val prices = pts.map { it.price }
                val avgs = pts.map { it.avg }
                val min = minOf(prices.minOrNull() ?: 0f, avgs.minOrNull() ?: 0f, ref)
                val max = maxOf(prices.maxOrNull() ?: 1f, avgs.maxOrNull() ?: 1f, ref)
                val range = if (max - min == 0f) 1f else max - min
                val top = 16f
                val bot = h - 14f
                val usable = bot - top
                val stepX = w / (pts.size - 1)
                val yOf: (Float) -> Float = { p -> top + (1f - (p - min) / range) * usable }

                // 昨收基准虚线（灰）
                c.strokeStyle(StockColor.FLAT)
                c.lineWidth(1f)
                ctx.dashLine(c, 0f, yOf(ref), w, yOf(ref))

                // 均价黄线
                c.beginPath()
                c.strokeStyle(Color(0xFFF5A623))
                c.lineWidth(1.2f)
                avgs.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = yOf(v)
                    if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
                }
                c.stroke()

                // 价格线（涨红跌绿）
                c.beginPath()
                c.strokeStyle(ctx.color)
                c.lineWidth(1.6f)
                prices.forEachIndexed { i, v ->
                    val x = i * stepX
                    val y = yOf(v)
                    if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
                }
                c.stroke()

                // 左上最新价（带颜色）
                val last = prices.last()
                c.fillStyle(ctx.color)
                c.font(12f)
                c.fillText(formatPrice(last), 4f, 12f)

                // 十字光标
                if (ctx.crossActive) {
                    var idx = (ctx.crossX / stepX).toInt()
                    idx = idx.coerceIn(0, pts.size - 1)
                    val cx = idx * stepX
                    val cy = ctx.crossY.coerceIn(top, bot)
                    c.strokeStyle(StockColor.FLAT)
                    c.lineWidth(1f)
                    ctx.dashLine(c, cx, top, cx, bot)
                    ctx.dashLine(c, 0f, cy, w, cy)
                    // 底部时间
                    val t = pts[idx].time
                    c.fillStyle(Color(0xFF666666))
                    c.font(10f)
                    c.fillText(t, (cx - 16f).coerceIn(0f, (w - 32f).coerceAtLeast(0f)), h - 3f)
                    // 右侧价格
                    val pr = pts[idx].price
                    c.fillStyle(ctx.color)
                    c.fillText(formatPrice(pr), (w - 46f).coerceAtLeast(0f), cy - 4f)
                }
            }
        }
    }

    private fun setCross(x: Float, y: Float) {
        crossX = x
        crossY = y
        crossActive = true
    }

    /** 简易虚线：逐段描 4px 实线 + 4px 空，避免依赖 setLineDash 兼容性 */
    private fun dashLine(c: CanvasContext, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dash = 4f
        val gap = 4f
        val dist = kotlin.math.hypot((x2 - x1).toDouble(), (y2 - y1).toDouble()).toFloat()
        if (dist == 0f) return
        val dx = (x2 - x1) / dist
        val dy = (y2 - y1) / dist
        var d = 0f
        while (d < dist) {
            val s = d
            val e = (d + dash).coerceAtMost(dist)
            c.beginPath()
            c.moveTo(x1 + dx * s, y1 + dy * s)
            c.lineTo(x1 + dx * e, y1 + dy * e)
            c.stroke()
            d += dash + gap
        }
    }
}

internal class KRMiniTimeSharingAttr : ComposeAttr()

internal fun ViewContainer<*, *>.KRMiniTimeSharing(init: KRMiniTimeSharing.() -> Unit) {
    addChild(KRMiniTimeSharing(), init)
}
