# KuiklyStock — AI 股票行情 + AI 股票问答 App

> **本文档 = 项目战略书 + AI 接手指南（单一事实来源）**
> 如果你是接手本项目的新 AI：请**从「一、AI 接手指南」开始读**，里面说明了当前进度、代码约定、下一步具体任务。其余章节是完整的产品规划与技术方案。

---

## 一、AI 接手指南（START HERE）

### 1.1 项目是什么
- **参赛项目**：2026 腾讯犀牛鸟对外开源人才计划 · KuiklyUI 实战
- **两个任务**：Task 01 AI 股票行情原型 Demo + Task 02 AI 股票问答应用 Demo
- **截止日期**：2026-09-14（约 18 天）
- **框架**：KuiklyUI（腾讯开源 Kotlin Multiplatform UI 框架）
- **形态**：一个 App，底部四 Tab（聊天管理 / 行情 / 自选股 / 我的）
- **当前进度（2026-09-04）**：Task01（行情）已收官、Task02（AI 问答）核心功能已齐并本地提交（富文本/提及股卡片/思考态/选字等），尚未真机验证与推送。源码已从 P0 的 3 个文件发展到 40 个文件。**本接手指南的"目录结构/排期状态/文件清单"等节见文末的进度更新，前面战略规划文字基本仍有效。**

### 1.2 仓库与路径
```
仓库根：D:/AAAAstudy/cEngineering/Appproject/AndroidProject/KuiklyStock/
源码根：KuiklyStock/shared/src/commonMain/kotlin/com/zeriehan/kuiklystock/
包名：  com.zeriehan.kuiklystock
```
- 工程本体已在 `KuiklyStock/` 总仓库内（**不要新建第二个 git 仓库**）。
- 仓库已建好 `.gitignore`（已忽略 `.gradle/`、`build/`、`.idea/`、`local.properties`、`*.keystore`、`*.env`、`secrets.properties`、`.kotlin/` 等）。

### 1.3 已写文件（在 `com/zeriehan/kuiklystock/` 下）
| 文件 | 内容 | 状态 |
|------|------|------|
| `core/Stock.kt` | `Stock` 数据类 + `StockColor`(涨红跌绿) + `formatPrice/formatPercent` | 已写，**未编译** |
| `components/KRStockBadge/KRStockBadge.kt` | 涨跌徽章（涨红跌绿） | 已写，**未编译** |
| `components/KRTable/KRTable.kt` | 行情列表 `KRStockList` + **行内展开**（expandedIndex 控制挤开下方） | 已写，**未编译**；走势图是占位块 TODO |

> ⚠️ **这三个文件从未在 Android Studio 编译过**，首次 Gradle Sync 大概率有少量 API 名称微调。请以模板里已有的文件（`RouterPage.kt`、`Page/HelloWorldPage.kt`、`base/BasePager.kt`）为权威参考来修正报错。

### 1.4 下一步该做什么（按优先级）
1. **编译验证**：在 Android Studio 打开 `KuiklyStock`，Gradle Sync，把上面 3 个文件的报错修掉（这是第一要务，否则后面无法验证）。
2. **KRTrendChart**：真·走势折线组件，替换 `KRTable.kt` 里的占位块（P1 加分点，需确认 Kuikly 的 Canvas/绘图 API）。
3. **MockStockSource**：造几只示例股票（含大盘指数，如 上证指数/深证成指/创业板指），让列表有数据。
4. **QuotesPage**：行情 Tab 宿主页，把 `KRStockList` 跑起来，真机/模拟器验证行内展开。
5. 然后按 P2→P4 推进（见第六节排期）。

### 1.5 关键约束（务必遵守）
- **涨红跌绿**（中国股市惯例）：涨=`0xFFE54D42` 红，跌=`0xFF1ABE5B` 绿，平=灰。
- **不提交任何 API Key**：Key 走 `local.properties`（已被忽略），`gradle.properties` 只放可公开项。
- **参考项目 `Study/KuiklyTableView-main/` 只能学思路，必须自己重写**，不可直接复制其代码。
- **KMP common 不支持 `String.format`**：价格格式化用 `Stock.kt` 里自写的 `formatPrice/formatPercent`，勿改用 JVM-only API。
- **git push 需要用户本机凭据**：命令行/AI 环境通常没有 GitHub 写权限（公开仓库 clone 可读、push 需认证）。改完文件后提醒用户在能 push 的终端执行 `git add -A && git commit && git push`，或让用户授权后再推。

