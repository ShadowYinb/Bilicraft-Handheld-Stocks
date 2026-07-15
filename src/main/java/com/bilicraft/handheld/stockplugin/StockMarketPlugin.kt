package com.bilicraft.handheld.stockplugin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bilicraft.handheld.pluginapi.BH_PLUGIN_API_VERSION
import com.bilicraft.handheld.pluginapi.BhPlugin
import com.bilicraft.handheld.pluginapi.BhPluginDescriptor
import com.bilicraft.handheld.pluginapi.BhPluginEntrypoint
import com.bilicraft.handheld.pluginapi.BhPluginHost
import com.bilicraft.handheld.pluginapi.BhPluginPanel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
        version = "0.1.7",
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
            StockMarketPanel(host = host, onClose = onClose)
        }
    }

    override fun onLoad(host: BhPluginHost) {
        host.log("股市插件已加载")
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
    var state by remember { mutableStateOf(StockUiState(loading = true)) }
    var action by remember { mutableStateOf("buy") }
    var amount by remember { mutableStateOf("") }
    var chartViewport by remember { mutableStateOf(ChartViewport()) }
    val pageScrollState = rememberScrollState()

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

    fun reloadKline() {
        scope.launch {
            val companyId = state.selectedCompanyId ?: return@launch
            runCatching { repository.fetchChartData(companyId, state.selectedInterval) }
                .onSuccess { data ->
                    state = state.copy(
                        loading = false,
                        kline = data.kline,
                        availableShares = data.availableShares,
                        lastError = null
                    )
                }
                .onFailure { error -> state = state.copy(loading = false, lastError = error.message ?: "K线加载失败") }
        }
    }

    fun refreshAll() {
        scope.launch {
            state = state.copy(loading = true, lastError = null)
            runCatching {
                val companies = repository.fetchCompanies().filter { it.latestPrice != null }
                val selected = state.selectedCompanyId
                    ?.takeIf { id -> companies.any { it.id == id } }
                    ?: companies.firstOrNull { it.latestPrice != 0.0 }?.id
                    ?: companies.firstOrNull()?.id
                val chartData = selected?.let { repository.fetchChartData(it, state.selectedInterval) }
                    ?: StockMarketChartData(emptyList(), emptyList())
                Triple(companies, chartData, repository.fetchHealth())
            }.onSuccess { (companies, chartData, health) ->
                val selectedId = state.selectedCompanyId?.takeIf { id -> companies.any { it.id == id } }
                    ?: companies.firstOrNull { it.latestPrice != 0.0 }?.id
                    ?: companies.firstOrNull()?.id
                val selectedName = companies.firstOrNull { it.id == selectedId }?.name
                state = state.copy(
                    loading = false,
                    companies = companies,
                    selectedCompanyId = selectedId,
                    kline = chartData.kline,
                    availableShares = chartData.availableShares,
                    health = health,
                    mappedServerId = gateway.mappings.value.firstOrNull { it.companyName == selectedName }?.serverId,
                    liveInfo = state.liveInfo?.takeIf { it.name == selectedName }
                )
            }.onFailure { error ->
                state = state.copy(loading = false, lastError = error.message ?: "加载失败")
            }
        }
    }

    LaunchedEffect(gateway) {
        combine(gateway.money, gateway.mappings) { money, mappings -> money to mappings }
            .collect { (money, mappings) ->
                val selectedName = selectedCompany()?.name
                state = state.copy(
                    walletBalance = money,
                    mappedServerId = mappings.firstOrNull { it.companyName == selectedName }?.serverId
                )
            }
    }

    LaunchedEffect(Unit) {
        refreshAll()
        if (gateway.isConnected()) {
            gateway.refreshMoney()
            state = state.copy(portfolioLoading = true)
            gateway.queryPortfolio()
                .onSuccess { holdings -> state = state.copy(portfolioLoading = false, holdings = holdings) }
                .onFailure { state = state.copy(portfolioLoading = false) }
        }
    }

    LaunchedEffect(state.selectedCompanyId, state.selectedInterval) {
        chartViewport = ChartViewport()
    }

    BackHandler(onBack = onClose)

    Scaffold(
        containerColor = Color.Black,
        contentColor = Color.White
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
                .verticalScroll(pageScrollState),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("退出")
                }
                OutlinedButton(onClick = ::refreshAll, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("刷新")
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.lastError?.let { ErrorCard("数据加载失败：$it") }
            StockPickerList(
                companies = state.companies,
                holdings = state.holdings,
                selectedId = state.selectedCompanyId,
                onSelect = { companyId ->
                    val companyName = state.companies.firstOrNull { it.id == companyId }?.name
                    state = state.copy(
                        selectedCompanyId = companyId,
                        mappedServerId = gateway.mappings.value.firstOrNull { it.companyName == companyName }?.serverId,
                        liveInfo = null,
                        loading = true,
                        lastError = null
                    )
                    reloadKline()
                }
            )
            StockSummary(selectedCompany())
            IntervalPicker(
                selected = state.selectedInterval,
                onSelect = { interval ->
                    state = state.copy(selectedInterval = interval, loading = true, lastError = null)
                    reloadKline()
                }
            )
            CandleChart(
                points = state.kline,
                availableShares = state.availableShares,
                interval = state.selectedInterval,
                viewportKey = "${state.selectedCompanyId}:${state.selectedInterval}",
                viewport = chartViewport,
                onViewportChange = { chartViewport = it },
                pageScrollState = pageScrollState,
                modifier = Modifier.fillMaxWidth().height(330.dp)
            )
            AvailableSharesChart(
                kline = state.kline,
                points = state.availableShares,
                viewportKey = "${state.selectedCompanyId}:${state.selectedInterval}",
                viewport = chartViewport,
                onViewportChange = { chartViewport = it },
                modifier = Modifier.fillMaxWidth().height(150.dp)
            )
            TradePanel(
                action = action,
                amount = amount,
                balance = state.walletBalance,
                liveInfo = state.liveInfo,
                holdings = state.holdings,
                portfolioLoading = state.portfolioLoading,
                mappedServerId = state.mappedServerId,
                syncing = state.syncInProgress,
                onActionChange = { action = it },
                onAmountChange = { amount = it.filter(Char::isDigit) },
                onMoney = { scope.launch { showResult(gateway.refreshMoney()) } },
                onSync = {
                    scope.launch {
                        state = state.copy(syncInProgress = true)
                        val result = gateway.syncMappings(state.companies)
                        val selectedName = selectedCompany()?.name
                        state = state.copy(
                            syncInProgress = false,
                            mappedServerId = gateway.mappings.value.firstOrNull { it.companyName == selectedName }?.serverId
                        )
                        showResult(result)
                    }
                },
                onPortfolio = {
                    scope.launch {
                        state = state.copy(portfolioLoading = true)
                        gateway.queryPortfolio()
                            .onSuccess { holdings ->
                                state = state.copy(
                                    portfolioLoading = false,
                                    holdings = holdings,
                                    dialogMessage = if (holdings.isEmpty()) "当前没有持股。" else null
                                )
                            }
                            .onFailure { error ->
                                state = state.copy(portfolioLoading = false, dialogMessage = "持股查询失败：${error.message}")
                            }
                    }
                },
                onQueryPrice = {
                    val serverId = state.mappedServerId
                    if (serverId == null) {
                        state = state.copy(dialogMessage = "当前公司尚未建立服务器 ID 映射，请先点击“同步股票映射”。")
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
                    val serverId = state.mappedServerId
                    when {
                        serverId == null -> state = state.copy(dialogMessage = "当前公司尚未建立服务器 ID 映射。")
                        amountValue == null || amountValue <= 0 -> state = state.copy(dialogMessage = "请输入有效的交易数量。")
                        else -> scope.launch {
                            gateway.queryCompanyInfo(serverId)
                                .onFailure { error -> state = state.copy(dialogMessage = "交易前实时价格查询失败：${error.message}") }
                                .onSuccess { info ->
                                    state = state.copy(liveInfo = info)
                                    val tradeResult = gateway.trade(action, serverId, amountValue)
                                    if (tradeResult is StockCommandResult.Success) {
                                        gateway.refreshMoney()
                                        gateway.queryPortfolio()
                                    }
                                    showResult(tradeResult)
                                }
                        }
                    }
                }
            )
            state.health?.let {
                Text(
                    "数据源：${StockMarketRepository.DEFAULT_BASE_URL} · ${it.companies} 家公司 · ${it.prices} 条价格记录",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
}

@Composable
private fun ErrorCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun StockPickerList(
    companies: List<StockCompany>,
    holdings: List<StockHolding>,
    selectedId: Int?,
    onSelect: (Int) -> Unit
) {
    val listScrollState = rememberScrollState()
    val holdingByName = remember(holdings) { holdings.associateBy { it.companyName } }
    val consumeNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource
            ): Offset = available

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = available
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = PANEL_BG)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("股票列表", fontWeight = FontWeight.Bold)
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp)
                    .nestedScroll(consumeNestedScroll)
                    .verticalScroll(listScrollState),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                companies.forEach { company ->
                    val holding = holdingByName[company.name]
                    val selected = company.id == selectedId
                    val change = company.changePct
                    val changeColor = when {
                        change == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        change >= 0 -> STOCK_UP
                        else -> STOCK_DOWN
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (selected) Color(0xFF13233A) else Color(0xFF111111), RoundedCornerShape(8.dp))
                            .clickable { onSelect(company.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(company.id.toString(), color = Color.LightGray, style = MaterialTheme.typography.labelSmall)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(company.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                            Text(
                                buildString {
                                    append("${company.status ?: "--"} · 风险 ${company.riskLevel ?: "--"}")
                                    if (holding != null) append(" · 持有 ${holding.shares} 股")
                                },
                                color = if (holding != null) Color(0xFFFFC857) else MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(company.latestPrice?.let { "%.2f".format(it) } ?: "--", fontWeight = FontWeight.Bold)
                            Text(change?.let { "%+.2f%%".format(it) } ?: "--", color = changeColor, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StockSummary(company: StockCompany?) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(company?.name ?: "暂无股票", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Text("状态：${company?.status ?: "--"} · 风险 ${company?.riskLevel ?: "--"}")
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
                        change == null -> MaterialTheme.colorScheme.onSurfaceVariant
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
            if (interval == selected) Button(onClick = { onSelect(interval) }) { Text(interval) }
            else OutlinedButton(onClick = { onSelect(interval) }) { Text(interval) }
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
    pageScrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier
) {
    var showCloseLine by remember(viewportKey) { mutableStateOf(true) }
    var showCandles by remember(viewportKey) { mutableStateOf(true) }
    var showTradeMarkers by remember(viewportKey) { mutableStateOf(true) }
    val latestViewport = rememberUpdatedState(viewport)

    Card(colors = CardDefaults.cardColors(containerColor = PANEL_BG), modifier = modifier) {
        Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("K 线", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = { showCloseLine = !showCloseLine },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(if (showCloseLine) "隐藏折线" else "显示折线", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showCandles = !showCandles },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(if (showCandles) "隐藏K线" else "显示K线", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
                OutlinedButton(
                    onClick = { showTradeMarkers = !showTradeMarkers },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        if (showTradeMarkers) "隐藏售出/回购" else "显示售出/回购",
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

                    val gridColor = Color(0xFF30343A)
                    val labelPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
                        textSize = 10.dp.toPx()
                        isAntiAlias = true
                    }
                    val accentPaint = android.graphics.Paint(labelPaint).apply { color = android.graphics.Color.WHITE }

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
                    if (showCloseLine) {
                        val closePath = Path()
                        visible.forEachIndexed { index, point ->
                            val x = plotLeft + index * stepX
                            val y = priceY(point.close)
                            if (index == 0) closePath.moveTo(x, y) else closePath.lineTo(x, y)
                        }
                        drawPath(closePath, Color(0xFF58A6FF), style = Stroke(width = 2.dp.toPx()))
                    }

                    if (showCandles) {
                        val candleWidth = (stepX * 0.55f).coerceIn(2.dp.toPx(), 10.dp.toPx())
                        visible.forEachIndexed { index, point ->
                            val x = plotLeft + index * stepX
                            val color = if (point.close >= point.open) STOCK_UP else STOCK_DOWN
                            drawLine(color, Offset(x, priceY(point.high)), Offset(x, priceY(point.low)), strokeWidth = 1.dp.toPx())
                            drawLine(color, Offset(x, priceY(point.open)), Offset(x, priceY(point.close)), strokeWidth = candleWidth)
                        }
                    }

                    if (showTradeMarkers && interval == "15m") {
                        buildTradeMarkers(points, availableShares).forEachIndexed { markerIndex, marker ->
                            if (marker.pointIndex !in range) return@forEachIndexed
                            val localIndex = marker.pointIndex - start
                            val x = plotLeft + localIndex * stepX
                            val y = priceY(points[marker.pointIndex].close)
                            val color = if (marker.isBuyback) Color(0xFF58A6FF) else Color(0xFFFF4D4F)
                            drawCircle(color, radius = 4.dp.toPx(), center = Offset(x, y))
                            val text = "${if (marker.isBuyback) "回购" else "售出"}${marker.shares}股"
                            val markerPaint = android.graphics.Paint(labelPaint).apply {
                                this.color = if (marker.isBuyback) android.graphics.Color.rgb(121, 192, 255)
                                else android.graphics.Color.rgb(255, 123, 114)
                                textSize = 9.dp.toPx()
                            }
                            val textWidth = markerPaint.measureText(text)
                            val labelX = (x - textWidth / 2f).coerceIn(plotLeft, plotRight - textWidth)
                            val offset = (12 + (markerIndex % 2) * 12).dp.toPx()
                            val labelY = if (marker.isBuyback) (y + offset).coerceAtMost(plotBottom - 2.dp.toPx())
                            else (y - offset).coerceAtLeast(plotTop + markerPaint.textSize)
                            drawContext.canvas.nativeCanvas.drawText(text, labelX, labelY, markerPaint)
                        }
                    }

                    viewport.selectedIndex?.takeIf { it in range }?.let { selectedIndex ->
                        val index = selectedIndex - start
                        val snappedX = plotLeft + index * stepX
                        val selectedPrice = visible[index].close
                        val y = priceY(selectedPrice)
                        drawLine(Color.White, Offset(snappedX, plotTop), Offset(snappedX, plotBottom), strokeWidth = 1.dp.toPx())
                        drawLine(Color.White, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1.dp.toPx())
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

private data class TradeMarker(
    val pointIndex: Int,
    val shares: Long,
    val isBuyback: Boolean
)

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
        if (next.time - current.time != FIFTEEN_MINUTES_MS) return@mapNotNull null
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
    modifier: Modifier = Modifier
) {
    val sharesByTime = remember(points) { points.associate { it.time to it.availableShares } }
    val latestViewport = rememberUpdatedState(viewport)

    Card(colors = CardDefaults.cardColors(containerColor = PANEL_BG), modifier = modifier) {
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

                    val gridColor = Color(0xFF30343A)
                    val labelPaint = android.graphics.Paint().apply {
                        color = android.graphics.Color.LTGRAY
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
                            drawCircle(Color(0xFF58A6FF), 2.dp.toPx(), Offset(x, y))
                        }
                    }
                    drawPath(linePath, Color(0xFF58A6FF), style = Stroke(width = 1.5.dp.toPx()))

                    viewport.selectedIndex?.takeIf { it in range }?.let { selectedIndex ->
                        val localIndex = selectedIndex - range.first
                        val x = plotLeft + localIndex * stepX
                        drawLine(Color.White, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1.dp.toPx())
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

private const val FIFTEEN_MINUTES_MS = 15 * 60 * 1000L

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
    portfolioLoading: Boolean,
    mappedServerId: Int?,
    syncing: Boolean,
    onActionChange: (String) -> Unit,
    onAmountChange: (String) -> Unit,
    onMoney: () -> Unit,
    onSync: () -> Unit,
    onPortfolio: () -> Unit,
    onQueryPrice: () -> Unit,
    onSend: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = PANEL_BG)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("交易命令", fontWeight = FontWeight.Bold)
            Text("服务器股票 ID：${mappedServerId?.toString() ?: "未映射"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            liveInfo?.let {
                Text("实时：${it.name} · ${"%.2f".format(it.price)} · ${it.status ?: "--"}")
                Text(
                    "可用股数：${it.availableShares?.toString() ?: "--"}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            balance?.let { Text("钱包：${"%.2f".format(it)} 帕元") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action == "buy") Button(onClick = { onActionChange("buy") }) { Text("买入") }
                else OutlinedButton(onClick = { onActionChange("buy") }) { Text("买入") }
                if (action == "sell") Button(onClick = { onActionChange("sell") }) { Text("卖出") }
                else OutlinedButton(onClick = { onActionChange("sell") }) { Text("卖出") }
            }
            OutlinedTextField(
                value = amount,
                onValueChange = onAmountChange,
                label = { Text("数量") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSend, enabled = mappedServerId != null) { Text("发送") }
                OutlinedButton(onClick = onQueryPrice, enabled = mappedServerId != null) { Text("查价") }
                OutlinedButton(onClick = onMoney) { Text("资金") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSync, enabled = !syncing) { Text(if (syncing) "同步中" else "同步映射") }
                OutlinedButton(onClick = onPortfolio, enabled = !portfolioLoading) { Text(if (portfolioLoading) "查询中" else "持股") }
            }
            if (holdings.isNotEmpty()) {
                val totalShares = holdings.sumOf { it.shares }
                val totalHoldingValue = holdings.sumOf { it.totalValue }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("持股汇总", style = MaterialTheme.typography.titleSmall)
                    Text("总计：$totalShares 股 · ${"%.2f".format(totalHoldingValue)} 帕元")
                    holdings.forEach { holding ->
                        Text("${holding.companyName}: ${holding.shares} 股 · ${"%.2f".format(holding.totalValue)}")
                    }
                }
            }
        }
    }
}

private val PANEL_BG = Color(0xFF151515)
private val STOCK_UP = Color(0xFF3FB950)
private val STOCK_DOWN = Color(0xFFF85149)
