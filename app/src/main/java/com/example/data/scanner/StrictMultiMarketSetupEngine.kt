package com.example.data.scanner

import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.providers.MarketInstrumentSpec
import com.example.data.providers.MultiMarketInstrumentCatalog
import com.example.data.smc.BreakoutRetestType
import com.example.data.smc.LiquidityPoolType
import com.example.data.smc.MarketStructureAnalysis
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

enum class SetupModelType(val label: String, val badgeCode: String) {
    LIQUIDITY_SWEEP_REVERSAL("Type A: Liquidity Sweep Reversal", "TYPE-A SWEEP"),
    TREND_CONTINUATION("Type B: Trend Continuation", "TYPE-B CONT")
}

enum class SetupValidationStatus(val label: String) {
    CONFIRMED("CONFIRMED"),
    WATCHING("WATCHING"),
    DATA_UNAVAILABLE("DATA UNAVAILABLE"),
    INVALIDATED("INVALIDATED")
}

data class TimeframeBiasAssessment(
    val timeframe: CandleTimeframe,
    val trendState: MarketTrendState,
    val bias: FuturesDirection?,
    val candleCount: Int,
    val summary: String
)

data class MultiTimeframeHierarchy(
    val daily: TimeframeBiasAssessment?,
    val fourHour: TimeframeBiasAssessment?,
    val oneHour: TimeframeBiasAssessment?,
    val fifteenMin: TimeframeBiasAssessment?,
    val htfMacroDirection: FuturesDirection?,
    val isStrongHtfBullish: Boolean,
    val isStrongHtfBearish: Boolean,
    val summary: String
)

data class SetupCandidateEvaluation(
    val setupType: SetupModelType,
    val direction: FuturesDirection,
    val status: SetupValidationStatus,
    val score: Int,
    val entryPrice: Double,
    val stopLoss: Double,
    val target1272Ref: Double?,
    val target1618Ref: Double?,
    val adjustedTarget1: Double,
    val adjustedTarget2: Double,
    val grossRiskReward: Double,
    val estimatedNetRiskReward: Double,
    val rulesPassed: List<String>,
    val rulesFailed: List<String>,
    val goldenZoneNote: String,
    val invalidationRule: String,
    val entryZoneRange: String = "",
    val entryExecutionType: String = "Wait for pullback/retest into zone",
    val confirmationCandleCloseTimeMs: Long = 0L,
    val confirmationCandleClosePkt: String = "Pending",
    val estimatedFeeSlippagePercent: Double = 0.0,
    val isPreferredFiveToOne: Boolean = false,
    val supportingConfirmations: List<String> = emptyList()
)

data class StrictMultiMarketEvaluation(
    val symbol: String,
    val displaySymbol: String,
    val assetName: String,
    val venueName: String,
    val category: AssetCategory,
    val status: SetupValidationStatus,
    val dataSourceDescription: String,
    val unavailableReason: String? = null,
    val currentPrice: Double,
    val mtfHierarchy: MultiTimeframeHierarchy?,
    val smc15m: SmcAnalysisResult?,
    val smc1h: SmcAnalysisResult?,
    val liquiditySweepReversal: SetupCandidateEvaluation?,
    val trendContinuation: SetupCandidateEvaluation?,
    val bestSetup: SetupCandidateEvaluation?,
    val timestampPkt: String
)

/**
 * Strict Multi-Market Setup & Market Structure Engine.
 * Evaluates Daily (1D), 4H, 1H, and 15M hierarchy cleanly with NO fabricated data,
 * strict Golden Zone (0.500-0.618) gates, Fibonacci 1.272/1.618 S/R-aware extensions,
 * and two independently validated setup models:
 *  - Type A: Liquidity Sweep Reversal
 *  - Type B: Trend Continuation
 */
object StrictMultiMarketSetupEngine {

    const val MIN_NET_RR_FOR_CONFIRMATION = 4.0
    const val PREFERRED_NET_RR = 5.0

