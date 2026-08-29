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