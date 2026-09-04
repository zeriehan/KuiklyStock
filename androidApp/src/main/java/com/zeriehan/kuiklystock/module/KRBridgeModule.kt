package com.zeriehan.kuiklystock.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.zeriehan.kuiklystock.KRApplication
import com.zeriehan.kuiklystock.KuiklyRenderActivity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.concurrent.thread

class KRBridgeModule : KuiklyRenderBaseModule() {

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "ssoRequest" -> {
                ssoRequest(params, callback)
            }

            "showAlert" -> {
                showAlert(params, callback)
            }

            "closePage" -> {
                closePage(params)
            }

            "openPage" -> {
                openPage(params)
            }

            "copyToPasteboard" -> {
                copyToPasteboard(params)
            }

            "showSelectableText" -> {
                showSelectableText(params)
            }

            "toast" -> {
                toast(params)
            }

            "log" -> {
                log(params)
            }

            "reportDT" -> {
                reportDT(params)
            }

            "reportRealtime" -> {
                reportRealtime(params)
            }

            "qqLiveSSORequest" -> {
                qqLiveSSORequest(params, callback)
            }

            "localServeTime" -> {
                localServeTime(params, callback)
            }

            "currentTimestamp" -> {
                currentTimestamp(params)
            }

            "dateFormatter" -> {
                dateFormatter(params)
            }

            "llmAnalyze" -> {
                llmAnalyze(params, callback)
            }

            "fetchQuotes" -> {
                fetchQuotes(params, callback)
            }

            "fetchClist" -> {
                fetchClist(params, callback)
            }

            "fetchSectors" -> {
                fetchSectors(params, callback)
            }

            "fetchSectorStocks" -> {
                fetchSectorStocks(params, callback)
            }

            "fetchKline" -> {
                fetchKline(params, callback)
            }

            "fetchTrends" -> {
                fetchTrends(params, callback)
            }

