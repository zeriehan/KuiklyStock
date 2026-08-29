package com.zeriehan.kuiklystock.module

import com.zeriehan.kuiklystock.BuildConfig

/**
 * 智谱开放平台（BigModel）接入配置。
 *
 * ## API Key 从哪来
 * Key 不硬编码在源码里，而是构建时从 `local.properties` 注入（该文件已 .gitignore，不入库）：
 * ```properties
 * # local.properties
 * GLM_API_KEY=你的key
 * ```
 * 也支持环境变量 `GLM_API_KEY`（便于 CI）。两者都没有时为空串，
 * App 会自动回退本地 Mock 分析，功能演示不受影响。
 *
 * ## 为什么要模型候选链
 * `glm-4.7-flash` 是当前免费的首选模型，但免费池经常返回
 * `1305 该模型当前访问量过大`。此时按 [MODEL_CANDIDATES] 顺序自动降级到下一个免费模型，
 * 保证 AI 分析不会因为限流而空白。
 */
object GLMConfig {

    /** OpenAI 兼容的 Chat Completions 端点 */
    const val ENDPOINT = "https://open.bigmodel.cn/api/paas/v4/chat/completions"

    /** 构建时注入的 API Key；为空表示未配置（上层回退 Mock） */
    val API_KEY: String
        get() = BuildConfig.GLM_API_KEY

    /**
     * 模型候选链，按顺序尝试，命中一个即返回。
     * 均为智谱当前的免费 Flash 系列；4.7 限流时自动降级。
     */
    val MODEL_CANDIDATES: List<String> = listOf(
        "glm-4.7-flash",
        "glm-4.5-flash",
        "glm-4-flash",
    )

    /** 采样温度：分析类任务取中等，兼顾稳定与多样 */
    const val TEMPERATURE = 0.7

    const val CONNECT_TIMEOUT_MS = 15_000

    /** Flash 系列可能带思考过程，读超时给足 */
    const val READ_TIMEOUT_MS = 60_000
}
