package com.zeriehan.kuiklystock.app.chat

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.views.ScrollerView
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockMention
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.llm.AIJobCenter
import com.zeriehan.kuiklystock.core.llm.ChatStore
import com.zeriehan.kuiklystock.core.llm.ChatSync
import com.zeriehan.kuiklystock.core.llm.LLM
import com.zeriehan.kuiklystock.components.KRStockCard.renderAiStockCards
import com.zeriehan.kuiklystock.components.KRMarkdown.renderMarkdown

/**
 * AI 聊天页（按股票代码隔离的同一段对话）。
 *
 * 进入方式：
 * - 行情/自选长按行 -> 「问 AI」；
 * - 详情页点「深入聊聊这只股票」。
 * 两者都传同一 stockCode，因此复用 [ChatStore] 中同一份消息，做到「同一个对话」。
 *
 * 布局：返回栏 + 股价条 + 消息流（气泡左右分列）+ 输入框/发送。
 *
 * 关键实现说明（避免反复踩坑）：
 * 1. 气泡宽度不依赖 pageViewWidth 计算负宽：用 `coerceIn(200f,300f)` 兜底，避免子页面 pageViewWidth
 *    未就绪时算成负宽导致气泡 0 宽不可见。
 * 2. 气泡行作为消息列(Scroller, 默认 alignItems=STRETCH)的直接子节点自动拉满整行宽度，再用
 *    justifyContent 控制左/右对齐；气泡只给 maxWidth 上限，文本在其中自动换行。绝不给气泡 flex(1f)。
 * 3. GLM/Mock 回调均已在主线程（宿主 KRBridgeModule 用 Handler(Looper.getMainLooper()).post 切主线程；Mock 同步即主线程），
 *    故 chat 回调里直接写单例 ChatStore 并 bump，不再用 setTimeout 切线程。
 * 3.1 「后台继续跑」：请求经 AIJobCenter 发到常驻根页面(MainTabPager)的桥上，
 *    因此退出本页后 AI 仍会继续生成；结果写进单例 ChatStore（并已落盘 SharedPreferences），
 *    重新进入时 pageDidAppear 会同步出来。等待状态由 ChatStore.isPending 跨页面保留。
 * 4. 键盘：宿主设 adjustNothing，这里监听 keyboardHeightChange 手动把内容区底部抬起(paddingBottom)，
 *    标题栏固定不动、仅输入栏贴着键盘上沿。
 */
@Page("Chat", supportInLocal = true)
internal class ChatPage : BasePager() {

    internal lateinit var code: String
    private lateinit var stock: Stock
    /** 自由对话模式：不绑个股（主 Tab 直接聊大盘/宏观/选股）。此时 code 固定为 "free" */
    internal var freeMode: Boolean by observable(false)

    /** 输入框当前文本（响应式，发送按钮据此启用） */
    private var inputText: String by observable("")
    /** AI 思考中：禁用发送、显示「思考中…」动态三点气泡 */
    internal var aiThinking: Boolean by observable(false)
    /** 思考态动态三点当前高亮索引(0..2)，由思考动画定时器驱动 */
    internal var thinkingDot: Int by observable(0)
    /** 思考动画定时器是否在跑（防重） */
    private var thinkingAnimRunning = false
    /** 消息长按菜单：当前操作的消息索引 / 文本（复制、选取文字用） */
    internal var msgMenuIndex: Int? by observable(null)
    internal var msgMenuText: String by observable("")
    /** 消息多选模式：长按菜单点「多选」进入，勾选后可批量删除 */
    internal var msgSelectMode: Boolean by observable(false)
    /** 多选态已勾选的消息索引集合（重新赋值触发响应式刷新，勿原地 mutate） */
    internal var selectedMsgIdx: Set<Int> by observable(emptySet())
    /** 消息版本号：每次增删消息 +1，配合 renderToggle 翻转强制重建消息列表 */
    internal var msgVersion: Int by observable(0)
    /** vif 翻转触发器：本版本 body 不随 observable 重跑，消息列表必须靠 vif 翻转才能强制重建 */
    internal var renderToggle: Boolean by observable(false)
    /** 本会话已触发过真实分时拉取的股票 code 集合（渲染卡片时去重，避免每次重建重复请求） */
    private val trendsRequested = mutableSetOf<String>()
    /** 键盘高度：弹出时把内容区底部抬起，使输入栏贴着键盘上沿（标题固定不动） */
    private var keyboardH: Float by observable(0f)
    /** 输入框 ref，用于发送后清空 */
    private lateinit var inputRef: ViewRef<InputView>
    /** 消息流 Scroller ref，进页/来新消息时滚到底部（最新） */
    private lateinit var scrollerRef: ViewRef<ScrollerView<*, *>>
    /** 是否“贴底”：用户在底部附近（或首次进入）时，新消息/布局变化自动滚到底部；在看历史时不打断 */
    private var stickToBottom: Boolean = true
    /** 聊天首屏是否已定位到底部：首屏强制贴底（无论 stickToBottom 当时算成啥），之后才受 stickToBottom 约束 */
    private var chatPositioned: Boolean = false
    /** 最近一次 contentSizeChanged 拿到的真实内容高度（用于精确滚到底：target = contentH - viewportH） */
    private var lastContentH: Float = 0f
    /** 已据此高度定位到底部过：同一高度重复 contentSizeChanged 时不再 setContentOffset，防回弹抖动 */
    private var lastScrolledContentH: Float = 0f
    /** 视口高度：scroll 事件实时回写；初始用 pagerData 估算（避免首帧 scroll 未触发时算错） */
    private var viewportH: Float = 0f
    /** 是否已初始化（参数须在 body 内读取，故用此标志保证仅初始化一次） */
    private var bootstrapped: Boolean = false
    /** 页面是否已销毁：销毁后监听回调直接返回，避免操作已失效的 observable */
    private var destroyed: Boolean = false
    /** ChatSync 监听（须为稳定的同一对象，才能在 pageWillDestroy 里精确移除） */
    private val chatListener: () -> Unit = { refreshMessages() }

