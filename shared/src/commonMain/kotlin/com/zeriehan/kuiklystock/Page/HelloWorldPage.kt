package com.zeriehan.kuiklystock.page

import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.pager.Pager
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.compose.Button

@Page("HelloWorld")
internal class HelloWorldPage : Pager() {

    // 响应式文本状态：值变化会自动触发 UI 重渲染
    var message: String by observable("Hello Kuikly")

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            attr {
                allCenter()
                flexDirectionColumn()
            }

            Text {
                attr {
                    text(ctx.message)
                    fontSize(20f)
                }
            }

            Button {
                attr {
                    size(60f, 40f)
                    backgroundColor(Color(0xFF23D3FD))
                    marginTop(20f)
                    // 按钮文字初始为 "1"
                    titleAttr {
                        text("1")
                        fontSize(16f)
                        color(Color.WHITE)
                    }
                }
                event {
                    click {
                        // 每次点击给文本末尾追加一个字母 y
                        ctx.message = ctx.message + "y"
                    }
                }
            }
        }
    }
}