            else -> callback?.invoke(
                mapOf(
                    "code" to -1,
                    "message" to "方法不存在"
                )
            )
        }
    }

    private fun reportRealtime(params: String?) {
    }

    private fun reportDT(params: String?) {
    }

    private fun log(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        Log.i("KuiklyRender", paramJSON.optString("content"))
    }

    private fun toast(params: String?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        Toast.makeText(
            KRApplication.application,
            paramJSON.optString("content"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyToPasteboard(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.setPrimaryClip(ClipData.newPlainText(MODULE_NAME, paramJSON.optString("content")))
        }
    }

    private fun showSelectableText(params: String?) {
        if (params == null) return
        val paramJSON = JSONObject(params)
        val content = paramJSON.optString("text")
        if (content.isEmpty()) return
        val title = paramJSON.optString("title", "选取文字")
        val ctx = context ?: return
        val act = activity ?: return
        act.runOnUiThread {
            // 防重复叠加
            val decor = act.window?.decorView as? android.view.ViewGroup ?: return@runOnUiThread
            if (decor.findViewWithTag<android.view.View>(TAG_SELECT_LAYER) != null) return@runOnUiThread
            val density = ctx.resources.displayMetrics.density
            val dp = fun(v: Int) = (v * density + 0.5f).toInt()
            // 是否深色主题由宿主皮肤状态决定：尽力从 Kuikly 当前背景推断，默认白底深字
            val isDark = false

            // 覆盖层根（全屏、状态栏之下）
            val overlay = android.widget.FrameLayout(ctx).apply {
                tag = TAG_SELECT_LAYER
                setBackgroundColor(
                    android.graphics.Color.parseColor(if (isDark) "#FF1A1B1E" else "#FFFFFFFF")
                )
            }

            // —— 顶部栏 ——
            val topColor = if (isDark) "#FF222326" else "#FFFFFFFF"
            val top = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundColor(android.graphics.Color.parseColor(topColor))
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48)
                )
            }
            // 返回
            val back = android.widget.TextView(ctx).apply {
                setText("‹ 返回")
                textSize = 17f
                setTextColor(android.graphics.Color.parseColor("#FF576B95"))
                setPadding(dp(16), 0, dp(8), 0)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            back.setOnClickListener { act.runOnUiThread { dismissSelectLayer(act) } }
            top.addView(back)
            val titleView = android.widget.TextView(ctx).apply {
                setText(title)
                textSize = 16f
                setTextColor(android.graphics.Color.parseColor(if (isDark) "#FFE0E0E0" else "#FF222222"))
            }
            top.addView(titleView)
            overlay.addView(top)

            // —— 可选中文本区（长按出系统标准选区 + 复制）——
            val bodyBg = android.graphics.Color.parseColor(if (isDark) "#FF1A1B1E" else "#FFFFFFFF")
            val textColor = android.graphics.Color.parseColor(if (isDark) "#FFE6E6E6" else "#FF222222")
            val pad = dp(16)
            val textView = android.widget.TextView(ctx).apply {
                setTextIsSelectable(true)
                setText(content)
                textSize = 16f
                setTextColor(textColor)
                setLineSpacing((3 * density).toFloat(), 1.0f)
                setPadding(pad, dp(12), pad, dp(12))
            }
            val scrollView = android.widget.ScrollView(ctx).apply {
                setBackgroundColor(bodyBg)
                isVerticalScrollBarEnabled = true
                addView(
                    textView,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
                layoutParams = android.widget.FrameLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                ).apply { topMargin = dp(48) }
            }
            overlay.addView(scrollView)

            // 加入 decorView（覆盖在 Kuikly 渲染之上）
            decor.post {
                decor.addView(
                    overlay,
                    android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
            }
        }
    }

    private fun dismissSelectLayer(act: android.app.Activity) {
        (act.window?.decorView as? android.view.ViewGroup)?.let { decor ->
            decor.findViewWithTag<android.view.View>(TAG_SELECT_LAYER)?.let { decor.removeView(it) }
        }
    }

    private fun openPage(params: String?) {
        if (params == null) {
            return
        }
        val ctx = context ?: return
        val paramJSON = JSONObject(params)
        val url = paramJSON.optString("url")
    }

    private fun closePage(params: String?) {
        activity?.finish()
    }

    private fun showAlert(params: String?, callback: KuiklyRenderCallback?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        val titleText = paramJSON.optString("title")
        val message = paramJSON.optString("message")
        val buttons = paramJSON.optJSONArray("buttons") ?: JSONArray()
    }

    private fun ssoRequest(params: String?, callback: KuiklyRenderCallback?) {}

    private fun qqLiveSSORequest(params: String?, callback: KuiklyRenderCallback?) {
    }

    private fun localServeTime(params: String?, callback: KuiklyRenderCallback?) {
        val time = (System.currentTimeMillis() / 1000.0)
        callback?.invoke(
            mapOf(
                "time" to time
            )
        )
    }

    private fun currentTimestamp(params: String?): String {
        return (System.currentTimeMillis()).toString()
    }

    private fun dateFormatter(params: String?): String {
        val paramJSONObject = JSONObject(params ?: "{}")
        val data = Date(paramJSONObject.optLong("timeStamp"))
        val format = SimpleDateFormat(paramJSONObject.optString("format"))
        return format.format(data)
    }

    /**
     * AI 分析：在子线程发起 HTTPS 请求，拿到模型文本后切回主线程回调。
     * 未配置 [GLMConfig.API_KEY]、全部候选模型均失败或抛异常时回调空串
     * （shared 端据此回退 Mock，界面不会卡在"分析中"）。
     */
    private fun llmAnalyze(params: String?, callback: KuiklyRenderCallback?) {
        if (params == null) {
            callback?.invoke(mapOf("text" to ""))
            return
        }
        val prompt = JSONObject(params).optString("prompt")
        val key = GLMConfig.API_KEY
        if (key.isBlank()) {
            Log.w("KRBridge", "GLM_API_KEY 未配置，回退空文本（shared 端将回退 Mock）")
            callback?.invoke(mapOf("text" to ""))
            return
        }
        thread(name = "glm-llm") {
            val text = try {
                glmChatWithFallback(prompt, key)
            } catch (e: Throwable) {
                Log.e("KRBridge", "llmAnalyze failed", e)
                ""
            }
            Handler(Looper.getMainLooper()).post {
                callback?.invoke(mapOf("text" to text))
            }
        }
    }

    /**
     * 依次尝试 [GLMConfig.MODEL_CANDIDATES]，返回第一个成功生成的文本。
     *
     * 免费 Flash 模型池常见 `1305 该模型当前访问量过大`，属于可重试/可降级错误，
     * 此时继续尝试下一个候选模型；全部失败返回空串。
     */
    private fun glmChatWithFallback(prompt: String, key: String): String {
        for (model in GLMConfig.MODEL_CANDIDATES) {
            val text = try {
                glmChat(prompt, key, model)
            } catch (e: Throwable) {
                Log.w("KRBridge", "模型 $model 请求异常，尝试下一个候选", e)
                null
            }
            if (!text.isNullOrBlank()) {
                Log.i("KRBridge", "AI 分析成功，实际使用模型：$model")
                return text
            }
            Log.w("KRBridge", "模型 $model 未返回内容，降级到下一个候选")
        }
        Log.e("KRBridge", "全部候选模型均失败，回退 Mock")
        return ""
    }

    /**
     * 调用智谱 Chat Completions（OpenAI 兼容协议）。
     * @return choices[0].message.content；失败返回 null 以便上层降级。
     */
    private fun glmChat(prompt: String, key: String, model: String): String? {
        val conn = (URL(GLMConfig.ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Authorization", "Bearer $key")
            doOutput = true
            connectTimeout = GLMConfig.CONNECT_TIMEOUT_MS
            readTimeout = GLMConfig.READ_TIMEOUT_MS
        }

        val body = JSONObject().apply {
            put("model", model)
            put("temperature", GLMConfig.TEMPERATURE)
            put("stream", false)
            // 关闭思考过程：只要结论文本，显著降低首字延迟（4.5/4.7 系列支持，旧模型忽略该字段）
            put("thinking", JSONObject().apply { put("type", "disabled") })
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }.toString()

        try {
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = conn.errorStream?.bufferedReader()?.readText()
                Log.e("KRBridge", "GLM[$model] HTTP $code: $err")
                return null
            }

            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            // 业务错误也可能以 200 返回，统一识别
            json.optJSONObject("error")?.let {
                Log.e("KRBridge", "GLM[$model] error ${it.optString("code")}: ${it.optString("message")}")
                return null
            }
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            return choices.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        private const val TAG_SELECT_LAYER = "kr_select_text_layer"
    }
}

/**
 * 拉取东方财富实时行情（免 token 的 push2 批量接口）。
 * 在子线程请求，结果切回主线程回调；任何失败回调空列表（shared 端保留 mock）。
 *
 * 接口：push2.eastmoney.com/api/qt/ulist.np/get
 * 字段：f12=代码 f13=市场 f14=名称 f2=现价(元) f3=涨跌幅(%) f4=涨跌额(元)
 *       f5=成交量(手) f15=最高 f16=最低 f17=今开 f18=昨收
 * 返回给 shared 的归一化结构（quotes 用 JSON 字符串传递，规避桥对嵌套 List 的序列化差异）：
 * { "quotes": "[ { secid, code, name, price, change, changePercent, high, low, volume(万手) }, ... ]" }
 */
private fun fetchQuotes(params: String?, callback: KuiklyRenderCallback?) {
    val empty = mapOf("quotes" to "[]")
    if (params == null) {
        callback?.invoke(empty)
        return
    }
    val secids = JSONObject(params).optString("secids")
    if (secids.isBlank()) {
        callback?.invoke(empty)
        return
    }
    thread(name = "em-quotes") {
        val result = try {
            fetchEastMoneyQuotes(secids)
        } catch (e: Throwable) {
            Log.e("KRBridge", "fetchQuotes failed", e)
            "[]"
        }
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(mapOf("quotes" to result))
        }
    }
}

