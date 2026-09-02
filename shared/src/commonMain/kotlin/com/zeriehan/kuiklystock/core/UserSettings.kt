package com.zeriehan.kuiklystock.core

import com.tencent.kuikly.core.module.SharedPreferencesModule

/**
 * 用户个性化设置（跨 app 重启保留，SharedPreferences 支撑）。
 *
 * - expand：行情/自选行内「迷你展开卡片」可显示的组件集合。
 *   "trend"=分时走势 / "ai"=AI 智能分析 / "brief"=简况。默认三项全开。
 * - themeColor：主题强调色（Int，0xAARRGGBB），用于顶栏、选中态、主按钮、分页圆点等。
 * - fontScale：字体缩放系数（1.0 / 1.15 / 1.3）。
 * - darkMode：深色模式开关（实验性，目前影响页面底色与顶栏）。
 *
 * 设计为「内存单一真相源 + load/save 桥接」：每次打开相关页面先 load，
 * 变更后 save；其它页面读取前也应 load 一次保证最新。
 */
internal object UserSettings {

    const val KEY_EXPAND = "kb_expand"
    const val KEY_THEME = "kb_theme"
    const val KEY_FONT = "kb_font"
    const val KEY_DARK = "kb_dark"
    const val KEY_COLOR_MODE = "kb_color_mode"

    // 展开组件键
    const val EXPAND_TREND = "trend"
    const val EXPAND_AI = "ai"
    const val EXPAND_BRIEF = "brief"

    // 可选主题色（外观页色板用）
    val THEME_PALETTE = listOf(
        0xFF23D3FD, // 青蓝（默认）
        0xFFAD37FE, // 紫
        0xFFFF5A5F, // 红
        0xFF1ABE5B, // 绿
        0xFFFF8C00, // 橙
        0xFF222222  // 墨黑
    )

    // ===== 内存当前值（单一真相源）=====
    var expand: MutableSet<String> = mutableSetOf(EXPAND_TREND, EXPAND_AI, EXPAND_BRIEF)
    var themeColor: Long = 0xFF23D3FD
    var fontScale: Float = 1.0f
    var darkMode: Boolean = false
    /** 涨跌配色：0=A股红涨绿跌（默认）；1=欧美红跌绿涨。影响所有涨跌红/绿标注（含 K线蜡烛） */
    var colorMode: Int = 0

    fun load(prefs: SharedPreferencesModule) {
        val ex = prefs.getItem(KEY_EXPAND)
        if (ex.isNotBlank()) {
            expand = ex.split(',').filter { it.isNotBlank() }.toMutableSet()
        }
        val th = prefs.getItem(KEY_THEME)
        if (th.isNotBlank()) th.toLongOrNull()?.let { themeColor = it }
        val fs = prefs.getItem(KEY_FONT)
        if (fs.isNotBlank()) fs.toFloatOrNull()?.let { fontScale = it }
        val dk = prefs.getItem(KEY_DARK)
        if (dk.isNotBlank()) darkMode = dk == "1"
        val cm = prefs.getItem(KEY_COLOR_MODE)
        if (cm.isNotBlank()) cm.toIntOrNull()?.let { colorMode = it.coerceIn(0, 1) }
    }

    fun saveExpand(prefs: SharedPreferencesModule) {
        prefs.setItem(KEY_EXPAND, expand.joinToString(","))
    }

    fun saveTheme(prefs: SharedPreferencesModule) {
        prefs.setItem(KEY_THEME, themeColor.toString())
    }

    fun saveFont(prefs: SharedPreferencesModule) {
        prefs.setItem(KEY_FONT, fontScale.toString())
    }

    fun saveDark(prefs: SharedPreferencesModule) {
        prefs.setItem(KEY_DARK, if (darkMode) "1" else "0")
    }

    fun saveColorMode(prefs: SharedPreferencesModule) {
        prefs.setItem(KEY_COLOR_MODE, colorMode.toString())
    }

