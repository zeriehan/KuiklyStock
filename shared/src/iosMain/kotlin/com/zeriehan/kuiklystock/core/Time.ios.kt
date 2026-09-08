package com.zeriehan.kuiklystock.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/** iOS 平台取毫秒时间戳（NSDate 距 1970 秒 × 1000）。注：Windows 不编 iOS，此 actual 保证 KMP 声明完整。 */
@OptIn(ExperimentalForeignApi::class)
actual fun currentTimeMillis(): Long =
    (NSDate().timeIntervalSince1970 * 1000.0).toLong()
