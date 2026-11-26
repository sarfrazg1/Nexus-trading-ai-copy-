package com.example.data.smc

import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Support/Resistance, Volume Profile (POC/VAH/VAL), Previous Day/Week High & Low,
 * Breakout-Retest & Failed-Breakout Trap detection, Contextual Price Action, and
 * Strict 7-Gate Fibonacci Retracement & Extension Validation Engine.
 */
object AdvancedStructureAndFibEngine {

    data class PreviousPeriodLevels(
        val pdh: Double?,
        val pdl: Double?,
        val pwh: Double?,
        val pwl: Double?
    )

    private data class CandidateLevel(
        val price: Double,
        val source: ZoneSourceType,
        val hintBullishSupport: Boolean? = null,
        val touchesWeight: Int = 1,
        val isRetested: Boolean = false
    )

    fun estimateAtr(candles: List<Candle>, period: Int = 14): Double {
        if (candles.size < 2) return (candles.lastOrNull()?.close ?: 1.0) * 0.01
        val start = max(1, candles.size - period)
        var sum = 0.0
        var count = 0
        for (i in start until candles.size) {
            val cur = candles[i]
            val prev = candles[i - 1]
            val tr = max(
                cur.high - cur.low,
                max(abs(cur.high - prev.close), abs(cur.low - prev.close))
            )
            sum += tr
            count++
        }
        return if (count > 0 && sum > 0.0) sum / count else candles.last().close * 0.01
    }

    /**
     * Volume Profile approximation over [candles] using 24 price buckets.
     * Computes POC, VAH, and VAL (70% value area) only when reliable positive volume exists.
     */
    fun computeVolumeProfile(candles: List<Candle>): VolumeProfileSummary? {
        if (candles.size < 10) return null
        val positiveVolCount = candles.count { it.volume.isFinite() && it.volume > 0.0 }
        val totalVol = candles.filter { it.volume.isFinite() && it.volume > 0.0 }.sumOf { it.volume }
        val isReliable = positiveVolCount >= (candles.size / 2) && totalVol > 0.0
        if (!isReliable) return null

        val minLow = candles.minOf { it.low }
        val maxHigh = candles.maxOf { it.high }
        val range = maxHigh - minLow
        if (range <= 0.0) return null

        val bucketCount = 24
        val step = range / bucketCount
        val bucketVol = DoubleArray(bucketCount)

        for (c in candles) {
            val typical = (c.high + c.low + c.close) / 3.0
            val idx = ((typical - minLow) / step).toInt().coerceIn(0, bucketCount - 1)
            bucketVol[idx] += c.volume
        }

        var pocIdx = 0
        for (i in 1 until bucketCount) {
            if (bucketVol[i] > bucketVol[pocIdx]) pocIdx = i
        }

        val targetValueArea = totalVol * 0.70
        var accumulated = bucketVol[pocIdx]
        var lowIdx = pocIdx
        var highIdx = pocIdx

        while (accumulated < targetValueArea && (lowIdx > 0 || highIdx < bucketCount - 1)) {
            val leftVol = if (lowIdx > 0) bucketVol[lowIdx - 1] else -1.0
            val rightVol = if (highIdx < bucketCount - 1) bucketVol[highIdx + 1] else -1.0
            if (rightVol >= leftVol && highIdx < bucketCount - 1) {
                highIdx++
                accumulated += bucketVol[highIdx]
            } else if (lowIdx > 0) {
                lowIdx--
                accumulated += bucketVol[lowIdx]
            } else {
                break
            }
        }

        val pocPrice = minLow + (pocIdx + 0.5) * step
        val vahPrice = minLow + (highIdx + 1.0) * step
        val valPrice = minLow + lowIdx * step

        return VolumeProfileSummary(
            pocPrice = pocPrice,
            vahPrice = vahPrice,
            valPrice = valPrice,
            isVolumeReliable = true,
            totalVolumeEvaluated = totalVol
        )
    }

