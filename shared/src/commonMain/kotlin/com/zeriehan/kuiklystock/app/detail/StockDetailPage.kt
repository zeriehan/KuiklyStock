package com.zeriehan.kuiklystock.app.detail

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.compose.Button
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockBrief
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.UserStockStore
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.llm.AIAnalysisStore
import com.zeriehan.kuiklystock.core.llm.DataSync
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

    /**
     * 可选模块（默认勾选全部）。
     * ⚠️ 原还有 FUND(资金流向)/NEWS(相关新闻) 两项，但它们只有「（待接入）」空卡、且项目
     *    无对应数据源；芯片却照常提供，用户点开只能看到空卡——属于"宣传了不存在的功能"。
     *    已移除：与其展示空壳，不如只保留真正有内容的模块（必要时实现数据源后再加回）。
     */
    private enum class DModule { AI, PROFILE, FINANCE }
    private val moduleLabels = mapOf(
        DModule.AI to "AI分析",
        DModule.PROFILE to "简况",
        // 原「财务」（营收/净利/ROE 无真实数据源），改为展示真实盘口指标，故标签同步改名为「盘口」
        DModule.FINANCE to "盘口",
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
    /** AI 结论：风险等级 + 操作建议（从 aiText 顶部【AI观点】行解析；解析不到为 null → 不显示标签，仅显示正文） */
    private var aiVerdict: AiVerdict? by observable(null)
    /** 去掉【AI观点】结论行后的正文（供卡片正文展示；aiText 保留全文用于复制） */
    private var aiBody: String by observable("")
    /** 自选集合（响应式镜像）与「加自选」按钮刷新触发器 */
    internal var watchlistCodes: Set<String> by observable(emptySet())
    private var watchUIVersion: Boolean by observable(false)
    /** 当前股票代码（params 读一次存字段，供 DataSync 回查最新报价） */
    private var curCode: String = ""
    /** 最新报价快照（observable）：行情刷新(DataSync)后更新，驱动 名称/现价/涨跌幅 顶栏与大字价实时跟上真实价 */
    internal var liveStock: Stock? by observable(null)
    /** 真实日K 行情源未返回（北交所/超新股）：详情页日K 卡片诚实占位而非显示本地假波浪 */
    internal var klineMissing: Boolean by observable(false)

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
                val (v, body) = parseAiVerdict(it.text)
                aiVerdict = v
                aiBody = body
                return
            }
        }
        aiLoading = true
        // 仅行情源不支持的市场无可信历史K线（传空列表）；新股/北交所现传真实日K给模型
        val klineForAI = if (isUnsafeStock(stock)) emptyList() else StockData.getKLine(stock, "日")
        LLM.client.analyze(stock, klineForAI) { text ->
            val ts = Utils.currentBridgeModule().currentTimeStamp()
            val tText = if (ts > 0) Utils.currentBridgeModule().dateFormatter(ts, "MM-dd HH:mm") else ""
            aiText = text
            aiTimeText = tText
            val (v, body) = parseAiVerdict(text)
            aiVerdict = v
            aiBody = body
            aiLoading = false
            AIAnalysisStore.put(code, text, tText)
        }
    }

    /** 用户点击圆形「重试」按钮时触发：强制重新分析 */
    private fun reanalyze(stock: Stock, code: String) {
        runAnalysis(stock, code, force = true)
    }

    /** 复制 AI 分析全文到剪贴板，并提示 */
    private fun copyAi() {
        if (aiText.isBlank()) return
        bridgeModule.copyToPasteboard(aiText)
        bridgeModule.toast("已复制")
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        watchlistCodes = UserStockStore.loadWatchlist(acquireModule(SharedPreferencesModule.MODULE_NAME))
        curCode = pageData.params.optString("stockCode")
        liveStock = if (curCode.isNotBlank()) StockData.findByCode(curCode) else null
        // 行情(报价)刷新到达时，把顶栏/大字价同步到最新真实价，避免显示过期 mock 价而与真实分时/K线不一致
        DataSync.addListener { if (curCode.isNotBlank()) liveStock = StockData.findByCode(curCode) }
        // 首屏拉真实K线/分时数据并缓存（到达后详情页图自动刷新成真实数据；失败维持本地兜底）
        val c = curCode
        if (c.isNotBlank()) {
            val st = StockData.findByCode(c)
            // 仅「行情源不支持的市场」(老三板/退市等 secid 为空)跳过真实拉取；
            // 新股/北交所现已接入真实源，直接拉真实分时+日K（回调已加固，异常自动回退本地，不会崩）。
            if (!isUnsafeStock(st)) {
                StockData.loadTrends(st) {
                    if (selectedPeriod == 0) chartRef?.view?.let { ch ->
                        ch.timeSharing = StockData.getIntraday(st)
                        ch.bars = emptyList()
                        ch.refPrice = StockData.intradayRefPrice(st)
                        ch.resetToLatest()
                    }
                }
                StockData.loadKline(st, "日", 80) { klineMissing = StockData.isKlineMissing(st.code) }
            }
        }
    }

    /** 切换自选（加/取消），落盘 + 翻转 watchUIVersion 刷新按钮态 + 提示 */
    internal fun toggleWatch(code: String) {
        watchlistCodes = if (watchlistCodes.contains(code)) watchlistCodes - code else watchlistCodes + code
        UserStockStore.saveWatchlist(acquireModule(SharedPreferencesModule.MODULE_NAME), watchlistCodes)
        watchUIVersion = !watchUIVersion
        bridgeModule.toast(if (watchlistCodes.contains(code)) "已加入自选" else "已取消自选")
    }

    /** 按周期切换 K线图数据 / 分时数据，并同步当前指标与十字光标清理 */
    private fun applyPeriod(stock: Stock, i: Int) {
        // 仅行情源不支持的市场（老三板/退市等）才停留在本地合成数据；新股/北交所已接真实源
        if (isUnsafeStock(stock)) {
            chartRef?.view?.let { chart ->
                chart.clearCrosshair()
                chart.resetToLatest()
                chart.bars = emptyList()
                chart.timeSharing = StockData.getIntraday(stock)
                chart.refPrice = StockData.intradayRefPrice(stock)
                chart.indicator = KRKLineChart.IND_NONE
            }
            return
        }
        chartRef?.view?.let { chart ->
            chart.clearCrosshair()
            // 换周期 / 换股 = 重新看这只票：清掉「用户已滑动」标记，允许重新定位到最新一根
            chart.resetToLatest()
            if (i == 0) {
                // 分时模式：下发分时数据 + 昨收基准，清空 K线，指标置空
                chart.bars = emptyList()
                chart.timeSharing = StockData.getIntraday(stock)
                chart.refPrice = StockData.intradayRefPrice(stock)
                chart.indicator = KRKLineChart.IND_NONE
            } else {
                // K线模式：下发对应周期（含已加载历史根数），清空分时，恢复所选指标
                chart.timeSharing = emptyList()
                chart.bars = StockData.getKLine(stock, periods[i], klineCount)
                chart.indicator = selectedIndicator
            }
        }
        // 拉到真实K线/分时后自动刷新当前视图（无网络则维持上方兜底数据，不阻塞）
        loadRealAndRefresh(stock, i)
    }

    /** 异步拉取真实数据（分时 i=0 / 该周期K线 i>0），到达后更新当前图 */
    private fun loadRealAndRefresh(stock: Stock, i: Int) {
        // 仅「行情源不支持的市场」跳过原生桥；新股/北交所现已接入真实源，直接拉（回调加固不会崩）
        if (isUnsafeStock(stock)) return
        if (i == 0) {
            StockData.loadTrends(stock) {
                if (selectedPeriod == 0) chartRef?.view?.let { ch ->
                    ch.timeSharing = StockData.getIntraday(stock)
                    ch.bars = emptyList()
                    ch.refPrice = StockData.intradayRefPrice(stock)
                    ch.resetToLatest()
                }
            }
        } else {
            val period = periods[i]
            StockData.loadKline(stock, period, klineCount) {
                klineMissing = StockData.isKlineMissing(stock.code)
                if (selectedPeriod == i) chartRef?.view?.let { ch ->
                    ch.bars = StockData.getKLine(stock, period, klineCount)
                    ch.resetToLatest()
                }
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

    /**
     * 判断该股票是否「行情源不支持 / 数据不可靠」，需跳过原生桥、用本地合成走势兜底：
     * - 数值字段退化(NaN/Infinity) → 图表/桥可能异常，走兜底；
     * - 老三板/退市/新三板等 secidOf 返回空 → 由 isUnsupportedExchange 判定为不支持 → 自动兜底。
     * 注：新股(N开头)与北交所(bj)现已接入真实源，不再在此强制 mock（回调已加固，异常自动回退不崩）。
     */
    private fun isUnsafeStock(stock: Stock): Boolean {
        if (isUnsupportedExchange(stock)) return true
        if (!stock.price.isFinite() || !stock.change.isFinite() || !stock.changePercent.isFinite()) return true
        if (!StockData.intradayRefPrice(stock).isFinite()) return true
        if (StockData.getIntraday(stock).any { !it.price.isFinite() || !it.avg.isFinite() }) return true
        if (StockData.getKLine(stock, "日").any { !it.close.isFinite() || !it.open.isFinite() || !it.high.isFinite() || !it.low.isFinite() }) return true
        return false
    }

    /**
     * 行情源(腾讯/东财)不支持的市场：以「secidOf 能否给出有效 secid」判定。
     * secidOf 对北交所已返回 bj. 前缀(真实可达)；唯有老三板/退市/新三板等返回空 secid → 判为不支持，
     * 自动用本地合成走势兜底。这样未来新增任何无法映射的市场代码都不会漏网导致闪退。
     */
    private fun isUnsupportedExchange(stock: Stock): Boolean {
        return StockData.secidOf(stock).isEmpty()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val code = pageData.params.optString("stockCode")
        val stock = StockData.findByCode(code)
        // 行情源不支持的市场（老三板/退市等）用本地合成走势 + 横幅说明；新股/北交所走真实源。
        val unsafe = ctx.isUnsafeStock(stock)
        // 触发 AI 分析：首次进入自动分析；若已有缓存（同一股票再次进入）则直接展示缓存结果 + 时间
        ctx.runAnalysis(stock, code, false)
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(Color(0xFFF2F3F5))
            }

            try {
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
                Text {
                    attr {
                        val ls = ctx.liveStock ?: stock
                        text(ls.name)
                        fontSize(17f); color(StockColor.text(ls.changePercent)); fontWeightSemisolid(); marginLeft(8f)
                    }
                }
                Text { attr { text(stock.code); fontSize(12f); color(Color(0xFF999999)); marginLeft(8f) } }
                View { attr { flex(1f) } }
                // 加自选按钮（随 watchUIVersion 翻转刷新选中态；body 不随 observable 重跑，故需翻转）
                vif({ ctx.watchUIVersion }) { val c = this; c.renderWatchButton(ctx, code) }
                vif({ !ctx.watchUIVersion }) { val c = this; c.renderWatchButton(ctx, code) }
            }

            // ===== 滚动内容 =====
            Scroller {
                attr { flex(1f); flexDirectionColumn() }

                // 实时价（价格与涨跌幅同处一行；读 liveStock 以在报价刷新后同步真实价）
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); padding(16f); backgroundColor(Color.WHITE) }
                    Text {
                        attr {
                            val ls = ctx.liveStock ?: stock
                            text(formatPrice(ls.price))
                            fontSize(30f)
                            fontWeightSemisolid()
                            color(StockColor.text(ls.changePercent))
                        }
                    }
                    Text {
                        attr {
                            val ls = ctx.liveStock ?: stock
                            text(formatPrice(ls.change) + "  " + formatPercent(ls.changePercent))
                            fontSize(14f)
                            color(StockColor.of(ls.changePercent))
                            marginLeft(10f)
                        }
                    }
                }

                // 行情源不支持的市场（老三板/退市等）说明横幅：下方走势为本地模拟示意，非真实数据
                vif({ unsafe }) {
                    View {
                        attr { margin(12f); padding(10f, 12f); backgroundColor(Color(0xFFFDF6E3)); borderRadius(10f) }
                        Text { attr { text("该股票当前行情源暂不支持（老三板 / 退市等）：以下走势为本地模拟示意，非真实数据"); fontSize(12f); color(Color(0xFF8A6D3B)) } }
                    }
                }

                // K线卡片
                View {
                    attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                    Text { attr { text(if (unsafe) "分时走势（模拟）" else "K线"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                    // 周期切换（分时/日/周/月/年 可点击；高亮随 selectedPeriod 响应式刷新，图表数据同步切换）
                    // 新股不展示周期/指标切换（仅模拟分时）
                    vif({ !unsafe }) {
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
                    }
                    // 指标切换（仅 K线模式可用：主图 / MACD / RSI / BOLL）
                    vif({ !unsafe }) {
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
                    }
                    // 图表区（横向滚动；分时 / K线 自适配；触摸拖动十字光标、双指缩放、滚到最左加载更多）
                    // 日K 模式且真实源未返回（北交所/超新股）→ 诚实占位，不显示本地假波浪冒充真实历史
                    vif({ !(ctx.selectedPeriod != 0 && ctx.klineMissing) }) {
                    KRKLineChart {
                        ref { ctx.chartRef = it }
                        attr { marginTop(8f) }
                        timeSharing = StockData.getIntraday(stock)
                        refPrice = StockData.intradayRefPrice(stock)
                        onLoadMore = {
                            val added = 40
                            ctx.klineCount += added
                            if (ctx.selectedPeriod != 0) {
                                ctx.chartRef?.view?.let { ch ->
                                    // 先登记「左侧将插入 added 根历史」，等宽度更新后补偿 offset：
                                    // 用户视野停在同一根 K线上，不会被弹回最新一根。
                                    ch.notifyPrepend(added)
                                    ch.bars = StockData.getKLine(stock, ctx.periods[ctx.selectedPeriod], ctx.klineCount)
                                }
                            }
                        }
                        // 「就这点问」：十字光标选点后跳转到该股的 AI 聊天页，自动发送针对这根K线/分时点的追问
                        onAsk = { label, price, chg, isTime ->
                            val periodName = if (isTime) "分时" else ctx.periods[ctx.selectedPeriod] + "K"
                            val follow = "关于 ${stock.name}(${stock.code}) 在 $label 的$periodName" +
                                "（${if (isTime) "价" else "收"} ${formatPrice(price)}，涨跌 ${formatPercent(chg)}），" +
                                "这一时刻为什么这么走？帮我分析一下当时的走势逻辑。"
                            val data = JSONObject()
                            data.put("stockCode", stock.code)
                            data.put("prompt", follow)
                            ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", data)
                        }
                    }
                    }
                    vif({ ctx.selectedPeriod != 0 && ctx.klineMissing }) {
                        View {
                            attr { height(300f); marginTop(8f); justifyContentCenter(); alignItemsCenter() }
                            Text {
                                attr {
                                    text("该股票暂无历史日K（当前免费行情源未提供北交所 / 新股历史K线）\n分时与实时报价为真实数据")
                                    fontSize(13f); color(Color(0xFF999999)); textAlignCenter()
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
                            // 复制全文按钮（分析完成有内容时才显示）
                            vif({ ctx.aiText.isNotEmpty() }) {
                                View {
                                    attr {
                                        height(24f); paddingLeft(10f); paddingRight(10f); borderRadius(12f)
                                        justifyContentCenter(); alignItemsCenter(); marginRight(8f)
                                        backgroundColor(Color(0xFFF2F3F5))
                                    }
                                    event { click { ctx.copyAi() } }
                                    Text { attr { text("复制"); fontSize(12f); color(Color(0xFF666666)) } }
                                }
                            }
                            KRRefreshButton({ ctx.aiLoading }) { ctx.reanalyze(stock, code) }
                        }
                        // 量化结论：两个醒目的"徽章按钮"（纯展示，不可点），在本行居中
                        // 语义：风险 = 按左格那个操作去做的风险（"操作风险"），而非整只股票的笼统风险
                        vif({ ctx.aiVerdict != null }) {
                            val v = ctx.aiVerdict
                            if (v != null) {
                                View {
                                    attr { flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); marginTop(10f) }
                                    val isBuy = v.action == "买入"
                                    val isSell = v.action == "卖出"
                                    // 左格：操作建议（大字动作）
                                    val actionColor = if (isBuy) Color(0xFFE54D42)
                                        else if (isSell) Color(0xFF1ABE5B)
                                        else Color(0xFF8A8A8A)
                                    verdictBadge(v.action, actionColor, caption = "操作建议")
                                    // 右格：该操作的风险（低绿/中橙/高红）
                                    val riskColor = when (v.risk) {
                                        "低风险" -> Color(0xFF1ABE5B)
                                        "高风险" -> Color(0xFFE54D42)
                                        else -> Color(0xFFFF9800)
                                    }
                                    verdictBadge(v.risk, riskColor, caption = "操作风险")
                                }
                            }
                        }
                        // 长按需弹出原生可选中文本对话框（选取文字 / 部分复制）；Kuikly Text 本身不支持文字选中
                        View {
                            attr { marginTop(8f) }
                            event { longPress { if (ctx.aiText.isNotBlank()) ctx.bridgeModule.showSelectableText("AI 分析", ctx.aiText) } }
                            Text {
                                attr {
                                    text(if (ctx.aiText.isEmpty()) "AI 分析中…" else ctx.aiBody)
                                    fontSize(13f); color(Color(0xFF555555))
                                }
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
                            Text { attr { text(StockBrief.industry(stock.name)); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("总市值"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            // ⚠️ 一律走 StockBrief：此前这里写死 price*12.56，与列表展开行的
                            //    codeSum 口径不一致，同一只股票两处显示两个不同市值。
                            Text { attr { text(StockBrief.marketCap(code) + " 亿"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("市盈率TTM"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text(StockBrief.pe(code)); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("换手率"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text(StockBrief.turnover(code)); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                    }
                }

                // 盘口卡片（真实行情指标；原「财务」为无数据源的写死常量，已替换）
                vif({ ctx.modules.contains(DModule.FINANCE) }) {
                    View {
                        attr { margin(12f); padding(12f); backgroundColor(Color.WHITE); borderRadius(12f) }
                        // ⚠️ 原「财务」卡的营收/净利(1478亿/747亿)、ROE(34.6%) 是写死常量——
                        // 每只股票都显示同一组茅台的数字；而项目根本没有基本面数据源，
                        // 再编一套派生值仍是假数据。改为展示行情里**真实存在**的盘口指标。
                        Text { attr { text("盘口"); fontSize(14f); fontWeightSemisolid(); color(Color(0xFF222222)) } }
                        // 振幅 = (最高 - 最低) / 昨收，昨收由「现价 - 涨跌额」还原，除零兜底
                        val preClose = stock.price - stock.change
                        val amplitude = if (preClose > 0f) (stock.high - stock.low) / preClose * 100f else 0f
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("最高 / 最低"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text(formatPrice(stock.high) + " / " + formatPrice(stock.low)); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("振幅"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text(formatPrice(amplitude) + "%"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                        View { attr { flexDirectionRow(); marginTop(8f) }
                            Text { attr { text("成交量"); fontSize(12f); color(Color(0xFF999999)); flex(1f) } }
                            Text { attr { text(formatPrice(stock.volume) + " 万手"); fontSize(14f); color(Color(0xFF222222)) } }
                        }
                    }
                }

                View { attr { height(16f) } }
            }
            } catch (e: Throwable) {
                // 兜底：任何渲染期异常（如极端退化数据）都不再闪退，改为展示可读错误，便于排查
                View {
                    attr { margin(16f); padding(16f); backgroundColor(Color.WHITE); borderRadius(12f) }
                    Text {
                        attr {
                            text("该股票数据异常，暂时无法展示详情：\n${e.message ?: e.toString()}")
                            fontSize(14f); color(Color(0xFFE54D42)); lineHeight(20f)
                        }
                    }
                }
            }
        }
    }
}

/** 详情页「加自选」按钮（文件级：在 vif 翻转闭包内调用，须为文件级扩展函数）。
 *  随 watchUIVersion 翻转重建以刷新选中态（body 不随 observable 重跑）。 */
internal fun ViewContainer<*, *>.renderWatchButton(ctx: StockDetailPage, code: String) {
    val watched = ctx.watchlistCodes.contains(code)
    View {
        attr {
            height(32f); paddingLeft(14f); paddingRight(14f); borderRadius(16f)
            backgroundColor(if (watched) Color(0xFFFFF4E0) else Color(0xFF23D3FD))
            flexDirectionRow(); alignItemsCenter(); justifyContentCenter(); marginRight(8f)
        }
        event { click { ctx.toggleWatch(code) } }
        Text {
            attr {
                text(if (watched) "★ 已自选" else "☆ 加自选")
                fontSize(13f)
                color(if (watched) Color(0xFFE58A00) else Color.WHITE)
            }
        }
    }
}

/** AI 结构化结论：风险档(低/中/高) + 操作档(买入/持有/卖出) */
internal data class AiVerdict(val risk: String, val action: String)

/**
 * 从 AI 分析全文解析顶部【AI观点】行 → 结论 + 剥离该行后的正文。
 * 兼容模型输出的轻微格式差异：风险取 低/中/高风险 归一为 低/中/高，操作取 买入/加仓→买入、
 * 卖出/减持→卖出、持有/观望→持有。解析不到(老缓存/模型没按格式) → 返回 (null, 全文) 不崩，UI 仅显示正文。
 */
internal fun parseAiVerdict(text: String): Pair<AiVerdict?, String> {
    if (text.isBlank()) return null to text
    // 找到「风险：...」与「操作建议：...」(或「操作:」)，无论是否带【AI观点】前缀
    val riskRegex = Regex("风险[：:](\\s*[高中低]\\s*(?:风险)?)")
    val actionRegex = Regex("操作建议?[：:](\\s*(?:买入|加仓|持有|观望|减持|卖出))")
    val riskM = riskRegex.find(text)
    val actionM = actionRegex.find(text)
    if (riskM == null || actionM == null) return null to text
    val rawRisk = riskM.groupValues[1].trim()
    val rawAction = actionM.groupValues[1].trim()
    val risk = when {
        rawRisk.contains("高") -> "高风险"
        rawRisk.contains("低") -> "低风险"
        else -> "中风险"
    }
    val action = when {
        rawAction.contains("买入") || rawAction.contains("加仓") -> "买入"
        rawAction.contains("卖出") || rawAction.contains("减持") -> "卖出"
        else -> "持有"
    }
    // 剥离整条【AI观点】结论行：从"风险"所在那一行行首 到 该行行尾(含换行)，用于正文展示
    val lineStart = text.lastIndexOf('\n', riskM.range.first).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', actionM.range.last).let { if (it < 0) text.length else it + 1 }
    val body = (text.substring(0, lineStart).trimEnd() + "\n" + text.substring(lineEnd)).trimStart('\n').trim()
    return AiVerdict(risk, action) to body
}

/**
 * 渲染一个醒目的"徽章按钮"（纯展示、不可点）：
 * 实色大胶囊(圆角矩形) + 白粗字，形似按钮。内含两行：上一行 10px 半透白小标签(caption)，
 * 下一行 18px 粗白大字(value)。
 */
private fun ViewContainer<*, *>.verdictBadge(value: String, color: Color, caption: String) {
    View {
        attr {
            flexDirectionColumn(); alignItemsCenter(); justifyContentCenter()
            marginRight(12f)
            height(46f)
            padding(left = 18f, right = 18f)
            borderRadius(10f)
            backgroundColor(color)
        }
        Text {
            attr { text(caption); fontSize(9f); color(Color(0xCCFFFFFF)) }
        }
        Text {
            attr { text(value); fontSize(17f); fontWeightSemisolid(); color(Color.WHITE); marginTop(1f) }
        }
    }
}

