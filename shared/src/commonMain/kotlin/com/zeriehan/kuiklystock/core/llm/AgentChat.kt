package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.zeriehan.kuiklystock.core.AgentActions
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings

/**
 * 「AI 决定动作」的 Agent 编排（模型决策协议版，非官方 tools 字段）。
 *
 * 让模型自己理解用户自然语言并决定是否执行 app 操作：若需执行，模型只输出一行
 * `⟦TOOL⟧{"name":"...","args":{...}}`；shared 解析 → 执行真实操作 → 把结果作为上下文
 * 再问一轮，让模型给出最终答复。**不靠本地规则枚举**（用户诉求：规则永远枚举不完，
 * 交给模型理解自然语言）。
 *
 * 现可用工具（name → 执行）：见 [toolsSpec]。
 *
 * ⚠️ 复用已验证稳定的非流式 glm-4-flash 通道（AIJobCenter.sendPrompt），不改 host。
 * 流程最多 2 轮：首轮判断是否要调工具 + 若要则执行；次轮带工具结果生成最终答复。
 */
object AgentChat {

    /** 对话历史里最多取最近 N 条喂给模型（控 token） */
    private const val MAX_HISTORY = 8

    /** 工具 JSON 协议行前缀 */
    private const val TAG = "⟦TOOL⟧"

    /** 人类可读的工具清单（喂给模型） */
    private const val toolsSpec =
        """可用工具(仅当用户明确要求执行某 app 操作时才调用；普通问答绝不调用)：
1. addWatch(stockName或代码): 把一只股票加入自选。
2. addCompare(stockName或代码): 把一只股票加入股票对比列表。
3. addAlert(stockName或代码, type, threshold): 给股票设价格预警。type ∈ {跌破,涨破,当日涨幅≥,当日跌幅≥}(直接给中文)；threshold 为数字(价格或百分比)。
4. setThemeColor(colorName): 改主题色。colorName ∈ 红/橙/黄/绿/青/蓝/紫/黑/白/粉。
5. setDarkMode(boolean): 切换深色(true)/浅色(false)。

判断规则：
- 用户说"把xx加入自选/加自选/收藏xx" → addWatch
- 用户说"把xx加进对比/对比里加上xx" → addCompare
- 用户说"xx跌破X提醒我/涨破X/跌超X%/提醒" → addAlert(threshold=X)
- 用户说"改成红色/换成蓝色/主题色变绿" → setThemeColor
- 用户说"深色模式/换成深色" → setDarkMode(true)；"浅色/白天模式" → setDarkMode(false)
- 股票名可能用简称(茅台=贵州茅台)；找不到明确股票或没让执行操作 → 不调用工具，直接正常回答用户。"""

    /** 判断消息是否"疑似要执行操作"，只用于选路（不是执行者）。宽松即可：误判走 agent 模型也正常回答 */
    fun isLikelyAction(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // 操作动词 + 工具目标词（加自选/加对比/设预警/改颜色/切深浅色）
        val actionWord = t.contains("自选") || t.contains("加入") || t.contains("加进") || t.contains("收藏") ||
            t.contains("对比") || t.contains("预警") || t.contains("提醒") || t.contains("跌破") ||
            t.contains("涨破") || t.contains("涨到") || t.contains("跌到") || t.contains("涨超") || t.contains("跌超")
        val recolor = (t.contains("改成") || t.contains("换成") || t.contains("调成") || t.contains("主题")) &&
            (t.contains("红色") || t.contains("红") || t.contains("橙") || t.contains("黄") || t.contains("绿") ||
                t.contains("蓝") || t.contains("紫") || t.contains("黑") || t.contains("白") || t.contains("粉") ||
                t.contains("深色") || t.contains("浅色") || t.contains("暗"))
        val theme = (t.contains("深色") || t.contains("暗") || t.contains("夜间") || t.contains("浅色")) &&
            (t.contains("模式") || t.contains("改成") || t.contains("换成") || t.contains("调成"))
        return actionWord || recolor || theme
    }

