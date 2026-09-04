package com.zeriehan.kuiklystock.core

/**
 * 从 AI 回复文本中识别「提到了哪些可展示的股票」。
 *
 * 供聊天页在 AI 气泡末尾追加股票卡片组使用：Task02 要求除 Markdown 文本外，
 * AI 回答还能展示股票/行情的结构化卡片。识别出 AI 提到的股票后，即可在其
 * 消息下方渲染迷你走势卡片；点卡片可跳转个股详情页承接。
 *
 * 匹配策略（纯文本现扫，不改 ChatStore 存储）：
 * - 词库 = [StockData.getQuotes] 中排除指数的全部股票（能画迷你走势的必然有行情数据）；
 * - 命中方式 = AI 文本中包含该股「代码」（如 600519）或「名称」（如 贵州茅台）；
 * - 按文本中首次出现位置排序（与 AI 叙述顺序一致），并去重；
 * - 名称做「最长优先」校验，避免极短名造成误伤（如候选里无单字/两字歧义名时不生效）。
 */
object StockMention {

    /** 按文本出现顺序返回命中的股票（无命中返回空表）。词库随行情实时变化。 */
    fun extract(text: String): List<Stock> {
        if (text.isBlank()) return emptyList()
        val candidates = StockData.getQuotes().filter { !it.isIndex }
        if (candidates.isEmpty()) return emptyList()

        // 名称含非字母数字的（如纯数字 code）不作为名称候选；code 单独匹配。
        data class Hit(val stock: Stock, val pos: Int, val len: Int)
        val hits = ArrayList<Hit>()

        for (c in candidates) {
            var idx = -1
            // 1) 代码命中：如 "600519"（代码为纯数字，短但唯一，安全）
            if (c.code.isNotBlank()) {
                idx = text.indexOf(c.code)
            }
            // 2) 名称命中：需名称长度 >= 3（>=3 个中文字才够具辨识度，规避单/双字歧义）
            if (idx < 0 && c.name.length >= 3) {
                idx = text.indexOf(c.name)
            }
            if (idx >= 0) {
                hits.add(Hit(c, idx, c.name.length.coerceAtLeast(c.code.length)))
            }
        }

        // 同一只股票可能有 name 与 code 两处命中，此处按 code 去重（取首次出现位置）。
        // 文本中可能出现「名称」子串被更长名称包含的歧义（如候选同时有"中国平安"与"平安银行"），
        // 两者名称互不包含，故不会误命中；仅防御同 code 的 name/code 双命中。
        val best = HashMap<String, Hit>()
        for (h in hits) {
            val old = best[h.stock.code]
            if (old == null || h.pos < old.pos) best[h.stock.code] = h
        }
        return best.values.sortedBy { it.pos }.map { it.stock }
    }
}
