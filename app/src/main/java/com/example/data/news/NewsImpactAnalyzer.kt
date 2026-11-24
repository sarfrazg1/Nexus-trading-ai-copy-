package com.example.data.news

import com.example.data.model.EventStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.util.TimeZoneUtils

data class AnalyzedNewsResult(
    val impact: NewsImpact,
    val impactLevel: NewsImpactLevel,
    val eventStatus: EventStatus,
    val affectedAssets: List<String>,
    val category: String,
    val rationale: String,
    val confirmedFacts: List<String> = emptyList(),
    val aiInterpretation: String = "",
    val btcImpactSummary: String = "",
    val altcoinImpactSummary: String = "",
    val shortTermScenario: String = "",
    val longTermScenario: String = "",
    val uncertaintyAndRisks: String = "",
    val analysisEngine: String = "Rule-Based Financial NLP (Probabilistic)"
)

object NewsImpactAnalyzer {

    private val bullishKeywords = listOf(
        "rate cut", "easing", "inflow", "inflows", "net inflow", "record high", "all-time high",
        "breakout", "adoption", "approved", "approval", "cooling inflation", "stimulus", "surplus",
        "bullish", "halving", "reserves drop", "institutional buying", "etf approval",
        "reserve currency", "dovish", "rate pause", "expansion", "growth", "listing",
        "strategic reserve", "custody launch", "settlement", "spot etf", "accumulation"
    )

    private val bearishKeywords = listOf(
        "rate hike", "hawkish", "outflow", "outflows", "net outflow", "crackdown", "lawsuit",
        "ban", "recession", "inflation spikes", "war", "conflict", "escalation", "sanctions",
        "tariff", "tariffs", "investigation", "subpoena", "default", "bankruptcy", "liquidation",
        "bearish", "selloff", "dump", "tightening", "deficit", "collapse", "contraction",
        "hack", "exploit", "delisting", "insolvency", "enforcement action", "fine", "freeze"
    )

    private val highImpactKeywords = listOf(
        "fomc", "federal reserve", "interest rate", "cpi", "ppi", "inflation", "nfp", "pce",
        "sec", "cftc", "mica", "etf", "blackrock", "fidelity", "war", "middle east", "opec",
        "treasury", "biden", "trump", "china", "default", "emergency", "powell", "crude oil",
        "gold", "binance", "coinbase", "bybit", "okx", "kraken", "tariff", "sanctions",
        "geopolitical", "bankruptcy", "hack", "exploit"
    )

    private val etfInstitutionalKeywords = listOf(
        "etf", "blackrock", "fidelity", "grayscale", "ibit", "fbtc", "etha",
        "institutional", "asset manager", "custody", "sovereign wealth", "treasury allocation",
        "fund flow", "inflow", "outflow"
    )

    private val regulationExchangeKeywords = listOf(
        "sec", "cftc", "mica", "regulation", "regulatory", "lawsuit", "crackdown", "ban",
        "enforcement", "license", "binance", "coinbase", "bybit", "okx", "kraken", "listing",
        "delisting", "proof of reserves", "exchange"
    )

    private val geopoliticalMacroKeywords = listOf(
        "fomc", "federal reserve", "fed ", "powell", "cpi", "ppi", "pce", "nfp",
        "interest rate", "rate cut", "rate hike", "inflation", "treasury", "dxy",
        "war", "conflict", "sanctions", "tariff", "geopolitical", "middle east", "opec"
    )

    private val scheduledKeywords = listOf(
        "scheduled", "tomorrow", "next week", "upcoming", "expected to", "forecast",
        "preview", "meeting set for", "to decide", "announcement pending"
    )

    private val unverifiedKeywords = listOf(
        "rumor", "speculation", "alleged", "reportedly", "sources say", "unconfirmed",
        "claim", "claims", "might", "could face", "insider"
    )

    private data class AssetMatcher(val symbol: String, val pattern: Regex)

