package com.zeriehan.kuiklystock.components

import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.CanvasContext
import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.TimeSharingPoint
import com.zeriehan.kuiklystock.core.computeMA
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 聊天里的交互式迷你走势图（对标"AI 回复中嵌入可点走势"）。
 *
 * - 分时(intraday)：价格红线 + 均价黄线；日/周/月K：蜡烛(涨红跌绿) + MA5/10/20。
 * - 点击主图任意位置：吸附到最近一根 K线/分时点，绘制十字光标（竖线+横线+底部时间/日期标签+左轴价格标签）。
 * - 选中后图表下方出现「选中信息条 + 就这点问」按钮：点按钮把一段预填好的追问（含股票名/代码/时间/价/涨跌）
 *   通过 [onAsk] 回调传回聊天页，填入输入框供用户编辑后发送——实现"据交互信息继续讨论"。
 * - 数据：首次渲染若本地无数据，按需调 [StockData.loadTrends]/[StockData.loadKline]，回调写回 observable 后自动重绘。
 *
 * 布局说明：本组件使用**单个 Canvas** 绘制左价格轴+主图。
 *   之前用 flex row 里放两个 Canvas（左轴固定宽 + 主图 flex:1），在聊天气泡里主图 Canvas 的 flex:1 不生效，
 *   导致主图画布实际宽度异常、走势线不显示。合并为单 Canvas 后，左轴占左侧 LEFT_AXIS_W 区域，
 *   主图区域 = w - LEFT_AXIS_W，与 KRMiniTimeSharing/KRTrendChart 的"单 Canvas 撑满"模式一致，可靠性最高。
 *
 * 与详情页 KRKLineChart 的区别：本组件刻意做"迷你"——固定 ~150px 高、无缩放/指标副窗/加载更多，
 * 且对外暴露 [onAsk] 让选中点能回灌到对话。
 */
internal class KRChatMiniChart : ComposeView<ComposeAttr, ComposeEvent>() {

    /** 目标股票 */
    var stock: Stock by observable(StockData.findByCode("000001"))
    /** 周期：intraday / day / week / month */
    var period: String by observable("day")
    /** 选中点后，把预填追问传回聊天页（填入输入框，不直接发送） */
    var onAsk: ((String) -> Unit)? = null
    /** 组件可用宽度（由外部气泡传入，避免 Canvas 在 flex column 里拿不到宽度而空白） */
    var contentW: Float by observable(300f)

    /** 分时数据（非空即分时模式） */
    var timeSharing: List<TimeSharingPoint> by observable(emptyList())
    /** K线数据 */
    var bars: List<KLineBar> by observable(emptyList())
    /** 分时基准价（昨收） */
    var refPrice: Float by observable(0f)

    /** 十字光标状态 */
    var crossActive: Boolean by observable(false)
    var crossX: Float by observable(0f)
    var crossY: Float by observable(0f)
    /** 选中点信息（非空即展示「就这点问」条） */
    var picked: CrossInfo? by observable(null)

    /** 主画布最近一次真实内容宽（吸附十字光标用） */
    private var lastCanvasW: Float = 0f
    /** 是否已发起过数据拉取（避免每次 body 重跑都重复请求原生桥） */
    private var dataRequested: Boolean = false

    private val CHART_H = 150f
    private val TOP = 8f
    private val DATE_BOTTOM = 14f
    private val LEFT_AXIS_W = 44f

    private fun isIntraday() = period == "intraday"
    private fun isTimeSharing() = timeSharing.isNotEmpty()
    private fun periodLabel() = when (period) {
        "day" -> "日"; "week" -> "周"; "month" -> "月"; else -> "日"
    }
    private fun periodTitle() = when (period) {
        "intraday" -> "分时"; "day" -> "日K"; "week" -> "周K"; "month" -> "月K"; else -> "日K"
    }

