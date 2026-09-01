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
import com.tencent.kuikly.core.views.compose.Button
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.Utils
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.MockStockSource
import com.zeriehan.kuiklystock.core.UserStockStore

/**
 * 「不感兴趣的股票」集中管理页。
 *
 * 入口：我的 → 「不感兴趣的股票」行（点击跳转），列表本身不再铺在「我的」页里，避免设置页过长。
 * 能力：查看全部被隐藏的股票、单只手动恢复、一次性全部恢复；并展示距自动恢复还剩多少天。
 *
 * 渲染约定（沿用项目铁律）：本版本 body 不随 observable 重跑，列表必须用 `vif(toggle)` 双分支
 * 翻转强制重建；渲染函数必须是**文件级**扩展函数并显式传 ctx（类内成员扩展函数会丢接收者）。
 */
@Page("HiddenStocks", supportInLocal = true)
internal class HiddenStocksPage : BasePager() {

    private val DAY_MS = 86_400_000L

    /** code -> 隐藏时刻(ms) */
    internal var hiddenMap: Map<String, Long> by observable(emptyMap())
    internal var hideDays: Int by observable(7)
    /** vif 翻转触发器：列表随其翻转强制重建 */
    internal var listToggle: Boolean by observable(false)

    private val prefs: SharedPreferencesModule
        get() = acquireModule(SharedPreferencesModule.MODULE_NAME)

    internal fun nowMs(): Long = Utils.currentBridgeModule().currentTimeStamp()

    override fun viewDidLoad() {
        super.viewDidLoad()
        reload()
    }

    /** 从子页返回 / 再次进入时同步磁盘最新状态 */
    override fun pageDidAppear() {
        super.pageDidAppear()
        reload()
    }

    private fun reload() {
        hiddenMap = UserStockStore.loadHidden(prefs)
        hideDays = UserStockStore.loadHideDays(prefs)
        listToggle = !listToggle
    }

    internal fun unhide(code: String) {
        hiddenMap = hiddenMap - code
        UserStockStore.saveHidden(prefs, hiddenMap)
        listToggle = !listToggle
    }

    internal fun unhideAll() {
        val n = hiddenMap.size
        hiddenMap = emptyMap()
        UserStockStore.saveHidden(prefs, hiddenMap)
        listToggle = !listToggle
        bridgeModule.toast("已恢复 $n 只")
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flex(1f); flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }

            // 内容卡宽度（Scroller 默认不拉伸子元素，须显式给宽以铺满、避免右侧留白）
            // 兜底 200f：子页面首帧 pageViewWidth 可能为 0，裸减会算出负宽导致卡片不可见
            val contentW = (ctx.pagerData.pageViewWidth - 24f).coerceAtLeast(200f)

            // ===== 返回栏 =====
            View {
                attr {
                    padding(12f)
                    paddingTop(pagerData.statusBarHeight)
                    height(44f + pagerData.statusBarHeight)
                    flexDirectionRow()
                    alignItemsCenter()
                    backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemisolid() } }
                }
                Text {
                    attr {
                        text("不感兴趣的股票")
                        fontSize(17f); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f)
                    }
                }
            }

            // ===== 列表区 =====
            Scroller {
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                // 用 vif 双分支翻转强制重建（body 不随 observable 重跑）
                vif({ ctx.listToggle }) { val c = this; c.renderHiddenList(ctx, contentW) }
                vif({ !ctx.listToggle }) { val c = this; c.renderHiddenList(ctx, contentW) }
            }
        }
    }
}

/**
 * 渲染隐藏股票列表（含说明卡、每行条目、空态、全部恢复）。
 * 必须是文件级扩展函数：类内成员扩展函数在 vif 闭包里会丢失分派接收者而编译失败。
 */
private fun ViewContainer<*, *>.renderHiddenList(ctx: HiddenStocksPage, contentW: Float) {
    ctx.listToggle // 依赖保险
    val map = ctx.hiddenMap

    // —— 说明卡 ——
    View {
        attr {
            flexDirectionColumn(); padding(14f); backgroundColor(Color.WHITE)
            borderRadius(10f); width(contentW)
        }
        Text {
            attr {
                text("共 ${map.size} 只股票被标记为「不感兴趣」，已从行情和自选列表中隐藏。")
                fontSize(14f); color(Color(0xFF222222))
            }
        }
        Text {
            attr {
                text("到达自动恢复周期（${ctx.hideDays} 天）后会重新出现，也可以在这里随时手动恢复。")
                fontSize(12f); color(Color(0xFF999999)); marginTop(6f)
            }
        }
    }

    if (map.isEmpty()) {
        View {
            attr {
                flexDirectionColumn(); alignItemsCenter(); marginTop(60f); width(contentW)
            }
            Text { attr { text("还没有标记为不感兴趣的股票"); fontSize(16f); color(Color(0xFF222222)) } }
            Text {
                attr {
                    text("在行情或自选里长按某只股票，选「不感兴趣」即可")
                    fontSize(13f); color(Color(0xFF999999)); marginTop(8f)
                }
            }
        }
        return
    }

    // —— 条目卡 ——
    View {
        attr {
            flexDirectionColumn(); marginTop(10f); padding(14f)
            backgroundColor(Color.WHITE); borderRadius(10f); width(contentW)
        }
        map.toList().forEachIndexed { i, (code, ts) ->
            val s = MockStockSource.findByCode(code)
            val remainMs = ctx.hideDays * 86_400_000L - (ctx.nowMs() - ts)
            val remainText = if (remainMs <= 0L) {
                "已到点，即将自动恢复"
            } else {
                "还有 ${(remainMs + 86_399_999L) / 86_400_000L} 天自动恢复"
            }
            View {
                attr { flexDirectionRow(); alignItemsCenter(); marginTop(if (i == 0) 0f else 12f) }
                View { attr { flex(1f); flexDirectionColumn() }
                    Text { attr { text(s.name); fontSize(15f); color(Color(0xFF222222)) } }
                    Text {
                        attr {
                            text("$code · $remainText")
                            fontSize(12f); color(Color(0xFF999999)); marginTop(3f)
                        }
                    }
                }
                Button {
                    attr {
                        size(56f, 28f); borderRadius(14f); backgroundColor(Color(0xFFF2F3F5))
                        titleAttr { text("恢复"); fontSize(13f); color(Color(0xFF23D3FD)) }
                    }
                    event {
                        click {
                            ctx.unhide(code)
                            ctx.bridgeModule.toast("已恢复 ${s.name}")
                        }
                    }
                }
            }
        }
    }

    // —— 全部恢复 ——
    View {
        attr {
            marginTop(16f); height(44f); borderRadius(22f)
            backgroundColor(Color.WHITE); width(contentW)
            alignItemsCenter(); justifyContentCenter()
        }
        event { click { ctx.unhideAll() } }
        Text { attr { text("全部恢复"); fontSize(15f); color(Color(0xFF23D3FD)) } }
    }
    View { attr { height(24f) } }
}
