package com.bilicraft.handheld.stockplugin

import com.bilicraft.handheld.pluginapi.BhChatEvent
import com.bilicraft.handheld.pluginapi.BhConnectionState
import com.bilicraft.handheld.pluginapi.BhPluginHost
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class StockCommandGateway(
    private val host: BhPluginHost
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val mappingFile = File(host.pluginDataDir, "stock_company_ids.json")
    private val _mappings = MutableStateFlow(loadMappings())
    val mappings: StateFlow<List<StockIdMapping>> = _mappings.asStateFlow()
    private val _money = MutableStateFlow<Double?>(null)
    val money: StateFlow<Double?> = _money.asStateFlow()
    private val messageWaiters = mutableListOf<MessageWaiter>()

    init {
        scope.launch {
            host.chatEvents.collect(::acceptMessage)
        }
    }

    fun isConnected(): Boolean = host.connectionState.value == BhConnectionState.Connected

    suspend fun refreshMoney(): StockCommandResult = commandMutex.withLock {
        if (!isConnected()) return StockCommandResult.NotConnected()
        val response = executeAndCollect("/money", 4_000) { text -> text.contains("资金:") || text.contains("资金：") }
            ?: return StockCommandResult.Timeout("/money")
        val amount = MONEY_REGEX.find(response)?.groupValues?.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
            ?: return StockCommandResult.Failure("服务器已响应，但未能解析资金数额")
        _money.value = amount
        StockCommandResult.Success("当前资金：$amount 帕元")
    }

    suspend fun queryCompanyInfo(serverId: Int): Result<LiveStockInfo> = commandMutex.withLock {
        if (!isConnected()) return Result.failure(IllegalStateException("请先连接 Minecraft 服务器"))
        val command = "/invest company info $serverId"
        val lines = executeWindow(command, timeoutMs = 4_500, settleMs = 800)
        parseCompanyInfo(lines, serverId)?.let { return Result.success(it) }
        val reason = lines.lastOrNull { looksLikeFailure(it) } ?: "查询超时或服务器未返回可解析的股票信息"
        Result.failure(IllegalStateException(reason))
    }

    suspend fun syncMappings(webCompanies: List<StockCompany>): StockCommandResult = commandMutex.withLock {
        if (!isConnected()) return StockCommandResult.NotConnected("需要连接服务器后才能建立股票 ID 映射")
        val webNames = webCompanies.map { it.name }.toSet()
        val retained = _mappings.value.filter { it.companyName in webNames }.toMutableList()
        val missing = webNames - retained.map { it.companyName }.toSet()
        if (missing.isEmpty()) {
            saveMappings(retained)
            return StockCommandResult.Success("股票 ID 映射已是最新")
        }

        val knownIds = retained.map { it.serverId }.toMutableSet()
        val candidates = (SEED_IDS + generateSequence((knownIds.maxOrNull() ?: SEED_IDS.max()) + 1) { it + 1 }.take(60))
            .distinct()
            .filterNot { it in knownIds }
        var consecutiveMisses = 0
        for (id in candidates) {
            if ((webNames - retained.map { it.companyName }.toSet()).isEmpty()) break
            val lines = executeWindow("/invest company info $id", timeoutMs = 3_500, settleMs = 700)
            val info = parseCompanyInfo(lines, id)
            if (info == null) {
                consecutiveMisses++
                if (id > SEED_IDS.max() && consecutiveMisses >= 12) break
                continue
            }
            consecutiveMisses = 0
            if (info.name in webNames) {
                retained.removeAll { it.companyName == info.name || it.serverId == id }
                retained += StockIdMapping(info.name, id)
                saveMappings(retained)
            }
        }
        val unresolved = webNames - retained.map { it.companyName }.toSet()
        if (unresolved.isEmpty()) StockCommandResult.Success("股票 ID 映射已同步，共 ${retained.size} 家公司")
        else StockCommandResult.Failure("仍有 ${unresolved.size} 家公司未匹配：${unresolved.joinToString("、")}")
    }

    suspend fun queryPortfolio(): Result<List<StockHolding>> = commandMutex.withLock {
        if (!isConnected()) return Result.failure(IllegalStateException("请先连接 Minecraft 服务器"))
        val command = "/invest portfolio"
        val lines = executeWindow(command, timeoutMs = 5_500, settleMs = 900)
        val holdings = parsePortfolio(lines)
        if (holdings.isNotEmpty()) Result.success(holdings)
        else {
            val reason = lines.lastOrNull { looksLikeFailure(it) } ?: "服务器已响应，但未解析到持股记录"
            Result.failure(IllegalStateException(reason))
        }
    }

    suspend fun trade(action: String, serverId: Int, amount: Long): StockCommandResult = commandMutex.withLock {
        if (!isConnected()) return StockCommandResult.NotConnected()
        val command = "/invest $action $serverId $amount"
        val lines = executeWindow(command, timeoutMs = 5_500, settleMs = 900)
        if (lines.isEmpty()) return StockCommandResult.Timeout(command)
        val failure = lines.firstOrNull(::looksLikeFailure)
        if (failure != null) StockCommandResult.Failure(failure)
        else StockCommandResult.Success(lines.lastOrNull { it.isNotBlank() } ?: "服务器已处理交易指令")
    }

    private suspend fun executeAndCollect(command: String, timeoutMs: Long, predicate: (String) -> Boolean): String? {
        val deferred = CompletableDeferred<String>()
        val waiter = MessageWaiter(predicate, deferred)
        synchronized(messageWaiters) { messageWaiters += waiter }
        return try {
            if (!host.sendChat(command)) return null
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            synchronized(messageWaiters) { messageWaiters.remove(waiter) }
        }
    }

    private suspend fun executeWindow(command: String, timeoutMs: Long, settleMs: Long): List<String> {
        val collected = mutableListOf<String>()
        val waiter = MessageWaiter({ true }, null, collected, commandEventPredicate(command))
        synchronized(messageWaiters) { messageWaiters += waiter }
        return try {
            if (!host.sendChat(command)) return emptyList()
            delay(COMMAND_GAP_MS)
            var waited = 0L
            var lastSize = -1
            var stableFor = 0L
            while (waited < timeoutMs) {
                delay(POLL_MS)
                waited += POLL_MS
                val sizeNow = collected.size
                if (sizeNow > 0 && sizeNow == lastSize) {
                    stableFor += POLL_MS
                    if (stableFor >= settleMs) break
                } else {
                    stableFor = 0L
                    lastSize = sizeNow
                }
            }
            collected.toList()
        } finally {
            synchronized(messageWaiters) { messageWaiters.remove(waiter) }
        }
    }

    private fun acceptMessage(event: BhChatEvent) {
        val text = normalizeChatText(event.plainText).ifBlank { normalizeChatText(event.rawJson) }
        if (text.isBlank()) return
        synchronized(messageWaiters) {
            messageWaiters.toList().forEach { waiter ->
                if (waiter.eventPredicate(event) && waiter.predicate(text)) {
                    waiter.collected?.add(text)
                    waiter.deferred?.complete(text)
                }
            }
        }
    }

    private fun commandEventPredicate(command: String): (BhChatEvent) -> Boolean = when {
        command.startsWith("/invest company info") -> ::isCompanyInfoMessage
        command.startsWith("/invest portfolio") -> ::isCompanyInfoMessage
        command.startsWith("/invest buy") || command.startsWith("/invest sell") -> ::isTradeResultMessage
        else -> ::isStockMarketMessage
    }

    private fun isStockMarketMessage(event: BhChatEvent): Boolean {
        val plain = normalizeCompact(event.plainText)
        val raw = normalizeCompact(event.rawJson)
        return plain.contains("帕拉伦股市") || raw.contains("帕拉伦股市")
    }

    private fun isCompanyInfoMessage(event: BhChatEvent): Boolean = event.sender == null

    private fun isTradeResultMessage(event: BhChatEvent): Boolean {
        val plain = normalizeCompact(event.plainText)
        val raw = normalizeCompact(event.rawJson)
        return plain.contains("帕拉伦股市>") || raw.contains("帕拉伦股市>") ||
            ((plain.contains("帕拉伦股市") || raw.contains("帕拉伦股市")) &&
                (plain.contains(">") || raw.contains(">")))
    }

    private fun parseCompanyInfo(lines: List<String>, requestedId: Int): LiveStockInfo? {
        val normalizedLines = lines
            .map(::normalizeChatText)
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val text = normalizedLines.joinToString("\n")
        val name = normalizedLines.firstOrNull { line ->
            line.isNotBlank() && !line.contains("=-=-=-=-") && !line.startsWith("Id:") &&
                !line.startsWith("状态:") && !line.startsWith("价格:") && !line.startsWith("风险等级:") &&
                !line.startsWith("可用股数:") && !line.startsWith("历史记录") && !line.startsWith("[") &&
                !line.matches(Regex("[+-]?\\d+(\\.\\d+)?%")) && !line.contains("帕拉伦股市")
        } ?: return null
        val id = ID_REGEX.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: requestedId
        val price = PRICE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: return null
        val status = STATUS_REGEX.find(text)?.groupValues?.getOrNull(1)?.trim()
        val available = AVAILABLE_REGEX.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
        return LiveStockInfo(id, name, price, status, available)
    }

    private fun parsePortfolio(lines: List<String>): List<StockHolding> {
        val normalized = lines.map(::normalizeChatText).map(String::trim).filter(String::isNotBlank)
        val holdings = mutableListOf<StockHolding>()
        var companyName: String? = null
        var shares: Long? = null
        var totalValue: Double? = null

        fun flush() {
            val name = companyName
            val stockCount = shares
            val value = totalValue
            if (name != null && stockCount != null && value != null) {
                holdings += StockHolding(name, stockCount, value)
            }
            companyName = null
            shares = null
            totalValue = null
        }

        normalized.forEach { line ->
            when {
                line.startsWith("股票:") || line.startsWith("股票：") -> {
                    shares = SHARES_REGEX.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                }
                line.startsWith("总价值:") || line.startsWith("总价值：") -> {
                    totalValue = TOTAL_VALUE_REGEX.find(line)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
                    flush()
                }
                isPortfolioCompanyName(line) -> {
                    flush()
                    companyName = line
                }
            }
        }
        flush()
        return holdings
    }

    private fun isPortfolioCompanyName(line: String): Boolean =
        !line.contains("帕拉伦股市") && !line.startsWith("股票:") && !line.startsWith("股票：") &&
            !line.startsWith("总价值:") && !line.startsWith("总价值：") && !line.startsWith("[") &&
            !line.contains("=-=-=-=-")

    private fun normalizeCompact(text: String): String = normalizeChatText(text).replace(" ", "")

    private fun normalizeChatText(text: String): String = text
        .replace(FORMAT_CODE_REGEX, "")
        .replace("\\u00a7", "")
        .replace("▌", " ")
        .replace("\\n", "\n")
        .replace(Regex("[\\t\\r]"), " ")
        .lineSequence()
        .map { it.trim().trim('-', '=', '|') }
        .joinToString("\n")
        .replace(Regex(" +"), " ")
        .trim()

    private fun looksLikeFailure(text: String): Boolean = FAILURE_WORDS.any(text::contains)

    private fun loadMappings(): List<StockIdMapping> = runCatching {
        if (!mappingFile.exists()) emptyList()
        else json.decodeFromString<List<StockIdMapping>>(mappingFile.readText())
    }.getOrElse { emptyList() }

    private fun saveMappings(value: List<StockIdMapping>) {
        val normalized = value.distinctBy { it.companyName }.sortedBy { it.serverId }
        mappingFile.parentFile?.mkdirs()
        mappingFile.writeText(json.encodeToString(normalized))
        _mappings.value = normalized
    }

    private data class MessageWaiter(
        val predicate: (String) -> Boolean,
        val deferred: CompletableDeferred<String>? = null,
        val collected: MutableList<String>? = null,
        val eventPredicate: (BhChatEvent) -> Boolean = { true }
    )

    companion object {
        private val SEED_IDS = listOf(0, 1, 5, 6, 7, 8, 11, 12, 13, 14, 15)
        private val MONEY_REGEX = Regex("资金[:：]\\s*\\$?([0-9,]+(?:\\.[0-9]+)?)")
        private val ID_REGEX = Regex("Id[:：]\\s*(\\d+)", RegexOption.IGNORE_CASE)
        private val PRICE_REGEX = Regex("价格[:：]\\s*([0-9]+(?:\\.[0-9]+)?)")
        private val STATUS_REGEX = Regex("状态[:：]\\s*([^\\n]+)")
        private val AVAILABLE_REGEX = Regex("可用股数[:：]\\s*(\\d+)")
        private val SHARES_REGEX = Regex("股票[:：]\\s*(\\d+)")
        private val TOTAL_VALUE_REGEX = Regex("总价值[:：]\\s*([0-9]+(?:\\.[0-9]+)?)")
        private val FORMAT_CODE_REGEX = Regex("§.")
        private const val COMMAND_GAP_MS = 300L
        private const val POLL_MS = 100L
        private val FAILURE_WORDS = listOf("失败", "错误", "不足", "无法", "不存在", "无效", "禁止", "没有", "超出")
    }
}