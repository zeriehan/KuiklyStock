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

    /** 切换深色/浅色（返回是否切到深色） */
    fun setDark(dark: Boolean, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        UserSettings.darkMode = dark
        UserSettings.saveDark(pre)
        return if (dark) "已为你切换到深色模式，重新回到主界面即可看到" else "已为你切换到浅色模式"
    }

    /** 设主题色（argb Long）。返回确认 */
    fun setThemeColor(argb: Long, prefsP: SharedPreferencesModule): String {
        val pre = prefsP
        UserSettings.themeColor = argb
        UserSettings.saveTheme(pre)
        return "已把主题色改成你指定的颜色"
    }
}
