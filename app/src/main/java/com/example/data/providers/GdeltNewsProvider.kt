package com.example.data.providers

import com.example.data.model.EventStatus
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.news.NewsImpactAnalyzer
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * GDELT Project v2 Financial & Macroeconomic News Provider.
 * Enforces strict filtering for:
 * 1. Crypto/macro/ETF/regulatory/exchange keyword relevance (drops noisy non-market articles).
 * 2. Publication recency (drops stale articles older than maxAgeHours when timestamp present).
 * 3. Domain reliability tier classification (HIGH / MEDIUM / LOW).
 * 4. Explicit non-causal labeling: headlines are NEVER treated as proven causes of market moves.
 * 5. All displayed timestamps formatted in Pakistan Standard Time (Asia/Karachi, PKT).
 */
class GdeltNewsProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val http: ProviderHttpSupport = ProviderHttpSupport(
        client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    ),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private var newsCache: TimedCacheEntry<String>? = null

    suspend fun fetchFilteredNews(
        maxAgeHours: Double = 72.0,
        minRelevanceScore: Int = 2,
        includeLowReliability: Boolean = false
    ): ProviderFetchOutcome<List<FilteredNewsItem>> = withContext(Dispatchers.IO) {
        val rawQuery = "(bitcoin OR crypto OR ethereum OR solana OR \"federal reserve\" OR fomc OR inflation OR cpi OR ppi OR etf OR binance OR coinbase OR sec OR regulation OR sanctions OR gold OR \"crude oil\" OR stablecoin) sourcelang:english"
        val encodedQuery = URLEncoder.encode(rawQuery, "UTF-8")
        val url = "$baseUrl/api/v2/doc/doc?query=$encodedQuery&mode=artlist&maxrecords=30&timespan=72h&format=json&sort=DateDesc"

        val outcome = http.getWithCacheAndRetry(
            providerId = PROVIDER_ID,
            providerName = PROVIDER_NAME,
            url = url,
            cacheEntry = newsCache,
            cacheTtlMillis = 180_000L,
            maxAttempts = 1
        )

        val body = outcome.data ?: return@withContext ProviderFetchOutcome(null, outcome.metadata)
        if (!outcome.metadata.fromCache && outcome.metadata.dataStatus == MarketDataStatus.LIVE) {
            newsCache = TimedCacheEntry(body, outcome.metadata.fetchedAtMillis ?: clockMillis(), url)
        }

        val now = clockMillis()
        val items = runCatching {
            filterAndParseArticles(
                body = body,
                nowMillis = now,
                maxAgeHours = maxAgeHours,
                minRelevanceScore = minRelevanceScore,
                includeLowReliability = includeLowReliability
            )
        }.getOrElse { ex ->
            return@withContext ProviderFetchOutcome(
                data = null,
                metadata = outcome.metadata.copy(
                    dataStatus = MarketDataStatus.UNAVAILABLE,
                    health = ProviderHealthStatus.UNAVAILABLE,
                    errorMessage = "GDELT JSON parse error: ${ex.message}"
                )
            )
        }

        ProviderFetchOutcome(
            data = items,
            metadata = outcome.metadata
        )
    }

    internal fun filterAndParseArticles(
        body: String,
        nowMillis: Long,
        maxAgeHours: Double = 72.0,
        minRelevanceScore: Int = 2,
        includeLowReliability: Boolean = false
    ): List<FilteredNewsItem> {
        val root = JSONObject(body)
        val articles = root.optJSONArray("articles") ?: return emptyList()
        val results = mutableListOf<FilteredNewsItem>()
        val seenTitles = HashSet<String>()

        for (i in 0 until articles.length()) {
            val art = articles.optJSONObject(i) ?: continue
            val title = art.optString("title", "").trim()
            if (title.isBlank()) continue
            val normalizedKey = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").take(48)
            if (!seenTitles.add(normalizedKey)) continue

            val domain = art.optString("domain", "").lowercase(Locale.US).trim()
            val articleUrl = art.optString("url", "")
            val seendate = art.optString("seendate", "")

            val publishedMs = parseGdeltTimestampMillis(seendate)
            val ageHours = publishedMs?.let { (nowMillis - it) / 3_600_000.0 }
            if (ageHours != null && ageHours > maxAgeHours) continue

            val relevance = computeRelevanceScore(title)
            if (relevance < minRelevanceScore) continue

            val reliability = classifyDomainReliability(domain)
            if (!includeLowReliability && reliability == NewsReliabilityTier.LOW) continue

            val publishedPkt = publishedMs?.let { TimeZoneUtils.formatKarachiShort(it) }
                ?: "Publication Time Unverified (${TimeZoneUtils.ASIA_KARACHI_ID})"
            val retrievedPkt = TimeZoneUtils.formatKarachiShort(nowMillis)
            val isStaleWarning = ageHours != null && ageHours > 24.0
            val formattedDate = if (isStaleWarning) "Published: $publishedPkt (Older Archive)" else "Published: $publishedPkt"
            val rawNews = MarketNews(
                id = "gdelt_${i}_${seendate.take(10)}",
                title = title,
                source = "${domain.ifBlank { "Financial Wire" }} (${reliability.label})",
                timestamp = formattedDate,
                category = inferCategory(title),
                summary = "Wire headline from $domain (Relevance $relevance • ${reliability.label} • Published $publishedPkt | Retrieved $retrievedPkt). Partial/Summary dispatch — tap to fetch full article if publisher permits.",
                impact = NewsImpact.NEUTRAL,
                fullContent = "Confirmed Dispatch Metadata:\n• Outlet: $domain (${reliability.label})\n• Publication Time (${TimeZoneUtils.ASIA_KARACHI_ID}): $publishedPkt\n• App Retrieval Time (${TimeZoneUtils.ASIA_KARACHI_ID}): $retrievedPkt\n• Relevance Score: $relevance\n\nPartial Wire Notice: GDELT provides indexed headline metadata; complete article body is fetched live from the publisher URL when technically & legally accessible.\n\nURL: $articleUrl",
                url = articleUrl,
                eventStatus = EventStatus.UNVERIFIED,
                analysisMethod = "GDELT Filtered Wire (${reliability.name})",
                publishedTimestampMs = publishedMs,
                publishedTimestampPkt = publishedPkt,
                retrievedTimestampMs = nowMillis,
                retrievedTimestampPkt = retrievedPkt,
                contentStatus = com.example.data.model.ArticleContentStatus.PARTIAL_SUMMARY_ONLY
            )

            val enriched = NewsImpactAnalyzer.enrichNewsItem(rawNews)
            results.add(
                FilteredNewsItem(
                    article = enriched,
                    domain = domain,
                    reliabilityTier = reliability,
                    relevanceScore = relevance,
                    publishedMillis = publishedMs,
                    ageHours = ageHours,
                    isVerifiedOfficialCause = false
                )
            )
        }

        return results.sortedWith(
            compareByDescending<FilteredNewsItem> { it.reliabilityTier.score }
                .thenByDescending { it.relevanceScore }
                .thenByDescending { it.publishedMillis ?: 0L }
        )
    }

    fun computeRelevanceScore(title: String): Int {
        val lower = title.lowercase(Locale.US)
        val highWeightTerms = listOf(
            "bitcoin", "btc", "ethereum", "eth", "crypto", "federal reserve", "fomc",
            "inflation", "cpi", "ppi", "pce", "interest rate", "rate cut", "rate hike",
            "etf", "sec ", "sec,", "cftc", "mica", "regulation", "gold", "crude oil",
            "opec", "treasury yield", "stablecoin", "usdt", "usdc", "defi", "liquidation",
            "futures", "binance", "coinbase", "bybit", "okx", "kraken", "blackrock",
            "sanctions", "tariff", "geopolitical"
        )
        val secondaryTerms = listOf(
            "solana", "xrp", "bnb", "altcoin", "market", "rally", "selloff", "crash",
            "bull", "bear", "powell", "payroll", "nfp", "dollar", "dxy", "gdp",
            "recession", "institutional", "custody", "inflow", "outflow"
        )
        var score = 0
        for (term in highWeightTerms) {
            if (lower.contains(term)) score += 2
        }
        for (term in secondaryTerms) {
            if (lower.contains(term)) score += 1
        }
        return score
    }

    fun classifyDomainReliability(domain: String): NewsReliabilityTier {
        val d = domain.lowercase(Locale.US)
        val tier1Domains = listOf(
            "reuters.com", "bloomberg.com", "ft.com", "wsj.com", "cnbc.com",
            "apnews.com", "marketwatch.com", "barrons.com", "federalreserve.gov",
            "bls.gov", "sec.gov", "cftc.gov", "treasury.gov", "coindesk.com", "theblock.co",
            "cointelegraph.com", "decrypt.co", "blockworks.co", "finance.yahoo.com",
            "yahoo.com", "bbc.com", "bbc.co.uk", "nytimes.com", "axios.com",
            "fortune.com", "dlnews.com", "theguardian.com", "binance.com", "coinbase.com"
        )
        if (tier1Domains.any { d == it || d.endsWith(".$it") }) return NewsReliabilityTier.HIGH

        val tier2Domains = listOf(
            "forbes.com", "businessinsider.com", "investing.com", "fxstreet.com",
            "kitco.com", "nasdaq.com", "seekingalpha.com", "coinmarketcap.com",
            "coingecko.com", "benzinga.com", "fool.com", "economist.com",
            "economictimes.indiatimes.com", "indiatimes.com", "livemint.com",
            "moneycontrol.com", "businesstoday.in", "cryptoslate.com",
            "bitcoinmagazine.com", "unchainedcrypto.com", "thestreet.com", "bybit.com"
        )
        if (tier2Domains.any { d == it || d.endsWith(".$it") }) return NewsReliabilityTier.MEDIUM

        return NewsReliabilityTier.LOW
    }

    private fun inferCategory(title: String): String {
        val l = title.lowercase(Locale.US)
        return when {
            l.contains("etf") || l.contains("blackrock") || l.contains("institutional") -> "ETF & Institutional"
            l.contains("sec ") || l.contains("cftc") || l.contains("mica") || l.contains("binance") || l.contains("coinbase") || l.contains("regulat") -> "Regulation & Exchanges"
            l.contains("gold") || l.contains("oil") || l.contains("crude") || l.contains("opec") -> "Commodities"
            l.contains("fed") || l.contains("cpi") || l.contains("ppi") || l.contains("rate") || l.contains("inflation") || l.contains("sanction") || l.contains("tariff") -> "Macro & Central Banks"
            l.contains("defi") || l.contains("stablecoin") -> "DeFi & Stablecoins"
            else -> "Crypto Markets"
        }
    }

    private fun parseGdeltTimestampMillis(seendate: String): Long? {
        if (seendate.length < 14) return null
        return runCatching {
            val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            fmt.parse(seendate.take(16))?.time
        }.getOrNull()
    }

    companion object {
        const val PROVIDER_ID = "gdelt_news_wire"
        const val PROVIDER_NAME = "GDELT Project Financial Wire"
        const val DEFAULT_BASE_URL = "https://api.gdeltproject.org"
    }
}

