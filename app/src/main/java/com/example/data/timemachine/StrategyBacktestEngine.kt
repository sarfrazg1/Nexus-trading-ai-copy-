package com.example.data.timemachine

import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.FuturesDirection
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Workstream 4: Real Multi-Coin Historical Backtest Engine (up to 6 months, ~18,000+ 15M bars).
 * - Strictly causal (zero look-ahead): bar i only reads indicators/candles with closeTime <= bar[i].closeTime.
 * - Causal higher-timeframe alignment (1H, 4H, 1D completed bars whose closeTime <= bar15m.closeTime).
 * - Models entry/exit fees, slippage, actual historical 8h funding carry rates, and conservative same-bar SL/TP handling.
 * - Computes chronological 70% In-Sample / 30% Out-of-Sample, 4-window Walk-Forward, Monthly PKT, Coin-wise, and LONG vs SHORT stats.
 */
data class MultiCoinBacktestConfig(
    val lookbackDays: Int = 180,
    val startingCapitalUsdt: Double = 10_000.0,
    val riskPerTradePercent: Double = 1.0,
    val minSetupQualityScore: Int = 92,
    val minNetRiskReward: Double = 4.0,
    val takerFeePercentPerSide: Double = 0.05,
    val makerFeePercentPerSide: Double = 0.02,
    val slippagePercentPerSide: Double = 0.03,
    val defaultFundingPer8hFraction: Double = 0.0001,
    val horizonBars15m: Int = 96, // 24 hours max hold on 15M
    val cooldownBars15m: Int = 12 // 3 hours cooldown between triggers
)

data class SimulatedBacktestTrade(
    val symbol: String,
    val direction: FuturesDirection,
    val qualityScore: Int,
    val entryBarIndex: Int,
    val exitBarIndex: Int,
    val entryTimeMs: Long,
    val exitTimeMs: Long,
    val entryTimePkt: String,
    val exitTimePkt: String,
    val monthKeyPkt: String,
    val entryPrice: Double,
    val exitPrice: Double,
    val stopLoss: Double,
    val takeProfit: Double,
    val grossRiskReward: Double,
    val netRiskReward: Double,
    val grossPnlUsdt: Double,
    val netPnlUsdt: Double,
    val feesAndSlippageUsdt: Double,
    val fundingCostUsdt: Double,
    val netReturnOnAccountPercent: Double,
    val netRMultiple: Double,
    val outcome: BacktestOutcomeType,
    val isOutOfSample: Boolean
)

data class DirectionalBacktestBreakdown(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePercent: Double,
    val profitFactor: Double,
    val netPnlUsdt: Double,
    val expectancyR: Double
)

data class MonthlyBacktestBucket(
    val monthPkt: String,
    val trades: Int,
    val wins: Int,
    val winRatePercent: Double,
    val netPnlUsdt: Double,
    val profitFactor: Double
)

data class WalkForwardWindowResult(
    val windowIndex: Int,
    val periodLabelPkt: String,
    val inSampleTrades: Int,
    val inSampleWinRatePercent: Double,
    val inSampleNetPnlUsdt: Double,
    val outOfSampleTrades: Int,
    val outOfSampleWinRatePercent: Double,
    val outOfSampleNetPnlUsdt: Double,
    val outOfSampleProfitFactor: Double
)

data class AggregateBacktestMetrics(
    val totalTrades: Int,
    val wins: Int,
    val losses: Int,
    val ambiguousSameCandle: Int,
    val unresolvedAtHorizon: Int,
    val winRatePercent: Double,
    val profitFactor: Double,
    val grossPnlUsdt: Double,
    val netPnlUsdt: Double,
    val totalCostsUsdt: Double,
    val expectancyR: Double,
    val expectancyPercent: Double,
    val maxDrawdownUsdt: Double,
    val maxDrawdownPercent: Double,
    val longStats: DirectionalBacktestBreakdown,
    val shortStats: DirectionalBacktestBreakdown
)

data class CoinBacktestBreakdown(
    val symbol: String,
    val total15mCandles: Int,
    val coverageDays: Double,
    val fundingEventsCount: Int,
    val hasSufficientData: Boolean,
    val statusNote: String,
    val periodStartPkt: String,
    val periodEndPkt: String,
    val metrics: AggregateBacktestMetrics
)

data class MultiCoinBacktestReport(
    val config: MultiCoinBacktestConfig,
    val generatedAtMs: Long,
    val generatedAtPkt: String,
    val overall: AggregateBacktestMetrics,
    val coinBreakdowns: List<CoinBacktestBreakdown>,
    val inOutSampleSplit: InOutSampleSplitMetrics,
    val walkForwardWindows: List<WalkForwardWindowResult>,
    val monthlyBreakdown: List<MonthlyBacktestBucket>,
    val sampleTrades: List<SimulatedBacktestTrade>,
    val hasSufficientOverallData: Boolean,
    val statusSummary: String,
    val methodologyDisclosure: String
)

data class SingleCoinHistoricalInput(
    val symbol: String,
    val candles15m: List<Candle>,
    val candles1h: List<Candle> = emptyList(),
    val candles4h: List<Candle> = emptyList(),
    val candles1d: List<Candle> = emptyList(),
    val fundingHistory: List<BinanceFuturesApiClient.HistoricalFundingEntry> = emptyList()
)

object StrategyBacktestEngine {

    private const val MIN_15M_CANDLES_PER_COIN = 120

    private data class CausalSeries15m(
        val ema20: DoubleArray,
        val ema50: DoubleArray,
        val ema100: DoubleArray,
        val ema200: DoubleArray,
        val rsi14: DoubleArray,
        val macdHist: DoubleArray,
        val atr14: DoubleArray,
        val rvol20: DoubleArray,
        val supertrendBullish: BooleanArray,
        val swingHigh20: DoubleArray,
        val swingLow20: DoubleArray
    )