    override fun created() {
        super.created()
    }

    /**
     * 重新进入聊天页（或从详情页/其它子页返回）时同步最新状态：
     * 页面关闭期间 AI 可能已经回复完成，也可能仍在"后台"生成中。
     */
    override fun pageDidAppear() {
        super.pageDidAppear()
        refreshMessages()
    }

    override fun pageWillDestroy() {
        destroyed = true
        ChatSync.removeListener(chatListener)
        super.pageWillDestroy()
    }

    /**
     * 在 body 首次调用时读取参数并补一句 AI 开场白。
     * Kuikly 的 pageData.params 须在 body 作用域内读取（created 中可能为空白），
     * 因此把参数读取与「最近对话」写库放到这里，确保对话按正确 stockCode 落库。
     */
    private fun ensureInit() {
        if (bootstrapped) return
        val raw = pageData.params.optString("stockCode")
        // 自由对话：未带 stockCode（或显式 "free"）时进入，不绑个股
        freeMode = raw.isBlank() || raw == "free"
        code = if (freeMode) "free" else raw
        // 自由模式仍取一个标的作兜底上下文（避免 lateinit 为空），但 UI 与 prompt 都不把它当唯一话题
        stock = StockData.findByCode(if (freeMode) "000001" else code)
        if (ChatStore.messages(code).isEmpty()) {
            val greeting = if (freeMode) {
                "你好，我是你的 AI 财经助手。可以问我大盘走势、宏观热点、行业逻辑或选股思路，" +
                    "也可以直接聊任意股票。想聊点什么？"
            } else {
                "你好，我是 ${stock.name} 的 AI 助手。关于这只股票（现价 ${formatPrice(stock.price)}，" +
                    "今日${if (stock.changePercent >= 0f) "涨" else "跌"}${formatPercent(
                        kotlin.math.abs(stock.changePercent)
                    )}），有什么想问的？"
            }
            ChatStore.append(code, ChatStore.ChatMessage("assistant", greeting))
        }
        bootstrapped = true
        // 若上一条提问还在"后台"生成中，进入页面时继续保持思考态
        updateThinkingUI(ChatStore.isPending(code))
        // 注册会话变更监听：AI 在页面关闭期间回复完成时，本页（若仍活着）即时刷新出气泡
        ChatSync.addListener(chatListener)
        // 通知主框架：本股票已有对话（用于「最近对话」即时刷新）
        ChatSync.bump()
        // 进页面时把消息流滚到最底（最新）。放在 body 内（ensureInit）而非 pageDidAppear，
        // 以确保「一定会执行」—— 部分子页生命周期下 pageDidAppear 不可靠，会导致从不滚到底。
        // 主路径由 contentSizeChanged 驱动；这里补一个延迟兜底，防止个别情况该事件不触发。
        com.tencent.kuikly.core.timer.setTimeout(pagerId, 300) { tryScrollToBottom() }
        // 外部预填问题（如个股页「AI 选股」推荐问题 chips）：进入即自动发出，复用现有对话能力
        val presetPrompt = pageData.params.optString("prompt").trim()
        if (presetPrompt.isNotEmpty()) {
            com.tencent.kuikly.core.timer.setTimeout(pagerId, 350) { ask(presetPrompt) }
        }
    }

    /**
     * 把消息流滚到底部（最新消息）。
     *
     * 关键：offset 必须「在范围内」才生效。2.7.0 的 Scroller 对超出 [0, content-viewport]
     * 的 offset 会直接忽略（不会自动 clamp），所以之前传极大值 / viewport 算成 0 时永远停在最顶。
     * 这里用 contentSizeChanged 拿到的真实内容高度，减去真实视口高度，得到精确且在范围内的 target。
     * 仅在「首屏」或「用户本就在底部附近(stickToBottom)」时跟随，看历史不打断（豆包/微信同款）。
     */
    private fun tryScrollToBottom() {
        if (!chatPositioned || stickToBottom) {
            val vh = if (viewportH > 0f) viewportH else estimateViewportH()
            if (lastContentH <= 0f || vh <= 0f) return
            // 同一内容高度已定位过（如 contentSizeChanged 连发），不再重复 setContentOffset，避免回弹
            if (chatPositioned && lastContentH == lastScrolledContentH) return
            val y = (lastContentH - vh).coerceAtLeast(0f)
            scrollerRef.view?.setContentOffset(0f, y, false)
            lastScrolledContentH = lastContentH
            chatPositioned = true
        }
    }

    /** 视口高度估算：页面高 - 返回栏(44+状态栏) - 输入栏(约48) - 键盘抬起量；用于 scroll 事件尚未回写时兜底 */
    private fun estimateViewportH(): Float {
        val sb = pagerData.statusBarHeight
        return (pagerData.pageViewHeight - (44f + sb) - 48f - keyboardH).coerceAtLeast(0f)
    }

    /**
     * 发送：追加用户消息 -> 标记等待 -> 调 LLM.chat（常驻根页桥，后台安全）-> 追加 AI 回复。
     *
     * ⚠️ 这里**刻意不用 setTimeout**：`setTimeout(pagerId, ...)` 绑定的是当前子页面，
     * 一退出聊天页定时器就被销毁 → 请求根本不会发出，这正是「发完消息就退出，AI 再也不回复」
     * 的元凶之一。现在直接发起，请求经 [com.zeriehan.kuiklystock.core.llm.AIJobCenter]
     * 挂在常驻根页面的桥上，页面关掉后照常飞行、照常回调。
     */
    private fun send() {
        val q = inputText.trim()
        if (q.isEmpty() || ChatStore.isPending(code)) return
        // 多选态下发消息：先退出多选，避免「勾选中的旧索引」与新消息列表语义错位
        if (msgSelectMode) exitMsgSelect()
        inputText = ""
        inputRef.view?.setText("")
        ChatStore.append(code, ChatStore.ChatMessage("user", q))
        ChatStore.setPending(code, true)
        // 统一走 ChatSync.bump()：本页监听刷新气泡，主框架监听刷新「最近对话」
        ChatSync.bump()

        // 传完整历史给模型作为上下文
        val history = ChatStore.messages(code)
        // ⚠️ 回调必须用具名参数 callback = {...}：混用「尾随 lambda + 具名参数 freeMode」时
        //    编译器会把尾随 lambda 误判成多余的实参（No value passed for parameter 'callback'）。
        LLM.client.chat(stock, q, history, callback = { text ->
            val reply = text.ifBlank { "（暂时没有回复，请稍后再试）" }
            // 结果写进单例 ChatStore：即使本页已销毁，重新进入也能看到这条回复
            ChatStore.append(code, ChatStore.ChatMessage("assistant", reply))
            ChatStore.setPending(code, false)
            ChatSync.bump()
            // 后台跑完的提示：若用户已退出聊天页，用常驻桥弹 toast 告知（本页桥可能已失效）
            if (destroyed) {
                AIJobCenter.toast(
                    if (freeMode) "AI 自由问答已回复，点开查看"
                    else "「${stock.name}」的 AI 已回复，点开对话查看"
                )
            }
        }, freeMode = freeMode)
    }

    /** 点按快捷问句：直接把该问句发出去（等同输入后点发送） */
    private fun ask(q: String) {
        if (ChatStore.isPending(code)) return
        inputText = q
        inputRef.view?.setText(q)
        send()
    }

    /**
     * 清空当前对话：清掉全部历史并重新开场，同时取消可能存在的等待态。
     * 仅影响本 code 的会话（自由对话与个股对话互相隔离）。
     */
    private fun clearChat() {
        // 清空会让全部勾选索引失效（操作栏会残留「已选 N」、点删除空转），必须先退出多选态
        if (msgSelectMode) exitMsgSelect()
        ChatStore.clear(code)
        ChatStore.setPending(code, false)
        updateThinkingUI(false)
        ChatStore.append(code, ChatStore.ChatMessage("assistant", "对话已清空，有什么想问的？"))
        ChatSync.bump()
        refreshMessages()
        bridgeModule.toast("已清空对话")
    }

    /** 长按气泡复制文本到剪贴板（internal：文件级扩展函数 bubble 要调用） */
    internal fun copyText(text: String) {
        bridgeModule.copyToPasteboard(text)
        bridgeModule.toast("已复制")
    }

    /** 打开消息长按菜单（记录索引 + 文本，供复制 / 删除 / 选取文字） */
    internal fun openMsgMenu(index: Int, text: String) { msgMenuIndex = index; msgMenuText = text }
    internal fun closeMsgMenu() { msgMenuIndex = null; msgMenuText = "" }
    /** AI 消息里的股票卡片点击 → 跳转个股详情页承接（Task02：聊天结果可跳转承接页） */
    internal fun openStockDetail(stock: Stock) {
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
    }
    /** 富文本行内可点股票 → 按代码跳详情（找不到则忽略） */
    internal fun openStockDetailByCode(code: String) {
        val d = JSONObject(); d.put("stockCode", code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
    }
    /**
     * 确保某只 AI 提及股已拉取真实分时（供卡片迷你走势显示，而非 mock）。去重：本会话只拉一次。
     * 拉取完成（无论成败）都翻转 renderToggle 重建消息列表，让卡片 getIntraday 读到真实分时。
     */
    internal fun ensureTrend(stock: Stock) {
        if (destroyed || stock.code in trendsRequested) return
        trendsRequested.add(stock.code)
        StockData.loadTrends(stock) {
            if (destroyed) return@loadTrends
            renderToggle = !renderToggle
            msgVersion++
        }
    }
    /** 统一更新思考态并启停「思考中」动态三点动画（避免散落赋值漏启停） */
    internal fun updateThinkingUI(thinking: Boolean) {
        if (aiThinking == thinking) return
        aiThinking = thinking
        if (thinking) startThinkingAnim() else stopThinkingAnim()
    }
    private fun startThinkingAnim() {
        if (thinkingAnimRunning || destroyed) return
        thinkingAnimRunning = true
        thinkingTick()
    }
    private fun thinkingTick() {
        if (destroyed || !aiThinking) { thinkingAnimRunning = false; return }
        thinkingDot = (thinkingDot + 1) % 3
        com.tencent.kuikly.core.timer.setTimeout(pagerId, 350) { thinkingTick() }
    }
    private fun stopThinkingAnim() {
        thinkingAnimRunning = false
        thinkingDot = 0
    }
    /** 删除某条消息：同步单例、跨页刷新「最近对话」、本页重建气泡 */
    internal fun deleteMsg(index: Int) {
        if (!::code.isInitialized) return
        ChatStore.deleteMessageAt(code, index)
        ChatSync.bump()
        refreshMessages()
    }

    // ===== 消息多选（长按菜单「多选」进入，可批量删除）=====

    /** 进入多选：关掉长按菜单，并预勾选触发长按的那条消息（符合直觉） */
    internal fun enterMsgSelect(index: Int) {
        closeMsgMenu()
        msgSelectMode = true
        selectedMsgIdx = setOf(index)
    }

    /** 退出多选：清空勾选 */
    internal fun exitMsgSelect() {
        msgSelectMode = false
        selectedMsgIdx = emptySet()
    }

    /** 勾选/取消勾选某条消息（重新赋值整个集合，触发响应式刷新） */
    internal fun toggleMsgSelect(index: Int) {
        selectedMsgIdx =
            if (selectedMsgIdx.contains(index)) selectedMsgIdx - index else selectedMsgIdx + index
    }

    /** 全选 / 取消全选 */
    internal fun toggleMsgSelectAll() {
        if (!::code.isInitialized) return
        val total = ChatStore.messages(code).size
        selectedMsgIdx =
            if (total > 0 && selectedMsgIdx.size >= total) emptySet() else (0 until total).toSet()
    }

    /** 删除已勾选的全部消息（一次性批量删，避免索引错位） */
    internal fun deleteSelectedMsgs() {
        if (!::code.isInitialized || selectedMsgIdx.isEmpty()) return
        val n = selectedMsgIdx.size
        val targets = selectedMsgIdx
        exitMsgSelect()
        ChatStore.deleteMessagesAt(code, targets)
        ChatSync.bump()
        refreshMessages()
        bridgeModule.toast("已删除 $n 条消息")
    }

    /**
     * 消息区刷新：由 [ChatSync] 监听驱动（本页监听 + 主框架监听各一份）。
     * 只在这里翻转 renderToggle，避免「直接翻转 + bump 触发监听再翻转」互相抵消。
     */
    private fun refreshMessages() {
        if (destroyed || !::code.isInitialized) return
        msgVersion++
        updateThinkingUI(ChatStore.isPending(code))
        renderToggle = !renderToggle
        tryScrollToBottom()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        // 在 body 内读取参数并初始化（保证对话按正确 stockCode 落库）
        ctx.ensureInit()
        return {
            attr {
                flex(1f)
                flexDirectionColumn()
                // 页面底色比气泡略深一档（微信同款），让 AI 的白色气泡轮廓清晰可辨
                backgroundColor(Color(0xFFEDEFF2))
                // 键盘弹出时手动把内容区底部抬起，使输入栏贴着键盘上沿（标题栏固定不动）
                paddingBottom(ctx.keyboardH)
            }
            // 消息列表的实际渲染放在 renderMessages() 中，由消息流 Scroller 内的 vif(renderToggle) 翻转重建。

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
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(UserSettings.fs(22f)); color(Color(0xFF222222)); fontWeightSemisolid() } }
                }
                Text {
                    attr {
                        text(if (ctx.freeMode) "AI 助手 · 自由问答" else ctx.stock.name)
                        fontSize(UserSettings.fs(17f)); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f)
                    }
                }
                vif({ !ctx.freeMode }) {
                    Text { attr { text(ctx.stock.code); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginLeft(8f) } }
                }
                View { attr { flex(1f) } }
                vif({ !ctx.freeMode }) {
                    Text {
                        attr {
                            text(formatPrice(ctx.stock.price))
                            fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)); fontWeightSemisolid()
                        }
                    }
                }
                // 清空：仅清当前这一份对话（个股对话与自由问答互相隔离）
                View {
                    attr { padding(4f); marginLeft(12f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.clearChat() } }
                    Text { attr { text("清空"); fontSize(UserSettings.fs(14f)); color(Color(UserSettings.themeColor)) } }
                }
            }

            // ===== 消息流 =====
            Scroller {
                ref { ctx.scrollerRef = it }
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                event {
                    // 真实内容尺寸就绪后（布局完成才触发），用「contentH - 真实视口」精确滚到底（最新）。
                    // 这是官方 setContentOffset 的正确用法；offset 必须在范围内才生效，故绝不再传极大值。
                    contentSizeChanged { _, h ->
                        ctx.lastContentH = h
                        ctx.tryScrollToBottom()
                    }
                    // 仅回写视口高度 + 是否贴底；**绝不在 scroll 事件里调 tryScrollToBottom**，
                    // 否则在底部附近会反复把位置拽回底部（滑不动/卡死），且与 contentSizeChanged
                    // 形成 setContentOffset→scroll→setContentOffset 死循环。
                    scroll { params ->
                        ctx.viewportH = params.viewHeight
                        ctx.stickToBottom = (params.contentHeight - params.offsetY - params.viewHeight) < 80f
                    }
                }
                // 关键：本版本 body 不会因 observable 变化而重跑，必须用 vif 翻转（renderToggle）
                // 强制重建消息列表内容，否则发消息后气泡永远不刷新。
                vif({ ctx.renderToggle }) { val c = this; c.renderMessages(ctx) }
                vif({ !ctx.renderToggle }) { val c = this; c.renderMessages(ctx) }
            }

            // ===== 快捷问句（点按即问，降低冷启动成本）=====
            // ⚠️ 用普通 View 分行 + flex(1f)，不用横 Scroller —— 横 Scroller 会吞掉子元素的 click。
            View {
                attr {
                    flexDirectionColumn()
                    padding(10f); paddingTop(8f); paddingBottom(2f)
                    backgroundColor(Color.WHITE)
                }
                val qs = if (ctx.freeMode) {
                    listOf("今日大盘怎么看", "当前市场主线是什么", "如何控制仓位", "短线选股思路")
                } else {
                    listOf("今日走势如何", "现在适合买入吗", "支撑压力位在哪", "量价关系怎么看")
                }
                qs.chunked(2).forEach { row ->
                    View {
                        attr { flexDirectionRow(); marginTop(6f) }
                        row.forEachIndexed { i, q ->
                            View {
                                attr {
                                    flex(1f)
                                    if (i > 0) marginLeft(6f)
                                    padding(7f); paddingLeft(10f); paddingRight(10f)
                                    borderRadius(14f)
                                    backgroundColor(Color(UserSettings.themeTint(0.12f)))
                                    justifyContentCenter(); alignItemsCenter()
                                }
                                event { click { ctx.ask(q) } }
                                Text {
                                    attr {
                                        text(q)
                                        fontSize(UserSettings.fs(12.5f))
                                        color(Color(UserSettings.themeColor))
                                        maxWidth(150f)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ===== 输入栏 =====
            View {
                attr {
                    flexDirectionRow(); alignItemsCenter()
                    padding(10f); backgroundColor(Color.WHITE)
                    border(Border(1f, BorderStyle.SOLID, Color(0xFFEEEEEE)))
                }
                Input {
                    ref { ctx.inputRef = it }
                    attr {
                        flex(1f); height(38f)
                        fontSize(UserSettings.fs(15f)); color(Color(0xFF222222))
                        backgroundColor(Color(0xFFF2F3F5)); borderRadius(19f)
                        placeholder(if (ctx.freeMode) "问大盘、行业或任意股票…" else "问点什么…")
                        placeholderColor(Color(0xFF999999))
                    }
                    event {
                        textDidChange { ctx.inputText = it.text }
                        // 键盘高度变化：抬起内容区并把最新消息滚到底部
                        keyboardHeightChange { params ->
                            ctx.keyboardH = params.height
                            ctx.tryScrollToBottom()
                        }
                    }
                }
                Button {
                    attr {
                        size(64f, 38f); marginLeft(10f); borderRadius(19f)
                        backgroundColor(
                            if (ctx.aiThinking || ctx.inputText.isBlank()) Color(UserSettings.themeTint(0.45f))
                            else Color(UserSettings.themeColor)
                        )
                        titleAttr {
                            text(if (ctx.aiThinking) "…" else "发送")
                            fontSize(UserSettings.fs(14f)); color(Color.WHITE)
                        }
                    }
                    event { click { ctx.send() } }
                }
            }

            // ===== 消息长按菜单 =====
            vif({ ctx.msgMenuIndex != null }) {
                View { attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.closeMsgMenu() } } }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val menuW = 160f
                        val left = (vw - menuW) / 2f
                        absolutePosition(top = 200f, left = left)
                        width(menuW); backgroundColor(Color.WHITE); borderRadius(10f); flexDirectionColumn()
                    }
                    val idx = ctx.msgMenuIndex!!
                    chatMsgItem("复制") { ctx.copyText(ctx.msgMenuText); ctx.closeMsgMenu() }
                    chatMsgDivider()
                    chatMsgItem("删除") { ctx.deleteMsg(idx); ctx.closeMsgMenu() }
                    chatMsgDivider()
                    chatMsgItem("选取文字") { ctx.bridgeModule.showSelectableText("选取文字", ctx.msgMenuText); ctx.closeMsgMenu() }
                    chatMsgDivider()
                    chatMsgItem("多选") { ctx.enterMsgSelect(idx) }
                    chatMsgDivider()
                    chatMsgItem("取消") { ctx.closeMsgMenu() }
                }
            }

            // ===== 消息多选操作栏（多选态出现，浮在输入栏上方）=====
            vif({ ctx.msgSelectMode }) {
                View {
                    attr {
                        absolutePosition(left = 0f, bottom = 60f)
                        width(ctx.pagerData.pageViewWidth)
                        height(52f); flexDirectionRow(); alignItemsCenter()
                        backgroundColor(Color.WHITE)
                        border(Border(1f, BorderStyle.SOLID, Color(0xFFEEEEEE)))
                        padding(0f, 12f)
                    }
                    // 全选 / 取消全选
                    View {
                        attr {
                            padding(6f, 4f, bottom = 6f, right = 4f); marginRight(8f)
                            borderRadius(8f); backgroundColor(Color(0xFFF2F3F5))
                        }
                        event { click { ctx.toggleMsgSelectAll() } }
                        Text { attr {
                            val total = ChatStore.messages(ctx.code).size
                            val all = total > 0 && ctx.selectedMsgIdx.size >= total
                            text(if (all) "取消全选" else "全选")
                            fontSize(UserSettings.fs(14f)); color(Color(0xFF333333))
                        } }
                    }
                    // 已选计数：flex(1f) 吃掉左侧空白，把右侧按钮顶到最右
                    Text { attr { text("已选 ${ctx.selectedMsgIdx.size}"); fontSize(UserSettings.fs(14f)); color(Color(0xFF222222)); flex(1f); marginRight(16f) } }
                    // 删除（红）
                    View {
                        attr {
                            padding(6f, 4f, bottom = 6f, right = 4f); marginRight(8f)
                            borderRadius(8f); backgroundColor(Color(0xFFFDECEA))
                        }
                        event { click { ctx.deleteSelectedMsgs() } }
                        Text { attr { text("删除"); fontSize(UserSettings.fs(14f)); color(Color(0xFFE54D42)) } }
                    }
                    // 取消多选
                    View {
                        attr {
                            padding(6f, 4f, bottom = 6f, right = 4f)
                            borderRadius(8f); backgroundColor(Color(0xFFF2F3F5))
                        }
                        event { click { ctx.exitMsgSelect() } }
                        Text { attr { text("取消"); fontSize(UserSettings.fs(14f)); color(Color(0xFF333333)) } }
                    }
                }
            }
        }
    }

}

