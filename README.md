# KuiklyStock — AI 股票行情 + AI 股票问答 App

> **参赛**：2026 腾讯犀牛鸟开源人才计划 · KuiklyUI 实战（截止 2026-09-14）
> **形态**：一个 App，底部四个 Tab，AI 聊天 / 行情 / 自选 / 我的（冷启动停「行情」）
> **文档导航**：README（总览 · 怎么跑）· `架构说明.md`（技术架构）· `亮点与创新.md`（可讲亮点）· `比赛自评与答辩要点.md`（评分自证）· `演示口播脚本.md` + `演示镜头时间轴录制卡.md`（演示视频用）

---

## 一、当前状态（2026-09-10 · 已收工）

**两大任务都已收官，真机跑通（安卓由 vivo 验证），演示视频已录。**

- **Task 01 · AI 行情原型**：行情（大盘 / 板块 / 个股榜单）、自选、个股与板块详情、多股对比、开始选股，全链路真实可演示。
- **Task 02 · AI 股票问答**：真流式逐字输出、富文本、提及股可点承接、量化徽章、图表选点问 AI、开始选股，闭环可演示。

**几个主要能力**（细节见 `亮点与创新.md`）：

- **真实数据**：腾讯（报价 / K线 / 分时）+ 新浪（榜单 / 板块 / 成分）+ 东方财富（F10）；mock 只在离线兜底，且明确标注。
- **真实大模型**：智谱 GLM Flash 免费池，SSE 流式、候选模型自动降级、超时兜底；没配 Key 或全部限流时回退本地 Mock。
- **AI Agent**：用自然语言直接操作 App，改主题色、改字号、换涨跌配色、开关迷你卡、设隐藏恢复天数、恢复隐藏股、加自选 / 对比 / 设预警。协议层的 JSON 容错、别名映射、语义兜底都已闭环。
- **图表实时问股**：分时 / K 线点选点位出十字光标，带着这个点位的上下文跳聊天自动问 AI。
- **量化结论徽章**：AI 分析直接给「操作建议（买 / 持 / 卖）+ 风险（低 / 中 / 高）」两个徽章。

---

## 二、怎么跑 / 构建

- **Android Studio**（≥ 2024.2.1）+ Kuikly 插件，Gradle JDK **17**。打开 `KuiklyStock` 根目录 → Run `androidApp`，入口 `MainTab`。
- **验证方式**：AS 里 Build → Rebuild → Run 真机。
- **命令行编译**：先 `./gradlew --stop`，再 `./gradlew :shared:compileDebugKotlinAndroid`（判 UP-TO-DATE 时加 `--rerun-tasks`）；改宿主用 `:androidApp:compileDebugKotlin`。

### 克隆下来怎么跑（换台机器 / 别人接手）

1. **前置**：JDK **17**；Android SDK（`compileSdk 34` + 对应 build-tools）；Android Studio（可选，命令行也能构建）。首次构建要联网拉 Gradle 8.5 / AGP / Kuikly 依赖。
2. **自己建 `local.properties`**（这个文件不入库，放在仓库根目录）：
   ```properties
   sdk.dir=/path/to/Android/Sdk   # Windows 如 D:\\Android\\Sdk；也可改用环境变量 ANDROID_HOME
   GLM_API_KEY=你的智谱Key         # 可选：不填也能编译运行，AI 自动走本地兜底，界面和交互不受影响
   ```
   （Key 也可以用环境变量 `GLM_API_KEY` 传；源码里没有任何密钥。）
3. **构建 / 运行**：根目录 `./gradlew :androidApp:assembleDebug`，产物在 `androidApp/build/outputs/apk/debug/`；或者直接用 AS 打开 Run。
   - macOS / Linux 若报 `permission denied`，执行 `chmod +x gradlew`（或 `sh gradlew ...`）。
4. **要签名包**：AS → Build → Generate Signed Bundle / APK，用自己的 keystore（不入库）。
5. **要不要真 AI**：填了 `GLM_API_KEY` 就走真模型（glm-4-flash 优先，失败自动降级）；没填或者全失败就回退本地兜底，不会崩，也不会假装是真 AI。

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
    └── quotes/       HeatPoolPage / QuotesPage
