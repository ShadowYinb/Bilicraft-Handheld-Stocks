# 程序描述

## 基本信息

- 程序名称：Bilicraft Handheld Stocks（股市面板）
- 当前版本：0.1.5
- 程序类型：Bilicraft Handheld 外部插件
- 运行平台：Android / Bilicraft Handheld
- 插件入口：`com.bilicraft.handheld.stockplugin.StockMarketPlugin`
- 插件 API：1

## 项目简介

Bilicraft Handheld Stocks 是一个面向帕拉伦 Minecraft 服务器玩家的移动股市操作面板。程序通过 Bilicraft Handheld 提供的插件接口展示外部市场行情，并通过宿主的聊天命令能力查询玩家账户和执行交易。

该程序的目标是在手机端提供清晰、触控友好的股市操作体验，让玩家能够在一个页面内完成公司选择、K 线分析、资金与持股查看、实时价格查询以及股票买卖。

## 核心模块

### 市场数据模块

通过 HTTPS 接口获取公司列表、K 线和数据服务状态。默认数据源为 `https://www.pleasance.icu`，网络访问由插件宿主代理执行。

### K 线交互模块

使用 Jetpack Compose Canvas 绘制蜡烛图、收盘价折线、价格轴、时间轴、视口最高/最低价和十字线。针对移动端手势进行了区分：横向拖动平移图表、双指缩放、单击选择数据点，纵向拖动则交由页面滚动处理。

### 游戏命令模块

通过 Minecraft 聊天命令查询资金、持股与公司实时数据，并执行买入、卖出。模块会监听服务器返回的聊天事件、识别成功或失败信息并解析数值结果。

### 股票映射模块

市场接口使用公司名称和网页 ID，服务器交易命令使用股票 ID。插件通过查询服务器公司信息建立二者映射，并将映射保存至插件数据目录，减少重复扫描。

## 数据更新策略

- 进入股票页面：自动查询资金和持股。
- 主动查询：立即用服务器最新结果更新页面。
- 买入或卖出成功：自动重新查询资金和持股。
- 交易失败：保留原有页面数据。
- 资金与持股：不写入持久化缓存。
- 股票 ID 映射：写入 `stock_company_ids.json`。

## 技术栈

- Kotlin 2.0.21
- Jetpack Compose / Material 3
- Kotlin Coroutines / StateFlow
- Kotlin Serialization
- Android Library Plugin 8.5.2
- Android API 24–34
- D8 DEX 编译与自定义 `.bhplugin` ZIP 打包

## 发布产物

Gradle 任务 `packageBhPlugin` 会生成 `stock-market-<version>.bhplugin`。插件包只包含运行所需的 `plugin.json` 和 `classes.dex`，可由 Bilicraft Handheld 的外部插件加载器导入。

## 兼容性说明

程序依赖 Bilicraft Handheld 插件 API v1，并依赖目标 Minecraft 服务器当前的 `/money` 与 `/invest` 命令格式。如果宿主 API、行情接口结构或服务器聊天文本发生变化，需要同步调整代码。
