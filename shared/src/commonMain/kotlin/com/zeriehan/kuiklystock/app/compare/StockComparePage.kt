package com.zeriehan.kuiklystock.app.compare

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.components.KRMiniTimeSharing.KRMiniTimeSharing
import com.zeriehan.kuiklystock.components.KRMarkdown.renderMarkdown
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

    companion object {
        const val KEY_COMPARE = "kb_compare_codes"
    }

    /** 当前参与对比的股票 code 列表（有序） */
    internal var compareCodes: List<String> by observable(emptyList())
    /** vif 翻转触发器：代码列表变化后强制内容区重建 */
    internal var uiToggle: Boolean by observable(false)
    /** 上区横向当前页（圆点指示） */
    internal var currentPage: Int by observable(0)
    /** 每只股票独立的迷你走势周期（"intraday" 分时 / "day" 日K），默认分时 */
    internal var comparePeriods: Map<String, String> by observable(emptyMap())
    /** 下区对比聊天消息列表（内存，不持久化） */
    internal var chatMessages: List<CompareChatMsg> by observable(emptyList())
    /** 输入框文本 */
    internal var cmpInput: String by observable("")
    /** 是否正在等 AI 回复 */
    internal var cmpWaiting: Boolean by observable(false)
    /** 聊天区域 vif 重建触发器（消息变更/等待态变更时翻转以更新列表） */
    internal var chatToggle: Boolean by observable(false)
    internal lateinit var cmpInputRef: ViewRef<InputView>
    /** 键盘实测高度（inputFocus 兜底用——keyboardHeightChange 偶发迟到/漏回调时，用上次实测高度顶上） */
    internal var lastKeyboardH: Float by observable(0f)
    /** 键盘当前抬起高度，驱动输入栏后的占位 Spacer 把输入栏顶到键盘上沿 */
    internal var keyboardH: Float by observable(0f)

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 对比股以持久化为唯一真相源：优先读上次保存的对比股（用户在选股页设好 + 应用选股后已落盘），
        // 无记录时 loadSavedCompare 内部兜底为默认 茅台/五粮液。入口 openCompare 已不再传默认 stocks 覆盖它。
        compareCodes = loadSavedCompare()
        // 每只股默认迷你走势周期为「分时」
        comparePeriods = compareCodes.associateWith { "intraday" }
        // 拉各股真实行情/分时/K线(各周期)，保证对比数据真
        compareCodes.forEach { code ->
            val st = StockData.findByCode(code)
            if (!st.isIndex) {
                StockData.loadTrends(st) { uiToggle = !uiToggle }
                StockData.loadKline(st, "日", 80) { uiToggle = !uiToggle }
                StockData.loadKline(st, "周", 60) { uiToggle = !uiToggle }
                StockData.loadKline(st, "月", 60) { uiToggle = !uiToggle }
                StockData.loadKline(st, "年", 60) { uiToggle = !uiToggle }
            }
        }
        uiToggle = !uiToggle
    }

    /** 页面重新出现（从选股页「完成」返回 / 从个股详情返回 / 重进前台）时自动重读持久化的对比股并应用——
     *  实现"选股页完成即自动生效"，无需在对比页手动点「应用选股」。 */
    override fun pageDidAppear() {
        super.pageDidAppear()
        // 读持久化最新对比股；若与当前不同则应用（幂等，避免每次 appear 都重复重建）
        val saved = loadSavedCompare()
        if (saved != compareCodes && saved.isNotEmpty()) {
            compareCodes = saved
            comparePeriods = saved.associateWith { "intraday" }
            // 对新列表补拉各周期数据
            saved.forEach { code ->
                val st = StockData.findByCode(code)
                if (!st.isIndex) {
                    StockData.loadTrends(st) { uiToggle = !uiToggle }
                    StockData.loadKline(st, "日", 80) { uiToggle = !uiToggle }
                    StockData.loadKline(st, "周", 60) { uiToggle = !uiToggle }
                    StockData.loadKline(st, "月", 60) { uiToggle = !uiToggle }
                    StockData.loadKline(st, "年", 60) { uiToggle = !uiToggle }
                }
            }
            uiToggle = !uiToggle
            bridgeModule.toast("已应用对比股")
        }
    }

    /** 切换某股迷你走势周期（"intraday" 分时 / "day" 日K）。 */
    internal fun setPeriod(code: String, period: String) {
        comparePeriods = comparePeriods.toMutableMap().apply { put(code, period) }
        // body 不随 observable 重跑，触发重建以交换图表组件（KRMiniTimeSharing ↔ KRTrendChart）
        uiToggle = !uiToggle
    }

    /** 持久化对比股列表（SharedPreferences），用户设置后重进仍保留。 */
    private fun saveCompare() {
        acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME)
            .setItem(KEY_COMPARE, compareCodes.joinToString(","))
    }

    /** 读取上次持久化的对比股；无则默认 茅台/五粮液。 */
    private fun loadSavedCompare(): List<String> {
        val raw = acquireModule<SharedPreferencesModule>(SharedPreferencesModule.MODULE_NAME).getItem(KEY_COMPARE)
        val list = if (raw != null) raw.split(",").map { it.trim() }.filter { it.isNotBlank() } else emptyList()
        return if (list.isNotEmpty()) list else listOf("600519", "000858")
    }

    /** 打开选股页：通过 pageData 把当前对比 codes 直接传给 picker，不依赖单例（避免单例残留/丢值的隐患）。 */
    internal fun openComparePicker(replaceIndex: Int) {
        ComparePicker.pendingReplaceIndex = replaceIndex
        val d = JSONObject()
        d.put("initialCodes", compareCodes.joinToString(","))
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockPicker", d)
    }

    /** 从选股页返回时手动应用新列表：读单例 pendingCodes 应用。Kuikly 暂无可靠的"子页 close → 父页自动 resume"，提供手动按钮兜底。 */
    internal fun applyPendingPicker() {
        val newCodes = ComparePicker.pendingCodes ?: run {
            bridgeModule.toast("暂无新的选股结果")
            return
        }
        if (newCodes.isEmpty()) {
            bridgeModule.toast("对比股列表为空")
            return
        }
        compareCodes = newCodes
        comparePeriods = newCodes.associateWith { "intraday" }
        saveCompare()  // 持久化，重进对比页仍保留用户选的对比股
        // 对新列表里没拉过的 code 补拉分时 + 各周期K线（保证切 日/周/月/年K 都有数据，迷你图正确变换）
        newCodes.forEach { code ->
            val st = StockData.findByCode(code)
            if (!st.isIndex) {
                StockData.loadTrends(st) { uiToggle = !uiToggle }
                StockData.loadKline(st, "日", 80) { uiToggle = !uiToggle }
                StockData.loadKline(st, "周", 60) { uiToggle = !uiToggle }
                StockData.loadKline(st, "月", 60) { uiToggle = !uiToggle }
                StockData.loadKline(st, "年", 60) { uiToggle = !uiToggle }
            }
        }
        ComparePicker.clear()
        uiToggle = !uiToggle
        bridgeModule.toast("已更新对比股")
    }

    /** 发送一条对比问题：构造 prompt（当前对比股 + 实时价/K线摘要 + 用户问题），bridge 调 GLM，
     *  回填到消息列表；不等/失败则本地兜底。 */
    internal fun sendCompareAsk() {
        val q = cmpInput.trim()
        if (q.isBlank() || cmpWaiting) return
        cmpInput = ""
        cmpInputRef.view?.setText("")
        sendCompareQuestion(q)
    }

    /** 发送一条对比问题并请求 AI（引导 chips / 手动输入共用；cmpWaiting 时忽略）。 */
    internal fun sendCompareQuestion(q: String) {
        if (q.isBlank() || cmpWaiting) return
        chatMessages = chatMessages + CompareChatMsg(role = "user", text = q)
        chatToggle = !chatToggle
        cmpWaiting = true
        val prompt = buildComparePrompt(q)
        bridgeModule.llmAnalyze(prompt, stream = false, sid = "") { resp ->
            val text = resp?.optString("text").orEmpty()
            val reply = if (text.isBlank()) "（AI 未返回，可能是限流或无 Key。请稍后重试。）" else text
            chatMessages = chatMessages + CompareChatMsg(role = "assistant", text = reply)
            cmpWaiting = false
            chatToggle = !chatToggle
        }
    }

    /** 构造对比 prompt：列出当前对比股的名称/代码/实时价/涨跌/近 N 日 K 线摘要 + 用户问题 */
    private fun buildComparePrompt(question: String): String {
        val sb = StringBuilder("你是一名资深证券分析师。用户正在进行多股对比分析，请基于以下对比股数据专业、客观地回答用户问题。\n\n")
        sb.append("【对比股数据】\n")
        compareCodes.forEachIndexed { i, code ->
            val st = StockData.findByCode(code)
            sb.append("${i + 1}. ${st.name}($code)\n")
            sb.append("   实时价：${formatPrice(st.price)}  涨跌：${formatPercent(st.changePercent)}\n")
            val bars = StockData.getKLine(st, "日", 20)
            if (bars.isNotEmpty()) {
                val closes = bars.map { it.close }
                val periodChg = if (closes.size >= 2 && closes.first() > 0f)
                    (closes.last() - closes.first()) / closes.first() * 100f else 0f
                sb.append("   近 ${bars.size} 日涨跌幅：${signChg(periodChg)}%；近期收盘：${closes.takeLast(20).joinToString(", ") { formatPrice(it) }}\n")
                val hi = bars.maxOfOrNull { it.high } ?: 0f
                val lo = bars.minOfOrNull { it.low } ?: 0f
                sb.append("   近 ${bars.size} 日区间：高 ${formatPrice(hi)} / 低 ${formatPrice(lo)}\n")
            }
            sb.append("\n")
        }
        sb.append("【用户问题】\n$question\n\n")
        sb.append("请基于上述数据给出对比分析、优势/风险对比、给出明确结论建议。")
        return sb.toString()
    }

    /** 涨跌幅带符号文案（正/负/0），KMP 安全两位小数 */
    private fun signChg(v: Float): String {
        if (!v.isFinite()) return "--"
        val sign = if (v > 0f) "+" else if (v < 0f) "-" else ""
        val abs = kotlin.math.abs(v)
        val i = abs.toInt()
        val d = ((abs - i) * 100).toInt().coerceIn(0, 99)
        return sign + i + "." + (if (d < 10) "0$d" else d.toString())
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
                // 「设置」：跳选股页；选股页点「完成」落盘后，pageDidAppear 自动重读并应用（完成即生效）
                View {
                    attr {
                        paddingLeft(10f); paddingRight(10f); height(30f); borderRadius(15f)
                        justifyContentCenter(); alignItemsCenter()
                        backgroundColor(Color(UserSettings.themeColor))
                    }
                    event { click { ctx.openComparePicker(-1) } }
                    Text { attr { text("设置"); fontSize(13f); color(Color.WHITE); fontWeightSemiBold() } }
                }
            }

            // ===== 上区（约 2/5）：对比股票轮播 =====
            vif({ ctx.uiToggle }) { val c = this; c.renderCompareUpper(ctx) }
            vif({ !ctx.uiToggle }) { val c = this; c.renderCompareUpper(ctx) }

            // ===== 分隔（尽量薄，让上区股票与下区聊天贴近）=====
            View { attr { height(4f); backgroundColor(Color(0xFFF2F3F5)) } }

            // ===== 下区（约 3/5）：对比 AI 聊天（#100）=====
            vif({ ctx.chatToggle }) { val c = this; c.renderCompareChat(ctx) }
            vif({ !ctx.chatToggle }) { val c = this; c.renderCompareChat(ctx) }
        }
    }
}

