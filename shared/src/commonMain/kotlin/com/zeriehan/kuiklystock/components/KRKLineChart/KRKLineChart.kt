package com.zeriehan.kuiklystock.components.KRKLineChart

import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.CanvasContext
import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.TimeSharingPoint
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.computeMA
import com.zeriehan.kuiklystock.core.computeMACD
import com.zeriehan.kuiklystock.core.computeRSI
import com.zeriehan.kuiklystock.core.computeBOLL
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 专业级自研行情图（Kuikly Canvas）。对标同花顺 / 币安的移动端操作：
 * - 左侧固定价格轴（不随横滚），含网格刻度 + 最新价标签 + 十字光标价格标签
 * - K线：蜡烛（涨红跌绿）+ MA5/MA10/MA20 均线叠线 + 顶部均线图例 + 成交量子图
 * - 分时图：价格线（红涨绿跌）+ 均价黄线 + 昨收基准虚线
 * - 指标窗（MACD / RSI / BOLL）：MACD、RSI 独立副窗，BOLL 叠加在主图
 * - 十字光标：长按/触摸拖动 → 竖虚线（底部日期/时间）+ 横虚线（左轴价格），顶部信息条
 * - 双指捏合缩放（step 0.5x–3x）；横向滚动到最左（历史最早处）触发 onLoadMore 加载更多
 *
 * 布局：Row[ 左价格轴 Canvas(固定46宽) | Scroller[ 主 Canvas(宽=数据量×步长×缩放) ] ]
 */
internal class KRKLineChart : ComposeView<ComposeAttr, ComposeEvent>() {

    /** 指标类型 */
    companion object {
        const val IND_NONE = 0
        const val IND_MACD = 1
        const val IND_RSI = 2
        const val IND_BOLL = 3
    }

    /** K线数据（日/周/月/年） */
    var bars: List<KLineBar> by observable(emptyList())
    /** 分时数据（非空即进入分时模式） */
    var timeSharing: List<TimeSharingPoint> by observable(emptyList())
    /** 分时基准价（昨收） */
    var refPrice: Float by observable(0f)
    /** 缩放倍数（双指捏合 / ± 按钮调节） */
    var zoom: Float by observable(1f)
    /** 横向滚动偏移（底部固定日期轴据此定位当前可视区对应的 K线） */
    var scrollOffsetX: Float by observable(0f)
    /** 当前指标 */
    var indicator: Int by observable(IND_NONE)
    /** 加载更多历史（横向滚到最左时由外层设置） */
    var onLoadMore: (() -> Unit)? = null

    /** 十字光标状态 */
    var crossActive: Boolean by observable(false)
    var crossX: Float by observable(0f)   // 主画布局部 X
    var crossY: Float by observable(0f)   // 主画布局部 Y

    /** 交互内部状态 */
    private var atStart = false
    /** 捏合缩放基准（手势起始时的 zoom 与 scale） */
    private var pinchBaseZoom = 1f
    private var pinchBaseScale = 1f

    /** 图表区布局常量 */
    private val CHART_H = 300f          // 整图更高（用户要求更大）
    private val CANDLE_TOP = 10f        // 顶部留给图例
    private val DATE_BOTTOM = 16f       // 主画布内底部预留（十字光标标签）
    private val LEFT_AXIS_W = 46f
    private val AXIS_H = 16f            // 底部固定日期轴高度（不随横滚）
    private val ZOOM_MIN = 0.5f
    private val ZOOM_MAX = 3f
    private val ZOOM_STEP = 1.3f

    private fun isTimeSharing() = timeSharing.isNotEmpty()
    private fun showIndicatorPane() = indicator == IND_MACD || indicator == IND_RSI

    /** 清除十字光标（切换周期/指标时由外层调用） */
    fun clearCrosshair() {
        crossActive = false
        crossX = 0f
        crossY = 0f
    }

    private fun setCross(x: Float, y: Float) {
        crossX = x
        crossY = y
        crossActive = true
    }

