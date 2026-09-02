package com.zeriehan.kuiklystock.app.mine

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.core.UserSettings

/**
 * 「行情展开组件」设置页（从「我的 → 个性化设置」进入）。
 *
 * 行情/自选列表里点开一只股票，会向上展开一个迷你卡片，原本固定包含三块：
 * 分时走势 / AI 智能分析 / 简况。本页让用户自由开关这三块——关掉的组件在展开卡片里不再显示。
 * 改动即时落盘，返回列表后下次展开即生效。
 *
 * 渲染约定（沿用项目铁律）：body 不随 observable 重跑，列表用 `vif(uiToggle)` 双分支翻转，
 * 在 viewDidLoad 载好状态后再翻转强制重建，保证初始开关状态正确。开关自身状态由
 * `trendOn/aiOn/briefOn` 这些 observable 驱动，点按时 attr 闭包读它们即自动重绘。
 */
@Page("ExpandSettings", supportInLocal = true)
internal class ExpandSettingsPage : BasePager() {

    internal var trendOn: Boolean by observable(true)
    internal var aiOn: Boolean by observable(true)
    internal var briefOn: Boolean by observable(true)
    /** vif 翻转触发器：viewDidLoad 载好初始状态后翻转，强制列表重建 */
    internal var uiToggle: Boolean by observable(false)

    private val prefs: SharedPreferencesModule
        get() = acquireModule(SharedPreferencesModule.MODULE_NAME)

    override fun viewDidLoad() {
        super.viewDidLoad()
        UserSettings.load(prefs)
        trendOn = UserSettings.expand.contains(UserSettings.EXPAND_TREND)
        aiOn = UserSettings.expand.contains(UserSettings.EXPAND_AI)
        briefOn = UserSettings.expand.contains(UserSettings.EXPAND_BRIEF)
        uiToggle = !uiToggle
    }

    internal fun toggle(key: String, on: Boolean) {
        if (on) UserSettings.expand.add(key) else UserSettings.expand.remove(key)
        UserSettings.saveExpand(prefs)
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(if (UserSettings.darkMode) Color(0xFF1A1B1E) else Color(0xFFF2F3F5))
            }

            // ===== 返回栏（顶栏用主题色）=====
            View {
                attr {
                    padding(12f)
                    paddingTop(pagerData.statusBarHeight)
                    height(44f + pagerData.statusBarHeight)
                    flexDirectionRow()
                    alignItemsCenter()
                    backgroundColor(Color(UserSettings.themeColor))
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color.WHITE); fontWeightSemiBold() } }
                }
                Text {
                    attr {
                        text("迷你卡片")
                        fontSize(17f); color(Color.WHITE); fontWeightSemiBold(); marginLeft(8f)
                    }
                }
            }

            Scroller {
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                val contentW = (ctx.pagerData.pageViewWidth - 24f).coerceAtLeast(200f)
                vif({ ctx.uiToggle }) { val c = this; c.renderExpandList(ctx, contentW) }
                vif({ !ctx.uiToggle }) { val c = this; c.renderExpandList(ctx, contentW) }
            }
        }
    }
}

/**
 * 渲染展开组件开关列表（说明卡 + 三个开关行）。
 * 文件级扩展函数：开关状态由 ctx.trendOn/aiOn/briefOn 这些 observable 驱动，
 * 在 attr 闭包里通过 getOn() 读取它们，变更即自动重绘（无需再套 vif）。
 */
private fun ViewContainer<*, *>.renderExpandList(ctx: ExpandSettingsPage, contentW: Float) {
    // 说明卡
    View {
        attr {
            flexDirectionColumn(); padding(14f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        Text {
            attr {
                text("在行情、自选列表里点开一只股票，会向上展开一个迷你卡片。下面三个组件可以自由开关，关掉后展开卡片里就不再显示它。")
                fontSize(UserSettings.fs(14f)); color(Color(0xFF222222))
            }
        }
    }

    renderCompRow(
        contentW, "分时走势", "当天的分时价格曲线，快速看涨跌节奏",
        { ctx.trendOn }, { v -> ctx.trendOn = v; ctx.toggle(UserSettings.EXPAND_TREND, v) }
    )
    renderCompRow(
        contentW, "AI 智能分析", "对该股票的智能解读，与详情页共用同一份缓存",
        { ctx.aiOn }, { v -> ctx.aiOn = v; ctx.toggle(UserSettings.EXPAND_AI, v) }
    )
    renderCompRow(
        contentW, "简况", "行业、总市值、市盈率、换手率等基础资料",
        { ctx.briefOn }, { v -> ctx.briefOn = v; ctx.toggle(UserSettings.EXPAND_BRIEF, v) }
    )

    View { attr { height(20f) } }
}

/** 单个组件开关行：标题 + 描述 + 右侧自定义开关（轨道色随 getOn() 变化，点击翻转） */
private fun ViewContainer<*, *>.renderCompRow(
    contentW: Float,
    title: String,
    subtitle: String,
    getOn: () -> Boolean,
    setOn: (Boolean) -> Unit
) {
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginTop(10f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        View {
            attr { flex(1f); flexDirectionColumn() }
            Text { attr { text(title); fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)) } }
            Text {
                attr {
                    text(subtitle)
                    fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginTop(4f)
                }
            }
        }
        // —— 自定义开关 ——
        View {
            attr {
                width(44f); height(24f); borderRadius(12f); flexDirectionRow()
                backgroundColor(if (getOn()) Color(UserSettings.themeColor) else Color(0xFFD0D3D8))
                marginTop(2f)
            }
            event { click { setOn(!getOn()) } }
            View {
                attr {
                    width(20f); height(20f); borderRadius(10f); backgroundColor(Color.WHITE)
                    marginTop(2f); marginLeft(if (getOn()) 22f else 2f)
                }
            }
        }
    }
}
