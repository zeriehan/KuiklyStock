package com.zeriehan.kuiklystock.app.compare

/**
 * 对比页与选股页之间的回传通道（单例）。
 *
 * 实现机制：Kuikly 页面间没有可靠的"返回时回调"，项目约定是单例。
 * 流程：
 *   1. 对比页点击股票名/点 "+" → 设置 [pendingReplaceIndex]，调用 openPage("StockPicker")
 *   2. StockPickerPage 从 pageData 读初始 codes，渲染可编辑列表
 *   3. 用户编辑后点「完成」→ 把最终 codes 写回 [pendingCodes] + closePage
 *   4. 对比页 viewWillReturn 或手动「刷新」按钮读 [pendingCodes] 应用
 *
 * 注：pendingReplaceIndex 用于 picker 决定"替换某位(-1=末尾追加)还是新增"，
 * 但 picker 当前版本允许用户自由增删，不再严格按 replaceIndex。
 */
internal object ComparePicker {
    /** picker 关闭时回传的对比 codes（null 表示无待应用结果） */
    var pendingCodes: List<String>? = null
    /** 对比页发起 picker 时记录的意图：替换第几位（-1=追加，>=0=替换该位） */
    var pendingReplaceIndex: Int = -1
    /** 对比页发起 picker 时的初始 codes（picker 据此渲染当前对比） */
    var initialCodes: List<String> = emptyList()

    fun clear() {
        pendingCodes = null
        pendingReplaceIndex = -1
        initialCodes = emptyList()
    }
}