---

## 二、产品形态：四 Tab 主框架

| Tab | 页面 | 内容 | 对应任务 |
|-----|------|------|----------|
| 1 | AI 聊天管理 | 会话列表（每只股票可建独立会话，标题=股票名）、每日推荐股票、新建/删除会话 | Task 02 主体 |
| 2 | 行情 | 大盘指数 + 个股列表，**行内展开交互**，点击"详细"跳转详情页 | Task 01 主体 |
| 3 | 自选股 | 自选列表，**完全复用行情页列表组件**，仅数据源换成自选池 | 组件复用加分 |
| 4 | 我的 | 设置：LLM API Key 配置、关于、主题等 | 工程完整性 |

**行情页"行内展开"交互**（富途/雪球式）：点击某股票行 → 原位展开一块区域（**挤开下方股票**），显示迷你走势图 + 关键信息（最新价/最高/最低/成交量）+ "详细"按钮。

**详情页 AI 交互（A+C 组合，已确认）**：
- **C·页面内 AI 分析区**：详情页内嵌"AI 分析"卡片，点按钮调用 LLM 生成结构化分析（基本面/技术面/风险提示三段式），可"重新生成" → Task 01 验收点。
- **A·专属会话入口**：分析卡片底部"深入聊聊这只股票" → 点击新建/跳转以该股票命名的会话（会话记录带 `stockCode` 字段，聊天页自动注入该股票行情上下文）→ 与 Task 02 形成双向闭环。
- 关键认知："股票专属 AI" = **同一 LLM + 不同 System Prompt + 注入股票上下文**，不是每只股票部署一个模型。

---

## 三、技术决策（已确认）

| 项 | 决策 |
|----|------|
| DSL | **Kuikly DSL**（非 Compose DSL），底层可控、自研组件友好 |
| Kotlin | 2.1.21 |
| Shared Module | `shared`；产物发布名 `shared` |
| 包名/namespace | `com.zeriehan.kuiklystock`（已生成正确，勿用默认 demo 包名） |
| Min SDK | API 21（Android 5.0，Kuikly 下限）；compile/target 用插件默认 |
| 运行期 LLM | **GLM-4-Flash 免费** + `LLMClient` 接口抽象（DeepSeek-v3 备选） |
| 数据源 | Mock 先行 + `StockRepository` 抽象；P4 接 Tushare 冲加分 |
| 开发期工具 | `npx skills add Tencent-TDS/KuiklyUI-AI/skills`（给 AI 编程工具用，与运行时无关） |
| 官方 KuiklyAIChat | 灰度中，仅作对照参考，不以它为依赖 |

**开发期 vs 运行期认知**：`npx skills add ...` 是给 CodeBuddy/Cursor 用的开发辅助；App 运行时 AI 必须自接 LLM API。两者无关。

---

## 四、目录结构（当前实际）

```
com/zeriehan/kuiklystock/
├── base/                      [模板自带，保留] BasePager / BridgeModule / Utils
├── Page/                      [模板自带] HelloWorldPage.kt
├── RouterPage.kt              [模板自带] 路由入口示例
├── ImageAdapterBenchmarks.kt  [模板自带] 忽略
├── core/
│   ├── Stock.kt              ✅ 已写：Stock 模型 + 配色 + 格式化
│   ├── LLMClient.kt          ⬜ 待写：LLM 接口抽象（GLM-4-Flash 实现）
│   ├── StockRepository.kt    ⬜ 待写：数据源接口
│   ├── MockStockSource.kt    ⬜ 待写：示例数据（含大盘指数）
│   └── TushareSource.kt      ⬜ P4：真实数据
├── components/
│   ├── KRStockBadge/         ✅ 已写：涨跌徽章
│   ├── KRTable/              ✅ 已写：KRStockList（含行内展开，走势占位）
│   ├── KRTrendChart/         ⬜ 待写：折线/分时走势（展开行+详情页共用）
│   ├── KRStockCard/          ⬜ 待写：股票卡片
│   ├── KRMarkdown/           ⬜ P3：Markdown 渲染
│   └── KRChatBubble/         ⬜ P3：聊天气泡
└── app/
    ├── chat/                 ⬜ Tab1：ChatListPage / ChatPage / StockBridgePage
    ├── quotes/               ⬜ Tab2：QuoteListPage / StockDetailPage / AIAnalysisCard
    ├── watchlist/            ⬜ Tab3：自选股（复用 quotes 列表组件）
    └── profile/              ⬜ Tab4：我的（LLM Key 配置）
```

