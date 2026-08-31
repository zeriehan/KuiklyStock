package com.zeriehan.kuiklystock.core.llm

/**
 * LLM 客户端选择器（全局单例）。
 *
 * 默认链路：纯前端本地 Mock（`MockLLMClient`）——根据股票量价本地生成结构化的分析/回答文本，
 * **零网络、零 API Key、零后端**，保证前端任务开箱即用、演示链路始终可用。
 *
 * 若要接真实智谱 GLM-4-Flash（需要宿主侧实现 `llmAnalyze` 桥 + 配置 GLM_API_KEY + 设备联网），
 * 把下面一行改成：
 *     var client: LLMClient = GLMFlashClient(MockLLMClient())
 * 真实链路失败时 `GLMFlashClient` 会自动回退到 Mock，不会卡在「分析中」。
 */
object LLM {
    var client: LLMClient = MockLLMClient()
}
