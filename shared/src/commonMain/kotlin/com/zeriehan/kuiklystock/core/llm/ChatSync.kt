package com.zeriehan.kuiklystock.core.llm

/**
 * 跨页会话变更信号。
 *
 * ChatPage 与 MainTabPager 是两个独立 Pager，无法共享 observable。
 * 当 ChatPage 写入/追加会话消息时调用 [bump]，注册在 [MainTabPager] 的监听会
 * 触发其列表重渲染，从而保证「最近对话」在返回主框架后即时刷新（无需手动切 Tab）。
 *
 * 监听在 MainTabPager.viewDidLoad 注册（主框架常驻，进程内仅一个实例）。
 */
object ChatSync {
    private val listeners = mutableListOf<() -> Unit>()

    /** 通知所有监听：会话状态发生变化 */
    fun bump() {
        listeners.forEach { it() }
    }

    fun addListener(l: () -> Unit) {
        if (!listeners.contains(l)) listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
