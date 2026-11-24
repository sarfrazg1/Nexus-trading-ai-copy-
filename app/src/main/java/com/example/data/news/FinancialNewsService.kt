package com.example.data.news

import com.example.data.model.EventStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.data.providers.FilteredNewsItem
import com.example.data.providers.GdeltNewsProvider
import com.example.data.providers.ProviderMetadata
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

class FinancialNewsService(
    private val gdeltProvider: GdeltNewsProvider = GdeltNewsProvider(),
    private val articleFetcher: ArticleContentFetcher = ArticleContentFetcher()
) {

    private var cachedNews: List<MarketNews> = emptyList()
    private var lastFetchTimestamp: Long = 0L
    private val seenHeadlineKeys: MutableSet<String> = mutableSetOf()
    private val enrichedDetailCache = java.util.concurrent.ConcurrentHashMap<String, MarketNews>()

    @Volatile
    var lastFetchWasLive: Boolean = false
        private set

    @Volatile
    var lastFetchTimeFormatted: String = "Preloaded Reference (${TimeZoneUtils.ASIA_KARACHI_ID})"
        private set

    @Volatile
    var lastFilteredNews: List<FilteredNewsItem> = emptyList()
        private set

    @Volatile
    var lastGdeltMetadata: ProviderMetadata? = null
        private set

    suspend fun fetchMarketNews(forceRefresh: Boolean = false): List<MarketNews> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // Cache for 3 minutes unless force refreshed
        if (!forceRefresh && cachedNews.isNotEmpty() && (now - lastFetchTimestamp < 180_000)) {
            return@withContext cachedNews
        }

        val outcome = gdeltProvider.fetchFilteredNews()
        lastGdeltMetadata = outcome.metadata
        val filteredItems = outcome.data.orEmpty()
        lastFilteredNews = filteredItems

        val rawCombined = if (filteredItems.isNotEmpty()) {
            lastFetchWasLive = true
            lastFetchTimeFormatted = TimeZoneUtils.formatKarachiTimestamp(outcome.metadata.fetchedAtMillis ?: now)
            filteredItems.map { item ->
                enrichedDetailCache[item.article.id]?.let { cached ->
                    cached.copy(isPreviouslySeen = item.article.isPreviouslySeen)
                } ?: item.article
            }
        } else {
            lastFetchWasLive = false
            lastFetchTimeFormatted = outcome.metadata.errorMessage?.let {
                "Live GDELT unavailable ($it) — static reference (${TimeZoneUtils.ASIA_KARACHI_ID})"
            } ?: "Live GDELT unavailable — static reference (${TimeZoneUtils.ASIA_KARACHI_ID})"
            getVerifiedMacroNews()
        }

        val combined = markSeenAndDeduplicate(rawCombined)
        cachedNews = combined
        lastFetchTimestamp = now
        combined
    }

    /**
     * Fetches complete readable article text from original publisher URL when legally & technically accessible.
     * Clearly preserves PARTIAL_SUMMARY_ONLY label if blocked, paywalled, JS-gated, or summary-only.
     */
    suspend fun fetchFullArticleForDetail(news: MarketNews): MarketNews = withContext(Dispatchers.IO) {
        enrichedDetailCache[news.id]?.let { cached ->
            return@withContext cached.copy(isSaved = news.isSaved, isPreviouslySeen = news.isPreviouslySeen)
        }
        if (news.contentStatus == com.example.data.model.ArticleContentStatus.COMPLETE && news.fullContent.length >= 360) {
            return@withContext news
        }

        val extracted = articleFetcher.fetchArticleBody(
            url = news.url,
            fallbackSummary = news.fullContent.ifBlank { news.summary }
        )

        val updated = NewsImpactAnalyzer.enrichNewsItem(
            news.copy(
                fullContent = extracted.extractedText,
                contentStatus = extracted.contentStatus,
                fetchedArticleCharCount = extracted.charCount,
                analysisMethod = if (extracted.contentStatus == com.example.data.model.ArticleContentStatus.COMPLETE) {
                    "Complete Publisher Article + NLP Impact"
                } else {
                    "${news.analysisMethod} • ${extracted.fetchNote}"
                }
            )
        )
        enrichedDetailCache[news.id] = updated
        cachedNews = cachedNews.map { if (it.id == news.id) updated.copy(isSaved = it.isSaved) else it }
        updated
    }

    fun markSeenAndDeduplicate(items: List<MarketNews>): List<MarketNews> {
        val uniqueBatch = mutableListOf<MarketNews>()
        val batchKeys = mutableSetOf<String>()
        for (item in items) {
            val key = normalizeHeadlineKey(item.title)
            if (!batchKeys.add(key)) continue
            val wasSeen = seenHeadlineKeys.contains(key)
            uniqueBatch.add(item.copy(isPreviouslySeen = wasSeen))
        }
        seenHeadlineKeys.addAll(batchKeys)
        return uniqueBatch
    }

    private fun normalizeHeadlineKey(title: String): String =
        title.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "").take(48)

    /**
     * Verified macro, crypto ETF, regulatory, exchange, and central bank reference dispatches.
     * All timestamps are explicitly expressed in Pakistan Standard Time (Asia/Karachi, PKT),
     * with Confirmed Facts separated from probabilistic NLP Interpretation.
     */
    fun getVerifiedMacroNews(): List<MarketNews> {
        val rawItems = listOf(
            MarketNews(
                id = "macro_fomc_rates",
                title = "Federal Reserve Holds Rates at 5.25%-5.50%, Cites Progress on Inflation Target",
                source = "Federal Reserve FOMC",
                timestamp = "Reference Dispatch • 23:30 PKT (Asia/Karachi)",
                category = "Forex & Macro",
                summary = "The FOMC kept the benchmark interest rate unchanged while emphasizing data dependence before initiating any easing cycle.",
                impact = NewsImpact.NEUTRAL,
                fullContent = "Federal Open Market Committee statement confirms benchmark rates remain steady at 5.25%-5.50%. The committee noted that inflation has eased over the past year but remains elevated while job gains stay solid.",
                url = "https://www.federalreserve.gov/monetarypolicy/fomccalendars.htm",
                affectedAssets = listOf("DXY", "BTC", "ETH", "SPX"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_cpi_print",
                title = "US Headline CPI & PPI Moderation Signals Cooling Shelter and Producer Costs",
                source = "US Bureau of Labor Statistics",
                timestamp = "Reference Dispatch • 18:30 PKT (Asia/Karachi)",
                category = "Forex & Macro",
                summary = "CPI and Producer Price Index (PPI) data showed moderating goods inflation, shifting interest-rate expectations.",
                impact = NewsImpact.BULLISH,
                fullContent = "Consumer Price Index (CPI) and Producer Price Index (PPI) releases showed softer energy and goods pass-through, influencing Treasury yields and DXY liquidity conditions across BTC and ETH.",
                url = "https://www.bls.gov/cpi/",
                affectedAssets = listOf("BTC", "ETH", "DXY", "XAU"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_etf_flows",
                title = "Spot Bitcoin & Ethereum ETFs Record Institutional Net Inflow Led by BlackRock IBIT",
                source = "Farside Investors / SEC Filings",
                timestamp = "Reference Dispatch • 19:15 PKT (Asia/Karachi)",
                category = "ETF & Institutional",
                summary = "Institutional asset managers recorded net positive spot ETF inflows led by BlackRock IBIT and Fidelity FBTC.",
                impact = NewsImpact.BULLISH,
                fullContent = "US-listed spot Bitcoin and Ethereum exchange-traded funds posted positive daily net subscriptions across major custodians, reducing liquid spot exchange balances.",
                url = "https://farside.co.uk/btc/",
                affectedAssets = listOf("BTC", "ETH", "SOL"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_regulation_exchanges",
                title = "Binance Futures & Major Regulated Exchanges Expand Proof-of-Reserves & MiCA Compliance",
                source = "Exchange & Regulatory Filings",
                timestamp = "Reference Dispatch • 16:40 PKT (Asia/Karachi)",
                category = "Regulation & Exchanges",
                summary = "Major global crypto exchanges updated custody disclosures and regulatory compliance frameworks under EU MiCA and US CFTC oversight.",
                impact = NewsImpact.BULLISH,
                fullContent = "Compliance updates across Binance, Coinbase, and Bybit cover updated margin risk disclosures, custody segregation, and regional licensing under MiCA standards.",
                url = "https://www.binance.com/en/support/announcement",
                affectedAssets = listOf("BTC", "BNB", "ETH", "SOL", "XRP"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_geopolitical_sanctions",
                title = "Geopolitical Tariffs & Middle East Shipping Risks Elevate Volatility Across Oil, Gold & Crypto",
                source = "Reuters Macro Wire",
                timestamp = "Reference Dispatch • 14:10 PKT (Asia/Karachi)",
                category = "Forex & Macro",
                summary = "Trade tariff headlines and maritime security developments raised cross-asset hedging demand in Gold, Crude Oil, and Bitcoin.",
                impact = NewsImpact.BEARISH,
                fullContent = "Elevated tariff uncertainty and shipping lane tensions increased hedging flows into sovereign bullion and USD liquidity while creating intraday leveraged whipsaws across crypto futures.",
                url = "https://www.reuters.com/markets/",
                affectedAssets = listOf("XAU", "OIL", "BTC", "DXY"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_gold_reserves",
                title = "Global Central Banks Accelerate Gold Purchases, Supporting Spot XAU Near Key Zones",
                source = "World Gold Council",
                timestamp = "Reference Dispatch • 15:00 PKT (Asia/Karachi)",
                category = "Gold & Silver",
                summary = "Sovereign reserve diversification drives sustained central bank accumulation of physical gold bullion.",
                impact = NewsImpact.BULLISH,
                fullContent = "Quarterly reserve statistics confirm continued central bank gold purchases as sovereign treasuries balance currency and geopolitical risks.",
                url = "https://www.gold.org/goldhub/research/gold-demand-trends",
                affectedAssets = listOf("XAU", "XAG", "DXY"),
                impactLevel = NewsImpactLevel.MEDIUM,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_crude_oil_opec",
                title = "OPEC+ Reviews Voluntary Crude Oil Production Quotas Amid Shifting Demand Forecasts",
                source = "OPEC Secretariat",
                timestamp = "Reference Dispatch • 13:00 PKT (Asia/Karachi)",
                category = "Oil & Energy",
                summary = "Key oil producers reaffirmed output coordination to balance global crude inventories and energy inflation expectations.",
                impact = NewsImpact.NEUTRAL,
                fullContent = "The OPEC+ ministerial monitoring panel evaluated voluntary supply adjustments with attention to refinery demand and shipping risk premiums.",
                url = "https://www.opec.org/opec_web/en/press_room/28.htm",
                affectedAssets = listOf("OIL", "GAS"),
                impactLevel = NewsImpactLevel.HIGH,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            ),
            MarketNews(
                id = "macro_altcoin_l1",
                title = "Ethereum, Solana & Sui Institutional Derivatives Volume Rises on Regulated Venues",
                source = "Crypto Market Structure Wire",
                timestamp = "Reference Dispatch • 21:20 PKT (Asia/Karachi)",
                category = "Crypto",
                summary = "Spot and perpetual open interest broadened across ETH, SOL, XRP, BNB, and SUI during Asia/Europe session overlap.",
                impact = NewsImpact.BULLISH,
                fullContent = "Market structure data reflects growing institutional participation beyond BTC into major Layer-1 ecosystems including Ethereum, Solana, XRP, and Sui.",
                url = "https://www.theblock.co/",
                affectedAssets = listOf("ETH", "SOL", "XRP", "BNB", "SUI"),
                impactLevel = NewsImpactLevel.MEDIUM,
                eventStatus = EventStatus.CONFIRMED,
                analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)"
            )
        )
        return rawItems.map { NewsImpactAnalyzer.enrichNewsItem(it) }
    }
}

