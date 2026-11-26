package com.example.data.signal

import com.example.data.indicators.IndicatorStance
import com.example.data.indicators.IndicatorValidationStatus
import com.example.data.indicators.OverallAnalysisResult
import com.example.data.indicators.RsiDivergenceType
import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.indicators.VolatilityRegime
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.model.NewsImpact
import com.example.data.providers.FuturesLiquiditySnapshot
import com.example.data.providers.FuturesTelemetryStatus
import com.example.data.providers.MultiMarketInstrumentCatalog
import com.example.data.providers.UnifiedHybridMarketState
import com.example.data.scanner.FuturesDirection
import com.example.data.smc.BreakoutRetestType
import com.example.data.smc.LiquidityPoolType
import com.example.data.smc.MarketTrendState
import com.example.data.smc.OrderBlockStatus
import com.example.data.smc.OrderBlockType
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import com.example.data.smc.SmcCalculationEngine
import com.example.data.smc.TimeframeSmcSnapshot
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * NEXUS MASTER UPGRADE — PART 2 OF 3
 * Strict Indicator Validation, Independent Evidence Confluence & Signal Quality Engine.
 *
 * Enforces:
 * - 8 Independent Evidence Groups (A–H = 100 pts) with anti-double-counting for correlated indicators
 * - Completed-candle-close confirmation (no repainting / future-bar leakage)
 * - Zero invented volume or liquidation data
 * - Strict 8 Mandatory Filters where ANY failed filter forces WAIT / NO TRADE regardless of score
 * - Score thresholds: <85 NO_TRADE, 85–91 QUALIFIED_SETUP, 92–100 HIGH_QUALITY_SETUP
 * - Mandatory NET R:R >= 1:4.0 after estimated trading fees & slippage
 */
object StrictSignalQualityEngine {

    const val SCORE_THRESHOLD_QUALIFIED = 85
    const val SCORE_THRESHOLD_HIGH_QUALITY = 92
    const val MIN_NET_RR = 4.0

    suspend fun evaluateSetup(
        symbol: String,
        assetName: String,
        currentPrice: Double?,
        dataStatus: MarketDataStatus,
        primaryTimeframe: CandleTimeframe,
        candles: List<Candle>,
        candles15m: List<Candle> = emptyList(),
        candles1h: List<Candle> = emptyList(),
        candles4h: List<Candle> = emptyList(),
        candles1d: List<Candle> = emptyList(),
        technicalAnalysis: OverallAnalysisResult? = null,
        smcAnalysis: SmcAnalysisResult? = null,
        mtfSnapshots: List<TimeframeSmcSnapshot> = emptyList(),
        futuresLiquidity: FuturesLiquiditySnapshot? = null,
        hybridState: UnifiedHybridMarketState? = null,
        proposedEntryPrice: Double? = null,
        proposedStopLoss: Double? = null,
        proposedTargetPrice: Double? = null,
        candidateDirectionOverride: SignalDecision? = null,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): StrictSignalQualityReport {
        val spec = MultiMarketInstrumentCatalog.resolve(symbol)
        val category = spec?.category ?: AssetCategory.CRYPTO
        val isCryptoFutures = spec?.isBinanceFuturesEligible ?: (category == AssetCategory.CRYPTO)
        val roundTripCostPct = spec?.roundTripCostPercent ?: 0.16

        val completedPrimary = TimeZoneUtils.filterCompletedCandles(candles, referenceTimeMs)
        val completed15m = TimeZoneUtils.filterCompletedCandles(
            if (candles15m.isNotEmpty()) candles15m else if (primaryTimeframe == CandleTimeframe.M15) candles else emptyList(),
            referenceTimeMs
        )
        val completed1h = TimeZoneUtils.filterCompletedCandles(candles1h, referenceTimeMs)
        val completed4h = TimeZoneUtils.filterCompletedCandles(candles4h, referenceTimeMs)
        val completed1d = TimeZoneUtils.filterCompletedCandles(candles1d, referenceTimeMs)

        // Filter #1 Pre-check: Reliable fresh data & sufficient completed candles
        val hasValidPrice = currentPrice != null && currentPrice.isFinite() && currentPrice > 0.0
        val isDataFresh = dataStatus != MarketDataStatus.UNAVAILABLE && dataStatus != MarketDataStatus.STALE
        val hasMinCandles = completedPrimary.size >= 30

        if (!hasValidPrice || !isDataFresh || !hasMinCandles) {
            return buildEarlyNoTradeReport(
                symbol = symbol,
                category = category,
                isCryptoFutures = isCryptoFutures,
                futuresLiquidity = futuresLiquidity,
                failureReason = when {
                    !hasValidPrice -> "Current price is missing or non-positive."
                    !isDataFresh -> "Market feed is ${dataStatus.label} (fresh live/delayed feed required)."
                    else -> "Insufficient completed candles (${completedPrimary.size}/30 minimum)."
                },
                referenceTimeMs = referenceTimeMs
            )
        }

        val price = currentPrice!!
        val ta = technicalAnalysis ?: TechnicalIndicatorsEngine.analyze(
            symbol, assetName, primaryTimeframe, completedPrimary, referenceTimeMs
        )
        val builtSnapshots = buildMtfSnapshots(
            symbol = symbol,
            assetName = assetName,
            existingSnapshots = mtfSnapshots,
            c15m = completed15m,
            c1h = completed1h,
            c4h = completed4h,
            c1d = completed1d
        )
        val smc = smcAnalysis ?: SmcCalculationEngine.analyze(
            symbol, assetName, primaryTimeframe, completedPrimary, builtSnapshots
        )
        val ta15m = if (completed15m.size >= 20 && primaryTimeframe != CandleTimeframe.M15) {
            TechnicalIndicatorsEngine.analyze(symbol, assetName, CandleTimeframe.M15, completed15m, referenceTimeMs)
        } else ta
        val smc15m = if (completed15m.size >= 20 && primaryTimeframe != CandleTimeframe.M15) {
            SmcCalculationEngine.analyze(symbol, assetName, CandleTimeframe.M15, completed15m, builtSnapshots)
        } else smc

        val directionsToEvaluate = when (candidateDirectionOverride) {
            SignalDecision.LONG -> listOf(SignalDecision.LONG)
            SignalDecision.SHORT -> listOf(SignalDecision.SHORT)
            else -> listOf(SignalDecision.LONG, SignalDecision.SHORT)
        }

        val evaluations = directionsToEvaluate.map { dir ->
            evaluateSingleDirection(
                direction = dir,
                symbol = symbol,
                category = category,
                isCryptoFutures = isCryptoFutures,
                roundTripCostPct = roundTripCostPct,
                currentPrice = price,
                dataStatus = dataStatus,
                primaryTimeframe = primaryTimeframe,
                completedPrimary = completedPrimary,
                completed15m = completed15m,
                ta = ta,
                smc = smc,
                ta15m = ta15m,
                smc15m = smc15m,
                mtfSnapshots = builtSnapshots,
                futuresLiquidity = futuresLiquidity,
                hybridState = hybridState,
                proposedEntryPrice = proposedEntryPrice,
                proposedStopLoss = proposedStopLoss,
                proposedTargetPrice = proposedTargetPrice,
                referenceTimeMs = referenceTimeMs
            )
        }

        // Pick best qualified report, otherwise highest-scoring candidate report
        return evaluations.firstOrNull { it.qualificationTier == StrictQualificationTier.HIGH_QUALITY_SETUP }
            ?: evaluations.firstOrNull { it.qualificationTier == StrictQualificationTier.QUALIFIED_SETUP }
            ?: evaluations.maxWith(compareBy<StrictSignalQualityReport>({ it.passedFilterCount }, { it.totalScore }))
    }

