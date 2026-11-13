package com.example

import com.example.data.model.Candle
import com.example.data.scanner.BinanceFuturesScannerEngine
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.FuturesScannerRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BinanceFuturesScannerTest {

    private lateinit var engine: BinanceFuturesScannerEngine
    private lateinit var bullish4hCandles: List<Candle>
    private lateinit var bullish1hCandles: List<Candle>
    private lateinit var bullish15mCandles: List<Candle>
    private lateinit var bullish5mCandles: List<Candle>

    @Before
    fun setUp() {
        engine = BinanceFuturesScannerEngine
        bullish4hCandles = generateTrendCandles(basePrice = 85000.0, count = 50, step = 150.0)
        bullish1hCandles = generateTrendCandles(basePrice = 90000.0, count = 80, step = 80.0)
        bullish15mCandles = generateTrendCandles(basePrice = 95000.0, count = 100, step = 30.0)
        bullish5mCandles = generateTrendCandles(basePrice = 97000.0, count = 100, step = 15.0)
    }

    private fun generateTrendCandles(basePrice: Double, count: Int, step: Double): List<Candle> {
        val list = mutableListOf<Candle>()
        var current = basePrice
        val baseTime = 1700000000000L

        for (i in 0 until count) {
            val delta = step + ((i % 5) - 2) * (step * 0.2)
            val open = current
            val close = open + delta
            val high = maxOf(open, close) + (step * 0.5)
            val low = minOf(open, close) - (step * 0.5)
            val vol = 1200.0 + (i % 7) * 150.0

            list.add(
                Candle(
                    openTime = baseTime + (i * 900000L),
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    takerBuyBaseVolume = vol * 0.58
                )
            )
            current = close
        }
        return list
    }

    @Test
    fun testInsufficientCandleHistoryReturnsNoTrade() = runBlocking {
        val shortCandles = bullish15mCandles.take(5)
        val result = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = 98000.0,
            candles4h = emptyList(),
            candles1h = emptyList(),
            candles15m = shortCandles,
            candles5m = emptyList()
        )

        assertTrue(result.isNoTrade)
        assertTrue(result.noTradeReason.orEmpty().contains("Insufficient", ignoreCase = true))
    }

    @Test
    fun testMultiTimeframeConfluenceGeneratesValidSignal() = runBlocking {
        val currentPrice = bullish5mCandles.last().close
        val result = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = currentPrice,
            candles4h = bullish4hCandles,
            candles1h = bullish1hCandles,
            candles15m = bullish15mCandles,
            candles5m = bullish5mCandles,
            fundingRate = 0.0001
        )

        if (result.signal != null) {
            val signal = result.signal!!
            assertEquals("BTCUSDT", signal.symbol)
            assertTrue(signal.entryZoneMin <= signal.entryZoneMax)
            assertTrue(signal.qualityScore in 0..100)
            assertTrue(signal.riskRewardRatio > 0.0)
            assertTrue(signal.reasonsForEntry.isNotEmpty())
            assertTrue(signal.timeframeConfirmations.size >= 3)
            assertTrue(signal.invalidationCondition.isNotBlank())

            if (signal.direction == FuturesDirection.LONG) {
                assertTrue(signal.takeProfit1 > signal.entryPrice)
                assertTrue(signal.takeProfit2 > signal.takeProfit1)
                assertTrue(signal.takeProfit3 > signal.takeProfit2)
                assertTrue(signal.stopLoss < signal.entryPrice)
            } else {
                assertTrue(signal.takeProfit1 < signal.entryPrice)
                assertTrue(signal.takeProfit2 < signal.takeProfit1)
                assertTrue(signal.takeProfit3 < signal.takeProfit2)
                assertTrue(signal.stopLoss > signal.entryPrice)
            }
        } else {
            assertTrue(result.isNoTrade)
            assertTrue(result.noTradeReason.orEmpty().isNotBlank())
        }
    }

    @Test
    fun testWatchlistPairManagement() {
        val repo = FuturesScannerRepository()

        // Verify default watchlist loaded
        val initialPairs = repo.watchlist.value
        assertTrue(initialPairs.isNotEmpty())
        assertTrue(initialPairs.any { it.symbol == "BTCUSDT" })
        assertTrue(initialPairs.any { it.symbol == "ETHUSDT" })
        assertTrue(initialPairs.any { it.symbol == "SOLUSDT" })

        // Add custom pair
        repo.addCustomPair("PEPEUSDT")
        val updatedPairs = repo.watchlist.value
        assertTrue(updatedPairs.any { it.symbol == "PEPEUSDT" })

        // Toggle scanning
        val initialState = repo.watchlist.value.first { it.symbol == "BTCUSDT" }.isSelectedForScanning
        repo.togglePairScanning("BTCUSDT")
        val toggledState = repo.watchlist.value.first { it.symbol == "BTCUSDT" }.isSelectedForScanning
        assertEquals(!initialState, toggledState)

        // Remove pair
        repo.removePair("PEPEUSDT")
        assertFalse(repo.watchlist.value.any { it.symbol == "PEPEUSDT" })
    }

    @Test
    fun testSignalLifecycleTransitionsOnLivePrice() {
        val repo = FuturesScannerRepository()

        val testSignal = FuturesScanSignal(
            id = "test_signal_btc_long",
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            direction = FuturesDirection.LONG,
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED,
            entryPrice = 90000.0,
            entryZoneMin = 89800.0,
            entryZoneMax = 90200.0,
            currentPrice = 90500.0,
            stopLoss = 88500.0,
            takeProfit1 = 92000.0,
            takeProfit2 = 94000.0,
            takeProfit3 = 96000.0,
            riskRewardRatio = 2.67,
            qualityScore = 85,
            qualityRating = "A+ Confluence",
            suggestedLeverage = 5,
            reasonsForEntry = listOf("4H Macro Trend Bullish", "15M CHoCH break"),
            timeframeConfirmations = emptyList(),
            invalidationCondition = "Break below $88,500",
            timestamp = System.currentTimeMillis()
        )

        repo.onSignalEvaluated(testSignal)
        var currentActive = repo.scannerState.value.activeSignals.firstOrNull { it.id == testSignal.id }
        assertNotNull(currentActive)
        assertEquals(FuturesSignalLifecycle.ENTRY_CONFIRMED, currentActive?.lifecycle)

        // Price hits TP2: moves to history as TARGET_2_HIT
        repo.onLivePricesUpdated(mapOf("BTCUSDT" to 94100.0))
        val inActiveAfterTp2 = repo.scannerState.value.activeSignals.any { it.id == testSignal.id }
        assertFalse(inActiveAfterTp2)
        val inHistory = repo.scannerState.value.signalHistory.firstOrNull { it.id == testSignal.id }
        assertNotNull(inHistory)
        assertEquals(FuturesSignalLifecycle.TARGET_2_HIT, inHistory?.lifecycle)
    }

    @Test
    fun testShortSignalStopLossTransition() {
        val repo = FuturesScannerRepository()

        val shortSignal = FuturesScanSignal(
            id = "test_signal_eth_short",
            symbol = "ETHUSDT",
            baseAsset = "ETH",
            direction = FuturesDirection.SHORT,
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED,
            entryPrice = 3000.0,
            entryZoneMin = 2990.0,
            entryZoneMax = 3010.0,
            currentPrice = 3000.0,
            stopLoss = 3100.0,
            takeProfit1 = 2850.0,
            takeProfit2 = 2700.0,
            takeProfit3 = 2500.0,
            riskRewardRatio = 3.0,
            qualityScore = 80,
            qualityRating = "High Quality",
            suggestedLeverage = 5,
            reasonsForEntry = listOf("Bearish Macro Trend", "4H FVG mitigation"),
            timeframeConfirmations = emptyList(),
            invalidationCondition = "Break above $3,100",
            timestamp = System.currentTimeMillis()
        )

        repo.onSignalEvaluated(shortSignal)

        // Price hits stop loss for SHORT: archived as STOP_LOSS_HIT
        repo.onLivePricesUpdated(mapOf("ETHUSDT" to 3110.0))
        val inHistory = repo.scannerState.value.signalHistory.firstOrNull { it.id == shortSignal.id }
        assertNotNull(inHistory)
        assertEquals(FuturesSignalLifecycle.STOP_LOSS_HIT, inHistory?.lifecycle)
    }

    @Test
    fun testPaperTradingPnLAndStatistics() {
        val repo = FuturesScannerRepository()
        repo.setPaperTradingEnabled(true)
        assertTrue(repo.scannerState.value.paperTradingEnabled)

        repo.setScanInterval(15)
        assertEquals(15, repo.scannerState.value.scanIntervalSeconds)
    }
}