    /** 取某段对话近 N 条做文本历史 */
    fun historyText(history: List<ChatStore.ChatMessage>): String {
        if (history.isEmpty()) return ""
        val recent = history.takeLast(MAX_HISTORY)
        val sb = StringBuilder("（此前对话：\n")
        recent.forEach { m -> sb.append(if (m.role == "user") "用户: " else "AI: ").append(m.text).append("\n") }
        sb.append("）")
        return sb.toString()
    }

    /**
     * 运行 Agent：首轮让模型判断是否执行操作；若返回 TOOL 行则执行并再问一轮拿最终答复。
     * @param callback 最终回复文本（失败/空则传空串，上层回退）
     */
    fun run(
        query: String,
        historyText: String,
        prefs: SharedPreferencesModule,
        callback: (String) -> Unit,
    ) {
        attemptTool(query, historyText, prefs) { toolLine, firstReply ->
            if (toolLine == null) {
                // 首轮无工具调用 → firstReply 即最终答复
                callback(firstReply)
                return@attemptTool
            }
            // 有工具调用：执行并再问一轮
            val result = executeTool(toolLine, prefs)
            val secondPrompt = buildSecondPrompt(query, historyText, toolLine, result)
            AIJobCenter.sendPrompt(secondPrompt) { resp2 ->
                val text = resp2?.optString("text").orEmpty()
                callback(text.ifBlank { firstReply })  // 次轮失败则用首轮(带TOOL行的)文本兜底展示
            }
        }
    }

    /** 首轮：非流式发一次，解析是否含 TOOL 行。回调 (toolLine, 首轮原文) */
    private fun attemptTool(
        query: String,
        hist: String,
        prefs: SharedPreferencesModule,
        cb: (String?, String) -> Unit,
    ) {
        val prompt = buildFirstPrompt(query, hist)
        AIJobCenter.sendPrompt(prompt) { resp ->
            val text = resp?.optString("text").orEmpty()
            if (text.isBlank()) { cb(null, ""); return@sendPrompt }
            val toolLine = extractToolLine(text)
            cb(toolLine, text)
        }
    }

    private fun buildFirstPrompt(query: String, hist: String): String {
        val sb = StringBuilder()
        sb.append("你是「RinoStock」股票的 AI 助手，能回答问题，也能在用户要求时执行 app 内的操作。\n\n")
        sb.append(toolsSpec).append("\n\n")
        sb.append("对话规则：\n")
        sb.append("- 若用户请求执行上述某个操作：只输出一行 ").append(TAG).append("{json}（json 含 name 与 args，args 里股票用中文名或6位代码），不要输出其它解释。\n")
        sb.append("- 否则：像普通财经助手一样正常回答，绝不输出 ").append(TAG).append(" 行。\n\n")
        if (hist.isNotBlank()) sb.append(hist).append("\n\n")
        sb.append("用户：").append(query)
        return sb.toString()
    }

    private fun buildSecondPrompt(query: String, hist: String, toolLine: String, result: String): String {
        val sb = StringBuilder()
        sb.append("你是「RinoStock」股票的 AI 助手。你刚才请求执行了 app 操作，以下是执行结果，请基于结果给用户一句简短、自然的最终确认（不要再说要执行，不要说 JSON，就用自然中文告知已做了什么）。\n\n")
        if (hist.isNotBlank()) sb.append(hist).append("\n\n")
        sb.append("用户请求：").append(query).append("\n")
        sb.append("你请求的操作：").append(toolLine).append("\n")
        sb.append("执行结果：").append(result).append("\n\n")
        sb.append("请用一句中文回复用户，说明执行了什么。")
        return sb.toString()
    }

