package com.zeriehan.kuiklystock.core

/**
 * 跨平台当前毫秒时间戳。
 *
 * ⚠️ 为何要 expect/actual：commonMain 不能直接用 JVM 的 System.currentTimeMillis()
 * （工程声明了 iOS 多目标，IDE 按 KMP 严格分析会报 Unresolved 'System'）。
 * actual 在 androidMain（用 System）与 iosMain（用 NSDate）各自提供。
 */
expect fun currentTimeMillis(): Long