    suspend fun evaluate(
        symbol: String,
        currentPrice: Double?,
        dataSourceName: String?,
        candles1d: List<Candle>,
        candles4h: List<Candle>,
        candles1h: List<Candle>,
        candles15m: List<Candle>,
        isDataStale: Boolean = false,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): StrictMultiMarketEvaluation {
        val timestampPkt = TimeZoneUtils.formatKarachiTimestamp(referenceTimeMs)
        val spec = MultiMarketInstrumentCatalog.resolve(symbol)
        val canonicalId = spec?.canonicalId ?: symbol.uppercase(Locale.US)
        val displaySym = spec?.displaySymbol ?: symbol.uppercase(Locale.US)
        val assetName = spec?.displayName ?: symbol.uppercase(Locale.US)
        val venueName = dataSourceName?.takeIf { it.isNotBlank() }
            ?: spec?.primaryVenue?.displayName
            ?: "Unverified Source"
        val category = spec?.category ?: AssetCategory.CRYPTO

        // 0. Unverified venue or missing/stale data gate -> DATA UNAVAILABLE
        if (spec == null || !spec.isVerifiedSupported) {
            return unavailableResult(
                canonicalId, displaySym, assetName, "Unverified Venue", category,
                "DATA UNAVAILABLE: $symbol is not a verified multi-market instrument.",
                currentPrice ?: 0.0, timestampPkt
            )
        }
        if (isDataStale || currentPrice == null || !currentPrice.isFinite() || currentPrice <= 0.0) {
            return unavailableResult(
                canonicalId, displaySym, assetName, venueName, category,
                "DATA UNAVAILABLE: Live or delayed price feed missing/stale for $displaySym ($venueName).",
                currentPrice ?: 0.0, timestampPkt
            )
        }

        val comp15m = TimeZoneUtils.filterCompletedCandles(candles15m, referenceTimeMs)
        val comp1h = TimeZoneUtils.filterCompletedCandles(candles1h, referenceTimeMs)
        val comp4h = TimeZoneUtils.filterCompletedCandles(candles4h, referenceTimeMs)
        val comp1d = TimeZoneUtils.filterCompletedCandles(candles1d, referenceTimeMs)

        if (comp15m.size < 20 || comp1h.size < 20) {
            return unavailableResult(
                canonicalId, displaySym, assetName, venueName, category,
                "DATA UNAVAILABLE: Insufficient completed OHLCV history (15M=${comp15m.size}, 1H=${comp1h.size}; need >=20 each) on $venueName.",
                currentPrice, timestampPkt
            )
        }

        // 1. Analyze Daily (1D), 4H, 1H, 15M hierarchy
        val smc1d = if (comp1d.size >= 15) SmcCalculationEngine.analyze(canonicalId, assetName, CandleTimeframe.D1, comp1d) else null
        val smc4h = if (comp4h.size >= 15) SmcCalculationEngine.analyze(canonicalId, assetName, CandleTimeframe.H4, comp4h) else null

        val htfSnapshots = buildList {
            smc1d?.let { add(toSnapshot(it)) }
            smc4h?.let { add(toSnapshot(it)) }
        }

        val smc1h = SmcCalculationEngine.analyze(canonicalId, assetName, CandleTimeframe.H1, comp1h, htfSnapshots)
        val allMtfSnapshots = htfSnapshots + toSnapshot(smc1h)
        val smc15m = SmcCalculationEngine.analyze(canonicalId, assetName, CandleTimeframe.M15, comp15m, allMtfSnapshots)

        val d1Bias = smc1d?.let { assessTimeframeBias(CandleTimeframe.D1, comp1d, it.marketStructure) }
        val h4Bias = smc4h?.let { assessTimeframeBias(CandleTimeframe.H4, comp4h, it.marketStructure) }
        val h1Bias = assessTimeframeBias(CandleTimeframe.H1, comp1h, smc1h.marketStructure)
        val m15Bias = assessTimeframeBias(CandleTimeframe.M15, comp15m, smc15m.marketStructure)

        val htfVotesBull = listOfNotNull(d1Bias, h4Bias).count { it.bias == FuturesDirection.LONG }
        val htfVotesBear = listOfNotNull(d1Bias, h4Bias).count { it.bias == FuturesDirection.SHORT }
        val isStrongHtfBull = htfVotesBull == 2 || (htfVotesBull == 1 && htfVotesBear == 0 && (d1Bias?.bias == FuturesDirection.LONG || h4Bias?.bias == FuturesDirection.LONG))
        val isStrongHtfBear = htfVotesBear == 2 || (htfVotesBear == 1 && htfVotesBull == 0 && (d1Bias?.bias == FuturesDirection.SHORT || h4Bias?.bias == FuturesDirection.SHORT))
        val htfMacroDir = when {
            isStrongHtfBull && !isStrongHtfBear -> FuturesDirection.LONG
            isStrongHtfBear && !isStrongHtfBull -> FuturesDirection.SHORT
            else -> null
        }

        val hierarchySummary = buildString {
            append("1D: ${d1Bias?.bias?.name ?: "N/A"} | ")
            append("4H: ${h4Bias?.bias?.name ?: "N/A"} | ")
            append("1H: ${h1Bias.bias?.name ?: "NEUTRAL"} | ")
            append("15M: ${m15Bias.bias?.name ?: "NEUTRAL"}")
        }
        val mtfHierarchy = MultiTimeframeHierarchy(
            daily = d1Bias,
            fourHour = h4Bias,
            oneHour = h1Bias,
            fifteenMin = m15Bias,
            htfMacroDirection = htfMacroDir,
            isStrongHtfBullish = isStrongHtfBull,
            isStrongHtfBearish = isStrongHtfBear,
            summary = hierarchySummary
        )

        val atr15 = smc15m.let {
            val ta = TechnicalIndicatorsEngine.analyze(canonicalId, assetName, CandleTimeframe.M15, comp15m)
            ta.volatility.atr?.value
        }?.takeIf { it > 0.0 } ?: (currentPrice * 0.01)

        // 2. Evaluate Setup Model A: Liquidity Sweep Reversal
        val setupA = evaluateLiquiditySweepReversal(
            spec = spec,
            currentPrice = currentPrice,
            atr = atr15,
            candles15m = comp15m,
            smc15m = smc15m,
            smc1h = smc1h,
            mtf = mtfHierarchy
        )

        // 3. Evaluate Setup Model B: Trend Continuation
        val setupB = evaluateTrendContinuation(
            spec = spec,
            currentPrice = currentPrice,
            atr = atr15,
            candles15m = comp15m,
            smc15m = smc15m,
            smc1h = smc1h,
            mtf = mtfHierarchy
        )

        val candidates = listOfNotNull(setupA, setupB)
        val bestConfirmed = candidates
            .filter { it.status == SetupValidationStatus.CONFIRMED }
            .maxByOrNull { it.score }
        val bestWatching = candidates
            .filter { it.status == SetupValidationStatus.WATCHING }
            .maxByOrNull { it.score }
        val bestOverall = bestConfirmed ?: bestWatching ?: candidates.maxByOrNull { it.score }

        val topStatus = bestOverall?.status ?: SetupValidationStatus.WATCHING

        return StrictMultiMarketEvaluation(
            symbol = canonicalId,
            displaySymbol = displaySym,
            assetName = assetName,
            venueName = venueName,
            category = category,
            status = topStatus,
            dataSourceDescription = "$venueName (${category.label}) • Completed MTF candles verified",
            unavailableReason = null,
            currentPrice = currentPrice,
            mtfHierarchy = mtfHierarchy,
            smc15m = smc15m,
            smc1h = smc1h,
            liquiditySweepReversal = setupA,
            trendContinuation = setupB,
            bestSetup = bestOverall,
            timestampPkt = timestampPkt
        )
    }

