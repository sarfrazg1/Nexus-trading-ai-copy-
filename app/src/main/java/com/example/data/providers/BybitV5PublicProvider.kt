package com.example.data.providers

import com.example.data.model.MarketDataStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Secondary Cross-Exchange Public Market Provider: Bybit V5 Public API.
 * Strictly separates Linear USDT Perpetuals (category=linear) from Spot (category=spot).
 * Official endpoints only — zero API keys required.
 */
class BybitV5PublicProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: ProviderHttpSupport = ProviderHttpSupport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private val tickerCache = ConcurrentHashMap<String, TimedCacheEntry<String>>()

    suspend fun fetchLinearFuturesQuotes(
        symbols: Set<String> = emptySet()
    ): ProviderFetchOutcome<Map<String, NormalizedMarketQuote>> =
        fetchCategoryQuotes(category = "linear", marketType = MarketInstrumentType.FUTURES_PERPETUAL, symbols = symbols)

    suspend fun fetchSpotQuotes(
        symbols: Set<String> = emptySet()
    ): ProviderFetchOutcome<Map<String, NormalizedMarketQuote>> =
        fetchCategoryQuotes(category = "spot", marketType = MarketInstrumentType.SPOT, symbols = symbols)

    private suspend fun fetchCategoryQuotes(
        category: String,
        marketType: MarketInstrumentType,
        symbols: Set<String>
    ): ProviderFetchOutcome<Map<String, NormalizedMarketQuote>> = withContext(Dispatchers.IO) {
        val url = "$baseUrl/v5/market/tickers?category=$category"
        val providerKey = "${PROVIDER_ID}_$category"

        val outcome = http.getWithCacheAndRetry(
            providerId = providerKey,
            providerName = "$PROVIDER_NAME ($category)",
            url = url,
            cacheEntry = tickerCache[category],
            cacheTtlMillis = 15_000L,
            maxAttempts = 2
        )

        val body = outcome.data ?: return@withContext ProviderFetchOutcome(null, outcome.metadata)

        val parsed = runCatching {
            val root = JSONObject(body)
            val retCode = root.optInt("retCode", -1)
            val retMsg = root.optString("retMsg", "Unknown Bybit response")

            // Bybit retCode 10006 = Too many visits / rate limit exceeded
            if (retCode == 10006) {
                return@withContext ProviderFetchOutcome<Map<String, NormalizedMarketQuote>>(
                    data = null,
                    metadata = outcome.metadata.copy(
                        dataStatus = MarketDataStatus.UNAVAILABLE,
                        health = ProviderHealthStatus.RATE_LIMITED,
                        rateLimitNote = "Bybit retCode=10006 ($retMsg)",
                        errorMessage = "Bybit V5 rate limit: $retMsg"
                    )
                )
            }
            if (retCode != 0) {
                return@withContext ProviderFetchOutcome<Map<String, NormalizedMarketQuote>>(
                    data = null,
                    metadata = outcome.metadata.copy(
                        dataStatus = MarketDataStatus.UNAVAILABLE,
                        health = ProviderHealthStatus.UNAVAILABLE,
                        errorMessage = "Bybit retCode=$retCode: $retMsg"
                    )
                )
            }

            if (!outcome.metadata.fromCache && outcome.metadata.dataStatus == MarketDataStatus.LIVE) {
                tickerCache[category] = TimedCacheEntry(body, outcome.metadata.fetchedAtMillis ?: clockMillis(), url)
            }

            val serverTime = root.optLong("time", 0L).takeIf { it > 0L }
                ?: (outcome.metadata.fetchedAtMillis ?: clockMillis())
            val fetchedAt = outcome.metadata.fetchedAtMillis ?: clockMillis()
            val list = root.optJSONObject("result")?.optJSONArray("list") ?: return@runCatching emptyMap()

            val targetUpper = symbols.map { it.uppercase() }.toSet()
            val map = LinkedHashMap<String, NormalizedMarketQuote>()

            for (i in 0 until list.length()) {
                val obj = list.optJSONObject(i) ?: continue
                val sym = obj.optString("symbol").uppercase()
                if (!sym.endsWith("USDT")) continue
                if (targetUpper.isNotEmpty() && sym !in targetUpper) continue

                val lastPrice = obj.optString("lastPrice").toDoubleOrNull() ?: continue
                if (lastPrice <= 0.0) continue

                val pcnt24h = obj.optString("price24hPcnt").toDoubleOrNull()?.times(100.0)
                val funding = obj.optString("fundingRate").toDoubleOrNull()?.times(100.0)
                val oi = obj.optString("openInterest").toDoubleOrNull()
                val mark = obj.optString("markPrice").toDoubleOrNull()
                val index = obj.optString("indexPrice").toDoubleOrNull()

                map[sym] = NormalizedMarketQuote(
                    symbol = sym,
                    baseAsset = sym.removeSuffix("USDT"),
                    quoteCurrency = "USDT",
                    marketType = marketType,
                    exchange = EXCHANGE_LABEL,
                    lastPrice = lastPrice,
                    markPrice = mark,
                    indexPrice = index,
                    change24hPercent = pcnt24h,
                    high24h = obj.optString("highPrice24h").toDoubleOrNull(),
                    low24h = obj.optString("lowPrice24h").toDoubleOrNull(),
                    volume24hBase = obj.optString("volume24h").toDoubleOrNull(),
                    volume24hQuote = obj.optString("turnover24h").toDoubleOrNull(),
                    fundingRatePercent = if (marketType == MarketInstrumentType.FUTURES_PERPETUAL) funding else null,
                    openInterestContracts = if (marketType == MarketInstrumentType.FUTURES_PERPETUAL) oi else null,
                    exchangeTimestampMillis = serverTime,
                    fetchedAtMillis = fetchedAt,
                    dataStatus = outcome.metadata.dataStatus
                )
            }
            map
        }.getOrElse { ex ->
            return@withContext ProviderFetchOutcome(
                data = null,
                metadata = outcome.metadata.copy(
                    dataStatus = MarketDataStatus.UNAVAILABLE,
                    health = ProviderHealthStatus.UNAVAILABLE,
                    errorMessage = "Bybit JSON parse error: ${ex.message}"
                )
            )
        }

        ProviderFetchOutcome(
            data = parsed.takeIf { it.isNotEmpty() },
            metadata = if (parsed.isNotEmpty()) outcome.metadata else outcome.metadata.copy(
                dataStatus = MarketDataStatus.UNAVAILABLE,
                health = ProviderHealthStatus.UNAVAILABLE,
                errorMessage = "Bybit V5 returned 0 matching USDT pairs"
            )
        )
    }

    companion object {
        const val PROVIDER_ID = "bybit_v5_public"
        const val PROVIDER_NAME = "Bybit V5 Public Market"
        const val EXCHANGE_LABEL = "Bybit V5"
        const val DEFAULT_BASE_URL = "https://api.bybit.com"
    }
}
