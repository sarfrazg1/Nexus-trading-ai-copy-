package com.example

import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.signal.AiTradingSignalEngine
import com.example.data.signal.RomanUrduSignalGlossary
import com.example.data.signal.SignalDecision
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AiTradingSignalEngineTest {

    private lateinit var bullishCandles: List<Candle>
    private lateinit var shortCandles: List<Candle>

    @Before
    fun setUp() {
        val baseTime = 1700000000000L

        // Generate 120 upward trending candles with solid volume
        val bullList = mutableListOf<Candle>()
        var price = 50000.0
        for (i in 0 until 120) {
            val open = price
            val close = open + 150.0 + (i % 5) * 20.0
            val high = close + 80.0
            val low = open - 40.0
            val volume = 2000.0 + (i % 4) * 300.0
            val takerBuy = volume * 0.65 // strong buying volume
            bullList.add(
                Candle(
                    openTime = baseTime + i * 3600000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    takerBuyBaseVolume = takerBuy
                )
            )
            price = close
        }
        bullishCandles = bullList

        // Generate short series (< 30 candles)
        shortCandles = bullList.take(20)
    }

    @Test
    fun testDataSafetyMissingPriceReturnsNoTrade() = runBlocking {
        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = null,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance",
            lastUpdatedTime = System.currentTimeMillis(),
            primaryTimeframe = CandleTimeframe.H1,
            candles = bullishCandles,
            technicalAnalysis = null,
            smcAnalysis = null
        )

        assertEquals(SignalDecision.NO_TRADE, signal.decision)
        assertNotNull(signal.noTradeReason)
        assertEquals("DATA_UNAVAILABLE", signal.noTradeReason?.code)
        assertNull(signal.riskPlan)
    }

    @Test
    fun testDataSafetyStaleStatusReturnsNoTrade() = runBlocking {
        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = 65000.0,
            dataStatus = MarketDataStatus.STALE,
            dataSourceName = "Binance",
            lastUpdatedTime = System.currentTimeMillis() - 600000L,
            primaryTimeframe = CandleTimeframe.H1,
            candles = bullishCandles,
            technicalAnalysis = null,
            smcAnalysis = null
        )

        assertEquals(SignalDecision.NO_TRADE, signal.decision)
        assertNotNull(signal.noTradeReason)
        assertEquals("MARKET_DATA_STALE", signal.noTradeReason?.code)
    }

    @Test
    fun testDataSafetyInsufficientCandlesReturnsNoTrade() = runBlocking {
        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = 52000.0,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance",
            lastUpdatedTime = System.currentTimeMillis(),
            primaryTimeframe = CandleTimeframe.H1,
            candles = shortCandles,
            technicalAnalysis = null,
            smcAnalysis = null
        )

        assertEquals(SignalDecision.NO_TRADE, signal.decision)
        assertNotNull(signal.noTradeReason)
        assertEquals("INSUFFICIENT_DATA", signal.noTradeReason?.code)
    }

    @Test
    fun testSignalGenerationAndRiskManagement() = runBlocking {
        val latestPrice = bullishCandles.last().close
        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = latestPrice,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance Public REST",
            lastUpdatedTime = System.currentTimeMillis(),
            primaryTimeframe = CandleTimeframe.H1,
            candles = bullishCandles,
            technicalAnalysis = null,
            smcAnalysis = null
        )

        // Must be one of the three valid decisions
        assertTrue(
            signal.decision == SignalDecision.LONG ||
                    signal.decision == SignalDecision.SHORT ||
                    signal.decision == SignalDecision.NO_TRADE
        )

        // Confidence scoring must be bounded 0..100
        assertTrue(signal.confidence.totalScore in 0..100)
        assertEquals(7, signal.confidence.factors.size)

        // Verify total possible points sum to 100
        val totalMaxPoints = signal.confidence.factors.sumOf { it.maxPoints }
        assertEquals(100, totalMaxPoints)

        // Multi-timeframe confirmation must evaluate 8 timeframes
        assertEquals(8, signal.timeframeConditions.size)

        // If signal is actionable (LONG/SHORT), verify strict risk management rules
        if (signal.decision == SignalDecision.LONG && signal.riskPlan != null) {
            val plan = signal.riskPlan!!
            // Stop loss must be below entry
            assertTrue("Stop loss must be below entry", plan.stopLoss < plan.entryPrice)
            // Take profit targets must be higher than entry
            assertTrue("TP1 must be higher than entry", plan.takeProfit1 > plan.entryPrice)
            assertTrue("TP2 must be higher than TP1", plan.takeProfit2 > plan.takeProfit1)
            assertTrue("TP3 must be higher than TP2", plan.takeProfit3 > plan.takeProfit2)
            // Risk reward must be >= 1.5
            assertTrue("R:R must be >= 1.5", plan.riskRewardRatio >= 1.5)
            // Max leverage must be disciplined (2x to 10x)
            assertTrue("Leverage must be between 2 and 10", plan.suggestedMaxLeverage in 2..10)
        }
    }

    @Test
    fun testRomanUrduGlossaryCoverage() {
        val requiredTerms = listOf(
            "LONG", "SHORT", "NO_TRADE", "ENTRY", "STOP_LOSS",
            "TAKE_PROFIT", "RISK_REWARD", "CONFIDENCE_SCORE", "LEVERAGE", "INVALIDATION"
        )

        for (term in requiredTerms) {
            val item = RomanUrduSignalGlossary.getTerm(term)
            assertNotNull("Glossary must contain term $term", item)
            assertTrue("Glossary item $term must have non-empty Roman Urdu summary", item!!.romanUrduSummary.isNotBlank())
            assertTrue("Glossary item $term must have non-empty Roman Urdu detail", item.romanUrduDetail.isNotBlank())
            assertTrue("Glossary item $term must have a pro trading tip", item.tradingTipRomanUrdu.isNotBlank())
        }
    }

    @Test
    fun testAlignedBullishTrendGeneratesLongSignal() = runBlocking {
        val candles = buildPullbackTrendingCandles(count = 240, bullish = true)
        val mtfBullish = listOf(
            CandleTimeframe.M15, CandleTimeframe.M30, CandleTimeframe.H1,
            CandleTimeframe.H4, CandleTimeframe.D1, CandleTimeframe.W1
        ).map { tf ->
            com.example.data.smc.TimeframeSmcSnapshot(
                timeframe = tf,
                trend = com.example.data.smc.MarketTrendState.BULLISH_UPTREND,
                lastBreakType = com.example.data.smc.MarketStructureBreakType.BOS_BULLISH,
                unmitigatedOBCount = 1,
                unfilledFvgCount = 1,
                nearestSupport = candles.last().close * 0.985,
                nearestResistance = candles.last().close * 1.04,
                isBullishBias = true
            )
        }

        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = candles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance Futures (LIVE)",
            lastUpdatedTime = System.currentTimeMillis(),
            primaryTimeframe = CandleTimeframe.H1,
            candles = candles,
            technicalAnalysis = null,
            smcAnalysis = null,
            mtfSnapshots = mtfBullish
        )

        assertEquals("Aligned bullish trend + MTF should produce LONG", SignalDecision.LONG, signal.decision)
        val plan = signal.riskPlan
        assertNotNull(plan)
        assertTrue(plan!!.stopLoss < plan.entryPrice)
        assertTrue(plan.takeProfit1 > plan.entryPrice)
        assertTrue(plan.takeProfit2 > plan.takeProfit1)
        assertTrue(plan.takeProfit3 > plan.takeProfit2)
        assertTrue(plan.riskRewardRatio >= 1.5)
        assertTrue(signal.confidence.totalScore >= 60)
    }

    @Test
    fun testAlignedBearishTrendGeneratesShortSignal() = runBlocking {
        val candles = buildPullbackTrendingCandles(count = 240, bullish = false)
        val mtfBearish = listOf(
            CandleTimeframe.M15, CandleTimeframe.M30, CandleTimeframe.H1,
            CandleTimeframe.H4, CandleTimeframe.D1, CandleTimeframe.W1
        ).map { tf ->
            com.example.data.smc.TimeframeSmcSnapshot(
                timeframe = tf,
                trend = com.example.data.smc.MarketTrendState.BEARISH_DOWNTREND,
                lastBreakType = com.example.data.smc.MarketStructureBreakType.BOS_BEARISH,
                unmitigatedOBCount = 1,
                unfilledFvgCount = 1,
                nearestSupport = candles.last().close * 0.96,
                nearestResistance = candles.last().close * 1.015,
                isBullishBias = false
            )
        }

        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "ETH",
            assetName = "Ethereum",
            currentPrice = candles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance Futures (LIVE)",
            lastUpdatedTime = System.currentTimeMillis(),
            primaryTimeframe = CandleTimeframe.H1,
            candles = candles,
            technicalAnalysis = null,
            smcAnalysis = null,
            mtfSnapshots = mtfBearish
        )

        assertEquals("Aligned bearish trend + MTF should produce SHORT", SignalDecision.SHORT, signal.decision)
        val plan = signal.riskPlan
        assertNotNull(plan)
        assertTrue(plan!!.stopLoss > plan.entryPrice)
        assertTrue(plan.takeProfit1 < plan.entryPrice)
        assertTrue(plan.takeProfit2 < plan.takeProfit1)
        assertTrue(plan.takeProfit3 < plan.takeProfit2)
        assertTrue(plan.riskRewardRatio >= 1.5)
    }

    @Test
    fun testChoppySidewaysMarketStillReturnsNoTrade() = runBlocking {
        val baseTime = 1700000000000L
        val choppyCandles = (0 until 120).map { i ->
            val swing = if (i % 2 == 0) 80.0 else -80.0
            val open = 50000.0 + swing * 0.5
            val close = 50000.0 - swing * 0.5
            Candle(
                openTime = baseTime + i * 3600000L,
                open = open,
                high = maxOf(open, close) + 35.0,
                low = minOf(open, close) - 35.0,
                close = close,
                volume = 1000.0,
                takerBuyBaseVolume = 500.0
            )
        }

        val signal = AiTradingSignalEngine.generateSignal(
            symbol = "SOL",
            assetName = "Solana",
            currentPrice = choppyCandles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance",
            lastUpdatedTime = System.currentTimeMillis(),
            primaryTimeframe = CandleTimeframe.H1,
            candles = choppyCandles,
            technicalAnalysis = null,
            smcAnalysis = null
        )

        assertEquals(SignalDecision.NO_TRADE, signal.decision)
        assertEquals("CONFLICTING_INDICATORS", signal.noTradeReason?.code)
        assertNull(signal.riskPlan)
    }

    private fun buildPullbackTrendingCandles(count: Int, bullish: Boolean): List<Candle> {
        val baseTime = 1700000000000L
        val list = mutableListOf<Candle>()
        var price = if (bullish) 40000.0 else 80000.0
        for (i in 0 until count) {
            val isPullback = (i % 9) in 6..7
            val step = when {
                bullish && !isPullback -> 260.0
                bullish && isPullback -> -110.0
                !bullish && !isPullback -> -260.0
                else -> 110.0
            }
            val open = price
            val close = (open + step).coerceAtLeast(1000.0)
            val high = maxOf(open, close) + 90.0
            val low = minOf(open, close) - 90.0
            val vol = if (isPullback) 1400.0 else 3200.0
            val buyRatio = if (bullish) {
                if (isPullback) 0.48 else 0.72
            } else {
                if (isPullback) 0.52 else 0.28
            }
            list.add(
                Candle(
                    openTime = baseTime + i * 3600000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    takerBuyBaseVolume = vol * buyRatio
                )
            )
            price = close
        }
        return list
    }
}
