package com.example

import com.example.data.indicators.IndicatorStance
import com.example.data.indicators.IndicatorValidationStatus
import com.example.data.indicators.RsiDivergenceType
import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.indicators.VolatilityRegime
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.providers.FearGreedSnapshot
import com.example.data.providers.FuturesLiquiditySnapshot
import com.example.data.providers.FuturesTelemetryStatus
import com.example.data.providers.OrderBookDepthSnapshot
import com.example.data.providers.ProviderHealthStatus
import com.example.data.providers.ProviderMetadata
import com.example.data.providers.UnifiedHybridMarketState
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.signal.EvidenceGroupId
import com.example.data.signal.SignalDecision
import com.example.data.signal.StrictQualificationTier
import com.example.data.signal.StrictSignalQualityEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterUpgradePart2StrictQualityTest {

    private val refTimeMs = 1_750_000_000_000L

    @Test
    fun `indicator math - EMA slopes, Pivot Points, Ichimoku lagging span and ADX-DMI separation`() = runBlocking {
        val candles = buildTrendingPullbackSeries(count = 220, bullish = true, basePrice = 50000.0)
        val analysis = TechnicalIndicatorsEngine.analyze(
            symbol = "BTC",
            assetName = "Bitcoin",
            timeframe = CandleTimeframe.H1,
            candles = candles,
            referenceTimeMs = refTimeMs
        )

        val slope = analysis.trend.emaSet.slopeEvaluation
        assertNotNull(slope)
        assertTrue("EMA 20/50/200 stack & slope should be bullish", slope!!.isBullishAlignedWithSlope)
        assertTrue((slope.ema20SlopePercent ?: 0.0) > 0.0)

        val adx = analysis.trend.adx
        assertNotNull(adx)
        assertEquals(IndicatorStance.BULLISH, adx!!.dmiDirection)

        val ichi = analysis.trend.ichimoku
        assertNotNull(ichi)
        assertTrue("Chikou lagging confirmation must evaluate against t-26 close", ichi!!.isChikouConfirmedBullish)

        // Exact Classic Pivot Points formula check on two explicit UTC days
        val day1Start = 1_749_600_000_000L // divisible cleanly by 86_400_000L
        val day2Start = day1Start + 86_400_000L
        val pivotTestCandles = listOf(
            Candle(openTime = day1Start, open = 100.0, high = 120.0, low = 90.0, close = 111.0, volume = 500.0),
            Candle(openTime = day2Start, open = 111.0, high = 115.0, low = 109.0, close = 114.0, volume = 400.0)
        )
        val pp = TechnicalIndicatorsEngine.calculatePivotPoints(pivotTestCandles)
        assertNotNull(pp)
        // PP = (120 + 90 + 111) / 3 = 107.0; R1 = 2*107 - 90 = 124.0; S1 = 2*107 - 120 = 94.0
        assertEquals(107.0, pp!!.pivotPoint, 0.001)
        assertEquals(124.0, pp.r1, 0.001)
        assertEquals(94.0, pp.s1, 0.001)
        assertEquals(137.0, pp.r2, 0.001)
        assertEquals(77.0, pp.s2, 0.001)
    }

    @Test
    fun `unclosed forming future candle is excluded to prevent repainting and future leakage`() = runBlocking {
        val closed = buildTrendingPullbackSeries(count = 80, bullish = true, basePrice = 100.0)
        val lastClosedTs = closed.last().openTime + 900_000L
        val unclosedCrashBar = Candle(
            openTime = lastClosedTs,
            open = closed.last().close,
            high = closed.last().close,
            low = 1.0,
            close = 1.0,
            volume = 999_999.0,
            closeTime = lastClosedTs + 900_000L // strictly after refTime
        )
        val withForming = closed + unclosedCrashBar

        val analysisClosed = TechnicalIndicatorsEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, closed, referenceTimeMs = lastClosedTs + 1000L)
        val analysisWithForming = TechnicalIndicatorsEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, withForming, referenceTimeMs = lastClosedTs + 1000L)

        assertEquals(analysisClosed.candleCount, analysisWithForming.candleCount)
        assertEquals(analysisClosed.currentClose, analysisWithForming.currentClose, 0.0001)
        assertEquals(analysisClosed.trend.emaSet.ema20 ?: 0.0, analysisWithForming.trend.emaSet.ema20 ?: 0.0, 0.0001)
        assertTrue(analysisWithForming.warnings.any { it.contains("unclosed", ignoreCase = true) })
    }

    @Test
    fun `non-repainting confirmed-swing RSI divergence detects bullish regular divergence only on closed 5-bar swings`() {
        val baseTime = 1_740_000_000_000L
        val prices = mutableListOf<Double>()
        // 30 bars steady decline to form oversold base
        var p = 200.0
        repeat(24) { p -= 2.2; prices.add(p) } // ~147.2
        // First deep V-bottom at index 26
        prices.add(143.0) // 24
        prices.add(138.0) // 25
        prices.add(130.0) // 26 -> Swing Low #1
        prices.add(139.0) // 27
        prices.add(146.0) // 28
        prices.add(152.0) // 29
        prices.add(155.0) // 30
        prices.add(150.0) // 31
        prices.add(142.0) // 32
        prices.add(134.0) // 33
        // Second lower price low at index 34 (with less sharp RSI drop -> higher RSI than #1)
        prices.add(128.5) // 34 -> Swing Low #2 (lower price, higher RSI)
        prices.add(133.0) // 35
        prices.add(137.0) // 36 (right shoulder closes at index 36!)

        val candles = prices.mapIndexed { idx, close ->
            Candle(
                openTime = baseTime + idx * 900_000L,
                open = close + 0.5,
                high = close + 2.0,
                low = close - 1.5,
                close = close,
                volume = 1200.0
            )
        }

        val div = TechnicalIndicatorsEngine.detectNonRepaintingRsiDivergence(candles, 14)
        assertNotNull("Confirmed closed 5-bar swing divergence must be detected", div)
        assertEquals(RsiDivergenceType.BULLISH_REGULAR, div!!.type)
        assertTrue(div.isConfirmedClosedSwings)
        assertTrue("Swing #2 index must leave >=2 right confirmation bars", div.swing2Index <= candles.size - 3)
        assertTrue(div.swing2Price < div.swing1Price)
        assertTrue(div.swing2Rsi > div.swing1Rsi)
    }

    @Test
    fun `missing or zero volume is never invented as 1_0x and disables Group D cleanly`() = runBlocking {
        val noVolCandles = buildTrendingPullbackSeries(count = 100, bullish = true, basePrice = 2500.0)
            .map { it.copy(volume = 0.0, takerBuyBaseVolume = 0.0) }

        val vols = DoubleArray(noVolCandles.size) { it.toDouble() * 0.0 }
        assertNull("RVOL must be null when volume is zero (never 1.0)", TechnicalIndicatorsEngine.calculateRvol(vols, 20))
        assertNull("VWAP must be null when volume is zero", TechnicalIndicatorsEngine.calculateVwap(noVolCandles, CandleTimeframe.M15))
        assertNull("MFI must be null when volume is zero", TechnicalIndicatorsEngine.calculateMfi(noVolCandles, 14))
        assertNull("CMF must be null when volume is zero", TechnicalIndicatorsEngine.calculateCmf(noVolCandles, 20))

        val ta = TechnicalIndicatorsEngine.analyze("XAU", "Gold Spot", CandleTimeframe.H1, noVolCandles, referenceTimeMs = refTimeMs)
        assertFalse(ta.volume.hasReliableVolume)
        assertNull(ta.volume.relativeVolume)

        val report = StrictSignalQualityEngine.evaluateSetup(
            symbol = "XAU",
            assetName = "Gold Spot",
            currentPrice = noVolCandles.last().close,
            dataStatus = MarketDataStatus.DELAYED,
            primaryTimeframe = CandleTimeframe.H1,
            candles = noVolCandles,
            technicalAnalysis = ta,
            referenceTimeMs = refTimeMs
        )
        val groupD = report.evidenceGroups.first { it.groupId == EvidenceGroupId.D_VOLUME_MONEY_FLOW }
        assertEquals(0, groupD.awardedPoints)
        assertEquals(IndicatorValidationStatus.UNAVAILABLE, groupD.status)
    }

    @Test
    fun `BB inside Keltner squeeze and extreme unstable volatility rejection`() = runBlocking {
        // 1. Tight compressed candles -> BB inside Keltner squeeze (high/low ranges wide relative to tight closes)
        val squeezeCandles = (0 until 60).map { i ->
            val c = 100.0 + (if (i % 2 == 0) 0.05 else -0.05)
            Candle(
                openTime = refTimeMs - (60 - i) * 900_000L,
                open = c,
                high = c + 1.5,
                low = c - 1.5,
                close = c,
                volume = 1000.0,
                closeTime = refTimeMs - (59 - i) * 900_000L
            )
        }
        val squeezeTa = TechnicalIndicatorsEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, squeezeCandles, referenceTimeMs = refTimeMs)
        assertTrue(squeezeTa.volatility.isBbInsideKeltnerSqueeze)
        assertEquals(VolatilityRegime.SQUEEZE, squeezeTa.volatility.volatilityRegime)

        // 2. Extreme wild swings -> ATR > 6.5% -> EXTREME_UNSTABLE -> Filter #6 fails -> NO TRADE
        val extremeCandles = (0 until 60).map { i ->
            val mid = 100.0 + (if (i % 2 == 0) 8.0 else -8.0)
            Candle(
                openTime = refTimeMs - (60 - i) * 900_000L,
                open = mid - 4.0,
                high = mid + 6.0,
                low = mid - 6.0,
                close = mid + 4.0,
                volume = 5000.0,
                closeTime = refTimeMs - (59 - i) * 900_000L
            )
        }
        val extremeTa = TechnicalIndicatorsEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, extremeCandles, referenceTimeMs = refTimeMs)
        assertEquals(VolatilityRegime.EXTREME_UNSTABLE, extremeTa.volatility.volatilityRegime)

        val extremeReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = extremeCandles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = extremeCandles,
            technicalAnalysis = extremeTa,
            referenceTimeMs = refTimeMs
        )
        assertEquals(StrictQualificationTier.NO_TRADE, extremeReport.qualificationTier)
        assertFalse(extremeReport.mandatoryFilters.first { it.filterNumber == 6 }.passed)
        assertEquals(0, extremeReport.evidenceGroups.first { it.groupId == EvidenceGroupId.F_VOLATILITY_RISK }.awardedPoints)
    }

    @Test
    fun `real Binance depth parsing and stale vs missing futures data rules`() = runBlocking {
        val sampleJson = """
            {
              "lastUpdateId": 1027024,
              "bids": [["65000.00", "2.5"], ["64995.00", "4.0"]],
              "asks": [["65005.00", "1.2"], ["65010.00", "2.0"]]
            }
        """.trimIndent()
        val parsed = BinanceFuturesApiClient().parseOrderBookDepth("BTCUSDT", sampleJson, refTimeMs)
        assertNotNull(parsed)
        assertEquals(65000.0, parsed!!.bestBid, 0.01)
        assertEquals(65005.0, parsed.bestAsk, 0.01)
        assertTrue(parsed.spreadBps < 2.0)
        assertTrue("Bid notional exceeds ask notional", parsed.bidAskImbalanceRatio > 1.5)

        val candles = buildAlignedBreakoutSeries(bullish = true)
        // Case A: Stale Futures Data -> Group G = 0, Filter #8 FAIL, forced NO_TRADE even with strong technicals
        val staleFutures = FuturesLiquiditySnapshot(
            symbol = "BTCUSDT",
            isCryptoFutures = true,
            status = FuturesTelemetryStatus.STALE,
            openInterestContracts = 50_000.0,
            fundingRatePercent = 0.01,
            longShortAccountRatio = 1.1,
            fetchedAtMillis = refTimeMs - 900_000L
        )
        val staleReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = candles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = candles,
            candles15m = candles,
            candles1h = candles,
            candles4h = candles,
            candles1d = candles,
            futuresLiquidity = staleFutures,
            proposedEntryPrice = candles.last().close,
            proposedStopLoss = candles.last().close * 0.99,
            proposedTargetPrice = candles.last().close * 1.05,
            candidateDirectionOverride = SignalDecision.LONG,
            referenceTimeMs = refTimeMs
        )
        val staleGroupG = staleReport.evidenceGroups.first { it.groupId == EvidenceGroupId.G_FUTURES_LIQUIDITY }
        assertEquals(0, staleGroupG.awardedPoints)
        assertEquals(IndicatorValidationStatus.STALE, staleGroupG.status)
        assertFalse("Stale futures data MUST fail Filter #8", staleReport.mandatoryFilters.first { it.filterNumber == 8 }.passed)
        assertEquals(StrictQualificationTier.NO_TRADE, staleReport.qualificationTier)

        // Case B: Non-Crypto (NVDA stock) -> Group G = 0 (Not Applicable) and Filter #8 passes cleanly
        val stockReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "NVDA",
            assetName = "NVIDIA Corporation",
            currentPrice = candles.last().close,
            dataStatus = MarketDataStatus.DELAYED,
            primaryTimeframe = CandleTimeframe.M15,
            candles = candles,
            candles15m = candles,
            candles1h = candles,
            candles4h = candles,
            candles1d = candles,
            proposedEntryPrice = candles.last().close,
            proposedStopLoss = candles.last().close * 0.99,
            proposedTargetPrice = candles.last().close * 1.05,
            candidateDirectionOverride = SignalDecision.LONG,
            referenceTimeMs = refTimeMs
        )
        val stockGroupG = stockReport.evidenceGroups.first { it.groupId == EvidenceGroupId.G_FUTURES_LIQUIDITY }
        assertEquals(0, stockGroupG.awardedPoints)
        assertTrue(stockReport.mandatoryFilters.first { it.filterNumber == 8 }.passed)
    }

    @Test
    fun `score cannot override failed Net RR below 1_4 or contradictory HTF`() = runBlocking {
        val candles = buildAlignedBreakoutSeries(bullish = true)
        val liveFutures = buildHealthyFuturesSnapshot(isLong = true, symbol = "BTCUSDT", price = candles.last().close)

        // Poor R:R (1:2.0 < 1:4.0) MUST be rejected as NO_TRADE even with strong technical alignment
        val lowRrReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = candles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = candles,
            candles15m = candles,
            candles1h = candles,
            candles4h = candles,
            candles1d = candles,
            futuresLiquidity = liveFutures,
            proposedEntryPrice = candles.last().close,
            proposedStopLoss = candles.last().close * 0.99,
            proposedTargetPrice = candles.last().close * 1.02, // Gross 2.0R -> Net < 2.0R
            candidateDirectionOverride = SignalDecision.LONG,
            referenceTimeMs = refTimeMs
        )
        assertFalse(lowRrReport.mandatoryFilters.first { it.filterNumber == 7 }.passed)
        assertEquals(StrictQualificationTier.NO_TRADE, lowRrReport.qualificationTier)

        // Strong Bearish Daily/4H opposing a LONG candidate without sweep reversal -> Filter #5 fails -> NO_TRADE
        val bearishD1 = buildTrendingPullbackSeries(count = 80, bullish = false, basePrice = 80000.0)
        val htfConflictReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = candles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = candles,
            candles15m = candles,
            candles1h = candles,
            candles4h = bearishD1,
            candles1d = bearishD1,
            futuresLiquidity = liveFutures,
            proposedEntryPrice = candles.last().close,
            proposedStopLoss = candles.last().close * 0.99,
            proposedTargetPrice = candles.last().close * 1.055,
            candidateDirectionOverride = SignalDecision.LONG,
            referenceTimeMs = refTimeMs
        )
        assertFalse(htfConflictReport.mandatoryFilters.first { it.filterNumber == 5 }.passed)
        assertEquals(StrictQualificationTier.NO_TRADE, htfConflictReport.qualificationTier)
    }

    @Test
    fun `genuine LONG and SHORT setups pass all 8 filters and earn QUALIFIED or HIGH_QUALITY tier`() = runBlocking {
        // LONG
        val bullCandles = buildAlignedBreakoutSeries(bullish = true)
        val bullPrice = bullCandles.last().close
        val bullFutures = buildHealthyFuturesSnapshot(isLong = true, symbol = "BTCUSDT", price = bullPrice)
        val healthyHybrid = UnifiedHybridMarketState(
            fearGreed = FearGreedSnapshot(
                value = 55,
                classification = "Neutral",
                apiTimestampSeconds = refTimeMs / 1000L,
                fetchedAtMillis = refTimeMs,
                metadata = ProviderMetadata(
                    providerId = "alt_me",
                    providerName = "Alternative.me",
                    endpointUsed = "/fng",
                    dataStatus = MarketDataStatus.LIVE,
                    health = ProviderHealthStatus.ACTIVE,
                    fetchedAtMillis = refTimeMs,
                    timestampFormatted = "live"
                )
            )
        )
        val bullReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = bullPrice,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = bullCandles,
            candles15m = bullCandles,
            candles1h = bullCandles,
            candles4h = bullCandles,
            candles1d = bullCandles,
            futuresLiquidity = bullFutures,
            hybridState = healthyHybrid,
            proposedEntryPrice = bullPrice,
            proposedStopLoss = bullPrice * 0.99,
            proposedTargetPrice = bullPrice * 1.055, // > 4.5 Net R:R after 0.16% cost
            candidateDirectionOverride = SignalDecision.LONG,
            referenceTimeMs = refTimeMs
        )
        assertTrue("All 8 filters must pass for genuine LONG: ${bullReport.missingConfirmations}", bullReport.allMandatoryFiltersPassed)
        assertTrue("Score must be >= 85 (actual ${bullReport.totalScore}: ${bullReport.evidenceGroups.joinToString { "${it.groupId.code}=${it.awardedPoints}" }})", bullReport.totalScore >= 85)
        assertEquals(SignalDecision.LONG, bullReport.candidateDirection)
        assertTrue(
            bullReport.qualificationTier == StrictQualificationTier.QUALIFIED_SETUP ||
                bullReport.qualificationTier == StrictQualificationTier.HIGH_QUALITY_SETUP
        )
        assertTrue(bullReport.qualityScoreDisclaimer.contains("NOT probability of winning"))

        // SHORT symmetry
        val bearCandles = buildAlignedBreakoutSeries(bullish = false)
        val bearPrice = bearCandles.last().close
        val bearFutures = buildHealthyFuturesSnapshot(isLong = false, symbol = "ETHUSDT", price = bearPrice)
        val bearReport = StrictSignalQualityEngine.evaluateSetup(
            symbol = "ETH",
            assetName = "Ethereum",
            currentPrice = bearPrice,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = bearCandles,
            candles15m = bearCandles,
            candles1h = bearCandles,
            candles4h = bearCandles,
            candles1d = bearCandles,
            futuresLiquidity = bearFutures,
            hybridState = healthyHybrid,
            proposedEntryPrice = bearPrice,
            proposedStopLoss = bearPrice * 1.01,
            proposedTargetPrice = bearPrice * 0.945,
            candidateDirectionOverride = SignalDecision.SHORT,
            referenceTimeMs = refTimeMs
        )
        assertTrue("All 8 filters must pass for genuine SHORT: ${bearReport.missingConfirmations}", bearReport.allMandatoryFiltersPassed)
        assertTrue("Score must be >= 85 (actual ${bearReport.totalScore}: ${bearReport.evidenceGroups.joinToString { "${it.groupId.code}=${it.awardedPoints}" }})", bearReport.totalScore >= 85)
        assertEquals(SignalDecision.SHORT, bearReport.candidateDirection)
        assertTrue(
            bearReport.qualificationTier == StrictQualificationTier.QUALIFIED_SETUP ||
                bearReport.qualificationTier == StrictQualificationTier.HIGH_QUALITY_SETUP
        )

        // Verify exact 8 Evidence Groups sum = 100 max points
        assertEquals(8, bullReport.evidenceGroups.size)
        assertEquals(100, bullReport.evidenceGroups.sumOf { it.maxPoints })
    }

    // -------------------------------------------------------------------------
    // TEST DATA FIXTURES
    // -------------------------------------------------------------------------

    private fun buildHealthyFuturesSnapshot(
        isLong: Boolean,
        symbol: String,
        price: Double
    ): FuturesLiquiditySnapshot {
        val bidNotional = if (isLong) 1_500_000.0 else 750_000.0
        val askNotional = if (isLong) 750_000.0 else 1_500_000.0
        return FuturesLiquiditySnapshot(
            symbol = symbol,
            isCryptoFutures = true,
            status = FuturesTelemetryStatus.LIVE,
            openInterestContracts = 82_000.0,
            fundingRatePercent = if (isLong) 0.008 else -0.008,
            longShortAccountRatio = if (isLong) 1.05 else 0.95,
            takerCvd = if (isLong) 45_000.0 else -45_000.0,
            lastBarVolumeDelta = if (isLong) 1_200.0 else -1_200.0,
            orderBookDepth = OrderBookDepthSnapshot(
                symbol = symbol,
                bestBid = price * 0.9998,
                bestAsk = price * 1.0002,
                spreadBps = 4.0,
                topBidsNotionalUsd = bidNotional,
                topAsksNotionalUsd = askNotional,
                bidAskImbalanceRatio = bidNotional / askNotional,
                strongestBidWallPrice = price * 0.995,
                strongestAskWallPrice = price * 1.02,
                fetchedAtMillis = refTimeMs - 15_000L
            ),
            liquidationDataAvailable = false,
            fetchedAtMillis = refTimeMs - 15_000L
        )
    }

    private fun buildTrendingPullbackSeries(
        count: Int,
        bullish: Boolean,
        basePrice: Double
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        var price = basePrice
        val startMs = refTimeMs - (count.toLong() + 2L) * 3_600_000L
        for (i in 0 until count) {
            val isPullback = (i % 10) in 6..7 && i < count - 4
            val pct = when {
                bullish && !isPullback -> 0.0045
                bullish && isPullback -> -0.0020
                !bullish && !isPullback -> -0.0045
                else -> 0.0020
            }
            val open = price
            val close = (open * (1.0 + pct)).coerceAtLeast(10.0)
            val high = maxOf(open, close) * 1.0018
            val low = minOf(open, close) * 0.9982
            val vol = if (isPullback) 1200.0 else 3200.0
            val buyRatio = if (bullish) {
                if (isPullback) 0.48 else 0.70
            } else {
                if (isPullback) 0.52 else 0.30
            }
            val openTs = startMs + i * 3_600_000L
            list.add(
                Candle(
                    openTime = openTs,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    closeTime = openTs + 3_599_000L,
                    takerBuyBaseVolume = vol * buyRatio
                )
            )
            price = close
        }
        return list
    }

    /**
     * Builds a realistic trend + Order Block displacement leg + controlled pullback into OB/S/R
     * + high-volume engulfing/continuation close with clean HH+HL (LONG) or LH+LL (SHORT).
     */
    private fun buildAlignedBreakoutSeries(bullish: Boolean): List<Candle> {
        val list = mutableListOf<Candle>()
        var price = if (bullish) 45000.0 else 65000.0
        val count = 210
        val startMs = refTimeMs - (count.toLong() + 2L) * 900_000L

        for (i in 0 until count - 10) {
            val isPullback = (i % 10) in 6..8
            val pct = when {
                bullish && !isPullback -> 0.0022
                bullish && isPullback -> -0.0016
                !bullish && !isPullback -> -0.0022
                else -> 0.0016
            }
            val open = price
            val close = (open * (1.0 + pct)).coerceAtLeast(100.0)
            val high = maxOf(open, close) * 1.0012
            val low = minOf(open, close) * 0.9988
            val vol = if (isPullback) 1100.0 else 2300.0
            val buyRatio = if (bullish) (if (isPullback) 0.45 else 0.65) else (if (isPullback) 0.55 else 0.35)
            val openTs = startMs + i * 900_000L
            list.add(
                Candle(
                    openTime = openTs,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    closeTime = openTs + 899_000L,
                    takerBuyBaseVolume = vol * buyRatio
                )
            )
            price = close
        }

        // Bars count-10 .. count-8: Create an active Order Block (counter-candle + 2-bar displacement > 1.5x avgBody)
        val obOpen = price
        val obClose = if (bullish) obOpen * 0.9975 else obOpen * 1.0025
        val obLow = minOf(obOpen, obClose) * 0.999
        val obHigh = maxOf(obOpen, obClose) * 1.001
        val obTs = startMs + (count - 10) * 900_000L
        list.add(
            Candle(
                openTime = obTs,
                open = obOpen,
                high = obHigh,
                low = obLow,
                close = obClose,
                volume = 1300.0,
                closeTime = obTs + 899_000L,
                takerBuyBaseVolume = 1300.0 * (if (bullish) 0.42 else 0.58)
            )
        )
        price = obClose

        // Strong displacement bars (count-9, count-8)
        for (k in 9 downTo 8) {
            val open = price
            val pct = if (bullish) 0.0062 else -0.0062
            val close = open * (1.0 + pct)
            val ts = startMs + (count - k) * 900_000L
            list.add(
                Candle(
                    openTime = ts,
                    open = open,
                    high = maxOf(open, close) * 1.001,
                    low = minOf(open, close) * 0.999,
                    close = close,
                    volume = 3100.0,
                    closeTime = ts + 899_000L,
                    takerBuyBaseVolume = 3100.0 * (if (bullish) 0.72 else 0.28)
                )
            )
            price = close
        }

        // Bars count-7 .. count-2: Controlled 6-bar flag pullback toward the Order Block zone (keeping RSI ~56-65 / 35-44)
        for (k in 7 downTo 2) {
            val open = price
            val pct = if (bullish) -0.0014 else 0.0014
            val close = open * (1.0 + pct)
            val ts = startMs + (count - k) * 900_000L
            val touchLow = if (k == 2 && bullish) minOf(close, obHigh * 0.9995) else minOf(open, close) * 0.9992
            val touchHigh = if (k == 2 && !bullish) maxOf(close, obLow * 1.0005) else maxOf(open, close) * 1.0008
            list.add(
                Candle(
                    openTime = ts,
                    open = open,
                    high = touchHigh,
                    low = touchLow,
                    close = close,
                    volume = 1150.0,
                    closeTime = ts + 899_000L,
                    takerBuyBaseVolume = 1150.0 * (if (bullish) 0.45 else 0.55)
                )
            )
            price = close
        }

        // Final bar count-1: Confirmed high-RVOL engulfing breakout close in trend direction
        val prevOpen = list.last().open
        val open2 = price
        val close2 = if (bullish) maxOf(prevOpen * 1.0045, open2 * 1.006) else minOf(prevOpen * 0.9955, open2 * 0.994)
        val openTs2 = startMs + (count - 1) * 900_000L
        val triggerVol = 4900.0 // > 1.8x 20-bar avg
        list.add(
            Candle(
                openTime = openTs2,
                open = open2,
                high = if (bullish) close2 * 1.0005 else open2 * 1.0008,
                low = if (bullish) open2 * 0.9992 else close2 * 0.9995,
                close = close2,
                volume = triggerVol,
                closeTime = openTs2 + 899_000L,
                takerBuyBaseVolume = triggerVol * (if (bullish) 0.78 else 0.22)
            )
        )

        return list
    }
}
