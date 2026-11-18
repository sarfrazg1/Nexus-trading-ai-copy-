package com.example.data.scanner

import com.example.data.indicators.TechnicalIndicatorsEngine
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.smc.FvgStatus
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcCalculationEngine
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Institutional Multi-Timeframe Confluence Engine for Binance USDT-M Futures.
 * Evaluates 4H, 1H, 15M, and 5M charts to generate verified LONG, SHORT, or NO_TRADE decisions.
 * Computes dynamic Futures take-profit targets targeting at least +10% NET ROI on initial margin
 * after estimated trading fees and reliable funding costs, while enforcing realistic Stop Loss
 * and liquidation safety without ever increasing leverage automatically.
 */
object BinanceFuturesScannerEngine {

    const val DEFAULT_INITIAL_MARGIN_USDT = 100.0
    const val TARGET_NET_ROI_RATIO = 0.10 // +10% NET margin ROI
    const val ENTRY_TAKER_FEE_RATE = 0.0005 // 0.05% per side on notional
    const val EXIT_TAKER_FEE_RATE = 0.0005  // 0.05% per side on notional
    const val MAINTENANCE_MARGIN_RATE = 0.005 // Conservative 0.5% isolated MMR
    const val ESTIMATED_FUNDING_INTERVALS = 1.0 // 1x 8h cycle expected hold horizon
    const val MAX_ALLOWED_LEVERAGE = 10
    const val MIN_REALISTIC_SL_PERCENT = 0.25
    const val MAX_REALISTIC_SL_PERCENT = 8.0
    const val MAX_LIQUIDATION_SL_RATIO = 0.55 // SL must trigger with >=45% buffer before liquidation
    const val MAX_SL_MARGIN_LOSS_PERCENT = 55.0
    const val MAX_MARKET_DATA_AGE_MS = 3 * 60 * 1000L // 3 minutes for LTF scanner
    const val MAX_PRICE_CANDLE_DIVERGENCE = 0.15 // 15% sanity threshold

    data class ScanEvaluationResult(
        val signal: FuturesScanSignal?,
        val isNoTrade: Boolean,
        val noTradeReason: String?
    )

    data class FuturesTargetCalculation(
        val isViable: Boolean,
        val rejectionReason: String? = null,
        val initialMarginUsdt: Double = DEFAULT_INITIAL_MARGIN_USDT,
        val leverage: Int = 1,
        val notionalValueUsdt: Double = 0.0,
        val positionSizeUnits: Double = 0.0,
        val entryPrice: Double = 0.0,
        val stopLoss: Double = 0.0,
        val estimatedLiquidationPrice: Double = 0.0,
        val liquidationDistancePercent: Double = 0.0,
        val slDistancePercent: Double = 0.0,
        val slNetMarginLossPercent: Double = 0.0,
        val takeProfit1: Double = 0.0,
        val takeProfit2: Double = 0.0,
        val takeProfit3: Double = 0.0,
        val tp1NetRoiPercent: Double = 0.0,
        val tp2NetRoiPercent: Double = 0.0,
        val tp3NetRoiPercent: Double = 0.0,
        val tp1GrossRoiPercent: Double = 0.0,
        val tp2GrossRoiPercent: Double = 0.0,
        val estimatedFeeRatePercent: Double = (ENTRY_TAKER_FEE_RATE + EXIT_TAKER_FEE_RATE) * 100.0,
        val estimatedFeeImpactMarginPercent: Double = 0.0,
        val estimatedFundingCostMarginPercent: Double = 0.0,
        val fundingDataReliable: Boolean = false,
        val riskRewardRatio: Double = 0.0
    )

