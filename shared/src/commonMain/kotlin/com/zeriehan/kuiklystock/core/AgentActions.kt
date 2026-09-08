package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * AI 操控 app 的执行层（Agent Actions）。
 *
 * 模型决策的 Agent（[com.zeriehan.kuiklystock.core.llm.AgentChat]）判断用户要执行某个 app 操作后，
 * 调用本对象的各方法执行**真实操作**（写持久化存储），并返回一句确认文本。
 * 加自选/加对比/设预警后 `loadCodesQuotes` 拉真实行情，保证冷门股在自选/对比里能显示名字/价。

 * 说明：这里只写**持久层**（prefs），不直接改 MainTabPager 的 observable 镜像；
 * 主界面常驻在 ChatPage 之下，用户返回主界面时 `pageDidAppear → loadState` 会自动同步。
 *
 * prefs 由调用方（ChatPage）显式传入。
 */
internal object AgentActions {

    /** 把股票加入自选（不重复）。返回确认文本 */
    fun addWatch(stock: Stock, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val cur = UserStockStore.loadWatchlist(pre)
        if (stock.code in cur) return "${stock.name} 本来就在你的自选里啦"
        UserStockStore.saveWatchlist(pre, cur + stock.code)
        // 池外冷门股拉一次真实行情入池，保证自选 Tab 能看到名字/价
        StockData.loadCodesQuotes(setOf(stock.code))
        return "已把「${stock.name}」加入自选"
    }

    /** 把股票加入对比列表（去重；key 与 StockComparePage 的 kb_compare_codes 一致） */
    fun addCompare(stock: Stock, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val raw = pre.getItem("kb_compare_codes")
        val list = if (!raw.isNullOrBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        if (stock.code in list) return "${stock.name} 已在对比列表里"
        list.add(stock.code)
        pre.setItem("kb_compare_codes", list.joinToString(","))
        StockData.loadCodesQuotes(setOf(stock.code))
        return "已把「${stock.name}」加入对比，可在「AI Tab → 股票对比」查看"
    }

    /** 设价格预警：type above/below/pctUp/pctDown。已存在则提示 */
    fun addAlert(stock: Stock, type: String, threshold: Float, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val alerts = AlertStore.load(pre)
        if (AlertStore.exists(alerts, stock.code, type, threshold)) return "该预警条件已设置过"
        AlertStore.save(pre, alerts + AlertStore.PriceAlert(stock.code, type, threshold))
        val label = when (type) { "above" -> "涨破"; "below" -> "跌破"; "pctUp" -> "当日涨幅≥"; "pctDown" -> "当日跌幅≥"; else -> type }
        return "已为「${stock.name}」设置预警：${label}${if (type.startsWith("pct")) "$threshold%" else "$threshold"}，行情刷新命中会提醒你"
    }

    /** 设主题色（argb Long）。返回确认 */
    fun setThemeColor(argb: Long, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        UserSettings.themeColor = argb
        UserSettings.saveTheme(pre)
        return "已把主题色改成你指定的颜色"
    }

    /** 设置字体大小（scale：0.85 小 / 1.0 标准 / 1.15 大 / 1.3 特大）。返回确认 */
    fun setFontScale(scale: Float, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val s = when {
            scale <= 0.9f -> 0.85f
            scale < 1.07f -> 1.0f
            scale < 1.22f -> 1.15f
            else -> 1.3f
        }
        UserSettings.fontScale = s
        UserSettings.saveFont(pre)
        val label = fontLabel(s)
        return "已把字体大小调整为「$label」"
    }

    /** 开关某个迷你卡片组件（key=trend分时走势/ai AI分析/brief简况/finance基本面）。返回确认 */
    fun toggleMiniCard(key: String, on: Boolean, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        if (on) UserSettings.expand.add(key) else UserSettings.expand.remove(key)
        UserSettings.saveExpand(pre)
        val label = miniCardLabel(key)
        return if (on) "已开启展开卡里的「$label」，返回列表点开股票即可看到" else "已关闭展开卡里的「$label」"
    }

    /** 设置涨跌配色：0=A股红涨绿跌 / 1=欧美红跌绿涨。返回确认 */
    fun setColorModeVal(mode: Int, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        UserSettings.colorMode = mode.coerceIn(0, 1)
        UserSettings.saveColorMode(pre)
        return if (UserSettings.colorMode == 0) "已切换为 A股配色（红涨绿跌）" else "已切换为 欧美配色（红跌绿涨）"
    }

    /** 设置「不感兴趣」股票的自动恢复天数。返回确认 */
    fun setHideDays(days: Int, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val d = days.coerceIn(1, 90)
        UserStockStore.saveHideDays(pre, d)
        return "已把「不感兴趣」股票的自动恢复周期设为 $d 天"
    }

    /** 恢复全部被隐藏（不感兴趣）的股票。返回确认 */
    fun restoreHiddenAll(prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val n = UserStockStore.loadHidden(pre).size
        if (n == 0) return "当前没有标记为「不感兴趣」的股票"
        UserStockStore.saveHidden(pre, emptyMap())
        return "已恢复全部 $n 只「不感兴趣」的股票"
    }

    /** 恢复单只被隐藏（不感兴趣）的股票。stockName=用户给的名字用于提示。返回确认 */
    fun restoreHiddenOne(code: String, nameHint: String, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        val hid = UserStockStore.loadHidden(pre).toMutableMap()
        if (code !in hid) return "「$nameHint」不在已隐藏的列表里"
        hid.remove(code)
        UserStockStore.saveHidden(pre, hid)
        return "已把「$nameHint」从「不感兴趣」里恢复，它会重新出现在行情/自选列表"
    }

    private fun fontLabel(s: Float): String = when (s) {
        0.85f -> "小"; 1.0f -> "标准"; 1.15f -> "大"; else -> "特大"
    }

    private fun miniCardLabel(key: String): String = when (key) {
        UserSettings.EXPAND_TREND -> "分时走势"
        UserSettings.EXPAND_AI -> "AI 智能分析"
        UserSettings.EXPAND_BRIEF -> "简况"
        UserSettings.EXPAND_FINANCE -> "基本面"
        else -> key
    }
}
