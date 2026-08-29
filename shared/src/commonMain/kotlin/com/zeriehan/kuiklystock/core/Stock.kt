package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.base.Color

/**
 * 股票基础数据模型
 */
data class Stock(
    val code: String,            // 股票代码，如 "600519"
    val name: String,            // 股票名称，如 "贵州茅台"
    val price: Float,            // 最新价
    val change: Float,           // 涨跌额
    val changePercent: Float,    // 涨跌幅（百分比，如 2.35 表示 +2.35%）
    val high: Float,             // 最高价
    val low: Float,              // 最低价
    val volume: Float,           // 成交量（万手）
    val isIndex: Boolean = false,// 是否指数（大盘）
    val trend: List<Float> = emptyList() // 迷你走势采样点
) {
    val isUp: Boolean get() = changePercent >= 0f
}

/**
 * K线单根柱状数据（日/周/月/年）
 */
data class KLineBar(
    val open: Float,    // 开盘价
    val high: Float,    // 最高价
    val low: Float,     // 最低价
    val close: Float,   // 收盘价
    val volume: Float,  // 成交量（相对值，仅用于高度占比）
    val date: String = "" // 日期标签（如 "08-29"），用于 X 轴
)

/**
 * 由"距今天数"推算 MM-DD 标签（演示用，仅处理 7/8 月边界）
 */
fun dateLabel(daysAgo: Int): String {
    var m = 8
    var d = 29
    repeat(daysAgo) {
        d -= 1
        if (d <= 0) { m -= 1; d = 31 } // 7月、8月均为31天
    }
    val pad2 = { v: Int -> if (v < 10) "0$v" else v.toString() }
    return "${pad2(m)}-${pad2(d)}"
}

/**
 * 涨红跌绿配色（中国股市惯例）
 */
object StockColor {
    val UP: Color = Color(0xFFE54D42)   // 涨：红
    val DOWN: Color = Color(0xFF1ABE5B) // 跌：绿
    val FLAT: Color = Color(0xFF999999) // 平：灰

    fun of(changePercent: Float): Color =
        if (changePercent > 0f) UP else if (changePercent < 0f) DOWN else FLAT
}

/**
 * 保留两位小数（KMP common 安全，避免使用 JVM-only 的 String.format）
 */
fun formatPrice(v: Float): String {
    val sign = if (v < 0f) "-" else ""
    val abs = kotlin.math.abs(v)
    val intPart = abs.toInt()
    val dec = ((abs - intPart) * 100).toInt().coerceIn(0, 99)
    return sign + intPart.toString() + "." + if (dec < 10) "0$dec" else dec.toString()
}

/**
 * 涨跌幅格式化，如 "+2.35%" / "-1.20%" / "0.00%"
 */
fun formatPercent(v: Float): String {
    val prefix = if (v > 0f) "+" else ""
    return prefix + formatPrice(v) + "%"
}
