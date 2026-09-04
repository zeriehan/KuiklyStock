package com.zeriehan.kuiklystock.core

/**
 * 聊天页「快捷建议区」的门控：决定"本次打开 App 后第一次进入的对话"要不要给快捷引导。
 *
 * 背景：建议区原本按"对话里有没有用户消息"永久显示/隐藏——但绑个股的会话是常驻的
 * (一般不删除不清空)，一旦用户首次提问后建议区就再也不会出现，等于功能只生效一次。
 *
 * 改为"每次打开 App 提示一次"：进程内 [armed]=true 时，第一个进入的对话 [claim]() 到
 * true 并置 [armed]=false，此后本次启动不再提示其它对话；MainTab(主入口) 每次重建
 * (重新打开 App) 时 [rearm]() 重新武装，下次进入对话即可再提示。
 */
object QuickTipsGate {
    /** 是否"本次启动还未给过建议"。默认 true = 进程启动后第一个对话可提示 */
    var armed: Boolean = true

    /** 尝试领取一次提示资格：若仍 armed 则返回 true 并置 false；否则 false。 */
    fun claim(): Boolean {
        if (armed) {
            armed = false
            return true
        }
        return false
    }

    /** 重新武装（MainTab 主入口重建 = 重新打开 App 时调用）。 */
    fun rearm() {
        armed = true
    }
}
