package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.zeriehan.kuiklystock.core.AgentActions
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings

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
5. setFontScale：改字体大小。args: {"level":"小/标准/大/特大"}（level 必填）
6. toggleMiniCard：开关「展开卡」里的某个迷你组件（行情/自选列表点开股票会弹出）。args: {"component":"分时走势/AI 智能分析/简况/基本面","on":true或false}
7. setColorMode：改涨跌配色。args: {"mode":"A股"或"欧美"}（A股=红涨绿跌，欧美=红跌绿涨）
8. setHideDays：设置「不感兴趣」股票的自动恢复天数。args: {"days":数字}
9. restoreHidden：恢复被「不感兴趣」隐藏的股票。args: {"scope":"全部"}，或只恢复某只 {"stock":"贵州茅台"}"""

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
        // 字体大小（用户要求调字号）
        val fontAct = (t.contains("字体") || t.contains("字号")) &&
            (t.contains("改") || t.contains("调") || t.contains("换") || t.contains("设") || t.contains("大") || t.contains("小"))
        // 迷你卡片/展开组件 开关
        val miniCard = (t.contains("迷你") || t.contains("卡片") || t.contains("展开") || t.contains("走势卡") || t.contains("分析卡")) &&
            (t.contains("开") || t.contains("关"))
        // 涨跌配色（A股/欧美、红涨绿跌/红跌绿涨）
        val colorModeAct = (t.contains("涨跌") || t.contains("配色") || t.contains("红涨") || t.contains("红跌") ||
            t.contains("绿跌") || t.contains("绿涨")) &&
            (t.contains("改") || t.contains("换") || t.contains("调") || t.contains("设") || t.contains("成") ||
                t.contains("A股") || t.contains("欧美"))
        // 恢复隐藏 / 不感兴趣 / 自动恢复周期
        val hiddenAct = (t.contains("不感兴趣") || t.contains("隐藏") || t.contains("自动恢复") ||
            (t.contains("恢复") && t.contains("股票")))
        return stockAct || recolor || fontAct || miniCard || colorModeAct || hiddenAct
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
        // 拉一份股票当前价（用于"加跌100的预警"这种无单位表达，AI 自己按当前价算阈值）
        val priceTable = buildPriceTable()
        // 首轮：判断是否要调工具
        val firstPrompt = buildFirstPrompt(query, historyText, priceTable)
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
            val retryPrompt = buildRetryPrompt(query, t1, priceTable)
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
        val result = executeTool(toolLine, prefs, query)
        val isFailure = result.contains("未") || result.contains("失败") || result.contains("无法") ||
            result.contains("不存在") || result.contains("错误") || result.contains("没找到")
        if (isFailure) { callback(result); return }
        val secondPrompt = buildSecondPrompt(query, historyText, toolLine, result)
        AIJobCenter.sendPrompt(secondPrompt) { r ->
            val text = r?.optString("text").orEmpty()
            callback(text.ifBlank { "已执行：$result" } + "")
        }
    }

    private fun buildFirstPrompt(query: String, hist: String, priceTable: String = ""): String {
        val sb = StringBuilder()
        sb.append("[SYSTEM · 工具调用模式]\n")
        sb.append("你是「RinoStock」股票的 AI 助手。你能回答问题，也能在用户要求时执行 App 内的操作。\n\n")
        sb.append(toolsSpec).append("\n\n")
        if (priceTable.isNotBlank()) {
            sb.append("当前股票价表（用来自动换算无单位金额预警）：\n").append(priceTable).append("\n\n")
        }
        // 换算规则无条件注入（不依赖价表）——用户说"跌100/涨100"这种只有金额没有'%'的，
        // 铁定是"元"不是"百分比"（跌超100%不可能）。价表有价就按当前价±金额算绝对阈值。
        sb.append("预警金额换算规则（对 addAlert 的 type/threshold 极其重要）：\n")
        sb.append("  - 用户说'跌100'/'跌到多少以下'/'跌破多少'：threshold 指**价格**。若价表里有该股现价，threshold=现价-金额；\n")
        sb.append("    若价表没有现价，直接 threshold=那个数字本身（如'跌到1200'→threshold=1200）。\n")
        sb.append("  - 用户说'跌100%'或'跌幅100'或明确说'百分之'才用当日跌幅≥（pctDown）。**没有%或百分之，绝不是百分比。**\n")
        sb.append("  - '当日跌幅≥5%'这类才走 type=当日跌幅≥/当日涨幅≥，threshold=5。\n\n")
        sb.append("判断与输出规则（极其重要）：\n")
        sb.append("- 若用户消息是要执行上述任一 App 操作（加自选/加对比/设价格预警/改主题色/调字号/开关迷你卡/换涨跌配色/设隐藏恢复天数/恢复隐藏股票），你**必须只输出一行 ").append(TAG).append("{json}**，形如：\n")
        sb.append("  · 用户“把茅台加进自选”→ ").append(TAG).append("""{"name":"addWatch","args":{"stock":"贵州茅台"}}""").append("\n")
        sb.append("  · 用户“宁德时代加入对比”→ ").append(TAG).append("""{"name":"addCompare","args":{"stock":"宁德时代"}}""").append("\n")
        sb.append("  · 用户“茅台跌破1500提醒我”→ ").append(TAG).append("""{"name":"addAlert","args":{"stock":"贵州茅台","type":"跌破","threshold":1500}}""").append("\n")
        sb.append("  · 用户“加茅台跌100的预警”→ ").append(TAG).append("""{"name":"addAlert","args":{"stock":"贵州茅台","type":"跌破","threshold":1216}}""").append("（说明：茅台现价1316，1316-100=1216）\n")
        sb.append("  · 用户“涨100提醒我”→ ").append(TAG).append("""{"name":"addAlert","args":{"stock":"贵州茅台","type":"涨破","threshold":1416}}""").append("（现价1316+100）\n")
        sb.append("  · 用户“茅台当日跌幅≥5%提醒我”→ ").append(TAG).append("""{"name":"addAlert","args":{"stock":"贵州茅台","type":"当日跌幅≥","threshold":5}}""").append("\n")
        sb.append("  · 用户“改成红色”→ ").append(TAG).append("""{"name":"setThemeColor","args":{"colorName":"红"}}""").append("\n")
        sb.append("  · 用户“字体调大一点”→ ").append(TAG).append("""{"name":"setFontScale","args":{"level":"大"}}""").append("\n")
        sb.append("  · 用户“把展开卡里的AI分析关掉”→ ").append(TAG).append("""{"name":"toggleMiniCard","args":{"component":"AI 智能分析","on":false}}""").append("\n")
        sb.append("  · 用户“涨跌配色换成欧美”→ ").append(TAG).append("""{"name":"setColorMode","args":{"mode":"欧美"}}""").append("\n")
        sb.append("  · 用户“不感兴趣的股票7天后自动恢复”→ ").append(TAG).append("""{"name":"setHideDays","args":{"days":7}}""").append("\n")
        sb.append("  · 用户“把贵州茅台从不感兴趣里恢复”→ ").append(TAG).append("""{"name":"restoreHidden","args":{"stock":"贵州茅台"}}""").append("\n")
        sb.append("  json 里 name/args 必须准确；股票尽量用中文全名（茅台→贵州茅台）。\n")
        sb.append("- 若用户消息**不是**要执行操作（就是问股票/闲聊/要分析），才用自然中文正常回答，**绝不输出 ").append(TAG).append("**。\n\n")
        if (hist.isNotBlank()) sb.append(hist).append("\n\n")
        sb.append("用户：").append(query)
        return sb.toString()
    }

    private fun buildRetryPrompt(query: String, lastReply: String, priceTable: String = ""): String {
        val sb = StringBuilder()
        sb.append("[SYSTEM · 重试，必须输出工具调用（严格规范）]\n")
        sb.append("上一条模型回复未按规范（它写成了普通回答）：\n").append(lastReply.take(200)).append("\n\n")
        sb.append("用户原话：").append(query).append("\n\n")
        sb.append("工具 name **必须是以下精确字符串之一，不要用 change_/set_/update_/modify_ 等其他形式**（这些都识别不到）：\n")
        sb.append("  - addWatch（加自选）args: {\"stock\":\"中文名\"}\n")
        sb.append("  - addCompare（加对比）args: {\"stock\":\"中文名\"}\n")
        sb.append("  - addAlert（设预警）args: {\"stock\":\"...\",\"type\":\"跌破/涨破/当日涨幅≥/当日跌幅≥\",\"threshold\":数字}\n")
        sb.append("  - setThemeColor（改主题色）args: {\"colorName\":\"红/橙/黄/绿/青/蓝/紫/黑/白/粉\"}\n")
        sb.append("  - setFontScale（改字号）args: {\"level\":\"小/标准/大/特大\"}\n")
        sb.append("  - toggleMiniCard（开关展开卡组件）args: {\"component\":\"分时走势/AI 智能分析/简况/基本面\",\"on\":true或false}\n")
        sb.append("  - setColorMode（涨跌配色）args: {\"mode\":\"A股\"或\"欧美\"}\n")
        sb.append("  - setHideDays（隐藏恢复天数）args: {\"days\":数字}\n")
        sb.append("  - restoreHidden（恢复隐藏股票）args: {\"scope\":\"全部\"} 或 {\"stock\":\"中文名\"}\n\n")
        sb.append("现在只输出一行 ").append(TAG).append("{json}，不要任何其它文字、不要解释、不要代码块。")
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

    /** 提取文本中的首段 ⟦TOOL⟧{json}；无则 null。剔除空 {} 和字面量 {json} 占位 */
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
                if (depth == 0) {
                    val candidate = rest.substring(0, k + 1)
                    // 过滤空对象 {} 和字面量占位 {json}/{tool}/{...}
                    val inner = candidate.trim().removePrefix("{").removeSuffix("}").trim()
                    if (inner.isEmpty() || inner.lowercase() in setOf("json", "tool", "func", "function",
                            "action", "cmd", "command", "name", "params", "args", "input", "data")) return null
                    return candidate
                }
            }
        }
        return null
    }

    /** 解析并执行 TOOL json，返回用户可见的结果文本。query=用户原话用于 addAlert 二次换算 */
    private fun executeTool(toolLine: String, prefs: SharedPreferencesModule, query: String = ""): String {
        return try {
            val obj = ToolArgs.parse(toolLine)
            // 优先协议字段（name/tool/function/...）；为空时模型可能把 name 当外层 key（{"addAlert":{...}}），
            // 从 map 的 keys 里挑一个看起来像工具名的 key 当 name
            var name = canonicalizeToolName(obj.toolName())
            if (name.isBlank()) name = obj.candidateNames().firstOrNull { canonicalizeToolName(it).isNotBlank() }
                ?.let { canonicalizeToolName(it) } ?: ""
            when (name) {
                "addWatch", "addCompare", "addAlert" -> {
                    val stockName = obj.optString("stock")
                    val st = resolveStock(stockName)
                        ?: return "未找到股票「$stockName」，未执行。可用完整名或6位代码。"
                    when (name) {
                        "addWatch" -> AgentActions.addWatch(st, prefs)
                        "addCompare" -> AgentActions.addCompare(st, prefs)
                        else -> {
                            val typeLabel = obj.optString("type")
                            var threshold = obj.optDouble("threshold").toFloat()
                            var mapped = mapType(typeLabel)
                            // 二次换算：拿用户原话 query 校准金额/百分比语义。
                            // 规则: query 里若没含 %/百分之/百分比词 + 出现 跌/跌破/涨/涨破/超过/少于 + 数字
                            //       且 st.price>0 → 模型若把 type 弄成金额式(below/above), 用 现价∓query里数字 替代
                            val qRecalc = recalcFromQuery(query, mapped, threshold, st.price)
                            if (qRecalc != null) {
                                mapped = qRecalc.first
                                threshold = qRecalc.second
                            }
                            AgentActions.addAlert(st, mapped, threshold, prefs)
                        }
                    }
                }
                "setThemeColor" -> {
                    var c = obj.optString("colorName").ifBlank { obj.optString("color") }
                    var argb = if (c.isNotBlank()) colorToArgb(c) else null
                    // 字段名/参数格式可能与少样本不一致：从原始 toolLine 里扫颜色词兜底
                    if (argb == null) {
                        argb = colorToArgb(toolLine)
                        c = "（已从描述解析）"
                    }
                    if (argb == null) "无法识别颜色，请说清楚要哪种颜色（如红/蓝/绿）"
                    else AgentActions.setThemeColor(argb, prefs)
                }
                "setFontScale" -> {
                    var level = obj.optString("level").ifBlank { obj.optString("size") }
                    if (level.isBlank()) level = toolLine // 兜底从原始文本扫档位词
                    val scale = fontLevelToScale(level)
                    AgentActions.setFontScale(scale, prefs)
                }
                "toggleMiniCard" -> {
                    var comp = obj.optString("component").ifBlank { obj.optString("name") }
                    if (comp.isBlank()) comp = toolLine // 兜底扫
                    val on = obj.optString("on") == "true" || obj.optBoolean("on", true)
                    val key = miniCardComponentToKey(comp)
                    if (key == null) "无法识别要开关的迷你卡组件，请说 分时走势/AI 智能分析/简况/基本面"
                    else AgentActions.toggleMiniCard(key, on, prefs)
                }
                "setColorMode" -> {
                    val m = obj.optString("mode").ifBlank { obj.optString("colorMode") }
                    val mode = when {
                        m.contains("欧") || m.contains("美") || m.contains("红跌") || m.contains("绿涨") || m.contains("1") -> 1
                        else -> 0
                    }
                    AgentActions.setColorModeVal(mode, prefs)
                }
                "setHideDays" -> {
                    val days = obj.optDouble("days").let { if (it.isNaN()) 7.0 else it }.toInt()
                    AgentActions.setHideDays(days, prefs)
                }
                "restoreHidden" -> {
                    val scopeAll = obj.optString("scope").contains("全部") || obj.optBoolean("all", false)
                    if (scopeAll) {
                        AgentActions.restoreHiddenAll(prefs)
                    } else {
                        val nm = obj.optString("stock").ifBlank { obj.optString("name") }
                        val st = if (nm.isNotBlank()) resolveStock(nm) else null
                        if (st == null) "未找到股票「$nm」，无法单独恢复。也可以说\"恢复全部不感兴趣的股票\"。"
                        else AgentActions.restoreHiddenOne(st.code, st.name, prefs)
                    }
                }
                else -> "未知操作：${if (name.isBlank()) "name 缺失" else name}（请把这个原文发我便于修复：${toolLine.take(200)}）"
            }
        } catch (e: Throwable) {
            "操作执行失败：${e.message ?: "未知错误"}（请把这个原文发我便于修复：${toolLine.take(200)}）"
        }
    }

/**
 * 容错 JSON 字段读取：先按标准 JSON 解析；失败时回退到正则从 raw 文本里抠字段。
 * 模型经常输出 {name:"x"}（key 没引号）或 {"name": x}（value 没引号）等非标 JSON，
 * 严格 JSONObject 会抛 Expected ':' / Expected ',' — 用 regex 兜底保证 agent 不死锁。
 * 同时维护 [candidateNames] — 模型有时把工具名当外层 key（如 {"addAlert":{"stock":...}}），
 * 这种情况下外层 key 不进 params 但作为候选工具名供 executeTool 遍历使用。
 */
private class ToolArgs private constructor(
        private val params: Map<String, String>,
        private val toolNameCandidates: List<String>,
    ) {
        fun optString(key: String): String = params[key] ?: params[key.lowercase()] ?: ""
        fun optDouble(key: String): Double = params[key]?.toDoubleOrNull()
            ?: params[key.lowercase()]?.toDoubleOrNull() ?: 0.0
        fun optBoolean(key: String, default: Boolean): Boolean {
            val v = params[key] ?: params[key.lowercase()] ?: return default
            return v == "true" || v == "1"
        }
        fun candidateNames(): List<String> = toolNameCandidates

        /** 模型把工具名放协议字段里时取出来（含驼峰后缀变体：functionName/toolName/...） */
        fun toolName(): String {
            listOf(
                "name", "tool", "function", "operation", "action", "cmd", "command",
                "functionName", "toolName", "methodName", "apiName", "actionName", "commandName",
            ).forEach { k ->
                val v = params[k] ?: params[k.lowercase()] ?: ""
                if (v.isNotBlank()) return v
            }
            return ""
        }

        companion object {
            private val NON_TOOL_KEYS = setOf("stock", "type", "threshold", "colorName", "color",
                "boolean", "args", "parameters", "params", "input", "data",
                "functionName", "toolName", "methodName", "apiName", "actionName", "commandName")

            fun parse(raw: String): ToolArgs {
                // 路径1: 标准 JSON（递归拍平所有内嵌对象字段，保留外层 keys 当候选名）
                try {
                    val obj = JSONObject(raw)
                    val params = mutableMapOf<String, String>()
                    val outerKeys = mutableListOf<String>()
                    flattenFlatten(obj, params, outerKeys)
                    val args = obj.optJSONObject("args")
                    if (args != null) flattenFlatten(args, params, outerKeys)
                    val candidates = outerKeys.filter { it.lowercase() !in NON_TOOL_KEYS }
                    return ToolArgs(params, candidates)
                } catch (_: Throwable) { /* 退化到 regex */ }
                // 路径2: regex 兜底
                return ToolArgs(regexFallback(raw), emptyList())
            }

            /** 把 obj 所有 string/number/boolean 字段拍平到 params；非 string 子对象再递归一层 */
            private fun flattenFlatten(obj: JSONObject, params: MutableMap<String, String>, outerKeys: MutableList<String>) {
                obj.keys().forEach { k ->
                    outerKeys.add(k)
                    val v = obj.opt(k)
                    when (v) {
                        is String -> if (v.isNotBlank()) params[k] = v
                        is Number -> params[k] = v.toString()
                        is Boolean -> params[k] = v.toString()
                        is JSONObject -> flattenFlatten(v, params, outerKeys)
                    }
                }
            }

            private fun regexFallback(raw: String): Map<String, String> {
                val m = mutableMapOf<String, String>()
                Regex("\"([\\w]+)\"\\s*:\\s*\"([^\"]*)\"").findAll(raw).forEach {
                    m[it.groupValues[1]] = it.groupValues[2]
                }
                Regex("\"([\\w]+)\"\\s*:\\s*([0-9.\\-]+)").findAll(raw).forEach {
                    if (!m.containsKey(it.groupValues[1])) m[it.groupValues[1]] = it.groupValues[2]
                }
                Regex("\"([\\w]+)\"\\s*:\\s*(true|false)").findAll(raw).forEach {
                    if (!m.containsKey(it.groupValues[1])) m[it.groupValues[1]] = it.groupValues[2]
                }
                Regex("([\\w]+)\\s*:\\s*\"([^\"]*)\"").findAll(raw).forEach {
                    if (!m.containsKey(it.groupValues[1])) m[it.groupValues[1]] = it.groupValues[2]
                }
                return m
            }
        }
    }

    /** 抓当前自选/行情池的（name, price）简短表，给模型用 — 用于"加跌100的预警"按当前价算阈值。
     *  上限 30 行避免 prompt 过长 */
    private fun buildPriceTable(): String {
        val quotes = try { StockData.getQuotes() } catch (_: Throwable) { return "" }
        if (quotes.isEmpty()) return ""
        val sb = StringBuilder()
        quotes.take(30).forEach { q ->
            if (q.name.isNotBlank() && q.price > 0) {
                sb.append(q.name).append('=').append("%.2f".format(q.price)).append("; ")
            }
        }
        return sb.toString().trim().removeSuffix(";")
    }

    /**
     * 用用户原话二次校准 addAlert 的 (type, threshold)。
     * 触发: query 没含%/"百分之"/"百分比" + 含 跌/跌破/涨/涨破/超过 + 数字
     *       + mapped 是金额式(below/above) + price>0 + 模型给的 threshold ≈ query 里的金额
     *       → 拿金额换算, threshold=price∓金额。
     */
    private fun recalcFromQuery(query: String, mapped: String, threshold: Float, price: Float): Pair<String, Float>? {
        if (price <= 0f || query.isBlank()) return null
        if (query.contains("%") || query.contains("百分之") || query.contains("百分比")) return null
        if (mapped != "below" && mapped != "above") return null
        val isDown = query.contains("跌") || query.contains("跌破")
        val isUp = query.contains("涨") || query.contains("涨破") || query.contains("超过")
        if (!isDown && !isUp) return null
        val numRegex = Regex("([0-9]+(?:\\.[0-9]+)?)")
        val match = numRegex.find(query) ?: return null
        val amount = match.groupValues[1].toFloatOrNull() ?: return null
        val directionMatch = (isDown && mapped == "below") || (isUp && mapped == "above")
        if (!directionMatch) return null
        if (kotlin.math.abs(threshold - amount) > 1f) return null
        val newThreshold = if (isDown) (price - amount).coerceAtLeast(0f) else price + amount
        return Pair(mapped, newThreshold)
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

    /** 把模型可能输出的别名（change_theme_color / set_theme_color / tool / name 等）规范化到我们的内部名。
 *  返回空字符串表示"识别不出来"，调用方应继续找下一个候选（不要把 raw 当 name 用） */
private fun canonicalizeToolName(raw: String): String {
    val n = raw.trim().lowercase()
    if (n.isEmpty()) return ""
    // 已是自己名字
    if (n in setOf("addwatch", "addcompare", "addalert", "setthemecolor", "setfontscale",
            "toggleminicard", "setcolormode", "sethidedays", "restorehidden")) return raw
    // watch / 自选
    if (n.contains("watch") || n.contains("self") || n.contains("favorite") || n.contains("关注")) return "addWatch"
    if (n.contains("compare") || n.contains("对比")) return "addCompare"
    // 预警/价格提醒：alert/预警/提醒/价格监控/trigger/notify 都视为 addAlert
    if (n.contains("alert") || n.contains("预警") || n.contains("提醒") ||
        n.contains("price") || n.contains("notify") || n.contains("trigger") || n.contains("monitor")) return "addAlert"
    // 涨跌配色（color+mode 优先于纯 color，避免配色误归主题色）
    if ((n.contains("color") && n.contains("mode")) || n.contains("涨跌") ||
        n.contains("配色") || n.contains("colormode") || n.contains("红涨") || n.contains("红跌")) return "setColorMode"
    // 改主题色（兼容 change_color / set_color / color_theme / change_theme / 改颜色 等）
    if (n.contains("color") || n.contains("theme") || n.contains("色")) return "setThemeColor"
    // 字体大小
    if (n.contains("font") || n.contains("字体") || n.contains("字号") || n.contains("fontsize")) return "setFontScale"
    // 迷你卡片/展开卡组件开关
    if (n.contains("minicard") || n.contains("minichart") || n.contains("expand") ||
        n.contains("迷你") || n.contains("卡片") || n.contains("展开")) return "toggleMiniCard"
    // 隐藏股票：设恢复天数 vs 恢复
    if (n.contains("hide") && (n.contains("day") || n.contains("天") || n.contains("period") || n.contains("周期"))) return "setHideDays"
    if (n.contains("restore") || n.contains("recover") || n.contains("不感兴趣") || n.contains("恢复")) return "restoreHidden"
    if (n.contains("hide") || n.contains("hidden")) return "restoreHidden"
    // 没匹配：返回空（让上层继续找其他候选）
    return ""
}

    /** 字体档位词面 → scale（0.85 小 / 1.0 标准 / 1.15 大 / 1.3 特大）。无法识别默认标准 */
    private fun fontLevelToScale(level: String): Float {
        val l = level.lowercase()
        return when {
            l.contains("特大") || l.contains("超大") || l.contains("很大") -> 1.3f
            l.contains("小") -> 0.85f
            l.contains("大") -> 1.15f
            l.contains("标准") || l.contains("中") || l.contains("normal") -> 1.0f
            else -> l.toFloatOrNull()?.let { f ->
                when { f <= 0.9f -> 0.85f; f < 1.07f -> 1.0f; f < 1.22f -> 1.15f; else -> 1.3f }
            } ?: 1.0f
        }
    }

    /** 迷你卡组件词面 → UserSettings.expand 的 key。无法识别返回 null */
    private fun miniCardComponentToKey(c: String): String? {
        val s = c.lowercase()
        return when {
            s.contains("分时") || s.contains("走势") || s.contains("trend") -> UserSettings.EXPAND_TREND
            s.contains("分析") || s.contains("ai") || s.contains("智能") -> UserSettings.EXPAND_AI
            s.contains("简况") || s.contains("brief") -> UserSettings.EXPAND_BRIEF
            s.contains("基本面") || s.contains("财务") || s.contains("finance") || s.contains("f10") -> UserSettings.EXPAND_FINANCE
            else -> null
        }
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