    private data class HtfCausalTrend(
        val closeTimeMs: Long,
        val bullishTrend: Boolean,
        val bearishTrend: Boolean
    )

    fun runMultiCoinBacktest(
        inputs: List<SingleCoinHistoricalInput>,
        config: MultiCoinBacktestConfig = MultiCoinBacktestConfig(),
        nowMs: Long = System.currentTimeMillis()
    ): MultiCoinBacktestReport {
        val allTrades = mutableListOf<SimulatedBacktestTrade>()
        val coinBreakdowns = mutableListOf<CoinBacktestBreakdown>()

        for (input in inputs) {
            val cleaned15m = input.candles15m
                .filter { it.closeTime <= nowMs && it.openTime > 0L && it.close > 0.0 }
                .sortedBy { it.openTime }

            if (cleaned15m.size < MIN_15M_CANDLES_PER_COIN) {
                coinBreakdowns.add(
                    CoinBacktestBreakdown(
                        symbol = input.symbol,
                        total15mCandles = cleaned15m.size,
                        coverageDays = estimateCoverageDays(cleaned15m),
                        fundingEventsCount = input.fundingHistory.size,
                        hasSufficientData = false,
                        statusNote = "INSUFFICIENT DATA: Only ${cleaned15m.size} completed 15M candles loaded (min $MIN_15M_CANDLES_PER_COIN required).",
                        periodStartPkt = cleaned15m.firstOrNull()?.let { TimeZoneUtils.formatKarachiDateOnly(it.openTime) } ?: "N/A",
                        periodEndPkt = cleaned15m.lastOrNull()?.let { TimeZoneUtils.formatKarachiDateOnly(it.closeTime) } ?: "N/A",
                        metrics = computeAggregateMetrics(emptyList(), config.startingCapitalUsdt)
                    )
                )
                continue
            }

            val candles1h = if (input.candles1h.isNotEmpty()) input.candles1h.sortedBy { it.openTime } else aggregateCandles(cleaned15m, 4)
            val candles4h = if (input.candles4h.isNotEmpty()) input.candles4h.sortedBy { it.openTime } else aggregateCandles(cleaned15m, 16)
            val candles1d = if (input.candles1d.isNotEmpty()) input.candles1d.sortedBy { it.openTime } else aggregateCandles(cleaned15m, 96)

            val trades = simulateSingleCoinCausal(
                symbol = input.symbol,
                candles15m = cleaned15m,
                candles1h = candles1h,
                candles4h = candles4h,
                candles1d = candles1d,
                fundingHistory = input.fundingHistory.sortedBy { it.fundingTimeMs },
                config = config
            )
            allTrades.addAll(trades)

            val coverageDays = estimateCoverageDays(cleaned15m)
            val metrics = computeAggregateMetrics(trades, config.startingCapitalUsdt)
            coinBreakdowns.add(
                CoinBacktestBreakdown(
                    symbol = input.symbol,
                    total15mCandles = cleaned15m.size,
                    coverageDays = coverageDays,
                    fundingEventsCount = input.fundingHistory.size,
                    hasSufficientData = true,
                    statusNote = if (trades.isEmpty()) {
                        "Verified ${cleaned15m.size} bars (${String.format(Locale.US, "%.0f", coverageDays)}d): 0 setups passed >=${config.minSetupQualityScore}/100 & NET R:R >=1:${config.minNetRiskReward}"
                    } else {
                        "Verified ${cleaned15m.size} bars (${String.format(Locale.US, "%.0f", coverageDays)}d) • ${trades.size} trades"
                    },
                    periodStartPkt = TimeZoneUtils.formatKarachiDateOnly(cleaned15m.first().openTime),
                    periodEndPkt = TimeZoneUtils.formatKarachiDateOnly(cleaned15m.last().closeTime),
                    metrics = metrics
                )
            )
        }

        val sortedAll = allTrades.sortedBy { it.entryTimeMs }
        val overallMetrics = computeAggregateMetrics(sortedAll, config.startingCapitalUsdt)

        // Chronological 70% In-Sample / 30% Out-of-Sample split across time span
        val splitIndex = ((sortedAll.size * 0.70).toInt()).coerceIn(0, sortedAll.size)
        val inSampleTrades = sortedAll.take(splitIndex)
        val outSampleTrades = sortedAll.drop(splitIndex).map { it.copy(isOutOfSample = true) }
        val inMetrics = computeAggregateMetrics(inSampleTrades, config.startingCapitalUsdt)
        val outMetrics = computeAggregateMetrics(outSampleTrades, config.startingCapitalUsdt)

        val inOutSplit = InOutSampleSplitMetrics(
            splitRatioInSample = 0.70,
            inSampleCount = inSampleTrades.size,
            inSampleWinRatePercent = inMetrics.winRatePercent,
            inSampleExpectancyR = inMetrics.expectancyR,
            inSampleProfitFactor = inMetrics.profitFactor,
            outOfSampleCount = outSampleTrades.size,
            outOfSampleWinRatePercent = outMetrics.winRatePercent,
            outOfSampleExpectancyR = outMetrics.expectancyR,
            outOfSampleProfitFactor = outMetrics.profitFactor,
            antiLeakageNote = "Chronological 70% In-Sample (${inSampleTrades.size} trades) vs final 30% Out-of-Sample (${outSampleTrades.size} trades) on strict closed bars."
        )

        val walkForward = computeWalkForwardWindows(sortedAll, config.startingCapitalUsdt)
        val monthly = computeMonthlyBuckets(sortedAll, config.startingCapitalUsdt)

        val sufficientCount = coinBreakdowns.count { it.total15mCandles >= MIN_15M_CANDLES_PER_COIN }
        val hasSufficientOverall = sufficientCount > 0 && sortedAll.size >= 5

        val summary = if (!hasSufficientOverall) {
            "INSUFFICIENT DATA: Loaded $sufficientCount/${inputs.size} symbols with sufficient history and ${sortedAll.size} qualifying strict trades (>= ${config.minSetupQualityScore}/100 & NET R:R >= 1:${config.minNetRiskReward})."
        } else {
            "Tested ${sortedAll.size} strict trades across $sufficientCount coins (${inSampleTrades.size} In-Sample / ${outSampleTrades.size} Out-of-Sample) after fees, slippage & funding."
        }

        return MultiCoinBacktestReport(
            config = config,
            generatedAtMs = nowMs,
            generatedAtPkt = TimeZoneUtils.formatKarachiWithSeconds(nowMs),
            overall = overallMetrics,
            coinBreakdowns = coinBreakdowns,
            inOutSampleSplit = inOutSplit,
            walkForwardWindows = walkForward,
            monthlyBreakdown = monthly,
            sampleTrades = (outSampleTrades + inSampleTrades).sortedByDescending { it.entryTimeMs }.take(40),
            hasSufficientOverallData = hasSufficientOverall,
            statusSummary = summary,
            methodologyDisclosure = "Causal closed-candle simulation: bar i evaluates only bars [0..i] & closed HTF candles; same-bar SL+TP conflicts are recorded conservatively as losses; past backtests do NOT guarantee future returns."
        )
    }

