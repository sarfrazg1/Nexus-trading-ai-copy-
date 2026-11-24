package com.example.data.scanner

import com.example.data.indicators.OverallAnalysisResult
import com.example.data.model.Candle
import com.example.data.smc.FvgStatus
import com.example.data.smc.PriceZoneType
import com.example.data.smc.SmcAnalysisResult
import com.example.util.TimeZoneUtils
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Crypto & Binance Futures chart setup patterns adapted from trader-scanner methodology
 * (24/7 crypto UTC session openings instead of NYSE stock hours).
 * A pattern match is a potential setup only — NEVER a guaranteed trade.
 */
enum class CryptoSetupPattern(
    val label: String,
    val badgeCode: String,
    val description: String
) {
    BREAKOUT(
        label = "Range Breakout",
        badgeCode = "BRK",
        description = "Completed candle close beyond multi-bar swing range"
    ),
    CRYPTO_SESSION_ORB(
        label = "UTC Session Range Breakout",
        badgeCode = "UTC-ORB",
        description = "Close breakout beyond 24/7 crypto UTC session opening range (00:00/4H UTC)"
    ),
    BULLISH_FLAG(
        label = "Bullish Flag",
        badgeCode = "BULL-FLAG",
        description = "Upward impulse pole followed by tight consolidation breaking higher"
    ),
    BEARISH_FLAG(
        label = "Bearish Flag",
        badgeCode = "BEAR-FLAG",
        description = "Downward impulse pole followed by tight counter-drift breaking lower"
    ),
    OVERSOLD_BOUNCE(
        label = "Oversold Bounce",
        badgeCode = "OS-BOUNCE",
        description = "RSI oversold exhaustion / lower Bollinger reclaim with bullish close"
    ),
    SR_BREAKOUT(
        label = "S/R Structure Breakout",
        badgeCode = "S/R-BRK",
        description = "Completed candle close through SMC Support/Resistance structure"
    ),
    VOLUME_CONFIRMED_MOVE(
        label = "Volume-Confirmed Move",
        badgeCode = "VOL-EXP",
        description = "Directional body expansion backed by elevated relative volume (RVOL >= 1.5x)"
    ),
    TREND_CONTINUATION(
        label = "Trend Continuation",
        badgeCode = "TREND-CONT",
        description = "HTF-aligned pullback near EMA20/EMA50 or Fib Golden Pocket resuming trend"
    ),
    POTENTIAL_REVERSAL(
        label = "Potential Reversal",
        badgeCode = "REV-CHOCH",
        description = "Structural CHoCH shift following liquidity sweep or RSI divergence"
    ),
    MTF_CONFLUENCE(
        label = "Multi-Timeframe Confluence",
        badgeCode = "MTF",
        description = "Multi-timeframe 4H/1H/15M momentum and SMC alignment"
    )
}

data class PatternDetectionResult(
    val pattern: CryptoSetupPattern,
    val direction: FuturesDirection?,
    val breakoutLevel: Double,
    val invalidationLevel: Double,
    val detectedCandleIndex: Int,
    val detectedCandleTimeMs: Long,
    val reasons: List<String>,
    val allDetectedPatterns: List<CryptoSetupPattern> = listOf(pattern),
    val disclaimer: String = SCANNER_SETUP_DISCLAIMER
) {
    companion object {
        const val SCANNER_SETUP_DISCLAIMER =
            "Potential setup only — not a guaranteed trade. Verify candle confirmation and risk limits."
    }
}

object CryptoPatternDetector {

