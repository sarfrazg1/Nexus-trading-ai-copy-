package com.example.data.scanner

import com.example.data.model.Candle
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.max

/**
 * Entry Timer Status adapted from trader-entry methodology for Binance Futures:
 * Monitors a qualified setup bar by bar on COMPLETED candles only.
 * Never enters trades automatically.
 */
enum class EntryTimerStatus(val label: String, val badgeColorHex: Long) {
    WAITING("WAITING", 0xFFE5A93C),
    TRIGGERED("TRIGGERED", 0xFF00E676),
    EXPIRED("EXPIRED", 0xFF9E9E9E),
    INVALIDATED("INVALIDATED", 0xFFFF5252),
    NO_DATA("NO DATA", 0xFF757575)
}

data class EntryTimerConfig(
    val requireCandleClose: Boolean = true,
    val requireVolumeConfirmation: Boolean = false,
    val minRvolForConfirm: Double = 1.25,
    val maxWaitBars: Int = 12
)

data class EntryTimerResult(
    val status: EntryTimerStatus,
    val symbol: String,
    val direction: FuturesDirection,
    val triggerLevel: Double,
    val invalidationLevel: Double,
    val barsMonitored: Int,
    val maxWaitBars: Int,
    val triggerCandle: Candle? = null,
    val triggerPrice: Double? = null,
    val triggerTimestampMs: Long? = null,
    val triggerTimestampPkt: String? = null,
    val wickRejectionCount: Int = 0,
    val volumeConfirmed: Boolean = false,
    val requireVolumeConfirmation: Boolean = false,
    val statusExplanation: String,
    val autoTradeExecuted: Boolean = false // Strictly never enters trades automatically
) {
    val triggerPriceFormatted: String
        get() = triggerPrice?.let { p ->
            when {
                p >= 100.0 -> String.format(Locale.US, "$%.2f", p)
                p >= 1.0 -> String.format(Locale.US, "$%.3f", p)
                else -> String.format(Locale.US, "$%.5f", p)
            }
        } ?: "N/A"
}

/**
 * Bar-by-bar Entry Timer Engine (adapted from trader-entry/entry.py).
 *
 * Strict rules enforced:
 * 1. Uses COMPLETED candles only (open/forming candles are filtered out).
 * 2. A wick beyond a breakout level NEVER triggers entry — requires a completed candle CLOSE beyond triggerLevel.
 * 3. Supports optional volume confirmation (RVOL >= minRvolForConfirm).
 * 4. Reports exact trigger candle, trigger close price, and Asia/Karachi (PKT) timestamp.
 * 5. Never places or executes live exchange orders.
 */
object EntryTimerEngine {

