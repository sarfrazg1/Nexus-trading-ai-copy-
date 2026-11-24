package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.example.data.alerts.AlertMonitorEngine
import com.example.data.alerts.AlertRuleCategory
import com.example.data.alerts.TradeAlertRule
import com.example.data.broker.ExecutionMode
import com.example.data.broker.OrderSubmissionOutcome
import com.example.data.broker.PaperOrderBrokerEngine
import com.example.data.broker.TradeWorkflowState
import com.example.data.model.AlertDirection
import com.example.data.model.AlertType
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAlert
import com.example.data.model.NavigationTab
import com.example.data.remote.NexusFirebaseMessagingService
import com.example.data.risk.BudgetConfig
import com.example.data.risk.BudgetManagerEngine
import com.example.data.risk.DailyRiskLedger
import com.example.data.risk.RiskCheckCategory
import com.example.data.risk.RiskCurrency
import com.example.data.risk.RiskDecision
import com.example.data.risk.RiskManagerEngine
import com.example.data.scanner.ConfidenceBreakdownItem
import com.example.data.scanner.ConfidenceEvidence
import com.example.data.scanner.EntryTimerResult
import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.scanner.FuturesScannerRepository
import com.example.data.scanner.ScannerEngineState
import com.example.data.timemachine.BacktestConfig
import com.example.data.timemachine.BacktestOutcomeType
import com.example.data.timemachine.MonteCarloConfig
import com.example.data.timemachine.TimeMachineEngine
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.TradeWorkflowSheet
import com.example.ui.theme.NexusTradingTheme
import com.example.util.NexusNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MasterUpgradePart2Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildTrendPullbackCandles(
        count: Int = 180,
        basePrice: Double = 100.0,
        intervalMs: Long = 900_000L,
        endTimeMs: Long = 1_760_000_000_000L
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        val start = endTimeMs - count * intervalMs
        var price = basePrice
        for (i in 0 until count) {
            val openTime = start + i * intervalMs
            val closeTime = openTime + intervalMs - 1L
            val open = price
            // Periodic small pullback then breakout surge to trigger causal setup rules reliably
            val delta = when {
                i % 15 in 0..1 -> -0.4
                i % 15 == 2 -> 1.8
                i % 15 in 3..6 -> 1.1
                else -> 0.45
            }
            val close = (open + delta).coerceAtLeast(10.0)
            val high = maxOf(open, close) + 0.85
            val low = minOf(open, close) - 0.55
            price = close
            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = if (i % 15 == 2) 3200.0 else 1500.0,
                    closeTime = closeTime
                )
            )
        }
        return list
    }

    private fun buildSampleSignal(
        symbol: String = "BTCUSDT",
        direction: FuturesDirection = FuturesDirection.LONG,
        entryPrice: Double = 50_000.0,
        stopLoss: Double = 49_000.0,
        tp1: Double = 51_500.0,
        tp2: Double = 52_500.0,
        tp3: Double = 54_000.0,
        qualityScore: Int = 82,
        leverage: Int = 4,
        timerStatus: EntryTimerStatus = EntryTimerStatus.TRIGGERED
    ): FuturesScanSignal {
        return FuturesScanSignal(
            id = "sig_${symbol}_test",
            symbol = symbol,
            baseAsset = symbol.removeSuffix("USDT"),
            direction = direction,
            currentPrice = entryPrice,
            entryZoneMin = entryPrice * 0.998,
            entryZoneMax = entryPrice * 1.002,
            entryPrice = entryPrice,
            stopLoss = stopLoss,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            takeProfit3 = tp3,
            riskRewardRatio = 2.5,
            qualityScore = qualityScore,
            qualityRating = if (qualityScore >= 92) "Qualifying >=92%" else "High Quality",
            timeframeConfirmations = emptyList(),
            reasonsForEntry = listOf("1H Bullish EMA50/200 alignment", "15M Bullish CHoCH"),
            invalidationCondition = "15M candle close below stop",
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED,
            suggestedLeverage = leverage,
            entryTimer = EntryTimerResult(
                status = timerStatus,
                symbol = symbol,
                direction = direction,
                triggerLevel = entryPrice,
                invalidationLevel = stopLoss,
                barsMonitored = 3,
                maxWaitBars = 12,
                triggerPrice = entryPrice,
                triggerTimestampMs = 1_760_000_000_000L,
                triggerTimestampPkt = "12 Oct 2025, 05:00:00 PM PKT",
                statusExplanation = "Confirmed on completed 15M candle close."
            ),
            confidenceEvidence = ConfidenceEvidence(
                modelConfidenceScore = qualityScore,
                rubricBreakdown = listOf(
                    ConfidenceBreakdownItem("HTF Trend", 25, 25, "Bullish"),
                    ConfidenceBreakdownItem("SMC CHoCH", 25, 25, "Confirmed"),
                    ConfidenceBreakdownItem("FVG", 15, 15, "Unfilled demand"),
                    ConfidenceBreakdownItem("RSI", 15, 15, "54.0"),
                    ConfidenceBreakdownItem("RVOL", 0, 10, "1.05x"),
                    ConfidenceBreakdownItem("Pattern", 2, 10, "Partial")
                )
            )
        )
    }

    @Test
    fun testTimeMachine_HistoricalBacktest_FeesSlippageAndAntiLookAhead() {
        val candles = buildTrendPullbackCandles(count = 160, basePrice = 100.0)
        val cfg = BacktestConfig(
            feePercentPerSide = 0.05,
            slippagePercentPerSide = 0.04,
            horizonBars = 12,
            cooldownBarsBetweenTrades = 5
        )
        val result = TimeMachineEngine.runBacktest(
            symbol = "ETHUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.0,
            candles = candles,
            config = cfg,
            referenceTimeMs = 1_800_000_000_000L
        )

        assertTrue("Expected historical occurrences >= 3, got ${result.sampleSize}", result.sampleSize >= 3)
        assertEquals(0.10, result.feePercentRoundTrip, 1e-6)
        assertEquals(0.04, result.slippagePercentPerSide, 1e-6)
        assertTrue(result.testPeriodStartPkt.contains("PKT"))
        assertTrue(result.occurrences.all { it.grossReturnPercent > it.netReturnPercent })

        // Anti-look-ahead check: mutate future candles strictly after the 1st occurrence's exitIndex
        val firstOcc = result.occurrences.first()
        val cut = firstOcc.exitIndex + 2
        val mutated = candles.mapIndexed { idx, c ->
            if (idx >= cut) c.copy(close = c.close * 0.2, low = c.low * 0.15, high = c.high * 2.5) else c
        }
        val rerun = TimeMachineEngine.runBacktest(
            symbol = "ETHUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.0,
            candles = mutated,
            config = cfg,
            referenceTimeMs = 1_800_000_000_000L
        )
        val rerunFirst = rerun.occurrences.first()
        assertEquals(firstOcc.setupIndex, rerunFirst.setupIndex)
        assertEquals(firstOcc.outcome, rerunFirst.outcome)
        assertEquals(firstOcc.entryPrice, rerunFirst.entryPrice, 1e-6)
    }

    @Test
    fun testTimeMachine_AmbiguousSameCandle_NeverPicksFavorableOutcome() {
        // Construct flat/slightly bearish prefix [0..24] so NO prior setup fires before bar 25,
        // then bar 25 breaks out cleanly and entry bar 26 touches BOTH TP (+3%) and SL (-2%).
        val start = 1_760_000_000_000L - 27 * 900_000L
        val baseList = (0 until 25).map { i ->
            val openTime = start + i * 900_000L
            Candle(
                openTime = openTime,
                open = 100.0,
                high = 100.1,
                low = 99.7,
                close = 99.8,
                volume = 1500.0,
                closeTime = openTime + 899_999L
            )
        }.toMutableList()
        val bar25OpenTime = start + 25 * 900_000L
        baseList.add(
            Candle(
                openTime = bar25OpenTime,
                open = 99.9,
                high = 103.0,
                low = 99.8,
                close = 102.5,
                volume = 5000.0,
                closeTime = bar25OpenTime + 899_999L
            )
        )
        val entryBarOpenTime = start + 26 * 900_000L
        val entryOpen = 102.5
        // Bar 26 touches BOTH +6% above and -6% below open
        baseList.add(
            Candle(
                openTime = entryBarOpenTime,
                open = entryOpen,
                high = entryOpen * 1.06,
                low = entryOpen * 0.94,
                close = entryOpen * 1.01,
                volume = 4000.0,
                closeTime = entryBarOpenTime + 899_999L
            )
        )

        val btAmbiguous = TimeMachineEngine.runBacktest(
            symbol = "BTCUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.0,
            candles = baseList,
            referenceTimeMs = 1_800_000_000_000L
        )
        val targetOcc = btAmbiguous.occurrences.lastOrNull()
        assertNotNull(targetOcc)
        assertEquals(BacktestOutcomeType.AMBIGUOUS_SAME_CANDLE, targetOcc?.outcome)
        assertEquals(0, btAmbiguous.tpFirstCount)
        assertTrue(btAmbiguous.ambiguousSameCandleCount >= 1)

        // When granular 1m sub-candles prove TP was reached first, resolve unambiguously to TP_FIRST
        val subCandles = listOf(
            Candle(entryBarOpenTime, entryOpen, entryOpen * 1.04, entryOpen * 0.995, entryOpen * 1.035, 200.0, entryBarOpenTime + 59_999L),
            Candle(entryBarOpenTime + 60_000L, entryOpen * 1.035, entryOpen * 1.04, entryOpen * 0.94, entryOpen * 0.95, 200.0, entryBarOpenTime + 119_999L)
        )
        val btResolved = TimeMachineEngine.runBacktest(
            symbol = "BTCUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.0,
            candles = baseList,
            granularSubCandlesByBarOpenTime = mapOf(entryBarOpenTime to subCandles),
            referenceTimeMs = 1_800_000_000_000L
        )
        assertEquals(BacktestOutcomeType.TP_FIRST, btResolved.occurrences.last().outcome)
    }

    @Test
    fun testTimeMachine_MonteCarlo10000_ReproducibleAndHonestOnSmallSample() {
        val candles = buildTrendPullbackCandles(count = 200, basePrice = 100.0)
        val bt = TimeMachineEngine.runBacktest(
            symbol = "SOLUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.5,
            candles = candles,
            referenceTimeMs = 1_800_000_000_000L
        )

        val mc1 = TimeMachineEngine.runMonteCarlo10000(
            symbol = "SOLUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.5,
            candles = candles,
            backtestResult = bt,
            mcConfig = MonteCarloConfig(iterations = 10_000, seed = 987654L),
            referenceTimeMs = 1_800_000_000_000L
        )
        val mc2 = TimeMachineEngine.runMonteCarlo10000(
            symbol = "SOLUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.5,
            candles = candles,
            backtestResult = bt,
            mcConfig = MonteCarloConfig(iterations = 10_000, seed = 987654L),
            referenceTimeMs = 1_800_000_000_000L
        )

        assertTrue("Sufficient history must allow Monte Carlo", mc1.isSufficientData)
        assertEquals(10_000, mc1.iterations)
        assertEquals(mc1.tpFirstPercent, mc2.tpFirstPercent, 1e-9)
        assertEquals(mc1.slFirstPercent, mc2.slFirstPercent, 1e-9)
        assertEquals(100.0, mc1.tpFirstPercent + mc1.slFirstPercent + mc1.neitherReachedPercent, 1e-5)
        assertNotNull(mc1.percentiles)

        // Insufficient sample check: must refuse to manufacture simulated results
        val tinyCandles = candles.take(30)
        val tinyBt = TimeMachineEngine.runBacktest(
            symbol = "SOLUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.5,
            candles = tinyCandles,
            referenceTimeMs = 1_800_000_000_000L
        )
        val mcInsufficient = TimeMachineEngine.runMonteCarlo10000(
            symbol = "SOLUSDT",
            timeframe = CandleTimeframe.M15,
            direction = FuturesDirection.LONG,
            slDistancePercent = 2.0,
            tpDistancePercent = 3.5,
            candles = tinyCandles,
            backtestResult = tinyBt,
            referenceTimeMs = 1_800_000_000_000L
        )
        assertFalse(mcInsufficient.isSufficientData)
        assertNotNull(mcInsufficient.insufficientDataReason)
        assertEquals(0.0, mcInsufficient.tpFirstPercent, 1e-9)
    }

    @Test
    fun testBudgetManager_UsdtAndPkrSizing_PrecisionAndLeverageRiskExplanation() {
        val usdtConfig = BudgetConfig(
            accountBalanceUsdt = 2000.0,
            maxRiskPerTradeAmount = 40.0,
            riskCurrency = RiskCurrency.USDT,
            pkrPerUsdtRate = 280.0,
            maxRiskPercent = 2.5,
            leverageLimit = 5
        )
        val planUsdt = BudgetManagerEngine.calculatePlan(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            entryPrice = 50_000.0,
            stopLossPrice = 49_000.0,
            takeProfit1Price = 51_500.0,
            takeProfit2Price = 52_500.0,
            requestedLeverage = 5,
            budgetConfig = usdtConfig
        )
        assertTrue(planUsdt.isValid)
        assertTrue(planUsdt.positionQuantity >= 0.030)
        assertTrue(planUsdt.maximumPlannedLossUsdt <= 40.5)
        assertEquals(2.5, planUsdt.rewardRiskRatio, 0.01)
        assertTrue(planUsdt.leverageRiskExplanation.contains("NEVER reduces"))

        // PKR currency conversion check: 11,200 PKR @ 280 = $40 USDT
        val pkrConfig = usdtConfig.copy(riskCurrency = RiskCurrency.PKR, maxRiskPerTradeAmount = 11_200.0)
        val planPkr = BudgetManagerEngine.calculatePlan(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            entryPrice = 50_000.0,
            stopLossPrice = 49_000.0,
            takeProfit1Price = 51_500.0,
            takeProfit2Price = 52_500.0,
            requestedLeverage = 5,
            budgetConfig = pkrConfig
        )
        assertEquals(planUsdt.positionQuantity, planPkr.positionQuantity, 1e-6)
        assertEquals(40.0, planPkr.configuredMaxLossUsdt, 0.01)
    }

    @Test
    fun testRiskManager_EnforcesAll12Checks_AndBlocksViolations() {
        val signal = buildSampleSignal()
        val cfg = BudgetConfig(
            accountBalanceUsdt = 1000.0,
            maxRiskPerTradeAmount = 20.0,
            riskCurrency = RiskCurrency.USDT,
            maxRiskPercent = 2.0,
            leverageLimit = 5,
            dailyLossLimitPercent = 5.0,
            maxTradesPerDay = 3
        )
        val plan = BudgetManagerEngine.calculatePlanForSignal(signal, cfg, requestedLeverage = 4)

        val healthy = RiskManagerEngine.evaluate(
            signal = signal,
            budgetConfig = cfg,
            budgetPlan = plan,
            requestedLeverage = 4,
            dailyLedger = DailyRiskLedger()
        )
        assertEquals(RiskCheckCategory.entries.size, healthy.checks.size)
        assertTrue(healthy.canProceedToOrder)
        assertEquals(RiskDecision.PASS, healthy.overallDecision)

        // Violation test: Exceed daily loss + exceed max trades + duplicate symbol exposure + high leverage
        val risky = RiskManagerEngine.evaluate(
            signal = signal,
            budgetConfig = cfg,
            budgetPlan = plan,
            requestedLeverage = 10,
            dailyLedger = DailyRiskLedger(
                tradesTakenToday = 3,
                realizedPnlUsdtToday = -55.0,
                existingOpenMarginUsdt = 200.0,
                openOrderSymbols = listOf("BTCUSDT")
            )
        )
        assertFalse(risky.canProceedToOrder)
        assertEquals(RiskDecision.BLOCK, risky.overallDecision)
        assertTrue(risky.blockedReasons.any { it.contains("Daily Loss") })
        assertTrue(risky.blockedReasons.any { it.contains("Max Daily Trades") })
        assertTrue(risky.blockedReasons.any { it.contains("Leverage Limit") })
        assertTrue(risky.blockedReasons.any { it.contains("Existing Exposure") })
    }

    @Test
    fun testPaperOrderBroker_SafeModeConfirmationAndManualPlan() {
        val signal = buildSampleSignal()
        val cfg = BudgetConfig(accountBalanceUsdt = 1500.0, maxRiskPerTradeAmount = 25.0)
        val plan = BudgetManagerEngine.calculatePlanForSignal(signal, cfg)
        val riskPass = RiskManagerEngine.evaluate(signal, cfg, plan, requestedLeverage = 3)
        val ticket = PaperOrderBrokerEngine.buildProposedTicket(signal, plan, ExecutionMode.PAPER_TRADING)

        // Must reject without explicit user confirmation
        val unconfirmedOutcome = PaperOrderBrokerEngine.submitOrder(ticket, riskPass, userExplicitlyConfirmed = false)
        assertTrue(unconfirmedOutcome is OrderSubmissionOutcome.Rejected)

        // Must submit cleanly when confirmed and Risk PASS
        val paperOutcome = PaperOrderBrokerEngine.submitOrder(ticket, riskPass, userExplicitlyConfirmed = true)
        assertTrue(paperOutcome is OrderSubmissionOutcome.PaperOrderSubmitted)
        val order = (paperOutcome as OrderSubmissionOutcome.PaperOrderSubmitted).order
        assertTrue(order.createdTimestampPkt.contains("PKT"))
        assertEquals(ExecutionMode.PAPER_TRADING, order.executionMode)

        // Manual Exchange Plan must never pretend a live order was placed
        val manualTicket = PaperOrderBrokerEngine.buildProposedTicket(signal, plan, ExecutionMode.MANUAL_EXCHANGE_PLAN)
        val manualOutcome = PaperOrderBrokerEngine.submitOrder(manualTicket, riskPass, userExplicitlyConfirmed = true)
        assertTrue(manualOutcome is OrderSubmissionOutcome.ManualPlanOnly)
        assertTrue((manualOutcome as OrderSubmissionOutcome.ManualPlanOnly).reason.contains("No live order was sent"))
    }

    @Test
    fun testAlerts_All7Categories_PersistentDeduplicationAndFcmDeepLink() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val mgr1 = NexusNotificationManager(context)

        val signal = buildSampleSignal(symbol = "ETHUSDT", entryPrice = 3000.0, stopLoss = 2900.0, tp1 = 3150.0, tp2 = 3300.0)
        val lifecycleRules = AlertMonitorEngine.createLifecycleRulesForSignal(signal)
        val customPriceRule = TradeAlertRule(
            ruleId = "pt_eth_1",
            signalId = signal.id,
            symbol = "ETHUSDT",
            direction = FuturesDirection.LONG,
            category = AlertRuleCategory.PRICE_TARGET,
            triggerPrice = 3050.0,
            conditionDescription = "Price target $3050"
        )
        val allRules = lifecycleRules + customPriceRule + TradeAlertRule(
            ruleId = "mkt_eth_1",
            signalId = signal.id,
            symbol = "ETHUSDT",
            direction = FuturesDirection.LONG,
            category = AlertRuleCategory.MARKET_CONDITION_CHANGE,
            conditionDescription = "High 24h volatility shift"
        )

        // Price below SL & expired clock triggers SL, Invalidation, Expiry, Entry (if timer triggered), Volatility
        val events = AlertMonitorEngine.evaluateRules(
            rules = allRules,
            livePrices = mapOf("ETHUSDT" to 2890.0),
            signalsById = mapOf(signal.id to signal),
            priceChange24hBySymbol = mapOf("ETHUSDT" to -8.5),
            alreadyTriggeredEventIds = emptySet(),
            nowMs = System.currentTimeMillis() + 10 * 3600 * 1000L
        )
        val categoriesTriggered = events.map { it.category }.toSet()
        assertTrue(categoriesTriggered.contains(AlertRuleCategory.ENTRY_TRIGGER))
        assertTrue(categoriesTriggered.contains(AlertRuleCategory.STOP_LOSS))
        assertTrue(categoriesTriggered.contains(AlertRuleCategory.SETUP_INVALIDATION))
        assertTrue(categoriesTriggered.contains(AlertRuleCategory.SIGNAL_EXPIRY))
        assertTrue(categoriesTriggered.contains(AlertRuleCategory.MARKET_CONDITION_CHANGE))

        // Dispatch via mgr1; re-dispatch same eventId via brand-new mgr2 -> MUST deduplicate across restart!
        val firstEvent = events.first()
        assertTrue(
            mgr1.notifyTradeLifecycleAlert(
                eventId = firstEvent.eventId,
                symbol = firstEvent.symbol,
                signalId = firstEvent.signalId,
                alertType = firstEvent.alertType,
                title = firstEvent.title,
                body = firstEvent.body,
                timestampMs = firstEvent.timestampMs
            )
        )
        val mgr2 = NexusNotificationManager(context)
        assertFalse(
            "Duplicate eventId must stay blocked across new manager instances",
            mgr2.notifyTradeLifecycleAlert(
                eventId = firstEvent.eventId,
                symbol = firstEvent.symbol,
                signalId = firstEvent.signalId,
                alertType = firstEvent.alertType,
                title = firstEvent.title,
                body = firstEvent.body,
                timestampMs = firstEvent.timestampMs
            )
        )
        assertTrue(mgr2.alertHistory.value.any { it.eventId == firstEvent.eventId && !it.isOrderExecution && it.timestampPkt.contains("PKT") })

        // Test FCM remote payload parsing & deep-link Intent
        val fcmParsed = NexusFirebaseMessagingService.parsePayload(
            data = mapOf(
                "event_id" to "fcm_tp_eth_99",
                "symbol" to "ETHUSDT",
                "signal_id" to signal.id,
                "alert_type" to "TAKE_PROFIT",
                "title" to "TP1 Reached: ETHUSDT",
                "body" to "Target 1 reached at $3150"
            )
        )
        assertNotNull(fcmParsed)
        assertEquals(AlertType.TAKE_PROFIT, fcmParsed?.alertType)
        assertTrue(fcmParsed?.openWorkflow == true)

        val deepLink = mgr2.buildDeepLinkIntent(context, "ETHUSDT", signal.id, openWorkflow = true)
        assertEquals(NavigationTab.SCANNER.name, deepLink.getStringExtra(NexusNotificationManager.EXTRA_NAV_TAB))
        assertEquals("ETHUSDT", deepLink.getStringExtra(NexusNotificationManager.EXTRA_SIGNAL_SYMBOL))
        assertEquals(signal.id, deepLink.getStringExtra(NexusNotificationManager.EXTRA_SIGNAL_ID))
        assertTrue(deepLink.getBooleanExtra(NexusNotificationManager.EXTRA_OPEN_WORKFLOW, false))

        // Verify user price alerts evaluation
        val priceAlertEvents = AlertMonitorEngine.evaluateUserPriceAlerts(
            alerts = listOf(
                MarketAlert("ua_1", "BTC/USDT", "Bitcoin", 60_000.0, AlertDirection.ABOVE)
            ),
            livePrices = mapOf("BTCUSDT" to 60_250.0)
        )
        assertEquals(1, priceAlertEvents.size)
        assertEquals(AlertRuleCategory.PRICE_TARGET, priceAlertEvents.first().category)
    }

    @Test
    fun test92ConfidencePolicy_NeverInflates_AndScannerUiShowsNoQualifyingSetupAvailable() {
        val repo = FuturesScannerRepository(context = null)
        repo.setStrict92ConfidenceMode(true)
        val lowScoreSignal = buildSampleSignal(symbol = "BTCUSDT", qualityScore = 82)
        val qualifyingSignal = buildSampleSignal(symbol = "SOLUSDT", qualityScore = 94)

        val filtered = repo.filterQualifyingSignals(listOf(lowScoreSignal))
        assertTrue("82% must not pass strict 92% filter and must never be inflated", filtered.isEmpty())
        assertEquals(1, repo.filterQualifyingSignals(listOf(lowScoreSignal, qualifyingSignal)).size)

        composeTestRule.setContent {
            NexusTradingTheme {
                ScannerScreen(
                    scannerItems = emptyList(),
                    scannerFilter = "ALL",
                    onFilterSelected = {},
                    onSetAlertClick = {},
                    futuresScannerState = ScannerEngineState(
                        activeSignals = listOf(lowScoreSignal),
                        strict92ConfidenceMode = true,
                        minQualifyingConfidence = 92
                    ),
                    strict92ConfidenceMode = true
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("no_qualifying_setup_card").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("No qualifying setup available.").assertIsDisplayed()
    }

    @Test
    fun testTradeWorkflowSheet_EndToEndConfirmationAndSafeModeSubmissionUi() {
        val signal = buildSampleSignal(symbol = "BTCUSDT", qualityScore = 93)
        val cfg = BudgetConfig(accountBalanceUsdt = 1000.0, maxRiskPerTradeAmount = 20.0)
        val plan = BudgetManagerEngine.calculatePlanForSignal(signal, cfg)
        val risk = RiskManagerEngine.evaluate(signal, cfg, plan)
        val ticket = PaperOrderBrokerEngine.buildProposedTicket(signal, plan, ExecutionMode.PAPER_TRADING)

        var confirmedState = false
        var submitClicked = false

        composeTestRule.setContent {
            NexusTradingTheme {
                TradeWorkflowSheet(
                    workflowState = TradeWorkflowState(
                        isVisible = true,
                        selectedSignal = signal,
                        budgetConfig = cfg,
                        budgetPlan = plan,
                        riskAssessment = risk,
                        proposedTicket = ticket,
                        executionMode = ExecutionMode.PAPER_TRADING,
                        userExplicitlyConfirmed = confirmedState
                    ),
                    onDismiss = {},
                    onUpdateBudgetConfig = {},
                    onRerunTimeMachine = { _, _, _ -> },
                    onSelectExecutionMode = {},
                    onExplicitConfirmationChanged = { confirmedState = it },
                    onSubmitPaperOrder = { submitClicked = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("trade_workflow_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithTag("workflow_lazy_column").performScrollToIndex(6)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("workflow_submit_order_btn").assertIsNotEnabled()
        composeTestRule.onNodeWithTag("workflow_confirm_checkbox").performClick()
        assertTrue("Checkbox callback must update explicit confirmation", confirmedState)
        assertFalse("Order must not auto-submit from checkbox alone", submitClicked)
    }
}
