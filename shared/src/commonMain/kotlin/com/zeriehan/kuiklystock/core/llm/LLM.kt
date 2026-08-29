package com.zeriehan.kuiklystock.core.llm

/**
 * LLM 客户端选择器（全局单例）。
 *
 * 默认链路：`GLMFlashClient(MockLLMClient())` —— 配置了 GLM_API_KEY 即走真实模型（智谱 Flash 免费系列），
 * 否则回退 Mock，保证离线/无 Key 时演示依然可用。后续可在此热切换 DeepSeek 等其它 provider。
 */
object LLM {
    var client: LLMClient = GLMFlashClient(MockLLMClient())
}
