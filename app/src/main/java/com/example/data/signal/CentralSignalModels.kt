package com.example.data.signal

import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.scanner.FuturesDirection
import com.example.util.TimeZoneUtils

/**
 * Canonical final output states of CentralSignalAuthority.
 */
enum class CentralSignalState(val label: String) {
    APPROVED_LONG("APPROVED LONG"),
    APPROVED_SHORT("APPROVED SHORT"),
    WAIT("WAIT"),
    REJECTED("REJECTED"),
    EXPIRED("EXPIRED"),
    INVALIDATED("INVALIDATED");

    val isApproved: Boolean
        get() = this == APPROVED_LONG || this == APPROVED_SHORT

    val isTerminal: Boolean
        get() = this == EXPIRED || this == INVALIDATED
}

/**
 * Existing engines connected as candidate providers to CentralSignalAuthority.
 */
enum class CandidateProviderSource(val displayName: String) {
    BINANCE_FUTURES_SCANNER("BinanceFuturesScannerEngine"),
    HIGH_REWARD_DETECTOR("HighRewardOpportunityDetector"),
    STRICT_MULTI_MARKET_SETUP("StrictMultiMarketSetupEngine"),
    STRICT_SIGNAL_QUALITY("StrictSignalQualityEngine"),
    AI_TRADING_SIGNAL("AiTradingSignalEngine"),
    MARKET_SCANNER("MarketScannerEngine"),
    MULTI_MARKET_RANKER("MultiMarketOpportunityRanker"),
    EXTERNAL_PUSH("ExternalPushPayload")
}

/**
 * Normalized candidate representation adapted from any upstream engine without inflating scores
 * or fabricating missing derivatives/volume fields.
 */
data class CentralSignalCandidate(
    val provider: CandidateProviderSource,
    val symbol: String,
    val baseAsset: String = symbol.removeSuffix("USDT"),
    val timeframe: CandleTimeframe = CandleTimeframe.M15,
    val direction: FuturesDirection?,
    val isEntryConfirmed: Boolean,
    val isWatchingOnly: Boolean = !isEntryConfirmed,
    val rawNativeScore: Int?,
    val strictQualityScore: Int? = null,
    val entryPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val takeProfit1: Double = 0.0,
    val takeProfit2: Double = 0.0,
    val takeProfit3: Double = 0.0,
    val grossRiskReward: Double = 0.0,
    val netRiskReward: Double = 0.0,
    val dataStatus: MarketDataStatus = MarketDataStatus.UNAVAILABLE,
    val dataSourceName: String = "UNAVAILABLE",
    val currentPrice: Double = entryPrice,
    val snapshotTimestampMs: Long = 0L,
    val confirmationCandleCloseMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val candleCount: Int = 0,
    val hasClosedCandleConfirmation: Boolean = false,
    val setupTypeLabel: String = "Multi-Factor Setup",
    val setupSignature: String = "",
    val reasons: List<String> = emptyList(),
    val invalidationCondition: String = "",
    val strictQualityReport: StrictSignalQualityReport? = null,
    val allMandatoryFiltersPassed: Boolean? = strictQualityReport?.allMandatoryFiltersPassed,
    val failedFilterReasons: List<String> = strictQualityReport?.mandatoryFilters?.filter { !it.passed }?.map {
        "Filter #${it.filterNumber} (${it.name}): ${it.reason}"
    } ?: emptyList(),
    val fundingStatus: String = "UNAVAILABLE",
    val liquidationStatus: String = "UNAVAILABLE",
    val isFundingAvailable: Boolean = false,
    val isLiquidationAvailable: Boolean = false,
    val originalSignalId: String? = null,
    val isContextOnly: Boolean = false
)

data class CentralValidationStageSummary(
    val stageCode: String,
    val stageName: String,
    val passed: Boolean,
    val summary: String,
    val details: List<String> = emptyList()
)