    /**
     * Extracts Previous Day High/Low (PDH/PDL) and Previous Week High/Low (PWH/PWL)
     * by UTC calendar bucket boundaries.
     */
    fun extractPreviousPeriodLevels(candles: List<Candle>): PreviousPeriodLevels {
        if (candles.size < 4) return PreviousPeriodLevels(null, null, null, null)
        val utc = TimeZone.getTimeZone("UTC")
        val dayBuckets = linkedMapOf<Int, MutableList<Candle>>()
        val weekBuckets = linkedMapOf<Int, MutableList<Candle>>()
        val cal = Calendar.getInstance(utc)

        for (c in candles) {
            cal.timeInMillis = c.openTime
            val dayKey = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
            val weekKey = cal.get(Calendar.YEAR) * 100 + cal.get(Calendar.WEEK_OF_YEAR)
            dayBuckets.getOrPut(dayKey) { mutableListOf() }.add(c)
            weekBuckets.getOrPut(weekKey) { mutableListOf() }.add(c)
        }

        val dayKeys = dayBuckets.keys.toList()
        val prevDayCandles = if (dayKeys.size >= 2) dayBuckets[dayKeys[dayKeys.size - 2]] else null

        val weekKeys = weekBuckets.keys.toList()
        val prevWeekCandles = if (weekKeys.size >= 2) weekBuckets[weekKeys[weekKeys.size - 2]] else null

        return PreviousPeriodLevels(
            pdh = prevDayCandles?.maxOfOrNull { it.high },
            pdl = prevDayCandles?.minOfOrNull { it.low },
            pwh = prevWeekCandles?.maxOfOrNull { it.high },
            pwl = prevWeekCandles?.minOfOrNull { it.low }
        )
    }

