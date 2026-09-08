package com.zeriehan.kuiklystock.app.main

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.timer.setTimeout
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.components.KRTable.KRStockList
import com.zeriehan.kuiklystock.components.KRStockBadge.KRStockBadge
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.QuickTipsGate
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.Sector
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.UserStockStore
import com.zeriehan.kuiklystock.core.AlertStore
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.llm.AIJobCenter
import com.zeriehan.kuiklystock.core.llm.AIAnalysisStore
import com.zeriehan.kuiklystock.core.llm.ChatStore
import com.zeriehan.kuiklystock.core.llm.ChatSync
import com.zeriehan.kuiklystock.core.llm.DataSync

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
internal class MainTabPager : BasePager(), StockNavigator {

    private val tabTitles = listOf("AI 聊天", "行情", "自选", "我的")
    private val DAY_MS = 86_400_000L

    private var selectedTab: Int by observable(1)

    // ===== 持久化镜像（响应式）=====
    internal var watchlistCodes: Set<String> by observable(emptySet())
    internal var hiddenMap: Map<String, Long> by observable(emptyMap())
    private var hideDays: Int by observable(7)
    // ===== 自选分组镜像（同会话分组模式）=====
    internal var watchGroups: List<UserStockStore.StockGroup> by observable(emptyList())
    internal var watchGroupMap: Map<String, String> by observable(emptyMap()) // code -> groupId(""未分组不存)
    /** 自选当前分组筛选：""=全部 */
    internal var watchGroupFilter: String by observable("")
    /** 长按自选行待移动到分组的 code（配合移动到分组 overlay） */
    internal var watchMoveCode: String? by observable(null)
    /** 长按自选分组 chip 的分组 id（弹分组操作菜单：重命名/删除） */
    internal var watchGroupSheetId: String? by observable(null)
    // ===== 价格预警镜像 =====
    internal var priceAlerts: List<AlertStore.PriceAlert> by observable(emptyList())
    /** 长按行正在设置预警的股票 code */
    internal var alertStock: Stock? by observable(null)
    /** 预警类型选择弹层的临时状态：当前选类型 + 阈值输入文本 */
    internal var alertType: String by observable("below")
    internal var alertThresholdText: String by observable("")
    /** 每预警类型的临时输入草稿（4 行各自独立填，切/保存不丢） */
    internal val alertDrafts = mutableMapOf<String, String>()
    /** 4 行 Input 的 ref 注册（供 openAlertFor 后延迟 setText 回显草稿） */
    internal val alertInputRefs = mutableMapOf<String, ViewRef<InputView>>()
    /** 预警行重建计数：草稿增删时 +1，驱动伪占位/占位逻辑按草稿刷新 */
    internal var alertRowTick: Int by observable(0)
    /** 强制重渲染计数：标签/隐藏/设置变更后 +1（辅助用，真正触发列表重建靠下方 vif 翻转） */
    internal var dataVersion: Int by observable(0)
    /** vif 翻转触发器：最近对话列表据此强制重建（本版本 body 不随 observable 重跑） */
    internal var convToggle: Boolean by observable(false)
    /** vif 翻转触发器：行情/自选列表据此强制重建 */
    internal var listToggle: Boolean by observable(false)
    /** vif 翻转触发器：「我的」页的隐藏股票入口行据其翻转重建（数量/天数的实时同步） */
    internal var mineToggle: Boolean by observable(false)
    /** 主题强调色（observable 镜像 UserSettings.themeColor）：所有主题色 attr 闭包读它即随个性化重绘 */
    internal var themeColor: Long by observable(UserSettings.themeColor)
    /** 深色模式（observable 镜像 UserSettings.darkMode）：页面底色等读它即随个性化重绘 */
    internal var darkOn: Boolean by observable(false)
    /** 字体缩放镜像（observable）：所有字号经 ctx.fs() 读取，字号一变即触发依赖闭包重绘 */
    internal var fontScale: Float by observable(1.0f)
    /** 内容区重建触发器：字号/主题等从设置页返回时翻转，强制整体（含 KRStockList）销毁重建 */
    internal var reseedToggle: Boolean by observable(false)
    /** 上次应用的字号快照，用于检测「外观」页返回时字号是否变化 */
    private var lastFontScale: Float = 1.0f
    /** 上次应用的涨跌配色快照，用于检测「外观」页返回时配色是否变化（变了则 reseed 全重建以翻转所有涨跌红绿） */
    private var lastColorMode: Int = 0
    /** 字号统一入口：base * 当前缩放，至少 10f，避免缩成不可读 */
    internal fun fs(base: Float): Float = (base * fontScale).coerceAtLeast(10f)
    /** 「自动恢复周期」展开态 */
    private var hideDaysExpanded: Boolean by observable(false)
    /** 「自动恢复周期」自定义输入缓冲 */
    private var hideDaysInput: String by observable("7")

    // ===== 行情页「子 Tab」状态（大盘 / 板块 / 个股）=====
    // ⚠️ 必须 internal：文件级扩展函数（renderMarketXxx）要读取/调用，private 不可见
    /** 行情子 Tab：0=大盘 1=板块 2=个股 */
    internal var marketSubTab: Int by observable(0)
    /** vif 翻转触发器：切换大盘/板块/个股时翻转，强制重建内容区 */
    internal var marketSubToggle: Boolean by observable(false)
    /** 个股子榜：0=涨幅榜 1=跌幅榜 2=换手率 3=振幅 */
    internal var stockRankTab: Int by observable(0)
    /** vif 翻转触发器：切换个股子榜时翻转，强制重建榜单 */
    internal var rankToggle: Boolean by observable(false)
    /** 市场子页(板块/个股)正在拉真实数据 → 顶部显示"加载中…"（true 由 selectMarketSub/selectRankTab 置位，fetch onDone 清位） */
    internal var mktLoading: Boolean by observable(false)
    /** 行情数据到达 tick：DataSync 每次翻转，驱动「大盘」子内容(指数/热度/领涨领跌)强制重建读最新真实报价 */
    internal var marketDataTick: Boolean by observable(false)

    // ===== 板块页交互状态（搜索 / 关注置顶）=====
    /** 关注板块 code 集合（持久化，载入于 viewDidLoad） */
    internal var followSectors: Set<String> by observable(emptySet())
    /** 板块搜索关键字（空=不过滤） */
    internal var sectorQuery: String by observable("")
    /** 搜索词/关注变更的 vif 翻转触发器：重画搜索框 + 关注 chips + 列表 */
    internal var sectorToggle: Boolean by observable(false)

    // ===== 个股榜搜索状态 =====
    /** 个股搜索关键字（空=不过滤，按名称/代码匹配） */
    internal var stockQuery: String by observable("")
    /** 个股搜索变更的 vif 翻转触发器：重建榜单列表以实时过滤（搜索框本身不随翻转重建，保输入流畅） */
    internal var stockToggle: Boolean by observable(false)

    // ===== 长按操作菜单状态 =====
    private var sheetStock: Stock? by observable(null)
    private var sheetX: Float by observable(0f)
    private var sheetY: Float by observable(0f)

    // ===== AI 对话管理状态（分组 / 置顶 / 重命名 / 多选 / 移动到分组）=====
    // ⚠️ 必须 internal：文件级扩展函数 renderRecents 要读取/调用，private 不可见
    internal var sheetConv: String? by observable(null)
    internal var sheetConvX: Float by observable(0f)
    internal var sheetConvY: Float by observable(0f)
    internal var groupSheetId: String? by observable(null)
    internal var selectMode: Boolean by observable(false)
    internal var selectedCodes: Set<String> by observable(emptySet())
    internal var selToggle: Boolean by observable(false)
    internal var groupFilter: String by observable("")
    internal var moveTargets: List<String> by observable(emptyList())
    internal var prompt: TextPrompt? by observable(null)
    internal var promptInput: String by observable("")

    private val prefs: SharedPreferencesModule
        get() = acquireModule(SharedPreferencesModule.MODULE_NAME)

    override fun created() {
        super.created()
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        loadState()
        // 每次打开 App 主界面(=重进 MainTab)即重新武装聊天快捷建议门控，使本次启动进对话可再提示一次
        QuickTipsGate.rearm()
        // 注入聊天持久化句柄：冷启动时从 SharedPreferences 恢复历史对话（否则「AI」Tab 记录会丢）
        ChatStore.attach(prefs)
        // 注入 AI 分析缓存持久化句柄：冷启动后详情页直接读磁盘缓存，不再每次等网络
        AIAnalysisStore.attach(prefs)
        // 同步个性化镜像（主题色 / 深色模式 / 字体），供本页相关 attr 闭包读取
        themeColor = UserSettings.themeColor
        darkOn = UserSettings.darkMode
        fontScale = UserSettings.fontScale
        lastFontScale = UserSettings.fontScale
        lastColorMode = UserSettings.colorMode
        // 把「常驻根页面」的桥注册给 AI 任务中心：此后所有 LLM 请求都走这个桥，
        // 子页面（ChatPage / StockDetailPage）关闭后请求与回调依然有效 —— 即 AI 在"后台"继续跑。
        // 显式传 pagerId：流式轮询泵需要绑一个"常驻不销毁"的定时器（即本根页的 pagerId）。
        AIJobCenter.attach(bridgeModule, pagerId)
        // 注册跨页监听：ChatPage 写入会话时即时刷新「最近对话」（vif 翻转强制重建，无需手动切 Tab）
        ChatSync.addListener { convToggle = !convToggle }
        // 注入行情数据源桥并注册监听：腾讯/新浪真实行情回来后翻转 listToggle/convToggle，
        // 行情 / 自选 / 板块 / 最近对话列表随 vif 重建，拿到真实价（失败保留 mock，不影响渲染）。
        StockData.attach(bridgeModule)
        DataSync.addListener {
            listToggle = !listToggle
            convToggle = !convToggle
            // 真实榜单/板块/成分股异步到达后也需重建对应子区：
            // 个股榜在 vif(rankToggle) 内、行情内容区在 vif(marketSubToggle) 内，
            // 只有翻转它们，renderRankList 里现读 StockData.rankOf / renderSectorList 里现读
            // StockData.getSectors 才能拿到新数据（body 与普通 attr 不随非 observable 数据变化重跑）。
            rankToggle = !rankToggle
            marketSubToggle = !marketSubToggle
            // 任何真实数据到达即结束"加载中"（大盘指数/行情刷新也走 DataSync）
            mktLoading = false
            // 行情数据 tick：驱动「大盘」子内容强制重建（即使一直停留在大盘子页也随新报价刷新）
            marketDataTick = !marketDataTick
            // 价格预警：真实行情到达即检查一次命中（fired 去重，不会重复刷）
            checkPriceAlerts()
        }
        // 首屏大盘报价刷新：未就绪先显示"加载中"，DataSync 到达后自动消失
        if (!StockData.isReal()) mktLoading = true
        // ⚠️ 只发一次 refresh(基础报价池)，不做多路并发预加载(loadRank×4+sectors+indices 一起打东财会触发限流→全 mock)。
        // 板块/个股列表数据由用户切到对应子页时按需单次加载(loadSectors/loadRank)，避免冷启动并发打崩接口。
        StockData.refresh()
        // 大盘市场热度/领涨领跌读池内非指数真实股票：冷启动即拉一次涨幅榜(新浪)入池，首进大盘即有真实非指数股。
        StockData.loadRank(0)
        // ⚠️ 自选恢复：冷启动 realPool 为空，自选里那些不在 baseQuotes 种子/涨幅榜 top30 的股票
        //    若不单独拉报价，就不在行情池 → 自选 Tab 过滤掉(加进自选、重进 app 就没了)。此处强制拉取。
        StockData.loadCodesQuotes(watchlistCodes)
    }

    /** 从子页（如 ChatPage / HiddenStocks）返回时强制刷新：已隐藏列表 / 最近对话即时同步 */
    override fun pageDidAppear() {
        super.pageDidAppear()
        loadState()
        // 从个性化设置页返回时同步主题镜像，触发本页主题色 / 底色重绘
        themeColor = UserSettings.themeColor
        darkOn = UserSettings.darkMode
        // 字体：同步可观察镜像（依赖 fs() 的闭包即时重绘），
        // 并在字号确实变化时翻转 reseed，强制「内容区」（含 KRStockList 等跨组件子节点）
        // 整体销毁重建——否则普通 var 的 fontScale 不会触发已有闭包重算，app 内字不会变小。
        fontScale = UserSettings.fontScale
        if (UserSettings.fontScale != lastFontScale) {
            lastFontScale = UserSettings.fontScale
            reseedToggle = !reseedToggle
        }
        // 涨跌配色：变了则 reseed 全重建（K线/列表/进度条等子节点 attr 求值时重读最新配色）
        if (UserSettings.colorMode != lastColorMode) {
            lastColorMode = UserSettings.colorMode
            reseedToggle = !reseedToggle
        }
        convToggle = !convToggle
        mineToggle = !mineToggle
        // ⚠️ 必须翻转 listToggle：行情/自选列表由 vif(listToggle) 包裹，
        // 否则从 HiddenStocks 恢复股票后，行情页不会重新显示该股票（hiddenMap 已更新但列表未重建）。
        listToggle = !listToggle
        // ⚠️ 始终刷新行情池：isReal() 在拉过榜单/成分后即 true，不代表大盘指数/baseQuotes 已刷新；
        // 从详情/设置返回时 refresh 一次，保证行情池(含指数)回到真实价，随 listToggle 翻转重建显示。
        StockData.refresh()
        // 自选恢复：返回时兜底把自选 code 的报价拉回池内（冷启动/首帧 loadState 后 realPool 可能仍缺个别自选股）
        StockData.loadCodesQuotes(watchlistCodes)
    }

    // ===== 持久化读写 =====
    private fun loadState() {
        watchlistCodes = UserStockStore.loadWatchlist(prefs)
        hiddenMap = UserStockStore.loadHidden(prefs)
        hideDays = UserStockStore.loadHideDays(prefs)
        followSectors = UserStockStore.loadFollowSectors(prefs)
        watchGroups = UserStockStore.loadWatchGroups(prefs)
        watchGroupMap = UserStockStore.loadWatchGroupMap(prefs)
        priceAlerts = AlertStore.load(prefs)
        // 载入个性化设置：主题色 / 字体 / 深色模式（渲染前保证最新）
        UserSettings.load(prefs)
    }

