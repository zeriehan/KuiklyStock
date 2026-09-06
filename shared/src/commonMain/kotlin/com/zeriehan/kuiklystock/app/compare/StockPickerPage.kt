package com.zeriehan.kuiklystock.app.compare

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.UserSettings
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.core.formatPrice

/**
 * 选股页（#96 选股辅助）：从股票池搜索选股，编辑"对比股列表"。
 *
 * 入口（来自 StockComparePage）：
 *   - 点某只股票名 → 跳过来编辑该位置（replaceIndex = 索引）
 *   - 点最右 "+"   → 跳过来追加（replaceIndex = -1）
 *
 * 数据流：读取 [ComparePicker] 单例（initialCodes 起始列表 + replaceIndex 意图），
 * 用户在页内可删/增股票，点「完成」把最终 codes 写回单例 + closePage。
 * 返回对比页后，对比页读取 [ComparePicker.pendingCodes] 应用（手动「刷新对比」按钮触发，
 * 因为 Kuikly 暂无可靠的"子页 close → 父页自动 resume"机制）。
 */
@Page("StockPicker", supportInLocal = true)
internal class StockPickerPage : BasePager() {

    /** 当前选定的对比股 code 列表（可编辑） */
    internal var picked: List<String> by observable(emptyList())
    /** 搜索词 */
    internal var query: String by observable("")
    /** vif 重建触发器（输入/列表变更时翻一下强制重建） */
    internal var toggle: Boolean by observable(false)
    private lateinit var inputRef: ViewRef<InputView>

    override fun viewDidLoad() {
        super.viewDidLoad()
        // 从 pageData 读初始 codes（对比页 openPage 时传过来）。空则默认两支热门股（茅台/五粮液）让用户开页即有可选项。
        val fromPageData = pageData.params.optString("initialCodes")
        val seed = if (fromPageData.isNotBlank()) fromPageData.split(",").map { it.trim() }.filter { it.isNotBlank() }
                   else listOf("600519", "000858")
        picked = seed
        toggle = !toggle
    }

    /** 完成：写回单例 + 关页 */
    internal fun finish() {
        ComparePicker.pendingCodes = picked.toList()
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage()
    }

    /** 移除某 index 的对比股 */
    internal fun removeAt(index: Int) {
        if (index in 0 until picked.size) {
            picked = picked.toMutableList().also { it.removeAt(index) }
            toggle = !toggle
        }
    }

    /** 把搜索结果中的一只股票加入对比股列表（追加） */
    internal fun addStock(code: String) {
        if (picked.contains(code)) return
        picked = picked + code
        toggle = !toggle
    }

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr { flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }

            // 顶部栏
            View {
                attr {
                    padding(12f); paddingTop(pagerData.statusBarHeight); height(44f + pagerData.statusBarHeight)
                    flexDirectionRow(); alignItemsCenter(); backgroundColor(Color.WHITE)
                }
                View {
                    attr { width(32f); height(32f); justifyContentCenter(); alignItemsCenter() }
                    event { click { ctx.acquireModule<RouterModule>(RouterModule.MODULE_NAME).closePage() } }
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemiBold() } }
                }
                Text {
                    attr {
                        text("设置对比股"); fontSize(17f); color(Color(0xFF222222)); fontWeightSemiBold(); marginLeft(8f)
                    }
                }
                View { attr { flex(1f) } }
                View {
                    attr {
                        paddingLeft(14f); paddingRight(14f); height(32f); borderRadius(16f)
                        justifyContentCenter(); alignItemsCenter()
                        backgroundColor(Color(UserSettings.themeColor))
                    }
                    event { click { ctx.finish() } }
                    Text { attr { text("完成"); fontSize(13f); color(Color.WHITE); fontWeightSemiBold() } }
                }
            }

            // 搜索框
            View {
                attr {
                    margin(12f); height(40f); borderRadius(20f); paddingLeft(14f); paddingRight(14f)
                    backgroundColor(Color.WHITE); flexDirectionRow(); alignItemsCenter()
                }
                // 搜索输入：依赖 Input 原生渲染。⚠️ 必须显式设置 color()，否则原生输入框文字可能透明/白看不见。
Input {
                    ref { ctx.inputRef = it }
                    attr {
                        flex(1f); height(36f); fontSize(UserSettings.fs(14f))
                        color(Color(0xFF222222))
                        placeholder(if (ctx.query.isBlank()) "搜索股票名/代码…" else "")
                        placeholderColor(Color(0xFF999999))
                    }
                    event { textDidChange { ctx.query = it.text } }
                }
            }

            // 已选段 + 搜索结果段（由文件级扩展统一构建；此处调用有正确的 ViewContainer 接收器，）内可调用 renderPickerHits
            renderPickerContent(ctx)
        }
    }
}

