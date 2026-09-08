package com.zeriehan.kuiklystock.app.compare

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.formatPrice

/**
 * 选股页（#96 对比股选择，复刻行情页"榜单+搜索"结构，行动作改为加入对比）。
 *
 * 整页竖向可滑。分块（用户确认的形态）：
 *   1. 顶部：已加对比股区（横向 chips，各带「×」删除；点＋/点某已选股可继续加）
 *   2. 中部：榜单子 Tab（涨幅 / 跌幅 / 换手 / 振幅）——与行情页同数据源
 *   3. 搜索框：按股票名/代码过滤榜单
 *   4. 榜单列表：market 风格行（名/代码/价/涨跌%），行尾「＋加入 / ✓已加」按钮；点整行或按钮都加入/移除。
 *
 * 数据复用行情页同源：
 *   - 榜单：真实榜 `StockData.rankOf(rankTab)`；无则用全池按 rankTab 本地排序兜底（同行情页）。
 *   - 不重复造选股 UI，行视觉对齐行情行（名随涨跌配色 + 涨跌%徽章 + 价）。
 *
 * 数据流：
 *   - 初始 codes 由对比页 openPage 的 pageData.initialCodes 传入（或默认 茅台/五粮液）。
 *   - 点「完成」→ 写 ComparePicker.pendingCodes → closePage；对比页手动「应用选股」应用。
 */
@Page("StockPicker", supportInLocal = true)
internal class StockPickerPage : BasePager() {

    /** 已选对比股 code 列表 */
    internal var picked: List<String> by observable(emptyList())
    /** 榜单 Tab：0=涨幅 1=跌幅 2=换手 3=振幅 */
    internal var rankTab: Int by observable(0)
    /** 搜索词 */
    internal var query: String by observable("")
    /** 内容区 vif 重建触发器（切换 Tab/加删股/搜索 时翻转） */
    internal var toggle: Boolean by observable(false)
    /** 已加对比 chips 区是否展开（默认折叠，节省纵向空间；点标题行展开/收起） */
    internal var chipsExpanded: Boolean by observable(false)
    internal lateinit var inputRef: ViewRef<InputView>

    internal val RANK_TABS = listOf("涨幅榜", "跌幅榜", "换手榜", "振幅榜", "全部")

    override fun viewDidLoad() {
        super.viewDidLoad()
        val fromPageData = pageData.params.optString("initialCodes")
        picked = if (fromPageData.isNotBlank())
            fromPageData.split(",").map { it.trim() }.filter { it.isNotBlank() }
        else listOf("600519", "000858")
        toggle = !toggle
    }

    /** 完成：持久化对比股 + 写回单例 + 返回对比页（对比页恢复时读持久化自动生效，无需再手动应用） */
    internal fun finish() {
        val codes = picked.filter { it.isNotBlank() }
        ComparePicker.pendingCodes = codes
        persistCompare(codes)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    /** 持久化对比股列表到 SharedPreferences（对比页与重进都从它恢复）。 */
    private fun persistCompare(codes: List<String>) {
        try {
            acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
                .setItem("kb_compare_codes", codes.joinToString(","))
        } catch (e: Throwable) { /* 持久化失败不阻断完成 */ }
    }

    /** 切榜单 Tab；"全部"(index4) 无需真实榜拉取(展示全池)。 */
    internal fun selectRank(i: Int) {
        if (i == rankTab && i != 4) return
        rankTab = i
        if (i != 4 && !StockData.hasRank(i)) StockData.loadRank(i) { toggle = !toggle }
        toggle = !toggle
    }

    /** 切换某股对比状态：未加→加，已加→移除（列表行/按钮点击走这个） */
    internal fun togglePick(stock: Stock) {
        val code = stock.code
        picked = if (picked.contains(code)) picked - code else picked + code
        toggle = !toggle
    }

    /** 移除某 code */
    internal fun removeByCode(code: String) {
        picked = picked - code
        toggle = !toggle
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }

            // ===== 顶部栏 =====
            View {
                attr {
                    padding(12f); paddingTop(pagerData.statusBarHeight); height(44f + pagerData.statusBarHeight)
                    flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemiBold() } }
                }
                Text { attr { text("设置对比股"); fontSize(17f); color(Color(0xFF222222)); fontWeightSemiBold(); marginLeft(8f) } }
                View { attr { flex(1f) } }
                View {
                    attr {
                        paddingLeft(14f); paddingRight(14f); height(32f); borderRadius(16f)
                        justifyContentCenter(); alignItemsCenter()
                        backgroundColor(Color(UserSettings.themeColor))
                    }
                    event { click { ctx.finish() } }
                    Text { attr { text("完成"); fontSize(13f); color(Color.WHITE); fontWeightSemiBold() } }
                }
            }

            // ===== 内容区 =====
            // 搜索框/榜单Tab 静态（不随 toggle 重建，保输入不丢焦点）；已选区随 toggle 重建；
            // 榜单列表为 flex(1f) 竖向滚动区（可下滑），点某行/按钮切换加入对比。
            renderPickerSearch(ctx)
            renderPickerRankTabs(ctx)
            vif({ ctx.toggle }) { val c = this; c.renderPickerSelectedChips(ctx) }
            vif({ !ctx.toggle }) { val c = this; c.renderPickerSelectedChips(ctx) }
            // 榜单滚动区
            View {
                attr { flex(1f); flexDirectionColumn(); padding(top=6f, bottom=8f) }
                Scroller {
                    attr { flex(1f); flexDirectionColumn() }
                    vif({ ctx.toggle }) { val c = this; c.renderPickerRankList(ctx) }
                    vif({ !ctx.toggle }) { val c = this; c.renderPickerRankList(ctx) }
                }
            }
        }
    }
}