/** 渲染消息列表：作为 vif 内容闭包，renderToggle 翻转时整体重建，确保发消息后气泡刷新 */
private fun ViewContainer<*, *>.renderMessages(ctx: ChatPage) {
    ctx.msgVersion // 建立依赖（保险）
    val msgs = ChatStore.messages(ctx.code)
    val maxBubbleW = (ctx.pagerData.pageViewWidth - 40f).coerceIn(200f, 300f)
    if (msgs.isEmpty()) {
        Text { attr { text("（暂无消息）"); fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)); marginTop(20f) } }
    }
    msgs.forEachIndexed { i, m -> renderMessage(ctx, i, m.role, m.text, maxBubbleW) }
    // 思考中占位气泡：动态三点（正在输入观感，thinkingDot 定时器驱动高亮轮转）
    vif({ ctx.aiThinking }) {
        View {
            attr {
                alignSelfFlexStart(); marginBottom(10f)
                padding(12f, 10f); borderRadius(12f); backgroundColor(Color(0xFFFFFFFF))
                flexDirectionRow(); alignItemsCenter()
            }
            // 三点：当前 thinkingDot 指向的点为主题色，其余浅灰（在 attr 闭包内现读 thinkingDot 以即时刷新）
            for (i in 0 until 3) {
                View {
                    attr {
                        val dot = ctx.thinkingDot == i
                        width(7f); height(7f); borderRadius(3.5f)
                        marginRight(4f)
                        backgroundColor(if (dot) Color(UserSettings.themeColor) else Color(0xFFD0D3D8))
                    }
                }
            }
            Text {
                attr {
                    text("思考中"); fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)); marginLeft(4f)
                }
            }
        }
    }
}

