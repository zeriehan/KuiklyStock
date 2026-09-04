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

    /**
     * 连接超时：免费 Flash 池高峰常挂起/限流，设太久会导致降级链逐个干等(曾 30s+ 无响应)。
     * 收紧到 6s：多数正常请求远快于此；超时就尽早降级/兜底，保证演示几秒内有反馈。
     */
    const val CONNECT_TIMEOUT_MS = 6_000

    /**
     * 读取(首字响应)超时：非流式整段返回，正常几秒内；收紧到 20s 避免最坏降级拖 60s+。
     * Flash 系列可能带思考但已 thinking:disabled 关闭，故无需过长。
     */
    const val READ_TIMEOUT_MS = 20_000
}
