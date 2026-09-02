package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.base.Color
import kotlin.math.sqrt

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
/** 两位补零（internal，供 StockData 复用生成时分标签） */
internal fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

/**
 * 由"距今天数"推算 MM-DD 标签（演示用，按真实月长回退，基准日 2026-08-29）。
 * 说明：这是 mock 演示标签，并非真实交易日历，仅用于 X 轴可读性，不影响任何逻辑。
 */
fun dateLabel(daysAgo: Int): String {
    var y = 2026
    var m = 8
    var d = 29
    repeat(daysAgo) {
        d -= 1
        if (d <= 0) {
            m -= 1
            if (m <= 0) { m = 12; y -= 1 } // 跨年回到 12 月并年份递减
            d = daysInMonth(m)
        }
    }
    return "$y.${pad2(m)}.${pad2(d)}"
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
            "$y.01.01"   // 统一点分格式（该年首日）
        }
        "月" -> {
            var y = 2026
            var m = 8 - offsetFromNewest
            while (m <= 0) { m += 12; y -= 1 } // 回退跨年并年份递减
            "$y.${pad2(m)}"
        }
        "周" -> dateLabel(offsetFromNewest * 7) // 按周步进（7 天），已带年份点分
        else -> dateLabel(offsetFromNewest)      // 日：按天步进，已带年份点分
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
 * 分时图单个采样点
 * @param time  时间标签，如 "09:30" / "13:00"（A股交易日 09:30-15:00，午间休市）
 * @param price 当时价格（白/蓝线）
 * @param avg   均价（黄线，分时图经典参照）
 */
data class TimeSharingPoint(
    val time: String,
    val price: Float,
    val avg: Float,
)

/**
 * 计算移动平均序列（与输入等长，前 period-1 个为 null）。
 * 用于 K线 MA5/MA10/MA20 叠线。
 * @param closes 收盘价序列
 * @param period 均线周期
 */
fun computeMA(closes: List<Float>, period: Int): List<Float?> {
    val out = MutableList<Float?>(closes.size) { null }
    if (period <= 1) {
        closes.forEachIndexed { i, v -> out[i] = v }
        return out
    }
    var sum = 0f
    for (i in closes.indices) {
        sum += closes[i]
        if (i >= period) sum -= closes[i - period]
        if (i >= period - 1) out[i] = sum / period
    }
    return out
}

/** 指数移动平均（EMA）。前 period-1 个为 null。 */
fun computeEMA(values: List<Float>, period: Int): List<Float?> {
    val out = MutableList<Float?>(values.size) { null }
    if (values.isEmpty() || period <= 0) return out
    val k = 2f / (period + 1)
    var ema = values[0]
    out[0] = ema
    for (i in 1 until values.size) {
        ema = values[i] * k + ema * (1f - k)
        out[i] = ema
    }
    return out
}

/** MACD：DIF=EMA12-EMA26，DEA=EMA9(DIF)，柱=(DIF-DEA)*2 */
data class MacdResult(
    val dif: List<Float?>,
    val dea: List<Float?>,
    val hist: List<Float?>,
)

fun computeMACD(closes: List<Float>): MacdResult {
    val e12 = computeEMA(closes, 12)
    val e26 = computeEMA(closes, 26)
    val dif = List(closes.size) { i ->
        val a = e12[i]; val b = e26[i]
        if (a != null && b != null) a - b else null
    }
    val difFilled = dif.map { it ?: 0f }
    val dea = computeEMA(difFilled, 9)
    val hist = List(closes.size) { i ->
        val a = dif[i]; val b = dea[i]
        if (a != null && b != null) (a - b) * 2f else null
    }
    return MacdResult(dif, dea, hist)
}

