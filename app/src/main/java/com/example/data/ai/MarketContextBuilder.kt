package com.example.data.ai

import com.example.data.indicators.OverallAnalysisResult
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.MarketOverview
import com.example.data.model.ScannerItem
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.FuturesScanSignal
import com.example.data.signal.AiTradingSignal
import com.example.data.smc.FvgStatus
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds an accurate, timestamped MarketContextSnapshot from all live NEXUS TRADING AI modules:
 * - Live Binance Public WebSocket / REST Tickers & CoinGecko Global Overview
 * - Binance USDT-M Futures Derivatives (Funding Rate, Open Interest, Long/Short Ratio)
 * - 22 Technical Indicators & Smart Money Concepts (BOS, CHoCH, OB, FVG, Fibonacci)
 * - Watchlist & Futures Scanner Rankings
 * - Financial News & Macro Dispatches (with live vs static provenance disclosure)
 *
 * Strictly never fabricates missing prices, indicators, or news.
 */
object MarketContextBuilder {

    /**
     * Detects which tracked asset (if any) the user explicitly mentioned in their query.
     */
    fun detectMentionedAssetId(query: String, assets: List<MarketAsset>): String? {
        val q = query.lowercase(Locale.US)
        val aliases = mapOf(
            "bitcoin" to "BTC",
            "btc" to "BTC",
            "بٹ کوائن" to "BTC",
            "ethereum" to "ETH",
            "ether" to "ETH",
            "eth" to "ETH",
            "ایتھیریم" to "ETH",
            "solana" to "SOL",
            "sol" to "SOL",
            "bnb" to "BNB",
            "binance coin" to "BNB",
            "xrp" to "XRP",
            "ripple" to "XRP",
            "doge" to "DOGE",
            "dogecoin" to "DOGE",
            "ada" to "ADA",
            "cardano" to "ADA",
            "link" to "LINK",
            "chainlink" to "LINK",
            "avax" to "AVAX",
            "avalanche" to "AVAX",
            "trx" to "TRX",
            "tron" to "TRX",
            "ton" to "TON",
            "toncoin" to "TON",
            "ltc" to "LTC",
            "litecoin" to "LTC",
            "dot" to "DOT",
            "polkadot" to "DOT",
            "bch" to "BCH",
            "sui" to "SUI",
            "gold" to "XAU",
            "xau" to "XAU",
            "سونا" to "XAU",
            "sona" to "XAU",
            "silver" to "XAG",
            "xag" to "XAG",
            "oil" to "WTI",
            "crude" to "WTI",
            "wti" to "WTI",
            "brent" to "BRENT",
            "natural gas" to "NATGAS",
            "natgas" to "NATGAS"
        )

        for ((alias, assetId) in aliases) {
            val regex = Regex("(^|[^a-z0-9])${Regex.escape(alias)}([^a-z0-9]|$)")
            if (regex.containsMatchIn(q) || q.contains(alias) && alias.length >= 4) {
                if (assets.isEmpty() || assets.any { it.id.equals(assetId, ignoreCase = true) }) {
                    return assetId
                }
            }
        }

        // Check dynamic asset list symbols/names
        for (asset in assets) {
            val idLower = asset.id.lowercase(Locale.US)
            val nameLower = asset.name.lowercase(Locale.US)
            val wordMatch = Regex("(^|[^a-z0-9])${Regex.escape(idLower)}([^a-z0-9]|$)")
            if (wordMatch.containsMatchIn(q) || (nameLower.length >= 4 && q.contains(nameLower))) {
                return asset.id
            }
        }
        return null
    }