private fun fetchEastMoneyQuotes(secids: String): String {
    // 直走单股 qt/get(实测可靠)；ulist 批量在某些网络空/超时，跳过以省时且保证能取到真实价。
    // 逐只取每只的 现价/涨跌/开高低/昨收/量，归一化成与批量一致的结构。
    return fetchEastMoneyQuotesPerStock(secids)
}

/**
 * 腾讯实时报价(批量,一次请求返回全部)：与K线/分时同源(web.ifzq.gtimg.cn / qt.gtimg.cn)，设备可达则报价与K线分时一起真。
 * 逐行 v_sh601318="1~中国平安~601318~现价~昨收~今开~量(手)~...~时间~涨跌额~涨跌幅~最高~最低~..."
 * 归一化成与东财一致的结构 { secid,code,name,price,change,changePercent,high,low,open,prevClose,volume(万手) }。
 */
private fun fetchEastMoneyQuotesPerStock(secids: String): String {
    val ids = secids.split(",").filter { it.isNotBlank() }
    if (ids.isEmpty()) return "[]"
    // secid "1.601318"/"0.000858" → sh601318 / sz000858
    val codes = ids.map { s ->
        val dot = s.indexOf('.')
        val market = if (dot > 0) s.substring(0, dot) else "1"
        val code = if (dot > 0) s.substring(dot + 1) else s
        (if (market == "1") "sh" else "sz") + code
    }.joinToString(",")
    val url = "https://qt.gtimg.cn/q=$codes"
    try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://gu.qq.com/")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("KRBridge", "TX quote HTTP ${conn.responseCode}")
                return "[]"
            }
            // 腾讯报价是 GBK 编码，名称中文需按 GBK 解码
            val bytes = conn.inputStream.readBytes()
            val body = String(bytes, Charsets.UTF_8).let {
                // 若按 UTF-8 解出来是乱码，则按 GBK 重解
                if (it.contains('\uFFFD')) String(bytes, java.nio.charset.Charset.forName("GBK")) else it
            }
            val out = JSONArray()
            for (s in ids) {
                val dot = s.indexOf('.')
                val market = if (dot > 0) s.substring(0, dot) else "1"
                val code = if (dot > 0) s.substring(dot + 1) else s
                val key = (if (market == "1") "sh" else "sz") + code
                // 找 v_<key>="..."
                val marker = "v_$key=\""
                val idx = body.indexOf(marker)
                if (idx < 0) continue
                val end = body.indexOf('"', idx + marker.length)
                if (end < 0) continue
                val f = body.substring(idx + marker.length, end).split("~")
                if (f.size < 35) continue
                val price = f[3].toDoubleOrNull() ?: continue
                if (price <= 0.0) continue
                val name = f[1]
                out.put(
                    JSONObject().apply {
                        put("secid", s)
                        put("code", code)
                        put("name", name)
                        put("price", price)
                        put("change", f[31].toDoubleOrNull() ?: 0.0)         // 涨跌额
                        put("changePercent", f[32].toDoubleOrNull() ?: 0.0)  // 涨跌幅
                        put("high", f[33].toDoubleOrNull() ?: 0.0)           // 最高
                        put("low", f[34].toDoubleOrNull() ?: 0.0)            // 最低
                        put("open", f[5].toDoubleOrNull() ?: 0.0)            // 今开
                        put("prevClose", f[4].toDoubleOrNull() ?: 0.0)       // 昨收
                        put("volume", (f[6].toDoubleOrNull() ?: 0.0) / 10000.0) // 手 → 万手
                    }
                )
            }
            return out.toString()
        } finally {
            conn.disconnect()
        }
    } catch (e: Throwable) {
        Log.e("KRBridge", "TX quote failed", e)
        return "[]"
    }
}

