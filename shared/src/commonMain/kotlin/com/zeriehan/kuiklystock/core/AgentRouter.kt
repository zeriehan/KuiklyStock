package com.zeriehan.kuiklystock.core

/**
 * AI 操控 app 的指令识别层（Agent Router）。
 *
 * 把用户消息识别为「可执行的 app 操作」：加自选 / 加对比 / 设价格预警 / 改外观。
 * 返回 [AgentAction] 或 null（null = 不是明确操作，走普通 AI 问答）。
 *
 * ⚠️ 识别原则：宁可漏判（走问答）也不误判——误把普通财经讨论当操作去执行会误导用户。
 * 因此每种操作都要求较明确的动词 + 目标；命中不到就返回 null。
 *
 * 股票目标解析：[resolveStock] 支持 ①6 位代码 ②池内完整股票名 ③常用简称表。
 */
internal object AgentRouter {

    /** 识别出的可执行操作 */
    sealed class AgentAction {
        data class AddWatch(val stock: Stock) : AgentAction()
        data class AddCompare(val stock: Stock) : AgentAction()
        data class AddAlert(val stock: Stock, val type: String, val threshold: Float) : AgentAction()
        data class SetDark(val dark: Boolean) : AgentAction()
        data class SetTheme(val colorLabel: String, val argb: Long) : AgentAction()
    }

    // 常用简称 → 完整名（覆盖演示常用股；完整名/代码本来就能直接命中）
    private val aliases = mapOf(
        "茅台" to "贵州茅台", "五粮液" to "五粮液", "平安" to "中国平安", "招行" to "招商银行",
        "工行" to "工商银行", "建行" to "建设银行", "宁德" to "宁德时代", "比亚迪" to "比亚迪",
        "隆基" to "隆基绿能", "中芯" to "中芯国际", "药明" to "药明康德", "恒瑞" to "恒瑞医药",
        "东财" to "东方财富", "中信" to "中信证券", "汾酒" to "山西汾酒", "人寿" to "中国人寿",
    )

    /** 主入口：text 为对用户可见的干净文本，current 为聊天当前绑定股（个股对话可能引用"这只"） */
    fun route(text: String): AgentAction? {
        val t = text.trim()
        if (t.isEmpty()) return null

        // 1) 外观设置（无股票依赖）
        when {
            t.contains("深色") || t.contains("暗黑") || t.contains("夜间") -> {
                if (t.contains("不要") || t.contains("取消") || t.contains("关")) return AgentAction.SetDark(false)
                if (containsAct(t)) return AgentAction.SetDark(true)
            }
            t.contains("浅色") || t.contains("白天") || t.contains("亮色") -> {
                if (containsAct(t)) return AgentAction.SetDark(false)
            }
        }
        // 主题色：换主题色/改成红色/主题改成绿/变成蓝色 等。
        // 放宽：只要含「改成/换成/调成/设为…」这类改色动作词 + 颜色词，就视为改主题色（"改成红色"也能命中）。
        // 用动作词限定避免把"收红/拉红"这种行情描述误判成改主题。
        colorNameToArgb(t)?.let { (label, argb) ->
            if (t.contains("主题") || t.contains("颜色") || t.contains("配色") || hasRecolorAct(t)) {
                return AgentAction.SetTheme(label, argb)
            }
        }

        // 2) 需股票的操作：先解析股票目标
        // 设预警：必须有价格数字 + 方向词
        val alert = tryParseAlert(t)
        if (alert != null) return alert

        // 加自选 / 加对比：需操作动词 + 目标词
        val stock = resolveStock(t) ?: return null
        if ((t.contains("自选")) && (t.contains("加") || t.contains("加入") || t.contains("添加") || t.contains("收藏") || t.contains("放到"))) {
            return AgentAction.AddWatch(stock)
        }
        if ((t.contains("对比")) && (t.contains("加") || t.contains("加入") || t.contains("比较"))) {
            return AgentAction.AddCompare(stock)
        }
        return null
    }

    private fun containsAct(t: String): Boolean =
        t.contains("换成") || t.contains("切换") || t.contains("改成") || t.contains("设为") ||
            t.contains("调成") || t.contains("打开") || t.contains("开") || t.contains("用") ||
            t.contains("设置") || t.contains("开启") || t.contains("试试")

