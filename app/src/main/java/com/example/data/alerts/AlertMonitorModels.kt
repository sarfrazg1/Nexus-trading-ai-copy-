package com.example.data.alerts

import com.example.data.model.AlertType
import com.example.data.scanner.FuturesDirection
import com.example.util.TimeZoneUtils
import java.util.UUID

enum class AlertRuleCategory(val label: String) {
    ENTRY_TRIGGER("Entry Trigger"),
    STOP_LOSS("Stop Loss"),
    TAKE_PROFIT("Take Profit"),
    PRICE_TARGET("Price Target"),
    SETUP_INVALIDATION("Setup Invalidation"),
    SIGNAL_EXPIRY("Signal Expiry"),
    MARKET_CONDITION_CHANGE("Market Condition Change")
}

data class TradeAlertRule(
    val ruleId: String = "rule_${UUID.randomUUID().toString().take(8)}",
    val signalId: String,
    val symbol: String,
    val direction: FuturesDirection,
    val category: AlertRuleCategory,
    val triggerPrice: Double? = null,
    val expiryTimestampMs: Long? = null,
    val conditionDescription: String,
    val isActive: Boolean = true,
    val isTriggered: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis(),
    val createdAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(createdAtMs)
)

data class TriggeredAlertEvent(
    val eventId: String,
    val ruleId: String,
    val signalId: String,
    val symbol: String,
    val category: AlertRuleCategory,
    val alertType: AlertType,
    val title: String,
    val body: String,
    val timestampMs: Long,
    val timestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(timestampMs),
    val openWorkflowOnTap: Boolean = true
)

data class AlertHistoryEntry(
    val eventId: String,
    val symbol: String,
    val signalId: String? = null,
    val alertType: AlertType,
    val title: String,
    val message: String,
    val timestampMs: Long,
    val timestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(timestampMs),
    val deliverySource: String,
    val isOrderExecution: Boolean = false, // Always false: alerts are NEVER executed orders
    val disclaimer: String = ALERT_VS_ORDER_NOTICE
) {
    companion object {
        const val ALERT_VS_ORDER_NOTICE =
            "Alert Only — Not an Executed Order. Notifications monitor market conditions and never auto-place live trades."
    }
}
