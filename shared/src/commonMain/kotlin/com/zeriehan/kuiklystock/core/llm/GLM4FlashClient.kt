package com.zeriehan.kuiklystock.core.llm

import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock

/**
 * 真实 GLM-4-Flash 接入点（预留 / 可热切换）。
 *
 * 当前 commonMain 未引入网络库与 kotlinx.coroutines，真实 HTTP 走 bridgeModule 下发到
 * Android 宿主（androidApp）执行：宿主用 OkHttp 调用
 *   POST https://open.bigmodel.cn/api/paas/v4/chat/completions
 *   Authorization: Bearer $GLM_API_KEY
 * 需要：① 在 BridgeModule 增加 "llmAnalyze" 方法；② androidApp 侧实现该 handler；③ 配置 GLM_API_KEY。
 *
 * 未配置 Key 时自动回退 [fallback]，保证演示链路始终可用——当前默认即走 Mock。
 */
class GLM4FlashClient(private val fallback: LLMClient) : LLMClient {

    // TODO: 配置为真实 Key 后启用真实通道（可改为从本地配置 / 远端下发读取）
    private val GLM_API_KEY: String = ""

    override fun analyze(
        stock: Stock,
        kline: List<KLineBar>,
        callback: (String) -> Unit
    ) {
        if (GLM_API_KEY.isBlank()) {
            fallback.analyze(stock, kline, callback)
            return
        }
        // 真实实现示意：
        // val params = JSONObject().apply {
        //     put("code", stock.code)
        //     put("name", stock.name)
        //     put("price", stock.price)
        //     put("changePercent", stock.changePercent)
        //     put("kline", kline.map { it.close })   // 仅传收盘价序列即可
        // }
        // bridgeModule.callNativeMethod("llmAnalyze", params) { resp ->
        //     callback(resp.optString("text"))
        // }
        fallback.analyze(stock, kline, callback)
    }
}
