package com.bilicraft.handheld.stockplugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bilicraft.handheld.pluginapi.BH_PLUGIN_API_VERSION
import com.bilicraft.handheld.pluginapi.BhPlugin
import com.bilicraft.handheld.pluginapi.BhPluginDescriptor
import com.bilicraft.handheld.pluginapi.BhPluginEntrypoint
import com.bilicraft.handheld.pluginapi.BhPluginHost
import com.bilicraft.handheld.pluginapi.BhPluginPanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

object StockMarketPlugin : BhPlugin {
    override val descriptor = BhPluginDescriptor(
        id = "stock-market-dashboard",
        name = "股市面板",
        description = "抓取网页股市数据，展示 K 线并生成 Minecraft 股票交易命令。",
        version = "0.2.5",
        minApiVersion = BH_PLUGIN_API_VERSION
    )

    override fun entrypoints(host: BhPluginHost): List<BhPluginEntrypoint> = listOf(
        BhPluginEntrypoint(
            id = "dashboard",
            title = "股市面板",
            description = "查看行情、资产和交易命令。",
            order = 10
        )
    )

    override fun createPanel(host: BhPluginHost): BhPluginPanel = object : BhPluginPanel {
        @Composable
        override fun Content(host: BhPluginHost, onClose: () -> Unit) {
            StockMarketTheme {
                StockMarketPanel(host = host, onClose = onClose)
            }
        }
    }

    override fun onLoad(host: BhPluginHost) {
        val player = host.currentPlayer
        host.log("股市插件已加载：玩家名=${player?.name}, UUID=${player?.uuid}")
    }