---

## 五、Kuikly 代码约定（已验证于模板的 API 模式）

> 以下是从已写 3 个文件提炼的**写组件范式**。首次编译若报 API 名错误，以模板文件为准修正。

**自定义组件标准写法**（ComposeView + 扩展函数 `addChild`）：
```kotlin
package com.zeriehan.kuiklystock.components.KRStockBadge

import com.tencent.kuikly.core.base.ComposeAttr
import com.tencent.kuikly.core.base.ComposeEvent
import com.tencent.kuikly.core.base.ComposeView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

internal class KRStockBadge : ComposeView<KRStockBadgeAttr, ComposeEvent>() {
    override fun createAttr() = KRStockBadgeAttr()
    override fun createEvent() = ComposeEvent()
    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr { padding(all = 4f); borderRadius(4f); backgroundColor(Color(0xFFF2F3F5)) }
                Text { attr { text("示例"); fontSize(13f); color(Color(0xFF222222)) } }
            }
        }
    }
}
internal class KRStockBadgeAttr : ComposeAttr() {
    var changePercent: Float by observable(0f)  // 配置项走 attr
}
internal fun ViewContainer<*, *>.KRStockBadge(init: KRStockBadge.() -> Unit) {
    addChild(KRStockBadge(), init)
}
```

**页面写法**（模板 `Page/HelloWorldPage.kt`、`RouterPage.kt`）：
```kotlin
@Page("QuotesPage")            // 路由名
class QuotesPage : Pager() {   // 或继承 base/BasePager
    override fun body(): ViewBuilder { ... }
}
```

**已确认可用的 attr/event API**（来自模板 + 已写文件）：
- 布局：`flex(1f)`、`flexDirectionRow()`、`flexDirectionColumn()`、`alignItemsCenter()`、`justifyContentFlexEnd()`、`alignSelfFlexEnd()`
- 间距：`padding(all = 14f)`、`padding(16f)`、`marginTop(4f)`、`marginLeft(16f)`
- 样式：`borderRadius(4f)`、`backgroundColor(Color(0xFF...))`、`fontSize(13f)`、`color(Color(...))`、`text("...")`、`size(96f, 36f)`
- 颜色：`Color(0xFFE54D42)`、`Color.WHITE`、`StockColor.of(changePercent)`
- 组件：`View {}`、`Text {}`、`Button { attr { titleAttr { text("详细") } } event { click { ... } } }`
- 响应式：`var x by observable(初始值)`（改了自动刷新 UI）
- 条件渲染：`vif({ 条件 }) { ... }`（来自 `com.tencent.kuikly.core.directives.vif`）
- 列表渲染：`stocks.forEachIndexed { index, stock -> View { ... } }`
- 点击事件：`event { click { /* 处理 */ } }`

**两个属性约定（注意一致性）**：
- `KRStockBadge` 把配置（`changePercent`）放进了 `Attr`（通过 `attr { changePercent = ... }` 设置）——**推荐此模式**。
- `KRStockList` 把 `stocks` / `onDetailClick` / `onRowClick` 直接定义为 ComposeView 的成员变量（在 `init` 块里赋值）——也能跑，但建议后续统一收敛到 `Attr` 里，便于一致。

---

## 六、总体排期（20 天 · 5 阶段）

原则：**组件先行、场景后置、保底交付、逐步加分**。

| 阶段 | 时间 | 核心事项 | 当前状态 |
|------|------|----------|----------|
| P0 地基 | 8/25-8/27 | 环境、框架学习、跑通 androidApp | ✅ 已完成 |
| P1 组件 | 8/28-9/1 | KRTable/KRStockBadge/KRTrendChart/KRStockCard | 🔶 进行中（已写 2 个，待编译+KRTrendChart） |
| P2 Task01 | 9/2-9/5 | 行情页+详情页+AI 分析模块+Mock 数据 | ⬜ |
| P3 Task02 | 9/6-9/9 | 聊天页+会话管理+LLM 接入+Markdown+承接页 | ⬜ |
| P4 交付 | 9/10-9/14 | Tushare 加分、跨端(可选)、文档、演示视频 | ⬜ |

