package com.zeriehan.kuiklystock.app.main

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.views.compose.Button
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.components.KRTable.KRStockList
import com.zeriehan.kuiklystock.core.MockStockSource
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.UserStockStore
import com.zeriehan.kuiklystock.core.llm.ChatStore
import com.zeriehan.kuiklystock.core.llm.ChatSync

/**
 * 四 Tab 主框架（App 启动入口）。
 * Tab1 AI聊天 / Tab2 行情 / Tab3 自选 / Tab4 我的。
 *
 * 本次新增：
 * - 行情/自选行「长按」弹出操作菜单（加自选 / 问 AI / 查看详细 / 不感兴趣 / 复制代码）；
 * - 「加自选」写入内部标签，带标签的股票进入「自选」Tab；
 * - 「不感兴趣」按时间戳隐藏，到「自动恢复天数」后自动重现（也可在「我的-设置」手动恢复）；
 * - 「问 AI」与详情页「深入聊聊」都打开 ChatPage(同一 stockCode)，共用 [ChatStore] 同一段对话；
 * - 「我的」提供设置：自动恢复天数、已隐藏管理、自选管理。
 */
@Page("MainTab", supportInLocal = true)
internal class MainTabPager : BasePager() {

    private val tabTitles = listOf("AI 聊天", "行情", "自选", "我的")
    private val DAY_MS = 86_400_000L

    private var selectedTab: Int by observable(1)

    // ===== 持久化镜像（响应式）=====
    private var watchlistCodes: Set<String> by observable(emptySet())
    private var hiddenMap: Map<String, Long> by observable(emptyMap())
    private var hideDays: Int by observable(7)
    /** 强制重渲染计数：标签/隐藏/设置变更后 +1，body 据此刷新列表（helper 内读 observable 不可靠） */
    private var dataVersion: Int by observable(0)
    /** 「自动恢复周期」展开态 */
    private var hideDaysExpanded: Boolean by observable(false)
    /** 「自动恢复周期」自定义输入缓冲 */
    private var hideDaysInput: String by observable("7")

    // ===== 长按操作菜单状态 =====
    private var sheetStock: Stock? by observable(null)
    private var sheetX: Float by observable(0f)
    private var sheetY: Float by observable(0f)

    private val prefs: SharedPreferencesModule
        get() = acquireModule(SharedPreferencesModule.MODULE_NAME)

    override fun created() {
        super.created()
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        loadState()
        // 注册跨页监听：ChatPage 写入会话时即时刷新「最近对话」（无需手动切 Tab）
        ChatSync.addListener { dataVersion++ }
    }

    /** 从子页（如 ChatPage）返回时强制刷新：已隐藏列表 / 最近对话即时同步 */
    override fun pageDidAppear() {
        super.pageDidAppear()
        loadState()
        dataVersion++
    }

    // ===== 持久化读写 =====
    private fun loadState() {
        watchlistCodes = UserStockStore.loadWatchlist(prefs)
        hiddenMap = UserStockStore.loadHidden(prefs)
        hideDays = UserStockStore.loadHideDays(prefs)
    }

    private fun nowMs(): Long = Utils.currentBridgeModule().currentTimeStamp()

    /** 某股票当前是否处于「不感兴趣」冷却期 */
    private fun isHidden(code: String): Boolean {
        val t = hiddenMap[code] ?: return false
        return nowMs() - t < hideDays * DAY_MS
    }

    /** 行情列表：剔除仍处于「不感兴趣」冷却期内的股票 */
    private fun visibleQuotes(): List<Stock> =
        MockStockSource.getQuotes().filter { !isHidden(it.code) }

    /** 自选列表：仅含被打「自选」标签、且未被隐藏的股票 */
    private fun watchlistStocks(): List<Stock> =
        MockStockSource.getQuotes().filter { it.code in watchlistCodes && !isHidden(it.code) }

    // ===== 标签/隐藏变更 =====
    private fun toggleWatch(code: String) {
        watchlistCodes = if (watchlistCodes.contains(code)) watchlistCodes - code else watchlistCodes + code
        UserStockStore.saveWatchlist(prefs, watchlistCodes)
        dataVersion++
    }

    private fun hideStock(code: String) {
        hiddenMap = hiddenMap + (code to nowMs())
        UserStockStore.saveHidden(prefs, hiddenMap)
        dataVersion++
    }

    private fun unhide(code: String) {
        hiddenMap = hiddenMap - code
        UserStockStore.saveHidden(prefs, hiddenMap)
        dataVersion++
    }

