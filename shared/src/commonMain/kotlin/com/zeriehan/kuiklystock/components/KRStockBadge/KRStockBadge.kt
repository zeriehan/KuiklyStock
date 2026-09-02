package com.zeriehan.kuiklystock.components.KRStockBadge

import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.formatPercent

/**
 * 涨跌徽章：涨红跌绿，显示涨跌幅文本
 *
 * 用法：
 *   KRStockBadge { attr { changePercent = stock.changePercent } }
 */
internal class KRStockBadge : ComposeView<KRStockBadgeAttr, ComposeEvent>() {

    override fun createAttr() = KRStockBadgeAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        // 在 body 构建时读取一次当前值（attr 由父层在 init 中已赋值），避免 observable 在 Attr 上的时序问题导致一直读到初始 0。
        val v = ctx.attr.changePercent
        val col = StockColor.of(v)
        return {
            View {
                attr {
                    padding(all = 4f)
                    borderRadius(4f)
                    backgroundColor(Color(0xFFF2F3F5))
                }
                Text {
                    attr {
                        text(formatPercent(v))
                        fontSize(13f)
                        fontWeightSemiBold()
                        color(col)
                    }
                }
            }
        }
    }
}

internal class KRStockBadgeAttr : ComposeAttr() {
    var changePercent: Float = 0f
}

internal fun ViewContainer<*, *>.KRStockBadge(init: KRStockBadge.() -> Unit) {
    addChild(KRStockBadge(), init)
}
