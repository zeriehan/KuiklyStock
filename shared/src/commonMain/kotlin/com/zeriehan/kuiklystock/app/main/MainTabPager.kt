package com.zeriehan.kuiklystock.app.main

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.components.KRTable.KRStockList
import com.zeriehan.kuiklystock.core.MockStockSource

/**
 * 四 Tab 主框架（App 启动入口）。
 * Tab1 AI聊天(占位) / Tab2 行情(已有 QuotesPage 内容) / Tab3 自选(复用 KRStockList) / Tab4 我的(占位)。
 * 切换机制：BottomTabBar 点击 -> 改 observable selectedTab -> ContentArea 重渲染对应页。
 */
@Page("MainTab", supportInLocal = true)
internal class MainTabPager : BasePager() {

    private val tabTitles = listOf("AI 聊天", "行情", "自选", "我的")
    private var selectedTab: Int by observable(1) // 默认展示行情 Tab

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(Color.WHITE)
            }
            // ===== TopBar（标题随选中 Tab 变化）=====
            View {
                attr {
                    height(48f)
                    flexDirectionRow()
                    alignItemsCenter()
                    padding(all = 14f)
                    backgroundColor(Color(0xFF23D3FD))
                }
                Text { attr { text(ctx.tabTitles[ctx.selectedTab]); fontSize(18f); color(Color.WHITE) } }
            }
            // ===== ContentArea（按 selectedTab 切换；在 attr 内读取选中态以触发响应式重渲染）=====
            View {
                attr { flex(1f); flexDirectionColumn() }
                // Tab0 AI 聊天
                View {
                    attr {
                        flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
                        if (ctx.selectedTab == 0) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    Text { attr { text("AI 聊天"); fontSize(20f); color(Color(0xFF222222)) } }
                    Text { attr { text("（待实现：会话列表 + 股票专属 AI）"); fontSize(13f); color(Color(0xFF999999)); marginTop(8f) } }
                }
                // Tab1 行情
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 1) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    KRStockList {
                        attr { flex(1f) }
                        stocks = MockStockSource.getQuotes()
                        onRowClick = { /* 展开/收起由 KRStockList 内部处理 */ }
                        onDetailClick = { stock ->
                            val data = JSONObject()
                            data.put("stockCode", stock.code)
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", data)
                        }
                    }
                }
                // Tab2 自选
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 2) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    KRStockList {
                        attr { flex(1f) }
                        stocks = MockStockSource.getQuotes() // TODO P2: 接自选股数据
                        onDetailClick = { stock ->
                            val data = JSONObject()
                            data.put("stockCode", stock.code)
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", data)
                        }
                    }
                }
                // Tab3 我的
                View {
                    attr {
                        flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
                        if (ctx.selectedTab == 3) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    Text { attr { text("我的"); fontSize(20f); color(Color(0xFF222222)) } }
                    Text { attr { text("（待实现：设置 / LLM Key）"); fontSize(13f); color(Color(0xFF999999)); marginTop(8f) } }
                }
            }
            // ===== BottomTabBar =====
            View {
                attr {
                    height(56f)
                    flexDirectionRow()
                    alignItemsCenter()
                    backgroundColor(Color.WHITE)
                }
                val tabs = listOf("AI", "行情", "自选", "我的")
                tabs.forEachIndexed { i, name ->
                    View {
                        attr {
                            flex(1f)
                            flexDirectionColumn()
                            alignItemsCenter()
                            justifyContentCenter()
                            // 选中态：浅青底（在 attr 内读取 selectedTab，确保响应式重渲染）
                            backgroundColor(if (ctx.selectedTab == i) Color(0xFFE6FBFF) else Color.WHITE)
                        }
                        event { click { ctx.selectedTab = i } }
                        Text {
                            attr {
                                text(name)
                                fontSize(if (ctx.selectedTab == i) 13f else 12f)
                                color(if (ctx.selectedTab == i) Color(0xFF23D3FD) else Color(0xFF999999))
                            }
                        }
                        // 选中指示器小横条（瞬时显隐，明确当前位置）
                        View {
                            attr {
                                width(22f)
                                height(3f)
                                marginTop(4f)
                                borderRadius(1.5f)
                                backgroundColor(Color(0xFF23D3FD))
                                opacity(if (ctx.selectedTab == i) 1f else 0f)
                            }
                        }
                    }
                }
            }
        }
    }
}
