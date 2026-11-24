package com.example

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GenerativeAiClient
import com.example.data.ai.MarketContextBuilder
import com.example.data.ai.NexusAiRepository
import com.example.data.ai.NexusAiStreamResult
import com.example.data.ai.NexusChatMessage
import com.example.data.ai.OfflineTradingAnalysisEngine
import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.learning.LearningLanguageMode
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.EventStatus
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.data.news.FinancialNewsService
import com.example.data.news.NewsImpactAnalyzer
import com.example.data.providers.GdeltNewsProvider
import com.example.data.reasoning.TradingReasoningEngine
import com.example.data.scanner.BinanceFuturesScannerEngine
import com.example.data.scanner.CryptoPatternDetector
import com.example.data.scanner.CryptoSetupPattern
import com.example.data.scanner.EntryTimerConfig
import com.example.data.scanner.EntryTimerEngine
import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.ExitPlannerEngine
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.smc.SmcCalculationEngine
import com.example.ui.dialogs.NewsDetailDialog
import com.example.ui.screens.FuturesSignalCard
import com.example.ui.theme.NexusTradingTheme
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MasterUpgradePart1Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildClosedCandles(
        count: Int,
        startPrice: Double,
        step: Double,
        intervalMs: Long = 900_000L,
        endTimeMs: Long = 1_760_000_000_000L
    ): List<Candle> {
        val list = mutableListOf<Candle>()
        var price = startPrice
        val startMs = endTimeMs - count * intervalMs
        for (i in 0 until count) {
            val openTime = startMs + i * intervalMs
            val closeTime = openTime + intervalMs - 1L
            val open = price
            val close = (open + step).coerceAtLeast(1.0)
            val high = maxOf(open, close) + kotlin.math.abs(step) * 0.4 + 0.5
            val low = (minOf(open, close) - kotlin.math.abs(step) * 0.4 - 0.5).coerceAtLeast(0.5)
            price = close
            list.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1_000.0 + i * 25.0,
                    closeTime = closeTime
                )
            )
        }
        return list
    }

    @Test
    fun testTimeZoneUtils_FiltersFormingCandleAndFormatsKarachiPkt() {
        val now = 1_760_000_000_000L
        val closed1 = Candle(now - 1_800_000L, 100.0, 102.0, 99.0, 101.0, 500.0, now - 900_001L)
        val closed2 = Candle(now - 900_000L, 101.0, 103.0, 100.0, 102.5, 650.0, now - 1L)
        val formingCandle = Candle(now, 102.5, 110.0, 102.0, 109.0, 900.0, now + 899_999L)
        val syntheticHistorical = Candle(now - 2_700_000L, 99.0, 100.5, 98.5, 100.0, 400.0, 0L)

        val filtered = TimeZoneUtils.filterCompletedCandles(
            listOf(syntheticHistorical, closed1, closed2, formingCandle),
            referenceTimeMs = now
        )
        assertEquals(3, filtered.size)
        assertFalse(filtered.contains(formingCandle))

        val formatted = TimeZoneUtils.formatKarachiTimestamp(now)
        val shortFmt = TimeZoneUtils.formatKarachiShort(now)
        assertTrue(formatted.contains("PKT"))
        assertTrue(shortFmt.endsWith("PKT"))
        assertEquals("Asia/Karachi", TimeZoneUtils.ASIA_KARACHI_ID)
    }

    @Test
    fun testEntryTimer_WickRejectedVsCompletedCloseTriggeredAndNeverAutoTrades() {
        val baseTime = 1_760_000_000_000L
        val history = buildClosedCandles(count = 20, startPrice = 100.0, step = 0.05, endTimeMs = baseTime)
            .map { it.copy(high = 104.5, low = 99.5, close = 101.0, volume = 1_000.0) }
            .toMutableList()

        // Bar 20: wick probes ABOVE triggerLevel 105.0 (high=106.5) but closes BELOW (close=104.2) -> MUST NOT trigger
        val wickProbeBar = Candle(
            openTime = baseTime,
            open = 101.2,
            high = 106.5,
            low = 101.0,
            close = 104.2,
            volume = 1_800.0,
            closeTime = baseTime + 899_999L
        )
        history.add(wickProbeBar)

        val waitingResult = EntryTimerEngine.evaluateBarByBar(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = 105.0,
            invalidationLevel = 98.0,
            candles = history,
            setupStartIndex = 20,
            config = EntryTimerConfig(requireCandleClose = true, requireVolumeConfirmation = true, maxWaitBars = 5),
            referenceTimeMs = baseTime + 1_000_000L
        )
        assertEquals(EntryTimerStatus.WAITING, waitingResult.status)
        assertEquals(1, waitingResult.wickRejectionCount)
        assertFalse(waitingResult.autoTradeExecuted)

        // Bar 21: completed candle CLOSES above 105.0 with RVOL >= 1.25x -> MUST trigger cleanly with PKT timestamp
        val confirmBar = Candle(
            openTime = baseTime + 900_000L,
            open = 104.2,
            high = 107.2,
            low = 104.0,
            close = 106.4,
            volume = 2_400.0,
            closeTime = baseTime + 1_799_999L
        )
        history.add(confirmBar)

        val triggeredResult = EntryTimerEngine.evaluateBarByBar(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = 105.0,
            invalidationLevel = 98.0,
            candles = history,
            setupStartIndex = 20,
            config = EntryTimerConfig(requireCandleClose = true, requireVolumeConfirmation = true, maxWaitBars = 5),
            referenceTimeMs = baseTime + 2_000_000L
        )
        assertEquals(EntryTimerStatus.TRIGGERED, triggeredResult.status)
        assertEquals(106.4, triggeredResult.triggerPrice ?: 0.0, 0.001)
        assertEquals(confirmBar.closeTime, triggeredResult.triggerTimestampMs)
        assertTrue(triggeredResult.triggerTimestampPkt?.contains("PKT") == true)
        assertTrue(triggeredResult.volumeConfirmed)
        assertEquals(1, triggeredResult.wickRejectionCount)
        assertFalse(triggeredResult.autoTradeExecuted)
    }

    @Test
    fun testEntryTimer_NoDataInvalidatedAndExpiredStates() {
        val noData = EntryTimerEngine.evaluateBarByBar(
            symbol = "ETHUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = 2000.0,
            invalidationLevel = 1900.0,
            candles = emptyList()
        )
        assertEquals(EntryTimerStatus.NO_DATA, noData.status)
        assertFalse(noData.autoTradeExecuted)

        val baseTime = 1_760_000_000_000L
        val bars = buildClosedCandles(count = 6, startPrice = 100.0, step = 0.0, endTimeMs = baseTime).toMutableList()
        bars[4] = bars[4].copy(close = 94.0, low = 93.0) // closes below invalidation 95.0
        val invalidated = EntryTimerEngine.evaluateBarByBar(
            symbol = "SOLUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = 105.0,
            invalidationLevel = 95.0,
            candles = bars,
            setupStartIndex = 3,
            referenceTimeMs = baseTime + 10_000L
        )
        assertEquals(EntryTimerStatus.INVALIDATED, invalidated.status)
        assertFalse(invalidated.autoTradeExecuted)

        val flatBars = buildClosedCandles(count = 6, startPrice = 100.0, step = 0.0, endTimeMs = baseTime)
            .map { it.copy(high = 101.0, low = 99.0, close = 100.0) }
        val expired = EntryTimerEngine.evaluateBarByBar(
            symbol = "SOLUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = 105.0,
            invalidationLevel = 95.0,
            candles = flatBars,
            setupStartIndex = 0,
            config = EntryTimerConfig(maxWaitBars = 4),
            referenceTimeMs = baseTime + 10_000L
        )
        assertEquals(EntryTimerStatus.EXPIRED, expired.status)
        assertFalse(expired.autoTradeExecuted)
    }

    @Test
    fun testCryptoPatternDetector_DetectsUtcSessionOrbAndBullishFlagWithoutStockHours() = runBlocking {
        // Construct candles anchored on a 04:00 UTC 4H crypto session boundary
        val utcCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            set(2026, Calendar.MAY, 10, 0, 0, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val startMs = utcCal.timeInMillis
        val candles = mutableListOf<Candle>()
        // 20 prior bars before 04:00 UTC (16 x 15m bars = 4 hours + 4 more)
        var price = 100.0
        for (i in 0 until 20) {
            val openTime = startMs + i * 900_000L
            candles.add(
                Candle(
                    openTime = openTime,
                    open = price,
                    high = price + 0.4,
                    low = price - 0.4,
                    close = price + 0.05,
                    volume = 1_000.0,
                    closeTime = openTime + 899_999L
                )
            )
            price += 0.05
        }
        // Index 16 & 17 are 04:00 UTC & 04:15 UTC? Wait: index 16 is 04:00 UTC!
        // Let's explicitly overwrite index 16..20 so index 16 (04:00 UTC) and 17 (04:15 UTC) form a session opening range [99.5..102.0],
        // and index 20 breaks out cleanly above 102.0 with volume expansion.
        val orbCandle0 = candles[16].copy(open = 100.5, high = 102.0, low = 99.5, close = 101.2)
        val orbCandle1 = candles[17].copy(open = 101.2, high = 101.9, low = 100.2, close = 101.5)
        val holdCandle = candles[18].copy(open = 101.5, high = 101.8, low = 101.0, close = 101.6)
        val holdCandle2 = candles[19].copy(open = 101.6, high = 101.9, low = 101.1, close = 101.8)
        candles[16] = orbCandle0
        candles[17] = orbCandle1
        candles[18] = holdCandle
        candles[19] = holdCandle2
        val breakoutTime = startMs + 20 * 900_000L
        candles.add(
            Candle(
                openTime = breakoutTime,
                open = 101.8,
                high = 104.2,
                low = 101.7,
                close = 103.8,
                volume = 2_500.0,
                closeTime = breakoutTime + 899_999L
            )
        )

        val tech = TechnicalIndicatorsEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, candles)
        val smc = SmcCalculationEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, candles)
        val result = CryptoPatternDetector.detect(
            candles15m = candles,
            tech15m = tech,
            smc15m = smc,
            referenceTimeMs = breakoutTime + 1_000_000L
        )
        assertNotNull(result)
        assertTrue(result!!.allDetectedPatterns.contains(CryptoSetupPattern.CRYPTO_SESSION_ORB))
        assertTrue(result.disclaimer.contains("not a guaranteed trade", ignoreCase = true))
    }

    @Test
    fun testExitPlannerEngine_PreEntryLevelsPartialsExpiryAndStopDisclaimer() {
        val refTime = 1_760_000_000_000L
        val targetCalc = BinanceFuturesScannerEngine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = 100.0,
            structuralStopLoss = 96.0,
            atr15m = 1.5,
            atr1h = 3.0,
            leverage = 5,
            initialMarginUsdt = 100.0,
            fundingRatePercent = 0.01
        )
        assertTrue(targetCalc.isViable)

        val plan = ExitPlannerEngine.buildPlan(
            direction = FuturesDirection.LONG,
            entryPrice = 100.0,
            srInvalidationLevel = 96.0,
            atr15m = 1.5,
            targetCalc = targetCalc,
            timeframe = CandleTimeframe.M15,
            setupExpiryBars = 12,
            referenceTimeMs = refTime
        )
        assertEquals(3, plan.takeProfitTiers.size)
        assertEquals(100, plan.takeProfitTiers.sumOf { it.allocationPercent })
        assertTrue(plan.riskRewardRatio > 1.0)
        assertTrue(plan.expiryDeadlinePkt.contains("PKT"))
        assertTrue(plan.invalidationBasis.contains("S/R", ignoreCase = true))
        assertTrue(plan.invalidationBasis.contains("ATR", ignoreCase = true))
        assertTrue(plan.calculationAssumptions.any { it.contains("BEFORE entry", ignoreCase = true) })
        assertTrue(plan.stopOrderDisclaimer.contains("NOT guaranteed", ignoreCase = true))
    }

    @Test
    fun testNewsMonitor_CryptoMacroAssetsFactsVsInterpretationPktAndSeenTracking() {
        val analyzedEtf = NewsImpactAnalyzer.analyze(
            headline = "BlackRock IBIT & Ethereum Spot ETF Net Inflows Surge While Solana & XRP Open Interest Jumps",
            summary = "Institutional filings show daily ETF inflow alongside SEC and Binance Futures updates.",
            source = "Reuters",
            timestampPkt = "May 10, 19:30 PKT"
        )
        assertTrue(analyzedEtf.affectedAssets.containsAll(listOf("BTC", "ETH", "SOL", "XRP")))
        assertEquals("ETF & Institutional", analyzedEtf.category)
        assertTrue(analyzedEtf.confirmedFacts.any { it.contains("Asia/Karachi") })
        assertTrue(analyzedEtf.aiInterpretation.contains("not confirmed order-flow causality", ignoreCase = true))

        val analyzedReg = NewsImpactAnalyzer.analyze(
            headline = "EU MiCA & CFTC Enforcement Guidance Impacts Coinbase and Bybit Listings",
            source = "Exchange Filings",
            timestampPkt = "May 10, 16:40 PKT"
        )
        assertEquals("Regulation & Exchanges", analyzedReg.category)
        assertEquals(NewsImpactLevel.HIGH, analyzedReg.impactLevel)

        // Test GDELT provider timestamp normalization to PKT
        val provider = GdeltNewsProvider(clockMillis = { 1_760_000_000_000L })
        val gdeltJson = """
            {
              "articles": [
                {
                  "title": "Federal Reserve FOMC Inflation CPI Report Moves Bitcoin and Solana",
                  "domain": "reuters.com",
                  "url": "https://www.reuters.com/test",
                  "seendate": "20251009T090000Z"
                }
              ]
            }
        """.trimIndent()
        val parsed = provider.filterAndParseArticles(gdeltJson, nowMillis = 1_760_000_000_000L)
        assertEquals(1, parsed.size)
        val article = parsed.first().article
        assertTrue(article.timestamp.contains("PKT"))
        assertTrue(article.affectedAssets.contains("BTC"))
        assertTrue(article.affectedAssets.contains("SOL"))
        assertTrue(article.confirmedFacts.isNotEmpty())
        assertTrue(article.aiInterpretation.isNotBlank())

        // Test duplicate & seen tracking across batches
        val service = FinancialNewsService()
        val firstRun = service.markSeenAndDeduplicate(listOf(article, article.copy(id = "dup")))
        assertEquals(1, firstRun.size)
        assertFalse(firstRun.first().isPreviouslySeen)

        val secondRun = service.markSeenAndDeduplicate(listOf(article))
        assertEquals(1, secondRun.size)
        assertTrue(secondRun.first().isPreviouslySeen)
    }

    @Test
    fun testUnifiedPipeline_MarketIntelligenceToReasoningEngineToNexusAiLanguages() = runBlocking {
        val now = 1_760_000_000_000L
        val candles = buildClosedCandles(count = 40, startPrice = 84000.0, step = 120.0, endTimeMs = now)
        val ta = TechnicalIndicatorsEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, candles)
        val smc = SmcCalculationEngine.analyze("BTC", "Bitcoin", CandleTimeframe.M15, candles)
        val timer = EntryTimerEngine.evaluateBarByBar(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = candles[candles.lastIndex - 1].close - 10.0,
            invalidationLevel = 83500.0,
            candles = candles,
            setupStartIndex = candles.lastIndex - 1,
            referenceTimeMs = now + 5_000L
        )
        val tc = BinanceFuturesScannerEngine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = candles.last().close,
            structuralStopLoss = 85000.0,
            atr15m = ta.volatility.atr?.value ?: 180.0,
            atr1h = 360.0,
            leverage = 5
        )
        val exitPlan = ExitPlannerEngine.buildPlan(
            direction = FuturesDirection.LONG,
            entryPrice = candles.last().close,
            srInvalidationLevel = 85000.0,
            atr15m = ta.volatility.atr?.value ?: 180.0,
            targetCalc = tc,
            referenceTimeMs = now
        )
        val fs = FuturesScanSignal(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            direction = FuturesDirection.LONG,
            currentPrice = candles.last().close,
            entryZoneMin = candles.last().close - 200.0,
            entryZoneMax = candles.last().close + 100.0,
            entryPrice = candles.last().close,
            stopLoss = tc.stopLoss,
            takeProfit1 = tc.takeProfit1,
            takeProfit2 = tc.takeProfit2,
            takeProfit3 = tc.takeProfit3,
            riskRewardRatio = tc.riskRewardRatio,
            qualityScore = 84,
            qualityRating = "High Quality",
            timeframeConfirmations = emptyList(),
            reasonsForEntry = listOf("15M Breakout Close"),
            invalidationCondition = "Close below SL",
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED,
            detectedPattern = CryptoSetupPattern.BREAKOUT,
            entryTimer = timer,
            exitPlan = exitPlan
        )
        val asset = MarketAsset(
            id = "BTC", name = "Bitcoin", symbol = "BTC/USDT", category = AssetCategory.CRYPTO,
            currentPrice = candles.last().close, change24hPercent = 2.4, dataStatus = MarketDataStatus.LIVE,
            lastUpdated = now
        )
        val macroNews = NewsImpactAnalyzer.enrichNewsItem(
            MarketNews(
                id = "n_fed",
                title = "FOMC Keeps Rates Unchanged as Bitcoin ETF Inflows Accelerate",
                source = "Federal Reserve / Reuters",
                timestamp = TimeZoneUtils.formatKarachiShort(now),
                category = "ETF & Institutional",
                summary = "Macro liquidity remains data dependent.",
                impact = NewsImpact.BULLISH,
                fullContent = "Details...",
                affectedAssets = listOf("BTC"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                publishedTimestampMs = now
            )
        )

        val snapshot = MarketContextBuilder.buildSnapshot(
            asset = asset,
            candles = candles,
            timeframe = CandleTimeframe.M15,
            technicalAnalysis = ta,
            smcAnalysis = smc,
            newsList = listOf(macroNews),
            futuresSignal = fs,
            allAssets = listOf(asset)
        )

        val promptContext = snapshot.toPromptContext(LearningLanguageMode.ROMAN_URDU)
        assertTrue(promptContext.contains("TRADING REASONING ENGINE", ignoreCase = true))
        assertTrue(promptContext.contains("Entry Timer", ignoreCase = true))
        assertTrue(promptContext.contains("Pre-Entry Exit Plan", ignoreCase = true))
        assertTrue(promptContext.contains("CONFIRMED FACTS", ignoreCase = true))
        assertTrue(promptContext.contains("PKT"))

        // Verify Offline engine incorporates Entry Timer & Exit Plan across English, Roman Urdu, Urdu
        val enOffline = OfflineTradingAnalysisEngine.generateAnalysis("Explain BTC setup", snapshot, LearningLanguageMode.ENGLISH, "API test")
        val romanOffline = OfflineTradingAnalysisEngine.generateAnalysis("BTC ka setup samjhao", snapshot, LearningLanguageMode.ROMAN_URDU, "API test")
        val urOffline = OfflineTradingAnalysisEngine.generateAnalysis("بی ٹی سی سیٹ اپ بتائیں", snapshot, LearningLanguageMode.STANDARD_URDU, "API test")
        assertTrue(enOffline.contains("Pre-Entry Exit Plan", ignoreCase = true) || enOffline.contains("Stop orders", ignoreCase = true))
        assertTrue(romanOffline.contains("Entry Timer", ignoreCase = true) || romanOffline.contains("Exit Plan", ignoreCase = true))
        assertTrue(urOffline.contains("انٹری ٹائمر") || urOffline.contains("Entry Timer"))

        // Verify NexusAiRepository system instruction enforces pipeline & PKT & facts separation
        var capturedSystem = ""
        val fakeClient = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(systemInstruction: String, history: List<Pair<String, String>>, userMessage: String): Flow<String> = flow {
                capturedSystem = systemInstruction
                emit("Explained analysis grounded in Trading Reasoning Engine.")
            }
        }
        val repo = NexusAiRepository(ApplicationProvider.getApplicationContext<Context>(), aiClient = fakeClient)
        var msg: NexusChatMessage? = null
        repo.generateResponseStream("Explain BTC setup", snapshot, LearningLanguageMode.ENGLISH).collect {
            if (it is NexusAiStreamResult.Completed) msg = it.message
        }
        assertNotNull(msg)
        assertTrue(capturedSystem.contains("MARKET INTELLIGENCE -> TRADING REASONING ENGINE -> NEXUS AI ASSISTANT"))
        assertTrue(capturedSystem.contains("Asia/Karachi") && capturedSystem.contains("PKT"))
        assertTrue(capturedSystem.contains("CONFIRMED FACTS"))
    }

    @Test
    fun testUi_FuturesSignalCardDisplaysPatternEntryTimerAndPreEntryExitPlanner() {
        val now = 1_760_000_000_000L
        val candles = buildClosedCandles(count = 25, startPrice = 87000.0, step = 40.0, endTimeMs = now)
        val timer = EntryTimerEngine.evaluateBarByBar(
            symbol = "BTCUSDT",
            direction = FuturesDirection.LONG,
            triggerLevel = candles[candles.lastIndex - 1].close - 5.0,
            invalidationLevel = 86000.0,
            candles = candles,
            setupStartIndex = candles.lastIndex - 1,
            referenceTimeMs = now + 10_000L
        )
        val tc = BinanceFuturesScannerEngine.calculateFuturesTargets(
            direction = FuturesDirection.LONG,
            entryPrice = 88000.0,
            structuralStopLoss = 86800.0,
            atr15m = 210.0,
            atr1h = 420.0,
            leverage = 5
        )
        val exitPlan = ExitPlannerEngine.buildPlan(
            direction = FuturesDirection.LONG,
            entryPrice = 88000.0,
            srInvalidationLevel = 86800.0,
            atr15m = 210.0,
            targetCalc = tc,
            referenceTimeMs = now
        )
        val signal = FuturesScanSignal(
            symbol = "BTCUSDT",
            baseAsset = "BTC",
            direction = FuturesDirection.LONG,
            currentPrice = 88000.0,
            entryZoneMin = 87700.0,
            entryZoneMax = 88100.0,
            entryPrice = 88000.0,
            stopLoss = tc.stopLoss,
            takeProfit1 = tc.takeProfit1,
            takeProfit2 = tc.takeProfit2,
            takeProfit3 = tc.takeProfit3,
            riskRewardRatio = tc.riskRewardRatio,
            qualityScore = 88,
            qualityRating = "A+ Confluence",
            timeframeConfirmations = emptyList(),
            reasonsForEntry = listOf("Bullish Flag breakout above resistance"),
            invalidationCondition = "15M close below stop loss",
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED,
            timestamp = now,
            detectedPattern = CryptoSetupPattern.BULLISH_FLAG,
            entryTimer = timer,
            exitPlan = exitPlan
        )

        composeTestRule.setContent {
            NexusTradingTheme {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    FuturesSignalCard(
                        signal = signal,
                        onExplainClick = {},
                        onCopyLevels = {},
                        onSimulatePaperTrade = {}
                    )
                }
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("pattern_badge_BTCUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithTag("entry_timer_chip_BTCUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithTag("entry_timer_box_BTCUSDT").assertIsDisplayed()
        composeTestRule.onNodeWithTag("view_details_BTCUSDT").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("exit_planner_card_BTCUSDT").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("PRE-ENTRY EXIT PLANNER").assertIsDisplayed()
    }

    @Test
    fun testUi_NewsDetailDialogSeparatesConfirmedFactsFromAiInterpretation() {
        val news = NewsImpactAnalyzer.enrichNewsItem(
            MarketNews(
                id = "ui_news_1",
                title = "Federal Reserve CPI Print & Spot Bitcoin ETF Inflow Boost Crypto Liquidity",
                source = "Reuters Wire",
                timestamp = "Oct 09, 19:00 PKT",
                category = "ETF & Institutional",
                summary = "Institutional ETF inflow coincided with softer CPI inflation print.",
                impact = NewsImpact.BULLISH,
                fullContent = "Full market recap content.",
                affectedAssets = listOf("BTC", "ETH"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                publishedTimestampMs = 1_760_000_000_000L
            )
        )

        composeTestRule.setContent {
            NexusTradingTheme {
                NewsDetailDialog(
                    news = news,
                    onDismiss = {},
                    onToggleBookmark = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("news_confirmed_facts_box").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("CONFIRMED FACTS").assertIsDisplayed()
        composeTestRule.onNodeWithTag("news_ai_interpretation_box").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("AI / NLP INTERPRETATION (PROBABILISTIC)").assertIsDisplayed()
    }
}