    fun buildSnapshot(
        asset: MarketAsset?,
        candles: List<Candle>,
        timeframe: CandleTimeframe = CandleTimeframe.H1,
        technicalAnalysis: OverallAnalysisResult? = null,
        smcAnalysis: SmcAnalysisResult? = null,
        signal: AiTradingSignal? = null,
        newsList: List<MarketNews> = emptyList(),
        futuresSignal: FuturesScanSignal? = null,
        allAssets: List<MarketAsset> = emptyList(),
        marketOverview: MarketOverview? = null,
        scannerItems: List<ScannerItem> = emptyList(),
        activeFuturesSignals: List<FuturesScanSignal> = emptyList(),
        watchlistSetups: List<FuturesScanSignal> = emptyList(),
        allAiSignals: List<AiTradingSignal> = emptyList(),
        derivatives: BinanceFuturesApiClient.DerivativesSnapshot? = null,
        isLiveNewsFeed: Boolean = false,
        newsLastFetchedFormatted: String = "Preloaded Macro Reference"
    ): MarketContextSnapshot {
        val symbol = asset?.symbol ?: signal?.symbol ?: futuresSignal?.symbol ?: "BTCUSDT"
        val assetName = asset?.name ?: futuresSignal?.baseAsset ?: "Bitcoin"
        val currentPrice = asset?.currentPrice ?: futuresSignal?.currentPrice ?: candles.lastOrNull()?.close ?: 0.0
        val change24h = asset?.change24hPercent ?: 0.0

        val contextBuiltAt = SimpleDateFormat("HH:mm:ss dd-MMM-yyyy", Locale.US).format(Date())
        val actualAssetTimestamp = when {
            asset?.lastUpdated != null -> SimpleDateFormat("HH:mm:ss dd-MMM-yyyy", Locale.US).format(Date(asset.lastUpdated))
            asset?.lastUpdatedFormatted?.isNotBlank() == true && asset.lastUpdatedFormatted != "Never" -> asset.lastUpdatedFormatted
            candles.isNotEmpty() -> SimpleDateFormat("HH:mm:ss dd-MMM-yyyy", Locale.US).format(Date(candles.last().openTime))
            else -> "Unavailable"
        }

        val dataStatus = when {
            asset != null -> asset.dataStatus.label
            currentPrice > 0.0 -> MarketDataStatus.LIVE.label
            else -> MarketDataStatus.UNAVAILABLE.label
        }

        val rsi = technicalAnalysis?.momentum?.rsi14
        val macdHist = technicalAnalysis?.momentum?.macd?.histogram
        val emaCond = technicalAnalysis?.trend?.let {
            val e20 = it.emaSet.ema20
            val e50 = it.emaSet.ema50
            val e200 = it.emaSet.ema200
            buildString {
                if (e20 != null && e50 != null) {
                    append(if (e20 > e50) "Bullish (EMA20 $${fmt(e20)} > EMA50 $${fmt(e50)})" else "Bearish (EMA20 $${fmt(e20)} < EMA50 $${fmt(e50)})")
                }
                if (e200 != null) {
                    if (isNotEmpty()) append(" | ")
                    append("EMA200: $${fmt(e200)}")
                }
            }.ifBlank { null }
        }
        val atr = technicalAnalysis?.volatility?.atr?.value

        val bosChoch = smcAnalysis?.marketStructure?.let {
            val breakCount = it.structureBreaks.size
            val lastBreak = it.latestBreak?.type?.label ?: "None"
            "Trend: ${it.trendState.label} | Breaks: $breakCount | Latest: $lastBreak"
        }

        val fvgStatus = smcAnalysis?.fairValueGaps?.let { gaps ->
            val unfilled = gaps.count { it.status == FvgStatus.UNFILLED }
            "Active Unfilled FVGs: $unfilled total"
        }

        val support = smcAnalysis?.priceZones
            ?.firstOrNull { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }?.centralPrice
            ?: technicalAnalysis?.trend?.emaSet?.ema50

        val resistance = smcAnalysis?.priceZones
            ?.firstOrNull { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }?.centralPrice
            ?: technicalAnalysis?.trend?.emaSet?.ema200

        val goldenPocket = smcAnalysis?.fibonacci?.let { fib ->
            String.format(Locale.US, "$%.2f - $%.2f", fib.goldenPocketLow, fib.goldenPocketHigh)
        }

        val multiTfSummary = smcAnalysis?.multiTimeframe?.snapshots
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" | ") { "${it.timeframe.label}: ${it.trend.label}" }

        val sigDecision = signal?.decision?.name ?: "NO_ACTIVE_SIGNAL"
        val sigConfidence = signal?.confidence?.totalScore

