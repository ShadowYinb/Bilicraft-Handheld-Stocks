package com.bilicraft.handheld.stockplugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StockCommandGatewayTest {
    @Test
    fun parsesAbbreviatedMarketPrices() {
        assertEquals(1_000.0, StockCommandGateway.parseMarketPrice("价格: 1K")!!, 0.0)
        assertEquals(32_140.0, StockCommandGateway.parseMarketPrice("价格：32.14K")!!, 0.0)
        assertEquals(2_500_000.0, StockCommandGateway.parseMarketPrice("价格: 2.5m")!!, 0.0)
    }

    @Test
    fun keepsUnabbreviatedMarketPricesUnchanged() {
        assertEquals(999.99, StockCommandGateway.parseMarketPrice("价格: 999.99")!!, 0.0)
        assertEquals(1_234.5, StockCommandGateway.parseMarketPrice("价格: 1,234.5")!!, 0.0)
    }

    @Test
    fun rejectsTextWithoutMarketPrice() {
        assertNull(StockCommandGateway.parseMarketPrice("状态: 交易中"))
    }

    @Test
    fun parsesAbbreviatedMoneyAmounts() {
        assertEquals(1_000.0, StockCommandGateway.parseMoneyAmount("余额: 1K")!!, 0.0)
        assertEquals(32_140.0, StockCommandGateway.parseMoneyAmount("资金：32.14K")!!, 0.0)
        assertEquals(2_500_000.0, StockCommandGateway.parseMoneyAmount("余额: \$2.5M")!!, 0.0)
    }

    @Test
    fun keepsUnabbreviatedMoneyAmountsUnchanged() {
        assertEquals(999.99, StockCommandGateway.parseMoneyAmount("余额: 999.99")!!, 0.0)
        assertEquals(1_234.5, StockCommandGateway.parseMoneyAmount("资金: 1,234.5")!!, 0.0)
    }

    @Test
    fun parsesAbbreviatedTotalValues() {
        assertEquals(1_500.0, StockCommandGateway.parseTotalValue("总价值: 1.5K")!!, 0.0)
        assertEquals(2_500_000.0, StockCommandGateway.parseTotalValue("总价值：2.5M")!!, 0.0)
        assertEquals(1_234.5, StockCommandGateway.parseTotalValue("总价值: 1,234.5")!!, 0.0)
    }
}