/** RSI（Wilder 平滑，默认 14 周期）。前 period 个为 null。 */
fun computeRSI(closes: List<Float>, period: Int = 14): List<Float?> {
    val n = closes.size
    val out = MutableList<Float?>(n) { null }
    if (n < period + 1) return out
    var gain = 0f
    var loss = 0f
    for (i in 1..period) {
        val d = closes[i] - closes[i - 1]
        if (d >= 0f) gain += d else loss -= d
    }
    var avgGain = gain / period
    var avgLoss = loss / period
    out[period] = if (avgLoss == 0f) 100f else 100f - 100f / (1f + avgGain / avgLoss)
    for (i in period + 1 until n) {
        val d = closes[i] - closes[i - 1]
        val g = if (d >= 0f) d else 0f
        val l = if (d < 0f) -d else 0f
        avgGain = (avgGain * (period - 1) + g) / period
        avgLoss = (avgLoss * (period - 1) + l) / period
        out[i] = if (avgLoss == 0f) 100f else 100f - 100f / (1f + avgGain / avgLoss)
    }
    return out
}

/** BOLL：中轨=MA(period)，上/下轨=中轨±k*标准差 */
data class BollResult(
    val mid: List<Float?>,
    val upper: List<Float?>,
    val lower: List<Float?>,
)

fun computeBOLL(closes: List<Float>, period: Int = 20, k: Int = 2): BollResult {
    val n = closes.size
    val mid = computeMA(closes, period)
    val upper = MutableList<Float?>(n) { null }
    val lower = MutableList<Float?>(n) { null }
    for (i in period - 1 until n) {
        val window = closes.subList(i - period + 1, i + 1)
        val m = window.average().toFloat()
        var v = 0f
        for (x in window) v += (x - m) * (x - m)
        val sd = sqrt(v / window.size)
        upper[i] = m + k * sd
        lower[i] = m - k * sd
    }
    return BollResult(mid, upper, lower)
}

/**
 * 涨跌配色（跟随 [UserSettings.colorMode]：0=A股红涨绿跌 / 1=欧美红跌绿涨）。
 * UP/DOWN/FLAT 用自定义 getter，每次访问按当前模式返回，供行情文字、K线蜡烛、
 * 进度条等所有涨跌红绿标注统一读取。调用点无需感知模式切换。
 */
object StockColor {
    /** 涨：A股=红，欧美=绿 */
    val UP: Color get() = Color(UserSettings.upMain())
    /** 跌：A股=绿，欧美=红 */
    val DOWN: Color get() = Color(UserSettings.downMain())
    /** 平：灰 */
    val FLAT: Color get() = Color(0xFF999999)

    fun of(changePercent: Float): Color =
        if (changePercent > 0f) UP else if (changePercent < 0f) DOWN else FLAT

    /**
     * 股票名称/价格文字的统一涨跌色：涨用涨色、跌用跌色、平用中性黑(0xFF222222)。
     * 所有列表行的 名称+价格 都应走这一个函数，保证全 App 涨跌着色一致，避免漏改/漂移。
     */
    fun text(v: Float): Color =
        if (v > 0f) UP else if (v < 0f) DOWN else Color(0xFF222222)
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

/**
 * 板块（行业 / 概念）数据模型。
 * 字段对齐 Tushare 板块指数（申万行业 sw_index / 同花顺概念 ths_index）：
 * - [code]            板块代码（如 "sw_bank" / "BK0473"，接入真实数据时即 Tushare 的板块指数代码）
 * - [name]            板块名称（如 "银行" / "白酒"）
 * - [changePercent]   板块涨跌幅（%），真实数据下取自板块指数行情；mock 下由成分股均值推导，保证与成分一致
 * - [constituentCodes] 成分股代码列表（对应 [Stock.code]，接入真实数据时由 Tushare 成分股接口回填）
 * - [upCount]/[downCount] 板块内上涨/下跌家数（真实数据取自东财 f104/f105，mock/未拉取为 0 → UI 据此降级隐藏）
 * - [leaderName]/[leaderChangePercent] 领涨股名称及其涨跌幅 %（东财 f128/f140，mock 为空 → UI 隐藏）
 *
 * 设计目标：让行情页「板块」Tab 与未来真实数据源结构对齐，替换 StockData 即可无缝接入。
 */
data class Sector(
    val code: String,
    val name: String,
    val changePercent: Float,
    val constituentCodes: List<String>,
    val upCount: Int = 0,
    val downCount: Int = 0,
    val leaderName: String = "",
    val leaderChangePercent: Float = 0f,
) {
    val isUp: Boolean get() = changePercent >= 0f
}
