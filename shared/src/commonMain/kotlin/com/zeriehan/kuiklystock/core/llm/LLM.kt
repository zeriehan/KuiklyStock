package com.zeriehan.kuiklystock.core.llm

/**
 * LLM 客户端选择器（全局单例）。
 *
 * 默认链路：GLM4FlashClient(MockLLMClient()) —— 配置了 GLM_API_KEY 即走真实模型，
 * 否则回退 Mock，保证演示可用。后续可在此热切换 DeepSeek 等其它 provider。
 */
object LLM {
    var client: LLMClient = GLM4FlashClient(MockLLMClient())
}
