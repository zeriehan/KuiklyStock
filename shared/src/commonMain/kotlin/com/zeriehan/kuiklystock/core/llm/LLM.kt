package com.zeriehan.kuiklystock.core.llm

/**
 * LLM 客户端选择器（全局单例）。
 *
 * 默认链路：真实智谱 GLM-4-Flash（`GLMFlashClient(MockLLMClient())`）——
 * 经宿主 `KRBridgeModule.llmAnalyze` 桥发起 HTTPS 请求（子线程），结果已在宿主侧
 * `Handler(Looper.getMainLooper()).post` 切回主线程回调。网络失败 / 限流 / 未配 Key 时，
 * `GLMFlashClient` 自动回退到 `MockLLMClient`，不会卡在「分析中」。
 *
 * 即：AI 卡片与聊天页共用此 client，全部走真实模型；只有真实链路不可用时才用本地 Mock 兜底。
 */
object LLM {
    var client: LLMClient = GLMFlashClient(MockLLMClient())
}