/**
 * 拉取东方财富榜单个股（clist 排序接口），返回榜内股票列表。
 * shared 传参：{ "fs": "市场过滤串", "fid": "排序字段(f3涨幅/f8换手/振幅用f3排序取高跌幅...)", "po": "1降/-1升", "pn": 页, "pz": 每页 }
 * 统一由宿主构造 clist URL。字段与 fetchQuotes 一致（归一化 JSON 字符串）。
 */
private fun fetchClist(params: String?, callback: KuiklyRenderCallback?) {
    val empty = mapOf("quotes" to "[]")
    if (params == null) { callback?.invoke(empty); return }
    val p = JSONObject(params)
    val fs = p.optString("fs", "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23")
    val fid = p.optString("fid", "f3")
    val po = p.optInt("po", 1)
    val pn = p.optInt("pn", 1)
    val pz = p.optInt("pz", 30)
    thread(name = "em-clist") {
        val result = try {
            fetchEastMoneyClist(fs, fid, po, pn, pz)
        } catch (e: Throwable) {
            Log.e("KRBridge", "fetchClist failed", e)
            "[]"
        }
        Handler(Looper.getMainLooper()).post { callback?.invoke(mapOf("quotes" to result)) }
    }
}

private fun fetchEastMoneyClist(fs: String, fid: String, po: Int, pn: Int, pz: Int): String {
    // 新浪全A股实时榜(设备可达; 东财 clist 在部分网络不可达)。sort 由 fid 映射, asc 由 po 映射(po=1降序→asc=0)。
    val sort = when (fid) {
        "f8" -> "turnoverratio" // 换手率
        "f7" -> "amplitude"     // 振幅
        else -> "changepercent" // 涨幅/跌幅(f3)
    }
    val asc = if (po == 1) 0 else 1
    val num = pz.coerceIn(1, 100)
    val url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
        "Market_Center.getHQNodeData?page=$pn&num=$num&sort=$sort&asc=$asc&node=hs_a"
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
        setRequestProperty("User-Agent", "Mozilla/5.0")
        setRequestProperty("Referer", "https://finance.sina.com.cn/")
    }
    try {
        if (conn.responseCode != HttpURLConnection.HTTP_OK) {
            Log.e("KRBridge", "Sina rank HTTP ${conn.responseCode}")
            return "[]"
        }
        val body = conn.inputStream.bufferedReader().readText().trim()
        // 返回 JSON 数组字符串 (json_v2 不做 JSONP 包裹)
        val arr = JSONArray(body)
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val code = it.optString("code")
            val price = it.optDouble("trade", 0.0)
            if (code.isBlank() || price <= 0.0) continue
            out.put(
                JSONObject().apply {
                    put("code", code)
                    put("name", it.optString("name"))
                    put("price", price)
                    put("change", it.optDouble("pricechange", 0.0))
                    put("changePercent", it.optDouble("changepercent", 0.0))
                    put("high", it.optDouble("high", 0.0))
                    put("low", it.optDouble("low", 0.0))
                    put("open", it.optDouble("open", 0.0))
                    put("prevClose", it.optDouble("settlement", 0.0))
                    put("volume", (it.optDouble("volume", 0.0) / 1000000.0)) // 新浪 volume 单位 股 → 百万股(近似万手量级, 归一化用不苛求)
                    put("turnover", it.optDouble("turnoverratio", 0.0))
                    put("amplitude", 0.0)
                }
            )
        }
        return out.toString()
    } catch (e: Throwable) {
        Log.e("KRBridge", "Sina rank failed", e)
        return "[]"
    } finally {
        conn.disconnect()
    }
}

