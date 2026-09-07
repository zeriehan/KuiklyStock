package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * 价格预警持久化层（App 内命中提示形态）。
 *
 * 给某只股票设一个价格/涨跌幅条件，行情刷新（DataSync）时检查命中，
 * 命中即 toast 提醒一次并把该条 [PriceAlert.fired] 置 true（防重复刷屏），
 * 直到用户改条件 / 手动复位后才再次触发。
 *
 * 存储格式：多条 `code~type~threshold~fired` 逗号分隔。
 * type：above=现价≥阈值 / below=现价≤阈值 / pctUp=当日涨幅≥阈值% / pctDown=当日跌幅≥阈值%。
 * 说明：现价型用当前价比对；pct 型用当日 changePercent 比对（取绝对值）。
 */
object AlertStore {

    const val KEY_ALERTS = "kb_price_alerts"

    /** 单条价格预警 */
    data class PriceAlert(
        val code: String,
        val type: String,     // "above" | "below" | "pctUp" | "pctDown"
        val threshold: Float,
        val fired: Boolean = false,
    )

    fun load(prefs: SharedPreferencesModule): List<PriceAlert> {
        val raw = prefs.getItem(KEY_ALERTS)
        if (raw.isBlank()) return emptyList()
        return raw.split(',').mapNotNull { seg ->
            if (seg.isBlank()) return@mapNotNull null
            val p = seg.split('~')
            if (p.size < 3) return@mapNotNull null
            val code = p[0].trim()
            val type = p[1].trim()
            val threshold = p[2].toFloatOrNull() ?: return@mapNotNull null
            val fired = p.getOrNull(3) == "1"
            if (code.isBlank() || type.isBlank()) null else PriceAlert(code, type, threshold, fired)
        }
    }

    fun save(prefs: SharedPreferencesModule, list: List<PriceAlert>) {
        prefs.setItem(KEY_ALERTS, list.joinToString(",") { a ->
            "${a.code}~${a.type}~${a.threshold}~${if (a.fired) "1" else "0"}"
        })
    }

    /** 某股票当前是否已有某类型+阈值的预警（避免重复设同一条） */
    fun exists(list: List<PriceAlert>, code: String, type: String, threshold: Float): Boolean =
        list.any { it.code == code && it.type == type && it.threshold == threshold }
}
