package com.example.data.remote

import android.util.Log
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Public REST & WebSocket Client for Binance USDT-M Futures.
 * Requires ZERO API keys, tokens, or permissions. 100% read-only public market data.
 */
class BinanceFuturesApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
) {
    // In-memory candle cache: "SYMBOL_TIMEFRAME" -> CachedCandles
    private val candleCache = ConcurrentHashMap<String, CachedCandles>()
    private val candleProvenance = ConcurrentHashMap<String, CandleFetchProvenance>()

    private data class CachedCandles(
        val timestamp: Long,
        val candles: List<Candle>,
        val sourceName: String = "Binance USDT-M Futures Public REST",
        val isFuturesPrimary: Boolean = true
    )

    data class CandleFetchProvenance(
        val symbol: String,
        val timeframe: CandleTimeframe,
        val sourceName: String,
        val isFuturesPrimary: Boolean,
        val isFromStaleFallbackCache: Boolean,
        val fetchedAtMs: Long,
        val latestCompletedCloseMs: Long?
    )

    data class ResolvedCandleFetchStatus(
        val dataStatus: com.example.data.model.MarketDataStatus,
        val dataSourceName: String,
        val fetchedAtMs: Long,
        val latestCompletedCloseMs: Long
    )

    fun resolveCandleFetchStatus(
        symbol: String,
        timeframe: CandleTimeframe,
        candles: List<Candle>,
        nowMs: Long = System.currentTimeMillis()
    ): ResolvedCandleFetchStatus {
        val normalizedSymbol = symbol.uppercase().trim()
        val cacheKey = "${normalizedSymbol}_${timeframe.label}"
        val completed = com.example.util.TimeZoneUtils.filterCompletedCandles(candles, nowMs)
        val latestCloseMs = completed.lastOrNull()?.closeTime ?: 0L
        if (completed.isEmpty() || latestCloseMs <= 0L) {
            return ResolvedCandleFetchStatus(
                dataStatus = com.example.data.model.MarketDataStatus.UNAVAILABLE,
                dataSourceName = "DATA UNAVAILABLE",
                fetchedAtMs = 0L,
                latestCompletedCloseMs = 0L
            )
        }

        val prov = candleProvenance[cacheKey]
        val fetchedAtMs = prov?.fetchedAtMs ?: nowMs
        val sourceName = prov?.sourceName ?: "Binance USDT-M Futures Public REST"
        val isFuturesPrimary = prov?.isFuturesPrimary ?: true
        val isStaleCache = prov?.isFromStaleFallbackCache ?: false

        // A 24/7 candle stream is stale if the latest completed bar is more than 1 bar + 20 min behind nowMs
        val maxCompletedBarAgeMs = timeframe.durationMillis + 20L * 60_000L
        val barAgeMs = (nowMs - latestCloseMs).coerceAtLeast(0L)
        val fetchAgeMs = (nowMs - fetchedAtMs).coerceAtLeast(0L)

        val status = when {
            isStaleCache || barAgeMs > maxCompletedBarAgeMs || fetchAgeMs > 5L * 60_000L ->
                com.example.data.model.MarketDataStatus.STALE
            !isFuturesPrimary ->
                com.example.data.model.MarketDataStatus.DELAYED
            else ->
                com.example.data.model.MarketDataStatus.LIVE
        }
        return ResolvedCandleFetchStatus(
            dataStatus = status,
            dataSourceName = sourceName,
            fetchedAtMs = fetchedAtMs,
            latestCompletedCloseMs = latestCloseMs
        )
    }

    // Live price updates flow
    private val _livePrices = MutableSharedFlow<Map<String, Double>>(extraBufferCapacity = 64)
    val livePrices: SharedFlow<Map<String, Double>> = _livePrices.asSharedFlow()

    private val _isWebSocketConnected = MutableStateFlow(false)
    val isWebSocketConnected: StateFlow<Boolean> = _isWebSocketConnected.asStateFlow()

    private var webSocket: WebSocket? = null
    private val isWsActive = AtomicBoolean(false)
    private var reconnectAttempt = 0

    companion object {
        private const val TAG = "BinanceFuturesApi"
        private const val FUTURES_BASE_URL = "https://fapi.binance.com"
        private const val SPOT_FALLBACK_URL = "https://api.binance.com"
        private const val FUTURES_WS_URL = "wss://fstream.binance.com/ws/!miniTicker@arr"
        private const val SPOT_WS_FALLBACK = "wss://stream.binance.com:9443/ws/!miniTicker@arr"
    }

    /**
     * Fetches historical OHLCV candles for a given Binance USDT-M Futures pair.
     * Maps intervals directly to Binance standard: 4h, 1h, 15m, 5m.
     */
    suspend fun fetchFuturesCandles(
        symbol: String,
        timeframe: CandleTimeframe,
        limit: Int = 100
    ): List<Candle> = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val cacheKey = "${normalizedSymbol}_${timeframe.label}"
        val now = System.currentTimeMillis()

        // Use cache if under 15 seconds fresh
        val cached = candleCache[cacheKey]
        if (cached != null && now - cached.timestamp < 15_000) {
            candleProvenance[cacheKey] = CandleFetchProvenance(
                symbol = normalizedSymbol,
                timeframe = timeframe,
                sourceName = cached.sourceName,
                isFuturesPrimary = cached.isFuturesPrimary,
                isFromStaleFallbackCache = false,
                fetchedAtMs = cached.timestamp,
                latestCompletedCloseMs = com.example.util.TimeZoneUtils.filterCompletedCandles(cached.candles, now).lastOrNull()?.closeTime
            )
            return@withContext cached.candles
        }

        val endpoints = listOf(
            "$FUTURES_BASE_URL/fapi/v1/klines",
            "$SPOT_FALLBACK_URL/api/v3/klines"
        )

        for (endpoint in endpoints) {
            val isFuturesEndpoint = endpoint.contains("fapi")
            val endpointLabel = if (isFuturesEndpoint) {
                "Binance USDT-M Futures Public REST"
            } else {
                "Binance Spot Public REST (Fallback)"
            }
            try {
                val intervalStr = when (timeframe) {
                    CandleTimeframe.M5 -> "5m"
                    CandleTimeframe.M15 -> "15m"
                    CandleTimeframe.H1 -> "1h"
                    CandleTimeframe.H4 -> "4h"
                    CandleTimeframe.D1 -> "1d"
                    else -> timeframe.binanceInterval
                }

                val url = "$endpoint?symbol=$normalizedSymbol&interval=$intervalStr&limit=$limit"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Android; NexusTradingAI)")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        Log.w(TAG, "Rate limited on $endpoint, backing off...")
                        delay(2000L)
                        return@use
                    }

                    if (!response.isSuccessful) {
                        Log.w(TAG, "Failed $endpoint with HTTP ${response.code} for $normalizedSymbol")
                        return@use
                    }

                    val body = response.body?.string() ?: return@use
                    val array = JSONArray(body)
                    val candles = ArrayList<Candle>(array.length())

                    for (i in 0 until array.length()) {
                        val item = array.getJSONArray(i)
                        val openTime = item.getLong(0)
                        val open = item.getString(1).toDoubleOrNull() ?: 0.0
                        val high = item.getString(2).toDoubleOrNull() ?: 0.0
                        val low = item.getString(3).toDoubleOrNull() ?: 0.0
                        val close = item.getString(4).toDoubleOrNull() ?: 0.0
                        val volume = item.getString(5).toDoubleOrNull() ?: 0.0
                        val closeTime = item.getLong(6)
                        val quoteVolume = item.getString(7).toDoubleOrNull() ?: 0.0
                        val tradesCount = item.getInt(8)
                        val takerBuyBase = item.getString(9).toDoubleOrNull() ?: 0.0
                        val takerBuyQuote = item.getString(10).toDoubleOrNull() ?: 0.0

                        candles.add(
                            Candle(
                                openTime = openTime,
                                open = open,
                                high = high,
                                low = low,
                                close = close,
                                volume = volume,
                                closeTime = closeTime,
                                quoteVolume = quoteVolume,
                                tradesCount = tradesCount,
                                takerBuyBaseVolume = takerBuyBase,
                                takerBuyQuoteVolume = takerBuyQuote
                            )
                        )
                    }

                    if (candles.isNotEmpty()) {
                        val fetchedAt = System.currentTimeMillis()
                        candleCache[cacheKey] = CachedCandles(fetchedAt, candles, endpointLabel, isFuturesEndpoint)
                        candleProvenance[cacheKey] = CandleFetchProvenance(
                            symbol = normalizedSymbol,
                            timeframe = timeframe,
                            sourceName = endpointLabel,
                            isFuturesPrimary = isFuturesEndpoint,
                            isFromStaleFallbackCache = false,
                            fetchedAtMs = fetchedAt,
                            latestCompletedCloseMs = com.example.util.TimeZoneUtils.filterCompletedCandles(candles, fetchedAt).lastOrNull()?.closeTime
                        )
                        return@withContext candles
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching from $endpoint for $normalizedSymbol: ${e.message}")
            }
        }

        // Return stale cached candles explicitly flagged as stale cache fallback (never labeled LIVE)
        if (cached != null && cached.candles.isNotEmpty()) {
            candleProvenance[cacheKey] = CandleFetchProvenance(
                symbol = normalizedSymbol,
                timeframe = timeframe,
                sourceName = "${cached.sourceName} (Stale Cache)",
                isFuturesPrimary = cached.isFuturesPrimary,
                isFromStaleFallbackCache = true,
                fetchedAtMs = cached.timestamp,
                latestCompletedCloseMs = com.example.util.TimeZoneUtils.filterCompletedCandles(cached.candles, now).lastOrNull()?.closeTime
            )
            return@withContext cached.candles
        }
        candleProvenance.remove(cacheKey)
        emptyList()
    }

    data class FundingRateSnapshot(
        val ratePercent: Double,
        val serverTimeMs: Long? = null,
        val fetchedAtMs: Long = System.currentTimeMillis()
    )

    /**
     * Fetches Funding Rate and Mark Price timestamp for USDT-M Futures.
     * Returns null when missing, non-finite, or out of sanity bounds.
     */
    suspend fun fetchFundingRateSnapshot(symbol: String): FundingRateSnapshot? = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val url = "$FUTURES_BASE_URL/fapi/v1/premiumIndex?symbol=$normalizedSymbol"

        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val ratePct = json.optString("lastFundingRate").toDoubleOrNull()?.times(100.0)
                    if (ratePct == null || !ratePct.isFinite() || kotlin.math.abs(ratePct) > 5.0) {
                        return@withContext null
                    }
                    val serverTime = json.optLong("time", 0L).takeIf { it > 0L }
                    return@withContext FundingRateSnapshot(
                        ratePercent = ratePct,
                        serverTimeMs = serverTime,
                        fetchedAtMs = System.currentTimeMillis()
                    )
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Funding rate unavailable for $normalizedSymbol: ${e.message}")
        }
        null
    }

    /**
     * Fetches Funding Rate percentage for USDT-M Futures.
     */
    suspend fun fetchFundingRate(symbol: String): Double? = fetchFundingRateSnapshot(symbol)?.ratePercent

    data class DerivativesSnapshot(
        val symbol: String,
        val fundingRatePercent: Double?,
        val openInterestContracts: Double?,
        val longShortAccountRatio: Double?,
        val fetchedAtMillis: Long = System.currentTimeMillis(),
        val source: String = "Binance USDT-M Futures Public API"
    )

    /**
     * Fetches current Open Interest (in contracts/coins) for USDT-M Futures.
     * Public endpoint requiring zero API keys.
     */
    suspend fun fetchOpenInterest(symbol: String): Double? = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val url = "$FUTURES_BASE_URL/fapi/v1/openInterest?symbol=$normalizedSymbol"

        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    return@withContext json.optString("openInterest").toDoubleOrNull()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Open interest unavailable for $normalizedSymbol: ${e.message}")
        }
        null
    }

    /**
     * Fetches latest 1h global Long/Short account ratio for USDT-M Futures.
     * Public endpoint requiring zero API keys.
     */
    suspend fun fetchLongShortRatio(symbol: String): Double? = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val url = "$FUTURES_BASE_URL/futures/data/globalLongShortAccountRatio?symbol=$normalizedSymbol&period=1h&limit=1"

        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext null
                    val array = JSONArray(body)
                    if (array.length() > 0) {
                        return@withContext array.getJSONObject(0).optString("longShortRatio").toDoubleOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Long/short ratio unavailable for $normalizedSymbol: ${e.message}")
        }
        null
    }

    data class FuturesTicker24hr(
        val symbol: String,
        val lastPrice: Double,
        val priceChangePercent: Double,
        val quoteVolume: Double = 0.0
    )

    /**
     * Fetches 24h public USDT-M Futures tickers (with Spot REST fallback) for requested symbols.
     */
    suspend fun fetch24hrTickers(symbols: List<String>): Map<String, FuturesTicker24hr> = withContext(Dispatchers.IO) {
        val targetSet = symbols.map { it.uppercase().trim() }.filter { it.isNotEmpty() }.toSet()
        if (targetSet.isEmpty()) return@withContext emptyMap()
        val urls = listOf(
            "$FUTURES_BASE_URL/fapi/v1/ticker/24hr",
            "$SPOT_FALLBACK_URL/api/v3/ticker/24hr"
        )
        for (url in urls) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use
                    val body = response.body?.string() ?: return@use
                    val array = JSONArray(body)
                    val result = mutableMapOf<String, FuturesTicker24hr>()
                    for (i in 0 until array.length()) {
                        val obj = array.optJSONObject(i) ?: continue
                        val sym = obj.optString("symbol").uppercase()
                        if (sym in targetSet) {
                            val lastPrice = obj.optString("lastPrice").toDoubleOrNull() ?: continue
                            val chgPct = obj.optString("priceChangePercent").toDoubleOrNull() ?: 0.0
                            val qVol = obj.optString("quoteVolume").toDoubleOrNull() ?: 0.0
                            result[sym] = FuturesTicker24hr(
                                symbol = sym,
                                lastPrice = lastPrice,
                                priceChangePercent = chgPct,
                                quoteVolume = qVol
                            )
                        }
                    }
                    if (result.isNotEmpty()) return@withContext result
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching 24h tickers from $url: ${e.message}")
            }
        }
        emptyMap()
    }

    /**
     * Fetches funding rate, open interest, and long/short ratio together for a Binance Futures symbol.
     */
    suspend fun fetchDerivativesSnapshot(symbol: String): DerivativesSnapshot = withContext(Dispatchers.IO) {
        val funding = fetchFundingRate(symbol)
        val oi = fetchOpenInterest(symbol)
        val lsRatio = fetchLongShortRatio(symbol)
        DerivativesSnapshot(
            symbol = symbol.uppercase().trim(),
            fundingRatePercent = funding,
            openInterestContracts = oi,
            longShortAccountRatio = lsRatio,
            fetchedAtMillis = System.currentTimeMillis()
        )
    }

    /**
     * Fetches real USDT-M Futures top-N order book depth from /fapi/v1/depth.
     * Public endpoint requiring zero API keys. Returns null when unreachable or invalid.
     */
    suspend fun fetchOrderBookDepth(
        symbol: String,
        limit: Int = 20
    ): com.example.data.providers.OrderBookDepthSnapshot? = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val safeLimit = limit.coerceIn(5, 50)
        val url = "$FUTURES_BASE_URL/fapi/v1/depth?symbol=$normalizedSymbol&limit=$safeLimit"
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0 (Android; NexusTradingAI)")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                parseOrderBookDepth(normalizedSymbol, body, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            Log.d(TAG, "Order book depth unavailable for $normalizedSymbol: ${e.message}")
            null
        }
    }

    internal fun parseOrderBookDepth(
        symbol: String,
        jsonBody: String,
        fetchedAtMillis: Long
    ): com.example.data.providers.OrderBookDepthSnapshot? {
        return try {
            val obj = JSONObject(jsonBody)
            val bids = obj.optJSONArray("bids") ?: return null
            val asks = obj.optJSONArray("asks") ?: return null
            if (bids.length() == 0 || asks.length() == 0) return null

            var bestBid = 0.0
            var bestAsk = 0.0
            var bidNotional = 0.0
            var askNotional = 0.0
            var maxBidNotional = 0.0
            var maxAskNotional = 0.0
            var strongestBidPrice: Double? = null
            var strongestAskPrice: Double? = null

            for (i in 0 until bids.length()) {
                val lvl = bids.optJSONArray(i) ?: continue
                val p = lvl.optString(0).toDoubleOrNull() ?: continue
                val q = lvl.optString(1).toDoubleOrNull() ?: continue
                if (!p.isFinite() || !q.isFinite() || p <= 0.0 || q < 0.0) continue
                if (i == 0 || p > bestBid) bestBid = p
                val notional = p * q
                bidNotional += notional
                if (notional > maxBidNotional) {
                    maxBidNotional = notional
                    strongestBidPrice = p
                }
            }

            for (i in 0 until asks.length()) {
                val lvl = asks.optJSONArray(i) ?: continue
                val p = lvl.optString(0).toDoubleOrNull() ?: continue
                val q = lvl.optString(1).toDoubleOrNull() ?: continue
                if (!p.isFinite() || !q.isFinite() || p <= 0.0 || q < 0.0) continue
                if (bestAsk == 0.0 || p < bestAsk) bestAsk = p
                val notional = p * q
                askNotional += notional
                if (notional > maxAskNotional) {
                    maxAskNotional = notional
                    strongestAskPrice = p
                }
            }

            if (bestBid <= 0.0 || bestAsk <= 0.0 || bestAsk < bestBid) return null
            val mid = (bestBid + bestAsk) / 2.0
            val spreadBps = ((bestAsk - bestBid) / mid) * 10_000.0
            val imbalanceRatio = if (askNotional > 0.0) bidNotional / askNotional else 1.0

            com.example.data.providers.OrderBookDepthSnapshot(
                symbol = symbol,
                bestBid = bestBid,
                bestAsk = bestAsk,
                spreadBps = spreadBps,
                topBidsNotionalUsd = bidNotional,
                topAsksNotionalUsd = askNotional,
                bidAskImbalanceRatio = imbalanceRatio,
                strongestBidWallPrice = strongestBidPrice,
                strongestAskWallPrice = strongestAskPrice,
                fetchedAtMillis = fetchedAtMillis
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Connects to Binance Public Futures WebSocket stream for live ticker prices.
     */
    fun startWebSocket(monitoredSymbols: Set<String>) {
        if (isWsActive.getAndSet(true)) return
        connectWebSocket(monitoredSymbols)
    }

    fun stopWebSocket() {
        isWsActive.set(false)
        webSocket?.close(1000, "Scanner stopped")
        webSocket = null
        _isWebSocketConnected.value = false
    }

    private fun connectWebSocket(symbols: Set<String>) {
        if (!isWsActive.get()) return

        val endpoint = if (reconnectAttempt % 2 == 0) FUTURES_WS_URL else SPOT_WS_FALLBACK
        val request = Request.Builder().url(endpoint).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                _isWebSocketConnected.value = true
                reconnectAttempt = 0
                Log.d(TAG, "Futures WebSocket connected to $endpoint")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val array = JSONArray(text)
                    val priceMap = mutableMapOf<String, Double>()

                    for (i in 0 until array.length()) {
                        val item = array.optJSONObject(i) ?: continue
                        val sym = item.optString("s")
                        if (symbols.isEmpty() || sym in symbols) {
                            val price = item.optString("c").toDoubleOrNull() ?: continue
                            priceMap[sym] = price
                        }
                    }

                    if (priceMap.isNotEmpty()) {
                        _livePrices.tryEmit(priceMap)
                    }
                } catch (e: Exception) {
                    // Ignore parse jitter
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                _isWebSocketConnected.value = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _isWebSocketConnected.value = false
                scheduleReconnect(symbols)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                _isWebSocketConnected.value = false
                Log.w(TAG, "WebSocket error: ${t.message}. Reconnecting...")
                scheduleReconnect(symbols)
            }
        })
    }

    private fun scheduleReconnect(symbols: Set<String>) {
        if (!isWsActive.get()) return
        reconnectAttempt++
        val backoff = (1000L * (1 shl reconnectAttempt.coerceAtMost(5))).coerceAtMost(30000L)
        CoroutineScope(Dispatchers.IO).launch {
            delay(backoff)
            connectWebSocket(symbols)
        }
    }

    data class HistoricalFundingEntry(
        val symbol: String,
        val fundingTimeMs: Long,
        val fundingRateFraction: Double
    )

    internal fun parseKlinesJson(jsonBody: String): List<Candle> {
        val array = try {
            JSONArray(jsonBody)
        } catch (_: Exception) {
            return emptyList()
        }
        val candles = ArrayList<Candle>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONArray(i) ?: continue
            val openTime = item.optLong(0, 0L)
            val open = item.optString(1).toDoubleOrNull() ?: continue
            val high = item.optString(2).toDoubleOrNull() ?: continue
            val low = item.optString(3).toDoubleOrNull() ?: continue
            val close = item.optString(4).toDoubleOrNull() ?: continue
            val volume = item.optString(5).toDoubleOrNull() ?: 0.0
            val closeTime = item.optLong(6, openTime)
            val quoteVolume = item.optString(7).toDoubleOrNull() ?: 0.0
            val tradesCount = item.optInt(8, 0)
            val takerBuyBase = item.optString(9).toDoubleOrNull() ?: 0.0
            val takerBuyQuote = item.optString(10).toDoubleOrNull() ?: 0.0
            if (openTime <= 0L || !open.isFinite() || !high.isFinite() || !low.isFinite() || !close.isFinite()) {
                continue
            }
            candles.add(
                Candle(
                    openTime = openTime,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume,
                    closeTime = closeTime,
                    quoteVolume = quoteVolume,
                    tradesCount = tradesCount,
                    takerBuyBaseVolume = takerBuyBase,
                    takerBuyQuoteVolume = takerBuyQuote
                )
            )
        }
        return candles
    }

    internal fun parseFundingRatesJson(symbol: String, jsonBody: String): List<HistoricalFundingEntry> {
        val array = try {
            JSONArray(jsonBody)
        } catch (_: Exception) {
            return emptyList()
        }
        val result = ArrayList<HistoricalFundingEntry>(array.length())
        val norm = symbol.uppercase().trim()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val timeMs = obj.optLong("fundingTime", 0L)
            val rate = obj.optString("fundingRate").toDoubleOrNull()
            if (timeMs > 0L && rate != null && rate.isFinite()) {
                result.add(
                    HistoricalFundingEntry(
                        symbol = norm,
                        fundingTimeMs = timeMs,
                        fundingRateFraction = rate
                    )
                )
            }
        }
        return result.sortedBy { it.fundingTimeMs }
    }

    /**
     * Paginated historical candle fetcher supporting up to 6 months (~18,000+ 15M bars)
     * from public Binance Futures /fapi/v1/klines (with Spot /api/v3/klines fallback).
     */
    suspend fun fetchHistoricalCandlesPaginated(
        symbol: String,
        timeframe: CandleTimeframe,
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis(),
        maxBars: Int = 18_500
    ): List<Candle> = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val intervalStr = when (timeframe) {
            CandleTimeframe.M5 -> "5m"
            CandleTimeframe.M15 -> "15m"
            CandleTimeframe.H1 -> "1h"
            CandleTimeframe.H4 -> "4h"
            CandleTimeframe.D1 -> "1d"
            else -> timeframe.binanceInterval
        }

        val endpoints = listOf(
            "$FUTURES_BASE_URL/fapi/v1/klines",
            "$SPOT_FALLBACK_URL/api/v3/klines"
        )

        for (endpoint in endpoints) {
            val isFutures = endpoint.contains("fapi")
            val pageLimit = if (isFutures) 1500 else 1000
            val collected = LinkedHashMap<Long, Candle>()
            var cursorStart = startTimeMs
            var consecutiveFailures = 0

            while (cursorStart < endTimeMs && collected.size < maxBars && consecutiveFailures < 3) {
                val url = "$endpoint?symbol=$normalizedSymbol&interval=$intervalStr&startTime=$cursorStart&endTime=$endTimeMs&limit=$pageLimit"
                var batch: List<Candle> = emptyList()
                try {
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .header("User-Agent", "Mozilla/5.0 (Android; NexusTradingAI)")
                        .build()

                    client.newCall(request).execute().use { response ->
                        if (response.code == 429 || response.code == 418) {
                            consecutiveFailures++
                            delay(1500L)
                            return@use
                        }
                        if (!response.isSuccessful) {
                            consecutiveFailures++
                            return@use
                        }
                        val body = response.body?.string() ?: return@use
                        batch = parseKlinesJson(body)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Paginated fetch error ($endpoint $normalizedSymbol): ${e.message}")
                    consecutiveFailures++
                }

                if (batch.isEmpty()) {
                    if (consecutiveFailures > 0) continue else break
                }

                consecutiveFailures = 0
                batch.forEach { candle ->
                    if (candle.openTime in startTimeMs..endTimeMs) {
                        collected[candle.openTime] = candle
                    }
                }

                val lastOpen = batch.last().openTime
                val step = lastOpen + 1L
                if (step <= cursorStart || batch.size < pageLimit) break
                cursorStart = step
                delay(45L)
            }

            if (collected.isNotEmpty()) {
                return@withContext collected.values.sortedBy { it.openTime }
            }
        }

        emptyList()
    }

    /**
     * Fetches paginated historical 8-hour funding rate events from Binance USDT-M Futures
     * (/fapi/v1/fundingRate) for realistic backtest carry cost calculation.
     */
    suspend fun fetchHistoricalFundingRatesPaginated(
        symbol: String,
        startTimeMs: Long,
        endTimeMs: Long = System.currentTimeMillis()
    ): List<HistoricalFundingEntry> = withContext(Dispatchers.IO) {
        val normalizedSymbol = symbol.uppercase().trim()
        val collected = LinkedHashMap<Long, HistoricalFundingEntry>()
        var cursor = startTimeMs
        var pageCount = 0

        while (cursor < endTimeMs && pageCount < 8) {
            pageCount++
            val url = "$FUTURES_BASE_URL/fapi/v1/fundingRate?symbol=$normalizedSymbol&startTime=$cursor&endTime=$endTimeMs&limit=1000"
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Android; NexusTradingAI)")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext collected.values.sortedBy { it.fundingTimeMs }
                    val body = response.body?.string() ?: return@withContext collected.values.sortedBy { it.fundingTimeMs }
                    val batch = parseFundingRatesJson(normalizedSymbol, body)
                    if (batch.isEmpty()) return@withContext collected.values.sortedBy { it.fundingTimeMs }
                    batch.forEach { collected[it.fundingTimeMs] = it }
                    val nextCursor = batch.last().fundingTimeMs + 1L
                    if (nextCursor <= cursor || batch.size < 1000) {
                        return@withContext collected.values.sortedBy { it.fundingTimeMs }
                    }
                    cursor = nextCursor
                }
            } catch (e: Exception) {
                Log.d(TAG, "Historical funding fetch skipped for $normalizedSymbol: ${e.message}")
                break
            }
            delay(40L)
        }
        collected.values.sortedBy { it.fundingTimeMs }
    }
}
