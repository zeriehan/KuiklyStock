package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.zeriehan.kuiklystock.base.BridgeModule
import com.zeriehan.kuiklystock.core.llm.DataSync

/**
 * 股票数据门面（全局单例）。
 *
 * - 默认装填一组 mock 行情，保证离线/无网络/首次启动时 App 也能正常渲染；
 * - 调 [refresh] 经宿主桥 `fetchQuotes` 拉取东方财富实时行情（免 token），成功后用真实价
 *   覆盖内存行情表（按 secid 匹配，兼容 mock 里 000001=上证 / 000002=平安银行 的代码碰撞）；
 * - 调 [loadRank] 拉取东方财富「真实个股榜单」并并入行情池，行情页「个股」子榜因此不再是
 *   mock 那 20 多只，而是市场里真实前 30（涨幅/跌幅/换手率/振幅）；
 * - 调 [loadSectors] / [loadSectorStocks] 拉取「真实行业板块列表」与「板块真实成分股」，同样并入池；
 * - 任何失败（无桥 / 网络异常 / 接口限流）都保留 mock，App 不崩、不留白。
 *
 * 设计：所有 UI 调用点统一走本门面（原 `StockData.*`），真实数据接入对上层透明。
 * K线/分时仍由 mock 算法基于"当前（真实）价"派生（用户明确：K 线接口不接）。
 */
object StockData {

    // ===================== 基础（mock 种子）行情池 =====================
    // ⚠️ 该列表在首次 refresh() / loadXxx 前即被 UI 读取，作为离线兜底；真实行情/榜单到达后会
    //    按 code 就地替换其中的 mock 价，并把榜单新股票追加进 [realPool]。
    private val baseQuotes = mutableListOf<Stock>(
        // ===== 大盘指数 =====
        Stock("000001", "上证指数", 3210.45f, 18.32f, 0.57f, 3218.66f, 3195.10f, 0f,
            isIndex = true, trend = wave(3210f, 0.4f)),
        Stock("399001", "深证成指", 10156.23f, -42.18f, -0.41f, 10210.50f, 10120.30f, 0f,
            isIndex = true, trend = wave(10156f, -0.4f)),
        Stock("399006", "创业板指", 2034.88f, 9.65f, 0.48f, 2045.20f, 2021.40f, 0f,
            isIndex = true, trend = wave(2034f, 0.5f)),

        // ===== 白酒 =====
        Stock("600519", "贵州茅台", 1685.00f, 23.50f, 1.41f, 1698.00f, 1662.00f, 3.21f, trend = wave(1685f, 0.6f)),
        Stock("000858", "五粮液", 142.30f, -1.85f, -1.28f, 144.60f, 141.20f, 8.74f, trend = wave(142f, -0.5f)),
        Stock("000568", "泸州老窖", 168.50f, 1.43f, 0.85f, 170.20f, 166.80f, 5.10f, trend = wave(168f, 0.5f)),
        Stock("600809", "山西汾酒", 245.60f, 4.95f, 2.05f, 248.10f, 242.30f, 4.02f, trend = wave(245f, 0.7f)),

        // ===== 银行 =====
        Stock("000002", "平安银行", 11.85f, -0.15f, -1.25f, 12.10f, 11.70f, 35.60f, trend = wave(11.8f, -0.4f)),
        Stock("600036", "招商银行", 36.20f, 0.40f, 1.12f, 36.50f, 35.60f, 18.90f, trend = wave(36f, 0.4f)),
        Stock("601398", "工商银行", 6.45f, 0.02f, 0.31f, 6.52f, 6.40f, 52.10f, trend = wave(6.45f, 0.3f)),
        Stock("601939", "建设银行", 7.82f, 0.04f, 0.52f, 7.88f, 7.76f, 41.30f, trend = wave(7.82f, 0.4f)),

        // ===== 保险 =====
        Stock("601318", "中国平安", 48.92f, 0.62f, 1.28f, 49.30f, 48.10f, 21.50f, trend = wave(48f, 0.5f)),
        Stock("601628", "中国人寿", 35.10f, -0.15f, -0.42f, 35.50f, 34.80f, 16.40f, trend = wave(35f, -0.4f)),

        // ===== 电池 / 新能源 =====
        Stock("300750", "宁德时代", 196.40f, 4.10f, 2.13f, 198.20f, 191.50f, 12.33f, trend = wave(196f, 0.7f)),
        Stock("002594", "比亚迪", 245.80f, 3.75f, 1.55f, 248.00f, 242.10f, 14.80f, trend = wave(245f, 0.6f)),
        Stock("601012", "隆基绿能", 18.65f, -0.40f, -2.10f, 19.10f, 18.40f, 22.60f, trend = wave(18.6f, -0.6f)),

        // ===== 证券 =====
        Stock("600030", "中信证券", 22.40f, 0.20f, 0.92f, 22.65f, 22.10f, 19.70f, trend = wave(22.4f, 0.5f)),
        Stock("300059", "东方财富", 14.85f, 0.46f, 3.20f, 15.10f, 14.50f, 28.40f, trend = wave(14.8f, 0.9f)),

        // ===== 医药 =====
        Stock("600276", "恒瑞医药", 45.30f, -0.30f, -0.65f, 45.80f, 44.90f, 13.20f, trend = wave(45.3f, -0.4f)),
        Stock("603259", "药明康德", 58.40f, 1.06f, 1.85f, 59.10f, 57.20f, 11.80f, trend = wave(58.4f, 0.6f)),

        // ===== 半导体 =====
        Stock("688981", "中芯国际", 52.10f, 2.05f, 4.10f, 53.00f, 50.60f, 17.90f, trend = wave(52f, 1.0f)),
        Stock("603501", "韦尔股份", 102.30f, -1.61f, -1.55f, 104.10f, 101.20f, 9.60f, trend = wave(102f, -0.6f)),

        // ===== 汽车 =====
        Stock("600104", "上汽集团", 15.20f, 0.07f, 0.45f, 15.40f, 15.00f, 12.10f, trend = wave(15.2f, 0.3f)),
        Stock("601633", "长城汽车", 25.60f, -0.21f, -0.80f, 25.95f, 25.30f, 10.40f, trend = wave(25.6f, -0.4f)),

        // ===== 地产 =====
        Stock("600048", "保利发展", 9.85f, -0.20f, -1.95f, 10.05f, 9.75f, 23.70f, trend = wave(9.85f, -0.7f)),
    )