/**
 * 拉取东方财富真实行业板块列表（涨跌幅降序）。返回 { "sectors": "[ { secid, code, name, changePercent }, ... ]" }
 */
private fun fetchSectors(params: String?, callback: KuiklyRenderCallback?) {
    thread(name = "em-sectors") {
        val result = try {
            fetchEastMoneySectors()
        } catch (e: Throwable) {
            Log.e("KRBridge", "fetchSectors failed", e)
            "[]"
        }
        Handler(Looper.getMainLooper()).post { callback?.invoke(mapOf("sectors" to result)) }
    }
}

private fun fetchEastMoneySectors(): String {
    // 新浪行业板块(newSinaHy) + 概念板块(newFLJK) —— 设备可达源；东财板块 clist 部分网络不可达。
    // 行业行格式: code,名称,家数,均价,涨跌额,涨跌幅,量,额,领涨sym,领涨涨幅,领涨价,领涨涨跌,领涨名
    val out = JSONArray()
    val sources = listOf(
        "https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php" to "new_",
        "https://money.finance.sina.com.cn/q/view/newFLJK.php?param=class" to "gn_"
    )
    for ((urlStr, keepPrefix) in sources) {
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://finance.sina.com.cn/")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) continue
                val bytes = conn.inputStream.readBytes()
                val body = String(bytes, Charsets.UTF_8).let {
                    if (it.contains('\uFFFD')) String(bytes, java.nio.charset.Charset.forName("GBK")) else it
                }
                // 形如 var ...= {"new_blhy":"new_blhy,玻璃行业,...", ...};
                val start = body.indexOf('{')
                val end = body.lastIndexOf('}')
                if (start < 0 || end < start) continue
                val jsonStr = body.substring(start, end + 1)
                val root = JSONObject(jsonStr)
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    if (!key.startsWith(keepPrefix)) continue
                    val line = root.optString(key)
                    val f = line.split(",")
                    if (f.size < 6) continue
                    val code = f[0]
                    val name = f[1]
                    val chgPct = f[5].toDoubleOrNull() ?: continue
                    if (code.isBlank() || name.isBlank()) continue
                    out.put(
                        JSONObject().apply {
                            put("secid", code)
                            put("code", code)
                            put("name", name)
                            put("changePercent", chgPct)
                            put("price", f[3].toDoubleOrNull() ?: 0.0)
                            put("upCount", 0)
                            put("downCount", 0)
                            put("leaderName", if (f.size > 12) f[12] else "")
                            put("leaderChangePercent", if (f.size > 9) f[9].toDoubleOrNull() ?: 0.0 else 0.0)
                        }
                    )
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.e("KRBridge", "Sina sectors $urlStr failed", e)
        }
    }
    // 按涨跌幅降序
    val arr = JSONArray()
    val list = mutableListOf<JSONObject>()
    for (i in 0 until out.length()) list.add(out.optJSONObject(i))
    list.sortByDescending { it.optDouble("changePercent") }
    for (o in list) arr.put(o)
    return arr.toString()
}

