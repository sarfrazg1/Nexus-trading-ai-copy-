package com.example.data.scanner

import com.example.util.TimeZoneUtils
import java.util.Locale
import java.util.UUID

/**
 * Priority tiers for Part 4 NEXUS 1:5 High-Reward Opportunity Detector.
 * Minimum net R:R after estimated fees and slippage is strictly 1:4.0.
 * Setups below 1:4.0 or without confirmed entry trigger are rejected (WAIT).
 */
enum class OpportunityPriorityTier(val label: String, val isQualified: Boolean) {
    HIGHEST_1_TO_5_PLUS("NEXUS 1:5 HIGH REWARD OPPORTUNITY", true),
    QUALIFIED_1_TO_4("NEXUS QUALIFIED 1:4 OPPORTUNITY", true),
    WAIT_FUNDING_UNAVAILABLE("WAIT / FUNDING DATA UNAVAILABLE", false),
    WAIT_BELOW_1_TO_4("WAIT — Below 1:4 Net R:R", false),
    WAIT_TRIGGER_NOT_CONFIRMED("WAIT — Entry Trigger Unconfirmed", false),
    WAIT_INSUFFICIENT_STRUCTURE("WAIT — Structure / Safety Rejected", false)
}

enum class OpportunityStatus(val label: String) {
    ACTIVE("ACTIVE"),
    INVALIDATED("INVALIDATED"),
    EXPIRED("EXPIRED")
}

/**
 * Structured plain-language explanation available in both Pakistani Roman Urdu and Urdu Script.
 */
data class HighRewardUrduExplanation(
    val whySelected: String,
    val whereOneDollarRiskComesFrom: String,
    val howFiveDollarRewardCalculated: String,
    val supportingSignals: String,
    val cancellationConditions: String
)

/**
 * Detailed Part 4 1:4 / 1:5+ Opportunity model with exact fee/slippage-adjusted R:R,
 * PKT timestamps, separate confidence vs empirical historical probability,
 * and Roman Urdu / Urdu explanations. Alert & analysis only — never executes orders.
 */
data class HighRewardOpportunity(
    val id: String = "hr_${UUID.randomUUID().toString().take(8)}",
    val signatureKey: String,
    val symbol: String,
    val baseAsset: String,
    val direction: FuturesDirection,
    val tier: OpportunityPriorityTier,
    val status: OpportunityStatus = OpportunityStatus.ACTIVE,
    val isQualified: Boolean,
    val entryTriggerTimestampMs: Long,
    val entryTriggerTimestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(entryTriggerTimestampMs),
    val detectedAtTimestampMs: Long = System.currentTimeMillis(),
    val detectedAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(detectedAtTimestampMs),
    val expiresAtTimestampMs: Long,
    val expiresAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(expiresAtTimestampMs),
    val currentPrice: Double,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val slDistancePercent: Double,
    val tpDistancePercent: Double,
    val grossRiskRewardRatio: Double,
    val netRiskRewardRatio: Double,
    val normalizedRiskUsd: Double = 1.0,
    val normalizedNetRewardUsd: Double,
    val budgetMarginUsdt: Double,
    val leverageAssumed: Int,
    val notionalUsdt: Double,
    val positionSizeUnits: Double,
    val estimatedGrossProfitUsdt: Double,
    val estimatedFeesUsdt: Double,
    val estimatedSlippageUsdt: Double,
    val estimatedFundingReserveUsdt: Double,
    val estimatedNetProfitUsdt: Double,
    val estimatedNetLossUsdt: Double,
    val estimatedNetRoiPercent: Double,
    val estimatedRiskMarginPercent: Double,
    val feeRatePercentPerSide: Double,
    val slippagePercentPerSide: Double,
    val fundingRatePercent: Double = 0.0,
    val fundingRateTimestampMs: Long? = null,
    val estimatedFundingIntervals: Double = BinanceFuturesScannerEngine.ESTIMATED_FUNDING_INTERVALS,
    val modelConfidencePercent: Int,
    val historicalTargetHitProbabilityPercent: Double? = null,
    val historicalSampleSize: Int = 0,
    val historicalProbabilityNote: String,
    val qualificationReasons: List<String>,
    val supportingIndicatorsAndSmc: List<String>,
    val risksAndInvalidations: List<String>,
    val romanUrduExplanation: HighRewardUrduExplanation,
    val urduScriptExplanation: HighRewardUrduExplanation,
    val statusDetail: String = "Confirmed trigger on completed candle close — Alert Only (No Auto-Execution).",
    val notificationSoundConfigured: Boolean = true,
    val isAlertOnlyNoAutoTrade: Boolean = true
) {
    val alertBannerTitle: String
        get() = when {
            tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS ->
                "NEXUS 1:5 HIGH REWARD OPPORTUNITY • $symbol ${direction.name}"
            isQualified ->
                "NEXUS QUALIFIED 1:4 OPPORTUNITY (1:${String.format(Locale.US, "%.2f", netRiskRewardRatio)}) • $symbol ${direction.name}"
            else ->
                "WAIT • $symbol (${tier.label})"
        }

    val entryFormatted: String get() = fmtPrice(entryPrice)
    val stopLossFormatted: String get() = fmtPrice(stopLoss)
    val takeProfitFormatted: String get() = fmtPrice(takeProfit)
    val currentPriceFormatted: String get() = fmtPrice(currentPrice)

    companion object {
        fun fmtPrice(p: Double): String {
            return when {
                !p.isFinite() -> "$0.00"
                p >= 100.0 -> String.format(Locale.US, "$%.2f", p)
                p >= 1.0 -> String.format(Locale.US, "$%.3f", p)
                p >= 0.01 -> String.format(Locale.US, "$%.4f", p)
                else -> String.format(Locale.US, "$%.6f", p)
            }
        }
    }
}

data class HighRewardCandidateDiagnostic(
    val symbol: String,
    val bestDirection: FuturesDirection?,
    val grossRiskRewardRatio: Double,
    val netRiskRewardRatio: Double,
    val tier: OpportunityPriorityTier,
    val reason: String,
    val isFundingAvailable: Boolean = true,
    val evaluatedAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(System.currentTimeMillis())
)

data class HighRewardScanResult(
    val qualifiedOpportunities: List<HighRewardOpportunity>,
    val bestDiagnostic: HighRewardCandidateDiagnostic
)

data class HighRewardScanState(
    val activeOpportunities: List<HighRewardOpportunity> = emptyList(),
    val archivedOpportunities: List<HighRewardOpportunity> = emptyList(),
    val diagnostics: List<HighRewardCandidateDiagnostic> = emptyList(),
    val waitBannerMessage: String = "WAIT — Scanning Binance USDT-M pairs for genuine >=1:4 net R:R setups (highest priority >=1:5). Never forces trades.",
    val lastScannedTimestampMs: Long = 0L,
    val lastScannedPkt: String = "Not scanned yet"
)
