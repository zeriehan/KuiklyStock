package com.zeriehan.kuiklystock.app.detail

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.compose.Button
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.MockStockSource
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.components.KRKLineChart.KRKLineChart

/**
 * 个股详情页（P2，骨架 + 假数据）。
 * 进入方式：行情/自选列表点行 -> RouterModule.openPage("StockDetail", stockCode)。
 * 布局：返回栏 + 实时价 + K线蜡烛卡片 + 可定制模块区（AI分析/简况/财务/资金/新闻）+ "深入聊聊"按钮。
 */
@Page("StockDetail", supportInLocal = true)
internal class StockDetailPage : BasePager() {

    /** 可选模块（默认勾选前三个） */
    private enum class DModule { AI, PROFILE, FINANCE, FUND, NEWS }
    private val moduleLabels = mapOf(
        DModule.AI to "AI分析",
        DModule.PROFILE to "简况",
        DModule.FINANCE to "财务",
        DModule.FUND to "资金",
        DModule.NEWS to "新闻",
    )
    private var modules: List<DModule> by observable(
        listOf(DModule.AI, DModule.PROFILE, DModule.FINANCE)
    )

    override fun body(): ViewBuilder {
        val ctx = this
        val code = pageData.params.optString("stockCode")
        val stock = MockStockSource.findByCode(code)
        val kline = MockStockSource.getKLine(stock)
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(Color(0xFFF2F3F5))
            }

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
                // 返回（关闭页面）
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemisolid() } }
                }
                Text { attr { text(stock.name); fontSize(17f); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f) } }
                Text { attr { text(stock.code); fontSize(12f); color(Color(0xFF999999)); marginLeft(8f) } }
            }

            // ===== 滚动内容 =====
            Scroller {
                attr { flex(1f); flexDirectionColumn() }

                // 实时价
                View {
                    attr { flexDirectionColumn(); padding(16f); backgroundColor(Color.WHITE) }
                    Text {
                        attr {
                            text(formatPrice(stock.price))
                            fontSize(30f)
                            fontWeightSemisolid()
                            color(Color(0xFF222222))
                        }
                    }
                    Text {
                        attr {
                            text(formatPrice(stock.change) + "  " + formatPercent(stock.changePercent))
                            fontSize(14f)
                            color(StockColor.of(stock.changePercent))
                            marginTop(4f)
                        }
                    }
                }

                // K线卡片
                View {
                    attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                    Text { attr { text("K线"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                    // 周期切换（静态，日选中）
                    View {
                        attr { flexDirectionRow(); marginTop(8f) }
                        listOf("日", "周", "月", "年").forEachIndexed { i, t ->
                            Text {
                                attr {
                                    text(t)
                                    fontSize(13f)
                                    color(if (i == 0) Color(0xFF23D3FD) else Color(0xFF999999))
                                    marginRight(16f)
                                }
                            }
                        }
                    }
                    KRKLineChart { attr { marginTop(8f) }; bars = kline }
                }

                // 模块芯片（点击增删模块）
                View {
                    attr { flexDirectionRow(); padding(12f); alignItemsCenter() }
                    ctx.moduleLabels.forEach { (m, label) ->
                        val on = ctx.modules.contains(m)
                        View {
                            attr {
                                paddingLeft(12f); paddingRight(12f); height(28f); borderRadius(14f)
                                backgroundColor(if (on) Color(0xFF23D3FD) else Color.WHITE)
                                marginRight(8f); marginTop(8f); justifyContentCenter(); alignItemsCenter()
                                border(if (on) Border(0f, BorderStyle.SOLID, Color(0)) else Border(1f, BorderStyle.SOLID, Color(0xFFDDDDDD)))
                            }
                            event { click { ctx.modules = if (on) ctx.modules - m else ctx.modules + m } }
                            Text {
                                attr {
                                    text(label + if (on) " ✓" else "")
                                    fontSize(12f)
                                    color(if (on) Color.WHITE else Color(0xFF666666))
                                }
                            }
                        }
                    }
                }

                // AI 分析卡片（Task01 验收点；此处为假数据，真接 LLM 留待后续）
                vif({ ctx.modules.contains(DModule.AI) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        View {
                            attr { flexDirectionRow(); alignItemsCenter() }
                            View {
                                attr { width(18f); height(18f); borderRadius(9f); backgroundColor(Color(0xFFE6F1FB)); marginRight(6f) }
                            }
                            Text { attr { text("AI 智能分析"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        }
                        Text {
                            attr {
                                text("基于近期量价与资金面，${stock.name} 处于震荡上行通道，短期受板块情绪带动明显；建议结合仓位控制，关注下方支撑位的有效性。")
                                fontSize(13f); color(Color(0xFF555555)); marginTop(8f)
                            }
                        }
                        Button {
                            attr {
                                size(200f, 36f); marginTop(12f); borderRadius(18f)
                                backgroundColor(Color(0xFF23D3FD))
                                titleAttr { text("深入聊聊这只股票 →"); fontSize(14f); color(Color.WHITE) }
                            }
                            event { click { ctx.bridgeModule.toast("深入聊聊（Task02 待实现）") } }
                        }
                    }
                }

                // 简况卡片
                vif({ ctx.modules.contains(DModule.PROFILE) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        Text { attr { text("简况"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("所属行业"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text("白酒 / 饮料制造"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("总市值"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text(formatPrice(stock.price * 12.56f) + " 亿"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                    }
                }

                // 财务卡片
                vif({ ctx.modules.contains(DModule.FINANCE) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        Text { attr { text("财务"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("营收 / 净利"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text("1478亿 / 747亿"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("ROE"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text("34.6%"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                    }
                }

                // 资金 / 新闻（占位）
                vif({ ctx.modules.contains(DModule.FUND) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        Text { attr { text("资金流向"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        Text { attr { text("（待接入）"); fontSize(13f); color(Color(0xFF999999)); marginTop(8f) } }
                    }
                }
                vif({ ctx.modules.contains(DModule.NEWS) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        Text { attr { text("相关新闻"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        Text { attr { text("（待接入）"); fontSize(13f); color(Color(0xFF999999)); marginTop(8f) } }
                    }
                }

                View { attr { height(16f) } }
            }
        }
    }
}
