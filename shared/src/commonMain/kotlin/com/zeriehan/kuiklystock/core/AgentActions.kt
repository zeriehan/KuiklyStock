package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * AI 操控 app 的执行层（Agent Actions）。
 *
 * 用户在 AI 聊天里发出明确操作指令（如「把茅台加进自选」「茅台跌破1800提醒我」），
 * ChatPage 在请求模型前先用 [AgentRouter] 识别；识别命中则调用本对象执行真实的 app 操作
 * （写持久化存储），并返回一句确认文本作为 AI 气泡回复。这样执行是确定性的，不依赖模型输出格式。
 *
 * 说明：这里只写**持久层**（prefs），不直接改 MainTabPager 的 observable 镜像；
 * 主界面常驻在 ChatPage 之下，用户返回主界面时 `pageDidAppear → loadState` 会自动同步
 * 到自选/设置等列表。加自选后回「自选」Tab 即可看到。
 *
 * prefs 句柄由常驻根页面 MainTabPager 在 viewDidLoad 注入（同 ChatStore.attach）。
 */
internal object AgentActions {

    private var prefs: SharedPreferencesModule? = null

    fun attach(p: SharedPreferencesModule) { if (prefs !== p) prefs = p }

    private fun p(): SharedPreferencesModule? = prefs

    /** 统一执行路由结果，返回给用户的确认文本 */
    fun execute(action: AgentRouter.AgentAction, prefsP: SharedPreferencesModule? = null): String = when (action) {
        is AgentRouter.AgentAction.AddWatch -> addWatch(action.stock, prefsP)
        is AgentRouter.AgentAction.AddCompare -> addCompare(action.stock, prefsP)
        is AgentRouter.AgentAction.AddAlert -> addAlert(action.stock, action.type, action.threshold, prefsP)
        is AgentRouter.AgentAction.SetDark -> setDark(action.dark, prefsP)
        is AgentRouter.AgentAction.SetTheme -> setThemeColor(action.argb, prefsP)
    }

    /** 把股票加入自选（不重复）。返回确认文本；false 表示已加过 */
    fun addWatch(stock: Stock, prefsP: SharedPreferencesModule? = null): String {
        val pre = prefsP ?: p() ?: return "（存储未就绪，无法加入自选）"
        val cur = UserStockStore.loadWatchlist(pre)
        if (stock.code in cur) return "${stock.name} 本来就在你的自选里啦"
        UserStockStore.saveWatchlist(pre, cur + stock.code)
        // 池外冷门股拉一次真实行情入池，保证自选 Tab 能看到名字/价
        StockData.loadCodesQuotes(setOf(stock.code))
        return "已把「${stock.name}」加入自选"
    }

    /** 把股票加入对比列表（去重；key 与 StockComparePage 的 kb_compare_codes 一致） */
    fun addCompare(stock: Stock, prefsP: SharedPreferencesModule? = null): String {
        val pre = prefsP ?: p() ?: return "（存储未就绪，无法加入对比）"
        val raw = pre.getItem("kb_compare_codes")
        val list = if (!raw.isNullOrBlank()) raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        if (stock.code in list) return "${stock.name} 已在对比列表里"
        list.add(stock.code)
        pre.setItem("kb_compare_codes", list.joinToString(","))
        StockData.loadCodesQuotes(setOf(stock.code))
        return "已把「${stock.name}」加入对比，可在「AI Tab → 股票对比」查看"
    }

    /** 设价格预警：type above/below/pctUp/pctDown。已存在则提示 */
    fun addAlert(stock: Stock, type: String, threshold: Float, prefsP: SharedPreferencesModule? = null): String {
        val pre = prefsP ?: p() ?: return "（存储未就绪，无法设预警）"
        val alerts = AlertStore.load(pre)
        if (AlertStore.exists(alerts, stock.code, type, threshold)) return "该预警条件已设置过"
        AlertStore.save(pre, alerts + AlertStore.PriceAlert(stock.code, type, threshold))
        val label = when (type) { "above" -> "涨破"; "below" -> "跌破"; "pctUp" -> "当日涨幅≥"; "pctDown" -> "当日跌幅≥"; else -> type }
        return "已为「${stock.name}」设置预警：${label}${if (type.startsWith("pct")) "$threshold%" else "$threshold"}，行情刷新命中会提醒你"
    }

    /** 切换深色/浅色（返回是否切到深色） */
    fun setDark(dark: Boolean, prefsP: SharedPreferencesModule? = null): String {
        val pre = prefsP ?: p() ?: return "（存储未就绪，无法切换外观）"
        UserSettings.darkMode = dark
        UserSettings.saveDark(pre)
        return if (dark) "已为你切换到深色模式，重新回到主界面即可看到" else "已为你切换到浅色模式"
    }

    /** 设主题色（argb Long）。返回确认 */
    fun setThemeColor(argb: Long, prefsP: SharedPreferencesModule? = null): String {
        val pre = prefsP ?: p() ?: return "（存储未就绪，无法改主题色）"
        UserSettings.themeColor = argb
        UserSettings.saveTheme(pre)
        return "已把主题色改成你指定的颜色"
    }
}