/**
 * 拉取某行业板块的实时成分股（clist 按板块过滤 fs=b:BKxxxx）。返回 { "quotes": "[ 同 quotes 结构 ]" }
 * shared 传 { "code": "BK0475" }
 */
private fun fetchSectorStocks(params: String?, callback: KuiklyRenderCallback?) {
    val empty = mapOf("quotes" to "[]")
    if (params == null) { callback?.invoke(empty); return }
    val bk = JSONObject(params).optString("code")
    if (bk.isBlank()) { callback?.invoke(empty); return }
    thread(name = "em-secstk") {
        val result = try {
            // 新浪板块成分：getHQNodeData?node=<新浪板块code>（行业 new_xxx / 概念 gn_xxx），设备可达
            val url = "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/" +
                "Market_Center.getHQNodeData?page=1&num=80&sort=changepercent&asc=0&node=$bk"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://finance.sina.com.cn/")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("KRBridge", "Sina secstk HTTP ${conn.responseCode}")
                    "[]"
                } else {
                    val body = conn.inputStream.bufferedReader().readText().trim()
                    val arr = JSONArray(body)
                    val out = JSONArray()
                    for (i in 0 until arr.length()) {
                        val it = arr.optJSONObject(i) ?: continue
                        val code = it.optString("code")
                        val price = it.optDouble("trade", 0.0)
                        if (code.isBlank() || price <= 0.0) continue
                        out.put(
                            JSONObject().apply {
                                put("code", code)
                                put("name", it.optString("name"))
                                put("price", price)
                                put("change", it.optDouble("pricechange", 0.0))
                                put("changePercent", it.optDouble("changepercent", 0.0))
                                put("high", it.optDouble("high", 0.0))
                                put("low", it.optDouble("low", 0.0))
                                put("open", it.optDouble("open", 0.0))
                                put("prevClose", it.optDouble("settlement", 0.0))
                                put("volume", (it.optDouble("volume", 0.0) / 1000000.0))
                                put("turnover", it.optDouble("turnoverratio", 0.0))
                            }
                        )
                    }
                    out.toString()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.e("KRBridge", "fetchSectorStocks failed", e)
            "[]"
        }
        Handler(Looper.getMainLooper()).post { callback?.invoke(mapOf("quotes" to result)) }
    }
}

