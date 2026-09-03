package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.zeriehan.kuiklystock.core.StockData

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

    /**
     * 正在等待 AI 回复的股票代码。
     * 刻意**只存内存、不落盘**：进程被杀时自动清空，避免下次启动卡在"AI 思考中…"；
     * 而同一次会话内退出聊天页再回来，思考态仍保留（配合"后台继续跑"）。
     */
    private val pending = mutableSetOf<String>()

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
        loadMeta()
    }

    private fun save() {
        val p = prefs ?: return
        val raw = conversations.entries.joinToString(SEP_CONV) { (code, list) ->
            code + SEP_KV + list.joinToString(SEP_MSG) { m -> m.role + SEP_KV + esc(m.text) }
        }
        p.setItem(KEY_CHAT, raw)
        saveMeta()
    }

    // ===== 分组 / 置顶 / 重命名 元数据持久化 =====
    private const val KEY_GROUPS = "kb_chat_groups"
    private const val KEY_META = "kb_chat_meta"

    data class ConvGroup(val id: String, var name: String)

    private data class Meta(var customName: String? = null, var groupId: String = "", var pinned: Boolean = false)

    private val groups = mutableListOf<ConvGroup>()
    private val convMeta = linkedMapOf<String, Meta>()

    private fun loadMeta() {
        val p = prefs ?: return
        groups.clear(); convMeta.clear()
        val g = p.getItem(KEY_GROUPS)
        if (g.isNotBlank()) g.split(SEP_CONV).forEach { seg ->
            if (seg.isBlank()) return@forEach
            val kv = seg.split(SEP_KV, limit = 2)
            val id = kv.getOrNull(0)?.trim() ?: return@forEach
            if (id.isEmpty()) return@forEach
            groups.add(ConvGroup(id, unesc(kv.getOrNull(1) ?: "分组")))
        }
        val m = p.getItem(KEY_META)
        if (m.isNotBlank()) m.split(SEP_CONV).forEach { seg ->
            if (seg.isBlank()) return@forEach
            val parts = seg.split(SEP_KV)
            val code = parts.getOrNull(0)?.trim() ?: return@forEach
            if (code.isEmpty()) return@forEach
            val customName = parts.getOrNull(1)?.let { unesc(it) }.takeIf { !it.isNullOrBlank() }
            val groupId = parts.getOrNull(2)?.trim() ?: ""
            val pinned = parts.getOrNull(3)?.trim() == "1"
            convMeta[code] = Meta(customName, groupId, pinned)
        }
    }

    private fun saveMeta() {
        val p = prefs ?: return
        val g = groups.joinToString(SEP_CONV) { it.id + SEP_KV + esc(it.name) }
        p.setItem(KEY_GROUPS, g)
        val m = convMeta.entries.joinToString(SEP_CONV) { (code, meta) ->
            code + SEP_KV + esc(meta.customName ?: "") + SEP_KV + meta.groupId + SEP_KV + (if (meta.pinned) "1" else "0")
        }
        p.setItem(KEY_META, m)
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

    // ===== 分组 / 置顶 / 重命名 / 删除 =====

    /** 有序代码列表：置顶的在前（保持插入序），其余在后 */
    fun orderedCodes(): List<String> {
        val keys = conversations.keys.filter { it.isNotBlank() }
        val pinned = keys.filter { isPinned(it) }
        val rest = keys.filter { !isPinned(it) }
        return pinned + rest
    }

    fun isPinned(code: String): Boolean = convMeta[code]?.pinned == true

    fun togglePin(code: String): Boolean {
        val m = convMeta.getOrPut(code) { Meta() }
        m.pinned = !m.pinned
        saveMeta()
        return m.pinned
    }

    fun setPinned(code: String, value: Boolean) {
        val m = convMeta.getOrPut(code) { Meta() }
        if (m.pinned != value) { m.pinned = value; saveMeta() }
    }

    /** 展示名：重命名优先；其次自由问答固定；否则股票名 */
    fun displayName(code: String): String {
        val meta = convMeta[code]
        if (meta?.customName?.isNotBlank() == true) return meta.customName!!
        if (code == "free") return "AI 自由问答"
        return StockData.findByCode(code).name
    }

    fun setCustomName(code: String, name: String) {
        val m = convMeta.getOrPut(code) { Meta() }
        m.customName = name.trim().ifBlank { null }
        saveMeta()
    }

    fun groupOf(code: String): String = convMeta[code]?.groupId ?: ""

    fun setGroup(code: String, groupId: String) {
        val m = convMeta.getOrPut(code) { Meta() }
        m.groupId = groupId
        saveMeta()
    }

    fun groups(): List<ConvGroup> = groups.toList()

    fun groupName(id: String): String = groups.find { it.id == id }?.name ?: "未分组"

    fun createGroup(name: String): ConvGroup {
        val g = ConvGroup("g${System.currentTimeMillis()}", name.trim().ifBlank { "新分组" })
        groups.add(g); saveMeta(); return g
    }

    fun renameGroup(id: String, name: String) {
        groups.find { it.id == id }?.let { it.name = name.trim().ifBlank { "新分组" } }
        saveMeta()
    }

    fun deleteGroup(id: String) {
        groups.removeAll { it.id == id }
        convMeta.values.forEach { if (it.groupId == id) it.groupId = "" }
        saveMeta()
    }

    /** 彻底删除某段对话（含消息与元数据） */
    fun deleteConversation(code: String) {
        conversations.remove(code)
        pending.remove(code)
        convMeta.remove(code)
        save(); saveMeta()
    }

    /** 删除单条消息（按索引） */
    fun deleteMessageAt(code: String, index: Int) {
        val list = conversations[code] ?: return
        if (index in list.indices) { list.removeAt(index); save() }
    }

    /** 清空某股票对话（自动落盘） */
    fun clear(code: String) {
        conversations.remove(code)
        pending.remove(code)
        save()
    }

    /** 该股票是否正在等待 AI 回复（跨页面：退出聊天页后仍为 true，直到回复回来） */
    fun isPending(code: String): Boolean = code in pending

    /** 标记/取消「等待 AI 回复」 */
    fun setPending(code: String, value: Boolean) {
        if (value) pending.add(code) else pending.remove(code)
    }

    data class ChatMessage(
        /** "user" = 用户提问；"assistant" = AI 回复 */
        val role: String,
        val text: String,
    )
}