    private fun simulateSingleCoinCausal(
        symbol: String,
        candles15m: List<Candle>,
        candles1h: List<Candle>,
        candles4h: List<Candle>,
        candles1d: List<Candle>,
        fundingHistory: List<BinanceFuturesApiClient.HistoricalFundingEntry>,
        config: MultiCoinBacktestConfig
    ): List<SimulatedBacktestTrade> {
        val n = candles15m.size
        if (n < MIN_15M_CANDLES_PER_COIN) return emptyList()

        val series = precomputeCausal15m(candles15m)
        val htf1h = precomputeHtfTrends(candles1h)
        val htf4h = precomputeHtfTrends(candles4h)
        val htf1d = precomputeHtfTrends(candles1d)

        val roundTripCostPct = (config.takerFeePercentPerSide + config.makerFeePercentPerSide) +
            (config.slippagePercentPerSide * 2.0)

        val startIndex = min(65, n - 10)
        var ptr1h = 0
        var ptr4h = 0
        var ptr1d = 0
        var nextAllowedIndex = startIndex
        val trades = mutableListOf<SimulatedBacktestTrade>()

        for (i in startIndex until (n - 2)) {
            if (i < nextAllowedIndex) continue
            val bar = candles15m[i]
            val prev = candles15m[i - 1]

            // Advance causal HTF pointers strictly up to bar.closeTime (ZERO future bar leakage)
            while (ptr1h + 1 < htf1h.size && htf1h[ptr1h + 1].closeTimeMs <= bar.closeTime) ptr1h++
            while (ptr4h + 1 < htf4h.size && htf4h[ptr4h + 1].closeTimeMs <= bar.closeTime) ptr4h++
            while (ptr1d + 1 < htf1d.size && htf1d[ptr1d + 1].closeTimeMs <= bar.closeTime) ptr1d++

            val t1h = htf1h.getOrNull(ptr1h)?.takeIf { it.closeTimeMs <= bar.closeTime }
            val t4h = htf4h.getOrNull(ptr4h)?.takeIf { it.closeTimeMs <= bar.closeTime }
            val t1d = htf1d.getOrNull(ptr1d)?.takeIf { it.closeTimeMs <= bar.closeTime }

            val atr = series.atr14[i]
            if (!atr.isFinite() || atr <= 0.0 || bar.close <= 0.0) continue
            val atrPct = (atr / bar.close) * 100.0
            if (atrPct > 6.5 || atrPct < 0.08) continue // Extreme or dead volatility rejected

            val barRange = (bar.high - bar.low).coerceAtLeast(1e-9)
            val bodyRatio = abs(bar.close - bar.open) / barRange
            if (bodyRatio < 0.30) continue

            val candidate = evaluateCausalBarCandidate(
                bar = bar,
                prev = prev,
                i = i,
                series = series,
                t1h = t1h,
                t4h = t4h,
                t1d = t1d,
                roundTripCostPct = roundTripCostPct,
                config = config
            ) ?: continue

            // Execute entry causally at NEXT bar (i+1) open price + entry slippage
            val entryBar = candles15m[i + 1]
            val slipFrac = config.slippagePercentPerSide / 100.0
            val entryFill = if (candidate.direction == FuturesDirection.LONG) {
                entryBar.open * (1.0 + slipFrac)
            } else {
                entryBar.open * (1.0 - slipFrac)
            }

            // Re-validate SL/TP orientation against realistic next-bar open fill
            val slDist = if (candidate.direction == FuturesDirection.LONG) {
                entryFill - candidate.stopLoss
            } else {
                candidate.stopLoss - entryFill
            }
            val tpDist = if (candidate.direction == FuturesDirection.LONG) {
                candidate.takeProfit - entryFill
            } else {
                entryFill - candidate.takeProfit
            }
            if (slDist <= entryFill * 0.0025 || tpDist <= slDist * 3.5) continue

            val actualGrossRR = tpDist / slDist
            val riskPct = (slDist / entryFill) * 100.0
            val grossRewardPct = (tpDist / entryFill) * 100.0
            val actualNetRR = ((grossRewardPct - roundTripCostPct).coerceAtLeast(0.0)) / (riskPct + roundTripCostPct)
            if (actualNetRR < config.minNetRiskReward) continue

            val riskBudgetUsdt = config.startingCapitalUsdt * (config.riskPerTradePercent / 100.0)
            val positionQty = riskBudgetUsdt / slDist
            val entryNotional = positionQty * entryFill
            val openFeeUsdt = entryNotional * (config.takerFeePercentPerSide / 100.0)

            var exitIndex = min(n - 1, i + config.horizonBars15m)
            var outcome = BacktestOutcomeType.UNRESOLVED
            var rawExitPrice = candles15m[exitIndex].close

            val maxScan = min(n - 1, i + 1 + config.horizonBars15m)
            for (k in (i + 1)..maxScan) {
                val c = candles15m[k]
                val hitSl = if (candidate.direction == FuturesDirection.LONG) c.low <= candidate.stopLoss else c.high >= candidate.stopLoss
                val hitTp = if (candidate.direction == FuturesDirection.LONG) c.high >= candidate.takeProfit else c.low <= candidate.takeProfit

                if (hitSl && hitTp) {
                    // Conservative same-candle rule: assume SL hit first
                    outcome = BacktestOutcomeType.AMBIGUOUS_SAME_CANDLE
                    rawExitPrice = candidate.stopLoss
                    exitIndex = k
                    break
                } else if (hitSl) {
                    outcome = BacktestOutcomeType.SL_FIRST
                    rawExitPrice = candidate.stopLoss
                    exitIndex = k
                    break
                } else if (hitTp) {
                    outcome = BacktestOutcomeType.TP_FIRST
                    rawExitPrice = candidate.takeProfit
                    exitIndex = k
                    break
                }
            }

            val exitBar = candles15m[exitIndex]
            val exitSlipFrac = if (outcome == BacktestOutcomeType.TP_FIRST) 0.0 else slipFrac
            val execExitPrice = when {
                outcome == BacktestOutcomeType.TP_FIRST -> rawExitPrice
                candidate.direction == FuturesDirection.LONG -> rawExitPrice * (1.0 - exitSlipFrac)
                else -> rawExitPrice * (1.0 + exitSlipFrac)
            }
            val closeFeeRate = if (outcome == BacktestOutcomeType.TP_FIRST) {
                config.makerFeePercentPerSide / 100.0
            } else {
                config.takerFeePercentPerSide / 100.0
            }
            val exitNotional = positionQty * execExitPrice
            val closeFeeUsdt = exitNotional * closeFeeRate

            val fundingCostUsdt = calculateHistoricalFundingCost(
                direction = candidate.direction,
                avgNotional = (entryNotional + exitNotional) / 2.0,
                entryTimeMs = entryBar.openTime,
                exitTimeMs = exitBar.closeTime,
                fundingHistory = fundingHistory,
                defaultPer8hFraction = config.defaultFundingPer8hFraction
            )

            val grossPnlUsdt = when (candidate.direction) {
                FuturesDirection.LONG -> (execExitPrice - entryFill) * positionQty
                FuturesDirection.SHORT -> (entryFill - execExitPrice) * positionQty
            }
            val entrySlipUsdt = abs(entryFill - entryBar.open) * positionQty
            val exitSlipUsdt = abs(execExitPrice - rawExitPrice) * positionQty
            val netPnlUsdt = grossPnlUsdt - openFeeUsdt - closeFeeUsdt - fundingCostUsdt
            val netRetPct = (netPnlUsdt / config.startingCapitalUsdt) * 100.0
            val netR = netPnlUsdt / riskBudgetUsdt.coerceAtLeast(1e-6)

            trades.add(
                SimulatedBacktestTrade(
                    symbol = symbol,
                    direction = candidate.direction,
                    qualityScore = candidate.qualityScore,
                    entryBarIndex = i + 1,
                    exitBarIndex = exitIndex,
                    entryTimeMs = entryBar.openTime,
                    exitTimeMs = exitBar.closeTime,
                    entryTimePkt = TimeZoneUtils.formatKarachiTimestamp(entryBar.openTime),
                    exitTimePkt = TimeZoneUtils.formatKarachiTimestamp(exitBar.closeTime),
                    monthKeyPkt = TimeZoneUtils.formatKarachiDateOnly(entryBar.openTime).take(7),
                    entryPrice = entryFill,
                    exitPrice = execExitPrice,
                    stopLoss = candidate.stopLoss,
                    takeProfit = candidate.takeProfit,
                    grossRiskReward = actualGrossRR,
                    netRiskReward = actualNetRR,
                    grossPnlUsdt = grossPnlUsdt,
                    netPnlUsdt = netPnlUsdt,
                    feesAndSlippageUsdt = openFeeUsdt + closeFeeUsdt + entrySlipUsdt + exitSlipUsdt,
                    fundingCostUsdt = fundingCostUsdt,
                    netReturnOnAccountPercent = netRetPct,
                    netRMultiple = netR,
                    outcome = outcome,
                    isOutOfSample = false
                )
            )
            nextAllowedIndex = exitIndex + config.cooldownBars15m
        }
        return trades
    }

