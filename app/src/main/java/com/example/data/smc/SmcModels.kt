package com.example.data.smc

import com.example.data.model.CandleTimeframe

/**
 * Smart Money Concepts (SMC), Market Structure, Volume Profile, Support/Resistance,
 * Price Action, and Strict Fibonacci Models for NEXUS TRADING AI.
 */

// ==========================================
// 1. MARKET STRUCTURE & SWING POINTS
// ==========================================

enum class SwingType(val label: String, val shortCode: String) {
    HIGHER_HIGH("Higher High", "HH"),
    HIGHER_LOW("Higher Low", "HL"),
    LOWER_HIGH("Lower High", "LH"),
    LOWER_LOW("Lower Low", "LL"),
    SWING_HIGH("Swing High", "SH"),
    SWING_LOW("Swing Low", "SL")
}

data class SwingPoint(
    val index: Int,
    val timestamp: Long,
    val price: Double,
    val type: SwingType,
    val isHigh: Boolean
)

enum class MarketStructureBreakType(val label: String, val isBullish: Boolean) {
    BOS_BULLISH("Bullish BOS", true),
    BOS_BEARISH("Bearish BOS", false),
    CHOCH_BULLISH("Bullish CHoCH", true),
    CHOCH_BEARISH("Bearish CHoCH", false),
    MSS_BULLISH("Bullish MSS", true),
    MSS_BEARISH("Bearish MSS", false)
}

data class MarketStructureBreak(
    val type: MarketStructureBreakType,
    val breakPrice: Double,
    val brokenSwingPrice: Double,
    val breakCandleIndex: Int,
    val timestamp: Long,
    val description: String
)

enum class MarketTrendState(val label: String) {
    BULLISH_UPTREND("Bullish Uptrend (HH + HL)"),
    BEARISH_DOWNTREND("Bearish Downtrend (LH + LL)"),
    NEUTRAL_RANGING("Consolidation / Ranging"),
    TRANSITION_REVERSAL("Potential Trend Reversal")
}

data class MarketStructureAnalysis(
    val trendState: MarketTrendState,
    val swingPoints: List<SwingPoint>,
    val structureBreaks: List<MarketStructureBreak>,
    val latestBreak: MarketStructureBreak?,
    val recentHigh: Double?,
    val recentLow: Double?,
    val isBullishBOSPresent: Boolean,
    val isBearishBOSPresent: Boolean,
    val isBullishCHoCHPresent: Boolean,
    val isBearishCHoCHPresent: Boolean,
    val isBullishMSSPresent: Boolean = false,
    val isBearishMSSPresent: Boolean = false
)

// ==========================================
// 2. ORDER BLOCKS (OB)
// ==========================================

enum class OrderBlockType(val label: String, val isBullish: Boolean) {
    BULLISH("Bullish Order Block", true),
    BEARISH("Bearish Order Block", false)
}

enum class OrderBlockStatus(val label: String) {
    UNMITIGATED("Unmitigated (Fresh)"),
    MITIGATED("Mitigated (Retested)"),
    INVALIDATED("Invalidated (Violated)")
}

data class OrderBlock(
    val id: String,
    val type: OrderBlockType,
    val topPrice: Double,
    val bottomPrice: Double,
    val meanThresholdPrice: Double = (topPrice + bottomPrice) / 2.0,
    val candleIndex: Int,
    val timestamp: Long,
    val volume: Double,
    val status: OrderBlockStatus,
    val mitigationCandleIndex: Int? = null,
    val strengthScore: Int = 3 // 1 to 5 scale
)

// ==========================================
// 3. FAIR VALUE GAPS (FVG)
// ==========================================

enum class FvgType(val label: String, val isBullish: Boolean) {
    BULLISH("Bullish FVG (Imbalance)", true),
    BEARISH("Bearish FVG (Imbalance)", false)
}

enum class FvgStatus(val label: String) {
    UNFILLED("Unfilled (Fresh Imbalance)"),
    PARTIALLY_FILLED("Partially Filled"),
    FULLY_FILLED("Fully Filled (Mitigated)")
}

data class FairValueGap(
    val id: String,
    val type: FvgType,
    val topPrice: Double,
    val bottomPrice: Double,
    val midPrice: Double = (topPrice + bottomPrice) / 2.0,
    val candleIndex: Int,
    val timestamp: Long,
    val status: FvgStatus,
    val fillPercentage: Double, // 0.0 to 100.0%
    val gapSizePercent: Double
)

// ==========================================
// 4. LIQUIDITY ANALYSIS
// ==========================================

