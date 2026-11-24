package com.example.data.broker

import com.example.data.risk.BudgetCalculationResult
import com.example.data.risk.RiskAssessmentResult
import com.example.data.risk.RiskDecision
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.util.TimeZoneUtils
import java.util.Locale

/**
 * Broker / Order Executor Engine — Safe Mode (adapted from trader-broker):
 * - Default mode is strictly PAPER TRADING.
 * - Requires explicit user confirmation AND non-BLOCKed RiskAssessment before submitting even a paper order.
 * - Never submits real-money orders, never stores exchange API secrets in Android, never requests withdrawal scopes.
 * - Generates a transparent Manual Order Plan when live exchange backend authorization is unavailable.
 */
object PaperOrderBrokerEngine {

    fun buildProposedTicket(
        signal: FuturesScanSignal,
        budgetPlan: BudgetCalculationResult,
        mode: ExecutionMode = ExecutionMode.PAPER_TRADING
    ): ProposedOrderTicket {
        val sideAction = if (signal.direction == FuturesDirection.LONG) "BUY / LONG" else "SELL / SHORT"
        val closeSide = if (signal.direction == FuturesDirection.LONG) "SELL (Reduce-Only)" else "BUY (Reduce-Only)"
        val manualSteps = listOf(
            "1. On Binance USDT-M Futures, select ${signal.symbol} and switch margin mode to ISOLATED at <= ${budgetPlan.leverageUsed}x leverage.",
            "2. Enter $sideAction Limit/Market order for ${budgetPlan.positionQuantityFormatted} ${signal.baseAsset} (~$${String.format(Locale.US, "%.2f", budgetPlan.notionalValueUsdt)} notional, ~$${String.format(Locale.US, "%.2f", budgetPlan.estimatedMarginUsdt)} margin) near ${signal.entryZoneFormatted}.",
            "3. Immediately attach Stop-Market $closeSide Stop Loss at ${signal.stopLossFormatted} (est. max planned loss $${String.format(Locale.US, "%.2f", budgetPlan.maximumPlannedLossUsdt)}).",
            "4. Place Reduce-Only Take-Profit ladder: 40% at TP1 (${signal.tp1Formatted}), 35% at TP2 (${signal.tp2Formatted}), 25% at TP3 (${signal.tp3Formatted}).",
            "5. Note: Stop orders are NOT guaranteed to fill at exact trigger price during volatility spikes."
        )

        return ProposedOrderTicket(
            signalId = signal.id,
            symbol = signal.symbol,
            direction = signal.direction,
            quantity = budgetPlan.positionQuantity,
            quantityFormatted = budgetPlan.positionQuantityFormatted,
            entryPrice = signal.entryPrice,
            stopLoss = signal.stopLoss,
            takeProfit1 = signal.takeProfit1,
            takeProfit2 = signal.takeProfit2,
            takeProfit3 = signal.takeProfit3,
            leverage = budgetPlan.leverageUsed,
            notionalValueUsdt = budgetPlan.notionalValueUsdt,
            estimatedMarginUsdt = budgetPlan.estimatedMarginUsdt,
            estimatedFeesUsdt = budgetPlan.estimatedFeesUsdt,
            estimatedSlippageUsdt = budgetPlan.estimatedSlippageUsdt,
            maxPlannedLossUsdt = budgetPlan.maximumPlannedLossUsdt,
            maxPlannedLossPkr = budgetPlan.maximumPlannedLossUsdt * budgetPlan.pkrPerUsdtRate,
            rewardRiskRatio = budgetPlan.rewardRiskRatio,
            executionMode = mode,
            manualOrderPlanSteps = manualSteps
        )
    }

    fun submitOrder(
        ticket: ProposedOrderTicket,
        riskAssessment: RiskAssessmentResult,
        userExplicitlyConfirmed: Boolean,
        hasSeparateBackendExchangeAuthorization: Boolean = false
    ): OrderSubmissionOutcome {
        if (!userExplicitlyConfirmed) {
            return OrderSubmissionOutcome.Rejected(
                reason = "Explicit user confirmation checkbox is required before submitting any paper or manual order plan."
            )
        }

        if (riskAssessment.overallDecision == RiskDecision.BLOCK || !riskAssessment.canProceedToOrder) {
            val reasons = riskAssessment.blockedReasons.joinToString("; ")
            return OrderSubmissionOutcome.Rejected(
                reason = "Risk Manager BLOCKED order submission: $reasons"
            )
        }

        if (ticket.quantity <= 0.0 || !ticket.entryPrice.isFinite() || !ticket.stopLoss.isFinite()) {
            return OrderSubmissionOutcome.Rejected(
                reason = "Invalid position quantity or entry/SL price on proposed ticket."
            )
        }

        // Live real-money exchange execution is strictly disallowed without authenticated backend authorization
        if (ticket.executionMode != ExecutionMode.PAPER_TRADING) {
            if (!hasSeparateBackendExchangeAuthorization) {
                return OrderSubmissionOutcome.ManualPlanOnly(
                    ticket = ticket,
                    reason = "No live order was sent to Binance. Automated real-money trading requires separate authenticated backend authorization (no API secrets stored in Android). Use the verified Manual Order Plan steps below."
                )
            }
        }

        val now = System.currentTimeMillis()
        val record = PaperOrderRecord(
            signalId = ticket.signalId,
            symbol = ticket.symbol,
            direction = ticket.direction,
            quantity = ticket.quantity,
            quantityFormatted = ticket.quantityFormatted,
            entryPrice = ticket.entryPrice,
            stopLoss = ticket.stopLoss,
            takeProfit1 = ticket.takeProfit1,
            takeProfit2 = ticket.takeProfit2,
            takeProfit3 = ticket.takeProfit3,
            leverage = ticket.leverage,
            estimatedMarginUsdt = ticket.estimatedMarginUsdt,
            estimatedFeesUsdt = ticket.estimatedFeesUsdt,
            maxPlannedLossUsdt = ticket.maxPlannedLossUsdt,
            status = PaperOrderStatus.OPEN_PAPER,
            userConfirmedExplicitly = true,
            executionMode = ExecutionMode.PAPER_TRADING,
            createdTimestampMs = now,
            createdTimestampPkt = TimeZoneUtils.formatKarachiTimestamp(now)
        )

        return OrderSubmissionOutcome.PaperOrderSubmitted(
            order = record,
            message = "PAPER ORDER recorded at ${record.createdTimestampPkt}: ${record.symbol} ${record.direction.name} qty ${record.quantityFormatted} @ $${String.format(Locale.US, "%.2f", record.entryPrice)}. (Simulated paper trade — no real exchange order submitted.)"
        )
    }
}
