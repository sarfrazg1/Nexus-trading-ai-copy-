package com.example.data.risk

import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import java.util.Locale
import kotlin.math.abs

/**
 * Risk Manager Engine (adapted from trader-risk):
 * Enforces all 12 pre-trade risk controls before allowing even a paper trade submission.
 * Returns explicit PASS / WARNING / BLOCK decisions with concrete reasons and NEVER silently overrides a BLOCK.
 */
object RiskManagerEngine {

    private const val MIN_SL_DISTANCE_PERCENT = 0.25
    private const val MAX_SL_DISTANCE_PERCENT = 10.0

    fun evaluate(
        signal: FuturesScanSignal,
        budgetConfig: BudgetConfig,
        budgetPlan: BudgetCalculationResult,
        requestedLeverage: Int = signal.suggestedLeverage,
        dailyLedger: DailyRiskLedger = DailyRiskLedger(),
        requireTriggeredEntry: Boolean = false
    ): RiskAssessmentResult {
        val checks = mutableListOf<RiskCheckItem>()

        // 1. Available Balance
        val availableBalance = (budgetConfig.accountBalanceUsdt - dailyLedger.existingOpenMarginUsdt).coerceAtLeast(0.0)
        val balanceCheck = when {
            budgetConfig.accountBalanceUsdt <= 0.0 || availableBalance <= 0.0 -> RiskCheckItem(
                category = RiskCheckCategory.AVAILABLE_BALANCE,
                decision = RiskDecision.BLOCK,
                reason = "Insufficient available balance ($${fmt(availableBalance)} USDT free after existing margin).",
                actualValue = "$${fmt(availableBalance)}",
                limitValue = "> $0.00"
            )
            availableBalance < budgetConfig.accountBalanceUsdt * 0.25 -> RiskCheckItem(
                category = RiskCheckCategory.AVAILABLE_BALANCE,
                decision = RiskDecision.WARNING,
                reason = "Free balance is below 25% of total equity.",
                actualValue = "$${fmt(availableBalance)}",
                limitValue = ">= 25% equity"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.AVAILABLE_BALANCE,
                decision = RiskDecision.PASS,
                reason = "Free account balance covers trade margin and buffers.",
                actualValue = "$${fmt(availableBalance)}",
                limitValue = "> $0.00"
            )
        }
        checks.add(balanceCheck)

        // 2. Max Permitted Loss
        val maxPctLossAllowed = budgetConfig.accountBalanceUsdt * (budgetConfig.maxRiskPercent / 100.0)
        val maxLossCheck = when {
            budgetPlan.effectivePermittedLossUsdt <= 0.0 -> RiskCheckItem(
                category = RiskCheckCategory.MAX_PERMITTED_LOSS,
                decision = RiskDecision.BLOCK,
                reason = "Configured max permitted loss is non-positive.",
                actualValue = "$0.00",
                limitValue = "> $0.00"
            )
            budgetPlan.maximumPlannedLossUsdt > budgetPlan.effectivePermittedLossUsdt * 1.05 -> RiskCheckItem(
                category = RiskCheckCategory.MAX_PERMITTED_LOSS,
                decision = RiskDecision.BLOCK,
                reason = "Planned SL + fees + slippage ($${fmt(budgetPlan.maximumPlannedLossUsdt)}) exceeds max permitted loss ($${fmt(budgetPlan.effectivePermittedLossUsdt)}).",
                actualValue = "$${fmt(budgetPlan.maximumPlannedLossUsdt)}",
                limitValue = "<= $${fmt(budgetPlan.effectivePermittedLossUsdt)}"
            )
            budgetConfig.maxRiskPercent > 3.0 -> RiskCheckItem(
                category = RiskCheckCategory.MAX_PERMITTED_LOSS,
                decision = RiskDecision.WARNING,
                reason = "Risk per trade (${String.format(Locale.US, "%.1f%%", budgetConfig.maxRiskPercent)}) exceeds conservative 3% institutional guideline.",
                actualValue = "$${fmt(budgetPlan.maximumPlannedLossUsdt)} (${String.format(Locale.US, "%.1f%%", budgetConfig.maxRiskPercent)})",
                limitValue = "<= $${fmt(maxPctLossAllowed)} (<=3%)"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.MAX_PERMITTED_LOSS,
                decision = RiskDecision.PASS,
                reason = "Planned worst-case loss including fees/slippage fits permitted risk budget.",
                actualValue = "$${fmt(budgetPlan.maximumPlannedLossUsdt)}",
                limitValue = "<= $${fmt(budgetPlan.effectivePermittedLossUsdt)}"
            )
        }
        checks.add(maxLossCheck)

        // 3. Daily Loss Limit
        val maxDailyLossUsdt = budgetConfig.accountBalanceUsdt * (budgetConfig.dailyLossLimitPercent / 100.0)
        val currentDailyLoss = (-dailyLedger.realizedPnlUsdtToday).coerceAtLeast(0.0)
        val projectedDailyLoss = currentDailyLoss + budgetPlan.maximumPlannedLossUsdt
        val dailyLossCheck = when {
            currentDailyLoss >= maxDailyLossUsdt -> RiskCheckItem(
                category = RiskCheckCategory.DAILY_LOSS_LIMIT,
                decision = RiskDecision.BLOCK,
                reason = "Daily realized loss ($${fmt(currentDailyLoss)}) has already reached the daily loss cap ($${fmt(maxDailyLossUsdt)}). Trading halted for today.",
                actualValue = "$${fmt(currentDailyLoss)}",
                limitValue = "< $${fmt(maxDailyLossUsdt)}"
            )
            projectedDailyLoss > maxDailyLossUsdt -> RiskCheckItem(
                category = RiskCheckCategory.DAILY_LOSS_LIMIT,
                decision = RiskDecision.WARNING,
                reason = "If this trade stops out, cumulative daily loss ($${fmt(projectedDailyLoss)}) would exceed daily cap ($${fmt(maxDailyLossUsdt)}).",
                actualValue = "$${fmt(projectedDailyLoss)} est.",
                limitValue = "<= $${fmt(maxDailyLossUsdt)}"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.DAILY_LOSS_LIMIT,
                decision = RiskDecision.PASS,
                reason = "Daily loss budget has ample headroom.",
                actualValue = "$${fmt(currentDailyLoss)} used",
                limitValue = "<= $${fmt(maxDailyLossUsdt)}"
            )
        }
        checks.add(dailyLossCheck)

        // 4. Maximum Number of Trades
        val maxTrades = budgetConfig.maxTradesPerDay.coerceAtLeast(1)
        val tradesCheck = when {
            dailyLedger.tradesTakenToday >= maxTrades -> RiskCheckItem(
                category = RiskCheckCategory.MAX_TRADES_LIMIT,
                decision = RiskDecision.BLOCK,
                reason = "Daily trade cap reached (${dailyLedger.tradesTakenToday}/$maxTrades trades today). Prevents overtrading.",
                actualValue = "${dailyLedger.tradesTakenToday}",
                limitValue = "< $maxTrades"
            )
            dailyLedger.tradesTakenToday == maxTrades - 1 -> RiskCheckItem(
                category = RiskCheckCategory.MAX_TRADES_LIMIT,
                decision = RiskDecision.WARNING,
                reason = "Approaching daily trade limit (${dailyLedger.tradesTakenToday}/$maxTrades taken).",
                actualValue = "${dailyLedger.tradesTakenToday}",
                limitValue = "<= $maxTrades"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.MAX_TRADES_LIMIT,
                decision = RiskDecision.PASS,
                reason = "Within daily trade frequency allowance.",
                actualValue = "${dailyLedger.tradesTakenToday}/$maxTrades",
                limitValue = "<= $maxTrades"
            )
        }
        checks.add(tradesCheck)

        // 5. Position Size & Exchange Precision
        val minLot = budgetPlan.precisionSpec?.minQty ?: 0.001
        val posCheck = when {
            !budgetPlan.isValid || budgetPlan.positionQuantity <= 0.0 || budgetPlan.positionQuantity < minLot -> RiskCheckItem(
                category = RiskCheckCategory.POSITION_SIZE,
                decision = RiskDecision.BLOCK,
                reason = budgetPlan.validationError ?: "Position quantity (${budgetPlan.positionQuantityFormatted}) is below Binance minQty ($minLot).",
                actualValue = budgetPlan.positionQuantityFormatted,
                limitValue = ">= $minLot"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.POSITION_SIZE,
                decision = RiskDecision.PASS,
                reason = "Contract size matches risk budget and Binance ${budgetPlan.symbol} step precision.",
                actualValue = "${budgetPlan.positionQuantityFormatted} ($${fmt(budgetPlan.notionalValueUsdt)})",
                limitValue = ">= $minLot"
            )
        }
        checks.add(posCheck)

        // 6. Leverage Cap
        val levLimit = budgetConfig.leverageLimit.coerceIn(1, 20)
        val levCheck = when {
            requestedLeverage <= 0 -> RiskCheckItem(
                category = RiskCheckCategory.LEVERAGE_CAP,
                decision = RiskDecision.BLOCK,
                reason = "Leverage must be at least 1x.",
                actualValue = "${requestedLeverage}x",
                limitValue = "1x-${levLimit}x"
            )
            requestedLeverage > levLimit -> RiskCheckItem(
                category = RiskCheckCategory.LEVERAGE_CAP,
                decision = RiskDecision.BLOCK,
                reason = "Requested leverage (${requestedLeverage}x) exceeds configured safety cap (${levLimit}x).",
                actualValue = "${requestedLeverage}x",
                limitValue = "<= ${levLimit}x"
            )
            requestedLeverage >= 8 -> RiskCheckItem(
                category = RiskCheckCategory.LEVERAGE_CAP,
                decision = RiskDecision.WARNING,
                reason = "High leverage (${requestedLeverage}x) accelerates liquidation risk on volatile wicks.",
                actualValue = "${requestedLeverage}x",
                limitValue = "<= ${levLimit}x"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.LEVERAGE_CAP,
                decision = RiskDecision.PASS,
                reason = "Leverage within configured safety cap.",
                actualValue = "${requestedLeverage}x",
                limitValue = "<= ${levLimit}x"
            )
        }
        checks.add(levCheck)

        // 7. Margin Requirements
        val marginCheck = when {
            budgetPlan.estimatedMarginUsdt > availableBalance -> RiskCheckItem(
                category = RiskCheckCategory.MARGIN_REQUIREMENTS,
                decision = RiskDecision.BLOCK,
                reason = "Required initial margin ($${fmt(budgetPlan.estimatedMarginUsdt)}) exceeds available free balance ($${fmt(availableBalance)}).",
                actualValue = "$${fmt(budgetPlan.estimatedMarginUsdt)}",
                limitValue = "<= $${fmt(availableBalance)}"
            )
            budgetPlan.estimatedMarginUsdt > availableBalance * 0.6 -> RiskCheckItem(
                category = RiskCheckCategory.MARGIN_REQUIREMENTS,
                decision = RiskDecision.WARNING,
                reason = "Position consumes over 60% of available margin ($${fmt(budgetPlan.estimatedMarginUsdt)} / $${fmt(availableBalance)}).",
                actualValue = "$${fmt(budgetPlan.estimatedMarginUsdt)}",
                limitValue = "<= 60% free bal"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.MARGIN_REQUIREMENTS,
                decision = RiskDecision.PASS,
                reason = "Estimated margin requirement fits comfortably within free balance.",
                actualValue = "$${fmt(budgetPlan.estimatedMarginUsdt)}",
                limitValue = "<= $${fmt(availableBalance)}"
            )
        }
        checks.add(marginCheck)

        // 8. Risk / Reward Ratio
        val rr = if (budgetPlan.rewardRiskRatio > 0.0) budgetPlan.rewardRiskRatio else signal.riskRewardRatio
        val rrCheck = when {
            rr < budgetConfig.minRiskRewardRatio -> RiskCheckItem(
                category = RiskCheckCategory.RISK_REWARD_RATIO,
                decision = RiskDecision.BLOCK,
                reason = "Reward/Risk (1:${String.format(Locale.US, "%.2f", rr)}) is below minimum required 1:${String.format(Locale.US, "%.2f", budgetConfig.minRiskRewardRatio)}.",
                actualValue = "1:${String.format(Locale.US, "%.2f", rr)}",
                limitValue = ">= 1:${String.format(Locale.US, "%.2f", budgetConfig.minRiskRewardRatio)}"
            )
            rr < 2.0 -> RiskCheckItem(
                category = RiskCheckCategory.RISK_REWARD_RATIO,
                decision = RiskDecision.WARNING,
                reason = "Reward/Risk (1:${String.format(Locale.US, "%.2f", rr)}) clears minimum threshold but is below 1:2.0 preferred target.",
                actualValue = "1:${String.format(Locale.US, "%.2f", rr)}",
                limitValue = "Preferred >= 1:2.0"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.RISK_REWARD_RATIO,
                decision = RiskDecision.PASS,
                reason = "Favorable structural Reward/Risk profile.",
                actualValue = "1:${String.format(Locale.US, "%.2f", rr)}",
                limitValue = ">= 1:${String.format(Locale.US, "%.2f", budgetConfig.minRiskRewardRatio)}"
            )
        }
        checks.add(rrCheck)

        // 9. Entry Validity
        val timerStatus = signal.entryTimer?.status
        val entryInvalidReason: String? = when {
            !signal.entryPrice.isFinite() || signal.entryPrice <= 0.0 -> "Invalid non-positive entry price."
            timerStatus == EntryTimerStatus.INVALIDATED -> "Entry Timer reports setup is INVALIDATED."
            timerStatus == EntryTimerStatus.EXPIRED -> "Entry Timer reports setup is EXPIRED."
            timerStatus == EntryTimerStatus.NO_DATA -> "Entry Timer reports NO DATA / insufficient candles."
            requireTriggeredEntry && timerStatus != EntryTimerStatus.TRIGGERED ->
                "Entry Timer is ${timerStatus?.label ?: "WAITING"}; completed candle close confirmation required."
            else -> null
        }
        val entryCheck = when {
            entryInvalidReason != null -> RiskCheckItem(
                category = RiskCheckCategory.ENTRY_VALIDITY,
                decision = RiskDecision.BLOCK,
                reason = entryInvalidReason,
                actualValue = timerStatus?.label ?: "INVALID",
                limitValue = "Active / Triggered"
            )
            timerStatus == EntryTimerStatus.WAITING -> RiskCheckItem(
                category = RiskCheckCategory.ENTRY_VALIDITY,
                decision = RiskDecision.WARNING,
                reason = "Entry Timer is WAITING for completed candle close confirmation; do not chase early wicks.",
                actualValue = "WAITING",
                limitValue = "TRIGGERED"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.ENTRY_VALIDITY,
                decision = RiskDecision.PASS,
                reason = "Entry levels valid and confirmed on completed candle.",
                actualValue = timerStatus?.label ?: "VALID",
                limitValue = "Valid Entry"
            )
        }
        checks.add(entryCheck)

        // 10. Stop-Loss Distance
        val slDistPct = if (signal.entryPrice > 0.0) abs(signal.entryPrice - signal.stopLoss) / signal.entryPrice * 100.0 else 0.0
        val slWrongSide = (signal.direction == FuturesDirection.LONG && signal.stopLoss >= signal.entryPrice) ||
            (signal.direction == FuturesDirection.SHORT && signal.stopLoss <= signal.entryPrice)
        val slCheck = when {
            slWrongSide || !signal.stopLoss.isFinite() || signal.stopLoss <= 0.0 -> RiskCheckItem(
                category = RiskCheckCategory.STOP_LOSS_DISTANCE,
                decision = RiskDecision.BLOCK,
                reason = "Stop-loss is missing or placed on the wrong side of entry price.",
                actualValue = "${String.format(Locale.US, "%.2f%%", slDistPct)}",
                limitValue = "${MIN_SL_DISTANCE_PERCENT}%-${MAX_SL_DISTANCE_PERCENT}%"
            )
            slDistPct < MIN_SL_DISTANCE_PERCENT -> RiskCheckItem(
                category = RiskCheckCategory.STOP_LOSS_DISTANCE,
                decision = RiskDecision.BLOCK,
                reason = "Stop-loss (${String.format(Locale.US, "%.2f%%", slDistPct)}) is too tight (< ${MIN_SL_DISTANCE_PERCENT}%) and prone to routine spread/wick stop-outs.",
                actualValue = "${String.format(Locale.US, "%.2f%%", slDistPct)}",
                limitValue = ">= ${MIN_SL_DISTANCE_PERCENT}%"
            )
            slDistPct > MAX_SL_DISTANCE_PERCENT -> RiskCheckItem(
                category = RiskCheckCategory.STOP_LOSS_DISTANCE,
                decision = RiskDecision.BLOCK,
                reason = "Stop-loss distance (${String.format(Locale.US, "%.2f%%", slDistPct)}) exceeds ${MAX_SL_DISTANCE_PERCENT}% structural risk cap.",
                actualValue = "${String.format(Locale.US, "%.2f%%", slDistPct)}",
                limitValue = "<= ${MAX_SL_DISTANCE_PERCENT}%"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.STOP_LOSS_DISTANCE,
                decision = RiskDecision.PASS,
                reason = "Stop-loss distance respects ATR/structure noise buffer and maximum cap.",
                actualValue = "${String.format(Locale.US, "%.2f%%", slDistPct)}",
                limitValue = "${MIN_SL_DISTANCE_PERCENT}%-${MAX_SL_DISTANCE_PERCENT}%"
            )
        }
        checks.add(slCheck)

        // 11. Liquidation Risk
        val liqDistPct = budgetPlan.liquidationDistancePercent
        val slToLiqRatio = if (liqDistPct > 0.0) slDistPct / liqDistPct else 1.0
        val liqCheck = when {
            liqDistPct <= slDistPct || slToLiqRatio >= 0.70 -> RiskCheckItem(
                category = RiskCheckCategory.LIQUIDATION_RISK,
                decision = RiskDecision.BLOCK,
                reason = "Stop-loss (${String.format(Locale.US, "%.2f%%", slDistPct)}) sits too close to estimated liquidation (${String.format(Locale.US, "%.2f%%", liqDistPct)}). Lower leverage or tighten structural setup.",
                actualValue = "SL ${String.format(Locale.US, "%.2f%%", slDistPct)} vs Liq ${String.format(Locale.US, "%.2f%%", liqDistPct)}",
                limitValue = "SL < 70% Liq dist"
            )
            slToLiqRatio >= 0.50 -> RiskCheckItem(
                category = RiskCheckCategory.LIQUIDATION_RISK,
                decision = RiskDecision.WARNING,
                reason = "Stop-loss uses ${String.format(Locale.US, "%.0f%%", slToLiqRatio * 100.0)} of distance to estimated liquidation.",
                actualValue = "${String.format(Locale.US, "%.0f%%", slToLiqRatio * 100.0)} buffer used",
                limitValue = "< 50% preferred"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.LIQUIDATION_RISK,
                decision = RiskDecision.PASS,
                reason = "Healthy buffer between Stop-Loss (${String.format(Locale.US, "%.2f%%", slDistPct)}) and estimated liquidation (${String.format(Locale.US, "%.2f%%", liqDistPct)}).",
                actualValue = "Liq ${String.format(Locale.US, "%.2f%%", liqDistPct)}",
                limitValue = "SL < 50% Liq dist"
            )
        }
        checks.add(liqCheck)

        // 12. Existing Exposure
        val newTotalMargin = dailyLedger.existingOpenMarginUsdt + budgetPlan.estimatedMarginUsdt
        val exposurePct = if (budgetConfig.accountBalanceUsdt > 0.0) (newTotalMargin / budgetConfig.accountBalanceUsdt) * 100.0 else 100.0
        val duplicateOpenSymbol = dailyLedger.openOrderSymbols.any { it.equals(signal.symbol, ignoreCase = true) }
        val exposureCheck = when {
            duplicateOpenSymbol -> RiskCheckItem(
                category = RiskCheckCategory.EXISTING_EXPOSURE,
                decision = RiskDecision.BLOCK,
                reason = "An open paper/tracked position already exists on ${signal.symbol}. Close or manage existing exposure before adding correlated risk.",
                actualValue = "Open on ${signal.symbol}",
                limitValue = "No duplicate pair"
            )
            exposurePct > budgetConfig.maxOpenExposurePercentOfBalance -> RiskCheckItem(
                category = RiskCheckCategory.EXISTING_EXPOSURE,
                decision = RiskDecision.BLOCK,
                reason = "Total margin exposure (${String.format(Locale.US, "%.1f%%", exposurePct)}) would exceed configured ${String.format(Locale.US, "%.0f%%", budgetConfig.maxOpenExposurePercentOfBalance)} equity exposure limit.",
                actualValue = "${String.format(Locale.US, "%.1f%%", exposurePct)}",
                limitValue = "<= ${String.format(Locale.US, "%.0f%%", budgetConfig.maxOpenExposurePercentOfBalance)}"
            )
            dailyLedger.openOrderSymbols.isNotEmpty() -> RiskCheckItem(
                category = RiskCheckCategory.EXISTING_EXPOSURE,
                decision = RiskDecision.WARNING,
                reason = "Other open positions (${dailyLedger.openOrderSymbols.joinToString(", ")}) increase portfolio correlation.",
                actualValue = "${dailyLedger.openOrderSymbols.size} open (${String.format(Locale.US, "%.1f%%", exposurePct)})",
                limitValue = "<= ${String.format(Locale.US, "%.0f%%", budgetConfig.maxOpenExposurePercentOfBalance)}"
            )
            else -> RiskCheckItem(
                category = RiskCheckCategory.EXISTING_EXPOSURE,
                decision = RiskDecision.PASS,
                reason = "No conflicting or excessive existing exposure.",
                actualValue = "${String.format(Locale.US, "%.1f%%", exposurePct)} equity",
                limitValue = "<= ${String.format(Locale.US, "%.0f%%", budgetConfig.maxOpenExposurePercentOfBalance)}"
            )
        }
        checks.add(exposureCheck)

        val blocked = checks.filter { it.decision == RiskDecision.BLOCK }.map { "${it.category.title}: ${it.reason}" }
        val warnings = checks.filter { it.decision == RiskDecision.WARNING }.map { "${it.category.title}: ${it.reason}" }

        val overall = when {
            blocked.isNotEmpty() -> RiskDecision.BLOCK
            warnings.isNotEmpty() -> RiskDecision.WARNING
            else -> RiskDecision.PASS
        }

        return RiskAssessmentResult(
            overallDecision = overall,
            checks = checks,
            blockedReasons = blocked,
            warningReasons = warnings,
            canProceedToOrder = overall != RiskDecision.BLOCK
        )
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.2f", v)
}