    private fun evaluateSingleDirection(
        direction: SignalDecision,
        symbol: String,
        category: AssetCategory,
        isCryptoFutures: Boolean,
        roundTripCostPct: Double,
        currentPrice: Double,
        dataStatus: MarketDataStatus,
        primaryTimeframe: CandleTimeframe,
        completedPrimary: List<Candle>,
        completed15m: List<Candle>,
        ta: OverallAnalysisResult,
        smc: SmcAnalysisResult,
        ta15m: OverallAnalysisResult,
        smc15m: SmcAnalysisResult,
        mtfSnapshots: List<TimeframeSmcSnapshot>,
        futuresLiquidity: FuturesLiquiditySnapshot?,
        hybridState: UnifiedHybridMarketState?,
        proposedEntryPrice: Double?,
        proposedStopLoss: Double?,
        proposedTargetPrice: Double?,
        referenceTimeMs: Long
    ): StrictSignalQualityReport {
        val isLong = direction == SignalDecision.LONG
        val missingConfirmations = mutableListOf<String>()

        // ============================================================
        // GROUP A: Trend & Market Structure (Max 20 pts)
        // ============================================================
        val groupA = scoreGroupA(isLong, currentPrice, ta, smc, mtfSnapshots)
        missingConfirmations.addAll(groupA.evidenceMissing)

        // ============================================================
        // GROUP B: Support/Resistance & Fibonacci (Max 15 pts)
        // ============================================================
        val groupB = scoreGroupB(isLong, currentPrice, ta, smc)
        missingConfirmations.addAll(groupB.evidenceMissing)

        // ============================================================
        // GROUP C: Candlestick & Price Action (Max 15 pts)
        // ============================================================
        val entryCandles = if (completed15m.size >= 5) completed15m else completedPrimary
        val entrySmc = if (completed15m.size >= 20) smc15m else smc
        val groupC = scoreGroupC(isLong, entryCandles, entrySmc)
        missingConfirmations.addAll(groupC.evidenceMissing)

        // ============================================================
        // GROUP D: Volume & Money Flow (Max 12 pts) — OBV/MFI/CMF merged
        // ============================================================
        val groupD = scoreGroupD(isLong, category, currentPrice, ta, smc)
        missingConfirmations.addAll(groupD.evidenceMissing)

        // ============================================================
        // GROUP E: Momentum (Max 12 pts) — RSI+StochRSI merged, OB/OS alone rejected
        // ============================================================
        val groupE = scoreGroupE(isLong, ta, smc)
        missingConfirmations.addAll(groupE.evidenceMissing)

        // ============================================================
        // GROUP F: Volatility & Risk (Max 10 pts)
        // ============================================================
        val atrVal = ta.volatility.atr?.value?.takeIf { it > 0.0 } ?: (currentPrice * 0.012)
        val riskSummary = computeNetRiskReward(
            isLong = isLong,
            currentPrice = currentPrice,
            atr = atrVal,
            smc = smc,
            roundTripCostPct = roundTripCostPct,
            proposedEntry = proposedEntryPrice,
            proposedSl = proposedStopLoss,
            proposedTp = proposedTargetPrice
        )
        val groupF = scoreGroupF(isLong, ta, riskSummary)
        missingConfirmations.addAll(groupF.evidenceMissing)

        // ============================================================
        // GROUP G: Futures Liquidity & Derivatives (Max 10 pts)
        // ============================================================
        val groupG = scoreGroupG(isLong, isCryptoFutures, futuresLiquidity, referenceTimeMs)
        missingConfirmations.addAll(groupG.evidenceMissing)

        // ============================================================
        // GROUP H: News & Market Conditions (Max 6 pts)
        // ============================================================
        val groupH = scoreGroupH(isLong, symbol, hybridState)
        missingConfirmations.addAll(groupH.evidenceMissing)

        val groups = listOf(groupA, groupB, groupC, groupD, groupE, groupF, groupG, groupH)
        val rawTotalScore = groups.sumOf { it.awardedPoints }.coerceIn(0, 100)

        // ============================================================
        // 8 MANDATORY FILTERS (Score CANNOT override a failed filter!)
        // ============================================================
        val f1 = MandatoryFilterCheck(
            id = "F1_RELIABLE_FRESH_DATA",
            filterNumber = 1,
            name = "Reliable Fresh Market Data",
            passed = currentPrice > 0.0 && dataStatus != MarketDataStatus.UNAVAILABLE &&
                dataStatus != MarketDataStatus.STALE && completedPrimary.size >= 30,
            reason = if (dataStatus == MarketDataStatus.LIVE || dataStatus == MarketDataStatus.DELAYED) {
                "Verified ${completedPrimary.size} completed ${primaryTimeframe.label} candles (${dataStatus.label})"
            } else {
                "Data feed is ${dataStatus.label}"
            }
        )

        val hasSweepReversal = hasConfirmedSweepReversal(isLong, smc, entrySmc)
        val hasClearStructure = hasDirectionalStructure(isLong, smc) || hasSweepReversal
        val f2 = MandatoryFilterCheck(
            id = "F2_DIRECTIONAL_STRUCTURE",
            filterNumber = 2,
            name = "Clear Directional Structure / Valid Sweep",
            passed = hasClearStructure,
            reason = when {
                hasSweepReversal -> "Validated Liquidity Sweep Reversal + CHoCH/MSS confirmed"
                hasDirectionalStructure(isLong, smc) -> "Market structure (${smc.marketStructure.trendState.label}) supports ${direction.label}"
                else -> "Choppy or conflicting structure — no clear HH/HL, LH/LL, BOS, or Sweep Reversal"
            }
        )

        val hasConfirmedSrAndPa = hasConfirmedSrConfluence(isLong, currentPrice, ta, smc) &&
            hasConfirmedCandlePriceAction(isLong, entryCandles, entrySmc)
        val f3 = MandatoryFilterCheck(
            id = "F3_SR_AND_PRICE_ACTION",
            filterNumber = 3,
            name = "Confirmed S/R + Price Action Context",
            passed = hasConfirmedSrAndPa,
            reason = if (hasConfirmedSrAndPa) {
                "Key S/R, OB, Pivot, or Golden Zone overlaps with confirmed candle-close reaction"
            } else {
                "Missing combined S/R zone overlap + confirmed price action (touch alone rejected)"
            }
        )

        val has15mTrigger = hasConfirmed15mTrigger(isLong, entryCandles, entrySmc, ta15m)
        val f4 = MandatoryFilterCheck(
            id = "F4_CONFIRMED_15M_TRIGGER",
            filterNumber = 4,
            name = "Confirmed 15M Candle-Close Trigger",
            passed = has15mTrigger,
            reason = if (has15mTrigger) {
                "Completed 15M entry candle closed cleanly in ${direction.label} direction"
            } else {
                "No confirmed closed 15M trigger candle (unclosed bar or weak follow-through)"
            }
        )

        val strongHtfConflict = hasStrongContradictoryHtf(isLong, mtfSnapshots)
        val f5Allowed = !strongHtfConflict || hasSweepReversal
        val f5 = MandatoryFilterCheck(
            id = "F5_NO_STRONG_HTF_CONTRADICTION",
            filterNumber = 5,
            name = "No Contradictory HTF Evidence",
            passed = f5Allowed,
            reason = when {
                !strongHtfConflict -> "No strong contradictory 1D/4H higher-timeframe trend"
                hasSweepReversal -> "Counter-HTF permitted ONLY via validated Type-A Liquidity Sweep Reversal"
                else -> "Blocked: Strong higher-timeframe (1D/4H) bias contradicts ${direction.label}"
            }
        )

        val slDistPct = abs(riskSummary.entryPrice - riskSummary.stopLoss) / riskSummary.entryPrice * 100.0
        val isRealisticSlTarget = slDistPct in 0.25..7.5 &&
            ta.volatility.volatilityRegime != VolatilityRegime.EXTREME_UNSTABLE &&
            ((isLong && riskSummary.targetPrice > riskSummary.entryPrice && riskSummary.stopLoss < riskSummary.entryPrice) ||
                (!isLong && riskSummary.targetPrice < riskSummary.entryPrice && riskSummary.stopLoss > riskSummary.entryPrice))
        val f6 = MandatoryFilterCheck(
            id = "F6_REALISTIC_SL_TARGET",
            filterNumber = 6,
            name = "Realistic Stop Loss, Target & Volatility",
            passed = isRealisticSlTarget,
            reason = when {
                ta.volatility.volatilityRegime == VolatilityRegime.EXTREME_UNSTABLE ->
                    "Blocked: Extreme / unstable volatility regime (ATR > 6.5% or spike bar)"
                slDistPct !in 0.25..7.5 ->
                    "Blocked: Stop-loss distance (${String.format(Locale.US, "%.2f", slDistPct)}%) outside realistic 0.25%–7.5% structural bounds"
                else ->
                    "Structural SL (${String.format(Locale.US, "%.2f", slDistPct)}%) and target inside healthy ${ta.volatility.volatilityRegime.label}"
            }
        )

        val f7 = MandatoryFilterCheck(
            id = "F7_MIN_NET_RR_1_4",
            filterNumber = 7,
            name = "Minimum Net R:R >= 1:4.0 After Costs",
            passed = riskSummary.meetsMinOneToFour,
            reason = if (riskSummary.meetsMinOneToFour) {
                "Net R:R ${riskSummary.netRiskRewardFormatted} meets >= 1:4.0 after ${String.format(Locale.US, "%.2f", riskSummary.roundTripFeeSlippagePercent)}% fees/slippage"
            } else {
                "Blocked: Net R:R ${riskSummary.netRiskRewardFormatted} is below strict 1:4.0 minimum after fees/slippage"
            }
        )

        val futuresTelemetryInvalidOrStale = isCryptoFutures && futuresLiquidity != null && (
            futuresLiquidity.status == FuturesTelemetryStatus.STALE ||
                (futuresLiquidity.status == FuturesTelemetryStatus.LIVE && !futuresLiquidity.isFresh(referenceTimeMs)) ||
                hasInvalidFuturesNumbers(futuresLiquidity)
            )
        val f8 = MandatoryFilterCheck(
            id = "F8_FUTURES_DATA_INTEGRITY",
            filterNumber = 8,
            name = "Futures Data Freshness & Integrity",
            passed = !futuresTelemetryInvalidOrStale,
            reason = when {
                !isCryptoFutures -> "Not applicable to non-crypto instrument (0 pts in Group G)"
                futuresTelemetryInvalidOrStale -> "Blocked: Stale or invalid futures telemetry cannot be used as confirmation"
                futuresLiquidity == null || futuresLiquidity.status == FuturesTelemetryStatus.UNAVAILABLE ->
                    "Futures telemetry unavailable — excluded from confirmation (0/10 pts in Group G)"
                else -> "Live fresh Binance USDT-M Futures telemetry verified (spread & OI/Funding valid)"
            }
        )

        val mandatoryFilters = listOf(f1, f2, f3, f4, f5, f6, f7, f8)
        val allFiltersPassed = mandatoryFilters.all { it.passed }

        // Strict Qualification Tier:
        // Score CANNOT override any failed mandatory filter!
        val qualificationTier = when {
            !allFiltersPassed || rawTotalScore < SCORE_THRESHOLD_QUALIFIED -> StrictQualificationTier.NO_TRADE
            rawTotalScore >= SCORE_THRESHOLD_HIGH_QUALITY -> StrictQualificationTier.HIGH_QUALITY_SETUP
            else -> StrictQualificationTier.QUALIFIED_SETUP
        }

        val finalMissing = buildList {
            mandatoryFilters.filter { !it.passed }.forEach { add("[Filter #${it.filterNumber} Failed] ${it.reason}") }
            addAll(missingConfirmations.distinct())
        }

        val audit = CorrelatedIndicatorAudit(
            emaSupertrendClusterCapped = true,
            rsiStochRsiMerged = true,
            obvMfiCmfMerged = true,
            adxStrengthOnlyEnforced = true,
            volumeAvailable = ta.volume.hasReliableVolume,
            futuresTelemetryStatus = when {
                !isCryptoFutures -> "NOT_APPLICABLE_NON_CRYPTO"
                futuresTelemetryInvalidOrStale -> "STALE_OR_INVALID"
                futuresLiquidity == null || futuresLiquidity.status == FuturesTelemetryStatus.UNAVAILABLE -> "UNAVAILABLE"
                else -> "LIVE_FRESH"
            }
        )

        return StrictSignalQualityReport(
            symbol = symbol,
            assetCategory = category,
            candidateDirection = if (qualificationTier == StrictQualificationTier.NO_TRADE) SignalDecision.NO_TRADE else direction,
            qualificationTier = qualificationTier,
            totalScore = rawTotalScore,
            rawDirectionalScore = rawTotalScore,
            evidenceGroups = groups,
            mandatoryFilters = mandatoryFilters,
            missingConfirmations = finalMissing,
            volatilityRegime = ta.volatility.volatilityRegime,
            netRiskRewardSummary = riskSummary,
            audit = audit,
            timestampMs = referenceTimeMs,
            timestampPkt = TimeZoneUtils.formatKarachiTimestamp(referenceTimeMs)
        )
    }