    /**
     * Builds meaningful S/R & Supply/Demand zones combining repeated price reactions,
     * confirmed swing highs/lows, PDH/PDL, PWH/PWL, Order Blocks, Volume Profile (POC/VAH/VAL),
     * and Breakout-Retest confirmation.
     */
    fun buildAdvancedPriceZones(
        candles: List<Candle>,
        structure: MarketStructureAnalysis,
        orderBlocks: List<OrderBlock>,
        volumeProfile: VolumeProfileSummary?,
        periodLevels: PreviousPeriodLevels,
        atr: Double
    ): Pair<List<PriceLevelZone>, List<BreakoutRetestEvent>> {
        if (candles.isEmpty()) return emptyList<PriceLevelZone>() to emptyList()
        val currentPrice = candles.last().close
        val candidates = mutableListOf<CandidateLevel>()

        // 1. Confirmed Swing Pivots
        for (sp in structure.swingPoints) {
            candidates.add(
                CandidateLevel(
                    price = sp.price,
                    source = ZoneSourceType.SWING_PIVOT,
                    hintBullishSupport = !sp.isHigh
                )
            )
        }

        // 2. Active/Mitigated Supply & Demand Order Blocks
        for (ob in orderBlocks.filter { it.status != OrderBlockStatus.INVALIDATED }) {
            candidates.add(
                CandidateLevel(
                    price = ob.meanThresholdPrice,
                    source = ZoneSourceType.SUPPLY_DEMAND_OB,
                    hintBullishSupport = ob.type == OrderBlockType.BULLISH,
                    touchesWeight = if (ob.status == OrderBlockStatus.MITIGATED) 2 else 1
                )
            )
        }

        // 3. Previous Day / Week High & Low
        periodLevels.pdh?.let { candidates.add(CandidateLevel(it, ZoneSourceType.PREVIOUS_DAY_HIGH_LOW, false)) }
        periodLevels.pdl?.let { candidates.add(CandidateLevel(it, ZoneSourceType.PREVIOUS_DAY_HIGH_LOW, true)) }
        periodLevels.pwh?.let { candidates.add(CandidateLevel(it, ZoneSourceType.PREVIOUS_WEEK_HIGH_LOW, false)) }
        periodLevels.pwl?.let { candidates.add(CandidateLevel(it, ZoneSourceType.PREVIOUS_WEEK_HIGH_LOW, true)) }

        // 4. Volume Profile Levels (POC, VAH, VAL)
        if (volumeProfile != null && volumeProfile.isVolumeReliable) {
            candidates.add(CandidateLevel(volumeProfile.pocPrice, ZoneSourceType.VOLUME_PROFILE_HVN, null, touchesWeight = 2))
            candidates.add(CandidateLevel(volumeProfile.vahPrice, ZoneSourceType.VOLUME_PROFILE_HVN, false))
            candidates.add(CandidateLevel(volumeProfile.valPrice, ZoneSourceType.VOLUME_PROFILE_HVN, true))
        }

        // Cluster nearby candidates into unified zones
        val clusterTol = max(atr * 0.65, currentPrice * 0.006)
        val sortedCandidates = candidates.sortedBy { it.price }
        val clusters = mutableListOf<MutableList<CandidateLevel>>()

        for (cand in sortedCandidates) {
            val match = clusters.lastOrNull()
            if (match != null && abs(cand.price - match.map { it.price }.average()) <= clusterTol) {
                match.add(cand)
            } else {
                clusters.add(mutableListOf(cand))
            }
        }

        val zoneHalfWidth = max(atr * 0.30, currentPrice * 0.0025)
        val rawZones = clusters.mapIndexedNotNull { idx, group ->
            val center = group.map { it.price }.average()
            val minCand = group.minOf { it.price }
            val maxCand = group.maxOf { it.price }
            val lower = min(minCand, center - zoneHalfWidth)
            val upper = max(maxCand, center + zoneHalfWidth)

            // Count repeated historical candle reactions against [lower..upper]
            var reactionCount = 0
            for (c in candles) {
                val testedFromAbove = c.low <= upper && c.close >= lower && c.low >= lower * 0.995
                val testedFromBelow = c.high >= lower && c.close <= upper && c.high <= upper * 1.005
                if (testedFromAbove || testedFromBelow) reactionCount++
            }

            val sources = group.map { it.source }.distinct().toMutableList()
            if (reactionCount >= 2 && ZoneSourceType.REPEATED_REACTION !in sources) {
                sources.add(ZoneSourceType.REPEATED_REACTION)
            }

            // Require either >= 2 independent sources or >= 2 reactions
            if (sources.size < 2 && reactionCount < 2 && group.size < 2) {
                return@mapIndexedNotNull null
            }

            val distPct = ((center - currentPrice) / currentPrice) * 100.0
            val hasOb = ZoneSourceType.SUPPLY_DEMAND_OB in sources
            val zoneType = when {
                center < currentPrice && hasOb -> PriceZoneType.DEMAND
                center >= currentPrice && hasOb -> PriceZoneType.SUPPLY
                center < currentPrice -> PriceZoneType.SUPPORT
                else -> PriceZoneType.RESISTANCE
            }

            val baseScore = (sources.size * 18) + min(reactionCount * 8, 32)
            val clampedScore = baseScore.coerceIn(20, 95)

            PriceLevelZone(
                id = "Z_${idx}_${sources.first().name}",
                type = zoneType,
                centralPrice = center,
                upperPrice = upper,
                lowerPrice = lower,
                touchesCount = max(group.sumOf { it.touchesWeight }, reactionCount),
                strength = ((clampedScore + 19) / 20).coerceIn(1, 5),
                isCurrentlyActive = abs(distPct) <= 15.0,
                distancePercent = distPct,
                zoneSources = sources,
                strengthScore = clampedScore,
                isBreakoutRetested = false,
                sourceSummary = sources.joinToString(" + ") { it.shortLabel }
            )
        }

        // Detect Breakouts, Retests, and Failed Breakouts (Traps) against these zones
        val breakoutEvents = detectBreakoutsRetestsAndTraps(candles, rawZones)
        val retestedLevels = breakoutEvents
            .filter {
                it.type == BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BULLISH ||
                    it.type == BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BEARISH
            }
            .map { it.zoneLevel }

        val enrichedZones = rawZones.map { z ->
            val wasRetested = retestedLevels.any { abs(it - z.centralPrice) <= zoneHalfWidth * 1.5 }
            if (wasRetested) {
                val updatedSources = (z.zoneSources + ZoneSourceType.BREAKOUT_RETEST).distinct()
                val newScore = (z.strengthScore + 15).coerceAtMost(100)
                z.copy(
                    zoneSources = updatedSources,
                    strengthScore = newScore,
                    strength = 5,
                    isBreakoutRetested = true,
                    sourceSummary = updatedSources.joinToString(" + ") { it.shortLabel }
                )
            } else z
        }.sortedWith(compareByDescending<PriceLevelZone> { it.strengthScore }.thenBy { abs(it.distancePercent) })
            .take(10)

        return enrichedZones to breakoutEvents
    }