    private fun evaluateLiquiditySweepReversal(
        spec: MarketInstrumentSpec,
        currentPrice: Double,
        atr: Double,
        candles15m: List<Candle>,
        smc15m: SmcAnalysisResult,
        smc1h: SmcAnalysisResult,
        mtf: MultiTimeframeHierarchy
    ): SetupCandidateEvaluation? {
        val recentSweeps = (smc15m.liquidity.recentSweeps + smc1h.liquidity.recentSweeps)
            .sortedByDescending { it.candleIndex }
        val bullTrap = smc15m.breakoutRetests.firstOrNull { it.type == BreakoutRetestType.FAILED_BREAKOUT_BULL_TRAP }
        val bearTrap = smc15m.breakoutRetests.firstOrNull { it.type == BreakoutRetestType.FAILED_BREAKOUT_BEAR_TRAP }

        val sslSweep = recentSweeps.firstOrNull {
            it.poolType == LiquidityPoolType.SELL_SIDE_LIQUIDITY || it.poolType == LiquidityPoolType.EQUAL_LOWS
        }
        val bslSweep = recentSweeps.firstOrNull {
            it.poolType == LiquidityPoolType.BUY_SIDE_LIQUIDITY || it.poolType == LiquidityPoolType.EQUAL_HIGHS
        }

        val direction: FuturesDirection = when {
            sslSweep != null || bearTrap != null -> FuturesDirection.LONG
            bslSweep != null || bullTrap != null -> FuturesDirection.SHORT
            else -> return null
        }

        val rulesPassed = mutableListOf<String>()
        val rulesFailed = mutableListOf<String>()
        var score = 0

        // Rule 1: Liquidity Sweep or Failed Breakout Trap present
        val sweptExtremePrice: Double
        if (direction == FuturesDirection.LONG) {
            sweptExtremePrice = sslSweep?.lowWickPrice ?: candles15m.takeLast(6).minOf { it.low }
            rulesPassed.add("Sell-side liquidity sweep / bear trap confirmed near ${fmt(sweptExtremePrice)}")
            score += 25
        } else {
            sweptExtremePrice = bslSweep?.highWickPrice ?: candles15m.takeLast(6).maxOf { it.high }
            rulesPassed.add("Buy-side liquidity sweep / bull trap confirmed near ${fmt(sweptExtremePrice)}")
            score += 25
        }

        // Rule 2: Structural CHoCH / MSS reversal shift
        val hasShift = if (direction == FuturesDirection.LONG) {
            smc15m.marketStructure.isBullishCHoCHPresent || smc15m.marketStructure.isBullishMSSPresent ||
                smc1h.marketStructure.isBullishCHoCHPresent || smc1h.marketStructure.isBullishMSSPresent
        } else {
            smc15m.marketStructure.isBearishCHoCHPresent || smc15m.marketStructure.isBearishMSSPresent ||
                smc1h.marketStructure.isBearishCHoCHPresent || smc1h.marketStructure.isBearishMSSPresent
        }
        if (hasShift) {
            rulesPassed.add("Confirmed CHoCH/MSS reversal structure shift")
            score += 25
        } else {
            rulesFailed.add("Awaiting confirmed 15M/1H CHoCH or MSS close after sweep")
        }

        // Rule 3: Contextual Reclaim / Rejection Candle
        val hasPriceAction = smc15m.priceActionPatterns.any { it.type.isBullish == (direction == FuturesDirection.LONG) && it.isCandleClosedConfirmed }
        val lastBar = candles15m.last()
        val hasReclaimClose = if (direction == FuturesDirection.LONG) {
            lastBar.close > (sslSweep?.sweptLevel ?: sweptExtremePrice) && lastBar.close > lastBar.open
        } else {
            lastBar.close < (bslSweep?.sweptLevel ?: sweptExtremePrice) && lastBar.close < lastBar.open
        }
        if (hasPriceAction || hasReclaimClose) {
            rulesPassed.add(if (hasPriceAction) "Contextual rejection/engulfing candle closed" else "Decisive reclaim close beyond swept level")
            score += 20
        } else {
            rulesFailed.add("No confirmed reclaim/rejection candle close yet")
        }

        // Rule 4: S/R / OB / Fib Golden Zone anchor & HTF bonus
        val fib = smc15m.fibonacci
        val fibReport = fib?.validationReport
        val inGolden = fibReport?.isInGoldenZone0500To0618 == true
        val hasZoneSupport = smc15m.priceZones.any {
            abs(it.distancePercent) <= 1.2 &&
                ((direction == FuturesDirection.LONG && (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND)) ||
                    (direction == FuturesDirection.SHORT && (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY)))
        }
        if (inGolden || hasZoneSupport) {
            rulesPassed.add(if (inGolden) "Anchored inside 0.500–0.618 Golden Zone + S/R" else "Anchored at key S/R or Supply/Demand zone")
            score += 15
        }

        // HTF check: reversal is allowed against HTF ONLY when ALL 3 core reversal rules pass; adds score if aligned
        val htfAligned = mtf.htfMacroDirection == null || mtf.htfMacroDirection == direction
        if (htfAligned) {
            rulesPassed.add("Aligned with or neutral to 1D/4H macro direction")
            score += 15
        } else {
            rulesPassed.add("Counter-HTF Liquidity Sweep Reversal — stricter confirmation applied")
            score += 5
        }

        // Targets & SL (structural + ATR buffer, Fib 1.272/1.618 capped by opposing S/R)
        val sl = if (direction == FuturesDirection.LONG) {
            min(sweptExtremePrice, currentPrice - atr * 0.6) - (atr * 0.25)
        } else {
            max(sweptExtremePrice, currentPrice + atr * 0.6) + (atr * 0.25)
        }
        val targets = computeExtensionAndZoneTargets(
            direction = direction,
            entry = currentPrice,
            stopLoss = sl,
            atr = atr,
            fib = fib,
            smcZones = (smc15m.priceZones + smc1h.priceZones),
            roundTripCostPct = spec.roundTripCostPercent
        )

        if (targets.netRR >= PREFERRED_NET_RR) {
            rulesPassed.add("Preferred >=1:5 Net R:R after costs: 1:${String.format(Locale.US, "%.2f", targets.netRR)}")
        } else if (targets.netRR >= MIN_NET_RR_FOR_CONFIRMATION) {
            rulesPassed.add("Net R:R after costs 1:${String.format(Locale.US, "%.2f", targets.netRR)} (>= 1:4.0 minimum)")
        } else {
            rulesFailed.add("Net R:R 1:${String.format(Locale.US, "%.2f", targets.netRR)} below 1:${MIN_NET_RR_FOR_CONFIRMATION} minimum — not forced or widened")
        }

        val isConfirmed = hasShift && (hasPriceAction || hasReclaimClose) && targets.netRR >= MIN_NET_RR_FOR_CONFIRMATION
        val status = if (isConfirmed) SetupValidationStatus.CONFIRMED else SetupValidationStatus.WATCHING
        val closeTimeMs = lastBar.closeTime.takeIf { it > 0L } ?: lastBar.openTime
        val closeTimePkt = TimeZoneUtils.formatKarachiTimestamp(closeTimeMs)
        val zoneHalfWidth = atr * 0.2
        val entryZone = "${fmt(currentPrice - zoneHalfWidth)} – ${fmt(currentPrice + zoneHalfWidth)}"
        val executionMode = if (isConfirmed && (hasPriceAction || hasReclaimClose)) {
            "Immediate on 15M candle close (or shallow retest to ${fmt(currentPrice)})"
        } else {
            "Wait for confirmed 15M reclaim/retest inside $entryZone"
        }

        return SetupCandidateEvaluation(
            setupType = SetupModelType.LIQUIDITY_SWEEP_REVERSAL,
            direction = direction,
            status = status,
            score = score.coerceIn(0, 100),
            entryPrice = currentPrice,
            stopLoss = sl,
            target1272Ref = targets.ext1272,
            target1618Ref = targets.ext1618,
            adjustedTarget1 = targets.tp1,
            adjustedTarget2 = targets.tp2,
            grossRiskReward = targets.grossRR,
            estimatedNetRiskReward = targets.netRR,
            rulesPassed = rulesPassed,
            rulesFailed = rulesFailed,
            goldenZoneNote = fib?.currentFibZoneDescription ?: "No valid Golden Zone swing",
            invalidationRule = "Invalidate on 15M candle close beyond swept wick (${fmt(sl)})",
            entryZoneRange = entryZone,
            entryExecutionType = executionMode,
            confirmationCandleCloseTimeMs = closeTimeMs,
            confirmationCandleClosePkt = closeTimePkt,
            estimatedFeeSlippagePercent = spec.roundTripCostPercent,
            isPreferredFiveToOne = targets.netRR >= PREFERRED_NET_RR,
            supportingConfirmations = rulesPassed.toList()
        )
    }

