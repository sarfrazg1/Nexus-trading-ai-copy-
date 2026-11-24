package com.example.data.scanner

import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.risk.BudgetConfig
import com.example.data.risk.RiskCurrency
import com.example.data.smc.FvgStatus
import com.example.data.smc.OrderBlockStatus
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcCalculationEngine
import com.example.data.timemachine.BacktestConfig
import com.example.data.timemachine.TimeMachineEngine
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * PART 4 — NEXUS 1:5 HIGH REWARD OPPORTUNITY DETECTOR
 *
 * Strictly additive quantitative detector checking BOTH LONG and SHORT structural setups on Binance USDT-M Futures:
 * - Minimum Net Risk-to-Reward Ratio after estimated fees, slippage & reliable adverse funding: 1:4.0
 * - Highest priority tier: 1:5.0 or better ("NEXUS 1:5 HIGH REWARD OPPORTUNITY")
 * - Below 1:4.0 or unconfirmed entry trigger: Rejected / WAIT (never forces signals, never inflates TP or tightens SL artificially)
 * - Uses Asia/Karachi (PKT) timestamps exclusively
 * - Keeps Model Confidence strictly separate from Time Machine empirical target-hit probability (only shown when >=5 real setups exist)
 * - Alert & analysis only — NEVER executes automated trades
 */
object HighRewardOpportunityDetector {

    const val MIN_QUALIFYING_NET_RR = 4.0
    const val HIGHEST_PRIORITY_NET_RR = 5.0
    const val MIN_CONFIRMED_MODEL_CONFIDENCE = 60
    const val MIN_REALISTIC_SL_PERCENT = 0.25
    const val MAX_REALISTIC_SL_PERCENT = 6.5
    const val MAX_LIQUIDATION_SL_RATIO = 0.55
    const val DEFAULT_SETUP_EXPIRY_BARS_15M = 12 // 3 hours on 15M chart
    const val MIN_HISTORICAL_OCCURRENCES_FOR_PROB = 5
    const val MAX_FUNDING_DATA_AGE_MS = 10 * 60 * 1000L // 10 minutes freshness limit

    fun isFundingDataReliable(
        fundingRatePercent: Double?,
        fundingDataTimestampMs: Long? = null,
        isFundingDataStale: Boolean = false,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (isFundingDataStale) return false
        if (fundingRatePercent == null || !fundingRatePercent.isFinite() || abs(fundingRatePercent) > 5.0) {
            return false
        }
        if (fundingDataTimestampMs != null) {
            if (fundingDataTimestampMs <= 0L) return false
            val ageMs = referenceTimeMs - fundingDataTimestampMs
            if (ageMs > MAX_FUNDING_DATA_AGE_MS || ageMs < -MAX_FUNDING_DATA_AGE_MS) return false
        }
        return true
    }

    suspend fun scanPair(
        symbol: String,
        baseAsset: String,
        currentPrice: Double,
        candles4h: List<Candle>,
        candles1h: List<Candle>,
        candles15m: List<Candle>,
        candles5m: List<Candle> = emptyList(),
        fundingRatePercent: Double? = null,
        budgetConfig: BudgetConfig = BudgetConfig(),
        configuredLeverage: Int? = null,
        referenceTimeMs: Long = System.currentTimeMillis(),
        fundingDataTimestampMs: Long? = null,
        isFundingDataStale: Boolean = false
    ): HighRewardScanResult {
        if (!currentPrice.isFinite() || currentPrice <= 0.0) {
            return HighRewardScanResult(
                qualifiedOpportunities = emptyList(),
                bestDiagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = null,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                    reason = "WAIT: Invalid or unavailable live price for $symbol."
                )
            )
        }

        val completed15m = TimeZoneUtils.filterCompletedCandles(candles15m, referenceTimeMs)
        val completed1h = TimeZoneUtils.filterCompletedCandles(candles1h, referenceTimeMs)
        val completed4h = TimeZoneUtils.filterCompletedCandles(candles4h, referenceTimeMs)
        val completed5m = TimeZoneUtils.filterCompletedCandles(candles5m, referenceTimeMs)