**里程碑**：P0 工程稳定跑通；P1 组件库成型；P2 Task01 全链路可演示；P3 Task02 全链路可演示且双任务闭环；P4 三件套交付。

---

## 七、技术方案细节

### 7.1 LLM 接入（GLM-4-Flash）
- 封装 `core/LLMClient.kt` 接口：`suspend fun analyze(prompt, systemPrompt, history): String`（或流式）。
- 实现类 `GLMClient` 调智谱 OpenAI 兼容接口 `https://open.bigmodel.cn/api/paas/v4/chat/completions`，模型 `glm-4-flash`。
- API Key 从 `local.properties` 读取（`LLM_API_KEY=...`），**绝不入库**。
- 评分 25% 要求：System Prompt 有设计感（角色+输出格式约束+领域知识）、结构化输出、多轮上下文保持。

### 7.2 数据源（StockRepository 抽象）
- `StockRepository` 接口：`fun getQuotes(): List<Stock>`、`fun getDetail(code): Stock`、`fun getWatchlist(): List<Stock>`。
- `MockStockSource` 实现：内置若干股票 + 3 个大盘指数（`isIndex=true`），`trend` 给一组采样点。
- P4 用 `TushareSource` 实现同接口替换，拿真实数据加分。

### 7.3 组件清单（对应 25% 代码质量分）
| 组件 | 优先级 | 说明 |
|------|--------|------|
| `KRTable`(KRStockList) | P1 ✅ | 行内展开已做 |
| `KRStockBadge` | P1 ✅ | 涨跌徽章 |
| `KRTrendChart` | P1 ⬜ | 折线/分时，展开行迷你图+详情页大图共用 |
| `KRStockCard` | P1 ⬜ | 股票信息卡片 |
| `KRMarkdown` | P3 ⬜ | Markdown 渲染（或引用 Kuikly-contrib/KuiklyMarkdown） |
| `KRChatBubble` | P3 ⬜ | 聊天气泡 |

---

## 八、验收与评分（对照自查）

四大维度：功能 40% / 代码 25% / AI 25% / 加分 10%。
交付三件套（缺一不可）：① 可编译运行代码 ② 说明文档（README+架构+亮点）③ 演示视频 3-5 分钟。

自查清单：
- [ ] Task01 三模块齐全可运行（列表/详情/AI 分析）
- [ ] Task02 三模块齐全可运行（聊天/渲染/承接页）
- [ ] 自研表格/卡片组件可脱离业务复用
- [ ] LLM 真实可用（非写死）
- [ ] Prompt 有设计感 + 结构化输出 + 多轮上下文
- [ ] 真实数据源（加分）
- [ ] README+架构+亮点文档
- [ ] 演示视频

---

## 九、运行与构建

- 用 **Android Studio**（≥ 2024.2.1）+ Kuikly 插件（≥ 1.1.0），Gradle JDK 切到 **17**。
- 打开 `KuiklyStock` 根目录，Run `androidApp`。
- Windows 不做 iOS：在 `shared/build.gradle.kts` 注释掉 iOS 相关配置避免编译报错。
- 首次同步后先把 `core/Stock.kt`、`components/KRStockBadge`、`components/KRTable` 的编译报错修掉，再继续写新组件。

---

## 十、给接手 AI 的最后提醒

1. 先编译、先编译、先编译——已写的 3 个文件没验证过。
2. 所有 UI 字符串、颜色、间距参照中国股市惯例（红涨绿跌）。
3. 每次给用户的改动尽量小步提交，便于他 `git push`。
4. 遇到 Kuikly API 不确定，优先 `Read` 仓库内 `RouterPage.kt` / `Page/HelloWorldPage.kt` / `base/BasePager.kt` 这三个模板文件，不要凭空猜。
5. 完整规划原文见本文档各章；如需更细的历史讨论，用户本地 `RhinoBrid/.workbuddy/document/项目规划书.md` 有 v1.2 规划书（不在本仓库，仅供参考）。

---

*本 README 同时承担「项目战略书 + AI 接手指南」角色，由前序 AI 于 2026-08-27 整理写入，供后续 AI 或本人无缝续做。*

---

## 附：截至 2026-09-04 的实际进度快照（接手 AI 先看这里）