    /** 内容区渲染：必须写成「挂在内容区 View 上的扩展函数」而非类成员函数——
     *  否则顶层 View {} 会挂到根容器（与 TopBar/BottomBar 同级），四个 Tab 视图被挤到
     *  BottomBar 下方、内容区本身空着，表现就是「上半部空白、内容从屏幕中间开始」。 */
    private fun ViewContainer<*, *>.renderContentArea(ctx: MainTabPager) {
        // 内容卡宽度（Scroller 默认不拉伸子元素，需显式宽度以铺满、避免右侧留白）
        val contentW = ctx.pagerData.pageViewWidth - 24f
                // ---- Tab0 AI 聊天（最近对话入口）----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 0) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    Scroller {
                        attr { flex(1f); flexDirectionColumn(); padding(12f) }
                        // 标题栏只渲染一次，避免 vif 双分支重建导致「最近对话」重复
                        renderRecentsHeader(ctx, contentW)
                        // 「股票对比」入口：进入多股对比页（上区股票轮播 + 下区对比 AI 聊天）
                        View {
                            attr {
                                flexDirectionRow(); alignItemsCenter()
                                marginBottom(8f); borderRadius(10f); padding(10f)
                                backgroundColor(Color(0xFFFFF4E0)); width(contentW)
                            }
                            event { click { ctx.openCompare() } }
                            View { attr { width(20f); height(20f); borderRadius(4f); backgroundColor(Color(UserSettings.themeColor)); marginRight(8f); justifyContentCenter(); alignItemsCenter() }
                                Text { attr { text("⇄"); fontSize(13f); color(Color.WHITE); fontWeightSemiBold() } }
                            }
                            Text { attr { text("股票对比"); fontSize(ctx.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                            Text { attr { text("多股并排 · AI 对比解读"); fontSize(ctx.fs(12f)); color(Color(0xFF999999)); marginLeft(8f) } }
                            View { attr { flex(1f) } }
                            Text { attr { text("›"); fontSize(20f); color(Color(0xFFBBBBBB)) } }
                        }
                        vif({ ctx.convToggle }) { val c = this; c.renderRecents(ctx, contentW) }
                        vif({ !ctx.convToggle }) { val c = this; c.renderRecents(ctx, contentW) }
                    }
                }

                // ---- Tab1 行情 ----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 1) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    vif({ ctx.listToggle }) { val c = this; c.renderMarket(ctx) }
                    vif({ !ctx.listToggle }) { val c = this; c.renderMarket(ctx) }
                }

                // ---- Tab2 自选 ----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 2) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    vif({ ctx.listToggle }) { val c = this; c.renderWatchlist(ctx) }
                    vif({ !ctx.listToggle }) { val c = this; c.renderWatchlist(ctx) }
                }

