package com.zeriehan.kuiklystock.core.llm

import com.zeriehan.kuiklystock.core.KLineBar
import com.zeriehan.kuiklystock.core.Stock

/**
 * AI 分析客户端抽象。
 *
 * - 以回调方式返回分析文本（本工程暂无 Markdown 渲染组件，统一用 Text 直出，故约定为
 *   纯文本 + 简单分段标记，不使用 `#`/`**` 等 Markdown 语法）。
 * - 默认实现为股票感知的 [MockLLMClient]；真实接入 GLM-4-Flash 见 [GLM4FlashClient]。
 * - 回调式接口与工程内 BridgeModule 的 CallbackFn 风格一致，便于后续真实网络通道复用。
 */
interface LLMClient {
    fun analyze(stock: Stock, kline: List<KLineBar>, callback: (String) -> Unit)
}
