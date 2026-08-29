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
 * 由"距今天数"推算 MM-DD 标签（演示用，按真实月长回退，基准日 2026-08-29）。
 * 说明：这是 mock 演示标签，并非真实交易日历，仅用于 X 轴可读性，不影响任何逻辑。
 */
/** 两位补零 */
private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

/**
 * 由"距今天数"推算 MM-DD 标签（演示用，按真实月长回退，基准日 2026-08-29）。
 * 说明：这是 mock 演示标签，并非真实交易日历，仅用于 X 轴可读性，不影响任何逻辑。
 */
fun dateLabel(daysAgo: Int): String {
    var m = 8
    var d = 29
    repeat(daysAgo) {
        d -= 1
        if (d <= 0) {
            m -= 1
            if (m <= 0) { m = 12 } // 跨年回到 12 月（演示范围不会触发，留作健壮性）
            d = daysInMonth(m)
        }
    }
    return "${pad2(m)}-${pad2(d)}"
}

/**
 * K线 X 轴标签：按周期返回不同的可读格式（用户要求：日→日期、周→日期、月→月份、年→年份）。
 * @param period "日"/"周"/"月"/"年"
 * @param offsetFromNewest 距离最新一根的偏移（0=最新；日=天数，周=周数，月=月数，年=年数）
 */
fun klineDateLabel(period: String, offsetFromNewest: Int): String {
    return when (period) {
        "年" -> {
            val y = 2026 - offsetFromNewest
            "${y}年"
        }
        "月" -> {
            var m = 8 - offsetFromNewest
            while (m <= 0) { m += 12 } // 回退跨年（仅演示，不显示年份前缀以保持紧凑）
            "${pad2(m)}月"
        }
        "周" -> dateLabel(offsetFromNewest * 7) // 按周步进（7 天）
        else -> dateLabel(offsetFromNewest)      // 日：按天步进
    }
}

/** 指定月份天数（2 月按平年 28 天，演示足够） */
private fun daysInMonth(m: Int): Int = when (m) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> 28
    else -> 30
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