    private fun priceBounds(): Pair<Float, Float> {
        return if (isTimeSharing()) {
            val ts = timeSharing
            if (ts.isEmpty()) return Pair(1f, 0f)
            var mx = ts.maxOf { maxOf(it.price, it.avg) }
            var mn = ts.minOf { minOf(it.price, it.avg) }
            if (refPrice > 0f) { mx = max(mx, refPrice); mn = min(mn, refPrice) }
            if (!mx.isFinite() || !mn.isFinite()) return Pair(1f, 0f)
            val pad = (mx - mn) * 0.08f + 0.01f
            Pair(mx + pad, mn - pad)
        } else {
            val bs = bars
            if (bs.isEmpty()) return Pair(1f, 0f)
            val mx = bs.maxOf { it.high }
            val mn = bs.minOf { it.low }
            if (!mx.isFinite() || !mn.isFinite()) return Pair(1f, 0f)
            val pad = (mx - mn) * 0.08f + 0.01f
            Pair(mx + pad, mn - pad)
        }
    }

    /** 把 x（相对单 Canvas）吸附到最近一根 K线/分时点中心，记录十字光标并算出选中信息 */
    private fun pickAt(x: Float, y: Float) {
        val mainX = (x - LEFT_AXIS_W).coerceAtLeast(0f)
        val n = if (isTimeSharing()) timeSharing.size else bars.size
        if (n == 0) return
        val s = if (n > 0 && lastCanvasW > 0f) lastCanvasW / n else 1f
        val idx = ((mainX - s / 2f) / s + 0.5f).toInt().coerceIn(0, n - 1)
        crossX = idx * s + s / 2f
        crossY = y
        crossActive = true
        picked = if (isTimeSharing()) {
            val hp = timeSharing[idx]
            val chg = if (refPrice != 0f) (hp.price - refPrice) / refPrice * 100f else 0f
            CrossInfo(hp.time, hp.price, chg, false, stock.name, stock.code, period)
        } else {
            val b = bars[idx]
            val prevClose = if (idx > 0) bars[idx - 1].close else 0f
            val chg = if (prevClose != 0f) (b.close - prevClose) / prevClose * 100f else 0f
            CrossInfo(b.date, b.close, chg, true, stock.name, stock.code, period)
        }
    }

    /** 预填追问文案 */
    private fun buildQuestion(p: CrossInfo): String {
        val nameCode = "${p.stockName}(${p.code})"
        return if (p.isKline) {
            "关于 $nameCode 在 ${p.label} 的日K（收 ${formatPrice(p.price)}，涨跌 ${formatPercent(p.chgPct)}），这一根线有什么说法？"
        } else {
            "关于 $nameCode 在 ${p.label} 的分时（价 ${formatPrice(p.price)}，涨跌 ${formatPercent(p.chgPct)}），当时为什么这么走？"
        }
    }

    private fun ensureData() {
        if (isIntraday()) {
            if (timeSharing.isEmpty()) {
                refPrice = StockData.intradayRefPrice(stock)
                if (!dataRequested) {
                    dataRequested = true
                    StockData.loadTrends(stock) { timeSharing = StockData.getIntraday(stock) }
                }
            }
        } else {
            if (bars.isEmpty()) {
                if (!dataRequested) {
                    dataRequested = true
                    val lbl = periodLabel()
                    StockData.loadKline(stock, lbl, 80) { bars = StockData.getKLine(stock, lbl, 80) }
                }
            }
        }
    }