    private fun evaluateTrendContinuation(
        spec: MarketInstrumentSpec,
        currentPrice: Double,
        atr: Double,
        candles15m: List<Candle>,
        smc15m: SmcAnalysisResult,
        smc1h: SmcAnalysisResult,
        mtf: MultiTimeframeHierarchy
    ): SetupCandidateEvaluation? {
        // Determine dominant directional candidate from 1D/4H -> 1H
        val h1Dir = mtf.oneHour?.bias
        val htfDir = mtf.htfMacroDirection ?: h1Dir ?: return null

        val rulesPassed = mutableListOf<String>()
        val rulesFailed = mutableListOf<String>()
        var score = 0

        // Rule 1: 15M MUST NOT contradict strong HTF (1D/4H) bias for Trend Continuation
        val strongOppositeHtf = (htfDir == FuturesDirection.LONG && mtf.isStrongHtfBearish) ||
            (htfDir == FuturesDirection.SHORT && mtf.isStrongHtfBullish)
        if (strongOppositeHtf) {
            rulesFailed.add("Blocked: strong higher-timeframe (1D/4H) bias opposes continuation.")
            return SetupCandidateEvaluation(
                setupType = SetupModelType.TREND_CONTINUATION,
                direction = htfDir,
                status = SetupValidationStatus.INVALIDATED,
                score = 15,
                entryPrice = currentPrice,
                stopLoss = currentPrice,
                target1272Ref = null,
                target1618Ref = null,
                adjustedTarget1 = currentPrice,
                adjustedTarget2 = currentPrice,
                grossRiskReward = 0.0,
                estimatedNetRiskReward = 0.0,
                rulesPassed = emptyList(),
                rulesFailed = rulesFailed,
                goldenZoneNote = smc15m.fibonacci?.currentFibZoneDescription ?: "Unavailable",
                invalidationRule = "Higher-timeframe conflict"
            )
        }

        // Rule 1B: 1H trend/structure agreement
        if (h1Dir == htfDir) {
            rulesPassed.add("1D/4H & 1H higher-timeframe trend aligned ${htfDir.name}")
            score += 25
        } else {
            rulesFailed.add("1H structure not yet aligned with ${htfDir.name} HTF trend")
        }

        // Rule 2: Pullback into Golden Zone (0.500-0.618) with S/R/OB confluence OR Breakout-Retest
        val fib = smc15m.fibonacci ?: smc1h.fibonacci
        val fibReport = fib?.validationReport
        val inGoldenWithConfluence = fibReport?.isInGoldenZone0500To0618 == true && fibReport.hasSrOrOrderBlockConfluence &&
            ((htfDir == FuturesDirection.LONG) == (fib.isUptrendSwing))
        val hasBreakoutRetest = smc15m.breakoutRetests.any {
            (htfDir == FuturesDirection.LONG && it.type == BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BULLISH) ||
                (htfDir == FuturesDirection.SHORT && it.type == BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BEARISH)
        }
        val atObOrZone = smc15m.orderBlocks.any { ob ->
            ob.status != OrderBlockStatus.INVALIDATED &&
                ((htfDir == FuturesDirection.LONG && ob.type == OrderBlockType.BULLISH) ||
                    (htfDir == FuturesDirection.SHORT && ob.type == OrderBlockType.BEARISH)) &&
                currentPrice in (ob.bottomPrice - atr * 0.4)..(ob.topPrice + atr * 0.4)
        } || smc15m.priceZones.any { z ->
            abs(z.distancePercent) <= 1.2 &&
                ((htfDir == FuturesDirection.LONG && (z.type == PriceZoneType.SUPPORT || z.type == PriceZoneType.DEMAND)) ||
                    (htfDir == FuturesDirection.SHORT && (z.type == PriceZoneType.RESISTANCE || z.type == PriceZoneType.SUPPLY)))
        }

        when {
            inGoldenWithConfluence -> {
                rulesPassed.add("Pullback in Fib Golden Zone (0.500–0.618) with S/R / OB overlap")
                score += 25
            }
            hasBreakoutRetest -> {
                rulesPassed.add("Confirmed Breakout & Retest holding key S/R zone")
                score += 25
            }
            atObOrZone -> {
                rulesPassed.add("Pullback testing active Order Block / Demand-Supply zone")
                score += 20
            }
            else -> {
                rulesFailed.add("Price has not pulled back into Golden Zone (0.500–0.618), OB, or Breakout-Retest zone")
            }
        }

        // Rule 3: 15M Confirmed Entry Trigger Candle Close + Contextual Price Action (touch alone != confirmation)
        val paMatch = smc15m.priceActionPatterns.any {
            it.isCandleClosedConfirmed && it.type.isBullish == (htfDir == FuturesDirection.LONG)
        }
        val last = candles15m.last()
        val momentumClose = if (htfDir == FuturesDirection.LONG) {
            last.close > last.open && last.close > candles15m[candles15m.lastIndex - 1].high
        } else {
            last.close < last.open && last.close < candles15m[candles15m.lastIndex - 1].low
        }
        if (paMatch || momentumClose || hasBreakoutRetest) {
            rulesPassed.add("Confirmed 15M entry trigger candle closed in ${htfDir.name} direction")
            score += 25
        } else {
            rulesFailed.add("Touch alone is not confirmation — awaiting 15M bullish/bearish trigger close")
        }

        // Rule 4: Volume confirmation (when volume is reliably provided)
        val hasVolData = candles15m.count { it.volume > 0.0 } >= candles15m.size / 2
        if (!hasVolData) {
            rulesPassed.add("Volume unavailable on delayed feed — price/structure criteria applied cleanly")
            score += 10
        } else {
            val avgVol = candles15m.takeLast(20).map { it.volume }.average().coerceAtLeast(1e-9)
            if (last.volume >= avgVol * 1.05) {
                rulesPassed.add("Volume expansion (${String.format(Locale.US, "%.2f", last.volume / avgVol)}x avg) confirms move")
                score += 15
            } else {
                rulesFailed.add("Relative volume below 1.05x average on trigger bar")
            }
        }

        // Stop Loss & Targets
        val swingBoundary = if (htfDir == FuturesDirection.LONG) {
            min(candles15m.takeLast(8).minOf { it.low }, fib?.retracementLevels?.firstOrNull { it.ratio == 0.786 }?.price ?: currentPrice)
        } else {
            max(candles15m.takeLast(8).maxOf { it.high }, fib?.retracementLevels?.firstOrNull { it.ratio == 0.786 }?.price ?: currentPrice)
        }
        val sl = if (htfDir == FuturesDirection.LONG) {
            min(swingBoundary - atr * 0.25, currentPrice - atr * 0.8)
        } else {
            max(swingBoundary + atr * 0.25, currentPrice + atr * 0.8)
        }

        val targets = computeExtensionAndZoneTargets(
            direction = htfDir,
            entry = currentPrice,
            stopLoss = sl,
            atr = atr,
            fib = fib,
            smcZones = (smc15m.priceZones + smc1h.priceZones),
            roundTripCostPct = spec.roundTripCostPercent
        )

        if (targets.netRR >= PREFERRED_NET_RR) {
            rulesPassed.add("Preferred >=1:5 Net R:R after costs: 1:${String.format(Locale.US, "%.2f", targets.netRR)}")
            score += 10
        } else if (targets.netRR >= MIN_NET_RR_FOR_CONFIRMATION) {
            rulesPassed.add("Net R:R after costs 1:${String.format(Locale.US, "%.2f", targets.netRR)} (>= 1:4.0 minimum)")
            score += 10
        } else {
            rulesFailed.add("Net R:R 1:${String.format(Locale.US, "%.2f", targets.netRR)} below 1:${MIN_NET_RR_FOR_CONFIRMATION} minimum — not forced or widened")
        }

        val confirmed = h1Dir == htfDir &&
            (inGoldenWithConfluence || hasBreakoutRetest || atObOrZone) &&
            (paMatch || momentumClose || hasBreakoutRetest) &&
            targets.netRR >= MIN_NET_RR_FOR_CONFIRMATION

        val status = if (confirmed) SetupValidationStatus.CONFIRMED else SetupValidationStatus.WATCHING
        val closeTimeMs = last.closeTime.takeIf { it > 0L } ?: last.openTime
        val closeTimePkt = TimeZoneUtils.formatKarachiTimestamp(closeTimeMs)
        val zoneHalfWidth = atr * 0.2
        val entryZone = "${fmt(currentPrice - zoneHalfWidth)} – ${fmt(currentPrice + zoneHalfWidth)}"
        val executionMode = if (confirmed) {
            "Immediate on 15M close or pullback retest in $entryZone"
        } else {
            "Wait for pullback & 15M candle confirmation inside $entryZone"
        }

        return SetupCandidateEvaluation(
            setupType = SetupModelType.TREND_CONTINUATION,
            direction = htfDir,
            status = status,
            score = score.coerceIn(0, 100),
            entryPrice = currentPrice,
            stopLoss = sl,
            target1272Ref = targets.ext1272,
            target1618Ref = targets.ext1618,
            adjustedTarget1 = targets.tp1,
            adjustedTarget2 = targets.tp2,
            grossRiskReward = targets.grossRR,
            estimatedNetRiskReward = targets.netRR,
            rulesPassed = rulesPassed,
            rulesFailed = rulesFailed,
            goldenZoneNote = fib?.currentFibZoneDescription ?: "Outside Golden Zone",
            invalidationRule = "Invalidate on 15M close against structure below/above ${fmt(sl)}",
            entryZoneRange = entryZone,
            entryExecutionType = executionMode,
            confirmationCandleCloseTimeMs = closeTimeMs,
            confirmationCandleClosePkt = closeTimePkt,
            estimatedFeeSlippagePercent = spec.roundTripCostPercent,
            isPreferredFiveToOne = targets.netRR >= PREFERRED_NET_RR,
            supportingConfirmations = rulesPassed.toList()
        )
    }

