package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * AI 聊天会话存储（按股票代码隔离），**已持久化**到 SharedPreferences。
 *
 * 行情页长按某只股票选「问 AI」、与详情页点「深入聊聊这只股票」，
 * 都打开同一个 [com.zeriehan.kuiklystock.app.chat.ChatPage] 并传入同一 stockCode，
 * 因此读取/追加的是同一段对话，做到「同一个对话」。
 *
 * 持久化：由主框架（MainTabPager）在 `viewDidLoad` 调用 [attach] 注入 SharedPreferences 句柄，
 * 之后每次 append/clear 都会落盘；下次冷启动 attach 时自动从磁盘恢复，
 * 解决「重新进入 App 后『AI』Tab 的聊天记录消失」的问题。
 *
 * 未注入 prefs 时退化为纯内存存储（不影响单进程内的功能）。
 */
object ChatStore {

    private const val KEY_CHAT = "kb_chat_log"

    // 三级分隔符（控制字符，正文里几乎不可能出现；仍做转义兜底）
    private const val SEP_CONV = "\u001E" // 会话之间
    private const val SEP_MSG = "\u001F"  // 消息之间
    private const val SEP_KV = "\u001D"   // 键值之间（code / role 与 text）

    /** 单只股票最多保留的消息条数，避免 SharedPreferences 无限膨胀 */
    private const val MAX_MSGS_PER_CODE = 200

    /** 用 LinkedHashMap 保证「最近对话」按产生顺序展示 */
    private val conversations = linkedMapOf<String, MutableList<ChatMessage>>()

    private var prefs: SharedPreferencesModule? = null

    // ===== 持久化 =====

    /** 注入存储句柄并从磁盘恢复；重复调用只恢复一次 */
    fun attach(preferences: SharedPreferencesModule) {
        if (prefs === preferences) return
        prefs = preferences
        load()
    }

    private fun load() {
        val p = prefs ?: return
        val raw = p.getItem(KEY_CHAT)
        conversations.clear()
        if (raw.isBlank()) return
        raw.split(SEP_CONV).forEach { conv ->
            if (conv.isBlank()) return@forEach
            val head = conv.split(SEP_KV, limit = 2)
            val code = head.getOrNull(0)?.trim() ?: ""
            if (code.isEmpty()) return@forEach
            val list = mutableListOf<ChatMessage>()
            val body = head.getOrNull(1) ?: ""
            if (body.isNotBlank()) {
                body.split(SEP_MSG).forEach { raw1 ->
                    if (raw1.isBlank()) return@forEach
                    val kv = raw1.split(SEP_KV, limit = 2)
                    val role = kv.getOrNull(0) ?: "assistant"
                    val text = unesc(kv.getOrNull(1) ?: "")
                    if (text.isNotBlank()) list.add(ChatMessage(role, text))
                }
            }
            if (list.isNotEmpty()) conversations[code] = list
        }
    }

    private fun save() {
        val p = prefs ?: return
        val raw = conversations.entries.joinToString(SEP_CONV) { (code, list) ->
            code + SEP_KV + list.joinToString(SEP_MSG) { m -> m.role + SEP_KV + esc(m.text) }
        }
        p.setItem(KEY_CHAT, raw)
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(SEP_KV, "")
        .replace(SEP_MSG, "")
        .replace(SEP_CONV, "")

    private fun unesc(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> { sb.append('\\'); i += 2 }
                    'n' -> { sb.append('\n'); i += 2 }
                    else -> { sb.append(s[i]); i += 1 }
                }
            } else {
                sb.append(s[i]); i += 1
            }
        }
        return sb.toString()
    }

    // ===== 对外 API =====

    /** 取某股票的完整对话（不可变列表，便于在 attr 闭包内直接读取渲染） */
    fun messages(code: String): List<ChatMessage> = conversations[code] ?: emptyList()

    /** 是否存在该股票对话（用于「最近对话」列表判断） */
    fun hasConversation(code: String): Boolean = conversations[code]?.isNotEmpty() == true

    /** 追加一条消息（自动落盘 + 超量裁剪） */
    fun append(code: String, msg: ChatMessage) {
        val list = conversations.getOrPut(code) { mutableListOf() }
        list.add(msg)
        if (list.size > MAX_MSGS_PER_CODE) {
            val drop = list.size - MAX_MSGS_PER_CODE
            repeat(drop) { list.removeAt(0) }
        }
        save()
    }

    /** 最后一条消息（用于「最近对话」预览） */
    fun last(code: String): ChatMessage? = conversations[code]?.lastOrNull()

    /** 所有有对话的股票代码（用于「最近对话」列表，避免依赖外部股票表过滤导致漏显） */
    fun conversationCodes(): List<String> =
        conversations.filter { it.value.isNotEmpty() }.keys.toList()

    /** 清空某股票对话（自动落盘） */
    fun clear(code: String) {
        conversations.remove(code)
        save()
    }

    data class ChatMessage(
        /** "user" = 用户提问；"assistant" = AI 回复 */
        val role: String,
        val text: String,
    )
}
