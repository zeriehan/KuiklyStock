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
import com.zeriehan.kuiklystock.components.KRMiniTimeSharing.KRMiniTimeSharing
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockBrief
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.llm.LLM
import com.zeriehan.kuiklystock.core.llm.AIAnalysisStore
import com.zeriehan.kuiklystock.components.KRRefreshButton.KRRefreshButton

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
/** 简况单元格（两行：标签灰 + 数值深），用于 Page3 网格布局 */
private fun ViewContainer<*, *>.briefCell(label: String, value: String) {
    View {
        attr { flex(1f); flexDirectionColumn() }
        Text { attr { text(label); fontSize(UserSettings.fs(11f)); color(Color(0xFF999999)) } }
        Text { attr { text(value); fontSize(UserSettings.fs(13f)); color(Color(0xFF333333)); marginTop(3f) } }
    }
}

internal class KRStockList : ComposeView<KRStockListAttr, ComposeEvent>() {

    var stocks: List<Stock> by observable(emptyList())
    var onDetailClick: ((Stock) -> Unit)? = null
    var onRowClick: ((Stock) -> Unit)? = null
    /** 长按某行：弹出操作菜单。回调参数含长按点相对 Page 的坐标（pageX/pageY），供菜单定位 */
    var onRowLongPress: ((Stock, Float, Float) -> Unit)? = null
    private var expandedIndex: Int by observable(-1)
    /** 行情行展开后的横向分页：0=分时图 1=AI分析 2=简况（pagingEnable 整屏吸附；currentPage 仅用于圆点指示） */
    private var currentPage: Int by observable(0)
    /** 行情行右滑 AI 分析：加载中集合（key=股票代码）。
     *  分析结果与时间统一写入共享 [AIAnalysisStore]（详情页也读同一份），保证两边内容一模一样。 */
    private var aiLoadingCodes: Set<String> by observable(emptySet())

    /** 展开某行时异步拉取真实分时（迷你图用）。到达后翻转 trendToggle 让迷你图重读真实数据。 */
    private var trendToggle: Boolean by observable(false)
    private fun loadTrendsFor(stock: Stock) {
        StockData.loadTrends(stock) { trendToggle = !trendToggle }
    }

    /** 展开某行时按需拉取 AI 分析（首次自动；force=true 用于「重试」按钮强制刷新）。
     *  结果写入共享 [AIAnalysisStore]（key=股票代码），与详情页读取同一份，确保内容完全一致。 */
    private fun loadAI(index: Int, stock: Stock, force: Boolean = false) {
        val code = stock.code
        if (!force && (AIAnalysisStore.get(code) != null || aiLoadingCodes.contains(code))) return
        aiLoadingCodes = aiLoadingCodes + code
        LLM.client.analyze(stock, StockData.getKLine(stock, "日")) { result ->
            val ts = Utils.currentBridgeModule().currentTimeStamp()
            val tText = if (ts > 0) Utils.currentBridgeModule().dateFormatter(ts, "MM-dd HH:mm") else ""
            AIAnalysisStore.put(code, result, tText)
            aiLoadingCodes = aiLoadingCodes - code
        }
    }

