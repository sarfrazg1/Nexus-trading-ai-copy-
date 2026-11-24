package com.example.data.alerts

import com.example.data.model.AlertDirection
import com.example.data.model.AlertType
import com.example.data.model.MarketAlert
import com.example.data.scanner.EntryTimerStatus
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.FuturesScanSignal
import com.example.data.scanner.FuturesSignalLifecycle
import com.example.util.TimeZoneUtils
import java.util.Locale
import kotlin.math.abs

/**
 * Evaluates all 7 trade lifecycle and market condition alert categories:
 * 1. ENTRY_TRIGGER
 * 2. STOP_LOSS
 * 3. TAKE_PROFIT
 * 4. PRICE_TARGET
 * 5. SETUP_INVALIDATION
 * 6. SIGNAL_EXPIRY
 * 7. MARKET_CONDITION_CHANGE
 *
 * Produces deterministic unique eventIds so neither foreground listeners, WorkManager background polls,
 * nor FCM dispatches duplicate alerts.
 */
object AlertMonitorEngine {

    fun createLifecycleRulesForSignal(
        signal: FuturesScanSignal,
        expiryHours: Int = 6
    ): List<TradeAlertRule> {
        val now = System.currentTimeMillis()
        val barDurationMs = when (signal.primaryTimeframe) {
            com.example.data.model.CandleTimeframe.M1 -> 60_000L
            com.example.data.model.CandleTimeframe.M3 -> 180_000L
            com.example.data.model.CandleTimeframe.M5 -> 300_000L
            com.example.data.model.CandleTimeframe.M15 -> 900_000L
            com.example.data.model.CandleTimeframe.M30 -> 1_800_000L
            com.example.data.model.CandleTimeframe.H1 -> 3_600_000L
            com.example.data.model.CandleTimeframe.H2 -> 7_200_000L
            com.example.data.model.CandleTimeframe.H4 -> 14_400_000L
            com.example.data.model.CandleTimeframe.H6 -> 21_600_000L
            else -> 900_000L
        }
        val expiryMs = signal.exitPlan?.let {
            signal.timestamp + it.setupExpiryBars.toLong() * barDurationMs
        } ?: (now + expiryHours * 60 * 60 * 1000L)
        val dir = signal.direction
        val tag = signal.id.take(8)
        return listOf(
            TradeAlertRule(
                ruleId = "entry_${signal.symbol}_$tag",
                signalId = signal.id,
                symbol = signal.symbol,
                direction = dir,
                category = AlertRuleCategory.ENTRY_TRIGGER,
                triggerPrice = signal.entryPrice,
                conditionDescription = "Entry zone triggered for ${signal.symbol} ${dir.name} near $${fmt(signal.entryPrice)}"
            ),
            TradeAlertRule(
                ruleId = "sl_${signal.symbol}_$tag",
                signalId = signal.id,
                symbol = signal.symbol,
                direction = dir,
                category = AlertRuleCategory.STOP_LOSS,
                triggerPrice = signal.stopLoss,
                conditionDescription = "Stop Loss level $${fmt(signal.stopLoss)} reached on ${signal.symbol}"
            ),
            TradeAlertRule(
                ruleId = "tp1_${signal.symbol}_$tag",
                signalId = signal.id,
                symbol = signal.symbol,
                direction = dir,
                category = AlertRuleCategory.TAKE_PROFIT,
                triggerPrice = signal.takeProfit1,
                conditionDescription = "TP1 level $${fmt(signal.takeProfit1)} reached on ${signal.symbol}"
            ),
            TradeAlertRule(
                ruleId = "inv_${signal.symbol}_$tag",
                signalId = signal.id,
                symbol = signal.symbol,
                direction = dir,
                category = AlertRuleCategory.SETUP_INVALIDATION,
                triggerPrice = signal.stopLoss,
                conditionDescription = "Setup invalidated before/at entry on ${signal.symbol}: ${signal.invalidationCondition}"
            ),
            TradeAlertRule(
                ruleId = "exp_${signal.symbol}_$tag",
                signalId = signal.id,
                symbol = signal.symbol,
                direction = dir,
                category = AlertRuleCategory.SIGNAL_EXPIRY,
                expiryTimestampMs = expiryMs,
                conditionDescription = "Setup window expired at ${TimeZoneUtils.formatKarachiTimestamp(expiryMs)}"
            )
        )
    }

