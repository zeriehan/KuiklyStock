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
 *
 * ## 流式（真·逐字蹦出）
 * 调研确认：Kuikly 桥的单次 callback **不透传多次 invoke**（request/response RPC），
 * 无法靠"宿主多次回调"推流。改为：宿主流式把累计文本写进按 sid 索引的缓存，
 * 这里用绑在常驻根页（[rootPagerId]，不随子页面销毁）的定时器 [pumpStream] 周期性轮询
 * 取增量 → 驱动聊天气泡逐字增长；生成结束仍经单次 done 回调兜底/后台落库。
 */
internal object AIJobCenter {

    private var rootBridge: BridgeModule? = null
    /** 常驻根页的 pagerId：流式轮询泵需要绑一个"常驻不销毁"的定时器 */
    private var rootPagerId: String = ""

    /** 由常驻根页面（MainTabPager）调用，注册一个不会随子页面关闭而失效的桥。
     *  @param pagerId 常驻根页的 pagerId（流式轮询泵绑它）；不传则回退 bridge.pagerId */
    fun attach(bridge: BridgeModule, pagerId: String? = null) {
        rootBridge = bridge
        if (!pagerId.isNullOrBlank()) rootPagerId = pagerId
        else if (bridge.pagerId.isNotBlank()) rootPagerId = bridge.pagerId
    }

    fun detach(bridge: BridgeModule) {
        if (rootBridge === bridge) {
            rootBridge = null
            rootPagerId = ""
        }
    }

    /**
     * 流式轮询泵：每隔 [intervalMs] 轮询宿主流式会话 [sid]，把累计文本回调给 [onUpdate]。
     * 宿主返回 finished（生成结束）时自动停止。返回取消句柄。
     *
     * 定时器优先绑**当前前台页**（调用 chat 时即在聊天页，其 pager 定时器可证会跑——思考中三点动画
     * 就靠它），确保子页面在前台时轮询持续、文字真能蹦出来；前台页不在时退回常驻根页。
     *
     * 首个 tick 也走 setTimeout（不立即同步轮询）：给宿主一个"创建 sid 缓存"的时序窗口，
     * 避免首次 poll 早于缓存创建而误判结束。另加最大 tick 数兜底，防 sid 真正不存在时无限轮询
     * （正常结束靠 done 回调兜底，此处只是防泄漏）。
     */
    fun pumpStream(sid: String, intervalMs: Int, onUpdate: (text: String, finished: Boolean) -> Unit): () -> Unit {
        // 调用发生在 ChatPage.send（当前页=聊天页）时前台页定时器最可靠；否则用常驻根页兜底
        val cur = com.tencent.kuikly.core.manager.BridgeManager.currentPageId
        val pagerId = if (!cur.isNullOrBlank()) cur else rootPagerId
        if (pagerId.isBlank() || sid.isBlank()) return {}
        var cancelled = false
        var ticks = 0
        fun tick() {
            if (cancelled) return
            ticks++
            pollStream(sid) { resp ->
                if (cancelled || resp == null) return@pollStream
                val text = resp.optString("text")
                val finished = resp.optBoolean("finished")
                onUpdate(text, finished)
                val keepGoing = !finished && !cancelled && ticks < MAX_POLL_TICKS
                if (keepGoing) {
                    com.tencent.kuikly.core.timer.setTimeout(pagerId, intervalMs) { tick() }
                }
            }
        }
        // 首个 tick 延迟一个周期再跑，避开缓存创建竞态
        com.tencent.kuikly.core.timer.setTimeout(pagerId, intervalMs) { tick() }
        return { cancelled = true }
    }

    /** 轮询泵最大 tick 数兜底：160ms×200≈32s，远大于单次生成耗时；防止 sid 不存在时无限轮询 */
    private const val MAX_POLL_TICKS = 200

    /** 轮询宿主流式会话 [sid] 的当前累计文本。回调 JSONObject 形如 {text, finished}；异常以 null 回调。 */
    fun pollStream(sid: String, callback: (JSONObject?) -> Unit) {
        val bridge = rootBridge
        if (bridge != null) {
            try {
                bridge.llmStreamPoll(sid) { resp -> callback(resp) }
                return
            } catch (e: Throwable) {
                // 根页桥异常 → 尝试当前页桥
            }
        }
        try {
            Utils.currentBridgeModule().llmStreamPoll(sid) { resp -> callback(resp) }
        } catch (e: Throwable) {
            callback(null)
        }
    }

    /**
     * 下发 prompt。优先用根页桥（后台安全）；未注册时退回当前页桥（兜底）。
     * @param stream true 走宿主流式（SSE 写缓存，shared 用 [pumpStream]/[pollStream] 拉增量），
     *               结束时回调一次 {type:done} 兜底/后台落库；false 一次性 {type:done,text}。
     * @param sid 流式会话 id（stream=true 时用于轮询定位宿主缓存）。
     * 任何异常都以 null 回调，上层（GLMFlashClient）会回退 Mock，不会卡在「分析中」。
     */
    fun sendPrompt(prompt: String, stream: Boolean = false, sid: String = "", callback: (JSONObject?) -> Unit) {
        val bridge = rootBridge
        if (bridge != null) {
            try {
                bridge.llmAnalyze(prompt, stream, sid) { resp -> callback(resp) }
                return
            } catch (e: Throwable) {
                // 根页桥异常（极端情况）→ 继续尝试当前页桥
            }
        }
        try {
            Utils.currentBridgeModule().llmAnalyze(prompt, stream, sid) { resp -> callback(resp) }
        } catch (e: Throwable) {
            callback(null)
        }
    }

    /** 用常驻桥弹一个 toast：供"后台任务完成"提示使用（发起任务的页面可能已经销毁，用本页桥会失效）。 */
    fun toast(message: String) {
        try {
            (rootBridge ?: Utils.currentBridgeModule()).toast(message)
        } catch (e: Throwable) {
            // 提示失败无所谓，不影响主流程
        }
    }
}
