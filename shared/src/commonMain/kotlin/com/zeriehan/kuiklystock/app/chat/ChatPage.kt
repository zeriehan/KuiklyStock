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
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.MockStockSource
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.llm.ChatStore
import com.zeriehan.kuiklystock.core.llm.ChatSync
import com.zeriehan.kuiklystock.core.llm.LLM
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.manager.BridgeManager

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
 *    故 chat 回调里直接改 observable，不再嵌套 setTimeout 切线程（那层在子页面不可靠，曾导致「分析完什么都不显示」）。
 *    仅用 setTimeout 做一次「思考」延迟，让加载态可见。
 * 4. 键盘：宿主设 adjustNothing，这里监听 keyboardHeightChange 手动把内容区底部抬起(paddingBottom)，
 *    标题栏固定不动、仅输入栏贴着键盘上沿。
 */
@Page("Chat", supportInLocal = true)
internal class ChatPage : BasePager() {

    internal lateinit var code: String
    private lateinit var stock: Stock

    /** 输入框当前文本（响应式，发送按钮据此启用） */
    private var inputText: String by observable("")
    /** AI 思考中：禁用发送、显示「思考中…」 */
    internal var aiThinking: Boolean by observable(false)
    /** 消息版本号：每次增删消息 +1，配合 renderToggle 翻转强制重建消息列表 */
    internal var msgVersion: Int by observable(0)
    /** vif 翻转触发器：本版本 body 不随 observable 重跑，消息列表必须靠 vif 翻转才能强制重建 */
    internal var renderToggle: Boolean by observable(false)
    /** 键盘高度：弹出时把内容区底部抬起，使输入栏贴着键盘上沿（标题固定不动） */
    private var keyboardH: Float by observable(0f)
    /** 输入框 ref，用于发送后清空 */
    private lateinit var inputRef: ViewRef<InputView>
    /** 消息流 Scroller ref，用于新消息到达时滚动到底部 */
    private lateinit var scrollerRef: ViewRef<ScrollerView<*, *>>
    /** 是否已初始化（参数须在 body 内读取，故用此标志保证仅初始化一次） */
    private var bootstrapped: Boolean = false

    override fun created() {
        super.created()
    }

