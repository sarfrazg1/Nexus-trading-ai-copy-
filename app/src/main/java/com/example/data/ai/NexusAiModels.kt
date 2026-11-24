package com.example.data.ai

import com.example.data.learning.LearningLanguageMode
import java.util.Locale
import java.util.UUID

/**
 * Message Model for NEXUS AI Chat.
 */
data class NexusChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val sender: MessageSender,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isStreaming: Boolean = false,
    val isOfflineEngine: Boolean = false,
    val isErrorFallback: Boolean = false,
    val errorReason: String? = null,
    val originalQueryForRetry: String? = null,
    val dataTimestampBadge: String? = null,
    val attachedContextSummary: String? = null,
    val suggestedFollowUps: List<String> = emptyList(),
    val providerUsed: String = "Gemini 3.8 Flash"
)

enum class MessageSender {
    USER,
    ASSISTANT
}

/**
 * Conversation Thread Model for NEXUS AI.
 */
data class NexusConversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val lastActiveAt: Long = System.currentTimeMillis(),
    val languageMode: LearningLanguageMode = LearningLanguageMode.BILINGUAL
)

/**
 * Comprehensive, timestamped Market Context snapshot built from all 6 verified public data feeds,
 * 22 technical indicators, SMC analysis, watchlist scanners, cross-exchange checks, and filtered GDELT news.
 */
