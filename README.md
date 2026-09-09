# KuiklyStock — AI 股票行情 + AI 股票问答 App

> **参赛项目**：2026 腾讯犀牛鸟开源人才计划 · KuiklyUI 实战（截止 2026-09-14）
> **形态**：一个 App，底部四 Tab —— **AI 聊天 / 行情 / 自选 / 我的**（默认停行情）
> **本仓库文档导航**：README（总览/当前状态/怎么跑）· `架构说明.md`（技术架构）· `亮点与创新.md`（可讲亮点）· `比赛自评与答辩要点.md`（评分自证）· `演示口播脚本.md` + `演示镜头时间轴录制卡.md`（演示视频）

---

## 一、当前状态（截至 2026-09-09）

**两大任务均已收官，真机跑通（华为/vivo 验证）：**

- **Task 01 · AI 行情原型**：行情（大盘 / 板块 / 个股榜单）/ 自选 / 个股与板块详情 / 多股对比 / 开始选股，全链路真实可演示。
- **Task 02 · AI 股票问答**：真流式逐字输出、富文本、提及股可点承接、量化徽章、图表"选一点问 AI"、开始选股，闭环可演示。

**核心能力**（点到即止，详见 `亮点与创新.md`）：
- **真实数据**：腾讯（报价/K线/分时）+ 新浪（榜单/板块/成分）+ 东方财富（F10）；mock 仅离线兜底并诚实标注。
- **真实大模型**：智谱 GLM Flash 免费池，SSE 真流式、候选模型降级、15s 超时兜底；无 Key/限流回退本地 Mock。
- **AI Agent（最强亮点）**：自然语言直接执行 App 操作——改主题色、改字号/涨跌配色、开关迷你卡、设隐藏恢复天数、恢复隐藏股、加自选/对比/设预警；协议层 JSON 容错 + 别名映射 + 语义兜底全闭环。
- **图表"看哪根问哪根"**：分时/K 线点选点位 → 十字光标 → 带着点位上下文跳聊天自动问 AI。
- **量化结论徽章**：AI 分析直给"操作建议(买/持/卖) + 风险(低/中/高)"双徽章。

> 注：早前实验性的**深色模式已移除**（做的效果不佳），外观设置现为 主题色 / 字体大小 / 涨跌配色（红涨绿跌 ↔ 红跌绿涨）。

---

## 二、怎么跑 / 构建

- 用 **Android Studio**（≥ 2024.2.1）+ Kuikly 插件，Gradle JDK **17**。打开 `KuiklyStock` 根目录 → Run `androidApp`，入口 `MainTab`。
- **运行验证**：只在 Android Studio Build→Rebuild→Run 真机验证（华为/vivo），不要主动打 debug APK。
- **真实 GLM**：`local.properties` 写 `GLM_API_KEY=你的key`（已 gitignore）；不写则自动用离线 Mock，演示不受阻。
- **命令行编译**：`./gradlew --stop` 后 `./gradlew :shared:compileDebugKotlinAndroid`（判 UP-TO-DATE 加 `--rerun-tasks`）；宿主改动用 `:androidApp:compileDebugKotlin`。
- **git push 走 SSH**：`GIT_SSH_COMMAND="ssh -o BatchMode=yes -o StrictHostKeyChecking=accept-new" git push git@github.com:zeriehan/KuiklyStock.git main`。

---

## 三、目录结构（shared 业务代码）

```
shared/src/commonMain/kotlin/com/zeriehan/kuiklystock/   （shared 49 个 .kt；宿主 androidApp 另 13 个 .kt）
├── base/             BasePager / BridgeModule(shared→宿主 RPC) / Utils
├── core/             Stock(模型+配色)/StockData(行情门面)/StockBrief/StockMention/KRMarkdown(解析器)
│   │                 /UserSettings(个性化:主题色/字号/涨跌配色)/UserStockStore(自选/隐藏)/AlertStore
│   └── llm/          LLMClient / GLMFlashClient / MockLLMClient / AIJobCenter / ChatStore / AIAnalysisStore / AgentChat(+AgentActions)
├── components/       KRStockBadge / KRStockList(行内展开) / KRKLineChart / KRTrendChart / KRMiniTimeSharing /
│                     KRStockCard / KRMarkdownView / KRChatMiniChart / KRRefreshButton / AiVerdict
└── app/
    ├── main/         MainTabPager(四 Tab 主框架 + 行情/自选/我的内容 + AI Agent 操作入口)
    ├── chat/         ChatPage(AI 对话)
    ├── detail/       StockDetailPage / SectorDetailPage
    ├── compare/      StockComparePage / StockPickerPage
    ├── mine/         AppearancePage / ExpandSettingsPage / HiddenStocksPage / CreditsPage / FullTextPage
    └── quotes/       HeatPoolPage / QuotesPage(旧验证页,无入口)
```

