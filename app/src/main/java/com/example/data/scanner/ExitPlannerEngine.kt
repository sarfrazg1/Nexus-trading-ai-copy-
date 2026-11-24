package com.example.data.scanner

import com.example.data.model.CandleTimeframe
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs

data class PartialProfitTier(
    val label: String,
    val price: Double,
    val priceFormatted: String,
    val allocationPercent: Int,
    val netRoiPercent: Double,
    val rMultiple: Double
)

/**
 * Pre-entry Exit Plan adapted from trader-exit/exit.py for Binance USDT-M Futures.
 * Calculates SL, TP tiers, R:R, S/R invalidation vs ATR risk, partial profit plan,
 * setup expiry, and explicit calculation assumptions BEFORE entry.
 */
data class PreEntryExitPlan(
    val stopLoss: Double,
    val stopLossFormatted: String,
    val srInvalidationLevel: Double,
    val atrBasedStopLevel: Double,
    val atrValue: Double,
    val invalidationBasis: String,
    val takeProfitTiers: List<PartialProfitTier>,
    val riskRewardRatio: Double,
    val setupExpiryBars: Int,
    val expiryDeadlinePkt: String,
    val expiryCondition: String,
    val exitConditions: List<String>,
    val calculationAssumptions: List<String>,
    val stopOrderDisclaimer: String = DEFAULT_STOP_ORDER_DISCLAIMER
) {
    companion object {
        const val DEFAULT_STOP_ORDER_DISCLAIMER =
            "Important: Stop orders are NOT guaranteed to fill at their exact price due to market slippage, spread widening, or liquidity gaps during volatile moves."
    }
}

object ExitPlannerEngine {

