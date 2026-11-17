package com.example.data.providers

import com.example.data.model.MarketDataStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Alternative.me Crypto Fear & Greed Sentiment Index Provider.
 * Official open endpoint: https://api.alternative.me/fng/?limit=7 (zero API keys required).
 */
class AlternativeMeFearGreedProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: ProviderHttpSupport = ProviderHttpSupport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var fngCache: TimedCacheEntry<String>? = null

    suspend fun fetchFearAndGreed(limit: Int = 7): ProviderFetchOutcome<FearGreedSnapshot> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/fng/?limit=$limit"
            val outcome = http.getWithCacheAndRetry(
                providerId = PROVIDER_ID,
                providerName = PROVIDER_NAME,
                url = url,
                cacheEntry = fngCache,
                cacheTtlMillis = 180_000L,
                maxAttempts = 2
            )

            val body = outcome.data ?: return@withContext ProviderFetchOutcome(null, outcome.metadata)
            if (!outcome.metadata.fromCache && outcome.metadata.dataStatus == MarketDataStatus.LIVE) {
                fngCache = TimedCacheEntry(body, outcome.metadata.fetchedAtMillis ?: clockMillis(), url)
            }

            val snapshot = runCatching {
                val root = JSONObject(body)
                val dataArr = root.optJSONArray("data") ?: return@runCatching null
                if (dataArr.length() == 0) return@runCatching null

                val latest = dataArr.getJSONObject(0)
                val value = latest.optString("value").toIntOrNull()
                    ?: latest.optInt("value", -1).takeIf { it >= 0 }
                    ?: return@runCatching null
                val label = latest.optString("value_classification", "Neutral").ifBlank { "Neutral" }
                val apiTsSec = latest.optString("timestamp").toLongOrNull()
                    ?: latest.optLong("timestamp", 0L)

                val prevDay = if (dataArr.length() > 1) {
                    dataArr.getJSONObject(1).optString("value").toIntOrNull()
                } else null
                val sevenDayAgo = if (dataArr.length() >= 7) {
                    dataArr.getJSONObject(dataArr.length() - 1).optString("value").toIntOrNull()
                } else prevDay

                val delta7d = sevenDayAgo?.let { value - it }
                val fetchedAt = outcome.metadata.fetchedAtMillis ?: clockMillis()

                FearGreedSnapshot(
                    value = value.coerceIn(0, 100),
                    classification = label,
                    previousDayValue = prevDay,
                    sevenDayDelta = delta7d,
                    apiTimestampSeconds = apiTsSec,
                    fetchedAtMillis = fetchedAt,
                    metadata = outcome.metadata
                )
            }.getOrNull()

            if (snapshot == null) {
                ProviderFetchOutcome(
                    data = null,
                    metadata = outcome.metadata.copy(
                        dataStatus = MarketDataStatus.UNAVAILABLE,
                        health = ProviderHealthStatus.UNAVAILABLE,
                        errorMessage = "Alternative.me Fear & Greed payload invalid"
                    )
                )
            } else {
                ProviderFetchOutcome(snapshot, outcome.metadata)
            }
        }

    companion object {
        const val PROVIDER_ID = "alternativeme_fng"
        const val PROVIDER_NAME = "Alternative.me Fear & Greed Index"
        const val DEFAULT_BASE_URL = "https://api.alternative.me"
    }
}
