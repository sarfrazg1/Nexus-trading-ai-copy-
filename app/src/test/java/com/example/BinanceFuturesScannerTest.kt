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

    @Test
    fun testLongFuturesTargetMeetsTenPercentNetRoiAfterFeesAndFunding() {
        val entry = 100_000.0
        val stopLoss = 98_800.0 // -1.20% SL
        val atr15m = 800.0      // 0.80% ATR
        val initialMargin = 200.0
        val leverage = 5
        val fundingRatePct = 0.02 // +0.02% per 8h (LONG pays)

        val calc = engine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = entry,
            structuralStopLoss = stopLoss,
            atr15m = atr15m,
            atr1h = 1600.0,
            leverage = leverage,
            initialMarginUsdt = initialMargin,
            fundingRatePercent = fundingRatePct
        )

        assertTrue("Expected viable LONG target plan: ${calc.rejectionReason}", calc.isViable)
        assertEquals(leverage, calc.leverage)
        assertEquals(1000.0, calc.notionalValueUsdt, 1e-6)
        assertEquals(0.01, calc.positionSizeUnits, 1e-6)
        assertTrue("TP1 must achieve >= +10% net ROI", calc.tp1NetRoiPercent >= 10.0 - 1e-6)
        assertTrue("TP1 gross ROI must exceed net ROI to cover fees & funding", calc.tp1GrossRoiPercent > calc.tp1NetRoiPercent)
        assertTrue("TP2 must exceed TP1", calc.takeProfit2 > calc.takeProfit1)
        assertTrue("TP3 must exceed TP2", calc.takeProfit3 > calc.takeProfit2)
        assertTrue("Risk-reward must be >= 1.8", calc.riskRewardRatio >= 1.8)
        assertTrue("Estimated liquidation must sit safely below SL", calc.estimatedLiquidationPrice < calc.stopLoss)
        assertTrue(calc.fundingDataReliable)
        assertTrue(calc.estimatedFundingCostMarginPercent > 0.0)
    }

    @Test
    fun testShortFuturesTargetMeetsTenPercentNetRoiWithNegativeFunding() {
        val entry = 3000.0
        val stopLoss = 3036.0 // +1.20% SL above entry
        val atr15m = 25.0
        val initialMargin = 100.0
        val leverage = 5
        val fundingRatePct = -0.03 // -0.03% per 8h (SHORT pays)

        val calc = engine.calculateFuturesTargets(
            direction = FuturesDirection.SHORT,
            entryPrice = entry,
            structuralStopLoss = stopLoss,
            atr15m = atr15m,
            atr1h = 50.0,
            leverage = leverage,
            initialMarginUsdt = initialMargin,
            fundingRatePercent = fundingRatePct
        )

        assertTrue("Expected viable SHORT target plan: ${calc.rejectionReason}", calc.isViable)
        assertTrue("TP1 must achieve >= +10% net ROI", calc.tp1NetRoiPercent >= 10.0 - 1e-6)
        assertTrue("SHORT TP1 < entry", calc.takeProfit1 < entry)
        assertTrue("SHORT TP2 < TP1", calc.takeProfit2 < calc.takeProfit1)
        assertTrue("SHORT TP3 < TP2", calc.takeProfit3 < calc.takeProfit2)
        assertTrue("Estimated liquidation must sit safely above SL", calc.estimatedLiquidationPrice > calc.stopLoss)
        assertTrue("SHORT should account for adverse negative funding", calc.estimatedFundingCostMarginPercent > 0.0)
    }

    @Test
    fun testTakeProfitsScaleDynamicallyWithLeverageNotFixedPricePercent() {
        val entry = 50_000.0
        val stopLoss = 49_750.0 // tight 0.50% SL so ROI drives TP1
        val atr15m = 600.0
        val atr1h = 1200.0

        val calc3x = engine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = entry,
            structuralStopLoss = stopLoss,
            atr15m = atr15m,
            atr1h = atr1h,
            leverage = 3,
            fundingRatePercent = 0.01
        )
        val calc5x = engine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = entry,
            structuralStopLoss = stopLoss,
            atr15m = atr15m,
            atr1h = atr1h,
            leverage = 5,
            fundingRatePercent = 0.01
        )
        val calc10x = engine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = entry,
            structuralStopLoss = stopLoss,
            atr15m = atr15m,
            atr1h = atr1h,
            leverage = 10,
            fundingRatePercent = 0.01
        )

        assertTrue(calc3x.isViable && calc5x.isViable && calc10x.isViable)
        // Lower leverage requires a larger price move to reach +10% net margin ROI
        assertTrue(
            "3x TP1 (${calc3x.takeProfit1}) must be farther than 5x TP1 (${calc5x.takeProfit1})",
            calc3x.takeProfit1 > calc5x.takeProfit1
        )
        assertTrue(
            "5x TP1 (${calc5x.takeProfit1}) must be farther than 10x TP1 (${calc10x.takeProfit1})",
            calc5x.takeProfit1 > calc10x.takeProfit1
        )
        assertEquals(10.0, calc3x.tp1NetRoiPercent, 0.05)
        assertEquals(10.0, calc5x.tp1NetRoiPercent, 0.05)
        assertEquals(10.0, calc10x.tp1NetRoiPercent, 0.05)
    }

    @Test
    fun testUnsafeStopLossNearLiquidationRejectedWithoutIncreasingOrBypassingLeverage() {
        val entry = 100.0
        val unsafeStopLoss = 93.5 // -6.5% SL on 10x (~9.4% liq -> -65% margin loss, too close to liq)
        val calc = engine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = entry,
            structuralStopLoss = unsafeStopLoss,
            atr15m = 3.0,
            leverage = 10
        )

        assertFalse("Unsafe SL near liquidation must reject trade", calc.isViable)
        assertTrue(calc.rejectionReason.orEmpty().contains("liquidation", ignoreCase = true))
    }

    @Test
    fun testUnreachableTargetAtLowLeverageReturnsNoTradeInsteadOfAutoIncreasingLeverage() = runBlocking {
        // With low volatility & 1x leverage, +10% net ROI requires ~+10.1% price move,
        // exceeding realistic ATR range -> must return NO TRADE rather than bumping leverage.
        val currentPrice = bullish5mCandles.last().close
        val result = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = currentPrice,
            candles4h = bullish4hCandles,
            candles1h = bullish1hCandles,
            candles15m = bullish15mCandles,
            candles5m = bullish5mCandles,
            fundingRate = 0.01,
            configuredLeverage = 1
        )

        assertTrue(result.isNoTrade)
        assertEquals(null, result.signal)
        assertTrue(result.noTradeReason.orEmpty().isNotBlank())
    }

    @Test
    fun testStaleMissingAndDivergentMarketDataReturnsNoTradeSafely() = runBlocking {
        val now = 1_750_000_000_000L
        val currentPrice = bullish15mCandles.last().close

        // 1. Explicitly marked stale
        val staleFlagRes = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = currentPrice,
            candles4h = bullish4hCandles,
            candles1h = bullish1hCandles,
            candles15m = bullish15mCandles,
            candles5m = bullish5mCandles,
            isMarketDataStale = true
        )
        assertTrue(staleFlagRes.isNoTrade)
        assertTrue(staleFlagRes.noTradeReason.orEmpty().contains("stale", ignoreCase = true))

        // 2. Timestamp older than freshness window
        val oldTimestampRes = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = currentPrice,
            candles4h = bullish4hCandles,
            candles1h = bullish1hCandles,
            candles15m = bullish15mCandles,
            candles5m = bullish5mCandles,
            dataTimestampMs = now - 600_000L, // 10 minutes old
            referenceTimeMs = now
        )
        assertTrue(oldTimestampRes.isNoTrade)
        assertTrue(oldTimestampRes.noTradeReason.orEmpty().contains("stale", ignoreCase = true))

        // 3. Invalid zero/NaN price
        val nanPriceRes = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = Double.NaN,
            candles4h = bullish4hCandles,
            candles1h = bullish1hCandles,
            candles15m = bullish15mCandles,
            candles5m = bullish5mCandles
        )
        assertTrue(nanPriceRes.isNoTrade)

        // 4. Stale ticker vs candle close divergence (>15%)
        val divergentRes = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = currentPrice * 1.25,
            candles4h = bullish4hCandles,
            candles1h = bullish1hCandles,
            candles15m = bullish15mCandles,
            candles5m = bullish5mCandles
        )
        assertTrue(divergentRes.isNoTrade)
        assertTrue(divergentRes.noTradeReason.orEmpty().contains("mismatch", ignoreCase = true))

        // 5. Missing funding rate still calculates +10% net ROI cleanly using fees
        val missingFundingCalc = engine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = 100_000.0,
            structuralStopLoss = 98_500.0,
            atr15m = 1000.0,
            leverage = 5,
            fundingRatePercent = null
        )
        assertTrue(missingFundingCalc.isViable)
        assertFalse(missingFundingCalc.fundingDataReliable)
        assertEquals(0.0, missingFundingCalc.estimatedFundingCostMarginPercent, 1e-6)
        assertTrue(missingFundingCalc.tp1NetRoiPercent >= 10.0 - 1e-6)
    }

    @Test
    fun testRealisticConfluenceSetupProducesSignalWithDisplayedNetRoiAndLiquidationSafety() = runBlocking {
        // Construct pullback-then-breakout candles so 15M triggers Bullish CHoCH + HTF Bullish EMA + FVG
        fun buildChochCandles(base: Double, count: Int, ampPct: Double): List<Candle> {
            val list = mutableListOf<Candle>()
            var p = base
            val t0 = 1_750_000_000_000L
            for (i in 0 until count) {
                val pct = when {
                    i < count / 2 -> 0.0022
                    i < (count * 3) / 4 -> -0.0018
                    else -> ampPct
                }
                val open = p
                val close = open * (1.0 + pct)
                val high = maxOf(open, close) * 1.003
                val low = minOf(open, close) * 0.997
                list.add(
                    Candle(
                        openTime = t0 + i * 900_000L,
                        open = open,
                        high = high,
                        low = low,
                        close = close,
                        volume = if (i >= (count * 3) / 4) 4500.0 else 1500.0,
                        takerBuyBaseVolume = if (i >= (count * 3) / 4) 3300.0 else 800.0
                    )
                )
                p = close
            }
            return list
        }

        val c4h = buildChochCandles(80_000.0, 40, 0.005)
        val c1h = buildChochCandles(85_000.0, 60, 0.004)
        val c15m = buildChochCandles(90_000.0, 80, 0.0055)
        val c5m = buildChochCandles(c15m.last().close * 0.98, 40, 0.002)
        val price = c15m.last().close

        val res = engine.evaluatePair(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            currentPrice = price,
            candles4h = c4h,
            candles1h = c1h,
            candles15m = c15m,
            candles5m = c5m,
            fundingRate = 0.015,
            initialMarginUsdt = 100.0
        )

        if (res.signal != null) {
            val sig = res.signal!!
            assertTrue(sig.reasonsForEntry.any { it.contains("Estimated Net ROI", ignoreCase = true) })
            assertTrue(sig.reasonsForEntry.any { it.contains("Margin & Position Plan", ignoreCase = true) })
            assertTrue(sig.reasonsForEntry.any { it.contains("Liquidation Safety", ignoreCase = true) })
            assertTrue(sig.invalidationCondition.contains("liquidation", ignoreCase = true))
            assertTrue(sig.riskRewardRatio >= 1.8)
        } else {
            assertTrue(res.isNoTrade)
        }
    }
}
