package com.example.data.repository

import android.util.Log
import com.example.data.cache.MarketDataCache
import com.example.data.model.AlertDirection
import com.example.data.model.ApiFeedConfig
import com.example.data.model.AssetCategory
import com.example.data.model.ConnectionStatus
import com.example.data.model.MarketAlert
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.MarketOverview
import com.example.data.model.NewsImpact
import com.example.data.model.ScannerItem
import com.example.data.model.Signal
import com.example.data.model.SignalStatus
import com.example.data.model.SignalType
import com.example.data.signal.AiTradingSignal
import com.example.data.signal.AiTradingSignalEngine
import com.example.data.signal.SignalDecision
import com.example.data.remote.BinanceTicker24hr
import com.example.data.remote.BinanceWebSocketManager
import com.example.data.remote.CoinGeckoApiService
import com.example.data.remote.CommoditiesApiService
import com.example.data.remote.CommodityQuote
import com.example.data.indicators.OverallAnalysisResult
import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.smc.FvgStatus
import com.example.data.smc.MarketTrendState
import com.example.data.smc.OrderBlockStatus
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import com.example.data.smc.SmcCalculationEngine
import com.example.data.smc.TimeframeSmcSnapshot
import com.example.data.remote.CandleApiService
import com.example.data.remote.BinanceClient
import com.example.data.remote.CoinPriceQuote
import com.example.data.remote.LiveTickerUpdate
import com.example.data.remote.MarketApiService
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class TradingRepository(
    private val binanceClient: BinanceClient = BinanceClient(),
    private val webSocketManager: BinanceWebSocketManager = BinanceWebSocketManager(),
    private val coinGeckoService: CoinGeckoApiService = CoinGeckoApiService(),
    private val commoditiesService: CommoditiesApiService = CommoditiesApiService(),
    private val candleService: CandleApiService = CandleApiService()
) {
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cache = MarketDataCache()

    val watchlistRepository = com.example.data.watchlist.WatchlistRepository()
    val financialNewsService = com.example.data.news.FinancialNewsService()
    val notificationManager = com.example.util.NexusNotificationManager()
    val futuresApiClient = com.example.data.remote.BinanceFuturesApiClient()
    val hybridAggregator = com.example.data.providers.HybridMarketAggregator(
        gdeltNewsProvider = com.example.data.providers.GdeltNewsProvider()
    )

    private val _hybridMarketState = MutableStateFlow(com.example.data.providers.UnifiedHybridMarketState())
    val hybridMarketState: StateFlow<com.example.data.providers.UnifiedHybridMarketState> = _hybridMarketState.asStateFlow()

    suspend fun fetchDerivativesForAsset(assetId: String): com.example.data.remote.BinanceFuturesApiClient.DerivativesSnapshot? {
        val spec = com.example.data.providers.MultiMarketInstrumentCatalog.resolve(assetId)
        val binanceSymbol = if (spec != null && spec.isBinanceFuturesEligible) {
            spec.binanceSymbol
        } else null
        if (binanceSymbol.isNullOrBlank()) {
            return null
        }
        return try {
            val outcome = hybridAggregator.binanceFuturesProvider.fetchDerivativesForSymbol(binanceSymbol)
            val q = outcome.data
            if (q != null) {
                com.example.data.remote.BinanceFuturesApiClient.DerivativesSnapshot(
                    symbol = q.symbol,
                    fundingRatePercent = q.fundingRatePercent,
                    openInterestContracts = q.openInterestContracts,
                    longShortAccountRatio = q.longShortRatio,
                    fetchedAtMillis = q.fetchedAtMillis,
                    source = "${q.exchange} (${q.marketType.label})"
                )
            } else {
                futuresApiClient.fetchDerivativesSnapshot(binanceSymbol)
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun buildFuturesLiquiditySnapshot(
        assetId: String,
        candles: List<Candle>
    ): com.example.data.providers.FuturesLiquiditySnapshot {
        val spec = com.example.data.providers.MultiMarketInstrumentCatalog.resolve(assetId)
        val binanceSymbol = spec?.takeIf { it.isBinanceFuturesEligible }?.binanceSymbol
        if (binanceSymbol.isNullOrBlank()) {
            return com.example.data.providers.FuturesLiquiditySnapshot(
                symbol = spec?.displaySymbol ?: assetId.uppercase(),
                isCryptoFutures = false,
                status = com.example.data.providers.FuturesTelemetryStatus.NOT_APPLICABLE,
                source = spec?.primaryVenue?.displayName ?: "Non-Crypto Instrument"
            )
        }
        return try {
            val derivDeferred = repositoryScope.async { fetchDerivativesForAsset(assetId) }
            val depthDeferred = repositoryScope.async { futuresApiClient.fetchOrderBookDepth(binanceSymbol, limit = 20) }
            val deriv = derivDeferred.await()
            val depth = depthDeferred.await()
            val vd = TechnicalIndicatorsEngine.calculateVolumeDelta(candles)
            val hasAnyLive = deriv != null && (
                deriv.openInterestContracts != null ||
                    deriv.fundingRatePercent != null ||
                    deriv.longShortAccountRatio != null
                )
            val status = when {
                !hasAnyLive && depth == null -> com.example.data.providers.FuturesTelemetryStatus.UNAVAILABLE
                else -> com.example.data.providers.FuturesTelemetryStatus.LIVE
            }
            com.example.data.providers.FuturesLiquiditySnapshot(
                symbol = binanceSymbol,
                isCryptoFutures = true,
                status = status,
                openInterestContracts = deriv?.openInterestContracts,
                fundingRatePercent = deriv?.fundingRatePercent,
                longShortAccountRatio = deriv?.longShortAccountRatio,
                takerCvd = vd?.takeIf { it.hasReliableData }?.cvd,
                lastBarVolumeDelta = vd?.takeIf { it.hasReliableData }?.delta,
                orderBookDepth = depth,
                liquidationDataAvailable = false,
                fetchedAtMillis = deriv?.fetchedAtMillis ?: depth?.fetchedAtMillis ?: 0L,
                source = deriv?.source ?: "Binance USDT-M Futures Public REST"
            )
        } catch (_: Exception) {
            com.example.data.providers.FuturesLiquiditySnapshot(
                symbol = binanceSymbol,
                isCryptoFutures = true,
                status = com.example.data.providers.FuturesTelemetryStatus.UNAVAILABLE
            )
        }
    }

    private val _assets = MutableStateFlow<List<MarketAsset>>(initialAssets().map {
        it.copy(isFavorite = watchlistRepository.isFavorite(it.id))
    })
    val assets: StateFlow<List<MarketAsset>> = _assets.asStateFlow()

    private val _signals = MutableStateFlow<List<Signal>>(initialSignals())
    val signals: StateFlow<List<Signal>> = _signals.asStateFlow()

    private val _aiSignals = MutableStateFlow<List<AiTradingSignal>>(emptyList())
    val aiSignals: StateFlow<List<AiTradingSignal>> = _aiSignals.asStateFlow()

    private val _alerts = MutableStateFlow<List<MarketAlert>>(initialAlerts())
    val alerts: StateFlow<List<MarketAlert>> = _alerts.asStateFlow()

    private val _news = MutableStateFlow<List<MarketNews>>(financialNewsService.getVerifiedMacroNews())
    val news: StateFlow<List<MarketNews>> = _news.asStateFlow()

    private val _scannerItems = MutableStateFlow<List<ScannerItem>>(initialScannerItems())
    val scannerItems: StateFlow<List<ScannerItem>> = _scannerItems.asStateFlow()

    private val _apiConfigs = MutableStateFlow<List<ApiFeedConfig>>(initialApiConfigs())
    val apiConfigs: StateFlow<List<ApiFeedConfig>> = _apiConfigs.asStateFlow()

    private val _marketOverview = MutableStateFlow(MarketOverview())
    val marketOverview: StateFlow<MarketOverview> = _marketOverview.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    val isWebSocketLive: StateFlow<Boolean> = webSocketManager.isConnected

    // Performance & Data Saving Controls
    private val _isDataSavingMode = MutableStateFlow(false)
    val isDataSavingMode: StateFlow<Boolean> = _isDataSavingMode.asStateFlow()

    private val _isLowPerformanceMode = MutableStateFlow(false)
    val isLowPerformanceMode: StateFlow<Boolean> = _isLowPerformanceMode.asStateFlow()

    private val _refreshIntervalSeconds = MutableStateFlow(15)
    val refreshIntervalSeconds: StateFlow<Int> = _refreshIntervalSeconds.asStateFlow()

    private val _lastMarketDataUpdateMillis = MutableStateFlow(System.currentTimeMillis())
    val lastMarketDataUpdateMillis: StateFlow<Long> = _lastMarketDataUpdateMillis.asStateFlow()

    private val candleCache = ConcurrentHashMap<String, Pair<Long, List<Candle>>>()

    private data class CachedAiSignal(
        val timestampMs: Long,
        val signal: AiTradingSignal
    )

    private val aiSignalCache = ConcurrentHashMap<String, CachedAiSignal>()
    private val inFlightAiSignals = ConcurrentHashMap<String, CompletableDeferred<AiTradingSignal>>()
    val aiSignalStateManager = com.example.data.scanner.ActiveSignalStateManager()

    fun getCachedAiSignalIfFresh(
        symbol: String,
        timeframe: CandleTimeframe,
        maxAgeMs: Long = 45_000L
    ): AiTradingSignal? {
        val key = "${normalizeAssetLookupId(symbol)}_${timeframe.name}"
        val cached = aiSignalCache[key] ?: return null
        return if (System.currentTimeMillis() - cached.timestampMs <= maxAgeMs) cached.signal else null
    }

    fun getLastCachedAiSignal(
        symbol: String,
        timeframe: CandleTimeframe
    ): AiTradingSignal? {
        val key = "${normalizeAssetLookupId(symbol)}_${timeframe.name}"
        return aiSignalCache[key]?.signal
    }

    fun clearSignalCache() {
        aiSignalCache.clear()
        candleCache.clear()
    }

    init {
        // Cache initial asset templates
        cache.putAll(_assets.value)

        // Observe real-time Binance WebSocket ticker stream
        repositoryScope.launch {
            webSocketManager.tickerUpdates.collect { updates ->
                if (!_isDataSavingMode.value) {
                    handleWebSocketUpdates(updates)
                }
            }
        }

        // Start WebSocket streaming if not in data-saving mode
        if (!_isDataSavingMode.value) {
            webSocketManager.start()
        }

        // Trigger initial data load
        repositoryScope.launch {
            refreshData()
        }
    }

    fun setDataSavingMode(enabled: Boolean) {
        _isDataSavingMode.value = enabled
        if (enabled) {
            webSocketManager.stop()
            if (_refreshIntervalSeconds.value == 15) {
                _refreshIntervalSeconds.value = 60
            }
        } else {
            webSocketManager.start()
        }
    }

    fun setLowPerformanceMode(enabled: Boolean) {
        _isLowPerformanceMode.value = enabled
        if (enabled) {
            candleCache.clear()
        }
    }

    fun setRefreshIntervalSeconds(seconds: Int) {
        _refreshIntervalSeconds.value = seconds
    }

    fun clearCache(): Int {
        val count = candleCache.size
        candleCache.clear()
        return count
    }

    private fun handleWebSocketUpdates(updates: List<LiveTickerUpdate>) {
        val now = System.currentTimeMillis()
        val timeStr = formatTimestamp(now)
        val updateMap = updates.associateBy { it.symbol }
        val wsLabel = webSocketManager.activeStreamLabel.value
        val instrumentTag = if (webSocketManager.isFuturesStream.value) "USDT-M Perpetual Futures" else "Spot Market"

        _assets.value = _assets.value.map { asset ->
            val binanceSymbol = assetToBinanceSymbol(asset.id)
            val update = updateMap[binanceSymbol]

            if (update != null) {
                val formattedVol = formatCryptoVolume(update.volume, asset.id)
                val newAsset = asset.copy(
                    currentPrice = update.lastPrice,
                    change24h = (update.lastPrice * (update.priceChangePercent / 100.0)),
                    change24hPercent = update.priceChangePercent,
                    high24h = update.highPrice,
                    low24h = update.lowPrice,
                    volume24h = formattedVol,
                    connectionStatus = ConnectionStatus.CONNECTED,
                    dataStatus = MarketDataStatus.LIVE,
                    dataSourceName = "$wsLabel ($instrumentTag)",
                    dataSourceInfo = "$instrumentTag • Real-time ticker stream • Updated $timeStr",
                    lastUpdated = now,
                    lastUpdatedFormatted = timeStr,
                    sparklinePoints = if (asset.sparklinePoints.isNotEmpty()) {
                        asset.sparklinePoints.drop(1) + update.lastPrice.toFloat()
                    } else {
                        generateSparkline(update.lastPrice, update.priceChangePercent)
                    }
                )
                cache.put(newAsset)
                checkAlertTriggers(asset.symbol, update.lastPrice)
                newAsset
            } else {
                asset
            }
        }
        updateOverviewCounts()
    }

    suspend fun refreshData() {
        _isRefreshing.value = true
        withContext(Dispatchers.IO) {
            try {
                // 1. Primary non-blocking crypto fetch + parallel optional hybrid sources
                fetchCryptoRestTickers()

                // 2. Refresh full hybrid state (Bybit V5 cross-check, CoinGecko Demo, DefiLlama, Alternative.me F&G, GDELT)
                val hybrid = hybridAggregator.refreshHybridMarketState(_hybridMarketState.value)
                _hybridMarketState.value = hybrid
                applyHybridMacroAndNews(hybrid)

                // 3. Fetch Commodities (Gold, Silver, WTI, Brent, Natural Gas) + Stocks (NVDA, GOOG, GOOGL) & FX/Index
                val commoditiesKey = _apiConfigs.value.firstOrNull { it.id == "commodities_feed" }?.apiKey ?: ""
                val commodityQuotes = commoditiesService.fetchCommodityQuotes(commoditiesKey)
                val stocksAndMacroQuotes = commoditiesService.fetchStocksAndMacroQuotes()
                updateCommoditiesState(commodityQuotes + stocksAndMacroQuotes)

            } catch (e: Exception) {
                Log.w("TradingRepo", "Data refresh error: ${e.message}")
                _assets.value = cache.markAllAsStale(e.localizedMessage ?: "Network error")
            } finally {
                _lastMarketDataUpdateMillis.value = System.currentTimeMillis()
                updateOverviewCounts()
                _isRefreshing.value = false
            }
        }
    }

    private val cryptoToCoinGeckoMap = mapOf(
        "BTC" to "bitcoin",
        "ETH" to "ethereum",
        "BNB" to "binancecoin",
        "SOL" to "solana",
        "XRP" to "ripple",
        "DOGE" to "dogecoin",
        "ADA" to "cardano",
        "LINK" to "chainlink",
        "AVAX" to "avalanche-2",
        "TRX" to "tron",
        "TON" to "the-open-network",
        "LTC" to "litecoin",
        "DOT" to "polkadot",
        "BCH" to "bitcoin-cash",
        "SUI" to "sui"
    )

    private suspend fun fetchCryptoRestTickers() {
        // Tier 1 (Primary): Binance USDT-M Futures Public REST API (fapi.binance.com)
        try {
            val futuresOutcome = hybridAggregator.refreshPrimaryFuturesOnly()
            val futuresQuotes = futuresOutcome.data
            if (!futuresQuotes.isNullOrEmpty()) {
                applyNormalizedFuturesQuotes(futuresQuotes, futuresOutcome.metadata)
                return
            }
        } catch (e: Exception) {
            Log.w("TradingRepo", "Binance USDT-M Futures unavailable (${e.message}). Trying Spot fallback.")
        }

        val symbolsList = listOf(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "TRXUSDT",
            "TONUSDT", "LTCUSDT", "DOTUSDT", "BCHUSDT", "SUIUSDT"
        )
        val jsonSymbols = "[\"" + symbolsList.joinToString("\",\"") + "\"]"

        // Tier 2 (Explicitly Labeled Spot Fallback): Binance Spot / Binance.US
        try {
            val tickers = binanceClient.get24hrTickers(jsonSymbols)
            if (tickers.isNotEmpty()) {
                applyBinanceTickers(tickers, "${binanceClient.activeSourceLabel} (Spot Fallback)")
                return
            }
        } catch (e: Exception) {
            Log.w("TradingRepo", "Binance Spot tickers unavailable (${e.message}). Trying Bybit V5 Linear / CoinGecko.")
        }

        // Tier 3 (Explicitly Labeled Bybit V5 Secondary Linear Futures)
        try {
            val bybitOutcome = hybridAggregator.bybitProvider.fetchLinearFuturesQuotes(
                com.example.data.providers.BinanceFuturesPrimaryProvider.DEFAULT_FUTURES_SYMBOLS.toSet()
            )
            val bybitQuotes = bybitOutcome.data
            if (!bybitQuotes.isNullOrEmpty()) {
                applyNormalizedFuturesQuotes(bybitQuotes, bybitOutcome.metadata)
                return
            }
        } catch (e: Exception) {
            Log.w("TradingRepo", "Bybit V5 Linear fallback failed (${e.message}). Trying CoinGecko Spot.")
        }

        // Tier 4: CoinGecko simple price quotes (Explicitly labeled Spot)
        try {
            val cgQuotes = coinGeckoService.fetchSimplePrices(cryptoToCoinGeckoMap.values.toList())
            if (cgQuotes.isNotEmpty()) {
                applyCoinGeckoQuotes(cgQuotes)
                return
            }
        } catch (e: Exception) {
            Log.w("TradingRepo", "CoinGecko fallback failed (${e.message}). Trying Coinbase spot.")
        }

        // Tier 5: Coinbase public spot fallback for major pairs
        fetchCoinbaseSpotFallback()
    }

    private fun applyNormalizedFuturesQuotes(
        quotes: Map<String, com.example.data.providers.NormalizedMarketQuote>,
        metadata: com.example.data.providers.ProviderMetadata
    ) {
        val now = System.currentTimeMillis()
        val timeStr = formatTimestamp(now)
        val isWsActive = webSocketManager.isConnected.value

        _assets.value = _assets.value.map { asset ->
            val binanceSymbol = assetToBinanceSymbol(asset.id)
            val quote = quotes[binanceSymbol]
            if (quote != null) {
                val volBase = quote.volume24hBase ?: 0.0
                val status = when {
                    isWsActive -> MarketDataStatus.LIVE
                    else -> metadata.dataStatus
                }
                val sourceLabel = if (isWsActive) {
                    "${webSocketManager.activeStreamLabel.value} (${quote.marketType.label})"
                } else {
                    "${quote.exchange} (${quote.marketType.label})"
                }
                val newAsset = asset.copy(
                    currentPrice = quote.lastPrice,
                    change24h = quote.change24hPercent?.let { quote.lastPrice * (it / 100.0) },
                    change24hPercent = quote.change24hPercent,
                    high24h = quote.high24h,
                    low24h = quote.low24h,
                    volume24h = formatCryptoVolume(volBase, asset.id),
                    connectionStatus = ConnectionStatus.CONNECTED,
                    dataStatus = status,
                    dataSourceName = sourceLabel,
                    dataSourceInfo = "${quote.marketType.label} • Funding: ${quote.fundingRatePercent?.let { String.format(Locale.US, "%+.4f%%", it) } ?: "N/A"} • ${metadata.timestampFormatted}",
                    lastUpdated = quote.fetchedAtMillis,
                    lastUpdatedFormatted = timeStr,
                    sparklinePoints = generateSparkline(quote.lastPrice, quote.change24hPercent ?: 0.0)
                )
                cache.put(newAsset)
                checkAlertTriggers(asset.symbol, quote.lastPrice)
                newAsset
            } else asset
        }
    }

    private fun applyBinanceTickers(tickers: List<BinanceTicker24hr>, sourceLabel: String) {
        val tickerMap = tickers.associateBy { it.symbol }
        val now = System.currentTimeMillis()
        val timeStr = formatTimestamp(now)

        _assets.value = _assets.value.map { asset ->
            val binanceSymbol = assetToBinanceSymbol(asset.id)
            val ticker = tickerMap[binanceSymbol]

            if (ticker != null) {
                val lastPrice = ticker.lastPrice.toDoubleOrNull()
                val change = ticker.priceChange.toDoubleOrNull()
                val changePct = ticker.priceChangePercent.toDoubleOrNull()
                val high = ticker.highPrice.toDoubleOrNull()
                val low = ticker.lowPrice.toDoubleOrNull()
                val vol = ticker.volume.toDoubleOrNull() ?: 0.0

                if (lastPrice != null) {
                    val isWsActive = webSocketManager.isConnected.value
                    val source = if (isWsActive) "${webSocketManager.activeStreamLabel.value} (${if (webSocketManager.isFuturesStream.value) "USDT-M Perpetual Futures" else "Spot Market"})" else sourceLabel
                    val status = if (isWsActive) MarketDataStatus.LIVE else MarketDataStatus.DELAYED

                    val newAsset = asset.copy(
                        currentPrice = lastPrice,
                        change24h = change,
                        change24hPercent = changePct,
                        high24h = high,
                        low24h = low,
                        volume24h = formatCryptoVolume(vol, asset.id),
                        connectionStatus = ConnectionStatus.CONNECTED,
                        dataStatus = status,
                        dataSourceName = source,
                        dataSourceInfo = "$source • 24hr Ticker",
                        lastUpdated = now,
                        lastUpdatedFormatted = timeStr,
                        sparklinePoints = generateSparkline(lastPrice, changePct ?: 0.0)
                    )
                    cache.put(newAsset)
                    checkAlertTriggers(asset.symbol, lastPrice)
                    newAsset
                } else asset
            } else asset
        }
    }

    private fun applyCoinGeckoQuotes(quotes: Map<String, CoinPriceQuote>) {
        val now = System.currentTimeMillis()
        val timeStr = formatTimestamp(now)

        _assets.value = _assets.value.map { asset ->
            val cgId = cryptoToCoinGeckoMap[asset.id]
            val quote = quotes[cgId]
            if (quote != null) {
                val price = quote.price
                val changePct = quote.change24hPercent
                val change = price * (changePct / 100.0)
                val volStr = formatLargeCurrency(quote.volume24hUsd)

                val newAsset = asset.copy(
                    currentPrice = price,
                    change24h = change,
                    change24hPercent = changePct,
                    high24h = null,
                    low24h = null,
                    volume24h = volStr,
                    connectionStatus = ConnectionStatus.CONNECTED,
                    dataStatus = MarketDataStatus.LIVE,
                    dataSourceName = "CoinGecko Demo API (Spot Market)",
                    dataSourceInfo = "Spot Market • CoinGecko Free/Demo Feed • 24hr Change",
                    lastUpdated = now,
                    lastUpdatedFormatted = timeStr,
                    sparklinePoints = generateSparkline(price, changePct)
                )
                cache.put(newAsset)
                checkAlertTriggers(asset.symbol, price)
                newAsset
            } else asset
        }
    }

    private suspend fun fetchCoinbaseSpotFallback() = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val now = System.currentTimeMillis()
        val timeStr = formatTimestamp(now)

        val updatedMap = mutableMapOf<String, Double>()
        for ((assetId, pair) in listOf("BTC" to "BTC-USD", "ETH" to "ETH-USD", "SOL" to "SOL-USD", "XRP" to "XRP-USD")) {
            try {
                val req = Request.Builder()
                    .url("https://api.coinbase.com/v2/prices/$pair/spot")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val root = JSONObject(body)
                        val data = root.optJSONObject("data")
                        val amount = data?.optDouble("amount", Double.NaN) ?: Double.NaN
                        if (!amount.isNaN() && amount > 0) {
                            updatedMap[assetId] = amount
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("TradingRepo", "Coinbase spot fetch failed for $assetId: ${e.message}")
            }
        }

        if (updatedMap.isNotEmpty()) {
            _assets.value = _assets.value.map { asset ->
                val price = updatedMap[asset.id]
                if (price != null) {
                    val newAsset = asset.copy(
                        currentPrice = price,
                        connectionStatus = ConnectionStatus.CONNECTED,
                        dataStatus = MarketDataStatus.LIVE,
                        dataSourceName = "Coinbase Public API (Spot Market)",
                        dataSourceInfo = "Spot Market • Coinbase USD Spot • Live Price",
                        lastUpdated = now,
                        lastUpdatedFormatted = timeStr,
                        sparklinePoints = generateSparkline(price, 0.5)
                    )
                    cache.put(newAsset)
                    newAsset
                } else asset
            }
        }
    }

    private fun updateCommoditiesState(quotes: Map<String, CommodityQuote>) {
        val now = System.currentTimeMillis()
        val timeStr = formatTimestamp(now)

        _assets.value = _assets.value.map { asset ->
            val quote = quotes[asset.id]
            if (quote != null) {
                val updated = asset.copy(
                    currentPrice = quote.price,
                    change24h = quote.change,
                    change24hPercent = quote.changePercent,
                    high24h = quote.high24h,
                    low24h = quote.low24h,
                    volume24h = quote.volume,
                    connectionStatus = ConnectionStatus.CONNECTED,
                    dataStatus = quote.status,
                    dataSourceName = quote.sourceName,
                    dataSourceInfo = "${quote.sourceName} • $timeStr",
                    lastUpdated = quote.timestamp,
                    lastUpdatedFormatted = timeStr,
                    sparklinePoints = if (quote.sparkline.isNotEmpty()) quote.sparkline else generateSparkline(quote.price, quote.changePercent),
                    setupInstructions = null
                )
                cache.put(updated)
                checkAlertTriggers(asset.symbol, quote.price)
                updated
            } else if ((asset.category == AssetCategory.COMMODITIES || asset.category == AssetCategory.STOCKS ||
                    asset.category == AssetCategory.FOREX || asset.category == AssetCategory.INDICES) && asset.currentPrice == null) {
                asset.copy(
                    connectionStatus = ConnectionStatus.NOT_CONNECTED,
                    dataStatus = MarketDataStatus.UNAVAILABLE,
                    dataSourceName = "DATA UNAVAILABLE (${asset.dataSourceName})",
                    dataSourceInfo = "Feed unreachable or delayed beyond tolerance",
                    setupInstructions = if (asset.category == AssetCategory.COMMODITIES) {
                        "Public Yahoo Finance feed temporarily unavailable. Optional: add a free TwelveData API key in Settings > Market Data Providers."
                    } else {
                        "Public Yahoo Finance delayed feed temporarily unavailable. Pull to refresh during market hours."
                    }
                )
            } else {
                asset
            }
        }
    }

    private fun applyHybridMacroAndNews(hybrid: com.example.data.providers.UnifiedHybridMarketState) {
        val cg = hybrid.coinGeckoGlobal
        val llama = hybrid.defiSnapshot
        val fng = hybrid.fearGreed
        val fngStatus = hybrid.providerStatuses[com.example.data.providers.AlternativeMeFearGreedProvider.PROVIDER_ID]

        val activeProviders = hybrid.providerStatuses.values.count {
            it.dataStatus == MarketDataStatus.LIVE || it.dataStatus == MarketDataStatus.STALE
        }

        _marketOverview.value = _marketOverview.value.copy(
            fearAndGreedIndex = fng?.value ?: _marketOverview.value.fearAndGreedIndex,
            sentimentLabel = fng?.classification ?: _marketOverview.value.sentimentLabel,
            hasLiveSentiment = fng != null,
            sentimentTimestamp = fng?.metadata?.timestampFormatted ?: fngStatus?.errorMessage ?: "Unavailable",
            totalCryptoMarketCap = cg?.totalMarketCapFormatted ?: _marketOverview.value.totalCryptoMarketCap,
            cryptoVolume24h = cg?.totalVolume24hFormatted ?: _marketOverview.value.cryptoVolume24h,
            btcDominance = cg?.btcDominanceFormatted ?: _marketOverview.value.btcDominance,
            defiTvlFormatted = llama?.totalTvlFormatted ?: _marketOverview.value.defiTvlFormatted,
            stablecoinSupplyFormatted = llama?.totalStablecoinFormatted ?: _marketOverview.value.stablecoinSupplyFormatted,
            marketStatus = if (hybrid.primaryFuturesQuotes.isNotEmpty()) "USDT-M Futures Live" else "Syncing Public Feeds",
            dataProviderSummary = "Active Sources: $activeProviders/6 • Binance Futures • Bybit V5 • CoinGecko • DefiLlama"
        )

        if (hybrid.filteredNews.isNotEmpty()) {
            val savedIds = _news.value.filter { it.isSaved }.map { it.id }.toSet()
            _news.value = financialNewsService.markSeenAndDeduplicate(
                hybrid.filteredNews.map { it.article }
            ).map { if (savedIds.contains(it.id)) it.copy(isSaved = true) else it }
        }
    }

    private suspend fun fetchCoinGeckoData() {
        val outcome = hybridAggregator.coinGeckoProvider.fetchGlobalAndTopRankings()
        val global = outcome.data
        if (global != null) {
            _marketOverview.value = _marketOverview.value.copy(
                totalCryptoMarketCap = global.totalMarketCapFormatted,
                cryptoVolume24h = global.totalVolume24hFormatted,
                btcDominance = global.btcDominanceFormatted
            )
        }
    }

    private fun checkAlertTriggers(symbol: String, currentPrice: Double) {
        _alerts.value = _alerts.value.map { alert ->
            if (alert.isActive && !alert.isTriggered && alert.assetSymbol.equals(symbol, ignoreCase = true)) {
                val hit = when (alert.direction) {
                    AlertDirection.ABOVE -> currentPrice >= alert.targetPrice
                    AlertDirection.BELOW -> currentPrice <= alert.targetPrice
                }
                if (hit) {
                    notificationManager.notifyPriceTarget(
                        alertId = alert.id,
                        symbol = alert.assetSymbol,
                        targetPrice = alert.targetPrice,
                        currentPrice = currentPrice,
                        directionAbove = alert.direction == AlertDirection.ABOVE
                    )
                    alert.copy(isTriggered = true, lastTriggeredTime = System.currentTimeMillis())
                } else alert
            } else alert
        }
    }

    fun toggleFavorite(assetId: String) {
        val isFav = watchlistRepository.toggleFavorite(assetId)
        _assets.value = _assets.value.map {
            if (it.id.equals(assetId, ignoreCase = true)) it.copy(isFavorite = isFav) else it
        }
        if (isFav) {
            notificationManager.notifyWatchlistUpdate(assetId.uppercase(), "Added to priority Watchlist")
        }
    }

    suspend fun refreshNews(forceRefresh: Boolean = false) {
        try {
            val savedIds = _news.value.filter { it.isSaved }.map { it.id }.toSet()
            val freshNews = financialNewsService.fetchMarketNews(forceRefresh).map {
                if (savedIds.contains(it.id)) it.copy(isSaved = true) else it
            }
            if (freshNews.isNotEmpty()) {
                _news.value = freshNews
                freshNews.firstOrNull { it.impactLevel == com.example.data.model.NewsImpactLevel.HIGH }?.let { highNews ->
                    notificationManager.notifyNews(highNews)
                }
            }
        } catch (e: Exception) {
            Log.w("TradingRepo", "Failed refreshing news: ${e.message}")
        }
    }

    suspend fun refreshScanner(timeframe: com.example.data.model.CandleTimeframe = com.example.data.model.CandleTimeframe.H1) {
        try {
            val assetsToScan = _assets.value.take(12)
            val scanned = mutableListOf<com.example.data.model.ScannerItem>()
            for (asset in assetsToScan) {
                val candles = getCandles(asset.id, timeframe)
                val item = com.example.data.scanner.MarketScannerEngine.scanAsset(asset, candles, timeframe)
                scanned.add(item)
            }
            if (scanned.isNotEmpty()) {
                _scannerItems.value = scanned
            }
        } catch (e: Exception) {
            Log.w("TradingRepo", "Failed scanning market assets: ${e.message}")
        }
    }

    private fun updateOverviewCounts() {
        val liveOrDelayedCount = _assets.value.count {
            it.dataStatus == MarketDataStatus.LIVE || it.dataStatus == MarketDataStatus.DELAYED
        }
        val totalCount = _assets.value.size
        val pricedCrypto = _assets.value.filter { it.category == AssetCategory.CRYPTO && it.currentPrice != null }
        val bullishPct = if (pricedCrypto.isNotEmpty()) {
            val green = pricedCrypto.count { (it.change24hPercent ?: 0.0) >= 0.0 }
            ((green * 100.0) / pricedCrypto.size).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val breadthText = if (pricedCrypto.isNotEmpty()) {
            val green = pricedCrypto.count { (it.change24hPercent ?: 0.0) >= 0.0 }
            "$green/${pricedCrypto.size} Positive (24h)"
        } else {
            "Unavailable"
        }

        _marketOverview.value = _marketOverview.value.copy(
            connectedFeedsCount = liveOrDelayedCount,
            totalFeedsCount = totalCount,
            bullishAssetsPercent = bullishPct,
            volatilityState = breadthText,
            activeSignalsCount = _signals.value.count { it.status == SignalStatus.ACTIVE }
        )
    }

    fun toggleAlert(alertId: String) {
        _alerts.value = _alerts.value.map { alert ->
            if (alert.id == alertId) alert.copy(isActive = !alert.isActive) else alert
        }
    }

    fun deleteAlert(alertId: String) {
        _alerts.value = _alerts.value.filter { it.id != alertId }
    }

    fun createAlert(
        assetSymbol: String,
        assetName: String,
        targetPrice: Double,
        direction: AlertDirection,
        note: String
    ) {
        val newAlert = MarketAlert(
            id = "alert_${System.currentTimeMillis()}",
            assetSymbol = assetSymbol,
            assetName = assetName,
            targetPrice = targetPrice,
            direction = direction,
            isActive = true,
            isTriggered = false,
            createdTime = System.currentTimeMillis(),
            note = note
        )
        _alerts.value = listOf(newAlert) + _alerts.value
    }

    fun toggleNewsBookmark(newsId: String) {
        _news.value = _news.value.map { item ->
            if (item.id == newsId) item.copy(isSaved = !item.isSaved) else item
        }
    }

    suspend fun fetchFullNewsArticle(newsId: String): MarketNews? {
        val current = _news.value.firstOrNull { it.id == newsId } ?: return null
        val enriched = financialNewsService.fetchFullArticleForDetail(current)
        val updated = if (current.isSaved) enriched.copy(isSaved = true) else enriched
        _news.value = _news.value.map { item ->
            if (item.id == newsId) updated else item
        }
        return updated
    }

    fun connectApiFeed(feedId: String, apiKey: String): Boolean {
        if (apiKey.isBlank()) return false
        _apiConfigs.value = _apiConfigs.value.map { config ->
            if (config.id == feedId) {
                config.copy(
                    apiKey = apiKey,
                    isConnected = true,
                    lastPing = "Connected just now"
                )
            } else config
        }
        repositoryScope.launch {
            refreshData()
        }
        return true
    }

    fun disconnectApiFeed(feedId: String) {
        _apiConfigs.value = _apiConfigs.value.map { config ->
            if (config.id == feedId) config.copy(isConnected = false, lastPing = "Disconnected") else config
        }
        updateOverviewCounts()
    }

    private fun assetToBinanceSymbol(id: String): String {
        val spec = com.example.data.providers.MultiMarketInstrumentCatalog.resolve(id)
        if (spec != null) {
            return if (spec.isBinanceFuturesEligible) spec.binanceSymbol.orEmpty() else ""
        }
        return ""
    }

    private fun formatCryptoVolume(vol: Double, id: String): String {
        return when {
            vol >= 1_000_000 -> String.format("%.2fM %s", vol / 1_000_000.0, id)
            vol >= 1_000 -> String.format("%.2fK %s", vol / 1_000.0, id)
            else -> String.format("%.2f %s", vol, id)
        }
    }

    private fun formatLargeCurrency(amount: Double): String {
        return when {
            amount >= 1_000_000_000.0 -> String.format(Locale.US, "$%.2fB", amount / 1_000_000_000.0)
            amount >= 1_000_000.0 -> String.format(Locale.US, "$%.2fM", amount / 1_000_000.0)
            amount >= 1_000.0 -> String.format(Locale.US, "$%.2fK", amount / 1_000.0)
            else -> String.format(Locale.US, "$%.2f", amount)
        }
    }

    private fun formatTimestamp(millis: Long): String =
        com.example.util.TimeZoneUtils.formatKarachiTime12h(millis)

    private fun generateSparkline(basePrice: Double, changePct: Double): List<Float> {
        val points = mutableListOf<Float>()
        val startPrice = basePrice * (1.0 - (changePct / 100.0))
        val step = (basePrice - startPrice) / 8.0
        for (i in 0..8) {
            val variance = (if (i % 2 == 0) 1 else -1) * (basePrice * 0.003)
            val p = (startPrice + (step * i) + variance).toFloat()
            points.add(p)
        }
        points[points.size - 1] = basePrice.toFloat()
        return points
    }

    companion object {
        fun initialAssets(): List<MarketAsset> = listOf(
            // 1. Primary Dashboard Crypto
            MarketAsset(
                id = "BTC",
                name = "Bitcoin",
                symbol = "BTC/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "ETH",
                name = "Ethereum",
                symbol = "ETH/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "BNB",
                name = "BNB Chain",
                symbol = "BNB/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "SOL",
                name = "Solana",
                symbol = "SOL/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "XRP",
                name = "Ripple",
                symbol = "XRP/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "DOGE",
                name = "Dogecoin",
                symbol = "DOGE/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "ADA",
                name = "Cardano",
                symbol = "ADA/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "LINK",
                name = "Chainlink",
                symbol = "LINK/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "AVAX",
                name = "Avalanche",
                symbol = "AVAX/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "TRX",
                name = "TRON",
                symbol = "TRX/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "TON",
                name = "Toncoin",
                symbol = "TON/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "LTC",
                name = "Litecoin",
                symbol = "LTC/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "DOT",
                name = "Polkadot",
                symbol = "DOT/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "BCH",
                name = "Bitcoin Cash",
                symbol = "BCH/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),
            MarketAsset(
                id = "SUI",
                name = "Sui Network",
                symbol = "SUI/USDT",
                category = AssetCategory.CRYPTO,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Binance WebSocket",
                dataSourceInfo = "Binance Public Stream",
                unit = "$"
            ),

            // 2. Commodities (Gold, Silver, WTI, Brent, Natural Gas)
            MarketAsset(
                id = "XAU",
                name = "Gold Spot (XAUUSD)",
                symbol = "XAU/USD",
                category = AssetCategory.COMMODITIES,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "NYMEX/COMEX Delayed Feed",
                dataSourceInfo = "Precious Metals Benchmark",
                setupInstructions = "Provider key required. Configure in Settings > Market Data Providers.",
                unit = "$/oz"
            ),
            MarketAsset(
                id = "XAG",
                name = "Silver Spot (XAGUSD)",
                symbol = "XAG/USD",
                category = AssetCategory.COMMODITIES,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "NYMEX/COMEX Delayed Feed",
                dataSourceInfo = "Precious Metals Benchmark",
                setupInstructions = "Provider key required. Configure in Settings > Market Data Providers.",
                unit = "$/oz"
            ),
            MarketAsset(
                id = "WTI",
                name = "WTI Crude Oil",
                symbol = "WTI/USD",
                category = AssetCategory.COMMODITIES,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "NYMEX Energy Stream",
                dataSourceInfo = "Energy Benchmark (WTI Light Sweet)",
                setupInstructions = "Provider key required. Configure in Settings > Market Data Providers.",
                unit = "$/bbl"
            ),
            MarketAsset(
                id = "BRENT",
                name = "Brent Crude Oil",
                symbol = "BRENT/USD",
                category = AssetCategory.COMMODITIES,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "ICE Energy Stream",
                dataSourceInfo = "International Crude Benchmark",
                setupInstructions = "Provider key required. Configure in Settings > Market Data Providers.",
                unit = "$/bbl"
            ),
            MarketAsset(
                id = "NATGAS",
                name = "Natural Gas",
                symbol = "NG/USD",
                category = AssetCategory.COMMODITIES,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Henry Hub Natural Gas",
                dataSourceInfo = "NYMEX Gas Benchmark",
                setupInstructions = "Provider key required. Configure in Settings > Market Data Providers.",
                unit = "$/MMBtu"
            ),

            // 3. Verified Equities / Stocks (NVDA, GOOG, GOOGL)
            MarketAsset(
                id = "NVDA",
                name = "NVIDIA Corporation",
                symbol = "NVDA",
                category = AssetCategory.STOCKS,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "NASDAQ / Yahoo Finance",
                dataSourceInfo = "US Equity Feed",
                unit = "$"
            ),
            MarketAsset(
                id = "GOOG",
                name = "Alphabet Inc. (Class C)",
                symbol = "GOOG",
                category = AssetCategory.STOCKS,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "NASDAQ / Yahoo Finance",
                dataSourceInfo = "US Equity Feed",
                unit = "$"
            ),
            MarketAsset(
                id = "GOOGL",
                name = "Alphabet Inc. (Class A)",
                symbol = "GOOGL",
                category = AssetCategory.STOCKS,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "NASDAQ / Yahoo Finance",
                dataSourceInfo = "US Equity Feed",
                unit = "$"
            ),

            // 4. Forex & Indices
            MarketAsset(
                id = "EURUSD",
                name = "Euro / US Dollar",
                symbol = "EUR/USD",
                category = AssetCategory.FOREX,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "Interbank FX / Yahoo Finance",
                dataSourceInfo = "Forex Spot Stream",
                unit = ""
            ),
            MarketAsset(
                id = "SPX",
                name = "S&P 500 Index",
                symbol = "SPX500",
                category = AssetCategory.INDICES,
                dataStatus = MarketDataStatus.UNAVAILABLE,
                dataSourceName = "US Equity Index / Yahoo Finance",
                dataSourceInfo = "Index Benchmark",
                unit = "pts"
            )
        )

        fun initialSignals(): List<Signal> = listOf(
            Signal(
                id = "sig_1",
                assetSymbol = "BTC/USDT",
                assetName = "Bitcoin",
                type = SignalType.STRONG_BUY,
                entryPrice = "$67,200 - $67,500",
                targetPrice = "$71,800 (+6.8%)",
                stopLoss = "$65,900 (-2.1%)",
                riskReward = "3.2 : 1",
                confidenceScore = 92,
                timeframe = "4H",
                timestamp = System.currentTimeMillis() - 1800000,
                status = SignalStatus.ACTIVE,
                thesis = "Bullish divergence formed on 4H RSI with massive spot delta absorption. Ascending wedge breakout confirmed above 200 EMA.",
                indicators = listOf("RSI Bullish Div (38.2)", "MACD Golden Cross", "Volume +180% vs 20-MA")
            ),
            Signal(
                id = "sig_2",
                assetSymbol = "XAU/USD",
                assetName = "Gold Spot",
                type = SignalType.BUY,
                entryPrice = "$2,725 - $2,735",
                targetPrice = "$2,780 (+1.8%)",
                stopLoss = "$2,705 (-0.9%)",
                riskReward = "2.0 : 1",
                confidenceScore = 88,
                timeframe = "1D",
                timestamp = System.currentTimeMillis() - 7200000,
                status = SignalStatus.ACTIVE,
                thesis = "Safe haven flows surge amid real yield softening and central bank accumulation. Retest of previous resistance turning to support.",
                indicators = listOf("SuperTrend Bullish", "Stochastic 22 (Oversold Bounce)", "Volume Accumulation")
            ),
            Signal(
                id = "sig_3",
                assetSymbol = "WTI/USD",
                assetName = "Crude Oil",
                type = SignalType.SELL,
                entryPrice = "$72.50 - $73.00",
                targetPrice = "$68.20 (-5.8%)",
                stopLoss = "$74.30 (+1.9%)",
                riskReward = "2.9 : 1",
                confidenceScore = 84,
                timeframe = "4H",
                timestamp = System.currentTimeMillis() - 14400000,
                status = SignalStatus.ACTIVE,
                thesis = "Inventory build exceeding EIA forecasts by 3.8M barrels. Rejection off descending trendline with bearish engulfing pattern.",
                indicators = listOf("Bearish Engulfing 4H", "RSI 64 Rejection", "MACD Bearish Divergence")
            ),
            Signal(
                id = "sig_4",
                assetSymbol = "SOL/USDT",
                assetName = "Solana",
                type = SignalType.STRONG_BUY,
                entryPrice = "$175.00 - $178.00",
                targetPrice = "$198.50 (+12.2%)",
                stopLoss = "$168.00 (-4.8%)",
                riskReward = "2.8 : 1",
                confidenceScore = 91,
                timeframe = "1D",
                timestamp = System.currentTimeMillis() - 10800000,
                status = SignalStatus.ACTIVE,
                thesis = "Breakout retest of multi-week consolidation channel. DEX volume expanding with network fees at 6-month high.",
                indicators = listOf("DEX Volume +240%", "RSI 58 Momentum", "Bollinger Band Expansion")
            ),
            Signal(
                id = "sig_5",
                assetSymbol = "XAG/USD",
                assetName = "Silver Spot",
                type = SignalType.STRONG_BUY,
                entryPrice = "$32.20 - $32.40",
                targetPrice = "$34.50 (+6.6%)",
                stopLoss = "$31.40 (-2.6%)",
                riskReward = "2.5 : 1",
                confidenceScore = 90,
                timeframe = "1D",
                timestamp = System.currentTimeMillis() - 21600000,
                status = SignalStatus.ACTIVE,
                thesis = "Gold/Silver ratio compressing from 85 to 81. Industrial solar demand forecast upward revision with low COMEX warehouse stocks.",
                indicators = listOf("G/S Ratio Breakdown", "Volume Breakout", "RSI 56 Momentum Expansion")
            )
        )

        fun initialAlerts(): List<MarketAlert> = listOf(
            MarketAlert(
                id = "alert_1",
                assetSymbol = "BTC/USDT",
                assetName = "Bitcoin",
                targetPrice = 70000.0,
                direction = AlertDirection.ABOVE,
                isActive = true,
                isTriggered = false,
                note = "Breakout alert above key psychological resistance"
            ),
            MarketAlert(
                id = "alert_2",
                assetSymbol = "BTC/USDT",
                assetName = "Bitcoin",
                targetPrice = 65000.0,
                direction = AlertDirection.BELOW,
                isActive = true,
                isTriggered = false,
                note = "Dip buy zone / stop defense limit"
            ),
            MarketAlert(
                id = "alert_3",
                assetSymbol = "XAU/USD",
                assetName = "Gold Spot",
                targetPrice = 2750.0,
                direction = AlertDirection.ABOVE,
                isActive = true,
                isTriggered = false,
                note = "All-time high continuation retest trigger"
            ),
            MarketAlert(
                id = "alert_4",
                assetSymbol = "WTI/USD",
                assetName = "Crude Oil",
                targetPrice = 70.0,
                direction = AlertDirection.BELOW,
                isActive = false,
                isTriggered = false,
                note = "Support failure confirmation"
            )
        )

        fun initialNews(): List<MarketNews> = listOf(
            MarketNews(
                id = "news_1",
                title = "Global Central Banks Boost Gold Reserves as Currency Diversification Accelerates",
                source = "Bloomberg Terminal",
                timestamp = "Reference • 14:40 PKT",
                category = "Commodities",
                summary = "Official sector bullion purchases reached 290 metric tons in the previous quarter, supporting precious metals macro strength.",
                impact = NewsImpact.BULLISH,
                isSaved = true,
                fullContent = "Global central banks continued their bullion accumulation spree through the current fiscal quarter, adding over 290 metric tons of physical gold to sovereign reserves."
            ),
            MarketNews(
                id = "news_2",
                title = "Bitcoin Spot ETFs See $420M Inflows in Single Session as Institutional Custody Expands",
                source = "Nexus Institutional Wire",
                timestamp = "Reference • 14:15 PKT",
                category = "ETF & Institutional",
                summary = "Institutional asset managers recorded net positive inflows led by IBIT and FBTC as market liquidity thickens.",
                impact = NewsImpact.BULLISH,
                isSaved = false,
                fullContent = "US-listed spot Bitcoin exchange-traded funds registered a combined net inflow of $420.5 million as institutional custody allocations expanded."
            ),
            MarketNews(
                id = "news_3",
                title = "OPEC+ Weighs Production Quota Delays as Global Crude Demand Projections Temper",
                source = "Reuters Energy",
                timestamp = "Reference • 12:30 PKT",
                category = "Oil & Energy",
                summary = "Delegates indicate the cartel may postpone its planned 180,000 bpd monthly output revival to stabilize crude benchmark prices.",
                impact = NewsImpact.BEARISH,
                isSaved = false,
                fullContent = "OPEC and its allies are evaluating an extension of voluntary supply reductions through the coming quarter amid moderated demand projections."
            ),
            MarketNews(
                id = "news_4",
                title = "Federal Reserve Balance Sheet Runoff Signals Liquidity Tightening in Short-Term Repo",
                source = "Financial Times Macro",
                timestamp = "Reference • 10:30 PKT",
                category = "Forex & Macro",
                summary = "Overnight Reverse Repo facility balances dip below $200 billion as Treasury bill issuance absorbs cash reserves.",
                impact = NewsImpact.NEUTRAL,
                isSaved = false,
                fullContent = "The Federal Reserve's quantitative tightening reduced reverse repo reserves, keeping FOMC liquidity guidance closely watched across risk markets."
            )
        ).map { com.example.data.news.NewsImpactAnalyzer.enrichNewsItem(it) }

        fun initialScannerItems(): List<ScannerItem> = listOf(
            ScannerItem(
                id = "scan_btc_1h",
                symbol = "BTC/USDT",
                name = "Bitcoin",
                category = AssetCategory.CRYPTO,
                rsi14 = 34.2,
                macdStatus = "Bullish Cross 4H",
                emaCondition = "Above 200 EMA",
                volumeMultiplier = 2.4,
                aiScore = 94,
                matchedCriteria = listOf("RSI Oversold (<30)", "MACD Crossovers", "High Volume (RVOL > 1.5x)", "BOS & CHoCH"),
                verifiedPrice = 67450.0,
                change24hPercent = 2.84,
                detectedCondition = "BOS & CHoCH Breakout",
                timeframe = "1H",
                lastUpdated = "Live",
                assetId = "BTC"
            ),
            ScannerItem(
                id = "scan_xau_1h",
                symbol = "XAU/USD",
                name = "Gold Spot",
                category = AssetCategory.COMMODITIES,
                rsi14 = 58.6,
                macdStatus = "Expanding Histogram",
                emaCondition = "Above 50 & 200 EMA",
                volumeMultiplier = 1.8,
                aiScore = 89,
                matchedCriteria = listOf("Strong Bullish Trend", "EMA Crossovers", "High Volume (RVOL > 1.5x)"),
                verifiedPrice = 2742.50,
                change24hPercent = 1.15,
                detectedCondition = "Strong Bullish Trend",
                timeframe = "1H",
                lastUpdated = "Live",
                assetId = "GOLD"
            ),
            ScannerItem(
                id = "scan_sol_1h",
                symbol = "SOL/USDT",
                name = "Solana",
                category = AssetCategory.CRYPTO,
                rsi14 = 28.5,
                macdStatus = "Histogram Turning Green",
                emaCondition = "Testing 100 EMA",
                volumeMultiplier = 3.1,
                aiScore = 91,
                matchedCriteria = listOf("RSI Oversold (<30)", "High Volume (RVOL > 1.5x)", "Near Support (≤0.8%)", "Active FVG Zones"),
                verifiedPrice = 178.20,
                change24hPercent = -2.14,
                detectedCondition = "RSI Oversold (<30)",
                timeframe = "1H",
                lastUpdated = "Live",
                assetId = "SOL"
            ),
            ScannerItem(
                id = "scan_wti_1h",
                symbol = "WTI/USD",
                name = "Crude Oil",
                category = AssetCategory.COMMODITIES,
                rsi14 = 66.8,
                macdStatus = "Bearish Divergence",
                emaCondition = "Below 50 EMA",
                volumeMultiplier = 1.2,
                aiScore = 48,
                matchedCriteria = listOf("Near Resistance (≤0.8%)", "Liquidity Sweeps"),
                verifiedPrice = 71.85,
                change24hPercent = -0.72,
                detectedCondition = "Near Resistance (≤0.8%)",
                timeframe = "1H",
                lastUpdated = "Live",
                assetId = "OIL_WTI"
            ),
            ScannerItem(
                id = "scan_eth_1h",
                symbol = "ETH/USDT",
                name = "Ethereum",
                category = AssetCategory.CRYPTO,
                rsi14 = 44.1,
                macdStatus = "Consolidating",
                emaCondition = "At 200 EMA",
                volumeMultiplier = 1.6,
                aiScore = 76,
                matchedCriteria = listOf("Active FVG Zones", "High Volume (RVOL > 1.5x)", "Near Support (≤0.8%)"),
                verifiedPrice = 2580.0,
                change24hPercent = 1.45,
                detectedCondition = "Active FVG Zones",
                timeframe = "1H",
                lastUpdated = "Live",
                assetId = "ETH"
            )
        )

        fun initialApiConfigs(): List<ApiFeedConfig> = listOf(
            ApiFeedConfig(
                id = "binance_ws",
                providerName = "Binance USDT-M Futures WebSocket",
                assetCovered = "BTC, ETH, BNB, SOL, XRP, DOGE, ADA, LINK, AVAX, SUI Perpetuals",
                apiKey = "Public (No Key Required)",
                isConnected = true,
                endpointUrl = "wss://fstream.binance.com/ws/!miniTicker@arr",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://developers.binance.com/docs/derivatives/usds-margined-futures/websocket-market-streams",
                instructions = "Primary USDT-M Perpetual Futures live stream (read-only, zero trading keys)."
            ),
            ApiFeedConfig(
                id = "binance_public",
                providerName = "Binance USDT-M Futures REST (Primary)",
                assetCovered = "Perpetual Tickers, Mark Price, Funding Rate, OI & L/S Ratio",
                apiKey = "Public (No Key Required)",
                isConnected = true,
                endpointUrl = "https://fapi.binance.com/fapi/v1/ticker/24hr",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api",
                instructions = "Primary public REST market feed for futures quotes, candles, and derivatives."
            ),
            ApiFeedConfig(
                id = "bybit_v5_public",
                providerName = "Bybit V5 Public Cross-Exchange",
                assetCovered = "Secondary Linear Futures & Spot Cross-Check",
                apiKey = "Public (No Key Required)",
                isConnected = true,
                endpointUrl = "https://api.bybit.com/v5/market/tickers?category=linear",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://bybit-exchange.github.io/docs/v5/market/tickers",
                instructions = "Cross-exchange price & funding consistency check (never mixes Spot & Futures)."
            ),
            ApiFeedConfig(
                id = "coingecko_public",
                providerName = "CoinGecko v3 Demo / Public API",
                assetCovered = "Global Market Cap, Dominance, Rankings & 7d History",
                apiKey = "Public (Optional Demo Key)",
                isConnected = true,
                endpointUrl = "https://api.coingecko.com/api/v3/global",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://docs.coingecko.com/reference/introduction",
                instructions = "Optional CoinGecko Demo API key can be configured via AI Studio Secrets."
            ),
            ApiFeedConfig(
                id = "defillama_public",
                providerName = "DefiLlama Public API",
                assetCovered = "Multi-Chain DeFi TVL & Stablecoin Circulating Supply",
                apiKey = "Public (No Key Required)",
                isConnected = true,
                endpointUrl = "https://api.llama.fi/v2/chains",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://defillama.com/docs/api",
                instructions = "Open on-chain TVL and stablecoin liquidity data with zero billing."
            ),
            ApiFeedConfig(
                id = "alternativeme_fng",
                providerName = "Alternative.me Fear & Greed API",
                assetCovered = "Daily Crypto Market Sentiment Index (0-100)",
                apiKey = "Public (No Key Required)",
                isConnected = true,
                endpointUrl = "https://api.alternative.me/fng/?limit=7",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://alternative.me/crypto/fear-and-greed-index/",
                instructions = "Public Fear & Greed sentiment endpoint."
            ),
            ApiFeedConfig(
                id = "gdelt_news_wire",
                providerName = "GDELT Project v2 Financial Wire",
                assetCovered = "Filtered Crypto & Macroeconomic Headlines",
                apiKey = "Public (No Key Required)",
                isConnected = true,
                endpointUrl = "https://api.gdeltproject.org/api/v2/doc/doc",
                lastPing = "Active",
                requiresApiKey = false,
                setupDocumentationUrl = "https://blog.gdeltproject.org/gdelt-doc-2-0-api-debuts/",
                instructions = "Public news wire filtered for relevance, recency & domain reliability."
            ),
            ApiFeedConfig(
                id = "commodities_feed",
                providerName = "TwelveData Commodities API",
                assetCovered = "Gold (XAU), Silver (XAG), WTI Crude, Brent, Natural Gas",
                apiKey = "",
                isConnected = false,
                endpointUrl = "https://api.twelvedata.com/price",
                lastPing = "Unconnected",
                requiresApiKey = true,
                setupDocumentationUrl = "https://twelvedata.com/pricing",
                instructions = "To connect live commodity spot prices, register for a free API key at twelvedata.com and paste it here."
            )
        )
    }

    suspend fun getCandles(
        symbol: String,
        timeframe: CandleTimeframe,
        limit: Int = 250
    ): List<Candle> {
        val effectiveLimit = if (_isLowPerformanceMode.value) minOf(limit, 60) else limit
        val cacheKey = "${symbol}_${timeframe.name}_$effectiveLimit"
        val now = System.currentTimeMillis()
        val ttlMs = if (_isDataSavingMode.value) 60_000L else 20_000L

        val cached = candleCache[cacheKey]
        if (cached != null && (now - cached.first) < ttlMs && cached.second.isNotEmpty()) {
            return cached.second
        }

        val candles = candleService.fetchCandles(symbol, timeframe, effectiveLimit)
        if (candles.isNotEmpty()) {
            candleCache[cacheKey] = Pair(now, candles)
            return candles
        }
        return cached?.second ?: emptyList()
    }

    suspend fun calculateTechnicalAnalysis(
        symbol: String,
        assetName: String,
        timeframe: CandleTimeframe
    ): OverallAnalysisResult = withContext(Dispatchers.Default) {
        val candles = candleService.fetchCandles(symbol, timeframe)
        TechnicalIndicatorsEngine.analyze(symbol, assetName, timeframe, candles)
    }

    private fun normalizeAssetLookupId(symbol: String): String =
        com.example.data.providers.MultiMarketInstrumentCatalog.normalizeCanonicalId(symbol)

    private fun findAssetBySymbol(symbol: String): MarketAsset? {
        val canonical = normalizeAssetLookupId(symbol)
        return _assets.value.firstOrNull {
            it.id.equals(canonical, ignoreCase = true) ||
                it.symbol.equals(symbol, ignoreCase = true) ||
                it.id.equals(symbol, ignoreCase = true)
        }
    }

    suspend fun calculateSmcAnalysis(
        symbol: String,
        assetName: String,
        timeframe: CandleTimeframe
    ): SmcAnalysisResult = withContext(Dispatchers.Default) {
        val candles = getCandles(symbol, timeframe, 250)
        calculateSmcAnalysisForCandles(symbol, assetName, timeframe, candles)
    }

    private suspend fun calculateSmcAnalysisForCandles(
        symbol: String,
        assetName: String,
        timeframe: CandleTimeframe,
        primaryCandles: List<Candle>
    ): SmcAnalysisResult = coroutineScope {
        val mtfTimeframes = listOf(
            CandleTimeframe.W1,
            CandleTimeframe.D1,
            CandleTimeframe.H4,
            CandleTimeframe.H1,
            CandleTimeframe.M30,
            CandleTimeframe.M15,
            CandleTimeframe.M5,
            CandleTimeframe.M1
        )
        val snapshotDeferreds = mtfTimeframes.map { tf ->
            async(Dispatchers.IO) {
                val tfCandles = if (tf == timeframe) primaryCandles else getCandles(symbol, tf, 100)
                if (tfCandles.size >= 20) {
                    val tempResult = SmcCalculationEngine.analyze(symbol, assetName, tf, tfCandles)
                    val isBullish = tempResult.marketStructure.trendState == MarketTrendState.BULLISH_UPTREND ||
                        tempResult.marketStructure.isBullishBOSPresent ||
                        tempResult.marketStructure.isBullishCHoCHPresent
                    TimeframeSmcSnapshot(
                        timeframe = tf,
                        trend = tempResult.marketStructure.trendState,
                        lastBreakType = tempResult.marketStructure.latestBreak?.type,
                        unmitigatedOBCount = tempResult.orderBlocks.count { it.status == OrderBlockStatus.UNMITIGATED },
                        unfilledFvgCount = tempResult.fairValueGaps.count { it.status == FvgStatus.UNFILLED },
                        nearestSupport = tempResult.priceZones.firstOrNull {
                            it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND
                        }?.centralPrice,
                        nearestResistance = tempResult.priceZones.firstOrNull {
                            it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY
                        }?.centralPrice,
                        isBullishBias = isBullish
                    )
                } else null
            }
        }
        val snapshots = snapshotDeferreds.awaitAll().filterNotNull()
        SmcCalculationEngine.analyze(symbol, assetName, timeframe, primaryCandles, snapshots)
    }

    suspend fun calculateAiSignal(
        symbol: String,
        assetName: String,
        timeframe: CandleTimeframe = CandleTimeframe.H1,
        forceRefresh: Boolean = false
    ): AiTradingSignal = withContext(Dispatchers.IO) {
        val canonical = normalizeAssetLookupId(symbol)
        val cacheKey = "${canonical}_${timeframe.name}"

        if (!forceRefresh) {
            aiSignalCache[cacheKey]?.let { cached ->
                if (System.currentTimeMillis() - cached.timestampMs <= 45_000L) {
                    return@withContext cached.signal
                }
            }
        }

        // In-flight deduplication so rapid re-taps share a single network calculation
        val existingDeferred = inFlightAiSignals[cacheKey]
        if (existingDeferred != null && !forceRefresh) {
            return@withContext existingDeferred.await()
        }

        val newDeferred = CompletableDeferred<AiTradingSignal>()
        val raced = inFlightAiSignals.putIfAbsent(cacheKey, newDeferred)
        if (raced != null && !forceRefresh) {
            return@withContext raced.await()
        }

        try {
            val computed = computeAiSignalUncached(symbol, assetName, timeframe)
            aiSignalCache[cacheKey] = CachedAiSignal(System.currentTimeMillis(), computed)
            newDeferred.complete(computed)
            computed
        } catch (e: Throwable) {
            val staleFallback = aiSignalCache[cacheKey]?.signal
            if (staleFallback != null) {
                newDeferred.complete(staleFallback)
                staleFallback
            } else {
                newDeferred.completeExceptionally(e)
                throw e
            }
        } finally {
            inFlightAiSignals.remove(cacheKey, newDeferred)
        }
    }

    private suspend fun computeAiSignalUncached(
        symbol: String,
        assetName: String,
        timeframe: CandleTimeframe
    ): AiTradingSignal = withContext(Dispatchers.IO) {
        val canonical = normalizeAssetLookupId(symbol)
        var asset = findAssetBySymbol(symbol)
        if (asset == null || asset.currentPrice == null || asset.currentPrice <= 0.0 || asset.dataStatus == MarketDataStatus.UNAVAILABLE) {
            val spec = com.example.data.providers.MultiMarketInstrumentCatalog.resolve(canonical)
            withTimeoutOrNull(3_500L) {
                when (spec?.category) {
                    AssetCategory.COMMODITIES -> {
                        val commoditiesKey = _apiConfigs.value.firstOrNull { it.id == "commodities_feed" }?.apiKey ?: ""
                        runCatching {
                            val quotes = commoditiesService.fetchCommodityQuotes(commoditiesKey)
                            updateCommoditiesState(quotes)
                        }
                    }
                    AssetCategory.STOCKS, AssetCategory.FOREX, AssetCategory.INDICES -> {
                        runCatching {
                            val quotes = commoditiesService.fetchStocksAndMacroQuotes()
                            updateCommoditiesState(quotes)
                        }
                    }
                    else -> runCatching { fetchCryptoRestTickers() }
                }
            }
            asset = findAssetBySymbol(symbol)
        }

        val spec = com.example.data.providers.MultiMarketInstrumentCatalog.resolve(symbol)
        val coreTimeframes = linkedSetOf(
            timeframe,
            CandleTimeframe.M15,
            CandleTimeframe.H1,
            CandleTimeframe.H4,
            CandleTimeframe.D1
        )

        // Single parallel candle batch with bounded 4.5s timeout per timeframe
        val candlesByTf: Map<CandleTimeframe, List<Candle>> = coroutineScope {
            coreTimeframes.map { tf ->
                async(Dispatchers.IO) {
                    val limit = if (tf == timeframe) 250 else 120
                    tf to (withTimeoutOrNull(4_500L) { getCandles(symbol, tf, limit) } ?: emptyList())
                }
            }.awaitAll().toMap()
        }

        val candles = candlesByTf[timeframe].orEmpty()
        val candles15m = candlesByTf[CandleTimeframe.M15].orEmpty()
        val candles1h = candlesByTf[CandleTimeframe.H1].orEmpty()
        val candles4h = candlesByTf[CandleTimeframe.H4].orEmpty()
        val candles1d = candlesByTf[CandleTimeframe.D1].orEmpty()

        val candleSource = candleService.lastDataSourceForSymbol(symbol)
        val currentPrice = asset?.currentPrice
        val dataStatus = asset?.dataStatus ?: MarketDataStatus.UNAVAILABLE
        val dataSourceName = candleSource?.takeIf { !it.startsWith("DATA UNAVAILABLE") }
            ?: asset?.dataSourceName
            ?: spec?.primaryVenue?.displayName
            ?: "DATA UNAVAILABLE"
        val lastUpdated = asset?.lastUpdated

        val mtfSnapshots: List<TimeframeSmcSnapshot> = withContext(Dispatchers.Default) {
            listOf(CandleTimeframe.D1, CandleTimeframe.H4, CandleTimeframe.H1, CandleTimeframe.M15)
                .mapNotNull { tf ->
                    val tfCandles = candlesByTf[tf].orEmpty()
                    if (tfCandles.size >= 20) {
                        val tempResult = SmcCalculationEngine.analyze(symbol, assetName, tf, tfCandles)
                        val isBullish = tempResult.marketStructure.trendState == MarketTrendState.BULLISH_UPTREND ||
                            tempResult.marketStructure.isBullishBOSPresent ||
                            tempResult.marketStructure.isBullishCHoCHPresent
                        TimeframeSmcSnapshot(
                            timeframe = tf,
                            trend = tempResult.marketStructure.trendState,
                            lastBreakType = tempResult.marketStructure.latestBreak?.type,
                            unmitigatedOBCount = tempResult.orderBlocks.count { it.status == OrderBlockStatus.UNMITIGATED },
                            unfilledFvgCount = tempResult.fairValueGaps.count { it.status == FvgStatus.UNFILLED },
                            nearestSupport = tempResult.priceZones.firstOrNull {
                                it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND
                            }?.centralPrice,
                            nearestResistance = tempResult.priceZones.firstOrNull {
                                it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY
                            }?.centralPrice,
                            isBullishBias = isBullish
                        )
                    } else null
                }
        }

        val (ta, smc) = withContext(Dispatchers.Default) {
            val taRes = if (candles.size >= 30) {
                TechnicalIndicatorsEngine.analyze(symbol, assetName, timeframe, candles)
            } else null
            val smcRes = if (candles.size >= 30) {
                SmcCalculationEngine.analyze(symbol, assetName, timeframe, candles, mtfSnapshots)
            } else null
            taRes to smcRes
        }

        val futuresLiq = if (spec?.isBinanceFuturesEligible == true && candles.isNotEmpty()) {
            withTimeoutOrNull(3_000L) { buildFuturesLiquiditySnapshot(symbol, candles) }
        } else null

        val strictReportPrecheck = if (candles.size >= 30 && currentPrice != null && currentPrice > 0.0) {
            com.example.data.signal.StrictSignalQualityEngine.evaluateSetup(
                symbol = symbol,
                assetName = assetName,
                currentPrice = currentPrice,
                dataStatus = dataStatus,
                primaryTimeframe = timeframe,
                candles = candles,
                candles15m = candles15m,
                candles1h = candles1h,
                candles4h = candles4h,
                candles1d = candles1d,
                technicalAnalysis = ta,
                smcAnalysis = smc,
                mtfSnapshots = mtfSnapshots,
                futuresLiquidity = futuresLiq,
                hybridState = _hybridMarketState.value
            )
        } else null

        val gatedSignal = AiTradingSignalEngine.generateSignal(
            symbol = symbol,
            assetName = assetName,
            currentPrice = currentPrice,
            dataStatus = dataStatus,
            dataSourceName = dataSourceName,
            lastUpdatedTime = lastUpdated,
            primaryTimeframe = timeframe,
            candles = candles,
            technicalAnalysis = ta,
            smcAnalysis = smc,
            mtfSnapshots = mtfSnapshots,
            strictQualityReportOverride = strictReportPrecheck,
            enforceStrictQualificationGate = true,
            strictMinScore = com.example.data.signal.StrictSignalQualityEngine.SCORE_THRESHOLD_HIGH_QUALITY
        )

        // Enforce ActiveSignalStateManager: cooldown, duplicate prevention & anti-flip protection
        if (currentPrice != null && currentPrice > 0.0) {
            aiSignalStateManager.evaluateMarketUpdate(canonical, currentPrice, System.currentTimeMillis())
        }

        if (gatedSignal.decision != SignalDecision.NO_TRADE && gatedSignal.riskPlan != null) {
            val dir = if (gatedSignal.decision == SignalDecision.LONG) {
                com.example.data.scanner.FuturesDirection.LONG
            } else {
                com.example.data.scanner.FuturesDirection.SHORT
            }
            val lastCloseMs = candles15m.lastOrNull()?.closeTime ?: candles.lastOrNull()?.closeTime ?: System.currentTimeMillis()
            val intake = aiSignalStateManager.submitCandidate(
                symbol = canonical,
                direction = dir,
                isConfirmed = true,
                entryPrice = gatedSignal.riskPlan.entryPrice,
                stopLoss = gatedSignal.riskPlan.stopLoss,
                takeProfit1 = gatedSignal.riskPlan.takeProfit1,
                takeProfit2 = gatedSignal.riskPlan.takeProfit3,
                netRiskReward = gatedSignal.strictQualityReport?.netRiskRewardSummary?.netRiskReward ?: gatedSignal.riskPlan.riskRewardRatio,
                qualityScore = gatedSignal.strictQualityReport?.totalScore ?: gatedSignal.confidence.totalScore,
                dataSourceName = dataSourceName,
                setupReason = "Strict ${gatedSignal.strictQualityReport?.totalScore ?: gatedSignal.confidence.totalScore}/100",
                confirmationCandleCloseMs = lastCloseMs,
                expiresAtMs = lastCloseMs + (4 * 3_600_000L)
            )
            if (!intake.accepted) {
                return@withContext gatedSignal.copy(
                    decision = SignalDecision.NO_TRADE,
                    riskPlan = null,
                    noTradeReason = com.example.data.signal.NoTradeReason(
                        code = "ANTI_FLIP_COOLDOWN_GUARD",
                        headline = "WAIT / NO TRADE — Active Opposite Signal Guard",
                        detailedExplanation = intake.rejectionReason
                            ?: "Blocked silent direction flip while prior setup remains live.",
                        conflictingFactors = listOfNotNull(
                            intake.rejectionReason,
                            "Anti-noise guard prevents flip-flopping between LONG and SHORT before SL/TP/Expiry"
                        ),
                        recommendedAction = "Wait until active signal closes or expires cleanly."
                    )
                )
            }
        }

        gatedSignal
    }

    suspend fun fetchBacktestInputsForSymbols(
        symbols: List<String>,
        lookbackDays: Int = 180,
        onProgress: (String) -> Unit = {}
    ): List<com.example.data.timemachine.SingleCoinHistoricalInput> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val startMs = now - (lookbackDays.coerceIn(7, 210).toLong() * 86_400_000L)
        val normalizedList = symbols.map {
            val up = it.uppercase(Locale.US).trim()
            if (up.endsWith("USDT")) up else "${up}USDT"
        }.distinct()

        val results = mutableListOf<com.example.data.timemachine.SingleCoinHistoricalInput>()
        for ((idx, pair) in normalizedList.withIndex()) {
            onProgress("Fetching ${idx + 1}/${normalizedList.size}: $pair (${lookbackDays}d 15M/HTF & funding)...")
            val input = coroutineScope {
                val c15Deferred = async { futuresApiClient.fetchHistoricalCandlesPaginated(pair, CandleTimeframe.M15, startMs, now) }
                val c1hDeferred = async { futuresApiClient.fetchHistoricalCandlesPaginated(pair, CandleTimeframe.H1, startMs, now, maxBars = 5000) }
                val c4hDeferred = async { futuresApiClient.fetchHistoricalCandlesPaginated(pair, CandleTimeframe.H4, startMs, now, maxBars = 1500) }
                val c1dDeferred = async { futuresApiClient.fetchHistoricalCandlesPaginated(pair, CandleTimeframe.D1, startMs - (120L * 86_400_000L), now, maxBars = 400) }
                val fundDeferred = async { futuresApiClient.fetchHistoricalFundingRatesPaginated(pair, startMs, now) }
                com.example.data.timemachine.SingleCoinHistoricalInput(
                    symbol = pair,
                    candles15m = c15Deferred.await(),
                    candles1h = c1hDeferred.await(),
                    candles4h = c4hDeferred.await(),
                    candles1d = c1dDeferred.await(),
                    fundingHistory = fundDeferred.await()
                )
            }
            results.add(input)
        }
        results
    }

    suspend fun refreshAiSignals(): List<AiTradingSignal> = withContext(Dispatchers.IO) {
        val symbolsToScan = listOf(
            Pair("BTC", "Bitcoin"),
            Pair("ETH", "Ethereum"),
            Pair("SOL", "Solana"),
            Pair("BNB", "BNB"),
            Pair("XRP", "XRP"),
            Pair("DOGE", "Dogecoin"),
            Pair("ADA", "Cardano"),
            Pair("LINK", "Chainlink")
        )

        val results = coroutineScope {
            symbolsToScan.map { (sym, name) ->
                async {
                    try {
                        withTimeoutOrNull(8_000L) {
                            calculateAiSignal(sym, name, CandleTimeframe.H1)
                        }
                    } catch (e: Exception) {
                        Log.w("TradingRepo", "Failed to generate AI signal for $sym: ${e.message}")
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        if (results.isNotEmpty()) {
            _aiSignals.value = results
            // Sync to legacy _signals so Dashboard and other screens display real live signals
            _signals.value = results.map { it.toLegacySignal() }
        }

        results
    }
}

