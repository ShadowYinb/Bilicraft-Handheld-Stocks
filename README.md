# Bilicraft Handheld Stocks

Bilicraft Handheld Stocks（股市面板）是为 **Bilicraft Handheld** 制作的外部 Compose 插件。它把帕拉伦股市行情、K 线分析、玩家资金与持股查询以及股票交易整合在同一个移动端面板中。

当前版本：`0.1.9`

## 功能

- 获取公司列表、市场健康状态和多周期 K 线数据。
- 支持 `15m`、`1h`、`4h`、`24h` K 线周期。
- 蜡烛图可切换收盘价折线显示。
- 折线、K 线和售出/回购点的显示设置会在切换股票及退出应用后保留。
- K 线支持横向拖动、双指缩放和单击十字线。
- K 线下方展示剩余股数趋势，并与 K 线同步缩放、横移和十字线定位。
- 根据连续 15 分钟剩余股数变化，在 K 线上绘制大小分级的售出/回购点，选中后显示具体股数。
- 根据当前可见范围显示最高价、最低价及自适应价格/时间刻度。
- K 线区域纵向滑动可自然滚动外层页面。
- 股票列表独立纵向滚动，边界滑动不会带动外层页面。
- 股票 ID 直接使用网页公司接口返回的 `market_id`，列表显示与交易命令保持一致。
- 进入股票页面时自动查询玩家资金和持股。
- 支持主动查询资金、持股和当前公司的实时价格。
- 买入或卖出成功后自动刷新资金与持股。
- 股票列表同步显示对应公司的持股数量。

## 数据与隐私

- 市场数据默认来自 `https://www.pleasance.icu`。
- 资金、持股和交易通过 Bilicraft Handheld 插件宿主发送 Minecraft 聊天命令完成。
- 插件不会持久缓存玩家资金或持股；每次进入股票页面时会重新查询。
- 插件不保存公司 ID 映射表，ID 与公司关系以网页接口数据为准。

## 项目结构

```text
.
├── plugin-api/              # 构建所需的 Bilicraft Handheld 插件 API 快照
├── src/main/java/com/bilicraft/handheld/stockplugin/
│   ├── StockCommandGateway.kt
│   ├── StockMarketPlugin.kt
│   ├── StockMarketRepository.kt
│   └── StockModels.kt
├── build.gradle.kts         # Android Library 与 .bhplugin 打包任务
├── settings.gradle.kts
└── gradle.properties
```

## 构建环境

- JDK 17
- Android SDK 34
- Android Build Tools（需要包含 `d8`）
- Gradle 8.9（项目未附带 Gradle Wrapper）

确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 Android SDK，也可以在项目根目录创建不提交到 Git 的 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

然后在项目根目录执行：

```bash
gradle --no-daemon clean packageBhPlugin
```

生成文件位于：

```text
build/outputs/bhplugin/stock-market-0.1.9.bhplugin
```

`.bhplugin` 是 ZIP 格式的插件包，包含：

```text
plugin.json
classes.dex
```

## 安装

1. 构建或从 GitHub Releases 下载 `.bhplugin` 文件。
2. 在支持外部插件的 Bilicraft Handheld 中导入插件。
3. 连接 Minecraft 服务器后打开“股市面板”。
4. 交易使用网页接口返回的股票 ID，无需手动同步映射。

## 服务器命令

插件当前依赖服务器提供以下聊天命令及其中文响应格式：

- `/money`
- `/invest portfolio`
- `/invest company info <id>`
- `/invest buy <id> <数量>`
- `/invest sell <id> <数量>`

如果服务器修改了命令输出文本，`StockCommandGateway.kt` 中的解析规则也需要同步调整。

## 开发说明

- 插件入口：`com.bilicraft.handheld.stockplugin.StockMarketPlugin`
- 插件 ID：`stock-market-dashboard`
- 插件 API 版本：`1`
- Android 最低版本：API 24
- UI：Jetpack Compose Material 3
- 网络请求由宿主的 `BhPluginHost.httpGet` 执行。

## 发布检查

建议发布前至少运行：

```bash
gradle --no-daemon clean packageBhPlugin
unzip -t build/outputs/bhplugin/stock-market-0.1.9.bhplugin
unzip -p build/outputs/bhplugin/stock-market-0.1.9.bhplugin plugin.json
sha256sum build/outputs/bhplugin/stock-market-0.1.9.bhplugin
```

## 许可

仅供学习研究。Minecraft 是 Mojang / Microsoft 的商标。
