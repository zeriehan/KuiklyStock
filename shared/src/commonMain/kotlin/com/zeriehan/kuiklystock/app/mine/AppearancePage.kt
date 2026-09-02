package com.zeriehan.kuiklystock.app.mine

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
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
 * 「外观与个性化」设置页（从「我的 → 个性化设置」进入）。
 *
 * 提供三类个性化：
 * - 主题色：一组预设强调色，应用于顶栏、选中态、主按钮、分页圆点等（即时生效，返回主框架即重塑）。
 * - 字体大小：标准 / 大 / 特大，作用于本页、展开组件页与股票展开卡片文字。
 * - 深色模式：实验性开关，切换页面底色与顶栏（卡片仍白底以保证可读）。
 *
 * 渲染约定（沿用项目铁律）：body 不随 observable 重跑，列表用 `vif(uiToggle)` 双分支翻转，
 * viewDidLoad 载好初始状态后翻转强制重建；色板选中态 / 字体选中态 / 开关由对应 observable
 * 驱动，在 attr 闭包内读取即自动重绘。
 */
@Page("Appearance", supportInLocal = true)
internal class AppearancePage : BasePager() {

    internal var selTheme: Long by observable(0xFF23D3FD)
    /** 0=标准 1=大 2=特大 */
    internal var selFont: Int by observable(0)
    internal var darkOn: Boolean by observable(false)
    /** 涨跌配色：0=A股红涨绿跌 1=欧美红跌绿涨 */
    internal var selColorMode: Int by observable(0)
    internal var uiToggle: Boolean by observable(false)

    private val prefs: SharedPreferencesModule
        get() = acquireModule(SharedPreferencesModule.MODULE_NAME)

    override fun viewDidLoad() {
        super.viewDidLoad()
        UserSettings.load(prefs)
        selTheme = UserSettings.themeColor
        selFont = when (UserSettings.fontScale) {
            0.85f -> 0
            1.0f -> 1
            1.15f -> 2
            1.3f -> 3
            else -> 1
        }
        darkOn = UserSettings.darkMode
        selColorMode = UserSettings.colorMode
        uiToggle = !uiToggle
    }

    internal fun pickColor(c: Long) {
        UserSettings.themeColor = c
        UserSettings.saveTheme(prefs)
        selTheme = c
    }

    internal fun pickFont(i: Int, scale: Float) {
        UserSettings.fontScale = scale
        UserSettings.saveFont(prefs)
        selFont = i
        // 翻转让预览区按新字号重建（预览文字本身未直接读 selFont observable）
        uiToggle = !uiToggle
    }

    internal fun toggleDark() {
        UserSettings.darkMode = !UserSettings.darkMode
        UserSettings.saveDark(prefs)
        darkOn = !darkOn
    }

    internal fun pickColorMode(mode: Int) {
        UserSettings.colorMode = mode
        UserSettings.saveColorMode(prefs)
        selColorMode = mode
        uiToggle = !uiToggle
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
                    backgroundColor(Color(ctx.selTheme))
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color.WHITE); fontWeightSemiBold() } }
                }
                Text {
                    attr {
                        text("外观与个性化")
                        fontSize(17f); color(Color.WHITE); fontWeightSemiBold(); marginLeft(8f)
                    }
                }
            }

            Scroller {
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                val contentW = (ctx.pagerData.pageViewWidth - 24f).coerceAtLeast(200f)
                vif({ ctx.uiToggle }) { val c = this; c.renderAppearanceList(ctx, contentW) }
                vif({ !ctx.uiToggle }) { val c = this; c.renderAppearanceList(ctx, contentW) }
            }
        }
    }
}