    private val assetMatchers = listOf(
        AssetMatcher("BTC", Regex("\\b(bitcoin|btc|ibit|fbtc|gbtc)\\b")),
        AssetMatcher("ETH", Regex("\\b(ethereum|ether|eth|etha|ethe)\\b")),
        AssetMatcher("SOL", Regex("\\b(solana|sol)\\b")),
        AssetMatcher("BNB", Regex("\\b(bnb|binance coin)\\b")),
        AssetMatcher("XRP", Regex("\\b(xrp|ripple)\\b")),
        AssetMatcher("DOGE", Regex("\\b(dogecoin|doge)\\b")),
        AssetMatcher("ADA", Regex("\\b(cardano|ada)\\b")),
        AssetMatcher("AVAX", Regex("\\b(avalanche|avax)\\b")),
        AssetMatcher("LINK", Regex("\\b(chainlink|link)\\b")),
        AssetMatcher("SUI", Regex("\\b(sui)\\b")),
        AssetMatcher("TON", Regex("\\b(toncoin|ton)\\b")),
        AssetMatcher("TRX", Regex("\\b(tron|trx)\\b")),
        AssetMatcher("LTC", Regex("\\b(litecoin|ltc)\\b")),
        AssetMatcher("DOT", Regex("\\b(polkadot|dot)\\b")),
        AssetMatcher("BCH", Regex("\\b(bitcoin cash|bch)\\b")),
        AssetMatcher("XAU", Regex("\\b(gold|xau)\\b")),
        AssetMatcher("XAG", Regex("\\b(silver|xag)\\b")),
        AssetMatcher("OIL", Regex("\\b(crude|oil|brent|wti)\\b")),
        AssetMatcher("GAS", Regex("\\b(natural gas|lng)\\b")),
        AssetMatcher("DXY", Regex("\\b(dollar|dxy|forex|treasury yield)\\b")),
        AssetMatcher("SPX", Regex("\\b(s&p|nasdaq|equities)\\b"))
    )

    fun analyze(
        headline: String,
        summary: String = "",
        content: String = "",
        source: String = "",
        timestampPkt: String = ""
    ): AnalyzedNewsResult {
        val fullText = "$headline $summary $content".lowercase()

        // 1. Detect Affected Assets accurately (word-boundary matched)
        val assets = mutableListOf<String>()
        for (matcher in assetMatchers) {
            if (matcher.pattern.containsMatchIn(fullText)) {
                assets.add(matcher.symbol)
            }
        }

        val isCryptoGeneral = fullText.contains("crypto") || fullText.contains("altcoin") ||
            fullText.contains("stablecoin") || fullText.contains("defi") ||
            fullText.contains("binance") || fullText.contains("coinbase") || fullText.contains("etf")
        val isMacroGeneral = geopoliticalMacroKeywords.any { fullText.contains(it) }

        if (assets.isEmpty()) {
            if (isCryptoGeneral) {
                assets.add("CRYPTO")
                assets.add("BTC")
            } else if (isMacroGeneral) {
                assets.add("DXY")
                assets.add("BTC")
            } else {
                assets.add("MACRO")
            }
        } else if (isMacroGeneral && !assets.contains("BTC") &&
            (fullText.contains("fed") || fullText.contains("cpi") || fullText.contains("ppi") || fullText.contains("fomc"))
        ) {
            assets.add("BTC")
        }

        // 2. Classify Category
        val hasCryptoAsset = assets.any {
            it in setOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "LINK", "SUI", "TON", "TRX", "LTC", "DOT", "BCH", "CRYPTO")
        }
        val category = when {
            etfInstitutionalKeywords.any { fullText.contains(it) } -> "ETF & Institutional"
            regulationExchangeKeywords.any { fullText.contains(it) } -> "Regulation & Exchanges"
            hasCryptoAsset -> "Crypto"
            assets.contains("XAU") || assets.contains("XAG") -> "Gold & Silver"
            assets.contains("OIL") || assets.contains("GAS") -> "Oil & Energy"
            else -> "Forex & Macro"
        }

        // 3. Score Bullish vs Bearish Sentiment
        var bullScore = 0
        var bearScore = 0

        for (kw in bullishKeywords) {
            if (fullText.contains(kw)) bullScore += 1
        }
        for (kw in bearishKeywords) {
            if (fullText.contains(kw)) bearScore += 1
        }