        // Derivatives summary
        val derivText = derivatives?.let { d ->
            val parts = mutableListOf<String>()
            d.fundingRatePercent?.let { parts.add("Funding Rate: ${String.format(Locale.US, "%+.4f%%", it)}") }
            d.openInterestContracts?.let { oi ->
                val oiUsd = if (currentPrice > 0.0) " (~$${formatLargeNumber(oi * currentPrice)})" else ""
                parts.add("Open Interest: ${formatLargeNumber(oi)} contracts$oiUsd")
            }
            d.longShortAccountRatio?.let { ls ->
                parts.add("Global Long/Short Account Ratio (1h): ${String.format(Locale.US, "%.2f", ls)}")
            }
            if (parts.isNotEmpty()) {
                val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(d.fetchedAtMillis))
                "${parts.joinToString(" | ")} [Source: ${d.source} at $ts]"
            } else null
        }

        // Broad Market Breadth across all tracked crypto assets
        val cryptoWithPrices = allAssets.filter { it.category == AssetCategory.CRYPTO && (it.currentPrice ?: 0.0) > 0.0 }
        val greenCount = cryptoWithPrices.count { (it.change24hPercent ?: 0.0) > 0.0 }
        val redCount = cryptoWithPrices.count { (it.change24hPercent ?: 0.0) < 0.0 }
        val sortedByDrop = cryptoWithPrices.sortedBy { it.change24hPercent ?: 0.0 }
        val sortedByGain = cryptoWithPrices.sortedByDescending { it.change24hPercent ?: 0.0 }

        val overviewLine = marketOverview?.let { ov ->
            "Global Crypto Cap: ${ov.totalCryptoMarketCap} | 24h Vol: ${ov.cryptoVolume24h} | BTC Dominance: ${ov.btcDominance} | Sentiment: ${ov.fearAndGreedIndex}/100 (${ov.sentimentLabel}) | Live Feeds: ${ov.connectedFeedsCount}/${ov.totalFeedsCount}"
        } ?: ""

        val breadthLine = if (cryptoWithPrices.isNotEmpty()) {
            val topMovers = (sortedByGain.take(3) + sortedByDrop.take(3)).distinctBy { it.id }
                .joinToString(", ") { a ->
                    "${a.id}: $${fmt(a.currentPrice ?: 0.0)} (${String.format(Locale.US, "%+.2f%%", a.change24hPercent ?: 0.0)})"
                }
            "Crypto Breadth ($greenCount green / $redCount red of ${cryptoWithPrices.size} tracked): $topMovers"
        } else {
            "Live multi-coin crypto breadth unavailable (no live ticker batch loaded yet)."
        }

        // Watchlist & Scanner Top Setups
        val watchlistText = buildWatchlistSummary(
            allAssets = allAssets,
            scannerItems = scannerItems,
            activeFuturesSignals = activeFuturesSignals,
            watchlistSetups = watchlistSetups,
            allAiSignals = allAiSignals
        )

        // News & Macro Dispatches with provenance
        val newsSourceDesc = if (isLiveNewsFeed) {
            "Live GDELT Financial Wire (Updated $newsLastFetchedFormatted)"
        } else {
            "Static Macro Reference Feed (Live news feed unavailable or not yet fetched; last check: $newsLastFetchedFormatted)"
        }

        val relevantNews = newsList.firstOrNull { news ->
            news.affectedAssets.any { it.equals(assetName, ignoreCase = true) || it.equals(symbol.take(3), ignoreCase = true) }
        }?.let { "${it.title} [${it.impact.name} - ${it.impactLevel.name}] (${it.timestamp})" }

        val macroNews = newsList.firstOrNull {
            it.category.contains("Macro", ignoreCase = true) ||
                it.title.contains("Fed", ignoreCase = true) ||
                it.title.contains("CPI", ignoreCase = true)
        }?.let { "${it.title} (${it.source}, ${it.timestamp})" }

        val headlines = newsList.take(6).map { item ->
            "${item.title} [${item.source} • ${item.timestamp} • ${item.impact.label}/${item.impactLevel.label}]"
        }

        return MarketContextSnapshot(
            symbol = symbol,
            assetName = assetName,
            currentPrice = currentPrice,
            change24hPercent = change24h,
            timeframe = timeframe.label,
            rsi14 = rsi,
            macdHistogram = macdHist,
            emaCondition = emaCond,
            atrVolatility = atr,
            bosChochCondition = bosChoch,
            fvgStatus = fvgStatus,
            supportLevel = support,
            resistanceLevel = resistance,
            fibGoldenPocket = goldenPocket,
            signalDecision = sigDecision,
            confidenceScore = sigConfidence,
            relevantNewsSummary = relevantNews,
            macroEventSummary = macroNews,
            dataSource = asset?.dataSourceName ?: futuresSignal?.dataSource ?: "Public Binance & CoinGecko Feeds",
            lastUpdatedFormatted = actualAssetTimestamp,
            isRealData = currentPrice > 0.0,
            futuresSignalSummary = futuresSignal?.let { fs ->
                """
                Scanned Pair: ${fs.symbol} | Direction: ${fs.direction.name} | Status: ${fs.lifecycle.label}
                Entry Zone: ${fs.entryZoneFormatted} (Current: ${fs.currentPriceFormatted})
                Stop Loss: ${fs.stopLossFormatted} (-${String.format(Locale.US, "%.2f", fs.slDistancePercent)}%)
                TP1: ${fs.tp1Formatted} (+${String.format(Locale.US, "%.2f", fs.tp1DistancePercent)}%)
                TP2: ${fs.tp2Formatted} (+${String.format(Locale.US, "%.2f", fs.tp2DistancePercent)}%)
                TP3: ${fs.tp3Formatted} (+${String.format(Locale.US, "%.2f", fs.tp3DistancePercent)}%)
                Risk/Reward: 1:${String.format(Locale.US, "%.2f", fs.riskRewardRatio)} | Quality Score: ${fs.qualityScore}/100 (${fs.qualityRating})
                Confluence Reasons: ${fs.reasonsForEntry.joinToString("; ")}
                Invalidation Rule: ${fs.invalidationCondition}
                Multi-Timeframe Confirmation: ${fs.timeframeConfirmations.joinToString(" | ") { "${it.timeframe.label}: ${it.trend} (${it.structure})" }}
                """.trimIndent()
            },
            dataStatus = dataStatus,
            high24h = asset?.high24h,
            low24h = asset?.low24h,
            volume24h = asset?.volume24h,
            fundingRatePercent = derivatives?.fundingRatePercent,
            openInterestContracts = derivatives?.openInterestContracts,
            longShortRatio = derivatives?.longShortAccountRatio,
            derivativesSummary = derivText,
            marketOverviewSummary = overviewLine,
            marketBreadthDetails = breadthLine,
            watchlistSummary = watchlistText,
            multiTimeframeSummary = multiTfSummary,
            newsSourceStatus = newsSourceDesc,
            recentNewsHeadlines = headlines,
            contextBuiltAtFormatted = contextBuiltAt
        )
    }

    private fun buildWatchlistSummary(
        allAssets: List<MarketAsset>,
        scannerItems: List<ScannerItem>,
        activeFuturesSignals: List<FuturesScanSignal>,
        watchlistSetups: List<FuturesScanSignal>,
        allAiSignals: List<AiTradingSignal>
    ): String? {
        val sections = mutableListOf<String>()

        if (activeFuturesSignals.isNotEmpty()) {
            val ranked = activeFuturesSignals.sortedByDescending { it.qualityScore }.take(3)
            sections.add(
                "Active Futures Signals: " + ranked.joinToString(" | ") {
                    "${it.symbol} ${it.direction.name} (${it.lifecycle.label}, Score ${it.qualityScore}/100, Entry ${it.entryZoneFormatted}, SL ${it.stopLossFormatted}, R:R 1:${String.format(Locale.US, "%.1f", it.riskRewardRatio)})"
                }
            )
        }

        if (watchlistSetups.isNotEmpty()) {
            val rankedSetups = watchlistSetups.sortedByDescending { it.qualityScore }.take(3)
            sections.add(
                "Developing Futures Setups: " + rankedSetups.joinToString(" | ") {
                    "${it.symbol} ${it.direction.name} (Score ${it.qualityScore}/100, Zone ${it.entryZoneFormatted})"
                }
            )
        }

        if (allAiSignals.isNotEmpty()) {
            val topSignals = allAiSignals.sortedByDescending { it.confidence.totalScore }.take(4)
            sections.add(
                "Multi-Asset AI Signal Scores: " + topSignals.joinToString(" | ") { sig ->
                    val levels = sig.riskPlan?.let { rp -> "Entry $${fmt(rp.entryPrice)}, SL $${fmt(rp.stopLoss)}" } ?: "No Active Entry"
                    "${sig.symbol}: ${sig.decision.label} (Score ${sig.confidence.totalScore}/100, $levels)"
                }
            )
        }

        if (scannerItems.isNotEmpty()) {
            val topScanner = scannerItems.sortedByDescending { it.aiScore }.take(4)
            sections.add(
                "Market Scanner Rankings: " + topScanner.joinToString(" | ") {
                    "${it.symbol} (Score ${it.aiScore}/100, RSI ${String.format(Locale.US, "%.1f", it.rsi14)}, ${it.emaCondition}, ${it.detectedCondition})"
                }
            )
        }

        val favAssets = allAssets.filter { it.isFavorite && (it.currentPrice ?: 0.0) > 0.0 }
        if (favAssets.isNotEmpty()) {
            sections.add(
                "User Favorites: " + favAssets.joinToString(", ") {
                    "${it.id} $${fmt(it.currentPrice ?: 0.0)} (${String.format(Locale.US, "%+.2f%%", it.change24hPercent ?: 0.0)})"
                }
            )
        }

        return if (sections.isEmpty()) null else sections.joinToString("\n")
    }

    private fun fmt(v: Double): String {
        return if (v >= 100.0) String.format(Locale.US, "%.2f", v)
        else if (v >= 1.0) String.format(Locale.US, "%.3f", v)
        else String.format(Locale.US, "%.4f", v)
    }

    private fun formatLargeNumber(v: Double): String {
        return when {
            v >= 1_000_000_000.0 -> String.format(Locale.US, "%.2fB", v / 1_000_000_000.0)
            v >= 1_000_000.0 -> String.format(Locale.US, "%.2fM", v / 1_000_000.0)
            v >= 1_000.0 -> String.format(Locale.US, "%.1fK", v / 1_000.0)
            else -> String.format(Locale.US, "%.2f", v)
        }
    }
}