                // ---- Tab3 我的（设置）----
                View {
                    attr {
                        flexDirectionColumn()
                        if (ctx.selectedTab == 3) { flex(1f); opacity(1f) } else { flex(0f); height(0f); opacity(0f) }
                    }
                    Scroller {
                        attr { flex(1f); flexDirectionColumn(); backgroundColor(if (ctx.darkOn) Color(0xFF1A1B1E) else Color(0xFFF2F3F5)); padding(12f) }

                        // 不感兴趣管理
                        Text { attr { text("不感兴趣"); fontSize(ctx.fs(13f)); color(if (ctx.darkOn) Color(0xFF9AA0A6) else Color(0xFF999999)); marginBottom(8f) } }
                        // —— 自动恢复周期（可展开自定义天数）——
                        View {
                            attr { flexDirectionColumn(); padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW) }
                            View {
                                attr { flexDirectionRow(); alignItemsCenter() }
                                event { click { ctx.toggleHideDaysExpanded() } }
                                Text { attr { text("自动恢复周期"); fontSize(ctx.fs(15f)); color(Color(0xFF222222)) } }
                                View { attr { flex(1f) } }
                                Text { attr { text("${ctx.hideDays} 天"); fontSize(ctx.fs(14f)); color(Color(ctx.themeColor)) } }
                                Text {
                                    attr {
                                        text(if (ctx.hideDaysExpanded) "  ▲" else "  >")
                                        fontSize(ctx.fs(15f)); color(Color(0xFFCCCCCC)); marginLeft(6f)
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
                                        Text { attr { text("自定义（最少 1 天）："); fontSize(ctx.fs(13f)); color(Color(0xFF666666)) } }
                                        View { attr { flex(1f) } }
                                        Input {
                                            attr {
                                                width(72f); height(34f); fontSize(ctx.fs(15f)); color(Color(0xFF222222))
                                                backgroundColor(Color.WHITE); borderRadius(6f)
                                                placeholder(""); placeholderColor(Color(0xFFBBBBBB))
                                            }
                                            event { textDidChange { ctx.hideDaysInput = it.text } }
                                        }
                                        Text { attr { text(" 天"); fontSize(ctx.fs(14f)); color(Color(0xFF222222)); marginLeft(6f) } }
                                    }
                                    // 快捷选项
                                    View {
                                        attr { flexDirectionRow(); alignItemsCenter(); padding(bottom = 8f) }
                                        listOf(3, 7, 14, 30).forEach { d ->
                                            View {
                                                attr {
                                                    height(30f); padding(left = 14f, right = 14f); marginRight(8f)
                                                    borderRadius(15f)
                                                    backgroundColor(if (ctx.hideDays == d) Color(ctx.themeColor) else Color(0xFFE6F1FB))
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
                                                        fontSize(ctx.fs(13f))
                                                        color(if (ctx.hideDays == d) Color.WHITE else Color(ctx.themeColor))
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
                                                backgroundColor(Color(ctx.themeColor)); alignItemsCenter(); justifyContentCenter()
                                            }
                                            event {
                                                click {
                                                    val parsed = ctx.hideDaysInput.toIntOrNull()?.coerceAtLeast(1) ?: ctx.hideDays
                                                    ctx.applyHideDays(parsed)
                                                    ctx.hideDaysExpanded = false
                                                    ctx.bridgeModule.toast("已设为 ${parsed} 天")
                                                }
                                            }
                                            Text { attr { text("确定"); fontSize(ctx.fs(14f)); color(Color.WHITE) } }
                                        }
                                    }
                                }
                            }
                        }
                        // —— 隐藏股票入口：点击跳转到独立页面集中管理，列表本身不再铺在「我的」页 ——
                        vif({ ctx.mineToggle }) { val c = this; c.renderHiddenEntry(ctx, contentW) }
                        vif({ !ctx.mineToggle }) { val c = this; c.renderHiddenEntry(ctx, contentW) }

                        // —— 个性化设置 ——
                        Text {
                            attr {
                                text("个性化设置")
                                fontSize(ctx.fs(13f))
                                color(if (ctx.darkOn) Color(0xFF9AA0A6) else Color(0xFF999999))
                                marginBottom(8f); marginTop(20f)
                            }
                        }
                        renderSettingRow(ctx, contentW, "迷你卡片", "分时走势 / AI 分析 / 简况，可自由开关") {
                            val d = JSONObject()
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("ExpandSettings", d)
                        }
                        renderSettingRow(ctx, contentW, "外观与个性化", "主题色、字体大小、深色模式") {
                            val d = JSONObject()
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Appearance", d)
                        }
                        renderSettingRow(ctx, contentW, "关于与致谢", "致谢一起开发它的 WorkBuddy、混元、GLM 及各数据源") {
                            val d = JSONObject()
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Credits", d)
                        }
                        View { attr { height(20f) } }
                    }
                }
    }
    private fun nowMs(): Long = Utils.currentBridgeModule().currentTimeStamp()

    /** 某股票是否被「不感兴趣」（持久态：标记后不再按时间自动消失，改为灰幕覆盖，可手动恢复） */
    override fun isHidden(code: String): Boolean = hiddenMap.containsKey(code)

    /** 行情列表：返回全部股票（被「不感兴趣」的股票不再消失，改为灰幕覆盖，见 [renderMarketRow]） */
    internal fun visibleQuotes(): List<Stock> = StockData.getQuotes()

    /** 自选列表：仅含被打「自选」标签、未被隐藏、且通过当前分组筛选的股票 */
    internal fun watchlistStocks(): List<Stock> =
        StockData.getQuotes().filter {
            it.code in watchlistCodes && !isHidden(it.code) &&
                (watchGroupFilter.isEmpty() || watchGroupMap[it.code] == watchGroupFilter)
        }

    /** 行情页「大盘指数大框」：仅取 isIndex 的指数（剔除冷却期内的隐藏项） */
    internal fun marketIndices(): List<Stock> = visibleQuotes().filter { it.isIndex }

    // ===== 标签/隐藏变更 =====
    private fun toggleWatch(code: String) {
        val adding = !watchlistCodes.contains(code)
        watchlistCodes = if (adding) watchlistCodes + code else watchlistCodes - code
        UserStockStore.saveWatchlist(prefs, watchlistCodes)
        // 取消自选时清理其分组归属，避免残留映射占用分组
        if (!adding && watchGroupMap[code] != null) {
            watchGroupMap = watchGroupMap - code
            UserStockStore.saveWatchGroupMap(prefs, watchGroupMap)
        }
        bumpList()
        // 新加的自选若不在行情池(baseQuotes 种子之外、且尚未被榜单/成分并入)：立即拉一次报价并入，
        // 否则刚加即被 watchlistStocks 的池内过滤挡掉(自选 Tab 空白)。
        if (adding) StockData.loadCodesQuotes(watchlistCodes)
    }

    /** 标签/隐藏/恢复天数变更后：计数 + 翻转让行情/自选列表（vif 内）整体重建 */
    private fun bumpList() {
        dataVersion++
        listToggle = !listToggle
        mineToggle = !mineToggle
    }

    private fun hideStock(code: String) {
        hiddenMap = hiddenMap + (code to nowMs())
        UserStockStore.saveHidden(prefs, hiddenMap)
        bumpList()
    }

    /** 恢复（取消「不感兴趣」）：移除标记、落盘、翻转列表重建 */
    private fun restoreStock(code: String) {
        hiddenMap = hiddenMap - code
        UserStockStore.saveHidden(prefs, hiddenMap)
        bumpList()
    }

    /** 设置自动恢复天数（最少 1 天），并落盘 */
    private fun applyHideDays(days: Int) {
        hideDays = days.coerceAtLeast(1)
        UserStockStore.saveHideDays(prefs, hideDays)
        bumpList()
    }

    /** 关注 / 取消关注某个板块（code），落盘并翻转板块页重建 */
    internal fun toggleFollowSector(code: String) {
        followSectors = if (followSectors.contains(code)) followSectors - code else followSectors + code
        UserStockStore.saveFollowSectors(prefs, followSectors)
        sectorToggle = !sectorToggle
        DataSync.bump()
    }

    /** 板块搜索关键字变化（输入清空/键入），翻转板块页重建以实时过滤 */
    internal fun onSectorQueryChange(q: String) {
        if (sectorQuery == q) return
        sectorQuery = q
        sectorToggle = !sectorToggle
    }

    /** 个股搜索关键字变化（输入清空/键入），翻转榜单列表以实时过滤（搜索框不重建、不丢焦点） */
    internal fun onStockQueryChange(q: String) {
        if (stockQuery == q) return
        stockQuery = q
        stockToggle = !stockToggle
    }

    /** 展开/收起「自动恢复周期」面板；展开时把当前值同步到输入框 */
    private fun toggleHideDaysExpanded() {
        hideDaysExpanded = !hideDaysExpanded
        if (hideDaysExpanded) hideDaysInput = hideDays.toString()
    }

    // ===== 菜单 / 跳转 =====
    override fun openSheet(stock: Stock, x: Float, y: Float) {
        sheetStock = stock; sheetX = x; sheetY = y
    }

    private fun closeSheet() { sheetStock = null }

    // ===== AI 对话管理：分组 / 置顶 / 重命名 / 多选 / 移动到分组 =====

    /** 文本输入弹窗状态（重命名对话 / 重命名分组 / 新建分组共用） */
    internal data class TextPrompt(
        val title: String,
        val initial: String,
        val onConfirm: (String) -> Unit,
    )

    /** 强制重建「最近对话」列表（置顶/删除/改名/移动后即时刷新） */
    internal fun refreshConvs() {
        convToggle = !convToggle
        ChatSync.bump()
    }

    internal fun openConv(code: String) {
        val d = JSONObject(); d.put("stockCode", code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
    }

    internal fun openConvSheet(code: String, x: Float, y: Float) {
        sheetConv = code; sheetConvX = x; sheetConvY = y
    }
    internal fun closeConvSheet() { sheetConv = null }

    internal fun openGroupSheet(id: String) { groupSheetId = id }
    internal fun closeGroupSheet() { groupSheetId = null }

    internal fun enterSelectMode() { selectMode = true }
    internal fun exitSelectMode() { selectMode = false; selectedCodes = emptySet(); selToggle = !selToggle }
    internal fun toggleSelect(code: String) {
        selectedCodes = if (selectedCodes.contains(code)) selectedCodes - code else selectedCodes + code
        selToggle = !selToggle
    }
    internal fun toggleSelectAll() {
        val visible = ChatStore.orderedCodes().filter { it != "free" && (groupFilter.isEmpty() || ChatStore.groupOf(it) == groupFilter) }
        val allSel = visible.isNotEmpty() && visible.all { selectedCodes.contains(it) }
        selectedCodes = if (allSel) emptySet() else visible.toSet()
        selToggle = !selToggle
    }

    internal fun promptRenameConv(code: String) {
        promptInput = ChatStore.displayName(code)
        prompt = TextPrompt("重命名对话", ChatStore.displayName(code)) { name ->
            ChatStore.setCustomName(code, name); prompt = null; refreshConvs()
        }
    }
    internal fun promptRenameGroup(id: String) {
        promptInput = ChatStore.groupName(id)
        prompt = TextPrompt("重命名分组", ChatStore.groupName(id)) { name ->
            ChatStore.renameGroup(id, name); prompt = null; refreshConvs()
        }
    }
    internal fun promptNewGroup() {
        promptInput = ""
        prompt = TextPrompt("新建分组", "") { name ->
            ChatStore.createGroup(name); prompt = null; refreshConvs()
        }
    }

    /** 把若干对话移动到指定分组（"" = 移回未分组） */
    internal fun moveTargetsToGroup(groupId: String) {
        val n = moveTargets.size
        moveTargets.forEach { ChatStore.setGroup(it, groupId) }
        moveTargets = emptyList()
        exitSelectMode()
        refreshConvs()
        bridgeModule.toast(if (groupId.isEmpty()) "已移回未分组" else "已移动到「${ChatStore.groupName(groupId)}」（${n}个）")
    }
    internal fun promptNewGroupAndMove() {
        val targets = moveTargets
        prompt = TextPrompt("新建分组并移入", "") { name ->
            val g = ChatStore.createGroup(name)
            targets.forEach { ChatStore.setGroup(it, g.id) }
            moveTargets = emptyList()
            exitSelectMode()
            prompt = null
            refreshConvs()
            bridgeModule.toast("已移动到「${g.name}」")
        }
    }

    internal fun deleteConvs(codes: List<String>) {
        codes.forEach { ChatStore.deleteConversation(it) }
        exitSelectMode()
        refreshConvs()
        bridgeModule.toast("已删除 ${codes.size} 个对话")
    }
    internal fun pinSelected() {
        selectedCodes.forEach { ChatStore.setPinned(it, true) }
        exitSelectMode()
        refreshConvs()
    }

    private fun askAI(stock: Stock) {
        closeSheet()
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
    }

    override fun openDetail(stock: Stock) {
        closeSheet()
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
    }

    /** 打开「股票对比」页（上区股票轮播对比 + 下区对比 AI 聊天） */
    internal fun openCompare() {
        // 不传 stocks：对比页从持久化读取上次设置的对比股（唯一真相源），首次为空则内部用默认
        val d = JSONObject()
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockCompare", d)
    }

    /** 打开板块详情页（行情「板块」Tab 行点击） */
    internal fun openSector(sector: Sector) {
        closeSheet()
        val d = JSONObject(); d.put("sectorCode", sector.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("SectorDetail", d)
    }

    /** 大盘「市场热度」卡点击：打开行情池明细页（展示池内非指数股票） */
    internal fun openHeatPool() {
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("HeatPool", JSONObject())
    }

    /** 「大盘」Tab 的 AI 入口：就今日大盘问 AI（复用聊天页，按上证指数代码隔离会话） */
    /** 行情页「问 AI 看大盘 →」：带大盘总结问题进入对话并自动发出，兑现卡片承诺。
     *  复用 ChatPage 的 prompt 预填自动发送能力（同「AI 选股」chips）。 */
    internal fun askMarketAI() {
        closeSheet()
        val d = JSONObject()
        d.put("stockCode", "000001")
        d.put(
            "prompt",
            "结合今日三大指数表现和市场涨跌家数，帮我总结当前市场的机会与风险，并给出接下来值得关注的方向。"
        )
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
    }

    /** 个股页「AI 选股」入口：跳 AI 对话（自由模式），可选预填问题 prompt 进入即自动发出（复用现有对话能力） */
    internal fun openAIPickFree(prompt: String) {
        val d = JSONObject()
        d.put("stockCode", "free")
        if (prompt.isNotEmpty()) d.put("prompt", prompt)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
    }

    /** 切换行情子 Tab（大盘/板块/个股） */
    internal fun selectMarketSub(i: Int) {
        if (i == marketSubTab) return
        marketSubTab = i
        marketSubToggle = !marketSubToggle
        rankToggle = !rankToggle // 复位个股子榜视图（避免跨 Tab 残留旧榜单）
        // 真实数据懒加载：切到「板块」拉真实行业板块、切到「个股」拉真实榜单。
        // 异步到达后由 DataSync.bump 翻转重建，用户看到的是真实内容而非 mock 那几个。
        when (i) {
            0 -> {
                // 大盘：除刷新指数/种子外，还要拉真实榜单(新浪)把非指数真实股票并入池——
                // 市场热度/领涨领跌 读池内非指数股票，不拉榜单则池里只有 base 种子(mock) → 首次进大盘热度/领涨错。
                mktLoading = true
                StockData.refresh()
                StockData.loadIndices { /* 各自 bump 清 loading */ }
                StockData.loadRank(0) { /* 涨幅榜真实股入池 → 热度/领涨首进即真 */ }
                StockData.loadRank(1)
                StockData.loadRank(2)
            }
            1 -> {
                if (StockData.hasRealSectors()) return
                mktLoading = true
                StockData.loadSectors { mktLoading = false }
            }
            2 -> {
                if (StockData.hasRank(stockRankTab)) return
                mktLoading = true
                StockData.loadRank(stockRankTab) { mktLoading = false }
            }
        }
    }

    /** 切换个股子榜（涨幅/跌幅/换手率/振幅/全部）。「全部」(index4) 无需真实榜拉取。 */
    internal fun selectRankTab(i: Int) {
        if (i == stockRankTab && i != 4) return
        stockRankTab = i
        rankToggle = !rankToggle
        if (i == 4) return
        if (StockData.hasRank(i)) return
        mktLoading = true
        StockData.loadRank(i) { mktLoading = false } // 切换即拉对应真实榜单
    }

    private fun copyCode(stock: Stock) {
        closeSheet()
        bridgeModule.copyToPasteboard(stock.code)
        bridgeModule.toast("代码 ${stock.code} 已复制")
    }

    /** 把某只股票加入「股票对比」列表（持久化 key 与 StockComparePage 的 kb_compare_codes 一致）。
     *  去重追加；进对比页时其 pageDidAppear 会自动应用最新列表(完成即生效)。
     *  指数也允许加入（用户在"全部"页可把指数当个股加进对比）。 */
    internal fun addToCompare(stock: Stock) {
        val code = stock.code
        val key = "kb_compare_codes"
        val raw = prefs.getItem(key)
        val list = if (!raw.isNullOrBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
        if (code in list) {
            closeSheet()
            bridgeModule.toast("已在对比列表")
            return
        }
        val merged = list + code
        prefs.setItem(key, merged.joinToString(","))
        // 立即拉真实行情入池(含名字/价)——避免对比页渲染时池外冷门股 fallback/显示成 code
        StockData.loadCodesQuotes(setOf(code))
        closeSheet()
        bridgeModule.toast("已加入对比，可在「AI Tab → 股票对比」查看")
    }

    // ===== 自选分组（同会话分组模式）=====

    /** 分组名展示；""=未分组 */
    internal fun watchGroupName(id: String): String = watchGroups.find { it.id == id }?.name ?: "未分组"

    /** 把某只自选股设到指定分组（""=移回未分组）并落盘+刷新 */
    internal fun setWatchGroup(code: String, groupId: String) {
        val m = watchGroupMap.toMutableMap()
        if (groupId.isEmpty()) m.remove(code) else m[code] = groupId
        watchGroupMap = m
        UserStockStore.saveWatchGroupMap(prefs, watchGroupMap)
        listToggle = !listToggle
    }

    /** 新建自选分组（弹窗确认后调用），返回新 id */
    internal fun createWatchGroup(name: String): String {
        val id = "g${System.currentTimeMillis()}"
        watchGroups = watchGroups + UserStockStore.StockGroup(id, name.trim().ifBlank { "新分组" })
        UserStockStore.saveWatchGroups(prefs, watchGroups)
        listToggle = !listToggle
        return id
    }

    /** 重命名自选分组 */
    internal fun renameWatchGroup(id: String, name: String) {
        watchGroups = watchGroups.map { if (it.id == id) UserStockStore.StockGroup(id, name.trim().ifBlank { "新分组" }) else it }
        UserStockStore.saveWatchGroups(prefs, watchGroups)
        listToggle = !listToggle
    }

    /** 删除自选分组：组内股票全部移回未分组 */
    internal fun deleteWatchGroup(id: String) {
        watchGroups = watchGroups.filterNot { it.id == id }
        watchGroupMap = watchGroupMap.filterValues { it != id }
        UserStockStore.saveWatchGroups(prefs, watchGroups)
        UserStockStore.saveWatchGroupMap(prefs, watchGroupMap)
        if (watchGroupFilter == id) watchGroupFilter = ""
        listToggle = !listToggle
    }

    /** 打开自选分组输入弹窗（新建/重命名共用） */
    internal fun promptWatchGroup(title: String, initial: String, onOk: (String) -> Unit) {
        promptInput = initial
        prompt = TextPrompt(title, initial, onOk)
    }

    // ===== 价格预警（App 内命中提示）=====

    /** 预警类型文案 */
    internal fun alertTypeLabel(type: String): String = when (type) {
        "above" -> "涨破"
        "below" -> "跌破"
        "pctUp" -> "当日涨幅≥"
        "pctDown" -> "当日跌幅≥"
        else -> type
    }

    /** 打开某股的设预警弹层：4 行各自回显已设阈值（vif 重建 + Input ref 就绪后延迟 setText 兜底） */
    internal fun openAlertFor(stock: Stock) {
        alertDrafts.clear()
        alertInputRefs.clear()
        listOf("below", "above", "pctDown", "pctUp").forEach { t ->
            val ex = priceAlerts.firstOrNull { it.code == stock.code && it.type == t }
            alertDrafts[t] = if (ex != null) {
                if (t.startsWith("pct")) ex.threshold.toInt().toString() else formatPrice(ex.threshold)
            } else ""
        }
        alertType = "below"
        alertThresholdText = ""
        alertStock = stock  // 触发 vif 翻转重建弹窗
        // 延迟一帧到下一 frame 时再 setText 兜底：此时所有 Input ref 已注册完毕(view 已 inflate)，
        // setText 生效(避免 ref{} 即时回调里 view 还没就绪导致 setText 静默失败)
        setTimeout(pagerId, 0) {
            listOf("below", "above", "pctDown", "pctUp").forEach { t ->
                val d = alertDrafts[t].orEmpty()
                if (d.isNotBlank()) {
                    try { alertInputRefs[t]?.view?.setText(d) } catch (_: Throwable) { /* 兜底, ref 可能在 vif 重建中暂未就绪 */ }
                }
            }
        }
    }

    /** 某行 Input 文本变化时写入该类型草稿 */
    internal fun updateAlertDraft(type: String, text: String) {
        val wasEmpty = alertDrafts[type].isNullOrEmpty()
        alertDrafts[type] = text
        // 空↔非空边界才触发伪占位 vif 重建（避免每键都重建抖动）
        if (wasEmpty != text.isEmpty()) alertRowTick++
        if (type == alertType) alertThresholdText = text
    }

    internal fun closeAlert() { alertStock = null; alertDrafts.clear(); alertInputRefs.clear() }

    /** 保存设置：把 4 行各自填的（非空）预警全部落盘（同 type 覆盖旧的） */
    internal fun confirmAddAlert() {
        val st = alertStock ?: return
        var added = 0
        val updated = priceAlerts.filterNot { it.code == st.code }.toMutableList() // 先移除该股旧的，按新草稿全量写入
        var hasAny = false
        listOf("below", "above", "pctDown", "pctUp").forEach { t ->
            val text = alertDrafts[t].orEmpty().trim()
            if (text.isEmpty()) return@forEach
            val th = text.toFloatOrNull()
            if (th == null || th <= 0f) { bridgeModule.toast("${alertTypeLabel(t)} 阈值无效：$text"); return }
            hasAny = true
            updated.add(AlertStore.PriceAlert(st.code, t, th))
            added++
        }
        if (!hasAny) { bridgeModule.toast("请至少填一项预警阈值"); return }
        priceAlerts = updated
        AlertStore.save(prefs, priceAlerts)
        closeAlert()
        bridgeModule.toast("已保存 $added 项预警")
    }

    /** 删除某股票的全部预警。若正在弹该股管理页，清完直接关闭（重进即空）。 */
    internal fun removeAlertsFor(code: String) {
        priceAlerts = priceAlerts.filterNot { it.code == code }
        AlertStore.save(prefs, priceAlerts)
        if (alertStock?.code == code) closeAlert()
        bridgeModule.toast("已清除该股预警")
    }

    /** 重置某条预警的 fired（允许再次命中提示） */
    internal fun resetAlertFired(code: String, type: String, threshold: Float) {
        priceAlerts = priceAlerts.map {
            if (it.code == code && it.type == type && it.threshold == threshold) it.copy(fired = false) else it
        }
        AlertStore.save(prefs, priceAlerts)
    }

    /** 行情刷新（DataSync）后检查所有预警是否命中；命中 → toast + 标 fired（防重复刷）。
     *  价格离开触发区间时自动复位 fired（允许下次再入区间时再次提示，如"跌破50"回到51再跌会再响）。 */
    internal fun checkPriceAlerts() {
        if (priceAlerts.isEmpty()) return
        var changed = false
        val updated = mutableListOf<AlertStore.PriceAlert>()
        priceAlerts.forEach { a ->
            val st = StockData.getQuotes().firstOrNull { it.code == a.code }
            if (st == null) { updated.add(a); return@forEach }
            val hit = when (a.type) {
                "above" -> st.price >= a.threshold
                "below" -> st.price <= a.threshold
                "pctUp" -> st.changePercent >= a.threshold
                "pctDown" -> st.changePercent <= -a.threshold
                else -> false
            }
            if (hit) {
                // 命中且此前未提示过 → 提示一次并标 fired
                if (!a.fired) {
                    val cond = alertTypeLabel(a.type) + (if (a.type.startsWith("pct")) "${a.threshold}%" else "${a.threshold}")
                    bridgeModule.toast("🔔 ${st.name} 已${cond}（现价${formatPrice(st.price)}）")
                    updated.add(a.copy(fired = true))
                    changed = true
                } else {
                    updated.add(a)  // 仍在触发区：保持 fired，不重复提示
                }
            } else {
                // 离开触发区：复位 fired，允许下次再入区间时再次提示
                if (a.fired) { updated.add(a.copy(fired = false)); changed = true } else updated.add(a)
            }
        }
        if (changed) {
            priceAlerts = updated
            AlertStore.save(prefs, updated)
        }
    }

    /** 切换主 Tab。切到「行情」(1)时：若真实报价未就绪则显示加载中并触发刷新，且翻转 listToggle 让行情/大盘/主列表重建读最新池 */
    internal fun selectMainTab(i: Int) {
        selectedTab = i
        if (i == 1) {
            // ⚠️ 始终刷新行情池：不能只用 isReal() 判断——isReal 在拉过榜单/成分后即为 true，
            // 但大盘指数/baseQuotes 的实时价可能还没刷新到，需每次进行情都 refresh 兜底。
            if (!StockData.isReal()) mktLoading = true
            StockData.refresh()
            listToggle = !listToggle
            marketSubToggle = !marketSubToggle
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            // 建立依赖：隐藏/自选/恢复天数等真实 observable 变化即重渲染列表。
            // 直接读取（而非仅读 dataVersion 计数），确保子组件（KRStockList）拿到最新过滤后的列表。
            ctx.hiddenMap; ctx.watchlistCodes; ctx.hideDays; ctx.dataVersion
            // 内容卡宽度（Scroller 默认不拉伸子元素，需显式宽度以铺满、避免右侧留白）
            val contentW = ctx.pagerData.pageViewWidth - 24f
            attr { flexDirectionColumn(); backgroundColor(if (ctx.darkOn) Color(0xFF1A1B1E) else Color.WHITE) }

            // ===== TopBar =====
            View {
                attr {
                    height(48f); flexDirectionRow(); alignItemsCenter(); padding(all = 14f)
                    backgroundColor(Color(ctx.themeColor))
                }
                Text { attr { text(ctx.tabTitles[ctx.selectedTab]); fontSize(ctx.fs(18f)); color(Color.WHITE) } }
            }

            // ===== ContentArea =====
            View {
                attr { flex(1f); flexDirectionColumn() }

                vif({ ctx.reseedToggle }) { val c = this; with(this@MainTabPager) { c.renderContentArea(ctx) } }
                vif({ !ctx.reseedToggle }) { val c = this; with(this@MainTabPager) { c.renderContentArea(ctx) } }
            }

            // ===== BottomTabBar =====
            // 克制精致：白底 + 顶部发丝分隔线；选中项=文字套浅主题色圆角胶囊底(像分段控件/一线金融App)，
            // 去掉了生硬的短横条。胶囊只在文字四周，不刷整格底，干净不默认。
            // 顶部发丝分隔线
            View { attr { height(1f); backgroundColor(Color(0xFFF0F0F0)) } }
            View {
                attr {
                    height(56f); flexDirectionRow(); alignItemsStretch()
                    backgroundColor(Color.WHITE)
                }
                val tabs = listOf("AI", "行情", "自选", "我的")
                tabs.forEachIndexed { i, name ->
                    // ⚠️ attr 闭包内现读 ctx.selectedTab(不提前提顶层 val, 否则不重跑, 见 685fe73 坑)
                    View {
                        attr { flex(1f); flexDirectionColumn(); alignItemsCenter(); justifyContentCenter() }
                        event { click { ctx.selectMainTab(i) } }
                        // 文字胶囊底：选中=浅主题色圆角底+主题色文字加粗；未选中=透明底+中性灰文字
                        View {
                            attr {
                                padding(14f, 5f, bottom = 14f, right = 5f)
                                borderRadius(15f)
                                backgroundColor(
                                    if (ctx.selectedTab == i) Color(UserSettings.blend(ctx.themeColor, -1L, 0.92f))
                                    else Color(0x00000000)
                                )
                            }
                            Text {
                                attr {
                                    text(name)
                                    fontSize(ctx.fs(12f))
                                    if (ctx.selectedTab == i) fontWeightSemisolid()
                                    color(if (ctx.selectedTab == i) Color(ctx.themeColor) else Color(0xFF8A8F99))
                                }
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
                        val watched = ctx.sheetStock != null && ctx.watchlistCodes.contains(ctx.sheetStock!!.code)
                        val menuH = if (watched) 500f else 380f
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
                    val dimmed = ctx.isHidden(stock.code)
                    // 加自选（可切换）
                    sheetItem(if (watched) "★ 已自选" else "☆ 加自选") {
                        ctx.toggleWatch(stock.code)
                        ctx.closeSheet()
                        ctx.bridgeModule.toast(if (watched) "已取消自选" else "已加入自选")
                    }
                    // 是自选股：提供「移动到分组」
                    vif({ watched }) {
                        sheetDivider()
                        sheetItem("⇲ 移动到分组") { val c = stock.code; ctx.closeSheet(); ctx.watchMoveCode = c }
                    }
                    sheetDivider()
                    sheetItem("问 AI") { ctx.askAI(stock) }
                    sheetItem("查看详细") { ctx.openDetail(stock) }
                    sheetDivider()
                    // 加入股票对比（持久化对比股列表，进对比页自动生效）
                    sheetItem("⇄ 加对比") { ctx.addToCompare(stock) }
                    sheetDivider()
                    // 价格预警（命中提示）
                    sheetItem(if (ctx.priceAlerts.any { it.code == stock.code }) "⏰ 管理价格预警" else "⏰ 设价格预警") {
                        val s = stock; ctx.closeSheet(); ctx.openAlertFor(s)
                    }
                    // 不感兴趣 / 恢复（按当前是否已被标记切换文案）
                    sheetItem(if (dimmed) "恢复" else "不感兴趣") {
                        if (dimmed) ctx.restoreStock(stock.code) else ctx.hideStock(stock.code)
                        ctx.closeSheet()
                        ctx.bridgeModule.toast(if (dimmed) "已恢复" else "已标记为不感兴趣（灰幕覆盖，可再次长按恢复）")
                    }
                    sheetDivider()
                    sheetItem("复制代码") { ctx.copyCode(stock) }
                }
            }

            // ===== 价格预警设置 overlay（类型 chips + 阈值输入 + 确认）=====
            vif({ ctx.alertStock != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.closeAlert() } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val menuW = 300f
                        val left = (vw - menuW) / 2f
                        val top = 90f
                        absolutePosition(top = top, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(12f); flexDirectionColumn()
                    }
                    val st = ctx.alertStock!!
                    View { attr { padding(14f); flexDirectionRow(); alignItemsCenter() }
                        Text { attr { text("价格预警 · ${st.name}"); fontSize(ctx.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        View { attr { flex(1f) } }
                        // 若已有预警：提供清除入口
                        vif({ ctx.priceAlerts.any { it.code == st.code } }) {
                            Text { attr { text("清除预警"); fontSize(ctx.fs(13f)); color(Color(ctx.themeColor)); textDecorationUnderLine() }
                                event { click { ctx.removeAlertsFor(st.code) } } }
                        }
                    }
                    View { attr { padding(left = 14f, right = 14f, bottom = 8f) }
                        Text { attr { text("现价 ${formatPrice(st.price)}（今日 ${formatPercent(st.changePercent)}）"); fontSize(ctx.fs(12f)); color(Color(0xFF999999)) } }
                    }
                    // 4 行预警类型，各自一个输入框（可一次设置多种）
                    View { attr { flexDirectionColumn(); paddingLeft(14f); paddingRight(14f) }
                        listOf("below", "above", "pctDown", "pctUp").forEach { t ->
                            View { attr { flexDirectionRow(); alignItemsCenter(); marginBottom(8f) }
                                Text { attr {
                                    width(72f); text(ctx.alertTypeLabel(t)); fontSize(ctx.fs(13f)); color(Color(0xFF444444)) } }
                                // 输入框（灰底圆角）
                                View { attr { flex(1f); height(36f); backgroundColor(Color(0xFFF5F6F8)); borderRadius(8f) }
                                    // 伪占位：浅灰、靠右；仅当草稿空时显示（用户输入后自动消失，靠右不跟左对齐的输入冲突）
                                    vif({ ctx.alertRowTick >= 0 && ctx.alertDrafts[t].isNullOrEmpty() }) {
                                        View { attr {
                                            absolutePosition(left = 10f, right = 10f, top = 0f, bottom = 0f)
                                            flexDirectionRow(); justifyContentFlexEnd(); alignItemsCenter()
                                        }
                                            Text { attr {
                                                text(if (t.startsWith("pct")) "如 5（%）" else "如 ${formatPrice(st.price.coerceAtLeast(1f))}")
                                                fontSize(ctx.fs(14f)); color(Color(0xFFB4B4B4))
                                            } }
                                        }
                                    }
                                    // 真实输入：文字深黑左对齐；外包一层左 padding 让文字不贴最左
                                    View { attr { flex(1f); height(36f); paddingLeft(10f); paddingRight(10f); justifyContentCenter() }
                                        Input {
                                            ref { ctx.alertInputRefs[t] = it }
                                            attr {
                                                flex(1f); height(30f)
                                                backgroundColor(Color(0x00000000))
                                                color(Color(0xFF222222)); fontSize(ctx.fs(14f)); textAlignLeft()
                                            }
                                            event { textDidChange { ctx.updateAlertDraft(t, it.text) } }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    View { attr { flexDirectionRow(); padding(14f) }
                        View {
                            attr { flex(1f); height(40f); borderRadius(8f); marginRight(8f); alignItemsCenter(); justifyContentCenter(); backgroundColor(Color(0xFFF2F3F5)) }
                            event { click { ctx.closeAlert() } }
                            Text { attr { text("取消"); fontSize(ctx.fs(14f)); color(Color(0xFF666666)) } }
                        }
                        View {
                            attr { flex(1f); height(40f); borderRadius(8f); alignItemsCenter(); justifyContentCenter(); backgroundColor(Color(ctx.themeColor)) }
                            event { click { ctx.confirmAddAlert() } }
                            Text { attr { text("保存设置"); fontSize(ctx.fs(14f)); color(Color.WHITE) } }
                        }
                    }
                }
            }

            // ===== 自选「移动到分组」overlay =====
            vif({ ctx.watchMoveCode != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.watchMoveCode = null } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val vh = ctx.pagerData.pageViewHeight
                        val menuW = 200f
                        val menuH = if (ctx.watchGroups.isEmpty()) 180f else 240f
                        val left = (vw - menuW) / 2f
                        val top = (vh - menuH) / 2f
                        absolutePosition(top = top, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(12f); flexDirectionColumn()
                    }
                    View { attr { padding(14f) }
                        Text { attr { text("移动到分组"); fontSize(ctx.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                    }
                    val code = ctx.watchMoveCode!!
                    sheetItem("未分组（移出分组）") { ctx.setWatchGroup(code, ""); ctx.watchMoveCode = null }
                    ctx.watchGroups.forEach { g -> sheetItem(g.name) { ctx.setWatchGroup(code, g.id); ctx.watchMoveCode = null } }
                    sheetDivider()
                    sheetItem("+ 新建分组并移入") {
                        val c = code
                        ctx.watchMoveCode = null
                        ctx.promptWatchGroup("新建分组并移入", "") { n -> val id = ctx.createWatchGroup(n); ctx.setWatchGroup(c, id) }
                    }
                    sheetDivider()
                    sheetItem("取消") { ctx.watchMoveCode = null }
                }
            }

            // ===== 自选分组操作菜单（长按分组 chip：重命名/删除）=====
            vif({ ctx.watchGroupSheetId != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.watchGroupSheetId = null } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val menuW = 200f
                        val menuH = 150f
                        val left = (vw - menuW) / 2f
                        val top = (ctx.pagerData.pageViewHeight - menuH) / 2f
                        absolutePosition(top = top, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(12f); flexDirectionColumn()
                    }
                    val gid = ctx.watchGroupSheetId!!
                    View { attr { padding(14f) }
                        Text { attr { text("分组：${ctx.watchGroupName(gid)}"); fontSize(ctx.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                    }
                    sheetItem("重命名") { val g = gid; ctx.watchGroupSheetId = null; ctx.promptWatchGroup("重命名分组", ctx.watchGroupName(g)) { n -> ctx.renameWatchGroup(g, n) } }
                    sheetItem("删除分组") { val g = gid; ctx.watchGroupSheetId = null; ctx.deleteWatchGroup(g) }
                    sheetItem("取消") { ctx.watchGroupSheetId = null }
                }
            }

            // ===== 对话 / 分组 管理 overlays =====
            // 对话长按菜单
            vif({ ctx.sheetConv != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.closeConvSheet() } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val vh = ctx.pagerData.pageViewHeight
                        val menuW = 180f
                        val menuH = 300f
                        val left = (ctx.sheetConvX - menuW / 2f).coerceIn(8f, (vw - menuW - 8f).coerceAtLeast(8f))
                        val top = ctx.sheetConvY.coerceIn(8f, (vh - menuH - 8f).coerceAtLeast(8f))
                        absolutePosition(top = top, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(10f); flexDirectionColumn()
                    }
                    val code = ctx.sheetConv!!
                    val pinned = ChatStore.isPinned(code)
                    sheetItem("删除") { ctx.deleteConvs(listOf(code)); ctx.closeConvSheet() }
                    sheetDivider()
                    sheetItem(if (pinned) "取消置顶" else "置顶") { ChatStore.togglePin(code); ctx.closeConvSheet(); ctx.refreshConvs() }
                    sheetItem("重命名") { val c = code; ctx.closeConvSheet(); ctx.promptRenameConv(c) }
                    sheetItem("移动到分组") { val c = code; ctx.closeConvSheet(); ctx.moveTargets = listOf(c) }
                    sheetDivider()
                    sheetItem("多选") { val c = code; ctx.closeConvSheet(); ctx.enterSelectMode(); ctx.toggleSelect(c) }
                    sheetDivider()
                    sheetItem("取消") { ctx.closeConvSheet() }
                }
            }

            // 分组选择（移动到分组）
            vif({ ctx.moveTargets.isNotEmpty() }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.moveTargets = emptyList() } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val menuW = 220f
                        val left = (vw - menuW) / 2f
                        absolutePosition(top = 120f, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(10f); flexDirectionColumn()
                    }
                    View { attr { padding(14f) }
                        Text { attr { text("移动到分组"); fontSize(ctx.fs(15f)); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                    }
                    sheetDivider()
                    sheetItem("未分组（移出分组）") { ctx.moveTargetsToGroup("") }
                    ChatStore.groups().forEach { g -> sheetItem(g.name) { ctx.moveTargetsToGroup(g.id) } }
                    sheetDivider()
                    sheetItem("+ 新建分组并移入") { ctx.promptNewGroupAndMove() }
                    sheetDivider()
                    sheetItem("取消") { ctx.moveTargets = emptyList() }
                }
            }

            // 分组操作菜单（长按分组 chip）
            vif({ ctx.groupSheetId != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.closeGroupSheet() } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val menuW = 180f
                        val left = (vw - menuW) / 2f
                        absolutePosition(top = 160f, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(10f); flexDirectionColumn()
                    }
                    val gid = ctx.groupSheetId!!
                    sheetItem("重命名分组") { val g = gid; ctx.closeGroupSheet(); ctx.promptRenameGroup(g) }
                    sheetDivider()
                    sheetItem("删除分组") {
                        val g = gid; ctx.closeGroupSheet()
                        if (ctx.groupFilter == g) ctx.groupFilter = ""
                        ChatStore.deleteGroup(g); ctx.refreshConvs()
                    }
                    sheetDivider()
                    sheetItem("取消") { ctx.closeGroupSheet() }
                }
            }

            // 文本输入弹窗（重命名 / 新建分组）
            vif({ ctx.prompt != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.prompt = null } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val cardW = 280f
                        val left = (vw - cardW) / 2f
                        absolutePosition(top = 180f, left = left)
                        width(cardW); backgroundColor(Color.WHITE); borderRadius(12f); flexDirectionColumn(); padding(16f)
                    }
                    val p = ctx.prompt!!
                    Text { attr { text(p.title); fontSize(ctx.fs(16f)); fontWeightSemisolid(); color(Color(0xFF222222)); marginBottom(12f) } }
                    View { attr { padding(8f); backgroundColor(Color(0xFFF2F3F5)); borderRadius(8f) }
                        Input {
                            attr {
                                text(ctx.promptInput)
                                height(40f); fontSize(ctx.fs(15f)); color(Color(0xFF222222))
                                placeholder("请输入名称"); placeholderColor(Color(0xFF999999))
                            }
                            event { textDidChange { ctx.promptInput = it.text } }
                        }
                    }
                    View { attr { flexDirectionRow(); marginTop(16f) }
                        View { attr { flex(1f); padding(10f); alignItemsCenter(); justifyContentCenter() }
                            event { click { ctx.prompt = null } }
                            Text { attr { text("取消"); fontSize(ctx.fs(15f)); color(Color(0xFF666666)) } } }
                        View { attr { flex(1f); padding(10f); alignItemsCenter(); justifyContentCenter() }
                            event { click { val name = ctx.promptInput; p.onConfirm(name); ctx.promptInput = "" } }
                            Text { attr { text("确定"); fontSize(ctx.fs(15f)); color(Color(ctx.themeColor)); fontWeightSemisolid() } } }
                    }
                }
            }

            // 多选底部操作栏
            vif({ ctx.selectMode }) {
                View {
                    attr {
                        absolutePosition(left = 0f, bottom = 56f)
                        width(ctx.pagerData.pageViewWidth)
                        height(56f); flexDirectionRow(); alignItemsCenter(); justifyContentFlexStart()
                        backgroundColor(Color.WHITE)
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFEEEEEE)))
                        padding(0f, 12f)
                    }
                    Text { attr { text("已选 ${ctx.selectedCodes.size}"); fontSize(ctx.fs(14f)); color(Color(0xFF222222)); flex(1f); marginRight(16f) } }
                    View { attr { padding(6f, 4f, bottom = 6f, right = 4f); marginRight(8f); borderRadius(8f); backgroundColor(Color(ctx.themeColor)) }
                        event { click { ctx.pinSelected() } }
                        Text { attr { text("置顶"); fontSize(ctx.fs(14f)); color(Color.WHITE) } } }
                    View { attr { padding(6f, 4f, bottom = 6f, right = 4f); marginRight(8f); borderRadius(8f); backgroundColor(Color(0xFFF2F3F5)) }
                        event { click { ctx.moveTargets = ctx.selectedCodes.toList() } }
                        Text { attr { text("移动到分组"); fontSize(ctx.fs(14f)); color(Color(0xFF333333)) } } }
                    View { attr { padding(6f, 4f, bottom = 6f, right = 4f); borderRadius(8f); backgroundColor(Color(0xFFFDECEA)) }
                        event { click { ctx.deleteConvs(ctx.selectedCodes.toList()) } }
                        Text { attr { text("删除"); fontSize(ctx.fs(14f)); color(Color(0xFFE54D42)) } } }
                }
            }

        }
    }
}

/** 行导航接口：行情页与板块详情页共用同一行渲染 [renderMarketRow]，各自实现跳转/菜单 */
internal interface StockNavigator {
    /** 点击行 -> 进个股详情 */
    fun openDetail(stock: Stock)
    /** 长按行 -> 弹操作菜单（锚定长按点） */
    fun openSheet(stock: Stock, x: Float, y: Float)
    /** 该行股票是否已被「不感兴趣」（用于灰幕覆盖与菜单文案切换） */
    fun isHidden(code: String): Boolean
}

/** 菜单单项（文本 + 点击） */
private fun ViewContainer<*, *>.sheetItem(label: String, onClick: () -> Unit) {
    View {
        attr {
            height(50f); justifyContentCenter(); paddingLeft(16f)
        }
        event { click { onClick() } }
        Text { attr { text(label); fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)) } }
    }
}

private fun ViewContainer<*, *>.sheetDivider() {
    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
}

/** 分组筛选 chip（全部 / 各分组 / + 新建）；分组 chip 可长按重命名/删除 */
private fun ViewContainer<*, *>.chatChip(
    ctx: MainTabPager,
    label: String,
    active: Boolean,
    onLongPress: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    View {
        attr {
            padding(6f, 4f, bottom = 6f, right = 4f); marginRight(6f); borderRadius(14f)
            alignItemsCenter(); justifyContentCenter()
            backgroundColor(if (active) Color(ctx.themeColor) else Color(0xFFF2F3F5))
        }
        event {
            click { onClick() }
            if (onLongPress != null) longPress { onLongPress.invoke() }
        }
        Text { attr { text(label); fontSize(ctx.fs(12f)); color(if (active) Color.WHITE else Color(0xFF666666)); textAlignCenter() } }
    }
}


// ===== 列表渲染（放进 vif 内容闭包，随 convToggle/listToggle 翻转强制重建；本版本 body 不随 observable 重跑）=====
// 注意：必须是「文件级」扩展函数，不能定义在类内（成员扩展函数在 vif 闭包里会丢失分派接收者）。

/** 最近对话：随 convToggle 翻转重建；支持分隔线 / 自由问答灰卡 / 分组筛选 / 多选 / 长按菜单 */
/** 最近对话标题栏：独立于 vif 重建，避免双分支都渲染时标题重复 */
private fun ViewContainer<*, *>.renderRecentsHeader(ctx: MainTabPager, contentW: Float) {
    // 标题 & 按钮按分组/多选态分别包 vif，保证状态切换时局部重建
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween()
            width(contentW + 24f); marginLeft(-12f)
            paddingLeft(12f); paddingRight(12f)
            marginBottom(8f); marginTop(2f)
        }
        vif({ ctx.groupFilter.isEmpty() }) {
            Text {
                attr {
                    text("最近对话")
                    fontSize(ctx.fs(16f)); fontWeightSemisolid(); color(Color(0xFF222222))
                }
            }
        }
        vif({ ctx.groupFilter.isNotEmpty() }) {
            Text {
                attr {
                    text("分组：${ChatStore.groupName(ctx.groupFilter)}")
                    fontSize(ctx.fs(16f)); fontWeightSemisolid(); color(Color(0xFF222222))
                }
            }
        }
        vif({ ctx.selectMode }) {
            View { attr { flexDirectionRow(); alignItemsCenter() }
                View { attr { padding(6f); marginRight(2f) }
                    event { click { ctx.toggleSelectAll() } }
                    Text { attr { text("全选"); fontSize(ctx.fs(13f)); color(Color(ctx.themeColor)) } }
                }
                View { attr { padding(6f) }
                    event { click { ctx.exitSelectMode() } }
                    Text { attr { text("完成"); fontSize(ctx.fs(13f)); color(Color(0xFF666666)) } }
                }
            }
        }
        vif({ !ctx.selectMode }) {
            View { attr { padding(6f) }
                event { click { ctx.enterSelectMode() } }
                Text { attr { text("选择"); fontSize(ctx.fs(13f)); color(Color(ctx.themeColor)) } }
            }
        }
    }
}

/** 最近对话：随 convToggle 翻转重建；支持分隔线 / 自由问答灰卡 / 分组筛选 / 多选 / 长按菜单 */
private fun ViewContainer<*, *>.renderRecents(ctx: MainTabPager, contentW: Float) {
    ctx.dataVersion; ctx.convToggle; ctx.selToggle; ctx.groupFilter; ctx.selectMode; ctx.selectedCodes
    // 分组筛选 chips
    val groups = ChatStore.groups()
    View {
        attr { width(contentW); flexDirectionRow(); alignItemsCenter(); justifyContentFlexStart(); marginBottom(8f) }
        chatChip(ctx, "全部", ctx.groupFilter.isEmpty()) { ctx.groupFilter = ""; ctx.refreshConvs() }
        groups.forEach { g ->
            chatChip(ctx, g.name, ctx.groupFilter == g.id, onLongPress = { ctx.openGroupSheet(g.id) }) { ctx.groupFilter = g.id; ctx.refreshConvs() }
        }
        chatChip(ctx, "+ 新建", false) { ctx.promptNewGroup() }
    }
    // 自由问答入口（灰卡，常驻置顶）
    renderFreeChatEntry(ctx, contentW, ChatStore.hasConversation("free"))
    // 个股对话列表（按置顶序；分组筛选）
    val codes = ChatStore.orderedCodes().filter {
        it != "free" && (ctx.groupFilter.isEmpty() || ChatStore.groupOf(it) == ctx.groupFilter)
    }
    if (codes.isEmpty()) {
        Text {
            attr {
                text(if (ctx.groupFilter.isEmpty()) "还没有个股对话。去行情页长按某只股票，选「问 AI」，就能和它开始一段对话～" else "该分组还没有对话，去聊一只股票或移动进来吧～")
                fontSize(ctx.fs(13f)); color(Color(0xFF999999)); marginTop(16f)
            }
        }
    } else {
        codes.forEachIndexed { idx, code ->
            val s = StockData.findByCode(code)
            val last = ChatStore.last(code)
            val name = ChatStore.displayName(code)
            val pinned = ChatStore.isPinned(code)
            View {
                attr {
                    flexDirectionRow(); alignItemsCenter(); marginTop(8f)
                    padding(12f)
                    width(contentW)
                    val sel = ctx.selectMode && ctx.selectedCodes.contains(code)
                    backgroundColor(if (sel) Color(0xFFEAF6FF) else Color.WHITE)
                    borderRadius(10f)
                    border(Border(1f, BorderStyle.SOLID, if (pinned) Color(0xFFCFD8DE) else Color(0xFFEEEEEE)))
                }
                event {
                    click { if (ctx.selectMode) ctx.toggleSelect(code) else ctx.openConv(code) }
                    longPress { p -> ctx.openConvSheet(code, p.pageX, p.pageY) }
                }
                vif({ ctx.selectMode }) {
                    View {
                        attr {
                            width(20f); height(20f); borderRadius(10f); marginRight(10f)
                            alignItemsCenter(); justifyContentCenter()
                            val sel = ctx.selectedCodes.contains(code)
                            border(Border(1.5f, BorderStyle.SOLID, Color(if (sel) ctx.themeColor else 0xFFCCCCCC)))
                            backgroundColor(if (sel) Color(ctx.themeColor) else Color.WHITE)
                        }
                        Text { attr {
                            val sel = ctx.selectedCodes.contains(code)
                            text(if (sel) "✓" else ""); fontSize(ctx.fs(13f)); color(Color.WHITE)
                        } }
                    }
                }
                View {
                    attr {
                        width(36f); height(36f); borderRadius(18f)
                        backgroundColor(Color(0xFFE6F1FB)); marginRight(10f)
                        alignItemsCenter(); justifyContentCenter()
                    }
                    Text { attr { text(name.take(1)); fontSize(ctx.fs(16f)); color(Color(ctx.themeColor)); fontWeightSemisolid() } }
                }
                View { attr { flex(1f); flexDirectionColumn() }
                    View { attr { flexDirectionRow(); alignItemsCenter() }
                        Text { attr { text(name); fontSize(ctx.fs(15f)); color(Color(0xFF222222)) } }
                        vif({ pinned }) {
                            View { attr { marginLeft(6f); backgroundColor(Color(0xFFF0F1F3)); padding(2f, 4f); borderRadius(4f) }
                                Text { attr { text("置顶"); fontSize(ctx.fs(10f)); color(Color(0xFF999999)) } }
                            }
                        }
                    }
                    Text {
                        attr {
                            text((last?.text ?: "").let { if (it.length > 22) it.take(22) + "…" else it })
                            fontSize(ctx.fs(12f)); color(Color(0xFF999999)); marginTop(3f)
                        }
                    }
                }
                vif({ !ctx.selectMode }) { Text { attr { text(">"); fontSize(ctx.fs(16f)); color(Color(0xFFCCCCCC)) } } }
            }
            // 分隔线（最后一条不加）
            vif({ idx < codes.lastIndex }) {
                View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(58f); marginRight(12f) } }
            }
        }
    }
}

/**
 * 自由问答入口卡片（AI Tab 常驻置顶）：不绑个股，可聊大盘、宏观、行业逻辑与选股思路。
 * 已聊过则显示最后一句作摘要；用主题色卡片与其下的白色个股对话行形成层次。
 */
private fun ViewContainer<*, *>.renderFreeChatEntry(ctx: MainTabPager, contentW: Float, hasFree: Boolean) {
    val last = if (hasFree) ChatStore.last("free") else null
    val summary = if (last == null) "聊大盘、宏观、行业逻辑或选股思路"
    else if (last.text.length > 22) last.text.take(22) + "…" else last.text
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginBottom(4f)
            padding(14f); backgroundColor(Color(0xFF8A9099)); borderRadius(12f)
            width(contentW)
        }
        event {
            click {
                val d = JSONObject(); d.put("stockCode", "free")
                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
            }
        }
        View {
            attr {
                width(36f); height(36f); borderRadius(18f)
                backgroundColor(Color(0xFFFFFFFF)); marginRight(10f)
                alignItemsCenter(); justifyContentCenter()
            }
            Text { attr { text("AI"); fontSize(ctx.fs(14f)); color(Color(0xFF8A9099)); fontWeightSemisolid() } }
        }
        View { attr { flex(1f); flexDirectionColumn() }
            Text { attr { text("AI 自由问答"); fontSize(ctx.fs(15f)); color(Color.WHITE); fontWeightSemisolid() } }
            Text { attr { text(summary); fontSize(ctx.fs(12f)); color(Color(0xFFF2F8FC)); marginTop(3f) } }
        }
        Text { attr { text(">"); fontSize(ctx.fs(16f)); color(Color(0xFFF2F8FC)) } }
    }
}

/** 行情页：顶部子 Tab 栏（大盘/板块/个股）+ 滚动内容，整体随 marketSubToggle 翻转重建
 *  ⚠️ 关键：Tab 栏与内容必须放在同一个翻转里一起重建，否则高亮（选中色）不跟着切（之前大盘蓝条永远停在大盘）。 */
private fun ViewContainer<*, *>.renderMarket(ctx: MainTabPager) {
    vif({ ctx.marketSubToggle }) { val c = this; c.renderMarketContent(ctx) }
    vif({ !ctx.marketSubToggle }) { val c = this; c.renderMarketContent(ctx) }
}

/** 行情内容（子 Tab 栏 + 滚动区），闭包内现读 marketSubTab，翻转即整体重建，高亮与列表同步 */
private fun ViewContainer<*, *>.renderMarketContent(ctx: MainTabPager) {
    renderMarketSubTabs(ctx)
    Scroller {
        attr { flex(1f); flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }
        // 数据来源标注：让用户一眼分清「实时行情」还是「离线演示数据」，避免误判
        Text {
            attr {
                text(if (StockData.isReal()) "数据来源：腾讯·新浪实时行情" else "当前为本地演示数据，联网后自动切换为实时行情")
                fontSize(ctx.fs(11f)); color(Color(0xFFAAAAAA)); margin(10f)
            }
        }
        // 板块/个股真实数据拉取中提示（随 mktLoading 显隐）
        vif({ ctx.mktLoading }) {
            View {
                attr {
                    flexDirectionRow(); alignItemsCenter(); justifyContentCenter()
                    padding(10f); marginBottom(6f); backgroundColor(Color(0xFFE8F3FC))
                }
                Text { attr { text("加载中…"); fontSize(ctx.fs(12f)); color(Color(0xFF3478F6)) } }
            }
        }
        when (ctx.marketSubTab) {
            // 大盘：包 marketDataTick 双分支——行情数据(报价)每到达一次即重建，读最新真实价刷新
            0 -> {
                vif({ ctx.marketDataTick }) { val c = this; c.renderMarketIndex(ctx) }
                vif({ !ctx.marketDataTick }) { val c = this; c.renderMarketIndex(ctx) }
            }
            1 -> renderSectorList(ctx)
            2 -> renderRankArea(ctx)
        }
    }
}

/** 行情子 Tab 栏：大盘 / 板块 / 个股 */
private fun ViewContainer<*, *>.renderMarketSubTabs(ctx: MainTabPager) {
    val tabs = listOf("大盘", "板块", "个股")
    View {
        attr {
            flexDirectionRow(); height(44f); alignItemsCenter()
            backgroundColor(Color.WHITE); border(Border(0.5f, BorderStyle.SOLID, Color(0xFFEEEEEE)))
        }
        tabs.forEachIndexed { i, title ->
            View {
                attr {
                    flex(1f); height(44f); flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
                }
                event { click { ctx.selectMarketSub(i) } }
                Text {
                    attr {
                        text(title)
                        fontSize(ctx.fs(15f))
                        // 响应式：直接读 marketSubTab，选中态随其变化即时刷新（不依赖外层 vif 翻转）
                        color(if (ctx.marketSubTab == i) Color(ctx.themeColor) else Color(0xFF666666))
                        fontWeightSemiBold()
                    }
                }
                View {
                    attr {
                        width(24f); height(2.5f); marginTop(4f); borderRadius(1.25f)
                        backgroundColor(if (ctx.marketSubTab == i) Color(ctx.themeColor) else Color(0))
                    }
                }
            }
        }
    }
}

/** 大盘：指数大框 + AI 分析入口（预留位，后续可接真实 AI 大盘解读） */
private fun ViewContainer<*, *>.renderMarketIndex(ctx: MainTabPager) {
    val w = ctx.pagerData.pageViewWidth - 24f
    ctx.marketIndices().let { if (it.isNotEmpty()) renderIndexBox(ctx, it, w) }
    // 市场热度：涨跌家数 + 占比条 + 领涨/领跌（样本 = 当前池内非指数标的 + 板块）
    renderMarketHeat(ctx, w)
    renderMarketLeaders(ctx, w)
    // AI 分析入口卡片
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(w); flexDirectionColumn()
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            View { attr { width(18f); height(18f); borderRadius(9f); backgroundColor(Color(0xFFE6F1FB)); marginRight(6f) } }
            Text { attr { text("AI 大盘解读"); fontSize(ctx.fs(15f)); fontWeightSemiBold(); color(Color(0xFF222222)) } }
        }
        Text {
            attr {
                text("基于今日三大指数表现，让 AI 帮你总结机会与风险。")
                fontSize(ctx.fs(13f)); color(Color(0xFF999999)); marginTop(8f)
            }
        }
        View {
            attr { flexDirectionRow(); marginTop(12f) }
            View {
                attr {
                    height(34f); paddingLeft(16f); paddingRight(16f)
                    borderRadius(17f); backgroundColor(Color(ctx.themeColor))
                    alignItemsCenter(); justifyContentCenter()
                }
                event { click { ctx.askMarketAI() } }
                Text { attr { text("问 AI 看大盘 →"); fontSize(ctx.fs(14f)); color(Color.WHITE) } }
            }
        }
    }
    View { attr { height(16f) } }
}

