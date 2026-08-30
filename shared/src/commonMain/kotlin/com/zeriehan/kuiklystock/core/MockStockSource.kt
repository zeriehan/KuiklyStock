package com.zeriehan.kuiklystock.core

/**
 * Mock 股票数据源（开发期使用）。
 * 提供大盘指数 + 个股样例，含迷你走势采样点。
 * P4 阶段由 TushareSource 实现同接口替换。
 */
object MockStockSource {

    fun getQuotes(): List<Stock> = listOf(
        // ===== 大盘指数 =====
        Stock("000001", "上证指数", 3210.45f, 18.32f, 0.57f, 3218.66f, 3195.10f, 0f,
            isIndex = true, trend = wave(3210f, 0.4f)),
        Stock("399001", "深证成指", 10156.23f, -42.18f, -0.41f, 10210.50f, 10120.30f, 0f,
            isIndex = true, trend = wave(10156f, -0.4f)),
        Stock("399006", "创业板指", 2034.88f, 9.65f, 0.48f, 2045.20f, 2021.40f, 0f,
            isIndex = true, trend = wave(2034f, 0.5f)),

        // ===== 个股 =====
        Stock("600519", "贵州茅台", 1685.00f, 23.50f, 1.41f, 1698.00f, 1662.00f, 3.21f,
            trend = wave(1685f, 0.6f)),
        Stock("000858", "五粮液", 142.30f, -1.85f, -1.28f, 144.60f, 141.20f, 8.74f,
            trend = wave(142f, -0.5f)),
        Stock("601318", "中国平安", 48.92f, 0.62f, 1.28f, 49.30f, 48.10f, 21.50f,
            trend = wave(48f, 0.5f)),
        Stock("300750", "宁德时代", 196.40f, 4.10f, 2.13f, 198.20f, 191.50f, 12.33f,
            trend = wave(196f, 0.7f)),
        Stock("000001", "平安银行", 11.85f, -0.15f, -1.25f, 12.10f, 11.70f, 35.60f,
            trend = wave(11.8f, -0.4f)),
        Stock("600036", "招商银行", 36.20f, 0.40f, 1.12f, 36.50f, 35.60f, 18.90f,
            trend = wave(36f, 0.4f)),
    )

    /** 生成一组平滑起伏的采样点，用于迷你走势图（仅演示） */
    private fun wave(base: Float, drift: Float): List<Float> {
        val n = 24
        val pts = mutableListOf<Float>()
        var v = base
        repeat(n) {
            v += (drift * base * 0.01f) + (if (it % 2 == 0) 1 else -1) * base * 0.006f
            pts.add(v)
        }
        return pts
    }

    /** 按代码查找个股/指数；找不到回退到第一支 */
    fun findByCode(code: String): Stock =
        getQuotes().firstOrNull { it.code == code } ?: getQuotes().first()

    /**
     * 生成一根股票的 K线（确定性，便于演示）。
     * 以当前价为基准随机游走，涨跌各半；成交量给相对值即可。
     * @param period 周期："日"/"周"/"月"/"年"，不同周期返回不同根数/波动，切换时可见变化。
     * @param count  根数；用于"加载更多历史"时增量增长（随机游走按索引确定，故首尾天然连续）。
     */
    fun getKLine(stock: Stock, period: String = "日", count: Int = 40): List<KLineBar> {
        // 不同周期：单根波动、起始价位不同，肉眼可见切换差异；根数由 count 决定
        val (vol, startFactor) = when (period) {
            "周" -> Pair(0.05f, 0.92f)
            "月" -> Pair(0.09f, 0.85f)
            "年" -> Pair(0.14f, 0.75f)
            else -> Pair(0.03f, 0.98f) // 日
        }
        val n = count
        val bars = mutableListOf<KLineBar>()
        var seed = (stock.code.filter { it.isDigit() }.sumOf { it.code } % 97 + 11) + period.length * 7
        fun rnd(): Float {
            seed = ((seed.toLong() * 1103515245L + 12345L) % 2147483648L).toInt()
            return seed / 2147483648f
        }
        var prevClose = stock.price * startFactor
        repeat(n) {
            val open = prevClose
            val drift = (rnd() - 0.5f) * stock.price * vol
            val close = (open + drift).coerceAtLeast(1f)
            val high = maxOf(open, close) + rnd() * stock.price * vol * 0.5f
            val low = minOf(open, close) - rnd() * stock.price * vol * 0.5f
            val volume = 1f + rnd() * 5f
            bars.add(KLineBar(open, high, low, close, volume, klineDateLabel(period, n - 1 - it)))
            prevClose = close
        }
        return bars
    }

    /**
     * 生成分时图采样点（A股交易日 09:30-15:00，午间休市，49 点 ≈ 每 5 分钟）。
     * 价格围绕"昨收"随机游走，同时给出逐步均价（黄线）；用于详情页分时模式。
     * 昨收 = 最新价 - 涨跌额，作为基准虚线。
     */
    fun getIntraday(stock: Stock): List<TimeSharingPoint> {
        val ref = (stock.price - stock.change).coerceAtLeast(0.01f)
        val n = 49
        val pts = mutableListOf<TimeSharingPoint>()
        var seed = (stock.code.filter { it.isDigit() }.sumOf { it.code } % 131 + 17)
        fun rnd(): Float {
            seed = ((seed.toLong() * 1103515245L + 12345L) % 2147483648L).toInt()
            return seed / 2147483648f
        }
        var price = ref
        var sum = 0f
        repeat(n) { i ->
            val drift = (rnd() - 0.47f) * stock.price * 0.010f
            price = (price + drift).coerceAtLeast(0.01f)
            sum += price
            val avg = sum / (i + 1)
            // 时间标签：09:30 起，每 5 分钟一点；午休 11:30-13:00（+90 分钟跳过）
            val totalMin = i * 5
            val m = if (totalMin >= 120) totalMin + 90 else totalMin
            val clock = 9 * 60 + 30 + m
            val hh = clock / 60
            val mm = clock % 60
            pts.add(TimeSharingPoint("${pad2(hh)}:${pad2(mm)}", price, avg))
        }
        return pts
    }
}
