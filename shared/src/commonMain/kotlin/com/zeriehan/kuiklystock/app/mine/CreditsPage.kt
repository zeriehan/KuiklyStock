package com.zeriehan.kuiklystock.app.mine

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.core.UserSettings

/**
 * 「关于与致谢」页：罗列驱动本 App 的工具 / 模型 / 数据源 / 框架，表达感谢。
 *
 * 入口：我的 → 「关于与致谢」行（点击跳转）。纯静态展示，无交互状态。
 * 感谢对象：WorkBuddy、混元大模型、GLM、腾讯 / 新浪 / 东方财富行情、KuiklyUI 等。
 */
@Page("Credits", supportInLocal = true)
internal class CreditsPage : BasePager() {

    /** vif 翻转触发器：静态页无需重建，仅保留双分支约定以防后续扩展 */
    internal var uiToggle: Boolean by observable(false)

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }

            val contentW = (ctx.pagerData.pageViewWidth - 24f).coerceAtLeast(200f)

            // ===== 返回栏 =====
            View {
                attr {
                    padding(12f); paddingTop(pagerData.statusBarHeight)
                    height(44f + pagerData.statusBarHeight)
                    flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemisolid() } }
                }
                Text {
                    attr { text("关于与致谢"); fontSize(17f); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f) }
                }
            }

            // ===== 内容区 =====
            Scroller {
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                vif({ ctx.uiToggle }) { val c = this; c.renderCredits(ctx, contentW) }
                vif({ !ctx.uiToggle }) { val c = this; c.renderCredits(ctx, contentW) }
            }
        }
    }
}

/** 渲染致谢内容：一句开场 + 若干致谢卡片 */
private fun ViewContainer<*, *>.renderCredits(ctx: CreditsPage, contentW: Float) {
    // 开场语
    View {
        attr { padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW); marginBottom(10f) }
        Text {
            attr {
                text("KuiklyStock 能走到今天，离不开这些工具、模型、平台与数据源的无偿支持，由衷感谢：")
                fontSize(UserSettings.fs(13f)); color(Color(0xFF666666))
            }
        }
    }

    // 致谢项（图标 emoji + 名称 + 说明）
    listOf(
        Triple("🐦", "腾讯犀牛鸟", "提供参赛机会与大模型 token 支持"),
        Triple("🤖", "WorkBuddy", "一起把它从想法变成现实的开发伙伴"),
        Triple("🧠", "腾讯混元大模型", "提供底层大模型能力的服务方"),
        Triple("✨", "智谱 GLM", "提供免费大模型 AI 能力，驱动智能对话与操作理解"),
        Triple("📈", "东方财富", "提供实时行情数据"),
        Triple("💹", "腾讯行情", "提供 K 线 / 分时等行情数据"),
        Triple("📊", "新浪财经", "提供辅助行情数据源"),
        Triple("🖼", "KuiklyUI", "承载界面渲染的开源 UI 框架"),
    ).forEach { (icon, name, desc) ->
        View {
            attr {
                flexDirectionRow(); alignItemsCenter(); padding(14f)
                backgroundColor(Color.WHITE); borderRadius(10f); width(contentW); marginBottom(10f)
            }
            Text { attr { text(icon); fontSize(20f); width(34f) } }
            View {
                attr { flex(1f); flexDirectionColumn() }
                Text { attr { text(name); fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)) } }
                Text { attr { text(desc); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginTop(3f) } }
            }
        }
    }

    View { attr { height(20f) } }
}
