package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * 详情页 AI 分析按股票代码做缓存：
 * - 进程内内存缓存：同一 app 会话内首次进入自动分析，之后进入直接展示，不重复调模型；
 * - **已持久化**到 SharedPreferences：冷启动后仍读取磁盘缓存，避免每次杀进程重进都再等几秒网络。
 *
 * 持久化由主框架（MainTabPager）在 `viewDidLoad` 调用 [attach] 注入句柄，
 * 之后每次 [put] 自动落盘；[attach] 时从磁盘恢复。
 *
 * 未注入 prefs 时退化为纯内存缓存（不影响单进程内功能）。
 */
internal object AIAnalysisStore {

    private const val KEY_AI = "kb_ai_analysis"
    private const val SEP_ENTRY = "\u001E" // 不同股票之间
    private const val SEP_KV = "\u001D"    // code / text / timeText 之间

    private val cache = linkedMapOf<String, Entry>()

    private var prefs: SharedPreferencesModule? = null

    /** 注入存储句柄并从磁盘恢复；重复调用只恢复一次 */
    fun attach(preferences: SharedPreferencesModule) {
        if (prefs === preferences) return
        prefs = preferences
        load()
    }

    private fun load() {
        val p = prefs ?: return
        val raw = p.getItem(KEY_AI)
        cache.clear()
        if (raw.isBlank()) return
        raw.split(SEP_ENTRY).forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(SEP_KV, limit = 3)
            val code = parts.getOrNull(0)?.trim() ?: ""
            if (code.isEmpty()) return@forEach
            val text = unesc(parts.getOrNull(1) ?: "")
            val timeText = unesc(parts.getOrNull(2) ?: "")
            if (text.isNotBlank()) cache[code] = Entry(text, timeText)
        }
    }

    private fun save() {
        val p = prefs ?: return
        val raw = cache.entries.joinToString(SEP_ENTRY) { (code, e) ->
            code + SEP_KV + esc(e.text) + SEP_KV + esc(e.timeText)
        }
        p.setItem(KEY_AI, raw)
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace(SEP_KV, "")
        .replace(SEP_ENTRY, "")

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

    fun get(code: String): Entry? = cache[code]

    fun put(code: String, text: String, timeText: String) {
        cache[code] = Entry(text, timeText)
        save()
    }

    data class Entry(val text: String, val timeText: String)
}
