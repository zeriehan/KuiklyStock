package com.zeriehan.kuiklystock.app.quotes

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.components.KRTable.KRStockList
import com.zeriehan.kuiklystock.core.MockStockSource

/**
 * 行情 Tab 宿主页（测试用）。
 * 引用 KRStockList 组件，加载 Mock 数据，验证行内展开交互。
 */
@Page("QuotesPage", supportInLocal = true)
internal class QuotesPage : BasePager() {

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(Color.WHITE)
            }
            // 顶部标题栏
            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(all = 14f)
                    backgroundColor(Color(0xFFF2F3F5))
                }
                Text { attr { text("行情"); fontSize(18f); color(Color(0xFF222222)) } }
            }
            // 行情列表（含行内展开：点行挤开下方，显示迷你走势+详细按钮）
            // 关键：自定义 ComposeView 放进纵向 column 时必须 flex(1f) 占满剩余高度，
            // 否则其内部 Scroller 因父高度未约束而塌缩为 0 -> 整块空白。
            KRStockList {
                attr { flex(1f) }
                stocks = MockStockSource.getQuotes()
                onRowClick = { /* 展开/收起由 KRStockList 内部 expandedIndex 处理 */ }
                onDetailClick = { stock ->
                    val data = JSONObject()
                    data.put("stockCode", stock.code)
                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", data)
                }
            }
        }
    }
}
