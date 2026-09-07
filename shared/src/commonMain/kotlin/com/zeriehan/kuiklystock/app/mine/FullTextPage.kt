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
 * 「全文」展示页：详情页/迷你卡里被截断(省略号)的长文本点开看全文。
 *
 * 入口：各卡片里截断文本的"点击展开全文"→ openPage("FullText", data{title, text})。
 * 展示：顶部返回栏(标题) + Scroller 全文，不截断、可滚动。纯展示无交互状态。
 */
@Page("FullText", supportInLocal = true)
internal class FullTextPage : BasePager() {

    internal var fullTitle: String by observable("")
    internal var fullText: String by observable("")

    override fun body(): ViewBuilder {
        val ctx = this
        // ⚠️ Kuikly 的 pageData.params 须在 body 作用域内读取(viewDidLoad 中为空白)
        if (fullText.isEmpty()) {
            fullTitle = pageData.params.optString("title", "全文")
            fullText = pageData.params.optString("text", "")
        }
        return {
            attr { flex(1f); flexDirectionColumn(); backgroundColor(Color(0xFFF2F3F5)) }

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
                    attr {
                        text(ctx.fullTitle); fontSize(17f); color(Color(0xFF222222)); fontWeightSemisolid(); marginLeft(8f)
                    }
                }
            }

            // ===== 正文（白底卡铺满，可滚动，不截断）=====
            // Scroller 默认不拉伸子元素，须显式给宽以铺满，避免右侧留白
            val contentW = (ctx.pagerData.pageViewWidth - 24f).coerceAtLeast(200f)
            Scroller {
                attr { flex(1f); flexDirectionColumn(); padding(12f) }
                View {
                    attr { width(contentW); padding(14f); backgroundColor(Color.WHITE); borderRadius(10f) }
                    if (ctx.fullText.isBlank()) {
                        Text { attr { text("暂无内容"); fontSize(UserSettings.fs(13f)); color(Color(0xFF999999)) } }
                    } else {
                        Text {
                            attr {
                                text(ctx.fullText)
                                fontSize(UserSettings.fs(14f)); color(Color(0xFF333333)); lineHeight(22f)
                            }
                        }
                    }
                }
            }
        }
    }
}