/** 把 clist 的 diff JSONArray 统一转成 normalized quotes JSON 字符串（成分股复用） */
private fun buildStocksJson(diff: JSONArray): String {
    val out = JSONArray()
    for (i in 0 until diff.length()) {
        val it = diff.optJSONObject(i) ?: continue
        val market = it.optInt("f13", 0)
        val code = it.optString("f12")
        val secid = "$market.$code"
        val price = it.optDouble("f2", 0.0).toFloat()
        if (price <= 0f) continue
        out.put(
            JSONObject().apply {
                put("secid", secid)
                put("code", code)
                put("name", it.optString("f14"))
                put("price", price.toDouble())
                put("change", it.optDouble("f4", 0.0))
                put("changePercent", it.optDouble("f3", 0.0))
                put("high", it.optDouble("f15", 0.0))
                put("low", it.optDouble("f16", 0.0))
                put("open", it.optDouble("f17", 0.0))
                put("prevClose", it.optDouble("f18", 0.0))
                put("volume", (it.optDouble("f5", 0.0) / 10000.0))
                put("turnover", it.optDouble("f8", 0.0))
            }
        )
    }
    return out.toString()
}

/**
 * 拉取个股历史 K线（东方财富 push2his kline）。返回 { "kline": "[{date,open,close,high,low,volume},...]" }（最老→最新）
 * shared 传 { "secid": "1.601318", "klt": 101, "count": 80 }；klt: 101日/102周/103月/104年（fqt=1 前复权）
 */
private fun fetchKline(params: String?, callback: KuiklyRenderCallback?) {
    val empty = mapOf("kline" to "[]")
    if (params == null) { callback?.invoke(empty); return }
    val p = JSONObject(params)
    val secid = p.optString("secid")
    if (secid.isBlank()) { callback?.invoke(empty); return }
    val klt = p.optInt("klt", 101)
    val count = p.optInt("count", 80).coerceIn(10, 500)
    thread(name = "em-kline") {
        val result = try {
            // 腾讯历史K线（沙箱与真机均可达；push2his 东财历史域在某些网络不可达）。
            // secid "1.601318"/"0.000858" → 腾讯 "sh601318"/"sz000858"
            val dot = secid.indexOf('.')
            val market = if (dot > 0) secid.substring(0, dot) else "1"
            val code = if (dot > 0) secid.substring(dot + 1) else secid
            val prefix = if (market == "1") "sh" else "sz"
            val periodKey = when (klt) { 102 -> "week"; 103 -> "month"; 104 -> "year"; else -> "day" }
            val fqKey = "qfq" + when (klt) { 102 -> "week"; 103 -> "month"; else -> "day" } // qfqweek/qfqmonth/qfqday
            val url = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get" +
                "?param=$prefix$code,$periodKey,,,$count,qfq"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://gu.qq.com/")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("KRBridge", "TX kline HTTP ${conn.responseCode}")
                    "[]"
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val root = json.optJSONObject("data")?.optJSONObject("$prefix$code")
                    val arr = root?.optJSONArray(fqKey) ?: JSONArray()
                    val out = JSONArray()
                    // 腾讯每根: ["2026-09-02", open, close, high, low, volume]
                    for (i in 0 until arr.length()) {
                        val row = arr.optJSONArray(i) ?: continue
                        if (row.length() < 5) continue
                        val close = row.optDouble(2)
                        if (close <= 0.0) continue
                        out.put(
                            JSONObject().apply {
                                put("date", row.optString(0))
                                put("open", row.optDouble(1))
                                put("close", close)
                                put("high", row.optDouble(3))
                                put("low", row.optDouble(4))
                                put("volume", if (row.length() > 5) row.optDouble(5) else 0.0)
                            }
                        )
                    }
                    out.toString()
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.e("KRBridge", "fetchKline failed", e)
            "[]"
        }
        Handler(Looper.getMainLooper()).post { callback?.invoke(mapOf("kline" to result)) }
    }
}

