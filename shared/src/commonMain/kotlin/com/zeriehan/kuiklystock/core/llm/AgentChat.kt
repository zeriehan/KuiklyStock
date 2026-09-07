package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.zeriehan.kuiklystock.core.AgentActions
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockData

/**
 * 「AI 决定动作」的 Agent 编排（模型决策协议版）。
 *
 * 让模型自己理解用户自然语言并决定是否执行 app 操作：若需执行，模型只输出一行
 * `⟦TOOL⟧{json}`；shared 解析 → 执行真实操作 → 把结果作为上下文再问一轮拿最终答复。
 * **不靠本地规则枚举**（用户诉求：交给模型理解自然语言）。
 *
 * 可用工具见 [toolsSpec]。流程最多 3 次模型调用：
 *   首轮 → 若无 ⟦TOOL⟧ 则强重试一次 → 有则执行 + 再问一轮拿最终答复。
 * 复用已验证稳定的非流式 glm-4-flash 通道（AIJobCenter.sendPrompt），不改 host。
 */
object AgentChat {

    private const val MAX_HISTORY = 8
    private const val TAG = "⟦TOOL⟧"

    private const val toolsSpec =
        """可用工具（仅当用户明确要求执行某 app 操作时才调用）。
工具 name 必须是下列之一，**不要用 change_/switch_/update_/modify_/turn_ 等其他前缀**（用了也识别不到）：
1. addWatch：把股票加入自选。args: {"stock":"股票中文名或6位代码"}
2. addCompare：把股票加入股票对比列表。args: {"stock":"..."}
3. addAlert：给股票设价格预警。args: {"stock":"...","type":"跌破/涨破/当日涨幅≥/当日跌幅≥","threshold":数字}
4. setThemeColor：改主题色。args: {"colorName":"红/橙/黄/绿/青/蓝/紫/黑/白/粉"}
5. setDarkMode：切换深色/浅色。args: {"boolean":true或false}"""

    /** 是否疑似要求执行 app 操作：只用于选路（宽松即可；误判走 agent 模型也只会正常答） */
    fun isLikelyAction(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val stockAct = t.contains("自选") || t.contains("加入") || t.contains("加进") || t.contains("收藏") ||
            t.contains("对比") || t.contains("预警") || t.contains("提醒") || t.contains("跌破") ||
            t.contains("涨破") || t.contains("涨到") || t.contains("跌到") || t.contains("涨超") || t.contains("跌超")
        val recolor = (t.contains("改成") || t.contains("换成") || t.contains("调成") || t.contains("主题") || t.contains("换") || t.contains("改")) &&
            (t.contains("红") || t.contains("橙") || t.contains("黄") || t.contains("绿") || t.contains("蓝") ||
                t.contains("紫") || t.contains("黑") || t.contains("白") || t.contains("粉") || t.contains("青"))
        val theme = (t.contains("深色") || t.contains("浅色") || t.contains("暗黑") || t.contains("夜间") || t.contains("白天")) &&
            (t.contains("模式") || t.contains("改成") || t.contains("换成") || t.contains("调成") || t.contains("切换") || t.contains("开"))
        return stockAct || recolor || theme
    }

    fun historyText(history: List<ChatStore.ChatMessage>): String {
        if (history.isEmpty()) return ""
        val recent = history.takeLast(MAX_HISTORY)
        val sb = StringBuilder("（此前对话：\n")
        recent.forEach { m -> sb.append(if (m.role == "user") "用户: " else "AI: ").append(m.text).append("\n") }
        sb.append("）")
        return sb.toString()
    }

    /** 运行 Agent（详见类注释）。callback 收到最终用户可见文本。 */
    fun run(
        query: String,
        historyText: String,
        prefs: SharedPreferencesModule,
        callback: (String) -> Unit,
    ) {
        // 首轮：判断是否要调工具
        val firstPrompt = buildFirstPrompt(query, historyText)
        AIJobCenter.sendPrompt(firstPrompt) { r1 ->
            val t1 = r1?.optString("text").orEmpty()
            val tool1 = extractToolLine(t1)
            if (tool1 != null) {
                finishWithTool(query, historyText, tool1, prefs, callback)
                return@sendPrompt
            }
            // 首轮无 TOOL：若模型空回 → 直接空（上层兜底）
            if (t1.isBlank()) { callback(""); return@sendPrompt }
            // 强重试一次（glm-4-flash 偶有"装作普通回答"倾向）
            val retryPrompt = buildRetryPrompt(query, t1)
            AIJobCenter.sendPrompt(retryPrompt) { r2 ->
                val t2 = r2?.optString("text").orEmpty()
                val tool2 = extractToolLine(t2)
                if (tool2 != null) {
                    finishWithTool(query, historyText, tool2, prefs, callback)
                } else {
                    val body = if (t2.isNotBlank()) t2 else t1
                    callback("（这条我理解成普通问答了，没能当成 App 操作执行。你可以换个更明确的说法，如“把茅台加进自选”。）\n$body")
                }
            }
        }
    }