    private data class CandidateSignal(
        val direction: FuturesDirection,
        val qualityScore: Int,
        val stopLoss: Double,
        val takeProfit: Double
    )

    private fun evaluateCausalBarCandidate(
        bar: Candle,
        prev: Candle,
        i: Int,
        series: CausalSeries15m,
        t1h: HtfCausalTrend?,
        t4h: HtfCausalTrend?,
        t1d: HtfCausalTrend?,
        roundTripCostPct: Double,
        config: MultiCoinBacktestConfig
    ): CandidateSignal? {
        val price = bar.close
        val atr = series.atr14[i]
        val ema20 = series.ema20[i]
        val ema50 = series.ema50[i]
        val ema100 = series.ema100[i]
        val ema200 = series.ema200[i]
        val rsi = series.rsi14[i]
        val macdH = series.macdHist[i]
        val rvol = series.rvol20[i]
        val stBull = series.supertrendBullish[i]
        val sh20 = series.swingHigh20[i]
        val sl20 = series.swingLow20[i]

        val bullCandidate = evaluateDirectionalScore(
            isLong = true,
            bar = bar,
            prev = prev,
            price = price,
            atr = atr,
            ema20 = ema20,
            ema50 = ema50,
            ema100 = ema100,
            ema200 = ema200,
            rsi = rsi,
            macdH = macdH,
            rvol = rvol,
            stBull = stBull,
            swingHigh = sh20,
            swingLow = sl20,
            t1h = t1h,
            t4h = t4h,
            t1d = t1d,
            roundTripCostPct = roundTripCostPct,
            config = config
        )

        val bearCandidate = evaluateDirectionalScore(
            isLong = false,
            bar = bar,
            prev = prev,
            price = price,
            atr = atr,
            ema20 = ema20,
            ema50 = ema50,
            ema100 = ema100,
            ema200 = ema200,
            rsi = rsi,
            macdH = macdH,
            rvol = rvol,
            stBull = stBull,
            swingHigh = sh20,
            swingLow = sl20,
            t1h = t1h,
            t4h = t4h,
            t1d = t1d,
            roundTripCostPct = roundTripCostPct,
            config = config
        )

        return listOfNotNull(bullCandidate, bearCandidate).maxByOrNull { it.qualityScore }
    }

