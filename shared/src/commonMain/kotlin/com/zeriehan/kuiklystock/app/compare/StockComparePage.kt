package com.zeriehan.kuiklystock.app.compare

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.ScrollerView
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
import com.zeriehan.kuiklystock.core.QuickTipsGate
import com.zeriehan.kuiklystock.core.llm.AIJobCenter
import com.zeriehan.kuiklystock.core.llm.ChatStore
import com.zeriehan.kuiklystock.core.llm.ChatSync

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
    /** 下区对比聊天消息列表（UI 态；持久真源在 ChatStore.COMPARE_CONV，退出重进/冷启动恢复） */
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
    /** 下区消息流 Scroller ref：内容变化时滚到底（最新消息可见） */
    internal lateinit var cmpScrollerRef: ViewRef<ScrollerView<*, *>>
    /** 消息流最近一次真实内容高度（滚底 target = contentH - viewportH） */
    internal var cmpContentH: Float = 0f
    /** 消息流视口高度（scroll 事件回写） */
    internal var cmpViewportH: Float = 0f
    // ===== 消息长按菜单 / 多选（对齐主聊天 ChatPage）=====
    /** 长按菜单：当前操作消息在 ChatStore(COMPARE_CONV) 里的索引 / 文本 */
    internal var cmpMsgMenuIndex: Int? by observable(null)
    internal var cmpMsgMenuText: String by observable("")
    /** 消息多选模式（长按菜单「多选」进入），可勾选后批量删除 */
    internal var cmpSelectMode: Boolean by observable(false)
    /** 多选态已勾选消息索引（重新赋值触发响应式，勿原地 mutate） */
    internal var cmpSelectedIdx: Set<Int> by observable(emptySet())
    /** 推荐问句(快捷胶囊)是否可见：本进程首次进对比页提示一次，发过第一条收起 */
    internal var cmpQuickTipsVisible: Boolean by observable(false)
    /** 页面是否已销毁：销毁后回调/监听只写单例，不再碰本页 observable（防后台完成回复丢失） */
    private var compDestroyed: Boolean = false
    /** ChatSync 监听（须为稳定同一对象，pageWillDestroy 里才能精确移除）：
     *  后台请求完成/任何会话变化落盘后 bump，本页(若存活)重读 ChatStore 刷新，实现"退出重进后台仍回复" */
    private val cmpChatListener: () -> Unit = { syncCompFromStore() }

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 对比股以持久化为唯一真相源：优先读上次保存的对比股（用户在选股页设好 + 应用选股后已落盘），
        // 无记录时 loadSavedCompare 内部兜底为默认 茅台/五粮液。入口 openCompare 已不再传默认 stocks 覆盖它。
        compareCodes = loadSavedCompare()
        // 每只股默认迷你走势周期为「分时」
        comparePeriods = compareCodes.associateWith { "intraday" }
        // 聊天消息/等待态统一由 ChatStore(COMPARE_CONV) 驱动：进入即同步历史 + 恢复等待态
        // （退出对比页后 AI 若仍在后台跑，isPending=true；完成后落盘 bump → 监听刷新显示，见 syncCompFromStore）
        ChatSync.addListener(cmpChatListener)
        syncCompFromStore()
        // 推荐问句：本进程首次进对比页提示一次（QuickTipsGate 与主聊天共享门控）
        cmpQuickTipsVisible = QuickTipsGate.claim()
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
        // 重新出现(返回本页/重进前台)：期间后台若已把 AI 回复落盘，重新同步一次（配合 ChatSync 监听双保险）
        syncCompFromStore()
        // 进页/重新出现都滚到底（最新消息可见），避免停留在历史最旧处
        com.tencent.kuikly.core.timer.setTimeout(pagerId, 120) { if (!compDestroyed) scrollCompToBottom() }
    }

    /** 页面销毁：解除监听 + 标记已销毁，后续回调只写单例(不碰本页 observable) */
    override fun pageWillDestroy() {
        compDestroyed = true
        ChatSync.removeListener(cmpChatListener)
        super.pageWillDestroy()
    }

    /**
     * 聊天消息/等待态从持久真源 ChatStore(COMPARE_CONV) 单向同步到本页 UI。
     * 由 ChatSync 监听、进入页面、pageDidAppear 驱动；本页销毁后不再被调（监听已移除）。
     * 关键作用：退出对比页时 AI 还在后台跑 → 完成后落盘 + bump → 重进(新实例)监听触发这里刷新，
     * 实现"发消息后退出、再进入，AI 后台回复仍会显示"。
     */
    private fun syncCompFromStore() {
        if (compDestroyed) return
        val hist = ChatStore.messages(ChatStore.COMPARE_CONV)
        val msgs = hist.map { CompareChatMsg(it.role, it.text) }
        val waiting = ChatStore.isPending(ChatStore.COMPARE_CONV)
        if (msgs != chatMessages || waiting != cmpWaiting) {
            chatMessages = msgs
            cmpWaiting = waiting
            chatToggle = !chatToggle  // vif 重建消息区
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
        exitCmpSelectIfNeeded()
        cmpInput = ""
        cmpInputRef.view?.setText("")
        sendCompareQuestion(q)
    }

    /** 发送一条对比问题并请求 AI（引导 chips / 手动输入共用；cmpWaiting 时忽略）。
     *  消息与等待态一律写持久真源 ChatStore(COMPARE_CONV)，UI 靠 syncCompFromStore（ChatSync.bump 驱动）刷新。
     *  这样即便用户在 AI 回复前退出对比页，请求仍经常驻根页桥在后台完成并落盘；
     *  重进(新实例)监听 ChatSync → syncCompFromStore 显示回复，实现"退出后后台继续处理"。 */
    internal fun sendCompareQuestion(q: String) {
        if (q.isBlank() || cmpWaiting) return
        cmpWaiting = true
        cmpQuickTipsVisible = false  // 发出第一条后：推荐问句收起，避免一直挡视野
        ChatStore.setPending(ChatStore.COMPARE_CONV, true)
        // 用户消息落盘（先落盘再 bump，避免依赖本页存活）
        ChatStore.append(ChatStore.COMPARE_CONV, ChatStore.ChatMessage("user", q))
        ChatSync.bump()  // 本页监听 → syncCompFromStore 立即显示 user 气泡 + 思考中
        val prompt = buildComparePrompt(q)
        // 走 AIJobCenter（常驻根页桥，同主聊天/GLMFlashClient）：对比页是 push 的子页面，
        // 直接 bridgeModule.llmAnalyze(当前页桥) 在页面切走/返回/后台时会失效 → 回调空 → 误报"无key/限流"。
        AIJobCenter.sendPrompt(prompt, stream = false, sid = "") { resp ->
            val text = resp?.optString("text").orEmpty()
            val reply = if (text.isBlank()) "（AI 未返回，可能是限流或无 Key。请稍后重试。）" else text
            // ⚠️ 回调先安全落盘单例（本页可能已销毁，绝不在回调里直接写 chatMessages observable，
            // 那会因页面已销毁而丢失后台回复）。落盘 + setPending(false) + bump 驱动 UI。
            ChatStore.append(ChatStore.COMPARE_CONV, ChatStore.ChatMessage("assistant", reply))
            ChatStore.setPending(ChatStore.COMPARE_CONV, false)
            ChatSync.bump()
            // 本页已销毁(用户已退出)：后台完成提示走常驻桥
            if (compDestroyed) {
                AIJobCenter.toast("对比 AI 已回复，点开查看")
            }
        }
    }

    /**
     * 消息流滚到底（最新消息可见）。offset 必须在 [0, contentH-viewportH] 内才生效，
     * 故用真实尺寸算 y=contentH-viewportH，绝不用极大值。
     * 进页首帧 scroll 事件可能尚未回写视口高，用估算值兜底；延迟一拍等布局完成后调用。
     */
    internal fun scrollCompToBottom() {
        val v = cmpScrollerRef.view ?: return
        if (cmpContentH <= 0f) return
        val vh = if (cmpViewportH > 0f) cmpViewportH else estimateCmpViewportH()
        val y = (cmpContentH - vh).coerceAtLeast(0f)
        com.tencent.kuikly.core.timer.setTimeout(pagerId, 30) {
            v.setContentOffset(0f, y, false)
        }
    }

    /**
     * 视口高度估算（scroll 事件尚未回写时兜底）：整页高 − 顶部返回栏(状态栏+44)
     * − 上区对比卡(206) − 分隔(4) − 下区内边距(上下各6) − 输入栏(40) − 卡片距输入栏(8)。
     * 供首帧滚底用，之后由 scroll 事件的真实 cmpViewportH 覆盖。
     */
    private fun estimateCmpViewportH(): Float {
        val sb = pagerData.statusBarHeight
        return (pagerData.pageViewHeight - (44f + sb) - 206f - 4f - 12f - 40f - 8f - keyboardH).coerceAtLeast(0f)
    }

    // ===== 消息长按菜单 / 多选（操作 ChatStore.COMPARE_CONV，与渲染单向同步）=====

    /** 长按某消息气泡 → 打开操作菜单（多选态下屏蔽，改由点按勾选） */
    internal fun openCmpMsgMenu(index: Int, text: String) { cmpMsgMenuIndex = index; cmpMsgMenuText = text }
    internal fun closeCmpMsgMenu() { cmpMsgMenuIndex = null; cmpMsgMenuText = "" }

    /** 复制消息文本到剪贴板 */
    internal fun copyCmpText(text: String) {
        bridgeModule.copyToPasteboard(text)
        bridgeModule.toast("已复制")
    }

    /** 删除单条消息（同步 ChatStore + 广播刷新 UI） */
    internal fun deleteCmpMsg(index: Int) {
        if (index !in ChatStore.messages(ChatStore.COMPARE_CONV).indices) return
        ChatStore.deleteMessageAt(ChatStore.COMPARE_CONV, index)
        ChatSync.bump()
    }

    /** 进入多选：关闭长按菜单，并预勾选触发长按的那条（符合直觉） */
    internal fun enterCmpSelect(index: Int) {
        closeCmpMsgMenu()
        cmpSelectMode = true
        cmpSelectedIdx = setOf(index)
    }
    internal fun exitCmpSelect() { cmpSelectMode = false; cmpSelectedIdx = emptySet() }

    /** 勾选/取消勾选（重新赋值整个集合触发响应式） */
    internal fun toggleCmpSelect(index: Int) {
        cmpSelectedIdx = if (cmpSelectedIdx.contains(index)) cmpSelectedIdx - index else cmpSelectedIdx + index
    }

    /** 全选 / 取消全选 */
    internal fun toggleCmpSelectAll() {
        val total = ChatStore.messages(ChatStore.COMPARE_CONV).size
        cmpSelectedIdx = if (total > 0 && cmpSelectedIdx.size >= total) emptySet() else (0 until total).toSet()
    }

    /** 批量删除已勾选消息（一次性删，避免索引错位） */
    internal fun deleteCmpSelected() {
        if (cmpSelectedIdx.isEmpty()) return
        val n = cmpSelectedIdx.size
        val targets = cmpSelectedIdx
        exitCmpSelect()
        ChatStore.deleteMessagesAt(ChatStore.COMPARE_CONV, targets)
        ChatSync.bump()
        bridgeModule.toast("已删除 $n 条消息")
    }

    /** 发送前退出多选态：避免勾选索引与新消息列表语义错位 */
    internal fun exitCmpSelectIfNeeded() { if (cmpSelectMode) exitCmpSelect() }

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

            // ===== 消息长按菜单（浮层，放根 column 常驻，不受 chatToggle 重建影响）=====
            vif({ ctx.cmpMsgMenuIndex != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.closeCmpMsgMenu() } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val menuW = 170f
                        val left = (vw - menuW) / 2f
                        absolutePosition(top = 240f, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(12f); flexDirectionColumn()
                    }
                    val idx = ctx.cmpMsgMenuIndex!!
                    ctx.cmpMsgMenuItem("复制") { ctx.copyCmpText(ctx.cmpMsgMenuText); ctx.closeCmpMsgMenu() }
                    ctx.cmpMsgDivider()
                    ctx.cmpMsgMenuItem("删除") { ctx.deleteCmpMsg(idx); ctx.closeCmpMsgMenu() }
                    ctx.cmpMsgDivider()
                    ctx.cmpMsgMenuItem("选取文字") { ctx.bridgeModule.showSelectableText("选取文字", ctx.cmpMsgMenuText); ctx.closeCmpMsgMenu() }
                    ctx.cmpMsgDivider()
                    ctx.cmpMsgMenuItem("多选") { ctx.enterCmpSelect(idx) }
                    ctx.cmpMsgDivider()
                    ctx.cmpMsgMenuItem("取消") { ctx.closeCmpMsgMenu() }
                }
            }

            // ===== 消息多选操作栏（多选态浮在输入栏上方）=====
            vif({ ctx.cmpSelectMode }) {
                View {
                    attr {
                        absolutePosition(left = 0f, bottom = 64f)
                        width(ctx.pagerData.pageViewWidth)
                        height(52f); flexDirectionRow(); alignItemsCenter()
                        backgroundColor(Color.WHITE)
                        padding(0f, 12f)
                    }
                    // 全选 / 取消全选
                    View {
                        attr { padding(6f, 4f, bottom = 6f, right = 4f); marginRight(8f)
                            borderRadius(8f); backgroundColor(Color(0xFFF2F3F5)) }
                        event { click { ctx.toggleCmpSelectAll() } }
                        Text { attr {
                            val total = ChatStore.messages(ChatStore.COMPARE_CONV).size
                            val all = total > 0 && ctx.cmpSelectedIdx.size >= total
                            text(if (all) "取消全选" else "全选"); fontSize(UserSettings.fs(14f)); color(Color(0xFF333333))
                        } }
                    }
                    Text { attr { text("已选 ${ctx.cmpSelectedIdx.size}"); fontSize(UserSettings.fs(14f)); color(Color(0xFF222222)); flex(1f); marginRight(16f) } }
                    // 删除（红）
                    View {
                        attr { padding(6f, 4f, bottom = 6f, right = 4f); marginRight(8f)
                            borderRadius(8f); backgroundColor(Color(0xFFFDECEA)) }
                        event { click { ctx.deleteCmpSelected() } }
                        Text { attr { text("删除"); fontSize(UserSettings.fs(14f)); color(Color(0xFFE54D42)) } }
                    }
                    // 取消多选
                    View {
                        attr { padding(6f, 4f, bottom = 6f, right = 4f)
                            borderRadius(8f); backgroundColor(Color(0xFFF2F3F5)) }
                        event { click { ctx.exitCmpSelect() } }
                        Text { attr { text("取消"); fontSize(UserSettings.fs(14f)); color(Color(0xFF333333)) } }
                    }
                }
            }
        }
    }
}