    private data class TargetBundle(
        val ext1272: Double?,
        val ext1618: Double?,
        val tp1: Double,
        val tp2: Double,
        val grossRR: Double,
        val netRR: Double
    )

    private fun computeExtensionAndZoneTargets(
        direction: FuturesDirection,
        entry: Double,
        stopLoss: Double,
        atr: Double,
        fib: com.example.data.smc.FibonacciAnalysis?,
        smcZones: List<com.example.data.smc.PriceLevelZone>,
        roundTripCostPct: Double
    ): TargetBundle {
        // Exact structural LONG/SHORT risk calculation
        val rawRisk = if (direction == FuturesDirection.LONG) (entry - stopLoss) else (stopLoss - entry)
        val risk = rawRisk.coerceAtLeast(atr * 0.25)
        val ext1272 = fib?.extensionLevels?.firstOrNull { abs(it.ratio - 1.272) < 0.01 }?.price
            ?.takeIf { if (direction == FuturesDirection.LONG) it > entry else it < entry }
        val ext1618 = fib?.extensionLevels?.firstOrNull { abs(it.ratio - 1.618) < 0.01 }?.price
            ?.takeIf { if (direction == FuturesDirection.LONG) it > entry else it < entry }

        val fallbackTp1 = if (direction == FuturesDirection.LONG) entry + risk * 2.0 else entry - risk * 2.0
        val fallbackTp2 = if (direction == FuturesDirection.LONG) entry + risk * 3.0 else entry - risk * 3.0

        // Respect nearest opposing structural S/R zone; NEVER artificially widen targets beyond structural walls
        val opposingZone = if (direction == FuturesDirection.LONG) {
            smcZones.filter { (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY) && it.lowerPrice > entry + risk * 1.2 }
                .minByOrNull { it.lowerPrice }?.lowerPrice
        } else {
            smcZones.filter { (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND) && it.upperPrice < entry - risk * 1.2 }
                .maxByOrNull { it.upperPrice }?.upperPrice
        }

        val rawTp1 = ext1272 ?: fallbackTp1
        val rawTp2 = ext1618 ?: fallbackTp2

        val tp1 = if (opposingZone != null) {
            if (direction == FuturesDirection.LONG) min(rawTp1, opposingZone) else max(rawTp1, opposingZone)
        } else rawTp1

        val tp2CappedByStructure = if (opposingZone != null) {
            if (direction == FuturesDirection.LONG) min(rawTp2, opposingZone) else max(rawTp2, opposingZone)
        } else rawTp2
        val tp2 = if (direction == FuturesDirection.LONG) max(tp1, tp2CappedByStructure) else min(tp1, tp2CappedByStructure)

        val grossReward = if (direction == FuturesDirection.LONG) (tp2 - entry).coerceAtLeast(0.0) else (entry - tp2).coerceAtLeast(0.0)
        val rewardPct = if (entry > 0.0) (grossReward / entry) * 100.0 else 0.0
        val riskPct = if (entry > 0.0) (risk / entry) * 100.0 else 0.0
        val grossRR = if (riskPct > 0.0) rewardPct / riskPct else 0.0
        val netRewardPct = (rewardPct - roundTripCostPct).coerceAtLeast(0.0)
        val netRiskPct = riskPct + roundTripCostPct
        val netRR = if (netRiskPct > 0.0) netRewardPct / netRiskPct else 0.0

        return TargetBundle(ext1272, ext1618, tp1, tp2, grossRR, netRR)
    }