    /**
     * 在 body 首次调用时读取参数并补一句 AI 开场白。
     * Kuikly 的 pageData.params 须在 body 作用域内读取（created 中可能为空白），
     * 因此把参数读取与「最近对话」写库放到这里，确保对话按正确 stockCode 落库。
     */
    private fun ensureInit() {
        if (bootstrapped) return
        code = pageData.params.optString("stockCode")
        stock = MockStockSource.findByCode(code)
        if (ChatStore.messages(code).isEmpty()) {
            ChatStore.append(
                code,
                ChatStore.ChatMessage(
                    "assistant",
                    "你好，我是 ${stock.name} 的 AI 助手。关于这只股票（现价 ${formatPrice(stock.price)}，" +
                        "今日${if (stock.changePercent >= 0f) "涨" else "跌"}${formatPercent(
                            kotlin.math.abs(stock.changePercent)
                        )}），有什么想问的？"
                )
            )
        }
        bootstrapped = true
        // 通知主框架：本股票已有对话（用于「最近对话」即时刷新）
        ChatSync.bump()
    }

    /** 滚动消息流到底部（新消息到达时调用，确保不被 Scroller 视口截断） */
    private fun scrollToBottom() {
        // offsetY 给极大值，由原生 Scroller 自动 clamp 到内容底部
        scrollerRef.view?.setContentOffset(0f, 100000f, true)
    }

    /** 延迟一帧再滚到底，等 vif 翻转后的列表完成布局 */
    private fun scrollSoon() {
        val pid = BridgeManager.currentPageId
        com.tencent.kuikly.core.timer.setTimeout(pid, 80) { scrollToBottom() }
    }

    /** 发送：追加用户消息 -> 调 LLM.chat -> 追加 AI 回复 */
    private fun send() {
        val q = inputText.trim()
        if (q.isEmpty() || aiThinking) return
        inputText = ""
        inputRef.view?.setText("")
        ChatStore.append(code, ChatStore.ChatMessage("user", q))
        msgVersion++
        renderToggle = !renderToggle
        scrollSoon()
        ChatSync.bump()
        aiThinking = true
        // 传完整历史给模型作为上下文
        val history = ChatStore.messages(code)
        // 宿主 KRBridgeModule.llmAnalyze 已在子线程请求、并在主线程（Handler post）回调，
        // Mock 同步也是主线程；因此 chat 回调里可直接改 observable，无需再嵌套 setTimeout 切线程
        // （之前多套的那层 setTimeout 在子页面不可靠，正是「分析完什么都不显示」的根因）。
        // 这里仅用 setTimeout 做一段「思考」延迟，让加载态可见。
        val pid = BridgeManager.currentPageId
        com.tencent.kuikly.core.timer.setTimeout(pid, 600) {
            LLM.client.chat(stock, q, history) { text ->
                val reply = text.ifBlank { "（暂时没有回复，请稍后再试）" }
                ChatStore.append(code, ChatStore.ChatMessage("assistant", reply))
                msgVersion++
                renderToggle = !renderToggle
                scrollSoon()
                ChatSync.bump()
                aiThinking = false
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        // 在 body 内读取参数并初始化（保证对话按正确 stockCode 落库）
        ctx.ensureInit()
        return {
            attr {
                flex(1f)
                flexDirectionColumn()
                backgroundColor(Color(0xFFF2F3F5))
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
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemisolid() } }
                }
                Text { attr { text(ctx.stock.name); fontSize(17f); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f) } }
                Text { attr { text(ctx.stock.code); fontSize(12f); color(Color(0xFF999999)); marginLeft(8f) } }
                View { attr { flex(1f) } }
                Text {
                    attr {
                        text(formatPrice(ctx.stock.price))
                        fontSize(15f); color(Color(0xFF222222)); fontWeightSemisolid()
                    }
                }
            }

            // ===== 消息流 =====
            Scroller {
                ref { ctx.scrollerRef = it }
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                // 关键：本版本 body 不会因 observable 变化而重跑，必须用 vif 翻转（renderToggle）
                // 强制重建消息列表内容，否则发消息后气泡永远不刷新。
                vif({ ctx.renderToggle }) { val c = this; c.renderMessages(ctx) }
                vif({ !ctx.renderToggle }) { val c = this; c.renderMessages(ctx) }
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
                        fontSize(15f); color(Color(0xFF222222))
                        backgroundColor(Color(0xFFF2F3F5)); borderRadius(19f)
                        placeholder("")
                        placeholderColor(Color(0xFF999999))
                    }
                    event {
                        textDidChange { ctx.inputText = it.text }
                        // 键盘高度变化：抬起内容区并把最新消息滚到底部
                        keyboardHeightChange { params ->
                            ctx.keyboardH = params.height
                            ctx.scrollToBottom()
                        }
                    }
                }
                Button {
                    attr {
                        size(64f, 38f); marginLeft(10f); borderRadius(19f)
                        backgroundColor(if (ctx.aiThinking || ctx.inputText.isBlank()) Color(0xFFB9E6F5) else Color(0xFF23D3FD))
                        titleAttr { text("发送"); fontSize(14f); color(Color.WHITE) }
                    }
                    event { click { ctx.send() } }
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
        Text { attr { text("（暂无消息）"); fontSize(13f); color(Color(0xFF999999)); marginTop(20f) } }
    }
    msgs.forEach { m -> bubble(m.role, m.text, maxBubbleW) }
    // 思考中占位气泡
    vif({ ctx.aiThinking }) {
        View {
            attr {
                alignSelfFlexStart(); marginBottom(10f)
                padding(10f); borderRadius(12f); backgroundColor(Color(0xFFF2F3F5))
            }
            Text { attr { text("AI 思考中…"); fontSize(14f); color(Color(0xFF999999)) } }
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
private fun ViewContainer<*, *>.bubble(role: String, text: String, maxBubbleW: Float) {
    val isUser = role == "user"
    View {
        attr {
            flexDirectionRow()
            justifyContent(if (isUser) FlexJustifyContent.FLEX_END else FlexJustifyContent.FLEX_START)
            marginBottom(10f)
        }
        View {
            attr {
                maxWidth(maxBubbleW)
                padding(10f)
                borderRadius(12f)
                backgroundColor(if (isUser) Color(0xFF23D3FD) else Color(0xFFF2F3F5))
            }
            Text {
                attr {
                    text(text)
                    fontSize(14f)
                    color(if (isUser) Color.WHITE else Color(0xFF333333))
                    // 关键：文本必须显式限宽，否则在「气泡只给 maxWidth、自身宽自适应」的情况下不换行，
                    // 长内容只显示一行并溢出气泡（右侧被裁掉）。20f 为左右内边距。
                    maxWidth(maxBubbleW - 20f)
                }
            }
        }
    }
}
