package com.example.data.providers

import com.example.BuildConfig
import com.example.data.model.MarketDataStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * CoinGecko Demo / Free Public API Provider.
 * Fetches global market cap, dominance, active cryptocurrencies, top market-cap rankings,
 * and optional coin price history.
 *
 * Security: CoinGecko Demo API key is strictly OPTIONAL and read from BuildConfig (via AI Studio
 * Secrets / .env), NEVER hardcoded. Requests work on public v3 endpoints when omitted.
 */
class CoinGeckoDemoProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val demoApiKeyRaw: String = BuildConfig.COINGECKO_DEMO_API_KEY,
    private val http: ProviderHttpSupport = ProviderHttpSupport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var globalCache: TimedCacheEntry<String>? = null
    private var marketsCache: TimedCacheEntry<String>? = null

    private fun buildHeaders(): Map<String, String> {
        val resolvedKey = ProviderHttpSupport.resolveOptionalSecret(demoApiKeyRaw, KEY_PLACEHOLDER)
        return if (resolvedKey != null) mapOf("x-cg-demo-api-key" to resolvedKey) else emptyMap()
    }

    suspend fun fetchGlobalAndTopRankings(
        topLimit: Int = 15
    ): ProviderFetchOutcome<CoinGeckoGlobalSnapshot> = withContext(Dispatchers.IO) {
        val headers = buildHeaders()
        val globalUrl = "$baseUrl/api/v3/global"

        val globalOutcome = http.getWithCacheAndRetry(
            providerId = PROVIDER_ID,
            providerName = PROVIDER_NAME,
            url = globalUrl,
            headers = headers,
            cacheEntry = globalCache,
            cacheTtlMillis = 90_000L,
            maxAttempts = 2
        )

        val globalBody = globalOutcome.data
        if (globalBody.isNullOrBlank()) {
            return@withContext ProviderFetchOutcome(null, globalOutcome.metadata)
        }
        if (!globalOutcome.metadata.fromCache && globalOutcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            globalCache = TimedCacheEntry(globalBody, globalOutcome.metadata.fetchedAtMillis ?: clockMillis(), globalUrl)
        }

        // Fetch top ranked coins & 7d percentage change (best effort, never fails global result)
        val marketsUrl = "$baseUrl/api/v3/coins/markets?vs_currency=usd&order=market_cap_desc&per_page=$topLimit&page=1&sparkline=false&price_change_percentage=24h,7d"
        val marketsOutcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_markets",
            providerName = "$PROVIDER_NAME (Rankings)",
            url = marketsUrl,
            headers = headers,
            cacheEntry = marketsCache,
            cacheTtlMillis = 90_000L,
            maxAttempts = 1
        )
        val marketsBody = marketsOutcome.data
        if (!marketsBody.isNullOrBlank() && !marketsOutcome.metadata.fromCache && marketsOutcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            marketsCache = TimedCacheEntry(marketsBody, marketsOutcome.metadata.fetchedAtMillis ?: clockMillis(), marketsUrl)
        }

        val snapshot = runCatching {
            val root = JSONObject(globalBody)
            val data = root.optJSONObject("data") ?: return@runCatching null
            val capObj = data.optJSONObject("total_market_cap")
            val volObj = data.optJSONObject("total_volume")
            val domObj = data.optJSONObject("market_cap_percentage")

            val totalCap = capObj?.optDouble("usd", 0.0) ?: 0.0
            val totalVol = volObj?.optDouble("usd", 0.0) ?: 0.0
            val btcDom = domObj?.optDouble("btc", 0.0) ?: 0.0
            val ethDom = domObj?.optDouble("eth", 0.0) ?: 0.0
            val activeCoins = data.optInt("active_cryptocurrencies", 0)
            if (totalCap <= 0.0) return@runCatching null

            val rankings = if (!marketsBody.isNullOrBlank()) parseMarkets(marketsBody) else emptyList()
            val btc7d = marketsBody?.let { extractBtc7dChange(it) }
            val fetchedAt = globalOutcome.metadata.fetchedAtMillis ?: clockMillis()

            CoinGeckoGlobalSnapshot(
                totalMarketCapUsd = totalCap,
                totalMarketCapFormatted = formatCompactUsd(totalCap),
                totalVolume24hUsd = totalVol,
                totalVolume24hFormatted = formatCompactUsd(totalVol),
                btcDominancePercent = btcDom,
                btcDominanceFormatted = String.format(Locale.US, "%.1f%%", btcDom),
                ethDominancePercent = ethDom,
                activeCryptocurrencies = activeCoins,
                topRankedCoins = rankings,
                btc7dChangePercent = btc7d,
                fetchedAtMillis = fetchedAt,
                metadata = globalOutcome.metadata
            )
        }.getOrNull()

        if (snapshot == null) {
            ProviderFetchOutcome(
                data = null,
                metadata = globalOutcome.metadata.copy(
                    dataStatus = MarketDataStatus.UNAVAILABLE,
                    health = ProviderHealthStatus.UNAVAILABLE,
                    errorMessage = "CoinGecko /global response missing market cap fields"
                )
            )
        } else {
            ProviderFetchOutcome(snapshot, globalOutcome.metadata)
        }
    }

    suspend fun fetchCoinHistory7d(coinId: String): ProviderFetchOutcome<List<Pair<Long, Double>>> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/api/v3/coins/$coinId/market_chart?vs_currency=usd&days=7"
            val outcome = http.getWithCacheAndRetry(
                providerId = "${PROVIDER_ID}_history",
                providerName = "$PROVIDER_NAME (History)",
                url = url,
                headers = buildHeaders(),
                cacheEntry = null,
                cacheTtlMillis = 120_000L,
                maxAttempts = 1
            )
            val body = outcome.data ?: return@withContext ProviderFetchOutcome(null, outcome.metadata)
            val points = runCatching {
                val arr = JSONObject(body).optJSONArray("prices") ?: return@runCatching emptyList()
                val out = ArrayList<Pair<Long, Double>>(arr.length())
                for (i in 0 until arr.length()) {
                    val row = arr.optJSONArray(i) ?: continue
                    out.add(row.getLong(0) to row.getDouble(1))
                }
                out
            }.getOrDefault(emptyList())

            ProviderFetchOutcome(
                data = points.takeIf { it.isNotEmpty() },
                metadata = outcome.metadata
            )
        }

    private fun parseMarkets(body: String): List<RankedCoinEntry> {
        val arr = JSONArray(body)
        val out = ArrayList<RankedCoinEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val price = obj.optDouble("current_price", 0.0)
            if (price <= 0.0) continue
            out.add(
                RankedCoinEntry(
                    rank = obj.optInt("market_cap_rank", i + 1),
                    id = obj.optString("id"),
                    symbol = obj.optString("symbol").uppercase(Locale.US),
                    name = obj.optString("name"),
                    currentPriceUsd = price,
                    marketCapUsd = obj.optDouble("market_cap", 0.0),
                    volume24hUsd = obj.optDouble("total_volume", 0.0),
                    change24hPercent = obj.optDouble("price_change_percentage_24h", 0.0),
                    marketType = MarketInstrumentType.SPOT
                )
            )
        }
        return out
    }

    private fun extractBtc7dChange(body: String): Double? {
        return runCatching {
            val arr = JSONArray(body)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                if (obj.optString("id") == "bitcoin") {
                    val v = obj.optDouble("price_change_percentage_7d_in_currency", Double.NaN)
                    if (!v.isNaN()) return@runCatching v
                }
            }
            null
        }.getOrNull()
    }

    companion object {
        const val PROVIDER_ID = "coingecko_demo"
        const val PROVIDER_NAME = "CoinGecko v3 Market Cap & Rankings"
        const val DEFAULT_BASE_URL = "https://api.coingecko.com"
        const val KEY_PLACEHOLDER = "OPTIONAL_COINGECKO_DEMO_KEY"
    }
}