    /** 执行工具 + 再问一轮拿最终答复。执行失败时直接把准确错误返给用户，不靠模型复述（避免模型把失败包装成模糊话术） */
    private fun finishWithTool(query: String, historyText: String, toolLine: String, prefs: SharedPreferencesModule, callback: (String) -> Unit) {
        val result = executeTool(toolLine, prefs)
        val isFailure = result.contains("未") || result.contains("失败") || result.contains("无法") ||
            result.contains("不存在") || result.contains("错误") || result.contains("没找到")
        if (isFailure) { callback(result); return }
        val secondPrompt = buildSecondPrompt(query, historyText, toolLine, result)
        AIJobCenter.sendPrompt(secondPrompt) { r ->
            val text = r?.optString("text").orEmpty()
            callback(text.ifBlank { "已执行：$result" })
        }
    }

    private fun buildFirstPrompt(query: String, hist: String): String {
        val sb = StringBuilder()
        sb.append("[SYSTEM · 工具调用模式]\n")
        sb.append("你是「RinoStock」股票的 AI 助手。你能回答问题，也能在用户要求时执行 App 内的操作。\n\n")
        sb.append(toolsSpec).append("\n\n")
        sb.append("判断与输出规则（极其重要）：\n")
        sb.append("- 若用户消息是要执行上述任一 App 操作（加自选/加对比/设价格预警/改主题色/切深色浅色），你**必须只输出一行 ").append(TAG).append("{json}**，形如：\n")
        sb.append("  · 用户“把茅台加进自选”→ ").append(TAG).append("""{"name":"addWatch","args":{"stock":"贵州茅台"}}""").append("\n")
        sb.append("  · 用户“宁德时代加入对比”→ ").append(TAG).append("""{"name":"addCompare","args":{"stock":"宁德时代"}}""").append("\n")
        sb.append("  · 用户“茅台跌破1500提醒我”→ ").append(TAG).append("""{"name":"addAlert","args":{"stock":"贵州茅台","type":"跌破","threshold":1500}}""").append("\n")
        sb.append("  · 用户“改成红色”→ ").append(TAG).append("""{"name":"setThemeColor","args":{"colorName":"红"}}""").append("\n")
        sb.append("  · 用户“换深色”→ ").append(TAG).append("""{"name":"setDarkMode","args":{"boolean":true}}""").append("\n")
        sb.append("  json 里 name/args 必须准确；股票尽量用中文全名（茅台→贵州茅台）。\n")
        sb.append("- 若用户消息**不是**要执行操作（就是问股票/闲聊/要分析），才用自然中文正常回答，**绝不输出 ").append(TAG).append("**。\n\n")
        if (hist.isNotBlank()) sb.append(hist).append("\n\n")
        sb.append("用户：").append(query)
        return sb.toString()
    }

    private fun buildRetryPrompt(query: String, lastReply: String): String {
        val sb = StringBuilder()
        sb.append("[SYSTEM · 重试，必须输出工具调用]\n")
        sb.append("上一条模型回复未按规范（它写成了普通回答）：\n").append(lastReply.take(300)).append("\n\n")
        sb.append("用户原话：").append(query).append("\n\n")
        sb.append("判定：这条用户消息**就是**要求执行 App 操作。现在只输出一行 ").append(TAG).append("{json}（name+args），不要输出任何其它文字、不要解释。")
        return sb.toString()
    }

    private fun buildSecondPrompt(query: String, hist: String, toolLine: String, result: String): String {
        val sb = StringBuilder()
        sb.append("你刚才请求执行了 App 操作并已执行完毕，结果如下。请给用户一句简短、自然的中文最终确认，说明做了什么（不要提 JSON/工具，就自然说）。\n\n")
        sb.append("用户请求：").append(query).append("\n")
        sb.append("你调用的操作：").append(toolLine).append("\n")
        sb.append("执行结果：").append(result).append("\n\n")
        sb.append("请用一句中文回复。")
        return sb.toString()
    }

    /** 提取文本中的首段 ⟦TOOL⟧{json}；无则 null */
    private fun extractToolLine(text: String): String? {
        if (text.isBlank()) return null
        val i = text.indexOf(TAG)
        val start = if (i >= 0) text.indexOf('{', i) else text.indexOf('{')
        if (start < 0) return null
        val rest = text.substring(start)
        var depth = 0
        for ((k, ch) in rest.withIndex()) {
            if (ch == '{') depth++
            else if (ch == '}') {
                depth--
                if (depth == 0) return rest.substring(0, k + 1)
            }
        }
        return null
    }