    /**
     * Scans completed 15M primary candles (+ optional 1H/4H, TA, SMC, Fib context) for crypto chart patterns.
     */
    fun detect(
        candles15m: List<Candle>,
        tech15m: OverallAnalysisResult,
        smc15m: SmcAnalysisResult,
        tech1h: OverallAnalysisResult? = null,
        smc1h: SmcAnalysisResult? = null,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): PatternDetectionResult? {
        val completed = TimeZoneUtils.filterCompletedCandles(candles15m, referenceTimeMs)
        if (completed.size < 20) return null

        val lastIdx = completed.lastIndex
        val latest = completed.last()
        val prev = completed[lastIdx - 1]
        val rsi15 = tech15m.momentum.rsi14 ?: 50.0
        val macd = tech15m.momentum.macd
        val ema9 = tech15m.trend.emaSet.ema9 ?: latest.close
        val ema20 = tech15m.trend.emaSet.ema20 ?: latest.close
        val ema50 = tech15m.trend.emaSet.ema50 ?: latest.close
        val atr15 = tech15m.volatility.atr?.value?.takeIf { it > 0.0 } ?: (latest.close * 0.012)
        val rvol = tech15m.volume.relativeVolume ?: 1.0

        val window20 = completed.subList(max(0, lastIdx - 20), lastIdx)
        val swingHigh20 = window20.maxOf { it.high }
        val swingLow20 = window20.minOf { it.low }

        val nearestSupport = (smc1h ?: smc15m).priceZones
            .filter { it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND }
            .filter { it.centralPrice < latest.close }
            .maxByOrNull { it.centralPrice }?.centralPrice
            ?: swingLow20

        val nearestResistance = (smc1h ?: smc15m).priceZones
            .filter { it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY }
            .filter { it.centralPrice > latest.close }
            .minByOrNull { it.centralPrice }?.centralPrice
            ?: swingHigh20

        val detectedTags = mutableListOf<CryptoSetupPattern>()
        val reasons = mutableListOf<String>()

        var primaryPattern: CryptoSetupPattern? = null
        var primaryDirection: FuturesDirection? = null
        var breakoutLevel = latest.close
        var invalidationLevel = latest.low

        // 1. Bullish Flag / Bearish Flag (pole + tight consolidation + breakout)
        if (completed.size >= 16) {
            val poleBars = completed.subList(max(0, lastIdx - 14), lastIdx - 5)
            val flagBars = completed.subList(lastIdx - 5, lastIdx)
            if (poleBars.size >= 4 && flagBars.size >= 3) {
                val poleStart = poleBars.first().open
                val poleEnd = poleBars.last().close
                val poleMove = poleEnd - poleStart
                val flagHigh = flagBars.maxOf { it.high }
                val flagLow = flagBars.minOf { it.low }
                val flagRange = flagHigh - flagLow

                if (poleMove >= atr15 * 1.6 && flagRange <= abs(poleMove) * 0.65 && latest.close > flagHigh) {
                    detectedTags.add(CryptoSetupPattern.BULLISH_FLAG)
                    primaryPattern = CryptoSetupPattern.BULLISH_FLAG
                    primaryDirection = FuturesDirection.LONG
                    breakoutLevel = flagHigh
                    invalidationLevel = min(flagLow, latest.close - atr15 * 1.2)
                    reasons.add("Bullish Flag breakout: pole +${fmt(poleMove)} followed by compressed flag above ${fmt(flagHigh)}")
                } else if (-poleMove >= atr15 * 1.6 && flagRange <= abs(poleMove) * 0.65 && latest.close < flagLow) {
                    detectedTags.add(CryptoSetupPattern.BEARISH_FLAG)
                    primaryPattern = CryptoSetupPattern.BEARISH_FLAG
                    primaryDirection = FuturesDirection.SHORT
                    breakoutLevel = flagLow
                    invalidationLevel = max(flagHigh, latest.close + atr15 * 1.2)
                    reasons.add("Bearish Flag breakdown: impulse -${fmt(abs(poleMove))} then tight flag break below ${fmt(flagLow)}")
                }
            }
        }

        // 2. 24/7 Crypto UTC Session Opening Range Breakout (00:00 UTC daily or 4H UTC session open)
        val orb = detectCryptoSessionOrb(completed, atr15)
        if (orb != null) {
            detectedTags.add(CryptoSetupPattern.CRYPTO_SESSION_ORB)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.CRYPTO_SESSION_ORB
                primaryDirection = orb.first
                breakoutLevel = orb.second
                invalidationLevel = orb.third
            }
            reasons.add("24/7 Crypto UTC session opening range breakout beyond ${fmt(orb.second)}")
        }

