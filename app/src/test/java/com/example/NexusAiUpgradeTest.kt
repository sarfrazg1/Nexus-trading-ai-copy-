package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.GenerativeAiClient
import com.example.data.ai.MarketContextBuilder
import com.example.data.ai.MessageSender
import com.example.data.ai.NexusAiRepository
import com.example.data.ai.NexusAiStreamResult
import com.example.data.ai.NexusChatMessage
import com.example.data.ai.NexusConversation
import com.example.data.ai.OfflineTradingAnalysisEngine
import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.learning.LearningLanguageMode
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.MarketOverview
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.data.model.ScannerItem
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.data.smc.SmcCalculationEngine
import com.example.ui.screens.NexusAiScreen
import com.example.ui.theme.NexusTradingTheme
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
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class NexusAiUpgradeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildCandles(base: Double, step: Double, count: Int = 80): List<Candle> {
        val list = mutableListOf<Candle>()
        var price = base
        val now = System.currentTimeMillis()
        for (i in 0 until count) {
            val open = price
            val close = open + step + ((i % 3) - 1) * (step * 0.25)
            val high = maxOf(open, close) + kotlin.math.abs(step) * 0.6
            val low = minOf(open, close) - kotlin.math.abs(step) * 0.6
            price = close
            list.add(
                Candle(
                    openTime = now - (count - i) * 3_600_000L,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = 1500.0 + i * 20.0,
                    closeTime = now - (count - i - 1) * 3_600_000L
                )
            )
        }
        return list
    }

    private fun buildRichContext(
        assetId: String = "BTC",
        assetName: String = "Bitcoin",
        symbol: String = "BTC/USDT",
        price: Double = 88450.0,
        changePct: Double = -3.85,
        isLive: Boolean = true
    ) = runBlocking {
        val now = System.currentTimeMillis()
        val candles = buildCandles(base = price - 1200.0, step = if (changePct < 0) -15.0 else 15.0)
        val ta = TechnicalIndicatorsEngine.analyze(assetId, assetName, CandleTimeframe.H1, candles)
        val smc = SmcCalculationEngine.analyze(assetId, assetName, CandleTimeframe.H1, candles)
        val btc = MarketAsset(
            id = "BTC", name = "Bitcoin", symbol = "BTC/USDT", category = AssetCategory.CRYPTO,
            currentPrice = 88450.0, change24hPercent = -3.85, high24h = 92100.0, low24h = 87900.0,
            volume24h = "32.4K BTC", dataStatus = if (isLive) MarketDataStatus.LIVE else MarketDataStatus.UNAVAILABLE,
            dataSourceName = if (isLive) "Binance WebSocket (Live)" else "Unconnected",
            lastUpdated = if (isLive) now else null,
            lastUpdatedFormatted = if (isLive) "00:30:00 UTC" else "Never"
        )
        val eth = MarketAsset(
            id = "ETH", name = "Ethereum", symbol = "ETH/USDT", category = AssetCategory.CRYPTO,
            currentPrice = 3120.0, change24hPercent = -4.60, high24h = 3300.0, low24h = 3080.0,
            volume24h = "180K ETH", dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance WebSocket (Live)", lastUpdated = now, lastUpdatedFormatted = "00:30:00 UTC"
        )
        val sol = MarketAsset(
            id = "SOL", name = "Solana", symbol = "SOL/USDT", category = AssetCategory.CRYPTO,
            currentPrice = 198.40, change24hPercent = +2.15, high24h = 202.0, low24h = 190.0,
            volume24h = "4.1M SOL", dataStatus = MarketDataStatus.LIVE,
            dataSourceName = "Binance WebSocket (Live)", lastUpdated = now, lastUpdatedFormatted = "00:30:00 UTC",
            isFavorite = true
        )
        val target = when (assetId) {
            "ETH" -> eth
            "SOL" -> sol
            else -> btc
        }
        val futuresSetup = FuturesScanSignal(
            symbol = "SOLUSDT", baseAsset = "SOL", direction = FuturesDirection.LONG,
            currentPrice = 198.40, entryZoneMin = 195.0, entryZoneMax = 197.5, entryPrice = 196.2,
            stopLoss = 189.0, takeProfit1 = 205.0, takeProfit2 = 212.0, takeProfit3 = 220.0,
            riskRewardRatio = 2.6, qualityScore = 87, qualityRating = "A+ Confluence",
            timeframeConfirmations = emptyList(),
            reasonsForEntry = listOf("4H Bullish Order Block", "15M CHoCH confirmation"),
            invalidationCondition = "1H close below $189.00",
            lifecycle = FuturesSignalLifecycle.ENTRY_CONFIRMED
        )
        val scannerItem = ScannerItem(
            id = "SOL_1H", symbol = "SOL/USDT", name = "Solana", category = AssetCategory.CRYPTO,
            rsi14 = 56.2, macdStatus = "Bullish Cross", emaCondition = "Above 50 & 200 EMA",
            volumeMultiplier = 1.9, aiScore = 88, matchedCriteria = listOf("BOS Up", "High Volume"),
            verifiedPrice = 198.40, change24hPercent = 2.15, detectedCondition = "Strongest Momentum",
            assetId = "SOL"
        )
        val news = listOf(
            MarketNews(
                id = "n1", title = "Bitcoin Spot ETF Outflows Weigh on Broad Crypto Sentiment",
                source = "Reuters Wire", timestamp = "00:15 UTC", category = "Crypto",
                summary = "Institutional outflows pressured BTC and ETH intraday.",
                impact = NewsImpact.BEARISH, fullContent = "...",
                affectedAssets = listOf("BTC", "ETH"), impactLevel = NewsImpactLevel.HIGH
            )
        )
        val deriv = BinanceFuturesApiClient.DerivativesSnapshot(
            symbol = "${assetId}USDT",
            fundingRatePercent = -0.0085,
            openInterestContracts = 94500.0,
            longShortAccountRatio = 1.42
        )

        MarketContextBuilder.buildSnapshot(
            asset = target,
            candles = candles,
            timeframe = CandleTimeframe.H1,
            technicalAnalysis = ta,
            smcAnalysis = smc,
            newsList = news,
            futuresSignal = futuresSetup,
            allAssets = listOf(btc, eth, sol),
            marketOverview = MarketOverview(fearAndGreedIndex = 41, sentimentLabel = "Fear", btcDominance = "57.8%"),
            scannerItems = listOf(scannerItem),
            activeFuturesSignals = listOf(futuresSetup),
            derivatives = deriv,
            isLiveNewsFeed = isLive,
            newsLastFetchedFormatted = if (isLive) "00:25:00" else "Live news unavailable"
        )
    }

    @Test
    fun testTwentyUnseenQuestions_OfflineFallbackIsDistinctAndHonest() {
        val ctx = buildRichContext()
        val unseenQuestions = listOf(
            "Why is the crypto market falling today?" to LearningLanguageMode.ENGLISH,
            "What is causing BTC to move right now?" to LearningLanguageMode.ENGLISH,
            "Is this a good time to buy Bitcoin?" to LearningLanguageMode.ENGLISH,
            "Which watchlist coin has the strongest setup?" to LearningLanguageMode.ENGLISH,
            "Where are possible entry zones for BTC?" to LearningLanguageMode.ENGLISH,
            "What are the support and resistance levels?" to LearningLanguageMode.ENGLISH,
            "Could the market fall further from here?" to LearningLanguageMode.ENGLISH,
            "Explain CPI in beginner-friendly language" to LearningLanguageMode.ENGLISH,
            "Explain PPI and why traders watch it" to LearningLanguageMode.ENGLISH,
            "How do Fed FOMC interest rate decisions affect crypto?" to LearningLanguageMode.ENGLISH,
            "What are perpetual funding rates?" to LearningLanguageMode.ENGLISH,
            "Explain open interest for beginners" to LearningLanguageMode.ENGLISH,
            "How do crypto liquidations cause sudden crashes?" to LearningLanguageMode.ENGLISH,
            "Compare 5m vs 1h chart timeframes" to LearningLanguageMode.ENGLISH,
            "How much risk and leverage should I use?" to LearningLanguageMode.ENGLISH,
            "Aaj crypto market kyun gir rahi hai?" to LearningLanguageMode.ROMAN_URDU,
            "Watchlist mein sab se strong setup kis coin ka hai?" to LearningLanguageMode.ROMAN_URDU,
            "Funding rate kya hota hai asaan alfaaz mein?" to LearningLanguageMode.ROMAN_URDU,
            "کیا اس وقت بٹ کوائن خریدنا مناسب ہے؟" to LearningLanguageMode.STANDARD_URDU,
            "اوپن انٹرسٹ اور لیکویڈیشن کی وضاحت کریں" to LearningLanguageMode.STANDARD_URDU,
            "What will the weather in Tokyo be tomorrow?" to LearningLanguageMode.ENGLISH
        )

        val responses = unseenQuestions.map { (question, lang) ->
            OfflineTradingAnalysisEngine.generateAnalysis(
                query = question,
                context = ctx,
                language = lang,
                unavailableReason = "Free-tier quota exhausted"
            )
        }

        // Every response must clearly disclose it is limited offline rule-based analysis (never fake AI)
        responses.forEachIndexed { idx, resp ->
            assertTrue("Missing offline disclosure for Q$idx", resp.contains("OFFLINE", ignoreCase = true) || resp.contains("آف لائن"))
            assertTrue("Missing unavailable reason for Q$idx", resp.contains("quota", ignoreCase = true))
        }

        // Verify distinct intent outputs (no single repeated canned response across categories!)
        val uniqueOutputs = responses.toSet()
        assertTrue(
            "Expected at least 15 distinct responses across 21 unseen questions, got ${uniqueOutputs.size}",
            uniqueOutputs.size >= 15
        )

        // Spot-check domain accuracy on specific questions
        assertTrue(responses[0].contains("BTC") && responses[0].contains("ETH") && responses[0].contains("SOL"))
        assertTrue(responses[3].contains("SOLUSDT") && responses[3].contains("87/100"))
        assertTrue(responses[8].contains("Producer Price Index", ignoreCase = true))
        assertTrue(responses[10].contains("-0.0085%"))
        assertTrue(responses[11].contains("94500"))
        assertTrue(responses[12].contains("cascade", ignoreCase = true))
        assertTrue(responses[20].contains("Retry Online AI", ignoreCase = true))
    }

    @Test
    fun testOnlineGenerativePipeline_MultiTurnMemoryAndAssetDetection() = runBlocking {
        val capturedTurns = mutableListOf<Triple<String, List<Pair<String, String>>, String>>()
        val fakeClient = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = flow {
                capturedTurns.add(Triple(systemInstruction, history, userMessage))
                emit("Live grounded answer for: ")
                emit(userMessage.substringAfter("USER QUESTION:").trim())
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NexusAiRepository(context = context, aiClient = fakeClient)

        // Turn 1: BTC market move
        val btcCtx = buildRichContext(assetId = "BTC", assetName = "Bitcoin", symbol = "BTC/USDT", price = 88450.0, changePct = -3.85)
        val turn1Msg = "Why is BTC falling today?"
        var reply1: NexusChatMessage? = null
        repo.generateResponseStream(turn1Msg, btcCtx, LearningLanguageMode.ENGLISH, emptyList()).collect {
            if (it is NexusAiStreamResult.Completed) reply1 = it.message
        }
        assertNotNull(reply1)
        assertFalse(reply1!!.isOfflineEngine)
        assertTrue(capturedTurns[0].third.contains("88450.0"))
        assertTrue(capturedTurns[0].third.contains("Funding Rate: -0.0085%"))
        assertTrue(capturedTurns[0].third.contains("Open Interest: 94.5K contracts"))
        assertTrue(capturedTurns[0].first.contains("NEVER invent prices"))

        // Turn 2: Follow-up switching to ETH -> verify detected asset & preserved history!
        val ethDetected = MarketContextBuilder.detectMentionedAssetId("What about ETH support levels?", listOf())
        assertEquals("ETH", ethDetected)
        val ethCtx = buildRichContext(assetId = "ETH", assetName = "Ethereum", symbol = "ETH/USDT", price = 3120.0, changePct = -4.60)
        val history = listOf(
            NexusChatMessage(conversationId = "c1", sender = MessageSender.USER, text = turn1Msg),
            reply1!!.copy(conversationId = "c1")
        )
        var reply2: NexusChatMessage? = null
        repo.generateResponseStream("What about ETH support levels?", ethCtx, LearningLanguageMode.ENGLISH, history).collect {
            if (it is NexusAiStreamResult.Completed) reply2 = it.message
        }
        assertNotNull(reply2)
        assertEquals(2, capturedTurns[1].second.size)
        assertEquals("user", capturedTurns[1].second[0].first)
        assertEquals(turn1Msg, capturedTurns[1].second[0].second)
        assertEquals("model", capturedTurns[1].second[1].first)
        assertTrue(capturedTurns[1].third.contains("Ethereum (ETH/USDT)"))
        assertTrue(capturedTurns[1].third.contains("3120.0"))
    }

    @Test
    fun testOnlineGenerativePipeline_TwentyTwoUnseenQuestions_GroundedAndContextualized() = runBlocking {
        val unseenQueries = listOf(
            "Why is the crypto market falling today?" to LearningLanguageMode.ENGLISH,
            "What is causing BTC/ETH to move right now?" to LearningLanguageMode.ENGLISH,
            "Is this a good time to buy Bitcoin?" to LearningLanguageMode.ENGLISH,
            "Which watchlist coin has the strongest setup?" to LearningLanguageMode.ENGLISH,
            "Where are possible entry zones and invalidation for SOL?" to LearningLanguageMode.ENGLISH,
            "What are the support and resistance levels for ETH?" to LearningLanguageMode.ENGLISH,
            "Could the market fall further from here?" to LearningLanguageMode.ENGLISH,
            "Explain CPI in beginner-friendly language" to LearningLanguageMode.ENGLISH,
            "Explain PPI and why traders care" to LearningLanguageMode.ENGLISH,
            "How do Fed interest rate decisions affect crypto?" to LearningLanguageMode.ENGLISH,
            "What are perpetual funding rates?" to LearningLanguageMode.ENGLISH,
            "Explain open interest for beginners" to LearningLanguageMode.ENGLISH,
            "How do liquidations trigger sudden wicks?" to LearningLanguageMode.ENGLISH,
            "Compare 15m and 4h chart signals" to LearningLanguageMode.ENGLISH,
            "What if BTC breaks below $87,900?" to LearningLanguageMode.ENGLISH,
            "Aaj crypto market kyun gir rahi hai?" to LearningLanguageMode.ROMAN_URDU,
            "Kya ab ETH khareedna chahiye ya intezar karun?" to LearningLanguageMode.ROMAN_URDU,
            "Watchlist mein sab se strong setup kis coin ka hai?" to LearningLanguageMode.ROMAN_URDU,
            "Funding rate aur open interest asaan alfaaz mein samjhayein" to LearningLanguageMode.ROMAN_URDU,
            "کیا اس وقت بٹ کوائن خریدنا مناسب ہے؟" to LearningLanguageMode.STANDARD_URDU,
            "سی پی آئی اور فیڈ کے فیصلے کرپٹو پر کیسے اثر انداز ہوتے ہیں؟" to LearningLanguageMode.STANDARD_URDU,
            "Should I use 20x leverage on a breakout?" to LearningLanguageMode.ENGLISH
        )

        val seenUserPayloads = mutableListOf<String>()
        val seenSystemInstructions = mutableListOf<String>()
        val fakeModel = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = flow {
                seenSystemInstructions.add(systemInstruction)
                seenUserPayloads.add(userMessage)
                val qOnly = userMessage.substringAfter("USER QUESTION:").trim()
                emit("Grounded[$qOnly] | history=${history.size}")
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = NexusAiRepository(context = context, aiClient = fakeModel)
        val btcCtx = buildRichContext(assetId = "BTC", assetName = "Bitcoin", symbol = "BTC/USDT", price = 88450.0, changePct = -3.85)
        val ethCtx = buildRichContext(assetId = "ETH", assetName = "Ethereum", symbol = "ETH/USDT", price = 3120.0, changePct = -4.60)
        val solCtx = buildRichContext(assetId = "SOL", assetName = "Solana", symbol = "SOL/USDT", price = 198.40, changePct = +2.15)

        val history = mutableListOf<NexusChatMessage>()
        val outputs = mutableListOf<String>()

        unseenQueries.forEach { (query, lang) ->
            val detected = MarketContextBuilder.detectMentionedAssetId(query, emptyList())
            val ctx = when (detected) {
                "ETH" -> ethCtx
                "SOL" -> solCtx
                else -> btcCtx
            }
            var completed: NexusChatMessage? = null
            repo.generateResponseStream(query, ctx, lang, history).collect { res ->
                if (res is NexusAiStreamResult.Completed) completed = res.message
            }
            assertNotNull(completed)
            assertFalse(completed!!.isOfflineEngine)
            assertFalse(completed!!.isErrorFallback)
            outputs.add(completed!!.text)
            history.add(NexusChatMessage(conversationId = "t22", sender = MessageSender.USER, text = query))
            history.add(completed!!.copy(conversationId = "t22"))
        }

        assertEquals(22, outputs.toSet().size)
        assertEquals(22, seenUserPayloads.toSet().size)
        assertTrue(seenSystemInstructions.all { it.contains("NEVER guarantee profits") && it.contains("NEVER invent prices") })
        assertTrue(seenUserPayloads.all { it.contains("Data Status: LIVE") && it.contains("Watchlist & Scanner Top Setups") })
        // Check follow-up history caps cleanly at <= 16 entries and preserves prior context
        assertTrue(outputs.last().contains("history=16"))
    }

    @Test
    fun testQuotaExhaustionAndNetworkErrorsSurfaceExplicitErrorWithoutSilentFallback() = runBlocking {
        val quotaClient = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = flow {
                throw RuntimeException("429 RESOURCE_EXHAUSTED: Free tier quota exceeded")
            }
        }
        val offlineClient = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = flow {
                throw UnknownHostException("generativelanguage.googleapis.com")
            }
        }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val ctx = buildRichContext(isLive = false)

        val quotaRepo = NexusAiRepository(context = context, aiClient = quotaClient)
        val quotaEvents = mutableListOf<NexusAiStreamResult>()
        quotaRepo.generateResponseStream("Is this a good time to buy?", ctx, LearningLanguageMode.ENGLISH).collect {
            quotaEvents.add(it)
        }
        val quotaErr = quotaEvents.filterIsInstance<NexusAiStreamResult.Error>().singleOrNull()
        assertNotNull("Expected transparent Error event without silent fallback", quotaErr)
        assertEquals(0, quotaEvents.filterIsInstance<NexusAiStreamResult.Completed>().size)
        assertEquals("Is this a good time to buy?", quotaErr!!.failedQuery)
        assertTrue(quotaErr.isQuotaOrPaymentDisabled)
        assertTrue(quotaErr.errorMessage.contains("quota", ignoreCase = true))
        assertTrue(ctx.toPromptContext(LearningLanguageMode.ENGLISH).contains("UNAVAILABLE"))

        // Explicit user-triggered offline fallback works cleanly only when explicitly invoked
        val explicitOffline = quotaRepo.runOfflineRuleBasedAnalysis(
            userPrompt = quotaErr.failedQuery,
            contextSnapshot = quotaErr.contextSnapshot,
            languageMode = LearningLanguageMode.ENGLISH,
            unavailableReason = quotaErr.errorMessage
        )
        assertTrue(explicitOffline.isOfflineEngine)
        assertTrue(explicitOffline.isErrorFallback)
        assertEquals("Is this a good time to buy?", explicitOffline.originalQueryForRetry)

        val offlineRepo = NexusAiRepository(context = context, aiClient = offlineClient)
        var networkErr: NexusAiStreamResult.Error? = null
        offlineRepo.generateResponseStream("Explain funding rates", ctx, LearningLanguageMode.ENGLISH).collect {
            if (it is NexusAiStreamResult.Error) networkErr = it
        }
        assertNotNull(networkErr)
        assertTrue(networkErr!!.errorMessage.contains("internet", ignoreCase = true))
    }

    @Test
    fun testNexusAiScreen_ErrorBannerRetrySwitchAndExplicitOfflineButtons() {
        var directRetryCount = 0
        var offlineTriggered = false
        val switchedProviders = mutableListOf<com.example.data.ai.AiProviderType>()

        composeTestRule.setContent {
            NexusTradingTheme {
                NexusAiScreen(
                    messages = listOf(
                        NexusChatMessage(
                            id = "msg_user_1",
                            conversationId = "default_session",
                            sender = MessageSender.USER,
                            text = "Why is BTC falling?"
                        )
                    ),
                    conversations = listOf(NexusConversation(id = "default_session", title = "Market Analysis")),
                    currentConversationId = "default_session",
                    isLoading = false,
                    isOnlineAiAvailable = true,
                    lastAiError = "Firebase App Check attestation rejected (HTTP 403).",
                    dataFreshnessLabel = "LIVE • 00:30:00 UTC",
                    currentAssetSymbol = "BTC",
                    currentAssetPrice = 88450.0,
                    languageMode = LearningLanguageMode.ENGLISH,
                    selectedAiProvider = com.example.data.ai.AiProviderType.GEMINI_FIREBASE,
                    onSendMessage = {},
                    onRetryLastFailedQuery = { directRetryCount++ },
                    onRunOfflineFallback = { offlineTriggered = true },
                    onSelectAiProvider = { switchedProviders.add(it) },
                    onDismissError = {},
                    onNewChat = {},
                    onClearChat = {},
                    onDeleteConversation = {},
                    onSelectConversation = {},
                    onAttachCurrentMarket = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("ai_error_banner").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ai_banner_retry_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ai_switch_provider_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ai_explicit_offline_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Online AI Runtime Error • Retry or Switch").assertIsDisplayed()
        composeTestRule.onNodeWithTag("ai_banner_retry_button").performClick()
        composeTestRule.onNodeWithTag("ai_switch_provider_button").performClick()
        composeTestRule.onNodeWithTag("ai_explicit_offline_button").performClick()
        assertEquals(1, directRetryCount)
        assertTrue(offlineTriggered)
        assertEquals(listOf(com.example.data.ai.AiProviderType.DEEPSEEK), switchedProviders)
    }
}
