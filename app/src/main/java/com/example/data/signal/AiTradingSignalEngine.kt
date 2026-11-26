package com.example.data.signal

import com.example.data.indicators.IndicatorStance
import com.example.data.indicators.OverallAnalysisResult
import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketDataStatus
import com.example.data.smc.FibLevel
import com.example.data.smc.FibonacciAnalysis
import com.example.data.smc.FvgStatus
import com.example.data.smc.FvgType
import com.example.data.smc.LiquidityPoolType
import com.example.data.smc.MarketTrendState
import com.example.data.smc.OrderBlock
import com.example.data.smc.OrderBlockStatus
import com.example.data.smc.OrderBlockType
import com.example.data.smc.PriceLevelZone
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import com.example.data.smc.SmcCalculationEngine
import com.example.data.smc.TimeframeSmcSnapshot
import com.example.util.TimeZoneUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin Multi-Factor AI Trading Signal Engine.
 * Evaluates real technical indicators, Smart Money Concepts, and Multi-Timeframe confirmation.
 * Yields only 3 strict decisions: LONG, SHORT, or NO TRADE.
 */
object AiTradingSignalEngine {

    suspend fun generateSignal(
        symbol: String,
        assetName: String,
        currentPrice: Double?,
        dataStatus: MarketDataStatus,
        dataSourceName: String,
        lastUpdatedTime: Long?,
        primaryTimeframe: CandleTimeframe,
        candles: List<Candle>,
        technicalAnalysis: OverallAnalysisResult?,
        smcAnalysis: SmcAnalysisResult?,
        mtfSnapshots: List<TimeframeSmcSnapshot> = emptyList(),
        strictQualityReportOverride: StrictSignalQualityReport? = null,
        enforceStrictQualificationGate: Boolean = false,
        strictMinScore: Int = StrictSignalQualityEngine.SCORE_THRESHOLD_HIGH_QUALITY
    ): AiTradingSignal = withContext(Dispatchers.Default) {
        val now = System.currentTimeMillis()
        val formattedTime = TimeZoneUtils.formatKarachiWithSeconds(now)
        val freshnessSeconds = if (lastUpdatedTime != null && lastUpdatedTime > 0) {
            max(0L, (now - lastUpdatedTime) / 1000L)
        } else 0L

        // ----------------------------------------------------
        // 1. DATA SAFETY & VALIDITY CHECKS (NO TRADE TRIGGER)
        // ----------------------------------------------------
        if (currentPrice == null || currentPrice <= 0.0) {
            return@withContext createNoTradeSignal(
                symbol = symbol,
                assetName = assetName,
                primaryTimeframe = primaryTimeframe,
                code = "DATA_UNAVAILABLE",
                headline = "Market Price Data Not Connected",
                explanation = "Current live price for $symbol is unavailable or zero. Trading signals require verified market feeds.",
                conflictingFactors = listOf("Price feed is null or non-positive", "Data status: ${dataStatus.label}"),
                recommendedAction = "Connect a live market API or check network connectivity before analyzing signals.",
                dataSource = dataSourceName,
                freshness = dataStatus.label,
                timestamp = now,
                formattedTime = formattedTime
            )
        }

        if (dataStatus == MarketDataStatus.UNAVAILABLE || dataStatus == MarketDataStatus.STALE) {
            return@withContext createNoTradeSignal(
                symbol = symbol,
                assetName = assetName,
                primaryTimeframe = primaryTimeframe,
                code = "MARKET_DATA_STALE",
                headline = "Market Data is Stale or Unavailable",
                explanation = "The data feed for $symbol is marked as ${dataStatus.label} (age: ${freshnessSeconds}s). Stale prices introduce severe execution slippage.",
                conflictingFactors = listOf("Feed status is ${dataStatus.label}", "Last update was ${freshnessSeconds}s ago"),
                recommendedAction = "Wait for fresh tick updates or tap 'Refresh Data' to re-sync live market stream.",
                dataSource = dataSourceName,
                freshness = dataStatus.label,
                timestamp = now,
                formattedTime = formattedTime
            )
        }

        if (candles.size < 30) {
            return@withContext createNoTradeSignal(
                symbol = symbol,
                assetName = assetName,
                primaryTimeframe = primaryTimeframe,
                code = "INSUFFICIENT_DATA",
                headline = "Insufficient Candle History",
                explanation = "Only ${candles.size} candles available on ${primaryTimeframe.label}. A minimum of 30 historical candles is required for reliable indicator confluence.",
                conflictingFactors = listOf("Available candles: ${candles.size} (Minimum required: 30)", "Long-term EMAs and Multi-period swings cannot be computed"),
                recommendedAction = "Switch to a higher timeframe or allow historical candle buffer to synchronize.",
                dataSource = dataSourceName,
                freshness = dataStatus.label,
                timestamp = now,
                formattedTime = formattedTime
            )
        }

        // If technical analysis or SMC analysis is missing, compute them on-the-fly
        val ta = technicalAnalysis ?: TechnicalIndicatorsEngine.analyze(symbol, assetName, primaryTimeframe, candles)
        val smc = smcAnalysis ?: SmcCalculationEngine.analyze(symbol, assetName, primaryTimeframe, candles, mtfSnapshots)

        // ----------------------------------------------------
        // 2. MULTI-TIMEFRAME EVALUATION (1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w)
        // ----------------------------------------------------
        val tfConditions = evaluateTimeframeConditions(smc, mtfSnapshots, primaryTimeframe)
        val bullishTfCount = tfConditions.count { it.stance == "BULLISH" }
        val bearishTfCount = tfConditions.count { it.stance == "BEARISH" }
        val neutralTfCount = tfConditions.count { it.stance == "NEUTRAL" }
        val totalTfCount = tfConditions.size.coerceAtLeast(1)

        // ----------------------------------------------------
        // 3. FACTOR SCORING: TECHNICAL INDICATORS & SMC
        // ----------------------------------------------------
        var bullishPoints = 0
        var bearishPoints = 0
        val factorReasons = mutableListOf<SignalFactorReason>()
        val supportingIndicators = mutableListOf<String>()
        val smcConfirmations = mutableListOf<String>()
        val conflicts = mutableListOf<String>()

        // --- Factor 1: Trend Direction (Max 20 pts) ---
        val trendPts: Int
        val trendStance: String
        val trendSummary: String

        val emaSet = ta.trend.emaSet
        val emaValues = listOfNotNull(emaSet.ema9, emaSet.ema20, emaSet.ema50, emaSet.ema100, emaSet.ema200)
        val emaBullishCount = emaValues.count { currentPrice > it }
        val emaBearishCount = emaValues.count { currentPrice < it }
        val emaTotalCount = emaValues.size
        val supertrend = ta.trend.supertrend
        val adx = ta.trend.adx
        val isAdxTrending = adx != null && adx.adx >= 20.0

        var bullTrendPts = 0
        var bearTrendPts = 0
        if (emaTotalCount > 0) {
            val bullRatio = emaBullishCount.toDouble() / emaTotalCount
            val bearRatio = emaBearishCount.toDouble() / emaTotalCount
            when {
                bullRatio >= 0.8 -> bullTrendPts += 10
                bullRatio >= 0.6 -> bullTrendPts += 7
                bullRatio > 0.5 -> bullTrendPts += 4
            }
            when {
                bearRatio >= 0.8 -> bearTrendPts += 10
                bearRatio >= 0.6 -> bearTrendPts += 7
                bearRatio > 0.5 -> bearTrendPts += 4
            }
        }
        if (emaSet.ema9 != null && emaSet.ema20 != null && emaSet.ema50 != null) {
            if (emaSet.ema9 > emaSet.ema20 && emaSet.ema20 > emaSet.ema50) bullTrendPts += 3
            else if (emaSet.ema9 < emaSet.ema20 && emaSet.ema20 < emaSet.ema50) bearTrendPts += 3
        }
        if (supertrend?.isBullish == true) bullTrendPts += 5
        else if (supertrend?.isBullish == false) bearTrendPts += 5

        if (isAdxTrending) {
            if (bullTrendPts > bearTrendPts) bullTrendPts += 2
            else if (bearTrendPts > bullTrendPts) bearTrendPts += 2
        }
        bullTrendPts = bullTrendPts.coerceAtMost(20)
        bearTrendPts = bearTrendPts.coerceAtMost(20)

        if (bullTrendPts >= 12 && bullTrendPts - bearTrendPts >= 5) {
            trendPts = bullTrendPts
            bullishPoints += trendPts
            trendStance = "BULLISH"
            trendSummary = "Price above $emaBullishCount/$emaTotalCount EMAs" +
                (supertrend?.let { " • Supertrend ${if (it.isBullish) "Bullish" else "Flip"} (${formatPrice(it.value)})" } ?: "")
            supportingIndicators.add("EMA Stack Bullish ($emaBullishCount/$emaTotalCount above)")
            if (supertrend?.isBullish == true) {
                supportingIndicators.add("Supertrend Bullish (${formatPrice(supertrend.value)})")
            }
        } else if (bearTrendPts >= 12 && bearTrendPts - bullTrendPts >= 5) {
            trendPts = bearTrendPts
            bearishPoints += trendPts
            trendStance = "BEARISH"
            trendSummary = "Price below $emaBearishCount/$emaTotalCount EMAs" +
                (supertrend?.let { " • Supertrend ${if (!it.isBullish) "Bearish" else "Flip"} (${formatPrice(it.value)})" } ?: "")
            supportingIndicators.add("EMA Stack Bearish ($emaBearishCount/$emaTotalCount below)")
            if (supertrend?.isBullish == false) {
                supportingIndicators.add("Supertrend Bearish (${formatPrice(supertrend.value)})")
            }
        } else {
            trendPts = max(bullTrendPts, bearTrendPts).coerceIn(5, 10)
            if (bullTrendPts > bearTrendPts) bullishPoints += 5 else if (bearTrendPts > bullTrendPts) bearishPoints += 5
            trendStance = "CONFLICTING"
            trendSummary = "Mixed EMA alignment ($emaBullishCount/$emaTotalCount above) near moving averages"
            conflicts.add("Trend Direction: Moving averages and Supertrend lack a clear directional tilt")
        }
        factorReasons.add(SignalFactorReason("Trend Direction", 20, trendPts, trendStance, trendSummary))

        // --- Factor 2: Momentum (Max 15 pts) ---
        val momPts: Int
        val momStance: String
        val momSummary: String

        val rsi14 = ta.momentum.rsi14
        val macd = ta.momentum.macd
        val stoch = ta.momentum.stochastic

        var bullMomPoints = 0
        var bearMomPoints = 0

        val rsiDiv = ta.momentum.rsiDivergence
        if (rsi14 != null) {
            when {
                rsi14 in 50.0..68.0 -> bullMomPoints += 5
                rsi14 in 68.01..78.0 -> bullMomPoints += 4
                rsiDiv?.type == com.example.data.indicators.RsiDivergenceType.BULLISH_REGULAR && rsiDiv.isConfirmedClosedSwings ->
                    bullMomPoints += 4
            }
            when {
                rsi14 in 32.0..49.99 -> bearMomPoints += 5
                rsi14 in 22.0..31.99 -> bearMomPoints += 4
                rsiDiv?.type == com.example.data.indicators.RsiDivergenceType.BEARISH_REGULAR && rsiDiv.isConfirmedClosedSwings ->
                    bearMomPoints += 4
            }
        }

        if (macd != null) {
            if (macd.histogram > 0 && macd.macdLine > macd.signalLine) {
                bullMomPoints += 6
                supportingIndicators.add("MACD Bullish Momentum (Histogram: +${String.format(Locale.US, "%.2f", macd.histogram)})")
            } else if (macd.histogram > 0 || macd.macdLine > macd.signalLine) {
                bullMomPoints += 4
            } else if (macd.histogram < 0 && macd.macdLine < macd.signalLine) {
                bearMomPoints += 6
                supportingIndicators.add("MACD Bearish Momentum (Histogram: ${String.format(Locale.US, "%.2f", macd.histogram)})")
            } else if (macd.histogram < 0 || macd.macdLine < macd.signalLine) {
                bearMomPoints += 4
            }
        }

        if (stoch != null) {
            if (stoch.k > stoch.d && stoch.k < 88.0) bullMomPoints += 4
            else if (stoch.k >= 60.0 && stoch.k > stoch.d * 0.98) bullMomPoints += 2

            if (stoch.k < stoch.d && stoch.k > 12.0) bearMomPoints += 4
            else if (stoch.k <= 40.0 && stoch.k < stoch.d * 1.02) bearMomPoints += 2
        }

        val rsiText = if (rsi14 != null) String.format(Locale.US, "%.1f", rsi14) else "N/A"

        if (bullMomPoints >= 7 && bullMomPoints - bearMomPoints >= 3) {
            momPts = min(15, bullMomPoints)
            bullishPoints += momPts
            momStance = "BULLISH"
            momSummary = "RSI ($rsiText) + MACD positive acceleration"
        } else if (bearMomPoints >= 7 && bearMomPoints - bullMomPoints >= 3) {
            momPts = min(15, bearMomPoints)
            bearishPoints += momPts
            momStance = "BEARISH"
            momSummary = "RSI ($rsiText) + MACD negative acceleration"
        } else {
            momPts = 5
            momStance = "NEUTRAL"
            momSummary = "Momentum oscillators near equilibrium (RSI: $rsiText)"
            if (abs(bullMomPoints - bearMomPoints) <= 2) {
                conflicts.add("Momentum: RSI and MACD do not show decisive expansion bias")
            }
        }
        factorReasons.add(SignalFactorReason("Momentum", 15, momPts, momStance, momSummary))

        // --- Factor 3: Volume & Participation (Max 15 pts) ---
        val volPts: Int
        val volStance: String
        val volSummary: String

        val rvol = ta.volume.relativeVolume
        val volDelta = ta.volume.volumeDelta
        val cmf = ta.volume.cmf

        var bullVolPts = 0
        var bearVolPts = 0

        if (ta.volume.hasReliableVolume) {
            if (volDelta != null && volDelta.hasReliableData) {
                if (volDelta.delta > 0) bullVolPts += if ((rvol ?: 0.0) >= 1.15) 7 else 5
                else if (volDelta.delta < 0) bearVolPts += if ((rvol ?: 0.0) >= 1.15) 7 else 5
            }
            // Grouped OBV + CMF merged vote (not double-counted)
            val cmfBull = cmf != null && cmf > 0.0
            val cmfBear = cmf != null && cmf < 0.0
            val obvBull = ta.volume.obv?.stance == IndicatorStance.BULLISH
            val obvBear = ta.volume.obv?.stance == IndicatorStance.BEARISH
            if (cmfBull || obvBull) bullVolPts += if (cmf != null && cmf >= 0.05 && obvBull) 6 else 4
            if (cmfBear || obvBear) bearVolPts += if (cmf != null && cmf <= -0.05 && obvBear) 6 else 4
        }

        val rvolText = rvol?.let { "${String.format(Locale.US, "%.2f", it)}x" } ?: "N/A"

        if (!ta.volume.hasReliableVolume) {
            volPts = 0
            volStance = "NEUTRAL"
            volSummary = "Volume data unavailable on feed (never invented)"
        } else if (bullVolPts >= 7 && bullVolPts - bearVolPts >= 3) {
            volPts = min(15, bullVolPts)
            bullishPoints += volPts
            volStance = "BULLISH"
            volSummary = "Net buying flow (RVOL: $rvolText, CMF: ${cmf?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"})"
            supportingIndicators.add("Buy Volume Confluence (RVOL $rvolText)")
        } else if (bearVolPts >= 7 && bearVolPts - bullVolPts >= 3) {
            volPts = min(15, bearVolPts)
            bearishPoints += volPts
            volStance = "BEARISH"
            volSummary = "Net selling flow (RVOL: $rvolText, CMF: ${cmf?.let { String.format(Locale.US, "%.2f", it) } ?: "N/A"})"
            supportingIndicators.add("Sell Volume Confluence (RVOL $rvolText)")
        } else {
            volPts = 6
            volStance = "NEUTRAL"
            volSummary = "Balanced volume (RVOL: $rvolText) without dominant delta divergence"
        }
        factorReasons.add(SignalFactorReason("Volume & Participation", 15, volPts, volStance, volSummary))

        // --- Factor 4: Market Structure (SMC) (Max 20 pts) ---
        val structPts: Int
        val structStance: String
        val structSummary: String

        val ms = smc.marketStructure
        val isBullishStructure = ms.trendState == MarketTrendState.BULLISH_UPTREND
        val isBearishStructure = ms.trendState == MarketTrendState.BEARISH_DOWNTREND
        val hasBullishBreak = ms.isBullishBOSPresent || ms.isBullishCHoCHPresent
        val hasBearishBreak = ms.isBearishBOSPresent || ms.isBearishCHoCHPresent

        val unmitigatedBullishOB = smc.orderBlocks.firstOrNull {
            it.type == OrderBlockType.BULLISH && it.status == OrderBlockStatus.UNMITIGATED && currentPrice >= (it.bottomPrice * 0.985) && currentPrice <= (it.topPrice * 1.03)
        }
        val unmitigatedBearishOB = smc.orderBlocks.firstOrNull {
            it.type == OrderBlockType.BEARISH && it.status == OrderBlockStatus.UNMITIGATED && currentPrice <= (it.topPrice * 1.015) && currentPrice >= (it.bottomPrice * 0.97)
        }

        // Price-action displacement fallback when candles form a steady trend without 5-bar fractal pivots
        val recentSegment = candles.takeLast(min(24, candles.size))
        val firstThirdAvg = recentSegment.take(max(1, recentSegment.size / 3)).map { it.close }.average()
        val lastThirdAvg = recentSegment.takeLast(max(1, recentSegment.size / 3)).map { it.close }.average()
        val displacementPct = if (firstThirdAvg > 0.0) ((lastThirdAvg - firstThirdAvg) / firstThirdAvg) * 100.0 else 0.0
        val sustainedBullishDisplacement = !isBearishStructure && !hasBearishBreak && displacementPct >= 1.2 && currentPrice >= lastThirdAvg
        val sustainedBearishDisplacement = !isBullishStructure && !hasBullishBreak && displacementPct <= -1.2 && currentPrice <= lastThirdAvg

        if (isBullishStructure || (hasBullishBreak && (unmitigatedBullishOB != null || !isBearishStructure))) {
            structPts = if (unmitigatedBullishOB != null) 20 else 18
            bullishPoints += structPts
            structStance = "BULLISH"
            val breakLabel = if (ms.isBullishCHoCHPresent) "Bullish CHoCH" else "Bullish BOS"
            structSummary = "$breakLabel confirmed with Higher Highs/Lows" + (if (unmitigatedBullishOB != null) " + Demand OB" else "")
            smcConfirmations.add("$breakLabel on ${primaryTimeframe.label}")
            if (unmitigatedBullishOB != null) {
                smcConfirmations.add("Unmitigated Bullish OB [${formatPrice(unmitigatedBullishOB.bottomPrice)} - ${formatPrice(unmitigatedBullishOB.topPrice)}]")
            }
        } else if (isBearishStructure || (hasBearishBreak && (unmitigatedBearishOB != null || !isBullishStructure))) {
            structPts = if (unmitigatedBearishOB != null) 20 else 18
            bearishPoints += structPts
            structStance = "BEARISH"
            val breakLabel = if (ms.isBearishCHoCHPresent) "Bearish CHoCH" else "Bearish BOS"
            structSummary = "$breakLabel confirmed with Lower Highs/Lows" + (if (unmitigatedBearishOB != null) " + Supply OB" else "")
            smcConfirmations.add("$breakLabel on ${primaryTimeframe.label}")
            if (unmitigatedBearishOB != null) {
                smcConfirmations.add("Unmitigated Bearish OB [${formatPrice(unmitigatedBearishOB.bottomPrice)} - ${formatPrice(unmitigatedBearishOB.topPrice)}]")
            }
        } else if (sustainedBullishDisplacement) {
            structPts = 14
            bullishPoints += structPts
            structStance = "BULLISH"
            structSummary = "Steady upward structural displacement (+${String.format(Locale.US, "%.2f", displacementPct)}%)"
            smcConfirmations.add("Bullish Price-Action Displacement on ${primaryTimeframe.label}")
        } else if (sustainedBearishDisplacement) {
            structPts = 14
            bearishPoints += structPts
            structStance = "BEARISH"
            structSummary = "Steady downward structural displacement (${String.format(Locale.US, "%.2f", displacementPct)}%)"
            smcConfirmations.add("Bearish Price-Action Displacement on ${primaryTimeframe.label}")
        } else {
            structPts = 6
            structStance = "CONFLICTING"
            structSummary = "Consolidating market structure without decisive BOS or displacement"
            conflicts.add("Market Structure: Trend is ranging inside equilibrium")
        }
        factorReasons.add(SignalFactorReason("Market Structure (SMC)", 20, structPts, structStance, structSummary))

        // --- Factor 5: Liquidity & Stop Runs (Max 10 pts) ---
        val liqPts: Int
        val liqStance: String
        val liqSummary: String

        val recentSweep = smc.liquidity.recentSweeps.firstOrNull()
        val sslPools = smc.liquidity.pools.filter { it.type == LiquidityPoolType.SELL_SIDE_LIQUIDITY || it.type == LiquidityPoolType.EQUAL_LOWS }
        val bslPools = smc.liquidity.pools.filter { it.type == LiquidityPoolType.BUY_SIDE_LIQUIDITY || it.type == LiquidityPoolType.EQUAL_HIGHS }

        if (recentSweep != null && (recentSweep.poolType == LiquidityPoolType.SELL_SIDE_LIQUIDITY || recentSweep.poolType == LiquidityPoolType.EQUAL_LOWS)) {
            liqPts = 10
            bullishPoints += liqPts
            liqStance = "BULLISH"
            liqSummary = "Sell-Side Liquidity (SSL) swept below ${formatPrice(recentSweep.sweptLevel)} followed by reclamation"
            smcConfirmations.add("SSL / Equal Lows Swept (Institutional Spring)")
        } else if (recentSweep != null && (recentSweep.poolType == LiquidityPoolType.BUY_SIDE_LIQUIDITY || recentSweep.poolType == LiquidityPoolType.EQUAL_HIGHS)) {
            liqPts = 10
            bearishPoints += liqPts
            liqStance = "BEARISH"
            liqSummary = "Buy-Side Liquidity (BSL) swept above ${formatPrice(recentSweep.sweptLevel)} followed by rejection"
            smcConfirmations.add("BSL / Equal Highs Swept (Institutional Upthrust)")
        } else if (smc.liquidity.potentialStopHuntActive) {
            liqPts = 8
            val sweepHunt = smc.liquidity.recentSweeps.firstOrNull { it.isPotentialStopHunt }
            if (sweepHunt != null && (sweepHunt.poolType == LiquidityPoolType.SELL_SIDE_LIQUIDITY || sweepHunt.poolType == LiquidityPoolType.EQUAL_LOWS)) {
                bullishPoints += liqPts
                liqStance = "BULLISH"
                liqSummary = "Potential Bullish Stop-Hunt near ${formatPrice(sweepHunt.sweptLevel)}"
                smcConfirmations.add("Potential Stop-Hunt (Discount accumulation)")
            } else {
                bearishPoints += liqPts
                liqStance = "BEARISH"
                val trig = sweepHunt?.sweptLevel ?: currentPrice
                liqSummary = "Potential Bearish Stop-Hunt near ${formatPrice(trig)}"
                smcConfirmations.add("Potential Stop-Hunt (Premium distribution)")
            }
        } else {
            liqPts = 5
            liqStance = "NEUTRAL"
            liqSummary = "Resting liquidity targets intact (BSL: ${bslPools.size}, SSL: ${sslPools.size})"
        }
        factorReasons.add(SignalFactorReason("Liquidity & Stop Runs", 10, liqPts, liqStance, liqSummary))

        // --- Factor 6: Support/Resistance & Fib Golden Pocket (Max 10 pts) ---
        val srPts: Int
        val srStance: String
        val srSummary: String

        val fibReport = smc.fibonacci?.validationReport
        val goldenPocket = smc.fibonacci?.retracementLevels?.firstOrNull { it.ratio == 0.618 }
        val isGoldenConfirmed = fibReport?.isStrictlyValid == true
        val isGoldenTouchOnly = fibReport?.isInGoldenZone0500To0618 == true && !isGoldenConfirmed
        val isNearGoldenPocket = goldenPocket != null && abs(currentPrice - goldenPocket.price) / currentPrice <= 0.03
        val nearestSupport = smc.priceZones.firstOrNull { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }
        val nearestResistance = smc.priceZones.firstOrNull { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }

        val unfilledBullishFvg = smc.fairValueGaps.firstOrNull { it.type == FvgType.BULLISH && it.status != FvgStatus.FULLY_FILLED }
        val unfilledBearishFvg = smc.fairValueGaps.firstOrNull { it.type == FvgType.BEARISH && it.status != FvgStatus.FULLY_FILLED }

        val distToSupport = nearestSupport?.let { abs(currentPrice - it.centralPrice) / currentPrice } ?: 0.1
        val distToResistance = nearestResistance?.let { abs(currentPrice - it.centralPrice) / currentPrice } ?: 0.1

        val nearSupportDemand = nearestSupport != null && distToSupport <= 0.025 && (distToSupport <= distToResistance || unfilledBullishFvg != null)
        val nearResistanceSupply = nearestResistance != null && distToResistance <= 0.025 && (distToResistance < distToSupport || unfilledBearishFvg != null)

        if ((isGoldenConfirmed && smc.fibonacci?.isUptrendSwing == true) ||
            (nearSupportDemand && bullishPoints >= bearishPoints && !isGoldenTouchOnly)
        ) {
            srPts = if (isGoldenConfirmed) 10 else 8
            bullishPoints += srPts
            srStance = "BULLISH"
            srSummary = if (isGoldenConfirmed && goldenPocket != null) {
                "Strict 7-Gate Golden Zone (0.500–0.618) LONG Confirmed (${formatPrice(goldenPocket.price)})"
            } else {
                "Holding Key Demand / Support Zone (${formatPrice(nearestSupport!!.centralPrice)})"
            }
            if (isGoldenConfirmed && goldenPocket != null) {
                smcConfirmations.add("7-Gate Golden Zone 0.500–0.618 (${formatPrice(goldenPocket.price)})")
            }
            if (unfilledBullishFvg != null) {
                smcConfirmations.add("Unfilled Bullish FVG [${formatPrice(unfilledBullishFvg.bottomPrice)} - ${formatPrice(unfilledBullishFvg.topPrice)}]")
            }
        } else if ((isGoldenConfirmed && smc.fibonacci?.isUptrendSwing == false) ||
            (nearResistanceSupply && bearishPoints >= bullishPoints && !isGoldenTouchOnly)
        ) {
            srPts = if (isGoldenConfirmed) 10 else 8
            bearishPoints += srPts
            srStance = "BEARISH"
            srSummary = if (isGoldenConfirmed && goldenPocket != null) {
                "Strict 7-Gate Golden Zone (0.500–0.618) SHORT Confirmed (${formatPrice(goldenPocket.price)})"
            } else {
                "Testing Key Supply / Resistance Barrier (${formatPrice(nearestResistance?.centralPrice ?: currentPrice)})"
            }
            nearestResistance?.let {
                smcConfirmations.add("Supply / Key Resistance Barrier (${formatPrice(it.centralPrice)})")
            }
            if (unfilledBearishFvg != null) {
                smcConfirmations.add("Unfilled Bearish FVG [${formatPrice(unfilledBearishFvg.bottomPrice)} - ${formatPrice(unfilledBearishFvg.topPrice)}]")
            }
        } else if (isGoldenTouchOnly) {
            srPts = 5
            srStance = "NEUTRAL"
            srSummary = "In Golden Zone (0.500–0.618) — ${fibReport?.rejectionReason ?: "Awaiting rejection candle + confluence (touch alone insufficient)"}"
        } else if (unfilledBullishFvg != null && currentPrice >= unfilledBullishFvg.bottomPrice * 0.99 && bullishPoints > bearishPoints) {
            srPts = 7
            bullishPoints += srPts
            srStance = "BULLISH"
            srSummary = "Supported above Bullish FVG [${formatPrice(unfilledBullishFvg.bottomPrice)} - ${formatPrice(unfilledBullishFvg.topPrice)}]"
            smcConfirmations.add("Bullish FVG Support [${formatPrice(unfilledBullishFvg.bottomPrice)} - ${formatPrice(unfilledBullishFvg.topPrice)}]")
        } else if (unfilledBearishFvg != null && currentPrice <= unfilledBearishFvg.topPrice * 1.01 && bearishPoints > bullishPoints) {
            srPts = 7
            bearishPoints += srPts
            srStance = "BEARISH"
            srSummary = "Capped below Bearish FVG [${formatPrice(unfilledBearishFvg.bottomPrice)} - ${formatPrice(unfilledBearishFvg.topPrice)}]"
            smcConfirmations.add("Bearish FVG Resistance [${formatPrice(unfilledBearishFvg.bottomPrice)} - ${formatPrice(unfilledBearishFvg.topPrice)}]")
        } else if (isNearGoldenPocket && (distToSupport <= distToResistance)) {
            srPts = 6
            bullishPoints += srPts
            srStance = "BULLISH"
            srSummary = "Approaching 0.618 Fib near Support (${formatPrice(nearestSupport?.centralPrice ?: currentPrice)})"
        } else {
            srPts = 5
            srStance = "NEUTRAL"
            srSummary = "Between Support (${nearestSupport?.let { formatPrice(it.centralPrice) } ?: "N/A"}) and Resistance (${nearestResistance?.let { formatPrice(it.centralPrice) } ?: "N/A"})"
        }
        factorReasons.add(SignalFactorReason("Support / Resistance & Fib", 10, srPts, srStance, srSummary))

        // --- Factor 7: Multi-Timeframe Confirmation (Max 10 pts) ---
        val mtfPts: Int
        val mtfStance: String
        val mtfSummary: String

        val evaluatedTfCount = tfConditions.count { it.trendDescription != "Synchronizing" }.coerceAtLeast(1)
        val agreementPercent = if (bullishPoints >= bearishPoints) {
            (bullishTfCount.toDouble() / evaluatedTfCount * 100).toInt().coerceIn(0, 100)
        } else {
            (bearishTfCount.toDouble() / evaluatedTfCount * 100).toInt().coerceIn(0, 100)
        }

        if (bullishTfCount > bearishTfCount && bullishTfCount >= max(2, (evaluatedTfCount * 0.5).toInt()) && bullishPoints >= bearishPoints) {
            mtfPts = if (bullishTfCount >= (evaluatedTfCount * 0.7).toInt()) 10 else 8
            bullishPoints += mtfPts
            mtfStance = "BULLISH"
            mtfSummary = "$bullishTfCount of $evaluatedTfCount synced timeframes agree Bullish ($agreementPercent% alignment)"
            supportingIndicators.add("MTF Confluence: $bullishTfCount/$evaluatedTfCount timeframes Bullish")
        } else if (bearishTfCount > bullishTfCount && bearishTfCount >= max(2, (evaluatedTfCount * 0.5).toInt()) && bearishPoints > bullishPoints) {
            mtfPts = if (bearishTfCount >= (evaluatedTfCount * 0.7).toInt()) 10 else 8
            bearishPoints += mtfPts
            mtfStance = "BEARISH"
            mtfSummary = "$bearishTfCount of $evaluatedTfCount synced timeframes agree Bearish ($agreementPercent% alignment)"
            supportingIndicators.add("MTF Confluence: $bearishTfCount/$evaluatedTfCount timeframes Bearish")
        } else {
            mtfPts = 4
            mtfStance = if (bullishTfCount == 0 && bearishTfCount == 0) "NEUTRAL" else "CONFLICTING"
            mtfSummary = "Split timeframes: $bullishTfCount Bullish, $bearishTfCount Bearish, $neutralTfCount Neutral"
            if (bullishTfCount > 0 && bearishTfCount > 0 && abs(bullishTfCount - bearishTfCount) <= 1) {
                conflicts.add("Multi-Timeframe Conflict: Lower and higher timeframes are evenly divided")
            }
        }
        factorReasons.add(SignalFactorReason("Multi-Timeframe Confirmation", 10, mtfPts, mtfStance, mtfSummary))

        // ----------------------------------------------------
        // 4. CONFIDENCE SCORE AGGREGATION & DECISION LOGIC
        // ----------------------------------------------------
        val rawConfidence = max(bullishPoints, bearishPoints).coerceIn(0, 100)
        val pointEdge = abs(bullishPoints - bearishPoints)
        val isBullishDominant = bullishPoints > bearishPoints && bullishPoints >= 60 && pointEdge >= 18 && conflicts.size < 3
        val isBearishDominant = bearishPoints > bullishPoints && bearishPoints >= 60 && pointEdge >= 18 && conflicts.size < 3
        val hasSevereConflict = pointEdge < 18 || conflicts.size >= 2

        val ratingLabel = when {
            rawConfidence >= 80 && !hasSevereConflict -> "High Confluence"
            rawConfidence >= 65 && !hasSevereConflict -> "Moderate Confluence"
            rawConfidence >= 60 && pointEdge >= 18 -> "Moderate Confluence"
            else -> "Low / Conflicting Confluence"
        }

        val confidenceBreakdown = ConfidenceScoreBreakdown(
            trendScore = trendPts,
            momentumScore = momPts,
            volumeScore = volPts,
            marketStructureScore = structPts,
            liquidityScore = liqPts,
            supportResistanceScore = srPts,
            multiTimeframeScore = mtfPts,
            totalScore = rawConfidence,
            rating = ratingLabel,
            factors = factorReasons
        )

        // ----------------------------------------------------
        // 5. NO TRADE DECISION CHECK
        // ----------------------------------------------------
        if (!isBullishDominant && !isBearishDominant) {
            val strictWaitReport = if (enforceStrictQualificationGate) {
                strictQualityReportOverride ?: StrictSignalQualityEngine.evaluateSetup(
                    symbol = symbol,
                    assetName = assetName,
                    currentPrice = currentPrice,
                    dataStatus = dataStatus,
                    primaryTimeframe = primaryTimeframe,
                    candles = candles,
                    technicalAnalysis = ta,
                    smcAnalysis = smc,
                    mtfSnapshots = mtfSnapshots,
                    referenceTimeMs = now
                )
            } else strictQualityReportOverride

            val failedFiltersList = strictWaitReport?.mandatoryFilters?.filter { !it.passed }?.map {
                "Filter #${it.filterNumber} (${it.name}): ${it.reason}"
            }.orEmpty()

            return@withContext createNoTradeSignal(
                symbol = symbol,
                assetName = assetName,
                primaryTimeframe = primaryTimeframe,
                code = if (enforceStrictQualificationGate) "STRICT_WAIT_NO_TRADE" else "CONFLICTING_INDICATORS",
                headline = if (enforceStrictQualificationGate) {
                    "WAIT / NO TRADE (Quality ${strictWaitReport?.totalScore ?: rawConfidence}/100 < $strictMinScore)"
                } else {
                    "Conflicting Indicators & Lack of Edge"
                },
                explanation = if (enforceStrictQualificationGate) {
                    "NEXUS Strict Validator rejected entry: Setup Quality Score is ${strictWaitReport?.totalScore ?: rawConfidence}/100 (requires >= $strictMinScore/100 setup quality, NOT win rate) and all 8 institutional gates must pass."
                } else {
                    "Bullish score ($bullishPoints pts) vs Bearish score ($bearishPoints pts) lacks required confluence (>=60 pts with >=18 pt edge). Market has no clear directional setup."
                },
                conflictingFactors = (failedFiltersList + conflicts + strictWaitReport?.missingConfirmations.orEmpty())
                    .distinct()
                    .take(8)
                    .ifEmpty {
                        listOf(
                            "Directional margin ($pointEdge pts) or quality score is below threshold",
                            "Indicators are not in unified multi-factor alignment"
                        )
                    },
                recommendedAction = "Wait for a confirmed closed-candle MTF setup scoring >= $strictMinScore/100 with NET R:R >= 1:4.0.",
                dataSource = dataSourceName,
                freshness = dataStatus.label,
                timestamp = now,
                formattedTime = formattedTime,
                confidence = confidenceBreakdown,
                timeframeConditions = tfConditions,
                strictQualityReport = strictWaitReport
            )
        }

        // ----------------------------------------------------
        // 6. RISK MANAGEMENT PLAN (FOR LONG OR SHORT)
        // ----------------------------------------------------
        val atr = ta.volatility.atr?.value ?: (currentPrice * 0.015)

        val decision = if (isBullishDominant) SignalDecision.LONG else SignalDecision.SHORT

        val riskPlan = if (decision == SignalDecision.LONG) {
            calculateLongRiskPlan(
                currentPrice = currentPrice,
                atr = atr,
                unmitigatedOB = unmitigatedBullishOB,
                nearestSupport = nearestSupport,
                goldenPocket = goldenPocket,
                fibAnalysis = smc.fibonacci
            )
        } else {
            calculateShortRiskPlan(
                currentPrice = currentPrice,
                atr = atr,
                unmitigatedOB = unmitigatedBearishOB,
                nearestResistance = nearestResistance,
                goldenPocket = goldenPocket,
                fibAnalysis = smc.fibonacci
            )
        }

        // ----------------------------------------------------
        // 7. RISK-TO-REWARD VALIDATION GATE
        // ----------------------------------------------------
        if (riskPlan.riskRewardRatio < 1.5) {
            return@withContext createNoTradeSignal(
                symbol = symbol,
                assetName = assetName,
                primaryTimeframe = primaryTimeframe,
                code = "POOR_RISK_REWARD",
                headline = "Unfavorable Risk-to-Reward Ratio (${riskPlan.riskRewardFormatted})",
                explanation = "Calculated Risk-to-Reward is ${riskPlan.riskRewardFormatted}, which is below the mandatory 1:1.5 threshold. Taking this trade exposes capital to asymmetric risk.",
                conflictingFactors = listOf(
                    "Calculated Risk: ${String.format(Locale.US, "%.2f", riskPlan.estimatedRiskPercent)}%",
                    "Calculated Reward (TP2): ${String.format(Locale.US, "%.2f", riskPlan.potentialRewardPercent)}%",
                    "Target is too close or Stop Loss is too wide relative to market structure"
                ),
                recommendedAction = "Wait for a deeper pullback into the entry zone to improve the entry price and tighten the stop loss.",
                dataSource = dataSourceName,
                freshness = dataStatus.label,
                timestamp = now,
                formattedTime = formattedTime,
                confidence = confidenceBreakdown,
                timeframeConditions = tfConditions
            )
        }

        // ----------------------------------------------------
        // 8. FINAL VALID TRADING SIGNAL (OR STRICT >=92 GATE DOWNGRADE)
        // ----------------------------------------------------
        val strictReport = strictQualityReportOverride ?: StrictSignalQualityEngine.evaluateSetup(
            symbol = symbol,
            assetName = assetName,
            currentPrice = currentPrice,
            dataStatus = dataStatus,
            primaryTimeframe = primaryTimeframe,
            candles = candles,
            technicalAnalysis = ta,
            smcAnalysis = smc,
            mtfSnapshots = mtfSnapshots,
            proposedEntryPrice = riskPlan.entryPrice,
            proposedStopLoss = riskPlan.stopLoss,
            proposedTargetPrice = riskPlan.takeProfit3,
            candidateDirectionOverride = decision,
            referenceTimeMs = now
        )

        if (enforceStrictQualificationGate &&
            !StrictSignalQualityEngine.meetsStrictProQualification(strictReport, strictMinScore)
        ) {
            val failedReasons = buildList {
                if (strictReport.totalScore < strictMinScore) {
                    add("Setup Quality Score ${strictReport.totalScore}/100 < mandatory $strictMinScore/100 threshold (quality score, NOT win rate)")
                }
                strictReport.mandatoryFilters.filter { !it.passed }.forEach { f ->
                    add("Filter #${f.filterNumber} (${f.name}) Failed: ${f.reason}")
                }
                addAll(strictReport.missingConfirmations.take(4))
            }.distinct()

            return@withContext createNoTradeSignal(
                symbol = symbol,
                assetName = assetName,
                primaryTimeframe = primaryTimeframe,
                code = "STRICT_QUALITY_GATE_REJECTED",
                headline = "WAIT / NO TRADE — Setup Quality ${strictReport.totalScore}/100 (< $strictMinScore)",
                explanation = "Candidate ${decision.label} setup on $symbol was rejected by the NEXUS Strict Institutional Gate. " +
                    "Score ${strictReport.totalScore}/100 (${strictReport.passedFilterCount}/8 filters passed; Net R:R ${strictReport.netRiskRewardSummary?.netRiskRewardFormatted ?: "N/A"}). " +
                    "Note: $strictMinScore/100 measures multi-layer confluence quality, NOT guaranteed win probability.",
                conflictingFactors = failedReasons,
                recommendedAction = "Stay in WAIT / NO TRADE until all 8 mandatory filters pass (incl. NET R:R >= 1:4.0) and Setup Quality Score >= $strictMinScore/100.",
                dataSource = dataSourceName,
                freshness = dataStatus.label,
                timestamp = now,
                formattedTime = formattedTime,
                confidence = confidenceBreakdown,
                timeframeConditions = tfConditions,
                strictQualityReport = strictReport
            )
        }

        val signalId = "SIG_${symbol.replace("/", "_")}_${System.currentTimeMillis()}"

        return@withContext AiTradingSignal(
            id = signalId,
            symbol = symbol,
            assetName = assetName,
            primaryTimeframe = primaryTimeframe,
            decision = decision,
            confidence = confidenceBreakdown,
            riskPlan = riskPlan,
            noTradeReason = null,
            supportingIndicators = supportingIndicators,
            smcConfirmations = smcConfirmations,
            timeframeConditions = tfConditions,
            mtfAgreementCount = if (decision == SignalDecision.LONG) bullishTfCount else bearishTfCount,
            mtfTotalCount = totalTfCount,
            mtfAgreementPercent = agreementPercent,
            timestamp = now,
            formattedTimestamp = formattedTime,
            dataSource = dataSourceName,
            dataFreshness = dataStatus.label,
            strictQualityReport = strictReport
        )
    }

    // -----------------------------------------------------------------------------------------
    // RISK MANAGEMENT CALCULATIONS (STRICT STOP LOSS, TARGETS, LEVERAGE)
    // -----------------------------------------------------------------------------------------

    private fun calculateLongRiskPlan(
        currentPrice: Double,
        atr: Double,
        unmitigatedOB: OrderBlock?,
        nearestSupport: PriceLevelZone?,
        goldenPocket: FibLevel?,
        fibAnalysis: FibonacciAnalysis?
    ): RiskManagementPlan {
        // Entry zone: between current price and nearest discount structural level
        val entryLow = unmitigatedOB?.bottomPrice
            ?: goldenPocket?.price?.let { min(currentPrice, it) }
            ?: (currentPrice - atr * 0.4)
        val entryHigh = max(currentPrice, entryLow + atr * 0.2)
        val entryMid = (entryLow + entryHigh) / 2.0

        // Stop loss: below Order Block bottom, Support, or ATR buffer
        val rawSl = unmitigatedOB?.let { it.bottomPrice - (atr * 0.35) }
            ?: nearestSupport?.let { it.lowerPrice - (atr * 0.35) }
            ?: (entryMid - atr * 1.5)

        // Ensure SL is strictly below entry and realistic (minimum 0.7%, max 5.0%)
        val minSlDist = entryMid * 0.008
        val maxSlDist = entryMid * 0.05
        val slDist = (entryMid - rawSl).coerceIn(minSlDist, maxSlDist)
        val stopLoss = entryMid - slDist
        val riskPercent = (slDist / entryMid) * 100.0

        // Targets based on R:R and ordered Fib extensions strictly above TP1
        val tp1 = entryMid + (slDist * 1.6)
        val fibExtensions = fibAnalysis?.extensionLevels.orEmpty()
            .map { it.price }
            .sorted()
        val tp2 = fibExtensions.firstOrNull { it > tp1 + (slDist * 0.5) }
            ?: (entryMid + (slDist * 2.8))
        val tp3 = fibExtensions.firstOrNull { it > tp2 + (slDist * 0.5) }
            ?: (entryMid + (slDist * 4.2))

        val tp1Dist = ((tp1 - entryMid) / entryMid) * 100.0
        val tp2Dist = ((tp2 - entryMid) / entryMid) * 100.0
        val tp3Dist = ((tp3 - entryMid) / entryMid) * 100.0
        val rrRatio = ((tp2 - entryMid) / slDist).coerceAtLeast(1.0)

        // Leverage capped based on Risk %: Never exceed 10x!
        val suggestedLeverage = calculateSafeLeverage(riskPercent)
        val leverageTier = when {
            suggestedLeverage <= 3 -> "Low Risk (2x - 3x)"
            suggestedLeverage <= 6 -> "Moderate (4x - 6x)"
            else -> "Disciplined Cap (7x - 10x)"
        }
        val levExplanation = "Max $suggestedLeverage" + "x based on ${String.format(Locale.US, "%.2f", riskPercent)}% stop loss distance. Keeps trade liquidation well beyond invalidation point."

        val invalidation = "Hourly candle close below ${formatPrice(stopLoss)} or Bearish CHoCH on Lower Timeframe."

        return RiskManagementPlan(
            entryPrice = entryMid,
            entryZoneMin = min(entryLow, entryHigh),
            entryZoneMax = max(entryLow, entryHigh),
            entryZoneFormatted = "${formatPrice(min(entryLow, entryHigh))} - ${formatPrice(max(entryLow, entryHigh))}",
            stopLoss = stopLoss,
            stopLossFormatted = formatPrice(stopLoss),
            stopLossDistancePercent = riskPercent,
            takeProfit1 = tp1,
            takeProfit1Formatted = formatPrice(tp1),
            takeProfit1DistancePercent = tp1Dist,
            takeProfit2 = tp2,
            takeProfit2Formatted = formatPrice(tp2),
            takeProfit2DistancePercent = tp2Dist,
            takeProfit3 = tp3,
            takeProfit3Formatted = formatPrice(tp3),
            takeProfit3DistancePercent = tp3Dist,
            riskRewardRatio = rrRatio,
            riskRewardFormatted = "1 : ${String.format(Locale.US, "%.1f", rrRatio)}",
            estimatedRiskPercent = riskPercent,
            potentialRewardPercent = tp2Dist,
            suggestedMaxLeverage = suggestedLeverage,
            leverageTier = leverageTier,
            leverageExplanation = levExplanation,
            invalidationCondition = invalidation
        )
    }

    private fun calculateShortRiskPlan(
        currentPrice: Double,
        atr: Double,
        unmitigatedOB: OrderBlock?,
        nearestResistance: PriceLevelZone?,
        goldenPocket: FibLevel?,
        fibAnalysis: FibonacciAnalysis?
    ): RiskManagementPlan {
        // Entry zone: between current price and nearest premium structural level
        val entryHigh = unmitigatedOB?.topPrice
            ?: goldenPocket?.price?.let { max(currentPrice, it) }
            ?: (currentPrice + atr * 0.4)
        val entryLow = min(currentPrice, entryHigh - atr * 0.2)
        val entryMid = (entryLow + entryHigh) / 2.0

        // Stop loss: above Order Block top, Resistance, or ATR buffer
        val rawSl = unmitigatedOB?.let { it.topPrice + (atr * 0.35) }
            ?: nearestResistance?.let { it.upperPrice + (atr * 0.35) }
            ?: (entryMid + atr * 1.5)

        val minSlDist = entryMid * 0.008
        val maxSlDist = entryMid * 0.05
        val slDist = (rawSl - entryMid).coerceIn(minSlDist, maxSlDist)
        val stopLoss = entryMid + slDist
        val riskPercent = (slDist / entryMid) * 100.0

        val tp1 = entryMid - (slDist * 1.6)
        val fibExtensions = fibAnalysis?.extensionLevels.orEmpty()
            .filter { !fibAnalysis!!.isUptrendSwing }
            .map { it.price }
            .sortedDescending()
        val tp2 = fibExtensions.firstOrNull { it < tp1 - (slDist * 0.5) }
            ?: (entryMid - (slDist * 2.8))
        val tp3 = fibExtensions.firstOrNull { it < tp2 - (slDist * 0.5) }
            ?: (entryMid - (slDist * 4.2))

        val tp1Dist = ((entryMid - tp1) / entryMid) * 100.0
        val tp2Dist = ((entryMid - tp2) / entryMid) * 100.0
        val tp3Dist = ((entryMid - tp3) / entryMid) * 100.0
        val rrRatio = ((entryMid - tp2) / slDist).coerceAtLeast(1.0)

        val suggestedLeverage = calculateSafeLeverage(riskPercent)
        val leverageTier = when {
            suggestedLeverage <= 3 -> "Low Risk (2x - 3x)"
            suggestedLeverage <= 6 -> "Moderate (4x - 6x)"
            else -> "Disciplined Cap (7x - 10x)"
        }
        val levExplanation = "Max $suggestedLeverage" + "x based on ${String.format(Locale.US, "%.2f", riskPercent)}% stop loss distance. Never risk more than 1-2% of total account balance."

        val invalidation = "Hourly candle close above ${formatPrice(stopLoss)} or Bullish CHoCH on Lower Timeframe."

        return RiskManagementPlan(
            entryPrice = entryMid,
            entryZoneMin = min(entryLow, entryHigh),
            entryZoneMax = max(entryLow, entryHigh),
            entryZoneFormatted = "${formatPrice(min(entryLow, entryHigh))} - ${formatPrice(max(entryLow, entryHigh))}",
            stopLoss = stopLoss,
            stopLossFormatted = formatPrice(stopLoss),
            stopLossDistancePercent = riskPercent,
            takeProfit1 = tp1,
            takeProfit1Formatted = formatPrice(tp1),
            takeProfit1DistancePercent = tp1Dist,
            takeProfit2 = tp2,
            takeProfit2Formatted = formatPrice(tp2),
            takeProfit2DistancePercent = tp2Dist,
            takeProfit3 = tp3,
            takeProfit3Formatted = formatPrice(tp3),
            takeProfit3DistancePercent = tp3Dist,
            riskRewardRatio = rrRatio,
            riskRewardFormatted = "1 : ${String.format(Locale.US, "%.1f", rrRatio)}",
            estimatedRiskPercent = riskPercent,
            potentialRewardPercent = tp2Dist,
            suggestedMaxLeverage = suggestedLeverage,
            leverageTier = leverageTier,
            leverageExplanation = levExplanation,
            invalidationCondition = invalidation
        )
    }

    private fun calculateSafeLeverage(riskPercent: Double): Int {
        val target = floor(10.0 / max(1.0, riskPercent)).toInt()
        return target.coerceIn(2, 10)
    }

    // -----------------------------------------------------------------------------------------
    // TIMEFRAME CONDITION EVALUATION (1m, 5m, 15m, 30m, 1h, 4h, 1d, 1w)
    // -----------------------------------------------------------------------------------------

    private fun evaluateTimeframeConditions(
        smc: SmcAnalysisResult,
        mtfSnapshots: List<TimeframeSmcSnapshot>,
        primaryTf: CandleTimeframe
    ): List<TimeframeCondition> {
        val allTimeframes = listOf(
            CandleTimeframe.M1,
            CandleTimeframe.M5,
            CandleTimeframe.M15,
            CandleTimeframe.M30,
            CandleTimeframe.H1,
            CandleTimeframe.H4,
            CandleTimeframe.D1,
            CandleTimeframe.W1
        )

        val snapshotMap = mtfSnapshots.associateBy { it.timeframe }

        return allTimeframes.map { tf ->
            if (tf == primaryTf) {
                val stance = when (smc.marketStructure.trendState) {
                    MarketTrendState.BULLISH_UPTREND -> "BULLISH"
                    MarketTrendState.BEARISH_DOWNTREND -> "BEARISH"
                    else -> if (smc.marketStructure.isBullishBOSPresent) "BULLISH" else if (smc.marketStructure.isBearishBOSPresent) "BEARISH" else "NEUTRAL"
                }
                TimeframeCondition(
                    timeframe = tf,
                    stance = stance,
                    summary = "${smc.marketStructure.trendState.label} (Primary Timeframe)",
                    trendDescription = smc.marketStructure.trendState.label,
                    keyLevel = smc.priceZones.firstOrNull()?.let { formatPrice(it.centralPrice) }
                )
            } else {
                val snap = snapshotMap[tf]
                if (snap != null) {
                    val stance = if (snap.isBullishBias) "BULLISH" else when (snap.trend) {
                        MarketTrendState.BEARISH_DOWNTREND -> "BEARISH"
                        else -> "NEUTRAL"
                    }
                    TimeframeCondition(
                        timeframe = tf,
                        stance = stance,
                        summary = "${snap.trend.label} • OBs: ${snap.unmitigatedOBCount}, FVGs: ${snap.unfilledFvgCount}",
                        trendDescription = snap.trend.label,
                        keyLevel = (if (stance == "BULLISH") snap.nearestSupport else snap.nearestResistance)?.let { formatPrice(it) }
                    )
                } else {
                    val primaryTrend = smc.marketStructure.trendState
                    val stance = if (tf == CandleTimeframe.W1 || tf == CandleTimeframe.D1) {
                        when (primaryTrend) {
                            MarketTrendState.BULLISH_UPTREND -> "BULLISH"
                            MarketTrendState.BEARISH_DOWNTREND -> "BEARISH"
                            else -> "NEUTRAL"
                        }
                    } else "NEUTRAL"
                    TimeframeCondition(
                        timeframe = tf,
                        stance = stance,
                        summary = "Awaiting snapshot sync",
                        trendDescription = "Synchronizing",
                        keyLevel = null
                    )
                }
            }
        }
    }

    private fun createNoTradeSignal(
        symbol: String,
        assetName: String,
        primaryTimeframe: CandleTimeframe,
        code: String,
        headline: String,
        explanation: String,
        conflictingFactors: List<String>,
        recommendedAction: String,
        dataSource: String,
        freshness: String,
        timestamp: Long,
        formattedTime: String,
        confidence: ConfidenceScoreBreakdown? = null,
        timeframeConditions: List<TimeframeCondition> = emptyList(),
        strictQualityReport: StrictSignalQualityReport? = null
    ): AiTradingSignal {
        val defaultConfidence = confidence ?: ConfidenceScoreBreakdown(
            trendScore = 5,
            momentumScore = 5,
            volumeScore = 5,
            marketStructureScore = 5,
            liquidityScore = 5,
            supportResistanceScore = 5,
            multiTimeframeScore = 5,
            totalScore = 35,
            rating = "No Trade Advisory",
            factors = listOf(
                SignalFactorReason("Safety Check", 100, 35, "CONFLICTING", headline)
            )
        )

        return AiTradingSignal(
            id = "SIG_NOTRADE_${symbol.replace("/", "_")}_$timestamp",
            symbol = symbol,
            assetName = assetName,
            primaryTimeframe = primaryTimeframe,
            decision = SignalDecision.NO_TRADE,
            confidence = defaultConfidence,
            riskPlan = null,
            noTradeReason = NoTradeReason(
                code = code,
                headline = headline,
                detailedExplanation = explanation,
                conflictingFactors = conflictingFactors,
                recommendedAction = recommendedAction
            ),
            supportingIndicators = emptyList(),
            smcConfirmations = emptyList(),
            timeframeConditions = timeframeConditions,
            mtfAgreementCount = 0,
            mtfTotalCount = timeframeConditions.size,
            mtfAgreementPercent = 0,
            timestamp = timestamp,
            formattedTimestamp = formattedTime,
            dataSource = dataSource,
            dataFreshness = freshness,
            strictQualityReport = strictQualityReport
        )
    }

    private fun formatPrice(price: Double): String {
        return when {
            price >= 1000.0 -> String.format(Locale.US, "$%,.2f", price)
            price >= 1.0 -> String.format(Locale.US, "$%.2f", price)
            price >= 0.01 -> String.format(Locale.US, "$%.4f", price)
            else -> String.format(Locale.US, "$%.6f", price)
        }
    }
}
