package com.example.data.risk

import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Budget Manager Engine (adapted from trader-budget):
 * Sizes Binance USDT-M Futures positions strictly from Maximum Permitted Loss & Stop-Loss distance,
 * honoring PKR or USDT risk budgets, fee + slippage buffers, and exchange contract stepSize/minQty precision.
 */
object BudgetManagerEngine {

    private const val MAINTENANCE_MARGIN_RATE = 0.005 // 0.5% isolated MMR

    fun calculatePlan(
        symbol: String,
        direction: FuturesDirection,
        entryPrice: Double,
        stopLossPrice: Double,
        takeProfit1Price: Double,
        takeProfit2Price: Double,
        requestedLeverage: Int,
        budgetConfig: BudgetConfig
    ): BudgetCalculationResult {
        if (symbol.isBlank() ||
            !entryPrice.isFinite() || entryPrice <= 0.0 ||
            !stopLossPrice.isFinite() || stopLossPrice <= 0.0 ||
            !takeProfit2Price.isFinite() || takeProfit2Price <= 0.0
        ) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "Invalid market entry, stop-loss, or take-profit price for $symbol."
            )
        }
        if (direction == FuturesDirection.LONG && stopLossPrice >= entryPrice) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "LONG stop-loss ($stopLossPrice) must be strictly below entry ($entryPrice)."
            )
        }
        if (direction == FuturesDirection.SHORT && stopLossPrice <= entryPrice) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "SHORT stop-loss ($stopLossPrice) must be strictly above entry ($entryPrice)."
            )
        }
        if (!budgetConfig.accountBalanceUsdt.isFinite() || budgetConfig.accountBalanceUsdt <= 0.0) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "Account balance must be greater than 0 USDT."
            )
        }
        if (!budgetConfig.pkrPerUsdtRate.isFinite() || budgetConfig.pkrPerUsdtRate <= 0.0) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "PKR/USDT exchange rate must be positive."
            )
        }

        val amountInUsdt = when (budgetConfig.riskCurrency) {
            RiskCurrency.USDT -> budgetConfig.maxRiskPerTradeAmount
            RiskCurrency.PKR -> budgetConfig.maxRiskPerTradeAmount / budgetConfig.pkrPerUsdtRate
        }.coerceAtLeast(0.0)

        val percentCapUsdt = budgetConfig.accountBalanceUsdt * (budgetConfig.maxRiskPercent.coerceAtLeast(0.0) / 100.0)
        val effectivePermittedLossUsdt = min(amountInUsdt, percentCapUsdt)
        if (effectivePermittedLossUsdt <= 0.0) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "Maximum permitted loss per trade is $0.00. Increase risk budget or risk %."
            )
        }

        val effectiveLeverage = requestedLeverage.coerceIn(1, budgetConfig.leverageLimit.coerceAtLeast(1))
        val slDistancePerUnit = abs(entryPrice - stopLossPrice)
        val feeRateSide = budgetConfig.feeRatePercentPerSide.coerceAtLeast(0.0) / 100.0
        val slippageRateSide = budgetConfig.slippagePercentPerSide.coerceAtLeast(0.0) / 100.0

        // Per-unit total worst-case planned risk = SL price move + round-trip fees + round-trip slippage
        val perUnitCostOverhead = (entryPrice * feeRateSide) + (stopLossPrice * feeRateSide) +
            (entryPrice * slippageRateSide) + (stopLossPrice * slippageRateSide)
        val totalLossPerUnit = slDistancePerUnit + perUnitCostOverhead

        if (totalLossPerUnit <= 0.0) {
            return BudgetCalculationResult(
                isValid = false,
                validationError = "Stop-loss distance is zero."
            )
        }

        val rawQty = effectivePermittedLossUsdt / totalLossPerUnit
        val spec = FuturesSymbolPrecisionRegistry.forSymbol(symbol, entryPrice)

        val steppedQty = if (spec.stepSize > 0.0) {
            floor(rawQty / spec.stepSize) * spec.stepSize
        } else rawQty
        val quantity = steppedQty.coerceAtMost(spec.maxQty)

        val notional = quantity * entryPrice
        val estimatedMargin = notional / effectiveLeverage.toDouble()
        val slAmount = quantity * slDistancePerUnit
        val estimatedFees = quantity * ((entryPrice * feeRateSide) + (stopLossPrice * feeRateSide))
        val estimatedSlippage = quantity * ((entryPrice * slippageRateSide) + (stopLossPrice * slippageRateSide))
        val maxPlannedLoss = slAmount + estimatedFees + estimatedSlippage

        val tp1Move = abs(takeProfit1Price - entryPrice)
        val tp2Move = abs(takeProfit2Price - entryPrice)
        val tp1NetProfit = (quantity * tp1Move) - (quantity * ((entryPrice + takeProfit1Price) * (feeRateSide + slippageRateSide)))
        val tp2NetProfit = (quantity * tp2Move) - (quantity * ((entryPrice + takeProfit2Price) * (feeRateSide + slippageRateSide)))
        val rewardRisk = if (slDistancePerUnit > 0.0) tp2Move / slDistancePerUnit else 0.0

        val liqMoveFraction = ((1.0 / effectiveLeverage.toDouble()) - MAINTENANCE_MARGIN_RATE - (feeRateSide * 2.0)).coerceAtLeast(0.005)
        val estLiqPrice = if (direction == FuturesDirection.LONG) {
            entryPrice * (1.0 - liqMoveFraction)
        } else {
            entryPrice * (1.0 + liqMoveFraction)
        }
        val liqDistancePct = liqMoveFraction * 100.0

        val qtyFormatted = String.format(Locale.US, "%.${spec.qtyDecimals}f", quantity)
        val configuredLossPkr = amountInUsdt * budgetConfig.pkrPerUsdtRate

        val errorMsg = if (quantity < spec.minQty) {
            "Calculated quantity ($qtyFormatted ${spec.symbol}) is below Binance minimum order lot (${spec.minQty}). Increase permitted loss or widen SL carefully within balance."
        } else null

        return BudgetCalculationResult(
            isValid = errorMsg == null,
            validationError = errorMsg,
            symbol = spec.symbol,
            direction = direction,
            entryPrice = entryPrice,
            stopLossPrice = stopLossPrice,
            takeProfit1Price = takeProfit1Price,
            takeProfit2Price = takeProfit2Price,
            configuredMaxLossUsdt = amountInUsdt,
            configuredMaxLossPkr = configuredLossPkr,
            effectivePermittedLossUsdt = effectivePermittedLossUsdt,
            positionQuantity = quantity,
            positionQuantityFormatted = qtyFormatted,
            precisionSpec = spec,
            notionalValueUsdt = notional,
            estimatedMarginUsdt = estimatedMargin,
            leverageUsed = effectiveLeverage,
            stopLossDistanceUsdt = slDistancePerUnit,
            stopLossAmountUsdt = slAmount,
            maximumPlannedLossUsdt = maxPlannedLoss,
            estimatedFeesUsdt = estimatedFees,
            estimatedSlippageUsdt = estimatedSlippage,
            projectedTp1NetProfitUsdt = tp1NetProfit,
            projectedTp2NetProfitUsdt = tp2NetProfit,
            rewardRiskRatio = rewardRisk,
            estimatedLiquidationPrice = estLiqPrice,
            liquidationDistancePercent = liqDistancePct,
            pkrPerUsdtRate = budgetConfig.pkrPerUsdtRate,
            leverageRiskExplanation = BudgetCalculationResult.LEVERAGE_RISK_EXPLANATION
        )
    }

    fun calculatePlanForSignal(
        signal: FuturesScanSignal,
        budgetConfig: BudgetConfig,
        requestedLeverage: Int = signal.suggestedLeverage
    ): BudgetCalculationResult {
        return calculatePlan(
            symbol = signal.symbol,
            direction = signal.direction,
            entryPrice = signal.entryPrice,
            stopLossPrice = signal.stopLoss,
            takeProfit1Price = signal.takeProfit1,
            takeProfit2Price = signal.takeProfit2,
            requestedLeverage = requestedLeverage,
            budgetConfig = budgetConfig
        )
    }
}