    /**
     * 真实榜单/成分股并入的动态池（code → Stock）。
     * 与 [baseQuotes] 合并构成对外 [getQuotes]；loadRank / loadSectorStocks 到达的新股票追加于此，
     * 同名（code 相同）则替换 baseQuotes 里的 mock 价，保证用户加自选/点详情的真实标的一直能取到。
     */
    private val realPool = mutableMapOf<String, Stock>()

    /** 是否已装入真实行情（用于 UI 标注数据来源） */
    private var realLoaded = false
    fun isReal(): Boolean = realLoaded

    /** 对外暴露全部行情（mock 种子 + 真实动态并入），个股榜单/自选/指数都从这取 */
    fun getQuotes(): List<Stock> = baseQuotes + realPool.values

    fun findByCode(code: String): Stock =
        poolAll().firstOrNull { it.code == code } ?: baseQuotes.first()

    private fun poolAll(): List<Stock> = baseQuotes + realPool.values

    private var bridge: BridgeModule? = null
    /** 由常驻根页面（MainTabPager）注入桥，刷新/拉榜走宿主网络 */
    internal fun attach(b: BridgeModule) { bridge = b }

    // ===================== 板块（真实优先，mock 兜底）=====================

    private data class SectorDef(val code: String, val name: String, val constituents: List<String>)

    /** mock 板块定义（离线兜底）。涨跌幅由成分股（真实价）均值推导，保证与成分一致。 */
    private val sectorDefs = listOf(
        SectorDef("sw_liquor", "白酒", listOf("600519", "000858", "000568", "600809")),
        SectorDef("sw_bank", "银行", listOf("000002", "600036", "601398", "601939")),
        SectorDef("sw_insurance", "保险", listOf("601318", "601628")),
        SectorDef("sw_battery", "电池", listOf("300750", "002594", "601012")),
        SectorDef("sw_security", "证券", listOf("600030", "300059")),
        SectorDef("sw_medical", "医药", listOf("600276", "603259")),
        SectorDef("sw_semi", "半导体", listOf("688981", "603501")),
        SectorDef("sw_auto", "汽车", listOf("600104", "601633")),
        SectorDef("sw_realestate", "地产", listOf("600048")),
    )

    /** 真实行业板块：code(BKxxxx) → Sector；拉取成功后取代 mock 板块列表 */
    private val realSectors = mutableMapOf<String, Sector>()

    /** 真实板块成分股（code → 成分股代码），供 getSectorStocks 用 */
    private val sectorConstituents = mutableMapOf<String, MutableList<String>>()