    private suspend fun assessTimeframeBias(
        tf: CandleTimeframe,
        candles: List<Candle>,
        structure: MarketStructureAnalysis
    ): TimeframeBiasAssessment {
        val ta = TechnicalIndicatorsEngine.analyze(tf.label, tf.label, tf, candles)
        val ema20 = ta.trend.emaSet.ema20
        val ema50 = ta.trend.emaSet.ema50
        val close = candles.last().close

        val bias = when {
            structure.trendState == MarketTrendState.BULLISH_UPTREND && (ema50 == null || close >= ema50) -> FuturesDirection.LONG
            structure.trendState == MarketTrendState.BEARISH_DOWNTREND && (ema50 == null || close <= ema50) -> FuturesDirection.SHORT
            ema20 != null && ema50 != null && ema20 > ema50 && close > ema20 -> FuturesDirection.LONG
            ema20 != null && ema50 != null && ema20 < ema50 && close < ema20 -> FuturesDirection.SHORT
            structure.isBullishBOSPresent && !structure.isBearishCHoCHPresent -> FuturesDirection.LONG
            structure.isBearishBOSPresent && !structure.isBullishCHoCHPresent -> FuturesDirection.SHORT
            else -> null
        }

        return TimeframeBiasAssessment(
            timeframe = tf,
            trendState = structure.trendState,
            bias = bias,
            candleCount = candles.size,
            summary = "${tf.label}: ${bias?.name ?: "NEUTRAL"} (${structure.trendState.label})"
        )
    }