    // =========================================================================
    // GROUP A: TREND & MARKET STRUCTURE (MAX 20 PTS)
    // =========================================================================
    private fun scoreGroupA(
        isLong: Boolean,
        currentPrice: Double,
        ta: OverallAnalysisResult,
        smc: SmcAnalysisResult,
        mtfSnapshots: List<TimeframeSmcSnapshot>
    ): EvidenceGroupScore {
        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        // 1. Structure & HTF Bias (Max 7 pts)
        var structPts = 0
        val ms = smc.marketStructure
        val structMatch = if (isLong) {
            ms.trendState == MarketTrendState.BULLISH_UPTREND || ms.isBullishBOSPresent || ms.isBullishCHoCHPresent || ms.isBullishMSSPresent
        } else {
            ms.trendState == MarketTrendState.BEARISH_DOWNTREND || ms.isBearishBOSPresent || ms.isBearishCHoCHPresent || ms.isBearishMSSPresent
        }
        val structOpposite = if (isLong) {
            ms.trendState == MarketTrendState.BEARISH_DOWNTREND
        } else {
            ms.trendState == MarketTrendState.BULLISH_UPTREND
        }
        if (structMatch && !structOpposite) {
            structPts += 4
            passed.add("Primary market structure (${ms.trendState.label}) aligns")
        } else {
            missing.add("Primary market structure not clearly aligned")
        }

        val htfSnaps = mtfSnapshots.filter { it.timeframe in listOf(CandleTimeframe.D1, CandleTimeframe.H4, CandleTimeframe.H1) }
        val htfAlignedCount = htfSnaps.count { snap ->
            if (isLong) snap.isBullishBias || snap.trend == MarketTrendState.BULLISH_UPTREND
            else snap.trend == MarketTrendState.BEARISH_DOWNTREND || (!snap.isBullishBias && snap.trend != MarketTrendState.NEUTRAL_RANGING)
        }
        if (htfSnaps.isNotEmpty() && htfAlignedCount >= max(1, (htfSnaps.size + 1) / 2)) {
            structPts += 3
            passed.add("$htfAlignedCount/${htfSnaps.size} HTF (1D/4H/1H) structures agree")
        } else if (htfSnaps.isNotEmpty()) {
            missing.add("Higher timeframe (1D/4H/1H) consensus incomplete ($htfAlignedCount/${htfSnaps.size})")
        } else if (structMatch) {
            // Single-TF fallback when caller omits HTF snapshots
            structPts += 2
        }

        // 2. EMA (20/50/200 price location + slope) & Close-Confirmed Supertrend MERGED (Max 5 pts)
        var trendOverlayPts = 0
        val slope = ta.trend.emaSet.slopeEvaluation
        val emaLocSlopeMatch = if (isLong) slope?.isBullishAlignedWithSlope == true else slope?.isBearishAlignedWithSlope == true
        val stMatch = ta.trend.supertrend?.let { it.isCloseConfirmed && it.isBullish == isLong } ?: false
        when {
            emaLocSlopeMatch && stMatch -> {
                trendOverlayPts = 5
                passed.add("EMA 20/50/200 location + positive slope & close-confirmed Supertrend agree (merged cluster 5/5)")
            }
            emaLocSlopeMatch || stMatch -> {
                trendOverlayPts = 3
                passed.add(if (emaLocSlopeMatch) "EMA 20/50/200 price location + slope aligned" else "Close-confirmed Supertrend aligned")
                missing.add(if (!emaLocSlopeMatch) "EMA 20/50/200 stack + slope not aligned" else "Supertrend opposing")
            }
            else -> {
                missing.add("Neither EMA location/slope nor Supertrend aligns")
            }
        }

        // 3. ADX (Trend Strength ONLY) + DMI (+DI/-DI Direction) (Max 4 pts)
        var adxDmiPts = 0
        val adx = ta.trend.adx
        if (adx != null) {
            val dmiAligned = if (isLong) adx.dmiDirection == IndicatorStance.BULLISH else adx.dmiDirection == IndicatorStance.BEARISH
            if (dmiAligned) {
                adxDmiPts = when {
                    adx.isStrongTrend -> 4
                    adx.isTrending -> 3
                    else -> 1
                }
                passed.add("DMI agrees (${if (isLong) "+DI > -DI" else "-DI > +DI"}) with ADX strength ${String.format(Locale.US, "%.1f", adx.adx)}")
            } else {
                missing.add("DMI direction (${adx.dmiDirection.label}) does not confirm trade direction; ADX (${String.format(Locale.US, "%.1f", adx.adx)}) cannot generate direction alone")
            }
        } else {
            missing.add("ADX/DMI needs 29+ completed candles")
        }

        // 4. Ichimoku Cloud + Tenkan/Kijun + Chikou Lagging Confirmation (Max 4 pts)
        var ichiPts = 0
        val ichi = ta.trend.ichimoku
        if (ichi != null) {
            val cloudTop = max(ichi.senkouSpanA, ichi.senkouSpanB)
            val cloudBottom = min(ichi.senkouSpanA, ichi.senkouSpanB)
            val cloudMatch = if (isLong) currentPrice > cloudTop else currentPrice < cloudBottom
            val tkMatch = if (isLong) ichi.isTenkanAboveKijun else !ichi.isTenkanAboveKijun
            val chikouMatch = if (isLong) ichi.isChikouConfirmedBullish else ichi.isChikouConfirmedBearish
            if (cloudMatch) ichiPts += 2
            if (tkMatch) ichiPts += 1
            if (chikouMatch) ichiPts += 1
            if (ichiPts >= 3) {
                passed.add("Ichimoku Cloud + Tenkan/Kijun + Chikou lagging span aligned ($ichiPts/4)")
            } else {
                missing.add("Ichimoku partial/neutral (${ichi.cloudDescription})")
            }
        } else {
            missing.add("Ichimoku Cloud requires 52+ completed candles")
        }

        val total = (structPts + trendOverlayPts + adxDmiPts + ichiPts).coerceIn(0, 20)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.A_TREND_STRUCTURE,
            awardedPoints = total,
            status = if (ta.candleCount >= 52) IndicatorValidationStatus.VALID else IndicatorValidationStatus.INSUFFICIENT_DATA,
            directionalStance = if (total >= 12) (if (isLong) "LONG" else "SHORT") else "CONFLICTING",
            summary = "Structure $structPts/7 • EMA/Supertrend(merged) $trendOverlayPts/5 • ADX+DMI $adxDmiPts/4 • Ichimoku $ichiPts/4",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = "EMA 20/50/200 + Supertrend capped as 1 cluster (5 pts max); ADX evaluated strictly as trend strength."
        )
    }

    // =========================================================================
    // GROUP B: SUPPORT/RESISTANCE & FIBONACCI (MAX 15 PTS)
    // =========================================================================
    private fun scoreGroupB(
        isLong: Boolean,
        currentPrice: Double,
        ta: OverallAnalysisResult,
        smc: SmcAnalysisResult
    ): EvidenceGroupScore {
        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        var srPts = 0
        val nearZone = smc.priceZones.any { z ->
            abs(z.distancePercent) <= 1.8 &&
                ((isLong && (z.type == PriceZoneType.SUPPORT || z.type == PriceZoneType.DEMAND)) ||
                    (!isLong && (z.type == PriceZoneType.RESISTANCE || z.type == PriceZoneType.SUPPLY)))
        }
        val nearOb = smc.orderBlocks.any { ob ->
            ob.status != OrderBlockStatus.INVALIDATED &&
                ((isLong && ob.type == OrderBlockType.BULLISH && currentPrice in (ob.bottomPrice * 0.985)..(ob.topPrice * 1.02)) ||
                    (!isLong && ob.type == OrderBlockType.BEARISH && currentPrice in (ob.bottomPrice * 0.98)..(ob.topPrice * 1.015)))
        }
        val pp = ta.volatility.pivotPoints
        val pivotAligned = pp != null && if (isLong) currentPrice >= pp.pivotPoint || currentPrice >= pp.s1 * 0.995 else currentPrice <= pp.pivotPoint || currentPrice <= pp.r1 * 1.005
        val donchian = ta.volatility.donchianChannels
        val donchianClear = donchian != null && if (isLong) currentPrice > donchian.middle else currentPrice < donchian.middle

        if (nearZone || nearOb) {
            srPts += 5
            passed.add("Anchored at confirmed multi-source S/R, Demand/Supply, or Order Block")
        } else {
            missing.add("Price is not reacting at a verified S/R, Demand/Supply, or OB zone")
        }
        if (pivotAligned) srPts += 2
        if (donchianClear) srPts += 1

        var fibPts = 0
        val fib = smc.fibonacci
        val fibReport = fib?.validationReport
        if (fib != null && fibReport != null && fib.isUptrendSwing == isLong) {
            when {
                fibReport.isStrictlyValid -> {
                    fibPts = 7
                    passed.add("7-Gate Fibonacci Golden Zone (0.500–0.618) + confluence strictly validated")
                }
                fibReport.isInGoldenZone0500To0618 && fibReport.hasSrOrOrderBlockConfluence -> {
                    fibPts = 5
                    passed.add("Fib Golden Zone overlaps structural S/R / OB")
                }
                fibReport.isInGoldenZone0500To0618 -> {
                    fibPts = 2
                    missing.add("Fibonacci Golden Zone touch alone without S/R + candle reaction confluence")
                }
                fib.extensionLevels.any { abs(it.ratio - 1.272) < 0.01 } && (nearZone || nearOb) -> {
                    fibPts = 5
                    passed.add("Valid Fibonacci swing + 1.272/1.618 extension room aligned with S/R")
                }
                else -> {
                    missing.add("Outside Golden Zone / Fibonacci gates incomplete (${fibReport.passedGatesCount}/7)")
                }
            }
        } else if (nearZone || nearOb) {
            // Structural breakout/trend continuation without pullback into Golden Zone still receives modest level points if extensions exist
            fibPts = 4
            passed.add("Structural S/R + Pivot level context present without forced Fib overlap")
        } else {
            missing.add("No valid Fibonacci swing alignment for ${if (isLong) "LONG" else "SHORT"}")
        }

        val total = (srPts + fibPts).coerceIn(0, 15)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.B_SR_FIBONACCI,
            awardedPoints = total,
            status = IndicatorValidationStatus.VALID,
            directionalStance = if (total >= 9) (if (isLong) "LONG" else "SHORT") else "NEUTRAL",
            summary = "Multi-Source S/R & Pivots $srPts/8 • Fibonacci Confluence $fibPts/7",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = "Fib Golden Zone awards full points ONLY when overlapping S/R, OB, or confirmed reaction (touch alone capped at 2 pts)."
        )
    }

    // =========================================================================
    // GROUP C: CANDLESTICK & PRICE ACTION (MAX 15 PTS)
    // =========================================================================
    private fun scoreGroupC(
        isLong: Boolean,
        entryCandles: List<Candle>,
        entrySmc: SmcAnalysisResult
    ): EvidenceGroupScore {
        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var pts = 0

        val paConfirmed = entrySmc.priceActionPatterns.any { it.isCandleClosedConfirmed && it.type.isBullish == isLong }
        val breakoutRetest = entrySmc.breakoutRetests.any {
            (isLong && (it.type == BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BULLISH || it.type == BreakoutRetestType.FAILED_BREAKOUT_BEAR_TRAP)) ||
                (!isLong && (it.type == BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BEARISH || it.type == BreakoutRetestType.FAILED_BREAKOUT_BULL_TRAP))
        }
        val sweep = entrySmc.liquidity.recentSweeps.any {
            if (isLong) it.poolType == LiquidityPoolType.SELL_SIDE_LIQUIDITY || it.poolType == LiquidityPoolType.EQUAL_LOWS
            else it.poolType == LiquidityPoolType.BUY_SIDE_LIQUIDITY || it.poolType == LiquidityPoolType.EQUAL_HIGHS
        }

        val last = entryCandles.last()
        val prev = entryCandles[entryCandles.size - 2]
        val barRange = (last.high - last.low).coerceAtLeast(1e-9)
        val bodyRatio = abs(last.close - last.open) / barRange
        val decisiveClose = if (isLong) {
            last.close > last.open && last.close > prev.close && bodyRatio >= 0.45
        } else {
            last.close < last.open && last.close < prev.close && bodyRatio >= 0.45
        }

        if (paConfirmed) {
            pts += 6
            passed.add("Closed-candle rejection/engulfing pattern confirmed")
        }
        if (breakoutRetest) {
            pts += 5
            passed.add("Confirmed Breakout & Retest / Trap reclamation")
        }
        if (sweep) {
            pts += 4
            passed.add("Liquidity sweep reclamation confirms trapped side")
        }
        if (decisiveClose) {
            pts += if (paConfirmed || breakoutRetest) 5 else 6
            passed.add("Completed trigger candle closed decisively (${String.format(Locale.US, "%.0f", bodyRatio * 100)}% body)")
        } else {
            missing.add("Latest completed bar lacks decisive directional candle close")
        }

        if (pts == 0) {
            missing.add("No confirmed candlestick reaction, breakout-retest, or sweep trigger")
        }

        val total = pts.coerceIn(0, 15)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.C_CANDLESTICK_PRICE_ACTION,
            awardedPoints = total,
            status = IndicatorValidationStatus.VALID,
            directionalStance = if (total >= 9) (if (isLong) "LONG" else "SHORT") else "NEUTRAL",
            summary = "Closed-candle Price Action & Trigger confirmation: $total/15",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = "Evaluated exclusively on closed completed bars (forming bars excluded)."
        )
    }

    // =========================================================================
    // GROUP D: VOLUME & MONEY FLOW (MAX 12 PTS) — OBV + MFI + CMF MERGED
    // =========================================================================
    private fun scoreGroupD(
        isLong: Boolean,
        category: AssetCategory,
        currentPrice: Double,
        ta: OverallAnalysisResult,
        smc: SmcAnalysisResult
    ): EvidenceGroupScore {
        if (!ta.volume.hasReliableVolume) {
            return EvidenceGroupScore(
                groupId = EvidenceGroupId.D_VOLUME_MONEY_FLOW,
                awardedPoints = 0,
                status = IndicatorValidationStatus.UNAVAILABLE,
                directionalStance = "UNAVAILABLE",
                summary = "UNAVAILABLE: Feed provides no valid volume (0/12 pts; never invented)",
                evidencePassed = emptyList(),
                evidenceMissing = listOf("Volume unavailable on feed — Group D excluded cleanly (0 pts)"),
                antiDoubleCountNote = "OBV/MFI/CMF merged; missing volume is never fabricated."
            )
        }

        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        // 1. RVOL vs 20-period average (Max 5 pts)
        val rvol = ta.volume.relativeVolume
        val strongRvolThreshold = if (category == AssetCategory.CRYPTO) 1.5 else 1.25
        val supportRvolThreshold = if (category == AssetCategory.CRYPTO) 1.1 else 1.0
        val rvolPts = when {
            rvol == null -> {
                missing.add("RVOL(20) unavailable")
                0
            }
            rvol >= strongRvolThreshold -> {
                passed.add("RVOL ${String.format(Locale.US, "%.2f", rvol)}x >= ${strongRvolThreshold}x volume expansion")
                5
            }
            rvol >= supportRvolThreshold -> {
                passed.add("RVOL ${String.format(Locale.US, "%.2f", rvol)}x supports participation")
                3
            }
            else -> {
                missing.add("Low RVOL (${String.format(Locale.US, "%.2f", rvol)}x < ${supportRvolThreshold}x)")
                1
            }
        }

        // 2. Session VWAP & Volume Profile POC/VAH/VAL (Max 3 pts)
        var vwapVpPts = 0
        val vwap = ta.trend.vwap
        val vp = smc.volumeProfile
        if (vwap != null && ((isLong && currentPrice >= vwap) || (!isLong && currentPrice <= vwap))) {
            vwapVpPts += 2
            passed.add("Price aligned with session VWAP")
        } else {
            missing.add("Price misaligned with session VWAP")
        }
        if (vp != null && vp.isVolumeReliable) {
            val vpSupport = if (isLong) currentPrice >= vp.valPrice else currentPrice <= vp.vahPrice
            if (vpSupport) {
                vwapVpPts += 1
                passed.add("Volume Profile (POC/VAH/VAL) supports positioning")
            }
        }

        // 3. OBV + MFI + CMF MERGED into ONE single Money Flow sub-vote (Max 4 pts total!)
        val obvMatch = ta.volume.obv?.let {
            if (isLong) it.stance == IndicatorStance.BULLISH || it.stance == IndicatorStance.STRONG_BULLISH
            else it.stance == IndicatorStance.BEARISH || it.stance == IndicatorStance.STRONG_BEARISH
        } ?: false
        val mfiMatch = ta.volume.mfi?.let { mfi ->
            if (isLong) mfi in 48.0..82.0 else mfi in 18.0..52.0
        } ?: false
        val cmfMatch = ta.volume.cmf?.let { cmf ->
            if (isLong) cmf > 0.0 else cmf < 0.0
        } ?: false
        val flowAgreementCount = listOf(obvMatch, mfiMatch, cmfMatch).count { it }
        val mergedFlowPts = when (flowAgreementCount) {
            3 -> 4
            2 -> 3
            1 -> 2
            else -> 0
        }
        if (mergedFlowPts >= 3) {
            passed.add("Merged OBV/MFI/CMF Money-Flow Cluster confirms ($flowAgreementCount/3 aligned -> $mergedFlowPts/4 pts)")
        } else {
            missing.add("Merged OBV/MFI/CMF Money-Flow Cluster weak ($flowAgreementCount/3 aligned)")
        }

        val total = (rvolPts + vwapVpPts + mergedFlowPts).coerceIn(0, 12)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.D_VOLUME_MONEY_FLOW,
            awardedPoints = total,
            status = IndicatorValidationStatus.VALID,
            directionalStance = if (total >= 8) (if (isLong) "LONG" else "SHORT") else "NEUTRAL",
            summary = "RVOL $rvolPts/5 • VWAP/VP $vwapVpPts/3 • OBV+MFI+CMF(merged) $mergedFlowPts/4",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = "OBV, MFI, and CMF are grouped as ONE 4-pt money-flow vote (never 3 separate votes)."
        )
    }

    // =========================================================================
    // GROUP E: MOMENTUM (MAX 12 PTS) — RSI+StochRSI MERGED, OB/OS ALONE REJECTED
    // =========================================================================
    private fun scoreGroupE(
        isLong: Boolean,
        ta: OverallAnalysisResult,
        smc: SmcAnalysisResult
    ): EvidenceGroupScore {
        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()

        val rsi14 = ta.momentum.rsi14
        val stochRsi = ta.momentum.stochRsi
        val macd = ta.momentum.macd
        val cci = ta.momentum.cci
        val rsiDiv = ta.momentum.rsiDivergence

        val hasStructure = hasDirectionalStructure(isLong, smc)
        val divAligned = rsiDiv != null && rsiDiv.isConfirmedClosedSwings &&
            ((isLong && rsiDiv.type == RsiDivergenceType.BULLISH_REGULAR) ||
                (!isLong && rsiDiv.type == RsiDivergenceType.BEARISH_REGULAR))

        // 1. RSI + StochRSI MERGED into ONE RSI-family sub-vote (Max 4 pts)
        // Strict rule: Standalone RSI Overbought (>=70) or Oversold (<=30) NEVER counts as a reversal signal!
        var rsiClusterPts = 0
        if (rsi14 != null) {
            val isBullTrendMomentum = isLong && rsi14 in 48.0..74.0
            val isBearTrendMomentum = !isLong && rsi14 in 26.0..52.0
            val stochAligned = stochRsi?.let { if (isLong) it.k >= it.d else it.k <= it.d } ?: false

            when {
                (isBullTrendMomentum || isBearTrendMomentum) && stochAligned -> {
                    rsiClusterPts = 4
                    passed.add("RSI(14)=${String.format(Locale.US, "%.1f", rsi14)} + StochRSI merged momentum aligned (4/4)")
                }
                isBullTrendMomentum || isBearTrendMomentum -> {
                    rsiClusterPts = 3
                    passed.add("RSI(14)=${String.format(Locale.US, "%.1f", rsi14)} supports trend momentum")
                }
                divAligned && hasStructure -> {
                    rsiClusterPts = 3
                    passed.add("Confirmed non-repainting RSI Divergence + structure shift")
                }
                else -> {
                    if ((isLong && rsi14 <= 30.0) || (!isLong && rsi14 >= 70.0)) {
                        missing.add("RSI extreme (${String.format(Locale.US, "%.1f", rsi14)}) alone is NOT a reversal signal without structure/divergence")
                    } else {
                        missing.add("RSI/StochRSI cluster does not confirm ${if (isLong) "LONG" else "SHORT"} momentum")
                    }
                }
            }
        } else {
            missing.add("RSI(14) requires 15+ completed candles")
        }

        // 2. MACD checked against trend & momentum (Max 4 pts)
        var macdPts = 0
        if (macd != null) {
            val macdStrong = if (isLong) macd.histogram > 0.0 && macd.macdLine > macd.signalLine && macd.macdLine > 0.0
            else macd.histogram < 0.0 && macd.macdLine < macd.signalLine && macd.macdLine < 0.0
            val macdBasic = if (isLong) macd.histogram > 0.0 && macd.macdLine > macd.signalLine
            else macd.histogram < 0.0 && macd.macdLine < macd.signalLine
            when {
                macdStrong -> {
                    macdPts = 4
                    passed.add("MACD line & histogram confirm trend expansion")
                }
                macdBasic -> {
                    macdPts = 3
                    passed.add("MACD crossover aligned with direction")
                }
                else -> {
                    missing.add("MACD contradicts or lacks momentum (${macd.crossoverStatus})")
                }
            }
        } else {
            missing.add("MACD requires 35+ completed candles")
        }

        // 3. CCI evaluated with price structure + Non-Repainting RSI Divergence (Max 4 pts)
        var cciDivPts = 0
        if (cci != null) {
            val cciWithStructure = hasStructure && if (isLong) cci > 0.0 else cci < 0.0
            val cciExtremeConfirmed = hasStructure && if (isLong) cci > 100.0 else cci < -100.0
            if (cciExtremeConfirmed) {
                cciDivPts += 3
                passed.add("CCI(${String.format(Locale.US, "%.0f", cci)}) expansion confirmed with price structure")
            } else if (cciWithStructure) {
                cciDivPts += 2
                passed.add("CCI(${String.format(Locale.US, "%.0f", cci)}) agrees with structure")
            } else {
                missing.add("CCI extreme/bias not confirmed by price structure")
            }
        }
        if (divAligned) {
            cciDivPts = (cciDivPts + 2).coerceAtMost(4)
            passed.add(rsiDiv!!.description)
        } else if (cciDivPts in 2..3 && macdPts == 4 && rsiClusterPts == 4) {
            // Clean trend continuation with full RSI+MACD+CCI alignment doesn't require reversal divergence
            cciDivPts = min(4, cciDivPts + 1)
        }

        val total = (rsiClusterPts + macdPts + cciDivPts).coerceIn(0, 12)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.E_MOMENTUM,
            awardedPoints = total,
            status = if (rsi14 != null && macd != null) IndicatorValidationStatus.VALID else IndicatorValidationStatus.INSUFFICIENT_DATA,
            directionalStance = if (total >= 8) (if (isLong) "LONG" else "SHORT") else "NEUTRAL",
            summary = "RSI+StochRSI(merged) $rsiClusterPts/4 • MACD $macdPts/4 • CCI+Divergence $cciDivPts/4",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = "StochRSI merged with RSI (never double-counted); standalone RSI OB/OS rejected."
        )
    }

    // =========================================================================
    // GROUP F: VOLATILITY & RISK (MAX 10 PTS)
    // =========================================================================
    private fun scoreGroupF(
        isLong: Boolean,
        ta: OverallAnalysisResult,
        riskSummary: NetRiskRewardSummary
    ): EvidenceGroupScore {
        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val regime = ta.volatility.volatilityRegime
        val atr = ta.volatility.atr

        if (regime == VolatilityRegime.EXTREME_UNSTABLE) {
            return EvidenceGroupScore(
                groupId = EvidenceGroupId.F_VOLATILITY_RISK,
                awardedPoints = 0,
                status = IndicatorValidationStatus.INVALID,
                directionalStance = "CONFLICTING",
                summary = "Extreme / Unstable Volatility (ATR ${atr?.let { String.format(Locale.US, "%.2f", it.percentOfPrice) } ?: "high"}%) — Setup Rejected",
                evidencePassed = emptyList(),
                evidenceMissing = listOf("Excessive/unstable volatility spike rejected"),
                antiDoubleCountNote = "BB touches are NOT treated as automatic reversal signals."
            )
        }

        var pts = 0
        // 1. ATR realistic SL feasibility (Max 4 pts)
        val slPct = abs(riskSummary.entryPrice - riskSummary.stopLoss) / riskSummary.entryPrice * 100.0
        if (atr != null && slPct in 0.25..6.5) {
            pts += 4
            passed.add("ATR(${String.format(Locale.US, "%.2f", atr.percentOfPrice)}%) supports realistic ${String.format(Locale.US, "%.2f", slPct)}% SL")
        } else {
            missing.add("ATR or realistic SL distance outside safe range")
        }

        // 2. Squeeze/Expansion or Controlled Normal Regime (Max 3 pts)
        val bb = ta.volatility.bollingerBands
        when (regime) {
            VolatilityRegime.SQUEEZE -> {
                pts += 3
                passed.add("BB/Keltner Squeeze priming directional expansion")
            }
            VolatilityRegime.EXPANSION -> {
                val alignedExpansion = bb == null || (isLong && ta.currentClose > bb.middle) || (!isLong && ta.currentClose < bb.middle)
                if (alignedExpansion) {
                    pts += 3
                    passed.add("Controlled directional volatility expansion")
                } else {
                    pts += 1
                    missing.add("Volatility expanding against trade direction")
                }
            }
            VolatilityRegime.NORMAL -> {
                pts += 3
                passed.add("Stable normal volatility envelope")
            }
            VolatilityRegime.EXTREME_UNSTABLE -> {}
        }

        // 3. Net R:R >= 1:4 Risk Efficiency (Max 3 pts)
        if (riskSummary.meetsMinOneToFour) {
            pts += 3
            passed.add("Net R:R ${riskSummary.netRiskRewardFormatted} (>= 1:4.0 after fees/slippage)")
        } else {
            missing.add("Net R:R ${riskSummary.netRiskRewardFormatted} below 1:4.0 minimum")
        }

        val total = pts.coerceIn(0, 10)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.F_VOLATILITY_RISK,
            awardedPoints = total,
            status = IndicatorValidationStatus.VALID,
            directionalStance = if (total >= 7) (if (isLong) "LONG" else "SHORT") else "CONFLICTING",
            summary = "Regime: ${regime.label} • Net R:R ${riskSummary.netRiskRewardFormatted} ($total/10)",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = "Bollinger Band outer touches never generate automatic reversal votes."
        )
    }

    // =========================================================================
    // GROUP G: FUTURES LIQUIDITY & DERIVATIVES (MAX 10 PTS)
    // =========================================================================
    private fun scoreGroupG(
        isLong: Boolean,
        isCryptoFutures: Boolean,
        futuresLiquidity: FuturesLiquiditySnapshot?,
        referenceTimeMs: Long
    ): EvidenceGroupScore {
        if (!isCryptoFutures) {
            return EvidenceGroupScore(
                groupId = EvidenceGroupId.G_FUTURES_LIQUIDITY,
                awardedPoints = 0,
                status = IndicatorValidationStatus.UNAVAILABLE,
                directionalStance = "UNAVAILABLE",
                summary = "Not Applicable (Non-Crypto Asset — 0/10 pts)",
                evidencePassed = emptyList(),
                evidenceMissing = emptyList(),
                antiDoubleCountNote = "Stocks, Commodities, Forex, and Indices are never evaluated as Binance Futures."
            )
        }

        if (futuresLiquidity == null || futuresLiquidity.status == FuturesTelemetryStatus.UNAVAILABLE) {
            return EvidenceGroupScore(
                groupId = EvidenceGroupId.G_FUTURES_LIQUIDITY,
                awardedPoints = 0,
                status = IndicatorValidationStatus.UNAVAILABLE,
                directionalStance = "UNAVAILABLE",
                summary = "UNAVAILABLE: Futures OI/Funding/Order Book unavailable (0/10 pts)",
                evidencePassed = emptyList(),
                evidenceMissing = listOf("Crypto futures derivatives & order-book telemetry unavailable (never counted as confirmation)"),
                antiDoubleCountNote = "Missing futures telemetry awards 0 points; liquidation heatmap remains UNAVAILABLE."
            )
        }

        if (futuresLiquidity.status == FuturesTelemetryStatus.STALE ||
            !futuresLiquidity.isFresh(referenceTimeMs) ||
            hasInvalidFuturesNumbers(futuresLiquidity)
        ) {
            return EvidenceGroupScore(
                groupId = EvidenceGroupId.G_FUTURES_LIQUIDITY,
                awardedPoints = 0,
                status = IndicatorValidationStatus.STALE,
                directionalStance = "UNAVAILABLE",
                summary = "STALE / INVALID: Derivatives snapshot expired or invalid (0/10 pts — Filter #8 Blocked)",
                evidencePassed = emptyList(),
                evidenceMissing = listOf("Stale or invalid futures telemetry cannot confirm setups"),
                antiDoubleCountNote = "Stale futures telemetry fails Filter #8 and awards 0 points."
            )
        }

        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var pts = 0

        // 1. Open Interest + Long/Short positioning (Max 3 pts)
        val oi = futuresLiquidity.openInterestContracts
        val ls = futuresLiquidity.longShortAccountRatio
        if (oi != null && oi > 0.0) {
            val healthyPositioning = ls == null || if (isLong) ls < 2.2 else ls > 0.55
            if (healthyPositioning) {
                pts += 3
                passed.add("Fresh OI verified & L/S ratio (${ls?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"}) not over-crowded")
            } else {
                pts += 1
                missing.add("Crowd L/S ratio (${String.format(Locale.US, "%.2f", ls)}) heavily skewed")
            }
        } else {
            missing.add("Open Interest unavailable")
        }

        // 2. Funding Rate sanity (Max 2 pts)
        val funding = futuresLiquidity.fundingRatePercent
        if (funding != null) {
            val fundingHealthy = if (isLong) funding <= 0.05 else funding >= -0.05
            if (fundingHealthy) {
                pts += 2
                passed.add("Funding rate (${String.format(Locale.US, "%+.4f%%", funding)}) healthy for ${if (isLong) "LONG" else "SHORT"}")
            } else {
                missing.add("Crowded funding rate (${String.format(Locale.US, "%+.4f%%", funding)}) against position")
            }
        } else {
            missing.add("Funding rate unavailable")
        }

        // 3. Real Order Book Depth (Max 3 pts)
        val depth = futuresLiquidity.orderBookDepth
        if (depth != null && depth.spreadBps <= 15.0) {
            val depthAligned = if (isLong) depth.bidAskImbalanceRatio >= 1.02 else depth.bidAskImbalanceRatio <= 0.98
            if (depthAligned) {
                pts += 3
                passed.add("Live Order Book Depth supports side (Bid/Ask ${String.format(Locale.US, "%.2f", depth.bidAskImbalanceRatio)}x, spread ${String.format(Locale.US, "%.1f", depth.spreadBps)} bps)")
            } else {
                pts += 1
                missing.add("Order book liquidity imbalance slightly favors opposing side")
            }
        } else {
            missing.add("Order book depth unavailable or spread > 15 bps")
        }

        // 4. Real Taker CVD / Delta (Max 2 pts)
        val cvd = futuresLiquidity.lastBarVolumeDelta ?: futuresLiquidity.takerCvd
        if (cvd != null) {
            val cvdAligned = if (isLong) cvd > 0.0 else cvd < 0.0
            if (cvdAligned) {
                pts += 2
                passed.add("Taker CVD / order-flow aggression aligns")
            } else {
                missing.add("Taker order flow delta opposes direction")
            }
        } else {
            missing.add("Taker CVD unavailable")
        }

        // Honest Liquidation note always surfaced
        val total = pts.coerceIn(0, 10)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.G_FUTURES_LIQUIDITY,
            awardedPoints = total,
            status = IndicatorValidationStatus.VALID,
            directionalStance = if (total >= 6) (if (isLong) "LONG" else "SHORT") else "NEUTRAL",
            summary = "Futures Telemetry: $total/10 • Liquidations: ${if (futuresLiquidity.liquidationDataAvailable) "LIVE" else "UNAVAILABLE"}",
            evidencePassed = passed,
            evidenceMissing = missing,
            antiDoubleCountNote = futuresLiquidity.liquidationNote
        )
    }

    // =========================================================================
    // GROUP H: NEWS & MARKET CONDITIONS (MAX 6 PTS)
    // =========================================================================
    private fun scoreGroupH(
        isLong: Boolean,
        symbol: String,
        hybridState: UnifiedHybridMarketState?
    ): EvidenceGroupScore {
        if (hybridState == null) {
            return EvidenceGroupScore(
                groupId = EvidenceGroupId.H_NEWS_MARKET_CONDITIONS,
                awardedPoints = 3,
                status = IndicatorValidationStatus.UNAVAILABLE,
                directionalStance = "NEUTRAL",
                summary = "Macro/News feed not attached (Neutral baseline 3/6 pts)",
                evidencePassed = listOf("Neutral macro baseline applied without inventing news"),
                evidenceMissing = emptyList()
            )
        }

        val passed = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var pts = 2 // baseline clean environment

        val fng = hybridState.fearGreed
        if (fng != null) {
            val fngSupportive = if (isLong) fng.value in 20..78 else fng.value in 22..80
            if (fngSupportive) {
                pts += 1
                passed.add("Fear & Greed (${fng.value} - ${fng.classification}) not in counter-extreme")
            } else {
                missing.add("Sentiment extreme (${fng.value}) warrants caution")
            }
        }

        val cross = hybridState.crossExchangeComparisons[symbol.uppercase(Locale.US)]
            ?: hybridState.crossExchangeComparisons["${MultiMarketInstrumentCatalog.normalizeCanonicalId(symbol)}USDT"]
        if (cross != null && cross.isComparable && (cross.spreadBps ?: 99.0) <= 25.0) {
            pts += 1
            passed.add("Cross-exchange pricing consistent (${String.format(Locale.US, "%.1f", cross.spreadBps ?: 0.0)} bps)")
        } else if (cross != null && !cross.isComparable) {
            pts = (pts - 1).coerceAtLeast(0)
            missing.add("Cross-exchange spread divergence detected")
        }

        val relevantNews = hybridState.filteredNews.take(8)
        val adverseHighImpact = relevantNews.any { item ->
            val imp = item.article.impact
            (isLong && imp == NewsImpact.BEARISH) || (!isLong && imp == NewsImpact.BULLISH)
        }
        val supportiveNews = relevantNews.any { item ->
            val imp = item.article.impact
            (isLong && imp == NewsImpact.BULLISH) || (!isLong && imp == NewsImpact.BEARISH)
        }
        when {
            adverseHighImpact -> {
                pts = (pts - 1).coerceAtLeast(0)
                missing.add("Headline risk conflicts with ${if (isLong) "LONG" else "SHORT"} bias")
            }
            supportiveNews -> {
                pts += 2
                passed.add("Verified wire headlines align with bias")
            }
            else -> {
                pts += 1
                passed.add("No conflicting macro wire shock detected")
            }
        }

        val total = pts.coerceIn(0, 6)
        return EvidenceGroupScore(
            groupId = EvidenceGroupId.H_NEWS_MARKET_CONDITIONS,
            awardedPoints = total,
            status = IndicatorValidationStatus.VALID,
            directionalStance = if (total >= 4) (if (isLong) "LONG" else "SHORT") else "NEUTRAL",
            summary = "News, Macro Sentiment & Cross-Venue Health: $total/6",
            evidencePassed = passed,
            evidenceMissing = missing
        )
    }

    // =========================================================================
    // MANDATORY FILTER & RISK HELPERS
    // =========================================================================
    private fun hasDirectionalStructure(isLong: Boolean, smc: SmcAnalysisResult): Boolean {
        val ms = smc.marketStructure
        return if (isLong) {
            ms.trendState == MarketTrendState.BULLISH_UPTREND || ms.isBullishBOSPresent || ms.isBullishCHoCHPresent || ms.isBullishMSSPresent
        } else {
            ms.trendState == MarketTrendState.BEARISH_DOWNTREND || ms.isBearishBOSPresent || ms.isBearishCHoCHPresent || ms.isBearishMSSPresent
        }
    }

    private fun hasConfirmedSweepReversal(isLong: Boolean, primarySmc: SmcAnalysisResult, entrySmc: SmcAnalysisResult): Boolean {
        val sweeps = primarySmc.liquidity.recentSweeps + entrySmc.liquidity.recentSweeps
        val hasSweep = sweeps.any {
            if (isLong) it.poolType == LiquidityPoolType.SELL_SIDE_LIQUIDITY || it.poolType == LiquidityPoolType.EQUAL_LOWS
            else it.poolType == LiquidityPoolType.BUY_SIDE_LIQUIDITY || it.poolType == LiquidityPoolType.EQUAL_HIGHS
        }
        val hasShift = if (isLong) {
            primarySmc.marketStructure.isBullishCHoCHPresent || primarySmc.marketStructure.isBullishMSSPresent ||
                entrySmc.marketStructure.isBullishCHoCHPresent || entrySmc.marketStructure.isBullishMSSPresent
        } else {
            primarySmc.marketStructure.isBearishCHoCHPresent || primarySmc.marketStructure.isBearishMSSPresent ||
                entrySmc.marketStructure.isBearishCHoCHPresent || entrySmc.marketStructure.isBearishMSSPresent
        }
        return hasSweep && hasShift
    }

    private fun hasConfirmedSrConfluence(
        isLong: Boolean,
        currentPrice: Double,
        ta: OverallAnalysisResult,
        smc: SmcAnalysisResult
    ): Boolean {
        val nearZone = smc.priceZones.any { z ->
            abs(z.distancePercent) <= 2.2 &&
                ((isLong && (z.type == PriceZoneType.SUPPORT || z.type == PriceZoneType.DEMAND)) ||
                    (!isLong && (z.type == PriceZoneType.RESISTANCE || z.type == PriceZoneType.SUPPLY)))
        }
        val nearOb = smc.orderBlocks.any { ob ->
            ob.status != OrderBlockStatus.INVALIDATED &&
                ((isLong && ob.type == OrderBlockType.BULLISH && currentPrice in (ob.bottomPrice * 0.98)..(ob.topPrice * 1.03)) ||
                    (!isLong && ob.type == OrderBlockType.BEARISH && currentPrice in (ob.bottomPrice * 0.97)..(ob.topPrice * 1.02)))
        }
        val fib = smc.fibonacci
        val fibConfluence = fib != null && fib.isUptrendSwing == isLong &&
            (fib.validationReport?.isStrictlyValid == true ||
                (fib.validationReport?.isInGoldenZone0500To0618 == true && fib.validationReport.hasSrOrOrderBlockConfluence))
        val pivotNear = ta.volatility.pivotPoints?.let { pp ->
            abs(currentPrice - pp.pivotPoint) / currentPrice <= 0.025 ||
                (isLong && abs(currentPrice - pp.s1) / currentPrice <= 0.02) ||
                (!isLong && abs(currentPrice - pp.r1) / currentPrice <= 0.02)
        } ?: false
        return nearZone || nearOb || fibConfluence || pivotNear
    }

    private fun hasConfirmedCandlePriceAction(
        isLong: Boolean,
        entryCandles: List<Candle>,
        entrySmc: SmcAnalysisResult
    ): Boolean {
        if (entrySmc.priceActionPatterns.any { it.isCandleClosedConfirmed && it.type.isBullish == isLong }) return true
        if (entrySmc.breakoutRetests.any { it.type.isBullish == isLong }) return true
        if (entryCandles.size < 2) return false
        val last = entryCandles.last()
        val prev = entryCandles[entryCandles.size - 2]
        return if (isLong) last.close > last.open && last.close > prev.close else last.close < last.open && last.close < prev.close
    }

    private fun hasConfirmed15mTrigger(
        isLong: Boolean,
        entryCandles: List<Candle>,
        entrySmc: SmcAnalysisResult,
        ta15m: OverallAnalysisResult
    ): Boolean {
        if (entryCandles.size < 2) return false
        val last = entryCandles.last()
        val prev = entryCandles[entryCandles.size - 2]
        val barRange = (last.high - last.low).coerceAtLeast(1e-9)
        val bodyRatio = abs(last.close - last.open) / barRange
        val candleAligned = if (isLong) {
            last.close > last.open && (last.close > prev.close || last.close > (last.low + barRange * 0.6))
        } else {
            last.close < last.open && (last.close < prev.close || last.close < (last.high - barRange * 0.6))
        }
        val paMatch = entrySmc.priceActionPatterns.any { it.isCandleClosedConfirmed && it.type.isBullish == isLong }
        val stMatch = ta15m.trend.supertrend?.let { it.isCloseConfirmed && it.isBullish == isLong } ?: true
        return (candleAligned && bodyRatio >= 0.25 && (stMatch || paMatch)) || paMatch
    }

    private fun hasStrongContradictoryHtf(isLong: Boolean, mtfSnapshots: List<TimeframeSmcSnapshot>): Boolean {
        val htf = mtfSnapshots.filter { it.timeframe == CandleTimeframe.D1 || it.timeframe == CandleTimeframe.H4 }
        if (htf.isEmpty()) return false
        val opposingCount = htf.count { snap ->
            if (isLong) snap.trend == MarketTrendState.BEARISH_DOWNTREND
            else snap.trend == MarketTrendState.BULLISH_UPTREND
        }
        val agreeingCount = htf.count { snap ->
            if (isLong) snap.trend == MarketTrendState.BULLISH_UPTREND
            else snap.trend == MarketTrendState.BEARISH_DOWNTREND
        }
        return opposingCount > agreeingCount && opposingCount >= 1
    }

    private fun hasInvalidFuturesNumbers(f: FuturesLiquiditySnapshot): Boolean {
        val nums = listOfNotNull(
            f.openInterestContracts,
            f.fundingRatePercent,
            f.longShortAccountRatio,
            f.takerCvd,
            f.lastBarVolumeDelta
        )
        if (nums.any { !it.isFinite() }) return true
        if (f.openInterestContracts != null && f.openInterestContracts < 0.0) return true
        if (f.longShortAccountRatio != null && f.longShortAccountRatio <= 0.0) return true
        if (f.fundingRatePercent != null && abs(f.fundingRatePercent) > 5.0) return true
        return false
    }

    private fun computeNetRiskReward(
        isLong: Boolean,
        currentPrice: Double,
        atr: Double,
        smc: SmcAnalysisResult,
        roundTripCostPct: Double,
        proposedEntry: Double?,
        proposedSl: Double?,
        proposedTp: Double?
    ): NetRiskRewardSummary {
        val entry = proposedEntry?.takeIf { it.isFinite() && it > 0.0 } ?: currentPrice

        val structuralSl = proposedSl?.takeIf { it.isFinite() && it > 0.0 } ?: run {
            if (isLong) {
                val obLow = smc.orderBlocks.firstOrNull {
                    it.type == OrderBlockType.BULLISH && it.status != OrderBlockStatus.INVALIDATED && it.bottomPrice < entry
                }?.bottomPrice
                val supLow = smc.priceZones.firstOrNull {
                    (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND) && it.lowerPrice < entry
                }?.lowerPrice
                val candidate = obLow ?: supLow ?: (entry - atr * 1.2)
                val dist = (entry - candidate).coerceIn(entry * 0.005, entry * 0.045)
                entry - dist
            } else {
                val obHigh = smc.orderBlocks.firstOrNull {
                    it.type == OrderBlockType.BEARISH && it.status != OrderBlockStatus.INVALIDATED && it.topPrice > entry
                }?.topPrice
                val resHigh = smc.priceZones.firstOrNull {
                    (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY) && it.upperPrice > entry
                }?.upperPrice
                val candidate = obHigh ?: resHigh ?: (entry + atr * 1.2)
                val dist = (candidate - entry).coerceIn(entry * 0.005, entry * 0.045)
                entry + dist
            }
        }

        val slDist = abs(entry - structuralSl).coerceAtLeast(1e-9)

        val structuralTarget = proposedTp?.takeIf { it.isFinite() && it > 0.0 } ?: run {
            val fibExts = smc.fibonacci?.extensionLevels.orEmpty()
                .filter { if (isLong) it.price > entry + slDist * 2.0 else it.price < entry - slDist * 2.0 }
                .map { it.price }
            val ext1618 = if (isLong) fibExts.maxOrNull() else fibExts.minOrNull()
            val liqTarget = if (isLong) {
                smc.liquidity.bslNearestPrice?.takeIf { it > entry + slDist * 2.5 }
            } else {
                smc.liquidity.sslNearestPrice?.takeIf { it < entry - slDist * 2.5 }
            }
            val structuralCandidate = listOfNotNull(ext1618, liqTarget).let { list ->
                if (isLong) list.maxOrNull() else list.minOrNull()
            }
            structuralCandidate ?: if (isLong) entry + slDist * 4.2 else entry - slDist * 4.2
        }

        val rewardDist = if (isLong) (structuralTarget - entry) else (entry - structuralTarget)
        val riskPct = (slDist / entry) * 100.0
        val grossRewardPct = (max(0.0, rewardDist) / entry) * 100.0
        val grossRR = if (riskPct > 0.0) grossRewardPct / riskPct else 0.0

        val netRewardPct = (grossRewardPct - roundTripCostPct).coerceAtLeast(0.0)
        val netRiskPct = riskPct + roundTripCostPct
        val netRR = if (netRiskPct > 0.0) netRewardPct / netRiskPct else 0.0

        return NetRiskRewardSummary(
            entryPrice = entry,
            stopLoss = structuralSl,
            targetPrice = structuralTarget,
            grossRiskReward = grossRR,
            roundTripFeeSlippagePercent = roundTripCostPct,
            netRiskReward = netRR,
            meetsMinOneToFour = netRR >= MIN_NET_RR,
            calculationSummary = "Gross 1:${String.format(Locale.US, "%.2f", grossRR)} -> Net 1:${String.format(Locale.US, "%.2f", netRR)} after ${String.format(Locale.US, "%.2f", roundTripCostPct)}% cost"
        )
    }

    private suspend fun buildMtfSnapshots(
        symbol: String,
        assetName: String,
        existingSnapshots: List<TimeframeSmcSnapshot>,
        c15m: List<Candle>,
        c1h: List<Candle>,
        c4h: List<Candle>,
        c1d: List<Candle>
    ): List<TimeframeSmcSnapshot> {
        val byTf = existingSnapshots.associateBy { it.timeframe }.toMutableMap()
        suspend fun addIfMissing(tf: CandleTimeframe, list: List<Candle>) {
            if (tf !in byTf && list.size >= 15) {
                val res = SmcCalculationEngine.analyze(symbol, assetName, tf, list)
                byTf[tf] = TimeframeSmcSnapshot(
                    timeframe = tf,
                    trend = res.marketStructure.trendState,
                    lastBreakType = res.marketStructure.latestBreak?.type,
                    unmitigatedOBCount = res.orderBlocks.count { it.status == OrderBlockStatus.UNMITIGATED },
                    unfilledFvgCount = res.fairValueGaps.count { it.status.name != "FULLY_FILLED" },
                    nearestSupport = res.priceZones.firstOrNull { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }?.centralPrice,
                    nearestResistance = res.priceZones.firstOrNull { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }?.centralPrice,
                    isBullishBias = res.marketStructure.trendState == MarketTrendState.BULLISH_UPTREND ||
                        res.marketStructure.isBullishBOSPresent || res.marketStructure.isBullishCHoCHPresent
                )
            }
        }
        addIfMissing(CandleTimeframe.D1, c1d)
        addIfMissing(CandleTimeframe.H4, c4h)
        addIfMissing(CandleTimeframe.H1, c1h)
        addIfMissing(CandleTimeframe.M15, c15m)
        return byTf.values.toList()
    }

    private fun buildEarlyNoTradeReport(
        symbol: String,
        category: AssetCategory,
        isCryptoFutures: Boolean,
        futuresLiquidity: FuturesLiquiditySnapshot?,
        failureReason: String,
        referenceTimeMs: Long
    ): StrictSignalQualityReport {
        val emptyGroups = EvidenceGroupId.entries.map { id ->
            EvidenceGroupScore(
                groupId = id,
                awardedPoints = 0,
                status = IndicatorValidationStatus.UNAVAILABLE,
                directionalStance = "UNAVAILABLE",
                summary = "Unavailable — $failureReason"
            )
        }
        val filters = listOf(
            MandatoryFilterCheck("F1_RELIABLE_FRESH_DATA", 1, "Reliable Fresh Market Data", false, failureReason),
            MandatoryFilterCheck("F2_DIRECTIONAL_STRUCTURE", 2, "Clear Directional Structure / Valid Sweep", false, "Blocked by missing data"),
            MandatoryFilterCheck("F3_SR_AND_PRICE_ACTION", 3, "Confirmed S/R + Price Action Context", false, "Blocked by missing data"),
            MandatoryFilterCheck("F4_CONFIRMED_15M_TRIGGER", 4, "Confirmed 15M Candle-Close Trigger", false, "Blocked by missing data"),
            MandatoryFilterCheck("F5_NO_STRONG_HTF_CONTRADICTION", 5, "No Contradictory HTF Evidence", false, "Blocked by missing data"),
            MandatoryFilterCheck("F6_REALISTIC_SL_TARGET", 6, "Realistic Stop Loss, Target & Volatility", false, "Blocked by missing data"),
            MandatoryFilterCheck("F7_MIN_NET_RR_1_4", 7, "Minimum Net R:R >= 1:4.0 After Costs", false, "Blocked by missing data"),
            MandatoryFilterCheck(
                "F8_FUTURES_DATA_INTEGRITY",
                8,
                "Futures Data Freshness & Integrity",
                !isCryptoFutures || futuresLiquidity?.status != FuturesTelemetryStatus.STALE,
                failureReason
            )
        )
        return StrictSignalQualityReport(
            symbol = symbol,
            assetCategory = category,
            candidateDirection = SignalDecision.NO_TRADE,
            qualificationTier = StrictQualificationTier.NO_TRADE,
            totalScore = 0,
            rawDirectionalScore = 0,
            evidenceGroups = emptyGroups,
            mandatoryFilters = filters,
            missingConfirmations = listOf(failureReason),
            volatilityRegime = VolatilityRegime.NORMAL,
            netRiskRewardSummary = null,
            audit = CorrelatedIndicatorAudit(
                emaSupertrendClusterCapped = true,
                rsiStochRsiMerged = true,
                obvMfiCmfMerged = true,
                adxStrengthOnlyEnforced = true,
                volumeAvailable = false,
                futuresTelemetryStatus = futuresLiquidity?.status?.name ?: "UNAVAILABLE"
            ),
            timestampMs = referenceTimeMs,
            timestampPkt = TimeZoneUtils.formatKarachiTimestamp(referenceTimeMs)
        )
    }
}