/** 板块：顶部涨跌概览卡 + 搜索框 + 关注板块 + 板块列表（点击进详情）
 *  搜索/关注是交互状态：列表区包在 vif(sectorToggle) 内，键入/点星即翻转重画；
 *  概览卡基于全量板块（不随搜索过滤），搜索框用响应式 attr 绑定 sectorQuery（不重建、不丢输入）。 */
private fun ViewContainer<*, *>.renderSectorList(ctx: MainTabPager) {
    val w = ctx.pagerData.pageViewWidth - 24f
    val allSectors = StockData.getSectors()
    if (allSectors.isEmpty()) {
        Text { attr { text("暂无板块数据"); fontSize(ctx.fs(13f)); color(Color(0xFF999999)); marginTop(16f); marginLeft(12f) } }
        View { attr { height(16f) } }
        return
    }
    // 涨跌幅进度条归一化基准：取板块最大绝对涨跌幅（至少 3%），同屏可直观比较强弱
    val maxAbs = allSectors.map { kotlin.math.abs(it.changePercent) }.maxOrNull()?.coerceAtLeast(3f) ?: 3f

    renderSectorOverview(ctx, allSectors, w)
    // 搜索框（响应式 attr 绑定 query，输入实时过滤；输入框自身不随翻转重建以保输入流畅）
    renderSectorSearch(ctx, w)
    // 交互区（关注 chips + 过滤后列表）随 sectorToggle 翻转
    vif({ ctx.sectorToggle }) { val c = this; c.renderSectorBody(ctx, allSectors, w, maxAbs) }
    vif({ !ctx.sectorToggle }) { val c = this; c.renderSectorBody(ctx, allSectors, w, maxAbs) }
    View { attr { height(16f) } }
}