/** 对比页聊天的消息（内存；user/assistant）。 */
internal data class CompareChatMsg(val role: String, val text: String)

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
            stocks.forEachIndexed { idx, st ->
                val code = st.code
                val period = ctx.comparePeriods[code] ?: "intraday"
                View {
                    attr {
                        width(ctx.pagerData.pageViewWidth - 20f); height(170f); marginRight(10f)
                        padding(10f); borderRadius(10f); flexDirectionColumn()
                        backgroundColor(Color.WHITE)
                    }
                    // 名（点击跳详情·红下划线，模仿聊天的提及股卡片样式）+ code + 价+涨跌（紧凑单行）
                    View { attr { flexDirectionRow(); alignItemsCenter() }
                        // 股票名 RichText +红下划线 +点击 → 跳个股详情页
                        RichText {
                            attr { maxWidth(160f) }
                            Span {
                                fontSize(UserSettings.fs(15f)); fontWeightSemiBold()
                                color(UserSettings.themeColor); textDecorationUnderLine()
                                text(st.name)
                                click {
                                    val d = JSONObject().put("stockCode", st.code)
                                    ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
                                }
                            }
                        }
                        Text { attr { text(st.code); fontSize(UserSettings.fs(11f)); color(Color(0xFF999999)); marginLeft(6f) } }
                        View { attr { flex(1f) } }
                        Text { attr { text(formatPrice(st.price) + "  " + formatPercent(st.changePercent)); fontSize(UserSettings.fs(14f)); fontWeightSemisolid(); color(StockColor.text(st.changePercent)) } }
                    }
                    // 周期 chips（分时 / 日K / 周K / 月K / 年K）
                    View { attr { flexDirectionRow(); marginTop(6f) }
                        comparePeriodChip(ctx, code, "intraday", "分时", period)
                        comparePeriodChip(ctx, code, "day", "日K", period)
                        comparePeriodChip(ctx, code, "week", "周K", period)
                        comparePeriodChip(ctx, code, "month", "月K", period)
                        comparePeriodChip(ctx, code, "year", "年K", period)
                    }
                    // 紧凑迷你走势：分时用 KRMiniTimeSharing(自选展开同款)，日/周/月/年K 用 KRTrendChart 收盘价趋势
                    when (period) {
                        "intraday" -> {
                            KRMiniTimeSharing {
                                points = StockData.getIntraday(st)
                                refPrice = StockData.intradayRefPrice(st)
                                color = StockColor.of(st.changePercent)
                            }
                        }
                        else -> {
                            // StockData.getKLine 中文 key: 日/周/月/年
                            val periodKey = if (period == "day") "日" else when (period) { "week" -> "周"; "month" -> "月"; "year" -> "年"; else -> "日" }
                            val closes = StockData.getKLine(st, periodKey, 60).map { it.close }
                            KRTrendChart {
                                points = closes
                                chartHeight = 122f
                            }
                        }
                    }
                }
            }
            // 最右「+」页：点击跳选股页追加新股票
            View {
                attr {
                    width(60f); height(170f); borderRadius(10f)
                    justifyContentCenter(); alignItemsCenter()
                    backgroundColor(Color.WHITE)
                }
                event { click { ctx.openComparePicker(-1) } }
                Text { attr { text("＋"); fontSize(30f); color(Color(0xFFBBBBBB)) } }
            }
        }
    }
}