enum class LiquidityPoolType(val label: String, val shortCode: String) {
    BUY_SIDE_LIQUIDITY("Buy-Side Liquidity", "BSL"),
    SELL_SIDE_LIQUIDITY("Sell-Side Liquidity", "SSL"),
    EQUAL_HIGHS("Equal Highs (Liquidity Magnet)", "EQH"),
    EQUAL_LOWS("Equal Lows (Liquidity Magnet)", "EQL")
}

data class LiquidityPool(
    val id: String,
    val type: LiquidityPoolType,
    val priceLevel: Double,
    val swingIndices: List<Int>,
    val isSwept: Boolean,
    val sweepCandleIndex: Int? = null,
    val potentialStopHunt: Boolean = false,
    val description: String
)

data class LiquiditySweepEvent(
    val candleIndex: Int,
    val timestamp: Long,
    val sweptLevel: Double,
    val poolType: LiquidityPoolType,
    val highWickPrice: Double,
    val lowWickPrice: Double,
    val closePrice: Double,
    val isPotentialStopHunt: Boolean,
    val note: String
)

data class LiquidityAnalysis(
    val pools: List<LiquidityPool>,
    val recentSweeps: List<LiquiditySweepEvent>,
    val bslNearestPrice: Double?,
    val sslNearestPrice: Double?,
    val potentialStopHuntActive: Boolean
)

// ==========================================
// 5. ADVANCED SUPPORT & RESISTANCE / SUPPLY & DEMAND / VOLUME PROFILE / PRICE ACTION
// ==========================================

enum class PriceZoneType(val label: String) {
    SUPPORT("Support Zone"),
    RESISTANCE("Resistance Zone"),
    DEMAND("Demand Zone (Aggressive Buying)"),
    SUPPLY("Supply Zone (Aggressive Selling)")
}

enum class ZoneSourceType(val shortLabel: String) {
    REPEATED_REACTION("Repeated Reaction"),
    SWING_PIVOT("Swing Pivot"),
    PREVIOUS_DAY_HIGH_LOW("PDH/PDL"),
    PREVIOUS_WEEK_HIGH_LOW("PWH/PWL"),
    SUPPLY_DEMAND_OB("Supply/Demand OB"),
    VOLUME_PROFILE_HVN("Volume Profile"),
    BREAKOUT_RETEST("Breakout-Retest")
}

data class PriceLevelZone(
    val id: String,
    val type: PriceZoneType,
    val centralPrice: Double,
    val upperPrice: Double,
    val lowerPrice: Double,
    val touchesCount: Int,
    val strength: Int, // 1 to 5 stars
    val isCurrentlyActive: Boolean,
    val distancePercent: Double, // from current price
    val zoneSources: List<ZoneSourceType> = emptyList(),
    val strengthScore: Int = (strength * 20).coerceIn(10, 100),
    val isBreakoutRetested: Boolean = false,
    val sourceSummary: String = type.label
)

data class VolumeProfileSummary(
    val pocPrice: Double,
    val vahPrice: Double,
    val valPrice: Double,
    val isVolumeReliable: Boolean,
    val totalVolumeEvaluated: Double
)

enum class PriceActionPatternType(val label: String, val isBullish: Boolean) {
    BULLISH_ENGULFING("Bullish Engulfing", true),
    BEARISH_ENGULFING("Bearish Engulfing", false),
    BULLISH_PIN_BAR("Bullish Pin Bar", true),
    BEARISH_PIN_BAR("Bearish Pin Bar", false),
    BULLISH_REJECTION_WICK("Bullish Rejection Candle", true),
    BEARISH_REJECTION_WICK("Bearish Rejection Candle", false)
}

data class PriceActionPatternEvent(
    val type: PriceActionPatternType,
    val candleIndex: Int,
    val timestamp: Long,
    val closePrice: Double,
    val hasZoneConfluence: Boolean,
    val isCandleClosedConfirmed: Boolean,
    val contextDescription: String
)

enum class BreakoutRetestType(val label: String, val isBullish: Boolean) {
    CONFIRMED_BREAKOUT_RETEST_BULLISH("Bullish Breakout & Retest", true),
    CONFIRMED_BREAKOUT_RETEST_BEARISH("Bearish Breakdown & Retest", false),
    FAILED_BREAKOUT_BULL_TRAP("Failed Bullish Breakout (Trap)", false),
    FAILED_BREAKOUT_BEAR_TRAP("Failed Bearish Breakdown (Trap)", true)
}