    /** 解析并执行 TOOL json，返回用户可见的结果文本 */
    private fun executeTool(toolLine: String, prefs: SharedPreferencesModule): String {
        return try {
            val obj = JSONObject(toolLine)
            val name = obj.optString("name").let { canonicalizeToolName(it) }
            val args = obj.optJSONObject("args") ?: JSONObject()
            when (name) {
                "addWatch", "addCompare", "addAlert" -> {
                    val stockName = args.optString("stock")
                    val st = resolveStock(stockName)
                        ?: return "未找到股票「$stockName」，未执行。可用完整名或6位代码。"
                    when (name) {
                        "addWatch" -> AgentActions.addWatch(st, prefs)
                        "addCompare" -> AgentActions.addCompare(st, prefs)
                        else -> {
                            val typeLabel = args.optString("type")
                            val threshold = args.optDouble("threshold").toFloat()
                            AgentActions.addAlert(st, mapType(typeLabel), threshold, prefs)
                        }
                    }
                }
                "setThemeColor" -> {
                    var c = args.optString("colorName").ifBlank { args.optString("color") }
                    var argb = if (c.isNotBlank()) colorToArgb(c) else null
                    // 字段名/参数格式可能与少样本不一致：从原始 toolLine 里扫颜色词兜底
                    if (argb == null) {
                        argb = colorToArgb(toolLine)
                        c = "（已从描述解析）"
                    }
                    if (argb == null) "无法识别颜色，请说清楚要哪种颜色（如红/蓝/绿）"
                    else AgentActions.setThemeColor(argb, prefs)
                }
                "setDarkMode" -> {
                    val on = args.optString("boolean") == "true" || args.optBoolean("boolean", false)
                    AgentActions.setDark(on, prefs)
                }
                else -> "未知操作：$name"
            }
        } catch (e: Throwable) {
            "操作执行失败：${e.message ?: "未知错误"}"
        }
    }

    private fun mapType(label: String): String = when {
        label.contains("跌幅") || label.contains("跌超") || label.contains("下跌") -> "pctDown"
        label.contains("涨幅") || label.contains("涨超") || label.contains("上涨") -> "pctUp"
        label.contains("跌破") || label.contains("跌") -> "below"
        label.contains("涨破") || label.contains("涨") -> "above"
        else -> "below"
    }

    /** 股票（中文名/代码/常见简称）→ Stock；找不到 null */
    private fun resolveStock(s: String): Stock? {
        val name = s?.trim().orEmpty()
        if (name.isEmpty()) return null
        val quotes = StockData.getQuotes()
        if (name.length == 6 && name.all { it.isDigit() }) {
            quotes.firstOrNull { it.code == name }?.let { return it }
        }
        // 完整名：精确优先，其次子串（按名长降序，避免“平安”撞“平安银行”）
        quotes.filter { it.name.isNotBlank() }
            .sortedByDescending { it.name.length }
            .firstOrNull { q -> q.name == name || name.contains(q.name) || q.name.contains(name) }
            ?.let { return it }
        return null
    }

    /** 把模型可能输出的别名（change_theme_color / set_theme_color 等）规范化到我们的内部名 */
    private fun canonicalizeToolName(raw: String): String {
        val n = raw.trim().lowercase()
        // 已是自己名字
        if (n in setOf("addwatch", "addcompare", "addalert", "setthemecolor", "setdarkmode")) return raw
        // watch / 自选
        if (n.contains("watch") || n.contains("self") || n.contains("favorite")) return "addWatch"
        if (n.contains("compare") || n.contains("对比")) return "addCompare"
        if (n.contains("alert") || n.contains("预警") || n.contains("提醒")) return "addAlert"
        // 改主题色（兼容 change_color / set_color / color_theme / change_theme / 改颜色 等）
        if (n.contains("color") || n.contains("theme") || n.contains("色")) return "setThemeColor"
        // 深色 / 暗黑 / 夜间模式
        if (n.contains("dark") || n.contains("深色") || n.contains("暗黑") || n.contains("夜间") || n.contains("night")) return "setDarkMode"
        // 没匹配：原样返（executeTool 会报"未知操作：xxx"）
        return raw
    }

    private fun colorToArgb(c0: String): Long? {
        val map = listOf(
            "红色" to 0xFFE54D42L, "red" to 0xFFE54D42L, "红" to 0xFFE54D42L,
            "橙色" to 0xFFFF7A45L, "orange" to 0xFFFF7A45L, "橙" to 0xFFFF7A45L,
            "黄色" to 0xFFF5A623L, "yellow" to 0xFFF5A623L, "黄" to 0xFFF5A623L,
            "绿色" to 0xFF1ABE5BL, "green" to 0xFF1ABE5BL, "绿" to 0xFF1ABE5BL,
            "青色" to 0xFF23B8FFL, "cyan" to 0xFF23B8FFL, "青" to 0xFF23B8FFL,
            "蓝色" to 0xFF3B82F6L, "blue" to 0xFF3B82F6L, "蓝" to 0xFF3B82F6L,
            "紫色" to 0xFF8B5CF6L, "purple" to 0xFF8B5CF6L, "紫" to 0xFF8B5CF6L,
            "黑色" to 0xFF222222L, "black" to 0xFF222222L, "黑" to 0xFF222222L,
            "白色" to 0xFFFFFFFFL, "white" to 0xFFFFFFFFL, "白" to 0xFFFFFFFFL,
            "粉色" to 0xFFFF6B9DL, "pink" to 0xFFFF6B9DL, "粉" to 0xFFFF6B9DL,
        )
        val c = c0.lowercase().replace("色", "").trim()
        return map.firstOrNull { c.contains(it.first) }?.second
    }
}
