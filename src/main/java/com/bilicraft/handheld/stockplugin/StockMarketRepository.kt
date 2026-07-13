package com.bilicraft.handheld.stockplugin

import com.bilicraft.handheld.pluginapi.BhPluginHost
import kotlinx.serialization.json.Json

class StockMarketRepository(
    private val host: BhPluginHost,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun fetchCompanies(): List<StockCompany> = getJson("/api/companies")

    suspend fun fetchKline(companyId: Int, interval: String): List<StockKlinePoint> =
        getJson<StockKlineResponse>("/api/kline/$companyId?interval=$interval").data

    suspend fun fetchHealth(): StockHealthResponse = getJson("/api/health")

    private suspend inline fun <reified T> getJson(path: String): T {
        val body = host.httpGet(baseUrl.trimEnd('/') + path)
        return json.decodeFromString<T>(body)
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://www.pleasance.icu"
        val SUPPORTED_INTERVALS = listOf("15m", "1h", "4h", "24h")
    }
}