        if (completed15m.size < 20 || completed1h.size < 20) {
            return HighRewardScanResult(
                qualifiedOpportunities = emptyList(),
                bestDiagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = null,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                    reason = "WAIT: Insufficient completed 15M/1H OHLCV candles on $symbol."
                )
            )
        }

        val latestClose15m = completed15m.last().close
        if (latestClose15m <= 0.0 || abs(currentPrice - latestClose15m) / latestClose15m > 0.15) {
            return HighRewardScanResult(
                qualifiedOpportunities = emptyList(),
                bestDiagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = null,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                    reason = "WAIT: Ticker price diverges >15% from completed 15M candle close on $symbol."
                )
            )
        }

        val tech15m = TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.M15, completed15m)
        val smc15m = SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.M15, completed15m)
        val tech1h = TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.H1, completed1h)
        val smc1h = SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.H1, completed1h)
        val tech4h = if (completed4h.size >= 15) {
            TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.H4, completed4h)
        } else tech1h
        val smc4h = if (completed4h.size >= 15) {
            SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.H4, completed4h)
        } else smc1h
        val tech5m = if (completed5m.size >= 15) {
            TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.M5, completed5m)
        } else tech15m
        val smc5m = if (completed5m.size >= 15) {
            SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.M5, completed5m)
        } else smc15m

        val longEval = evaluateDirection(
            direction = FuturesDirection.LONG,
            symbol = symbol,
            baseAsset = baseAsset,
            currentPrice = currentPrice,
            completed15m = completed15m,
            tech4h = tech4h,
            smc4h = smc4h,
            tech1h = tech1h,
            smc1h = smc1h,
            tech15m = tech15m,
            smc15m = smc15m,
            tech5m = tech5m,
            smc5m = smc5m,
            fundingRatePercent = fundingRatePercent,
            fundingDataTimestampMs = fundingDataTimestampMs,
            isFundingDataStale = isFundingDataStale,
            budgetConfig = budgetConfig,
            configuredLeverage = configuredLeverage,
            referenceTimeMs = referenceTimeMs
        )

        val shortEval = evaluateDirection(
            direction = FuturesDirection.SHORT,
            symbol = symbol,
            baseAsset = baseAsset,
            currentPrice = currentPrice,
            completed15m = completed15m,
            tech4h = tech4h,
            smc4h = smc4h,
            tech1h = tech1h,
            smc1h = smc1h,
            tech15m = tech15m,
            smc15m = smc15m,
            tech5m = tech5m,
            smc5m = smc5m,
            fundingRatePercent = fundingRatePercent,
            fundingDataTimestampMs = fundingDataTimestampMs,
            isFundingDataStale = isFundingDataStale,
            budgetConfig = budgetConfig,
            configuredLeverage = configuredLeverage,
            referenceTimeMs = referenceTimeMs
        )

        val qualified = listOfNotNull(longEval.opportunity, shortEval.opportunity)
            .sortedWith(
                compareByDescending<HighRewardOpportunity> { it.tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS }
                    .thenByDescending { it.netRiskRewardRatio }
                    .thenByDescending { it.modelConfidencePercent }
            )

        val bestDiag = listOf(longEval.diagnostic, shortEval.diagnostic)
            .maxWith(
                compareBy<HighRewardCandidateDiagnostic>(
                    { it.netRiskRewardRatio },
                    { it.tier == OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE },
                    { it.grossRiskRewardRatio }
                )
            )

        return HighRewardScanResult(
            qualifiedOpportunities = qualified,
            bestDiagnostic = bestDiag
        )
    }

    /**
     * Checks live market price and clock against an active opportunity; transitions to INVALIDATED or EXPIRED safely.
     */
    fun evaluateLifecycle(
        opportunity: HighRewardOpportunity,
        livePrice: Double?,
        nowMs: Long = System.currentTimeMillis()
    ): HighRewardOpportunity {
        if (opportunity.status != OpportunityStatus.ACTIVE) return opportunity

        if (livePrice != null && livePrice.isFinite() && livePrice > 0.0) {
            val slHit = if (opportunity.direction == FuturesDirection.LONG) {
                livePrice <= opportunity.stopLoss
            } else {
                livePrice >= opportunity.stopLoss
            }
            if (slHit) {
                return opportunity.copy(
                    status = OpportunityStatus.INVALIDATED,
                    isQualified = false,
                    currentPrice = livePrice,
                    statusDetail = "INVALIDATED at ${TimeZoneUtils.formatKarachiTimestamp(nowMs)}: Live price (${HighRewardOpportunity.fmtPrice(livePrice)}) breached Stop Loss (${opportunity.stopLossFormatted})."
                )
            }
        }

        if (nowMs > opportunity.expiresAtTimestampMs) {
            return opportunity.copy(
                status = OpportunityStatus.EXPIRED,
                isQualified = false,
                currentPrice = livePrice ?: opportunity.currentPrice,
                statusDetail = "EXPIRED at ${TimeZoneUtils.formatKarachiTimestamp(nowMs)}: Entry window elapsed (${opportunity.expiresAtPkt})."
            )
        }

        return if (livePrice != null && livePrice.isFinite() && livePrice > 0.0) {
            opportunity.copy(currentPrice = livePrice)
        } else opportunity
    }

    private data class DirectionEvaluation(
        val opportunity: HighRewardOpportunity?,
        val diagnostic: HighRewardCandidateDiagnostic
    )

    private fun evaluateDirection(
        direction: FuturesDirection,
        symbol: String,
        baseAsset: String,
        currentPrice: Double,
        completed15m: List<Candle>,
        tech4h: com.example.data.indicators.OverallAnalysisResult,
        smc4h: com.example.data.smc.SmcAnalysisResult,
        tech1h: com.example.data.indicators.OverallAnalysisResult,
        smc1h: com.example.data.smc.SmcAnalysisResult,
        tech15m: com.example.data.indicators.OverallAnalysisResult,
        smc15m: com.example.data.smc.SmcAnalysisResult,
        tech5m: com.example.data.indicators.OverallAnalysisResult,
        smc5m: com.example.data.smc.SmcAnalysisResult,
        fundingRatePercent: Double?,
        fundingDataTimestampMs: Long?,
        isFundingDataStale: Boolean,
        budgetConfig: BudgetConfig,
        configuredLeverage: Int?,
        referenceTimeMs: Long
    ): DirectionEvaluation {
        val isLong = direction == FuturesDirection.LONG
        val atr15m = (tech15m.volatility.atr?.value ?: (currentPrice * 0.01)).coerceAtLeast(currentPrice * 0.002)
        val atr1h = (tech1h.volatility.atr?.value ?: (atr15m * 2.0)).coerceAtLeast(atr15m)
        val atr4h = (tech4h.volatility.atr?.value ?: (atr1h * 2.2)).coerceAtLeast(atr1h)
        val atrPct15m = (atr15m / currentPrice) * 100.0

        val ema50_1h = tech1h.trend.emaSet.ema50 ?: currentPrice
        val ema200_1h = tech1h.trend.emaSet.ema200 ?: currentPrice
        val ema50_4h = tech4h.trend.emaSet.ema50 ?: currentPrice
        val rsi15m = tech15m.momentum.rsi14 ?: 50.0
        val rvol15m = tech15m.volume.relativeVolume ?: 1.0

        // 1. Structural Confluence Scoring for requested direction
        var confidence = 0
        val supportingSignals = mutableListOf<String>()

        val htfAligned = if (isLong) {
            currentPrice >= ema50_1h && (ema50_1h >= ema200_1h || currentPrice >= ema50_4h)
        } else {
            currentPrice <= ema50_1h && (ema50_1h <= ema200_1h || currentPrice <= ema50_4h)
        }
        if (htfAligned) {
            confidence += 25
            supportingSignals.add(
                if (isLong) "HTF Bullish Bias: Price above 1H EMA50 (${HighRewardOpportunity.fmtPrice(ema50_1h)})"
                else "HTF Bearish Bias: Price below 1H EMA50 (${HighRewardOpportunity.fmtPrice(ema50_1h)})"
            )
        }

        val hasStructureBreak = if (isLong) {
            smc15m.marketStructure.isBullishBOSPresent || smc15m.marketStructure.isBullishCHoCHPresent ||
                smc5m.marketStructure.isBullishCHoCHPresent
        } else {
            smc15m.marketStructure.isBearishBOSPresent || smc15m.marketStructure.isBearishCHoCHPresent ||
                smc5m.marketStructure.isBearishCHoCHPresent
        }
        if (hasStructureBreak) {
            confidence += 25
            supportingSignals.add(
                if (isLong) "SMC Structure: Confirmed Bullish BOS/CHoCH on 15M/5M"
                else "SMC Structure: Confirmed Bearish BOS/CHoCH on 15M/5M"
            )
        }

        val hasDirectionalFvg = if (isLong) {
            smc15m.fairValueGaps.any { it.type.isBullish && it.status != FvgStatus.FULLY_FILLED } ||
                smc1h.fairValueGaps.any { it.type.isBullish && it.status != FvgStatus.FULLY_FILLED }
        } else {
            smc15m.fairValueGaps.any { !it.type.isBullish && it.status != FvgStatus.FULLY_FILLED } ||
                smc1h.fairValueGaps.any { !it.type.isBullish && it.status != FvgStatus.FULLY_FILLED }
        }
        if (hasDirectionalFvg) {
            confidence += 15
            supportingSignals.add("Unfilled ${if (isLong) "Bullish" else "Bearish"} Fair Value Gap (FVG) alignment")
        }

        val hasOrderBlock = if (isLong) {
            smc15m.orderBlocks.any { it.type.isBullish && it.status != OrderBlockStatus.INVALIDATED } ||
                smc1h.orderBlocks.any { it.type.isBullish && it.status != OrderBlockStatus.INVALIDATED }
        } else {
            smc15m.orderBlocks.any { !it.type.isBullish && it.status != OrderBlockStatus.INVALIDATED } ||
                smc1h.orderBlocks.any { !it.type.isBullish && it.status != OrderBlockStatus.INVALIDATED }
        }
        if (hasOrderBlock) {
            confidence += 10
            supportingSignals.add("Fresh ${if (isLong) "Demand" else "Supply"} Order Block confluence")
        }

        val rsiFavorable = if (isLong) rsi15m in 32.0..68.0 else rsi15m in 32.0..68.0
        if (rsiFavorable) {
            confidence += 12
            supportingSignals.add("15M RSI (${String.format(Locale.US, "%.1f", rsi15m)}) has clean momentum headroom")
        }

        if (rvol15m >= 1.1) {
            confidence += 9
            supportingSignals.add("Volume Confirmation: 15M RVOL ${String.format(Locale.US, "%.2fx", rvol15m)}")
        }

        val pattern = CryptoPatternDetector.detect(
            candles15m = completed15m,
            tech15m = tech15m,
            smc15m = smc15m,
            tech1h = tech1h,
            smc1h = smc1h,
            referenceTimeMs = referenceTimeMs
        )
        if (pattern?.direction == direction) {
            confidence += 5
            supportingSignals.add("Pattern Trigger: ${pattern.pattern.label}")
        }
        confidence = confidence.coerceAtMost(96)

        if (confidence < MIN_CONFIRMED_MODEL_CONFIDENCE) {
            return DirectionEvaluation(
                opportunity = null,
                diagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = direction,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                    reason = "WAIT (${direction.name}): Model confluence score $confidence% < $MIN_CONFIRMED_MODEL_CONFIDENCE% minimum."
                )
            )
        }

        // 2. Entry Trigger Confirmation (Strictly Completed Candles via EntryTimerEngine)
        val latestCompleted = completed15m.last()
        val prevCompleted = completed15m[completed15m.lastIndex - 1]
        val structuralTriggerLevel = if (pattern != null && pattern.direction == direction) {
            pattern.breakoutLevel
        } else {
            if (isLong) {
                completed15m.takeLast(8).dropLast(1).maxOfOrNull { it.close } ?: prevCompleted.close
            } else {
                completed15m.takeLast(8).dropLast(1).minOfOrNull { it.close } ?: prevCompleted.close
            }
        }

        // 3. Structural Stop Loss (genuine S/R, swing, or OB — NEVER tightened artificially below MIN_REALISTIC_SL_PERCENT)
        val structuralInvalidation = computeGenuineStopLoss(
            direction = direction,
            entryPrice = currentPrice,
            atr15m = atr15m,
            completed15m = completed15m,
            smc15m = smc15m,
            smc1h = smc1h
        )

        val timerResult = EntryTimerEngine.evaluateBarByBar(
            symbol = symbol,
            direction = direction,
            triggerLevel = structuralTriggerLevel,
            invalidationLevel = structuralInvalidation,
            candles = completed15m,
            setupStartIndex = (completed15m.lastIndex - 1).coerceAtLeast(0),
            config = EntryTimerConfig(
                requireCandleClose = true,
                requireVolumeConfirmation = false,
                minRvolForConfirm = 1.25,
                maxWaitBars = DEFAULT_SETUP_EXPIRY_BARS_15M
            ),
            referenceTimeMs = referenceTimeMs
        )

        val latestStillConfirms = if (isLong) {
            latestCompleted.close >= structuralTriggerLevel && latestCompleted.close >= latestCompleted.open
        } else {
            latestCompleted.close <= structuralTriggerLevel && latestCompleted.close <= latestCompleted.open
        }

        if (timerResult.status != EntryTimerStatus.TRIGGERED || timerResult.triggerTimestampMs == null || !latestStillConfirms) {
            return DirectionEvaluation(
                opportunity = null,
                diagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = direction,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_TRIGGER_NOT_CONFIRMED,
                    reason = "WAIT (${direction.name}): Entry trigger unconfirmed (${timerResult.status.label}) — completed candle close confirmation required."
                )
            )
        }

        // 4. Genuine HTF Structural Take Profit (NEVER artificially widened beyond real HTF pools/levels or ATR reachability)
        val structuralTp = computeGenuineTakeProfit(
            direction = direction,
            entryPrice = currentPrice,
            atr15m = atr15m,
            atr1h = atr1h,
            atr4h = atr4h,
            smc1h = smc1h,
            smc4h = smc4h,
            completed15m = completed15m
        ) ?: return DirectionEvaluation(
            opportunity = null,
            diagnostic = HighRewardCandidateDiagnostic(
                symbol = symbol,
                bestDirection = direction,
                grossRiskRewardRatio = 0.0,
                netRiskRewardRatio = 0.0,
                tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                reason = "WAIT (${direction.name}): No realistic HTF liquidity or S/R target within ATR reachability bounds."
            )
        )

        val slDistance = abs(currentPrice - structuralInvalidation)
        val tpDistance = abs(structuralTp - currentPrice)
        val slDistancePercent = (slDistance / currentPrice) * 100.0
        val tpDistancePercent = (tpDistance / currentPrice) * 100.0

        if (slDistancePercent < (MIN_REALISTIC_SL_PERCENT - 1e-4) || slDistancePercent > (MAX_REALISTIC_SL_PERCENT + 1e-4)) {
            return DirectionEvaluation(
                opportunity = null,
                diagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = direction,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                    reason = "WAIT (${direction.name}): Structural SL distance (${fmt2(slDistancePercent)}%) outside safe ${MIN_REALISTIC_SL_PERCENT}%–${MAX_REALISTIC_SL_PERCENT}% bounds."
                )
            )
        }

        // 5. Leverage, Fees, Slippage, Funding & Net Risk-to-Reward Calculation
        val volatilitySafeLev = when {
            atrPct15m > 4.0 -> 2
            atrPct15m > 2.5 -> 3
            else -> 5
        }
        val leverageAssumed = if (configuredLeverage != null) {
            configuredLeverage.coerceIn(1, BinanceFuturesScannerEngine.MAX_ALLOWED_LEVERAGE)
        } else {
            min(budgetConfig.leverageLimit.coerceIn(1, BinanceFuturesScannerEngine.MAX_ALLOWED_LEVERAGE), volatilitySafeLev)
        }

        val maxLiqMoveFraction = (1.0 / leverageAssumed.toDouble()) - BinanceFuturesScannerEngine.MAINTENANCE_MARGIN_RATE - 0.001
        val liqDistancePercent = (maxLiqMoveFraction * 100.0).coerceAtLeast(1.0)
        if (slDistancePercent > liqDistancePercent * MAX_LIQUIDATION_SL_RATIO) {
            return DirectionEvaluation(
                opportunity = null,
                diagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = direction,
                    grossRiskRewardRatio = 0.0,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_INSUFFICIENT_STRUCTURE,
                    reason = "WAIT (${direction.name}): SL (${fmt2(slDistancePercent)}%) too close to est. liquidation (${fmt2(liqDistancePercent)}%) at ${leverageAssumed}x."
                )
            )
        }

        val feePerSidePct = budgetConfig.feeRatePercentPerSide.coerceAtLeast(0.05)
        val slipPerSidePct = budgetConfig.slippagePercentPerSide.coerceAtLeast(0.03)
        val feeRate = feePerSidePct / 100.0
        val slipRate = slipPerSidePct / 100.0

        val unitGrossRisk = slDistance
        val unitGrossReward = tpDistance
        val grossRR = if (unitGrossRisk > 0.0) unitGrossReward / unitGrossRisk else 0.0

        val fundingReliable = isFundingDataReliable(
            fundingRatePercent = fundingRatePercent,
            fundingDataTimestampMs = fundingDataTimestampMs,
            isFundingDataStale = isFundingDataStale,
            referenceTimeMs = referenceTimeMs
        )
        if (!fundingReliable) {
            return DirectionEvaluation(
                opportunity = null,
                diagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = direction,
                    grossRiskRewardRatio = grossRR,
                    netRiskRewardRatio = 0.0,
                    tier = OpportunityPriorityTier.WAIT_FUNDING_UNAVAILABLE,
                    isFundingAvailable = false,
                    reason = "WAIT / FUNDING DATA UNAVAILABLE (${direction.name}): Live Binance Futures funding rate for $symbol is missing, stale, or unreliable. Net R:R cannot be calculated without assuming 0% funding (Gross 1:${fmt2(grossRR)})."
                )
            )
        }

        val validFundingPct = fundingRatePercent!!
        val dec = validFundingPct / 100.0
        val signedFunding = if (isLong) dec else -dec
        val adverseFundingFraction = max(0.0, signedFunding) * BinanceFuturesScannerEngine.ESTIMATED_FUNDING_INTERVALS

        val unitNetLossAtSl = unitGrossRisk +
            (currentPrice * (feeRate + slipRate)) +
            (structuralInvalidation * (feeRate + slipRate)) +
            (currentPrice * adverseFundingFraction)
        val unitNetProfitAtTp = unitGrossReward -
            (currentPrice * (feeRate + slipRate)) -
            (structuralTp * (feeRate + slipRate)) -
            (currentPrice * adverseFundingFraction)

        val netRR = if (unitNetLossAtSl > 0.0) unitNetProfitAtTp / unitNetLossAtSl else 0.0

        if (netRR < MIN_QUALIFYING_NET_RR) {
            return DirectionEvaluation(
                opportunity = null,
                diagnostic = HighRewardCandidateDiagnostic(
                    symbol = symbol,
                    bestDirection = direction,
                    grossRiskRewardRatio = grossRR,
                    netRiskRewardRatio = netRR,
                    tier = OpportunityPriorityTier.WAIT_BELOW_1_TO_4,
                    reason = "WAIT (${direction.name}): Net R:R 1:${fmt2(netRR)} (Gross 1:${fmt2(grossRR)}) is below strict 1:4.00 minimum after fees, slippage & funding (${String.format(Locale.US, "%+.4f%%", validFundingPct)})."
                )
            )
        }

        // 6. Position sizing & Budget Assumptions
        val accountBalance = budgetConfig.accountBalanceUsdt.takeIf { it.isFinite() && it > 0.0 } ?: 1000.0
        val configuredRiskUsdt = if (budgetConfig.riskCurrency == RiskCurrency.PKR) {
            budgetConfig.maxRiskPerTradeAmount / budgetConfig.pkrPerUsdtRate.coerceAtLeast(1.0)
        } else {
            budgetConfig.maxRiskPerTradeAmount
        }.takeIf { it.isFinite() && it > 0.0 } ?: 20.0
        val pctRiskCapUsdt = accountBalance * (budgetConfig.maxRiskPercent.coerceIn(0.25, 10.0) / 100.0)
        val targetRiskUsdt = min(configuredRiskUsdt, pctRiskCapUsdt).coerceAtLeast(1.0)

        val maxUnitsByMargin = (accountBalance * 0.75 * leverageAssumed.toDouble()) / currentPrice
        val rawUnits = targetRiskUsdt / unitNetLossAtSl
        val positionSizeUnits = min(rawUnits, maxUnitsByMargin)
        val notionalUsdt = positionSizeUnits * currentPrice
        val marginUsedUsdt = (notionalUsdt / leverageAssumed.toDouble()).coerceAtLeast(0.01)

        val estNetLossUsdt = positionSizeUnits * unitNetLossAtSl
        val estNetProfitUsdt = positionSizeUnits * unitNetProfitAtTp
        val estGrossProfitUsdt = positionSizeUnits * unitGrossReward
        val estFeesUsdt = positionSizeUnits * (currentPrice + structuralTp) * feeRate
        val estSlippageUsdt = positionSizeUnits * (currentPrice + structuralTp) * slipRate
        val estFundingUsdt = notionalUsdt * adverseFundingFraction
        val estNetRoiPct = (estNetProfitUsdt / marginUsedUsdt) * 100.0
        val estRiskMarginPct = (estNetLossUsdt / marginUsedUsdt) * 100.0

        // 7. Empirical Historical Probability Check (Strictly from real Time Machine backtest; NEVER fabricated)
        val btResult = TimeMachineEngine.runBacktest(
            symbol = symbol,
            timeframe = CandleTimeframe.M15,
            direction = direction,
            slDistancePercent = slDistancePercent,
            tpDistancePercent = tpDistancePercent,
            candles = completed15m,
            config = BacktestConfig(
                feePercentPerSide = feePerSidePct,
                slippagePercentPerSide = slipPerSidePct,
                horizonBars = 48
            ),
            referenceTimeMs = referenceTimeMs
        )
        val hasRealHistProb = btResult.sampleSize >= MIN_HISTORICAL_OCCURRENCES_FOR_PROB
        val historicalProb: Double? = if (hasRealHistProb) btResult.empiricalWinRateConservativePercent else null
        val historicalNote = if (hasRealHistProb) {
            "Empirical Time Machine (${btResult.sampleSize} setups, ${btResult.testPeriodStartPkt}–${btResult.testPeriodEndPkt}): ${fmt1(btResult.empiricalWinRateConservativePercent)}% conservative TP-first rate (excl. ambiguous: ${fmt1(btResult.empiricalWinRateExclAmbiguousPercent)}%). Separate from Model Confidence."
        } else {
            "Insufficient real historical sample (${btResult.sampleSize} matching setup(s) found; need >=$MIN_HISTORICAL_OCCURRENCES_FOR_PROB) — historical target-hit probability withheld (never fabricated)."
        }

        val tier = if (netRR >= HIGHEST_PRIORITY_NET_RR) {
            OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS
        } else {
            OpportunityPriorityTier.QUALIFIED_1_TO_4
        }

        val triggerTsMs = timerResult.triggerTimestampMs
        val triggerPkt = TimeZoneUtils.formatKarachiTimestamp(triggerTsMs)
        val expiresAtMs = referenceTimeMs + (DEFAULT_SETUP_EXPIRY_BARS_15M * 15 * 60_000L)
        val expiresPkt = TimeZoneUtils.formatKarachiTimestamp(expiresAtMs)
        val signatureKey = "hr_${symbol}_${direction.name}_${triggerTsMs}_${tier.name}"

        val fundingDisplay = String.format(Locale.US, "%+.4f%%", validFundingPct)
        val qualificationReasons = buildList {
            add("Gross R:R 1:${fmt2(grossRR)} -> Net R:R 1:${fmt2(netRR)} exceeds strict ${if (netRR >= HIGHEST_PRIORITY_NET_RR) "1:5.00 top-priority" else "1:4.00 minimum"} threshold after fees ($${fmt2(estFeesUsdt)}), slippage ($${fmt2(estSlippageUsdt)}) & funding ($fundingDisplay -> -$${fmt2(estFundingUsdt)} reserve).")
            add("Entry Trigger Confirmed at $triggerPkt on completed 15M candle close (${timerResult.triggerPriceFormatted}).")
            add("Genuine structural Stop Loss at ${HighRewardOpportunity.fmtPrice(structuralInvalidation)} (-${fmt2(slDistancePercent)}%) vs HTF structural Target at ${HighRewardOpportunity.fmtPrice(structuralTp)} (+${fmt2(tpDistancePercent)}%).")
            add("Sizing Assumptions: $${fmt2(marginUsedUsdt)} margin @ ${leverageAssumed}x isolated leverage = $${fmt2(notionalUsdt)} notional; est. net ROI +${fmt1(estNetRoiPct)}% vs -${fmt1(estRiskMarginPct)}% margin risk.")
        }

        val risksAndInvalidations = listOf(
            "Invalidation: Close or live wick beyond Stop Loss ${HighRewardOpportunity.fmtPrice(structuralInvalidation)} cancels setup immediately (-$${fmt2(estNetLossUsdt)} est. net loss).",
            "Time-Stop Expiry: Expires automatically at $expiresPkt if structure fails to expand.",
            "Slippage & Volatility Risk: Fast wicks or thin liquidity can widen fill cost beyond assumed ${fmt2(slipPerSidePct)}%/side slippage.",
            "No Guaranteed Outcomes: High R:R trades typically win less frequently than 1:1 scalps; empirical outcomes depend on market regime."
        )

        val urduPair = buildBilingualExplanations(
            symbol = symbol,
            direction = direction,
            tier = tier,
            entryPrice = currentPrice,
            stopLoss = structuralInvalidation,
            takeProfit = structuralTp,
            slPct = slDistancePercent,
            tpPct = tpDistancePercent,
            grossRR = grossRR,
            netRR = netRR,
            feeRoundTripPct = feePerSidePct * 2.0,
            slipRoundTripPct = slipPerSidePct * 2.0,
            fundingRateDisplay = fundingDisplay,
            estFundingUsdt = estFundingUsdt,
            marginUsdt = marginUsedUsdt,
            leverage = leverageAssumed,
            estNetLossUsdt = estNetLossUsdt,
            estNetProfitUsdt = estNetProfitUsdt,
            estNetRoiPct = estNetRoiPct,
            triggerPkt = triggerPkt,
            expiresPkt = expiresPkt,
            supportingSignals = supportingSignals
        )

        val opp = HighRewardOpportunity(
            signatureKey = signatureKey,
            symbol = symbol,
            baseAsset = baseAsset,
            direction = direction,
            tier = tier,
            status = OpportunityStatus.ACTIVE,
            isQualified = true,
            entryTriggerTimestampMs = triggerTsMs,
            entryTriggerTimestampPkt = triggerPkt,
            detectedAtTimestampMs = referenceTimeMs,
            detectedAtPkt = TimeZoneUtils.formatKarachiTimestamp(referenceTimeMs),
            expiresAtTimestampMs = expiresAtMs,
            expiresAtPkt = expiresPkt,
            currentPrice = currentPrice,
            entryPrice = currentPrice,
            stopLoss = structuralInvalidation,
            takeProfit = structuralTp,
            slDistancePercent = slDistancePercent,
            tpDistancePercent = tpDistancePercent,
            grossRiskRewardRatio = grossRR,
            netRiskRewardRatio = netRR,
            normalizedRiskUsd = 1.0,
            normalizedNetRewardUsd = netRR,
            budgetMarginUsdt = marginUsedUsdt,
            leverageAssumed = leverageAssumed,
            notionalUsdt = notionalUsdt,
            positionSizeUnits = positionSizeUnits,
            estimatedGrossProfitUsdt = estGrossProfitUsdt,
            estimatedFeesUsdt = estFeesUsdt,
            estimatedSlippageUsdt = estSlippageUsdt,
            estimatedFundingReserveUsdt = estFundingUsdt,
            estimatedNetProfitUsdt = estNetProfitUsdt,
            estimatedNetLossUsdt = estNetLossUsdt,
            estimatedNetRoiPercent = estNetRoiPct,
            estimatedRiskMarginPercent = estRiskMarginPct,
            feeRatePercentPerSide = feePerSidePct,
            slippagePercentPerSide = slipPerSidePct,
            fundingRatePercent = validFundingPct,
            fundingRateTimestampMs = fundingDataTimestampMs,
            modelConfidencePercent = confidence,
            historicalTargetHitProbabilityPercent = historicalProb,
            historicalSampleSize = btResult.sampleSize,
            historicalProbabilityNote = historicalNote,
            qualificationReasons = qualificationReasons,
            supportingIndicatorsAndSmc = supportingSignals,
            risksAndInvalidations = risksAndInvalidations,
            romanUrduExplanation = urduPair.first,
            urduScriptExplanation = urduPair.second,
            statusDetail = "${tier.label} — Triggered at $triggerPkt (Alert Only — Never auto-executes)."
        )

        return DirectionEvaluation(
            opportunity = opp,
            diagnostic = HighRewardCandidateDiagnostic(
                symbol = symbol,
                bestDirection = direction,
                grossRiskRewardRatio = grossRR,
                netRiskRewardRatio = netRR,
                tier = tier,
                reason = opp.alertBannerTitle
            )
        )
    }

    private fun computeGenuineStopLoss(
        direction: FuturesDirection,
        entryPrice: Double,
        atr15m: Double,
        completed15m: List<Candle>,
        smc15m: com.example.data.smc.SmcAnalysisResult,
        smc1h: com.example.data.smc.SmcAnalysisResult
    ): Double {
        val recentBars = completed15m.takeLast(14)
        val buffer = atr15m * 0.25
        return if (direction == FuturesDirection.LONG) {
            val recentSwingLow = recentBars.minOfOrNull { it.low }?.minus(buffer)
            val demandZoneLow = (smc15m.priceZones + smc1h.priceZones)
                .filter { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }
                .map { it.lowerPrice - buffer }
                .filter { it < entryPrice && (entryPrice - it) / entryPrice in 0.0025..0.06 }
                .maxOrNull()
            val obBottom = (smc15m.orderBlocks + smc1h.orderBlocks)
                .filter { it.type.isBullish && it.status != OrderBlockStatus.INVALIDATED }
                .map { it.bottomPrice - buffer }
                .filter { it < entryPrice && (entryPrice - it) / entryPrice in 0.0025..0.06 }
                .maxOrNull()

            val structuralFloor = listOfNotNull(demandZoneLow, obBottom, recentSwingLow)
                .filter { it < entryPrice && (entryPrice - it) / entryPrice <= MAX_REALISTIC_SL_PERCENT / 100.0 }
                .maxOrNull()
                ?: (entryPrice - max(atr15m * 1.1, entryPrice * (MIN_REALISTIC_SL_PERCENT / 100.0)))

            // Enforce minimum noise threshold so SL is never unrealistically squeezed
            val minSlCap = entryPrice * (1.0 - (MIN_REALISTIC_SL_PERCENT / 100.0))
            min(structuralFloor, minSlCap)
        } else {
            val recentSwingHigh = recentBars.maxOfOrNull { it.high }?.plus(buffer)
            val supplyZoneHigh = (smc15m.priceZones + smc1h.priceZones)
                .filter { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }
                .map { it.upperPrice + buffer }
                .filter { it > entryPrice && (it - entryPrice) / entryPrice in 0.0025..0.06 }
                .minOrNull()
            val obTop = (smc15m.orderBlocks + smc1h.orderBlocks)
                .filter { !it.type.isBullish && it.status != OrderBlockStatus.INVALIDATED }
                .map { it.topPrice + buffer }
                .filter { it > entryPrice && (it - entryPrice) / entryPrice in 0.0025..0.06 }
                .minOrNull()

            val structuralCeiling = listOfNotNull(supplyZoneHigh, obTop, recentSwingHigh)
                .filter { it > entryPrice && (it - entryPrice) / entryPrice <= MAX_REALISTIC_SL_PERCENT / 100.0 }
                .minOrNull()
                ?: (entryPrice + max(atr15m * 1.1, entryPrice * (MIN_REALISTIC_SL_PERCENT / 100.0)))

            val minSlCap = entryPrice * (1.0 + (MIN_REALISTIC_SL_PERCENT / 100.0))
            max(structuralCeiling, minSlCap)
        }
    }

    private fun computeGenuineTakeProfit(
        direction: FuturesDirection,
        entryPrice: Double,
        atr15m: Double,
        atr1h: Double,
        atr4h: Double,
        smc1h: com.example.data.smc.SmcAnalysisResult,
        smc4h: com.example.data.smc.SmcAnalysisResult,
        completed15m: List<Candle>
    ): Double? {
        // Realistic volatility reachability cap — NEVER widen TP artificially
        val maxReachableMove = max(atr4h * 3.5, max(atr1h * 6.0, atr15m * 14.0))
            .coerceAtMost(entryPrice * 0.25)

        val candidates = mutableListOf<Double>()
        if (direction == FuturesDirection.LONG) {
            smc4h.liquidity.pools.filter { it.priceLevel > entryPrice }.forEach { candidates.add(it.priceLevel) }
            smc1h.liquidity.pools.filter { it.priceLevel > entryPrice }.forEach { candidates.add(it.priceLevel) }
            (smc4h.priceZones + smc1h.priceZones)
                .filter { (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY) && it.centralPrice > entryPrice }
                .forEach { candidates.add(it.centralPrice) }
            (smc4h.marketStructure.swingPoints + smc1h.marketStructure.swingPoints)
                .filter { it.isHigh && it.price > entryPrice }
                .forEach { candidates.add(it.price) }
            completed15m.maxOfOrNull { it.high }?.takeIf { it > entryPrice }?.let { candidates.add(it) }

            return candidates
                .filter { it.isFinite() && it > entryPrice && (it - entryPrice) <= maxReachableMove }
                .maxOrNull()
        } else {
            smc4h.liquidity.pools.filter { it.priceLevel < entryPrice }.forEach { candidates.add(it.priceLevel) }
            smc1h.liquidity.pools.filter { it.priceLevel < entryPrice }.forEach { candidates.add(it.priceLevel) }
            (smc4h.priceZones + smc1h.priceZones)
                .filter { (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND) && it.centralPrice < entryPrice }
                .forEach { candidates.add(it.centralPrice) }
            (smc4h.marketStructure.swingPoints + smc1h.marketStructure.swingPoints)
                .filter { !it.isHigh && it.price < entryPrice }
                .forEach { candidates.add(it.price) }
            completed15m.minOfOrNull { it.low }?.takeIf { it > 0.0 && it < entryPrice }?.let { candidates.add(it) }

            return candidates
                .filter { it.isFinite() && it > 0.0 && it < entryPrice && (entryPrice - it) <= maxReachableMove }
                .minOrNull()
        }
    }

    private fun buildBilingualExplanations(
        symbol: String,
        direction: FuturesDirection,
        tier: OpportunityPriorityTier,
        entryPrice: Double,
        stopLoss: Double,
        takeProfit: Double,
        slPct: Double,
        tpPct: Double,
        grossRR: Double,
        netRR: Double,
        feeRoundTripPct: Double,
        slipRoundTripPct: Double,
        fundingRateDisplay: String,
        estFundingUsdt: Double,
        marginUsdt: Double,
        leverage: Int,
        estNetLossUsdt: Double,
        estNetProfitUsdt: Double,
        estNetRoiPct: Double,
        triggerPkt: String,
        expiresPkt: String,
        supportingSignals: List<String>
    ): Pair<HighRewardUrduExplanation, HighRewardUrduExplanation> {
        val dirRoman = if (direction == FuturesDirection.LONG) "LONG (Tezi)" else "SHORT (Mandi)"
        val dirUrdu = if (direction == FuturesDirection.LONG) "LONG (خرید / تیزی)" else "SHORT (فروخت / مندی)"
        val entryStr = HighRewardOpportunity.fmtPrice(entryPrice)
        val slStr = HighRewardOpportunity.fmtPrice(stopLoss)
        val tpStr = HighRewardOpportunity.fmtPrice(takeProfit)
        val supportBulletSummary = supportingSignals.take(4).joinToString(" • ")

        val roman = HighRewardUrduExplanation(
            whySelected = "NEXUS ne $symbol par $dirRoman setup is liye select kiya kyun ke completed 15M candle close ($triggerPkt PKT) par entry trigger confirm hua aur 4H/1H HTF structure se genuine 1:${fmt2(netRR)} net Risk-to-Reward (Gross 1:${fmt2(grossRR)} | ${tier.label}) nikal raha hai.",
            whereOneDollarRiskComesFrom = "Har $1.00 ka risk structural Stop Loss ($slStr, entry $entryStr se ${fmt2(slPct)}% faslay par) plus round-trip trading fees (${fmt2(feeRoundTripPct)}%), slippage (${fmt2(slipRoundTripPct)}%) aur funding rate ($fundingRateDisplay, reserve $${fmt2(estFundingUsdt)}) se banta hai. Aapke budget margin $${fmt2(marginUsdt)} (${leverage}x leverage) par maximum est. net nuqsan -$${fmt2(estNetLossUsdt)} hai.",
            howFiveDollarRewardCalculated = "Agar market aapke actual HTF structural Take Profit ($tpStr, +${fmt2(tpPct)}%) tak pohanchti hai, to fees, slippage aur adverse funding minus karne ke baad har $1 risk ke muqablay $${fmt2(netRR)} net reward banta hai (Gross 1:${fmt2(grossRR)}). Budget trade par est. net munafa +$${fmt2(estNetProfitUsdt)} (+${fmt1(estNetRoiPct)}% ROI) hai. Koi profit guarantee nahi.",
            supportingSignals = supportBulletSummary.ifBlank { "1H/4H trend alignment, SMC BOS/CHoCH, FVG/Order Block aur completed candle trigger confirmation." },
            cancellationConditions = "Agar price Stop Loss $slStr ko cross kar le, 15M structure toot jaye, ya $expiresPkt (PKT) tak move na aaye to ye setup foran CANCEL / INVALIDATE ho jata hai."
        )

        val urdu = HighRewardUrduExplanation(
            whySelected = "نیکسس نے $symbol پر $dirUrdu سیٹ اپ اس لیے منتخب کیا کیونکہ مکمل 15M کینڈل کلوز ($triggerPkt PKT) پر انٹری ٹرگر کنفرم ہوا اور اصل 4H/1H اسٹرکچر سے فیس، سلپیج اور فنڈنگ نکال کر 1:${fmt2(netRR)} خالص رِسک ٹو ریوارڈ (گراس 1:${fmt2(grossRR)} | ${tier.label}) حاصل ہو رہا ہے۔",
            whereOneDollarRiskComesFrom = "ہر $1.00 کا رِسک انٹری ($entryStr) سے ${fmt2(slPct)}% دور اصل اسٹاپ لاس ($slStr)، راؤنڈ ٹرپ ٹریڈنگ فیس (${fmt2(feeRoundTripPct)}%)، سلپیج (${fmt2(slipRoundTripPct)}%) اور فنڈنگ ریٹ ($fundingRateDisplay، ریزرو $${fmt2(estFundingUsdt)}) سے بنتا ہے۔ $${fmt2(marginUsdt)} مارجن (${leverage}x لیوریج) پر زیادہ سے زیادہ متوقع خالص نقصان -$${fmt2(estNetLossUsdt)} ہے۔",
            howFiveDollarRewardCalculated = "اگر قیمت اصل اسٹرکچرل ٹیک پرافٹ ($tpStr، +${fmt2(tpPct)}%) تک پہنچتی ہے تو تمام فیس، سلپیج اور فنڈنگ نکالنے کے بعد ہر $1 رِسک پر $${fmt2(netRR)} خالص منافع بنتا ہے (گراس 1:${fmt2(grossRR)})۔ آپ کے بجٹ پر متوقع خالص منافع +$${fmt2(estNetProfitUsdt)} (+${fmt1(estNetRoiPct)}% ROI) ہے۔ منافع کی کوئی گارنٹی نہیں۔",
            supportingSignals = supportBulletSummary.ifBlank { "1H/4H ٹرینڈ، SMC اسٹرکچر بریک (BOS/CHoCH)، فیئر ویلیو گیپ (FVG)، آرڈر بلاک اور مکمل کینڈل کلوز کنفرمیشن۔" },
            cancellationConditions = "اگر قیمت اسٹاپ لاس ($slStr) کو توڑ دے، مارکیٹ اسٹرکچر الٹ جائے، یا $expiresPkt (PKT) تک ٹریڈ فعال نہ ہو تو یہ موقع فوری طور پر کینسل / غیر مؤثر ہو جائے گا۔"
        )

        return roman to urdu
    }

    private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
    private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
}
