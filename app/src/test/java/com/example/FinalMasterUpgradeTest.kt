package com.example

import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.paper.PaperExitReason
import com.example.data.paper.PaperOrderActionResult
import com.example.data.paper.PaperOrderSide
import com.example.data.paper.PaperTradingEngine
import com.example.data.paper.PaperWalletConfig
import com.example.data.providers.FuturesLiquiditySnapshot
import com.example.data.providers.FuturesTelemetryStatus
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.ActiveSignalStateManager
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.ManagedSignalState
import com.example.data.signal.AiTradingSignalEngine
import com.example.data.signal.EvidenceGroupId
import com.example.data.signal.SignalDecision
import com.example.data.signal.StrictQualificationTier
import com.example.data.signal.StrictSignalQualityEngine
import com.example.data.smc.SmcCalculationEngine
import com.example.data.timemachine.MultiCoinBacktestConfig
import com.example.data.timemachine.SingleCoinHistoricalInput
import com.example.data.timemachine.StrategyBacktestEngine
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
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinalMasterUpgradeTest {

    // ---------------------------------------------------------------------------------------------
    // Synthetic Historical Generators (Strict Closed Candles Only)
    // ---------------------------------------------------------------------------------------------

    private fun buildClosed15mCandles(
        count: Int,
        startTimeMs: Long = 1_700_000_000_000L,
        basePrice: Double = 60_000.0,
        driftPerBar: Double = 18.0,
        waveAmp: Double = 120.0,
        volumeBase: Double = 1_500.0
    ): List<Candle> {
        val step = 15L * 60_000L
        val list = ArrayList<Candle>(count)
        var prevClose = basePrice
        for (i in 0 until count) {
            val openTime = startTimeMs + i * step
            val closeTime = openTime + step - 1L
            val wave = sin(i * 0.23) * waveAmp + sin(i * 0.61) * (waveAmp * 0.45)
            val close = (basePrice + i * driftPerBar + wave).coerceAtLeast(100.0)
            val open = prevClose
            val high = maxOf(open, close) + waveAmp * 0.35 + (if (i % 7 == 0) waveAmp * 0.5 else 0.0)
            val low = minOf(open, close) - waveAmp * 0.35 - (if (i % 9 == 0) waveAmp * 0.4 else 0.0)
            val vol = volumeBase * (1.0 + 0.4 * ((i % 5) / 4.0))
            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = vol,
                    closeTime = closeTime
                )
            )
            prevClose = close
        }
        return list
    }

    private fun aggregateBars(bars15m: List<Candle>, groupSize: Int): List<Candle> {
        val out = ArrayList<Candle>(bars15m.size / groupSize + 1)
        var i = 0
        while (i + groupSize <= bars15m.size) {
            val slice = bars15m.subList(i, i + groupSize)
            out.add(
                Candle(
                    openTime = slice.first().openTime,
                    open = slice.first().open,
                    high = slice.maxOf { it.high },
                    low = slice.minOf { it.low },
                    close = slice.last().close,
                    volume = slice.sumOf { it.volume },
                    closeTime = slice.last().closeTime
                )
            )
            i += groupSize
        }
        return out
    }

    private fun buildFundingRates(
        symbol: String,
        startTimeMs: Long,
        count: Int,
        rateFraction: Double = 0.0001
    ): List<BinanceFuturesApiClient.HistoricalFundingEntry> {
        val step = 8L * 60L * 60_000L
        return (0 until count).map { i ->
            BinanceFuturesApiClient.HistoricalFundingEntry(
                symbol = symbol,
                fundingTimeMs = startTimeMs + i * step,
                fundingRateFraction = rateFraction
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 1. STRICT PROFESSIONAL SIGNAL ENGINE TESTS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun strictSignalEngine_downgradesWeakOrInsufficientSetupToNoTradeWithExactReason() = runBlocking {
        val shortCandles = buildClosed15mCandles(count = 15, driftPerBar = 1.0, waveAmp = 5.0)
        val refTime = shortCandles.last().closeTime + 1_000L

        val result = AiTradingSignalEngine.generateSignal(
            symbol = "BTC",
            assetName = "Bitcoin",
            currentPrice = shortCandles.last().close,
            dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance Futures Public REST",
            lastUpdatedTime = refTime,
            primaryTimeframe = CandleTimeframe.H1,
            candles = shortCandles,
            technicalAnalysis = null,
            smcAnalysis = null,
            enforceStrictQualificationGate = true,
            strictMinScore = StrictSignalQualityEngine.SCORE_THRESHOLD_HIGH_QUALITY
        )

        assertEquals(SignalDecision.NO_TRADE, result.decision)
        assertNotNull(result.noTradeReason)
        assertTrue(result.noTradeReason!!.headline.isNotBlank())
        assertNull(result.riskPlan)
    }

    @Test
    fun strictSignalEngine_enforces92ScoreAndNetRr4AndMarksMissingCoinGlassUnavailable() = runBlocking {
        val baseStart = 1_700_000_000_000L
        val candles15m = buildClosed15mCandles(count = 1_600, startTimeMs = baseStart, driftPerBar = 8.0, waveAmp = 90.0)
        val candles1h = aggregateBars(candles15m, 4)
        val candles4h = aggregateBars(candles15m, 16)
        val candles1d = aggregateBars(candles15m, 96)
        val refTime = candles15m.last().closeTime + 1_000L

        // Intentionally supply a weak/sub-1:4 proposed target so Filter #8 / < 92 fails
        val entry = candles15m.last().close
        val badSl = entry * 0.98
        val badTp = entry * 1.03 // Gross R:R ~ 1.5 (< 4.0)

        val ta = TechnicalIndicatorsEngine.analyze("BTCUSDT", "Bitcoin", CandleTimeframe.H1, candles1h, refTime)
        val smc = SmcCalculationEngine.analyze("BTCUSDT", "Bitcoin", CandleTimeframe.H1, candles1h)

        val report = StrictSignalQualityEngine.evaluateSetup(
            symbol = "BTCUSDT",
            assetName = "Bitcoin",
            currentPrice = entry,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.H1,
            candles = candles1h,
            candles15m = candles15m,
            candles1h = candles1h,
            candles4h = candles4h,
            candles1d = candles1d,
            technicalAnalysis = ta,
            smcAnalysis = smc,
            futuresLiquidity = FuturesLiquiditySnapshot(
                symbol = "BTCUSDT",
                isCryptoFutures = true,
                status = FuturesTelemetryStatus.LIVE,
                openInterestContracts = 500_000.0,
                fundingRatePercent = 0.01,
                liquidationDataAvailable = false,
                fetchedAtMillis = refTime - 5_000L
            ),
            proposedEntryPrice = entry,
            proposedStopLoss = badSl,
            proposedTargetPrice = badTp,
            referenceTimeMs = refTime
        )

        // Missing CoinGlass / liquidation feed must remain UNAVAILABLE, never invented
        val groupG = report.evidenceGroups.firstOrNull { it.groupId == EvidenceGroupId.G_FUTURES_LIQUIDITY }
        assertNotNull(groupG)
        assertTrue(groupG!!.evidenceMissing.any { it.contains("Unavailable", ignoreCase = true) || it.contains("Liquidation", ignoreCase = true) })

        // Because proposed R:R is 1.5 (< 4.0) and strict Pro threshold requires >= 92 + all 8 filters:
        assertFalse(StrictSignalQualityEngine.meetsStrictProQualification(report, minScore = 92))
        assertEquals(StrictQualificationTier.NO_TRADE, report.qualificationTier)
        assertTrue(report.mandatoryFilters.any { !it.passed })
    }

    @Test
    fun strictSignalEngine_blocksStrongContradictoryHigherTimeframeDirection() = runBlocking {
        // Construct a macro downtrend on 4H & 1D while checking LONG candidate
        val start = 1_700_000_000_000L
        val candles15m = buildClosed15mCandles(
            count = 1_600,
            startTimeMs = start,
            basePrice = 80_000.0,
            driftPerBar = -15.0,
            waveAmp = 80.0
        )
        val candles1h = aggregateBars(candles15m, 4)
        val candles4h = aggregateBars(candles15m, 16)
        val candles1d = aggregateBars(candles15m, 96)
        val refTime = candles15m.last().closeTime + 1_000L
        val lastPrice = candles15m.last().close

        // Override candidate LONG against strong 4H + 1D downtrend
        val report = StrictSignalQualityEngine.evaluateSetup(
            symbol = "ETHUSDT",
            assetName = "Ethereum",
            currentPrice = lastPrice,
            dataStatus = MarketDataStatus.LIVE,
            primaryTimeframe = CandleTimeframe.M15,
            candles = candles15m,
            candles15m = candles15m,
            candles1h = candles1h,
            candles4h = candles4h,
            candles1d = candles1d,
            proposedEntryPrice = lastPrice,
            proposedStopLoss = lastPrice * 0.99,
            proposedTargetPrice = lastPrice * 1.06,
            candidateDirectionOverride = SignalDecision.LONG,
            referenceTimeMs = refTime
        )

        assertFalse(StrictSignalQualityEngine.meetsStrictProQualification(report, minScore = 92))
        assertEquals(StrictQualificationTier.NO_TRADE, report.qualificationTier)
        assertTrue(report.mandatoryFilters.any { !it.passed })
    }

    @Test
    fun activeSignalStateManager_preventsDuplicatesAndBlocksSilentOppositeFlip() {
        val manager = ActiveSignalStateManager(defaultNotificationCooldownMs = 45 * 60_000L)
        val now = 1_700_100_000_000L
        val candleClose = now - 5_000L
        val expires = now + 4 * 3_600_000L

        val first = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            isConfirmed = true,
            entryPrice = 60_000.0,
            stopLoss = 59_000.0,
            takeProfit1 = 62_500.0,
            takeProfit2 = 65_000.0,
            netRiskReward = 4.6,
            qualityScore = 94,
            dataSourceName = "Binance Futures",
            setupReason = "BOS + 15M Pinbar",
            confirmationCandleCloseMs = candleClose,
            expiresAtMs = expires,
            nowMs = now
        )
        assertTrue(first.accepted)
        val firstEntry = first.entry!!
        assertEquals(ManagedSignalState.ACTIVE, firstEntry.state)

        // Immediate opposite flip must be BLOCKED while LONG is ACTIVE
        val flip = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.SHORT,
            isConfirmed = true,
            entryPrice = 59_900.0,
            stopLoss = 60_900.0,
            takeProfit1 = 57_000.0,
            takeProfit2 = 55_000.0,
            netRiskReward = 4.5,
            qualityScore = 95,
            dataSourceName = "Binance Futures",
            setupReason = "Opposite candidate",
            confirmationCandleCloseMs = candleClose + 900_000L,
            expiresAtMs = expires + 900_000L,
            nowMs = now + 900_000L
        )
        assertFalse(flip.accepted)
        assertNotNull(flip.rejectionReason)
        assertTrue(flip.rejectionReason!!.contains("Blocked", ignoreCase = true))

        // Duplicate unconfirmed watching tick in same direction is suppressed
        val dup = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            isConfirmed = false,
            entryPrice = 60_050.0,
            stopLoss = 59_000.0,
            takeProfit1 = 62_500.0,
            takeProfit2 = 65_000.0,
            netRiskReward = 4.6,
            qualityScore = 94,
            dataSourceName = "Binance Futures",
            setupReason = "Same watch",
            confirmationCandleCloseMs = candleClose + 60_000L,
            expiresAtMs = expires,
            nowMs = now + 60_000L
        )
        assertFalse(dup.accepted)
        assertTrue(dup.isDuplicateSuppressed)

        // Cooldown prevents premature repeated notification
        manager.markNotificationDispatched(firstEntry.signalId, "BTCUSDT", FuturesDirection.LONG, now)
        assertFalse(manager.shouldDispatchNotification(firstEntry.signalId, "BTCUSDT", FuturesDirection.LONG, now + 60_000L))
        assertFalse(manager.shouldDispatchNotification(firstEntry.signalId, "BTCUSDT", FuturesDirection.LONG, now + 46 * 60_000L)) // same signal ID still blocked
        assertTrue(manager.shouldDispatchNotification("new_sig_2", "BTCUSDT", FuturesDirection.LONG, now + 46 * 60_000L))
    }

    // ---------------------------------------------------------------------------------------------
    // 2. COMPLETE PAPER TRADING ENGINE TESTS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun paperTrading_limitOrderNeverFillsPrematurelyAndRespectsEditAndCancel() {
        val start = PaperTradingEngine.resetWallet(
            initialBalanceUsdt = 10_000.0,
            configOverride = PaperWalletConfig(
                initialBalanceUsdt = 10_000.0,
                takerFeePercentPerSide = 0.05,
                makerFeePercentPerSide = 0.02,
                slippagePercentPerSide = 0.03,
                fundingRatePer8hPercent = 0.01
            ),
            nowMs = 1_700_000_000_000L
        )

        // Place LONG Limit at 58,000 while live price is 60,000 -> MUST stay PENDING!
        val placed = PaperTradingEngine.placeLimitOrder(
            snapshot = start,
            symbol = "BTCUSDT",
            side = PaperOrderSide.LONG,
            limitPrice = 58_000.0,
            quantity = 0.1,
            leverage = 5,
            stopLoss = 56_000.0,
            takeProfit = 66_000.0,
            nowMs = 1_700_000_010_000L
        )
        assertTrue(placed is PaperOrderActionResult.Success)
        val snap1 = (placed as PaperOrderActionResult.Success).snapshot
        assertEquals(1, snap1.pendingOrders.size)
        assertEquals(0, snap1.openPositions.size)
        val orderId = snap1.pendingOrders.first().id

        // Tick price at 59,000 -> above limit 58,000 -> MUST NOT fill
        val tickAbove = PaperTradingEngine.onMarketPricesUpdated(
            snap1,
            mapOf("BTCUSDT" to 59_000.0),
            nowMs = 1_700_000_020_000L
        )
        assertEquals(1, tickAbove.pendingOrders.size)
        assertEquals(0, tickAbove.openPositions.size)

        // Edit limit order price to 58,500 and quantity to 0.2
        val edited = PaperTradingEngine.editPendingOrder(
            snapshot = tickAbove,
            orderId = orderId,
            newLimitPrice = 58_500.0,
            newQuantity = 0.2,
            newStopLoss = 56_500.0,
            newTakeProfit = 67_000.0,
            nowMs = 1_700_000_030_000L
        )
        assertTrue(edited is PaperOrderActionResult.Success)
        val snapEdited = (edited as PaperOrderActionResult.Success).snapshot
        assertEquals(58_500.0, snapEdited.pendingOrders.first().limitPrice, 1e-6)
        assertEquals(0.2, snapEdited.pendingOrders.first().quantity, 1e-6)

        // Place a second SHORT limit then cancel it to verify margin refund
        val placedShort = PaperTradingEngine.placeLimitOrder(
            snapshot = snapEdited,
            symbol = "ETHUSDT",
            side = PaperOrderSide.SHORT,
            limitPrice = 3_500.0,
            quantity = 1.0,
            leverage = 2,
            stopLoss = 3_700.0,
            takeProfit = 2_800.0,
            nowMs = 1_700_000_035_000L
        ) as PaperOrderActionResult.Success
        val shortOrderId = placedShort.snapshot.pendingOrders.first { it.symbol == "ETHUSDT" }.id
        val canceled = PaperTradingEngine.cancelPendingOrder(placedShort.snapshot, shortOrderId) as PaperOrderActionResult.Success
        assertEquals(1, canceled.snapshot.pendingOrders.size)
        assertEquals(snapEdited.availableBalanceUsdt, canceled.snapshot.availableBalanceUsdt, 1e-4)

        // Tick BTCUSDT down to 58,400 (<= 58,500) -> NOW limit fills!
        val filled = PaperTradingEngine.onMarketPricesUpdated(
            canceled.snapshot,
            mapOf("BTCUSDT" to 58_400.0),
            nowMs = 1_700_000_040_000L
        )
        assertEquals(0, filled.pendingOrders.size)
        assertEquals(1, filled.openPositions.size)
        assertEquals(58_500.0, filled.openPositions.first().entryPrice, 1e-6)
    }

    @Test
    fun paperTrading_marketLongAndShortGrossVsNetPnlFeesFundingSlTpLiquidationAndEquityConservation() {
        val t0 = System.currentTimeMillis()
        val start = PaperTradingEngine.resetWallet(
            initialBalanceUsdt = 10_000.0,
            configOverride = PaperWalletConfig(
                initialBalanceUsdt = 10_000.0,
                takerFeePercentPerSide = 0.05,
                makerFeePercentPerSide = 0.02,
                slippagePercentPerSide = 0.03,
                fundingRatePer8hPercent = 0.01
            ),
            nowMs = t0
        )

        // 1. Open Market LONG BTCUSDT @ 60,000, qty=0.1, lev=5x, SL=58,000, TP=65,000
        val openLong = (PaperTradingEngine.placeMarketOrder(
            snapshot = start,
            symbol = "BTCUSDT",
            side = PaperOrderSide.LONG,
            currentMarketPrice = 60_000.0,
            quantity = 0.1,
            leverage = 5,
            stopLoss = 58_000.0,
            takeProfit = 65_000.0,
            nowMs = t0
        ) as PaperOrderActionResult.Success).snapshot

        // Entry fill includes 0.03% slippage -> 60,000 * 1.0003 = 60,018.0
        val posLong = openLong.openPositions.first()
        assertEquals(60_018.0, posLong.entryPrice, 1e-4)
        assertTrue(posLong.openingFeeUsdt > 0.0)
        assertTrue(posLong.liquidationPrice in 47_000.0..50_000.0)

        // Advance 16 hours (2 funding intervals!) and hit TP = 65,000
        val t16h = t0 + 16L * 3_600_000L
        val afterTp = PaperTradingEngine.onMarketPricesUpdated(
            openLong,
            mapOf("BTCUSDT" to 65_200.0),
            nowMs = t16h
        )
        assertEquals(0, afterTp.openPositions.size)
        assertEquals(1, afterTp.closedTrades.size)
        val tpTrade = afterTp.closedTrades.first()
        assertEquals(PaperExitReason.TP_HIT, tpTrade.exitReason)
        assertTrue(tpTrade.fundingCostUsdt > 0.0)
        assertTrue(tpTrade.grossPnlUsdt > tpTrade.netPnlUsdt) // Costs reduce net below gross
        assertEquals(afterTp.config.initialBalanceUsdt + afterTp.stats.totalNetPnlUsdt, afterTp.equityUsdt, 1e-4)

        // 2. Open Market SHORT ETHUSDT @ 3,000, qty=2.0, lev=4x, SL=3,200, TP=2,500
        val openShort = (PaperTradingEngine.placeMarketOrder(
            snapshot = afterTp,
            symbol = "ETHUSDT",
            side = PaperOrderSide.SHORT,
            currentMarketPrice = 3_000.0,
            quantity = 2.0,
            leverage = 4,
            stopLoss = 3_200.0,
            takeProfit = 2_500.0,
            nowMs = t16h + 1_000L
        ) as PaperOrderActionResult.Success).snapshot

        // Hit SL = 3,200 -> automatic SL_HIT losing trade
        val afterSl = PaperTradingEngine.onMarketPricesUpdated(
            openShort,
            mapOf("ETHUSDT" to 3_250.0),
            nowMs = t16h + 60_000L
        )
        assertEquals(0, afterSl.openPositions.size)
        assertEquals(2, afterSl.closedTrades.size)
        assertEquals(PaperExitReason.SL_HIT, afterSl.closedTrades.first().exitReason)
        assertTrue(afterSl.closedTrades.first().netPnlUsdt < 0.0)

        // Check statistics (1 win, 1 loss -> 50% win rate, finite PF > 0, equity invariant preserved)
        assertEquals(2, afterSl.stats.totalTrades)
        assertEquals(1, afterSl.stats.wins)
        assertEquals(1, afterSl.stats.losses)
        assertEquals(50.0, afterSl.stats.winRatePercent, 1e-4)
        assertTrue(afterSl.stats.profitFactor > 0.0)
        assertTrue(afterSl.stats.maxDrawdownPercent > 0.0)
        assertEquals(afterSl.config.initialBalanceUsdt + afterSl.stats.totalNetPnlUsdt, afterSl.equityUsdt, 1e-4)

        // 3. Test isolated liquidation without SL (10x LONG BTC @ 50,000 -> price crashes to 43,000)
        val openLiq = (PaperTradingEngine.placeMarketOrder(
            snapshot = afterSl,
            symbol = "BTCUSDT",
            side = PaperOrderSide.LONG,
            currentMarketPrice = 50_000.0,
            quantity = 0.2,
            leverage = 10,
            stopLoss = null,
            takeProfit = null,
            nowMs = t16h + 120_000L
        ) as PaperOrderActionResult.Success).snapshot
        val afterLiq = PaperTradingEngine.onMarketPricesUpdated(
            openLiq,
            mapOf("BTCUSDT" to 43_000.0),
            nowMs = t16h + 180_000L
        )
        assertEquals(0, afterLiq.openPositions.size)
        assertEquals(PaperExitReason.LIQUIDATED, afterLiq.closedTrades.first().exitReason)
        // Equity conservation invariant STILL exact even after liquidation!
        assertEquals(afterLiq.config.initialBalanceUsdt + afterLiq.stats.totalNetPnlUsdt, afterLiq.equityUsdt, 1e-4)
    }

    // ---------------------------------------------------------------------------------------------
    // 3. REAL HISTORICAL PARSER & 6-MONTH CAUSAL BACKTEST ENGINE TESTS
    // ---------------------------------------------------------------------------------------------

    @Test
    fun binanceHistoricalParsers_parseKlinesAndFundingCleanlyAndHandleMalformedInputs() {
        val apiClient = BinanceFuturesApiClient()
        val klinesJson = """
            [
              [1700000000000, "60000.0", "60500.0", "59800.0", "60300.0", "1234.5", 1700000899999, "74000000", 500, "600", "36000000", "0"],
              [1700000900000, "60300.0", "60900.0", "60200.0", "60800.0", "987.6", 1700001799999, "59000000", 420, "500", "30000000", "0"]
            ]
        """.trimIndent()
        val parsedCandles = apiClient.parseKlinesJson(klinesJson)
        assertEquals(2, parsedCandles.size)
        assertEquals(60_000.0, parsedCandles[0].open, 1e-6)
        assertEquals(60_800.0, parsedCandles[1].close, 1e-6)
        assertTrue(parsedCandles[0].closeTime < parsedCandles[1].closeTime)

        val fundingJson = """
            [
              {"symbol": "BTCUSDT", "fundingTime": 1700006400000, "fundingRate": "0.000125", "markPrice": "60400.0"},
              {"symbol": "BTCUSDT", "fundingTime": 1700035200000, "fundingRate": "-0.000050", "markPrice": "60900.0"}
            ]
        """.trimIndent()
        val parsedFunding = apiClient.parseFundingRatesJson("BTCUSDT", fundingJson)
        assertEquals(2, parsedFunding.size)
        assertEquals(0.000125, parsedFunding[0].fundingRateFraction, 1e-8)
        assertEquals(-0.000050, parsedFunding[1].fundingRateFraction, 1e-8)

        assertTrue(apiClient.parseKlinesJson("not json").isEmpty())
        assertTrue(apiClient.parseFundingRatesJson("BTCUSDT", "[]").isEmpty())
    }

    @Test
    fun strategyBacktestEngine_isStrictlyCausalZeroLookAheadAndReportsSplitsAndInsufficientData() {
        val baseStart = 1_690_000_000_000L
        // 1) Causal / Zero-look-ahead test: prefix [0..399] identical; future tail [400..449] violently mutated
        val seriesA = buildClosed15mCandles(count = 450, startTimeMs = baseStart, basePrice = 40_000.0, driftPerBar = 12.0, waveAmp = 75.0)
        val seriesB = seriesA.mapIndexed { idx, c ->
            if (idx < 400) c
            else c.copy(
                open = c.open * 0.45,
                high = c.high * 0.45,
                low = c.low * 0.40,
                close = c.close * 0.42
            )
        }
        val cutoffTimeMs = seriesA[399].closeTime

        val reportA = StrategyBacktestEngine.runMultiCoinBacktest(
            inputs = listOf(
                SingleCoinHistoricalInput(
                    symbol = "BTCUSDT",
                    candles15m = seriesA,
                    fundingHistory = buildFundingRates("BTCUSDT", baseStart, 30)
                )
            ),
            config = MultiCoinBacktestConfig(
                lookbackDays = 10,
                minSetupQualityScore = 70, // allow trades to occur so we compare causal trade entries
                minNetRiskReward = 2.0
            )
        )
        val reportB = StrategyBacktestEngine.runMultiCoinBacktest(
            inputs = listOf(
                SingleCoinHistoricalInput(
                    symbol = "BTCUSDT",
                    candles15m = seriesB,
                    fundingHistory = buildFundingRates("BTCUSDT", baseStart, 30)
                )
            ),
            config = MultiCoinBacktestConfig(
                lookbackDays = 10,
                minSetupQualityScore = 70,
                minNetRiskReward = 2.0
            )
        )

        // All trades entered at or before bar 399 MUST have identical entry bar/time/price/SL/TP/qualityScore
        val entriesBeforeCutoffA = reportA.sampleTrades.filter { it.entryTimeMs <= cutoffTimeMs }
        val entriesBeforeCutoffB = reportB.sampleTrades.filter { it.entryTimeMs <= cutoffTimeMs }
        assertEquals(entriesBeforeCutoffA.size, entriesBeforeCutoffB.size)
        entriesBeforeCutoffA.zip(entriesBeforeCutoffB).forEach { (ta, tb) ->
            assertEquals(ta.entryBarIndex, tb.entryBarIndex)
            assertEquals(ta.direction, tb.direction)
            assertEquals(ta.entryPrice, tb.entryPrice, 1e-6)
            assertEquals(ta.stopLoss, tb.stopLoss, 1e-6)
            assertEquals(ta.takeProfit, tb.takeProfit, 1e-6)
            assertEquals(ta.qualityScore, tb.qualityScore)
        }

        // 2) Full multi-coin report structure + INSUFFICIENT DATA handling for tiny coin
        val btcFull = buildClosed15mCandles(count = 2_400, startTimeMs = baseStart, basePrice = 30_000.0, driftPerBar = 6.0, waveAmp = 95.0)
        val tinySol = buildClosed15mCandles(count = 40, startTimeMs = baseStart, basePrice = 100.0) // < 120 bars!

        val multiReport = StrategyBacktestEngine.runMultiCoinBacktest(
            inputs = listOf(
                SingleCoinHistoricalInput("BTCUSDT", btcFull, fundingHistory = buildFundingRates("BTCUSDT", baseStart, 80)),
                SingleCoinHistoricalInput("SOLUSDT", tinySol)
            ),
            config = MultiCoinBacktestConfig(
                lookbackDays = 180,
                minSetupQualityScore = 92,
                minNetRiskReward = 4.0
            )
        )

        assertEquals(2, multiReport.coinBreakdowns.size)
        val btcBreakdown = multiReport.coinBreakdowns.first { it.symbol == "BTCUSDT" }
        val solBreakdown = multiReport.coinBreakdowns.first { it.symbol == "SOLUSDT" }
        assertTrue(btcBreakdown.hasSufficientData)
        assertFalse(solBreakdown.hasSufficientData)
        assertTrue(solBreakdown.statusNote.contains("INSUFFICIENT", ignoreCase = true))
        assertEquals(0.70, multiReport.inOutSampleSplit.splitRatioInSample, 1e-4)
        assertTrue(multiReport.generatedAtPkt.contains("PKT"))
    }
}