/** 板块搜索输入框 + 清空按钮（不随 sectorToggle 重建，attr 响应式回显/清空） */
private fun ViewContainer<*, *>.renderSectorSearch(ctx: MainTabPager, w: Float) {
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f, bottom = 12f)
            padding(left = 12f, right = 8f); height(40f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(w)
            flexDirectionRow(); alignItemsCenter()
        }
        // 搜索图标（左侧灰色，替代纯文字占位，醒目且不依赖占位符颜色）
        Text { attr { text("🔍"); fontSize(ctx.fs(15f)); color(Color(0xFF999999)); marginRight(6f) } }
        Input {
            attr {
                flex(1f); height(34f); fontSize(ctx.fs(14f)); color(Color(0xFF222222))
                text(ctx.sectorQuery)
                placeholder("搜索板块"); placeholderColor(Color(0xFFBBBBBB))
            }
            event { textDidChange { ctx.onSectorQueryChange(it.text) } }
        }
        // 清空按钮：query 非空时可点
        View {
            attr {
                width(30f); height(30f); justifyContentCenter(); alignItemsCenter()
                opacity(if (ctx.sectorQuery.isEmpty()) 0f else 1f)
            }
            event { click { ctx.onSectorQueryChange("") } }
            Text { attr { text("✕"); fontSize(ctx.fs(14f)); color(Color(0xFF999999)) } }
        }
    }
}