    /** 改色动作词：把界面/主题改成某种颜色（"改成/换成/调成红色"） */
    private fun hasRecolorAct(t: String): Boolean =
        t.contains("改成") || t.contains("换成") || t.contains("调成") || t.contains("变成") ||
            t.contains("设为") || t.contains("设置成") || t.contains("改为") || t.contains("切换成") ||
            t.contains("调为") || t.contains("变为")

    /** 解析价格预警。返回 null 表示不是预警指令 */
    private fun tryParseAlert(t: String): AgentAction.AddAlert? {
        // 必须含明确预警动作词（提醒/预警/破），避免把"会跌到哪"这类提问误判成设预警
        val hasCmd = t.contains("提醒") || t.contains("预警") || t.contains("跌破") || t.contains("涨破") ||
            t.contains("破") && (t.contains("提醒") || t.contains("预警"))
        if (!hasCmd) return null
        // 方向类型（只在确实要设时才要求，缺方向 → 不是设预警指令）
        val type: String = when {
            t.contains("跌破") || t.contains("跌到") || t.contains("跌至") || t.contains("回落到") -> "below"
            t.contains("涨破") || t.contains("涨到") || t.contains("突破") -> "above"
            t.contains("涨幅") || t.contains("涨超") || t.contains("上涨") -> "pctUp"
            t.contains("跌幅") || t.contains("跌超") || t.contains("下跌") -> "pctDown"
            else -> return null
        }
        val numbers = Regex("""\d+(?:\.\d+)?""").findAll(t).map { it.value.toFloat() }.toList()
        if (numbers.isEmpty()) return null
        val stock = resolveStock(t) ?: return null
        return AgentAction.AddAlert(stock, type, numbers.last())
    }

    /** 颜色词 → (标签, ARGB)。支持中文颜色名 */
    private fun colorNameToArgb(t: String): Pair<String, Long>? {
        val map = listOf(
            "红色" to 0xFFE54D42L, "红" to 0xFFE54D42L, "玫红" to 0xFFFF5A5FL, "橙" to 0xFFFF7A45L,
            "橙色" to 0xFFFF7A45L, "黄" to 0xFFF5A623L, "黄色" to 0xFFF5A623L, "绿" to 0xFF1ABE5BL,
            "绿色" to 0xFF1ABE5BL, "青" to 0xFF23B8FFL, "蓝" to 0xFF3B82F6L, "蓝色" to 0xFF3B82F6L,
            "紫" to 0xFF8B5CF6L, "紫色" to 0xFF8B5CF6L, "黑" to 0xFF222222L, "黑色" to 0xFF222222L,
            "白" to 0xFFFFFFFFL, "白色" to 0xFFFFFFFFL, "粉" to 0xFFFF6B9DL, "粉色" to 0xFFFF6B9DL,
        )
        for ((word, argb) in map) {
            if (t.contains(word)) return word to argb
        }
        return null
    }

    /** 从文本解析股票目标（code / 完整名 / 简称）。找不到返回 null */
    fun resolveStock(text: String): Stock? {
        // ① 6 位代码
        val codeHit = Regex("""\d{6}""").find(text)
        if (codeHit != null) {
            StockData.getQuotes().firstOrNull { it.code == codeHit.value }?.let { return it }
        }
        // ② 完整名 / 简称（优先更长的名，避免"平安"误匹配到"平安银行"）
        //    先试最长匹配的完整名；再试简称表
        val quotes = StockData.getQuotes()
        val fullNames = quotes.map { it.name }.filter { it.length >= 2 }.sortedByDescending { it.length }
        // 完整名作为子串出现在句子里 → 命中（名越长越优先，避免歧义）
        for (n in fullNames) {
            if (text.contains(n)) return quotes.first { it.name == n }
        }
        // ③ 简称表
        for ((alias, full) in aliases) {
            if (text.contains(alias)) {
                quotes.firstOrNull { it.name == full }?.let { return it }
            }
        }
        // ④ 当前聊天绑定的个股（消息里说"这只/它/该股"时）
        return null
    }
}