    /**
     * Evaluates a single pair across 4H, 1H, 15M, and 5M candle datasets.
     * Preserves original 8-arg signature while supporting optional margin, leverage cap, and freshness inputs.
     */
    suspend fun evaluatePair(
        symbol: String,
        baseAsset: String,
        currentPrice: Double,
        candles4h: List<Candle>,
        candles1h: List<Candle>,
        candles15m: List<Candle>,
        candles5m: List<Candle>,
        fundingRate: Double? = null,
        initialMarginUsdt: Double = DEFAULT_INITIAL_MARGIN_USDT,
        configuredLeverage: Int? = null,
        isMarketDataStale: Boolean = false,
        dataTimestampMs: Long? = null,
        referenceTimeMs: Long? = null
    ): ScanEvaluationResult {
        // 0. Missing / Stale / Invalid Data Safety Gates
        if (!currentPrice.isFinite() || currentPrice <= 0.0) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Missing or invalid live price for $symbol."
            )
        }
        if (!initialMarginUsdt.isFinite() || initialMarginUsdt <= 0.0) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Invalid initial margin ($initialMarginUsdt) for $symbol."
            )
        }
        if (isMarketDataStale) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Market data for $symbol is stale or unavailable. Returning NO TRADE for execution safety."
            )
        }
        if (dataTimestampMs != null) {
            if (dataTimestampMs <= 0L) {
                return ScanEvaluationResult(
                    signal = null,
                    isNoTrade = true,
                    noTradeReason = "Invalid market data timestamp for $symbol."
                )
            }
            if (referenceTimeMs != null && (referenceTimeMs - dataTimestampMs) > MAX_MARKET_DATA_AGE_MS) {
                val ageSeconds = max(0L, (referenceTimeMs - dataTimestampMs) / 1000L)
                return ScanEvaluationResult(
                    signal = null,
                    isNoTrade = true,
                    noTradeReason = "Market data for $symbol is stale (${ageSeconds}s old). Awaiting fresh live feed."
                )
            }
        }
        if (candles15m.size < 20 || candles1h.size < 20) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Insufficient candle history on $symbol for multi-timeframe analysis."
            )
        }
        if (!hasValidCandles(candles15m) || !hasValidCandles(candles1h) ||
            (candles4h.isNotEmpty() && !hasValidCandles(candles4h)) ||
            (candles5m.isNotEmpty() && !hasValidCandles(candles5m))
        ) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Corrupted or non-finite OHLCV candle data detected on $symbol."
            )
        }
        val latestClose15m = candles15m.last().close
        val priceDivergence = abs(currentPrice - latestClose15m) / latestClose15m
        if (priceDivergence > MAX_PRICE_CANDLE_DIVERGENCE) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Stale candle/ticker mismatch on $symbol (${String.format(Locale.US, "%.1f", priceDivergence * 100.0)}% divergence)."
            )
        }

        // 1. Analyze 4H & 1H for Macro Direction & Higher-Timeframe (HTF) Key Zones
        val tech1h = TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.H1, candles1h)
        val smc1h = SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.H1, candles1h)

        val tech4h = if (candles4h.size >= 15) {
            TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.H4, candles4h)
        } else tech1h

        val smc4h = if (candles4h.size >= 15) {
            SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.H4, candles4h)
        } else smc1h

        // 2. Analyze 15M & 5M for Execution Timing & Lower-Timeframe (LTF) Confirmation
        val tech15m = TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.M15, candles15m)
        val smc15m = SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.M15, candles15m)

        val tech5m = if (candles5m.size >= 15) {
            TechnicalIndicatorsEngine.analyze(symbol, baseAsset, CandleTimeframe.M5, candles5m)
        } else tech15m

        val smc5m = if (candles5m.size >= 15) {
            SmcCalculationEngine.analyze(symbol, baseAsset, CandleTimeframe.M5, candles5m)
        } else smc15m

        // Extract key indicators
        val ema50_1h = tech1h.trend.emaSet.ema50 ?: currentPrice
        val ema200_1h = tech1h.trend.emaSet.ema200 ?: currentPrice
        val rsi15m = tech15m.momentum.rsi14 ?: 50.0
        val rsi1h = tech1h.momentum.rsi14 ?: 50.0
        val atr15m = tech15m.volatility.atr?.value ?: (currentPrice * 0.015)
        val atr1h = tech1h.volatility.atr?.value ?: (atr15m * 2.0)
        val atrPct = if (currentPrice > 0) (atr15m / currentPrice) * 100.0 else 1.5

        // HTF Bias
        val isHtfBullish = currentPrice >= ema50_1h && (ema50_1h >= ema200_1h || currentPrice >= ema200_1h)
        val isHtfBearish = currentPrice <= ema50_1h && (ema50_1h <= ema200_1h || currentPrice <= ema200_1h)

        // LTF Structure
        val hasBullishChoch = smc15m.marketStructure.structureBreaks.any { it.type.name.contains("CHOCH") && it.type.isBullish } ||
            smc5m.marketStructure.structureBreaks.any { it.type.name.contains("CHOCH") && it.type.isBullish }
        val hasBearishChoch = smc15m.marketStructure.structureBreaks.any { it.type.name.contains("CHOCH") && !it.type.isBullish } ||
            smc5m.marketStructure.structureBreaks.any { it.type.name.contains("CHOCH") && !it.type.isBullish }

        val hasBullishFvg = smc15m.fairValueGaps.any { it.type.isBullish && it.status == FvgStatus.UNFILLED } ||
            smc1h.fairValueGaps.any { it.type.isBullish && it.status == FvgStatus.UNFILLED }
        val hasBearishFvg = smc15m.fairValueGaps.any { !it.type.isBullish && it.status == FvgStatus.UNFILLED } ||
            smc1h.fairValueGaps.any { !it.type.isBullish && it.status == FvgStatus.UNFILLED }

        // Find nearest Support & Resistance / Order Blocks
        val nearestSupport = smc1h.priceZones
            .filter { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }
            .filter { it.centralPrice < currentPrice }
            .minByOrNull { abs(currentPrice - it.centralPrice) }?.centralPrice
            ?: (currentPrice - (atr15m * 2.0))

        val nearestResistance = smc1h.priceZones
            .filter { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }
            .filter { it.centralPrice > currentPrice }
            .minByOrNull { abs(it.centralPrice - currentPrice) }?.centralPrice
            ?: (currentPrice + (atr15m * 2.0))

        // Timeframe stances breakdown
        val stance4h = TimeframeStance(
            timeframe = CandleTimeframe.H4,
            trend = if (candles4h.isNotEmpty() && currentPrice > (tech4h.trend.emaSet.ema50 ?: currentPrice)) "Bullish" else "Bearish",
            structure = smc4h.marketStructure.trendState.label,
            isConfirmed = true,
            detail = "4H Macro Trend: ${smc4h.marketStructure.trendState.label}"
        )

        val stance1h = TimeframeStance(
            timeframe = CandleTimeframe.H1,
            trend = if (isHtfBullish) "Bullish" else if (isHtfBearish) "Bearish" else "Consolidation",
            structure = "EMA 50: ${formatPrice(ema50_1h)}",
            isConfirmed = true,
            detail = "1H Alignment: RSI ${String.format(Locale.US, "%.1f", rsi1h)}"
        )

        val stance15m = TimeframeStance(
            timeframe = CandleTimeframe.M15,
            trend = if ((tech15m.momentum.rsi14 ?: 50.0) > 50) "Bullish Momentum" else "Bearish Momentum",
            structure = if (hasBullishChoch) "Bullish CHoCH" else if (hasBearishChoch) "Bearish CHoCH" else "Structural Range",
            isConfirmed = hasBullishChoch || hasBearishChoch,
            detail = "15M Structure: ATR ${formatPrice(atr15m)}"
        )

        val stance5m = TimeframeStance(
            timeframe = CandleTimeframe.M5,
            trend = if ((tech5m.momentum.rsi14 ?: 50.0) > 50) "Bullish" else "Bearish",
            structure = "Execution Precision Zone",
            isConfirmed = true,
            detail = "5M Volume: RVOL ${String.format(Locale.US, "%.2fx", tech5m.volume.relativeVolume ?: 1.0)}"
        )

        val mtfStances = listOf(stance4h, stance1h, stance15m, stance5m)

        // Evaluate Confluence Scores
        var bullishScore = 0
        var bearishScore = 0
        val reasons = mutableListOf<String>()

        // 1. HTF Trend & EMA Confluence (25 pts)
        if (isHtfBullish) {
            bullishScore += 25
            reasons.add("1H/4H Macro Trend is Bullish (Price above key 50/200 EMAs)")
        } else if (isHtfBearish) {
            bearishScore += 25
            reasons.add("1H/4H Macro Trend is Bearish (Price below key 50/200 EMAs)")
        }

        // 2. SMC Market Structure Break (25 pts)
        if (hasBullishChoch) {
            bullishScore += 25
            reasons.add("Confirmed Bullish Change of Character (CHoCH) on lower timeframe")
        }
        if (hasBearishChoch) {
            bearishScore += 25
            reasons.add("Confirmed Bearish Change of Character (CHoCH) on lower timeframe")
        }

        // 3. Fair Value Gaps & Liquidity (15 pts)
        if (hasBullishFvg) {
            bullishScore += 15
            reasons.add("Active Unfilled Bullish FVG providing demand support")
        }
        if (hasBearishFvg) {
            bearishScore += 15
            reasons.add("Active Unfilled Bearish FVG providing supply resistance")
        }

        // 4. Momentum & RSI Health (15 pts)
        if (rsi15m in 35.0..65.0) {
            if (isHtfBullish) {
                bullishScore += 15
                reasons.add("15M RSI (${String.format(Locale.US, "%.1f", rsi15m)}) healthy with room for upward expansion")
            } else if (isHtfBearish) {
                bearishScore += 15
                reasons.add("15M RSI (${String.format(Locale.US, "%.1f", rsi15m)}) aligned for continuation breakdown")
            }
        }

        // 5. Volume Confirmation (10 pts)
        val rvol = tech15m.volume.relativeVolume ?: 1.0
        if (rvol >= 1.15) {
            if (isHtfBullish) bullishScore += 10 else bearishScore += 10
            reasons.add("Elevated Volume Confirmation: RVOL ${String.format(Locale.US, "%.2fx", rvol)}")
        }

        // Determine Direction & Confluence
        val isLongEligible = bullishScore >= 60 && bullishScore > bearishScore + 15
        val isShortEligible = bearishScore >= 60 && bearishScore > bullishScore + 15

        if (!isLongEligible && !isShortEligible) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = "Conflicting multi-timeframe conditions on $symbol (Bullish $bullishScore vs Bearish $bearishScore). Awaiting clear structural alignment."
            )
        }

        val direction = if (isLongEligible) FuturesDirection.LONG else FuturesDirection.SHORT
        val totalScore = if (direction == FuturesDirection.LONG) bullishScore.coerceAtMost(96) else bearishScore.coerceAtMost(96)

        val qualityRating = when {
            totalScore >= 85 -> "A+ Institutional Confluence"
            totalScore >= 70 -> "High Quality Multi-Timeframe Setup"
            else -> "Moderate Confluence"
        }

        // Volatility-disciplined leverage (NEVER auto-increased to force a target)
        val volatilitySafeLeverage = when {
            atrPct > 4.0 -> 2
            atrPct > 2.5 -> 3
            else -> 5
        }
        val actualLeverage = if (configuredLeverage != null) {
            if (configuredLeverage !in 1..MAX_ALLOWED_LEVERAGE) {
                return ScanEvaluationResult(
                    signal = null,
                    isNoTrade = true,
                    noTradeReason = "Configured leverage (${configuredLeverage}x) on $symbol is outside safe bounds (1x-${MAX_ALLOWED_LEVERAGE}x)."
                )
            }
            configuredLeverage
        } else {
            volatilitySafeLeverage
        }

        // Calculate Entry Zone & Structural Stop Loss
        val entryZoneBuffer = atr15m * 0.4
        val entryPrice = currentPrice
        val entryMin: Double
        val entryMax: Double
        val structuralSL: Double

        if (direction == FuturesDirection.LONG) {
            entryMin = currentPrice - entryZoneBuffer
            entryMax = currentPrice + (entryZoneBuffer * 0.5)
            structuralSL = min(nearestSupport, currentPrice - (atr15m * 1.3))
        } else {
            entryMin = currentPrice - (entryZoneBuffer * 0.5)
            entryMax = currentPrice + entryZoneBuffer
            structuralSL = max(nearestResistance, currentPrice + (atr15m * 1.3))
        }

        // Compute dynamic leverage-aware +10% NET margin ROI targets & liquidation safety
        val targetCalc = calculateFuturesTargets(
            direction = direction,
            entryPrice = entryPrice,
            structuralStopLoss = structuralSL,
            atr15m = atr15m,
            atr1h = atr1h,
            leverage = actualLeverage,
            initialMarginUsdt = initialMarginUsdt,
            fundingRatePercent = fundingRate
        )

        if (!targetCalc.isViable) {
            return ScanEvaluationResult(
                signal = null,
                isNoTrade = true,
                noTradeReason = targetCalc.rejectionReason
                    ?: "Futures margin/liquidation risk check failed on $symbol. Returning NO TRADE."
            )
        }

        val fundingLabel = if (targetCalc.fundingDataReliable) {
            "Funding ${String.format(Locale.US, "%+.4f%%", fundingRate)} (${String.format(Locale.US, "-%.2f%%", targetCalc.estimatedFundingCostMarginPercent)} margin est.)"
        } else {
            "Funding N/A (excluded)"
        }

        reasons.add(
            "Margin & Position Plan: $${String.format(Locale.US, "%.2f", targetCalc.initialMarginUsdt)} margin @ ${targetCalc.leverage}x = $${String.format(Locale.US, "%.2f", targetCalc.notionalValueUsdt)} (${formatUnits(targetCalc.positionSizeUnits)} $baseAsset)"
        )
        reasons.add(
            "Estimated Net ROI (after ${String.format(Locale.US, "%.2f%%", targetCalc.estimatedFeeImpactMarginPercent)} fees & $fundingLabel): TP1 +${String.format(Locale.US, "%.1f", targetCalc.tp1NetRoiPercent)}% | TP2 +${String.format(Locale.US, "%.1f", targetCalc.tp2NetRoiPercent)}% | TP3 +${String.format(Locale.US, "%.1f", targetCalc.tp3NetRoiPercent)}%"
        )
        reasons.add(
            "Liquidation Safety: Est. Liq ${formatPrice(targetCalc.estimatedLiquidationPrice)} (${String.format(Locale.US, "%.1f", targetCalc.liquidationDistancePercent)}% move) vs SL ${formatPrice(targetCalc.stopLoss)} (-${String.format(Locale.US, "%.2f", targetCalc.slDistancePercent)}% / ${String.format(Locale.US, "%.1f", targetCalc.slNetMarginLossPercent)}% margin)"
        )

        val invalidation = if (direction == FuturesDirection.LONG) {
            "15M candle body close below ${formatPrice(targetCalc.stopLoss)} (-${String.format(Locale.US, "%.2f", targetCalc.slDistancePercent)}% / ${String.format(Locale.US, "%.1f", targetCalc.slNetMarginLossPercent)}% margin) — triggers safely before est. liquidation at ${formatPrice(targetCalc.estimatedLiquidationPrice)}."
        } else {
            "15M candle body close above ${formatPrice(targetCalc.stopLoss)} (-${String.format(Locale.US, "%.2f", targetCalc.slDistancePercent)}% / ${String.format(Locale.US, "%.1f", targetCalc.slNetMarginLossPercent)}% margin) — triggers safely before est. liquidation at ${formatPrice(targetCalc.estimatedLiquidationPrice)}."
        }

        // Determine Lifecycle State:
        // If price is within entry zone and score >= 68: ENTRY_CONFIRMED
        // Otherwise if approaching zone: WATCHLIST_SETUP
        val isInsideEntryZone = currentPrice in (entryMin * 0.999)..(entryMax * 1.001)
        val lifecycle = if (isInsideEntryZone && totalScore >= 68) {
            FuturesSignalLifecycle.ENTRY_CONFIRMED
        } else {
            FuturesSignalLifecycle.WATCHLIST_SETUP
        }

        val signal = FuturesScanSignal(
            symbol = symbol,
            baseAsset = baseAsset,
            direction = direction,
            currentPrice = currentPrice,
            entryZoneMin = entryMin,
            entryZoneMax = entryMax,
            entryPrice = entryPrice,
            stopLoss = targetCalc.stopLoss,
            takeProfit1 = targetCalc.takeProfit1,
            takeProfit2 = targetCalc.takeProfit2,
            takeProfit3 = targetCalc.takeProfit3,
            riskRewardRatio = targetCalc.riskRewardRatio,
            qualityScore = totalScore,
            qualityRating = qualityRating,
            timeframeConfirmations = mtfStances,
            reasonsForEntry = reasons,
            invalidationCondition = invalidation,
            lifecycle = lifecycle,
            suggestedLeverage = targetCalc.leverage,
            atrVolatilityPercent = atrPct,
            fundingRatePercent = if (targetCalc.fundingDataReliable) fundingRate else null,
            maxPriceReached = currentPrice,
            minPriceReached = currentPrice
        )

        return ScanEvaluationResult(
            signal = signal,
            isNoTrade = false,
            noTradeReason = null
        )
    }

    /**
     * Pure deterministic Futures target & risk calculator.
     * Computes LONG / SHORT TP1, TP2, TP3 using actual leverage, entry price, position size,
     * initial margin, estimated round-trip fees, and reliable funding cost to achieve >= +10%
     * net ROI on initial margin while validating realistic Stop Loss and liquidation safety.
     */
    fun calculateFuturesTargets(
        direction: FuturesDirection,
        entryPrice: Double,
        structuralStopLoss: Double,
        atr15m: Double,
        atr1h: Double = atr15m * 2.0,
        leverage: Int,
        initialMarginUsdt: Double = DEFAULT_INITIAL_MARGIN_USDT,
        fundingRatePercent: Double? = null,
        entryFeeRate: Double = ENTRY_TAKER_FEE_RATE,
        exitFeeRate: Double = EXIT_TAKER_FEE_RATE,
        targetNetRoiRatio: Double = TARGET_NET_ROI_RATIO
    ): FuturesTargetCalculation {
        if (!entryPrice.isFinite() || entryPrice <= 0.0 ||
            !structuralStopLoss.isFinite() || structuralStopLoss <= 0.0 ||
            !atr15m.isFinite() || atr15m <= 0.0 ||
            !initialMarginUsdt.isFinite() || initialMarginUsdt <= 0.0 ||
            leverage !in 1..MAX_ALLOWED_LEVERAGE
        ) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Invalid Futures calculation inputs (entry=$entryPrice, SL=$structuralStopLoss, margin=$initialMarginUsdt, lev=$leverage)."
            )
        }

        if (direction == FuturesDirection.LONG && structuralStopLoss >= entryPrice) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Invalid LONG stop loss: SL must be strictly below entry."
            )
        }
        if (direction == FuturesDirection.SHORT && structuralStopLoss <= entryPrice) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Invalid SHORT stop loss: SL must be strictly above entry."
            )
        }

        val notionalValueUsdt = initialMarginUsdt * leverage.toDouble()
        val positionSizeUnits = notionalValueUsdt / entryPrice

        // Conservative isolated liquidation distance & price
        val maxLiqMoveFraction = (1.0 / leverage.toDouble()) - MAINTENANCE_MARGIN_RATE - entryFeeRate - exitFeeRate
        if (maxLiqMoveFraction <= 0.01) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Leverage ${leverage}x leaves insufficient margin before liquidation."
            )
        }
        val estimatedLiqPrice = if (direction == FuturesDirection.LONG) {
            entryPrice * (1.0 - maxLiqMoveFraction)
        } else {
            entryPrice * (1.0 + maxLiqMoveFraction)
        }
        val liqDistancePercent = maxLiqMoveFraction * 100.0

        val riskDistance = abs(entryPrice - structuralStopLoss)
        val slDistancePercent = (riskDistance / entryPrice) * 100.0

        if (slDistancePercent < MIN_REALISTIC_SL_PERCENT) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Stop loss distance (${String.format(Locale.US, "%.2f", slDistancePercent)}%) is below minimum ${MIN_REALISTIC_SL_PERCENT}% noise threshold."
            )
        }
        if (slDistancePercent > MAX_REALISTIC_SL_PERCENT) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Structural stop loss distance (${String.format(Locale.US, "%.2f", slDistancePercent)}%) exceeds maximum ${MAX_REALISTIC_SL_PERCENT}% risk cap."
            )
        }
        if (slDistancePercent > liqDistancePercent * MAX_LIQUIDATION_SL_RATIO) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Stop loss (-${String.format(Locale.US, "%.2f", slDistancePercent)}%) is too close to estimated liquidation (-${String.format(Locale.US, "%.2f", liqDistancePercent)}%) at ${leverage}x leverage."
            )
        }

        // Reliable funding check (Binance premiumIndex lastFundingRate * 100, typically -0.75%..+0.75%)
        val fundingDataReliable = fundingRatePercent != null &&
            fundingRatePercent.isFinite() &&
            abs(fundingRatePercent) <= 5.0

        val signedFundingCostUsdt = if (fundingDataReliable) {
            val fundingDecimal = fundingRatePercent!! / 100.0
            if (direction == FuturesDirection.LONG) {
                notionalValueUsdt * fundingDecimal * ESTIMATED_FUNDING_INTERVALS
            } else {
                notionalValueUsdt * (-fundingDecimal) * ESTIMATED_FUNDING_INTERVALS
            }
        } else 0.0

        // Reserve only adverse funding costs when setting target floor; never weaken TP on uncertain funding rebates
        val adverseFundingReserveUsdt = max(0.0, signedFundingCostUsdt)

        fun computeGrossRoiPct(exitPrice: Double): Double {
            val grossPnl = if (direction == FuturesDirection.LONG) {
                (exitPrice - entryPrice) * positionSizeUnits
            } else {
                (entryPrice - exitPrice) * positionSizeUnits
            }
            return (grossPnl / initialMarginUsdt) * 100.0
        }

        fun computeNetRoiPct(exitPrice: Double): Double {
            val grossPnl = if (direction == FuturesDirection.LONG) {
                (exitPrice - entryPrice) * positionSizeUnits
            } else {
                (entryPrice - exitPrice) * positionSizeUnits
            }
            val entryFee = entryPrice * positionSizeUnits * entryFeeRate
            val exitFee = exitPrice * positionSizeUnits * exitFeeRate
            val netPnl = grossPnl - entryFee - exitFee - adverseFundingReserveUsdt
            return (netPnl / initialMarginUsdt) * 100.0
        }

        val slNetMarginLossPct = computeNetRoiPct(structuralStopLoss)
        if (slNetMarginLossPct < -MAX_SL_MARGIN_LOSS_PERCENT) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Stop loss margin loss (${String.format(Locale.US, "%.1f", slNetMarginLossPct)}%) exceeds maximum -${MAX_SL_MARGIN_LOSS_PERCENT}% initial margin safety limit at ${leverage}x."
            )
        }

        // Solve exact exit price yielding targetNetRoiRatio (+10% net ROI on initial margin)
        val requiredNetProfitUsdt = initialMarginUsdt * targetNetRoiRatio
        val roiTargetPrice = if (direction == FuturesDirection.LONG) {
            (entryPrice * (1.0 + entryFeeRate) + (requiredNetProfitUsdt + adverseFundingReserveUsdt) / positionSizeUnits) /
                (1.0 - exitFeeRate)
        } else {
            (entryPrice * (1.0 - entryFeeRate) - (requiredNetProfitUsdt + adverseFundingReserveUsdt) / positionSizeUnits) /
                (1.0 + exitFeeRate)
        }

        if (!roiTargetPrice.isFinite() || roiTargetPrice <= 0.0) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Cannot achieve +${String.format(Locale.US, "%.0f", targetNetRoiRatio * 100.0)}% net ROI at ${leverage}x leverage within valid price bounds."
            )
        }

        val roiTargetDistance = abs(roiTargetPrice - entryPrice)
        val maxReachableDistance = max(atr1h * 4.5, atr15m * 8.0).coerceAtMost(entryPrice * 0.25)
        if (roiTargetDistance > maxReachableDistance) {
            val neededPct = (roiTargetDistance / entryPrice) * 100.0
            val maxPct = (maxReachableDistance / entryPrice) * 100.0
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "+10% net ROI requires ${String.format(Locale.US, "%.2f", neededPct)}% move at ${leverage}x leverage, exceeding realistic volatility target range (${String.format(Locale.US, "%.2f", maxPct)}%)."
            )
        }

        // Dynamic TP1 / TP2 / TP3: TP1 guarantees >= +10% NET ROI AND >= 1.5R; TP2 & TP3 scale dynamically
        val tp1Distance = max(riskDistance * 1.5, roiTargetDistance)
        val tp2Distance = max(riskDistance * 2.8, tp1Distance + max(riskDistance * 0.8, atr15m * 0.5))
        val tp3Distance = max(riskDistance * 4.2, tp2Distance + max(riskDistance * 1.0, atr15m * 0.8))

        val tp1: Double
        val tp2: Double
        val tp3: Double
        if (direction == FuturesDirection.LONG) {
            tp1 = entryPrice + tp1Distance
            tp2 = entryPrice + tp2Distance
            tp3 = entryPrice + tp3Distance
        } else {
            tp1 = entryPrice - tp1Distance
            tp2 = entryPrice - tp2Distance
            tp3 = entryPrice - tp3Distance
            if (tp1 <= 0.0 || tp2 <= 0.0 || tp3 <= 0.0) {
                return FuturesTargetCalculation(
                    isViable = false,
                    rejectionReason = "Calculated SHORT take-profit levels fall below zero."
                )
            }
        }

        val riskReward = tp2Distance / riskDistance
        if (riskReward < 1.8) {
            return FuturesTargetCalculation(
                isViable = false,
                rejectionReason = "Setup Risk-to-Reward (${String.format(Locale.US, "1:%.2f", riskReward)}) is below required 1:1.80 threshold."
            )
        }

        val tp1EntryExitFeesPct = ((entryPrice * positionSizeUnits * entryFeeRate + tp1 * positionSizeUnits * exitFeeRate) / initialMarginUsdt) * 100.0
        val fundingCostMarginPct = (adverseFundingReserveUsdt / initialMarginUsdt) * 100.0

        return FuturesTargetCalculation(
            isViable = true,
            initialMarginUsdt = initialMarginUsdt,
            leverage = leverage,
            notionalValueUsdt = notionalValueUsdt,
            positionSizeUnits = positionSizeUnits,
            entryPrice = entryPrice,
            stopLoss = structuralStopLoss,
            estimatedLiquidationPrice = estimatedLiqPrice,
            liquidationDistancePercent = liqDistancePercent,
            slDistancePercent = slDistancePercent,
            slNetMarginLossPercent = slNetMarginLossPct,
            takeProfit1 = tp1,
            takeProfit2 = tp2,
            takeProfit3 = tp3,
            tp1NetRoiPercent = computeNetRoiPct(tp1),
            tp2NetRoiPercent = computeNetRoiPct(tp2),
            tp3NetRoiPercent = computeNetRoiPct(tp3),
            tp1GrossRoiPercent = computeGrossRoiPct(tp1),
            tp2GrossRoiPercent = computeGrossRoiPct(tp2),
            estimatedFeeRatePercent = (entryFeeRate + exitFeeRate) * 100.0,
            estimatedFeeImpactMarginPercent = tp1EntryExitFeesPct,
            estimatedFundingCostMarginPercent = fundingCostMarginPct,
            fundingDataReliable = fundingDataReliable,
            riskRewardRatio = riskReward
        )
    }

    private fun hasValidCandles(candles: List<Candle>): Boolean {
        if (candles.isEmpty()) return false
        var prevOpenTime = Long.MIN_VALUE
        for (c in candles) {
            if (!c.open.isFinite() || !c.high.isFinite() || !c.low.isFinite() || !c.close.isFinite() || !c.volume.isFinite()) {
                return false
            }
            if (c.open <= 0.0 || c.high <= 0.0 || c.low <= 0.0 || c.close <= 0.0 || c.volume < 0.0) {
                return false
            }
            if (c.high < c.low) return false
            if (c.openTime < prevOpenTime) return false
            prevOpenTime = c.openTime
        }
        return true
    }

    private fun formatPrice(price: Double): String {
        return when {
            price >= 1000.0 -> String.format(Locale.US, "$%.2f", price)
            price >= 1.0 -> String.format(Locale.US, "$%.2f", price)
            price >= 0.01 -> String.format(Locale.US, "$%.4f", price)
            else -> String.format(Locale.US, "$%.6f", price)
        }
    }

    private fun formatUnits(units: Double): String {
        return when {
            units >= 100.0 -> String.format(Locale.US, "%.2f", units)
            units >= 1.0 -> String.format(Locale.US, "%.3f", units)
            else -> String.format(Locale.US, "%.4f", units)
        }
    }
}