/** 板块关注 chips（若有） + 过滤后列表：随 sectorToggle 翻转重建，现读 followSectors/sectorQuery */
private fun ViewContainer<*, *>.renderSectorBody(ctx: MainTabPager, allSectors: List<Sector>, w: Float, maxAbs: Float) {
    ctx.sectorToggle // 依赖保险（确保翻转时重建）
    val query = ctx.sectorQuery.trim()
    val q = if (query.isEmpty()) "" else query

    // 展示列表：有关键词则按名称过滤；否则关注板块置顶 + 其余按涨跌序
    val followed = ctx.followSectors
    val filtered = if (q.isEmpty()) allSectors
        else allSectors.filter { it.name.contains(q, ignoreCase = true) }
    val followedList = filtered.filter { it.code in followed }
    val restList = filtered.filter { it.code !in followed }.sortedByDescending { it.changePercent }
    val shown = followedList + restList

    // 已关注板块标签行（关注板块置顶便于快速盯盘）
    if (followedList.isNotEmpty()) {
        Text {
            attr {
                text("已关注")
                fontSize(ctx.fs(12f)); color(Color(0xFF999999)); margin(left = 16f, bottom = 6f)
            }
        }
        View {
            attr {
                margin(left = 12f, right = 12f); flexDirectionColumn()
            }
            followedList.forEach { s ->
                View {
                    attr {
                        flexDirectionRow(); alignItemsCenter(); padding(12f)
                        backgroundColor(Color(0xFFFFF8E1)); borderRadius(10f); width(w)
                        marginBottom(8f)
                    }
                    event { click { ctx.openSector(s) } }
                    // 左侧：高亮关注板块
                    View {
                        attr { flex(1f); flexDirectionRow(); alignItemsCenter() }
                        Text { attr { text(s.name); fontSize(ctx.fs(16f)); fontWeightSemiBold(); color(Color(0xFF222222)); marginRight(6f) } }
                        Text { attr { text("★"); fontSize(ctx.fs(14f)); color(0xFFFFB300) } }
                    }
                    Text {
                        attr {
                            text(formatPercent(s.changePercent))
                            fontSize(ctx.fs(16f)); color(StockColor.of(s.changePercent))
                        }
                    }
                }
            }
        }
    }

    // 空结果提示
    if (shown.isEmpty()) {
        Text {
            attr {
                text("没有匹配「$q」的板块"); fontSize(ctx.fs(13f)); color(Color(0xFF999999)); marginTop(16f); marginLeft(16f)
            }
        }
    } else {
        // 非搜索且已有关注：分隔线提示下方为全部板块
        if (q.isEmpty() && followedList.isNotEmpty()) {
            Text {
                attr {
                    text("全部板块")
                    fontSize(ctx.fs(12f)); color(Color(0xFF999999)); margin(top = 14f, left = 16f, bottom = 6f)
                }
            }
        }
        shown.forEach { s -> renderSectorRow(ctx, s, w, maxAbs) }
    }
}

/** 板块涨跌概览卡：上涨板块数/下跌板块数 + 今日领涨/领跌板块，让用户一眼读懂板块整体情绪再决定看哪 */
internal fun ViewContainer<*, *>.renderSectorOverview(ctx: MainTabPager, sectors: List<Sector>, w: Float) {
    val ups = sectors.filter { it.changePercent > 0f }
    val downs = sectors.filter { it.changePercent < 0f }
    val flat = sectors.size - ups.size - downs.size
    val leaderUp = ups.maxByOrNull { it.changePercent }
    val leaderDown = downs.minByOrNull { it.changePercent }
    val barW = (w - 24f) / 2f
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(12f); width(w); flexDirectionColumn()
        }
        Text {
            attr { text("板块概览"); fontSize(ctx.fs(13f)); fontWeightSemiBold(); color(Color(0xFF222222)); marginBottom(10f) }
        }
        View {
            attr { flexDirectionRow() }
            // 上涨卡（跟随配色模式）
            View {
                attr {
                    width(barW); marginRight(8f); padding(10f); borderRadius(10f)
                    backgroundColor(Color(UserSettings.upBg())); flexDirectionColumn()
                }
                event { click { ctx.bridgeModule.toast("上涨 ${ups.size} · 平盘 ${flat} · 下跌 ${downs.size}") } }
                Text { attr { text("上涨板块"); fontSize(ctx.fs(11f)); color(Color(UserSettings.upDeep())) } }
                Text { attr { text("${ups.size}"); fontSize(ctx.fs(24f)); fontWeightSemiBold(); color(Color(UserSettings.upDeep())) } }
                Text {
                    attr {
                        text("领涨 ${leaderUp?.name ?: "--"}")
                        fontSize(ctx.fs(11f)); color(Color(UserSettings.upDeep()))
                    }
                }
            }
            // 下跌卡（跟随配色模式）
            View {
                attr {
                    width(barW); padding(10f); borderRadius(10f)
                    backgroundColor(Color(UserSettings.downBg())); flexDirectionColumn()
                }
                event { click { ctx.bridgeModule.toast("上涨 ${ups.size} · 平盘 ${flat} · 下跌 ${downs.size}") } }
                Text { attr { text("下跌板块"); fontSize(ctx.fs(11f)); color(Color(UserSettings.downDeep())) } }
                Text { attr { text("${downs.size}"); fontSize(ctx.fs(24f)); fontWeightSemiBold(); color(Color(UserSettings.downDeep())) } }
                Text {
                    attr {
                        text("领跌 ${leaderDown?.name ?: "--"}")
                        fontSize(ctx.fs(11f)); color(Color(UserSettings.downDeep()))
                    }
                }
            }
        }
    }
}