```

**路由页（@Page，KSP 自动注册）**：`MainTab` / `QuotesPage` / `StockDetail` / `SectorDetail` / `Chat` / `HeatPool` / `StockCompare` / `StockPicker` / `Appearance` / `ExpandSettings` / `HiddenStocks` / `Credits` / `FullText`。

---

## 四、组件速览（自研可复用，数据驱动）

| 组件 | 职责 |
| --- | --- |
| KRStockList（KRTable） | 行情 / 自选列表 + 行内展开分页轮播（分时 / AI 分析 / 简况 / 基本面，按 `UserSettings.expand` 开关） |
| KRKLineChart / KRTrendChart / KRMiniTimeSharing | 专业 K 线（含指标 / 缩放）/ 迷你走势 / 迷你分时 |
| KRChatMiniChart | AI 回复内嵌迷你走势图（分时 / 日 / 周 / 月 / 年 K 可切）+ 十字光标选点 |
| KRMarkdown | Markdown 富文本渲染（RichText + Span，股票名可点） |
| KRStockCard / KRStockBadge / KRRefreshButton | 提及股窄卡 / 涨跌徽标 / 下拉刷新 |
| AiVerdict | AI 结论「风险 + 买卖」双徽章的解析与渲染 |

---

## 五、关键工程约定（接手 / 续做必读）

- **涨红跌绿**：涨 `0xFFE54D42`、跌 `0xFF1ABE5B`、平灰。配色统一走 `StockColor.text()` / `UserSettings.up/down()`，别写死。
- **源码零密钥**：GLM Key 走 `local.properties` → `BuildConfig.GLM_API_KEY`，不入库。
- **个性化手动和 AI 都能改**：主题色、字号、涨跌配色、迷你卡组件可在「我的 → 外观 / 迷你卡片」改，也能直接让 AI 改（见 Agent）。
- **板块源是新浪**：板块 code 形如 `new_/gn_`。凡对板块 code 做前缀判断的地方都要匹配新浪格式，别假设东财的 `BK`。
- **响应式**：`observable` 变了只重跑读过它的 `vif`/`attr` 闭包，不重跑 `body`。行内「是否隐藏 / 选中」这类判断要在 attr 闭包里现读 observable，别提到局部 val。
- **Input 设初值**：一律用声明式 `attr { text(...) }`，别依赖 ref 时序（细节见 `架构说明.md`）。
- **大列表别整容器高频重建**：行情页按「大盘 / 板块 / 个股」拆常驻块，用 `flex` + `opacity` 显隐；200 个板块的列表不跟着每次报价 DataSync 重建。

---

## 六、功能演进（里程碑，逐条改动看 commit log）

- **9/4 前**：工程脚手架 → 组件库成型 → Task01（行情 + 详情 + AI 分析）全链路可演示。
- **9/5-9/6**：Task02 收尾与延伸，真流式、富文本、提及股承接、量化徽章、选点问 K 线、开始选股、输入草稿；行情 / 详情 / 对比页接真实数据，补 F10 基本面与迷你卡第 4 页；「多股对比」（上股票轮播 + 下对比 AI 聊天 + 独立选股页）。
- **9/7**：Agent 协议闭环（模型决策 + `⟦TOOL⟧{json}` + JSON 容错 / 别名映射 / 语义二次校准 / 失败短路）；预警弹窗改「4 行独立输入一次保存」，回显改用声明式 `text()`；「我的」加「关于与致谢」页。
- **9/8**：设置项全 AI 化，字号、涨跌配色、迷你卡 4 个组件开关、隐藏恢复天数、恢复隐藏股都能让 AI 调；**删掉实验性的深色模式**（数据层、各页 UI、Agent 触发词全清）。
- **9/8-9/9**：「不感兴趣」灰幕即时生效、加自选标记后禁展开；App 图标换自研方图（纯 legacy 多密度 png，去掉 adaptive 避免比例问题）。
- **9/9-9/10 收尾**：对比页 AI 接入 Agent 与流式；修掉打包时的 dexing 崩溃（R8 版本覆盖）、模型把走势图写成图片链接、按钮文字不居中这几处问题；补齐「克隆下来怎么跑」。

---

## 七、已知限制（如实说明）

- **键盘**：沉浸式全屏下，输入栏靠**输入栏后面的占位 Spacer** 随键盘顶起（不是整页重排，属 Kuikly / 沉浸式系统行为）。
- **北交所 / 新股的历史日 K**：免费行情源不提供，详情页该类股如实占位（分时和实时价是真的），不拿假波浪冒充。
- **AI 快慢取决于 GLM 免费池**：高峰期可能慢或限流（会降级候选模型 / 超时回 Mock）。录演示建议挑 GLM 通畅的时候。
- **只有真实 GLM 才走流式**：Mock 是本地即时生成的，没有中间态。

---

*README 讲总览和运行；技术架构看 `架构说明.md`，可讲亮点看 `亮点与创新.md`，评分自证看 `比赛自评与答辩要点.md`。*
