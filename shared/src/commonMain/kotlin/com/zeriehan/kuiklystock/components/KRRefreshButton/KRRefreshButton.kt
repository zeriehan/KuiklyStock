package com.zeriehan.kuiklystock.components.KRRefreshButton

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*

/**
 * 圆形「重试 / 刷新」图标（透明背景，只显示图标本身，无外圈按钮）。
 *
 * - [loadingGetter] 返回 true（分析中）：图标置灰，点击被忽略；
 * - 否则：青色高亮，点击触发 [onClick] 重新分析。
 *
 * 图标用 Canvas 绘制（圆环 + 箭头），彻底摆脱字体字形错位 / 方块问题，且天然居中。
 *
 * ⚠️ [loadingGetter] 必须在函数体内部被「即时调用」（在 attr / event / Canvas 绘制闭包里 loadingGetter()），
 * 而不能在外部先用 `val loading = loadingGetter()` 捕获一次；否则加载态切换不会重绘。
 */
internal fun ViewContainer<*, *>.KRRefreshButton(
    loadingGetter: () -> Boolean,
    onClick: () -> Unit
) {
    View {
        attr {
            width(28f); height(28f)
            justifyContentCenter(); alignItemsCenter()
        }
        event { click { if (!loadingGetter()) onClick() } }
        Canvas({ attr { width(22f); height(22f) } }) { c, w, h ->
            val loading = loadingGetter()
            val col = if (loading) Color(0xFFBBBBBB) else Color(0xFF23D3FD)
            val cx = w / 2f
            val cy = h / 2f
            val r = (if (w < h) w else h) / 2f - 2f
            val PI = 3.14159265f
            // 圆环（右上留缺口给箭头）
            c.lineWidth(2f)
            c.strokeStyle(col)
            c.beginPath()
            val seg = 36
            val start = -PI * 0.20f
            val end = PI * 1.80f
            for (i in 0..seg) {
                val a = start + (end - start) * (i.toFloat() / seg.toFloat())
                val x = cx + r * kotlin.math.cos(a.toDouble()).toFloat()
                val y = cy + r * kotlin.math.sin(a.toDouble()).toFloat()
                if (i == 0) c.moveTo(x, y) else c.lineTo(x, y)
            }
            c.stroke()
            // 箭头：在 end 处沿切线方向画一个 V 形
            val ex = cx + r * kotlin.math.cos(end.toDouble()).toFloat()
            val ey = cy + r * kotlin.math.sin(end.toDouble()).toFloat()
            val tx = -kotlin.math.sin(end.toDouble()).toFloat()
            val ty = kotlin.math.cos(end.toDouble()).toFloat()
            val len = 5f
            val sp = 0.6f
            val rot = { ang: Float, vx: Float, vy: Float ->
                val ca = kotlin.math.cos(ang.toDouble()).toFloat()
                val sa = kotlin.math.sin(ang.toDouble()).toFloat()
                (vx * ca - vy * sa) to (vx * sa + vy * ca)
            }
            val (ax1, ay1) = rot(sp, tx, ty)
            val (ax2, ay2) = rot(-sp, tx, ty)
            c.beginPath()
            c.moveTo(ex, ey)
            c.lineTo(ex + len * ax1, ey + len * ay1)
            c.moveTo(ex, ey)
            c.lineTo(ex + len * ax2, ey + len * ay2)
            c.stroke()
        }
    }
}
