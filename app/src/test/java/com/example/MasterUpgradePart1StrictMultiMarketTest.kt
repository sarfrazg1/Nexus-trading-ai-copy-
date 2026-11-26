package com.example

import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.providers.BybitV5PublicProvider
import com.example.data.providers.MarketVenue
import com.example.data.providers.MultiMarketInstrumentCatalog
import com.example.data.remote.CandleApiService
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.SetupModelType
import com.example.data.scanner.SetupValidationStatus
import com.example.data.scanner.StrictMultiMarketSetupEngine
import com.example.data.smc.AdvancedStructureAndFibEngine
import com.example.data.smc.BreakoutRetestType
import com.example.data.smc.MarketStructureBreakType
import com.example.data.smc.OrderBlock
import com.example.data.smc.OrderBlockStatus
import com.example.data.smc.OrderBlockType
import com.example.data.smc.PriceActionPatternType
import com.example.data.smc.PriceLevelZone
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcCalculationEngine
import com.example.data.smc.SwingPoint
import com.example.data.smc.SwingType
import com.example.data.smc.ZoneSourceType
import com.example.data.timemachine.TimeMachineEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterUpgradePart1StrictMultiMarketTest {

    // =========================================================================
    // 1. MULTI-MARKET VENUE SEPARATION & DATA HONESTY TESTS
    // =========================================================================

    @Test
    fun `catalog strictly separates commodities and stocks from Binance Futures`() {
        val gold = MultiMarketInstrumentCatalog.resolve("XAU")
        assertNotNull(gold)
        assertEquals(AssetCategory.COMMODITIES, gold!!.category)
        assertEquals(MarketVenue.COMMODITIES_YAHOO, gold.primaryVenue)
        assertEquals("GC=F", gold.yahooTicker)
        assertNull(gold.binanceSymbol)
        assertFalse(gold.isBinanceFuturesEligible)
        assertNotEquals("PAXGUSDT", gold.binanceSymbol)

        val silver = MultiMarketInstrumentCatalog.resolve("SILVER")
        assertNotNull(silver)
        assertEquals("XAG", silver!!.canonicalId)
        assertEquals("SI=F", silver.yahooTicker)
        assertFalse(silver.isBinanceFuturesEligible)

        val wti = MultiMarketInstrumentCatalog.resolve("CRUDE_OIL")
        assertNotNull(wti)
        assertEquals("WTI", wti!!.canonicalId)
        assertEquals("CL=F", wti.yahooTicker)
        assertFalse(wti.isBinanceFuturesEligible)

        for (ticker in listOf("NVDA", "GOOG", "GOOGL")) {
            val stock = MultiMarketInstrumentCatalog.resolve(ticker)
            assertNotNull("Expected $ticker to resolve", stock)
            assertEquals(AssetCategory.STOCKS, stock!!.category)
            assertEquals(MarketVenue.STOCKS_YAHOO, stock.primaryVenue)
            assertNull(stock.binanceSymbol)
            assertFalse(stock.isBinanceFuturesEligible)
        }

        val btc = MultiMarketInstrumentCatalog.resolve("BTC")
        assertNotNull(btc)
        assertEquals(AssetCategory.CRYPTO, btc!!.category)
        assertEquals(MarketVenue.BINANCE_USDT_M_FUTURES, btc.primaryVenue)
        assertEquals(MarketVenue.BYBIT_V5_LINEAR, btc.secondaryVenue)
        assertEquals("BTCUSDT", btc.binanceSymbol)
        assertEquals("BTCUSDT", btc.bybitSymbol)
        assertTrue(btc.isBinanceFuturesEligible)

        val sol = MultiMarketInstrumentCatalog.resolve("SOLUSDT")
        assertNotNull(sol)
        assertEquals("SOL", sol!!.canonicalId)
        assertTrue(sol.isBinanceFuturesEligible)

        // Unverified ticker without USDT suffix must NOT auto-route to Binance Futures
        assertNull(MultiMarketInstrumentCatalog.resolve("TSLA"))
    }

    @Test
    fun `bybit v5 linear kline parser sorts newest-first rows ascending chronologically`() {
        val body = """
            {
              "retCode": 0,
              "retMsg": "OK",
              "result": {
                "symbol": "BTCUSDT",
                "category": "linear",
                "list": [
                  ["1700000900000", "65100", "65300", "65000", "65250", "120.5", "7850000"],
                  ["1700000000000", "65000", "65200", "64900", "65100", "98.2", "6390000"]
                ]
              }
            }
        """.trimIndent()

        val candles = BybitV5PublicProvider.parseLinearKlineBody(body, CandleTimeframe.M15.durationMillis)
        assertEquals(2, candles.size)
        assertEquals(1700000000000L, candles[0].openTime)
        assertEquals(65100.0, candles[0].close, 0.001)
        assertEquals(1700000900000L, candles[1].openTime)
        assertEquals(65250.0, candles[1].close, 0.001)
    }

    @Test
    fun `candle api service aggregates yahoo hourly bars into 4h OHLCV cleanly`() {
        val baseBucket = (1_700_000_000_000L / (4L * 3_600_000L)) * (4L * 3_600_000L)
        val hourly = listOf(
            Candle(baseBucket + 0L, 100.0, 105.0, 99.0, 103.0, 10.0),
            Candle(baseBucket + 3_600_000L, 103.0, 108.0, 101.0, 106.0, 15.0),
            Candle(baseBucket + 7_200_000L, 106.0, 107.0, 97.5, 98.0, 20.0),
            Candle(baseBucket + 10_800_000L, 98.0, 104.0, 98.0, 102.5, 12.0),
            Candle(baseBucket + 14_400_000L, 102.5, 110.0, 102.0, 109.0, 25.0)
        )

        val h4 = CandleApiService.aggregateHourlyTo4H(hourly)
        assertEquals(2, h4.size)
        assertEquals(baseBucket, h4[0].openTime)
        assertEquals(100.0, h4[0].open, 0.001)
        assertEquals(108.0, h4[0].high, 0.001)
        assertEquals(97.5, h4[0].low, 0.001)
        assertEquals(102.5, h4[0].close, 0.001)
        assertEquals(57.0, h4[0].volume, 0.001)

        assertEquals(baseBucket + 14_400_000L, h4[1].openTime)
        assertEquals(109.0, h4[1].close, 0.001)
    }

    // =========================================================================
    // 2. DATA UNAVAILABLE & STRICT MTF ENGINE GATING
    // =========================================================================

    @Test
    fun `strict multi-market engine emits DATA_UNAVAILABLE on missing, stale, or unverified data`() = runBlocking {
        val validCandles = buildBullishTrendSeries(40, 100.0, CandleTimeframe.M15)
        val refTime = validCandles.last().closeTime + 10_000L

        // Unverified ticker
        val unverified = StrictMultiMarketSetupEngine.evaluate(
            symbol = "TSLA",
            currentPrice = 250.0,
            dataSourceName = "Unknown",
            candles1d = validCandles,
            candles4h = validCandles,
            candles1h = validCandles,
            candles15m = validCandles,
            referenceTimeMs = refTime
        )
        assertEquals(SetupValidationStatus.DATA_UNAVAILABLE, unverified.status)
        assertNull(unverified.bestSetup)

        // Stale price feed on NVDA
        val staleNvda = StrictMultiMarketSetupEngine.evaluate(
            symbol = "NVDA",
            currentPrice = 135.0,
            dataSourceName = MarketVenue.STOCKS_YAHOO.displayName,
            candles1d = validCandles,
            candles4h = validCandles,
            candles1h = validCandles,
            candles15m = validCandles,
            isDataStale = true,
            referenceTimeMs = refTime
        )
        assertEquals(SetupValidationStatus.DATA_UNAVAILABLE, staleNvda.status)
        assertTrue(staleNvda.unavailableReason?.contains("DATA UNAVAILABLE") == true)

        // Insufficient candle bars
        val insufficientGold = StrictMultiMarketSetupEngine.evaluate(
            symbol = "XAU",
            currentPrice = 2650.0,
            dataSourceName = MarketVenue.COMMODITIES_YAHOO.displayName,
            candles1d = emptyList(),
            candles4h = emptyList(),
            candles1h = validCandles.take(10),
            candles15m = validCandles.take(10),
            referenceTimeMs = refTime
        )
        assertEquals(SetupValidationStatus.DATA_UNAVAILABLE, insufficientGold.status)
    }

    @Test
    fun `strict engine tracks 1D-4H-1H-15M hierarchy and never mislabels stock or commodity venue`() = runBlocking {
        val c1d = buildBullishTrendSeries(35, 110.0, CandleTimeframe.D1)
        val c4h = buildBullishTrendSeries(45, 115.0, CandleTimeframe.H4)
        val c1h = buildBullishTrendSeries(50, 120.0, CandleTimeframe.H1)
        val c15m = buildBullishTrendSeries(55, 125.0, CandleTimeframe.M15)
        val refTime = maxOf(c1d.last().closeTime, c15m.last().closeTime) + 60_000L

        val eval = StrictMultiMarketSetupEngine.evaluate(
            symbol = "NVDA",
            currentPrice = c15m.last().close,
            dataSourceName = MarketVenue.STOCKS_YAHOO.displayName,
            candles1d = c1d,
            candles4h = c4h,
            candles1h = c1h,
            candles15m = c15m,
            referenceTimeMs = refTime
        )

        assertNotEquals(SetupValidationStatus.DATA_UNAVAILABLE, eval.status)
        assertEquals(AssetCategory.STOCKS, eval.category)
        assertEquals(MarketVenue.STOCKS_YAHOO.displayName, eval.venueName)
        assertFalse(eval.venueName.contains("Binance", ignoreCase = true))
        assertNotNull(eval.mtfHierarchy)
        assertNotNull(eval.mtfHierarchy!!.daily)
        assertNotNull(eval.mtfHierarchy!!.fourHour)
        assertNotNull(eval.mtfHierarchy!!.oneHour)
        assertNotNull(eval.mtfHierarchy!!.fifteenMin)
        assertEquals(FuturesDirection.LONG, eval.mtfHierarchy!!.htfMacroDirection)
        assertTrue(eval.timestampPkt.contains("PKT"))
    }

    // =========================================================================
    // 3. VOLUME PROFILE, PDH/PDL, PWH/PWL, BREAKOUT-RETEST & PRICE ACTION
    // =========================================================================

    @Test
    fun `advanced engine computes Volume Profile POC-VAH-VAL and PDH-PDL-PWH-PWL`() {
        val dayMs = 86_400_000L
        val week0Start = 1_699_833_600_000L // Monday UTC
        val candles = mutableListOf<Candle>()
        // Week 1 bars across 3 days
        for (d in 0..2) {
            val dayStart = week0Start + d * dayMs
            candles.add(Candle(dayStart + 3_600_000L * 1, 100.0 + d, 106.0 + d, 98.0 + d, 104.0 + d, 500.0))
            candles.add(Candle(dayStart + 3_600_000L * 6, 104.0 + d, 108.0 + d, 101.0 + d, 105.0 + d, 900.0))
        }
        // Week 2 (next week) bars across 2 days
        val week1Start = week0Start + 7 * dayMs
        candles.add(Candle(week1Start + 3_600_000L * 2, 108.0, 115.0, 107.0, 114.0, 1200.0))
        candles.add(Candle(week1Start + 3_600_000L * 8, 114.0, 117.0, 111.0, 113.0, 1100.0))
        candles.add(Candle(week1Start + dayMs + 3_600_000L * 2, 113.0, 116.0, 112.0, 115.0, 800.0))
        candles.add(Candle(week1Start + dayMs + 3_600_000L * 6, 115.0, 118.0, 114.0, 117.0, 950.0))

        val vp = AdvancedStructureAndFibEngine.computeVolumeProfile(candles)
        assertNotNull(vp)
        assertTrue(vp!!.isVolumeReliable)
        assertTrue(vp.vahPrice >= vp.pocPrice)
        assertTrue(vp.pocPrice >= vp.valPrice)

        val periods = AdvancedStructureAndFibEngine.extractPreviousPeriodLevels(candles)
        assertEquals(117.0, periods.pdh!!, 0.001)
        assertEquals(107.0, periods.pdl!!, 0.001)
        assertEquals(110.0, periods.pwh!!, 0.001)
        assertEquals(98.0, periods.pwl!!, 0.001)
    }

    @Test
    fun `detects failed bull trap, breakout retest, and contextual pin bar at zone`() {
        val baseTime = 1_700_000_000_000L
        val candles = mutableListOf<Candle>()
        for (i in 0 until 12) {
            candles.add(Candle(baseTime + i * 60_000L, 100.0, 102.0, 99.0, 101.0, 500.0))
        }
        // Breakout above zone [100..102] then bull trap closing below 100
        candles.add(Candle(baseTime + 12 * 60_000L, 101.5, 104.5, 101.0, 104.0, 1200.0)) // Break above 102
        candles.add(Candle(baseTime + 13 * 60_000L, 104.0, 104.2, 98.5, 99.0, 1500.0))   // Bull trap close below 100
        // Contextual Bullish Pin Bar at Support [98.0..99.5]
        candles.add(Candle(baseTime + 14 * 60_000L, 99.2, 99.7, 95.0, 99.6, 1800.0))

        val resZone = PriceLevelZone(
            id = "RES_1",
            type = PriceZoneType.RESISTANCE,
            centralPrice = 101.0,
            upperPrice = 102.0,
            lowerPrice = 100.0,
            touchesCount = 3,
            strength = 4,
            isCurrentlyActive = true,
            distancePercent = 1.4,
            zoneSources = listOf(ZoneSourceType.REPEATED_REACTION, ZoneSourceType.SWING_PIVOT),
            strengthScore = 72
        )
        val supZone = PriceLevelZone(
            id = "SUP_1",
            type = PriceZoneType.SUPPORT,
            centralPrice = 98.75,
            upperPrice = 99.5,
            lowerPrice = 98.0,
            touchesCount = 3,
            strength = 4,
            isCurrentlyActive = true,
            distancePercent = -0.8,
            zoneSources = listOf(ZoneSourceType.REPEATED_REACTION),
            strengthScore = 65
        )

        val trapsAndBreakouts = AdvancedStructureAndFibEngine.detectBreakoutsRetestsAndTraps(candles, listOf(resZone))
        assertTrue(trapsAndBreakouts.any { it.type == BreakoutRetestType.FAILED_BREAKOUT_BULL_TRAP })

        val patterns = AdvancedStructureAndFibEngine.detectContextualPriceAction(
            candles = candles,
            zones = listOf(supZone),
            orderBlocks = emptyList(),
            goldenZoneLow = null,
            goldenZoneHigh = null,
            atr = 1.5
        )
        assertTrue(
            patterns.any {
                it.type == PriceActionPatternType.BULLISH_PIN_BAR ||
                    it.type == PriceActionPatternType.BULLISH_REJECTION_WICK
            }
        )
    }

    // =========================================================================
    // 4. STRICT 7-GATE FIBONACCI GOLDEN ZONE & TYPE A vs TYPE B SETUP MODELS
    // =========================================================================

    @Test
    fun `touching golden zone without closed rejection candle fails strict 7-gate confirmation`() {
        val baseTime = 1_700_000_000_000L
        // Significant swing low at idx 2 (~100), swing high at idx 12 (~150) => 0.500 = 125.0, 0.618 = 119.1
        val prices = listOf(
            105.0, 102.0, 100.0, 106.0, 114.0, 122.0, 130.0, 136.0,
            142.0, 147.0, 150.0, 145.0, 138.0, 132.0, 127.0, 122.5 // sits in Golden Zone, small red close (no bullish trigger)
        )
        val candles = prices.mapIndexed { i, p ->
            val prev = if (i == 0) p else prices[i - 1]
            val low = if (i == 2) 100.0 else minOf(prev, p) - 0.6
            val high = if (i == 10) 150.0 else maxOf(prev, p) + 0.6
            Candle(baseTime + i * 900_000L, prev, high, low, p, 1000.0)
        }

        val swings = listOf(
            SwingPoint(2, candles[2].openTime, 100.0, SwingType.HIGHER_LOW, false),
            SwingPoint(10, candles[10].openTime, 150.0, SwingType.HIGHER_HIGH, true)
        )
        val structure = com.example.data.smc.MarketStructureAnalysis(
            trendState = com.example.data.smc.MarketTrendState.BULLISH_UPTREND,
            swingPoints = swings,
            structureBreaks = emptyList(),
            latestBreak = null,
            recentHigh = 150.0,
            recentLow = 100.0,
            isBullishBOSPresent = true,
            isBearishBOSPresent = false,
            isBullishCHoCHPresent = false,
            isBearishCHoCHPresent = false
        )
        val bullishOb = OrderBlock(
            id = "OB_1",
            type = OrderBlockType.BULLISH,
            topPrice = 124.0,
            bottomPrice = 119.0,
            candleIndex = 5,
            timestamp = candles[5].openTime,
            volume = 1200.0,
            status = OrderBlockStatus.UNMITIGATED
        )

        val (fib, _) = AdvancedStructureAndFibEngine.calculateValidatedFibonacci(
            candles = candles,
            structure = structure,
            orderBlocks = listOf(bullishOb),
            priceZones = emptyList(),
            htfSnapshots = emptyList()
        )

        assertNotNull(fib)
        val report = fib!!.validationReport!!
        assertTrue(report.hasConfirmedSwingLeg)
        assertTrue(report.isInGoldenZone0500To0618)
        assertTrue(report.hasSrOrOrderBlockConfluence)
        // Touch-only without bullish rejection/engulfing candle MUST fail gate 4 and remain NOT strictly valid
        assertFalse(report.hasConfirmedPriceActionReaction)
        assertFalse(report.isStrictlyValid)
    }

    @Test
    fun `smc engine detects MSS displacement on reversal shift`() = runBlocking {
        // Downtrend making LH/LL then huge displacement bullish candle breaking prior LH
        val baseTime = 1_700_000_000_000L
        val closes = listOf(
            200.0, 206.0, 210.0, 204.0, 195.0, 188.0, 192.0, 198.0, // SH 210, SL 188
            193.0, 184.0, 178.0, 182.0, 187.0, 181.0, 172.0, 176.0, // LH 198, LL 178 -> LH 187, LL 172
            183.0, 196.0, 214.0, 218.0, 212.0, 208.0               // Displacement up breaking LH 187 and SH 198
        )
        val candles = closes.mapIndexed { idx, c ->
            val open = if (idx == 0) 202.0 else closes[idx - 1]
            val h = maxOf(open, c) + (if (idx in listOf(2, 7, 12, 19)) 3.0 else 0.8)
            val l = minOf(open, c) - (if (idx in listOf(5, 10, 14)) 3.0 else 0.8)
            Candle(baseTime + idx * 3_600_000L, open, h, l, c, 1500.0)
        }

        val res = SmcCalculationEngine.analyze("BTC", "Bitcoin", CandleTimeframe.H1, candles)
        val hasBullShift = res.marketStructure.isBullishCHoCHPresent ||
            res.marketStructure.isBullishMSSPresent ||
            res.marketStructure.structureBreaks.any {
                it.type == MarketStructureBreakType.CHOCH_BULLISH ||
                    it.type == MarketStructureBreakType.MSS_BULLISH
            }
        assertTrue("Expected bullish CHoCH or MSS reversal shift", hasBullShift)
    }

    // =========================================================================
    // 5. TIME MACHINE INDEPENDENT BACKTESTS FOR TYPE A & TYPE B SETUPS
    // =========================================================================

    @Test
    fun `time machine runs independent backtests for Type A and Type B setups`() {
        val candles = buildOscillatingTrendSeries(90, 100.0, CandleTimeframe.H1)
        val refTime = candles.last().closeTime + 60_000L

        val backtestTypeA = TimeMachineEngine.runSetupModelBacktest(
            symbol = "BTC",
            timeframe = CandleTimeframe.H1,
            setupType = SetupModelType.LIQUIDITY_SWEEP_REVERSAL,
            direction = FuturesDirection.LONG,
            slDistancePercent = 1.5,
            tpDistancePercent = 3.2,
            candles = candles,
            referenceTimeMs = refTime
        )
        assertEquals(SetupModelType.LIQUIDITY_SWEEP_REVERSAL.label, backtestTypeA.setupTypeName)
        assertEquals(candles.size, backtestTypeA.completedCandlesEvaluated)

        val backtestTypeB = TimeMachineEngine.runSetupModelBacktest(
            symbol = "BTC",
            timeframe = CandleTimeframe.H1,
            setupType = SetupModelType.TREND_CONTINUATION,
            direction = FuturesDirection.LONG,
            slDistancePercent = 1.5,
            tpDistancePercent = 3.2,
            candles = candles,
            referenceTimeMs = refTime
        )
        assertEquals(SetupModelType.TREND_CONTINUATION.label, backtestTypeB.setupTypeName)
        assertTrue(backtestTypeB.sampleSize > 0)
    }

    // =========================================================================
    // TEST DATA HELPERS
    // =========================================================================

    private fun buildBullishTrendSeries(
        count: Int,
        startPrice: Double,
        tf: CandleTimeframe
    ): List<Candle> {
        val start = 1_700_000_000_000L
        val list = mutableListOf<Candle>()
        var price = startPrice
        for (i in 0 until count) {
            // 5 up, 3 down zig-zag creating clean HH + HL swings
            val cycle = i % 8
            val delta = if (cycle < 5) 1.8 else -1.4
            val open = price
            val close = (price + delta).coerceAtLeast(1.0)
            val high = maxOf(open, close) + (if (cycle == 4) 1.2 else 0.4)
            val low = minOf(open, close) - (if (cycle == 7) 1.2 else 0.4)
            val openTime = start + i * tf.durationMillis
            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1200.0 + i * 25.0,
                    closeTime = openTime + tf.durationMillis - 1L
                )
            )
            price = close
        }
        return list
    }

    private fun buildOscillatingTrendSeries(
        count: Int,
        startPrice: Double,
        tf: CandleTimeframe
    ): List<Candle> {
        val start = 1_700_000_000_000L
        val list = mutableListOf<Candle>()
        var price = startPrice
        for (i in 0 until count) {
            val phase = i % 10
            val delta = when {
                phase == 3 -> -2.2 // sweep dip
                phase == 4 -> 3.0  // reclaim bar
                phase in 0..6 -> 1.1
                else -> -0.8
            }
            val open = price
            val close = (price + delta).coerceAtLeast(1.0)
            val high = maxOf(open, close) + 0.7
            val low = minOf(open, close) - (if (phase == 3 || phase == 4) 1.8 else 0.5)
            val openTime = start + i * tf.durationMillis
            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1500.0 + (phase * 100.0),
                    closeTime = openTime + tf.durationMillis - 1L
                )
            )
            price = close
        }
        return list
    }
}
