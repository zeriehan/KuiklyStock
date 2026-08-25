# KuiklyStock

基于 [Kuikly](https://kuikly.tds.qq.com/) 的 AI 股票行情与问答 Demo，参赛作品（2026 腾讯犀牛鸟对外开源人才计划 · KuiklyUI 实战）。

## 功能概览

四 Tab 主框架：

1. **AI 聊天管理**（Task 02）：会话列表（每只股票可建独立会话）、每日推荐股票
2. **行情**（Task 01）：大盘指数 + 个股列表，行内展开看走势，点击进详情
3. **自选股**：复用行情列表组件，数据源换成自选池
4. **我的**：LLM API Key 配置等设置

详情页 AI 交互（A+C 组合）：页面内 AI 分析卡片 + “深入聊聊这只股票”跳转该股票专属会话。

## 环境要求

- JDK 17
- Android Studio（安装 Kuikly 插件 ≥ 1.1.0、Kotlin、Kotlin MultiPlatform 插件）
- Android SDK
- 将 Gradle JDK 切换为 17（Android Studio 2024.2.1+ 默认 JDK21 不兼容）

## 运行

1. 用 Android Studio 打开本工程（通过 Kuikly 插件“新建 Kuikly 业务工程”生成）
2. Windows 不做 iOS：在 `shared/build.gradle.kts` 注释掉 iOS 相关配置
3. 运行 Android App 模块

## 目录结构（规划）

```
app/        四 Tab 业务模块（chat / quotes / watchlist / profile）
components/ 自研通用组件（KRTable / KRStockBadge / KRTrendChart / KRMarkdown / KRChatBubble）
core/       基础能力（LLMClient / StockRepository / Mock 数据源）
```

## 配置说明

- **LLM API Key**：放在 `local.properties`（已 gitignore），不要提交到仓库。
  例如：`GLM_API_KEY=your_key_here`
- **应用包名**：建议生成工程时改为自有标识，如 `com.zeriehan.kuiklystock`，避免与官方 demo 包名冲突。

## 开发进度

按 5 阶段排期推进（P0 地基 → P1 组件 → P2 Task01 → P3 Task02 → P4 交付）。
