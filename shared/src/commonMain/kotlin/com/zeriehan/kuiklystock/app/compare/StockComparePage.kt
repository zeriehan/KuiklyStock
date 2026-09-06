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
import com.zeriehan.kuiklystock.components.KRMiniTimeSharing.KRMiniTimeSharing
import com.zeriehan.kuiklystock.components.KRTrendChart.KRTrendChart
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
    /** 每只股票独立的迷你走势周期（"intraday" 分时 / "day" 日K），默认分时 */
    internal var comparePeriods: Map<String, String> by observable(emptyMap())

    override fun viewDidLoad() {
        super.viewDidLoad()
        val raw = pageData.params.optString("stocks")
        val list = if (raw.isNotBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
        compareCodes = if (list.isNotEmpty()) list else listOf("600519", "000858")
        // 每只股默认迷你走势周期为「分时」
        comparePeriods = compareCodes.associateWith { "intraday" }
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

    /** 切换某股迷你走势周期（"intraday" 分时 / "day" 日K）。 */
    internal fun setPeriod(code: String, period: String) {
        comparePeriods = comparePeriods.toMutableMap().apply { put(code, period) }
        // body 不随 observable 重跑，触发重建以交换图表组件（KRMiniTimeSharing ↔ KRTrendChart）
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

            // ===== 分隔（尽量薄，让上区股票与下区聊天贴近）=====
            View { attr { height(4f); backgroundColor(Color(0xFFF2F3F5)) } }

            // ===== 下区（约 3/5）：对比 AI 聊天 =====
            vif({ ctx.uiToggle }) { val c = this; c.renderCompareChatPlaceholder(ctx) }
            vif({ !ctx.uiToggle }) { val c = this; c.renderCompareChatPlaceholder(ctx) }
        }
    }
}

/** 上区实现（阶段 #98/#99 过渡）：当前对比股横向分页卡片（名+价紧凑行 + 紧凑走势区）。
 *  走势区高度对标自选展开迷你图(KRMiniTimeSharing 122 高)，紧凑不占大块。 */
private fun ViewContainer<*, *>.renderCompareUpper(ctx: StockComparePage) {
    val stocks = ctx.compareCodes.map { StockData.findByCode(it) }
    View {
        attr {
            height(206f)  // 卡片 170(名24+间距+走势122) + 标题行 + 内边距；上区紧凑, 走势与自选展开同尺寸
            flexDirectionColumn(); paddingTop(6f); paddingLeft(10f); paddingRight(10f); paddingBottom(2f)
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
                val code = st.code
                val period = ctx.comparePeriods[code] ?: "intraday"
                View {
                    attr {
                        width(ctx.pagerData.pageViewWidth - 20f); height(170f); marginRight(10f)
                        padding(10f); borderRadius(10f); flexDirectionColumn()
                        backgroundColor(Color.WHITE)
                    }
                    // 名 + code + 价+涨跌（紧凑单行）
                    View { attr { flexDirectionRow(); alignItemsCenter() }
                        Text { attr { text(st.name); fontSize(UserSettings.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        Text { attr { text(st.code); fontSize(UserSettings.fs(11f)); color(Color(0xFF999999)); marginLeft(6f) } }
                        View { attr { flex(1f) } }
                        Text { attr { text(formatPrice(st.price) + "  " + formatPercent(st.changePercent)); fontSize(UserSettings.fs(14f)); fontWeightSemisolid(); color(StockColor.text(st.changePercent)) } }
                    }
                    // 周期 chips（分时 / 日K）；对标自选展开迷你图尺寸的紧凑切换
                    View { attr { flexDirectionRow(); marginTop(6f) }
                        comparePeriodChip(ctx, code, "intraday", "分时", period)
                        comparePeriodChip(ctx, code, "day", "日K", period)
                    }
                    // 紧凑迷你走势：分时用 KRMiniTimeSharing(自选展开同款)，日K用 KRTrendChart 收盘价趋势
                    if (period == "intraday") {
                        KRMiniTimeSharing {
                            points = StockData.getIntraday(st)
                            refPrice = StockData.intradayRefPrice(st)
                            color = StockColor.of(st.changePercent)
                        }
                    } else {
                        val closes = StockData.getKLine(st, "日", 60).map { it.close }
                        KRTrendChart {
                            points = closes
                            chartHeight = 122f
                        }
                    }
                }
            }
            // 最右「+」页：继续添加对比股（阶段 #99 接选股）
            View {
                attr {
                    width(60f); height(170f); borderRadius(10f)
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
        attr { flex(1f); paddingLeft(10f); paddingRight(10f); paddingBottom(4f) }
        View {
            attr { flex(1f); borderRadius(10f); backgroundColor(Color.WHITE); justifyContentCenter(); alignItemsCenter() }
            Text { attr { text("对比 AI 聊天（待接入）"); fontSize(UserSettings.fs(14f)); color(Color(0xFF999999)) } }
        }
    }
}

/** 对比页迷你走势周期切换 chip（点选改 ctx.comparePeriods，attr 现读即时变色）。 */
private fun ViewContainer<*, *>.comparePeriodChip(
    ctx: StockComparePage, code: String, key: String, label: String, current: String
) {
    val on = current == key
    View {
        attr {
            paddingLeft(8f); paddingRight(8f); height(22f); borderRadius(11f); marginRight(6f)
            justifyContentCenter(); alignItemsCenter()
            backgroundColor(if (on) Color(UserSettings.themeColor) else Color(0xFFF2F3F5))
        }
        event { click { ctx.setPeriod(code, key) } }
        Text {
            attr {
                text(label)
                fontSize(UserSettings.fs(11f))
                color(if (on) Color.WHITE else Color(0xFF666666))
            }
        }
    }
}