**路由页（@Page，KSP 自动注册）**：`MainTab` / `QuotesPage` / `StockDetail` / `SectorDetail` / `Chat` / `HeatPool` / `StockCompare` / `StockPicker` / `Appearance` / `ExpandSettings` / `HiddenStocks` / `Credits` / `FullText`。

---

## 四、组件速览（自研可复用，数据驱动）

| 组件 | 职责 |
|---|---|
| KRStockList（KRTable） | 行情/自选列表 + 行内展开分页轮播（分时/AI分析/简况/基本面，按 `UserSettings.expand` 开关）|
| KRKLineChart / KRTrendChart / KRMiniTimeSharing | 专业 K线(+指标/缩放) / 迷你走势 / 迷你分时 |
| KRChatMiniChart | AI 回复内嵌迷你走势图（分时/日/周/月/年K 可切）+ 十字光标选点 |
| KRMarkdown | Markdown 富文本渲染（RichText+Span，含可点股票名）|
| KRStockCard / KRStockBadge / KRRefreshButton | 提及股窄卡 / 涨跌徽标 / 下拉刷新 |
| AiVerdict | AI 结论「风险+买卖」双徽章解析与渲染 |

---

## 五、关键工程约定（接手/续做必读）

- **涨红跌绿**（A股惯例）：涨 `0xFFE54D42` 红、跌 `0xFF1ABE5B` 绿、平灰；配色经 `StockColor.text()` / `UserSettings.up/down()` 统一读取，勿写死。
- **源码零密钥**：GLM Key 走 `local.properties` → `BuildConfig.GLM_API_KEY`，绝不入库。
- **涨跌配色/主题色等个性化可切换**：主题色、字号、涨跌配色、迷你卡组件均可在「我的→外观/迷你卡片」手动改，**也可让 AI 改**（见 Agent）。
- **数据源**：东财板块曾迁新浪（板块 code 为 `new_/gn_`），凡对板块 code 做前缀判断处须匹配新浪格式，勿假设东财 `BK`。
- **响应式铁律**：`observable` 变化只重跑读了它的 `vif/attr` 闭包，不重跑 `body`；行内"是否隐藏/选中"等状态判断须在 attr 闭包内**现读** observable，勿提局部 val。
- **Input 设初值/回显**：一律用声明式 `attr { text(...) }`，勿依赖 ref 时序 hack（详见 `架构说明.md`）。
- **大列表避免整容器高频重建**：行情页按"大盘/板块/个股"拆常驻块 + `flex/opacity` 显隐；200 个板块的列表不随每次报价 DataSync 重建（详见 `亮点与创新.md` 与工程记忆）。

---

## 六、功能演进记录（里程碑式，细到 commit 的改动见各 commit log / `亮点与创新.md`）

- **9/4 前**：工程脚手架 → 组件库成型 → Task01（行情+详情+AI 分析）全链路可演示。
- **9/5-9/6（Task02 收尾 + 延伸）**：真流式、富文本、提及股承接、量化徽章、选点问 K 线、开始选股、输入草稿；行情/详情/对比页真实数据 + F10 基本面 + 迷你卡第 4 页；"多股对比"（上股票轮播 + 下对比 AI 聊天 + 独立选股页）。
- **9/7（Agent 收尾 + 预警/致谢）**：AI Agent 协议全闭环（模型决策 + `⟦TOOL⟧{json}` + JSON 容错/别名映射/语义二次校准/失败短路）；预警弹窗改"4 行独立输入一次保存"+ 回显用声明式 `text()`；我的页加「关于与致谢」Credits 页。
- **9/8（设置项全 AI 化 + 删深色）**：设置里所有手动项改为 AI 可调用——字体大小 / 涨跌配色 / 迷你卡 4 组件开关 / 隐藏恢复天数 / 恢复隐藏股（原有主题色已支持）；**删除实验性深色模式**（数据层 + 各页 UI + Agent 触发词全清）。
- **9/8-9/9（体验修复）**："不感兴趣"灰幕即时生效 + 自选标记后改灰幕禁展开（不再消失）；**App 图标换自研方图**（纯 legacy 多密度 png，移除 adaptive 避免比例问题）。

---

## 七、已知限制 / 诚实备注

- **键盘**：沉浸式全屏下，输入栏通过**输入栏后置占位 Spacer**随键盘顶起（非整页重排属 Kuikly/沉浸式系统行为）。
- **北交所/新股历史日K**：免费行情源不提供，详情页该类股诚实占位（分时与实时价真实），不拿假波浪冒充。
- **AI 延迟取决于 GLM 免费池**：高峰可能慢/限流（自动降级候选模型 / 15s 超时回 Mock）。演示录制建议在 GLM 通畅时进行。
- **AI 回答仅真实 GLM 走流式**：Mock 为本地即时生成，无中间态。

---

*README 定位总览与运行；技术架构见 `架构说明.md`，可讲亮点见 `亮点与创新.md`，评分自证见 `比赛自评与答辩要点.md`。*