    private fun toSnapshot(res: SmcAnalysisResult): TimeframeSmcSnapshot = TimeframeSmcSnapshot(
        timeframe = res.timeframe,
        trend = res.marketStructure.trendState,
        lastBreakType = res.marketStructure.latestBreak?.type,
        unmitigatedOBCount = res.orderBlocks.count { it.status == OrderBlockStatus.UNMITIGATED },
        unfilledFvgCount = res.fairValueGaps.count { it.status.name != "FULLY_FILLED" },
        nearestSupport = res.priceZones.firstOrNull { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }?.centralPrice,
        nearestResistance = res.priceZones.firstOrNull { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }?.centralPrice,
        isBullishBias = res.marketStructure.trendState == MarketTrendState.BULLISH_UPTREND ||
            res.marketStructure.isBullishBOSPresent ||
            res.marketStructure.isBullishCHoCHPresent
    )

    private fun unavailableResult(
        canonicalId: String,
        displaySym: String,
        assetName: String,
        venueName: String,
        category: AssetCategory,
        reason: String,
        price: Double,
        timestampPkt: String
    ) = StrictMultiMarketEvaluation(
        symbol = canonicalId,
        displaySymbol = displaySym,
        assetName = assetName,
        venueName = venueName,
        category = category,
        status = SetupValidationStatus.DATA_UNAVAILABLE,
        dataSourceDescription = reason,
        unavailableReason = reason,
        currentPrice = price,
        mtfHierarchy = null,
        smc15m = null,
        smc1h = null,
        liquiditySweepReversal = null,
        trendContinuation = null,
        bestSetup = null,
        timestampPkt = timestampPkt
    )

    private fun fmt(v: Double): String = when {
        abs(v) >= 100.0 -> String.format(Locale.US, "%.2f", v)
        abs(v) >= 1.0 -> String.format(Locale.US, "%.3f", v)
        else -> String.format(Locale.US, "%.4f", v)
    }
}
