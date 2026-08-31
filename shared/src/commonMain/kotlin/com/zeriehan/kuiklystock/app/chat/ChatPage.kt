package com.zeriehan.kuiklystock.app.chat

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.layout.FlexJustifyContent
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.compose.Button
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
 */
@Page("Chat", supportInLocal = true)
internal class ChatPage : BasePager() {

    private lateinit var code: String
    private lateinit var stock: Stock

    /** 输入框当前文本（响应式，send 按钮据此启用） */
    private var inputText: String by observable("")
    /** AI 思考中：禁用发送、显示「思考中…」 */
    private var aiThinking: Boolean by observable(false)
    /** 消息版本号：每次增删消息 +1，body 据此重新读取 ChatStore 渲染最新对话 */
    private var msgVersion: Int by observable(0)
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

    /** 发送：追加用户消息 -> 调 LLM.chat -> 追加 AI 回复 */
    private fun send() {
        val q = inputText.trim()
        if (q.isEmpty() || aiThinking) return
        inputText = ""
        inputRef.view?.setText("")
        ChatStore.append(code, ChatStore.ChatMessage("user", q))
        msgVersion++
        scrollToBottom()
        ChatSync.bump()
        aiThinking = true
        // 传完整历史给模型作为上下文
        val history = ChatStore.messages(code)
        // 捕获当前页 id：模型结果由宿主在后台线程回调，必须切回渲染线程再改 observable，
        // 否则不会触发重渲染（表现为一直「分析中」、需重进才看到消息）。
        val pid = BridgeManager.currentPageId
        LLM.client.chat(stock, q, history) { text ->
            val reply = text.ifBlank { "（暂时没有回复，请稍后再试）" }
            com.tencent.kuikly.core.timer.setTimeout(pid, 0) {
                ChatStore.append(code, ChatStore.ChatMessage("assistant", reply))
                msgVersion++
                scrollToBottom()
                ChatSync.bump()
                aiThinking = false
            }
        }
    }

    override fun body(): ViewBuilder {
        val ctx = this
        // 在 body 内读取参数并初始化（保证对话按正确 stockCode 落库）
        ctx.ensureInit()
        // 气泡可用宽度：Scroller 两侧各 12 内边距，内容宽 = pageViewWidth - 24；
        // 单条气泡最大宽度封顶 264，避免长文本把屏幕占满。
        val contentW = ctx.pagerData.pageViewWidth - 24f
        val maxBubbleW = contentW.coerceAtMost(264f)
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(Color(0xFFF2F3F5))
            }
            // 在「渲染闭包内」读取消息版本号建立依赖（关键：在闭包外读可能不触发重渲染，
            // 表现为发消息后界面不刷新、卡在「分析中」）。
            ctx.msgVersion
            val msgs = ChatStore.messages(ctx.code)

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
                val c = this
                if (msgs.isEmpty()) {
                    Text { attr { text("（暂无消息）"); fontSize(13f); color(Color(0xFF999999)); marginTop(20f) } }
                }
                msgs.forEach { m ->
                    c.bubble(m.role, m.text, maxBubbleW, contentW)
                }
                // 思考中占位气泡
                vif({ ctx.aiThinking }) {
                    View {
                        attr { flexDirectionRow(); marginBottom(10f) }
                        View {
                            attr { padding(10f); borderRadius(12f); backgroundColor(Color(0xFFF2F3F5)) }
                            Text { attr { text("AI 思考中…"); fontSize(14f); color(Color(0xFF999999)) } }
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
                        fontSize(15f); color(Color(0xFF222222))
                        backgroundColor(Color(0xFFF2F3F5)); borderRadius(19f)
                        placeholder("问点什么…")
                        placeholderColor(Color(0xFF999999))
                    }
                    event { textDidChange { ctx.inputText = it.text } }
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

/**
 * 单条气泡：用户右侧青色、AI 左侧灰底。
 * 关键：气泡所在「行」必须有确定宽度（Scroller 内子元素默认不横向拉伸，否则整行塌缩成最左 1 字宽），
 * 气泡自身给一个确定宽度，长文本才能在气泡内自动换行、整条消息完整可见。
 * 不使用 flex(1f)（在宽度未定的行里会让整行塌缩成 1 字）。
 */
private fun ViewContainer<*, *>.bubble(role: String, text: String, maxBubbleW: Float, rowW: Float) {
    val isUser = role == "user"
    View {
        attr {
            flexDirectionRow()
            marginBottom(10f)
            justifyContent(if (isUser) FlexJustifyContent.FLEX_END else FlexJustifyContent.FLEX_START)
            width(rowW)
        }
        View {
            attr {
                width(maxBubbleW)
                padding(10f)
                borderRadius(12f)
                backgroundColor(if (isUser) Color(0xFF23D3FD) else Color(0xFFF2F3F5))
            }
            Text {
                attr {
                    text(text)
                    fontSize(14f)
                    color(if (isUser) Color.WHITE else Color(0xFF333333))
                }
            }
        }
    }
}
