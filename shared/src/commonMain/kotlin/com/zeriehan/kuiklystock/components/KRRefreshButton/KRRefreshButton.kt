package com.zeriehan.kuiklystock.components.KRRefreshButton

import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*

/**
 * 圆形「重试 / 刷新」按钮。
 *
 * - [loadingGetter] 返回 true（分析中）：按钮置灰、图标变浅，且点击被忽略；
 * - 否则：高亮青色圆环，点击触发 [onClick] 重新分析。
 * 图标用 ↻，圆环由 `borderRadius = 半宽` 实现。
 *
 * ⚠️ [loadingGetter] 必须在函数体内部被「即时调用」（在 attr/event 闭包里 `loadingGetter()`），
 * 而不能在外部先用 `val loading = loadingGetter()` 捕获一次。因为 Kuikly 只在**直接读取
 * observable 的闭包**里重渲染，外部捕获的旧值不会随状态变化刷新（与详情页/行情行 AI 卡同一坑）。
 */
internal fun ViewContainer<*, *>.KRRefreshButton(
    loadingGetter: () -> Boolean,
    onClick: () -> Unit
) {
    View {
        attr {
            width(28f); height(28f); borderRadius(14f)
            justifyContentCenter(); alignItemsCenter()
            backgroundColor(Color.WHITE)
            border(
                Border(
                    1f,
                    BorderStyle.SOLID,
                    if (loadingGetter()) Color(0xFFDDDDDD) else Color(0xFF23D3FD)
                )
            )
        }
        event { click { if (!loadingGetter()) onClick() } }
        Text {
            attr {
                text("↻")
                fontSize(16f)
                color(if (loadingGetter()) Color(0xFFBBBBBB) else Color(0xFF23D3FD))
            }
        }
    }
}