        // 3. Oversold Bounce Setup
        val minRecentRsi = rsi15
        val lowerBand = tech15m.volatility.bollingerBands?.lower
        val bullishBounceCandle = latest.close > latest.open && (latest.close >= ema9 || prev.low <= (lowerBand ?: prev.low))
        if (minRecentRsi <= 34.0 && bullishBounceCandle) {
            detectedTags.add(CryptoSetupPattern.OVERSOLD_BOUNCE)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.OVERSOLD_BOUNCE
                primaryDirection = FuturesDirection.LONG
                breakoutLevel = max(prev.high, ema9)
                invalidationLevel = min(latest.low, swingLow20) - atr15 * 0.35
            }
            reasons.add("Oversold bounce setup (15M RSI ${String.format(Locale.US, "%.1f", rsi15)}) with bullish reclaim close")
        }

        // 4. Support / Resistance & 20-Bar Range Breakouts (completed close only)
        if (latest.close > swingHigh20 && prev.close <= swingHigh20) {
            detectedTags.add(CryptoSetupPattern.BREAKOUT)
            detectedTags.add(CryptoSetupPattern.SR_BREAKOUT)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.BREAKOUT
                primaryDirection = FuturesDirection.LONG
                breakoutLevel = swingHigh20
                invalidationLevel = min(nearestSupport, swingHigh20 - atr15 * 1.2)
            }
            reasons.add("20-bar resistance breakout confirmed on completed 15M close above ${fmt(swingHigh20)}")
        } else if (latest.close < swingLow20 && prev.close >= swingLow20) {
            detectedTags.add(CryptoSetupPattern.BREAKOUT)
            detectedTags.add(CryptoSetupPattern.SR_BREAKOUT)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.BREAKOUT
                primaryDirection = FuturesDirection.SHORT
                breakoutLevel = swingLow20
                invalidationLevel = max(nearestResistance, swingLow20 + atr15 * 1.2)
            }
            reasons.add("20-bar support breakdown confirmed on completed 15M close below ${fmt(swingLow20)}")
        } else if (smc15m.priceZones.any { (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY) && latest.close > it.upperPrice && prev.close <= it.upperPrice }) {
            detectedTags.add(CryptoSetupPattern.SR_BREAKOUT)
            val zone = smc15m.priceZones.first { (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY) && latest.close > it.upperPrice && prev.close <= it.upperPrice }
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.SR_BREAKOUT
                primaryDirection = FuturesDirection.LONG
                breakoutLevel = zone.upperPrice
                invalidationLevel = zone.lowerPrice - atr15 * 0.4
            }
            reasons.add("SMC Supply/Resistance breakout close above ${fmt(zone.upperPrice)}")
        } else if (smc15m.priceZones.any { (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND) && latest.close < it.lowerPrice && prev.close >= it.lowerPrice }) {
            detectedTags.add(CryptoSetupPattern.SR_BREAKOUT)
            val zone = smc15m.priceZones.first { (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND) && latest.close < it.lowerPrice && prev.close >= it.lowerPrice }
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.SR_BREAKOUT
                primaryDirection = FuturesDirection.SHORT
                breakoutLevel = zone.lowerPrice
                invalidationLevel = zone.upperPrice + atr15 * 0.4
            }
            reasons.add("SMC Demand/Support breakdown close below ${fmt(zone.lowerPrice)}")
        }

        // 5. Potential Reversal (CHoCH / Sweep + counter shift)
        val hasBullChoch = smc15m.marketStructure.structureBreaks.any { it.type.name.contains("CHOCH") && it.type.isBullish }
        val hasBearChoch = smc15m.marketStructure.structureBreaks.any { it.type.name.contains("CHOCH") && !it.type.isBullish }
        val hasSweep = smc15m.liquidity.recentSweeps.isNotEmpty()
        if (hasBullChoch && (hasSweep || rsi15 < 55.0)) {
            detectedTags.add(CryptoSetupPattern.POTENTIAL_REVERSAL)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.POTENTIAL_REVERSAL
                primaryDirection = FuturesDirection.LONG
                breakoutLevel = latest.close * 0.999
                invalidationLevel = min(swingLow20, latest.close - atr15 * 1.4)
            }
            reasons.add("Potential bullish structural reversal (15M CHoCH${if (hasSweep) " + Liquidity Sweep" else ""})")
        } else if (hasBearChoch && (hasSweep || rsi15 > 45.0)) {
            detectedTags.add(CryptoSetupPattern.POTENTIAL_REVERSAL)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.POTENTIAL_REVERSAL
                primaryDirection = FuturesDirection.SHORT
                breakoutLevel = latest.close * 1.001
                invalidationLevel = max(swingHigh20, latest.close + atr15 * 1.4)
            }
            reasons.add("Potential bearish structural reversal (15M CHoCH${if (hasSweep) " + Liquidity Sweep" else ""})")
        }

        // 6. Volume-Confirmed Move
        val bodyRatio = if (latest.high > latest.low) abs(latest.close - latest.open) / (latest.high - latest.low) else 0.0
        if (rvol >= 1.5 && bodyRatio >= 0.55) {
            detectedTags.add(CryptoSetupPattern.VOLUME_CONFIRMED_MOVE)
            val volDir = if (latest.close >= latest.open) FuturesDirection.LONG else FuturesDirection.SHORT
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.VOLUME_CONFIRMED_MOVE
                primaryDirection = volDir
                breakoutLevel = latest.close
                invalidationLevel = if (volDir == FuturesDirection.LONG) latest.low - atr15 * 0.5 else latest.high + atr15 * 0.5
            }
            reasons.add("Volume-confirmed directional expansion (RVOL ${String.format(Locale.US, "%.2fx", rvol)})")
        }

        // 7. Trend Continuation (HTF + EMA20/50 + Fib Golden Pocket + Unfilled FVG)
        val htfEma50 = tech1h?.trend?.emaSet?.ema50 ?: ema50
        val htfBullish = latest.close >= htfEma50 && ema20 >= ema50
        val htfBearish = latest.close <= htfEma50 && ema20 <= ema50
        val inFibPocket = smc15m.fibonacci?.let { latest.close in it.goldenPocketLow..it.goldenPocketHigh } == true
        val hasFvg = smc15m.fairValueGaps.any { it.status == FvgStatus.UNFILLED }
        val macdBull = (macd?.histogram ?: 0.0) > 0.0
        val macdBear = (macd?.histogram ?: 0.0) < 0.0

        if (htfBullish && latest.close > ema20 && (macdBull || inFibPocket || hasFvg)) {
            detectedTags.add(CryptoSetupPattern.TREND_CONTINUATION)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.TREND_CONTINUATION
                primaryDirection = FuturesDirection.LONG
                breakoutLevel = prev.high
                invalidationLevel = min(ema50, latest.close - atr15 * 1.4)
            }
            reasons.add("Trend continuation above EMA20/50${if (inFibPocket) " & Fib Golden Pocket" else ""}")
        } else if (htfBearish && latest.close < ema20 && (macdBear || inFibPocket || hasFvg)) {
            detectedTags.add(CryptoSetupPattern.TREND_CONTINUATION)
            if (primaryPattern == null) {
                primaryPattern = CryptoSetupPattern.TREND_CONTINUATION
                primaryDirection = FuturesDirection.SHORT
                breakoutLevel = prev.low
                invalidationLevel = max(ema50, latest.close + atr15 * 1.4)
            }
            reasons.add("Bearish trend continuation below EMA20/50${if (inFibPocket) " & Fib Golden Pocket" else ""}")
        }

        val resolvedPattern = primaryPattern ?: return null
        val triggerTime = if (latest.closeTime > 0L) latest.closeTime else latest.openTime

        return PatternDetectionResult(
            pattern = resolvedPattern,
            direction = primaryDirection,
            breakoutLevel = breakoutLevel,
            invalidationLevel = invalidationLevel,
            detectedCandleIndex = lastIdx,
            detectedCandleTimeMs = triggerTime,
            reasons = reasons.distinct(),
            allDetectedPatterns = detectedTags.distinct()
        )
    }

    /**
     * Crypto 24/7 session opening range: uses UTC 4H session open boundaries (00:00, 04:00, 08:00, ... UTC)
     * with initial 2x 15M candles = 30-minute crypto session range. Returns (Direction, BreakoutLevel, InvalidationLevel).
     */
    private fun detectCryptoSessionOrb(
        completed15m: List<Candle>,
        atr15: Double
    ): Triple<FuturesDirection, Double, Double>? {
        if (completed15m.size < 6) return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        // Find most recent UTC 4-hour session boundary candle (hour % 4 == 0 && minute == 0)
        var sessionStartIdx = -1
        for (i in completed15m.lastIndex - 2 downTo max(0, completed15m.lastIndex - 16)) {
            cal.timeInMillis = completed15m[i].openTime
            val hr = cal.get(Calendar.HOUR_OF_DAY)
            val min = cal.get(Calendar.MINUTE)
            if (hr % 4 == 0 && min == 0) {
                sessionStartIdx = i
                break
            }
        }
        if (sessionStartIdx < 0 || sessionStartIdx + 2 > completed15m.lastIndex) return null

        val rangeHigh = max(completed15m[sessionStartIdx].high, completed15m[sessionStartIdx + 1].high)
        val rangeLow = min(completed15m[sessionStartIdx].low, completed15m[sessionStartIdx + 1].low)
        val latest = completed15m.last()
        val prev = completed15m[completed15m.lastIndex - 1]

        return when {
            latest.close > rangeHigh && prev.close <= rangeHigh ->
                Triple(FuturesDirection.LONG, rangeHigh, min(rangeLow, rangeHigh - atr15 * 1.2))
            latest.close < rangeLow && prev.close >= rangeLow ->
                Triple(FuturesDirection.SHORT, rangeLow, max(rangeHigh, rangeLow + atr15 * 1.2))
            else -> null
        }
    }

    private fun fmt(v: Double): String {
        return when {
            v >= 100.0 -> String.format(Locale.US, "$%.2f", v)
            v >= 1.0 -> String.format(Locale.US, "$%.3f", v)
            else -> String.format(Locale.US, "$%.5f", v)
        }
    }
}