    fun evaluateRules(
        rules: List<TradeAlertRule>,
        livePrices: Map<String, Double>,
        signalsById: Map<String, FuturesScanSignal> = emptyMap(),
        priceChange24hBySymbol: Map<String, Double> = emptyMap(),
        alreadyTriggeredEventIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<TriggeredAlertEvent> {
        val events = mutableListOf<TriggeredAlertEvent>()

        for (rule in rules) {
            if (!rule.isActive || rule.isTriggered) continue
            val price = livePrices[rule.symbol]?.takeIf { it.isFinite() && it > 0.0 }
            val linkedSignal = signalsById[rule.signalId]

            val triggered = when (rule.category) {
                AlertRuleCategory.ENTRY_TRIGGER -> {
                    val timerTriggered = linkedSignal?.entryTimer?.status == EntryTimerStatus.TRIGGERED
                    val priceCrossed = price != null && rule.triggerPrice != null && if (rule.direction == FuturesDirection.LONG) {
                        price <= rule.triggerPrice * 1.0015 && price >= (linkedSignal?.stopLoss ?: 0.0)
                    } else {
                        price >= rule.triggerPrice * 0.9985 && (linkedSignal == null || price <= linkedSignal.stopLoss)
                    }
                    timerTriggered || priceCrossed
                }

                AlertRuleCategory.STOP_LOSS -> {
                    val p = price ?: continue
                    val sl = rule.triggerPrice ?: continue
                    if (rule.direction == FuturesDirection.LONG) p <= sl else p >= sl
                }

                AlertRuleCategory.TAKE_PROFIT -> {
                    val p = price ?: continue
                    val tp = rule.triggerPrice ?: continue
                    if (rule.direction == FuturesDirection.LONG) p >= tp else p <= tp
                }

                AlertRuleCategory.PRICE_TARGET -> {
                    val p = price ?: continue
                    val target = rule.triggerPrice ?: continue
                    val distPct = abs(p - target) / target
                    distPct <= 0.0015
                }

                AlertRuleCategory.SETUP_INVALIDATION -> {
                    linkedSignal?.lifecycle == FuturesSignalLifecycle.INVALIDATED ||
                        linkedSignal?.entryTimer?.status == EntryTimerStatus.INVALIDATED ||
                        (price != null && rule.triggerPrice != null && linkedSignal?.paperTradeExecuted != true &&
                            ((rule.direction == FuturesDirection.LONG && price <= rule.triggerPrice) ||
                                (rule.direction == FuturesDirection.SHORT && price >= rule.triggerPrice)))
                }

                AlertRuleCategory.SIGNAL_EXPIRY -> {
                    val exp = rule.expiryTimestampMs ?: Long.MAX_VALUE
                    linkedSignal?.lifecycle == FuturesSignalLifecycle.EXPIRED ||
                        linkedSignal?.entryTimer?.status == EntryTimerStatus.EXPIRED ||
                        nowMs >= exp
                }

                AlertRuleCategory.MARKET_CONDITION_CHANGE -> {
                    val chg = priceChange24hBySymbol[rule.symbol]
                    chg != null && abs(chg) >= 6.0
                }
            }

            if (triggered) {
                val eventId = "${rule.category.name}_${rule.symbol}_${rule.signalId}_${rule.ruleId}"
                if (eventId !in alreadyTriggeredEventIds) {
                    val priceNote = price?.let { " | Live: $${fmt(it)}" }.orEmpty()
                    val alertType = mapCategoryToAlertType(rule.category)
                    events.add(
                        TriggeredAlertEvent(
                            eventId = eventId,
                            ruleId = rule.ruleId,
                            signalId = rule.signalId,
                            symbol = rule.symbol,
                            category = rule.category,
                            alertType = alertType,
                            title = "${rule.category.label}: ${rule.symbol}",
                            body = "${rule.conditionDescription}$priceNote (Alert Only — Not an Executed Order)",
                            timestampMs = nowMs
                        )
                    )
                }
            }
        }

        // Also evaluate global volatility/condition shifts across monitored symbols
        for ((sym, changePct) in priceChange24hBySymbol) {
            if (abs(changePct) >= 7.5) {
                val bucket = nowMs / (4 * 60 * 60 * 1000L) // 4h deduplication bucket
                val eventId = "MKT_COND_${sym}_$bucket"
                if (eventId !in alreadyTriggeredEventIds) {
                    val moveLabel = if (changePct >= 0) "Volatility Surge (+${String.format(Locale.US, "%.1f", changePct)}%)" else "Sharp Drawdown (${String.format(Locale.US, "%.1f", changePct)}%)"
                    events.add(
                        TriggeredAlertEvent(
                            eventId = eventId,
                            ruleId = "mkt_$sym",
                            signalId = "",
                            symbol = sym,
                            category = AlertRuleCategory.MARKET_CONDITION_CHANGE,
                            alertType = AlertType.MARKET_CONDITION_CHANGE,
                            title = "Market Condition Change: $sym",
                            body = "$sym experienced $moveLabel in 24h. Review stop-loss buffers and funding impact. (Alert Only — Not an Executed Order)",
                            timestampMs = nowMs,
                            openWorkflowOnTap = false
                        )
                    )
                }
            }
        }

        return events
    }

    fun evaluateUserPriceAlerts(
        alerts: List<MarketAlert>,
        livePrices: Map<String, Double>,
        alreadyTriggeredEventIds: Set<String> = emptySet(),
        nowMs: Long = System.currentTimeMillis()
    ): List<TriggeredAlertEvent> {
        val events = mutableListOf<TriggeredAlertEvent>()
        for (alert in alerts) {
            if (!alert.isActive || alert.isTriggered || alert.targetPrice <= 0.0) continue
            val normSym = alert.assetSymbol.uppercase(Locale.US).replace("/", "").let {
                if (it.endsWith("USDT")) it else "${it}USDT"
            }
            val currentPrice = livePrices[normSym] ?: livePrices[alert.assetSymbol] ?: continue
            val crossed = when (alert.direction) {
                AlertDirection.ABOVE -> currentPrice >= alert.targetPrice
                AlertDirection.BELOW -> currentPrice <= alert.targetPrice
            }
            if (crossed) {
                val eventId = "PRICE_ALERT_${alert.id}_$normSym"
                if (eventId !in alreadyTriggeredEventIds) {
                    events.add(
                        TriggeredAlertEvent(
                            eventId = eventId,
                            ruleId = alert.id,
                            signalId = "",
                            symbol = normSym,
                            category = AlertRuleCategory.PRICE_TARGET,
                            alertType = AlertType.PRICE_TARGET,
                            title = "Price Alert: ${alert.assetSymbol}",
                            body = "${alert.assetSymbol} ${alert.direction.label.lowercase(Locale.US)} $${fmt(alert.targetPrice)} (Live: $${fmt(currentPrice)}). Alert Only — Not an Executed Order.",
                            timestampMs = nowMs,
                            openWorkflowOnTap = false
                        )
                    )
                }
            }
        }
        return events
    }

    fun mapCategoryToAlertType(category: AlertRuleCategory): AlertType {
        return when (category) {
            AlertRuleCategory.ENTRY_TRIGGER -> AlertType.ENTRY_TRIGGER
            AlertRuleCategory.STOP_LOSS -> AlertType.STOP_LOSS
            AlertRuleCategory.TAKE_PROFIT -> AlertType.TAKE_PROFIT
            AlertRuleCategory.PRICE_TARGET -> AlertType.PRICE_TARGET
            AlertRuleCategory.SETUP_INVALIDATION -> AlertType.SETUP_INVALIDATION
            AlertRuleCategory.SIGNAL_EXPIRY -> AlertType.SIGNAL_EXPIRY
            AlertRuleCategory.MARKET_CONDITION_CHANGE -> AlertType.MARKET_CONDITION_CHANGE
        }
    }

    private fun fmt(v: Double): String = if (v >= 10.0) {
        String.format(Locale.US, "%.2f", v)
    } else {
        String.format(Locale.US, "%.4f", v)
    }
}
