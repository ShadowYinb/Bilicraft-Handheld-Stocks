package com.bilicraft.handheld.stockplugin

import com.bilicraft.handheld.pluginapi.BhPluginHost
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class StockMarketRepository(
    private val host: BhPluginHost,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun fetchCompanies(): List<StockCompany> = getJson("/api/companies")

    suspend fun fetchChartData(
        companyId: Int,
        interval: String,
        before: String? = null
    ): StockMarketChartData = coroutineScope {
        require(interval in SUPPORTED_INTERVALS) { "Unsupported stock interval: $interval" }
        require(before == null || before.isNotBlank()) { "The history cursor must not be blank" }

        val query = buildString {
            append("?interval=").append(interval)
            before?.let {
                append("&before=")
                    .append(URLEncoder.encode(it, StandardCharsets.UTF_8.name()))
            }
        }
        val kline = async {
            getJson<StockKlineResponse>("/api/kline/$companyId$query")
        }
        val availableShares = async {
            getJson<StockAvailableSharesResponse>("/api/available-shares/$companyId$query")
        }
        val klineResponse = kline.await()
        val sharesResponse = availableShares.await()
        require(klineResponse.companyId == companyId && klineResponse.interval == interval) {
            "K-line response does not match the requested stock and interval"
        }
        require(sharesResponse.companyId == companyId && sharesResponse.interval == interval) {
            "Available-shares response does not match the requested stock and interval"
        }
        StockMarketChartData(
            kline = klineResponse.data,
            availableShares = sharesResponse.data,
            segmentStart = klineResponse.segmentStart,
            hasMore = klineResponse.hasMore
        )
    }

    suspend fun fetchHealth(): StockHealthResponse = getJson("/api/health")

    private suspend inline fun <reified T> getJson(path: String): T {
        val body = host.httpGet(baseUrl.trimEnd('/') + path)
        return json.decodeFromString<T>(body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.pleasance.icu"
        val SUPPORTED_INTERVALS = listOf("15m", "1h", "4h", "24h")

        fun intervalLabel(interval: String): String = if (interval == "15m") "单次采集" else interval
    }
}