data class MarketContextSnapshot(
    val symbol: String,
    val assetName: String,
    val currentPrice: Double,
    val change24hPercent: Double,
    val timeframe: String,
    val rsi14: Double?,
    val macdHistogram: Double?,
    val emaCondition: String?,
    val atrVolatility: Double?,
    val bosChochCondition: String?,
    val fvgStatus: String?,
    val supportLevel: Double?,
    val resistanceLevel: Double?,
    val fibGoldenPocket: String?,
    val signalDecision: String?,
    val confidenceScore: Int?,
    val relevantNewsSummary: String?,
    val macroEventSummary: String?,
    val dataSource: String,
    val lastUpdatedFormatted: String,
    val isRealData: Boolean = true,
    val futuresSignalSummary: String? = null,
    val dataStatus: String = if (isRealData) "LIVE" else "UNAVAILABLE",
    val high24h: Double? = null,
    val low24h: Double? = null,
    val volume24h: String? = null,
    val fundingRatePercent: Double? = null,
    val openInterestContracts: Double? = null,
    val longShortRatio: Double? = null,
    val derivativesSummary: String? = null,
    val marketOverviewSummary: String = "",
    val marketBreadthDetails: String = "",
    val watchlistSummary: String? = null,
    val multiTimeframeSummary: String? = null,
    val newsSourceStatus: String = "Preloaded Macro Reference",
    val recentNewsHeadlines: List<String> = emptyList(),
    val contextBuiltAtFormatted: String = lastUpdatedFormatted,
    // Hybrid Multi-Provider Verified Sections
    val instrumentTypeLabel: String = "USDT-M Perpetual Futures",
    val crossExchangeSummary: String? = null,
    val coinGeckoGlobalSummary: String? = null,
    val defiLlamaSummary: String? = null,
    val fearGreedSummary: String? = null,
    val providerHealthSummary: String? = null,
    // Part 1 Trading Reasoning Engine + Entry Timer + Exit Planner + News Separation
    val reasoningReportSummary: String? = null,
    val entryTimerSummary: String? = null,
    val exitPlanSummary: String? = null,
    val newsFactsVsInterpretationSummary: String? = null,
    // Part 3 Multi-Asset Comparison + Trade Workflow Grounding + Unavailable Disclosures
    val comparisonAssetSummary: String? = null,
    val workflowContextSummary: String? = null,
    val dataUnavailableDisclosures: List<String> = emptyList()
) {
    fun toPromptContext(lang: LearningLanguageMode): String {
        val sb = StringBuilder()
        sb.appendLine("=== VERIFIED HYBRID MARKET FACTS (NEVER INVENT DATA) ===")
        sb.appendLine("Context Built At: $contextBuiltAtFormatted")
        sb.appendLine("Primary Asset: $assetName ($symbol) | Instrument: $instrumentTypeLabel")
        if (isRealData && currentPrice > 0.0) {
            sb.appendLine("Price: $$currentPrice (24h: ${String.format(Locale.US, "%+.2f%%", change24hPercent)})")
            high24h?.let { h -> low24h?.let { l -> sb.appendLine("24h Range: $$l - $$h") } }
            volume24h?.let { sb.appendLine("24h Volume: $it") }
        } else {
            sb.appendLine("Price: UNAVAILABLE (Live quote not yet connected for $symbol)")
        }
        sb.appendLine("Data Status: $dataStatus | Source Attribution: $dataSource | Timestamp: $lastUpdatedFormatted")
        sb.appendLine("Active Chart Timeframe: $timeframe")

        sb.appendLine("--- Cross-Exchange Consistency (Binance Futures vs Bybit V5) ---")
        sb.appendLine(crossExchangeSummary ?: "Cross-exchange comparison unavailable.")

        sb.appendLine("--- Global Crypto, Sentiment & On-Chain DeFi Liquidity ---")
        sb.appendLine("CoinGecko Global & Rankings: ${coinGeckoGlobalSummary ?: "Unavailable"}")
        sb.appendLine("Alternative.me Fear & Greed: ${fearGreedSummary ?: "Unavailable"}")
        sb.appendLine("DefiLlama DeFi TVL & Stablecoins: ${defiLlamaSummary ?: "Unavailable"}")

        sb.appendLine("--- Technical Indicators & SMC ---")
        rsi14?.let {
            sb.appendLine("RSI (14): ${String.format(Locale.US, "%.2f", it)} ${if (it > 70) "[OVERBOUGHT]" else if (it < 30) "[OVERSOLD]" else "[NEUTRAL]"}")
        } ?: sb.appendLine("RSI (14): Unavailable (insufficient candle history)")
        macdHistogram?.let { sb.appendLine("MACD Histogram: ${String.format(Locale.US, "%.4f", it)}") }
        emaCondition?.let { sb.appendLine("EMA Structure: $it") }
        atrVolatility?.let { sb.appendLine("ATR Volatility: ${String.format(Locale.US, "%.4f", it)}") }
        bosChochCondition?.let { sb.appendLine("SMC Structure: $it") }
        fvgStatus?.let { sb.appendLine("Fair Value Gaps: $it") }
        supportLevel?.let { sb.appendLine("Nearest Support: $${String.format(Locale.US, "%.2f", it)}") }
        resistanceLevel?.let { sb.appendLine("Nearest Resistance: $${String.format(Locale.US, "%.2f", it)}") }
        fibGoldenPocket?.let { sb.appendLine("Fibonacci 0.5-0.618 Zone: $it") }
        multiTimeframeSummary?.let { sb.appendLine("Multi-Timeframe Structure: $it") }
        signalDecision?.let { sb.appendLine("NEXUS Signal Engine Decision: $it (Confidence: ${confidenceScore ?: "N/A"}/100)") }

        sb.appendLine("--- Binance USDT-M Futures Derivatives ---")
        if (!derivativesSummary.isNullOrBlank()) {
            sb.appendLine(derivativesSummary)
        } else {
            sb.appendLine("Derivatives (Funding Rate / Open Interest / Long-Short Ratio): Unavailable for this asset or offline.")
        }

        if (marketOverviewSummary.isNotBlank() || marketBreadthDetails.isNotBlank()) {
            sb.appendLine("--- Broad Market & Watchlist Snapshot ---")
            if (marketOverviewSummary.isNotBlank()) sb.appendLine(marketOverviewSummary)
            if (marketBreadthDetails.isNotBlank()) sb.appendLine(marketBreadthDetails)
        }

        if (!watchlistSummary.isNullOrBlank()) {
            sb.appendLine("--- Watchlist & Scanner Top Setups ---")
            sb.appendLine(watchlistSummary)
        }

        futuresSignalSummary?.let {
            sb.appendLine("--- Active Binance USDT-M Futures Scanner Setup ---")
            sb.appendLine(it)
        }

        if (!entryTimerSummary.isNullOrBlank() || !exitPlanSummary.isNullOrBlank()) {
            sb.appendLine("--- Entry Timer & Pre-Entry Exit Planner ---")
            entryTimerSummary?.let { sb.appendLine("Entry Timer: $it") }
            exitPlanSummary?.let { sb.appendLine("Exit Planner: $it") }
        }

        reasoningReportSummary?.let {
            sb.appendLine(it)
        }

        sb.appendLine("--- Filtered News Headlines ($newsSourceStatus) ---")
        sb.appendLine("IMPORTANT: Headlines below are unverified potential context, NOT proven causal explanations.")
        newsFactsVsInterpretationSummary?.let {
            sb.appendLine(it)
        }
        if (recentNewsHeadlines.isNotEmpty()) {
            recentNewsHeadlines.forEach { sb.appendLine("• $it") }
        } else {
            relevantNewsSummary?.let { sb.appendLine("Recent Asset News: $it") }
            macroEventSummary?.let { sb.appendLine("Macro Environment: $it") }
            if (relevantNewsSummary == null && macroEventSummary == null) {
                sb.appendLine("No live filtered news items available.")
            }
        }

        comparisonAssetSummary?.let {
            sb.appendLine("--- Secondary / Comparison Asset Snapshot ---")
            sb.appendLine(it)
        }

        workflowContextSummary?.let {
            sb.appendLine("--- Trade Workflow, Time Machine Backtest, Monte Carlo & Risk Guard ---")
            sb.appendLine(it)
        }

        if (dataUnavailableDisclosures.isNotEmpty()) {
            sb.appendLine("--- Explicitly Unavailable / Unverified Fields (Do NOT Invent) ---")
            dataUnavailableDisclosures.forEach { sb.appendLine("• $it") }
        }

        providerHealthSummary?.let {
            sb.appendLine("--- Provider Health & Cache Status ---")
            sb.appendLine(it)
        }
        sb.appendLine("==========================================================")
        return sb.toString()
    }
}