    private fun evaluateDirectionalScore(
        isLong: Boolean,
        bar: Candle,
        prev: Candle,
        price: Double,
        atr: Double,
        ema20: Double,
        ema50: Double,
        ema100: Double,
        ema200: Double,
        rsi: Double,
        macdH: Double,
        rvol: Double,
        stBull: Boolean,
        swingHigh: Double,
        swingLow: Double,
        t1h: HtfCausalTrend?,
        t4h: HtfCausalTrend?,
        t1d: HtfCausalTrend?,
        roundTripCostPct: Double,
        config: MultiCoinBacktestConfig
    ): CandidateSignal? {
        // 1. Mandatory 15M candle-close trigger
        val candleOk = if (isLong) {
            bar.close > bar.open && bar.close > prev.close && stBull
        } else {
            bar.close < bar.open && bar.close < prev.close && !stBull
        }
        if (!candleOk) return null

        // 2. Block strong contradictory HTF (4H/1D)
        val strongHtfConflict = if (isLong) {
            (t4h?.bearishTrend == true && t1d?.bearishTrend == true) || (t1d?.bearishTrend == true && t4h?.bullishTrend != true)
        } else {
            (t4h?.bullishTrend == true && t1d?.bullishTrend == true) || (t1d?.bullishTrend == true && t4h?.bearishTrend != true)
        }
        if (strongHtfConflict) return null

        // 3. Structural SL & Target + Fib extension room (without widening artificially)
        val swingRange = (swingHigh - swingLow).coerceAtLeast(atr * 1.5)
        val fibGoldenLow = swingLow + swingRange * 0.382
        val fibGoldenHigh = swingLow + swingRange * 0.618
        val inDiscountPullback = price in (swingLow * 0.995)..(fibGoldenHigh * 1.015)
        val inPremiumPullback = price in ((swingHigh - swingRange * 0.618) * 0.985)..(swingHigh * 1.005)

        val sl = if (isLong) {
            val cand = min(swingLow - atr * 0.25, price - atr * 1.2)
            val dist = (price - cand).coerceIn(price * 0.005, price * 0.032)
            price - dist
        } else {
            val cand = max(swingHigh + atr * 0.25, price + atr * 1.2)
            val dist = (cand - price).coerceIn(price * 0.005, price * 0.032)
            price + dist
        }
        val slDist = abs(price - sl)
        val fibExt1618 = if (isLong) swingHigh + swingRange * 0.618 else swingLow - swingRange * 0.618
        val structuralTp = if (isLong) {
            max(swingHigh + swingRange * 0.272, fibExt1618).takeIf { it > price + slDist * 3.8 }
                ?: (price + slDist * 4.5)
        } else {
            min(swingLow - swingRange * 0.272, fibExt1618).takeIf { it < price - slDist * 3.8 }
                ?: (price - slDist * 4.5)
        }

        val riskPct = (slDist / price) * 100.0
        val rewardPct = (abs(structuralTp - price) / price) * 100.0
        val netRR = ((rewardPct - roundTripCostPct).coerceAtLeast(0.0)) / (riskPct + roundTripCostPct)
        if (netRR < config.minNetRiskReward) return null

        // 4. Multi-layer Setup Quality Score (out of 100; >= 92 required)
        var score = 0
        // Group A: EMA 20/50/100/200 stack + HTF consensus (max 28)
        val emaAlignedCount = listOf(ema20, ema50, ema100, ema200).count { if (isLong) price > it else price < it }
        val stackOrdered = if (isLong) (ema20 >= ema50 && ema50 >= ema100) else (ema20 <= ema50 && ema50 <= ema100)
        score += when {
            emaAlignedCount == 4 && stackOrdered -> 16
            emaAlignedCount >= 3 -> 12
            emaAlignedCount >= 2 -> 7
            else -> 0
        }
        val htfMatches = listOfNotNull(t1h, t4h, t1d).count {
            if (isLong) it.bullishTrend else it.bearishTrend
        }
        score += when (htfMatches) {
            3 -> 12
            2 -> 9
            1 -> 5
            else -> 0
        }

        // Group B: Fibonacci / Structural pullback / S&R zone (max 20)
        val structPullback = if (isLong) inDiscountPullback else inPremiumPullback
        val emaTouch = abs(price - ema20) / price <= 0.012 || abs(price - ema50) / price <= 0.012
        val inFibZone = price in min(fibGoldenLow, fibGoldenHigh) * 0.985..max(fibGoldenLow, fibGoldenHigh) * 1.015
        if (structPullback) score += 10
        if (emaTouch) score += 6
        if (inFibZone) score += 4

        // Group C: Price action close strength (max 16)
        val bodyPct = abs(bar.close - bar.open) / (bar.high - bar.low).coerceAtLeast(1e-9)
        score += when {
            bodyPct >= 0.55 -> 16
            bodyPct >= 0.40 -> 12
            else -> 8
        }

        // Group D: Volume / RVOL (max 12)
        score += when {
            rvol >= 1.45 -> 12
            rvol >= 1.1 -> 8
            rvol >= 0.8 -> 4
            else -> 0
        }

        // Group E: De-correlated Momentum RSI + MACD (max 14)
        val rsiOk = if (isLong) rsi in 48.0..72.0 else rsi in 28.0..52.0
        val macdOk = if (isLong) macdH > 0.0 else macdH < 0.0
        if (rsiOk && macdOk) score += 14
        else if (rsiOk || macdOk) score += 7

        // Group F: Risk & Volatility sanity (max 10)
        if (netRR >= 5.0) score += 10 else if (netRR >= config.minNetRiskReward) score += 8

        if (score < config.minSetupQualityScore) return null

        return CandidateSignal(
            direction = if (isLong) FuturesDirection.LONG else FuturesDirection.SHORT,
            qualityScore = score.coerceIn(0, 100),
            stopLoss = sl,
            takeProfit = structuralTp
        )
    }

