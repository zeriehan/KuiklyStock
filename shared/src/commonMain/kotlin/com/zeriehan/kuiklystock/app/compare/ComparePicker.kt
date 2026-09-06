package com.zeriehan.kuiklystock.app.compare

/**
 * 对比页与选股页之间的回传通道（单例）。
 *
 * 实现机制：Kuikly 页面间没有可靠的"返回时回调"，项目约定是单例。
 * 流程：
 *   1. 对比页点击股票名/点 "+" → openPage("StockPicker")，initialCodes 通过 pageData 传（不依赖本单例）
 *   2. StockPickerPage 从 pageData.params.optString("initialCodes") 读初始 codes
 *   3. 用户编辑后点「完成」→ 把最终 codes 写回 [pendingCodes] + closePage
 *   4. 对比页手动「应用选股」读 [pendingCodes] 应用
 */
internal object ComparePicker {
    /** picker 关闭时回传的对比 codes（null 表示无待应用结果） */
    var pendingCodes: List<String>? = null
    /** 对比页发起 picker 时记录的意图：替换第几位（-1=追加，>=0=替换该位） */
    var pendingReplaceIndex: Int = -1

    fun clear() {
        pendingCodes = null
        pendingReplaceIndex = -1
    }
}