    fun buildPlan(
        direction: FuturesDirection,
        entryPrice: Double,
        srInvalidationLevel: Double,
        atr15m: Double,
        targetCalc: BinanceFuturesScannerEngine.FuturesTargetCalculation,
        timeframe: CandleTimeframe = CandleTimeframe.M15,
        setupExpiryBars: Int = 12,
        referenceTimeMs: Long = System.currentTimeMillis()
    ): PreEntryExitPlan {
        val atrStop = if (direction == FuturesDirection.LONG) {
            entryPrice - (atr15m * 1.3)
        } else {
            entryPrice + (atr15m * 1.3)
        }

        val slDistance = abs(entryPrice - targetCalc.stopLoss).coerceAtLeast(1e-9)
        val rTp1 = abs(targetCalc.takeProfit1 - entryPrice) / slDistance
        val rTp2 = abs(targetCalc.takeProfit2 - entryPrice) / slDistance
        val rTp3 = abs(targetCalc.takeProfit3 - entryPrice) / slDistance

        val tiers = listOf(
            PartialProfitTier(
                label = "TP1 (Initial Scale-Out)",
                price = targetCalc.takeProfit1,
                priceFormatted = fmtPrice(targetCalc.takeProfit1),
                allocationPercent = 40,
                netRoiPercent = targetCalc.tp1NetRoiPercent,
                rMultiple = rTp1
            ),
            PartialProfitTier(
                label = "TP2 (Primary Structural Target)",
                price = targetCalc.takeProfit2,
                priceFormatted = fmtPrice(targetCalc.takeProfit2),
                allocationPercent = 35,
                netRoiPercent = targetCalc.tp2NetRoiPercent,
                rMultiple = rTp2
            ),
            PartialProfitTier(
                label = "TP3 (Extended Runner)",
                price = targetCalc.takeProfit3,
                priceFormatted = fmtPrice(targetCalc.takeProfit3),
                allocationPercent = 25,
                netRoiPercent = targetCalc.tp3NetRoiPercent,
                rMultiple = rTp3
            )
        )

        val barDurationMs = when (timeframe) {
            CandleTimeframe.M1 -> 60_000L
            CandleTimeframe.M3 -> 180_000L
            CandleTimeframe.M5 -> 300_000L
            CandleTimeframe.M15 -> 900_000L
            CandleTimeframe.M30 -> 1_800_000L
            CandleTimeframe.H1 -> 3_600_000L
            CandleTimeframe.H2 -> 7_200_000L
            CandleTimeframe.H4 -> 14_400_000L
            CandleTimeframe.H6 -> 21_600_000L
            CandleTimeframe.H12 -> 43_200_000L
            CandleTimeframe.D1 -> 86_400_000L
            CandleTimeframe.W1 -> 604_800_000L
        }
        val expiryMs = referenceTimeMs + (setupExpiryBars.toLong() * barDurationMs)
        val expiryPkt = TimeZoneUtils.formatKarachiTimestamp(expiryMs)

        val invalidationBasis = if (direction == FuturesDirection.LONG) {
            "Uses deeper of 1H S/R demand (${fmtPrice(srInvalidationLevel)}) vs 1.3x 15M ATR floor (${fmtPrice(atrStop)})"
        } else {
            "Uses higher of 1H S/R supply (${fmtPrice(srInvalidationLevel)}) vs 1.3x 15M ATR ceiling (${fmtPrice(atrStop)})"
        }

        val exitConditions = listOf(
            "Hard Stop Loss near ${fmtPrice(targetCalc.stopLoss)} (-${String.format(Locale.US, "%.2f", targetCalc.slDistancePercent)}% / ${String.format(Locale.US, "%.1f", targetCalc.slNetMarginLossPercent)}% margin est.) — protects before est. liquidation ${fmtPrice(targetCalc.estimatedLiquidationPrice)}.",
            "Scale 40% at TP1 (${fmtPrice(targetCalc.takeProfit1)}, +${String.format(Locale.US, "%.1f", targetCalc.tp1NetRoiPercent)}% net ROI) and consider moving stop to breakeven.",
            "Scale 35% at TP2 (${fmtPrice(targetCalc.takeProfit2)}, 1:${String.format(Locale.US, "%.2f", rTp2)} R) and trail remaining 25% toward TP3 (${fmtPrice(targetCalc.takeProfit3)}).",
            "Time-stop / Setup Expiry: invalidate un-triggered setup if no completed close triggers within $setupExpiryBars ${timeframe.label} bars ($expiryPkt)."
        )

        val assumptions = listOf(
            "Calculated BEFORE entry using ${targetCalc.leverage}x capped leverage and $${String.format(Locale.US, "%.2f", targetCalc.initialMarginUsdt)} initial margin.",
            "Net ROI subtracts ${String.format(Locale.US, "%.2f", targetCalc.estimatedFeeRatePercent)}% round-trip taker fees" +
                (if (targetCalc.fundingDataReliable) " and ${String.format(Locale.US, "%.2f", targetCalc.estimatedFundingCostMarginPercent)}% adverse funding reserve." else " (live funding unavailable/excluded)."),
            "15M ATR(14) volatility measured at ${fmtPrice(atr15m)}; structural S/R invalidation at ${fmtPrice(srInvalidationLevel)}."
        )

        return PreEntryExitPlan(
            stopLoss = targetCalc.stopLoss,
            stopLossFormatted = fmtPrice(targetCalc.stopLoss),
            srInvalidationLevel = srInvalidationLevel,
            atrBasedStopLevel = atrStop,
            atrValue = atr15m,
            invalidationBasis = invalidationBasis,
            takeProfitTiers = tiers,
            riskRewardRatio = targetCalc.riskRewardRatio,
            setupExpiryBars = setupExpiryBars,
            expiryDeadlinePkt = expiryPkt,
            expiryCondition = "Expires after $setupExpiryBars completed ${timeframe.label} candles ($expiryPkt) if un-triggered.",
            exitConditions = exitConditions,
            calculationAssumptions = assumptions,
            stopOrderDisclaimer = PreEntryExitPlan.DEFAULT_STOP_ORDER_DISCLAIMER
        )
    }

    private fun fmtPrice(price: Double): String {
        return when {
            price >= 1000.0 -> String.format(Locale.US, "$%.2f", price)
            price >= 1.0 -> String.format(Locale.US, "$%.2f", price)
            price >= 0.01 -> String.format(Locale.US, "$%.4f", price)
            else -> String.format(Locale.US, "$%.6f", price)
        }
    }
}
