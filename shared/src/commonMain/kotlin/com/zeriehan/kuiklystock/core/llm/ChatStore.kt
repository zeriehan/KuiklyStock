package com.zeriehan.kuiklystock.core.llm

/**
 * AI 聊天会话存储（按股票代码隔离）。
 *
 * 行情页长按某只股票选「问 AI」、与详情页点「深入聊聊这只股票」，
 * 都打开同一个 [com.zeriehan.kuiklystock.app.chat.ChatPage] 并传入同一 stockCode，
 * 因此读取/追加的是同一段对话，做到「同一个对话」。
 *
 * 进程内内存存储：app 进程被杀后对话会清空（当前阶段不持久化聊天记录，演示足够）。
 */
object ChatStore {

    private val conversations = mutableMapOf<String, MutableList<ChatMessage>>()

    /** 取某股票的完整对话（不可变列表，便于在 attr 闭包内直接读取渲染） */
    fun messages(code: String): List<ChatMessage> = conversations[code] ?: emptyList()

    /** 是否存在该股票对话（用于「最近对话」列表判断） */
    fun hasConversation(code: String): Boolean = conversations[code]?.isNotEmpty() == true

    /** 追加一条消息 */
    fun append(code: String, msg: ChatMessage) {
        conversations.getOrPut(code) { mutableListOf() }.add(msg)
    }

    /** 最后一条消息（用于「最近对话」预览） */
    fun last(code: String): ChatMessage? = conversations[code]?.lastOrNull()

    /** 清空某股票对话（可选） */
    fun clear(code: String) {
        conversations.remove(code)
    }

    data class ChatMessage(
        /** "user" = 用户提问；"assistant" = AI 回复 */
        val role: String,
        val text: String,
    )
}