/** 板块单行：涨跌进度条 + 名称 / 成分数 + 领涨股 + 涨跌幅（红绿）。[maxAbs] 为进度条归一化基准（板块最大涨跌幅）。 */
internal fun ViewContainer<*, *>.renderSectorRow(ctx: MainTabPager, sector: Sector, w: Float, maxAbs: Float = 3f) {
    val isUp = sector.changePercent >= 0f
    val ratio = (kotlin.math.abs(sector.changePercent) / maxAbs).coerceIn(0.05f, 1f)
    val barColor = if (isUp) UserSettings.upMain() else UserSettings.downMain()
    View {
        attr {
            height(76f); flexDirectionColumn(); justifyContentCenter()
            margin(left = 12f, right = 12f, top = 8f)
            padding(left = 12f, right = 12f); backgroundColor(Color.WHITE); borderRadius(10f); width(w)
        }
        event { click { ctx.openSector(sector) } }
        // 主行：名称 + 进度条 + 涨跌幅
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            Text {
                attr {
                    text(sector.name)
                    fontSize(ctx.fs(16f)); color(Color(0xFF222222)); width(88f)
                }
            }
            // 涨跌进度条：内层色条宽度按 |chg|/maxAbs 归一
            View {
                attr {
                    flex(1f); height(6f); marginLeft(8f); marginRight(8f); borderRadius(3f)
                    backgroundColor(if (ctx.darkOn) Color(0xFF2A2B2F) else Color(0xFFF0F0F0))
                    flexDirectionRow(); alignItemsCenter()
                }
                View {
                    attr {
                        width((w - 88f - 60f - 40f) * ratio)
                        height(6f); borderRadius(3f); backgroundColor(Color(barColor))
                    }
                }
            }
            // 涨跌幅（固定宽右对齐）
            View {
                attr { width(60f); flexDirectionRow(); justifyContentFlexEnd() }
                Text {
                    attr {
                        text(formatPercent(sector.changePercent))
                        fontSize(ctx.fs(15f)); color(StockColor.of(sector.changePercent))
                    }
                }
            }
        }
        // 副行：成分数 + 领涨股（透传字段为空则隐藏该段）+ 右侧关注切换
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginTop(4f) }
            Text {
                attr {
                    text(if (sector.constituentCodes.isNotEmpty()) "${sector.constituentCodes.size} 只" else "行业板块")
                    fontSize(ctx.fs(11f)); color(Color(0xFF999999)); width(88f)
                }
            }
            if (sector.leaderName.isNotBlank()) {
                Text {
                    attr {
                        text("领涨 ${sector.leaderName}  ${formatPercent(sector.leaderChangePercent)}")
                        fontSize(ctx.fs(11f)); color(if (sector.leaderChangePercent >= 0f) Color(UserSettings.upDeep()) else Color(UserSettings.downDeep()))
                    }
                }
            }
            View { attr { flex(1f) } }
            // 关注/取消关注切换（点击星标，不进入详情）
            View {
                attr { width(30f); height(30f); justifyContentCenter(); alignItemsCenter() }
                event { click { ctx.toggleFollowSector(sector.code) } }
                Text {
                    attr {
                        text(if (ctx.followSectors.contains(sector.code)) "★" else "☆")
                        fontSize(ctx.fs(16f)); color(if (ctx.followSectors.contains(sector.code)) Color(0xFFFFB300) else Color(0xFFBBBBBB))
                    }
                }
            }
        }
    }
}

/** 个股：搜索框 + 子榜切换（涨幅/跌幅/换手率/振幅）+ 榜单列表。
 *  搜索框置于 4 个子榜 Tab 之上，子 Tab 栏与搜索框都在翻转区之外（rx 高亮 + attr 绑定，不随翻转重建、不丢焦点）；
 *  列表区随 rankToggle（切子榜）/ stockToggle（搜索）翻转重建，现读 stockRankTab / stockQuery。
 *  ⚠️ 列表需同时随两个开关重建 → 用嵌套 vif（rankToggle 内再分 stockToggle 双分支），
 *     任一开关翻转都重跑 renderRankList 闭包，读最新 stockRankTab / stockQuery。 */
private fun ViewContainer<*, *>.renderRankArea(ctx: MainTabPager) {
    renderAIPickCard(ctx)
    renderStockSearch(ctx)
    renderRankTabs(ctx)
    vif({ ctx.rankToggle }) {
        vif({ ctx.stockToggle }) { val c = this; c.renderRankList(ctx) }
        vif({ !ctx.stockToggle }) { val c = this; c.renderRankList(ctx) }
    }
    vif({ !ctx.rankToggle }) {
        vif({ ctx.stockToggle }) { val c = this; c.renderRankList(ctx) }
        vif({ !ctx.stockToggle }) { val c = this; c.renderRankList(ctx) }
    }
    View { attr { height(16f) } }
}

/** 个股子榜 Tab 栏（普通 View，非 Scroller：横向 Scroller 会吞掉子 View 的 click，导致切换无反应）
 *  与顶层「大盘/板块/个股」Tab 栏同款：flex(1f) 等分 + 蓝色文字 + 蓝色下划线指示器。
 *  含「全部」Tab(index4)：搜索时高亮切到「全部」。 */
private fun ViewContainer<*, *>.renderRankTabs(ctx: MainTabPager) {
    val tabs = listOf("涨幅榜", "跌幅榜", "换手率", "振幅", "全部")
    View {
        attr {
            flexDirectionRow(); height(40f); alignItemsCenter()
            backgroundColor(Color.WHITE); border(Border(0.5f, BorderStyle.SOLID, Color(0xFFEEEEEE)))
        }
        tabs.forEachIndexed { i, title ->
            View {
                attr {
                    flex(1f); height(40f); flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
                }
                event { click { ctx.selectRankTab(i) } }
                Text {
                    attr {
                        text(title)
                        fontSize(ctx.fs(13f))
                        // 响应式现读 stockRankTab/stockQuery，切 Tab/搜索时即时变色（勿提成局部 val，否则不随 observable 重跑）
                        val on = if (ctx.stockQuery.isNotBlank()) (i == 4) else (ctx.stockRankTab == i)
                        color(if (on) Color(ctx.themeColor) else Color(0xFF666666))
                        fontWeightSemiBold()
                    }
                }
                View {
                    attr {
                        width(20f); height(2.5f); marginTop(4f); borderRadius(1.25f)
                        val on = if (ctx.stockQuery.isNotBlank()) (i == 4) else (ctx.stockRankTab == i)
                        backgroundColor(if (on) Color(ctx.themeColor) else Color(0))
                    }
                }
            }
        }
    }
}

/** 个股榜单：优先显示新浪拉到的真实有序榜；未拉到（首帧/离线）则按当前池排序兜底 */
private fun ViewContainer<*, *>.renderRankList(ctx: MainTabPager) {
    val pool = ctx.visibleQuotes().filter { !it.isIndex }
    // 搜索中 或 选「全部」(index4) → 展示全池；否则按真实榜/本地排序展示具体榜
    val q = ctx.stockQuery.trim()
    val searching = q.isNotEmpty()
    val showAll = searching || ctx.stockRankTab == 4
    val stocks: List<Stock> = if (showAll) {
        pool
    } else {
        val real = StockData.rankOf(ctx.stockRankTab)
        if (real != null) real
        else when (ctx.stockRankTab) {
            0 -> pool.sortedByDescending { it.changePercent }                       // 涨幅榜
            1 -> pool.sortedBy { it.changePercent }                                 // 跌幅榜
            2 -> pool.sortedByDescending { it.volume }                              // 换手率（以成交量代理）
            3 -> pool.sortedByDescending { if (it.price > 0f) (it.high - it.low) / it.price else 0f } // 振幅
            else -> pool
        }
    }
    // 个股搜索：按名称或代码实时过滤（空=不过滤）；搜索时已切「全部」池
    val shown = if (searching)
        stocks.filter { it.name.contains(q, ignoreCase = true) || it.code.contains(q, ignoreCase = true) }
    else stocks
    if (shown.isEmpty()) {
        Text {
            attr {
                text(if (searching) "没有匹配「$q」的个股" else "暂无个股数据")
                fontSize(ctx.fs(13f)); color(Color(0xFF999999)); marginTop(16f); marginLeft(16f)
            }
        }
    } else {
        shown.forEach { s -> renderMarketRow(ctx, s) }
    }
}

/** 个股搜索输入框 + 清空按钮（不随 rankToggle/stockToggle 重建，attr 响应式回显/清空，保输入流畅） */
private fun ViewContainer<*, *>.renderStockSearch(ctx: MainTabPager) {
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f, bottom = 12f)
            padding(left = 12f, right = 8f); height(32f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(ctx.pagerData.pageViewWidth - 24f)
            flexDirectionRow(); alignItemsCenter()
        }
        // 搜索图标（左侧灰色，替代纯文字占位，醒目且不依赖占位符颜色）
        Text { attr { text("🔍"); fontSize(ctx.fs(14f)); color(Color(0xFF999999)); marginRight(4f) } }
        Input {
            attr {
                flex(1f); height(28f); fontSize(ctx.fs(14f)); color(Color(0xFF222222))
                text(ctx.stockQuery)
                placeholder("搜索个股"); placeholderColor(Color(0xFFBBBBBB))
            }
            event { textDidChange { ctx.onStockQueryChange(it.text) } }
        }
        // 清空按钮：query 非空时可点
        View {
            attr {
                width(28f); height(28f); justifyContentCenter(); alignItemsCenter()
                opacity(if (ctx.stockQuery.isEmpty()) 0f else 1f)
            }
            event { click { ctx.onStockQueryChange("") } }
            Text { attr { text("✕"); fontSize(ctx.fs(14f)); color(Color(0xFF999999)) } }
        }
    }
}

/** 个股页「AI 选股」入口卡片：标题/副标题 + 主题色按钮（第一行），下方一排可点击的推荐问题
 *  chips（第二行，补齐与「大盘指数」「板块概览」同等的卡片体量）。点击 chips/按钮复用现有 AI 对话页
 *  预填并提问（自由模式），不接入新的选股 LLM 能力。 */
private fun ViewContainer<*, *>.renderAIPickCard(ctx: MainTabPager) {
    val w = ctx.pagerData.pageViewWidth - 24f
    val questions = listOf("今日强势板块", "低估值蓝筹", "技术面突破股")
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f)
            padding(left = 14f, right = 12f, top = 16f, bottom = 16f)
            backgroundColor(Color.WHITE); borderRadius(12f); width(w)
            flexDirectionColumn()
        }
        // 第一行：标题 + 副标题（左） + 按钮（右）
        View {
            attr { flexDirectionRow(); alignItemsCenter() }
            View {
                attr { flex(1f); flexDirectionColumn() }
                Text {
                    attr { text("AI 选股"); fontSize(ctx.fs(15f)); fontWeightSemiBold(); color(Color(ctx.themeColor)) }
                }
                Text {
                    attr { text("智能挖掘当下潜力个股"); fontSize(ctx.fs(12f)); color(Color(0xFF999999)); marginTop(4f) }
                }
            }
            View {
                attr {
                    height(38f); paddingLeft(18f); paddingRight(18f)
                    borderRadius(19f); backgroundColor(Color(ctx.themeColor))
                    alignItemsCenter(); justifyContentCenter()
                }
                event { click { ctx.openAIPickFree(
                    "请结合当下行情帮我做一次选股：选出 3-5 只当前值得关注的个股，说明看好逻辑、所处板块与介入参考，兼顾不同风格（强势题材 / 低估值 / 趋势突破）。"
                ) } }
                Text { attr { text("开始选股"); fontSize(ctx.fs(14f)); color(Color.WHITE) } }
            }
        }
        // 第二行：推荐问题 chips（点击跳 AI 对话并预填提问）
        View {
            attr { flexDirectionRow(); marginTop(14f) }
            questions.forEach { q ->
                View {
                    attr {
                        padding(left = 10f, right = 10f, top = 6f, bottom = 6f)
                        marginRight(8f); borderRadius(14f); backgroundColor(Color(0xFFF2F3F5))
                    }
                    event { click { ctx.openAIPickFree(q) } }
                    Text { attr { text(q); fontSize(ctx.fs(12f)); color(Color(ctx.themeColor)) } }
                }
            }
        }
    }
}