    fun getSectors(): List<Sector> {
        if (realSectors.isNotEmpty()) {
            return realSectors.values.toList().sortedByDescending { it.changePercent }
        }
        // mock 兜底：板块涨跌幅由成分股均值推导
        return sectorDefs.map { def ->
            val stocks = def.constituents.mapNotNull { findByCode(it) }
            val avg = if (stocks.isNotEmpty())
                (stocks.map { it.changePercent }.average().toFloat() * 100f).toInt() / 100f
            else 0f
            Sector(def.code, def.name, avg, def.constituents)
        }.sortedByDescending { it.changePercent }
    }

    fun findSectorByCode(code: String): Sector? =
        getSectors().firstOrNull { it.code == code }

    fun getSectorStocks(sector: Sector): List<Stock> {
        // 真实板块：若 Sector 实例未带成分（如未先经过板块列表就直达详情），则回退到成分缓存
        val codes = if (sector.constituentCodes.isNotEmpty()) sector.constituentCodes
        else sectorConstituents[sector.code] ?: emptyList()
        return codes.mapNotNull { findByCode(it) }
    }

    /** 生成一组平滑起伏的采样点，用于迷你走势图（仅演示，真实数据下仅作占位） */
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

    // ===================== 迷你图表（本地算法派生）=====================

    /**
     * 生成一根股票的 K线（确定性，便于演示）。以「当前（真实）价」为最新一根收盘价，向历史按 offset 回退。
     *
     * 稳定性保证：每根K线只由它「距最新一根的偏移 offset」决定（offset=0 即最新一根 = 当前价），
     * **与总根数 count 无关**。因此「往左滑加载更多历史」使 count 变大时，只会向左补出更老且数值不变的新K线，
     * 已显示的历史K线（数值 + 日期）永远稳定 —— 修复旧版"count 一变整套随机游走从头重算，同一日期价格漂移"的 bug。
     *
     * @param period 周期："日"/"周"/"月"/"年"
     * @param count  根数（最新一根在最右，最老一根在最左）
     */
    fun getKLine(stock: Stock, period: String = "日", count: Int = 40): List<KLineBar> {
        if (count <= 0) return emptyList()
        // 相邻两根之间的单期收益率标准差（跨期放大，让更久历史的价格波动幅度不至于太小/太离谱）
        val stepVol = when (period) {
            "周" -> 0.035f
            "月" -> 0.075f
            "年" -> 0.16f
            else -> 0.018f   // 日
        }
        // 单根内部 开/高/低 相对其收盘价的振幅
        val volAmp = when (period) {
            "周" -> 0.02f
            "月" -> 0.03f
            "年" -> 0.05f
            else -> 0.015f   // 日
        }
        // 确定性种子：代码数字和 + 周期 → 同股同周期，任意 offset 的数据恒定
        val codeSeed = (stock.code.filter { it.isDigit() }.sumOf { it.code }.toLong() % 97L + 11L).toInt()
        val periodSeed = period.sumBy { it.code }

        /** 无状态哈希：offset → [0,1)。只依赖 (offset, codeSeed, periodSeed)，与 count 无关。 */
        fun hash(o: Int): Float {
            var x = o * 2654435761L
            x = x xor (codeSeed * 0x9E3779B1L)
            x = x xor (periodSeed * 1234567L)
            x = x xor (x shl 13)
            x = x xor (x ushr 7)
            x = x xor (x shl 17)
            return (x and 0xFFFFFFFFL).toFloat() / 4294967296f
        }

        // 先算每个 offset 的收盘价：offset 0 = 最新一根 = 当前价；
        // 更老一根的收盘 = 除以 (1 + 当步收益率)，而每步收益率只由该 offset 决定。
        val closeByOffset = FloatArray(count)
        var prod = 1f
        for (o in 0 until count) {
            closeByOffset[o] = stock.price / prod
            val r = (hash(o * 2 + 5) - 0.5f) * 2f * stepVol
            prod *= 1f + r
        }

        // 输出：最老(offset=count-1) → 最新(offset=0)，日期标签用 offset。
        return List(count) { i ->
            val o = count - 1 - i
            val close = closeByOffset[o]
            val open = close * (1f + (hash(o * 2 + 6) - 0.5f) * 2f * volAmp)
            val high = maxOf(open, close) * (1f + hash(o * 2 + 7) * volAmp)
            val low = minOf(open, close) * (1f - hash(o * 2 + 8) * volAmp)
            val volume = 1f + hash(o * 2 + 9) * 5f
            KLineBar(open, high, low, close, volume, klineDateLabel(period, o))
        }
    }

