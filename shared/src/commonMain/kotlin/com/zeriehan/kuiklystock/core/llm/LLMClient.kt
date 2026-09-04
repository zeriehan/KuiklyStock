package com.zeriehan.kuiklystock.core.llm

import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock

/**
 * AI 分析客户端抽象。
 *
 * - [analyze]：基于量价数据生成一段结构化分析文本（详情页卡片、行情行右滑面板用）。
 * - [chat]：多轮问答。传入股票上下文 + 历史消息 + 用户当前问题，返回 AI 回复文本。
 *   两者都通过回调返回纯文本（本工程无 Markdown 渲染组件，统一用 Text 直出）。
 * - 离线兜底实现为股票感知的 [MockLLMClient]；真实模型接入见 [GLMFlashClient]。
 */
interface LLMClient {
    fun analyze(stock: Stock, kline: List<KLineBar>, callback: (String) -> Unit)

    /**
     * 多轮问答。
     * @param freeMode 自由对话模式：不绑定具体个股，可问大盘/宏观/选股思路等泛财经问题。
     *                 此时 [stock] 仅作为兜底上下文传入（可为任意标的），实现方不应把它当唯一话题。
     * @param onDelta 流式增量回调（可选）：真实模型边生成边多次回调累计全文，用于"逐字蹦出"；
     *                本地 Mock 一次性生成则只回调一次完整文本。任何情况下 [callback] 都会在
     *                最终收尾时恰好调用一次（传最终完整文本）。
     */
    fun chat(
        stock: Stock,
        question: String,
        history: List<ChatStore.ChatMessage>,
        callback: (String) -> Unit,
        freeMode: Boolean = false,
        onDelta: ((String) -> Unit)? = null,
    )
}
