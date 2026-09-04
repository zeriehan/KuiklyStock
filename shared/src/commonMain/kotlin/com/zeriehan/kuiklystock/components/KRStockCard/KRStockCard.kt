package com.zeriehan.kuiklystock.components.KRStockCard

import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.components.KRStockBadge.KRStockBadge
import com.zeriehan.kuiklystock.components.KRTrendChart.KRTrendChart

/**
 * AI 回复中提及股票的迷你行情卡片（Task02 发散性渲染：聊天结果里展示结构化行情卡）。
 *
 * 形态：每只股票一张「竖卡」——上半行名称+现价+涨跌徽章，下半一条迷你走势图(KRTrendChart)。
 * 由 [renderAiStockCards] 将多只命中的股票纵向叠成一列，整体靠左、与 AI 气泡对齐；
 * 点卡片回调 [onOpen]，由页面注入跳转个股详情页(StockDetail)承接。
 *
 * 纯展示 + 回调，不持有任何页面状态（避免 ComposeView attr 的 observable 首帧时序坑），
 * 故实现为文件级扩展函数，ChatPage 可直接在 vif 翻转重建闭包内调用。
 */

/** 卡片占用的内容宽（不含外边距），供外层与气泡等宽对齐。 */
const val AI_STOCK_CARD_W = 236f

/** 渲染一组 AI 提及股票的卡片，纵向叠列；每张可点。stocks 为空则什么都不渲染。 */
internal fun ViewContainer<*, *>.renderAiStockCards(
    stocks: List<Stock>,
    width: Float = AI_STOCK_CARD_W,
    onOpen: (Stock) -> Unit,
) {
    if (stocks.isEmpty()) return
    stocks.forEach { s ->
        View {
            attr {
                alignSelfFlexStart()   // 靠左且不被消息列 STRETCH 拉满整行宽
                width(width)
                flexDirectionColumn()
                marginBottom(6f)
                padding(10f)
                borderRadius(10f)
                border(Border(1f, BorderStyle.SOLID, Color(0xFFE8EAED)))
                backgroundColor(Color.WHITE)
            }
            event { click { onOpen(s) } }
            // 上半行：左列=名称+现价，右列=涨跌徽章（自然高度，走势图在下方固定80）
            View {
                attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                View {
                    attr { flex(1f); flexDirectionColumn() }
                    Text {
                        attr {
                            text(s.name); fontSize(14f); fontWeightSemiBold()
                            color(StockColor.text(s.changePercent))
                        }
                    }
                    Text {
                        attr {
                            text("现价 " + formatPrice(s.price)); fontSize(12f)
                            color(StockColor.text(s.changePercent)); marginTop(3f)
                        }
                    }
                }
                KRStockBadge { attr { changePercent = s.changePercent } }
            }
            // 下半：迷你走势（KRTrendChart 固定 80 高，涨红跌绿随当前价方向）
            KRTrendChart {
                points = s.trend
                color = StockColor.of(s.changePercent)
            }
        }
    }
}