    override fun onUnload(host: BhPluginHost) {
        host.log("股市插件已卸载")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StockMarketPanel(host: BhPluginHost, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val repository = remember(host) { StockMarketRepository(host) }
    val gateway = remember(host) { StockCommandGateway(host) }
    val purchaseStore = remember(host) { PurchaseRecordStore(host) }
    val profitStore = remember(host) { ProfitHistoryStore(host) }
    val watchlistStore = remember(host) { WatchlistStore(host) }
    val currentPlayer = remember(host) { host.currentPlayer }
    var state by remember { mutableStateOf(StockUiState(loading = true)) }
    var selectedTab by remember { mutableStateOf(StockMainTab.Market) }
    var showDetail by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var marketSort by remember { mutableStateOf(MarketSort.Default) }
    var watchlistIds by remember(watchlistStore) { mutableStateOf(watchlistStore.load()) }
    var action by remember { mutableStateOf("buy") }
    var amount by remember { mutableStateOf("") }
    var chartViewport by remember { mutableStateOf(ChartViewport()) }
    var chartRequestSequence by remember { mutableStateOf(0L) }
    var chartDisplaySettings by remember(host) {
        mutableStateOf(loadChartDisplaySettings(host.pluginDataDir))
    }
    val initialPlayerState = remember(currentPlayer) {
        StockUiState(
            loading = true,
            playerUuid = currentPlayer?.uuid,
            playerName = currentPlayer?.name
        )
    }
    var playerStateInitialized by remember { mutableStateOf(false) }
    var confirmClearRecords by remember { mutableStateOf(false) }
    var confirmClearProfitHistory by remember { mutableStateOf(false) }
    var editProfitRecord by remember { mutableStateOf<ProfitHistoryRecord?>(null) }
    var editProfitValue by remember { mutableStateOf("") }

    fun toggleWatchlist(companyId: Int) {
        watchlistIds = if (companyId in watchlistIds) watchlistIds - companyId else watchlistIds + companyId
        runCatching { watchlistStore.save(watchlistIds) }
            .onFailure { host.log("保存自选股失败：${it.message}") }
    }

    fun updateChartDisplaySettings(settings: ChartDisplaySettings) {
        chartDisplaySettings = settings
        runCatching { saveChartDisplaySettings(host.pluginDataDir, settings) }
            .onFailure { host.log("保存 K 线显示设置失败：${it.message}") }
    }

    fun openProfitEditor(record: ProfitHistoryRecord) {
        val previous = state.profitHistory.sortedBy(ProfitHistoryRecord::timestamp)
            .takeWhile { it.day != record.day }.lastOrNull()?.totalPnl ?: 0.0
        editProfitRecord = record
        editProfitValue = (record.totalPnl - previous).toString()
    }

    fun saveProfitEdit() {
        val record = editProfitRecord ?: return
        val value = editProfitValue.toDoubleOrNull()
        val playerUuid = currentPlayer?.uuid
        if (value == null || !value.isFinite() || playerUuid == null) {
            state = state.copy(dialogMessage = "请输入有效的当日盈亏数字。")
            return
        }
        state = state.copy(profitHistory = profitStore.updateDailyPnl(playerUuid, record.day, value))
        editProfitRecord = null
    }

    fun selectedCompany(): StockCompany? = state.companies.firstOrNull { it.id == state.selectedCompanyId }

    fun showResult(result: StockCommandResult) {
        val message = when (result) {
            is StockCommandResult.Success -> result.message
            is StockCommandResult.Failure -> "操作失败：${result.reason}"
            is StockCommandResult.Timeout -> "操作超时：未收到服务器对 ${result.command} 的明确响应"
            is StockCommandResult.NotConnected -> result.reason
        }
        state = state.copy(dialogMessage = message)
    }

    fun recordProfitSnapshot(
        holdings: List<StockHolding>,
        purchaseRecords: Map<Int, PurchaseRecord>
    ): List<ProfitHistoryRecord> {
        val playerUuid = currentPlayer?.uuid ?: return state.profitHistory
        val metrics = portfolioMetrics(state.copy(holdings = holdings, purchaseRecords = purchaseRecords))
        val unrealizedPnl = metrics.rows.mapNotNull(PortfolioMetricRow::pnl).sum()
        return profitStore.recordSnapshot(
            playerUuid = playerUuid,
            unrealizedPnl = unrealizedPnl,
            currentTrackedCost = metrics.trackedCost,
            totalAssets = gateway.money.value?.plus(metrics.marketValue)
        )
    }

    suspend fun refreshPortfolio(showEmptyMessage: Boolean = false) {
        state = state.copy(portfolioLoading = true, holdings = emptyList())
        gateway.queryPortfolio()
            .onSuccess { holdings ->
                val activeHoldings = holdings.filter { it.shares > 0 }
                val records = currentPlayer?.uuid?.let(purchaseStore::load).orEmpty()
                val profitHistory = recordProfitSnapshot(activeHoldings, records)
                state = state.copy(
                    portfolioLoading = false,
                    holdings = activeHoldings,
                    purchaseRecords = records,
                    profitHistory = profitHistory,
                    dialogMessage = if (showEmptyMessage && activeHoldings.isEmpty()) "当前没有持股。" else state.dialogMessage
                )
            }
            .onFailure { error ->
                state = state.copy(
                    portfolioLoading = false,
                    dialogMessage = if (showEmptyMessage) "持股查询失败：${error.message}" else state.dialogMessage
                )
            }
    }

    fun isCurrentChartRequest(requestId: Long, companyId: Int, interval: String): Boolean =
        requestId == chartRequestSequence &&
            state.selectedCompanyId == companyId &&
            state.selectedInterval == interval

    fun reloadKline() {
        val companyId = state.selectedCompanyId ?: return
        val interval = state.selectedInterval
        val requestId = chartRequestSequence + 1
        chartRequestSequence = requestId
        state = state.copy(
            loading = true,
            kline = emptyList(),
            availableShares = emptyList(),
            chartSegmentStart = null,
            chartHasMore = false,
            chartLoadingMore = false,
            lastError = null
        )
        chartViewport = ChartViewport()
        scope.launch {
            runCatching { repository.fetchChartData(companyId, interval) }
                .onSuccess { data ->
                    if (!isCurrentChartRequest(requestId, companyId, interval)) return@onSuccess
                    state = state.copy(
                        loading = false,
                        kline = mergeKlinePoints(emptyList(), data.kline),
                        availableShares = mergeAvailableShares(emptyList(), data.availableShares),
                        chartSegmentStart = data.segmentStart,
                        chartHasMore = data.hasMore && data.segmentStart != null,
                        chartLoadingMore = false,
                        lastError = null
                    )
                }
                .onFailure { error ->
                    if (isCurrentChartRequest(requestId, companyId, interval)) {
                        state = state.copy(loading = false, lastError = error.message ?: "K线加载失败")
                    }
                }
        }
    }

    fun loadOlderChartSegment() {
        if (state.chartLoadingMore || !state.chartHasMore) return
        val companyId = state.selectedCompanyId ?: return
        val interval = state.selectedInterval
        val before = state.chartSegmentStart ?: return
        val requestId = chartRequestSequence
        state = state.copy(chartLoadingMore = true)
        scope.launch {
            runCatching { repository.fetchChartData(companyId, interval, before) }
                .onSuccess { data ->
                    if (!isCurrentChartRequest(requestId, companyId, interval)) return@onSuccess
                    val oldKline = state.kline
                    val mergedKline = mergeKlinePoints(oldKline, data.kline)
                    val oldFirstTime = oldKline.firstOrNull()?.time
                    val prependedCount = if (oldFirstTime == null) 0 else
                        mergedKline.count { it.time != null && it.time < oldFirstTime }
                    chartViewport = viewportAfterPrepend(
                        chartViewport,
                        oldKline.size,
                        mergedKline.size,
                        prependedCount
                    )
                    state = state.copy(
                        kline = mergedKline,
                        availableShares = mergeAvailableShares(state.availableShares, data.availableShares),
                        chartSegmentStart = data.segmentStart,
                        chartHasMore = data.hasMore && data.segmentStart != null,
                        chartLoadingMore = false,
                        lastError = null
                    )
                }
                .onFailure { error ->
                    if (isCurrentChartRequest(requestId, companyId, interval)) {
                        state = state.copy(
                            chartLoadingMore = false,
                            lastError = error.message ?: "加载更早 K 线失败"
                        )
                    }
                }
        }
    }

    fun openCompany(companyId: Int) {
        state = state.copy(
            selectedCompanyId = companyId,
            liveInfo = null,
            loading = true,
            lastError = null
        )
        showDetail = true
        reloadKline()
    }

    fun refreshAll() {
        val requestId = chartRequestSequence + 1
        chartRequestSequence = requestId
        val interval = state.selectedInterval
        state = state.copy(
            loading = true,
            lastError = null,
            kline = emptyList(),
            availableShares = emptyList(),
            chartSegmentStart = null,
            chartHasMore = false,
            chartLoadingMore = false
        )
        chartViewport = ChartViewport()
        scope.launch {
            try {
                val companies = repository.fetchCompanies().filter { it.latestPrice != null }
                if (requestId != chartRequestSequence) return@launch
                val selected = state.selectedCompanyId
                    ?.takeIf { id -> companies.any { it.id == id } }
                    ?: companies.firstOrNull { it.latestPrice != 0.0 }?.id
                    ?: companies.firstOrNull()?.id
                val chartData = selected?.let { repository.fetchChartData(it, interval) }
                    ?: StockMarketChartData(emptyList(), emptyList())
                val health = repository.fetchHealth()
                if (requestId != chartRequestSequence) return@launch
                state = state.copy(
                    loading = false,
                    companies = companies,
                    selectedCompanyId = selected,
                    kline = mergeKlinePoints(emptyList(), chartData.kline),
                    availableShares = mergeAvailableShares(emptyList(), chartData.availableShares),
                    chartSegmentStart = chartData.segmentStart,
                    chartHasMore = chartData.hasMore && chartData.segmentStart != null,
                    chartLoadingMore = false,
                    health = health,
                    purchaseRecords = currentPlayer?.uuid?.let(purchaseStore::load).orEmpty(),
                    profitHistory = currentPlayer?.uuid?.let(profitStore::load).orEmpty(),
                    liveInfo = state.liveInfo?.takeIf { it.name == companies.firstOrNull { company -> company.id == selected }?.name }
                )
            } catch (error: Exception) {
                if (requestId == chartRequestSequence) {
                    state = state.copy(loading = false, lastError = error.message ?: "加载失败")
                }
            }
        }
    }

    LaunchedEffect(gateway) {
        gateway.money.collect { money -> state = state.copy(walletBalance = money) }
    }

    LaunchedEffect(Unit) {
        if (!playerStateInitialized) {
            state = initialPlayerState
            playerStateInitialized = true
        }
        refreshAll()
        if (currentPlayer == null) {
            state = state.copy(dialogMessage = "未获取到当前登录账号，请登录后重新打开股市面板。")
        } else if (gateway.isConnected()) {
            gateway.refreshMoney()
            refreshPortfolio()
        }
    }

    BackHandler {
        if (showDetail) showDetail = false else onClose()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (showDetail) selectedCompany()?.name ?: "股票详情" else selectedTab.label,
                            fontWeight = FontWeight.Bold
                        )
                        if (!showDetail) Text("帕拉伦证券", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (showDetail) showDetail = false else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = if (showDetail) "返回" else "退出")
                    }
                },
                actions = {
                    if (showDetail) {
                        val selectedId = state.selectedCompanyId
                        IconButton(onClick = { selectedId?.let(::toggleWatchlist) }, enabled = selectedId != null) {
                            Icon(
                                if (selectedId in watchlistIds) Icons.Default.Star else Icons.Outlined.StarBorder,
                                contentDescription = if (selectedId in watchlistIds) "取消自选" else "加入自选",
                                tint = if (selectedId in watchlistIds) STOCK_GOLD else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = ::refreshAll) { Icon(Icons.Default.Refresh, contentDescription = "刷新") }
                }
            )
        },
        bottomBar = {
            if (!showDetail) {
                NavigationBar {
                    StockMainTab.entries.forEach { tab ->
                        val icon = when (tab) {
                            StockMainTab.Market -> Icons.Default.Home
                            StockMainTab.Watchlist -> Icons.Default.Star
                            StockMainTab.Portfolio -> Icons.Default.AccountBalanceWallet
                            StockMainTab.Analysis -> Icons.Default.Analytics
                        }
                        NavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(icon, contentDescription = tab.label) },
                            label = { Text(tab.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.lastError?.let { ErrorCard("数据加载失败：$it") }
            if (showDetail) {
                StockSummary(selectedCompany())
                DetailQuoteGrid(selectedCompany(), state.kline)
                IntervalPicker(
                    selected = state.selectedInterval,
                    onSelect = { interval ->
                        state = state.copy(selectedInterval = interval, loading = true, lastError = null)
                        reloadKline()
                    }
                )
                if (state.chartLoadingMore) LinearProgressIndicator(Modifier.fillMaxWidth())
                CandleChart(
                    points = state.kline,
                    availableShares = state.availableShares,
                    interval = state.selectedInterval,
                    viewportKey = "${state.selectedCompanyId}:${state.selectedInterval}",
                    viewport = chartViewport,
                    onViewportChange = { chartViewport = it },
                    onReachStart = ::loadOlderChartSegment,
                    displaySettings = chartDisplaySettings,
                    onDisplaySettingsChange = ::updateChartDisplaySettings,
                    modifier = Modifier.fillMaxWidth().height(330.dp)
                )
                AvailableSharesChart(
                    kline = state.kline,
                    points = state.availableShares,
                    viewportKey = "${state.selectedCompanyId}:${state.selectedInterval}",
                    viewport = chartViewport,
                    onViewportChange = { chartViewport = it },
                    onReachStart = ::loadOlderChartSegment,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
                TradePanel(
                action = action,
                amount = amount,
                balance = state.walletBalance,
                liveInfo = state.liveInfo,
                holdings = state.holdings,
                serverId = selectedCompany()?.marketId,
                onActionChange = { action = it },
                onAmountChange = { amount = it.filter(Char::isDigit) },
                onMoney = { scope.launch { showResult(gateway.refreshMoney()) } },
                onQueryPrice = {
                    val serverId = selectedCompany()?.marketId
                    if (serverId == null) {
                        state = state.copy(dialogMessage = "网页数据未提供当前公司的股票 ID，请刷新后重试。")
                    } else {
                        scope.launch {
                            gateway.queryCompanyInfo(serverId)
                                .onSuccess { info -> state = state.copy(liveInfo = info) }
                                .onFailure { error -> state = state.copy(dialogMessage = "实时价格查询失败：${error.message}") }
                        }
                    }
                },
                onSend = {
                    val amountValue = amount.toLongOrNull()
                    val serverId = selectedCompany()?.marketId
                    when {
                        serverId == null -> state = state.copy(dialogMessage = "网页数据未提供当前公司的股票 ID，请刷新后重试。")
                        amountValue == null || amountValue <= 0 -> state = state.copy(dialogMessage = "请输入有效的交易数量。")
                        else -> scope.launch {
                            gateway.queryCompanyInfo(serverId)
                                .onFailure { error -> state = state.copy(dialogMessage = "交易前实时价格查询失败：${error.message}") }
                                .onSuccess { info ->
                                    state = state.copy(liveInfo = info)
                                    val tradeResult = gateway.trade(action, serverId, amountValue)
                                    if (tradeResult is StockCommandResult.Success) {
                                        val playerUuid = currentPlayer?.uuid
                                        val company = selectedCompany()
                                        val price = info.price
                                        if (playerUuid != null && company != null) {
                                            if (action == "buy") {
                                                purchaseStore.recordBuy(playerUuid, serverId, company.name, amountValue, price)
                                                profitStore.recordBuy(playerUuid, price * amountValue)
                                            } else {
                                                val tracked = state.purchaseRecords[serverId]
                                                    ?: state.purchaseRecords.values.firstOrNull { it.companyName == company.name }
                                                val trackedSoldShares = min(amountValue, tracked?.shares ?: 0L)
                                                if (tracked != null && trackedSoldShares > 0L) {
                                                    profitStore.recordRealizedPnl(
                                                        playerUuid,
                                                        (price - tracked.averagePrice) * trackedSoldShares
                                                    )
                                                }
                                                purchaseStore.recordSell(
                                                    playerUuid,
                                                    serverId,
                                                    amountValue,
                                                    fullySold = tracked != null && amountValue >= (state.holdings.firstOrNull { holding ->
                                                        holding.marketId == serverId || holding.companyName == company.name
                                                    }?.shares ?: tracked.shares)
                                                )
                                            }
                                            state = state.copy(purchaseRecords = purchaseStore.load(playerUuid))
                                        }
                                        gateway.refreshMoney()
                                        if (action == "sell") {
                                            state = state.copy(holdings = state.holdings.mapNotNull { holding ->
                                                val matches = holding.marketId == serverId || holding.companyName == company?.name
                                                if (!matches) holding else {
                                                    val remaining = (holding.shares - amountValue).coerceAtLeast(0L)
                                                    holding.copy(
                                                        shares = remaining,
                                                        totalValue = if (holding.shares > 0) holding.totalValue * remaining / holding.shares else 0.0
                                                    ).takeIf { remaining > 0 }
                                                }
                                            })
                                        }
                                        gateway.queryPortfolio().onSuccess { holdings ->
                                            if (action == "sell" && currentPlayer?.uuid != null) {
                                                val remaining = holdings.firstOrNull { holding ->
                                                    holding.marketId == serverId || holding.companyName == company?.name
                                                }?.shares ?: 0L
                                                if (remaining == 0L) {
                                                    purchaseStore.recordSell(currentPlayer.uuid, serverId, amountValue, fullySold = true)
                                                }
                                            }
                                            val activeHoldings = holdings.filter { it.shares > 0 }
                                            val records = currentPlayer?.uuid?.let(purchaseStore::load).orEmpty()
                                            state = state.copy(
                                                holdings = activeHoldings,
                                                purchaseRecords = records,
                                                profitHistory = recordProfitSnapshot(activeHoldings, records)
                                            )
                                        }
                                    }
                                    showResult(tradeResult)
                                }
                        }
                    }
                }
                )
            } else {
                when (selectedTab) {
                    StockMainTab.Market -> MarketPage(
                        companies = state.companies,
                        holdings = state.holdings,
                        health = state.health,
                        searchQuery = searchQuery,
                        marketSort = marketSort,
                        watchlistIds = watchlistIds,
                        onSearchChange = { searchQuery = it },
                        onSortChange = { marketSort = it },
                        onToggleWatchlist = ::toggleWatchlist,
                        onCompanyClick = ::openCompany
                    )
                    StockMainTab.Watchlist -> WatchlistPage(
                        companies = state.companies.filter { it.id in watchlistIds },
                        holdings = state.holdings,
                        watchlistIds = watchlistIds,
                        onToggleWatchlist = ::toggleWatchlist,
                        onCompanyClick = ::openCompany,
                        onBrowseMarket = { selectedTab = StockMainTab.Market }
                    )
                    StockMainTab.Portfolio -> PortfolioPage(
                        state = state,
                        onRefresh = { scope.launch { refreshPortfolio(showEmptyMessage = true); gateway.refreshMoney() } },
                        onInfo = { state = state.copy(dialogMessage = "购买均价、持有收益仅统计通过本插件完成的买入；其他途径取得的持仓仍显示市值，但不纳入成本收益。") },
                        onClearRecords = { confirmClearRecords = true },
                        onCompanyClick = { holding ->
                            state.companies.firstOrNull { it.marketId == holding.marketId || it.name == holding.companyName }
                                ?.let { openCompany(it.id) }
                        }
                    )
                    StockMainTab.Analysis -> AnalysisPage(
                        state = state,
                        onClearHistory = { confirmClearProfitHistory = true },
                        onEditProfit = ::openProfitEditor
                    )
                }
            }
            state.health?.let { health ->
                Text(
                    "行情更新 · ${health.companies} 家公司 · ${health.prices} 条价格记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    state.dialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { state = state.copy(dialogMessage = null) },
            title = { Text("股市操作提示") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { state = state.copy(dialogMessage = null) }) { Text("确定") } }
        )
    }

    if (confirmClearRecords) {
        AlertDialog(
            onDismissRequest = { confirmClearRecords = false },
            title = { Text("清除本地记录") },
            text = { Text("确定要清除本机所有账号的买入成本和收益历史吗？此操作无法撤销，且不会影响服务器持仓。") },
            dismissButton = { TextButton(onClick = { confirmClearRecords = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    purchaseStore.clearAll()
                    profitStore.clearAll()
                    state = state.copy(purchaseRecords = emptyMap(), profitHistory = emptyList())
                    confirmClearRecords = false
                }) { Text("清除") }
            }
        )
    }

    if (confirmClearProfitHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearProfitHistory = false },
            title = { Text("清除收益历史") },
            text = { Text("确定清除当前账号的本地收益记录吗？清除后将从下一次同步持仓重新开始记录，历史数据无法恢复。") },
            dismissButton = { TextButton(onClick = { confirmClearProfitHistory = false }) { Text("取消") } },
            confirmButton = {
                TextButton(onClick = {
                    currentPlayer?.uuid?.let(profitStore::clear)
                    state = state.copy(profitHistory = emptyList())
                    confirmClearProfitHistory = false
                }) { Text("清除") }
            }
        )
    }

    editProfitRecord?.let { record ->
        AlertDialog(
            onDismissRequest = { editProfitRecord = null },
            title = { Text("编辑 ${record.day} 当日盈亏") },
            text = {
                OutlinedTextField(
                    value = editProfitValue,
                    onValueChange = { editProfitValue = it },
                    label = { Text("当日盈亏（元）") },
                    singleLine = true
                )
            },
            dismissButton = { TextButton(onClick = { editProfitRecord = null }) { Text("取消") } },
            confirmButton = { TextButton(onClick = ::saveProfitEdit) { Text("保存") } }
        )
    }
}

@Composable
private fun ErrorCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

private enum class MarketSort(val label: String) {
    Default("默认榜"),
    ChangeDesc("涨幅榜"),
    ChangeAsc("跌幅榜"),
    PriceDesc("价格榜")
}

private fun StockCompany.isBankrupt(): Boolean {
    val normalized = status.orEmpty().trim().lowercase()
    return normalized.contains("破产") || normalized.contains("bankrupt") || normalized.contains("已退市")
}

@Composable
private fun MarketPage(
    companies: List<StockCompany>,
    holdings: List<StockHolding>,
    health: StockHealthResponse?,
    searchQuery: String,
    marketSort: MarketSort,
    watchlistIds: Set<Int>,
    onSearchChange: (String) -> Unit,
    onSortChange: (MarketSort) -> Unit,
    onToggleWatchlist: (Int) -> Unit,
    onCompanyClick: (Int) -> Unit
) {
    val activeCompanies = companies.filterNot(StockCompany::isBankrupt)
    val rising = activeCompanies.count { (it.changePct ?: 0.0) > 0.0 }
    val falling = activeCompanies.count { (it.changePct ?: 0.0) < 0.0 }
    val flat = activeCompanies.size - rising - falling
    val averageChange = activeCompanies.mapNotNull(StockCompany::changePct).average().takeUnless(Double::isNaN)
    MarketPulseCard(rising, flat, falling, averageChange, health)

    OutlinedTextField(
        value = searchQuery,
        onValueChange = onSearchChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        trailingIcon = {
            if (searchQuery.isNotEmpty()) IconButton(onClick = { onSearchChange("") }) {
                Icon(Icons.Default.Close, contentDescription = "清空搜索")
            }
        },
        placeholder = { Text("搜索公司名称或股票 ID") },
        shape = RoundedCornerShape(12.dp)
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        MarketSort.entries.forEach { sort ->
            if (sort == marketSort) Button(
                onClick = { onSortChange(sort) },
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) { Text(sort.label, fontSize = 11.sp, maxLines = 1) }
            else OutlinedButton(
                onClick = { onSortChange(sort) },
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.weight(1f)
            ) { Text(sort.label, fontSize = 11.sp, maxLines = 1) }
        }
    }

    val filtered = companies.filter { company ->
        searchQuery.isBlank() || company.name.contains(searchQuery, ignoreCase = true) ||
            company.marketId?.toString()?.contains(searchQuery) == true
    }.let { rows ->
        when (marketSort) {
            MarketSort.Default -> rows.sortedWith(
                compareBy<StockCompany> { it.isBankrupt() }
                    .thenBy { it.marketId ?: it.id }
                    .thenBy(StockCompany::id)
            )
            MarketSort.ChangeDesc -> rows.sortedWith(
                compareBy<StockCompany> { it.isBankrupt() }
                    .thenByDescending { it.changePct ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.marketId ?: it.id }
            )
            MarketSort.ChangeAsc -> rows.sortedWith(
                compareBy<StockCompany> { it.isBankrupt() }
                    .thenBy { it.changePct ?: Double.POSITIVE_INFINITY }
                    .thenBy { it.marketId ?: it.id }
            )
            MarketSort.PriceDesc -> rows.sortedWith(
                compareBy<StockCompany> { it.isBankrupt() }
                    .thenByDescending { it.latestPrice ?: Double.NEGATIVE_INFINITY }
                    .thenBy { it.marketId ?: it.id }
            )
        }
    }
    StockListSection(
        title = if (searchQuery.isBlank()) "全部股票 · ${filtered.size}" else "搜索结果 · ${filtered.size}",
        companies = filtered,
        holdings = holdings,
        watchlistIds = watchlistIds,
        onToggleWatchlist = onToggleWatchlist,
        onCompanyClick = onCompanyClick
    )
}

@Composable
private fun MarketPulseCard(
    rising: Int,
    flat: Int,
    falling: Int,
    averageChange: Double?,
    health: StockHealthResponse?
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("市场温度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        averageChange?.let { "%+.2f%%".format(it) } ?: "--",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor(averageChange)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("涨 $rising  平 $flat  跌 $falling", fontWeight = FontWeight.Bold)
                    Text(
                        health?.latestPriceAt?.let { "行情已同步" } ?: "等待行情",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val total = (rising + flat + falling).coerceAtLeast(1).toFloat()
            Row(Modifier.fillMaxWidth().height(5.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                if (rising > 0) Box(Modifier.weight(rising / total).fillMaxSize().background(STOCK_UP, RoundedCornerShape(5.dp)))
                if (flat > 0) Box(Modifier.weight(flat / total).fillMaxSize().background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(5.dp)))
                if (falling > 0) Box(Modifier.weight(falling / total).fillMaxSize().background(STOCK_DOWN, RoundedCornerShape(5.dp)))
            }
        }
    }
}

@Composable
private fun WatchlistPage(
    companies: List<StockCompany>,
    holdings: List<StockHolding>,
    watchlistIds: Set<Int>,
    onToggleWatchlist: (Int) -> Unit,
    onCompanyClick: (Int) -> Unit,
    onBrowseMarket: () -> Unit
) {
    if (companies.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = 44.dp, horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(Icons.Outlined.StarBorder, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("还没有自选股", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("在行情或详情页点亮星标，关注的股票会保存在本机。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onBrowseMarket) { Text("去行情看看") }
            }
        }
    } else {
        val average = companies.mapNotNull(StockCompany::changePct).average().takeUnless(Double::isNaN)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("自选表现", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(average?.let { "%+.2f%%".format(it) } ?: "--", style = MaterialTheme.typography.headlineSmall, color = pnlColor(average), fontWeight = FontWeight.Bold)
                }
                Text("${companies.size} 只", style = MaterialTheme.typography.titleMedium)
            }
        }
        StockListSection("我的自选", companies, holdings, watchlistIds, onToggleWatchlist, onCompanyClick)
    }
}

@Composable
private fun StockListSection(
    title: String,
    companies: List<StockCompany>,
    holdings: List<StockHolding>,
    watchlistIds: Set<Int>,
    onToggleWatchlist: (Int) -> Unit,
    onCompanyClick: (Int) -> Unit
) {
    val holdingByName = remember(holdings) { holdings.associateBy(StockHolding::companyName) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("最新价 / 涨跌幅", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            companies.forEach { company ->
                StockListRow(company, holdingByName[company.name], company.id in watchlistIds, onToggleWatchlist, onCompanyClick)
            }
            if (companies.isEmpty()) Text("没有匹配的股票", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StockListRow(
    company: StockCompany,
    holding: StockHolding?,
    watched: Boolean,
    onToggleWatchlist: (Int) -> Unit,
    onCompanyClick: (Int) -> Unit
) {
    val change = company.changePct
    val quoteColor = when {
        change == null -> MaterialTheme.colorScheme.onSurfaceVariant
        change >= 0.0 -> STOCK_UP
        else -> STOCK_DOWN
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onCompanyClick(company.id) },
        color = Color.Transparent
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onToggleWatchlist(company.id) }, modifier = Modifier.size(38.dp)) {
                Icon(
                    if (watched) Icons.Default.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (watched) "取消自选" else "加入自选",
                    tint = if (watched) STOCK_GOLD else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(Modifier.weight(1f)) {
                Text(company.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                Text(
                    buildString {
                        append(company.marketId?.let { "%04d".format(it) } ?: "----")
                        append(" · 风险${company.riskLevel ?: "--"}")
                        holding?.let { append(" · 持有${it.shares}股") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    buildString {
                        append("可流通 ${company.availableShares?.toString() ?: "--"} 股")
                        company.status?.takeIf(String::isNotBlank)?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (company.isBankrupt()) STOCK_DOWN else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(company.latestPrice?.let { "%.2f".format(it) } ?: "--", fontWeight = FontWeight.Bold, color = quoteColor)
                Text(change?.let { "%+.2f%%".format(it) } ?: "--", style = MaterialTheme.typography.bodySmall, color = quoteColor)
            }
        }
    }
}

private data class PortfolioMetrics(
    val marketValue: Double,
    val trackedCost: Double,
    val pnl: Double?,
    val pnlPct: Double?,
    val rows: List<PortfolioMetricRow>
)

private data class PortfolioMetricRow(val holding: StockHolding, val cost: Double?, val pnl: Double?, val pnlPct: Double?)

private fun portfolioMetrics(state: StockUiState): PortfolioMetrics {
    val companyByName = state.companies.associateBy(StockCompany::name)
    val recordsByName = state.purchaseRecords.values.associateBy(PurchaseRecord::companyName)
    val rows = state.holdings.map { holding ->
        val company = companyByName[holding.companyName]
        val record = (holding.marketId ?: company?.marketId)?.let(state.purchaseRecords::get) ?: recordsByName[holding.companyName]
        val trackedShares = min(holding.shares, record?.shares ?: 0L)
        val cost = record?.averagePrice?.times(trackedShares)
        val trackedMarketValue = if (holding.shares > 0L) holding.totalValue / holding.shares * trackedShares else 0.0
        val pnl = cost?.let { trackedMarketValue - it }
        PortfolioMetricRow(holding, cost, pnl, if (cost != null && cost > 0.0) pnl!! / cost * 100.0 else null)
    }
    val marketValue = rows.sumOf { it.holding.totalValue }
    val trackedRows = rows.filter { it.cost != null }
    val trackedCost = trackedRows.sumOf { it.cost ?: 0.0 }
    val trackedValue = trackedRows.sumOf { row -> (row.cost ?: 0.0) + (row.pnl ?: 0.0) }
    val pnl = if (trackedCost > 0.0) trackedValue - trackedCost else null
    return PortfolioMetrics(marketValue, trackedCost, pnl, pnl?.let { it / trackedCost * 100.0 }, rows)
}

@Composable
private fun PortfolioPage(
    state: StockUiState,
    onRefresh: () -> Unit,
    onInfo: () -> Unit,
    onClearRecords: () -> Unit,
    onCompanyClick: (StockHolding) -> Unit
) {
    val metrics = portfolioMetrics(state)
    val totalAssets = state.walletBalance?.plus(metrics.marketValue)
    val position = totalAssets?.takeIf { it > 0.0 }?.let { metrics.marketValue / it * 100.0 }
    Card(colors = CardDefaults.cardColors(containerColor = PORTFOLIO_CARD)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("总资产（元）", color = Color.White.copy(alpha = .75f))
            Text(formatMoneyNumber(totalAssets), color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AssetLabel("持仓市值", formatMoneyNumber(metrics.marketValue))
                AssetLabel("可用资金", formatMoneyNumber(state.walletBalance), Alignment.End)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                AssetLabel("累计收益", formatPnl(metrics.pnl), valueColor = if ((metrics.pnl ?: 0.0) >= 0.0) Color(0xFF9AE6B4) else Color(0xFFFFB5B0))
                AssetLabel("仓位", position?.let { "%.1f%%".format(it) } ?: "--", Alignment.End)
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onRefresh, enabled = !state.portfolioLoading, modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(5.dp)); Text(if (state.portfolioLoading) "同步中" else "同步资产")
        }
        OutlinedButton(onClick = onInfo, modifier = Modifier.weight(1f)) { Text("收益说明") }
    }
    if (metrics.rows.isEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(38.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp)); Text("暂无持仓", fontWeight = FontWeight.Bold)
                Text("连接服务器后同步持仓", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    } else {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth()) {
                Text("我的持仓", fontWeight = FontWeight.Bold, modifier = Modifier.padding(14.dp))
                metrics.rows.sortedByDescending { it.holding.totalValue }.forEach { row ->
                    Surface(Modifier.fillMaxWidth().clickable { onCompanyClick(row.holding) }, color = Color.Transparent) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(row.holding.companyName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(formatPnl(row.pnl), color = pnlColor(row.pnl), fontWeight = FontWeight.Bold)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${row.holding.shares} 股 · 市值 ${formatMoney(row.holding.totalValue)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Text(formatPercent(row.pnlPct), color = pnlColor(row.pnl), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
    TextButton(onClick = onClearRecords, modifier = Modifier.fillMaxWidth()) { Text("管理本地成本记录") }
}

@Composable
private fun AssetLabel(label: String, value: String, alignment: Alignment.Horizontal = Alignment.Start, valueColor: Color = Color.White) {
    Column(horizontalAlignment = alignment) {
        Text(label, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.labelSmall)
        Text(value, color = valueColor, fontWeight = FontWeight.Bold)
    }
}

private data class DailyProfitPoint(
    val day: String,
    val epochMillis: Long,
    val cumulativeProfit: Double,
    val cumulativeProfitPct: Double?,
    val dailyProfit: Double,
    val dailyProfitPct: Double?,
    val investedCapital: Double,
    val totalAssets: Double?
)

private fun buildDailyProfitSeries(state: StockUiState): List<DailyProfitPoint> {
    var previousCumulativeProfit = 0.0
    return state.profitHistory.sortedBy(ProfitHistoryRecord::timestamp).map { record ->
        val dailyProfit = record.totalPnl - previousCumulativeProfit
        previousCumulativeProfit = record.totalPnl
        DailyProfitPoint(
            day = record.day,
            epochMillis = record.timestamp,
            cumulativeProfit = record.totalPnl,
            cumulativeProfitPct = record.totalPnlPct,
            dailyProfit = dailyProfit,
            dailyProfitPct = (dailyProfit / (record.totalAssets ?: record.investedCapital) * 100.0).takeIf(Double::isFinite),
            investedCapital = record.investedCapital,
            totalAssets = record.totalAssets
        )
    }

}

private enum class ProfitRange(val label: String, val days: Int?) {
    Week("7日", 7),
    Month("30日", 30),
    Quarter("90日", 90),
    Year("1年", 365),
    All("全部", null)
}

@Composable
private fun ProfitTrendCard(allSeries: List<DailyProfitPoint>) {
    var selectedRange by remember { mutableStateOf(ProfitRange.Month) }
    val latestEpoch = allSeries.lastOrNull()?.epochMillis
    val series = remember(allSeries, selectedRange) {
        selectedRange.days?.let { days ->
            latestEpoch?.let { latest -> allSeries.filter { it.epochMillis >= latest - days * 24L * 60 * 60 * 1_000 } }
        } ?: allSeries
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("每日收益走势", fontWeight = FontWeight.Bold)
                Text("${series.size} 个真实快照", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                ProfitRange.entries.forEach { range ->
                    if (range == selectedRange) Button(
                        onClick = { selectedRange = range },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) { Text(range.label, fontSize = 11.sp) }
                    else TextButton(onClick = { selectedRange = range }, modifier = Modifier.weight(1f)) {
                        Text(range.label, fontSize = 11.sp)
                    }
                }
            }
            when {
                series.isEmpty() -> Text("暂无真实收益快照。同步持仓或通过插件完成交易后开始记录。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val latest = series.last()
                    Text(formatPnl(latest.dailyProfit), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = pnlColor(latest.dailyProfit))
                    Text("当日收益率 ${formatPercent(latest.dailyProfitPct)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val minProfit = min(series.minOf(DailyProfitPoint::dailyProfit), 0.0)
                    val maxProfit = max(series.maxOf(DailyProfitPoint::dailyProfit), 0.0)
                    val range = (maxProfit - minProfit).takeIf { it > 0.0001 } ?: 1.0
                    val gridColor = MaterialTheme.colorScheme.outlineVariant
                    Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                        val left = 4.dp.toPx()
                        val right = size.width - 4.dp.toPx()
                        val top = 8.dp.toPx()
                        val bottom = size.height - 8.dp.toPx()
                        fun pointOffset(index: Int, value: Double): Offset {
                            val x = if (series.size <= 1) (left + right) / 2f else left + (right - left) * index / (series.size - 1f)
                            val y = bottom - ((value - minProfit) / range).toFloat() * (bottom - top)
                            return Offset(x, y)
                        }
                        val zeroY = pointOffset(0, 0.0).y
                        drawLine(gridColor, Offset(left, zeroY), Offset(right, zeroY), strokeWidth = 1.dp.toPx())
                        if (series.size == 1) {
                            drawCircle(pnlColor(series[0].dailyProfit), radius = 3.dp.toPx(), center = pointOffset(0, series[0].dailyProfit))
                        } else {
                            for (index in 1 until series.size) {
                                val previous = series[index - 1]
                                val current = series[index]
                                drawLine(
                                    color = pnlColor(current.dailyProfit),
                                    start = pointOffset(index - 1, previous.dailyProfit),
                                    end = pointOffset(index, current.dailyProfit),
                                    strokeWidth = 2.dp.toPx()
                                )
                            }
                            drawCircle(pnlColor(latest.dailyProfit), radius = 3.dp.toPx(), center = pointOffset(series.lastIndex, latest.dailyProfit))
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(series.first().day.substring(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("最高 ${formatPnl(maxProfit)} · 最低 ${formatPnl(minProfit)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(series.last().day.substring(5), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private enum class ProfitCalendarMode(val label: String) {
    Calendar("按日"),
    Monthly("按月")
}

@Composable
private fun ProfitCalendarCard(
    series: List<DailyProfitPoint>,
    onLongPressDay: (DailyProfitPoint) -> Unit
) {
    var mode by remember { mutableStateOf(ProfitCalendarMode.Calendar) }
    var monthOffset by remember { mutableStateOf(0) }
    var selectedDay by remember { mutableStateOf<String?>(null) }
    val anchor = series.lastOrNull()?.epochMillis ?: System.currentTimeMillis()
    val calendar = Calendar.getInstance().apply {
        timeInMillis = anchor
        set(Calendar.DAY_OF_MONTH, 1)
        add(Calendar.MONTH, monthOffset)
    }
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("收益日历", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ProfitCalendarMode.entries.forEach { item ->
                        if (mode == item) Button(
                            onClick = { mode = item },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) { Text(item.label, fontSize = 11.sp) }
                        else TextButton(onClick = { mode = item }) { Text(item.label, fontSize = 11.sp) }
                    }
                }
            }
            Text("仅显示本插件实际保存的快照；无记录日期保持空白", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (mode == ProfitCalendarMode.Calendar) {
                ProfitCalendarMonth(
                    series = series,
                    calendar = calendar,
                    selectedDay = selectedDay,
                    onSelectDay = { selectedDay = it },
                    onLongPressDay = onLongPressDay,
                    onPreviousMonth = { monthOffset -= 1; selectedDay = null },
                    onNextMonth = { monthOffset += 1; selectedDay = null },
                    canGoNext = monthOffset < 0
                )
            } else {
                ProfitMonthlyList(series)
            }
        }
    }
}

@Composable
private fun ProfitCalendarMonth(
    series: List<DailyProfitPoint>,
    calendar: Calendar,
    selectedDay: String?,
    onSelectDay: (String) -> Unit,
    onLongPressDay: (DailyProfitPoint) -> Unit,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    canGoNext: Boolean
) {
    val year = calendar.get(Calendar.YEAR)
    val month = calendar.get(Calendar.MONTH)
    val daysInMonth = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
    val mondayFirstOffset = (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val valuesByDay = series.filter { point ->
        Calendar.getInstance().apply { timeInMillis = point.epochMillis }.let {
            it.get(Calendar.YEAR) == year && it.get(Calendar.MONTH) == month
        }
    }.associateBy { Calendar.getInstance().apply { timeInMillis = it.epochMillis }.get(Calendar.DAY_OF_MONTH) }
    val weekCount = (mondayFirstOffset + daysInMonth + 6) / 7

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onPreviousMonth) { Text("‹ 上月") }
        Text("${year}年${month + 1}月", fontWeight = FontWeight.Bold)
        TextButton(onClick = onNextMonth, enabled = canGoNext) { Text("下月 ›") }
    }
    Row(Modifier.fillMaxWidth()) {
        listOf("一", "二", "三", "四", "五", "六", "日").forEach { day ->
            Text(day, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    repeat(weekCount) { week ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(7) { weekDay ->
                val day = week * 7 + weekDay - mondayFirstOffset + 1
                val point = valuesByDay[day]
                val background = when {
                    point == null -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f)
                    point.dailyProfit >= 0.0 -> STOCK_UP.copy(alpha = .14f)
                    else -> STOCK_DOWN.copy(alpha = .14f)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .height(64.dp)
                        .background(if (day in 1..daysInMonth) background else Color.Transparent, RoundedCornerShape(6.dp))
                        .profitCalendarLongPress(point, onLongPressDay)
                        .clickable(enabled = point != null) { point?.let { onSelectDay(it.day) } }
                        .padding(vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (day in 1..daysInMonth) {
                        Text(day.toString(), style = MaterialTheme.typography.labelSmall, fontWeight = if (point?.day == selectedDay) FontWeight.Bold else FontWeight.Normal)
                        if (point != null) {
                            Text(formatCalendarProfit(point.dailyProfit), style = MaterialTheme.typography.labelSmall, color = pnlColor(point.dailyProfit), maxLines = 1)
                            Text(point.dailyProfitPct?.let { "%+.1f%%".format(it) } ?: "--", style = MaterialTheme.typography.labelSmall, color = pnlColor(point.dailyProfit), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
    val selected = selectedDay?.let { day -> series.firstOrNull { it.day == day } }
    if (selected != null) {
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp)) {
            Text("${selected.day} 最后一次真实快照", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("当日盈亏 ${formatPnl(selected.dailyProfit)} · ${formatPercent(selected.dailyProfitPct)}", color = pnlColor(selected.dailyProfit), fontWeight = FontWeight.Bold)
            Text("累计盈亏 ${formatPnl(selected.cumulativeProfit)} · ${formatPercent(selected.cumulativeProfitPct)}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            Text("累计投入 ${formatMoney(selected.investedCapital)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun Modifier.profitCalendarLongPress(
    point: DailyProfitPoint?,
    onLongPress: (DailyProfitPoint) -> Unit
): Modifier = if (point == null) this else pointerInput(point.day) {
    val holdDurationMillis = 10_000L
    // A small raw-pixel threshold keeps the hidden hold gesture from
    // interfering with normal scrolling; any noticeable drag cancels it.
    val movementThreshold = 24f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val start = down.position
        val completed = withTimeoutOrNull(holdDurationMillis) {
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull() ?: return@withTimeoutOrNull false
                if (!change.pressed) return@withTimeoutOrNull false
                if ((change.position - start).getDistance() > movementThreshold) return@withTimeoutOrNull false
            }
            true
        } ?: true
        if (completed) onLongPress(point)
    }
}

@Composable
private fun ProfitMonthlyList(series: List<DailyProfitPoint>) {
    val months = series.groupBy { it.day.take(7) }.toSortedMap()
    if (months.isEmpty()) {
        Text("暂无月度收益记录", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val summaries = months.map { (month, records) ->
        val ordered = records.sortedBy(DailyProfitPoint::epochMillis)
        val last = ordered.last()
        val change = ordered.sumOf(DailyProfitPoint::dailyProfit)
        val changePct = (change / (last.totalAssets ?: last.investedCapital) * 100.0).takeIf(Double::isFinite)
        MonthlyProfitSummary(month, ordered.first(), last, change, changePct)
    }.asReversed()
    summaries.forEach { summary ->
        Row(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(summary.month.replace("-", "年") + "月", fontWeight = FontWeight.Bold)
                Text(
                    "记录区间 ${summary.open.day.substring(5)}—${summary.close.day.substring(5)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(formatPnl(summary.change), color = pnlColor(summary.change), fontWeight = FontWeight.Bold)
                Text("月收益率 ${formatPercent(summary.changePct)}", color = pnlColor(summary.changePct), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private data class MonthlyProfitSummary(
    val month: String,
    val open: DailyProfitPoint,
    val close: DailyProfitPoint,
    val change: Double,
    val changePct: Double?
)

private fun formatCalendarProfit(value: Double): String = when {
    abs(value) >= 10_000.0 -> "%+.1fw".format(value / 10_000.0)
    abs(value) >= 1_000.0 -> "%+.1fk".format(value / 1_000.0)
    else -> "%+.0f".format(value)
}

@Composable
private fun AnalysisPage(
    state: StockUiState,
    onClearHistory: () -> Unit,
    onEditProfit: (ProfitHistoryRecord) -> Unit
) {
    val metrics = portfolioMetrics(state)
    val profitSeries = remember(state.profitHistory) {
        buildDailyProfitSeries(state)
    }
    val latestRecordedProfit = profitSeries.lastOrNull()
    val tracked = metrics.rows.filter { it.pnl != null }
    val winners = tracked.count { (it.pnl ?: 0.0) >= 0.0 }
    val winRate = tracked.takeIf { it.isNotEmpty() }?.let { winners * 100.0 / it.size }
    val best = tracked.maxByOrNull { it.pnlPct ?: Double.NEGATIVE_INFINITY }
    val weakest = tracked.minByOrNull { it.pnlPct ?: Double.POSITIVE_INFINITY }
    val activeCompanies = state.companies.filterNot(StockCompany::isBankrupt)
    val rising = activeCompanies.count { (it.changePct ?: 0.0) > 0.0 }
    val falling = activeCompanies.count { (it.changePct ?: 0.0) < 0.0 }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("收益分析", fontWeight = FontWeight.Bold)
            Text(formatPnl(latestRecordedProfit?.cumulativeProfit), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = pnlColor(latestRecordedProfit?.cumulativeProfit))
            Text("累计收益率 ${formatPercent(latestRecordedProfit?.cumulativeProfitPct)} · ${profitSeries.size} 个真实快照", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("成交盈亏按交易前服务器实时查价记录；持仓盈亏按服务器返回市值记录", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AnalysisStat("胜率", winRate?.let { "%.0f%%".format(it) } ?: "--", Modifier.weight(1f))
                AnalysisStat("盈利持仓", "$winners/${tracked.size}", Modifier.weight(1f))
                AnalysisStat("市场涨跌", "$rising/$falling", Modifier.weight(1f))
            }
        }
    }

    ProfitTrendCard(profitSeries)
    ProfitCalendarCard(profitSeries) { point ->
        state.profitHistory.firstOrNull { it.day == point.day }?.let(onEditProfit)
    }
    OutlinedButton(onClick = onClearHistory, modifier = Modifier.fillMaxWidth()) {
        Text("清理本地收益记录缓存")
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("持仓诊断", fontWeight = FontWeight.Bold)
            AnalysisHoldingLine("表现最佳", best)
            AnalysisHoldingLine("表现最弱", weakest)
            if (tracked.isEmpty()) Text("完成一次插件内买入后即可生成收益诊断。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    if (metrics.rows.isNotEmpty()) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("持仓分布", fontWeight = FontWeight.Bold)
                metrics.rows.sortedByDescending { it.holding.totalValue }.take(6).forEachIndexed { index, row ->
                    val ratio = if (metrics.marketValue > 0.0) row.holding.totalValue / metrics.marketValue else 0.0
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.holding.companyName, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("%.1f%%".format(ratio * 100.0), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Box(Modifier.fillMaxWidth().height(6.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))) {
                            Box(Modifier.fillMaxWidth(ratio.toFloat().coerceIn(0f, 1f)).height(6.dp).background(ALLOCATION_COLORS[index % ALLOCATION_COLORS.size], RoundedCornerShape(6.dp)))
                        }
                    }
                }
            }
        }
    }

}

@Composable
private fun AnalysisStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AnalysisHoldingLine(label: String, row: PortfolioMetricRow?) {
    if (row != null) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Column(Modifier.weight(1f)) { Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall); Text(row.holding.companyName, fontWeight = FontWeight.Bold) }
        Column(horizontalAlignment = Alignment.End) { Text(formatPnl(row.pnl), color = pnlColor(row.pnl)); Text(formatPercent(row.pnlPct), color = pnlColor(row.pnl), style = MaterialTheme.typography.labelSmall) }
    }
}

@Composable
private fun DetailQuoteGrid(company: StockCompany?, kline: List<StockKlinePoint>) {
    val latest = kline.lastOrNull()
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            QuoteField("今开", latest?.open)
            QuoteField("最高", latest?.high)
            QuoteField("最低", latest?.low)
            QuoteField("可流通", company?.availableShares?.toDouble(), integer = true)
        }
    }
}

@Composable
private fun QuoteField(label: String, value: Double?, integer: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value?.let { if (integer) "%.0f".format(it) else "%.2f".format(it) } ?: "--", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun StockSummary(company: StockCompany?) {
    val colors = MaterialTheme.colorScheme
    Card(
        colors = CardDefaults.cardColors(
            containerColor = colors.surface,
            contentColor = colors.onSurface
        )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(company?.name ?: "暂无股票", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("状态：${company?.status ?: "--"} · 风险 ${company?.riskLevel ?: "--"}", color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                val change = company?.changePct
                Text(company?.latestPrice?.let { "%.2f".format(it) } ?: "--", style = MaterialTheme.typography.headlineSmall)
                if (change != null && abs(change) >= 15.0) {
                    Text(if (change > 0) "异动↑" else "异动↓", color = if (change > 0) STOCK_UP else STOCK_DOWN, fontWeight = FontWeight.Bold)
                }
                Text(
                    change?.let { "%+.2f%%".format(it) } ?: "--",
                    color = when {
                        change == null -> colors.onSurfaceVariant
                        change >= 0 -> STOCK_UP
                        else -> STOCK_DOWN
                    }
                )
            }
        }
    }
}

@Composable
private fun IntervalPicker(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StockMarketRepository.SUPPORTED_INTERVALS.forEach { interval ->
            val label = StockMarketRepository.intervalLabel(interval)
            if (interval == selected) Button(onClick = { onSelect(interval) }) { Text(label) }
            else OutlinedButton(onClick = { onSelect(interval) }) { Text(label) }
        }
    }
}

@Composable
private fun CandleChart(
    points: List<StockKlinePoint>,
    availableShares: List<StockAvailableSharesPoint>,
    interval: String,
    viewportKey: String,
    viewport: ChartViewport,
    onViewportChange: (ChartViewport) -> Unit,
    onReachStart: () -> Unit,
    displaySettings: ChartDisplaySettings,
    onDisplaySettingsChange: (ChartDisplaySettings) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val latestViewport = rememberUpdatedState(viewport)

    Card(colors = CardDefaults.cardColors(containerColor = colors.surface), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("K 线", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        onDisplaySettingsChange(displaySettings.copy(showCloseLine = !displaySettings.showCloseLine))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(if (displaySettings.showCloseLine) "隐藏折线" else "显示折线", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        onDisplaySettingsChange(displaySettings.copy(showCandles = !displaySettings.showCandles))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(if (displaySettings.showCandles) "隐藏K线" else "显示K线", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        onDisplaySettingsChange(displaySettings.copy(showTradeMarkers = !displaySettings.showTradeMarkers))
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        if (displaySettings.showTradeMarkers) "隐藏售出/回购" else "显示售出/回购",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1
                    )
                }
            }
            if (points.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无 K 线数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(viewportKey, points.size) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var gestureViewport = latestViewport.value
                                var totalMovement = Offset.Zero
                                var lastPosition = Offset.Zero
                                var transformed = false
                                do {
                                    val event = awaitPointerEvent()
                                    val panChange = event.calculatePan()
                                    val zoomChange = event.calculateZoom()
                                    event.changes.firstOrNull()?.let { lastPosition = it.position }
                                    totalMovement += panChange
                                    if (event.changes.size > 1 || abs(zoomChange - 1f) > 0.01f) transformed = true

                                    if (event.changes.size > 1) {
                                        val nextZoom = (gestureViewport.zoom * zoomChange).coerceIn(1f, max(1f, points.size / 5f))
                                        val visibleCount = (points.size / nextZoom).coerceAtLeast(2f)
                                        val maxPan = (points.size - visibleCount).coerceAtLeast(0f) / 2f
                                        gestureViewport = gestureViewport.copy(
                                            zoom = nextZoom,
                                            pan = (gestureViewport.pan - panChange.x * visibleCount / size.width.coerceAtLeast(1)).coerceIn(-maxPan, maxPan),
                                            selectedIndex = null
                                        )
                                        onViewportChange(gestureViewport)
                                        event.changes.forEach { it.consume() }
                                    } else if (abs(panChange.x) > abs(panChange.y)) {
                                        val visibleCount = (points.size / gestureViewport.zoom).coerceAtLeast(2f)
                                        val maxPan = (points.size - visibleCount).coerceAtLeast(0f) / 2f
                                        gestureViewport = gestureViewport.copy(
                                            pan = (gestureViewport.pan - panChange.x * visibleCount / size.width.coerceAtLeast(1)).coerceIn(-maxPan, maxPan),
                                            selectedIndex = null
                                        )
                                        onViewportChange(gestureViewport)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!transformed && totalMovement.getDistance() < viewConfiguration.touchSlop) {
                                    val plotRight = (size.width - 62.dp.toPx()).coerceAtLeast(7.dp.toPx())
                                    val range = chartVisibleRange(points.size, gestureViewport.zoom, gestureViewport.pan)
                                    val count = range.count()
                                    val localIndex = if (count <= 1) 0 else
                                        (((lastPosition.x - 6.dp.toPx()) / (plotRight - 6.dp.toPx())) * (count - 1))
                                            .toInt().coerceIn(0, count - 1)
                                    onViewportChange(gestureViewport.copy(selectedIndex = range.first + localIndex))
                                }
                                if ((transformed || abs(totalMovement.x) >= viewConfiguration.touchSlop) &&
                                    chartVisibleRange(points.size, gestureViewport.zoom, gestureViewport.pan).first == 0
                                ) {
                                    onReachStart()
                                }
                            }
                        }
                ) {
                    val priceAxisWidth = 62.dp.toPx()
                    val timeAxisHeight = 32.dp.toPx()
                    val plotLeft = 6.dp.toPx()
                    val plotTop = 18.dp.toPx()
                    val plotRight = (size.width - priceAxisWidth).coerceAtLeast(plotLeft + 1f)
                    val plotBottom = (size.height - timeAxisHeight).coerceAtLeast(plotTop + 1f)
                    val plotWidth = plotRight - plotLeft
                    val plotHeight = plotBottom - plotTop

                    val range = chartVisibleRange(points.size, viewport.zoom, viewport.pan)
                    val visibleCount = range.count()
                    val start = range.first
                    val visible = points.subList(start, start + visibleCount)

                    val rawMin = visible.minOf { min(it.low, min(it.open, it.close)) }
                    val rawMax = visible.maxOf { max(it.high, max(it.open, it.close)) }
                    val rawSpan = (rawMax - rawMin).takeIf { it > 0.0 } ?: max(abs(rawMax) * 0.02, 1.0)
                    val paddedMin = rawMin - rawSpan * 0.08
                    val paddedMax = rawMax + rawSpan * 0.08
                    val tickStep = niceTickStep((paddedMax - paddedMin) / 4.0)
                    val axisMin = floor(paddedMin / tickStep) * tickStep
                    val axisMax = ceil(paddedMax / tickStep) * tickStep
                    val priceSpan = (axisMax - axisMin).takeIf { it > 0.0 } ?: 1.0
                    fun priceY(price: Double): Float = plotBottom - (((price - axisMin) / priceSpan).toFloat() * plotHeight)
                    fun yPrice(y: Float): Double = axisMin + ((plotBottom - y) / plotHeight) * priceSpan

                    val gridColor = colors.outlineVariant
                    val labelPaint = android.graphics.Paint().apply {
                        color = colors.onSurfaceVariant.toArgb()
                        textSize = 10.dp.toPx()
                        isAntiAlias = true
                    }
                    val accentPaint = android.graphics.Paint(labelPaint).apply { color = colors.onSurface.toArgb() }

                    drawContext.canvas.nativeCanvas.drawText("最高 ${formatPriceTick(rawMax, tickStep)}", plotLeft, 11.dp.toPx(), accentPaint)
                    val minLabel = "最低 ${formatPriceTick(rawMin, tickStep)}"
                    drawContext.canvas.nativeCanvas.drawText(minLabel, plotRight - accentPaint.measureText(minLabel), 11.dp.toPx(), accentPaint)

                    var priceTick = axisMin
                    while (priceTick <= axisMax + tickStep * 0.25) {
                        val y = priceY(priceTick)
                        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                        drawContext.canvas.nativeCanvas.drawText(formatPriceTick(priceTick, tickStep), plotRight + 6.dp.toPx(), y + labelPaint.textSize * 0.35f, labelPaint)
                        priceTick += tickStep
                    }

                    val timeTickCount = (if (plotWidth < 260.dp.toPx()) 3 else if (plotWidth < 420.dp.toPx()) 4 else 5).coerceAtMost(visible.size)
                    val timeIndices = evenlySpacedIndices(visible.size, timeTickCount)
                    timeIndices.forEachIndexed { tickIndex, index ->
                        val x = if (visible.size == 1) plotLeft else plotLeft + index * plotWidth / (visible.size - 1)
                        drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
                        val text = formatChartTime(visible[index], visible, timeIndices)
                        val textWidth = labelPaint.measureText(text)
                        val textX = when (tickIndex) { 0 -> plotLeft; timeIndices.lastIndex -> plotRight - textWidth; else -> x - textWidth / 2f }
                        drawContext.canvas.nativeCanvas.drawText(text, textX.coerceIn(plotLeft, plotRight - textWidth), plotBottom + 19.dp.toPx(), labelPaint)
                    }

                    val stepX = if (visible.size <= 1) plotWidth else plotWidth / (visible.size - 1)
                    if (displaySettings.showCloseLine) {
                        val closePath = Path()
                        visible.forEachIndexed { index, point ->
                            val x = plotLeft + index * stepX
                            val y = priceY(point.close)
                            if (index == 0) closePath.moveTo(x, y) else closePath.lineTo(x, y)
                        }
                        drawPath(closePath, Color(0xFF58A6FF), style = Stroke(width = 2.dp.toPx()))
                    }

                    if (displaySettings.showCandles) {
                        val candleWidth = (stepX * 0.55f).coerceIn(2.dp.toPx(), 10.dp.toPx())
                        visible.forEachIndexed { index, point ->
                            val x = plotLeft + index * stepX
                            val color = if (point.close >= point.open) STOCK_UP else STOCK_DOWN
                            drawLine(color, Offset(x, priceY(point.high)), Offset(x, priceY(point.low)), strokeWidth = 1.dp.toPx())
                            drawLine(color, Offset(x, priceY(point.open)), Offset(x, priceY(point.close)), strokeWidth = candleWidth)
                        }
                    }

                    if (displaySettings.showTradeMarkers && interval == "15m") {
                        buildTradeMarkers(points, availableShares).forEach { marker ->
                            if (marker.pointIndex !in range) return@forEach
                            val localIndex = marker.pointIndex - start
                            val x = plotLeft + localIndex * stepX
                            val y = priceY(points[marker.pointIndex].close)
                            val color = if (marker.isBuyback) Color(0xFF58A6FF) else Color(0xFFFF4D4F)
                            val radius = tradeMarkerRadius(marker.shares).dp.toPx()
                            drawCircle(color.copy(alpha = 0.22f), radius = radius + 2.dp.toPx(), center = Offset(x, y))
                            drawCircle(color, radius = radius, center = Offset(x, y))
                            if (viewport.selectedIndex == marker.pointIndex) {
                                val text = "${if (marker.isBuyback) "回购" else "售出"} ${marker.shares} 股"
                                val markerPaint = android.graphics.Paint(labelPaint).apply {
                                    this.color = if (marker.isBuyback) android.graphics.Color.rgb(121, 192, 255)
                                    else android.graphics.Color.rgb(255, 123, 114)
                                    textSize = 10.dp.toPx()
                                    isFakeBoldText = true
                                }
                                val textWidth = markerPaint.measureText(text)
                                val labelX = (x - textWidth / 2f).coerceIn(plotLeft, plotRight - textWidth)
                                val labelY = if (marker.isBuyback) {
                                    (y + radius + 13.dp.toPx()).coerceAtMost(plotBottom - 2.dp.toPx())
                                } else {
                                    (y - radius - 7.dp.toPx()).coerceAtLeast(plotTop + markerPaint.textSize)
                                }
                                drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, markerPaint)
                            }
                        }
                    }

                    viewport.selectedIndex?.takeIf { it in range }?.let { selectedIndex ->
                        val index = selectedIndex - start
                        val snappedX = plotLeft + index * stepX
                        val selectedPrice = visible[index].close
                        val y = priceY(selectedPrice)
                        drawLine(colors.onSurface, Offset(snappedX, plotTop), Offset(snappedX, plotBottom), strokeWidth = 1.dp.toPx())
                        drawLine(colors.onSurface, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
                        val priceText = formatPriceTick(selectedPrice, tickStep)
                        val timeText = formatChartTime(visible[index], visible, listOf(0, visible.lastIndex))
                        drawContext.canvas.nativeCanvas.drawText(priceText, plotRight + 5.dp.toPx(), y - 3.dp.toPx(), accentPaint)
                        val timeWidth = accentPaint.measureText(timeText)
                        drawContext.canvas.nativeCanvas.drawText(timeText, (snappedX - timeWidth / 2).coerceIn(plotLeft, plotRight - timeWidth), plotBottom + 30.dp.toPx(), accentPaint)
                    }
                }
            }
        }
    }
}

private data class ChartViewport(
    val zoom: Float = 1f,
    val pan: Float = 0f,
    val selectedIndex: Int? = null
)

private data class ChartDisplaySettings(
    val showCloseLine: Boolean = true,
    val showCandles: Boolean = true,
    val showTradeMarkers: Boolean = true
)

private data class TradeMarker(
    val pointIndex: Int,
    val shares: Long,
    val isBuyback: Boolean
)

private fun tradeMarkerRadius(shares: Long): Float = when {
    shares >= 10_000 -> 8f
    shares >= 1_000 -> 7f
    shares >= 100 -> 6f
    shares >= 10 -> 5f
    else -> 4f
}

private fun chartVisibleRange(size: Int, zoom: Float, pan: Float): IntRange {
    if (size <= 1) return 0..0
    val visibleCount = ceil(size / zoom).toInt().coerceIn(2, size)
    val center = ((size - 1) / 2f + pan)
        .coerceIn((visibleCount - 1) / 2f, size - 1 - (visibleCount - 1) / 2f)
    val start = floor(center - (visibleCount - 1) / 2f).toInt().coerceIn(0, size - visibleCount)
    return start until start + visibleCount
}

private fun buildTradeMarkers(
    kline: List<StockKlinePoint>,
    points: List<StockAvailableSharesPoint>
): List<TradeMarker> {
    val candleIndexByTime = kline.mapIndexedNotNull { index, point -> point.time?.let { it to index } }.toMap()
    return points.sortedBy { it.time }.zipWithNext().mapNotNull { (current, next) ->
        val elapsed = next.time - current.time
        if (elapsed !in MIN_TRADE_SAMPLE_INTERVAL_MS..MAX_TRADE_SAMPLE_INTERVAL_MS) return@mapNotNull null
        val change = next.availableShares - current.availableShares
        val pointIndex = candleIndexByTime[current.time] ?: return@mapNotNull null
        change.takeIf { it != 0L }?.let {
            TradeMarker(pointIndex = pointIndex, shares = abs(it), isBuyback = it > 0)
        }
    }
}

@Composable
private fun AvailableSharesChart(
    kline: List<StockKlinePoint>,
    points: List<StockAvailableSharesPoint>,
    viewportKey: String,
    viewport: ChartViewport,
    onViewportChange: (ChartViewport) -> Unit,
    onReachStart: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = MaterialTheme.colorScheme
    val sharesByTime = remember(points) { points.associate { it.time to it.availableShares } }
    val latestViewport = rememberUpdatedState(viewport)

    Card(colors = CardDefaults.cardColors(containerColor = colors.surface), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("剩余股数趋势", fontWeight = FontWeight.Bold)
            if (kline.isEmpty() || points.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("暂无剩余股数数据", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(viewportKey, kline.size) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                var gestureViewport = latestViewport.value
                                var totalMovement = Offset.Zero
                                var lastPosition = Offset.Zero
                                var transformed = false
                                do {
                                    val event = awaitPointerEvent()
                                    val panChange = event.calculatePan()
                                    val zoomChange = event.calculateZoom()
                                    event.changes.firstOrNull()?.let { lastPosition = it.position }
                                    totalMovement += panChange
                                    if (event.changes.size > 1 || abs(zoomChange - 1f) > 0.01f) transformed = true

                                    if (event.changes.size > 1) {
                                        val nextZoom = (gestureViewport.zoom * zoomChange).coerceIn(1f, max(1f, kline.size / 5f))
                                        val visibleCount = (kline.size / nextZoom).coerceAtLeast(2f)
                                        val maxPan = (kline.size - visibleCount).coerceAtLeast(0f) / 2f
                                        gestureViewport = gestureViewport.copy(
                                            zoom = nextZoom,
                                            pan = (gestureViewport.pan - panChange.x * visibleCount / size.width.coerceAtLeast(1)).coerceIn(-maxPan, maxPan),
                                            selectedIndex = null
                                        )
                                        onViewportChange(gestureViewport)
                                        event.changes.forEach { it.consume() }
                                    } else if (abs(panChange.x) > abs(panChange.y)) {
                                        val visibleCount = (kline.size / gestureViewport.zoom).coerceAtLeast(2f)
                                        val maxPan = (kline.size - visibleCount).coerceAtLeast(0f) / 2f
                                        gestureViewport = gestureViewport.copy(
                                            pan = (gestureViewport.pan - panChange.x * visibleCount / size.width.coerceAtLeast(1)).coerceIn(-maxPan, maxPan),
                                            selectedIndex = null
                                        )
                                        onViewportChange(gestureViewport)
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    }
                                } while (event.changes.any { it.pressed })

                                if (!transformed && totalMovement.getDistance() < viewConfiguration.touchSlop) {
                                    val plotLeft = 6.dp.toPx()
                                    val plotRight = (size.width - 62.dp.toPx()).coerceAtLeast(plotLeft + 1f)
                                    val range = chartVisibleRange(kline.size, gestureViewport.zoom, gestureViewport.pan)
                                    val count = range.count()
                                    val localIndex = if (count <= 1) 0 else
                                        (((lastPosition.x - plotLeft) / (plotRight - plotLeft)) * (count - 1))
                                            .toInt().coerceIn(0, count - 1)
                                    onViewportChange(gestureViewport.copy(selectedIndex = range.first + localIndex))
                                }
                                if ((transformed || abs(totalMovement.x) >= viewConfiguration.touchSlop) &&
                                    chartVisibleRange(kline.size, gestureViewport.zoom, gestureViewport.pan).first == 0
                                ) {
                                    onReachStart()
                                }
                            }
                        }
                ) {
                    val axisWidth = 62.dp.toPx()
                    val plotLeft = 6.dp.toPx()
                    val plotTop = 6.dp.toPx()
                    val plotRight = (size.width - axisWidth).coerceAtLeast(plotLeft + 1f)
                    val plotBottom = (size.height - 8.dp.toPx()).coerceAtLeast(plotTop + 1f)
                    val plotWidth = plotRight - plotLeft
                    val plotHeight = plotBottom - plotTop
                    val range = chartVisibleRange(kline.size, viewport.zoom, viewport.pan)
                    val visible = range.map { index -> kline[index].time?.let(sharesByTime::get) }
                    val values = visible.filterNotNull()
                    if (values.isEmpty()) return@Canvas

                    val rawMin = values.minOrNull()!!.toDouble()
                    val rawMax = values.maxOrNull()!!.toDouble()
                    val span = (rawMax - rawMin).takeIf { it > 0.0 } ?: max(abs(rawMax) * 0.02, 1.0)
                    val axisMin = rawMin - span * 0.08
                    val axisMax = rawMax + span * 0.08
                    fun shareY(value: Long): Float = plotBottom - (((value - axisMin) / (axisMax - axisMin)).toFloat() * plotHeight)

                    val gridColor = colors.outlineVariant
                    val labelPaint = android.graphics.Paint().apply {
                        color = colors.onSurfaceVariant.toArgb()
                        textSize = 9.dp.toPx()
                        isAntiAlias = true
                    }
                    repeat(3) { tick ->
                        val fraction = tick / 2f
                        val y = plotBottom - fraction * plotHeight
                        val value = axisMin + fraction * (axisMax - axisMin)
                        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
                        drawContext.canvas.nativeCanvas.drawText(value.toLong().toString(), plotRight + 5.dp.toPx(), y + labelPaint.textSize * 0.35f, labelPaint)
                    }

                    val stepX = if (visible.size <= 1) plotWidth else plotWidth / (visible.size - 1)
                    var pathStarted = false
                    val linePath = Path()
                    visible.forEachIndexed { index, value ->
                        if (value == null) {
                            pathStarted = false
                        } else {
                            val x = plotLeft + index * stepX
                            val y = shareY(value)
                            if (!pathStarted) {
                                linePath.moveTo(x, y)
                                pathStarted = true
                            } else {
                                linePath.lineTo(x, y)
                            }
                            drawCircle(colors.primary, 2.dp.toPx(), Offset(x, y))
                        }
                    }
                    drawPath(linePath, colors.primary, style = Stroke(width = 1.5.dp.toPx()))

                    viewport.selectedIndex?.takeIf { it in range }?.let { selectedIndex ->
                        val localIndex = selectedIndex - range.first
                        val x = plotLeft + localIndex * stepX
                        drawLine(colors.onSurface, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
                        visible[localIndex]?.let { value ->
                            val text = "$value 股"
                            val textWidth = labelPaint.measureText(text)
                            drawContext.canvas.nativeCanvas.drawText(
                                text,
                                (x - textWidth / 2f).coerceIn(plotLeft, plotRight - textWidth),
                                (shareY(value) - 5.dp.toPx()).coerceAtLeast(plotTop + labelPaint.textSize),
                                labelPaint
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val MIN_TRADE_SAMPLE_INTERVAL_MS = 10 * 60 * 1000L
private const val MAX_TRADE_SAMPLE_INTERVAL_MS = 25 * 60 * 1000L
private const val CHART_SETTINGS_FILE = "chart-display.properties"

private fun loadChartDisplaySettings(pluginDataDir: File): ChartDisplaySettings {
    val values = runCatching {
        pluginDataDir.resolve(CHART_SETTINGS_FILE)
            .takeIf(File::isFile)
            ?.readLines()
            ?.mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) null else line.substring(0, separator) to line.substring(separator + 1)
            }
            ?.toMap()
            .orEmpty()
    }.getOrDefault(emptyMap())
    return ChartDisplaySettings(
        showCloseLine = values["showCloseLine"]?.toBooleanStrictOrNull() ?: true,
        showCandles = values["showCandles"]?.toBooleanStrictOrNull() ?: true,
        showTradeMarkers = values["showTradeMarkers"]?.toBooleanStrictOrNull() ?: true
    )
}

private fun saveChartDisplaySettings(pluginDataDir: File, settings: ChartDisplaySettings) {
    pluginDataDir.mkdirs()
    pluginDataDir.resolve(CHART_SETTINGS_FILE).writeText(
        buildString {
            appendLine("showCloseLine=${settings.showCloseLine}")
            appendLine("showCandles=${settings.showCandles}")
            appendLine("showTradeMarkers=${settings.showTradeMarkers}")
        }
    )
}

private fun niceTickStep(rawStep: Double): Double {
    if (!rawStep.isFinite() || rawStep <= 0.0) return 1.0
    val exponent = floor(log10(rawStep))
    val magnitude = 10.0.pow(exponent)
    val normalized = rawStep / magnitude
    val nice = when {
        normalized <= 1.0 -> 1.0
        normalized <= 2.0 -> 2.0
        normalized <= 2.5 -> 2.5
        normalized <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

private fun formatPriceTick(value: Double, step: Double): String = when {
    step >= 100 -> "%.0f".format(value)
    step >= 1 -> "%.1f".format(value)
    else -> "%.2f".format(value)
}

private fun evenlySpacedIndices(size: Int, count: Int): List<Int> {
    if (size <= 1) return listOf(0)
    val safeCount = count.coerceIn(2, size)
    return (0 until safeCount)
        .map { ((size - 1) * it.toFloat() / (safeCount - 1)).toInt() }
        .distinct()
}

private fun formatChartTime(
    point: StockKlinePoint,
    visible: List<StockKlinePoint>,
    tickIndices: List<Int>
): String {
    val epoch = point.time ?: return point.bucket?.takeLast(11) ?: "--"
    val firstTime = visible.getOrNull(tickIndices.firstOrNull() ?: 0)?.time
    val lastTime = visible.getOrNull(tickIndices.lastOrNull() ?: visible.lastIndex)?.time
    val crossesDay = firstTime != null && lastTime != null &&
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(firstTime)) !=
        SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date(lastTime))
    val pattern = if (crossesDay) "MM-dd HH:mm" else "HH:mm"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(epoch))
}
@Composable
private fun TradePanel(
    action: String,
    amount: String,
    balance: Double?,
    liveInfo: LiveStockInfo?,
    holdings: List<StockHolding>,
    serverId: Int?,
    onActionChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onMoney: () -> Unit,
    onQueryPrice: () -> Unit,
    onSend: () -> Unit
) {
    val colors = MaterialTheme.colorScheme
    val holdingShares = holdings.firstOrNull { it.marketId == serverId || it.companyName == liveInfo?.name }?.shares
    val estimated = amount.toLongOrNull()?.let { shares -> liveInfo?.price?.times(shares) }
    Card(colors = CardDefaults.cardColors(containerColor = colors.surface)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("交易", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("股票 ID ${serverId ?: "--"}", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onActionChange("buy") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (action == "buy") STOCK_UP else colors.surfaceVariant, contentColor = if (action == "buy") Color.White else colors.onSurfaceVariant)
                ) { Text("买入") }
                Button(
                    onClick = { onActionChange("sell") },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = if (action == "sell") STOCK_DOWN else colors.surfaceVariant, contentColor = if (action == "sell") Color.White else colors.onSurfaceVariant)
                ) { Text("卖出") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column { Text("实时价格", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant); Text(liveInfo?.price?.let { "%.2f 元".format(it) } ?: "点击查价", fontWeight = FontWeight.Bold) }
                Column(horizontalAlignment = Alignment.End) { Text(if (action == "buy") "可用资金" else "可卖数量", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant); Text(if (action == "buy") formatMoney(balance) else holdingShares?.let { "$it 股" } ?: "--", fontWeight = FontWeight.Bold) }
            }
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                label = { Text("委托数量（股）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = colors.onSurface,
                    unfocusedTextColor = colors.onSurface,
                    cursorColor = colors.primary,
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.outline,
                    focusedLabelColor = colors.primary,
                    unfocusedLabelColor = colors.onSurfaceVariant
                )
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("预估金额", color = colors.onSurfaceVariant)
                Text(formatMoney(estimated), fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onQueryPrice, enabled = serverId != null, modifier = Modifier.weight(1f)) { Text("刷新报价") }
                OutlinedButton(onClick = onMoney, modifier = Modifier.weight(1f)) { Text("刷新资金") }
            }
            Button(
                onClick = onSend,
                enabled = serverId != null && !amount.isBlank(),
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = if (action == "buy") STOCK_UP else STOCK_DOWN)
            ) { Text(if (action == "buy") "确认买入" else "确认卖出") }
        }
    }
}

private fun formatMoney(value: Double?): String = value?.let { "%.2f 元".format(it) } ?: "--"
private fun formatMoneyNumber(value: Double?): String = value?.let { "%.2f".format(it) } ?: "--"
private fun formatPnl(value: Double?): String = value?.let { "%+.2f 元".format(it) } ?: "未记录"
private fun formatPercent(value: Double?): String = value?.let { "%+.2f%%".format(it) } ?: "未记录"
private fun pnlColor(value: Double?): Color = when {
    value == null -> Color.Unspecified
    value >= 0.0 -> STOCK_UP
    else -> STOCK_DOWN
}

private fun mergeKlinePoints(
    existing: List<StockKlinePoint>,
    incoming: List<StockKlinePoint>
): List<StockKlinePoint> {
    val pointsByTime = linkedMapOf<Long, StockKlinePoint>()
    (existing + incoming).forEach { point -> point.time?.let { pointsByTime[it] = point } }
    return pointsByTime.values.sortedBy { it.time }
}

private fun mergeAvailableShares(
    existing: List<StockAvailableSharesPoint>,
    incoming: List<StockAvailableSharesPoint>
): List<StockAvailableSharesPoint> {
    val pointsByTime = linkedMapOf<Long, StockAvailableSharesPoint>()
    (existing + incoming).forEach { pointsByTime[it.time] = it }
    return pointsByTime.values.sortedBy { it.time }
}

private fun viewportAfterPrepend(
    viewport: ChartViewport,
    oldSize: Int,
    newSize: Int,
    prependedCount: Int
): ChartViewport {
    if (oldSize <= 1) return ChartViewport()
    if (newSize <= oldSize || prependedCount <= 0) return viewport

    val zoom = viewport.zoom.coerceAtLeast(1f)
    val oldVisibleCount = ceil(oldSize / zoom).toInt().coerceIn(2, oldSize)
    val oldHalfSpan = (oldVisibleCount - 1) / 2f
    val oldCenter = ((oldSize - 1) / 2f + viewport.pan)
        .coerceIn(oldHalfSpan, oldSize - 1 - oldHalfSpan)
    val newZoom = (newSize.toFloat() / oldVisibleCount)
        .coerceIn(1f, max(1f, newSize / 5f))
    val newVisibleCount = ceil(newSize / newZoom).toInt().coerceIn(2, newSize)
    val newHalfSpan = (newVisibleCount - 1) / 2f
    val newCenter = (oldCenter + prependedCount)
        .coerceIn(newHalfSpan, newSize - 1 - newHalfSpan)

    return viewport.copy(
        zoom = newZoom,
        pan = newCenter - (newSize - 1) / 2f,
        selectedIndex = viewport.selectedIndex?.plus(prependedCount)
    )
}

private val STOCK_UP = Color(0xFF16A36A)
private val STOCK_DOWN = Color(0xFFE64545)
private val STOCK_GOLD = Color(0xFFFFB020)
private val PORTFOLIO_CARD = Color(0xFF285F9E)
private val ALLOCATION_COLORS = listOf(
    Color(0xFFE64545), Color(0xFFEA8B3A), Color(0xFF4B7BE5),
    Color(0xFF8A5CD6), Color(0xFF16A36A), Color(0xFF2C9DB7)
)

@Composable
private fun riskColor(riskLevel: Int?): Color = when (riskLevel) {
    1 -> if (isSystemInDarkTheme()) Color(0xFF58A6FF) else Color(0xFF0969DA)
    2 -> if (isSystemInDarkTheme()) Color(0xFF3FB950) else Color(0xFF1A7F37)
    3 -> if (isSystemInDarkTheme()) Color(0xFFD29922) else Color(0xFF9A6700)
    4 -> if (isSystemInDarkTheme()) Color(0xFFDB6D28) else Color(0xFFBC4C00)
    5 -> if (isSystemInDarkTheme()) Color(0xFFF85149) else Color(0xFFCF222E)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StockMarketTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) STOCK_DARK_COLORS else STOCK_LIGHT_COLORS
    MaterialTheme(colorScheme = colorScheme, content = content)
}

private val STOCK_DARK_COLORS: ColorScheme = darkColorScheme(
    primary = Color(0xFF49BCE8),
    onPrimary = Color(0xFF071017),
    secondary = Color(0xFF31D69B),
    onSecondary = Color(0xFF071017),
    secondaryContainer = Color(0xFF182C3B),
    onSecondaryContainer = Color(0xFFD8F1FF),
    tertiary = Color(0xFFE8B85B),
    background = Color(0xFF090D12),
    onBackground = Color(0xFFEEF3F8),
    surface = Color(0xFF111821),
    onSurface = Color(0xFFEEF3F8),
    surfaceVariant = Color(0xFF151E29),
    onSurfaceVariant = Color(0xFF8390A3),
    outline = Color(0xFF344358),
    outlineVariant = Color(0xFF263143)
)

private val STOCK_LIGHT_COLORS: ColorScheme = lightColorScheme(
    primary = Color(0xFF0783B5),
    onPrimary = Color.White,
    secondary = Color(0xFF168A67),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD9F3EA),
    onSecondaryContainer = Color(0xFF0D4B3A),
    tertiary = Color(0xFF9A6700),
    background = Color(0xFFF3F6F8),
    onBackground = Color(0xFF17202A),
    surface = Color.White,
    onSurface = Color(0xFF17202A),
    surfaceVariant = Color(0xFFE8EEF2),
    onSurfaceVariant = Color(0xFF617083),
    outline = Color(0xFF8798A8),
    outlineVariant = Color(0xFFC8D3DD)
)
