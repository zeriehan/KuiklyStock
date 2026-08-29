package com.zeriehan.kuiklystock.core.llm

/**
 * 详情页 AI 分析按股票代码做「进程内」缓存：
 * - 第一次进入某只股票：自动分析；
 * - 之后再次进入（同一 app 会话内）：直接展示缓存结果 + 分析时间，不再调模型，
 *   既避免每次进详情页都重新请求（慢、闪动、浪费免费额度），也符合「第一次自动、之后手动刷新」的诉求。
 *
 * 注意：这是内存缓存，app 进程被杀后失效，下次进入会重新自动分析一次。
 */
internal object AIAnalysisStore {

    private val cache = mutableMapOf<String, Entry>()

    fun get(code: String): Entry? = cache[code]

    fun put(code: String, text: String, timeText: String) {
        cache[code] = Entry(text, timeText)
    }

    data class Entry(val text: String, val timeText: String)
}
