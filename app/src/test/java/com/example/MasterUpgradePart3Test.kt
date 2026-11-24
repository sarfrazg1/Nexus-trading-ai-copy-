package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiProviderAvailability
import com.example.data.ai.AiProviderException
import com.example.data.ai.AiProviderStatus
import com.example.data.ai.AiProviderType
import com.example.data.ai.DeepSeekAiProvider
import com.example.data.ai.MarketContextBuilder
import com.example.data.ai.OfflineTradingAnalysisEngine
import com.example.data.ai.OnlineAiProvider
import com.example.data.learning.LearningLanguageMode
import com.example.data.model.ArticleContentStatus
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.data.news.ArticleContentFetcher
import com.example.data.news.NewsImpactAnalyzer
import com.example.data.news.NewsLanguageOption
import com.example.data.news.NewsTranslationService
import com.example.data.providers.formatPktTimestamp
import com.example.data.scanner.DayTraderSkillId
import com.example.data.scanner.SkillAdaptationRegistry
import com.example.data.scanner.SkillAdaptationStatus
import com.example.data.signal.AiTradingSignalEngine
import com.example.util.TimeZoneUtils
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetSocketAddress
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MasterUpgradePart3Test {

    @Test
    fun timezoneStandardizationUsesAsiaKarachiEvenIfDeviceTimezoneDiffers() = runBlocking {
        val originalTz = TimeZone.getDefault()
        try {
            // Simulate device manually set to New York (UTC-5 / UTC-4)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            // 2026-11-24 12:00:00 UTC -> 17:00:00 PKT (UTC+5)
            val utcNoonMs = 1795521600000L

            val formattedFull = TimeZoneUtils.formatKarachiTimestamp(utcNoonMs)
            val formattedSec = TimeZoneUtils.formatKarachiWithSeconds(utcNoonMs)
            val formattedCompact = TimeZoneUtils.formatKarachiCompact(utcNoonMs)
            val providerPkt = formatPktTimestamp(utcNoonMs)

            assertTrue("Expected PKT marker in '$formattedFull'", formattedFull.contains("PKT"))
            assertTrue("Expected 05:00:00 PM PKT in '$formattedFull'", formattedFull.contains("05:00:00 PM"))
            assertTrue("Expected 17:00:00 PKT in '$formattedSec'", formattedSec.contains("17:00:00"))
            assertTrue("Expected PKT in compact '$formattedCompact'", formattedCompact.contains("PKT"))
            assertTrue("Expected PKT in provider format '$providerPkt'", providerPkt.contains("PKT"))

            // Verify AiTradingSignalEngine uses PKT timestamp
            val candles = (0 until 40).map { idx ->
                val base = 90000.0 + idx * 50.0
                Candle(
                    openTime = utcNoonMs - (40 - idx) * 3_600_000L,
                    open = base,
                    high = base + 80.0,
                    low = base - 40.0,
                    close = base + 30.0,
                    volume = 1200.0 + idx * 20.0,
                    closeTime = utcNoonMs - (39 - idx) * 3_600_000L
                )
            }
            val sig = AiTradingSignalEngine.generateSignal(
                symbol = "BTC",
                assetName = "Bitcoin",
                currentPrice = 92000.0,
                dataStatus = MarketDataStatus.LIVE,
                dataSourceName = "Binance Futures",
                lastUpdatedTime = utcNoonMs,
                primaryTimeframe = CandleTimeframe.H1,
                candles = candles,
                technicalAnalysis = null,
                smcAnalysis = null
            )
            assertTrue("Signal timestamp must be PKT: ${sig.formattedTimestamp}", sig.formattedTimestamp.contains("PKT"))
        } finally {
            TimeZone.setDefault(originalTz)
        }
    }

    @Test
    fun articleContentFetcherExtractsCompleteVsPaywallAndShortSummary() {
        val fetcher = ArticleContentFetcher()
        val fullHtml = """
            <html>
              <head><script>var bad = true;</script></head>
              <body>
                <nav>Navigation links that should be stripped</nav>
                <article>
                  <p>The Federal Reserve held its benchmark interest rate steady at 5.25% to 5.50% during Wednesday's FOMC meeting while noting inflation progress remains uneven.</p>
                  <p>Spot Bitcoin ETF net inflows reached $420M across major US issuers, driving total institutional custody assets above $58B according to market filings.</p>
                  <p>Binance USDT-M perpetual funding rates adjusted from +0.018% to +0.010% as leveraged positions consolidated near the $92,500 liquidity pocket.</p>
                </article>
                <footer>All rights reserved. Cookie Policy.</footer>
              </body>
            </html>
        """.trimIndent()

        val complete = fetcher.extractFromHtml(
            url = "https://example.com/fed-btc-article",
            rawHtml = fullHtml,
            fallbackSummary = "Short fallback summary."
        )
        assertEquals(ArticleContentStatus.COMPLETE, complete.contentStatus)
        assertTrue(complete.extractedText.contains("5.25%"))
        assertTrue(complete.extractedText.contains("$420M"))
        assertFalse(complete.extractedText.contains("Navigation links"))
        assertFalse(complete.extractedText.contains("Cookie Policy"))

        val paywallHtml = """
            <html><body>
              <p>Bitcoin rose 3.2% ahead of the CPI print today in early Asia trading.</p>
              <p>Subscribe to continue reading the full institutional analysis on this topic.</p>
            </body></html>
        """.trimIndent()
        val partial = fetcher.extractFromHtml(
            url = "https://example.com/paywalled",
            rawHtml = paywallHtml,
            fallbackSummary = "Verified headline summary only."
        )
        assertEquals(ArticleContentStatus.PARTIAL_SUMMARY_ONLY, partial.contentStatus)
        assertTrue(partial.fetchNote.contains("partial/summary only", ignoreCase = true))

        val tinyHtml = "<html><body><p>Brief market note.</p></body></html>"
        val summaryOnly = fetcher.extractFromHtml(
            url = "https://example.com/short",
            rawHtml = tinyHtml,
            fallbackSummary = "Verified GDELT dispatch metadata only."
        )
        assertEquals(ArticleContentStatus.PARTIAL_SUMMARY_ONLY, summaryOnly.contentStatus)
        assertTrue(summaryOnly.extractedText.contains("Verified GDELT dispatch metadata only."))
    }

    @Test
    fun newsImpactAnalyzerSeparatesConfirmedFactsBtcAltcoinImpactScenariosAndRisks() {
        val raw = MarketNews(
            id = "n_fed_1",
            title = "Federal Reserve FOMC holds interest rate steady at 5.25% as CPI cools to 2.9%",
            summary = "Spot Bitcoin ETF net inflows increased by $310M following the release.",
            source = "Reuters Dispatch",
            timestamp = "24 Nov 2026, 17:00:00 PKT",
            impact = NewsImpact.NEUTRAL,
            impactLevel = NewsImpactLevel.MEDIUM,
            category = "Macro",
            url = "https://example.com/news/fed",
            fullContent = "Reported FOMC statement held rates at 5.25%."
        )
        val enriched = NewsImpactAnalyzer.enrichNewsItem(raw)

        assertTrue(enriched.confirmedFacts.any { it.contains("Reuters Dispatch") })
        assertTrue(enriched.confirmedFacts.any { it.contains("PKT") })
        assertTrue(enriched.aiInterpretation.contains("Probabilistic", ignoreCase = true))
        assertTrue(enriched.btcImpactSummary.contains("BTC", ignoreCase = true))
        assertTrue(
            enriched.altcoinImpactSummary.contains("alt", ignoreCase = true) ||
                enriched.altcoinImpactSummary.contains("ETH/SOL", ignoreCase = true)
        )
        assertTrue(enriched.shortTermScenario.isNotBlank())
        assertTrue(enriched.longTermScenario.isNotBlank())
        assertTrue(enriched.uncertaintyAndRisks.contains("not proven causes", ignoreCase = true))
    }

    @Test
    fun newsTranslationServicePreservesNumbersTickersDatesAcrossRomanUrduAndUrduScriptPlusCache() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val offlineProvider = object : OnlineAiProvider {
            override val providerType: AiProviderType = AiProviderType.GEMINI_FIREBASE
            override fun isConfigured(): Boolean = false
            override fun getStatus(): AiProviderStatus = AiProviderStatus(
                provider = AiProviderType.GEMINI_FIREBASE,
                availability = AiProviderAvailability.UNCONFIGURED,
                detail = "Offline unit test"
            )
            override fun streamChat(systemInstruction: String, history: List<Pair<String, String>>, userMessage: String): Flow<String> = flow {}
        }
        val unconfiguredDeepSeek = object : OnlineAiProvider {
            override val providerType: AiProviderType = AiProviderType.DEEPSEEK
            override fun isConfigured(): Boolean = false
            override fun getStatus(): AiProviderStatus = AiProviderStatus(
                provider = AiProviderType.DEEPSEEK,
                availability = AiProviderAvailability.UNCONFIGURED,
                detail = "Unconfigured"
            )
            override fun streamChat(systemInstruction: String, history: List<Pair<String, String>>, userMessage: String): Flow<String> = flow {}
        }

        val service = NewsTranslationService(context, offlineProvider, unconfiguredDeepSeek)
        service.clearCache()

        val rawNews = NewsImpactAnalyzer.enrichNewsItem(
            MarketNews(
                id = "test_cpi_99",
                title = "Federal Reserve Interest Rate stays 5.25% after CPI 2.8% as Bitcoin ETF inflow hits $450M on 24 Nov 2026",
                summary = "BTC and ETH traders monitor FOMC liquidity.",
                source = "Reuters",
                timestamp = "24 Nov 2026, 17:00:00 PKT",
                impact = NewsImpact.BULLISH,
                impactLevel = NewsImpactLevel.HIGH,
                category = "Macro",
                url = "https://example.com/cpi",
                fullContent = "Bitcoin ETF inflow totaled $450M while CPI measured 2.8% and Interest Rate stayed at 5.25%."
            )
        )

        val roman = service.translateArticle(rawNews, NewsLanguageOption.ROMAN_URDU)
        assertFalse(roman.fromCache)
        assertTrue(roman.body.contains("5.25%"))
        assertTrue(roman.body.contains("2.8%"))
        assertTrue(roman.body.contains("$450M"))
        assertTrue(roman.btcImpact.contains("BTC", ignoreCase = true))

        val romanCached = service.translateArticle(rawNews, NewsLanguageOption.ROMAN_URDU)
        assertTrue("Second call should load from cache", romanCached.fromCache)

        val urdu = service.translateArticle(rawNews, NewsLanguageOption.URDU_SCRIPT)
        assertTrue(urdu.title.any { it in '\u0600'..'\u06FF' })
        assertTrue(urdu.body.contains("5.25%"))
        assertTrue(urdu.body.contains("$450M"))
        assertTrue(urdu.uncertaintyAndRisks.any { it in '\u0600'..'\u06FF' })
    }

    @Test
    fun newsTranslationServiceOnlineJsonAndForceRetryBypassCache() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val callCounter = AtomicInteger(0)
        val fakeOnlineProvider = object : OnlineAiProvider {
            override val providerType: AiProviderType = AiProviderType.GEMINI_FIREBASE
            override fun isConfigured(): Boolean = true
            override fun getStatus(): AiProviderStatus = AiProviderStatus(
                provider = AiProviderType.GEMINI_FIREBASE,
                availability = AiProviderAvailability.READY,
                detail = "Fake Online"
            )
            override fun streamChat(systemInstruction: String, history: List<Pair<String, String>>, userMessage: String): Flow<String> = flow {
                val n = callCounter.incrementAndGet()
                emit(
                    """
                    {
                      "title": "BTC ETF mein $500M inflow (Attempt #$n)",
                      "body": "Bitcoin ETF mein $500M sarmaya dakhil hua aur 4.75% rate par market stable rahi.",
                      "confirmedFacts": ["Source: Bloomberg | 24 Nov 2026 PKT"],
                      "aiInterpretation": "Bullish imkani ishara",
                      "btcImpact": "BTC $92,000 support hold kare to momentum barh sakta hai",
                      "altcoinImpact": "ETH aur SOL mein 2x tez harkat mumkin hai",
                      "shortTermScenario": "15m candle close ke baad breakout dekhein",
                      "longTermScenario": "Institutional inflow trend ko support karta hai",
                      "uncertaintyAndRisks": "High leverage par liquidation risk rehta hai"
                    }
                    """.trimIndent()
                )
            }
        }
        val unconfigured = object : OnlineAiProvider {
            override val providerType: AiProviderType = AiProviderType.DEEPSEEK
            override fun isConfigured(): Boolean = false
            override fun getStatus(): AiProviderStatus = AiProviderStatus(
                provider = AiProviderType.DEEPSEEK,
                availability = AiProviderAvailability.UNCONFIGURED,
                detail = "Off"
            )
            override fun streamChat(systemInstruction: String, history: List<Pair<String, String>>, userMessage: String): Flow<String> = flow {}
        }

        val service = NewsTranslationService(context, fakeOnlineProvider, unconfigured)
        service.clearCache()

        val news = MarketNews(
            id = "online_trans_1",
            title = "Bitcoin ETF records $500M inflow at 4.75% yield",
            summary = "BTC institutional inflows.",
            source = "Bloomberg",
            timestamp = "24 Nov 2026, 17:00:00 PKT",
            impact = NewsImpact.BULLISH,
            impactLevel = NewsImpactLevel.HIGH,
            category = "ETF",
            url = "https://example.com/etf",
            fullContent = "Bitcoin ETF saw $500M inflow while yields held at 4.75%."
        )

        val first = service.translateArticle(news, NewsLanguageOption.ROMAN_URDU, forceRetry = false)
        assertFalse(first.fromCache)
        assertEquals(1, callCounter.get())
        assertTrue(first.title.contains("Attempt #1"))

        val secondCached = service.translateArticle(news, NewsLanguageOption.ROMAN_URDU, forceRetry = false)
        assertTrue(secondCached.fromCache)
        assertEquals(1, callCounter.get())

        val retried = service.translateArticle(news, NewsLanguageOption.ROMAN_URDU, forceRetry = true)
        assertFalse(retried.fromCache)
        assertEquals(2, callCounter.get())
        assertTrue(retried.title.contains("Attempt #2"))
    }

    @Test
    fun marketContextBuilderDetectsMultiAssetsInOrderAndRendersWorkflowDisclosures() {
        val assets = listOf(
            MarketAsset(id = "BTC", name = "Bitcoin", symbol = "BTC/USDT", category = AssetCategory.CRYPTO, currentPrice = 91500.0, change24hPercent = -1.2, dataStatus = MarketDataStatus.LIVE),
            MarketAsset(id = "ETH", name = "Ethereum", symbol = "ETH/USDT", category = AssetCategory.CRYPTO, currentPrice = 3150.0, change24hPercent = 2.4, dataStatus = MarketDataStatus.LIVE),
            MarketAsset(id = "SOL", name = "Solana", symbol = "SOL/USDT", category = AssetCategory.CRYPTO, currentPrice = 210.0, change24hPercent = 3.1, dataStatus = MarketDataStatus.LIVE)
        )

        val detected = MarketContextBuilder.detectMentionedAssets("Compare BTC and ETH funding rate", assets)
        assertEquals(listOf("BTC", "ETH"), detected)

        val snapshot = MarketContextBuilder.buildSnapshot(
            asset = assets[0],
            candles = emptyList(),
            timeframe = CandleTimeframe.M15,
            allAssets = assets,
            comparisonAssetSummary = "Ethereum (ETH/USDT) | Price: $3150.00 (+2.40%) | RSI(14): 58.2",
            workflowContextSummary = "Risk Guard Decision: PASS | Time Machine Samples=12 | Monte Carlo 10000 runs"
        )
        val promptText = snapshot.toPromptContext(LearningLanguageMode.ENGLISH)
        assertTrue(promptText.contains("Secondary / Comparison Asset Snapshot"))
        assertTrue(promptText.contains("Ethereum (ETH/USDT)"))
        assertTrue(promptText.contains("Trade Workflow, Time Machine Backtest, Monte Carlo & Risk Guard"))
        assertTrue(promptText.contains("Explicitly Unavailable / Unverified Fields"))
    }

    @Test
    fun offlineTradingAnalysisEngineHandlesCompareWorkflowAndRomanUrduLongQueries() {
        val btcAsset = MarketAsset(id = "BTC", name = "Bitcoin", symbol = "BTC/USDT", category = AssetCategory.CRYPTO, currentPrice = 91500.0, change24hPercent = 1.5, dataStatus = MarketDataStatus.LIVE)
        val snapshot = MarketContextBuilder.buildSnapshot(
            asset = btcAsset,
            candles = emptyList(),
            timeframe = CandleTimeframe.M15,
            comparisonAssetSummary = "Ethereum (ETH/USDT) | Price: $3150.00 (+2.40%) | 15m RSI(14): 58.0",
            workflowContextSummary = "Budget Balance=1000 USDT | Risk Guard=PASS | Time Machine: Conservative 58.3% (12 samples)"
        )

        val compareReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "Compare BTC and ETH",
            context = snapshot,
            language = LearningLanguageMode.ENGLISH
        )
        assertTrue(compareReply.contains("BTC/USDT"))
        assertTrue(compareReply.contains("Ethereum (ETH/USDT)"))

        val workflowReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "Check Time Machine backtest and Risk Guard",
            context = snapshot,
            language = LearningLanguageMode.ENGLISH
        )
        assertTrue(workflowReply.contains("Time Machine"))
        assertTrue(workflowReply.contains("Conservative 58.3%"))

        val romanLongReply = OfflineTradingAnalysisEngine.generateAnalysis(
            query = "Kya abhi LONG lena chahiye?",
            context = snapshot,
            language = LearningLanguageMode.ROMAN_URDU
        )
        assertTrue(romanLongReply.contains("BTC") && romanLongReply.contains("support", ignoreCase = true))
        assertTrue(romanLongReply.contains("guarantee nahi", ignoreCase = true))
    }

    @Test
    fun deepSeekAiProviderStreamsRealSseChunksAndEnforcesHttp402AndHttp429() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val mode = AtomicInteger(0)
        server.createContext("/chat/completions") { exchange ->
            val currentMode = mode.get()
            when (currentMode) {
                0 -> {
                    val sse = """
                        data: {"id":"1","choices":[{"delta":{"role":"assistant","content":null}}]}

                        data: {"id":"1","choices":[{"delta":{"content":"BTC holding "}}]}

                        data: {"id":"1","choices":[{"delta":{"content":"$91,500 support."}}]}

                        data: [DONE]
                    """.trimIndent().toByteArray()
                    exchange.responseHeaders.add("Content-Type", "text/event-stream")
                    exchange.sendResponseHeaders(200, sse.size.toLong())
                    exchange.responseBody.use { it.write(sse) }
                }
                1 -> {
                    val err = """{"error":{"message":"Rate limit exceeded"}}""".toByteArray()
                    exchange.responseHeaders.add("Retry-After", "45")
                    exchange.sendResponseHeaders(429, err.size.toLong())
                    exchange.responseBody.use { it.write(err) }
                }
                else -> {
                    val err = """{"error":{"message":"Insufficient Balance"}}""".toByteArray()
                    exchange.sendResponseHeaders(402, err.size.toLong())
                    exchange.responseBody.use { it.write(err) }
                }
            }
        }
        server.start()
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            var fakeClock = 1_000_000L
            val provider = DeepSeekAiProvider(
                baseUrl = baseUrl,
                apiKeyRaw = "test_deepseek_key_abc123",
                clockMillis = { fakeClock }
            )

            // 1. Real SSE streaming chunks
            val chunks = provider.streamChat("sys", emptyList(), "Analyze BTC").toList()
            assertEquals(listOf("BTC holding ", "$91,500 support."), chunks)
            assertEquals(AiProviderAvailability.READY, provider.getStatus().availability)

            // 2. HTTP 429 cooldown
            mode.set(1)
            var got429 = false
            try {
                provider.streamChat("sys", emptyList(), "Analyze BTC").toList()
            } catch (e: AiProviderException) {
                got429 = e.isQuotaExhausted && e.httpCode == 429
            }
            assertTrue(got429)
            assertEquals(AiProviderAvailability.COOLDOWN_RATE_LIMIT, provider.getStatus().availability)
            assertFalse(provider.isConfigured())

            // Advance past cooldown and test HTTP 402 permanent auto-disablement
            fakeClock += 50_000L
            mode.set(2)
            var got402 = false
            try {
                provider.streamChat("sys", emptyList(), "Analyze BTC").toList()
            } catch (e: AiProviderException) {
                got402 = e.isPaymentRequired && e.httpCode == 402
            }
            assertTrue(got402)
            assertEquals(AiProviderAvailability.DISABLED_PAYMENT_REQUIRED, provider.getStatus().availability)
            assertFalse(provider.isConfigured())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun skillAdaptationRegistryDocumentsAll11SkillsIncludingEarningsAnalystAndPelosiTracker() {
        val all = SkillAdaptationRegistry.allAdaptedSkills
        assertEquals(11, all.size)

        val earnings = SkillAdaptationRegistry.getSkill(DayTraderSkillId.EARNINGS_ANALYST)
        assertEquals(SkillAdaptationStatus.REQUIRES_EXTERNAL_CONFIG, earnings.status)
        assertTrue(earnings.honestDisclosure.contains("no synthetic", ignoreCase = true) || earnings.honestDisclosure.contains("external", ignoreCase = true))

        val pelosi = SkillAdaptationRegistry.getSkill(DayTraderSkillId.PELOSI_TRACKER)
        assertEquals(SkillAdaptationStatus.NOT_APPLICABLE, pelosi.status)
        assertTrue(pelosi.honestDisclosure.contains("Not applicable", ignoreCase = true))
        assertTrue(pelosi.honestDisclosure.contains("never simulated", ignoreCase = true))

        val activeCount = all.count { it.status == SkillAdaptationStatus.ACTIVE_ADAPTED }
        assertEquals(9, activeCount)
    }
}