    fun evaluateBarByBar(
        symbol: String,
        direction: FuturesDirection,
        triggerLevel: Double,
        invalidationLevel: Double,
        candles: List<Candle>,
        setupStartIndex: Int = (candles.size - 1).coerceAtLeast(0),
        config: EntryTimerConfig = EntryTimerConfig(),
        referenceTimeMs: Long = System.currentTimeMillis()
    ): EntryTimerResult {
        if (!triggerLevel.isFinite() || triggerLevel <= 0.0 ||
            !invalidationLevel.isFinite() || invalidationLevel <= 0.0
        ) {
            return EntryTimerResult(
                status = EntryTimerStatus.NO_DATA,
                symbol = symbol,
                direction = direction,
                triggerLevel = triggerLevel,
                invalidationLevel = invalidationLevel,
                barsMonitored = 0,
                maxWaitBars = config.maxWaitBars,
                requireVolumeConfirmation = config.requireVolumeConfirmation,
                statusExplanation = "NO DATA: Invalid trigger or invalidation levels for $symbol.",
                autoTradeExecuted = false
            )
        }

        val completed = TimeZoneUtils.filterCompletedCandles(candles, referenceTimeMs)
        if (completed.isEmpty() || setupStartIndex !in completed.indices) {
            return EntryTimerResult(
                status = EntryTimerStatus.NO_DATA,
                symbol = symbol,
                direction = direction,
                triggerLevel = triggerLevel,
                invalidationLevel = invalidationLevel,
                barsMonitored = 0,
                maxWaitBars = config.maxWaitBars,
                requireVolumeConfirmation = config.requireVolumeConfirmation,
                statusExplanation = "NO DATA: Waiting for completed candle history for $symbol.",
                autoTradeExecuted = false
            )
        }

        var wickRejections = 0
        var barsMonitored = 0

        for (i in setupStartIndex until completed.size) {
            val bar = completed[i]
            barsMonitored++
            val barTimeMs = if (bar.closeTime > 0L) bar.closeTime else bar.openTime
            val barTimePkt = TimeZoneUtils.formatKarachiTimestamp(barTimeMs)

            // 1. Check invalidation on completed candle close before trigger
            val isInvalidated = if (direction == FuturesDirection.LONG) {
                bar.close <= invalidationLevel
            } else {
                bar.close >= invalidationLevel
            }
            if (isInvalidated) {
                return EntryTimerResult(
                    status = EntryTimerStatus.INVALIDATED,
                    symbol = symbol,
                    direction = direction,
                    triggerLevel = triggerLevel,
                    invalidationLevel = invalidationLevel,
                    barsMonitored = barsMonitored,
                    maxWaitBars = config.maxWaitBars,
                    triggerCandle = bar,
                    triggerPrice = bar.close,
                    triggerTimestampMs = barTimeMs,
                    triggerTimestampPkt = barTimePkt,
                    wickRejectionCount = wickRejections,
                    requireVolumeConfirmation = config.requireVolumeConfirmation,
                    statusExplanation = "INVALIDATED at $barTimePkt: completed close (${fmt(bar.close)}) breached invalidation (${fmt(invalidationLevel)}).",
                    autoTradeExecuted = false
                )
            }

            // 2. Check completed close breakout vs wick-only probe
            val closeConfirmed = if (direction == FuturesDirection.LONG) {
                bar.close > triggerLevel
            } else {
                bar.close < triggerLevel
            }
            val wickOnlyProbe = !closeConfirmed && if (direction == FuturesDirection.LONG) {
                bar.high > triggerLevel
            } else {
                bar.low < triggerLevel
            }

            if (wickOnlyProbe) {
                wickRejections++
                continue
            }

            if (closeConfirmed) {
                // Compute preceding 20-bar average volume for optional volume gate
                val volWindowStart = max(0, i - 20)
                val priorBars = completed.subList(volWindowStart, i)
                val avgVol = if (priorBars.isNotEmpty()) priorBars.map { it.volume }.average() else bar.volume
                val volumeOk = if (avgVol > 0.0) bar.volume >= avgVol * config.minRvolForConfirm else true

                if (!config.requireVolumeConfirmation || volumeOk) {
                    return EntryTimerResult(
                        status = EntryTimerStatus.TRIGGERED,
                        symbol = symbol,
                        direction = direction,
                        triggerLevel = triggerLevel,
                        invalidationLevel = invalidationLevel,
                        barsMonitored = barsMonitored,
                        maxWaitBars = config.maxWaitBars,
                        triggerCandle = bar,
                        triggerPrice = bar.close,
                        triggerTimestampMs = barTimeMs,
                        triggerTimestampPkt = barTimePkt,
                        wickRejectionCount = wickRejections,
                        volumeConfirmed = volumeOk,
                        requireVolumeConfirmation = config.requireVolumeConfirmation,
                        statusExplanation = "TRIGGERED on completed close at ${fmt(bar.close)} ($barTimePkt)" +
                            (if (volumeOk) " with RVOL confirmation" else "") +
                            ". Manual entry only — no auto-order placed.",
                        autoTradeExecuted = false
                    )
                }
            }

            if (barsMonitored >= config.maxWaitBars) {
                return EntryTimerResult(
                    status = EntryTimerStatus.EXPIRED,
                    symbol = symbol,
                    direction = direction,
                    triggerLevel = triggerLevel,
                    invalidationLevel = invalidationLevel,
                    barsMonitored = barsMonitored,
                    maxWaitBars = config.maxWaitBars,
                    wickRejectionCount = wickRejections,
                    requireVolumeConfirmation = config.requireVolumeConfirmation,
                    statusExplanation = "EXPIRED after $barsMonitored completed bars without confirmed close beyond ${fmt(triggerLevel)} (wick rejections ignored: $wickRejections).",
                    autoTradeExecuted = false
                )
            }
        }

        val lastBar = completed.last()
        val lastPkt = TimeZoneUtils.formatKarachiTimestamp(if (lastBar.closeTime > 0L) lastBar.closeTime else lastBar.openTime)
        val wickNote = if (wickRejections > 0) {
            " Blocked $wickRejections wick-only probe(s) beyond ${fmt(triggerLevel)} (candle close required)."
        } else {
            ""
        }
        return EntryTimerResult(
            status = EntryTimerStatus.WAITING,
            symbol = symbol,
            direction = direction,
            triggerLevel = triggerLevel,
            invalidationLevel = invalidationLevel,
            barsMonitored = barsMonitored,
            maxWaitBars = config.maxWaitBars,
            wickRejectionCount = wickRejections,
            requireVolumeConfirmation = config.requireVolumeConfirmation,
            statusExplanation = "WAITING ($barsMonitored/${config.maxWaitBars} bars, last closed $lastPkt): awaiting completed candle close beyond ${fmt(triggerLevel)}.$wickNote",
            autoTradeExecuted = false
        )
    }

    private fun fmt(v: Double): String {
        return when {
            v >= 100.0 -> String.format(Locale.US, "$%.2f", v)
            v >= 1.0 -> String.format(Locale.US, "$%.3f", v)
            else -> String.format(Locale.US, "$%.5f", v)
        }
    }
}