    private fun calculateHistoricalFundingCost(
        direction: FuturesDirection,
        avgNotional: Double,
        entryTimeMs: Long,
        exitTimeMs: Long,
        fundingHistory: List<BinanceFuturesApiClient.HistoricalFundingEntry>,
        defaultPer8hFraction: Double
    ): Double {
        if (exitTimeMs <= entryTimeMs || avgNotional <= 0.0) return 0.0
        if (fundingHistory.isNotEmpty()) {
            var total = 0.0
            for (event in fundingHistory) {
                if (event.fundingTimeMs in entryTimeMs..exitTimeMs) {
                    val paidFrac = if (direction == FuturesDirection.LONG) {
                        event.fundingRateFraction
                    } else {
                        -event.fundingRateFraction
                    }
                    total += avgNotional * paidFrac
                }
            }
            return total
        }
        val elapsedHours = (exitTimeMs - entryTimeMs) / 3_600_000.0
        val periods = (elapsedHours / 8.0).toInt()
        return if (periods > 0) avgNotional * defaultPer8hFraction * periods else 0.0
    }

    private fun precomputeCausal15m(candles: List<Candle>): CausalSeries15m {
        val n = candles.size
        val ema20 = DoubleArray(n)
        val ema50 = DoubleArray(n)
        val ema100 = DoubleArray(n)
        val ema200 = DoubleArray(n)
        val rsi14 = DoubleArray(n) { 50.0 }
        val macdHist = DoubleArray(n)
        val atr14 = DoubleArray(n)
        val rvol20 = DoubleArray(n) { 1.0 }
        val stBull = BooleanArray(n) { true }
        val sh20 = DoubleArray(n)
        val sl20 = DoubleArray(n)

        val k20 = 2.0 / 21.0
        val k50 = 2.0 / 51.0
        val k100 = 2.0 / 101.0
        val k200 = 2.0 / 201.0
        val k12 = 2.0 / 13.0
        val k26 = 2.0 / 27.0
        val k9 = 2.0 / 10.0

        var e20 = candles[0].close
        var e50 = candles[0].close
        var e100 = candles[0].close
        var e200 = candles[0].close
        var e12 = candles[0].close
        var e26 = candles[0].close
        var macdSig = 0.0
        var avgGain = 0.0
        var avgLoss = 0.0
        var atrVal = candles[0].high - candles[0].low
        var volSum = 0.0
        var finalUpper = candles[0].high
        var finalLower = candles[0].low
        var prevStBull = true

        for (i in 0 until n) {
            val c = candles[i]
            if (i > 0) {
                e20 = c.close * k20 + e20 * (1.0 - k20)
                e50 = c.close * k50 + e50 * (1.0 - k50)
                e100 = c.close * k100 + e100 * (1.0 - k100)
                e200 = c.close * k200 + e200 * (1.0 - k200)
                e12 = c.close * k12 + e12 * (1.0 - k12)
                e26 = c.close * k26 + e26 * (1.0 - k26)
                val macdLine = e12 - e26
                macdSig = macdLine * k9 + macdSig * (1.0 - k9)
                macdHist[i] = macdLine - macdSig

                val change = c.close - candles[i - 1].close
                val gain = max(0.0, change)
                val loss = max(0.0, -change)
                if (i <= 14) {
                    avgGain += gain / 14.0
                    avgLoss += loss / 14.0
                } else {
                    avgGain = (avgGain * 13.0 + gain) / 14.0
                    avgLoss = (avgLoss * 13.0 + loss) / 14.0
                }
                val rs = if (avgLoss > 1e-9) avgGain / avgLoss else 100.0
                rsi14[i] = if (i >= 14) 100.0 - (100.0 / (1.0 + rs)) else 50.0

                val tr = max(
                    c.high - c.low,
                    max(abs(c.high - candles[i - 1].close), abs(c.low - candles[i - 1].close))
                )
                atrVal = if (i < 14) ((atrVal * i + tr) / (i + 1)) else ((atrVal * 13.0 + tr) / 14.0)
            }

            ema20[i] = e20
            ema50[i] = e50
            ema100[i] = e100
            ema200[i] = e200
            atr14[i] = atrVal

            val hl2 = (c.high + c.low) / 2.0
            val basicUpper = hl2 + 3.0 * atrVal
            val basicLower = hl2 - 3.0 * atrVal
            if (i > 0) {
                finalUpper = if (basicUpper < finalUpper || candles[i - 1].close > finalUpper) basicUpper else finalUpper
                finalLower = if (basicLower > finalLower || candles[i - 1].close < finalLower) basicLower else finalLower
                prevStBull = when {
                    c.close > finalUpper -> true
                    c.close < finalLower -> false
                    else -> prevStBull
                }
            } else {
                finalUpper = basicUpper
                finalLower = basicLower
            }
            stBull[i] = prevStBull

            volSum += c.volume
            if (i >= 20) volSum -= candles[i - 20].volume
            val avgVol = if (i >= 20) volSum / 20.0 else (volSum / (i + 1))
            rvol20[i] = if (avgVol > 1e-9) c.volume / avgVol else 1.0

            val lookStart = max(0, i - 19)
            var maxH = candles[lookStart].high
            var minL = candles[lookStart].low
            for (j in lookStart..i) {
                if (candles[j].high > maxH) maxH = candles[j].high
                if (candles[j].low < minL) minL = candles[j].low
            }
            sh20[i] = maxH
            sl20[i] = minL
        }

        return CausalSeries15m(
            ema20 = ema20,
            ema50 = ema50,
            ema100 = ema100,
            ema200 = ema200,
            rsi14 = rsi14,
            macdHist = macdHist,
            atr14 = atr14,
            rvol20 = rvol20,
            supertrendBullish = stBull,
            swingHigh20 = sh20,
            swingLow20 = sl20
        )
    }