/** 搜索框（静态，不随 toggle 重建；query 经 ctx.query observable 驱动过滤，输入不丢焦点）。 */
private fun ViewContainer<*, *>.renderPickerSearch(ctx: StockPickerPage) {
    View {
        attr {
            backgroundColor(Color.WHITE); padding(top=10f, bottom=2f)
        }
        View {
            attr {
                height(40f); borderRadius(20f); paddingLeft(14f); paddingRight(14f); margin(left=12f, right=12f)
                backgroundColor(Color(0xFFF2F3F5L)); flexDirectionRow(); alignItemsCenter()
            }
            Text { attr { text("🔍"); fontSize(UserSettings.fs(14f)); color(Color(0xFF999999)); marginRight(6f) } }
            Input {
                ref { ctx.inputRef = it }
                attr {
                    flex(1f); height(34f); fontSize(UserSettings.fs(14f)); color(Color(0xFF222222))
                    placeholder("搜索个股名/代码"); placeholderColor(Color(0xFF999999))
                }
                event { textDidChange { ctx.query = it.text; ctx.toggle = !ctx.toggle } }
            }
        }
    }
}

/** 榜单 Tab 栏（静态；选中态在各自 attr 闭包内现读 ctx.rankTab/ctx.query，即时随切换移动；搜索中高亮"全部"）。 */
private fun ViewContainer<*, *>.renderPickerRankTabs(ctx: StockPickerPage) {
    View {
        attr {
            flexDirectionRow(); height(44f); alignItemsCenter()
            backgroundColor(Color.WHITE); paddingLeft(6f); paddingRight(6f)
        }
        ctx.RANK_TABS.forEachIndexed { i, label ->
            View {
                attr { flex(1f); height(44f); flexDirectionColumn(); alignItemsCenter(); justifyContentCenter() }
                event { click { ctx.selectRank(i) } }
                Text {
                    attr {
                        text(label); fontSize(UserSettings.fs(13f)); fontWeightSemiBold()
                        // ⚠️ 现读 observable(rankTab/query) 于 attr 闭包内，切 Tab/搜索时即时变色
                        val active = if (ctx.query.isNotBlank()) (i == 4) else (ctx.rankTab == i)
                        color(if (active) Color(UserSettings.themeColor) else Color(0xFF666666))
                    }
                }
                View {
                    attr {
                        width(24f); height(2.5f); marginTop(3f); borderRadius(1.25f)
                        // ⚠️ 横线背景也现读，切 Tab 时横线移动到位
                        val active = if (ctx.query.isNotBlank()) (i == 4) else (ctx.rankTab == i)
                        backgroundColor(if (active) Color(UserSettings.themeColor) else Color(0))
                    }
                }
            }
        }
    }
}

/** 已加对比股 chips 区（默认折叠，点标题行展开；按用户灵感）。 */
private fun ViewContainer<*, *>.renderPickerSelectedChips(ctx: StockPickerPage) {
    val chipsExpanded = ctx.chipsExpanded
    val n = ctx.picked.size
    View {
        attr { backgroundColor(Color.WHITE); padding(top=6f, bottom=8f) }
        // 标题行：可点展开/收起
        View {
            attr {
                flexDirectionRow(); alignItemsCenter(); padding(left=14f, right=14f, top=4f, bottom=4f)
                height(36f)
            }
            event { click { ctx.chipsExpanded = !ctx.chipsExpanded; ctx.toggle = !ctx.toggle } }
            Text {
                attr {
                    text(if (n == 0) "已加对比（0）· 点下方榜单股票或「＋」加入"
                         else "已加对比（$n）· ${if (chipsExpanded) "点击收起" else "点击展开已选"}")
                    fontSize(UserSettings.fs(13f)); color(Color(0xFF666666)); flex(1f)
                }
            }
            if (n > 0) {
                // 醒目"已加 N"按钮
                View {
                    attr {
                        paddingLeft(10f); paddingRight(10f); height(24f); borderRadius(12f)
                        justifyContentCenter(); alignItemsCenter()
                        backgroundColor(Color(UserSettings.themeColor))
                    }
                    Text { attr { text("$n 只"); fontSize(11f); color(Color.WHITE); fontWeightSemiBold() } }
                }
                View { attr { width(8f) } }
                Text { attr { text(if (chipsExpanded) "▴" else "▾"); fontSize(16f); color(Color(0xFF666666)) } }
            }
        }
        // 展开的 chips 行
        if (chipsExpanded && n > 0) {
            Scroller {
                attr { flexDirectionRow(); padding(left=12f, right=12f); minHeight(40f); height(40f) }
                ctx.picked.forEach { code ->
                    val st = StockData.findByCode(code)
                    if (st.code != code) return@forEach
                    View {
                        attr {
                            height(28f); borderRadius(14f); marginRight(8f); paddingLeft(12f); paddingRight(4f)
                            backgroundColor(Color(0xFFE8F1FB)); flexDirectionRow(); alignItemsCenter()
                        }
                        Text { attr { text(st.name); fontSize(UserSettings.fs(13f)); color(Color(0xFF222222)); marginRight(6f) } }
                        View {
                            attr { width(22f); height(22f); justifyContentCenter(); alignItemsCenter() }
                            event { click { ctx.removeByCode(code) } }
                            Text { attr { text("×"); fontSize(16f); color(Color(0xFF666666)) } }
                        }
                    }
                }
            }
        }
    }
}

