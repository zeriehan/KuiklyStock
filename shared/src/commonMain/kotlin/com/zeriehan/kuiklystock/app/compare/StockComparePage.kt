package com.zeriehan.kuiklystock.app.compare

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.formatPrice

/**
 * 「股票对比」页（#96）：多股并排对比 + AI 对比解读。
 *
 * 布局（上区固定约 2/5 高度，下区约 3/5）：
 * - 上区：股票对比轮播，一"页"一只股票（名字可点击换股 / 迷你走势可切周期 / 实时数据），
 *   横滑切换，最右一页为「+」可继续添加对比股。
 * - 下区：针对当前对比股票列表的「对比 AI」聊天。
 *
 * 本文件为框架骨架（阶段 #98）：入口已由 MainTabPager.openCompare 接入；上/下区分别由
 * 后续阶段（#99 股票轮播、#100 对比聊天）填充。渲染沿用项目铁律：body 不随 observable 重跑，
 * 列表/内容用 vif 双分支 + toggle 翻转重建。
 */
@Page("StockCompare", supportInLocal = true)
internal class StockComparePage : BasePager() {

    /** 当前参与对比的股票 code 列表（有序） */
    internal var compareCodes: List<String> by observable(emptyList())
    /** vif 翻转触发器：代码列表变化后强制内容区重建 */
    internal var uiToggle: Boolean by observable(false)
    /** 上区横向当前页（圆点指示） */
    internal var currentPage: Int by observable(0)

    override fun viewDidLoad() {
        super.viewDidLoad()
        val raw = pageData.params.optString("stocks")
        val list = if (raw.isNotBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
        compareCodes = if (list.isNotEmpty()) list else listOf("600519", "000858")
        // 拉各股真实行情/分时/K线，保证对比数据真
        compareCodes.forEach { code ->
            val st = StockData.findByCode(code)
            if (!st.isIndex) {
                StockData.loadTrends(st) { uiToggle = !uiToggle }
                StockData.loadKline(st, "日", 80) { uiToggle = !uiToggle }
            }
        }
        uiToggle = !uiToggle
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flexDirectionColumn(); backgroundColor(if (UserSettings.darkMode) Color(0xFF1A1B1E) else Color(0xFFF2F3F5)) }

            // ===== 顶部返回栏 =====
            View {
                attr {
                    padding(12f); paddingTop(pagerData.statusBarHeight); height(44f + pagerData.statusBarHeight)
                    flexDirectionRow(); alignItemsCenter()
                    backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemiBold() } }
                }
                Text {
                    attr { text("股票对比"); fontSize(17f); color(Color(0xFF222222)); fontWeightSemiBold(); marginLeft(8f) }
                }
                View { attr { flex(1f) } }
            }

            // ===== 上区（约 2/5）：对比股票轮播 =====
            vif({ ctx.uiToggle }) { val c = this; c.renderCompareUpper(ctx) }
            vif({ !ctx.uiToggle }) { val c = this; c.renderCompareUpper(ctx) }

            // 分隔
            View { attr { height(8f); backgroundColor(Color(0xFFF2F3F5)) } }

            // ===== 下区（约 3/5）：对比 AI 聊天 =====
            vif({ ctx.uiToggle }) { val c = this; c.renderCompareChatPlaceholder(ctx) }
            vif({ !ctx.uiToggle }) { val c = this; c.renderCompareChatPlaceholder(ctx) }
        }
    }
}

/** 上区占位实现（阶段 #98）：显示当前对比股为横向分页卡片（名 + 现价 + 涨跌幅 + 占位迷你走势框）。
 *  阶段 #99 将替换为可换股 / 可切周期的完整对比组件。 */
private fun ViewContainer<*, *>.renderCompareUpper(ctx: StockComparePage) {
    val stocks = ctx.compareCodes.map { StockData.findByCode(it) }
    View {
        attr {
            height(190f)  // 顶部返回栏外，上区约占屏高（本骨架先固定，阶段内再按 2/5 由父布局给 flex）
            flexDirectionColumn(); padding(10f)
        }
        // 页标题行 + 分页圆点
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(8f); paddingLeft(2f) }
            Text { attr { text("对比"); fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)) } }
            View { attr { flex(1f) } }
            if (stocks.size > 1) {
                View {
                    attr { flexDirectionRow(); alignItemsCenter() }
                    stocks.forEachIndexed { di, _ ->
                        View {
                            attr {
                                width(7f); height(7f); borderRadius(3.5f); marginLeft(4f)
                                backgroundColor(if (ctx.currentPage == di) Color(UserSettings.themeColor) else Color(0xFFD0D3D8))
                            }
                        }
                    }
                }
            }
        }
        // 横向分页：一页一只股票
        Scroller {
            attr { flexDirectionRow(); flex(1f) }
            event {
                scroll(sync = true) { p ->
                    val vw = if (p.viewWidth > 0f) p.viewWidth else 1f
                    ctx.currentPage = (p.offsetX / vw + 0.5f).toInt()
                }
            }
            stocks.forEach { st ->
                View {
                    attr {
                        width(ctx.pagerData.pageViewWidth - 20f); height(150f); marginRight(10f)
                        padding(12f); borderRadius(10f); flexDirectionColumn()
                        backgroundColor(Color.WHITE)
                    }
                    // 名 + code
                    View { attr { flexDirectionRow(); alignItemsCenter() }
                        Text { attr { text(st.name); fontSize(UserSettings.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        Text { attr { text(st.code); fontSize(UserSettings.fs(11f)); color(Color(0xFF999999)); marginLeft(6f) } }
                        View { attr { flex(1f) } }
                        Text { attr { text(formatPercent(st.changePercent)); fontSize(UserSettings.fs(13f)); color(StockColor.text(st.changePercent)) } }
                    }
                    // 现价
                    View { attr { flexDirectionRow(); alignItemsCenter(); marginTop(6f) }
                        Text { attr { text(formatPrice(st.price)); fontSize(UserSettings.fs(22f)); fontWeightSemiBold(); color(StockColor.text(st.changePercent)) } }
                    }
                    // 迷你走势占位框（阶段 #99 换成真迷你走势 + 周期切换）
                    View {
                        attr {
                            flex(1f); marginTop(8f); borderRadius(6f)
                            backgroundColor(Color(0xFFF7F8FA)); justifyContentCenter(); alignItemsCenter()
                        }
                        Text { attr { text("走势图（待接入）"); fontSize(UserSettings.fs(12f)); color(Color(0xFFBBBBBB)) } }
                    }
                }
            }
            // 最右「+」页：继续添加对比股（阶段 #99 接选股）
            View {
                attr {
                    width(60f); height(150f); borderRadius(10f)
                    justifyContentCenter(); alignItemsCenter()
                    backgroundColor(Color.WHITE)
                }
                Text { attr { text("＋"); fontSize(30f); color(Color(0xFFBBBBBB)) } }
            }
        }
    }
}

/** 下区占位（阶段 #98）：对比 AI 聊天区，阶段 #100 接真实聊天。 */
private fun ViewContainer<*, *>.renderCompareChatPlaceholder(ctx: StockComparePage) {
    View {
        attr { flex(1f); padding(12f) }
        View {
            attr { flex(1f); borderRadius(10f); backgroundColor(Color.WHITE); justifyContentCenter(); alignItemsCenter() }
            Text { attr { text("对比 AI 聊天（待接入）"); fontSize(UserSettings.fs(14f)); color(Color(0xFF999999)) } }
        }
    }
}
