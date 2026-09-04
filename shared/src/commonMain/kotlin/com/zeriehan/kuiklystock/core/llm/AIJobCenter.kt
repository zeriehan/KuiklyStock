package com.zeriehan.kuiklystock.core.llm

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.zeriehan.kuiklystock.base.BridgeModule
import com.zeriehan.kuiklystock.base.Utils

/**
 * AI 任务中心（全局单例）：所有 LLM 请求统一走「常驻根页面」的桥，实现"后台继续跑"。
 *
 * ## 为什么需要它
 * `ChatPage` / `StockDetailPage` 都是**子页面**，返回即销毁。而 LLM 请求是异步的，
 * 原来用 `Utils.currentBridgeModule()`（= 当前页的桥）发起，一旦退出页面，
 * 该页的桥随页面一起失效 → 回调丢失 → 表现为「发完消息就退出，AI 再也不回复」。
 * 详情页的 AI 分析卡片同理：中途退出会导致分析结果没写进 [AIAnalysisStore]，下次进来要重新分析一遍。
 *
 * ## 解法
 * 根页面 `MainTabPager`（常驻，不会被销毁）在 `viewDidLoad` 把自己的 BridgeModule
 * 注册进来；此后所有 LLM 请求一律用这个"常驻桥"发送。子页面关掉后请求照常飞行、
 * 结果照样回调，回调里只写 [ChatStore] / [AIAnalysisStore] 这类**单例**，
 * 因此结果不会丢；子页面自己的 observable 若已销毁则写空操作，无害。
 */
internal object AIJobCenter {

    private var rootBridge: BridgeModule? = null

    /** 由常驻根页面（MainTabPager）调用，注册一个不会随子页面关闭而失效的桥 */
    fun attach(bridge: BridgeModule) {
        rootBridge = bridge
    }

    fun detach(bridge: BridgeModule) {
        if (rootBridge === bridge) rootBridge = null
    }

    /**
     * 用常驻桥弹一个 toast：供"后台任务完成"提示使用
     * （发起任务的页面可能已经销毁，用本页桥会失效）。
     */
    fun toast(message: String) {
        try {
            (rootBridge ?: Utils.currentBridgeModule()).toast(message)
        } catch (e: Throwable) {
            // 提示失败无所谓，不影响主流程
        }
    }

    /**
     * 下发 prompt。优先用根页桥（后台安全）；未注册时退回当前页桥（兜底）。
     * @param stream true 走宿主流式回调（多次 delta + 一次 done）；false 一次性 done。
     * 任何异常都以 null 回调，上层（GLMFlashClient）会回退 Mock，不会卡在「分析中」。
     */
    fun sendPrompt(prompt: String, stream: Boolean = false, callback: (JSONObject?) -> Unit) {
        val bridge = rootBridge
        if (bridge != null) {
            try {
                bridge.llmAnalyze(prompt, stream) { resp -> callback(resp) }
                return
            } catch (e: Throwable) {
                // 根页桥异常（极端情况）→ 继续尝试当前页桥
            }
        }
        try {
            Utils.currentBridgeModule().llmAnalyze(prompt, stream) { resp -> callback(resp) }
        } catch (e: Throwable) {
            callback(null)
        }
    }
}