    /** 缩放（± 按钮 / 兜底捏合），限制在 0.5x–3x */
    private fun zoomBy(factor: Float) {
        zoom = (zoom * factor).coerceIn(ZOOM_MIN, ZOOM_MAX)
    }

    /** 价格上下界（含 8% 留白） */
    private fun priceBounds(): Pair<Float, Float> {
        return if (isTimeSharing()) {
            val ts = timeSharing
            if (ts.isEmpty()) return Pair(1f, 0f)
            var mx = ts.maxOf { maxOf(it.price, it.avg) }
            var mn = ts.minOf { minOf(it.price, it.avg) }
            if (refPrice > 0f) { mx = max(mx, refPrice); mn = min(mn, refPrice) }
            val pad = (mx - mn) * 0.08f + 0.01f
            Pair(mx + pad, mn - pad)
        } else {
            val bs = bars
            if (bs.isEmpty()) return Pair(1f, 0f)
            val mx = bs.maxOf { it.high }
            val mn = bs.minOf { it.low }
            val pad = (mx - mn) * 0.08f + 0.01f
            Pair(mx + pad, mn - pad)
        }
    }

    /** 计算各区域边界（主画布局部坐标 Y） */
    private data class Regions(
        val candleTop: Float, val candleBottom: Float,
        val volTop: Float, val volBottom: Float,
        val indTop: Float, val indBottom: Float,
    )
    private fun regions(h: Float): Regions {
        val top = CANDLE_TOP
        val usable = h - top - DATE_BOTTOM
        val candleH: Float
        val volH: Float
        if (showIndicatorPane()) {
            candleH = usable * 0.54f
            volH = usable * 0.16f
        } else {
            candleH = usable * 0.76f       // 成交量占比更小
            volH = usable - candleH
        }
        val candleBottom = top + candleH
        val volTop = candleBottom
        val volBottom = volTop + volH
        return Regions(top, candleBottom, volTop, volBottom, volBottom, h - DATE_BOTTOM)
    }

