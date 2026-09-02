package com.zeriehan.kuiklystock.app.detail

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.module.SharedPreferencesModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.base.ViewContainer
import com.zeriehan.kuiklystock.base.BasePager
import com.zeriehan.kuiklystock.base.bridgeModule
import com.zeriehan.kuiklystock.core.StockData
import com.zeriehan.kuiklystock.core.Sector
import com.zeriehan.kuiklystock.core.Stock
import com.zeriehan.kuiklystock.core.StockColor
import com.zeriehan.kuiklystock.core.UserStockStore
import com.zeriehan.kuiklystock.core.formatPrice
import com.zeriehan.kuiklystock.core.formatPercent
import com.zeriehan.kuiklystock.app.main.StockNavigator
import com.zeriehan.kuiklystock.app.main.renderMarketRow

/**
 * 板块详情页（行情「板块」Tab 行点击进入）。
 * 进入方式：行情板块行 -> RouterModule.openPage("SectorDetail", sectorCode)。
 * 布局：返回栏 + 板块头（名称 / 涨跌幅 / 成分数）+ 成分股列表（复用 [renderMarketRow]）+ 长按菜单。
 */
@Page("SectorDetail", supportInLocal = true)
internal class SectorDetailPage : BasePager(), StockNavigator {

    /** 长按操作菜单状态 */
    private var sheetStock: Stock? by observable(null)
    private var sheetX: Float by observable(0f)
    private var sheetY: Float by observable(0f)
    /** 自选集合（响应式镜像，进入页时从 SharedPreferences 载入） */
    private var watchlistCodes: Set<String> by observable(emptySet())
    /** 不感兴趣集合（响应式镜像，进入页时载入；用于灰幕覆盖与菜单文案切换） */
    private var hiddenMap: Map<String, Long> by observable(emptyMap())
    /** vif 翻转触发器：标记/恢复后强制重建成分股列表（body 不随 observable 重跑） */
    private var sectorListToggle: Boolean by observable(false)

    // ===== 真实行业板块（BKxxxx）异步成分股状态 =====
    /** 板块代码（body 从 params 读取前为占位） */
    private var sectorCode: String = ""
    /** 响应式板块信息：真实板块成分股拉取完成后更新，驱动头部与列表重读 */
    private var curSector: Sector? by observable(null)
    private var curStocks: List<Stock> by observable(emptyList())

    override fun viewDidLoad() {
        super.viewDidLoad()
        watchlistCodes = UserStockStore.loadWatchlist(acquireModule(SharedPreferencesModule.MODULE_NAME))
        hiddenMap = UserStockStore.loadHidden(acquireModule(SharedPreferencesModule.MODULE_NAME))
    }

    /** 重新解析板块 + 成分股到响应式状态（从 params 或重拉后调用） */
    private fun resolveSector(code: String) {
        sectorCode = code
        val s = StockData.findSectorByCode(code)
            ?: Sector(code, if (code.startsWith("BK")) "行业板块" else "未找到板块", 0f, emptyList())
        curSector = s
        curStocks = StockData.getSectorStocks(s)
        sectorListToggle = !sectorListToggle
        // 真实行业板块且成分尚未拉取：触发拉取，完成后回调里再 resolve 一次
        if (code.startsWith("BK") && s.constituentCodes.isEmpty()) {
            StockData.loadSectorStocks(code) { resolveSector(code) }
        }
    }

    /** 切换自选（加/取消），落盘并提示 */
    private fun toggleWatch(code: String) {
        watchlistCodes = if (watchlistCodes.contains(code)) watchlistCodes - code else watchlistCodes + code
        UserStockStore.saveWatchlist(acquireModule(SharedPreferencesModule.MODULE_NAME), watchlistCodes)
        bridgeModule.toast(if (watchlistCodes.contains(code)) "已加入自选" else "已取消自选")
    }

    /** 标记/恢复「不感兴趣」：加/移除灰幕标记并落盘，翻转列表重建 */
    private fun toggleHide(code: String) {
        hiddenMap = if (hiddenMap.containsKey(code)) hiddenMap - code else hiddenMap + (code to System.currentTimeMillis())
        UserStockStore.saveHidden(acquireModule(SharedPreferencesModule.MODULE_NAME), hiddenMap)
        sectorListToggle = !sectorListToggle
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val rawCode = pageData.params.optString("sectorCode")
        // 首次进页解析一次；若为真实板块且未拉成分，resolveSector 内部会异步重拉并再次 resolve
        if (curSector == null) ctx.resolveSector(rawCode)
        return {
            attr {
                flexDirectionColumn()
                backgroundColor(Color(0xFFF2F3F5))
            }

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
                    Text { attr { text("<"); fontSize(22f); color(Color(0xFF222222)); fontWeightSemiBold() } }
                }
                // 引用响应式 curSector：真实板块成分股拉取完成后 name/count 会随其变化自动更新
                Text { attr { text(ctx.curSector?.name ?: "板块"); fontSize(17f); color(Color(0xFF222222)); fontWeightSemiBold(); marginLeft(8f) } }
                Text {
                    attr {
                        val n = ctx.curSector?.constituentCodes?.size ?: 0
                        text(if (n > 0) "$n 只" else "")
                        fontSize(12f); color(Color(0xFF999999)); marginLeft(8f)
                    }
                }
            }

