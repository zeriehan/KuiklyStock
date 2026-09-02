package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * 用户本地标签 / 隐藏状态持久化层（SharedPreferences 支撑，跨 app 重启保留）。
 *
 * - 自选（watchlist）：用户长按「加自选」打的内部标签，对应股票进入「自选」Tab。
 * - 不感兴趣（hidden）：用户长按「不感兴趣」后暂时隐藏的股票（code -> 隐藏时刻 ms），
 *   到达「自动恢复天数」后自动重新出现在行情/自选列表；也可在「我的-设置」里手动恢复。
 *
 * 本对象只负责「序列化 / 反序列化」，不持有可响应式的状态；响应式镜像由使用方
 * （MainTabPager）用 observable 字段维护，并在每次变更后调用本对象的 saveXxx 落盘。
 */
internal object UserStockStore {

    const val KEY_WATCH = "kb_watchlist"
    const val KEY_HIDDEN = "kb_hidden"
    const val KEY_HIDE_DAYS = "kb_hide_days"
    const val KEY_FOLLOW_SECTORS = "kb_follow_sectors"

    fun loadWatchlist(prefs: SharedPreferencesModule): Set<String> {
        val raw = prefs.getItem(KEY_WATCH)
        if (raw.isBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun loadHidden(prefs: SharedPreferencesModule): Map<String, Long> {
        val raw = prefs.getItem(KEY_HIDDEN)
        if (raw.isBlank()) return emptyMap()
        return raw.split(',').mapNotNull { kv ->
            val (c, t) = kv.split(':')
            if (c.isNotBlank()) c to (t.toLongOrNull() ?: 0L) else null
        }.toMap()
    }

    fun loadHideDays(prefs: SharedPreferencesModule): Int {
        return prefs.getItem(KEY_HIDE_DAYS).toIntOrNull() ?: 7
    }

    fun saveWatchlist(prefs: SharedPreferencesModule, set: Set<String>) {
        prefs.setItem(KEY_WATCH, set.joinToString(","))
    }

    fun saveHidden(prefs: SharedPreferencesModule, map: Map<String, Long>) {
        prefs.setItem(KEY_HIDDEN, map.map { (c, t) -> "$c:$t" }.joinToString(","))
    }

    fun saveHideDays(prefs: SharedPreferencesModule, days: Int) {
        prefs.setItem(KEY_HIDE_DAYS, days.toString())
    }

    // ===== 关注板块（code CSV）=====
    fun loadFollowSectors(prefs: SharedPreferencesModule): Set<String> {
        val raw = prefs.getItem(KEY_FOLLOW_SECTORS)
        if (raw.isBlank()) return emptySet()
        return raw.split(',').filter { it.isNotBlank() }.toSet()
    }

    fun saveFollowSectors(prefs: SharedPreferencesModule, set: Set<String>) {
        prefs.setItem(KEY_FOLLOW_SECTORS, set.joinToString(","))
    }
}