    // ===== 简况派生：统一走 core.StockBrief（与个股详情页共用同一套口径），
    //      避免两处算法不一致导致同一只股票显示两个不同的市值/行业。 =====

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
                            // 横向分页轮播（pagingEnable 整屏吸附，松手即落定，无中间态）：
                            // Page1=当天分时图；Page2=AI 智能分析；Page3=用户自定义模块（暂为「简况」，后续「我的-设置」可配）。
                            // 外框 + 底部分页圆点；关键信息与「详细」按钮常驻框下方（始终可见）。
                            val pageW = ctx.getPager().pageData.pageViewWidth.let { if (it <= 0f) 360f else it }
                            // 外框容器
                            View {
                                attr {
                                    flexDirectionColumn()
                                    margin(all = 8f)
                                    borderRadius(10f)
                                    border(Border(1f, BorderStyle.SOLID, Color(0xFFE3E5E8)))
                                    backgroundColor(Color.WHITE)
                                }
                                // ===== 分页轮播（组件按「我的」设置动态增减：趋势=0 / AI=1 / 简况=2）=====
                                val pages = UserSettings.expandPages()
                                Scroller {
                                    attr {
                                        flexDirectionRow()
                                        height(if (pages.isEmpty()) 0f else 150f)
                                        pagingEnable(pages.size > 1)
                                        showScrollerIndicator(false)
                                    }
                                    event {
                                        scroll(sync = true) { p ->
                                            val vw = if (p.viewWidth > 0f) p.viewWidth else 1f
                                            ctx.currentPage = (p.offsetX / vw + 0.5f).toInt()
                                                .coerceIn(0, (pages.size - 1).coerceAtLeast(0))
                                        }
                                    }
                                    pages.forEach { id ->
                                        when (id) {
                                            // ===== Page 1：当天分时图（真实分时拉取到达后随 trendToggle 重建）=====
                                            0 -> View {
                                                attr {
                                                    width(pageW); height(150f); flexDirectionColumn()
                                                    padding(12f); backgroundColor(Color(0xFFF7F8FA))
                                                }
                                                vif({ ctx.trendToggle }) {
                                                    KRMiniTimeSharing {
                                                        points = StockData.getIntraday(stock)
                                                        refPrice = StockData.intradayRefPrice(stock)
                                                        color = Color(0xFFE54D42)
                                                    }
                                                }
                                                vif({ !ctx.trendToggle }) {
                                                    KRMiniTimeSharing {
                                                        points = StockData.getIntraday(stock)
                                                        refPrice = StockData.intradayRefPrice(stock)
                                                        color = Color(0xFFE54D42)
                                                    }
                                                }
                                            }
                                            // ===== Page 2：AI 智能分析（引用共享 AIAnalysisStore，与详情页完全一致）=====
                                            1 -> View {
                                                attr {
                                                    width(pageW); height(150f); flexDirectionColumn()
                                                    padding(16f); backgroundColor(Color(0xFFF7F8FA))
                                                }
                                                View {
                                                    attr { flexDirectionRow(); alignItemsCenter() }
                                                    View { attr { width(18f); height(18f); borderRadius(9f); backgroundColor(Color(0xFFE6F1FB)); marginRight(6f) } }
                                                    Text { attr { text("AI 智能分析"); fontSize(UserSettings.fs(14f)); fontWeightSemiBold(); color(Color(0xFF222222)) } }
                                                    View { attr { flex(1f) } }
                                                    Text {
                                                        attr {
                                                            val busy = ctx.aiLoadingCodes.contains(stock.code)
                                                            val t = AIAnalysisStore.get(stock.code)?.timeText ?: ""
                                                            text(if (busy) "分析中…" else t)
                                                            fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginRight(8f)
                                                        }
                                                    }
                                                    KRRefreshButton({ ctx.aiLoadingCodes.contains(stock.code) }) { ctx.loadAI(index, stock, force = true) }
                                                }
                                                // ⚠️ 必须在 attr 闭包内直接读取 observable（aiLoadingCodes 与 AIAnalysisStore），
                                                // 否则只在 vif 挂载时捕获一次旧值，AI 回调回填后不会重渲染（卡在"分析中"）。
                                                Text {
                                                    attr {
                                                        val busy = ctx.aiLoadingCodes.contains(stock.code)
                                                        val cached = AIAnalysisStore.get(stock.code)?.text
                                                        text(if (busy) "AI 分析中…" else (cached ?: "AI 分析中…"))
                                                        fontSize(UserSettings.fs(13f)); color(Color(0xFF555555)); marginTop(8f)
                                                        lines(5); textOverFlowTail()   // 超长文本末尾省略号，避免溢出卡片
                                                    }
                                                }
                                            }
                                            // ===== Page 3：简况 =====
                                            2 -> View {
                                                attr {
                                                    width(pageW); height(150f); flexDirectionColumn()
                                                    padding(14f); backgroundColor(Color(0xFFF7F8FA))
                                                }
                                                Text { attr { text("简况"); fontSize(UserSettings.fs(14f)); fontWeightSemiBold(); color(Color(0xFF222222)) } }
                                                View {
                                                    attr { flexDirectionRow(); marginTop(10f) }
                                                    briefCell("行业", StockBrief.industry(stock.name))
                                                    briefCell("总市值", StockBrief.marketCap(stock.code) + "亿")
                                                    briefCell("市盈率TTM", StockBrief.pe(stock.code))
                                                }
                                                View {
                                                    attr { flexDirectionRow(); marginTop(8f) }
                                                    briefCell("换手率", StockBrief.turnover(stock.code))
                                                    briefCell("最高", formatPrice(stock.high))
                                                    briefCell("最低", formatPrice(stock.low))
                                                }
                                                Text {
                                                    attr {
                                                        text("简介：" + StockBrief.intro(stock.name))
                                                        fontSize(UserSettings.fs(12f)); color(Color(0xFF777777)); marginTop(10f)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                // 分页圆点指示（随组件数量动态；当前页高亮为主题色）
                                if (pages.isNotEmpty()) {
                                    View {
                                        attr { flexDirectionRow(); justifyContentCenter(); height(18f); marginTop(2f) }
                                        pages.forEachIndexed { di, _ ->
                                            View {
                                                attr {
                                                    width(7f); height(7f); borderRadius(3.5f)
                                                    marginRight(if (di < pages.size - 1) 6f else 0f)
                                                    backgroundColor(if (ctx.currentPage == di) Color(UserSettings.themeColor) else Color(0xFFD0D3D8))
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    View {
                                        attr { flexDirectionRow(); justifyContentCenter(); height(18f); marginTop(2f) }
                                        Text {
                                            attr {
                                                text("展开组件已全部关闭，可到「我的 → 行情展开组件」开启")
                                                fontSize(UserSettings.fs(12f)); color(Color(0xFF999999))
                                            }
                                        }
                                    }
                                }
                            }
                            // ===== 常驻：关键信息 + 详细按钮（同行，字号缩小、留白收紧）=====
                            View {
                                attr { flexDirectionRow(); alignItemsCenter(); marginTop(8f); paddingLeft(12f); paddingRight(12f) }
                                Text { attr { text("最高 " + formatPrice(stock.high)); fontSize(UserSettings.fs(12f)); color(Color(0xFF666666)) } }
                                Text { attr { text("最低 " + formatPrice(stock.low)); fontSize(UserSettings.fs(12f)); color(Color(0xFF666666)); marginLeft(10f) } }
                                Text { attr { text("量 " + formatPrice(stock.volume) + "万"); fontSize(UserSettings.fs(12f)); color(Color(0xFF666666)); marginLeft(10f) } }
                                View { attr { flex(1f) } }   // 把「详细」推到最右
                                Button {
                                    attr {
                                        size(58f, 26f)
                                        marginLeft(10f)
                                        borderRadius(13f)
                                        backgroundColor(Color(UserSettings.themeColor))
                                        titleAttr { text("详细"); fontSize(UserSettings.fs(12f)); color(Color.WHITE) }
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
                                backgroundColor(if (ctx.expandedIndex == index) Color(UserSettings.themeTint(0.9f)) else Color.WHITE)
                            }
                            event {
                            click {
                                val willExpand = ctx.expandedIndex != index
                                ctx.expandedIndex = if (willExpand) index else -1
                                if (willExpand) { ctx.loadAI(index, stock); ctx.currentPage = 0; ctx.loadTrendsFor(stock) }
                                ctx.onRowClick?.invoke(stock)
                            }
                            longPress { p ->
                                // 长按手势可能分 start/move/end 多次回调；由父层按 sheetStock 去重，
                                // 这里只负责把「股票 + 长按点坐标」抛上去定位菜单。
                                ctx.onRowLongPress?.invoke(stock, p.pageX, p.pageY)
                            }
                            }
                            // 名称 + 代码（名称跟随涨跌配色，与价格/涨跌幅一致）
                            View {
                                attr { flex(1f); flexDirectionColumn() }
                                Text { attr { text(stock.name); fontSize(UserSettings.fs(16f)); color(StockColor.text(stock.changePercent)) } }
                                Text { attr { text(stock.code); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginTop(4f) } }
                            }
                            // 最新价（右对齐）
                            View {
                                attr { flex(1f); flexDirectionRow(); justifyContentFlexEnd() }
                                Text { attr { text(formatPrice(stock.price)); fontSize(UserSettings.fs(16f)); color(StockColor.text(stock.changePercent)) } }
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
