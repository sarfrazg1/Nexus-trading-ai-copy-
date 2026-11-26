package com.example

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.MarketContextBuilder
import com.example.data.ai.OfflineTradingAnalysisEngine
import com.example.data.learning.LearningLanguageMode
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.news.FinancialNewsService
import com.example.data.providers.GdeltNewsProvider
import com.example.data.providers.MarketVenue
import com.example.data.risk.BudgetConfig
import com.example.data.risk.BudgetManagerEngine
import com.example.data.risk.DailyRiskLedger
import com.example.data.risk.RiskCheckCategory
import com.example.data.risk.RiskDecision
import com.example.data.risk.RiskManagerEngine
import com.example.data.scanner.ActiveSignalStateManager
import com.example.data.scanner.CryptoSetupPattern
import com.example.data.scanner.EntryTimerResult
import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.ManagedSignalState
import com.example.data.scanner.MultiMarketOpportunityRanker
import com.example.data.scanner.MultiTimeframeHierarchy
import com.example.data.scanner.SetupCandidateEvaluation
import com.example.data.scanner.SetupModelType
import com.example.data.scanner.SetupValidationStatus
import com.example.data.scanner.StrictMultiMarketEvaluation
import com.example.data.scanner.StrictMultiMarketSetupEngine
import com.example.data.scanner.TimeframeBiasAssessment
import com.example.data.settings.AppThemeMode
import com.example.data.smc.MarketTrendState
import com.example.data.timemachine.TimeMachineEngine
import com.example.ui.theme.DarkTradingColors
import com.example.ui.theme.LightTradingColors
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.NexusTradingTheme
import com.example.util.NexusNotificationManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterUpgradePart3IntegrationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // =========================================================================
    // 1. STRICT ENTRY, SL, TP, NET R:R (>=1:4, PREFER 1:5) & 15M PKT CLOSE
    // =========================================================================

    @Test
    fun strictMultiMarketEnginePopulatesExecutionModeZoneAnd15mPktCloseWithoutFakingRr() = runBlocking {
        val c1d = buildOscillatingSeries(30, 100.0, CandleTimeframe.D1, bullish = true)
        val c4h = buildOscillatingSeries(40, 105.0, CandleTimeframe.H4, bullish = true)
        val c1h = buildOscillatingSeries(50, 110.0, CandleTimeframe.H1, bullish = true)
        val c15m = buildOscillatingSeries(60, 115.0, CandleTimeframe.M15, bullish = true)
        val refTime = maxOf(c1d.last().closeTime, c15m.last().closeTime) + 60_000L

        val eval = StrictMultiMarketSetupEngine.evaluate(
            symbol = "BTC",
            currentPrice = c15m.last().close,
            dataSourceName = MarketVenue.BINANCE_USDT_M_FUTURES.displayName,
            candles1d = c1d,
            candles4h = c4h,
            candles1h = c1h,
            candles15m = c15m,
            referenceTimeMs = refTime
        )

        assertEquals(MarketVenue.BINANCE_USDT_M_FUTURES.displayName, eval.venueName)
        assertTrue(eval.timestampPkt.contains("PKT"))
        val setup = eval.bestSetup
        if (setup != null) {
            assertTrue("Expected Net R:R <= Gross R:R", setup.estimatedNetRiskReward <= setup.grossRiskReward + 1e-6)
            assertTrue("Expected PKT on 15M candle close", setup.confirmationCandleClosePkt.contains("PKT"))
            assertTrue(setup.entryExecutionType.isNotBlank())
            assertTrue(setup.entryZoneRange.isNotBlank())
            if (setup.estimatedNetRiskReward < StrictMultiMarketSetupEngine.MIN_NET_RR_FOR_CONFIRMATION) {
                assertNotEquals(
                    "Setups below Net 1:4 must NEVER be promoted to CONFIRMED",
                    SetupValidationStatus.CONFIRMED,
                    setup.status
                )
            }
        }
    }

    // =========================================================================
    // 2. ACTIVE SIGNAL STATE MANAGER LIFECYCLE & REVERSAL BLOCKING
    // =========================================================================

    @Test
    fun activeSignalStateManagerEnforcesLifecycleBlocksSilentFlipAndDedupes() {
        val manager = ActiveSignalStateManager(defaultNotificationCooldownMs = 45 * 60 * 1000L)
        val closeTs = 1_795_521_600_000L // fixed timestamp -> PKT check
        var now = closeTs + 5_000L

        // 1. WATCHING intake
        val watchRes = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            isConfirmed = false,
            entryPrice = 90000.0,
            stopLoss = 89000.0,
            takeProfit1 = 92500.0,
            takeProfit2 = 95000.0,
            netRiskReward = 4.6,
            qualityScore = 82,
            dataSourceName = "Binance Futures",
            setupReason = "Pullback to Golden Zone",
            confirmationCandleCloseMs = closeTs,
            expiresAtMs = now + 3_600_000L,
            nowMs = now
        )
        assertTrue(watchRes.accepted)
        assertEquals(ManagedSignalState.WATCHING, watchRes.entry?.state)
        assertEquals(1, manager.getWatchingSignals().size)

        // 2. Promotion to CONFIRMED -> ACTIVE
        now += 60_000L
        val confirmRes = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            isConfirmed = true,
            entryPrice = 90100.0,
            stopLoss = 89000.0,
            takeProfit1 = 92800.0,
            takeProfit2 = 95800.0,
            netRiskReward = 5.05,
            qualityScore = 95,
            dataSourceName = "Binance Futures",
            setupReason = "15M Bullish Engulfing in Golden Zone",
            confirmationCandleCloseMs = closeTs,
            expiresAtMs = now + 3_600_000L,
            nowMs = now
        )
        assertTrue(confirmRes.accepted)
        assertEquals(ManagedSignalState.ACTIVE, confirmRes.entry?.state)
        assertEquals(1, manager.getActiveSignals().size)
        val expectedId = ActiveSignalStateManager.buildDeterministicId("BTCUSDT", FuturesDirection.LONG, closeTs)
        assertEquals(expectedId, confirmRes.entry?.signalId)

        // 3. BLOCK silent LONG -> SHORT reversal while ACTIVE exists
        now += 120_000L
        val flipRes = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.SHORT,
            isConfirmed = true,
            entryPrice = 90500.0,
            stopLoss = 91500.0,
            takeProfit1 = 87500.0,
            takeProfit2 = 85000.0,
            netRiskReward = 5.1,
            qualityScore = 96,
            dataSourceName = "Binance Futures",
            setupReason = "Conflicting short scalp",
            confirmationCandleCloseMs = closeTs + 900_000L,
            expiresAtMs = now + 3_600_000L,
            nowMs = now
        )
        assertFalse("Silent LONG <-> SHORT flip must be blocked while ACTIVE", flipRes.accepted)
        assertTrue(flipRes.rejectionReason?.contains("Blocked silent", ignoreCase = true) == true)
        assertEquals(FuturesDirection.LONG, manager.getEntryForSymbol("BTCUSDT")?.direction)

        // 4. Cooldown & duplicate notification suppression
        assertTrue(manager.shouldDispatchNotification(expectedId, "BTCUSDT", FuturesDirection.LONG, now))
        manager.markNotificationDispatched(expectedId, "BTCUSDT", FuturesDirection.LONG, now)
        assertFalse(manager.shouldDispatchNotification(expectedId, "BTCUSDT", FuturesDirection.LONG, now + 60_000L))

        // 5. Transition ACTIVE -> TARGET_HIT
        now += 600_000L
        val hitRecord = manager.evaluateMarketUpdate("BTCUSDT", currentPrice = 95900.0, nowMs = now)
        assertNotNull(hitRecord)
        assertEquals(ManagedSignalState.TARGET_HIT, hitRecord!!.toState)
        assertTrue(hitRecord.timestampPkt.contains("PKT"))
        assertEquals(0, manager.getActiveSignals().size)

        // 6. After terminal state, opposite SHORT direction is now permitted cleanly
        now += 60_000L
        val shortAfterTarget = manager.submitCandidate(
            symbol = "BTCUSDT",
            direction = FuturesDirection.SHORT,
            isConfirmed = true,
            entryPrice = 96000.0,
            stopLoss = 97000.0,
            takeProfit1 = 92000.0,
            takeProfit2 = 90500.0,
            netRiskReward = 5.2,
            qualityScore = 94,
            dataSourceName = "Binance Futures",
            setupReason = "BSL sweep reversal at supply",
            confirmationCandleCloseMs = closeTs + 1_800_000L,
            expiresAtMs = now + 3_600_000L,
            nowMs = now
        )
        assertTrue(shortAfterTarget.accepted)
        assertEquals(FuturesDirection.SHORT, shortAfterTarget.entry?.direction)

        // 7. Also verify STOP_LOSS_HIT, INVALIDATED, EXPIRED terminal paths
        val slHit = manager.evaluateMarketUpdate("BTCUSDT", currentPrice = 97100.0, nowMs = now + 30_000L)
        assertEquals(ManagedSignalState.STOP_LOSS_HIT, slHit?.toState)

        manager.submitCandidate(
            symbol = "ETHUSDT",
            direction = FuturesDirection.LONG,
            isConfirmed = true,
            entryPrice = 3000.0,
            stopLoss = 2950.0,
            takeProfit1 = 3150.0,
            takeProfit2 = 3260.0,
            netRiskReward = 4.9,
            qualityScore = 93,
            dataSourceName = "Bybit V5",
            setupReason = "BOS continuation",
            confirmationCandleCloseMs = closeTs,
            expiresAtMs = now + 1_800_000L,
            nowMs = now
        )
        val inv = manager.evaluateMarketUpdate(
            symbol = "ETHUSDT",
            currentPrice = 3010.0,
            nowMs = now + 60_000L,
            invalidatedByStructure = true,
            invalidationReason = "1H CHoCH against bias"
        )
        assertEquals(ManagedSignalState.INVALIDATED, inv?.toState)

        manager.submitCandidate(
            symbol = "SOLUSDT",
            direction = FuturesDirection.LONG,
            isConfirmed = true,
            entryPrice = 200.0,
            stopLoss = 195.0,
            takeProfit1 = 215.0,
            takeProfit2 = 226.0,
            netRiskReward = 5.0,
            qualityScore = 94,
            dataSourceName = "Binance Futures",
            setupReason = "Golden Zone reclaim",
            confirmationCandleCloseMs = closeTs,
            expiresAtMs = now + 300_000L,
            nowMs = now
        )
        val exp = manager.evaluateMarketUpdate("SOLUSDT", currentPrice = 201.0, nowMs = now + 400_000L)
        assertEquals(ManagedSignalState.EXPIRED, exp?.toState)
    }

    // =========================================================================
    // 3. MULTI-MARKET OPPORTUNITY RANKER & CRYPTO 24/7 + EMPTY STATE
    // =========================================================================

    @Test
    fun multiMarketRankerPrioritizesFiveToOneKeepsCrypto24x7AndEmitsEmptyBoardFallback() {
        val now = 1_795_521_600_000L

        val cryptoWatching = buildEvaluation(
            symbol = "BTC",
            category = AssetCategory.CRYPTO,
            venue = MarketVenue.BINANCE_USDT_M_FUTURES.displayName,
            status = SetupValidationStatus.WATCHING,
            netRr = 3.2,
            score = 78
        )
        val nvdaUnavailable = buildEvaluation(
            symbol = "NVDA",
            category = AssetCategory.STOCKS,
            venue = MarketVenue.STOCKS_YAHOO.displayName,
            status = SetupValidationStatus.DATA_UNAVAILABLE,
            netRr = 0.0,
            score = 0
        )

        // Empty qualified board -> "NO STRONG OPPORTUNITY FOUND"
        val emptyBoard = MultiMarketOpportunityRanker.rank(
            evaluations = listOf(cryptoWatching, nvdaUnavailable),
            referenceTimeMs = now
        )
        assertEquals(0, emptyBoard.qualifiedCount)
        assertEquals(MultiMarketOpportunityRanker.EMPTY_BOARD_HEADLINE, emptyBoard.emptyHeadline)
        assertNotNull(emptyBoard.emptyReason)
        assertTrue(emptyBoard.generatedAtPkt.contains("PKT"))

        // Qualified board: 1:5.4 Crypto LIVE setup MUST rank #1 ahead of 1:4.2 Gold setup, with 24/7 note
        val crypto5to1 = buildEvaluation(
            symbol = "BTC",
            category = AssetCategory.CRYPTO,
            venue = MarketVenue.BINANCE_USDT_M_FUTURES.displayName,
            status = SetupValidationStatus.CONFIRMED,
            netRr = 5.4,
            score = 94
        )
        val gold4to2 = buildEvaluation(
            symbol = "XAU",
            category = AssetCategory.COMMODITIES,
            venue = MarketVenue.COMMODITIES_YAHOO.displayName,
            status = SetupValidationStatus.CONFIRMED,
            netRr = 4.2,
            score = 93
        )
        val rankedBoard = MultiMarketOpportunityRanker.rank(
            evaluations = listOf(nvdaUnavailable, gold4to2, crypto5to1),
            dataStatusesBySymbol = mapOf(
                "BTC" to MarketDataStatus.LIVE,
                "XAU" to MarketDataStatus.DELAYED,
                "NVDA" to MarketDataStatus.UNAVAILABLE
            ),
            referenceTimeMs = now
        )
        assertEquals(2, rankedBoard.qualifiedCount)
        assertEquals(1, rankedBoard.preferredFiveToOneCount)
        assertNull(rankedBoard.emptyHeadline)
        assertEquals("BTC", rankedBoard.opportunities.first().evaluation.symbol)
        assertTrue(rankedBoard.opportunities.first().isPreferredFiveToOne)
        assertTrue(rankedBoard.opportunities.first().sessionNote.contains("24/7"))
        assertEquals("NVDA", rankedBoard.opportunities.last().evaluation.symbol)
        assertFalse(rankedBoard.opportunities.last().isQualified)
    }

    // =========================================================================
    // 4. RISK MANAGEMENT DEFAULTS (1% RISK, 3% LOSS WARNING, MAX 2 TRADES/DAY)
    // =========================================================================

    @Test
    fun riskManagerEnforcesDefault1PercentRisk3PercentDailyWarningAndMax2TradesPerDay() {
        val defaultCfg = BudgetConfig()
        assertEquals(1.0, defaultCfg.maxRiskPercent, 0.001)
        assertEquals(3.0, defaultCfg.dailyLossWarningPercent, 0.001)
        assertEquals(2, defaultCfg.maxTradesPerDay)

        val signal = FuturesScanSignal(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            direction = FuturesDirection.LONG,
            currentPrice = 90000.0,
            entryZoneMin = 89900.0,
            entryZoneMax = 90100.0,
            entryPrice = 90000.0,
            stopLoss = 89000.0,
            takeProfit1 = 93000.0,
            takeProfit2 = 95500.0,
            takeProfit3 = 97000.0,
            riskRewardRatio = 5.5,
            qualityScore = 95,
            qualityRating = "A+ Confluence",
            timeframeConfirmations = emptyList(),
            reasonsForEntry = listOf("MTF + Golden Zone + BOS"),
            invalidationCondition = "Close below 89000",
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED,
            entryTimer = EntryTimerResult(
                status = EntryTimerStatus.TRIGGERED,
                symbol = "BTCUSDT",
                direction = FuturesDirection.LONG,
                triggerLevel = 90000.0,
                invalidationLevel = 89000.0,
                barsMonitored = 3,
                maxWaitBars = 12,
                triggerPrice = 90000.0,
                triggerTimestampMs = System.currentTimeMillis(),
                statusExplanation = "15M candle closed bullish"
            )
        )

        val plan = BudgetManagerEngine.calculatePlanForSignal(signal, defaultCfg, requestedLeverage = 5)
        assertTrue(plan.isValid)
        assertTrue(plan.maximumPlannedLossUsdt <= defaultCfg.accountBalanceUsdt * 0.01 * 1.05)
        assertTrue(plan.estimatedFeesUsdt > 0.0)
        assertTrue(plan.estimatedSlippageUsdt > 0.0)

        // Daily Loss >= 3% ($30 on $1000 equity) triggers WARNING while allowing within 5% hard cap
        val warningLedger = DailyRiskLedger(
            tradesTakenToday = 0,
            realizedPnlUsdtToday = -30.5
        )
        val warnResult = RiskManagerEngine.evaluate(signal, defaultCfg, plan, dailyLedger = warningLedger)
        val dailyLossCheck = warnResult.checks.first { it.category == RiskCheckCategory.DAILY_LOSS_LIMIT }
        assertEquals(RiskDecision.WARNING, dailyLossCheck.decision)
        assertTrue(dailyLossCheck.reason.contains("3.0%", ignoreCase = true))

        // Max 2 trades/day reached -> BLOCK
        val maxedTradesLedger = DailyRiskLedger(
            tradesTakenToday = 2,
            realizedPnlUsdtToday = 15.0
        )
        val blockedResult = RiskManagerEngine.evaluate(signal, defaultCfg, plan, dailyLedger = maxedTradesLedger)
        assertEquals(RiskDecision.BLOCK, blockedResult.overallDecision)
        assertFalse(blockedResult.canProceedToOrder)
        val maxTradesCheck = blockedResult.checks.first { it.category == RiskCheckCategory.MAX_TRADES_LIMIT }
        assertEquals(RiskDecision.BLOCK, maxTradesCheck.decision)
    }

    // =========================================================================
    // 5. STRICT PUSH ALERT GATE (SCORE > 92, PKT TIMESTAMP, DEDUP)
    // =========================================================================

    @Test
    fun notificationManagerEnforcesStrictScoreAbove92MandatoryFiltersAndDedup() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val mgr = NexusNotificationManager(context)
        mgr.setMasterAlertsEnabled(true)
        mgr.setLongSignalsEnabled(true)
        mgr.setShortSignalsEnabled(true)

        val uniqueSuffix = System.nanoTime()

        // Score == 92 must be rejected (strictly > 92 required)
        val blockedScore92 = mgr.notifyStrictQualifiedSignal(
            signalId = "sig_92_$uniqueSuffix",
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            entryPriceFormatted = "$90000.00",
            stopLossFormatted = "$89000.00",
            tp1Formatted = "$93000.00",
            tpFinalFormatted = "$95500.00",
            netRiskRewardRatio = 5.2,
            qualityScore = 92,
            allMandatoryFiltersPassed = true,
            setupReason = "Golden Zone Reclaim",
            dataSourceName = "Binance Futures"
        )
        assertFalse("Quality score 92 must NOT pass >92 push gate", blockedScore92)

        // Score 95 with failed mandatory filter must be rejected
        val blockedFilterFail = mgr.notifyStrictQualifiedSignal(
            signalId = "sig_filter_fail_$uniqueSuffix",
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            entryPriceFormatted = "$90000.00",
            stopLossFormatted = "$89000.00",
            tp1Formatted = "$93000.00",
            tpFinalFormatted = "$95500.00",
            netRiskRewardRatio = 5.2,
            qualityScore = 95,
            allMandatoryFiltersPassed = false,
            setupReason = "Missing HTF alignment",
            dataSourceName = "Binance Futures"
        )
        assertFalse(blockedFilterFail)

        // Score 94 + all filters + Net R:R >= 4.0 passes once, and second call is deduplicated
        val sigId = "sig_94_pass_$uniqueSuffix"
        val dispatched = mgr.notifyStrictQualifiedSignal(
            signalId = sigId,
            symbol = "ETHUSDT",
            direction = FuturesDirection.SHORT,
            entryPriceFormatted = "$3200.00",
            stopLossFormatted = "$3240.00",
            tp1Formatted = "$3050.00",
            tpFinalFormatted = "$2960.00",
            netRiskRewardRatio = 5.4,
            qualityScore = 94,
            allMandatoryFiltersPassed = true,
            setupReason = "Type A BSL Sweep Reversal",
            dataSourceName = "Bybit V5 Linear",
            timestampMs = 1_795_521_600_000L
        )
        assertTrue(dispatched)

        val duplicate = mgr.notifyStrictQualifiedSignal(
            signalId = sigId,
            symbol = "ETHUSDT",
            direction = FuturesDirection.SHORT,
            entryPriceFormatted = "$3200.00",
            stopLossFormatted = "$3240.00",
            tp1Formatted = "$3050.00",
            tpFinalFormatted = "$2960.00",
            netRiskRewardRatio = 5.4,
            qualityScore = 94,
            allMandatoryFiltersPassed = true,
            setupReason = "Type A BSL Sweep Reversal",
            dataSourceName = "Bybit V5 Linear",
            timestampMs = 1_795_521_600_000L
        )
        assertFalse("Duplicate strict alert must be suppressed", duplicate)

        val latest = mgr.alertHistory.value.firstOrNull { it.signalId == sigId }
        assertNotNull(latest)
        assertTrue(latest!!.title.contains("ETHUSDT"))
        assertTrue(latest.title.contains("SHORT"))
        assertTrue(latest.message.contains("Bybit V5 Linear"))
        assertTrue(latest.message.contains("Net R:R 1:5.40"))
        assertTrue(latest.timestampPkt.contains("PKT"))
    }

    // =========================================================================
    // 6. BACKTEST: 70/30 IN/OUT-OF-SAMPLE SPLIT, DRAWDOWN, EXPECTANCY, LONG vs SHORT
    // =========================================================================

    @Test
    fun timeMachineBacktestComputesInOutSampleSplitDrawdownExpectancyAndDirectionalComparison() {
        val candles = buildOscillatingSeries(140, 100.0, CandleTimeframe.H1, bullish = true)
        val refTime = candles.last().closeTime + 60_000L

        val report = TimeMachineEngine.runDirectionalBacktestComparison(
            symbol = "BTCUSDT",
            timeframe = CandleTimeframe.H1,
            setupType = SetupModelType.TREND_CONTINUATION,
            slDistancePercent = 1.5,
            tpDistancePercent = 3.5,
            candles = candles,
            referenceTimeMs = refTime
        )

        assertTrue(report.longResult.sampleSize > 0)
        assertNotNull(report.longResult.inOutSampleSplit)
        assertEquals(0.70, report.longResult.inOutSampleSplit!!.splitRatioInSample, 0.001)
        assertTrue(report.longResult.inOutSampleSplit!!.antiLeakageNote.contains("70%"))
        assertTrue(report.longResult.maxDrawdownPercent >= 0.0)
        assertTrue(report.longResult.expectancyR.isFinite())
        assertTrue(report.longResult.expectancyPercent.isFinite())
        assertTrue(report.disclaimer.contains("do NOT guarantee", ignoreCase = true))
        assertTrue(report.comparisonSummary.contains("LONG") && report.comparisonSummary.contains("SHORT"))
    }

    // =========================================================================
    // 7. NEWS TIMESTAMPS (PUBLICATION TIME vs APP RETRIEVAL TIME IN PKT)
    // =========================================================================

    @Test
    fun gdeltAndMacroNewsSeparatePublicationPktFromAppRetrievalPkt() {
        val fixedNowMs = 1_795_525_200_000L // 2026-11-24 18:00 PKT
        val gdelt = GdeltNewsProvider(clockMillis = { fixedNowMs })
        val json = """
            {
              "articles": [
                {
                  "title": "Bitcoin ETF inflows rise as Federal Reserve officials discuss inflation",
                  "domain": "reuters.com",
                  "url": "https://reuters.com/markets/btc-etf",
                  "seendate": "20261124T110000Z"
                }
              ]
            }
        """.trimIndent()

        val parsed = gdelt.filterAndParseArticles(json, nowMillis = fixedNowMs)
        assertEquals(1, parsed.size)
        val article = parsed.first().article
        assertTrue(article.publishedTimestampPkt.contains("PKT"))
        assertTrue(article.retrievedTimestampPkt.contains("PKT"))
        assertNotEquals(article.publishedTimestampPkt, article.retrievedTimestampPkt)

        val staticNews = FinancialNewsService().getVerifiedMacroNews()
        assertTrue(staticNews.isNotEmpty())
        assertTrue(staticNews.all { it.publishedTimestampPkt.contains("PKT") && it.retrievedTimestampPkt.contains("PKT") })
    }

    // =========================================================================
    // 8. CHATBOT INDICATOR / NET R:R Q&A + HONEST DATA FRESHNESS LABELING
    // =========================================================================

    @Test
    fun offlineChatbotAnswersIndicatorAndNetRrInAllLanguagesWithHonestDataStatus() {
        val liveAsset = MarketAsset(
            id = "BTC",
            name = "Bitcoin",
            symbol = "BTC/USDT",
            category = AssetCategory.CRYPTO,
            currentPrice = 91500.0,
            change24hPercent = 2.1,
            dataStatus = MarketDataStatus.LIVE
        )
        val liveCtx = MarketContextBuilder.buildSnapshot(liveAsset, emptyList(), CandleTimeframe.M15)

        val enReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "Explain RSI and 1:5 Net R:R",
            context = liveCtx,
            language = LearningLanguageMode.ENGLISH
        )
        assertTrue(enReply.contains("LIVE DATA"))
        assertTrue(enReply.contains("RSI"))
        assertTrue(enReply.contains("1:4"))

        val romanReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "EMA, Supertrend aur Fibonacci Golden Zone samjhao",
            context = liveCtx,
            language = LearningLanguageMode.ROMAN_URDU
        )
        assertTrue(romanReply.contains("EMA"))
        assertTrue(romanReply.contains("Supertrend"))
        assertTrue(romanReply.contains("Golden Zone"))

        val urduReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "انڈیکیٹرز اور فبوناچی کی وضاحت کریں",
            context = liveCtx,
            language = LearningLanguageMode.STANDARD_URDU
        )
        assertTrue(urduReply.any { it in '\u0600'..'\u06FF' })
        assertTrue(urduReply.contains("1:4"))

        val unavailableCtx = liveCtx.copy(dataStatus = MarketDataStatus.UNAVAILABLE.label, isRealData = false)
        val unavailableReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "Explain ADX",
            context = unavailableCtx,
            language = LearningLanguageMode.ENGLISH
        )
        assertTrue(unavailableReply.contains("DATA UNAVAILABLE"))
    }

    // =========================================================================
    // 9. DAY / NIGHT THEME TOKENS & COMPOSE RENDERING SMOKE TEST
    // =========================================================================

    @Test
    fun lightAndDarkThemesHaveOppositeSurfacePolarityAndRenderCleanly() {
        assertTrue("Light background must have high luminance", LightTradingColors.background.luminance() > 0.7f)
        assertTrue("Dark background must have low luminance", DarkTradingColors.background.luminance() < 0.1f)
        assertTrue("Light primary text must be dark for legibility", LightTradingColors.textPrimary.luminance() < 0.15f)
        assertTrue("Dark primary text must be light for legibility", DarkTradingColors.textPrimary.luminance() > 0.7f)
        assertFalse(LightTradingColors.isDark)
        assertTrue(DarkTradingColors.isDark)

        composeRule.setContent {
            NexusTradingTheme(themeMode = AppThemeMode.LIGHT) {
                val colors = NexusTheme.colors
                Text(text = if (colors.isDark) "THEME_DARK" else "THEME_LIGHT")
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithText("THEME_LIGHT").assertIsDisplayed()
    }

    // =========================================================================
    // TEST HELPERS
    // =========================================================================

    private fun buildEvaluation(
        symbol: String,
        category: AssetCategory,
        venue: String,
        status: SetupValidationStatus,
        netRr: Double,
        score: Int
    ): StrictMultiMarketEvaluation {
        val cand = if (status == SetupValidationStatus.DATA_UNAVAILABLE) {
            null
        } else {
            SetupCandidateEvaluation(
                setupType = SetupModelType.TREND_CONTINUATION,
                direction = FuturesDirection.LONG,
                status = status,
                score = score,
                entryPrice = 100.0,
                stopLoss = 98.0,
                target1272Ref = 106.0,
                target1618Ref = 111.0,
                adjustedTarget1 = 106.0,
                adjustedTarget2 = 111.0,
                grossRiskReward = netRr + 0.2,
                estimatedNetRiskReward = netRr,
                rulesPassed = listOf("MTF Aligned", "Golden Zone Reclaim"),
                rulesFailed = if (status == SetupValidationStatus.CONFIRMED) emptyList() else listOf("Awaiting 15M close"),
                goldenZoneNote = "0.500-0.618 Golden Zone",
                invalidationRule = "Close below 98.0",
                confirmationCandleClosePkt = "24 Nov 2026, 05:00:00 PM PKT"
            )
        }
        return StrictMultiMarketEvaluation(
            symbol = symbol,
            displaySymbol = symbol,
            assetName = symbol,
            venueName = venue,
            category = category,
            status = status,
            dataSourceDescription = venue,
            unavailableReason = if (status == SetupValidationStatus.DATA_UNAVAILABLE) "DATA UNAVAILABLE on $venue" else null,
            currentPrice = 100.0,
            mtfHierarchy = if (status == SetupValidationStatus.DATA_UNAVAILABLE) null else MultiTimeframeHierarchy(
                daily = TimeframeBiasAssessment(CandleTimeframe.D1, MarketTrendState.BULLISH_UPTREND, FuturesDirection.LONG, 30, "Bullish"),
                fourHour = TimeframeBiasAssessment(CandleTimeframe.H4, MarketTrendState.BULLISH_UPTREND, FuturesDirection.LONG, 40, "Bullish"),
                oneHour = TimeframeBiasAssessment(CandleTimeframe.H1, MarketTrendState.BULLISH_UPTREND, FuturesDirection.LONG, 50, "Bullish"),
                fifteenMin = TimeframeBiasAssessment(CandleTimeframe.M15, MarketTrendState.BULLISH_UPTREND, FuturesDirection.LONG, 60, "Bullish"),
                htfMacroDirection = FuturesDirection.LONG,
                isStrongHtfBullish = true,
                isStrongHtfBearish = false,
                summary = "1D/4H/1H/15M LONG"
            ),
            smc15m = null,
            smc1h = null,
            liquiditySweepReversal = null,
            trendContinuation = cand,
            bestSetup = cand,
            timestampPkt = "24 Nov 2026, 05:00:00 PM PKT"
        )
    }

    private fun buildOscillatingSeries(
        count: Int,
        startPrice: Double,
        tf: CandleTimeframe,
        bullish: Boolean
    ): List<Candle> {
        val start = 1_700_000_000_000L
        val list = mutableListOf<Candle>()
        var price = startPrice
        val sign = if (bullish) 1.0 else -1.0
        for (i in 0 until count) {
            val phase = i % 10
            val delta = when {
                phase == 3 -> -1.8 * sign
                phase == 4 -> 2.8 * sign
                phase in 0..6 -> 1.1 * sign
                else -> -0.7 * sign
            }
            val open = price
            val close = (price + delta).coerceAtLeast(1.0)
            val high = maxOf(open, close) + 0.7
            val low = minOf(open, close) - (if (phase == 3 || phase == 4) 1.5 else 0.5)
            val openTime = start + i * tf.durationMillis
            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1400.0 + phase * 90.0,
                    closeTime = openTime + tf.durationMillis - 1L
                )
            )
            price = close
        }
        return list
    }
}