/**
 * 拉取个股当日分时（东方财富 push2 trends2）。返回 { "trends": "[{time,price,avg},...]", "preClose": 57.23 }
 * shared 传 { "secid": "1.601318" }
 */
private fun fetchTrends(params: String?, callback: KuiklyRenderCallback?) {
    val empty = mapOf("trends" to "[]", "preClose" to 0.0)
    if (params == null) { callback?.invoke(empty); return }
    val secid = JSONObject(params).optString("secid")
    if (secid.isBlank()) { callback?.invoke(empty); return }
    thread(name = "em-trends") {
        val (trendsJson, preClose) = try {
            // 腾讯分时（与K线同源，K线可达时分时也可靠；东财 trends2 部分网络限流/不可达）
            // secid "1.601318"/"0.000858" → sh601318 / sz000858
            val dot = secid.indexOf('.')
            val market = if (dot > 0) secid.substring(0, dot) else "1"
            val code = if (dot > 0) secid.substring(dot + 1) else secid
            val prefix = if (market == "1") "sh" else "sz"
            val tcode = "$prefix$code"
            val url = "https://web.ifzq.gtimg.cn/appstock/app/minute/query?code=$tcode"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Referer", "https://gu.qq.com/")
            }
            try {
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    Log.e("KRBridge", "TX minute HTTP ${conn.responseCode}")
                    "[]" to 0.0
                } else {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val node = json.optJSONObject("data")?.optJSONObject(tcode)
                    val dataArr = node?.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
                    val pre = node?.optJSONObject("qt")?.optJSONArray(tcode)?.optDouble(4) ?: 0.0
                    val out = JSONArray()
                    var cumAmount = 0.0
                    var cumVol = 0.0
                    // 腾讯每项: "HHmm price vol(手) amount(元)" → 累计均价 = Σamount/Σ(vol*100)
                    for (i in 0 until dataArr.length()) {
                        val line = dataArr.optString(i)
                        val part = line.split(" ")
                        if (part.size < 3) continue
                        val price = part[1].toDoubleOrNull() ?: continue
                        if (price <= 0.0) continue
                        val vol = part[2].toDoubleOrNull() ?: 0.0
                        val amount = if (part.size >= 4) part[3].toDoubleOrNull() ?: 0.0 else 0.0
                        cumAmount += amount
                        cumVol += vol * 100.0
                        val hhmm = part[0]
                        val time = if (hhmm.length >= 4) hhmm.substring(0, 2) + ":" + hhmm.substring(2, 4) else hhmm
                        out.put(
                            JSONObject().apply {
                                put("time", time)
                                put("price", price)
                                put("avg", if (cumVol > 0.0) cumAmount / cumVol else price)
                            }
                        )
                    }
                    out.toString() to pre
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Throwable) {
            Log.e("KRBridge", "fetchTrends failed", e)
            "[]" to 0.0
        }
        Handler(Looper.getMainLooper()).post {
            callback?.invoke(mapOf("trends" to trendsJson, "preClose" to preClose))
        }
    }
}

private fun JSONObject.toMap(): Map<Any, Any> {
    val map = mutableMapOf<Any, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = opt(key)) {
            is JSONObject -> {
                map[key] = v.toMap()
            }

            else -> {
                v?.also {
                    map[key] = it
                }
            }
        }
    }
    return map
}