        val impact = when {
            bullScore > bearScore -> NewsImpact.BULLISH
            bearScore > bullScore -> NewsImpact.BEARISH
            else -> NewsImpact.NEUTRAL
        }

        // 4. Classify Impact Level
        val isHighImpact = highImpactKeywords.any { fullText.contains(it) }
        val impactLevel = when {
            isHighImpact || (bullScore + bearScore >= 3) -> NewsImpactLevel.HIGH
            (bullScore + bearScore >= 1) -> NewsImpactLevel.MEDIUM
            else -> NewsImpactLevel.LOW
        }

        // 5. Classify Event Status
        val eventStatus = when {
            unverifiedKeywords.any { fullText.contains(it) } -> EventStatus.UNVERIFIED
            scheduledKeywords.any { fullText.contains(it) } -> EventStatus.SCHEDULED
            else -> EventStatus.CONFIRMED
        }

        val rationale = buildString {
            append("NLP Catalyst Score for ${assets.joinToString(", ")}: ")
            when (impact) {
                NewsImpact.BULLISH -> append("Probabilistic bullish tilt (+$bullScore vs -$bearScore matched keywords). Verify candle close & funding before trading.")
                NewsImpact.BEARISH -> append("Probabilistic downside risk (-$bearScore vs +$bullScore matched keywords). Monitor liquidity sweeps & invalidation levels.")
                NewsImpact.NEUTRAL -> append("Balanced or mixed drivers (+$bullScore / -$bearScore). Wait for directional confirmation.")
            }
        }

        val confirmedFacts = buildList {
            if (headline.isNotBlank()) add("Headline (Reported): $headline")
            if (source.isNotBlank()) add("Reported Source: $source")
            val timeDisplay = timestampPkt.ifBlank { "Timestamp unverified" }
            add("Published Time (${TimeZoneUtils.ASIA_KARACHI_ID}): $timeDisplay")
            add("Verification Status: ${eventStatus.label} (Headline report != guaranteed price causality)")
            add("Matched Assets: ${assets.joinToString(", ")}")
        }

        val aiInterpretation = "$rationale Impact classification (${impact.label} / ${impactLevel.label}) is rule-based NLP context, not confirmed order-flow causality."

        val btcImpact = when {
            !assets.contains("BTC") && !isMacroGeneral && !isCryptoGeneral ->
                "Indirect or minimal expected sensitivity to BTC unless broader DXY/risk appetite shifts."
            impact == NewsImpact.BULLISH ->
                "Potential BTC upside support (${impactLevel.label}): Watch if 15m/1h candle closes hold above immediate support and ETF/spot bids absorb funding."
            impact == NewsImpact.BEARISH ->
                "Potential BTC downside pressure (${impactLevel.label}): Elevated risk of leveraged long sweeps or DXY strength if risk-off flows intensify."
            else ->
                "Mixed/Neutral BTC sensitivity (${impactLevel.label}): Range-bound consolidation likely until futures Open Interest or spot volume resolves direction."
        }

        val altcoinImpact = when {
            assets.any { it in listOf("ETH", "SOL", "BNB", "XRP", "SUI", "ADA", "AVAX", "LINK") } -> {
                val alts = assets.filter { it in listOf("ETH", "SOL", "BNB", "XRP", "SUI", "ADA", "AVAX", "LINK", "DOGE", "TON", "TRX", "LTC", "DOT", "BCH") }
                "Direct/Beta sensitivity for ${alts.joinToString(", ")}: Altcoins generally amplify BTC liquidity and regulatory/ETF headlines with 1.5x-2.5x volatility."
            }
            impact == NewsImpact.BULLISH ->
                "Secondary beta benefit for ETH/SOL/major alts ONLY if BTC holds structure and stablecoin float/BTC dominance does not drain alt liquidity."
            impact == NewsImpact.BEARISH ->
                "High beta downside vulnerability for altcoins: During macro risk-off or exchange headlines, altcoins frequently suffer deeper liquidity sweeps than BTC."
            else ->
                "Selective rotation: Altcoins await BTC trend clarity; monitor ETH/BTC and funding crowding before committing size."
        }