/** 榜单行列表：
 *  - query 非空 → 跨整个股票池搜（不受当前榜限制），且此时 Tab 高亮切到"全部"(index4)。
 *  - query 空 → 按当前 rankTab：index4=全部(全池,不排序)；0~3=涨幅/跌幅/换手/振幅(真实榜 rankOf 或本地排序)。
 */
private fun ViewContainer<*, *>.renderPickerRankList(ctx: StockPickerPage) {
    // 全部 Tab/搜索也含指数：用户要求把指数当个股可选可对比，统一在全部页里可搜可加
    val pool = StockData.getQuotes()
    val q = ctx.query.trim()
    val showAllTab = q.isNotEmpty() || ctx.rankTab == 4  // 搜索时视为切到"全部"
    val shown: List<Stock> = if (showAllTab) {
        // 全部：有搜索则跨全池过滤；否则全池（不排序，保持池顺序接近自然浏览）
        if (q.isEmpty()) pool
        else pool.filter { it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true) }
    } else {
        // 具体榜单 0~3
        StockData.rankOf(ctx.rankTab) ?: when (ctx.rankTab) {
            0 -> pool.sortedByDescending { it.changePercent }
            1 -> pool.sortedBy { it.changePercent }
            2 -> pool.sortedByDescending { it.volume }
            3 -> pool.sortedByDescending { if (it.price > 0f) (it.high - it.low) / it.price else 0f }
            else -> pool
        }
    }
    if (shown.isEmpty()) {
        Text {
            attr {
                text(if (q.isEmpty()) "暂无个股数据" else "没有匹配「$q」的个股（全池已搜）")
                fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)); marginTop(16f); marginLeft(16f)
            }
        }
    } else {
        shown.forEach { s -> renderPickerRow(ctx, s) }
    }
}

/** 单行：market 风格（名/代码 + 价 + 涨跌%徽章），行尾状态按钮（＋加入 / ✓ 已加）。点整行或按钮都切换。 */
private fun ViewContainer<*, *>.renderPickerRow(ctx: StockPickerPage, st: Stock) {
    val added = ctx.picked.contains(st.code)
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); height(60f); marginBottom(6f); marginLeft(12f); marginRight(12f)
            backgroundColor(Color.WHITE); borderRadius(8f); padding(left=12f, right=12f)
        }
        event { click { ctx.togglePick(st) } }
        // 名 + code
        View {
            attr { flex(1f); flexDirectionColumn() }
            Text {
                attr { text(st.name); fontSize(UserSettings.fs(16f)); color(if (added) StockColor.text(st.changePercent) else StockColor.text(st.changePercent)) }
            }
            Text { attr { text(st.code); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginTop(3f) } }
        }
        // 价 + 涨跌%
        View {
            attr { flex(1f); flexDirectionRow(); justifyContentFlexEnd(); alignItemsCenter(); marginRight(8f) }
            Text { attr { text(formatPrice(st.price)); fontSize(UserSettings.fs(16f)); color(StockColor.text(st.changePercent)) } }
            Text { attr { text("  " + formatPercent(st.changePercent)); fontSize(UserSettings.fs(13f)); color(StockColor.text(st.changePercent)); marginLeft(6f) } }
        }
        // 状态按钮
        View {
            attr {
                width(64f); height(32f); borderRadius(16f)
                justifyContentCenter(); alignItemsCenter()
                backgroundColor(if (added) Color(0xFFEEEEEE) else Color(UserSettings.themeColor))
            }
            event { click { ctx.togglePick(st) } }
            Text {
                attr {
                    text(if (added) "✓ 移除" else "＋ 加入")
                    fontSize(12f); fontWeightSemiBold()
                    color(if (added) Color(0xFF888888) else Color.WHITE)
                }
            }
        }
    }
}