    fun detectBreakoutsRetestsAndTraps(
        candles: List<Candle>,
        zones: List<PriceLevelZone>
    ): List<BreakoutRetestEvent> {
        if (candles.size < 8 || zones.isEmpty()) return emptyList()
        val events = mutableListOf<BreakoutRetestEvent>()
        val avgVol = candles.takeLast(20).map { it.volume }.average().takeIf { it > 0.0 } ?: 1.0
        val startIdx = max(2, candles.size - 35)

        for (zone in zones.take(8)) {
            for (i in startIdx until (candles.size - 2)) {
                val prev = candles[i - 1]
                val brk = candles[i]

                // Bullish breakout attempt above upperPrice
                if (prev.close <= zone.upperPrice && brk.close > zone.upperPrice) {
                    val volOk = brk.volume <= 0.0 || brk.volume >= avgVol * 1.05
                    var resolved = false
                    for (j in (i + 1)..min(candles.lastIndex, i + 4)) {
                        val c = candles[j]
                        if (c.close < zone.lowerPrice) {
                            // Failed breakout (Bull Trap)
                            events.add(
                                BreakoutRetestEvent(
                                    type = BreakoutRetestType.FAILED_BREAKOUT_BULL_TRAP,
                                    zoneLevel = zone.centralPrice,
                                    breakCandleIndex = i,
                                    testCandleIndex = j,
                                    timestamp = c.openTime,
                                    volumeConfirmed = volOk,
                                    description = "Bull trap above ${fmt(zone.upperPrice)}: closed back below zone within ${j - i} bars."
                                )
                            )
                            resolved = true
                            break
                        } else if (c.low <= zone.upperPrice * 1.003 && c.close >= zone.upperPrice * 0.998) {
                            events.add(
                                BreakoutRetestEvent(
                                    type = BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BULLISH,
                                    zoneLevel = zone.centralPrice,
                                    breakCandleIndex = i,
                                    testCandleIndex = j,
                                    timestamp = c.openTime,
                                    volumeConfirmed = volOk,
                                    description = "Confirmed bullish breakout & retest holding ${fmt(zone.upperPrice)}."
                                )
                            )
                            resolved = true
                            break
                        }
                    }
                    if (resolved) break
                }

                // Bearish breakdown attempt below lowerPrice
                if (prev.close >= zone.lowerPrice && brk.close < zone.lowerPrice) {
                    val volOk = brk.volume <= 0.0 || brk.volume >= avgVol * 1.05
                    var resolved = false
                    for (j in (i + 1)..min(candles.lastIndex, i + 4)) {
                        val c = candles[j]
                        if (c.close > zone.upperPrice) {
                            // Failed breakdown (Bear Trap)
                            events.add(
                                BreakoutRetestEvent(
                                    type = BreakoutRetestType.FAILED_BREAKOUT_BEAR_TRAP,
                                    zoneLevel = zone.centralPrice,
                                    breakCandleIndex = i,
                                    testCandleIndex = j,
                                    timestamp = c.openTime,
                                    volumeConfirmed = volOk,
                                    description = "Bear trap below ${fmt(zone.lowerPrice)}: reclaimed above zone within ${j - i} bars."
                                )
                            )
                            resolved = true
                            break
                        } else if (c.high >= zone.lowerPrice * 0.997 && c.close <= zone.lowerPrice * 1.002) {
                            events.add(
                                BreakoutRetestEvent(
                                    type = BreakoutRetestType.CONFIRMED_BREAKOUT_RETEST_BEARISH,
                                    zoneLevel = zone.centralPrice,
                                    breakCandleIndex = i,
                                    testCandleIndex = j,
                                    timestamp = c.openTime,
                                    volumeConfirmed = volOk,
                                    description = "Confirmed bearish breakdown & retest rejecting below ${fmt(zone.lowerPrice)}."
                                )
                            )
                            resolved = true
                            break
                        }
                    }
                    if (resolved) break
                }
            }
        }
        return events.sortedByDescending { it.testCandleIndex }.take(8)
    }

