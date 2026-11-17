package com.example.data.providers

import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class MarketInstrumentType(val label: String) {
    FUTURES_PERPETUAL("USDT-M Perpetual Futures"),
    SPOT("Spot Market"),
    GLOBAL_INDEX("Global Crypto Index"),
    DEFI_METRIC("DeFi On-Chain Metric"),
    SENTIMENT_INDEX("Sentiment Index"),
    NEWS_WIRE("Financial Wire")
}

enum class ProviderHealthStatus(val label: String) {
    ACTIVE("Active"),
    RATE_LIMITED("Rate Limited (Cooldown)"),
    QUOTA_EXHAUSTED_DISABLED("Disabled (Free Quota Exhausted)"),
    PAYMENT_REQUIRED_DISABLED("Disabled (Payment/Key Required)"),
    UNAVAILABLE("Unavailable")
}

enum class NewsReliabilityTier(val label: String, val score: Int) {
    HIGH("Tier-1 Wire / Official Agency", 3),
    MEDIUM("Specialist Crypto / Finance", 2),
    LOW("Unverified / Low Reliability", 1)
}

data class ProviderMetadata(
    val providerId: String,
    val providerName: String,
    val endpointUsed: String,
    val dataStatus: MarketDataStatus,
    val health: ProviderHealthStatus,
    val fetchedAtMillis: Long?,
    val timestampFormatted: String,
    val rateLimitNote: String? = null,
    val errorMessage: String? = null,
    val fromCache: Boolean = false
)

data class ProviderFetchOutcome<T>(
    val data: T?,
    val metadata: ProviderMetadata
)

data class NormalizedMarketQuote(
    val symbol: String,
    val baseAsset: String,
    val quoteCurrency: String,
    val marketType: MarketInstrumentType,
    val exchange: String,
    val lastPrice: Double,
    val markPrice: Double? = null,
    val indexPrice: Double? = null,
    val change24hPercent: Double? = null,
    val high24h: Double? = null,
    val low24h: Double? = null,
    val volume24hBase: Double? = null,
    val volume24hQuote: Double? = null,
    val fundingRatePercent: Double? = null,
    val openInterestContracts: Double? = null,
    val longShortRatio: Double? = null,
    val exchangeTimestampMillis: Long,
    val fetchedAtMillis: Long,
    val dataStatus: MarketDataStatus
) {
    val timestampUtcFormatted: String
        get() = formatUtcTimestamp(fetchedAtMillis)
}

data class CrossExchangeComparison(
    val symbol: String,
    val quoteCurrency: String,
    val marketType: MarketInstrumentType,
    val primaryQuote: NormalizedMarketQuote,
    val secondaryQuote: NormalizedMarketQuote?,
    val priceSpreadUsd: Double?,
    val spreadBps: Double?,
    val timestampSkewMs: Long?,
    val isComparable: Boolean,
    val validationNote: String
)

data class RankedCoinEntry(
    val rank: Int,
    val id: String,
    val symbol: String,
    val name: String,
    val currentPriceUsd: Double,
    val marketCapUsd: Double,
    val volume24hUsd: Double,
    val change24hPercent: Double,
    val marketType: MarketInstrumentType = MarketInstrumentType.SPOT
)

data class CoinGeckoGlobalSnapshot(
    val totalMarketCapUsd: Double,
    val totalMarketCapFormatted: String,
    val totalVolume24hUsd: Double,
    val totalVolume24hFormatted: String,
    val btcDominancePercent: Double,
    val btcDominanceFormatted: String,
    val ethDominancePercent: Double,
    val activeCryptocurrencies: Int,
    val topRankedCoins: List<RankedCoinEntry> = emptyList(),
    val btc7dChangePercent: Double? = null,
    val fetchedAtMillis: Long,
    val metadata: ProviderMetadata
)

data class ChainTvlEntry(
    val chainName: String,
    val tvlUsd: Double
)

data class DefiMarketSnapshot(
    val totalTvlUsd: Double,
    val totalTvlFormatted: String,
    val topChains: List<ChainTvlEntry>,
    val totalStablecoinSupplyUsd: Double,
    val totalStablecoinFormatted: String,
    val usdtDominancePercent: Double?,
    val usdcDominancePercent: Double?,
    val fetchedAtMillis: Long,
    val metadata: ProviderMetadata
)

data class FearGreedSnapshot(
    val value: Int,
    val classification: String,
    val previousDayValue: Int? = null,
    val sevenDayDelta: Int? = null,
    val apiTimestampSeconds: Long,
    val fetchedAtMillis: Long,
    val metadata: ProviderMetadata
)

data class FilteredNewsItem(
    val article: MarketNews,
    val domain: String,
    val reliabilityTier: NewsReliabilityTier,
    val relevanceScore: Int,
    val publishedMillis: Long?,
    val ageHours: Double?,
    val isVerifiedOfficialCause: Boolean = false,
    val causalDisclaimer: String = "Headline report only — not verified as the direct causal market driver without corroborating price/volume or official release data."
)

data class UnifiedHybridMarketState(
    val primaryFuturesQuotes: Map<String, NormalizedMarketQuote> = emptyMap(),
    val crossExchangeComparisons: Map<String, CrossExchangeComparison> = emptyMap(),
    val coinGeckoGlobal: CoinGeckoGlobalSnapshot? = null,
    val defiSnapshot: DefiMarketSnapshot? = null,
    val fearGreed: FearGreedSnapshot? = null,
    val filteredNews: List<FilteredNewsItem> = emptyList(),
    val providerStatuses: Map<String, ProviderMetadata> = emptyMap(),
    val assembledAtMillis: Long = 0L
) {
    val assembledAtUtc: String
        get() = if (assembledAtMillis > 0L) formatUtcTimestamp(assembledAtMillis) else "Not synced"
}

fun formatUtcTimestamp(millis: Long): String {
    val sdf = SimpleDateFormat("HH:mm:ss dd-MMM-yyyy 'UTC'", Locale.US)
    sdf.timeZone = TimeZone.getTimeZone("UTC")
    return sdf.format(Date(millis))
}

fun formatCompactUsd(value: Double): String {
    return when {
        value >= 1_000_000_000_000.0 -> String.format(Locale.US, "$%.2f Trillion", value / 1_000_000_000_000.0)
        value >= 1_000_000_000.0 -> String.format(Locale.US, "$%.2f Billion", value / 1_000_000_000.0)
        value >= 1_000_000.0 -> String.format(Locale.US, "$%.2f Million", value / 1_000_000.0)
        else -> String.format(Locale.US, "$%,.2f", value)
    }
}
