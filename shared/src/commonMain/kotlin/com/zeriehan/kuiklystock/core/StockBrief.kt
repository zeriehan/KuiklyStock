package com.zeriehan.kuiklystock.core

/**
 * 个股「派生资料」的统一来源（行业 / 总市值 / 市盈率 / 换手率 / 简介）。
 *
 * ⚠️ 背景：本项目没有基本面数据源（无市值、股本、营收、ROE 等真实接口），
 * `Stock` 也只有行情字段。因此这些资料只能由「股票代码 / 名称」派生，属模拟数据。
 *
 * ⚠️ 关键点：**全 App 必须用同一套派生口径**。
 * 此前 `KRTable`（列表行展开）与 `StockDetailPage`（详情页简况卡）各写了一套算法：
 *   - 总市值：列表用 `codeSum % 9000 + 500`，详情页却写死 `price * 12.56`
 *   - 行业：列表按名称关键字判断，详情页却对所有股票写死「白酒 / 饮料制造」
 * 结果同一只股票在两处显示两个不同的市值、行业一个是「银行」另一个是「白酒」——
 * 演示时一眼可辨的自相矛盾。故抽到此处统一调用。
 *
 * 派生值均为**按代码确定性**生成：同一只股票在任何页面、任何时刻结果一致（不会每次进入都变）。
 */
internal object StockBrief {

    private fun codeSum(code: String): Int = code.filter { it.isDigit() }.sumOf { it.code }

    /** 按名称关键字推断行业；命中不了给一个中性兜底，避免对所有股票返回同一行业 */
    fun industry(name: String): String = when {
        name.contains("茅台") || name.contains("五粮液") || name.contains("泸州") -> "白酒"
        name.contains("银行") -> "银行"
        name.contains("平安") || name.contains("人寿") || name.contains("太保") -> "保险"
        name.contains("宁德") || name.contains("比亚迪") -> "电池 / 新能源"
        name.contains("证券") -> "证券"
        name.contains("医药") || name.contains("生物") -> "医药生物"
        name.contains("半导体") || name.contains("芯片") || name.contains("中芯") -> "半导体"
        name.contains("地产") || name.contains("万科") -> "房地产"
        name.contains("指数") -> "大盘指数"
        else -> "制造业"
    }

    /** 总市值（亿元） */
    fun marketCap(code: String): String = (codeSum(code) % 9000 + 500).toString()

    /** 市盈率 TTM（倍） */
    fun pe(code: String): String = (codeSum(code) % 40 + 8).toString()

    /** 换手率（%） */
    fun turnover(code: String): String = formatPrice((codeSum(code) % 30 + 5) / 10f) + "%"

    /** 一句话简介 */
    fun intro(name: String): String = when {
        name.contains("茅台") || name.contains("五粮液") -> "白酒行业龙头，品牌护城河深厚。"
        name.contains("银行") -> "零售银行标杆，资产质量稳健。"
        name.contains("平安") -> "综合金融集团，寿险财险双轮驱动。"
        name.contains("宁德") -> "动力电池全球龙头，市占率领先。"
        name.contains("指数") -> "A股核心宽基指数，代表市场整体表现。"
        else -> "细分领域优质企业，业绩稳健增长。"
    }
}
