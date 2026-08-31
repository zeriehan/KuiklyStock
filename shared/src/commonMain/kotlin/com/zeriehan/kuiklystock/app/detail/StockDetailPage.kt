package com.zeriehan.kuiklystock.app.detail

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.compose.Button
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.MockStockSource
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.llm.AIAnalysisStore
import com.zeriehan.kuiklystock.core.llm.LLM
import com.zeriehan.kuiklystock.components.KRRefreshButton.KRRefreshButton
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

    /** K线周期：0=分时 1=日 2=周 3=月 4=年（默认分时，展示新能力） */
    private val periods = listOf("分时", "日", "周", "月", "年")
    private var selectedPeriod: Int by observable(0)
    /** K线已加载根数（加载更多历史时递增，保证随机游走首尾连续） */
    private var klineCount: Int by observable(60)
    /** 当前指标：主图 / MACD / RSI / BOLL */
    private var selectedIndicator: Int by observable(KRKLineChart.IND_NONE)
    /** K线图引用，用于切换周期时刷新数据（ref 返回 ViewRef，取 .view 拿实例） */
    private var chartRef: ViewRef<KRKLineChart>? = null
    /** AI 分析文本（详情页 AI 卡由 LLM 层生成） */
    private var aiText: String by observable("")
    /** AI 分析加载中：控制「重试」按钮置灰/禁用与「分析中…」文案 */
    private var aiLoading: Boolean by observable(false)
    /** AI 分析时间文案（如 08-29 17:52）；首次进入为空，分析完成后写入；再次进入直接读缓存 */
    private var aiTimeText: String by observable("")

    /**
     * 执行一次 AI 分析。
     * - force=false 且进程内已有该股票缓存：直接展示缓存（首次进入之后不再自动调模型，省额度 / 不闪动）。
     * - 否则：置 loading，调 LLM，回填文本 + 分析时间（MM-dd HH:mm），并写入缓存。
     */
    private fun runAnalysis(stock: Stock, code: String, force: Boolean) {
        if (!force) {
            AIAnalysisStore.get(code)?.let {
                aiText = it.text
                aiTimeText = it.timeText
                return
            }
        }
        aiLoading = true
        LLM.client.analyze(stock, MockStockSource.getKLine(stock, "日")) { text ->
            val ts = Utils.currentBridgeModule().currentTimeStamp()
            val tText = if (ts > 0) Utils.currentBridgeModule().dateFormatter(ts, "MM-dd HH:mm") else ""
            aiText = text
            aiTimeText = tText
            aiLoading = false
            AIAnalysisStore.put(code, text, tText)
        }
    }

    /** 用户点击圆形「重试」按钮时触发：强制重新分析 */
    private fun reanalyze(stock: Stock, code: String) {
        runAnalysis(stock, code, force = true)
    }

    /** 按周期切换 K线图数据 / 分时数据，并同步当前指标与十字光标清理 */
    private fun applyPeriod(stock: Stock, i: Int) {
        chartRef?.view?.let { chart ->
            chart.clearCrosshair()
            if (i == 0) {
                // 分时模式：下发分时数据 + 昨收基准，清空 K线，指标置空
                chart.bars = emptyList()
                chart.timeSharing = MockStockSource.getIntraday(stock)
                chart.refPrice = (stock.price - stock.change).coerceAtLeast(0.01f)
                chart.indicator = KRKLineChart.IND_NONE
            } else {
                // K线模式：下发对应周期（含已加载历史根数），清空分时，恢复所选指标
                chart.timeSharing = emptyList()
                chart.bars = MockStockSource.getKLine(stock, periods[i], klineCount)
                chart.indicator = selectedIndicator
            }
        }
    }

    /** 设置指标（主图 / MACD / RSI / BOLL），仅 K线模式生效 */
    private fun applyIndicator(stock: Stock, ind: Int) {
        selectedIndicator = ind
        if (selectedPeriod != 0) {
            chartRef?.view?.let { it.indicator = ind }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val code = pageData.params.optString("stockCode")
        val stock = MockStockSource.findByCode(code)
        // 触发 AI 分析：首次进入自动分析；若已有缓存（同一股票再次进入）则直接展示缓存结果 + 时间
        ctx.runAnalysis(stock, code, false)
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

                // 实时价（价格与涨跌幅同处一行）
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); padding(16f); backgroundColor(Color.WHITE) }
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
                            marginLeft(10f)
                        }
                    }
                }

                // K线卡片
                View {
                    attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                    Text { attr { text("K线"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                    // 周期切换（分时/日/周/月/年 可点击；高亮随 selectedPeriod 响应式刷新，图表数据同步切换）
                    View {
                        attr { flexDirectionRow(); marginTop(8f) }
                        ctx.periods.forEachIndexed { i, t ->
                            View {
                                attr {
                                    paddingLeft(10f); paddingRight(10f); height(24f); borderRadius(12f)
                                    marginRight(8f); justifyContentCenter(); alignItemsCenter()
                                    backgroundColor(if (ctx.selectedPeriod == i) Color(0xFF23D3FD) else Color(0xFFF2F3F5))
                                }
                                event { click {
                                    ctx.selectedPeriod = i
                                    ctx.applyPeriod(stock, i)
                                } }
                                Text {
                                    attr {
                                        text(t)
                                        fontSize(13f)
                                        color(if (ctx.selectedPeriod == i) Color.WHITE else Color(0xFF666666))
                                    }
                                }
                            }
                        }
                    }
                    // 指标切换（仅 K线模式可用：主图 / MACD / RSI / BOLL）
                    vif({ ctx.selectedPeriod != 0 }) {
                        View {
                            attr { flexDirectionRow(); marginTop(8f) }
                            val inds = listOf(
                                KRKLineChart.IND_NONE to "主图",
                                KRKLineChart.IND_MACD to "MACD",
                                KRKLineChart.IND_RSI to "RSI",
                                KRKLineChart.IND_BOLL to "BOLL",
                            )
                            inds.forEach { (ind, label) ->
                                View {
                                    attr {
                                        paddingLeft(10f); paddingRight(10f); height(24f); borderRadius(12f)
                                        marginRight(8f); justifyContentCenter(); alignItemsCenter()
                                        backgroundColor(if (ctx.selectedIndicator == ind) Color(0xFF23D3FD) else Color(0xFFF2F3F5))
                                    }
                                    event { click { ctx.applyIndicator(stock, ind) } }
                                    Text {
                                        attr {
                                            text(label)
                                            fontSize(13f)
                                            color(if (ctx.selectedIndicator == ind) Color.WHITE else Color(0xFF666666))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // 图表区（横向滚动；分时 / K线 自适配；触摸拖动十字光标、双指缩放、滚到最左加载更多）
                    KRKLineChart {
                        ref { ctx.chartRef = it }
                        attr { marginTop(8f) }
                        timeSharing = MockStockSource.getIntraday(stock)
                        refPrice = (stock.price - stock.change).coerceAtLeast(0.01f)
                        onLoadMore = {
                            ctx.klineCount += 40
                            if (ctx.selectedPeriod != 0) {
                                ctx.chartRef?.view?.let { ch ->
                                    ch.bars = MockStockSource.getKLine(stock, ctx.periods[ctx.selectedPeriod], ctx.klineCount)
                                }
                            }
                        }
                    }
                }

                // 模块芯片（横向可滑动；点击增删模块，on 状态在 attr/event 闭包内读取）
                Scroller {
                    attr { flexDirectionRow(); height(52f); padding(12f); alignItemsCenter() }
                    ctx.moduleLabels.forEach { (m, label) ->
                        View {
                            attr {
                                paddingLeft(12f); paddingRight(12f); height(28f); borderRadius(14f)
                                marginRight(8f); justifyContentCenter(); alignItemsCenter()
                                val on = ctx.modules.contains(m)
                                backgroundColor(if (on) Color(0xFF23D3FD) else Color.WHITE)
                                border(if (on) Border(0f, BorderStyle.SOLID, Color(0)) else Border(1f, BorderStyle.SOLID, Color(0xFFDDDDDD)))
                            }
                            event { click {
                                val on = ctx.modules.contains(m)
                                ctx.modules = if (on) ctx.modules - m else ctx.modules + m
                            } }
                            Text {
                                attr {
                                    val on = ctx.modules.contains(m)
                                    text(label + if (on) " ✓" else "")
                                    fontSize(12f)
                                    color(if (on) Color.WHITE else Color(0xFF666666))
                                }
                            }
                        }
                    }
                }

                // AI 分析卡片（Task01 验收点；首进自动分析，之后再进读缓存，可手动刷新）
                vif({ ctx.modules.contains(DModule.AI) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        // 头部：标题 + 分析时间 + 圆形重试按钮
                        View {
                            attr { flexDirectionRow(); alignItemsCenter() }
                            View { attr { width(18f); height(18f); borderRadius(9f); backgroundColor(Color(0xFFE6F1FB)); marginRight(6f) } }
                            Text { attr { text("AI 智能分析"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                            View { attr { flex(1f) } }
                            Text {
                                attr {
                                    text(if (ctx.aiLoading) "分析中…" else ctx.aiTimeText)
                                    fontSize(12f); color(Color(0xFF999999)); marginRight(8f)
                                }
                            }
                            KRRefreshButton({ ctx.aiLoading }) { ctx.reanalyze(stock, code) }
                        }
                        Text {
                            attr {
                                text(if (ctx.aiText.isEmpty()) "AI 分析中…" else ctx.aiText)
                                fontSize(13f); color(Color(0xFF555555)); marginTop(8f)
                            }
                        }
                        Button {
                            attr {
                                size(200f, 36f); marginTop(12f); borderRadius(18f)
                                backgroundColor(Color(0xFF23D3FD))
                                titleAttr { text("深入聊聊这只股票 →"); fontSize(14f); color(Color.WHITE) }
                            }
                            event {
                                click {
                                    // 与行情长按「问 AI」共用同一段对话（按 stockCode 隔离）
                                    val data = JSONObject()
                                    data.put("stockCode", code)
                                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", data)
                                }
                            }
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