    override fun createAttr() = ComposeAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        ctx.ensureData()
        return {
            View {
                attr {
                    flexDirectionColumn()
                    // 父 View 显式 width，让 Canvas 在 flex column 内能拿到真实宽度。
                    // 之前用 alignSelfStretch 但效果不稳（Canvas 在某些 flex 列里拿不到 layoutFrame.width，
                    // drawAll 里 w=0 导致完全空白），所以这里直接给 width(contentW)。
                    width(ctx.contentW)
                    marginTop(6f); marginBottom(2f)
                    padding(8f); borderRadius(10f)
                    backgroundColor(Color(0xFFF6F7F9))
                }

                // 头部：股票名 + 周期 + 「轻点图表选点」提示
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                    Text { attr { text("${ctx.stock.name} · ${ctx.periodTitle()}"); fontSize(12f); fontWeightSemisolid(); color(Color(0xFF333333)) } }
                    View { attr { flex(1f) } }
                    Text { attr { text("轻点选点可追问"); fontSize(10f); color(Color(0xFFAAAAAA)) } }
                }

                // 单 Canvas：左侧价格轴 + 主图（避免 flex row 里两个 Canvas 的兼容性问题）
                // ⚠️ **不显式设 width**：复刻 KRMiniTimeSharing 的成熟写法——只给 height，
                // Canvas 在父 View 的 padding 内自然 fill 到 (contentW - 16f)。
                // 之前显式 width(contentW - 16f) 在聊天气泡的 flex column 里 flex 算法有时算错，
                // 导致 flexNode.layoutFrame.width 在 drawCallback 里取到 0，整片空白。
                Canvas(
                    {
                        attr { height(ctx.CHART_H) }
                        event { click { param -> ctx.pickAt(param.x, param.y) } }
                    }
                ) { c, w, h -> ctx.drawAll(c, w, h) }

                // 选中信息条 + 「就这点问」
                vif({ ctx.picked != null }) {
                    val p = ctx.picked
                    if (p != null) {
                        View {
                            attr {
                                flexDirectionRow(); alignItemsCenter(); marginTop(6f)
                                padding(6f, 8f); borderRadius(8f)
                                backgroundColor(Color(0xFFEEF1F5))
                            }
                            Text {
                                attr {
                                    text("${p.label}  价 ${formatPrice(p.price)}  ${formatPercent(p.chgPct)}")
                                    fontSize(11f); color(Color(0xFF555555))
                                }
                            }
                            View { attr { flex(1f) } }
                            View {
                                attr {
                                    height(24f); padding(0f, 10f); borderRadius(12f)
                                    backgroundColor(Color(UserSettings.themeColor))
                                    alignItemsCenter(); justifyContentCenter()
                                }
                                event { click { ctx.onAsk?.invoke(ctx.buildQuestion(p)) } }
                                Text { attr { text("就这点问"); fontSize(11f); color(Color.WHITE) } }
                            }
                        }
                    }
                }
            }
        }
    }

    // ===================== 绘制 =====================

    /** 单 Canvas 入口：先画左轴，再在主图区域画走势 */
    private fun drawAll(c: CanvasContext, w: Float, h: Float) {
        val mainW = (w - LEFT_AXIS_W).coerceAtLeast(1f)
        lastCanvasW = mainW
        drawLeftAxis(c, LEFT_AXIS_W, h)
        drawMain(c, mainW, h, LEFT_AXIS_W)
    }

    private fun drawLeftAxis(c: CanvasContext, w: Float, h: Float) {
        val r = regions(h)
        val candleH = r.candleBottom - r.candleTop
        val (max, min) = priceBounds()
        val range = (max - min).coerceAtLeast(0.01f)
        c.font(9f)
        for (k in 0..4) {
            val y = r.candleTop + k * (candleH / 4f)
            val price = max - k * (range / 4f)
            c.fillStyle(Color(0xFF999999))
            c.fillText(formatPrice(price), 2f, y + 3f)
        }
        // 最新价标签
        val lastPrice = if (isTimeSharing()) timeSharing.lastOrNull()?.price ?: refPrice
        else bars.lastOrNull()?.close ?: 0f
        if (lastPrice > 0f) {
            val y = r.candleTop + (1f - (lastPrice - min) / range) * candleH
            val col = if (isTimeSharing()) Color(0xFFE54D42)
            else {
                val up = (bars.lastOrNull()?.close ?: 0f) >= (bars.lastOrNull()?.open ?: lastPrice)
                if (up) StockColor.UP else StockColor.DOWN
            }
            fillRect(c, 1f, y - 7f, w - 2f, 14f, col)
            c.fillStyle(Color.WHITE); c.fillText(formatPrice(lastPrice), 3f, y + 3f)
        }
        // 十字光标价格标签
        if (crossActive) {
            val cy = crossY.coerceIn(r.candleTop, r.candleBottom)
            val price = min + (1f - (cy - r.candleTop) / candleH) * range
            fillRect(c, 1f, cy - 7f, w - 2f, 14f, Color(0xFF555555))
            c.fillStyle(Color.WHITE); c.fillText(formatPrice(price), 3f, cy + 3f)
        }
    }

    private fun regions(h: Float): Regions {
        val top = TOP
        val usable = h - top - DATE_BOTTOM
        val candleBottom = top + usable
        return Regions(top, candleBottom)
    }

    /**
     * 绘制主图（不含左轴）。
     * @param w 主图区域宽度
     * @param offsetX 主图区域在单 Canvas 里的水平偏移
     */
    private fun drawMain(c: CanvasContext, w: Float, h: Float, offsetX: Float) {
        val r = regions(h)
        val (max, min) = priceBounds()
        val range = (max - min).coerceAtLeast(0.01f)
        val candleH = r.candleBottom - r.candleTop
        val dateTop = h - DATE_BOTTOM
        val n = if (isTimeSharing()) timeSharing.size else bars.size
        if (n == 0) {
            c.font(11f); c.fillStyle(Color(0xFFBBBBBB))
            c.fillText("暂无数据", offsetX + w / 2f - 22f, h / 2f)
            return
        }
        val s = if (w > 0f) w / n else 1f
        val priceToY: (Float) -> Float = { p -> r.candleTop + (1f - (p - min) / range) * candleH }

        // 横向网格
        c.strokeStyle(Color(0xFFEEEEEE)); c.lineWidth(1f)
        for (k in 0..4) {
            val y = r.candleTop + k * (candleH / 4f)
            c.beginPath(); c.moveTo(offsetX, y); c.lineTo(offsetX + w, y); c.stroke()
        }

        val hoverIdx = if (crossActive) ((crossX - s / 2f) / s).toInt().coerceIn(0, n - 1) else n - 1

        if (isTimeSharing()) {
            drawTimeSharing(c, w, r, s, priceToY, offsetX)
        } else {
            drawCandles(c, w, s, candleH, priceToY, offsetX)
        }

        // 十字光标（置顶）
        if (crossActive) drawCrosshair(c, w, dateTop, r, s, priceToY, hoverIdx, offsetX)
    }

    private fun drawCandles(c: CanvasContext, w: Float, s: Float, candleH: Float, priceToY: (Float) -> Float, offsetX: Float) {
        val bs = bars
        val n = bs.size
        val cw = (s * 0.6f).coerceAtLeast(2f)
        val closes = bs.map { it.close }
        val ma5 = computeMA(closes, 5)
        val ma10 = computeMA(closes, 10)
        val ma20 = computeMA(closes, 20)
        bs.forEachIndexed { i, bar ->
            val cx = offsetX + i * s + s / 2f
            val color = if (bar.close >= bar.open) StockColor.UP else StockColor.DOWN
            val yH = priceToY(bar.high); val yL = priceToY(bar.low)
            c.strokeStyle(color); c.lineWidth(1f)
            c.beginPath(); c.moveTo(cx, yH); c.lineTo(cx, yL); c.stroke()
            val yO = priceToY(bar.open); val yC = priceToY(bar.close)
            val top = minOf(yO, yC); val bh = (maxOf(yO, yC) - top).coerceAtLeast(1f)
            fillRect(c, cx - cw / 2f, top, cw, bh, color)
        }
        drawSeries(c, ma5, Color(0xFFF5A623), s, priceToY, offsetX)
        drawSeries(c, ma10, Color(0xFF3B82F6), s, priceToY, offsetX)
        drawSeries(c, ma20, Color(0xFF9C27B0), s, priceToY, offsetX)
        // 最新价虚线
        val last = bs.last()
        c.strokeStyle(if (last.close >= last.open) StockColor.UP else StockColor.DOWN); c.lineWidth(1f)
        dashLine(c, offsetX, priceToY(last.close), offsetX + lastCanvasW, priceToY(last.close))
    }

    private fun drawTimeSharing(c: CanvasContext, w: Float, r: Regions, s: Float, priceToY: (Float) -> Float, offsetX: Float) {
        val ts = timeSharing
        if (refPrice > 0f) {
            val yRef = priceToY(refPrice)
            c.strokeStyle(Color(0xFFBBBBBB)); c.lineWidth(1f)
            dashLine(c, offsetX, yRef, offsetX + w, yRef)
        }
        c.strokeStyle(Color(0xFFE54D42)); c.lineWidth(1.2f)
        c.beginPath()
        ts.forEachIndexed { i, p ->
            val x = offsetX + i * s + s / 2f
            val y = priceToY(p.price)
            if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
        }
        c.stroke()
        c.strokeStyle(Color(0xFFF5A623)); c.lineWidth(1f)
        c.beginPath()
        ts.forEachIndexed { i, p ->
            val x = offsetX + i * s + s / 2f
            val y = priceToY(p.avg)
            if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
        }
        c.stroke()
    }

    private fun drawCrosshair(c: CanvasContext, w: Float, dateTop: Float, r: Regions, s: Float, priceToY: (Float) -> Float, hoverIdx: Int, offsetX: Float) {
        val cx = offsetX + crossX.coerceIn(0f, w)
        val cy = crossY.coerceIn(r.candleTop, r.candleBottom)
        c.strokeStyle(Color(0xFF888888)); c.lineWidth(1f)
        dashLine(c, cx, r.candleTop, cx, dateTop)
        dashLine(c, offsetX, cy, offsetX + w, cy)
        val label = if (isTimeSharing()) timeSharing.getOrNull(hoverIdx)?.time ?: ""
        else bars.getOrNull(hoverIdx)?.date ?: ""
        if (label.isNotEmpty()) {
            val bw = (label.length * 7f) + 10f
            var bx = (cx - bw / 2f).coerceIn(offsetX, offsetX + w - bw)
            fillRect(c, bx, dateTop, bw, 14f, Color(0xFF555555))
            c.fillStyle(Color.WHITE); c.fillText(label, bx + 5f, dateTop + 10f)
        }
    }

    private fun fillRect(c: CanvasContext, x: Float, y: Float, w: Float, hh: Float, color: Color) {
        if (hh <= 0f || w <= 0f) return
        c.beginPath()
        c.moveTo(x, y)
        c.lineTo(x + w, y)
        c.lineTo(x + w, y + hh)
        c.lineTo(x, y + hh)
        c.closePath()
        c.fillStyle(color)
        c.fill()
    }

    private fun drawSeries(c: CanvasContext, list: List<Float?>, color: Color, s: Float, mapY: (Float) -> Float, offsetX: Float) {
        c.strokeStyle(color); c.lineWidth(1f)
        var started = false
        list.forEachIndexed { i, v ->
            if (v == null) { started = false; return@forEachIndexed }
            val x = offsetX + i * s + s / 2f; val y = mapY(v)
            if (!started) { c.beginPath(); c.moveTo(x, y); started = true } else c.lineTo(x, y)
        }
        c.stroke()
    }

    private fun dashLine(c: CanvasContext, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1; val dy = y2 - y1
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 0.5f) { c.beginPath(); c.moveTo(x1, y1); c.lineTo(x2, y2); c.stroke(); return }
        val ux = dx / len; val uy = dy / len
        var d = 0f; val seg = 3f; val gap = 3f
        while (d < len) {
            val e = min(d + seg, len)
            c.beginPath(); c.moveTo(x1 + ux * d, y1 + uy * d); c.lineTo(x1 + ux * e, y1 + uy * e); c.stroke()
            d += seg + gap
        }
    }

    private data class Regions(val candleTop: Float, val candleBottom: Float)
}

/** 十字光标选中的点信息 */
internal data class CrossInfo(
    val label: String,      // 时间(分时) / 日期(日K)
    val price: Float,
    val chgPct: Float,
    val isKline: Boolean,
    val stockName: String,
    val code: String,
    val period: String,
)

internal fun ViewContainer<*, *>.KRChatMiniChart(init: KRChatMiniChart.() -> Unit) {
    addChild(KRChatMiniChart(), init)
}