        val shortTermScenario = when (impact) {
            NewsImpact.BULLISH -> "Next 15m-24h: Initial spike or liquidity sweep above nearby highs; confirmation requires completed 15m/1h candle hold rather than chasing wicks."
            NewsImpact.BEARISH -> "Next 15m-24h: Sharp intraday whipsaws or stop hunts below nearby support; avoid knife-catching before 15m structure stabilizes."
            NewsImpact.NEUTRAL -> "Next 15m-24h: Two-sided liquidity sweeps around headline release; wait for completed range breakout."
        }

        val longTermScenario = if (isMacroGeneral || etfInstitutionalKeywords.any { fullText.contains(it) } || regulationExchangeKeywords.any { fullText.contains(it) }) {
            "Multi-week structural lens: Policy rates (Fed/CPI/PPI), institutional ETF flow persistence, and regulatory clarity drive multi-month liquidity cycles if sustained across consecutive prints."
        } else {
            "Multi-week structural lens: Single-headline impact usually fades unless backed by sustained spot volume, DeFi TVL growth, or macro liquidity trends."
        }

        val uncertaintyRisks = buildString {
            append("1. Headlines are NOT proven causes of immediate price candles without order-flow, funding, and volume confirmation. ")
            if (eventStatus != EventStatus.CONFIRMED) {
                append("2. Event status is ${eventStatus.label} — details may revise or fail to materialize. ")
            } else {
                append("2. 'Buy the rumor, sell the news' reactions and DXY reversals can move price opposite to headline tone. ")
            }
            append("3. Pre-existing leverage crowding on Binance Futures can invert initial moves via liquidation cascades.")
        }

        return AnalyzedNewsResult(
            impact = impact,
            impactLevel = impactLevel,
            eventStatus = eventStatus,
            affectedAssets = assets,
            category = category,
            rationale = rationale,
            confirmedFacts = confirmedFacts,
            aiInterpretation = aiInterpretation,
            btcImpactSummary = btcImpact,
            altcoinImpactSummary = altcoinImpact,
            shortTermScenario = shortTermScenario,
            longTermScenario = longTermScenario,
            uncertaintyAndRisks = uncertaintyRisks
        )
    }

    fun enrichNewsItem(raw: MarketNews): MarketNews {
        val normalizedTimestamp = when {
            raw.publishedTimestampMs != null -> TimeZoneUtils.formatKarachiShort(raw.publishedTimestampMs)
            raw.timestamp.contains("PKT", ignoreCase = true) -> raw.timestamp
            else -> "${raw.timestamp} (${TimeZoneUtils.ASIA_KARACHI_ID})"
        }
        val analysis = analyze(
            headline = raw.title,
            summary = raw.summary,
            content = raw.fullContent,
            source = raw.source,
            timestampPkt = normalizedTimestamp
        )
        return raw.copy(
            timestamp = normalizedTimestamp,
            impact = analysis.impact,
            impactLevel = analysis.impactLevel,
            eventStatus = analysis.eventStatus,
            affectedAssets = analysis.affectedAssets,
            category = analysis.category,
            analysisMethod = "Rule-Based NLP (Facts & Interpretation Separated)",
            confirmedFacts = if (raw.confirmedFacts.isNotEmpty()) raw.confirmedFacts else analysis.confirmedFacts,
            aiInterpretation = if (raw.aiInterpretation.isNotBlank()) raw.aiInterpretation else analysis.aiInterpretation,
            btcImpactSummary = raw.btcImpactSummary.ifBlank { analysis.btcImpactSummary },
            altcoinImpactSummary = raw.altcoinImpactSummary.ifBlank { analysis.altcoinImpactSummary },
            shortTermScenario = raw.shortTermScenario.ifBlank { analysis.shortTermScenario },
            longTermScenario = raw.longTermScenario.ifBlank { analysis.longTermScenario },
            uncertaintyAndRisks = raw.uncertaintyAndRisks.ifBlank { analysis.uncertaintyAndRisks },
            fetchedArticleCharCount = raw.fetchedArticleCharCount.takeIf { it > 0 } ?: raw.fullContent.length
        )
    }
}

