package com.zeriehan.kuiklystock.core.llm

/**
 * 行情数据变更总线（与 [ChatSync] 同款模式）。
 *
 * 真实行情（东方财富）异步拉取完成后，[com.zeriehan.kuiklystock.core.StockData] 更新内存行情表并 [bump]，
 * 主框架监听后翻转 listToggle / convToggle，使行情、自选、板块、最近对话列表整体重建（拿到真实价）。
 *
 * 为什么不用 observable 直接传：跨页面（主框架常驻、ChatPage/详情为子页）共享状态必须靠单例 + 监听，
 * 这与 ChatSync 一致。
 */
object DataSync {
    private val listeners = mutableListOf<() -> Unit>()

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }

    fun bump() {
        for (l in listeners) {
            try { l() } catch (e: Throwable) { /* 单个监听异常不影响其它 */ }
        }
    }
}