    /** 设置自动恢复天数（最少 1 天），并落盘 */
    private fun applyHideDays(days: Int) {
        hideDays = days.coerceAtLeast(1)
        UserStockStore.saveHideDays(prefs, hideDays)
        dataVersion++
    }

    /** 展开/收起「自动恢复周期」面板；展开时把当前值同步到输入框 */
    private fun toggleHideDaysExpanded() {
        hideDaysExpanded = !hideDaysExpanded
        if (hideDaysExpanded) hideDaysInput = hideDays.toString()
    }

    // ===== 菜单 / 跳转 =====
    private fun openSheet(stock: Stock, x: Float, y: Float) {
        sheetStock = stock; sheetX = x; sheetY = y
    }

    private fun closeSheet() { sheetStock = null }

    private fun askAI(stock: Stock) {
        closeSheet()
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
    }

    private fun openDetail(stock: Stock) {
        closeSheet()
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
    }

    private fun copyCode(stock: Stock) {
        closeSheet()
        bridgeModule.copyToPasteboard(stock.code)
        bridgeModule.toast("代码 ${stock.code} 已复制")
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            // 建立依赖：隐藏/自选/恢复天数等真实 observable 变化即重渲染列表。
            // 直接读取（而非仅读 dataVersion 计数），确保子组件（KRStockList）拿到最新过滤后的列表。
            ctx.hiddenMap; ctx.watchlistCodes; ctx.hideDays; ctx.dataVersion
            // 内容卡宽度（Scroller 默认不拉伸子元素，需显式宽度以铺满、避免右侧留白）
            val contentW = ctx.pagerData.pageViewWidth - 24f
            attr { flexDirectionColumn(); backgroundColor(Color.WHITE) }

            // ===== TopBar =====
            View {
                attr {
                    height(48f); flexDirectionRow(); alignItemsCenter(); padding(all = 14f)
                    backgroundColor(Color(0xFF23D3FD))
                }
                Text { attr { text(ctx.tabTitles[ctx.selectedTab]); fontSize(18f); color(Color.WHITE) } }
            }

            // ===== ContentArea =====
            View {
                attr { flex(1f); flexDirectionColumn() }

                // ---- Tab0 AI 聊天（最近对话入口）----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 0) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    Scroller {
                        attr { flex(1f); flexDirectionColumn(); padding(12f) }
                        Text { attr { text("最近对话"); fontSize(16f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        val convs = ChatStore.conversationCodes().mapNotNull { MockStockSource.findByCode(it) }
                        if (convs.isEmpty()) {
                            Text {
                                attr {
                                    text("还没有聊过。去行情页长按某只股票，选「问 AI」，就能和它开始一段对话～")
                                    fontSize(13f); color(Color(0xFF999999)); marginTop(16f)
                                }
                            }
                        } else {
                            convs.forEach { s ->
                                val last = ChatStore.last(s.code)
                                View {
                                    attr {
                                        flexDirectionRow(); alignItemsCenter(); marginTop(10f)
                                        padding(12f); backgroundColor(Color.WHITE); borderRadius(10f)
                                        width(contentW)
                                    }
                                    event {
                                        click {
                                            val d = JSONObject(); d.put("stockCode", s.code)
                                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
                                        }
                                    }
                                    View {
                                        attr {
                                            width(36f); height(36f); borderRadius(18f)
                                            backgroundColor(Color(0xFFE6F1FB)); marginRight(10f)
                                            alignItemsCenter(); justifyContentCenter()
                                        }
                                        Text { attr { text(s.name.take(1)); fontSize(16f); color(Color(0xFF23D3FD)); fontWeightSemisolid() } }
                                    }
                                    View { attr { flex(1f); flexDirectionColumn() }
                                        Text { attr { text(s.name); fontSize(15f); color(Color(0xFF222222)) } }
                                        Text {
                                            attr {
                                                text((last?.text ?: "").let { if (it.length > 22) it.take(22) + "…" else it })
                                                fontSize(12f); color(Color(0xFF999999)); marginTop(3f)
                                            }
                                        }
                                    }
                                    Text { attr { text(">"); fontSize(16f); color(Color(0xFFCCCCCC)) } }
                                }
                            }
                        }
                    }
                }

                // ---- Tab1 行情 ----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 1) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    KRStockList {
                        attr { flex(1f) }
                        stocks = ctx.visibleQuotes()
                        onRowClick = { /* 展开/收起由 KRStockList 内部处理 */ }
                        onDetailClick = { ctx.openDetail(it) }
                        onRowLongPress = { stock, x, y -> ctx.openSheet(stock, x, y) }
                    }
                }

                // ---- Tab2 自选 ----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 2) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    vif({ ctx.watchlistCodes.isEmpty() }) {
                        View {
                            attr { flex(1f); alignItemsCenter(); justifyContentCenter(); flexDirectionColumn() }
                            Text { attr { text("暂无自选股"); fontSize(18f); color(Color(0xFF222222)) } }
                            Text { attr { text("长按行情里的股票，选「加自选」即可加入这里"); fontSize(13f); color(Color(0xFF999999)); marginTop(8f) } }
                        }
                    }
                    vif({ ctx.watchlistCodes.isNotEmpty() }) {
                        KRStockList {
                            attr { flex(1f) }
                            stocks = ctx.watchlistStocks()
                            onRowClick = { /* 展开/收起内部处理 */ }
                            onDetailClick = { ctx.openDetail(it) }
                            onRowLongPress = { stock, x, y -> ctx.openSheet(stock, x, y) }
                        }
                    }
                }

                // ---- Tab3 我的（设置）----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 3) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    Scroller {
                        attr { flex(1f); flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)); padding(12f) }

                        // 不感兴趣管理
                        Text { attr { text("不感兴趣"); fontSize(13f); color(Color(0xFF999999)); marginBottom(8f) } }
                        // —— 自动恢复周期（可展开自定义天数）——
                        View {
                            attr { flexDirectionColumn(); padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW) }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                event { click { ctx.toggleHideDaysExpanded() } }
                                Text { attr { text("自动恢复周期"); fontSize(15f); color(Color(0xFF222222)) } }
                                View { attr { flex(1f) } }
                                Text { attr { text("${ctx.hideDays} 天"); fontSize(14f); color(Color(0xFF23D3FD)) } }
                                Text {
                                    attr {
                                        text(if (ctx.hideDaysExpanded) "  ▲" else "  >")
                                        fontSize(15f); color(Color(0xFFCCCCCC)); marginLeft(6f)
                                    }
                                }
                            }
                            vif({ ctx.hideDaysExpanded }) {
                                View {
                                    attr {
                                        flexDirectionColumn(); marginTop(12f); padding(10f)
                                        backgroundColor(Color(0xFFF7F8FA)); borderRadius(8f)
                                    }
                                    // 自定义输入（最少 1 天，天为单位）
                                    View {
                                        attr { flexDirectionRow(); alignItemsCenter(); padding(top = 2f, bottom = 6f) }
                                        Text { attr { text("自定义（最少 1 天）："); fontSize(13f); color(Color(0xFF666666)) } }
                                        View { attr { flex(1f) } }
                                        Input {
                                            attr {
                                                width(72f); height(34f); fontSize(15f); color(Color(0xFF222222))
                                                backgroundColor(Color.WHITE); borderRadius(6f)
                                                placeholder(""); placeholderColor(Color(0xFFBBBBBB))
                                            }
                                            event { textDidChange { ctx.hideDaysInput = it.text } }
                                        }
                                        Text { attr { text(" 天"); fontSize(14f); color(Color(0xFF222222)); marginLeft(6f) } }
                                    }
                                    // 快捷选项
                                    View {
                                        attr { flexDirectionRow(); alignItemsCenter(); padding(bottom = 8f) }
                                        listOf(3, 7, 14, 30).forEach { d ->
                                            View {
                                                attr {
                                                    height(30f); padding(left = 14f, right = 14f); marginRight(8f)
                                                    borderRadius(15f)
                                                    backgroundColor(if (ctx.hideDays == d) Color(0xFF23D3FD) else Color(0xFFE6F1FB))
                                                    alignItemsCenter(); justifyContentCenter()
                                                }
                                                event {
                                                    click {
                                                        ctx.applyHideDays(d)
                                                        ctx.hideDaysExpanded = false
                                                        ctx.bridgeModule.toast("已设为 ${d} 天")
                                                    }
                                                }
                                                Text {
                                                    attr {
                                                        text("${d}天")
                                                        fontSize(13f)
                                                        color(if (ctx.hideDays == d) Color.WHITE else Color(0xFF23D3FD))
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    // 确定（应用自定义输入）
                                    View {
                                        attr { flexDirectionRow(); justifyContent(FlexJustifyContent.FLEX_END) }
                                        View {
                                            attr {
                                                height(32f); padding(left = 18f, right = 18f); borderRadius(16f)
                                                backgroundColor(Color(0xFF23D3FD)); alignItemsCenter(); justifyContentCenter()
                                            }
                                            event {
                                                click {
                                                    val parsed = ctx.hideDaysInput.toIntOrNull()?.coerceAtLeast(1) ?: ctx.hideDays
                                                    ctx.applyHideDays(parsed)
                                                    ctx.hideDaysExpanded = false
                                                    ctx.bridgeModule.toast("已设为 ${parsed} 天")
                                                }
                                            }
                                            Text { attr { text("确定"); fontSize(14f); color(Color.WHITE) } }
                                        }
                                    }
                                }
                            }
                        }
                        // —— 已隐藏列表（隐藏的股票即在此展示，可手动恢复）——
                        View {
                            attr { flexDirectionColumn(); marginTop(10f); padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW) }
                            Text { attr { text("已隐藏的股票（到点自动恢复，也可手动恢复）"); fontSize(13f); color(Color(0xFF999999)); marginBottom(6f) } }
                            if (ctx.hiddenMap.isEmpty()) {
                                Text { attr { text("暂无"); fontSize(14f); color(Color(0xFF999999)) } }
                            } else {
                                ctx.hiddenMap.toList().forEach { (code, _) ->
                                    val s = MockStockSource.findByCode(code)
                                    View {
                                        attr { flexDirectionRow(); alignItemsCenter(); marginTop(8f) }
                                        Text { attr { text(s.name); fontSize(15f); color(Color(0xFF222222)); flex(1f) } }
                                        Text { attr { text(code); fontSize(12f); color(Color(0xFF999999)); marginRight(10f) } }
                                        Button {
                                            attr {
                                                size(56f, 28f); borderRadius(14f); backgroundColor(Color(0xFFF2F3F5))
                                                titleAttr { text("恢复"); fontSize(13f); color(Color(0xFF23D3FD)) }
                                            }
                                            event { click { ctx.unhide(code); ctx.bridgeModule.toast("已恢复 ${s.name}") } }
                                        }
                                    }
                                }
                            }
                        }
                        View { attr { height(20f) } }
                    }
                }
            }

            // ===== BottomTabBar =====
            View {
                attr { height(56f); flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE) }
                val tabs = listOf("AI", "行情", "自选", "我的")
                tabs.forEachIndexed { i, name ->
                    View {
                        attr {
                            flex(1f); flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
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
                        View {
                            attr {
                                width(22f); height(3f); marginTop(4f); borderRadius(1.5f); backgroundColor(Color(0xFF23D3FD))
                                opacity(if (ctx.selectedTab == i) 1f else 0f)
                            }
                        }
                    }
                }
            }

            // ===== 长按操作菜单（覆盖层）=====
            vif({ ctx.sheetStock != null }) {
                // 半透明遮罩：点击关闭
                View {
                    attr {
                        absolutePositionAllZero()
                        backgroundColor(Color(0x55000000))
                    }
                    event { click { ctx.closeSheet() } }
                }
                // 菜单卡片：锚定到长按点（夹在屏幕内）
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val vh = ctx.pagerData.pageViewHeight
                        val menuW = 176f
                        val menuH = 250f
                        val left = (ctx.sheetX - menuW / 2f).coerceIn(8f, (vw - menuW - 8f).coerceAtLeast(8f))
                        val top = ctx.sheetY.coerceIn(8f, (vh - menuH - 8f).coerceAtLeast(8f))
                        absolutePosition(top = top, left = left)
                        width(menuW)
                        backgroundColor(Color.WHITE)
                        borderRadius(10f)
                        flexDirectionColumn()
                    }
                    val stock = ctx.sheetStock!!
                    val watched = ctx.watchlistCodes.contains(stock.code)
                    // 加自选（可切换）
                    sheetItem(if (watched) "★ 已自选" else "☆ 加自选") {
                        ctx.toggleWatch(stock.code)
                        ctx.closeSheet()
                        ctx.bridgeModule.toast(if (watched) "已取消自选" else "已加入自选")
                    }
                    sheetDivider()
                    sheetItem("问 AI") { ctx.askAI(stock) }
                    sheetItem("查看详细") { ctx.openDetail(stock) }
                    sheetItem("不感兴趣") {
                        ctx.hideStock(stock.code)
                        ctx.closeSheet()
                        ctx.bridgeModule.toast("已隐藏，${ctx.hideDays} 天后自动恢复（设置可改）")
                    }
                    sheetDivider()
                    sheetItem("复制代码") { ctx.copyCode(stock) }
                }
            }
        }
    }
}

/** 菜单单项（文本 + 点击） */
private fun ViewContainer<*, *>.sheetItem(label: String, onClick: () -> Unit) {
    View {
        attr {
            height(50f); justifyContentCenter(); paddingLeft(16f)
        }
        event { click { onClick() } }
        Text { attr { text(label); fontSize(15f); color(Color(0xFF222222)) } }
    }
}

private fun ViewContainer<*, *>.sheetDivider() {
    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
}

