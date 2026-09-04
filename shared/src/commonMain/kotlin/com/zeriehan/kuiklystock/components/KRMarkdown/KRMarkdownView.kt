package com.zeriehan.kuiklystock.components.KRMarkdown

import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.views.*
import com.zeriehan.kuiklystock.core.KRMarkdown
import com.zeriehan.kuiklystock.core.MdBlock
import com.zeriehan.kuiklystock.core.MdBlockType
import com.zeriehan.kuiklystock.core.MdToken
import com.zeriehan.kuiklystock.core.UserSettings

/**
 * KRMarkdown 富文本渲染器（Task02 发散性渲染：AI 回答渲染成结构化 Markdown）。
 *
 * 用 Kuikly 原生 RichText(跨行混排、自动换行) + 按块组织的 View 布局，把 [KRMarkdown.parse]
 * 产出的块序列渲染成：标题(#/##)、段落、列表、引用、代码块，并支持行内加粗/斜体/行内代码、
 * 以及把股票代码/名称(StockRef)渲染成主题色可点元素(点开跳个股详情)。
 *
 * 纯展示 + onOpenStock 回调；作为文件级扩展函数被 ChatPage 在 AI 气泡内调用。
 *
 * 用法：在纵向容器(View)上调用
 *   renderMarkdown(text, contentW = w, textColor = Color(...), accent = themeColor, onOpenStock = {...})
 */

/** 渲染一段 Markdown 到当前容器（纵向）。依次追加若干块 View。 */
internal fun ViewContainer<*, *>.renderMarkdown(
    text: String,
    contentW: Float,
    textColor: Color,
    accent: Color,
    onOpenStock: (code: String) -> Unit,
) {
    if (text.isBlank()) return
    val blocks = KRMarkdown.parse(text)
    if (blocks.isEmpty()) return
    blocks.forEach { renderOneBlock(it, contentW, textColor, accent, onOpenStock) }
}

/** 渲染单个块 */
private fun ViewContainer<*, *>.renderOneBlock(
    b: MdBlock,
    w: Float,
    textColor: Color,
    accent: Color,
    onOpenStock: (code: String) -> Unit,
) {
    when (b.type) {
        MdBlockType.H1 -> para(w, b.tokens, UserSettings.fs(18f), textColor, accent, onOpenStock, bold = true, top = 8f, bottom = 2f)
        MdBlockType.H2 -> para(w, b.tokens, UserSettings.fs(16f), textColor, accent, onOpenStock, bold = true, top = 6f, bottom = 2f)
        MdBlockType.PARAGRAPH -> para(w, b.tokens, UserSettings.fs(14f), textColor, accent, onOpenStock, bold = false, top = 2f, bottom = 2f)
        MdBlockType.UL_ITEM -> listItem(w, b.tokens, "•", textColor, accent, onOpenStock)
        MdBlockType.OL_ITEM -> listItem(w, b.tokens, b.raw.ifBlank { "•" }, textColor, accent, onOpenStock)
        MdBlockType.QUOTE -> quoteBlock(w, b.tokens, textColor, accent, onOpenStock)
        MdBlockType.CODE -> codeBlock(w, b.raw)
    }
}

/** 普通段落 / 标题（两者都是单 RichText 渲染行内 token） */
private fun ViewContainer<*, *>.para(
    w: Float,
    toks: List<MdToken>,
    fs: Float,
    textColor: Color,
    accent: Color,
    onOpenStock: (String) -> Unit,
    bold: Boolean,
    top: Float,
    bottom: Float,
) {
    View {
        attr { width(w); marginTop(top); marginBottom(bottom) }
        RichText {
            attr { maxWidth(w) }
            for (t in toks) emitSpan(t, fs, textColor, accent, bold, onOpenStock)
        }
    }
}

/** 列表项：前缀文本 + 正文（横向两段） */
private fun ViewContainer<*, *>.listItem(
    w: Float,
    toks: List<MdToken>,
    prefix: String,
    textColor: Color,
    accent: Color,
    onOpenStock: (String) -> Unit,
) {
    View {
        attr { width(w); flexDirectionRow(); marginTop(1f); marginBottom(1f) }
        Text { attr { text(prefix + " "); fontSize(UserSettings.fs(14f)); lineHeight(UserSettings.fs(21f)); color(textColor) } }
        View {
            attr { flex(1f); flexDirectionColumn() }
            RichText {
                attr { maxWidth(w - 24f) }
                for (t in toks) emitSpan(t, UserSettings.fs(14f), textColor, accent, false, onOpenStock)
            }
        }
    }
}

/** 引用块：左侧竖条 + 浅灰底圆角 */
private fun ViewContainer<*, *>.quoteBlock(
    w: Float,
    toks: List<MdToken>,
    textColor: Color,
    accent: Color,
    onOpenStock: (String) -> Unit,
) {
    View {
        attr { width(w); flexDirectionRow(); alignItemsStretch(); marginTop(4f); marginBottom(4f); padding(10f); borderRadius(8f); backgroundColor(Color(0xFFF2F3F5)) }
        View { attr { width(3f); borderRadius(1.5f); backgroundColor(Color(0xFFC7CBD1)); marginRight(8f) } }
        View {
            attr { flex(1f); flexDirectionColumn() }
            RichText {
                attr { maxWidth(w - 34f) }
                for (t in toks) emitSpan(t, UserSettings.fs(13f), textColor, accent, false, onOpenStock)
            }
        }
    }
}

/** 代码块：整块灰底等宽感文本 */
private fun ViewContainer<*, *>.codeBlock(w: Float, raw: String) {
    View {
        attr { width(w); marginTop(4f); marginBottom(4f); padding(10f); borderRadius(8f); backgroundColor(Color(0xFFF4F5F7)) }
        Text {
            attr { text(raw); fontSize(UserSettings.fs(13f)); lineHeight(UserSettings.fs(20f)); color(Color(0xFF444444)); maxWidth(w - 20f) }
        }
    }
}

/** 在 RichText init 内把单个行内 token 变成一个 Span */
private fun RichTextView.emitSpan(
    t: MdToken,
    fs: Float,
    textColor: Color,
    accent: Color,
    bold: Boolean,
    onOpenStock: (String) -> Unit,
) {
    when (t) {
        is MdToken.Plain -> Span { fontSize(fs); color(textColor); if (bold) fontWeightSemiBold(); text(t.text) }
        is MdToken.Bold -> Span { fontSize(fs); color(textColor); fontWeightSemiBold(); text(t.text) }
        is MdToken.Italic -> Span { fontSize(fs); color(textColor); fontStyleItalic(); text(t.text) }
        is MdToken.Code -> Span { fontSize(fs - 1f); color(Color(0xFFC0392B)); text(t.text) }
        is MdToken.Newline -> Span { fontSize(fs); color(textColor); text("\n") }
        is MdToken.StockRef -> Span {
            fontSize(fs); color(accent); fontWeightSemiBold(); textDecorationUnderLine()
            text(t.name)
            click { onOpenStock(t.code) }
        }
    }
}
