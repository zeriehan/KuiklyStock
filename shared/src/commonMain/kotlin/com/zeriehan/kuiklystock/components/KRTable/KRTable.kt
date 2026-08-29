package com.zeriehan.kuiklystock.components.KRTable

import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.components.KRStockBadge.KRStockBadge
import com.zeriehan.kuiklystock.components.KRTrendChart.KRTrendChart
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.MockStockSource
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.llm.LLM

/** 折叠行固定高度：KRStockList 折叠行 attr 的 height 需与此保持一致 */
private val ROW_HEIGHT = 64f

/**
 * 行情列表（含行内展开）：点击股票行 -> 向上展开（挤开上方行），
 * 原位展开迷你走势 + 关键信息 + "详细"按钮。向上展开可避免底行被底部 Tab 遮挡，
 * 因此无需任何自动滚动 / 偏移计算。
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
    /** 行情行右滑 AI 分析：按行缓存分析结果（key=行index） */
    private var aiCache: Map<Int, String> by observable(emptyMap())
    private var aiLoading: Set<Int> by observable(emptySet())

    /** 展开某行时按需拉取 AI 分析（Mock 同步返回，真实 GLM-4-Flash 异步回填） */
    private fun loadAI(index: Int, stock: Stock) {
        if (aiCache.containsKey(index) || aiLoading.contains(index)) return
        aiLoading = aiLoading + index
        LLM.client.analyze(stock, MockStockSource.getKLine(stock, "日")) { result ->
            aiCache = aiCache + (index to result)
            aiLoading = aiLoading - index
        }
    }

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
                    // 把「展开块 + 折叠行」包进同一容器：展开时整组加青色边框 + 上下间距，
                    // 让用户一眼看出「这条详情属于它下方的那只股票」（解决向上展开后归属不清的问题）。
                    View {
                        attr {
                            flexDirectionColumn()
                            // 展开/收起两分支都显式赋值边框与间距，避免收起时旧边框残留（Kuikly 属性残留坑）
                            val expanded = ctx.expandedIndex == index
                            marginTop(if (expanded) 8f else 0f)
                            marginBottom(if (expanded) 8f else 0f)
                            border(if (expanded) Border(1.5f, BorderStyle.SOLID, Color(0xFF23D3FD)) else Border(0f, BorderStyle.SOLID, Color(0)))
                        }
                        // ===== 展开态（行内，vif 瞬时挂载，向上展开、挤开上方行；用户放弃动画）=====
                        // 向上展开：展开块长在折叠行「上方」，底行展开也不会被底部 Tab 遮挡，
                        // 因此无需任何自动滚动 / 偏移计算（此前 postDelayed / layoutFrameDidChange 方案均失效）。
                        vif({ ctx.expandedIndex == index }) {
                            // 横向轮播区（固定高度，避免横滑 Scroller 在列容器里高度塌缩）：
                            // Page1=迷你走势图；Page2=AI 智能分析，右滑在两者间切换；两页各宽=页面宽度。
                            // 关键信息 + 「详细」按钮常驻在轮播下方（始终可见，无需翻页）。
                            View {
                                attr { flexDirectionColumn() }
                                val pageW = ctx.getPager().pageData.pageViewWidth.let { if (it <= 0f) 360f else it }
                                Scroller {
                                    attr {
                                        flexDirectionRow()
                                        height(150f)
                                    }
                                    // ===== Page 1：迷你走势图 + 右滑提示 =====
                                    View {
                                        attr {
                                            width(pageW)
                                            height(150f)
                                            flexDirectionColumn()
                                            padding(16f)
                                            backgroundColor(Color(0xFFF7F8FA))
                                        }
                                        // 迷你走势折线（自研 KRTrendChart，高 80f）
                                        KRTrendChart {
                                            points = stock.trend
                                            color = if (stock.isUp) Color(0xFFE54D42) else Color(0xFF1ABE5B)
                                        }
                                        Text {
                                            attr {
                                                text("〈 右滑查看 AI 分析")
                                                fontSize(12f); color(Color(0xFF23D3FD))
                                                marginTop(8f); alignSelfFlexEnd()
                                            }
                                        }
                                    }
                                    // ===== Page 2：AI 智能分析（与详情页卡片同源）=====
                                    View {
                                        attr {
                                            width(pageW)
                                            height(150f)
                                            flexDirectionColumn()
                                            padding(16f)
                                            backgroundColor(Color(0xFFF7F8FA))
                                        }
                                        View {
                                            attr { flexDirectionRow(); alignItemsCenter() }
                                            View { attr { width(18f); height(18f); borderRadius(9f); backgroundColor(Color(0xFFE6F1FB)); marginRight(6f) } }
                                            Text { attr { text("AI 智能分析"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                                        }
                                        val aiTxt = ctx.aiCache[index]
                                        val aiBusy = ctx.aiLoading.contains(index)
                                        Text {
                                            attr {
                                                text(if (aiBusy) "AI 分析中…" else (aiTxt ?: "AI 分析中…"))
                                                fontSize(13f); color(Color(0xFF555555)); marginTop(8f)
                                            }
                                        }
                                    }
                                }
                                // ===== 常驻：关键信息 =====
                                View {
                                    attr { flexDirectionRow(); marginTop(10f); paddingLeft(16f); paddingRight(16f) }
                                    Text { attr { text("最高 " + formatPrice(stock.high)); fontSize(13f); color(Color(0xFF666666)) } }
                                    Text { attr { text("最低 " + formatPrice(stock.low)); fontSize(13f); color(Color(0xFF666666)); marginLeft(16f) } }
                                    Text { attr { text("量 " + formatPrice(stock.volume) + "万"); fontSize(13f); color(Color(0xFF666666)); marginLeft(16f) } }
                                }
                                // 详细按钮
                                Button {
                                    attr {
                                        size(96f, 36f)
                                        marginTop(12f); marginRight(16f)
                                        alignSelfFlexEnd()
                                        borderRadius(18f)
                                        backgroundColor(Color(0xFF23D3FD))
                                        titleAttr { text("详细"); fontSize(14f); color(Color.WHITE) }
                                    }
                                    event { click { ctx.onDetailClick?.invoke(stock) } }
                                }
                            }
                        }

                        // ===== 折叠态行（展开时作为「被选中行」浅蓝高亮，进一步提示归属）=====
                        View {
                            attr {
                                height(ROW_HEIGHT)
                                padding(all = 14f)
                                flexDirectionRow()
                                alignItemsCenter()
                                backgroundColor(if (ctx.expandedIndex == index) Color(0xFFEAFBFF) else Color.WHITE)
                            }
                            event {
                                click {
                                    val willExpand = ctx.expandedIndex != index
                                    ctx.expandedIndex = if (willExpand) index else -1
                                    if (willExpand) ctx.loadAI(index, stock)
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