    /**
     * Detects Engulfing candles, Pin bars, and Rejection wicks ONLY when anchored to
     * S/R zones, Order Blocks, or Fibonacci Golden Zone (0.500-0.618).
     */
    fun detectContextualPriceAction(
        candles: List<Candle>,
        zones: List<PriceLevelZone>,
        orderBlocks: List<OrderBlock>,
        goldenZoneLow: Double?,
        goldenZoneHigh: Double?,
        atr: Double
    ): List<PriceActionPatternEvent> {
        if (candles.size < 4) return emptyList()
        val events = mutableListOf<PriceActionPatternEvent>()
        val tol = max(atr * 0.5, candles.last().close * 0.004)
        val startIdx = max(1, candles.size - 8)

        for (i in startIdx until candles.size) {
            val prev = candles[i - 1]
            val cur = candles[i]
            val body = abs(cur.close - cur.open)
            val fullRange = (cur.high - cur.low).coerceAtLeast(1e-9)
            val upperWick = cur.high - max(cur.open, cur.close)
            val lowerWick = min(cur.open, cur.close) - cur.low

            val inBullishContext = zones.any {
                (it.type == PriceZoneType.SUPPORT || it.type == PriceZoneType.DEMAND) &&
                    cur.low <= it.upperPrice + tol && cur.close >= it.lowerPrice - tol
            } || orderBlocks.any {
                it.type == OrderBlockType.BULLISH && it.status != OrderBlockStatus.INVALIDATED &&
                    cur.low <= it.topPrice + tol && cur.close >= it.bottomPrice - tol
            } || (goldenZoneLow != null && goldenZoneHigh != null &&
                cur.low <= goldenZoneHigh + tol && cur.high >= goldenZoneLow - tol)

            val inBearishContext = zones.any {
                (it.type == PriceZoneType.RESISTANCE || it.type == PriceZoneType.SUPPLY) &&
                    cur.high >= it.lowerPrice - tol && cur.close <= it.upperPrice + tol
            } || orderBlocks.any {
                it.type == OrderBlockType.BEARISH && it.status != OrderBlockStatus.INVALIDATED &&
                    cur.high >= it.bottomPrice - tol && cur.close <= it.topPrice + tol
            } || (goldenZoneLow != null && goldenZoneHigh != null &&
                cur.high >= goldenZoneLow - tol && cur.low <= goldenZoneHigh + tol)

            // Bullish Engulfing
            val prevBearish = prev.close < prev.open
            val curBullish = cur.close > cur.open
            if (inBullishContext && prevBearish && curBullish &&
                cur.close >= prev.open && cur.open <= prev.close &&
                body > abs(prev.close - prev.open) * 1.05
            ) {
                events.add(
                    PriceActionPatternEvent(
                        type = PriceActionPatternType.BULLISH_ENGULFING,
                        candleIndex = i,
                        timestamp = cur.openTime,
                        closePrice = cur.close,
                        hasZoneConfluence = true,
                        isCandleClosedConfirmed = true,
                        contextDescription = "Bullish Engulfing closed at ${fmt(cur.close)} at structural Support / Fib zone"
                    )
                )
                continue
            }

            // Bearish Engulfing
            val prevBullish = prev.close > prev.open
            val curBearish = cur.close < cur.open
            if (inBearishContext && prevBullish && curBearish &&
                cur.close <= prev.open && cur.open >= prev.close &&
                body > abs(prev.close - prev.open) * 1.05
            ) {
                events.add(
                    PriceActionPatternEvent(
                        type = PriceActionPatternType.BEARISH_ENGULFING,
                        candleIndex = i,
                        timestamp = cur.openTime,
                        closePrice = cur.close,
                        hasZoneConfluence = true,
                        isCandleClosedConfirmed = true,
                        contextDescription = "Bearish Engulfing closed at ${fmt(cur.close)} at Resistance / Supply zone"
                    )
                )
                continue
            }

            // Bullish Pin Bar / Rejection Wick
            if (inBullishContext && lowerWick >= body * 2.0 && lowerWick / fullRange >= 0.55 && cur.close >= cur.open) {
                val type = if (upperWick <= fullRange * 0.15) {
                    PriceActionPatternType.BULLISH_PIN_BAR
                } else {
                    PriceActionPatternType.BULLISH_REJECTION_WICK
                }
                events.add(
                    PriceActionPatternEvent(
                        type = type,
                        candleIndex = i,
                        timestamp = cur.openTime,
                        closePrice = cur.close,
                        hasZoneConfluence = true,
                        isCandleClosedConfirmed = true,
                        contextDescription = "${type.label} rejecting lows with strong buyback close at ${fmt(cur.close)}"
                    )
                )
                continue
            }

            // Bearish Pin Bar / Rejection Wick
            if (inBearishContext && upperWick >= body * 2.0 && upperWick / fullRange >= 0.55 && cur.close <= cur.open) {
                val type = if (lowerWick <= fullRange * 0.15) {
                    PriceActionPatternType.BEARISH_PIN_BAR
                } else {
                    PriceActionPatternType.BEARISH_REJECTION_WICK
                }
                events.add(
                    PriceActionPatternEvent(
                        type = type,
                        candleIndex = i,
                        timestamp = cur.openTime,
                        closePrice = cur.close,
                        hasZoneConfluence = true,
                        isCandleClosedConfirmed = true,
                        contextDescription = "${type.label} rejecting highs with heavy supply close at ${fmt(cur.close)}"
                    )
                )
            }
        }
        return events.sortedByDescending { it.candleIndex }
    }

