package com.bilicraft.handheld.stockplugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StockCompany(
    val id: Int,
    val name: String,
    @SerialName("risk_level") val riskLevel: Int? = null,
    val status: String? = null,
    @SerialName("latest_price") val latestPrice: Double? = null,
    @SerialName("change_pct") val changePct: Double? = null
)

@Serializable
data class StockKlineResponse(
    @SerialName("company_id") val companyId: Int,
    val interval: String,
    val data: List<StockKlinePoint>
)

@Serializable
data class StockKlinePoint(
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Int = 0,
    val time: Long? = null,
    val bucket: String? = null
)

@Serializable
data class StockAvailableSharesResponse(
    @SerialName("company_id") val companyId: Int,
    val interval: String,
    val data: List<StockAvailableSharesPoint>
)

@Serializable
data class StockAvailableSharesPoint(
    @SerialName("available_shares") val availableShares: Long,
    val time: Long,
    val bucket: String? = null
)

data class StockMarketChartData(
    val kline: List<StockKlinePoint>,
    val availableShares: List<StockAvailableSharesPoint>
)

@Serializable
data class StockHealthResponse(
    val companies: Int,
    val prices: Int,
    @SerialName("latest_price_at") val latestPriceAt: String? = null,
    @SerialName("latest_cash_at") val latestCashAt: String? = null,
    @SerialName("last_update_marker") val lastUpdateMarker: String? = null
)

data class StockHolding(
    val companyName: String,
    val shares: Long,
    val totalValue: Double
)

data class LiveStockInfo(
    val id: Int,
    val name: String,
    val price: Double,
    val status: String? = null,
    val availableShares: Long? = null
)

@Serializable
data class StockIdMapping(val companyName: String, val serverId: Int)

data class StockUiState(
    val loading: Boolean = false,
    val companies: List<StockCompany> = emptyList(),
    val selectedCompanyId: Int? = null,
    val selectedInterval: String = "15m",
    val kline: List<StockKlinePoint> = emptyList(),
    val availableShares: List<StockAvailableSharesPoint> = emptyList(),
    val health: StockHealthResponse? = null,
    val walletBalance: Double? = null,
    val liveInfo: LiveStockInfo? = null,
    val holdings: List<StockHolding> = emptyList(),
    val portfolioLoading: Boolean = false,
    val mappedServerId: Int? = null,
    val syncInProgress: Boolean = false,
    val dialogMessage: String? = null,
    val lastError: String? = null
)

sealed interface StockCommandResult {
    data class Success(val message: String) : StockCommandResult
    data class Failure(val reason: String) : StockCommandResult
    data class Timeout(val command: String) : StockCommandResult
    data class NotConnected(val reason: String = "请先连接 Minecraft 服务器") : StockCommandResult
}