    private fun precomputeHtfTrends(candles: List<Candle>): List<HtfCausalTrend> {
        if (candles.isEmpty()) return emptyList()
        val k20 = 2.0 / 21.0
        val k50 = 2.0 / 51.0
        var e20 = candles[0].close
        var e50 = candles[0].close
        return candles.mapIndexed { idx, c ->
            if (idx > 0) {
                e20 = c.close * k20 + e20 * (1.0 - k20)
                e50 = c.close * k50 + e50 * (1.0 - k50)
            }
            HtfCausalTrend(
                closeTimeMs = c.closeTime,
                bullishTrend = c.close > e20 && e20 > e50,
                bearishTrend = c.close < e20 && e20 < e50
            )
        }
    }

    private fun aggregateCandles(bars15m: List<Candle>, groupSize: Int): List<Candle> {
        if (bars15m.size < groupSize) return emptyList()
        return bars15m.chunked(groupSize).filter { it.size == groupSize }.map { chunk ->
            Candle(
                openTime = chunk.first().openTime,
                open = chunk.first().open,
                high = chunk.maxOf { it.high },
                low = chunk.minOf { it.low },
                close = chunk.last().close,
                volume = chunk.sumOf { it.volume },
                closeTime = chunk.last().closeTime,
                quoteVolume = chunk.sumOf { it.quoteVolume },
                tradesCount = chunk.sumOf { it.tradesCount },
                takerBuyBaseVolume = chunk.sumOf { it.takerBuyBaseVolume },
                takerBuyQuoteVolume = chunk.sumOf { it.takerBuyQuoteVolume }
            )
        }
    }

    private fun computeAggregateMetrics(
        trades: List<SimulatedBacktestTrade>,
        startingCapitalUsdt: Double
    ): AggregateBacktestMetrics {
        if (trades.isEmpty()) {
            val emptyDir = DirectionalBacktestBreakdown(0, 0, 0, 0.0, 0.0, 0.0, 0.0)
            return AggregateBacktestMetrics(
                totalTrades = 0,
                wins = 0,
                losses = 0,
                ambiguousSameCandle = 0,
                unresolvedAtHorizon = 0,
                winRatePercent = 0.0,
                profitFactor = 0.0,
                grossPnlUsdt = 0.0,
                netPnlUsdt = 0.0,
                totalCostsUsdt = 0.0,
                expectancyR = 0.0,
                expectancyPercent = 0.0,
                maxDrawdownUsdt = 0.0,
                maxDrawdownPercent = 0.0,
                longStats = emptyDir,
                shortStats = emptyDir
            )
        }

        val sorted = trades.sortedBy { it.entryTimeMs }
        val winsList = sorted.filter { it.netPnlUsdt > 0.0 }
        val lossList = sorted.filter { it.netPnlUsdt <= 0.0 }
        val grossProfit = winsList.sumOf { it.netPnlUsdt }
        val grossLoss = abs(lossList.sumOf { it.netPnlUsdt })
        val pf = when {
            grossLoss > 1e-6 -> grossProfit / grossLoss
            grossProfit > 0.0 -> 99.9
            else -> 0.0
        }

        var eq = startingCapitalUsdt.coerceAtLeast(1.0)
        var peak = eq
        var maxDdUsdt = 0.0
        var maxDdPct = 0.0
        for (t in sorted) {
            eq += t.netPnlUsdt
            if (eq > peak) peak = eq
            val dd = (peak - eq).coerceAtLeast(0.0)
            val ddPct = if (peak > 0.0) (dd / peak) * 100.0 else 0.0
            if (dd > maxDdUsdt) maxDdUsdt = dd
            if (ddPct > maxDdPct) maxDdPct = ddPct
        }

        return AggregateBacktestMetrics(
            totalTrades = sorted.size,
            wins = winsList.size,
            losses = lossList.size,
            ambiguousSameCandle = sorted.count { it.outcome == BacktestOutcomeType.AMBIGUOUS_SAME_CANDLE },
            unresolvedAtHorizon = sorted.count { it.outcome == BacktestOutcomeType.UNRESOLVED },
            winRatePercent = (winsList.size.toDouble() / sorted.size) * 100.0,
            profitFactor = pf,
            grossPnlUsdt = sorted.sumOf { it.grossPnlUsdt },
            netPnlUsdt = sorted.sumOf { it.netPnlUsdt },
            totalCostsUsdt = sorted.sumOf { it.feesAndSlippageUsdt + abs(it.fundingCostUsdt) },
            expectancyR = sorted.map { it.netRMultiple }.average(),
            expectancyPercent = sorted.map { it.netReturnOnAccountPercent }.average(),
            maxDrawdownUsdt = maxDdUsdt,
            maxDrawdownPercent = maxDdPct,
            longStats = computeDirectionalBreakdown(sorted.filter { it.direction == FuturesDirection.LONG }),
            shortStats = computeDirectionalBreakdown(sorted.filter { it.direction == FuturesDirection.SHORT })
        )
    }