data class CentralEvaluationContext(
    val symbol: String,
    val timeframe: CandleTimeframe = CandleTimeframe.M15,
    val dataStatus: MarketDataStatus = MarketDataStatus.LIVE,
    val dataSourceName: String = "",
    val currentPrice: Double? = null,
    val snapshotTimestampMs: Long = System.currentTimeMillis(),
    val completedCandleCount: Int = 0,
    val hasClosedCandleConfirmation: Boolean = true,
    val maxDataAgeMs: Long = 5 * 60 * 1000L,
    val strictQualityReport: StrictSignalQualityReport? = null,
    val fundingStatus: String? = null,
    val liquidationStatus: String? = null,
    val minNetRiskReward: Double = 4.0,
    val requireNetRiskRewardCheck: Boolean = true,
    val referenceTimeMs: Long = System.currentTimeMillis()
)

/**
 * Single authoritative verdict produced by CentralSignalAuthority.
 */
data class CentralSignalVerdict(
    val verdictId: String,
    val signalId: String,
    val dedupKey: String,
    val symbol: String,
    val displaySymbol: String,
    val timeframe: CandleTimeframe,
    val state: CentralSignalState,
    val approvedDirection: FuturesDirection?,
    val finalQualityScore: Int?,
    val allMandatoryFiltersPassed: Boolean,
    val failedFilters: List<String>,
    val isEntryConfirmed: Boolean,
    val hasDirectionConflict: Boolean,
    val suppressedAsDuplicate: Boolean,
    val shouldNotifyNewSignal: Boolean,
    val dataStatus: MarketDataStatus,
    val dataSourceName: String,
    val entryPrice: Double = 0.0,
    val stopLoss: Double = 0.0,
    val takeProfit1: Double = 0.0,
    val takeProfit2: Double = 0.0,
    val grossRiskReward: Double = 0.0,
    val netRiskReward: Double = 0.0,
    val fundingStatus: String = "UNAVAILABLE",
    val liquidationStatus: String = "UNAVAILABLE",
    val setupTypeLabel: String = "",
    val setupSignature: String = "",
    val invalidationCondition: String = "",
    val confirmationCandleCloseMs: Long = 0L,
    val expiresAtMs: Long = 0L,
    val evaluatedAtMs: Long = System.currentTimeMillis(),
    val evaluatedAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(evaluatedAtMs),
    val explanation: String,
    val rejectionReasons: List<String> = emptyList(),
    val stageSummaries: List<CentralValidationStageSummary> = emptyList(),
    val contributingProviders: List<CandidateProviderSource> = emptyList(),
    val strictQualityReport: StrictSignalQualityReport? = null
)

enum class ForwardValidationOutcome(val label: String) {
    STILL_OPEN("Still Open"),
    TP1_HIT("TP1 Hit"),
    TP2_HIT("TP2 Hit"),
    SL_HIT("SL Hit"),
    EXPIRED("Expired"),
    INVALIDATED("Invalidated")
}

/**
 * Forward-validation ledger entry tracking real-time outcome of every CentralSignalAuthority-approved
 * signal after approval. Stored and displayed completely separately from historical backtest metrics.
 */
data class CentralForwardValidationRecord(
    val verdictId: String,
    val signalId: String,
    val dedupKey: String,
    val symbol: String,
    val direction: FuturesDirection,
    val timeframe: CandleTimeframe,
    val qualityScore: Int,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val netRiskReward: Double,
    val dataSourceName: String,
    val approvedAtMs: Long,
    val approvedAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(approvedAtMs),
    val expiresAtMs: Long,
    val outcome: ForwardValidationOutcome = ForwardValidationOutcome.STILL_OPEN,
    val maxFavorablePrice: Double = entryPrice,
    val maxAdversePrice: Double = entryPrice,
    val resolvedAtMs: Long? = null,
    val resolvedAtPkt: String? = null,
    val resolutionPrice: Double? = null,
    val resolutionNote: String = "Tracking live forward outcome since Central Approval"
) {
    val isResolved: Boolean
        get() = outcome != ForwardValidationOutcome.STILL_OPEN
}