/** 渲染外观设置列表（主题色 / 字体 / 深色模式 / 实时预览） */
private fun ViewContainer<*, *>.renderAppearanceList(ctx: AppearancePage, contentW: Float) {
    val headerColor = if (UserSettings.darkMode) Color(0xFF9AA0A6) else Color(0xFF999999)

    // —— 主题色 ——
    Text { attr { text("主题色"); fontSize(UserSettings.fs(13f)); color(headerColor); marginBottom(8f) } }
    View {
        attr {
            flexDirectionRow(); padding(14f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        UserSettings.THEME_PALETTE.forEach { c ->
            View {
                attr {
                    width(38f); height(38f); borderRadius(19f); marginRight(10f); marginBottom(8f)
                    backgroundColor(Color(c)); alignItemsCenter(); justifyContentCenter()
                    border(if (ctx.selTheme == c) Border(2f, BorderStyle.SOLID, Color(0xFF222222)) else Border(0f, BorderStyle.SOLID, Color(0L)))
                }
                event { click { ctx.pickColor(c) } }
                if (ctx.selTheme == c) {
                    Text { attr { text("✓"); fontSize(18f); color(Color.WHITE); fontWeightSemiBold() } }
                }
            }
        }
    }

    // —— 字体大小 ——
    Text { attr { text("字体大小"); fontSize(UserSettings.fs(13f)); color(headerColor); marginTop(18f); marginBottom(8f) } }
    View {
        attr {
            flexDirectionRow(); padding(14f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        listOf(
            Triple(0, "小", 0.85f),
            Triple(1, "标准", 1.0f),
            Triple(2, "大", 1.15f),
            Triple(3, "特大", 1.3f)
        ).forEach { (i, label, scale) ->
            View {
                attr {
                    flex(1f); height(40f); marginRight(if (i < 3) 10f else 0f)
                    borderRadius(8f); alignItemsCenter(); justifyContentCenter()
                    backgroundColor(if (ctx.selFont == i) Color(ctx.selTheme) else Color(0xFFF2F3F5))
                }
                event { click { ctx.pickFont(i, scale) } }
                Text {
                    attr {
                        text(label)
                        fontSize(UserSettings.fs(15f))
                        color(if (ctx.selFont == i) Color.WHITE else Color(0xFF333333))
                    }
                }
            }
        }
    }

    // —— 深色模式 ——
    View {
        attr {
            flexDirectionRow(); alignItemsCenter(); marginTop(10f)
            padding(14f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        View {
            attr { flex(1f); flexDirectionColumn() }
            Text { attr { text("深色模式"); fontSize(UserSettings.fs(15f)); color(Color(0xFF222222)) } }
            Text {
                attr {
                    text("开启后整体背景变暗（实验性）")
                    fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)); marginTop(4f)
                }
            }
        }
        // 自定义开关
        View {
            attr {
                width(44f); height(24f); borderRadius(12f); flexDirectionRow()
                backgroundColor(if (ctx.darkOn) Color(ctx.selTheme) else Color(0xFFD0D3D8))
                marginTop(2f)
            }
            event { click { ctx.toggleDark() } }
            View {
                attr {
                    width(20f); height(20f); borderRadius(10f); backgroundColor(Color.WHITE)
                    marginTop(2f); marginLeft(if (ctx.darkOn) 22f else 2f)
                }
            }
        }
    }

    // —— 涨跌配色 ——
    Text { attr { text("涨跌配色"); fontSize(UserSettings.fs(13f)); color(headerColor); marginTop(18f); marginBottom(8f) } }
    View {
        attr {
            flexDirectionRow(); padding(14f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        listOf(
            Triple(0, "红涨绿跌", true),
            Triple(1, "红跌绿涨", false)
        ).forEach { (mode, label, cnIsRedUp) ->
            View {
                attr {
                    flex(1f); flexDirectionColumn(); padding(10f); marginRight(if (mode == 0) 10f else 0f)
                    borderRadius(8f); alignItemsCenter(); justifyContentCenter()
                    backgroundColor(if (ctx.selColorMode == mode) Color(0xFFE6F1FB) else Color(0xFFF5F6F7))
                    border(if (ctx.selColorMode == mode) Border(1.5f, BorderStyle.SOLID, Color(ctx.selTheme)) else Border(0f, BorderStyle.SOLID, Color(0L)))
                }
                event { click { ctx.pickColorMode(mode) } }
                Text {
                    attr {
                        text(label)
                        fontSize(UserSettings.fs(15f)); color(Color(0xFF333333)); marginBottom(8f); fontWeightSemiBold()
                    }
                }
                View { attr { flexDirectionRow(); alignItemsCenter() } }
                // 示例色块：涨/跌各一格（红绿按所选项展示）
                View {
                    attr {
                        flexDirectionRow(); alignItemsCenter(); justifyContentCenter()
                    }
                    colorSwatch(ctx, "涨", if (cnIsRedUp) 0xFFE54D42 else 0xFF1ABE5B)
                    colorSwatch(ctx, "跌", if (cnIsRedUp) 0xFF1ABE5B else 0xFFE54D42)
                }
            }
        }
    }

    // —— 实时预览 ——
    View {
        attr {
            flexDirectionColumn(); alignItemsCenter(); marginTop(10f)
            padding(16f); backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        Text {
            attr {
                text("预览")
                fontSize(UserSettings.fs(15f)); color(Color(ctx.selTheme)); fontWeightSemiBold()
            }
        }
        Text {
            attr {
                text("这是一段示例文字，用于预览字体大小与主题色。")
                fontSize(UserSettings.fs(13f)); color(Color(0xFF555555)); marginTop(8f)
            }
        }
        View {
            attr {
                marginTop(12f); height(36f); padding(left = 20f, right = 20f)
                borderRadius(18f); backgroundColor(Color(ctx.selTheme))
                alignItemsCenter(); justifyContentCenter()
            }
            Text { attr { text("主按钮示例"); fontSize(UserSettings.fs(14f)); color(Color.WHITE) } }
        }
    }

    View { attr { height(20f) } }
}

/** 涨跌配色示例色块：圆形颜色 + 下方标签（用于「涨跌配色」选项展示当前模式的涨/跌色） */
private fun ViewContainer<*, *>.colorSwatch(ctx: AppearancePage, label: String, c: Long) {
    View {
        attr { flexDirectionColumn(); alignItemsCenter(); marginRight(6f) }
        View { attr { width(22f); height(22f); borderRadius(11f); backgroundColor(Color(c)); marginBottom(2f) } }
        Text { attr { text(label); fontSize(UserSettings.fs(11f)); color(Color(0xFF666666)) } }
    }
}