data class BreakoutRetestEvent(
    val type: BreakoutRetestType,
    val zoneLevel: Double,
    val breakCandleIndex: Int,
    val testCandleIndex: Int,
    val timestamp: Long,
    val volumeConfirmed: Boolean,
    val description: String
)

// ==========================================
// 6. STRICT FIBONACCI RETRACEMENT & EXTENSION
// ==========================================

data class FibLevel(
    val ratio: Double,
    val price: Double,
    val label: String,
    val isExtension: Boolean,
    val isGoldenPocket: Boolean = false,
    val isEquilibrium: Boolean = false
)

data class FibonacciValidationReport(
    val hasConfirmedSwingLeg: Boolean,
    val alignsWithTrendOrStructure: Boolean,
    val hasSrOrOrderBlockConfluence: Boolean,
    val hasConfirmedPriceActionReaction: Boolean,
    val hasVolumeConfirmation: Boolean,
    val alignsWithHigherTimeframe: Boolean,
    val hasValidRiskLevels: Boolean,
    val isInGoldenZone0500To0618: Boolean,
    val isStrictlyValid: Boolean,
    val passedGatesCount: Int,
    val rejectionReason: String? = null
)

data class FibonacciAnalysis(
    val swingHighPrice: Double,
    val swingLowPrice: Double,
    val swingHighTime: Long,
    val swingLowTime: Long,
    val isUptrendSwing: Boolean,
    val retracementLevels: List<FibLevel>,
    val extensionLevels: List<FibLevel>,
    val goldenPocketLow: Double,
    val goldenPocketHigh: Double,
    val equilibriumPrice: Double,
    val currentFibZoneDescription: String,
    val isSwingValid: Boolean = true,
    val goldenZone0500To0618Low: Double = goldenPocketLow,
    val goldenZone0500To0618High: Double = goldenPocketHigh,
    val goldenRatio0618Price: Double = goldenPocketLow,
    val premiumZoneLow: Double = equilibriumPrice,
    val premiumZoneHigh: Double = swingHighPrice,
    val discountZoneLow: Double = swingLowPrice,
    val discountZoneHigh: Double = equilibriumPrice,
    val validationReport: FibonacciValidationReport? = null
)

// ==========================================
// 7. MULTI-TIMEFRAME ANALYSIS (MTF)
// ==========================================

data class TimeframeSmcSnapshot(
    val timeframe: CandleTimeframe,
    val trend: MarketTrendState,
    val lastBreakType: MarketStructureBreakType?,
    val unmitigatedOBCount: Int,
    val unfilledFvgCount: Int,
    val nearestSupport: Double?,
    val nearestResistance: Double?,
    val isBullishBias: Boolean
)

enum class MtfConfluenceStance(val label: String) {
    STRONG_BULLISH_CONFLUENCE("Strong Bullish Confluence"),
    MODERATE_BULLISH("Moderate Bullish Alignment"),
    NEUTRAL_MIXED("Mixed / Indecisive Timeframes"),
    MODERATE_BEARISH("Moderate Bearish Alignment"),
    STRONG_BEARISH_CONFLUENCE("Strong Bearish Confluence")
}

data class MultiTimeframeSMCAnalysis(
    val snapshots: List<TimeframeSmcSnapshot>,
    val bullishTimeframeCount: Int,
    val bearishTimeframeCount: Int,
    val neutralTimeframeCount: Int,
    val confluenceStance: MtfConfluenceStance,
    val agreementSummary: String,
    val disagreementDetails: String
)

// ==========================================
// 8. MASTER SMC ANALYSIS RESULT
// ==========================================

data class SmcAnalysisResult(
    val symbol: String,
    val assetName: String,
    val timeframe: CandleTimeframe,
    val currentPrice: Double,
    val candleCount: Int,
    val marketStructure: MarketStructureAnalysis,
    val orderBlocks: List<OrderBlock>,
    val fairValueGaps: List<FairValueGap>,
    val liquidity: LiquidityAnalysis,
    val priceZones: List<PriceLevelZone>,
    val fibonacci: FibonacciAnalysis?,
    val multiTimeframe: MultiTimeframeSMCAnalysis?,
    val isInsufficientData: Boolean,
    val calculationTimestamp: Long = System.currentTimeMillis(),
    val volumeProfile: VolumeProfileSummary? = null,
    val breakoutRetests: List<BreakoutRetestEvent> = emptyList(),
    val priceActionPatterns: List<PriceActionPatternEvent> = emptyList(),
    val previousDayHigh: Double? = null,
    val previousDayLow: Double? = null,
    val previousWeekHigh: Double? = null,
    val previousWeekLow: Double? = null
)
