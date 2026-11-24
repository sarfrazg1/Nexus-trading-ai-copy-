package com.example.data.broker

import com.example.data.scanner.FuturesDirection
import com.example.util.TimeZoneUtils
import java.util.UUID

enum class ExecutionMode(val label: String) {
    PAPER_TRADING("PAPER TRADING (Safe Default)"),
    MANUAL_EXCHANGE_PLAN("MANUAL EXCHANGE PLAN (Live Disabled)")
}

enum class PaperOrderStatus(val label: String) {
    OPEN_PAPER("Open Paper Position"),
    TP1_HIT_PAPER("TP1 Hit (Paper)"),
    CLOSED_TP_PAPER("Closed at Target (Paper)"),
    CLOSED_SL_PAPER("Closed at Stop Loss (Paper)"),
    CANCELLED_PAPER("Cancelled (Paper)")
}

data class ProposedOrderTicket(
    val ticketId: String = "ticket_${UUID.randomUUID().toString().take(8)}",
    val signalId: String,
    val symbol: String,
    val direction: FuturesDirection,
    val quantity: Double,
    val quantityFormatted: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val takeProfit3: Double,
    val leverage: Int,
    val notionalValueUsdt: Double,
    val estimatedMarginUsdt: Double,
    val estimatedFeesUsdt: Double,
    val estimatedSlippageUsdt: Double,
    val maxPlannedLossUsdt: Double,
    val maxPlannedLossPkr: Double,
    val rewardRiskRatio: Double,
    val executionMode: ExecutionMode = ExecutionMode.PAPER_TRADING,
    val manualOrderPlanSteps: List<String>,
    val exchangeIntegrationNotice: String =
        "Live automated exchange execution is NOT configured. NEXUS never embeds Binance API secrets in Android code and never requests withdrawal permissions. Live execution requires separate authenticated backend/Cloud Function authorization.",
    val disclaimer: String =
        "Safe Mode: Default execution is Paper Trading only. Explicit user confirmation is mandatory before recording any order."
)

data class PaperOrderRecord(
    val orderId: String = "paper_${UUID.randomUUID().toString().take(10)}",
    val signalId: String,
    val symbol: String,
    val direction: FuturesDirection,
    val quantity: Double,
    val quantityFormatted: String,
    val entryPrice: Double,
    val stopLoss: Double,
    val takeProfit1: Double,
    val takeProfit2: Double,
    val takeProfit3: Double,
    val leverage: Int,
    val estimatedMarginUsdt: Double,
    val estimatedFeesUsdt: Double,
    val maxPlannedLossUsdt: Double,
    val status: PaperOrderStatus = PaperOrderStatus.OPEN_PAPER,
    val userConfirmedExplicitly: Boolean,
    val executionMode: ExecutionMode = ExecutionMode.PAPER_TRADING,
    val createdTimestampMs: Long = System.currentTimeMillis(),
    val createdTimestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(createdTimestampMs),
    val closedPrice: Double? = null,
    val realizedPnlUsdt: Double? = null
)

sealed class OrderSubmissionOutcome {
    data class PaperOrderSubmitted(
        val order: PaperOrderRecord,
        val message: String
    ) : OrderSubmissionOutcome()

    data class ManualPlanOnly(
        val ticket: ProposedOrderTicket,
        val reason: String
    ) : OrderSubmissionOutcome()

    data class Rejected(
        val reason: String
    ) : OrderSubmissionOutcome()
}
