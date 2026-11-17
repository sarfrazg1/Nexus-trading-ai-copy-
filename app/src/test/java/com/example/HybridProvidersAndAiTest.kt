package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.ai.AiProviderAvailability
import com.example.data.ai.AiProviderException
import com.example.data.ai.AiProviderType
import com.example.data.ai.DeepSeekAiProvider
import com.example.data.ai.FirebaseChatHistorySanitizer
import com.example.data.ai.GenerativeAiClient
import com.example.data.ai.MarketContextBuilder
import com.example.data.ai.MessageSender
import com.example.data.ai.NexusAiRepository
import com.example.data.ai.NexusAiStreamResult
import com.example.data.ai.NexusChatMessage
import com.example.data.learning.LearningLanguageMode
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.providers.AlternativeMeFearGreedProvider
import com.example.data.providers.BinanceFuturesPrimaryProvider
import com.example.data.providers.BybitV5PublicProvider
import com.example.data.providers.CoinGeckoDemoProvider
import com.example.data.providers.CrossExchangeValidator
import com.example.data.providers.DefiLlamaProvider
import com.example.data.providers.GdeltNewsProvider
import com.example.data.providers.HybridMarketAggregator
import com.example.data.providers.MarketInstrumentType
import com.example.data.providers.NewsReliabilityTier
import com.example.data.providers.NormalizedMarketQuote
import com.example.data.providers.ProviderHealthStatus
import com.example.data.providers.ProviderHttpSupport
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HybridProvidersAndAiTest {

    private fun startMockServer(handler: (path: String, query: String, headers: Map<String, String>) -> Triple<Int, Map<String, String>, String>): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path ?: "/"
            val query = exchange.requestURI.rawQuery ?: ""
            val reqHeaders = exchange.requestHeaders.entries.associate { it.key.lowercase(Locale.US) to it.value.joinToString(",") }
            val (code, respHeaders, body) = handler(path, query, reqHeaders)
            respHeaders.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test
    fun testBinanceFuturesPrimaryProvider_QuotesDerivativesCacheAnd429() = runBlocking {
        var nowMs = 1_750_000_000_000L
        val calls = AtomicInteger(0)
        val return429 = AtomicInteger(0)

        val server = startMockServer { path, _, _ ->
            calls.incrementAndGet()
            if (return429.get() == 1) {
                return@startMockServer Triple(429, mapOf("Retry-After" to "30"), """{"code":-1003,"msg":"Too many requests"}""")
            }
            when {
                path == "/fapi/v1/ticker/24hr" -> Triple(
                    200,
                    emptyMap(),
                    """[{"symbol":"BTCUSDT","lastPrice":"89100.50","priceChangePercent":"-2.45","highPrice":"92000.0","lowPrice":"88500.0","volume":"120500.0","quoteVolume":"10700000000.0","closeTime":$nowMs}]"""
                )
                path == "/fapi/v1/premiumIndex" -> Triple(
                    200,
                    emptyMap(),
                    """[{"symbol":"BTCUSDT","markPrice":"89110.00","indexPrice":"89120.00","lastFundingRate":"0.00015","time":$nowMs}]"""
                )
                path == "/fapi/v1/openInterest" -> Triple(
                    200,
                    emptyMap(),
                    """{"symbol":"BTCUSDT","openInterest":"84500.25","time":$nowMs}"""
                )
                path == "/futures/data/globalLongShortAccountRatio" -> Triple(
                    200,
                    emptyMap(),
                    """[{"symbol":"BTCUSDT","longShortRatio":"1.34","timestamp":$nowMs}]"""
                )
                path == "/fapi/v1/klines" -> Triple(
                    200,
                    emptyMap(),
                    """[[${nowMs - 3600000},"89500.0","89800.0","88900.0","89100.5","1500.0",$nowMs,"133000000.0",5000,"700.0","62000000.0"]]"""
                )
                else -> Triple(404, emptyMap(), "{}")
            }
        }

        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val http = ProviderHttpSupport(clockMillis = { nowMs })
            val provider = BinanceFuturesPrimaryProvider(baseUrl = baseUrl, http = http, clockMillis = { nowMs })

            val res1 = provider.fetchFuturesQuotes(listOf("BTCUSDT"))
            assertEquals(MarketDataStatus.LIVE, res1.metadata.dataStatus)
            assertEquals(ProviderHealthStatus.ACTIVE, res1.metadata.health)
            val btc = res1.data!!["BTCUSDT"]!!
            assertEquals(MarketInstrumentType.FUTURES_PERPETUAL, btc.marketType)
            assertEquals(89100.50, btc.lastPrice, 0.01)
            assertEquals(0.015, btc.fundingRatePercent!!, 0.0001)

            val callsAfterFirst = calls.get()
            // Within 12s TTL cache -> should serve from cache without new HTTP call
            nowMs += 3_000L
            val resCached = provider.fetchFuturesQuotes(listOf("BTCUSDT"))
            assertTrue(resCached.metadata.fromCache)
            assertEquals(callsAfterFirst, calls.get())

            // Derivatives + Klines
            val deriv = provider.fetchDerivativesForSymbol("BTCUSDT")
            assertNotNull(deriv.data)
            assertEquals(84500.25, deriv.data!!.openInterestContracts!!, 0.01)
            assertEquals(1.34, deriv.data!!.longShortRatio!!, 0.01)

            val klines = provider.fetchFuturesCandles("BTCUSDT", CandleTimeframe.H1, 1)
            assertEquals(1, klines.data!!.size)

            // Expire cache and trigger 429 -> should serve STALE cache gracefully with RATE_LIMITED health
            nowMs += 30_000L
            return429.set(1)
            val rateLimited = provider.fetchFuturesQuotes(listOf("BTCUSDT"))
            assertEquals(MarketDataStatus.STALE, rateLimited.metadata.dataStatus)
            assertEquals(ProviderHealthStatus.RATE_LIMITED, rateLimited.metadata.health)
            assertNotNull(rateLimited.data)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testBybitV5PublicProvider_LinearVsSpotAndRateLimit10006() = runBlocking {
        var nowMs = 1_750_000_000_000L
        val mode = AtomicInteger(0)
        val server = startMockServer { _, query, _ ->
            if (mode.get() == 1) {
                return@startMockServer Triple(200, emptyMap(), """{"retCode":10006,"retMsg":"Too many visits"}""")
            }
            if (query.contains("category=linear")) {
                Triple(
                    200, emptyMap(),
                    """{"retCode":0,"retMsg":"OK","time":$nowMs,"result":{"list":[{"symbol":"BTCUSDT","lastPrice":"89115.0","price24hPcnt":"-0.023","highPrice24h":"92010","lowPrice24h":"88480","volume24h":"55000","turnover24h":"4900000000","fundingRate":"0.00012","openInterest":"42000"}]}}"""
                )
            } else {
                Triple(
                    200, emptyMap(),
                    """{"retCode":0,"retMsg":"OK","time":$nowMs,"result":{"list":[{"symbol":"BTCUSDT","lastPrice":"89040.0","price24hPcnt":"-0.021","highPrice24h":"91900","lowPrice24h":"88400","volume24h":"18000","turnover24h":"1600000000"}]}}"""
                )
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val http = ProviderHttpSupport(clockMillis = { nowMs })
            val provider = BybitV5PublicProvider(baseUrl = baseUrl, http = http, clockMillis = { nowMs })

            val linear = provider.fetchLinearFuturesQuotes(setOf("BTCUSDT"))
            assertEquals(MarketInstrumentType.FUTURES_PERPETUAL, linear.data!!["BTCUSDT"]!!.marketType)
            assertEquals(89115.0, linear.data!!["BTCUSDT"]!!.lastPrice, 0.01)

            val spot = provider.fetchSpotQuotes(setOf("BTCUSDT"))
            assertEquals(MarketInstrumentType.SPOT, spot.data!!["BTCUSDT"]!!.marketType)
            assertNull(spot.data!!["BTCUSDT"]!!.fundingRatePercent)

            nowMs += 30_000L
            mode.set(1)
            val rateLimited = provider.fetchLinearFuturesQuotes(setOf("BTCUSDT"))
            assertNull(rateLimited.data)
            assertEquals(ProviderHealthStatus.RATE_LIMITED, rateLimited.metadata.health)
            assertTrue(rateLimited.metadata.rateLimitNote!!.contains("10006"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testCoinGeckoDemoProvider_OptionalKeyHeaderAnd402ZeroCostDisablement() = runBlocking {
        var nowMs = 1_750_000_000_000L
        var capturedDemoHeader: String? = null
        val codeToReturn = AtomicInteger(200)

        val server = startMockServer { path, _, headers ->
            capturedDemoHeader = headers["x-cg-demo-api-key"]
            if (codeToReturn.get() == 402) {
                return@startMockServer Triple(402, emptyMap(), """{"error":"Payment Required"}""")
            }
            when (path) {
                "/api/v3/global" -> Triple(
                    200, emptyMap(),
                    """{"data":{"active_cryptocurrencies":14500,"total_market_cap":{"usd":2950000000000.0},"total_volume":{"usd":115000000000.0},"market_cap_percentage":{"btc":58.4,"eth":12.1}}}"""
                )
                "/api/v3/coins/markets" -> Triple(
                    200, emptyMap(),
                    """[{"id":"bitcoin","symbol":"btc","name":"Bitcoin","current_price":89050.0,"market_cap":1760000000000.0,"market_cap_rank":1,"total_volume":45000000000.0,"price_change_percentage_24h":-2.2,"price_change_percentage_7d_in_currency":4.8}]"""
                )
                else -> Triple(200, emptyMap(), """{"prices":[[1750000000000,89050.0]]}""")
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val http = ProviderHttpSupport(clockMillis = { nowMs })

            // 1. Placeholder key must NOT be transmitted
            val noKeyProvider = CoinGeckoDemoProvider(
                baseUrl = baseUrl,
                demoApiKeyRaw = CoinGeckoDemoProvider.KEY_PLACEHOLDER,
                http = http,
                clockMillis = { nowMs }
            )
            val snap = noKeyProvider.fetchGlobalAndTopRankings()
            assertNull(capturedDemoHeader)
            assertNotNull(snap.data)
            assertEquals(58.4, snap.data!!.btcDominancePercent, 0.01)
            assertEquals(4.8, snap.data!!.btc7dChangePercent!!, 0.01)
            assertEquals(MarketInstrumentType.SPOT, snap.data!!.topRankedCoins.first().marketType)

            // 2. Real key IS transmitted via x-cg-demo-api-key; HTTP 402 auto-disables provider
            val keyedProvider = CoinGeckoDemoProvider(
                baseUrl = baseUrl,
                demoApiKeyRaw = "CG-DEMO-TEST-123",
                http = ProviderHttpSupport(clockMillis = { nowMs }),
                clockMillis = { nowMs }
            )
            keyedProvider.fetchGlobalAndTopRankings()
            assertEquals("CG-DEMO-TEST-123", capturedDemoHeader)

            nowMs += 100_000L
            codeToReturn.set(402)
            val disabledCached = keyedProvider.fetchGlobalAndTopRankings()
            assertEquals(ProviderHealthStatus.PAYMENT_REQUIRED_DISABLED, disabledCached.metadata.health)
            assertEquals(MarketDataStatus.STALE, disabledCached.metadata.dataStatus)

            val freshKeyed402 = CoinGeckoDemoProvider(
                baseUrl = baseUrl,
                demoApiKeyRaw = "CG-DEMO-TEST-123",
                http = ProviderHttpSupport(clockMillis = { nowMs }),
                clockMillis = { nowMs }
            )
            val disabledUncached = freshKeyed402.fetchGlobalAndTopRankings()
            assertEquals(ProviderHealthStatus.PAYMENT_REQUIRED_DISABLED, disabledUncached.metadata.health)
            assertEquals(MarketDataStatus.UNAVAILABLE, disabledUncached.metadata.dataStatus)
            assertNull(disabledUncached.data)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testDefiLlamaAndAlternativeMeProviders() = runBlocking {
        val nowMs = 1_750_000_000_000L
        val server = startMockServer { path, _, _ ->
            when (path) {
                "/v2/chains" -> Triple(
                    200, emptyMap(),
                    """[{"name":"Ethereum","tvl":65000000000.0},{"name":"Solana","tvl":12000000000.0},{"name":"BSC","tvl":6000000000.0}]"""
                )
                "/stablecoins" -> Triple(
                    200, emptyMap(),
                    """{"peggedAssets":[{"symbol":"USDT","circulating":{"peggedUSD":140000000000.0}},{"symbol":"USDC","circulating":{"peggedUSD":60000000000.0}}]}"""
                )
                "/fng/" -> Triple(
                    200, emptyMap(),
                    """{"data":[{"value":"32","value_classification":"Fear","timestamp":"1750000000"},{"value":"35","value_classification":"Fear","timestamp":"1749913600"},{"value":"38","value_classification":"Fear","timestamp":"1749827200"},{"value":"40","value_classification":"Fear","timestamp":"1749740800"},{"value":"44","value_classification":"Fear","timestamp":"1749654400"},{"value":"48","value_classification":"Neutral","timestamp":"1749568000"},{"value":"50","value_classification":"Neutral","timestamp":"1749481600"}]}"""
                )
                else -> Triple(404, emptyMap(), "{}")
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val http = ProviderHttpSupport(clockMillis = { nowMs })
            val llama = DefiLlamaProvider(llamaBaseUrl = baseUrl, stablecoinsBaseUrl = baseUrl, http = http, clockMillis = { nowMs })
            val fng = AlternativeMeFearGreedProvider(baseUrl = baseUrl, http = http, clockMillis = { nowMs })

            val defiRes = llama.fetchDefiAndStablecoins()
            assertNotNull(defiRes.data)
            assertEquals(83_000_000_000.0, defiRes.data!!.totalTvlUsd, 1.0)
            assertEquals("Ethereum", defiRes.data!!.topChains.first().chainName)
            assertEquals(200_000_000_000.0, defiRes.data!!.totalStablecoinSupplyUsd, 1.0)
            assertEquals(70.0, defiRes.data!!.usdtDominancePercent!!, 0.01)
            assertEquals(30.0, defiRes.data!!.usdcDominancePercent!!, 0.01)

            val fngRes = fng.fetchFearAndGreed()
            assertNotNull(fngRes.data)
            assertEquals(32, fngRes.data!!.value)
            assertEquals("Fear", fngRes.data!!.classification)
            assertEquals(-18, fngRes.data!!.sevenDayDelta)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testGdeltNewsProvider_FilteringRecencyReliabilityAndNonCausalClaim() {
        val nowMs = 1_750_000_000_000L
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val recentTs = fmt.format(Date(nowMs - 2 * 3_600_000L))
        val staleTs = fmt.format(Date(nowMs - 96 * 3_600_000L))

        val payload = """
            {
              "articles": [
                {"title":"Bitcoin and Ethereum slide ahead of Federal Reserve CPI data","domain":"reuters.com","url":"https://reuters.com/1","seendate":"$recentTs"},
                {"title":"Old stale Bitcoin news from four days ago","domain":"reuters.com","url":"https://reuters.com/2","seendate":"$staleTs"},
                {"title":"Celebrity chef opens pasta shop in Rome","domain":"reuters.com","url":"https://reuters.com/3","seendate":"$recentTs"},
                {"title":"Bitcoin Federal Reserve inflation rumor blog","domain":"random-unverified-blog.xyz","url":"https://random-unverified-blog.xyz/4","seendate":"$recentTs"},
                {"title":"Gold rally cools as treasury yields rise before FOMC","domain":"investing.com","url":"https://investing.com/5","seendate":"$recentTs"}
              ]
            }
        """.trimIndent()

        val gdelt = GdeltNewsProvider(clockMillis = { nowMs })
        val filtered = gdelt.filterAndParseArticles(
            body = payload,
            nowMillis = nowMs,
            maxAgeHours = 72.0,
            minRelevanceScore = 2,
            includeLowReliability = false
        )

        assertEquals(2, filtered.size)
        assertEquals(NewsReliabilityTier.HIGH, filtered[0].reliabilityTier)
        assertTrue(filtered[0].article.title.contains("Bitcoin"))
        assertEquals(NewsReliabilityTier.MEDIUM, filtered[1].reliabilityTier)
        assertTrue(filtered.all { !it.isVerifiedOfficialCause })
        assertTrue(filtered.all { it.causalDisclaimer.contains("not verified", ignoreCase = true) })
    }

    @Test
    fun testCrossExchangeValidator_RejectsSpotVsFuturesSkewQuoteMismatchAndComputesBps() {
        val now = 1_750_000_000_000L
        val binanceFutures = NormalizedMarketQuote(
            symbol = "BTCUSDT", baseAsset = "BTC", quoteCurrency = "USDT",
            marketType = MarketInstrumentType.FUTURES_PERPETUAL, exchange = "Binance Futures",
            lastPrice = 90000.0, exchangeTimestampMillis = now, fetchedAtMillis = now,
            dataStatus = MarketDataStatus.LIVE
        )
        val bybitFuturesMatch = binanceFutures.copy(
            exchange = "Bybit V5", lastPrice = 90018.0, fetchedAtMillis = now + 2_000L
        )
        val bybitSpotMismatch = bybitFuturesMatch.copy(marketType = MarketInstrumentType.SPOT)
        val staleBybit = bybitFuturesMatch.copy(fetchedAtMillis = now + 120_000L)
        val usdQuoteMismatch = bybitFuturesMatch.copy(quoteCurrency = "USD")

        val valid = CrossExchangeValidator.compareQuotes(binanceFutures, bybitFuturesMatch)
        assertTrue(valid.isComparable)
        assertEquals(18.0, valid.priceSpreadUsd!!, 0.001)
        assertEquals(2.0, valid.spreadBps!!, 0.01)

        val spotReject = CrossExchangeValidator.compareQuotes(binanceFutures, bybitSpotMismatch)
        assertFalse(spotReject.isComparable)
        assertTrue(spotReject.validationNote.contains("cannot mix", ignoreCase = true))

        val skewReject = CrossExchangeValidator.compareQuotes(binanceFutures, staleBybit)
        assertFalse(skewReject.isComparable)
        assertTrue(skewReject.validationNote.contains("Stale timestamp skew", ignoreCase = true))

        val quoteReject = CrossExchangeValidator.compareQuotes(binanceFutures, usdQuoteMismatch)
        assertFalse(quoteReject.isComparable)
        assertTrue(quoteReject.validationNote.contains("quote currency mismatch", ignoreCase = true))
    }

    @Test
    fun testHybridMarketAggregator_OptionalFailuresNeverBlockPrimaryFutures() = runBlocking {
        val nowMs = 1_750_000_000_000L
        val server = startMockServer { path, _, _ ->
            when {
                path == "/fapi/v1/ticker/24hr" -> Triple(
                    200, emptyMap(),
                    """[{"symbol":"BTCUSDT","lastPrice":"89500.0","priceChangePercent":"1.2","highPrice":"90000","lowPrice":"88000","volume":"90000","quoteVolume":"8000000000","closeTime":$nowMs}]"""
                )
                path == "/fapi/v1/premiumIndex" -> Triple(200, emptyMap(), "[]")
                else -> Triple(503, emptyMap(), """{"error":"Optional down"}""")
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${server.address.port}"
            val http = ProviderHttpSupport(clockMillis = { nowMs })
            val aggregator = HybridMarketAggregator(
                binanceFuturesProvider = BinanceFuturesPrimaryProvider(baseUrl, http, { nowMs }),
                bybitProvider = BybitV5PublicProvider(baseUrl, http, { nowMs }),
                coinGeckoProvider = CoinGeckoDemoProvider(baseUrl, "", http, { nowMs }),
                defiLlamaProvider = DefiLlamaProvider(baseUrl, baseUrl, http, { nowMs }),
                fearGreedProvider = AlternativeMeFearGreedProvider(baseUrl, http, { nowMs }),
                gdeltNewsProvider = GdeltNewsProvider(baseUrl, http, { nowMs }),
                clockMillis = { nowMs }
            )

            val hybrid = aggregator.refreshHybridMarketState(optionalTimeoutMs = 2_000L)
            assertEquals(89500.0, hybrid.primaryFuturesQuotes["BTCUSDT"]!!.lastPrice, 0.01)
            assertEquals(MarketDataStatus.LIVE, hybrid.providerStatuses[BinanceFuturesPrimaryProvider.PROVIDER_ID]!!.dataStatus)
            assertEquals(MarketDataStatus.UNAVAILABLE, hybrid.providerStatuses[CoinGeckoDemoProvider.PROVIDER_ID]!!.dataStatus)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun testHistorySanitizerAndDeepSeekProviderAndGeminiErrorTransparency() = runBlocking {
        // 1. Verify FirebaseChatHistorySanitizer removes orphan user turns & preserves alternating pairs
        val raw = listOf(
            "model" to "stray opening model",
            "user" to "Q1",
            "user" to "Q2 (orphan overwritten or paired properly)",
            "model" to "A2",
            "user" to "Q3"
        )
        val sanitized = FirebaseChatHistorySanitizer.sanitize(raw, maxMessages = 16)
        assertEquals(2, sanitized.size)
        assertEquals("user" to "Q2 (orphan overwritten or paired properly)", sanitized[0])
        assertEquals("model" to "A2", sanitized[1])

        // 2. Verify DeepSeek unconfigured, 200, 402 auto-disablement, and 429 cooldown
        val nowMs = 1_750_000_000_000L
        val deepSeekCode = AtomicInteger(200)
        var capturedBody = ""
        val dsServer = startMockServer { _, _, _ ->
            capturedBody = "called"
            when (deepSeekCode.get()) {
                402 -> Triple(402, emptyMap(), """{"error":{"message":"Insufficient Balance"}}""")
                429 -> Triple(429, mapOf("Retry-After" to "60"), """{"error":{"message":"Rate limit"}}""")
                else -> Triple(200, emptyMap(), """{"choices":[{"message":{"content":"VERIFIED FACTS: BTCUSDT Futures @ $89,500."}}]}""")
            }
        }
        try {
            val baseUrl = "http://127.0.0.1:${dsServer.address.port}"
            val unconfiguredDs = DeepSeekAiProvider(baseUrl = baseUrl, apiKeyRaw = DeepSeekAiProvider.KEY_PLACEHOLDER)
            assertEquals(AiProviderAvailability.UNCONFIGURED, unconfiguredDs.getStatus().availability)

            val ds = DeepSeekAiProvider(baseUrl = baseUrl, apiKeyRaw = "sk-test-deepseek-123", clockMillis = { nowMs })
            assertTrue(ds.isConfigured())

            val context = ApplicationProvider.getApplicationContext<Context>()
            val failingGemini = object : GenerativeAiClient {
                override fun isConfigured(): Boolean = true
                override fun streamChat(systemInstruction: String, history: List<Pair<String, String>>, userMessage: String): Flow<String> =
                    flow { throw RuntimeException("Firebase App Check 403 error") }
            }
            val repo = NexusAiRepository(context = context, aiClient = failingGemini, deepSeekProvider = ds)
            val snap = MarketContextBuilder.buildSnapshot(asset = null, candles = emptyList())

            // Gemini failure emits explicit Error (never hidden)
            val geminiEvents = mutableListOf<NexusAiStreamResult>()
            repo.generateResponseStream("Why is BTC moving?", snap, LearningLanguageMode.ENGLISH, emptyList(), AiProviderType.GEMINI_FIREBASE)
                .collect { geminiEvents.add(it) }
            assertEquals(1, geminiEvents.filterIsInstance<NexusAiStreamResult.Error>().size)
            assertTrue(geminiEvents.filterIsInstance<NexusAiStreamResult.Error>().first().errorMessage.contains("403"))

            // DeepSeek succeeds with real response
            val dsEvents = mutableListOf<NexusAiStreamResult>()
            repo.generateResponseStream("Why is BTC moving?", snap, LearningLanguageMode.ENGLISH, emptyList(), AiProviderType.DEEPSEEK)
                .collect { dsEvents.add(it) }
            val completed = dsEvents.filterIsInstance<NexusAiStreamResult.Completed>().single()
            assertEquals("VERIFIED FACTS: BTCUSDT Futures @ $89,500.", completed.message.text)
            assertEquals("called", capturedBody)

            // DeepSeek 402 permanently disables itself under Zero-Cost Policy
            deepSeekCode.set(402)
            val ds402Events = mutableListOf<NexusAiStreamResult>()
            repo.generateResponseStream("Check again", snap, LearningLanguageMode.ENGLISH, emptyList(), AiProviderType.DEEPSEEK)
                .collect { ds402Events.add(it) }
            val err402 = ds402Events.filterIsInstance<NexusAiStreamResult.Error>().single()
            assertTrue(err402.isQuotaOrPaymentDisabled)
            assertFalse(err402.canRetry)
            assertEquals(AiProviderAvailability.DISABLED_PAYMENT_REQUIRED, ds.getStatus().availability)
            assertFalse(ds.isConfigured())
        } finally {
            dsServer.stop(0)
        }
    }

    @Test
    fun testRealPublicEndpointsIndependentDiagnosticProbe() = runBlocking {
        println("=== NEXUS LIVE 6-SOURCE PUBLIC ENDPOINT DIAGNOSTIC PROBE ===")
        val binance = BinanceFuturesPrimaryProvider()
        val bybit = BybitV5PublicProvider()
        val cg = CoinGeckoDemoProvider()
        val llama = DefiLlamaProvider()
        val fng = AlternativeMeFearGreedProvider()
        val gdelt = GdeltNewsProvider()

        val bRes = binance.fetchFuturesQuotes(listOf("BTCUSDT", "ETHUSDT"))
        println("[1. Binance Futures] status=${bRes.metadata.dataStatus}, health=${bRes.metadata.health}, ts=${bRes.metadata.timestampFormatted}, quotes=${bRes.data?.size ?: 0}, btc=${bRes.data?.get("BTCUSDT")?.lastPrice}, err=${bRes.metadata.errorMessage}, rate=${bRes.metadata.rateLimitNote}")
        assertNotNull(bRes.metadata.providerId)

        val byRes = bybit.fetchLinearFuturesQuotes(setOf("BTCUSDT", "ETHUSDT"))
        println("[2. Bybit V5 Linear] status=${byRes.metadata.dataStatus}, health=${byRes.metadata.health}, ts=${byRes.metadata.timestampFormatted}, quotes=${byRes.data?.size ?: 0}, btc=${byRes.data?.get("BTCUSDT")?.lastPrice}, err=${byRes.metadata.errorMessage}, rate=${byRes.metadata.rateLimitNote}")
        assertNotNull(byRes.metadata.providerId)

        val cgRes = cg.fetchGlobalAndTopRankings(topLimit = 5)
        println("[3. CoinGecko Demo] status=${cgRes.metadata.dataStatus}, health=${cgRes.metadata.health}, ts=${cgRes.metadata.timestampFormatted}, cap=${cgRes.data?.totalMarketCapFormatted}, btcDom=${cgRes.data?.btcDominanceFormatted}, err=${cgRes.metadata.errorMessage}, rate=${cgRes.metadata.rateLimitNote}")
        assertNotNull(cgRes.metadata.providerId)

        val dfRes = llama.fetchDefiAndStablecoins()
        println("[4. DefiLlama] status=${dfRes.metadata.dataStatus}, health=${dfRes.metadata.health}, ts=${dfRes.metadata.timestampFormatted}, tvl=${dfRes.data?.totalTvlFormatted}, stables=${dfRes.data?.totalStablecoinFormatted}, err=${dfRes.metadata.errorMessage}")
        assertNotNull(dfRes.metadata.providerId)

        val fgRes = fng.fetchFearAndGreed()
        println("[5. Alternative.me F&G] status=${fgRes.metadata.dataStatus}, health=${fgRes.metadata.health}, ts=${fgRes.metadata.timestampFormatted}, val=${fgRes.data?.value}, label=${fgRes.data?.classification}, err=${fgRes.metadata.errorMessage}")
        assertNotNull(fgRes.metadata.providerId)

        val gdRes = gdelt.fetchFilteredNews()
        println("[6. GDELT Wire] nowUtc=${com.example.data.providers.formatUtcTimestamp(System.currentTimeMillis())}, status=${gdRes.metadata.dataStatus}, health=${gdRes.metadata.health}, ts=${gdRes.metadata.timestampFormatted}, filteredArticles=${gdRes.data?.size ?: 0}, sample=${gdRes.data?.firstOrNull()?.article?.title}, err=${gdRes.metadata.errorMessage}")
        if (gdRes.data.isNullOrEmpty()) {
            val gdBroad = gdelt.fetchFilteredNews(maxAgeHours = 720.0, minRelevanceScore = 1, includeLowReliability = true)
            println("[6b. GDELT Diagnostic Broad] count=${gdBroad.data?.size ?: 0}, topDomains=${gdBroad.data?.take(3)?.map { it.domain + "@" + it.article.timestamp }}")
        }
        assertNotNull(gdRes.metadata.providerId)

        val btcBin = bRes.data?.get("BTCUSDT")
        val btcByb = byRes.data?.get("BTCUSDT")
        if (btcBin != null && btcByb != null) {
            val cmp = CrossExchangeValidator.compareQuotes(btcBin, btcByb)
            println("[Cross-Exchange BTCUSDT Futures Check] comparable=${cmp.isComparable}, note=${cmp.validationNote}")
            assertTrue(cmp.isComparable)
        }
        println("=== END DIAGNOSTIC PROBE ===")
    }

    @Test
    fun testGeminiTimeoutErrorPropagationAndCancellationPreservation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val snap = MarketContextBuilder.buildSnapshot(asset = null, candles = emptyList())

        // 1. TimeoutCancellationException from provider surfaces explicit Retryable Error (never silent offline fallback)
        val timingOutClient = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = flow {
                kotlinx.coroutines.withTimeout(10L) {
                    kotlinx.coroutines.delay(5_000L)
                }
            }
        }
        val timeoutRepo = NexusAiRepository(context = context, aiClient = timingOutClient)
        val timeoutEvents = mutableListOf<NexusAiStreamResult>()
        timeoutRepo.generateResponseStream("Why is BTC moving?", snap, LearningLanguageMode.ENGLISH)
            .collect { timeoutEvents.add(it) }

        val timeoutErr = timeoutEvents.filterIsInstance<NexusAiStreamResult.Error>().singleOrNull()
        assertNotNull("Expected explicit timeout Error event", timeoutErr)
        assertTrue(timeoutErr!!.canRetry)
        assertTrue(timeoutErr.errorMessage.contains("timed out after 60s", ignoreCase = true))

        // 2. Non-timeout CancellationException propagates without being swallowed as generic error
        val cancellingClient = object : GenerativeAiClient {
            override fun isConfigured(): Boolean = true
            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = flow {
                throw kotlinx.coroutines.CancellationException("Caller cancelled stream")
            }
        }
        val cancelRepo = NexusAiRepository(context = context, aiClient = cancellingClient)
        var cancelledProperly = false
        try {
            cancelRepo.generateResponseStream("Cancel check", snap, LearningLanguageMode.ENGLISH).collect {}
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelledProperly = true
        }
        assertTrue("Expected CancellationException to propagate", cancelledProperly)
    }
}
