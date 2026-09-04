package com.zeriehan.kuiklystock.components.KRStockCard

import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.components.KRTrendChart.KRTrendChart

/**
 * AI 回复中提及股票的迷你行情窄卡（Task02 发散性渲染）。
 *
 * AI 一次回答可能提多只股票，若全部纵向叠放会很占纵向空间。故渲染成
 * **横向可滚动的一行窄卡**：每张窄卡 = 名称 + 现价 + 涨跌徽章 + 底部一小条
 * 真实分时迷你线；多只并排，横向拖动看全，点卡跳个股详情承接。
 *
 * 走势用 [KRTrendChart] 且喂 [StockData.getIntraday]（真实分时，拉不到自动回退），
 * 与详情页同源，不再显示 mock 的 Stock.trend。
 *
 * 纯展示 + 回调；文件级扩展函数，ChatPage 在 vif 重建闭包内调用。
 */

/** 单张窄卡宽度 */
const val AI_STOCK_CARD_W = 148f

/** 渲染一组 AI 提及股票为横向滚动窄卡行；点卡回调 [onOpen]。stocks 为空则什么都不渲染。 */
internal fun ViewContainer<*, *>.renderAiStockCards(
    stocks: List<Stock>,
    width: Float = AI_STOCK_CARD_W,
    onOpen: (Stock) -> Unit,
) {
    if (stocks.isEmpty()) return
    // 横向滚动卡片行（一行，超宽拖动查看）。固定高度容纳 名称行+现价行+小走势。
    Scroller {
        attr {
            flexDirectionRow()
            height(76f)
            showScrollerIndicator(false)
        }
        stocks.forEach { s ->
            View {
                attr {
                    width(width)
                    marginRight(8f)
                    padding(8f)
                    flexDirectionColumn()
                    borderRadius(10f)
                    border(Border(1f, BorderStyle.SOLID, Color(0xFFE8EAED)))
                    backgroundColor(Color.WHITE)
                }
                event { click { onOpen(s) } }
                // 名称（单行截断）
                Text {
                    attr {
                        text(s.name); fontSize(13f); fontWeightSemiBold()
                        color(StockColor.text(s.changePercent))
                        lines(1); textOverFlowTail()
                    }
                }
                // 现价 + 涨跌徽章（同一行，右对齐徽章）
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); marginTop(3f) }
                    Text {
                        attr {
                            text(formatPrice(s.price)); fontSize(13f); fontWeightSemiBold()
                            color(StockColor.text(s.changePercent)); flex(1f)
                        }
                    }
                    Text {
                        attr {
                            text(formatPercent(s.changePercent)); fontSize(11f); fontWeightSemiBold()
                            color(StockColor.text(s.changePercent))
                        }
                    }
                }
                // 底部一小条真实分时迷你线（高约 22）
                KRTrendChart {
                    realPoints = StockData.getIntraday(s)
                    color = StockColor.of(s.changePercent)
                    chartHeight = 24f
                }
            }
        }
    }
}
