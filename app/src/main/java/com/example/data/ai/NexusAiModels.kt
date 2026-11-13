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
    val suggestedFollowUps: List<String> = emptyList()
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
 * Comprehensive, timestamped Market Context snapshot built from live Binance public streams,
 * 22 technical indicators, SMC analysis, watchlist scanners, and financial news.
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
    val contextBuiltAtFormatted: String = lastUpdatedFormatted
) {
    fun toPromptContext(lang: LearningLanguageMode): String {
        val sb = StringBuilder()
        sb.appendLine("=== VERIFIED MARKET CONTEXT (DO NOT FABRICATE DATA) ===")
        sb.appendLine("Context Built At: $contextBuiltAtFormatted")
        sb.appendLine("Primary Asset: $assetName ($symbol)")
        if (isRealData && currentPrice > 0.0) {
            sb.appendLine("Price: $$currentPrice (24h: ${String.format(Locale.US, "%+.2f%%", change24hPercent)})")
            high24h?.let { h -> low24h?.let { l -> sb.appendLine("24h Range: $$l - $$h") } }
            volume24h?.let { sb.appendLine("24h Volume: $it") }
        } else {
            sb.appendLine("Price: UNAVAILABLE (Live quote not yet connected for $symbol)")
        }
        sb.appendLine("Data Status: $dataStatus | Data Source: $dataSource | Asset Timestamp: $lastUpdatedFormatted")
        sb.appendLine("Active Chart Timeframe: $timeframe")

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

        sb.appendLine("--- Binance Futures Derivatives ---")
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

        sb.appendLine("--- News & Macroeconomic Feed ($newsSourceStatus) ---")
        if (recentNewsHeadlines.isNotEmpty()) {
            recentNewsHeadlines.forEach { sb.appendLine("• $it") }
        } else {
            relevantNewsSummary?.let { sb.appendLine("Recent Asset News: $it") }
            macroEventSummary?.let { sb.appendLine("Macro Environment: $it") }
            if (relevantNewsSummary == null && macroEventSummary == null) {
                sb.appendLine("No live news items available.")
            }
        }
        sb.appendLine("======================================================")
        return sb.toString()
    }
}