            // ===== 板块头 =====
            View {
                attr {
                    flexDirectionRow(); alignItemsCenter(); padding(16f); backgroundColor(Color.WHITE)
                    marginTop(10f); marginLeft(12f); marginRight(12f); borderRadius(10f)
                }
                View {
                    attr { flex(1f); flexDirectionColumn() }
                    Text { attr { text("板块涨跌幅"); fontSize(12f); color(Color(0xFF999999)) } }
                    Text {
                        attr {
                            val chg = ctx.curSector?.changePercent ?: 0f
                            text(formatPercent(chg))
                            fontSize(26f); fontWeightSemiBold(); color(StockColor.of(chg))
                            marginTop(4f)
                        }
                    }
                }
                // 简易成分数提示（左块 flex(1f) 已把右块顶到最右，无需额外对齐）
                View {
                    attr { flexDirectionColumn() }
                    Text { attr { text("成分股"); fontSize(12f); color(Color(0xFF999999)) } }
                    Text {
                        attr {
                            val n = ctx.curSector?.constituentCodes?.size ?: 0
                            text("$n 只")
                            fontSize(16f); color(Color(0xFF222222)); marginTop(4f)
                        }
                    }
                }
            }

            // ===== 成分股列表（随 sectorListToggle 翻转重建；读响应式 curStocks，异步拉成分后自动补齐）=====
            Scroller {
                attr { flex(1f); flexDirectionColumn(); marginTop(8f) }
                if (ctx.curStocks.isEmpty()) {
                    Text {
                        attr {
                            text(if (ctx.sectorCode.startsWith("BK")) "正在加载板块成分股…" else "该板块暂无成分股数据")
                            fontSize(13f); color(Color(0xFF999999)); marginTop(16f); marginLeft(12f)
                        }
                    }
                } else {
                    vif({ ctx.sectorListToggle }) { val c = this; ctx.curStocks.forEach { s -> c.renderMarketRow(ctx, s) } }
                    vif({ !ctx.sectorListToggle }) { val c = this; ctx.curStocks.forEach { s -> c.renderMarketRow(ctx, s) } }
                }
                View { attr { height(16f) } }
            }

            // ===== 长按操作菜单（覆盖层）=====
            vif({ ctx.sheetStock != null }) {
                View {
                    attr { absolutePositionAllZero(); backgroundColor(Color(0x55000000)) }
                    event { click { ctx.sheetStock = null } }
                }
                View {
                    attr {
                        val vw = ctx.pagerData.pageViewWidth
                        val vh = ctx.pagerData.pageViewHeight
                        val menuW = 176f
                        val menuH = 250f
                        val left = (ctx.sheetX - menuW / 2f).coerceIn(8f, (vw - menuW - 8f).coerceAtLeast(8f))
                        val top = ctx.sheetY.coerceIn(8f, (vh - menuH - 8f).coerceAtLeast(8f))
                        absolutePosition(top = top, left = left)
                        width(menuW)
                        backgroundColor(Color.WHITE)
                        borderRadius(10f)
                        flexDirectionColumn()
                    }
                    val stock = ctx.sheetStock!!
                    val watched = ctx.watchlistCodes.contains(stock.code)
                    val dimmed = ctx.isHidden(stock.code)
                    // 加自选（可切换）
                    View {
                        attr { height(50f); justifyContentCenter(); paddingLeft(16f) }
                        event {
                            click {
                                ctx.toggleWatch(stock.code)
                                ctx.sheetStock = null
                            }
                        }
                        Text { attr { text(if (watched) "★ 已自选" else "☆ 加自选"); fontSize(15f); color(Color(0xFF222222)) } }
                    }
                    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
                    // 问 AI
                    View {
                        attr { height(50f); justifyContentCenter(); paddingLeft(16f) }
                        event { click { ctx.askAI(stock) } }
                        Text { attr { text("问 AI"); fontSize(15f); color(Color(0xFF222222)) } }
                    }
                    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
                    // 查看详细
                    View {
                        attr { height(50f); justifyContentCenter(); paddingLeft(16f) }
                        event { click { ctx.openDetail(stock) } }
                        Text { attr { text("查看详细"); fontSize(15f); color(Color(0xFF222222)) } }
                    }
                    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
                    // 不感兴趣 / 恢复（按当前是否已被标记切换文案；标记后该行灰幕覆盖）
                    View {
                        attr { height(50f); justifyContentCenter(); paddingLeft(16f) }
                        event {
                            click {
                                ctx.toggleHide(stock.code)
                                ctx.sheetStock = null
                            }
                        }
                        Text { attr { text(if (dimmed) "恢复" else "不感兴趣"); fontSize(15f); color(Color(0xFF222222)) } }
                    }
                    View { attr { height(0.5f); backgroundColor(Color(0xFFEEEEEE)); marginLeft(16f) } }
                    // 复制代码
                    View {
                        attr { height(50f); justifyContentCenter(); paddingLeft(16f) }
                        event { click { ctx.copyCode(stock) } }
                        Text { attr { text("复制代码"); fontSize(15f); color(Color(0xFF222222)) } }
                    }
                }
            }
        }
    }

    // ===== StockNavigator =====
    override fun openDetail(stock: Stock) {
        sheetStock = null
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("StockDetail", d)
    }

    override fun openSheet(stock: Stock, x: Float, y: Float) {
        sheetStock = stock; sheetX = x; sheetY = y
    }

    override fun isHidden(code: String): Boolean = hiddenMap.containsKey(code)

    // ===== 菜单动作 =====
    private fun askAI(stock: Stock) {
        sheetStock = null
        val d = JSONObject(); d.put("stockCode", stock.code)
        acquireModule<RouterModule>(RouterModule.MODULE_NAME).openPage("Chat", d)
    }

    private fun copyCode(stock: Stock) {
        sheetStock = null
        bridgeModule.copyToPasteboard(stock.code)
        bridgeModule.toast("代码 ${stock.code} 已复制")
    }
}