    /**
     * Builds Fibonacci levels strictly from confirmed significant swings (never minor noise)
     * and validates the 7-gate Golden Zone (0.500 - 0.618) setup criteria.
     */
    fun calculateValidatedFibonacci(
        candles: List<Candle>,
        structure: MarketStructureAnalysis,
        orderBlocks: List<OrderBlock>,
        priceZones: List<PriceLevelZone>,
        htfSnapshots: List<TimeframeSmcSnapshot>
    ): Pair<FibonacciAnalysis?, List<PriceActionPatternEvent>> {
        val swings = structure.swingPoints
        if (swings.size < 2 || candles.size < 15) return null to emptyList()

        val atr = estimateAtr(candles)
        val currentPrice = candles.last().close
        val minSignificantRange = max(atr * 1.35, currentPrice * 0.0035)

        // Select most recent confirmed swing high + swing low forming a genuine swing leg
        val highs = swings.filter { it.isHigh }
        val lows = swings.filter { !it.isHigh }
        if (highs.isEmpty() || lows.isEmpty()) return null to emptyList()

        var bestHigh = highs.last()
        var bestLow = lows.last()
        var foundSignificantLeg = false

        // Search backwards for a pair of high/low with genuine range and >= 3 bars span
        outer@ for (h in highs.asReversed()) {
            for (l in lows.asReversed()) {
                val span = abs(h.index - l.index)
                val range = h.price - l.price
                if (span >= 3 && range >= minSignificantRange) {
                    bestHigh = h
                    bestLow = l
                    foundSignificantLeg = true
                    break@outer
                }
            }
        }

        // Fall back to extremes if recent fractal leg is compact, but mark swing significance accurately
        if (!foundSignificantLeg) {
            val extremeHigh = highs.maxByOrNull { it.price } ?: highs.last()
            val extremeLow = lows.minByOrNull { it.price } ?: lows.last()
            if (extremeHigh.price - extremeLow.price >= minSignificantRange && abs(extremeHigh.index - extremeLow.index) >= 3) {
                bestHigh = extremeHigh
                bestLow = extremeLow
                foundSignificantLeg = true
            }
        }

        val range = bestHigh.price - bestLow.price
        if (range <= 0.0) return null to emptyList()

        val isUptrend = bestHigh.index > bestLow.index
        val retracementRatios = listOf(0.236, 0.382, 0.500, 0.618, 0.786)
        val extensionRatios = listOf(1.272, 1.618, 2.618)

        val retracements = retracementRatios.map { ratio ->
            val p = if (isUptrend) bestHigh.price - (range * ratio) else bestLow.price + (range * ratio)
            FibLevel(
                ratio = ratio,
                price = p,
                label = "Fib ${String.format(Locale.US, "%.3f", ratio)}",
                isExtension = false,
                isGoldenPocket = ratio == 0.618 || ratio == 0.500,
                isEquilibrium = ratio == 0.500
            )
        }

        val extensions = extensionRatios.map { ratio ->
            val p = if (isUptrend) bestLow.price + (range * ratio) else bestHigh.price - (range * ratio)
            FibLevel(
                ratio = ratio,
                price = p,
                label = "Ext ${String.format(Locale.US, "%.3f", ratio)}",
                isExtension = true,
                isGoldenPocket = ratio == 1.618
            )
        }

        val fib0500 = retracements.first { it.ratio == 0.500 }.price
        val fib0618 = retracements.first { it.ratio == 0.618 }.price
        val fib0650 = if (isUptrend) bestHigh.price - (range * 0.650) else bestLow.price + (range * 0.650)
        val fib0786 = retracements.first { it.ratio == 0.786 }.price

        val gz0500To0618Low = min(fib0500, fib0618)
        val gz0500To0618High = max(fib0500, fib0618)
        val gp618To650Low = min(fib0618, fib0650)
        val gp618To650High = max(fib0618, fib0650)

        // Contextual Price Action patterns at zones or Golden Zone
        val priceActionEvents = detectContextualPriceAction(
            candles = candles,
            zones = priceZones,
            orderBlocks = orderBlocks,
            goldenZoneLow = gz0500To0618Low,
            goldenZoneHigh = gz0500To0618High,
            atr = atr
        )

        // Check all 7 Strict Fibonacci Validation Gates
        val gate1Swing = foundSignificantLeg
        val gate2Structure = (isUptrend && (structure.trendState == MarketTrendState.BULLISH_UPTREND || structure.isBullishCHoCHPresent || structure.isBullishBOSPresent)) ||
            (!isUptrend && (structure.trendState == MarketTrendState.BEARISH_DOWNTREND || structure.isBearishCHoCHPresent || structure.isBearishBOSPresent))

        val tol = max(atr * 0.45, range * 0.08)
        val gate3Confluence = priceZones.any { z ->
            val zoneMatchesDir = if (isUptrend) {
                z.type == PriceZoneType.SUPPORT || z.type == PriceZoneType.DEMAND
            } else {
                z.type == PriceZoneType.RESISTANCE || z.type == PriceZoneType.SUPPLY
            }
            zoneMatchesDir && z.upperPrice >= gz0500To0618Low - tol && z.lowerPrice <= gz0500To0618High + tol
        } || orderBlocks.any { ob ->
            ob.status != OrderBlockStatus.INVALIDATED &&
                ((isUptrend && ob.type == OrderBlockType.BULLISH) || (!isUptrend && ob.type == OrderBlockType.BEARISH)) &&
                ob.topPrice >= gz0500To0618Low - tol && ob.bottomPrice <= gz0500To0618High + tol
        }

        val gate4Reaction = priceActionEvents.any { pa ->
            pa.isCandleClosedConfirmed && pa.type.isBullish == isUptrend && pa.candleIndex >= candles.size - 4
        }

        val avgVol = candles.takeLast(20).map { it.volume }.average().takeIf { it > 0.0 }
        val gate5Volume = if (avgVol == null || candles.count { it.volume > 0.0 } < 5) {
            true // Volume unprovided on spot/FX; marked cleanly without hallucinating volume
        } else {
            val recentReactionIdx = priceActionEvents.firstOrNull { it.type.isBullish == isUptrend }?.candleIndex ?: candles.lastIndex
            candles[recentReactionIdx].volume >= avgVol * 1.0
        }

        val htfD1orH4 = htfSnapshots.filter { it.timeframe == CandleTimeframe.D1 || it.timeframe == CandleTimeframe.H4 }
        val gate6Htf = if (htfD1orH4.isEmpty()) {
            true
        } else {
            val htfBullish = htfD1orH4.count { it.isBullishBias }
            val htfBearish = htfD1orH4.count { !it.isBullishBias && it.trend == MarketTrendState.BEARISH_DOWNTREND }
            if (isUptrend) htfBullish >= htfBearish else htfBearish >= htfBullish
        }

        val slDist = if (isUptrend) currentPrice - fib0786 else fib0786 - currentPrice
        val ext1272 = extensions.first { it.ratio == 1.272 }.price
        val tpDist = if (isUptrend) ext1272 - currentPrice else currentPrice - ext1272
        val gate7Risk = slDist > 0.0 && tpDist > 0.0 && (tpDist / slDist) >= 1.5

        val inGoldenZone = currentPrice in (gz0500To0618Low - tol * 0.35)..(gz0500To0618High + tol * 0.35)
        val gates = listOf(gate1Swing, gate2Structure, gate3Confluence, gate4Reaction, gate5Volume, gate6Htf, gate7Risk)
        val passedCount = gates.count { it }
        val strictlyValid = gates.all { it } && inGoldenZone

        val rejectionReason = when {
            !gate1Swing -> "Swing leg failed significance filter (minor fluctuation rejected)."
            !inGoldenZone -> "Price (${fmt(currentPrice)}) is outside Golden Zone 0.500–0.618 [${fmt(gz0500To0618Low)}–${fmt(gz0500To0618High)}]."
            !gate3Confluence -> "Golden Zone lacks overlapping S/R, Demand/Supply, or Order Block confluence."
            !gate4Reaction -> "Touching Golden Zone alone is NOT confirmation — awaiting engulfing/pin-bar close reaction."
            !gate6Htf -> "Counter to higher-timeframe (1D/4H) structural bias."
            !gate2Structure -> "Trend/structure break does not align with swing direction."
            !gate7Risk -> "Stop beyond 0.786 vs 1.272 extension yields insufficient risk-to-reward."
            else -> null
        }

        val report = FibonacciValidationReport(
            hasConfirmedSwingLeg = gate1Swing,
            alignsWithTrendOrStructure = gate2Structure,
            hasSrOrOrderBlockConfluence = gate3Confluence,
            hasConfirmedPriceActionReaction = gate4Reaction,
            hasVolumeConfirmation = gate5Volume,
            alignsWithHigherTimeframe = gate6Htf,
            hasValidRiskLevels = gate7Risk,
            isInGoldenZone0500To0618 = inGoldenZone,
            isStrictlyValid = strictlyValid,
            passedGatesCount = passedCount,
            rejectionReason = rejectionReason
        )

        val zoneDesc = when {
            strictlyValid && isUptrend -> "CONFIRMED Golden Zone (0.500–0.618) LONG Confluence ($passedCount/7 gates)"
            strictlyValid && !isUptrend -> "CONFIRMED Golden Zone (0.500–0.618) SHORT Confluence ($passedCount/7 gates)"
            inGoldenZone -> "WATCHING Golden Zone (0.500–0.618) — ${rejectionReason ?: "Awaiting full confirmation"}"
            isUptrend && currentPrice < fib0500 -> "In Discount Zone (< 0.500 Equilibrium) | ${passedCount}/7 Fib Gates"
            isUptrend && currentPrice >= fib0500 -> "In Premium Zone (> 0.500 Equilibrium)"
            !isUptrend && currentPrice > fib0500 -> "In Premium Zone (> 0.500 Equilibrium) | ${passedCount}/7 Fib Gates"
            else -> "In Discount Zone (< 0.500 Equilibrium)"
        }

        val analysis = FibonacciAnalysis(
            swingHighPrice = bestHigh.price,
            swingLowPrice = bestLow.price,
            swingHighTime = bestHigh.timestamp,
            swingLowTime = bestLow.timestamp,
            isUptrendSwing = isUptrend,
            retracementLevels = retracements,
            extensionLevels = extensions,
            goldenPocketLow = gp618To650Low,
            goldenPocketHigh = gp618To650High,
            equilibriumPrice = fib0500,
            currentFibZoneDescription = zoneDesc,
            isSwingValid = foundSignificantLeg,
            goldenZone0500To0618Low = gz0500To0618Low,
            goldenZone0500To0618High = gz0500To0618High,
            goldenRatio0618Price = fib0618,
            premiumZoneLow = fib0500,
            premiumZoneHigh = bestHigh.price,
            discountZoneLow = bestLow.price,
            discountZoneHigh = fib0500,
            validationReport = report
        )
        return analysis to priceActionEvents
    }

    private fun fmt(v: Double): String = when {
        abs(v) >= 100.0 -> String.format(Locale.US, "%.2f", v)
        abs(v) >= 1.0 -> String.format(Locale.US, "%.3f", v)
        else -> String.format(Locale.US, "%.4f", v)
    }
}