    override fun createAttr() = ComposeAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flexDirectionColumn()
                    alignItemsStretch()
                    marginTop(8f)
                }

                // 缩放控制条（＋ / － 直接调节 zoom；设备不支持捏合时仍可用）
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); height(22f); marginRight(4f) }
                    View { attr { flex(1f) } }   // 把按钮推到右侧
                    View {
                        attr {
                            width(28f); height(20f); marginRight(8f); borderRadius(6f)
                            backgroundColor(Color(0xFFF0F0F0)); justifyContentCenter(); alignItemsCenter()
                        }
                        event { click { ctx.zoomBy(1f / ctx.ZOOM_STEP) } }
                        Text { attr { text("－"); fontSize(15f); color(Color(0xFF333333)) } }
                    }
                    View {
                        attr {
                            width(28f); height(20f); marginRight(8f); borderRadius(6f)
                            backgroundColor(Color(0xFFF0F0F0)); justifyContentCenter(); alignItemsCenter()
                        }
                        event { click { ctx.zoomBy(ctx.ZOOM_STEP) } }
                        Text { attr { text("＋"); fontSize(15f); color(Color(0xFF333333)) } }
                    }
                    Text { attr { fontSize(11f); color(Color(0xFF999999)) }; "${(ctx.zoom * 100).toInt()}%" }
                }

                // 固定信息条（不随横滚，始终显示十字光标那根 K线的 开/高/低/收/涨跌幅）
                View {
                    attr { flexDirectionRow(); height(22f); alignItemsStretch(); marginTop(2f) }
                    View { attr { width(ctx.LEFT_AXIS_W) } }   // 与左价格轴对齐
                    Canvas(
                        { attr { flex(1f); height(22f) } }
                    ) { c, w, h -> ctx.drawInfoBar(c, w, h) }
                }

                // 图表行：左固定价格轴 | 主画布（横向滚动）
                View {
                    attr { flexDirectionRow(); height(ctx.CHART_H); alignItemsStretch() }
                    // 左固定价格轴（不随横滚）
                    Canvas(
                        { attr { width(ctx.LEFT_AXIS_W); height(ctx.CHART_H) } }
                    ) { c, w, h -> ctx.drawLeftAxis(c, w, h) }

                    // 主图（横向滚动浏览历史；滚到最左触发加载更多）
                    Scroller {
                        attr { flex(1f); flexDirectionRow(); height(ctx.CHART_H) }
                        event {
                            scroll(sync = true) { params ->
                                val offX = params.offsetX
                                ctx.scrollOffsetX = offX
                                if (offX <= 4f) {
                                    if (!ctx.atStart) {
                                        ctx.atStart = true
                                        ctx.onLoadMore?.invoke()
                                    }
                                } else if (offX > 24f) {
                                    ctx.atStart = false
                                }
                            }
                        }
                        Canvas(
                            {
                                attr {
                                    height(ctx.CHART_H)
                                    // ⚠️ 关键：width 必须在 attr 闭包内读取 zoom/bars，
                                    // 否则 zoom 变化不会重算宽度 → 缩放看起来“不生效”
                                    val n = if (ctx.isTimeSharing()) ctx.timeSharing.size else ctx.bars.size
                                    val step = (if (ctx.isTimeSharing()) 6f else 9f) * ctx.zoom
                                    val cw = (n * step + 16f).coerceAtLeast(1f)
                                    width(cw)
                                }
                                event {
                                    // 点击定位十字光标（点哪显示哪；再点移动）
                                    click { param -> ctx.setCross(param.x, param.y) }
                                    // 双指捏合缩放（scale 为当前手势累计倍数，起始≈1）
                                    pinch { param ->
                                        val s = param.scale
                                        if (abs(s - 1f) < 0.02f) {
                                            ctx.pinchBaseZoom = ctx.zoom
                                            ctx.pinchBaseScale = s
                                        }
                                        ctx.zoom = (ctx.pinchBaseZoom * (s / ctx.pinchBaseScale)).toFloat().coerceIn(ctx.ZOOM_MIN, ctx.ZOOM_MAX)
                                    }
                                }
                            }
                        ) { c, w, h -> ctx.drawMain(c, w, h) }
                    }
                }

                // 底部日期轴（不随横滚，固定在可视区；只标 最左/1/4/中间/3/4/最右 五处）
                View {
                    attr { flexDirectionRow(); height(ctx.AXIS_H); alignItemsStretch() }
                    View { attr { width(ctx.LEFT_AXIS_W) } }
                    Canvas(
                        { attr { flex(1f); height(ctx.AXIS_H) } }
                    ) { c, w, h -> ctx.drawDateAxis(c, w, h) }
                }
            }
        }
    }

    // ===================== 绘制工具 =====================

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

    /** 虚线（手动分段） */
    private fun dashLine(c: CanvasContext, x1: Float, y1: Float, x2: Float, y2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        if (len < 0.5f) { c.beginPath(); c.moveTo(x1, y1); c.lineTo(x2, y2); c.stroke(); return }
        val ux = dx / len
        val uy = dy / len
        var d = 0f
        val seg = 3f
        val gap = 3f
        while (d < len) {
            val e = min(d + seg, len)
            c.beginPath()
            c.moveTo(x1 + ux * d, y1 + uy * d)
            c.lineTo(x1 + ux * e, y1 + uy * e)
            c.stroke()
            d += seg + gap
        }
    }

    private fun drawSeries(
        c: CanvasContext, list: List<Float?>, color: Color, slot: Float,
        mapY: (Float) -> Float,
    ) {
        c.strokeStyle(color)
        c.lineWidth(1f)
        var started = false
        list.forEachIndexed { i, v ->
            if (v == null) { started = false; return@forEachIndexed }
            val x = i * slot + slot / 2f
            val y = mapY(v)
            if (!started) { c.beginPath(); c.moveTo(x, y); started = true }
            else c.lineTo(x, y)
        }
        c.stroke()
    }

    // ===================== 左价格轴 =====================

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
            c.fillText(formatPrice(price), 3f, y + 3f)
        }

        // 最新价 / 昨收 标签
        val lastPrice = if (isTimeSharing()) timeSharing.lastOrNull()?.price ?: refPrice
        else bars.lastOrNull()?.close ?: 0f
        if (lastPrice > 0f) {
            val y = r.candleTop + (1f - (lastPrice - min) / range) * candleH
            val up = if (isTimeSharing()) lastPrice >= refPrice
            else (bars.lastOrNull()?.close ?: 0f) >= (bars.lastOrNull()?.open ?: lastPrice)
            val col = if (up) StockColor.UP else StockColor.DOWN
            fillRect(c, 1f, y - 7f, w - 2f, 14f, col)
            c.fillStyle(Color.WHITE)
            c.fillText(formatPrice(lastPrice), 4f, y + 3f)
        }

        // 十字光标：左侧价格标签
        if (crossActive) {
            val cy = crossY.coerceIn(r.candleTop, r.candleBottom)
            val price = min + (1f - (cy - r.candleTop) / candleH) * range
            fillRect(c, 1f, cy - 7f, w - 2f, 14f, Color(0xFF555555))
            c.fillStyle(Color.WHITE)
            c.fillText(formatPrice(price), 4f, cy + 3f)
        }
    }

    // ===================== 主画布 =====================

    private fun drawMain(c: CanvasContext, w: Float, h: Float) {
        val r = regions(h)
        val (max, min) = priceBounds()
        val range = (max - min).coerceAtLeast(0.01f)
        val candleH = r.candleBottom - r.candleTop
        val dateTop = h - DATE_BOTTOM
        val n = if (isTimeSharing()) timeSharing.size else bars.size
        if (n == 0) return
        val baseStep = if (isTimeSharing()) 6f else 9f
        val s = w / n

        val priceToY: (Float) -> Float = { p -> r.candleTop + (1f - (p - min) / range) * candleH }

        // 横向网格（价格刻度）
        c.strokeStyle(Color(0xFFEEEEEE))
        c.lineWidth(1f)
        for (k in 0..4) {
            val y = r.candleTop + k * (candleH / 4f)
            c.beginPath(); c.moveTo(0f, y); c.lineTo(w, y); c.stroke()
        }
        // 纵向网格
        for (k in 1..4) {
            val x = s * (n * k / 4) - s / 2f
            c.beginPath(); c.moveTo(x, r.candleTop); c.lineTo(x, dateTop); c.stroke()
        }

        val hoverIdx = if (crossActive) {
            ((crossX - s / 2f) / s).toInt().coerceIn(0, n - 1)
        } else n - 1

        if (isTimeSharing()) {
            drawTimeSharing(c, w, h, r, s, dateTop, priceToY, hoverIdx)
        } else {
            drawCandles(c, w, h, r, s, candleH, priceToY, range, hoverIdx)
        }

        // 指标副窗（MACD / RSI）
        if (showIndicatorPane()) {
            drawIndicator(c, w, h, r, s, hoverIdx)
        }

        // 十字光标（最后画，置顶）
        if (crossActive) drawCrosshair(c, w, h, r, s, priceToY, hoverIdx)
    }

    private fun drawCandles(
        c: CanvasContext, w: Float, h: Float, r: Regions, s: Float,
        candleH: Float, priceToY: (Float) -> Float, range: Float, hoverIdx: Int,
    ) {
        val bs = bars
        val n = bs.size
        val cw = (s * 0.6f).coerceAtLeast(2f)
        val maxVol = bs.maxOf { it.volume }.coerceAtLeast(0.01f)
        val closes = bs.map { it.close }
        val ma5 = computeMA(closes, 5)
        val ma10 = computeMA(closes, 10)
        val ma20 = computeMA(closes, 20)

        // 蜡烛 + 成交量
        bs.forEachIndexed { i, bar ->
            val cx = i * s + s / 2f
            val color = if (bar.close >= bar.open) StockColor.UP else StockColor.DOWN
            val yH = priceToY(bar.high)
            val yL = priceToY(bar.low)
            c.beginPath()
            c.strokeStyle(color)
            c.lineWidth(1f)
            c.moveTo(cx, yH); c.lineTo(cx, yL); c.stroke()
            val yO = priceToY(bar.open)
            val yC = priceToY(bar.close)
            val top = minOf(yO, yC)
            val bh = (maxOf(yO, yC) - top).coerceAtLeast(1f)
            c.beginPath()
            c.moveTo(cx - cw / 2f, top)
            c.lineTo(cx + cw / 2f, top)
            c.lineTo(cx + cw / 2f, top + bh)
            c.lineTo(cx - cw / 2f, top + bh)
            c.closePath()
            c.fillStyle(color)
            c.fill()
            // 成交量（与 K线对齐）
            val volH = (bar.volume / maxVol) * (r.volBottom - r.volTop - 2f)
            val vy = r.volBottom - volH
            fillRect(c, cx - cw / 2f, vy, cw, volH,
                if (bar.close >= bar.open) Color(0x33E54D42) else Color(0x331ABE5B))
        }

        // 均线叠线
        drawSeries(c, ma5, Color(0xFFF5A623), s, priceToY)
        drawSeries(c, ma10, Color(0xFF3B82F6), s, priceToY)
        drawSeries(c, ma20, Color(0xFF9C27B0), s, priceToY)

        // BOLL 叠加（指标=布林带时）
        if (indicator == IND_BOLL) {
            val boll = computeBOLL(closes)
            drawSeries(c, boll.mid, Color(0xFF888888), s, priceToY)
            drawSeries(c, boll.upper, Color(0xFFE54D42), s, priceToY)
            drawSeries(c, boll.lower, Color(0xFF1ABE5B), s, priceToY)
        }

        // 最新价虚线
        val last = bs.last()
        val yLast = priceToY(last.close)
        c.strokeStyle(if (last.close >= last.open) StockColor.UP else StockColor.DOWN)
        c.lineWidth(1f)
        dashLine(c, 0f, yLast, w, yLast)
        // 注：顶部图例 / 开高低收信息串已移至固定的 drawInfoBar（不随横滚）
    }

    private fun drawTimeSharing(
        c: CanvasContext, w: Float, h: Float, r: Regions, s: Float,
        dateTop: Float, priceToY: (Float) -> Float, hoverIdx: Int,
    ) {
        val ts = timeSharing
        val n = ts.size

        if (refPrice > 0f) {
            val yRef = priceToY(refPrice)
            c.strokeStyle(Color(0xFFBBBBBB)); c.lineWidth(1f)
            dashLine(c, 0f, yRef, w, yRef)
        }

        // 价格线（分段着色）
        c.lineWidth(1.2f)
        ts.forEachIndexed { i, p ->
            val x = i * s + s / 2f
            val y = priceToY(p.price)
            if (i == 0) { c.beginPath(); c.moveTo(x, y) }
            else {
                val prev = ts[i - 1]
                c.strokeStyle(if (prev.price >= refPrice) StockColor.UP else StockColor.DOWN)
                c.lineTo(x, y); c.stroke()
                if (i < n - 1) { c.beginPath(); c.moveTo(x, y) }
            }
        }
        // 均价黄线
        c.strokeStyle(Color(0xFFF5A623)); c.lineWidth(1f)
        ts.forEachIndexed { i, p ->
            val x = i * s + s / 2f
            val y = priceToY(p.avg)
            if (i == 0) { c.beginPath(); c.moveTo(x, y) } else c.lineTo(x, y)
        }
        c.stroke()
        // 注：顶部均价 / 价 / 涨跌幅信息串已移至固定的 drawInfoBar（不随横滚）
    }

    private fun drawIndicator(
        c: CanvasContext, w: Float, h: Float, r: Regions, s: Float, hoverIdx: Int,
    ) {
        val bs = bars
        val n = bs.size
        val top = r.indTop
        val bottom = r.indBottom
        val paneH = (bottom - top).coerceAtLeast(1f)
        // 面板背景
        c.strokeStyle(Color(0xFFEEEEEE)); c.lineWidth(1f)
        c.beginPath(); c.moveTo(0f, top); c.lineTo(w, top); c.stroke()
        c.font(9f)

        when (indicator) {
            IND_MACD -> {
                val macd = computeMACD(bs.map { it.close })
                val vals = macd.dif.filterNotNull() + macd.dea.filterNotNull() + macd.hist.filterNotNull()
                if (vals.isEmpty()) return
                val mx = vals.maxOf { abs(it) }.coerceAtLeast(0.01f)
                val mid = top + paneH / 2f
                val mapY: (Float) -> Float = { v -> mid - (v / mx) * (paneH / 2f - 4f) }
                // 零轴
                c.strokeStyle(Color(0xFFEEEEEE)); c.lineWidth(1f)
                c.beginPath(); c.moveTo(0f, mid); c.lineTo(w, mid); c.stroke()
                // 柱
                val cw = (s * 0.5f).coerceAtLeast(1f)
                macd.hist.forEachIndexed { i, v ->
                    if (v == null) return@forEachIndexed
                    val x = i * s + s / 2f
                    val y = mapY(v)
                    fillRect(c, x - cw / 2f, minOf(mid, y), cw, abs(y - mid),
                        if (v >= 0f) StockColor.UP else StockColor.DOWN)
                }
                drawSeries(c, macd.dif, Color(0xFFF5A623), s, mapY)
                drawSeries(c, macd.dea, Color(0xFF3B82F6), s, mapY)
                val hi = hoverIdx.coerceIn(0, n - 1)
                c.fillStyle(Color(0xFF999999))
                c.fillText("MACD(12,26,9)  DIF ${fmtMA(macd.dif, hi)}  DEA ${fmtMA(macd.dea, hi)}",
                    4f, top + 11f)
            }
            IND_RSI -> {
                val rsi = computeRSI(bs.map { it.close })
                val mapY: (Float) -> Float = { v -> bottom - (v / 100f) * paneH }
                // 20/50/80 参考线
                c.strokeStyle(Color(0xFFEEEEEE)); c.lineWidth(1f)
                listOf(20f, 50f, 80f).forEach { lvl ->
                    val y = mapY(lvl)
                    c.beginPath(); c.moveTo(0f, y); c.lineTo(w, y); c.stroke()
                }
                drawSeries(c, rsi, Color(0xFF9C27B0), s, mapY)
                val hi = hoverIdx.coerceIn(0, n - 1)
                c.fillStyle(Color(0xFF999999))
                c.fillText("RSI(14)  ${fmtMA(rsi, hi)}", 4f, top + 11f)
            }
        }
    }

    private fun drawCrosshair(
        c: CanvasContext, w: Float, h: Float, r: Regions, s: Float,
        priceToY: (Float) -> Float, hoverIdx: Int,
    ) {
        val dateTop = h - DATE_BOTTOM
        val cx = crossX.coerceIn(0f, w)
        val cy = crossY.coerceIn(r.candleTop, r.candleBottom)

        c.strokeStyle(Color(0xFF888888)); c.lineWidth(1f)
        dashLine(c, cx, r.candleTop, cx, dateTop)   // 竖线（日期）
        dashLine(c, 0f, cy, w, cy)                 // 横线（价格）

        // 底部日期/时间标签
        val label = if (isTimeSharing()) timeSharing.getOrNull(hoverIdx)?.time ?: ""
        else bars.getOrNull(hoverIdx)?.date ?: ""
        if (label.isNotEmpty()) {
            val bw = (label.length * 7f) + 10f
            var bx = cx - bw / 2f
            bx = bx.coerceIn(0f, w - bw)
            fillRect(c, bx, dateTop, bw, 14f, Color(0xFF555555))
            c.fillStyle(Color.WHITE)
            c.fillText(label, bx + 5f, dateTop + 10f)
        }
    }

    /**
     * 底部固定日期轴（不随横滚）。
     * 只标 3 个固定可视位置：最左 / 中间 / 最右，
     * 读取对应位置的 K线时间，避免缩小时每根都标导致重叠看不清。
     */
    private fun drawDateAxis(c: CanvasContext, w: Float, h: Float) {
        val n = if (isTimeSharing()) timeSharing.size else bars.size
        if (n == 0) return
        val baseStep = if (isTimeSharing()) 6f else 9f
        val s = baseStep * zoom
        c.font(9f)
        c.fillStyle(Color(0xFF999999))
        val fracs = listOf(0f, 0.5f, 1f)
        fracs.forEach { f ->
            val vpX = f * w                       // 可视区位置
            val contentX = scrollOffsetX + vpX   // 映射回内容坐标
            var idx = ((contentX - s / 2f) / s).toInt()
            idx = idx.coerceIn(0, n - 1)
            val label = if (isTimeSharing()) timeSharing.getOrNull(idx)?.time ?: ""
            else bars.getOrNull(idx)?.date ?: ""
            if (label.isNotEmpty()) {
                val tw = label.length * 6f
                val tx = when {
                    f <= 0.001f -> 0f                                   // 最左：左对齐
                    f >= 0.999f -> (w - tw).coerceAtLeast(0f)           // 最右：右对齐
                    else -> (vpX - tw / 2f).coerceIn(0f, (w - tw).coerceAtLeast(0f))
                }
                c.fillText(label, tx, h - 4f)
            }
        }
    }

    /**
     * 固定信息条（位于 Scroller 之外，不随横滚）。
     * 显示十字光标所选（或最新）那根 K线/分时点的：均线图例 + 开/高/低/收/涨跌幅。
     * 横滚时不改变内容、位置固定，解决"往左滑信息飞出屏幕"的问题。
     */
    private fun drawInfoBar(c: CanvasContext, w: Float, h: Float) {
        val n = if (isTimeSharing()) timeSharing.size else bars.size
        if (n == 0) return
        c.font(9f)
        if (isTimeSharing()) {
            val s = 6f * zoom
            val idx = if (crossActive) ((crossX - s / 2f) / s).toInt().coerceIn(0, n - 1) else n - 1
            val hp = timeSharing[idx]
            val chg = if (refPrice != 0f) (hp.price - refPrice) / refPrice * 100f else 0f
            c.fillStyle(Color(0xFF999999))
            c.fillText("均价 ${formatPrice(hp.avg)}", 4f, 11f)
            c.fillStyle(Color(0xFF666666))
            c.fillText("${hp.time}  价 ${formatPrice(hp.price)}  ${formatPercent(chg)}", 4f, 21f)
        } else {
            val s = 9f * zoom
            val idx = if (crossActive) ((crossX - s / 2f) / s).toInt().coerceIn(0, n - 1) else n - 1
            val b = bars[idx]
            val chg = if (b.open != 0f) (b.close - b.open) / b.open * 100f else 0f
            val closes = bars.map { it.close }
            val ma5 = computeMA(closes, 5)
            val ma10 = computeMA(closes, 10)
            val ma20 = computeMA(closes, 20)
            c.fillStyle(Color(0xFF999999))
            c.fillText("MA5 ${fmtMA(ma5, idx)}   MA10 ${fmtMA(ma10, idx)}   MA20 ${fmtMA(ma20, idx)}", 4f, 11f)
            c.fillStyle(Color(0xFF666666))
            c.fillText("${b.date}  开${formatPrice(b.open)} 高${formatPrice(b.high)} 低${formatPrice(b.low)} 收${formatPrice(b.close)} ${formatPercent(chg)}", 4f, 21f)
        }
    }

    private fun fmtMA(ma: List<Float?>, i: Int): String {
        val v = ma.getOrNull(i)
        return if (v == null) "--" else formatPrice(v)
    }
}

internal fun ViewContainer<*, *>.KRKLineChart(init: KRKLineChart.() -> Unit) {
    addChild(KRKLineChart(), init)
}