    private fun computeDirectionalBreakdown(trades: List<SimulatedBacktestTrade>): DirectionalBacktestBreakdown {
        if (trades.isEmpty()) return DirectionalBacktestBreakdown(0, 0, 0, 0.0, 0.0, 0.0, 0.0)
        val wins = trades.filter { it.netPnlUsdt > 0.0 }
        val losses = trades.filter { it.netPnlUsdt <= 0.0 }
        val gp = wins.sumOf { it.netPnlUsdt }
        val gl = abs(losses.sumOf { it.netPnlUsdt })
        val pf = when {
            gl > 1e-6 -> gp / gl
            gp > 0.0 -> 99.9
            else -> 0.0
        }
        return DirectionalBacktestBreakdown(
            trades = trades.size,
            wins = wins.size,
            losses = losses.size,
            winRatePercent = (wins.size.toDouble() / trades.size) * 100.0,
            profitFactor = pf,
            netPnlUsdt = trades.sumOf { it.netPnlUsdt },
            expectancyR = trades.map { it.netRMultiple }.average()
        )
    }

    private fun computeMonthlyBuckets(
        trades: List<SimulatedBacktestTrade>,
        startingCapitalUsdt: Double
    ): List<MonthlyBacktestBucket> {
        if (trades.isEmpty()) return emptyList()
        return trades.groupBy { it.monthKeyPkt }
            .toSortedMap()
            .map { (month, list) ->
                val agg = computeAggregateMetrics(list, startingCapitalUsdt)
                MonthlyBacktestBucket(
                    monthPkt = month,
                    trades = agg.totalTrades,
                    wins = agg.wins,
                    winRatePercent = agg.winRatePercent,
                    netPnlUsdt = agg.netPnlUsdt,
                    profitFactor = agg.profitFactor
                )
            }
    }

    private fun computeWalkForwardWindows(
        trades: List<SimulatedBacktestTrade>,
        startingCapitalUsdt: Double
    ): List<WalkForwardWindowResult> {
        if (trades.size < 8) return emptyList()
        val sorted = trades.sortedBy { it.entryTimeMs }
        val minT = sorted.first().entryTimeMs
        val maxT = sorted.last().entryTimeMs
        val span = (maxT - minT).coerceAtLeast(1L)
        val windowSpan = span / 4L
        val results = mutableListOf<WalkForwardWindowResult>()

        for (w in 0 until 4) {
            val wStart = minT + (windowSpan * w)
            val wEnd = if (w == 3) maxT else (minT + windowSpan * (w + 1))
            val windowTrades = sorted.filter { it.entryTimeMs in wStart..wEnd }
            if (windowTrades.isEmpty()) continue
            val split = ((windowTrades.size * 0.70).toInt()).coerceIn(1, windowTrades.size)
            val isTrades = windowTrades.take(split)
            val oosTrades = windowTrades.drop(split)
            val isAgg = computeAggregateMetrics(isTrades, startingCapitalUsdt)
            val oosAgg = computeAggregateMetrics(oosTrades, startingCapitalUsdt)
            results.add(
                WalkForwardWindowResult(
                    windowIndex = w + 1,
                    periodLabelPkt = "${TimeZoneUtils.formatKarachiDateOnly(wStart)} → ${TimeZoneUtils.formatKarachiDateOnly(wEnd)}",
                    inSampleTrades = isAgg.totalTrades,
                    inSampleWinRatePercent = isAgg.winRatePercent,
                    inSampleNetPnlUsdt = isAgg.netPnlUsdt,
                    outOfSampleTrades = oosAgg.totalTrades,
                    outOfSampleWinRatePercent = oosAgg.winRatePercent,
                    outOfSampleNetPnlUsdt = oosAgg.netPnlUsdt,
                    outOfSampleProfitFactor = oosAgg.profitFactor
                )
            )
        }
        return results
    }

    private fun estimateCoverageDays(candles: List<Candle>): Double {
        if (candles.size < 2) return 0.0
        val ms = (candles.last().closeTime - candles.first().openTime).coerceAtLeast(0L)
        return ms / 86_400_000.0
    }
}
