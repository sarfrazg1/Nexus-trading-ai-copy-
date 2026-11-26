package com.example.data.remote

import android.util.Log
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.providers.BybitV5PublicProvider
import com.example.data.providers.MultiMarketInstrumentCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class CandleApiService(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val bybitProvider: BybitV5PublicProvider = BybitV5PublicProvider()
) {
    private val cache = ConcurrentHashMap<String, CachedCandles>()
    private val sourceBySymbol = ConcurrentHashMap<String, String>()

    private data class CachedCandles(
        val timestamp: Long,
        val candles: List<Candle>,
        val dataSource: String
    )

    fun lastDataSourceForSymbol(assetSymbol: String): String? {
        val canonical = MultiMarketInstrumentCatalog.normalizeCanonicalId(assetSymbol)
        return sourceBySymbol[canonical] ?: sourceBySymbol[assetSymbol.uppercase(Locale.US).trim()]
    }

    suspend fun fetchCandles(
        assetSymbol: String,
        timeframe: CandleTimeframe,
        limit: Int = 250
    ): List<Candle> = withContext(Dispatchers.IO) {
        val normalizedSymbol = assetSymbol.uppercase(Locale.US).trim()
        val spec = MultiMarketInstrumentCatalog.resolve(normalizedSymbol)
            ?: return@withContext emptyList<Candle>().also {
                sourceBySymbol[normalizedSymbol] = "DATA UNAVAILABLE (Unverified Instrument)"
            }

        val cacheKey = "${spec.canonicalId}_${timeframe.label}"
        val cached = cache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < 25_000L) {
            sourceBySymbol[spec.canonicalId] = cached.dataSource
            return@withContext cached.candles
        }

        try {
            val (candles, sourceLabel) = if (spec.category == AssetCategory.CRYPTO) {
                fetchCryptoCandlesWithHierarchy(spec.binanceSymbol!!, spec.bybitSymbol, timeframe, limit)
            } else {
                // Non-crypto (Commodities, Stocks, Forex, Indices): NEVER route to Binance Futures
                val yahooTicker = spec.yahooTicker
                val result = if (!yahooTicker.isNullOrBlank()) {
                    fetchYahooCandles(yahooTicker, timeframe)
                } else emptyList()
                val label = if (result.isNotEmpty()) {
                    spec.primaryVenue.displayName
                } else {
                    "DATA UNAVAILABLE (${spec.primaryVenue.displayName})"
                }
                result to label
            }

            sourceBySymbol[spec.canonicalId] = sourceLabel
            if (candles.isNotEmpty()) {
                val trimmed = if (candles.size > limit) candles.takeLast(limit) else candles
                cache[cacheKey] = CachedCandles(System.currentTimeMillis(), trimmed, sourceLabel)
                return@withContext trimmed
            }
            emptyList()
        } catch (e: Exception) {
            Log.e("CandleApiService", "Failed to fetch candles for ${spec.canonicalId}", e)
            cached?.let {
                sourceBySymbol[spec.canonicalId] = "${it.dataSource} (Cached)"
                return@withContext it.candles
            }
            sourceBySymbol[spec.canonicalId] = "DATA UNAVAILABLE (${e.message ?: "Network error"})"
            emptyList()
        }
    }

    private suspend fun fetchCryptoCandlesWithHierarchy(
        binancePair: String,
        bybitPair: String?,
        timeframe: CandleTimeframe,
        limit: Int
    ): Pair<List<Candle>, String> {
        // 1. Primary Crypto Venue: Binance USDT-M Futures
        val binanceFuturesCandles = fetchKlinesFromEndpoint(
            url = "https://fapi.binance.com/fapi/v1/klines?symbol=$binancePair&interval=${timeframe.binanceInterval}&limit=$limit",
            durationMs = timeframe.durationMillis
        )
        if (binanceFuturesCandles.isNotEmpty()) {
            return binanceFuturesCandles to "Binance USDT-M Futures"
        }

        // 2. Secondary Crypto Venue: Bybit V5 Linear Perpetuals
        if (!bybitPair.isNullOrBlank()) {
            val bybitOutcome = runCatching {
                bybitProvider.fetchLinearCandles(bybitPair, timeframe, limit)
            }.getOrNull()
            val bybitCandles = bybitOutcome?.data.orEmpty()
            if (bybitCandles.isNotEmpty()) {
                return bybitCandles to "Bybit V5 Linear"
            }
        }

        // 3. Spot Fallback: Binance Spot / Binance.US
        for (base in listOf("https://api.binance.com/api/v3/klines", "https://api.binance.us/api/v3/klines")) {
            val spotCandles = fetchKlinesFromEndpoint(
                url = "$base?symbol=$binancePair&interval=${timeframe.binanceInterval}&limit=$limit",
                durationMs = timeframe.durationMillis
            )
            if (spotCandles.isNotEmpty()) {
                val label = if (base.contains("binance.us")) "Binance.US Spot (Fallback)" else "Binance Spot (Fallback)"
                return spotCandles to label
            }
        }

        // 4. Last Resort Crypto Feed: Yahoo Finance
        val baseAsset = binancePair.removeSuffix("USDT")
        val yahooCandles = fetchYahooCandles("$baseAsset-USD", timeframe)
        if (yahooCandles.isNotEmpty()) {
            return yahooCandles to "Yahoo Finance Crypto (Delayed Fallback)"
        }

        return emptyList<Candle>() to "DATA UNAVAILABLE (All Crypto Venues Unreachable)"
    }

    private fun fetchKlinesFromEndpoint(
        url: String,
        durationMs: Long
    ): List<Candle> {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "Mozilla/5.0")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w("CandleApiService", "$url returned HTTP ${response.code}")
                    return emptyList()
                }
                val body = response.body?.string() ?: return emptyList()
                val array = JSONArray(body)
                val candles = mutableListOf<Candle>()

                for (i in 0 until array.length()) {
                    val item = array.getJSONArray(i)
                    val openTime = item.getLong(0)
                    val open = item.getString(1).toDoubleOrNull() ?: 0.0
                    val high = item.getString(2).toDoubleOrNull() ?: 0.0
                    val low = item.getString(3).toDoubleOrNull() ?: 0.0
                    val close = item.getString(4).toDoubleOrNull() ?: 0.0
                    val volume = item.getString(5).toDoubleOrNull() ?: 0.0
                    val closeTime = item.optLong(6, openTime + durationMs - 1L)
                    val quoteVolume = item.optString(7, "0").toDoubleOrNull() ?: 0.0
                    val tradesCount = item.optInt(8, 0)
                    val takerBuyBase = item.optString(9, "0").toDoubleOrNull() ?: 0.0
                    val takerBuyQuote = item.optString(10, "0").toDoubleOrNull() ?: 0.0

                    if (close > 0.0 && high >= low) {
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
                }
                candles
            }
        } catch (e: Exception) {
            Log.w("CandleApiService", "Failed $url: ${e.message}")
            emptyList()
        }
    }

    private fun fetchYahooCandles(
        ticker: String,
        timeframe: CandleTimeframe
    ): List<Candle> {
        val yahooInterval = timeframe.yahooInterval
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$ticker?interval=$yahooInterval&range=${timeframe.yahooRange}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile)")
            .header("Accept", "application/json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val chart = root.optJSONObject("chart") ?: return emptyList()
            val resultArr = chart.optJSONArray("result") ?: return emptyList()
            if (resultArr.length() == 0) return emptyList()

            val first = resultArr.getJSONObject(0)
            val timestamps = first.optJSONArray("timestamp") ?: return emptyList()
            val indicators = first.optJSONObject("indicators") ?: return emptyList()
            val quoteArr = indicators.optJSONArray("quote") ?: return emptyList()
            if (quoteArr.length() == 0) return emptyList()

            val quoteObj = quoteArr.getJSONObject(0)
            val opens = quoteObj.optJSONArray("open")
            val highs = quoteObj.optJSONArray("high")
            val lows = quoteObj.optJSONArray("low")
            val closes = quoteObj.optJSONArray("close")
            val volumes = quoteObj.optJSONArray("volume")

            val rawDurationMs = when (timeframe) {
                CandleTimeframe.H4 -> CandleTimeframe.H1.durationMillis
                else -> timeframe.durationMillis
            }

            val rawCandles = mutableListOf<Candle>()
            for (i in 0 until timestamps.length()) {
                val t = timestamps.getLong(i) * 1000L
                val o = opens?.optDouble(i, Double.NaN) ?: Double.NaN
                val h = highs?.optDouble(i, Double.NaN) ?: Double.NaN
                val l = lows?.optDouble(i, Double.NaN) ?: Double.NaN
                val c = closes?.optDouble(i, Double.NaN) ?: Double.NaN
                val v = volumes?.optDouble(i, 0.0) ?: 0.0

                if (!o.isNaN() && !h.isNaN() && !l.isNaN() && !c.isNaN() && c > 0.0) {
                    rawCandles.add(
                        Candle(
                            openTime = t,
                            open = o,
                            high = h,
                            low = l,
                            close = c,
                            volume = if (v.isNaN()) 0.0 else v,
                            closeTime = t + rawDurationMs - 1L
                        )
                    )
                }
            }

            return if (timeframe == CandleTimeframe.H4) {
                aggregateHourlyTo4H(rawCandles)
            } else {
                rawCandles
            }
        }
    }

    companion object {
        private const val FOUR_HOURS_MS = 4L * 60L * 60L * 1000L

        /**
         * Aggregates Yahoo Finance 60m bars into aligned 4H OHLCV bars so commodities/stocks/FX
         * get accurate 4-hour structure instead of 1-hour bars.
         */
        fun aggregateHourlyTo4H(hourlyCandles: List<Candle>): List<Candle> {
            if (hourlyCandles.isEmpty()) return emptyList()
            val sorted = hourlyCandles.sortedBy { it.openTime }
            val buckets = linkedMapOf<Long, MutableList<Candle>>()
            for (bar in sorted) {
                val bucketStart = (bar.openTime / FOUR_HOURS_MS) * FOUR_HOURS_MS
                buckets.getOrPut(bucketStart) { mutableListOf() }.add(bar)
            }
            return buckets.entries.map { (bucketStart, bars) ->
                val first = bars.first()
                val last = bars.last()
                Candle(
                    openTime = bucketStart,
                    open = first.open,
                    high = bars.maxOf { it.high },
                    low = bars.minOf { it.low },
                    close = last.close,
                    volume = bars.sumOf { it.volume },
                    closeTime = bucketStart + FOUR_HOURS_MS - 1L
                )
            }
        }
    }
}