/** 选股页主体列表：上方「已选」固定（仅在 picked 非空时渲染，避免 0/空态矛盾），下方「搜索结果」flex 滚动。 */
private fun ViewContainer<*, *>.renderPickerContent(ctx: StockPickerPage) {
    // 已选段（固定，不滚动；键盘弹起时仍可见）
    // ⚠️ 仅当 picked 真正非空时才渲染「已选 N 只 + 列表」；否则只显示一次空态提示。
    // （防止 picked=2 个空字符串这种诡异中间态让标题说 2 只但 forEach 渲染异常。）
    if (ctx.picked.isNotEmpty()) {
        View {
            attr { paddingLeft(12f); paddingRight(12f); paddingBottom(8f) }
            Text {
                attr {
                    text("已选 ${ctx.picked.size} 只（点「×」删除）")
                    fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)); marginBottom(8f); marginLeft(4f)
                }
            }
            ctx.picked.forEachIndexed { idx, code ->
                if (code.isBlank()) return@forEachIndexed
                val st = StockData.findByCode(code)
                // 防御：findByCode 对未知 code 回退到上证指数，避免误显示。空名字也跳过。
                if (st.code != code) return@forEachIndexed
                View {
                    attr {
                        backgroundColor(Color.WHITE); borderRadius(8f); padding(10f); marginBottom(6f)
                        flexDirectionRow(); alignItemsCenter()
                    }
                    Text { attr { text(st.name); fontSize(UserSettings.fs(14f)); color(Color(0xFF222222)); flex(1f) } }
                    Text { attr { text(st.code); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)) } }
                    Text {
                        attr {
                            text(formatPrice(st.price) + "  " + formatPercent(st.changePercent))
                            fontSize(UserSettings.fs(12f)); color(StockColor.text(st.changePercent))
                            marginLeft(8f); marginRight(8f)
                        }
                    }
                    View {
                        attr {
                            paddingLeft(10f); paddingRight(10f); height(26f); borderRadius(13f)
                            justifyContentCenter(); alignItemsCenter(); backgroundColor(Color(0xFFF2F3F5))
                        }
                        event { click { ctx.removeAt(idx) } }
                        Text { attr { text("×"); fontSize(16f); color(Color(0xFF666666)); fontWeightSemiBold() } }
                    }
                }
            }
        }
    } else {
        // picked 真正为空：显示一次性友好提示
        View {
            attr { paddingLeft(16f); paddingRight(16f); paddingTop(8f) }
            Text {
                attr {
                    text("尚未选股，请在搜索框输入股票名/代码后点「＋」加入对比。")
                    fontSize(UserSettings.fs(13f)); color(Color(0xFFBBBBBB)); marginLeft(4f)
                }
            }
        }
    }

    // 搜索结果段（始终显示候选池：query 非空走过滤，query 空走默认热门前60只；minHeight 保键盘弹起仍可见）
    Scroller {
        attr {
            flex(1f); minHeight(160f)
            flexDirectionColumn(); paddingLeft(12f); paddingRight(12f); paddingBottom(12f)
        }
        vif({ ctx.toggle }) { val c = this; c.renderPickerHits(ctx) }
        vif({ !ctx.toggle }) { val c = this; c.renderPickerHits(ctx) }
    }
}

/** 渲染搜索结果/候选列表：query 空→热门前60只；query 非空→过滤。点整行 / 点 ＋ 均加入。 */
private fun ViewContainer<*, *>.renderPickerHits(ctx: StockPickerPage) {
    val q = ctx.query.trim()
    val pool = StockData.getQuotes().filter { !it.isIndex }
    val hits = if (q.isBlank()) pool.take(60)
               else pool.filter { it.code.contains(q) || it.name.contains(q) }.take(40)
    Text {
        attr {
            text(if (q.isBlank()) "热门候选（点「＋」或整行加入对比）" else "搜索结果（点「＋」或整行加入对比）")
            fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)); marginTop(4f); marginBottom(8f); marginLeft(4f)
        }
    }
    if (hits.isEmpty()) {
        Text {
            attr {
                text("无匹配股票，换个关键词试试"); fontSize(UserSettings.fs(13f)); color(Color(0xFFBBBBBB)); marginLeft(4f)
            }
        }
    } else {
        hits.forEach { st ->
            // 已选过的股在候选中显示「已加」灰态（不再可点＋，避免重复）
            val alreadyAdded = ctx.picked.contains(st.code)
            View {
                attr {
                    backgroundColor(if (alreadyAdded) Color(0xFFF5F5F5) else Color.WHITE); borderRadius(8f)
                    padding(10f); marginBottom(6f); flexDirectionRow(); alignItemsCenter()
                }
                event { if (!alreadyAdded) ctx.addStock(st.code) }
                Text {
                    attr {
                        text(st.name); fontSize(UserSettings.fs(14f))
                        color(if (alreadyAdded) Color(0xFFBBBBBB) else Color(0xFF222222))
                        flex(1f)
                    }
                }
                Text { attr { text(st.code); fontSize(UserSettings.fs(12f)); color(Color(0xFF999999)) } }
                Text {
                    attr {
                        text(formatPrice(st.price) + "  " + formatPercent(st.changePercent))
                        fontSize(UserSettings.fs(12f)); color(StockColor.text(st.changePercent))
                        marginLeft(8f); marginRight(8f)
                    }
                }
                // 「＋加入」按钮（主题色醒目，已加则显示「✓ 已加」灰底）
                View {
                    attr {
                        paddingLeft(12f); paddingRight(12f); height(28f); borderRadius(14f); marginLeft(6f)
                        justifyContentCenter(); alignItemsCenter()
                        backgroundColor(if (alreadyAdded) Color(0xFFEEEEEE) else Color(UserSettings.themeColor))
                    }
                    Text {
                        attr {
                            text(if (alreadyAdded) "✓ 已加" else "＋ 加入")
                            fontSize(12f)
                            color(if (alreadyAdded) Color(0xFF999999) else Color.WHITE)
                            fontWeightSemiBold()
                        }
                    }
                }
            }
        }
    }
}
