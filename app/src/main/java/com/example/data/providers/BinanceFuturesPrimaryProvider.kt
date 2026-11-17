package com.example.data.providers

import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Primary Live Trading Data Provider: Binance USDT-M Futures Public REST API.
 * Official documented read-only endpoints only — zero API keys or trading permissions required.
 */
class BinanceFuturesPrimaryProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: ProviderHttpSupport = ProviderHttpSupport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var tickersCache: TimedCacheEntry<String>? = null
    private var premiumCache: TimedCacheEntry<String>? = null
    private val openInterestCache = ConcurrentHashMap<String, TimedCacheEntry<String>>()
    private val longShortCache = ConcurrentHashMap<String, TimedCacheEntry<String>>()
    private val klinesCache = ConcurrentHashMap<String, TimedCacheEntry<String>>()

    suspend fun fetchFuturesQuotes(
        symbols: List<String> = DEFAULT_FUTURES_SYMBOLS
    ): ProviderFetchOutcome<Map<String, NormalizedMarketQuote>> = withContext(Dispatchers.IO) {
        val tickerUrl = "$baseUrl/fapi/v1/ticker/24hr"
        val tickerOutcome = http.getWithCacheAndRetry(
            providerId = PROVIDER_ID,
            providerName = PROVIDER_NAME,
            url = tickerUrl,
            cacheEntry = tickersCache,
            cacheTtlMillis = 12_000L,
            maxAttempts = 2
        )

        val tickerBody = tickerOutcome.data
        if (tickerBody.isNullOrBlank()) {
            return@withContext ProviderFetchOutcome(
                data = null,
                metadata = tickerOutcome.metadata
            )
        }
        if (!tickerOutcome.metadata.fromCache && tickerOutcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            tickersCache = TimedCacheEntry(tickerBody, tickerOutcome.metadata.fetchedAtMillis ?: clockMillis(), tickerUrl)
        }

        // Optional premiumIndex enrichment (markPrice + fundingRate) — non-blocking on failure
        val premiumMap = mutableMapOf<String, Pair<Double?, Double?>>()
        val premiumUrl = "$baseUrl/fapi/v1/premiumIndex"
        val premiumOutcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_premium",
            providerName = "$PROVIDER_NAME (Funding/Mark)",
            url = premiumUrl,
            cacheEntry = premiumCache,
            cacheTtlMillis = 20_000L,
            maxAttempts = 1
        )
        val premiumBody = premiumOutcome.data
        if (!premiumBody.isNullOrBlank()) {
            if (!premiumOutcome.metadata.fromCache && premiumOutcome.metadata.dataStatus == MarketDataStatus.LIVE) {
                premiumCache = TimedCacheEntry(premiumBody, premiumOutcome.metadata.fetchedAtMillis ?: clockMillis(), premiumUrl)
            }
            parsePremiumIndex(premiumBody, premiumMap)
        }

        val targetSet = symbols.map { it.uppercase() }.toSet()
        val fetchedAt = tickerOutcome.metadata.fetchedAtMillis ?: clockMillis()
        val quotes = parse24hrTickers(
            body = tickerBody,
            targetSymbols = targetSet,
            premiumMap = premiumMap,
            fetchedAtMillis = fetchedAt,
            status = tickerOutcome.metadata.dataStatus
        )

        val finalMeta = if (quotes.isEmpty()) {
            tickerOutcome.metadata.copy(
                dataStatus = MarketDataStatus.UNAVAILABLE,
                health = ProviderHealthStatus.UNAVAILABLE,
                errorMessage = "No matching USDT-M Futures symbols parsed from Binance response"
            )
        } else {
            tickerOutcome.metadata
        }

        ProviderFetchOutcome(
            data = quotes.takeIf { it.isNotEmpty() },
            metadata = finalMeta
        )
    }

    suspend fun fetchDerivativesForSymbol(
        symbol: String
    ): ProviderFetchOutcome<NormalizedMarketQuote> = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase().trim()
        val premUrl = "$baseUrl/fapi/v1/premiumIndex?symbol=$sym"
        val oiUrl = "$baseUrl/fapi/v1/openInterest?symbol=$sym"
        val lsUrl = "$baseUrl/futures/data/globalLongShortAccountRatio?symbol=$sym&period=1h&limit=1"

        val premOutcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_prem_$sym",
            providerName = PROVIDER_NAME,
            url = premUrl,
            cacheEntry = null,
            cacheTtlMillis = 15_000L,
            maxAttempts = 2
        )
        val oiOutcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_oi_$sym",
            providerName = PROVIDER_NAME,
            url = oiUrl,
            cacheEntry = openInterestCache[sym],
            cacheTtlMillis = 20_000L,
            maxAttempts = 2
        )
        if (!oiOutcome.metadata.fromCache && oiOutcome.data != null) {
            openInterestCache[sym] = TimedCacheEntry(oiOutcome.data, oiOutcome.metadata.fetchedAtMillis ?: clockMillis(), oiUrl)
        }

        val lsOutcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_ls_$sym",
            providerName = PROVIDER_NAME,
            url = lsUrl,
            cacheEntry = longShortCache[sym],
            cacheTtlMillis = 30_000L,
            maxAttempts = 1
        )
        if (!lsOutcome.metadata.fromCache && lsOutcome.data != null) {
            longShortCache[sym] = TimedCacheEntry(lsOutcome.data, lsOutcome.metadata.fetchedAtMillis ?: clockMillis(), lsUrl)
        }

        val premJson = premOutcome.data?.let { runCatching { JSONObject(it) }.getOrNull() }
        val oiJson = oiOutcome.data?.let { runCatching { JSONObject(it) }.getOrNull() }
        val lsArr = lsOutcome.data?.let { runCatching { JSONArray(it) }.getOrNull() }

        val markPrice = premJson?.optString("markPrice")?.toDoubleOrNull()
        val indexPrice = premJson?.optString("indexPrice")?.toDoubleOrNull()
        val fundingPct = premJson?.optString("lastFundingRate")?.toDoubleOrNull()?.times(100.0)
        val oiContracts = oiJson?.optString("openInterest")?.toDoubleOrNull()
        val lsRatio = lsArr?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("longShortRatio")?.toDoubleOrNull()
        val exchangeTs = premJson?.optLong("time", 0L)?.takeIf { it > 0L } ?: clockMillis()

        if (markPrice == null && fundingPct == null && oiContracts == null) {
            return@withContext ProviderFetchOutcome(
                data = null,
                metadata = premOutcome.metadata
            )
        }

        val fetchedAt = clockMillis()
        val base = sym.removeSuffix("USDT")
        val quote = NormalizedMarketQuote(
            symbol = sym,
            baseAsset = base,
            quoteCurrency = "USDT",
            marketType = MarketInstrumentType.FUTURES_PERPETUAL,
            exchange = EXCHANGE_LABEL,
            lastPrice = markPrice ?: indexPrice ?: 0.0,
            markPrice = markPrice,
            indexPrice = indexPrice,
            fundingRatePercent = fundingPct,
            openInterestContracts = oiContracts,
            longShortRatio = lsRatio,
            exchangeTimestampMillis = exchangeTs,
            fetchedAtMillis = fetchedAt,
            dataStatus = premOutcome.metadata.dataStatus
        )
        ProviderFetchOutcome(quote, premOutcome.metadata)
    }

    suspend fun fetchFuturesCandles(
        symbol: String,
        timeframe: CandleTimeframe,
        limit: Int = 100
    ): ProviderFetchOutcome<List<Candle>> = withContext(Dispatchers.IO) {
        val sym = symbol.uppercase().trim()
        val interval = timeframe.binanceInterval
        val key = "${sym}_${interval}_$limit"
        val url = "$baseUrl/fapi/v1/klines?symbol=$sym&interval=$interval&limit=$limit"

        val outcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_klines",
            providerName = PROVIDER_NAME,
            url = url,
            cacheEntry = klinesCache[key],
            cacheTtlMillis = 15_000L,
            maxAttempts = 2
        )
        val body = outcome.data ?: return@withContext ProviderFetchOutcome(null, outcome.metadata)
        if (!outcome.metadata.fromCache && outcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            klinesCache[key] = TimedCacheEntry(body, outcome.metadata.fetchedAtMillis ?: clockMillis(), url)
        }

        val candles = runCatching { parseKlines(body) }.getOrNull().orEmpty()
        ProviderFetchOutcome(
            data = candles.takeIf { it.isNotEmpty() },
            metadata = if (candles.isNotEmpty()) outcome.metadata else outcome.metadata.copy(
                dataStatus = MarketDataStatus.UNAVAILABLE,
                health = ProviderHealthStatus.UNAVAILABLE,
                errorMessage = "Failed parsing Binance Futures klines"
            )
        )
    }

    private fun parsePremiumIndex(body: String, target: MutableMap<String, Pair<Double?, Double?>>) {
        runCatching {
            val trimmed = body.trim()
            if (trimmed.startsWith("[")) {
                val arr = JSONArray(trimmed)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val sym = obj.optString("symbol")
                    val mark = obj.optString("markPrice").toDoubleOrNull()
                    val ratePct = obj.optString("lastFundingRate").toDoubleOrNull()?.times(100.0)
                    if (sym.isNotBlank()) target[sym] = mark to ratePct
                }
            }
        }
    }

    private fun parse24hrTickers(
        body: String,
        targetSymbols: Set<String>,
        premiumMap: Map<String, Pair<Double?, Double?>>,
        fetchedAtMillis: Long,
        status: MarketDataStatus
    ): Map<String, NormalizedMarketQuote> {
        val out = LinkedHashMap<String, NormalizedMarketQuote>()
        runCatching {
            val trimmed = body.trim()
            val arr = if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONArray().apply { put(JSONObject(trimmed)) }
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol").uppercase()
                if (targetSymbols.isNotEmpty() && sym !in targetSymbols) continue
                val last = obj.optString("lastPrice").toDoubleOrNull() ?: continue
                if (last <= 0.0) continue
                val prem = premiumMap[sym]
                val quoteVol = obj.optString("quoteVolume").toDoubleOrNull()
                val baseVol = obj.optString("volume").toDoubleOrNull()
                val closeTime = obj.optLong("closeTime", 0L).takeIf { it > 0L } ?: fetchedAtMillis

                out[sym] = NormalizedMarketQuote(
                    symbol = sym,
                    baseAsset = sym.removeSuffix("USDT"),
                    quoteCurrency = "USDT",
                    marketType = MarketInstrumentType.FUTURES_PERPETUAL,
                    exchange = EXCHANGE_LABEL,
                    lastPrice = last,
                    markPrice = prem?.first,
                    change24hPercent = obj.optString("priceChangePercent").toDoubleOrNull(),
                    high24h = obj.optString("highPrice").toDoubleOrNull(),
                    low24h = obj.optString("lowPrice").toDoubleOrNull(),
                    volume24hBase = baseVol,
                    volume24hQuote = quoteVol,
                    fundingRatePercent = prem?.second,
                    exchangeTimestampMillis = closeTime,
                    fetchedAtMillis = fetchedAtMillis,
                    dataStatus = status
                )
            }
        }
        return out
    }

    private fun parseKlines(body: String): List<Candle> {
        val arr = JSONArray(body)
        val candles = ArrayList<Candle>(arr.length())
        for (i in 0 until arr.length()) {
            val row = arr.getJSONArray(i)
            candles.add(
                Candle(
                    openTime = row.getLong(0),
                    open = row.getString(1).toDoubleOrNull() ?: 0.0,
                    high = row.getString(2).toDoubleOrNull() ?: 0.0,
                    low = row.getString(3).toDoubleOrNull() ?: 0.0,
                    close = row.getString(4).toDoubleOrNull() ?: 0.0,
                    volume = row.getString(5).toDoubleOrNull() ?: 0.0,
                    closeTime = row.getLong(6),
                    quoteVolume = row.getString(7).toDoubleOrNull() ?: 0.0,
                    tradesCount = row.getInt(8),
                    takerBuyBaseVolume = row.getString(9).toDoubleOrNull() ?: 0.0,
                    takerBuyQuoteVolume = row.getString(10).toDoubleOrNull() ?: 0.0
                )
            )
        }
        return candles
    }

    companion object {
        const val PROVIDER_ID = "binance_usdt_m_futures"
        const val PROVIDER_NAME = "Binance USDT-M Futures Public"
        const val EXCHANGE_LABEL = "Binance USDT-M Futures"
        const val DEFAULT_BASE_URL = "https://fapi.binance.com"

        val DEFAULT_FUTURES_SYMBOLS = listOf(
            "BTCUSDT", "ETHUSDT", "BNBUSDT", "SOLUSDT", "XRPUSDT",
            "DOGEUSDT", "ADAUSDT", "LINKUSDT", "AVAXUSDT", "TRXUSDT",
            "TONUSDT", "LTCUSDT", "DOTUSDT", "BCHUSDT", "SUIUSDT", "PAXGUSDT"
        )
    }
}