    // ===== 涨跌配色（按 colorMode 返回 涨/跌 的红或绿，供所有行情/图表红绿标注统一读取）=====
    // 色值语义：红系=0xFFE54D42(主)/0xFF791F1F(深)/0xFFFCEBEB(浅底)；绿系=0xFF1ABE5B(主)/0xFF27500A(深)/0xFFEAF3DE(浅底)。
    private val RED_MAIN = 0xFFE54D42L
    private val RED_DEEP = 0xFF791F1FL
    private val RED_BG = 0xFFFCEBEBL
    private val GREEN_MAIN = 0xFF1ABE5BL
    private val GREEN_DEEP = 0xFF27500AL
    private val GREEN_BG = 0xFFEAF3DEL
    /** 涨 = 该模式下的"上涨"主体色（A股红 / 欧美绿） */
    fun upMain(): Long = if (colorMode == 0) RED_MAIN else GREEN_MAIN
    /** 跌 = 该模式下的"下跌"主体色 */
    fun downMain(): Long = if (colorMode == 0) GREEN_MAIN else RED_MAIN
    /** 涨的文字深色（用于浅底上对比鲜明的文字/数字） */
    fun upDeep(): Long = if (colorMode == 0) RED_DEEP else GREEN_DEEP
    /** 跌的文字深色 */
    fun downDeep(): Long = if (colorMode == 0) GREEN_DEEP else RED_DEEP
    /** 涨的浅色底（卡片底色） */
    fun upBg(): Long = if (colorMode == 0) RED_BG else GREEN_BG
    /** 跌的浅色底 */
    fun downBg(): Long = if (colorMode == 0) GREEN_BG else RED_BG
    /** 依据涨跌幅返回该模式下的涨跌主色；平=0xFF999999 灰 */
    fun trendMain(v: Float): Long = if (v > 0f) upMain() else if (v < 0f) downMain() else 0xFF999999L
    /** 依据涨跌幅返回该模式下的文字色（平用当前"中性黑"便于阅读数值） */
    fun trendDeep(v: Float): Long = if (v > 0f) upDeep() else if (v < 0f) downDeep() else 0xFF222222L

    /** 展开组件对应的页码顺序（趋势=0 / AI=1 / 简况=2），按集合动态生成 */
    fun expandPages(): List<Int> {
        val list = mutableListOf<Int>()
        if (expand.contains(EXPAND_TREND)) list.add(0)
        if (expand.contains(EXPAND_AI)) list.add(1)
        if (expand.contains(EXPAND_BRIEF)) list.add(2)
        return list
    }

    /** 字体缩放辅助：base * fontScale，至少 10f，避免缩成不可读 */
    fun fs(base: Float): Float = (base * fontScale).coerceAtLeast(10f)

    /** 强调色的浅色染色（用于选中背景/高亮底），ratio 越大越接近白色 */
    fun themeTint(ratio: Float = 0.86f): Long = blend(themeColor, -1L, ratio)

    /** 颜色线性混合（from/to 均为 0xAARRGGBB 的 Long；to=-1 即白色）。结果 alpha 强制不透明 */
    internal fun blend(from: Long, to: Long, ratio: Float): Long {
        val r1 = (from shr 16) and 0xFFL
        val g1 = (from shr 8) and 0xFFL
        val b1 = from and 0xFFL
        val r2 = (to shr 16) and 0xFFL
        val g2 = (to shr 8) and 0xFFL
        val b2 = to and 0xFFL
        val r = (r1 + (r2 - r1) * ratio).toLong().coerceIn(0L, 255L)
        val g = (g1 + (g2 - g1) * ratio).toLong().coerceIn(0L, 255L)
        val b = (b1 + (b2 - b1) * ratio).toLong().coerceIn(0L, 255L)
        return 0xFF000000L or (r shl 16) or (g shl 8) or b
    }

    /** 当前设置指纹（用于判断「返回主框架时是否需要重塑主题」） */
    fun signature(): String = "$themeColor|$fontScale|$darkMode"
}
