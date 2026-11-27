package com.example.data.timemachine

import com.example.data.model.CandleTimeframe
import com.example.data.scanner.FuturesDirection
import java.util.Locale

enum class BacktestOutcomeType(val label: String) {
    TP_FIRST("TP Hit First"),
    SL_FIRST("SL Hit First"),
    AMBIGUOUS_SAME_CANDLE("Ambiguous (Same Candle)"),
    UNRESOLVED("Unresolved (Horizon Reached)")
}

data class BacktestConfig(
    val feePercentPerSide: Double = 0.05,
    val slippagePercentPerSide: Double = 0.03,
    val horizonBars: Int = 24,
    val cooldownBarsBetweenTrades: Int = 4,
    val useTp1AsPrimaryTarget: Boolean = false // false = uses primary TP2 / signal R:R target
) {
    val roundTripFeePercent: Double get() = feePercentPerSide * 2.0
    val roundTripSlippagePercent: Double get() = slippagePercentPerSide * 2.0
}

data class BacktestTradeOccurrence(
    val setupIndex: Int,
    val entryIndex: Int,
    val exitIndex: Int,
    val entryTimestampMs: Long,
    val entryTimestampPkt: String,
    val exitTimestampPkt: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val grossReturnPercent: Double,
    val netReturnPercent: Double,
    val rMultiple: Double,
    val outcome: BacktestOutcomeType,
    val barsHeld: Int,
    val ambiguityNote: String? = null
)

data class InOutSampleSplitMetrics(
    val splitRatioInSample: Double = 0.70,
    val inSampleCount: Int,
    val inSampleWinRatePercent: Double,
    val inSampleExpectancyR: Double,
    val inSampleProfitFactor: Double,
    val outOfSampleCount: Int,
    val outOfSampleWinRatePercent: Double,
    val outOfSampleExpectancyR: Double,
    val outOfSampleProfitFactor: Double,
    val antiLeakageNote: String = "Strict chronological split: first 70% In-Sample vs final 30% Out-of-Sample with zero shared look-ahead.",
    val inSamplePeriodPkt: String = "N/A",
    val outOfSamplePeriodPkt: String = "N/A",
    val inSampleCandleCount: Int = 0,
    val outOfSampleCandleCount: Int = 0
)

data class HistoricalBacktestResult(
    val symbol: String,
    val timeframe: CandleTimeframe,
    val direction: FuturesDirection,
    val totalCandlesInput: Int,
    val completedCandlesEvaluated: Int,
    val testPeriodStartPkt: String,
    val testPeriodEndPkt: String,
    val sampleSize: Int,
    val tpFirstCount: Int,
    val slFirstCount: Int,
    val ambiguousSameCandleCount: Int,
    val unresolvedCount: Int,
    val empiricalWinRateConservativePercent: Double,
    val empiricalWinRateExclAmbiguousPercent: Double,
    val averageRewardRisk: Double,
    val averageNetReturnPercent: Double,
    val profitFactor: Double,
    val feePercentRoundTrip: Double,
    val slippagePercentPerSide: Double,
    val occurrences: List<BacktestTradeOccurrence>,
    val hasSufficientData: Boolean,
    val missingDataAndLimitations: List<String>,
    val antiLookAheadNote: String =
        "Anti-look-ahead enforced: each historical bar i is evaluated strictly from completed bars [0..i] prior to walking forward from bar i+1.",
    val setupTypeName: String? = null,
    val maxDrawdownPercent: Double = 0.0,
    val expectancyR: Double = 0.0,
    val expectancyPercent: Double = 0.0,
    val inOutSampleSplit: InOutSampleSplitMetrics? = null,
    val backtestDisclaimer: String = BACKTEST_DISCLAIMER
) {
    val resolvedDecisiveCount: Int get() = tpFirstCount + slFirstCount
    val resolvedAllCount: Int get() = tpFirstCount + slFirstCount + ambiguousSameCandleCount

    val winRateSummaryFormatted: String
        get() = if (sampleSize == 0) {
            "Insufficient historical occurrences"
        } else {
            String.format(
                Locale.US,
                "Conservative %.1f%% (%d/%d resolved incl. %d ambiguous)",
                empiricalWinRateConservativePercent,
                tpFirstCount,
                resolvedAllCount.coerceAtLeast(1),
                ambiguousSameCandleCount
            )
        }

    companion object {
        const val BACKTEST_DISCLAIMER =
            "Historical backtests reflect past market regimes after assumed fees & slippage; they do NOT guarantee future returns or target hits."
    }
}

data class DirectionalSetupBacktestReport(
    val symbol: String,
    val timeframe: CandleTimeframe,
    val setupTypeName: String,
    val longResult: HistoricalBacktestResult,
    val shortResult: HistoricalBacktestResult,
    val comparisonSummary: String,
    val disclaimer: String = HistoricalBacktestResult.BACKTEST_DISCLAIMER
)

data class OutcomePercentiles(
    val p10ReturnPercent: Double,
    val p25ReturnPercent: Double,
    val p50MedianReturnPercent: Double,
    val p75ReturnPercent: Double,
    val p90ReturnPercent: Double,
    val averageSimulatedReturnPercent: Double,
    val worstSimulatedDrawdownPercent: Double
)

data class MonteCarloConfig(
    val iterations: Int = 10_000,
    val seed: Long = 20261123L,
    val simulationHorizonBars: Int = 24,
    val minCandlesRequired: Int = 80,
    val minOccurrencesRequired: Int = 6,
    val blockLengthBars: Int = 3
)

data class MonteCarloResult(
    val symbol: String,
    val timeframe: CandleTimeframe,
    val direction: FuturesDirection,
    val isSufficientData: Boolean,
    val insufficientDataReason: String? = null,
    val iterations: Int,
    val seed: Long,
    val simulationHorizonBars: Int,
    val historicalSampleSize: Int,
    val historicalCandleCount: Int,
    val historicalPeriodPkt: String,
    val samplingMethod: String,
    val tpFirstPercent: Double = 0.0,
    val slFirstPercent: Double = 0.0,
    val neitherReachedPercent: Double = 0.0,
    val ambiguousInPathCount: Int = 0,
    val percentiles: OutcomePercentiles? = null,
    val assumptions: List<String> = emptyList(),
    val limitations: List<String> = emptyList(),
    val disclaimer: String = MONTE_CARLO_DISCLAIMER
) {
    companion object {
        const val MONTE_CARLO_DISCLAIMER =
            "Simulated Monte Carlo probabilities are derived purely from historical bootstrap resampling and are NEVER guaranteed real-world win rates or proven predictive accuracy."
    }
}