/** 下区：对比 AI 聊天。消息列表（user / assistant，assistant 走 Markdown 渲染）+ 输入栏 + 发送。 */
private fun ViewContainer<*, *>.renderCompareChat(ctx: StockComparePage) {
    View {
        attr { flex(1f); paddingLeft(10f); paddingRight(10f); paddingTop(6f); paddingBottom(6f); flexDirectionColumn() }
        // 消息列表
        View {
            attr { flex(1f); borderRadius(10f); backgroundColor(Color.WHITE); padding(10f); marginBottom(8f) }
            if (ctx.chatMessages.isEmpty()) {
                // 首条消息前：引导提示（只提示一次，发过就不再显示）
                val names = ctx.compareCodes.take(2).map { StockData.findByCode(it).name }
                val whoText = if (names.size >= 2) "${names[0]} 和 ${names[1]} 谁更值得关注？" else "当前对比股谁更值得关注？"
                Text {
                    attr {
                        text("对比 AI：会综合几只股票的实时价与近期走势给出横向对比、优劣势与结论。点下面问题开始：")
                        fontSize(UserSettings.fs(12f)); color(Color(0xFF999999))
                    }
                }
                View {
                    attr { flexDirectionColumn() }
                    listOf(whoText, "帮我挑一只更稳的", "谁短线更强？").forEach { q ->
                        View {
                            attr {
                                marginTop(6f); paddingLeft(12f); paddingRight(12f); height(34f)
                                borderRadius(17f); justifyContentCenter()
                                backgroundColor(Color(0xFFFFF3E0))
                            }
                            event { click { ctx.sendCompareQuestion(q) } }
                            Text { attr { text(q); fontSize(UserSettings.fs(13f)); color(Color(0xFF9A6B00)) } }
                        }
                    }
                }
            } else {
                ctx.chatMessages.forEach { msg ->
                    if (msg.role == "user") {
                        View {
                            attr { marginBottom(8f); flexDirectionRow(); justifyContentFlexEnd() }
                            View {
                                attr {
                                    backgroundColor(Color(UserSettings.themeColor)); borderRadius(8f); padding(8f)
                                    // 限制最大宽度，避免长文本横铺；attr 现读 text
                                }
                                Text {
                                    attr {
                                        text(msg.text); fontSize(UserSettings.fs(13f)); color(Color.WHITE)
                                    }
                                }
                            }
                        }
                    } else {
                        View {
                            attr { marginBottom(8f); backgroundColor(Color(0xFFF7F8FA)); borderRadius(8f); padding(8f) }
                            // assistant: Markdown 渲染（复用聊天富文本）
                            renderMarkdown(
                                text = msg.text,
                                contentW = ctx.pagerData.pageViewWidth - 60f,
                                textColor = Color(0xFF222222),
                                accent = Color(UserSettings.themeColor),
                                onOpenStock = { code ->
                                    val st = StockData.findByCode(code)
                                    if (st.name.isNotEmpty() && st.name != code) {
                                        val d = JSONObject().put("stockCode", code)
                                        ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
                                    }
                                }
                            )
                        }
                    }
                }
                if (ctx.cmpWaiting) {
                    Text { attr { text("AI 思考中…"); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)) } }
                }
            }
        }
        // 输入栏
        View {
            attr { flexDirectionRow(); alignItemsCenter(); height(40f) }
            View {
                attr {
                    flex(1f); height(40f); paddingLeft(10f); paddingRight(10f); borderRadius(20f)
                    backgroundColor(Color.WHITE); justifyContentCenter()
                }
                Input {
                    ref { ctx.cmpInputRef = it }
                    attr {
                        flex(1f); height(36f)
                        fontSize(UserSettings.fs(13f))
                        color(Color(0xFF222222))  // 缺色会导致文字不可见（Kuikly 隐性坑）
                        placeholder(if (ctx.cmpInput.isBlank()) "问问对比分析…" else "")
                        placeholderColor(Color(0xFF999999))
                    }
                    event {
                        textDidChange { ctx.cmpInput = it.text }
                        // 键盘抬起时把输入栏后的 Spacer 撑到键盘上沿（保持最新消息可见）
                        keyboardHeightChange { params ->
                            val h = params.height.coerceAtLeast(0f)
                            if (h > 0f) ctx.lastKeyboardH = h
                            ctx.keyboardH = h
                        }
                        // 兜底：极少数 keyboardHeightChange 漏回调时用上次实测高度顶上
                        inputFocus {
                            if (ctx.keyboardH <= 0f && ctx.lastKeyboardH > 0f) ctx.keyboardH = ctx.lastKeyboardH
                        }
                        inputBlur { ctx.keyboardH = 0f }
                    }
                }
            }
            View {
                attr {
                    height(36f); paddingLeft(14f); paddingRight(14f); borderRadius(18f); marginLeft(8f)
                    justifyContentCenter(); alignItemsCenter()
                    backgroundColor(if (ctx.cmpWaiting || ctx.cmpInput.isBlank()) Color(0xFFCCCCCC) else Color(UserSettings.themeColor))
                }
                event { click { if (!ctx.cmpWaiting && ctx.cmpInput.isNotBlank()) ctx.sendCompareAsk() } }
                Text {
                    attr {
                        text(if (ctx.cmpWaiting) "发送中…" else "发送")
                        fontSize(UserSettings.fs(13f)); color(Color.WHITE); fontWeightSemiBold()
                    }
                }
            }
        }
        // 占位 Spacer（键盘抬起时撑高，把输入栏顶到键盘上沿；自身落进键盘遮挡区）
        View {
            attr {
                height(ctx.keyboardH)
                backgroundColor(if (UserSettings.darkMode) Color(0xFF1A1B1E) else Color(0xFFF2F3F5))
            }
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
