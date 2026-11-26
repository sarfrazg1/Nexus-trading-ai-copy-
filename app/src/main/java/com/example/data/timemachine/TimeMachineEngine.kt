package com.example.data.timemachine

import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Time Machine Engine (adapted from trader-timemachine):
 * 1. Historical OHLCV Backtester with strict anti-look-ahead bias, fees, slippage, and conservative same-candle SL/TP handling.
 * 2. 10,000-Iteration Reproducible Monte Carlo Simulator using empirical block bootstrap of the selected asset's actual historical returns.
 * Never fabricates data or guarantees future predictive accuracy.
 */
object TimeMachineEngine {

    private const val WARMUP_BARS = 20

    fun runSetupModelBacktest(
        symbol: String,
        timeframe: CandleTimeframe,
        setupType: com.example.data.scanner.SetupModelType,
        direction: FuturesDirection,
        slDistancePercent: Double,
        tpDistancePercent: Double,
        candles: List<Candle>,
        config: BacktestConfig = BacktestConfig(),
        granularSubCandlesByBarOpenTime: Map<Long, List<Candle>> = emptyMap(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): HistoricalBacktestResult {
        return runBacktestInternal(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            slDistancePercent = slDistancePercent,
            tpDistancePercent = tpDistancePercent,
            candles = candles,
            config = config,
            granularSubCandlesByBarOpenTime = granularSubCandlesByBarOpenTime,
            referenceTimeMs = referenceTimeMs,
            setupType = setupType
        )
    }

    fun runDirectionalBacktestComparison(
        symbol: String,
        timeframe: CandleTimeframe,
        setupType: com.example.data.scanner.SetupModelType,
        slDistancePercent: Double,
        tpDistancePercent: Double,
        candles: List<Candle>,
        config: BacktestConfig = BacktestConfig(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): DirectionalSetupBacktestReport {
        val longBt = runSetupModelBacktest(
            symbol = symbol,
            timeframe = timeframe,
            setupType = setupType,
            direction = FuturesDirection.LONG,
            slDistancePercent = slDistancePercent,
            tpDistancePercent = tpDistancePercent,
            candles = candles,
            config = config,
            referenceTimeMs = referenceTimeMs
        )
        val shortBt = runSetupModelBacktest(
            symbol = symbol,
            timeframe = timeframe,
            setupType = setupType,
            direction = FuturesDirection.SHORT,
            slDistancePercent = slDistancePercent,
            tpDistancePercent = tpDistancePercent,
            candles = candles,
            config = config,
            referenceTimeMs = referenceTimeMs
        )
        val summary = String.format(
            Locale.US,
            "%s (%s %s): LONG Win %.1f%% (n=%d, Exp %+.2fR, MaxDD %.1f%%) | SHORT Win %.1f%% (n=%d, Exp %+.2fR, MaxDD %.1f%%)",
            setupType.label,
            symbol,
            timeframe.label,
            longBt.empiricalWinRateConservativePercent,
            longBt.sampleSize,
            longBt.expectancyR,
            longBt.maxDrawdownPercent,
            shortBt.empiricalWinRateConservativePercent,
            shortBt.sampleSize,
            shortBt.expectancyR,
            shortBt.maxDrawdownPercent
        )
        return DirectionalSetupBacktestReport(
            symbol = symbol,
            timeframe = timeframe,
            setupTypeName = setupType.label,
            longResult = longBt,
            shortResult = shortBt,
            comparisonSummary = summary
        )
    }

    fun runBacktest(
        symbol: String,
        timeframe: CandleTimeframe,
        direction: FuturesDirection,
        slDistancePercent: Double,
        tpDistancePercent: Double,
        candles: List<Candle>,
        config: BacktestConfig = BacktestConfig(),
        granularSubCandlesByBarOpenTime: Map<Long, List<Candle>> = emptyMap(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): HistoricalBacktestResult {
        return runBacktestInternal(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            slDistancePercent = slDistancePercent,
            tpDistancePercent = tpDistancePercent,
            candles = candles,
            config = config,
            granularSubCandlesByBarOpenTime = granularSubCandlesByBarOpenTime,
            referenceTimeMs = referenceTimeMs,
            setupType = null
        )
    }

    private fun runBacktestInternal(
        symbol: String,
        timeframe: CandleTimeframe,
        direction: FuturesDirection,
        slDistancePercent: Double,
        tpDistancePercent: Double,
        candles: List<Candle>,
        config: BacktestConfig,
        granularSubCandlesByBarOpenTime: Map<Long, List<Candle>>,
        referenceTimeMs: Long,
        setupType: com.example.data.scanner.SetupModelType?
    ): HistoricalBacktestResult {
        val completed = TimeZoneUtils.filterCompletedCandles(candles, referenceTimeMs)
            .sortedBy { it.openTime }

        val limitations = mutableListOf<String>()
        if (candles.size > completed.size) {
            limitations.add("Excluded ${candles.size - completed.size} unclosed/corrupted candle(s) to prevent incomplete-bar bias.")
        }
        if (granularSubCandlesByBarOpenTime.isEmpty()) {
            limitations.add("Granular intra-bar tick/1m data is unavailable for this pass; candles touching both SL & TP are classified conservatively as AMBIGUOUS_SAME_CANDLE.")
        }
        limitations.add("Applies flat fee (${String.format(Locale.US, "%.2f", config.feePercentPerSide)}%/side) and estimated slippage (${String.format(Locale.US, "%.2f", config.slippagePercentPerSide)}%/side); live liquidity gaps or funding spikes can differ.")

        if (completed.size < WARMUP_BARS + 5 ||
            !slDistancePercent.isFinite() || slDistancePercent <= 0.0 ||
            !tpDistancePercent.isFinite() || tpDistancePercent <= 0.0
        ) {
            limitations.add("Insufficient completed OHLCV bars (${completed.size}; need >= ${WARMUP_BARS + 5}) or invalid SL/TP bounds.")
            return HistoricalBacktestResult(
                symbol = symbol,
                timeframe = timeframe,
                direction = direction,
                totalCandlesInput = candles.size,
                completedCandlesEvaluated = completed.size,
                testPeriodStartPkt = completed.firstOrNull()?.let { TimeZoneUtils.formatKarachiShort(it.openTime) } ?: "Unavailable",
                testPeriodEndPkt = completed.lastOrNull()?.let { TimeZoneUtils.formatKarachiShort(it.openTime) } ?: "Unavailable",
                sampleSize = 0,
                tpFirstCount = 0,
                slFirstCount = 0,
                ambiguousSameCandleCount = 0,
                unresolvedCount = 0,
                empiricalWinRateConservativePercent = 0.0,
                empiricalWinRateExclAmbiguousPercent = 0.0,
                averageRewardRisk = 0.0,
                averageNetReturnPercent = 0.0,
                profitFactor = 0.0,
                feePercentRoundTrip = config.roundTripFeePercent,
                slippagePercentPerSide = config.slippagePercentPerSide,
                occurrences = emptyList(),
                hasSufficientData = false,
                missingDataAndLimitations = limitations,
                setupTypeName = setupType?.label
            )
        }

        val slFraction = slDistancePercent / 100.0
        val tpFraction = tpDistancePercent / 100.0
        val slippageFraction = (config.slippagePercentPerSide.coerceAtLeast(0.0)) / 100.0
        val roundTripFeePct = config.roundTripFeePercent.coerceAtLeast(0.0)

        val occurrences = mutableListOf<BacktestTradeOccurrence>()
        var lastTriggerIndex = -config.cooldownBarsBetweenTrades - 1

        // Strictly walk chronologically; setupCheck ONLY sees prefix completed[0..i]
        for (i in WARMUP_BARS until (completed.size - 1)) {
            if (i - lastTriggerIndex <= config.cooldownBarsBetweenTrades) continue

            val visiblePrefix = completed.subList(0, i + 1)
            val matched = if (setupType != null) {
                matchesSetupModelHistoricalRule(visiblePrefix, setupType, direction)
            } else {
                matchesHistoricalSetupRule(visiblePrefix, direction)
            }
            if (!matched) continue

            val entryIndex = i + 1
            val entryBar = completed[entryIndex]
            val rawEntry = entryBar.open
            if (!rawEntry.isFinite() || rawEntry <= 0.0) continue

            // Apply adverse entry slippage
            val effectiveEntry = if (direction == FuturesDirection.LONG) {
                rawEntry * (1.0 + slippageFraction)
            } else {
                rawEntry * (1.0 - slippageFraction)
            }

            val stopPrice = if (direction == FuturesDirection.LONG) {
                rawEntry * (1.0 - slFraction)
            } else {
                rawEntry * (1.0 + slFraction)
            }
            val tpPrice = if (direction == FuturesDirection.LONG) {
                rawEntry * (1.0 + tpFraction)
            } else {
                rawEntry * (1.0 - tpFraction)
            }

            var outcome = BacktestOutcomeType.UNRESOLVED
            var exitIndex = min(completed.lastIndex, entryIndex + config.horizonBars - 1)
            var exitPrice = completed[exitIndex].close
            var ambiguityNote: String? = null

            val walkEnd = min(completed.lastIndex, entryIndex + config.horizonBars - 1)
            for (j in entryIndex..walkEnd) {
                val bar = completed[j]
                val hitTp = if (direction == FuturesDirection.LONG) bar.high >= tpPrice else bar.low <= tpPrice
                val hitSl = if (direction == FuturesDirection.LONG) bar.low <= stopPrice else bar.high >= stopPrice

                if (hitTp && hitSl) {
                    // Check finer granular candles if supplied; NEVER assume favorable TP-first!
                    val subBars = granularSubCandlesByBarOpenTime[bar.openTime]
                    val resolvedSub = if (!subBars.isNullOrEmpty()) {
                        resolveWithSubCandles(direction, tpPrice, stopPrice, subBars)
                    } else null

                    if (resolvedSub != null) {
                        outcome = resolvedSub
                        exitIndex = j
                        exitPrice = if (resolvedSub == BacktestOutcomeType.TP_FIRST) tpPrice else stopPrice
                    } else {
                        outcome = BacktestOutcomeType.AMBIGUOUS_SAME_CANDLE
                        exitIndex = j
                        exitPrice = stopPrice // Conservative accounting treats ambiguous bar as stop-risk cost
                        ambiguityNote = "Both SL & TP touched within ${timeframe.label} bar at ${TimeZoneUtils.formatKarachiShort(bar.openTime)}; intra-bar sequence unknown so classified conservatively as AMBIGUOUS."
                    }
                    break
                } else if (hitSl) {
                    outcome = BacktestOutcomeType.SL_FIRST
                    exitIndex = j
                    // Adverse exit slippage on stop
                    exitPrice = if (direction == FuturesDirection.LONG) {
                        stopPrice * (1.0 - slippageFraction)
                    } else {
                        stopPrice * (1.0 + slippageFraction)
                    }
                    break
                } else if (hitTp) {
                    outcome = BacktestOutcomeType.TP_FIRST
                    exitIndex = j
                    exitPrice = if (direction == FuturesDirection.LONG) {
                        tpPrice * (1.0 - slippageFraction * 0.5)
                    } else {
                        tpPrice * (1.0 + slippageFraction * 0.5)
                    }
                    break
                }
            }

            if (outcome == BacktestOutcomeType.UNRESOLVED) {
                // Close mark-to-market at horizon end with adverse exit slippage
                val endClose = completed[exitIndex].close
                exitPrice = if (direction == FuturesDirection.LONG) {
                    endClose * (1.0 - slippageFraction)
                } else {
                    endClose * (1.0 + slippageFraction)
                }
            }

            val grossRetPct = if (direction == FuturesDirection.LONG) {
                ((exitPrice - effectiveEntry) / effectiveEntry) * 100.0
            } else {
                ((effectiveEntry - exitPrice) / effectiveEntry) * 100.0
            }
            val netRetPct = grossRetPct - roundTripFeePct
            val rMultiple = if (slDistancePercent > 0.0) netRetPct / slDistancePercent else 0.0

            val entryTs = if (entryBar.openTime > 0L) entryBar.openTime else 0L
            val exitBar = completed[exitIndex]
            val exitTs = if (exitBar.closeTime > 0L) exitBar.closeTime else exitBar.openTime

            occurrences.add(
                BacktestTradeOccurrence(
                    setupIndex = i,
                    entryIndex = entryIndex,
                    exitIndex = exitIndex,
                    entryTimestampMs = entryTs,
                    entryTimestampPkt = TimeZoneUtils.formatKarachiShort(entryTs),
                    exitTimestampPkt = TimeZoneUtils.formatKarachiShort(exitTs),
                    entryPrice = effectiveEntry,
                    stopLoss = stopPrice,
                    takeProfit = tpPrice,
                    grossReturnPercent = grossRetPct,
                    netReturnPercent = netRetPct,
                    rMultiple = rMultiple,
                    outcome = outcome,
                    barsHeld = (exitIndex - entryIndex + 1).coerceAtLeast(1),
                    ambiguityNote = ambiguityNote
                )
            )
            lastTriggerIndex = i
        }

        val tpFirst = occurrences.count { it.outcome == BacktestOutcomeType.TP_FIRST }
        val slFirst = occurrences.count { it.outcome == BacktestOutcomeType.SL_FIRST }
        val ambiguous = occurrences.count { it.outcome == BacktestOutcomeType.AMBIGUOUS_SAME_CANDLE }
        val unresolved = occurrences.count { it.outcome == BacktestOutcomeType.UNRESOLVED }

        val conservativeDenom = tpFirst + slFirst + ambiguous
        val winRateConservative = if (conservativeDenom > 0) (tpFirst.toDouble() / conservativeDenom) * 100.0 else 0.0

        val decisiveDenom = tpFirst + slFirst
        val winRateExclAmbiguous = if (decisiveDenom > 0) (tpFirst.toDouble() / decisiveDenom) * 100.0 else 0.0

        val avgNetReturn = if (occurrences.isNotEmpty()) occurrences.map { it.netReturnPercent }.average() else 0.0
        val avgR = if (occurrences.isNotEmpty()) occurrences.map { it.rMultiple }.average() else 0.0

        val grossWins = occurrences.filter { it.netReturnPercent > 0.0 }.sumOf { it.netReturnPercent }
        val grossLosses = abs(occurrences.filter { it.netReturnPercent < 0.0 }.sumOf { it.netReturnPercent })
        val profitFactor = when {
            grossLosses > 1e-9 -> grossWins / grossLosses
            grossWins > 0.0 -> 9.99
            else -> 0.0
        }

        // Peak-to-trough equity curve max drawdown (%)
        var equity = 100.0
        var peakEquity = 100.0
        var maxDrawdownPct = 0.0
        for (occ in occurrences) {
            equity *= (1.0 + (occ.netReturnPercent / 100.0)).coerceAtLeast(0.01)
            if (equity > peakEquity) peakEquity = equity
            val dd = if (peakEquity > 0.0) ((peakEquity - equity) / peakEquity) * 100.0 else 0.0
            if (dd > maxDrawdownPct) maxDrawdownPct = dd
        }

        // Expectancy in R and %
        val wins = occurrences.filter { it.netReturnPercent > 0.0 }
        val losses = occurrences.filter { it.netReturnPercent <= 0.0 }
        val winProb = if (occurrences.isNotEmpty()) wins.size.toDouble() / occurrences.size else 0.0
        val lossProb = if (occurrences.isNotEmpty()) losses.size.toDouble() / occurrences.size else 0.0
        val avgWinR = if (wins.isNotEmpty()) wins.map { it.rMultiple }.average() else 0.0
        val avgLossR = if (losses.isNotEmpty()) abs(losses.map { it.rMultiple }.average()) else 0.0
        val expectancyR = (winProb * avgWinR) - (lossProb * avgLossR)
        val expectancyPct = avgNetReturn

        // Chronological In-Sample (first 70% bars) vs Out-of-Sample (final 30% bars)
        val splitIndex = (completed.size * 0.70).toInt().coerceIn(WARMUP_BARS, completed.lastIndex)
        val isOccs = occurrences.filter { it.entryIndex < splitIndex }
        val oosOccs = occurrences.filter { it.entryIndex >= splitIndex }

        fun winRateOf(list: List<BacktestTradeOccurrence>): Double {
            val tp = list.count { it.outcome == BacktestOutcomeType.TP_FIRST }
            val denom = list.count { it.outcome != BacktestOutcomeType.UNRESOLVED }
            return if (denom > 0) (tp.toDouble() / denom) * 100.0 else 0.0
        }

        fun expectancyROf(list: List<BacktestTradeOccurrence>): Double =
            if (list.isNotEmpty()) list.map { it.rMultiple }.average() else 0.0

        fun pfOf(list: List<BacktestTradeOccurrence>): Double {
            val w = list.filter { it.netReturnPercent > 0.0 }.sumOf { it.netReturnPercent }
            val l = abs(list.filter { it.netReturnPercent < 0.0 }.sumOf { it.netReturnPercent })
            return when {
                l > 1e-9 -> w / l
                w > 0.0 -> 9.99
                else -> 0.0
            }
        }

        val splitMetrics = InOutSampleSplitMetrics(
            splitRatioInSample = 0.70,
            inSampleCount = isOccs.size,
            inSampleWinRatePercent = winRateOf(isOccs),
            inSampleExpectancyR = expectancyROf(isOccs),
            inSampleProfitFactor = pfOf(isOccs),
            outOfSampleCount = oosOccs.size,
            outOfSampleWinRatePercent = winRateOf(oosOccs),
            outOfSampleExpectancyR = expectancyROf(oosOccs),
            outOfSampleProfitFactor = pfOf(oosOccs)
        )

        val sufficient = occurrences.size >= 6
        if (!sufficient) {
            limitations.add("Low historical occurrence count (${occurrences.size} matching setups found). Small samples have wide statistical variance.")
        }
        if (ambiguous > 0) {
            limitations.add("$ambiguous occurrence(s) touched both SL & TP on the same candle and were counted conservatively outside wins.")
        }

        return HistoricalBacktestResult(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            totalCandlesInput = candles.size,
            completedCandlesEvaluated = completed.size,
            testPeriodStartPkt = TimeZoneUtils.formatKarachiShort(completed.first().openTime),
            testPeriodEndPkt = TimeZoneUtils.formatKarachiShort(completed.last().closeTime.takeIf { it > 0L } ?: completed.last().openTime),
            sampleSize = occurrences.size,
            tpFirstCount = tpFirst,
            slFirstCount = slFirst,
            ambiguousSameCandleCount = ambiguous,
            unresolvedCount = unresolved,
            empiricalWinRateConservativePercent = winRateConservative,
            empiricalWinRateExclAmbiguousPercent = winRateExclAmbiguous,
            averageRewardRisk = avgR,
            averageNetReturnPercent = avgNetReturn,
            profitFactor = profitFactor,
            feePercentRoundTrip = config.roundTripFeePercent,
            slippagePercentPerSide = config.slippagePercentPerSide,
            occurrences = occurrences,
            hasSufficientData = sufficient,
            missingDataAndLimitations = limitations,
            setupTypeName = setupType?.label,
            maxDrawdownPercent = maxDrawdownPct,
            expectancyR = expectancyR,
            expectancyPercent = expectancyPct,
            inOutSampleSplit = splitMetrics
        )
    }

    fun runBacktestForSignal(
        signal: FuturesScanSignal,
        candles: List<Candle>,
        config: BacktestConfig = BacktestConfig(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): HistoricalBacktestResult {
        val tpDist = if (config.useTp1AsPrimaryTarget) signal.tp1DistancePercent else signal.tp2DistancePercent
        return runBacktest(
            symbol = signal.symbol,
            timeframe = signal.primaryTimeframe,
            direction = signal.direction,
            slDistancePercent = signal.slDistancePercent.coerceAtLeast(0.25),
            tpDistancePercent = tpDist.coerceAtLeast(0.4),
            candles = candles,
            config = config,
            referenceTimeMs = referenceTimeMs
        )
    }

    /**
     * Runs 10,000 reproducible Monte Carlo paths using empirical block bootstrap of the asset's
     * own historical candle returns (OHLC intra-bar high/low volatility relative to open/close preserved).
     * Honestly reports insufficient data instead of synthesizing results if history is inadequate.
     */
    fun runMonteCarlo10000(
        symbol: String,
        timeframe: CandleTimeframe,
        direction: FuturesDirection,
        slDistancePercent: Double,
        tpDistancePercent: Double,
        candles: List<Candle>,
        backtestResult: HistoricalBacktestResult,
        mcConfig: MonteCarloConfig = MonteCarloConfig(),
        backtestConfig: BacktestConfig = BacktestConfig(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): MonteCarloResult {
        val completed = TimeZoneUtils.filterCompletedCandles(candles, referenceTimeMs)
            .sortedBy { it.openTime }

        val periodStr = if (completed.size >= 2) {
            "${TimeZoneUtils.formatKarachiShort(completed.first().openTime)} -> ${TimeZoneUtils.formatKarachiShort(completed.last().closeTime.takeIf { it > 0L } ?: completed.last().openTime)}"
        } else {
            "Unavailable"
        }

        val samplingDesc = "Stationary/Block Empirical OHLC Bootstrap (${mcConfig.blockLengthBars}-bar blocks) preserving historical volatility & tail excursions, seed=${mcConfig.seed}"

        if (completed.size < mcConfig.minCandlesRequired ||
            backtestResult.sampleSize < mcConfig.minOccurrencesRequired ||
            !slDistancePercent.isFinite() || slDistancePercent <= 0.0 ||
            !tpDistancePercent.isFinite() || tpDistancePercent <= 0.0
        ) {
            val reason = buildString {
                append("Insufficient historical data on $symbol (${timeframe.label}) for 10,000-path Monte Carlo: ")
                if (completed.size < mcConfig.minCandlesRequired) {
                    append("${completed.size}/${mcConfig.minCandlesRequired} completed candles available; ")
                }
                if (backtestResult.sampleSize < mcConfig.minOccurrencesRequired) {
                    append("${backtestResult.sampleSize}/${mcConfig.minOccurrencesRequired} backtested historical setup occurrences found.")
                }
            }
            return MonteCarloResult(
                symbol = symbol,
                timeframe = timeframe,
                direction = direction,
                isSufficientData = false,
                insufficientDataReason = reason.trim(),
                iterations = mcConfig.iterations,
                seed = mcConfig.seed,
                simulationHorizonBars = mcConfig.simulationHorizonBars,
                historicalSampleSize = backtestResult.sampleSize,
                historicalCandleCount = completed.size,
                historicalPeriodPkt = periodStr,
                samplingMethod = samplingDesc,
                limitations = listOf(
                    reason.trim(),
                    "No simulated win rates or paths were generated because minimum empirical sample size requirements were not met."
                )
            )
        }

        // Extract historical bar kinematics: (openToCloseRet, openToHighRet, openToLowRet)
        data class BarStep(val closeRet: Double, val highRet: Double, val lowRet: Double)
        val steps = completed.mapNotNull { c ->
            if (c.open > 0.0 && c.high >= c.low) {
                BarStep(
                    closeRet = (c.close - c.open) / c.open,
                    highRet = (c.high - c.open) / c.open,
                    lowRet = (c.low - c.open) / c.open
                )
            } else null
        }

        if (steps.size < 30) {
            return MonteCarloResult(
                symbol = symbol,
                timeframe = timeframe,
                direction = direction,
                isSufficientData = false,
                insufficientDataReason = "Historical step extraction yielded only ${steps.size} valid bars (minimum 30 required).",
                iterations = mcConfig.iterations,
                seed = mcConfig.seed,
                simulationHorizonBars = mcConfig.simulationHorizonBars,
                historicalSampleSize = backtestResult.sampleSize,
                historicalCandleCount = completed.size,
                historicalPeriodPkt = periodStr,
                samplingMethod = samplingDesc
            )
        }

        val rng = Random(mcConfig.seed)
        val iterations = mcConfig.iterations.coerceAtLeast(1)
        val horizon = mcConfig.simulationHorizonBars.coerceAtLeast(1)
        val blockLen = mcConfig.blockLengthBars.coerceIn(1, 10)
        val maxStart = (steps.size - blockLen).coerceAtLeast(0)

        val slBarrier = if (direction == FuturesDirection.LONG) 1.0 - (slDistancePercent / 100.0) else 1.0 + (slDistancePercent / 100.0)
        val tpBarrier = if (direction == FuturesDirection.LONG) 1.0 + (tpDistancePercent / 100.0) else 1.0 - (tpDistancePercent / 100.0)
        val roundTripCostPct = backtestConfig.roundTripFeePercent + backtestConfig.roundTripSlippagePercent

        var tpFirstHits = 0
        var slFirstHits = 0
        var neitherHits = 0
        var ambiguousCount = 0

        val simulatedReturns = DoubleArray(iterations)
        var worstPathDrawdownPct = 0.0

        for (sim in 0 until iterations) {
            var price = 1.0
            var resolved = false
            var finalReturnPct = 0.0
            var pathWorstAdvPct = 0.0

            var barCounter = 0
            while (barCounter < horizon && !resolved) {
                val blockStart = if (maxStart > 0) rng.nextInt(0, maxStart + 1) else 0
                for (offset in 0 until blockLen) {
                    if (barCounter >= horizon || resolved) break
                    val step = steps[(blockStart + offset).coerceAtMost(steps.lastIndex)]
                    val barHigh = price * (1.0 + step.highRet)
                    val barLow = price * (1.0 + step.lowRet)
                    val barClose = (price * (1.0 + step.closeRet)).coerceAtLeast(1e-6)

                    val adverseMovePct = if (direction == FuturesDirection.LONG) {
                        ((1.0 - barLow) * 100.0).coerceAtLeast(0.0)
                    } else {
                        ((barHigh - 1.0) * 100.0).coerceAtLeast(0.0)
                    }
                    pathWorstAdvPct = max(pathWorstAdvPct, adverseMovePct)

                    val hitTp = if (direction == FuturesDirection.LONG) barHigh >= tpBarrier else barLow <= tpBarrier
                    val hitSl = if (direction == FuturesDirection.LONG) barLow <= slBarrier else barHigh >= slBarrier

                    when {
                        hitTp && hitSl -> {
                            // Conservative: never assume TP won on ambiguous bar
                            slFirstHits++
                            ambiguousCount++
                            finalReturnPct = -slDistancePercent - roundTripCostPct
                            resolved = true
                        }
                        hitSl -> {
                            slFirstHits++
                            finalReturnPct = -slDistancePercent - roundTripCostPct
                            resolved = true
                        }
                        hitTp -> {
                            tpFirstHits++
                            finalReturnPct = tpDistancePercent - roundTripCostPct
                            resolved = true
                        }
                        else -> {
                            price = barClose
                        }
                    }
                    barCounter++
                }
            }

            if (!resolved) {
                neitherHits++
                val mtmPct = if (direction == FuturesDirection.LONG) {
                    (price - 1.0) * 100.0
                } else {
                    (1.0 - price) * 100.0
                }
                finalReturnPct = mtmPct - roundTripCostPct
            }

            simulatedReturns[sim] = finalReturnPct
            worstPathDrawdownPct = max(worstPathDrawdownPct, pathWorstAdvPct)
        }

        simulatedReturns.sort()
        fun percentile(p: Double): Double {
            val idx = ((p / 100.0) * (simulatedReturns.size - 1)).toInt().coerceIn(0, simulatedReturns.lastIndex)
            return simulatedReturns[idx]
        }

        val tpPct = (tpFirstHits.toDouble() / iterations) * 100.0
        val slPct = (slFirstHits.toDouble() / iterations) * 100.0
        val neitherPct = (neitherHits.toDouble() / iterations) * 100.0

        return MonteCarloResult(
            symbol = symbol,
            timeframe = timeframe,
            direction = direction,
            isSufficientData = true,
            iterations = iterations,
            seed = mcConfig.seed,
            simulationHorizonBars = horizon,
            historicalSampleSize = backtestResult.sampleSize,
            historicalCandleCount = completed.size,
            historicalPeriodPkt = periodStr,
            samplingMethod = samplingDesc,
            tpFirstPercent = tpPct,
            slFirstPercent = slPct,
            neitherReachedPercent = neitherPct,
            ambiguousInPathCount = ambiguousCount,
            percentiles = OutcomePercentiles(
                p10ReturnPercent = percentile(10.0),
                p25ReturnPercent = percentile(25.0),
                p50MedianReturnPercent = percentile(50.0),
                p75ReturnPercent = percentile(75.0),
                p90ReturnPercent = percentile(90.0),
                averageSimulatedReturnPercent = simulatedReturns.average(),
                worstSimulatedDrawdownPercent = worstPathDrawdownPct
            ),
            assumptions = listOf(
                "$iterations independent bootstrap paths over $horizon ${timeframe.label} bars using ${completed.size} completed historical bars.",
                "SL distance: ${String.format(Locale.US, "%.2f%%", slDistancePercent)} | TP target: ${String.format(Locale.US, "%.2f%%", tpDistancePercent)}.",
                "Subtracted ${String.format(Locale.US, "%.2f%%", roundTripCostPct)} total fees & slippage per simulated outcome; ambiguous TP+SL bars treated conservatively as SL loss."
            ),
            limitations = listOf(
                "Regime changes, black-swan liquidation cascades, and exchange spread spikes outside the sampled historical window are not modeled.",
                "Does NOT incorporate real-time orderbook depth or future macro catalysts."
            ),
            disclaimer = MonteCarloResult.MONTE_CARLO_DISCLAIMER
        )
    }

    /**
     * Strictly causal historical rule check inspecting only completed bars [0..i].
     * Evaluates EMA20 trend tilt + RSI health + candle momentum/breakout relative to recent swing.
     */
    internal fun matchesHistoricalSetupRule(
        visiblePrefix: List<Candle>,
        direction: FuturesDirection
    ): Boolean {
        if (visiblePrefix.size < WARMUP_BARS) return false
        val current = visiblePrefix.last()
        val prev = visiblePrefix[visiblePrefix.lastIndex - 1]

        // Simple EMA20 on closes (prefix only)
        val k = 2.0 / 21.0
        var ema20 = visiblePrefix.take(20).map { it.close }.average()
        for (idx in 20 until visiblePrefix.size) {
            ema20 = (visiblePrefix[idx].close * k) + (ema20 * (1.0 - k))
        }

        // Prior 8-bar structural high/low excluding current bar
        val lookbackStart = max(0, visiblePrefix.size - 9)
        val priorSlice = visiblePrefix.subList(lookbackStart, visiblePrefix.lastIndex)
        val recentHigh = priorSlice.maxOfOrNull { it.high } ?: prev.high
        val recentLow = priorSlice.minOfOrNull { it.low } ?: prev.low

        // Avg volume
        val volSlice = visiblePrefix.subList(max(0, visiblePrefix.size - 16), visiblePrefix.lastIndex)
        val avgVol = if (volSlice.isNotEmpty()) volSlice.map { it.volume }.average() else current.volume
        val volHealthy = avgVol <= 0.0 || current.volume >= avgVol * 0.85

        return if (direction == FuturesDirection.LONG) {
            val aboveTrend = current.close > ema20
            val bullishClose = current.close > current.open
            val pullbackReclaimOrBreakout = current.close > prev.high || current.close > recentHigh * 0.996
            aboveTrend && bullishClose && pullbackReclaimOrBreakout && volHealthy
        } else {
            val belowTrend = current.close < ema20
            val bearishClose = current.close < current.open
            val breakdownOrRejection = current.close < prev.low || current.close < recentLow * 1.004
            belowTrend && bearishClose && breakdownOrRejection && volHealthy
        }
    }

    internal fun matchesSetupModelHistoricalRule(
        visiblePrefix: List<Candle>,
        setupType: com.example.data.scanner.SetupModelType,
        direction: FuturesDirection
    ): Boolean {
        if (visiblePrefix.size < WARMUP_BARS) return false
        val current = visiblePrefix.last()
        val prev = visiblePrefix[visiblePrefix.lastIndex - 1]
        val priorSlice = visiblePrefix.subList(max(0, visiblePrefix.size - 12), visiblePrefix.lastIndex)
        val priorLow = priorSlice.minOfOrNull { it.low } ?: prev.low
        val priorHigh = priorSlice.maxOfOrNull { it.high } ?: prev.high

        return when (setupType) {
            com.example.data.scanner.SetupModelType.LIQUIDITY_SWEEP_REVERSAL -> {
                val range = (current.high - current.low).coerceAtLeast(1e-9)
                if (direction == FuturesDirection.LONG) {
                    val sweptLow = current.low < priorLow || prev.low < priorLow
                    val reclaimed = current.close > priorLow && current.close > current.open
                    val lowerWick = min(current.open, current.close) - current.low
                    sweptLow && reclaimed && (lowerWick / range >= 0.25 || current.close > prev.high)
                } else {
                    val sweptHigh = current.high > priorHigh || prev.high > priorHigh
                    val rejected = current.close < priorHigh && current.close < current.open
                    val upperWick = current.high - max(current.open, current.close)
                    sweptHigh && rejected && (upperWick / range >= 0.25 || current.close < prev.low)
                }
            }
            com.example.data.scanner.SetupModelType.TREND_CONTINUATION -> {
                matchesHistoricalSetupRule(visiblePrefix, direction)
            }
        }
    }

    private fun resolveWithSubCandles(
        direction: FuturesDirection,
        tpPrice: Double,
        stopPrice: Double,
        subCandles: List<Candle>
    ): BacktestOutcomeType? {
        for (sub in subCandles.sortedBy { it.openTime }) {
            val hitTp = if (direction == FuturesDirection.LONG) sub.high >= tpPrice else sub.low <= tpPrice
            val hitSl = if (direction == FuturesDirection.LONG) sub.low <= stopPrice else sub.high >= stopPrice
            if (hitTp && hitSl) return null // Still ambiguous even at granular resolution
            if (hitSl) return BacktestOutcomeType.SL_FIRST
            if (hitTp) return BacktestOutcomeType.TP_FIRST
        }
        return null
    }
}