/** 对比页聊天的消息（user/assistant；经 ChatStore.COMPARE_CONV 持久化，见 ChatStore.ChatMessage） */
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
        // 消息列表：白卡片弹性区(圆角) 内包一个 Scroller —— 消息一多即可上下滚动，
        // 不会溢出/盖住输入栏（参考主聊天页：Scroller 放 flex(1f) 容器里作头部~输入栏间唯一滚动区）。
        View {
            attr { flex(1f); borderRadius(10f); backgroundColor(Color.WHITE); marginBottom(8f); flexDirectionColumn() }
            Scroller {
                ref { ctx.cmpScrollerRef = it }
                attr {
                    flex(1f); flexDirectionColumn()
                    padding(top = 10f, left = 10f, bottom = 10f, right = 10f)
                }
                event {
                    // 内容尺寸变化(布局完成/来新消息)后滚到底，让最新消息可见
                    contentSizeChanged { _, h ->
                        ctx.cmpContentH = h
                        ctx.scrollCompToBottom()
                    }
                    // 记录视口高度：滚底 target = contentH - viewportH
                    scroll { params -> ctx.cmpViewportH = params.viewHeight }
                }
            if (ctx.chatMessages.isEmpty()) {
                // 空态：仅一行提示；推荐问句统一放在输入栏上方(见 body 的快捷胶囊区)，避免两处重复
                Text {
                    attr {
                        text("对比 AI：综合几只股票的实时价与近期走势，给出横向对比、优劣势与结论。点下方推荐问题或直接输入开始。")
                        fontSize(UserSettings.fs(12f)); color(Color(0xFF999999))
                    }
                }
            } else {
                ctx.chatMessages.forEachIndexed { mi, msg ->
                    val isUser = msg.role == "user"
                    View {
                        attr {
                            flexDirectionRow(); alignItemsCenter(); marginBottom(8f)
                            justifyContent(if (isUser) FlexJustifyContent.FLEX_END else FlexJustifyContent.FLEX_START)
                        }
                        // 多选态：气泡左侧勾选圆点（选中填充主题色+打勾；attr 内现读勾选态）
                        vif({ ctx.cmpSelectMode }) {
                            View {
                                attr {
                                    width(20f); height(20f); borderRadius(10f); marginRight(8f)
                                    alignItemsCenter(); justifyContentCenter()
                                    val sel = ctx.cmpSelectedIdx.contains(mi)
                                    border(Border(1.5f, BorderStyle.SOLID, Color(if (sel) UserSettings.themeColor else 0xFFCCCCCC)))
                                    backgroundColor(if (sel) Color(UserSettings.themeColor) else Color.WHITE)
                                }
                                event { click { ctx.toggleCmpSelect(mi) } }
                                Text { attr {
                                    val sel = ctx.cmpSelectedIdx.contains(mi)
                                    text(if (sel) "✓" else ""); fontSize(UserSettings.fs(13f)); color(Color.WHITE)
                                } }
                            }
                        }
                        View {
                            attr {
                                maxWidth((ctx.pagerData.pageViewWidth - 60f))
                                flexDirectionColumn()
                                backgroundColor(if (isUser) Color(UserSettings.themeColor) else Color(0xFFF7F8FA))
                                borderRadius(8f); padding(8f)
                            }
                            event {
                                // 长按弹操作菜单（复制/删除/选取文字/多选）；多选态改由点按勾选，屏蔽长按
                                longPress { if (!ctx.cmpSelectMode) ctx.openCmpMsgMenu(mi, msg.text) }
                                click { if (ctx.cmpSelectMode) ctx.toggleCmpSelect(mi) }
                            }
                            if (isUser) {
                                Text {
                                    attr {
                                        text(msg.text); fontSize(UserSettings.fs(13f)); color(Color.WHITE)
                                        maxWidth((ctx.pagerData.pageViewWidth - 60f) - 16f)
                                    }
                                }
                            } else {
                                // assistant: Markdown 渲染（复用聊天富文本）
                                renderMarkdown(
                                    text = msg.text,
                                    contentW = (ctx.pagerData.pageViewWidth - 60f) - 16f,
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
                }
                if (ctx.cmpWaiting) {
                    Text { attr { text("AI 思考中…"); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)) } }
                }
            }
            }  // Scroller 结束
        }  // 白卡片弹性区结束
        // ===== 推荐问句（快捷胶囊，本进程首次进对比页提示一次；发过第一条收起）=====
        vif({ ctx.cmpQuickTipsVisible }) {
            View {
                attr {
                    flexDirectionColumn(); backgroundColor(Color.WHITE)
                    borderRadius(10f); padding(8f); marginBottom(6f)
                }
                Text { attr { text("试试这样问："); fontSize(UserSettings.fs(11f)); color(Color(0xFF999999)) } }
                val names = ctx.compareCodes.take(2).map { StockData.findByCode(it).name }
                val qs = if (names.size >= 2) {
                    listOf("${names[0]} 和 ${names[1]} 谁更值得关注？", "这两只谁短线更强？", "帮我挑一只更稳的", "现在更适合买哪只？")
                } else listOf("谁更值得关注？", "短线还是长线更适合？", "帮我挑一只更稳的")
                qs.chunked(2).forEach { row ->
                    View {
                        attr { flexDirectionRow(); marginTop(6f) }
                        row.forEachIndexed { i, q ->
                            View {
                                attr {
                                    flex(1f)
                                    if (i > 0) marginLeft(6f)
                                    padding(7f); paddingLeft(10f); paddingRight(10f); borderRadius(14f)
                                    backgroundColor(Color(UserSettings.themeTint(0.12f)))
                                    justifyContentCenter(); alignItemsCenter()
                                }
                                event { click { ctx.sendCompareQuestion(q) } }
                                Text { attr { text(q); fontSize(UserSettings.fs(12f)); color(Color(UserSettings.themeColor)); maxWidth(160f) } }
                            }
                        }
                    }
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

/** 消息长按菜单项（对齐主聊天 chatMsgItem） */
private fun ViewContainer<*, *>.cmpMsgMenuItem(label: String, onClick: () -> Unit) {
    View { attr { height(48f); justifyContentCenter(); paddingLeft(16f) }
        event { click { onClick() } }
        Text { attr { text(label); fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)) } }
    }
}

/** 消息长按菜单分隔线 */
private fun ViewContainer<*, *>.cmpMsgDivider() {
    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
}