/**
 * 渲染单条消息：原气泡（[bubble]）+ AI 回复末尾的提及股票卡片组（Task02 发散性渲染）。
 *
 * 消息列(Scroller)是纵排容器，气泡行与卡片组作为相邻子节点天然上下排列：
 * 先渲染原气泡行，若该条是 AI 回复且文本中识别到行情池内的股票，再在其下方
 * 追加一组迷你行情卡片（点卡片跳转个股详情页）。列表每次重建(AI 回复到达触发
 * msgVersion/renderToggle 翻转)都会重扫文本，故新回答会自动带出卡片。
 */
private fun ViewContainer<*, *>.renderMessage(ctx: ChatPage, index: Int, role: String, text: String, maxBubbleW: Float) {
    bubble(ctx, index, role, text, maxBubbleW)
    if (role != "user") {
        val hits = StockMention.extract(text)
        if (hits.isNotEmpty()) {
            // 触发真实分时拉取（去重），完成后翻转重建，卡片走势从 mock 变真实
            hits.forEach { ctx.ensureTrend(it) }
            renderAiStockCards(hits, onOpen = { ctx.openStockDetail(it) })
        }
    }
}

/**
 * 单条气泡：用户右侧青色、AI 左侧灰底。
 *
 * 行作为消息列(Scroller, 默认 alignItems=STRETCH)的直接子节点，自动被拉满整行宽度；
 * 用 justifyContent 控制气泡靠右(用户)/靠左(AI)。气泡只给 maxWidth 上限，文本在其中自动换行。
 * 不显式给行宽（避免依赖 pageViewWidth 算成 0 宽）、不给气泡 flex(1f)（否则列宽未定时循环塌缩）。
 */
