package com.example.data.providers

import com.example.data.model.MarketDataStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * DefiLlama Public On-Chain TVL & Stablecoin Liquidity Provider.
 * Official open endpoints (no API keys required):
 * - https://api.llama.fi/v2/chains
 * - https://stablecoins.llama.fi/stablecoins?includePrices=false
 */
class DefiLlamaProvider(
    private val llamaBaseUrl: String = DEFAULT_LLAMA_BASE_URL,
    private val stablecoinsBaseUrl: String = DEFAULT_STABLECOINS_BASE_URL,
    private val http: ProviderHttpSupport = ProviderHttpSupport(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var chainsCache: TimedCacheEntry<String>? = null
    private var stablecoinsCache: TimedCacheEntry<String>? = null

    suspend fun fetchDefiAndStablecoins(): ProviderFetchOutcome<DefiMarketSnapshot> = withContext(Dispatchers.IO) {
        val chainsUrl = "$llamaBaseUrl/v2/chains"
        val stableUrl = "$stablecoinsBaseUrl/stablecoins?includePrices=false"

        val chainsOutcome = http.getWithCacheAndRetry(
            providerId = PROVIDER_ID,
            providerName = PROVIDER_NAME,
            url = chainsUrl,
            cacheEntry = chainsCache,
            cacheTtlMillis = 180_000L,
            maxAttempts = 2
        )

        val chainsBody = chainsOutcome.data
        if (chainsBody.isNullOrBlank()) {
            return@withContext ProviderFetchOutcome(null, chainsOutcome.metadata)
        }
        if (!chainsOutcome.metadata.fromCache && chainsOutcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            chainsCache = TimedCacheEntry(chainsBody, chainsOutcome.metadata.fetchedAtMillis ?: clockMillis(), chainsUrl)
        }

        val stableOutcome = http.getWithCacheAndRetry(
            providerId = "${PROVIDER_ID}_stables",
            providerName = "$PROVIDER_NAME (Stablecoins)",
            url = stableUrl,
            cacheEntry = stablecoinsCache,
            cacheTtlMillis = 180_000L,
            maxAttempts = 2
        )
        val stableBody = stableOutcome.data
        if (!stableBody.isNullOrBlank() && !stableOutcome.metadata.fromCache && stableOutcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            stablecoinsCache = TimedCacheEntry(stableBody, stableOutcome.metadata.fetchedAtMillis ?: clockMillis(), stableUrl)
        }

        val snapshot = runCatching {
            val chainsArr = JSONArray(chainsBody)
            var totalTvl = 0.0
            val chainEntries = mutableListOf<ChainTvlEntry>()
            for (i in 0 until chainsArr.length()) {
                val obj = chainsArr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val tvl = obj.optDouble("tvl", 0.0)
                if (name.isNotBlank() && tvl > 0.0) {
                    totalTvl += tvl
                    chainEntries.add(ChainTvlEntry(name, tvl))
                }
            }
            if (totalTvl <= 0.0) return@runCatching null

            val topChains = chainEntries.sortedByDescending { it.tvlUsd }.take(5)

            var totalStables = 0.0
            var usdtSupply = 0.0
            var usdcSupply = 0.0
            if (!stableBody.isNullOrBlank()) {
                val pegged = JSONObject(stableBody).optJSONArray("peggedAssets")
                if (pegged != null) {
                    for (i in 0 until pegged.length()) {
                        val asset = pegged.optJSONObject(i) ?: continue
                        val sym = asset.optString("symbol").uppercase()
                        val circ = asset.optJSONObject("circulating")?.optDouble("peggedUSD", 0.0) ?: 0.0
                        if (circ > 0.0) {
                            totalStables += circ
                            if (sym == "USDT") usdtSupply = circ
                            if (sym == "USDC") usdcSupply = circ
                        }
                    }
                }
            }

            val usdtDom = if (totalStables > 0.0 && usdtSupply > 0.0) (usdtSupply / totalStables) * 100.0 else null
            val usdcDom = if (totalStables > 0.0 && usdcSupply > 0.0) (usdcSupply / totalStables) * 100.0 else null
            val fetchedAt = chainsOutcome.metadata.fetchedAtMillis ?: clockMillis()

            DefiMarketSnapshot(
                totalTvlUsd = totalTvl,
                totalTvlFormatted = formatCompactUsd(totalTvl),
                topChains = topChains,
                totalStablecoinSupplyUsd = totalStables,
                totalStablecoinFormatted = if (totalStables > 0.0) formatCompactUsd(totalStables) else "Unavailable",
                usdtDominancePercent = usdtDom,
                usdcDominancePercent = usdcDom,
                fetchedAtMillis = fetchedAt,
                metadata = chainsOutcome.metadata
            )
        }.getOrNull()

        if (snapshot == null) {
            ProviderFetchOutcome(
                data = null,
                metadata = chainsOutcome.metadata.copy(
                    dataStatus = MarketDataStatus.UNAVAILABLE,
                    health = ProviderHealthStatus.UNAVAILABLE,
                    errorMessage = "DefiLlama TVL payload invalid or empty"
                )
            )
        } else {
            ProviderFetchOutcome(snapshot, chainsOutcome.metadata)
        }
    }

    companion object {
        const val PROVIDER_ID = "defillama_public"
        const val PROVIDER_NAME = "DefiLlama DeFi TVL & Stablecoins"
        const val DEFAULT_LLAMA_BASE_URL = "https://api.llama.fi"
        const val DEFAULT_STABLECOINS_BASE_URL = "https://stablecoins.llama.fi"
    }
}
