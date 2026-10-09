# Bilicraft Handheld Stocks

Bilicraft Handheld Stocks（股市面板）是为 **Bilicraft Handheld** 制作的外部 Compose 插件。它把帕拉伦股市行情、K 线分析、玩家资金与持股查询以及股票交易整合在同一个移动端面板中。

当前版本：`0.2.5`

## 功能

- 采用手机证券应用式四栏布局：行情、自选、持仓、分析，并使用独立股票详情页承载图表和交易。
- 行情页提供市场温度、涨跌家数、搜索以及默认榜、涨幅榜、跌幅榜、价格榜；默认榜按公司 ID 排列，各榜均将破产公司置底。
- 市场温度排除破产公司，股票行直接展示可流通股数与公司状态。
- 自选股按公司 ID 保存在插件本地，可在列表和详情页一键添加或移除。
- 持仓页集中展示总资产、持仓市值、可用资金、累计收益、仓位与逐股盈亏。
- 分析页根据已记录成本计算累计收益率、胜率、最佳/最弱持仓和持仓分布。
- 插件从实际成交和持仓同步开始建立本地盈亏账本，不使用历史 K 线倒推或补造以前的收益记录。
- 收益走势支持 7 日、30 日、90 日、1 年和全部记录，并按相邻日累计盈亏差额展示当日收益；收益日历支持切换月份、查看当日盈亏金额和百分比，并提供月度汇总。收益率以当次同步的总资产（钱包余额加持仓市值）为分母。
- 分析页可清理当前账号的本地收益历史；持仓页可清理所有账号的本地买入成本和收益记录。
- 全局采用绿涨红跌视觉语义，货币单位统一显示为“元”。
- 获取公司列表、市场健康状态和多周期 K 线数据。
- 支持“单次采集”（接口周期 `15m`）、`1h`、`4h`、`24h` K 线周期。
- 首次请求展示服务端返回的完整 K 线分段；平移或缩放到左边界后按需加载更早历史。
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
- 按当前登录账号 UUID 在插件本地分别保存本插件买入记录，计算购买均价、单股和整体持有收益及涨跌幅。

## 数据与隐私

- 市场数据默认来自 `https://www.pleasance.icu`。
- 资金、持股和交易通过 Bilicraft Handheld 插件宿主发送 Minecraft 聊天命令完成。
- 插件不会持久缓存玩家资金或服务器持股；每次进入股票页面时会重新查询。购买均价和盈亏快照仅记录本插件可确认的数据，并按当前账号 UUID 隔离。
- 历史空白日期不会通过 K 线或插值补齐；卖出盈亏使用交易前服务器实时查价，持仓盈亏快照使用服务器持仓命令返回的实际市值。
- 插件不保存公司 ID 映射表，ID 与公司关系以网页接口数据为准。

## 项目结构

```text
.
├── plugin-api/              # 构建所需的 Bilicraft Handheld 插件 API 快照
├── src/main/java/com/bilicraft/handheld/stockplugin/
│   ├── StockCommandGateway.kt
│   ├── StockMarketPlugin.kt
│   ├── StockMarketRepository.kt
│   ├── StockModels.kt
│   ├── PurchaseRecordStore.kt
│   ├── ProfitHistoryStore.kt
│   └── WatchlistStore.kt
├── build.gradle.kts         # Android Library 与 .bhplugin 打包任务
├── settings.gradle.kts
└── gradle.properties
```

## 构建环境

- JDK 17
- Android SDK 34
- Android Build Tools（需要包含 `d8`）
- Gradle Wrapper 9.3

确保 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT` 指向 Android SDK，也可以在项目根目录创建不提交到 Git 的 `local.properties`：

```properties
sdk.dir=/path/to/android-sdk
```

然后在项目根目录执行：

```bash
./gradlew packageBhPlugin
```

生成文件位于：

```text
build/outputs/bhplugin/stock-market-0.2.5.bhplugin
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

- `/bal`
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
unzip -t build/outputs/bhplugin/stock-market-0.2.5.bhplugin
unzip -p build/outputs/bhplugin/stock-market-0.2.5.bhplugin plugin.json
sha256sum build/outputs/bhplugin/stock-market-0.2.5.bhplugin
```

## 许可

仅供学习研究。Minecraft 是 Mojang / Microsoft 的商标。
