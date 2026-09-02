package com.zeriehan.kuiklystock.app.quotes

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.formatPrice

/**
 * 行情池明细页：大盘「市场热度」卡点进来。
 * 只展示当前内存行情池里非指数股票（上涨/平盘/下跌分组），行点击进个股详情。
 * 说明：这是内存样本（约几十只），不是全 A 市场。
 */
@Page("HeatPool", supportInLocal = true)
internal class HeatPoolPage : BasePager() {

    override fun body(): ViewBuilder {
        val ctx = this
        val pool = StockData.getQuotes().filter { !it.isIndex }
        val ups = pool.filter { it.changePercent > 0f }.sortedByDescending { it.changePercent }
        val flats = pool.filter { it.changePercent == 0f }
        val downs = pool.filter { it.changePercent < 0f }.sortedBy { it.changePercent }
        return {
            attr { flex(1f); flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }

            // ===== 返回栏 =====
            View {
                attr {
                    padding(12f)
                    paddingTop(pagerData.statusBarHeight)
                    height(44f + pagerData.statusBarHeight)
                    flexDirectionRow()
                    alignItemsCenter()
                    backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.close() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemisolid() } }
                }
                Text { attr { text("行情池 · ${pool.size} 只"); fontSize(17f); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f) } }
            }

            // ===== 列表（行整宽铺满，左右对称无右侧悬空）=====
            Scroller {
                attr { flex(1f); flexDirectionColumn(); marginTop(8f); paddingBottom(16f) }
                if (pool.isEmpty()) {
                    Text { attr { text("当前行情池为空"); fontSize(14f); color(Color(0xFF999999)); margin(24f) } }
                } else {
                    val openD: (Stock) -> Unit = { ctx.openDetail(it) }
                    if (ups.isNotEmpty()) {
                        Text { attr { text("上涨 ${ups.size}"); fontSize(12f); color(Color(UserSettings.upDeep())); margin(left = 12f, top = 8f, bottom = 4f) } }
                        ups.forEach { s -> heatRow(s, openD) }
                    }
                    if (flats.isNotEmpty()) {
                        Text { attr { text("平盘 ${flats.size}"); fontSize(12f); color(Color(0xFF8A8A8A)); margin(left = 12f, top = 8f, bottom = 4f) } }
                        flats.forEach { s -> heatRow(s, openD) }
                    }
                    if (downs.isNotEmpty()) {
                        Text { attr { text("下跌 ${downs.size}"); fontSize(12f); color(Color(UserSettings.downDeep())); margin(left = 12f, top = 8f, bottom = 4f) } }
                        downs.forEach { s -> heatRow(s, openD) }
                    }
                }
            }
        }
    }

    internal fun close() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    internal fun openDetail(stock: Stock) {
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
    }
}

/** 单行：名称(随涨跌) + 价格 + 涨跌幅；点击进详情 */
private fun ViewContainer<*, *>.heatRow(stock: Stock, open: (Stock) -> Unit) {
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginTop(2f)
            padding(left = 12f, right = 12f); backgroundColor(Color.WHITE)
        }
        event { click { open(stock) } }
        View { attr { flex(1f); flexDirectionColumn() }
            Text { attr { text(stock.name); fontSize(UserSettings.fs(15f)); color(StockColor.text(stock.changePercent)) } }
            Text { attr { text(stock.code); fontSize(UserSettings.fs(11f)); color(Color(0xFF999999)); marginTop(2f) } }
        }
        Text { attr { text(formatPrice(stock.price)); fontSize(UserSettings.fs(15f)); color(StockColor.text(stock.changePercent)); marginRight(10f) } }
        Text { attr { text(formatPercent(stock.changePercent)); fontSize(UserSettings.fs(14f)); color(StockColor.text(stock.changePercent)) } }
    }
}