    /** 从模型文本提取首个 TOOL json 行；无则返回 null */
    private fun extractToolLine(text: String): String? {
        val i = text.indexOf(TAG)
        if (i < 0) return null
        var rest = text.substring(i + TAG.length).trim()
        // 定位到 { ... }（跨行取到配平大括号，稳妥截到 } 结尾）
        val start = rest.indexOf('{')
        if (start < 0) return null
        rest = rest.substring(start)
        // 简单配平取第一对完整 { }
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

    /** 解析 TOOL json 并执行，返回给用户的中文结果；解析/执行失败返回失败说明 */
    private fun executeTool(toolLine: String, prefs: SharedPreferencesModule): String {
        return try {
            val obj = JSONObject(toolLine)
            val name = obj.optString("name")
            val args = obj.optJSONObject("args") ?: JSONObject()
            when (name) {
                "addWatch", "addCompare", "addAlert" -> {
                    val stockName = args.optString("stock") ?: args.optString("stockName")
                    val st = resolveStock(stockName) ?: return "未找到股票「$stockName」，未执行"
                    when (name) {
                        "addWatch" -> AgentActions.addWatch(st, prefs)
                        "addCompare" -> AgentActions.addCompare(st, prefs)
                        else -> {
                            val typeLabel = args.optString("type")
                            val threshold = args.optDouble("threshold").toFloat()
                            val type = mapOf("跌破" to "below", "涨破" to "above", "当日涨幅≥" to "pctUp", "当日跌幅≥" to "pctDown").let { m -> m[typeLabel] ?: inferType(typeLabel) }
                            AgentActions.addAlert(st, type, threshold, prefs)
                        }
                    }
                }
                "setThemeColor" -> {
                    val c = args.optString("colorName") ?: args.optString("color")
                    val argb = colorToArgb(c)
                    if (argb == null) "无法识别颜色「$c」" else AgentActions.setThemeColor(argb, prefs)
                }
                "setDarkMode" -> {
                    val on = args.optString("boolean").let { it == "true" } || args.optBoolean("boolean") || args.optBoolean("value")
                    AgentActions.setDark(on, prefs)
                }
                else -> "未知操作 $name"
            }
        } catch (e: Throwable) {
            "操作执行失败：${e.message ?: "未知错误"}"
        }
    }

    private fun inferType(label: String): String = when {
        label.contains("跌破") || label.contains("跌") -> "below"
        label.contains("涨破") || label.contains("涨") -> "above"
        label.contains("跌幅") -> "pctDown"
        label.contains("涨幅") -> "pctUp"
        else -> "below"
    }

    /** 股票名(中文/代码/简称) → Stock；找不到返回 null */
    private fun resolveStock(s: String): com.zeriehan.kuiklystock.core.Stock? {
        val name = s?.trim().orEmpty()
        if (name.isEmpty()) return null
        val quotes = StockData.getQuotes()
        // 6位代码
        if (name.length == 6 && name.all { it.isDigit() }) quotes.firstOrNull { it.code == name }?.let { return it }
        // 完整名子串匹配（长优先，避免"平安"撞"平安银行"——取精确/最长）
        quotes.filter { it.name.length >= 2 }
            .sortedByDescending { it.name.length }
            .firstOrNull { it.name == name || name.contains(it.name) || it.name.contains(name) }
            ?.let { return it }
        return null
    }

    private fun colorToArgb(c: String): Long? {
        val map = listOf(
            "红色" to 0xFFE54D42L, "红" to 0xFFE54D42L, "橙" to 0xFFFF7A45L, "橙色" to 0xFFFF7A45L,
            "黄色" to 0xFFF5A623L, "黄" to 0xFFF5A623L, "绿色" to 0xFF1ABE5BL, "绿" to 0xFF1ABE5BL,
            "青色" to 0xFF23B8FFL, "青" to 0xFF23B8FFL, "蓝色" to 0xFF3B82F6L, "蓝" to 0xFF3B82F6L,
            "紫色" to 0xFF8B5CF6L, "紫" to 0xFF8B5CF6L, "黑色" to 0xFF222222L, "黑" to 0xFF222222L,
            "白色" to 0xFFFFFFFFL, "白" to 0xFFFFFFFFL, "粉色" to 0xFFFF6B9DL, "粉" to 0xFFFF6B9DL,
        )
        val k = map.firstOrNull { c.contains(it.first) } ?: return null
        return k.second
    }
}
