package com.zeriehan.kuiklystock.components.KRTable

import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.components.KRStockBadge.KRStockBadge
import com.zeriehan.kuiklystock.components.KRTrendChart.KRTrendChart
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.formatPrice

/**
 * 行情列表（含行内展开）：点击股票行 -> 挤开下方行，
 * 原位展开迷你走势 + 关键信息 + "详细"按钮。
 *
 * 用法：
 *   KRStockList {
 *       stocks = mockStocks
 *       onDetailClick = { stock -> /* 跳转详情页 */ }
 *   }
 */
internal class KRStockList : ComposeView<KRStockListAttr, ComposeEvent>() {

    var stocks: List<Stock> by observable(emptyList())
    var onDetailClick: ((Stock) -> Unit)? = null
    var onRowClick: ((Stock) -> Unit)? = null
    private var expandedIndex: Int by observable(-1)

    override fun createAttr() = KRStockListAttr()
    override fun createEvent() = ComposeEvent()

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            Scroller {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(Color(0xFFF2F3F5))
                }
                ctx.stocks.forEachIndexed { index, stock ->
                    // ===== 折叠态行 =====
                    View {
                        attr {
                            padding(all = 14f)
                            flexDirectionRow()
                            alignItemsCenter()
                            backgroundColor(Color.WHITE)
                        }
                        event {
                            click {
                                ctx.expandedIndex = if (ctx.expandedIndex == index) -1 else index
                                ctx.onRowClick?.invoke(stock)
                            }
                        }
                        // 名称 + 代码
                        View {
                            attr { flex(1f); flexDirectionColumn() }
                            Text { attr { text(stock.name); fontSize(16f); color(Color(0xFF222222)) } }
                            Text { attr { text(stock.code); fontSize(12f); color(Color(0xFF999999)); marginTop(4f) } }
                        }
                        // 最新价（右对齐）
                        View {
                            attr { flex(1f); flexDirectionRow(); justifyContentFlexEnd() }
                            Text { attr { text(formatPrice(stock.price)); fontSize(16f); color(Color(0xFF222222)) } }
                        }
                        // 涨跌幅徽章
                        KRStockBadge { attr { changePercent = stock.changePercent } }
                    }

                    // ===== 展开态（行内，挤开下方） =====
                    vif({ ctx.expandedIndex == index }) {
                        View {
                            attr {
                                padding(16f)
                                flexDirectionColumn()
                                backgroundColor(Color(0xFFF7F8FA))
                            }
                            // 迷你走势折线（自研 KRTrendChart）
                            KRTrendChart {
                                points = stock.trend
                                color = if (stock.isUp) Color(0xFFE54D42) else Color(0xFF1ABE5B)
                            }
                            // 关键信息
                            View {
                                attr { flexDirectionRow(); marginTop(10f) }
                                Text { attr { text("最高 " + formatPrice(stock.high)); fontSize(13f); color(Color(0xFF666666)) } }
                                Text { attr { text("最低 " + formatPrice(stock.low)); fontSize(13f); color(Color(0xFF666666)); marginLeft(16f) } }
                                Text { attr { text("量 " + formatPrice(stock.volume) + "万"); fontSize(13f); color(Color(0xFF666666)); marginLeft(16f) } }
                            }
                            // 详细按钮
                            Button {
                                attr {
                                    size(96f, 36f)
                                    marginTop(12f)
                                    alignSelfFlexEnd()
                                    borderRadius(18f)
                                    backgroundColor(Color(0xFF23D3FD))
                                    titleAttr { text("详细"); fontSize(14f); color(Color.WHITE) }
                                }
                                event { click { ctx.onDetailClick?.invoke(stock) } }
                            }
                        }
                    }
                }
            }
        }
    }
}

internal class KRStockListAttr : ComposeAttr()

internal fun ViewContainer<*, *>.KRStockList(init: KRStockList.() -> Unit) {
    addChild(KRStockList(), init)
}