    /** 生成分时图采样点（围绕昨收随机游走，给出逐步均价） */
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
            val totalMin = i * 5
            val m = if (totalMin >= 120) totalMin + 90 else totalMin
            val clock = 9 * 60 + 30 + m
            val hh = clock / 60
            val mm = clock % 60
            pts.add(TimeSharingPoint("${pad2(hh)}:${pad2(mm)}", price, avg))
        }
        return pts
    }

    // ===================== 东方财富实时行情 / 榜单 / 板块 =====================

    /**
     * 计算某股票的东方财富 secid（market.code），用于按真实接口拉取。
     * 兼容 mock 的代码碰撞：000001=上证指数(1.000001)、000002 在 mock 里是平安银行(真实 secid 0.000001)。
     */
    fun secidOf(stock: Stock): String {
        if (stock.isIndex) {
            return when (stock.code) {
                "000001" -> "1.000001"   // 上证指数
                "399001" -> "0.399001"   // 深证成指
                "399006" -> "0.399006"   // 创业板指
                else -> "1.${stock.code}"
            }
        }
        if (stock.code == "000002") return "0.000001" // mock 里"平安银行"的真实 secid
        return when {
            stock.code.startsWith("6") -> "1.${stock.code}" // 沪市
            stock.code.startsWith("8") -> "1.${stock.code}" // 科创板/联通等
            else -> "0.${stock.code}"                        // 深市
        }
    }

    /** 刷新「当前池内全部标的」的实时价（base + 真实并入），成功则覆盖并标记 isReal；失败保留 mock */
    fun refresh() {
        val b = bridge ?: return
        val map = mutableMapOf<String, Stock>()
        poolAll().forEach { map[secidOf(it)] = it }
        if (map.isEmpty()) return
        val secids = map.keys.joinToString(",")
        try {
            b.fetchQuotes(secids) { resp -> applyQuotes(resp, map) }
        } catch (e: Throwable) {
            // 桥不可用 → 保持 mock
        }
    }

    /** 真实榜单缓存：rankType → 榜内有序股票（按东方财富排序，非重排） */
    private val rankCache = mutableMapOf<Int, List<Stock>>()

    /** 返回某子榜最近一次拉到的有序股票；尚未拉过返回 null（UI 退化为按当前池排序） */
    fun rankOf(rankType: Int): List<Stock>? = rankCache[rankType]

    /**
     * 拉取真实个股榜单（行情页「个股」子榜用）。
     * @param rankType 0=涨幅榜 1=跌幅榜 2=换手率榜 3=振幅榜
     * @param pz 拉取条数（默认 30）
     * 成功后把榜内股票并入 [realPool]（同 code 覆盖），缓存有序榜单并触发 [DataSync.bump] 让行情/自选重建。
     */
    fun loadRank(rankType: Int, pz: Int = 30) {
        val b = bridge ?: return
        val fid: String
        val desc: Boolean
        when (rankType) {
            1 -> { fid = "f3"; desc = false }   // 跌幅榜：f3 升序
            2 -> { fid = "f8"; desc = true }    // 换手率：f8 降序
            3 -> { fid = "f7"; desc = true }    // 振幅：f7 降序
            else -> { fid = "f3"; desc = true } // 涨幅榜：f3 降序
        }
        try {
            b.fetchClist("m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23", fid, desc, pz) { resp ->
                val ordered = mergeQuotesFromJson(resp, "quotes")
                if (ordered.isNotEmpty()) rankCache[rankType] = ordered
                DataSync.bump()
            }
        } catch (e: Throwable) {
            // 失败 → 维持 mock 榜
        }
    }

    /** 拉取真实行业板块列表，成功则替换 [realSectors]（mock 板块作为离线兜底保留） */
    fun loadSectors() {
        val b = bridge ?: return
        try {
            b.fetchSectors { resp ->
                applySectors(resp)
            }
        } catch (e: Throwable) {
            // 失败 → 维持 mock 板块
        }
    }

    /** 拉取某真实行业板块（BKxxxx）的实时成分股：并入 realPool，并登记该板块成分 */
    fun loadSectorStocks(sectorCode: String, onDone: (() -> Unit)? = null) {
        val b = bridge ?: return
        try {
            b.fetchSectorStocks(sectorCode) { resp ->
                val merged = mergeQuotesFromJson(resp, "quotes")
                if (merged.isNotEmpty()) {
                    sectorConstituents[sectorCode] = merged.map { it.code }.toMutableList()
                    val cons = merged.map { it.code }
                    // 同步真实板块对象的成分（仅当该板块已在 realSectors 中，即先经过板块列表）
                    realSectors[sectorCode]?.let { existing ->
                        realSectors[sectorCode] = existing.copy(constituentCodes = cons)
                    }
                }
                DataSync.bump()
                onDone?.invoke()
            }
        } catch (e: Throwable) {
            // 失败 → 维持现状
        }
    }

    private fun applySectors(resp: JSONObject?) {
        try {
            val raw = resp?.optString("sectors") ?: return
            if (raw.isBlank() || raw == "[]") return
            val arr = JSONArray(raw)
            val loaded = mutableListOf<Sector>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val code = it.optString("code")
                val name = it.optString("name")
                if (code.isBlank() || name.isBlank()) continue
                val chg = it.optDouble("changePercent").toFloat()
                val upCount = it.optInt("upCount", 0)
                val downCount = it.optInt("downCount", 0)
                val leaderName = it.optString("leaderName")
                val leaderChg = it.optDouble("leaderChangePercent").toFloat()
                // 已在池中的成分优先复用（提升打开板块详情时的首屏体验）
                val cons = sectorConstituents[code] ?: mutableListOf()
                loaded.add(Sector(code, name, chg, cons.toList(), upCount, downCount, leaderName, leaderChg))
            }
            if (loaded.isNotEmpty()) {
                realSectors.clear()
                loaded.forEach { realSectors[it.code] = it }
                realLoaded = true
            }
        } catch (e: Throwable) {
            // 解析失败 → 维持 mock
        } finally {
            DataSync.bump()
        }
    }

    private fun applyQuotes(resp: JSONObject?, map: Map<String, Stock>) {
        try {
            val raw = resp?.optString("quotes") ?: return
            if (raw.isBlank()) return
            val arr = JSONArray(raw)
            val updated = mutableListOf<Stock>()
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val secid = it.optString("secid")
                val local = map[secid] ?: continue
                val price = it.optDouble("price").toFloat()
                if (price <= 0f) continue // 无数据（如停牌）保留 mock
                updated.add(local.copy(
                    price = price,
                    change = it.optDouble("change").toFloat(),
                    changePercent = it.optDouble("changePercent").toFloat(),
                    high = it.optDouble("high").toFloat(),
                    low = it.optDouble("low").toFloat(),
                    volume = it.optDouble("volume").toFloat(),
                ))
            }
            if (updated.isNotEmpty()) {
                for (u in updated) {
                    val idx = baseQuotes.indexOfFirst { it.code == u.code }
                    if (idx >= 0) baseQuotes[idx] = u
                    else if (realPool.containsKey(u.code)) realPool[u.code] = u
                    else { /* 池外标的忽略（榜单/成分股另有并入通道） */ }
                }
                realLoaded = true
            }
        } catch (e: Throwable) {
            // 解析异常 → 保持 mock
        } finally {
            DataSync.bump()
        }
    }

    /**
     * 把榜单/成分股接口返回的归一化 JSON 并入行情池。
     * @return 本次成功并入/更新的 [Stock] 列表
     */
    private fun mergeQuotesFromJson(resp: JSONObject?, key: String): List<Stock> {
        val merged = mutableListOf<Stock>()
        try {
            val raw = resp?.optString(key) ?: return merged
            if (raw.isBlank() || raw == "[]") return merged
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                val code = it.optString("code")
                if (code.isBlank()) continue
                val price = it.optDouble("price").toFloat()
                if (price <= 0f) continue
                val name = it.optString("name").ifBlank { code }
                val stock = Stock(
                    code = code,
                    name = name,
                    price = price,
                    change = it.optDouble("change").toFloat(),
                    changePercent = it.optDouble("changePercent").toFloat(),
                    high = it.optDouble("high").toFloat(),
                    low = it.optDouble("low").toFloat(),
                    volume = it.optDouble("volume").toFloat(),
                )
                // 已存在同 code：覆盖（可能是 mock 同名价）
                val idx = baseQuotes.indexOfFirst { it.code == code }
                if (idx >= 0) baseQuotes[idx] = stock
                else realPool[code] = stock
                merged.add(stock)
            }
            if (merged.isNotEmpty()) realLoaded = true
        } catch (e: Throwable) {
            // 解析失败
        }
        return merged
    }
}
