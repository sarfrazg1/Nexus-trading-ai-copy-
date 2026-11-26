package com.example.data.signal

import com.example.data.indicators.IndicatorValidationStatus
import com.example.data.indicators.VolatilityRegime
import com.example.data.model.AssetCategory
import com.example.util.TimeZoneUtils
import java.util.Locale

enum class EvidenceGroupId(
    val code: String,
    val title: String,
    val maxPoints: Int
) {
    A_TREND_STRUCTURE("A", "Trend & Market Structure", 20),
    B_SR_FIBONACCI("B", "Support/Resistance & Fibonacci", 15),
    C_CANDLESTICK_PRICE_ACTION("C", "Candlestick & Price Action", 15),
    D_VOLUME_MONEY_FLOW("D", "Volume & Money Flow", 12),
    E_MOMENTUM("E", "Momentum", 12),
    F_VOLATILITY_RISK("F", "Volatility & Risk", 10),
    G_FUTURES_LIQUIDITY("G", "Futures Liquidity & Derivatives", 10),
    H_NEWS_MARKET_CONDITIONS("H", "News & Market Conditions", 6)
}

enum class StrictQualificationTier(val label: String) {
    NO_TRADE("WAIT / NO TRADE"),
    QUALIFIED_SETUP("QUALIFIED SETUP"),
    HIGH_QUALITY_SETUP("HIGH-QUALITY SETUP")
}

data class EvidenceGroupScore(
    val groupId: EvidenceGroupId,
    val awardedPoints: Int,
    val maxPoints: Int = groupId.maxPoints,
    val status: IndicatorValidationStatus,
    val directionalStance: String, // "LONG", "SHORT", "NEUTRAL", "CONFLICTING", "UNAVAILABLE"
    val summary: String,
    val evidencePassed: List<String> = emptyList(),
    val evidenceMissing: List<String> = emptyList(),
    val antiDoubleCountNote: String? = null
)

data class MandatoryFilterCheck(
    val id: String,
    val filterNumber: Int,
    val name: String,
    val passed: Boolean,
    val reason: String
)

data class CorrelatedIndicatorAudit(
    val emaSupertrendClusterCapped: Boolean,
    val rsiStochRsiMerged: Boolean,
    val obvMfiCmfMerged: Boolean,
    val adxStrengthOnlyEnforced: Boolean,
    val volumeAvailable: Boolean,
    val futuresTelemetryStatus: String
)

data class NetRiskRewardSummary(
    val entryPrice: Double,
    val stopLoss: Double,
    val targetPrice: Double,
    val grossRiskReward: Double,
    val roundTripFeeSlippagePercent: Double,
    val netRiskReward: Double,
    val meetsMinOneToFour: Boolean,
    val calculationSummary: String
) {
    val netRiskRewardFormatted: String
        get() = "1 : ${String.format(Locale.US, "%.2f", netRiskReward)}"
}

data class StrictSignalQualityReport(
    val symbol: String,
    val assetCategory: AssetCategory,
    val candidateDirection: SignalDecision,
    val qualificationTier: StrictQualificationTier,
    val totalScore: Int,
    val rawDirectionalScore: Int,
    val evidenceGroups: List<EvidenceGroupScore>,
    val mandatoryFilters: List<MandatoryFilterCheck>,
    val missingConfirmations: List<String>,
    val volatilityRegime: VolatilityRegime,
    val netRiskRewardSummary: NetRiskRewardSummary?,
    val audit: CorrelatedIndicatorAudit,
    val timestampMs: Long = System.currentTimeMillis(),
    val timestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(timestampMs),
    val qualityScoreDisclaimer: String =
        "Setup Quality Score (0–100) measures multi-factor technical & risk alignment — NOT probability of winning."
) {
    val allMandatoryFiltersPassed: Boolean
        get() = mandatoryFilters.all { it.passed }

    val passedFilterCount: Int
        get() = mandatoryFilters.count { it.passed }
}