> 上文战略/排期/目录为 8/27 初版，以下为 9/4 实测状态，两者冲突以下文为准。

### 当前进度
- Task01（AI 行情原型）**已收官**：行情页(大盘/板块/个股)/自选/详情/板块详情/我的 全链路可演示，假数据与"待接入"空壳已清。
- Task02（AI 股票问答）**核心已齐**（均在本地提交、未 push、待真机验证）：AI 回答 Markdown 富文本渲染、提及股横滚窄卡+真实分时、思考态三点动画、选取文字全屏原生选字、富文本字号跟随设置。
- 源码 40 个 .kt（shared/commonMain 下），另有宿主 androidApp 层 KRBridgeModule（选字/行情桥）。
- **运行验证**：用户只在 Android Studio Build→Rebuild→Run 真机验证；不要主动打 debug APK。

### 目录结构（9/4 实际）
```
shared/src/commonMain/kotlin/com/zeriehan/kuiklystock/
├── base/             BasePager/BridgeModule/Utils/IPagerIdKtx
├── core/             Stock(数据+配色)、StockData(行情门面:mock+东财真实)、StockBrief(派生口径)、
│                     StockMention(AI文本股票识别)、KRMarkdown(Markdown解析器)、UserSettings(个性化)、
│                     UserStockStore(自选/隐藏)、llm/ (ChatStore/GLMFlashClient/LLM/Mock/AIJobCenter 等)
├── components/       KRStockBadge/KRStockList(KRTable行内展开)/KRTrendChart(可配高,吃真实分时)/
│                     KRKLineChart/KRMiniTimeSharing/KRStockCard(AI提及股横滚窄卡)/KRMarkdown(渲染器)/KRRefreshButton
├── app/
│   ├── main/         MainTabPager(四Tab 主框架,含行情/自选/我的Tab)
│   ├── chat/         ChatPage(AI对话:富文本+提及股卡+思考态+多选+长按)
│   ├── detail/       StockDetailPage/SectorDetailPage(承接页,含AI分析/K线/分时)
│   ├── mine/         AppearancePage/ExpandSettingsPage/HiddenStocksPage
│   └── quotes/       HeatPoolPage(行情池)/QuotesPage(旧验证页,无入口,勿用)
```
- 路由页 @Page: Chat/StockDetail/SectorDetail/MainTab/Appearance/ExpandSettings/HiddenStocks/HeatPool（含模板自带 HelloWorld/Router）。

### 编译
- `./gradlew --stop` 后 `./gradlew :shared:compileDebugKotlinAndroid`（判 UP-TO-DATE 加 `--rerun-tasks`）。
- 宿主选字改动：`./gradlew :androidApp:compileDebugKotlin`（不打包 APK）。
- git push 走 SSH（`GIT_SSH_COMMAND=...` + `git push git@github.com:zeriehan/KuiklyStock.git main`）；当前本地领先若干提交未推。

### 组件状态（9/4，对照 7.3 表）
| 组件 | 状态 |
|------|------|
| KRTable(KRStockList) | ✅ 行内展开 3 页轮播(分时/AI分析/简况) |
| KRStockBadge | ✅ |
| KRTrendChart | ✅ 可 chartHeight 配高；realPoints 喂真实分时；紧凑迷你走势首选 |
| KRKLineChart / KRMiniTimeSharing | ✅ 详情页 K线 / 行内分时 |
| KRStockCard | ✅ AI 提及股**横滚窄卡** |
| KRMarkdown | ✅ 解析器(core)+渲染器(components)，基于 Kuikly 原生 RichText |
| KRChatBubble | ⬜ 空目录占位，气泡已在 ChatPage 内联实现，未独立成组件 |

### Task02 关键实现速览（供演示/续做）
- **富文本**：KRMarkdown 解析 Markdown→块+行内 token；渲染用 Kuikly `RichText+Span`(跨行/自动换行/行内 click)，块=标题/段落/列表/引用/代码；股票名→主题色可点跳详情。字号走 `UserSettings.fs`。
- **提及股卡片**：AI 文本现扫 `StockMention.extract` 命中池内股→回答下方横滚窄卡(真实分时小走势)。详情页 AI 分析仍走纯文本(刻意不混富文本)。
- **思考态**：动态三点(thinkingDot+setTimeout 自递归)。
- **数据**：StockData 门面 = mock 种子 + 东财真实并入；K线走腾讯、分时走东财。
