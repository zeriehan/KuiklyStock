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
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.MockStockSource
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
        Text { attr { text(label); fontSize(11f); color(Color(0xFF999999)) } }
        Text { attr { text(value); fontSize(13f); color(Color(0xFF333333)); marginTop(3f) } }
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

    /** 展开某行时按需拉取 AI 分析（首次自动；force=true 用于「重试」按钮强制刷新）。
     *  结果写入共享 [AIAnalysisStore]（key=股票代码），与详情页读取同一份，确保内容完全一致。 */
    private fun loadAI(index: Int, stock: Stock, force: Boolean = false) {
        val code = stock.code
        if (!force && (AIAnalysisStore.get(code) != null || aiLoadingCodes.contains(code))) return
        aiLoadingCodes = aiLoadingCodes + code
        LLM.client.analyze(stock, MockStockSource.getKLine(stock, "日")) { result ->
            val ts = Utils.currentBridgeModule().currentTimeStamp()
            val tText = if (ts > 0) Utils.currentBridgeModule().dateFormatter(ts, "MM-dd HH:mm") else ""
            AIAnalysisStore.put(code, result, tText)
            aiLoadingCodes = aiLoadingCodes - code
        }
    }

    // ===== 简况（自定义模块占位）派生：演示数据，后续「我的-设置」可配置模块列表与顺序 =====
    private fun codeSum(code: String): Int = code.filter { it.isDigit() }.sumOf { it.code }
    private fun deriveIndustry(name: String): String = when {
        name.contains("茅台") || name.contains("五粮液") -> "白酒"
        name.contains("银行") -> "银行"
        name.contains("平安") -> "保险"
        name.contains("宁德") -> "电池"
        name.contains("指数") -> "大盘指数"
        else -> "制造业"
    }
    private fun deriveMarketCap(code: String): String = (codeSum(code) % 9000 + 500).toString()
    private fun derivePE(code: String): String = (codeSum(code) % 40 + 8).toString()
    private fun deriveTurnover(code: String): String = formatPrice((codeSum(code) % 30 + 5) / 10f) + "%"
    private fun deriveIntro(name: String): String = when {
        name.contains("茅台") || name.contains("五粮液") -> "白酒行业龙头，品牌护城河深厚。"
        name.contains("银行") -> "零售银行标杆，资产质量稳健。"
        name.contains("平安") -> "综合金融集团，寿险财险双轮驱动。"
        name.contains("宁德") -> "动力电池全球龙头，市占率领先。"
        name.contains("指数") -> "A股核心宽基指数，代表市场整体表现。"
        else -> "细分领域优质企业，业绩稳健增长。"
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
                                // 分页轮播
                                Scroller {
                                    attr {
                                        flexDirectionRow()
                                        height(150f)
                                        pagingEnable(true)
                                        showScrollerIndicator(false)
                                    }
                                    event {
                                        scroll(sync = true) { p ->
                                            val vw = if (p.viewWidth > 0f) p.viewWidth else 1f
                                            ctx.currentPage = (p.offsetX / vw + 0.5f).toInt().coerceIn(0, 2)
                                        }
                                    }
                                    // ===== Page 1：当天分时图（休市取最近交易日；带价格 + 十字光标）=====
                                    View {
                                        attr {
                                            width(pageW)
                                            height(150f)
                                            flexDirectionColumn()
                                            padding(12f)
                                            backgroundColor(Color(0xFFF7F8FA))
                                        }
                                        KRMiniTimeSharing {
                                            points = MockStockSource.getIntraday(stock)
                                            refPrice = (stock.price - stock.change).coerceAtLeast(0.01f)
                                            color = StockColor.of(stock.changePercent)
                                        }
                                    }
                                    // ===== Page 2：AI 智能分析（引用共享 AIAnalysisStore，与详情页完全一致）=====
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
                                            View { attr { flex(1f) } }
                                            Text {
                                                attr {
                                                    val busy = ctx.aiLoadingCodes.contains(stock.code)
                                                    val t = AIAnalysisStore.get(stock.code)?.timeText ?: ""
                                                    text(if (busy) "分析中…" else t)
                                                    fontSize(12f); color(Color(0xFF999999)); marginRight(8f)
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
                                                fontSize(13f); color(Color(0xFF555555)); marginTop(8f)
                                                lines(5); textOverFlowTail()   // 超长文本末尾省略号，避免溢出卡片
                                            }
                                        }
                                    }
                                    // ===== Page 3：用户自定义模块（暂为「简况」，后续「我的-设置」可增删顺序）=====
                                    View {
                                        attr {
                                            width(pageW)
                                            height(150f)
                                            flexDirectionColumn()
                                            padding(14f)
                                            backgroundColor(Color(0xFFF7F8FA))
                                        }
                                        Text { attr { text("简况"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                                        View {
                                            attr { flexDirectionRow(); marginTop(10f) }
                                            briefCell("行业", ctx.deriveIndustry(stock.name))
                                            briefCell("总市值", ctx.deriveMarketCap(stock.code) + "亿")
                                            briefCell("市盈率TTM", ctx.derivePE(stock.code))
                                        }
                                        View {
                                            attr { flexDirectionRow(); marginTop(8f) }
                                            briefCell("换手率", ctx.deriveTurnover(stock.code))
                                            briefCell("最高", formatPrice(stock.high))
                                            briefCell("最低", formatPrice(stock.low))
                                        }
                                        Text {
                                            attr {
                                                text("简介：" + ctx.deriveIntro(stock.name))
                                                fontSize(12f); color(Color(0xFF777777)); marginTop(10f)
                                            }
                                        }
                                    }
                                }
                                // 分页圆点指示（当前页高亮）
                                View {
                                    attr { flexDirectionRow(); justifyContentCenter(); height(18f); marginTop(2f) }
                                    View { attr { width(7f); height(7f); borderRadius(3.5f); marginRight(6f); backgroundColor(if (ctx.currentPage == 0) Color(0xFF23D3FD) else Color(0xFFD0D3D8)) } }
                                    View { attr { width(7f); height(7f); borderRadius(3.5f); marginRight(6f); backgroundColor(if (ctx.currentPage == 1) Color(0xFF23D3FD) else Color(0xFFD0D3D8)) } }
                                    View { attr { width(7f); height(7f); borderRadius(3.5f); backgroundColor(if (ctx.currentPage == 2) Color(0xFF23D3FD) else Color(0xFFD0D3D8)) } }
                                }
                            }
                            // ===== 常驻：关键信息 + 详细按钮（同行，字号缩小、留白收紧）=====
                            View {
                                attr { flexDirectionRow(); alignItemsCenter(); marginTop(8f); paddingLeft(12f); paddingRight(12f) }
                                Text { attr { text("最高 " + formatPrice(stock.high)); fontSize(12f); color(Color(0xFF666666)) } }
                                Text { attr { text("最低 " + formatPrice(stock.low)); fontSize(12f); color(Color(0xFF666666)); marginLeft(10f) } }
                                Text { attr { text("量 " + formatPrice(stock.volume) + "万"); fontSize(12f); color(Color(0xFF666666)); marginLeft(10f) } }
                                View { attr { flex(1f) } }   // 把「详细」推到最右
                                Button {
                                    attr {
                                        size(58f, 26f)
                                        marginLeft(10f)
                                        borderRadius(13f)
                                        backgroundColor(Color(0xFF23D3FD))
                                        titleAttr { text("详细"); fontSize(12f); color(Color.WHITE) }
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
                                if (willExpand) { ctx.loadAI(index, stock); ctx.currentPage = 0 }
                                ctx.onRowClick?.invoke(stock)
                            }
                            longPress { p ->
                                // 长按手势可能分 start/move/end 多次回调；由父层按 sheetStock 去重，
                                // 这里只负责把「股票 + 长按点坐标」抛上去定位菜单。
                                ctx.onRowLongPress?.invoke(stock, p.pageX, p.pageY)
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