/** 自选列表：随 listToggle 翻转重建；支持分组筛选 chips + 长按行移入分组 */
private fun ViewContainer<*, *>.renderWatchlist(ctx: MainTabPager) {
    vif({ ctx.watchlistCodes.isEmpty() }) {
        View {
            attr { flex(1f); alignItemsCenter(); justifyContentCenter(); flexDirectionColumn() }
            Text { attr { text("暂无自选股"); fontSize(ctx.fs(18f)); color(Color(0xFF222222)) } }
            Text { attr { text("长按行情里的股票，选「加自选」即可加入这里"); fontSize(ctx.fs(13f)); color(Color(0xFF999999)); marginTop(8f) } }
        }
    }
    vif({ ctx.watchlistCodes.isNotEmpty() }) {
        // 分组筛选 chips（全部 / 各分组 / + 新建）；分组 chip 长按重命名/删除
        View {
            attr {
                flexDirectionRow(); alignItemsCenter(); width(ctx.pagerData.pageViewWidth)
                paddingLeft(10f); paddingRight(10f); paddingBottom(6f); marginTop(6f)
            }
            // 横滑避免分组多时溢出裁掉
            // （用横向 Scroller 包一行 chips；横向 Scroller 不吞 chip 的 click/长按需验证——先直接行内排，分组多再展开）
            chatChip(ctx, "全部", ctx.watchGroupFilter.isEmpty()) {
                ctx.watchGroupFilter = ""; ctx.listToggle = !ctx.listToggle
            }
            ctx.watchGroups.forEach { g ->
                chatChip(ctx, g.name, ctx.watchGroupFilter == g.id,
                    onLongPress = { ctx.watchGroupSheetId = g.id }) {
                    ctx.watchGroupFilter = g.id; ctx.listToggle = !ctx.listToggle
                }
            }
            chatChip(ctx, "+ 新建", false) { ctx.promptWatchGroup("新建分组", "") { n -> ctx.createWatchGroup(n); ctx.watchGroupFilter = ctx.watchGroups.lastOrNull()?.id ?: ""; ctx.listToggle = !ctx.listToggle } }
        }
        vif({ ctx.watchlistStocks().isEmpty() && ctx.watchGroupFilter.isNotEmpty() }) {
            View { attr { flex(1f); alignItemsCenter(); justifyContentCenter() }
                Text { attr { text("该分组暂无股票，长按自选行选「移动到分组」即可归入"); fontSize(ctx.fs(13f)); color(Color(0xFF999999)) } }
            }
        }
        vif({ ctx.watchlistStocks().isNotEmpty() }) {
            KRStockList {
                attr { flex(1f) }
                stocks = ctx.watchlistStocks()
                onRowClick = { /* 展开/收起内部处理 */ }
                onDetailClick = { ctx.openDetail(it) }
                onRowLongPress = { stock, x, y -> ctx.openSheet(stock, x, y) }
            }
        }
    }
}

/**
 * 股票所属板块/行业（已迁移到 Sector 数据模型，由 StockData.getSectors() 提供，对齐真实板块数据）。
 */

/** 大盘指数大框：横向并列显示各指数，点击进详情 */
private fun ViewContainer<*, *>.renderIndexBox(ctx: MainTabPager, indices: List<Stock>, w: Float) {
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f)
            padding(12f); backgroundColor(Color.WHITE); borderRadius(10f); width(w)
        }
        Text { attr { text("大盘指数"); fontSize(ctx.fs(14f)); fontWeightSemiBold(); color(Color(0xFF222222)); marginBottom(10f) } }
        View {
            attr { flexDirectionRow() }
            indices.forEach { s ->
                View {
                    attr { flex(1f); flexDirectionColumn(); alignItemsCenter() }
                    event { click { ctx.openDetail(s) } }
                    Text { attr { text(s.name); fontSize(ctx.fs(13f)); color(Color(0xFF666666)) } }
                    Text { attr { text(formatPrice(s.price)); fontSize(ctx.fs(16f)); color(StockColor.text(s.changePercent)); marginTop(4f) } }
                    Text {
                        attr {
                            text(formatPercent(s.changePercent))
                            fontSize(ctx.fs(13f)); color(StockColor.of(s.changePercent)); marginTop(2f)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 市场热度卡（参照同花顺）：上涨/平/下跌家数（样本 = 当前行情池非指数标的）+ 涨跌占比条。
 * 涨红跌绿跟随配色模式。
 */
private fun ViewContainer<*, *>.renderMarketHeat(ctx: MainTabPager, w: Float) {
    val pool = ctx.visibleQuotes().filter { !it.isIndex }
    if (pool.isEmpty()) return
    val ups = pool.count { it.changePercent > 0f }
    val flats = pool.count { it.changePercent == 0f }
    val downs = pool.count { it.changePercent < 0f }
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(12f); width(w); flexDirectionColumn()
        }
        event { click { ctx.openHeatPool() } }
        // 标题行（可点 → 打开行情池明细）
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
            Text { attr { text("市场热度 · 当前池 ${pool.size} 只"); fontSize(ctx.fs(13f)); fontWeightSemiBold(); color(Color(0xFF222222)); flex(1f) } }
            Text { attr { text("查看 >"); fontSize(ctx.fs(12f)); color(Color(0xFF999999)) } }
        }
        // 样本说明小字：内存池随浏览扩充，计数会变属正常（见选项 B）
        Text { attr { text("样本随浏览扩充，非全市场数据"); fontSize(ctx.fs(11f)); color(Color(0xFF999999)); marginBottom(8f) } }
        // 三格统计：上涨 / 平盘 / 下跌
        View { attr { flexDirectionRow() }
            View {
                attr { flex(1f); flexDirectionColumn() }
                Text { attr { text("上涨"); fontSize(ctx.fs(11f)); color(Color(UserSettings.upDeep())) } }
                Text { attr { text("$ups"); fontSize(ctx.fs(26f)); fontWeightSemiBold(); color(Color(UserSettings.upDeep())) } }
            }
            View {
                attr { flex(1f); flexDirectionColumn(); alignItemsCenter() }
                Text { attr { text("平盘"); fontSize(ctx.fs(11f)); color(Color(0xFF8A8A8A)) } }
                Text { attr { text("$flats"); fontSize(ctx.fs(26f)); fontWeightSemiBold(); color(Color(0xFF555555)) } }
            }
            View {
                attr { flex(1f); flexDirectionColumn(); alignItemsFlexEnd() }
                Text { attr { text("下跌"); fontSize(ctx.fs(11f)); color(Color(UserSettings.downDeep())) } }
                Text { attr { text("$downs"); fontSize(ctx.fs(26f)); fontWeightSemiBold(); color(Color(UserSettings.downDeep())) } }
            }
        }
        // 涨跌占比条：红(涨)/灰(平)/绿(跌)，按家数比例 flex 分段
        View {
            attr {
                flexDirectionRow(); marginTop(12f); height(10f); borderRadius(5f)
                backgroundColor(Color(0xFFF0F0F0))
            }
            if (ups > 0) {
                View { attr { flex(ups.toFloat()); height(10f); backgroundColor(Color(UserSettings.upMain())) } }
            }
            if (flats > 0) {
                View { attr { flex(flats.toFloat()); height(10f); backgroundColor(Color(0xFFB4B2A9)) } }
            }
            if (downs > 0) {
                View { attr { flex(downs.toFloat()); height(10f); backgroundColor(Color(UserSettings.downMain())) } }
            }
        }
        Text {
            attr {
                text(if (StockData.isReal()) "腾讯·新浪实时行情" else "本地演示数据（联网自动切换）")
                fontSize(ctx.fs(10f)); color(Color(0xFFAAAAAA)); marginTop(6f)
            }
        }
    }
}

/**
 * 大盘领涨/领跌快览：个股与板块各取涨幅榜/跌幅榜前列（涨跌配色随设置）。
 */
private fun ViewContainer<*, *>.renderMarketLeaders(ctx: MainTabPager, w: Float) {
    val pool = ctx.visibleQuotes().filter { !it.isIndex }
    val upStocks = pool.sortedByDescending { it.changePercent }.take(2)
    val downStocks = pool.sortedBy { it.changePercent }.take(2)
    val sectors = StockData.getSectors()
    val upSectors = sectors.sortedByDescending { it.changePercent }.take(2)
    val downSectors = sectors.sortedBy { it.changePercent }.take(2)
    if (pool.isEmpty() && sectors.isEmpty()) return
    View {
        attr {
            margin(left = 12f, right = 12f, top = 12f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(12f); width(w); flexDirectionColumn()
        }
        // 领涨列
        Text { attr { text("领涨"); fontSize(ctx.fs(13f)); fontWeightSemiBold(); color(Color(UserSettings.upMain())); marginBottom(8f) } }
        upStocks.forEach { s ->
            View {
                attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                event { click { ctx.openDetail(s) } }
                Text { attr { text(s.name); fontSize(ctx.fs(13f)); color(StockColor.text(s.changePercent)); flex(1f) } }
                Text { attr { text(formatPercent(s.changePercent)); fontSize(ctx.fs(13f)); color(StockColor.text(s.changePercent)) } }
            }
        }
        upSectors.forEach { s ->
            View {
                attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                event { click { ctx.openSector(s) } }
                Text { attr { text(s.name + " 板块"); fontSize(ctx.fs(12f)); color(Color(0xFF666666)); flex(1f) } }
                Text { attr { text(formatPercent(s.changePercent)); fontSize(ctx.fs(12f)); color(StockColor.of(s.changePercent)) } }
            }
        }
        View { attr { height(1f); marginTop(10f); marginBottom(10f); backgroundColor(Color(0xFFF0F0F0)) } }
        // 领跌列
        Text { attr { text("领跌"); fontSize(ctx.fs(13f)); fontWeightSemiBold(); color(Color(UserSettings.downMain())); marginBottom(8f) } }
        downStocks.forEach { s ->
            View {
                attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                event { click { ctx.openDetail(s) } }
                Text { attr { text(s.name); fontSize(ctx.fs(13f)); color(StockColor.text(s.changePercent)); flex(1f) } }
                Text { attr { text(formatPercent(s.changePercent)); fontSize(ctx.fs(13f)); color(StockColor.text(s.changePercent)) } }
            }
        }
        downSectors.forEach { s ->
            View {
                attr { flexDirectionRow(); alignItemsCenter(); marginBottom(4f) }
                event { click { ctx.openSector(s) } }
                Text { attr { text(s.name + " 板块"); fontSize(ctx.fs(12f)); color(Color(0xFF666666)); flex(1f) } }
                Text { attr { text(formatPercent(s.changePercent)); fontSize(ctx.fs(12f)); color(StockColor.of(s.changePercent)) } }
            }
        }
    }
}

/**
 * 行情/板块详情里的单只股票行：名称+代码 / 最新价 / 涨跌幅徽章；点击进详情、长按弹菜单。
 * [nav] 为 StockNavigator（MainTabPager 与 SectorDetailPage 各自实现），解耦行渲染与具体页面。
 *
 * 「不感兴趣」新行为：被标记的股票不再从列表消失，而是整体降透明度并铺一层半透明灰白"布"（朦胧感），
 * 长按时菜单按钮变为「恢复」。这样既保留板块整体涨跌幅的连贯性，又让不感兴趣状态一目了然。
 */
internal fun ViewContainer<*, *>.renderMarketRow(nav: StockNavigator, stock: Stock) {
    val dimmed = nav.isHidden(stock.code)
    View {
        attr {
            height(64f); flexDirectionRow(); alignItemsCenter()
            padding(left = 12f, right = 12f); backgroundColor(Color.WHITE)
        }
        event {
            click { nav.openDetail(stock) }
            longPress { p -> nav.openSheet(stock, p.pageX, p.pageY) }
        }
        // 内容（不感兴趣时整体降透明度，营造朦胧感）
        View {
            attr { flex(1f); flexDirectionRow(); alignItemsCenter(); opacity(if (dimmed) 0.3f else 1f) }
            // 名称 + 代码（名称跟随涨跌配色，与价格一致）
            View {
                attr { flex(1f); flexDirectionColumn() }
                Text { attr { text(stock.name); fontSize(UserSettings.fs(16f)); color(StockColor.text(stock.changePercent)) } }
                Text { attr { text(stock.code); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginTop(4f) } }
            }
            // 最新价（右对齐；随涨跌染色，涨红跌绿，平/其他保持中性黑）
            View {
                attr { flex(1f); flexDirectionRow(); justifyContentFlexEnd() }
                Text {
                    attr {
                        text(formatPrice(stock.price))
                        fontSize(UserSettings.fs(16f))
                        color(StockColor.text(stock.changePercent))
                    }
                }
            }
            // 涨跌幅徽章
            KRStockBadge { attr { changePercent = stock.changePercent } }
        }
        // 朦胧"布"：不感兴趣时铺一层半透明灰白覆盖（不再消失，仅淡化）
        vif({ dimmed }) {
            View {
                attr {
                    absolutePositionAllZero()
                    backgroundColor(Color(0x99F2F3F5))
                    flexDirectionRow(); alignItemsCenter(); justifyContentCenter()
                }
                event {
                    click { nav.openDetail(stock) }
                    longPress { p -> nav.openSheet(stock, p.pageX, p.pageY) }
                }
                Text { attr { text("不感兴趣"); fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)) } }
            }
        }
    }
}

/**
 * 「我的」页的隐藏股票入口行：点击跳转到 HiddenStocks 页集中管理。
 * 列表本身不再铺在「我的」页（避免设置页过长），这里只展示数量与跳转箭头。
 * 数量随 mineToggle 翻转同步（本版本 body 不随 observable 重跑）。
 */
private fun ViewContainer<*, *>.renderHiddenEntry(ctx: MainTabPager, contentW: Float) {
    ctx.mineToggle // 依赖保险
    val n = ctx.hiddenMap.size
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginTop(10f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        event {
            click {
                ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME)
                    .openPage("HiddenStocks", JSONObject())
            }
        }
        Text { attr { text("不感兴趣的股票"); fontSize(ctx.fs(15f)); color(Color(0xFF222222)); flex(1f) } }
        Text {
            attr {
                text(if (n == 0) "无" else "$n 只")
                fontSize(ctx.fs(14f)); color(Color(0xFF999999)); marginRight(8f)
            }
        }
        Text { attr { text(">"); fontSize(ctx.fs(16f)); color(Color(0xFFCCCCCC)) } }
    }
}

/**
 * 通用「设置行」：标题 + 描述 + 右侧箭头，点击执行 onClick。
 * 用于「我的 → 个性化设置」跳转到独立的设置页（行情展开组件 / 外观与个性化）。
 * 文件级扩展函数（与 renderHiddenEntry 同款约定），字体随个性化缩放。
 */
private fun ViewContainer<*, *>.renderSettingRow(
    ctx: MainTabPager,
    contentW: Float,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginTop(10f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        event { click { onClick() } }
        View {
            attr { flex(1f); flexDirectionColumn() }
            Text { attr { text(title); fontSize(ctx.fs(15f)); color(Color(0xFF222222)) } }
            Text {
                attr {
                    text(subtitle)
                    fontSize(ctx.fs(12f)); color(Color(0xFF999999)); marginTop(4f)
                }
            }
        }
        Text { attr { text(">"); fontSize(ctx.fs(16f)); color(Color(0xFFCCCCCC)) } }
    }
}