private fun ViewContainer<*, *>.bubble(ctx: ChatPage, index: Int, role: String, text: String, maxBubbleW: Float) {
    val isUser = role == "user"
    View {
        attr {
            flexDirectionRow()
            alignItemsCenter()
            justifyContent(if (isUser) FlexJustifyContent.FLEX_END else FlexJustifyContent.FLEX_START)
            marginBottom(10f)
        }
        // 多选态：气泡左侧的勾选圆点（选中填充主题色 + 打勾）
        vif({ ctx.msgSelectMode }) {
            View {
                attr {
                    width(20f); height(20f); borderRadius(10f); marginRight(8f)
                    alignItemsCenter(); justifyContentCenter()
                    // 关键：在 attr 闭包内**现读** observable（不能提成函数体的局部 val，否则不随勾选刷新）
                    val sel = ctx.selectedMsgIdx.contains(index)
                    border(Border(1.5f, BorderStyle.SOLID, Color(if (sel) UserSettings.themeColor else 0xFFCCCCCC)))
                    backgroundColor(if (sel) Color(UserSettings.themeColor) else Color.WHITE)
                }
                event { click { ctx.toggleMsgSelect(index) } }
                Text { attr {
                    val sel = ctx.selectedMsgIdx.contains(index)
                    text(if (sel) "✓" else ""); fontSize(UserSettings.fs(13f)); color(Color.WHITE)
                } }
            }
        }
        View {
            attr {
                maxWidth(maxBubbleW)
                flexDirectionColumn()   // AI 富文本需纵向容纳多个内容块；对用户单 Text 也等效
                padding(10f)
                borderRadius(12f)
                // AI 气泡用「灰白」底：页面背景是 0xFFF2F3F5（浅灰），气泡用近白 0xFFFAFBFC 会几乎融进背景，
                // 故直接用纯白 0xFFFFFFFF —— 在浅灰页面上呈现为清晰可辨的「灰白色气泡」（微信同款观感）。
                // 用户气泡跟随个性化主题色（长按时可复制文本）。
                backgroundColor(if (isUser) Color(UserSettings.themeColor) else Color(0xFFFFFFFF))
            }
            event {
                // 长按弹出操作菜单（复制 / 删除 / 选取文字 / 多选）；多选态下改由点按勾选，故屏蔽长按菜单
                longPress { if (!ctx.msgSelectMode) ctx.openMsgMenu(index, text) }
                click { if (ctx.msgSelectMode) ctx.toggleMsgSelect(index) }
            }
            if (isUser) {
                Text {
                    attr {
                        text(text)
                        fontSize(UserSettings.fs(14f))
                        color(Color.WHITE)
                        // 关键：文本必须显式限宽，否则在「气泡只给 maxWidth、自身宽自适应」的情况下不换行，
                        // 长内容只显示一行并溢出气泡（右侧被裁掉）。20f 为左右内边距。
                        maxWidth(maxBubbleW - 20f)
                    }
                }
            } else {
                // AI 气泡：KRMarkdown 富文本渲染（标题/列表/引用/代码 + 行内加粗/可点股票），
                // 兼容历史纯文本/AI 开场白（按单段渲染）。点行内股票跳详情。
                renderMarkdown(
                    text = text,
                    contentW = maxBubbleW - 20f,
                    textColor = Color(0xFF333333),
                    accent = Color(UserSettings.themeColor),
                    onOpenStock = { code -> ctx.openStockDetailByCode(code) },
                )
            }
        }
    }
    }

    // ===== 消息长按菜单项辅助 =====
    private fun ViewContainer<*, *>.chatMsgItem(label: String, onClick: () -> Unit) {
        View { attr { height(48f); justifyContentCenter(); paddingLeft(16f) }
            event { click { onClick() } }
            Text { attr { text(label); fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)) } }
        }
    }
    private fun ViewContainer<*, *>.chatMsgDivider() {
        View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
    }
