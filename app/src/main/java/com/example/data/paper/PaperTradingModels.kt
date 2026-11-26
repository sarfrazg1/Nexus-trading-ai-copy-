package com.example.data.paper

import com.example.data.scanner.FuturesDirection
import com.example.util.TimeZoneUtils
import java.util.Locale
import java.util.UUID

enum class PaperOrderType(val label: String) {
    MARKET("Market Order"),
    LIMIT("Limit Order")
}

enum class PaperOrderSide(val label: String) {
    LONG("LONG"),
    SHORT("SHORT");

    fun toFuturesDirection(): FuturesDirection = when (this) {
        LONG -> FuturesDirection.LONG
        SHORT -> FuturesDirection.SHORT
    }

    companion object {
        fun fromFuturesDirection(dir: FuturesDirection): PaperOrderSide = when (dir) {
            FuturesDirection.LONG -> LONG
            FuturesDirection.SHORT -> SHORT
        }
    }
}

enum class PendingLimitStatus(val label: String) {
    PENDING("Pending Limit"),
    FILLED("Filled"),
    CANCELLED("Cancelled")
}

enum class PaperExitReason(val label: String) {
    TP_HIT("Take Profit Hit"),
    SL_HIT("Stop Loss Hit"),
    MANUAL_CLOSE("Manual Close"),
    LIQUIDATED("Simulated Liquidation")
}

data class PaperWalletConfig(
    val initialBalanceUsdt: Double = 10_000.0,
    val takerFeePercentPerSide: Double = 0.05,
    val makerFeePercentPerSide: Double = 0.02,
    val slippagePercentPerSide: Double = 0.03,
    val fundingRatePer8hPercent: Double = 0.01
)

data class PaperPendingOrder(
    val id: String = "plim_${UUID.randomUUID().toString().take(8)}",
    val symbol: String,
    val baseAsset: String,
    val side: PaperOrderSide,
    val limitPrice: Double,
    val quantity: Double,
    val leverage: Int,
    val reservedMarginUsdt: Double,
    val reservedOpeningFeeUsdt: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val status: PendingLimitStatus = PendingLimitStatus.PENDING,
    val createdAtMs: Long = System.currentTimeMillis(),
    val createdAtPkt: String = TimeZoneUtils.formatKarachiWithSeconds(createdAtMs),
    val updatedAtMs: Long = createdAtMs,
    val sourceSignalId: String? = null,
    val rrLabel: String? = null,
    val statusNote: String = "Waiting for verified market price to reach limit level"
) {
    val notionalUsdt: Double get() = quantity * limitPrice
}

data class PaperPosition(
    val id: String = "ppos_${UUID.randomUUID().toString().take(8)}",
    val symbol: String,
    val baseAsset: String,
    val side: PaperOrderSide,
    val orderType: PaperOrderType,
    val quantity: Double,
    val entryPrice: Double,
    val marketEntryReferencePrice: Double,
    val leverage: Int,
    val marginUsedUsdt: Double,
    val notionalUsdt: Double,
    val stopLoss: Double? = null,
    val takeProfit: Double? = null,
    val liquidationPrice: Double,
    val openingFeeUsdt: Double,
    val entrySlippageCostUsdt: Double,
    val accumulatedFundingUsdt: Double = 0.0,
    val lastFundingAccrualMs: Long,
    val currentMarkPrice: Double,
    val grossUnrealizedPnlUsdt: Double = 0.0,
    val netUnrealizedPnlUsdt: Double = 0.0,
    val openedAtMs: Long = System.currentTimeMillis(),
    val openedAtPkt: String = TimeZoneUtils.formatKarachiWithSeconds(openedAtMs),
    val sourceSignalId: String? = null,
    val rrLabel: String? = null
) {
    val netRoiPercent: Double
        get() = if (marginUsedUsdt > 0.0) (netUnrealizedPnlUsdt / marginUsedUsdt) * 100.0 else 0.0
}

data class PaperClosedTrade(
    val id: String = "pcls_${UUID.randomUUID().toString().take(8)}",
    val symbol: String,
    val baseAsset: String,
    val side: PaperOrderSide,
    val orderType: PaperOrderType,
    val quantity: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val leverage: Int,
    val marginUsedUsdt: Double,
    val openingFeeUsdt: Double,
    val closingFeeUsdt: Double,
    val entrySlippageCostUsdt: Double,
    val exitSlippageCostUsdt: Double,
    val fundingCostUsdt: Double,
    val grossPnlUsdt: Double,
    val netPnlUsdt: Double,
    val netRoiPercent: Double,
    val netRMultiple: Double?,
    val exitReason: PaperExitReason,
    val openedAtMs: Long,
    val openedAtPkt: String,
    val closedAtMs: Long,
    val closedAtPkt: String = TimeZoneUtils.formatKarachiWithSeconds(closedAtMs),
    val closedDatePkt: String = TimeZoneUtils.formatKarachiDateOnly(closedAtMs),
    val sourceSignalId: String? = null
) {
    val totalTradingCostsUsdt: Double
        get() = openingFeeUsdt + closingFeeUsdt + entrySlippageCostUsdt + exitSlippageCostUsdt + fundingCostUsdt
}

data class PaperTradingStatistics(
    val totalTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRatePercent: Double = 0.0,
    val grossProfitUsdt: Double = 0.0,
    val grossLossUsdt: Double = 0.0,
    val profitFactor: Double = 0.0,
    val totalGrossPnlUsdt: Double = 0.0,
    val totalNetPnlUsdt: Double = 0.0,
    val totalFeesPaidUsdt: Double = 0.0,
    val totalSlippageUsdt: Double = 0.0,
    val totalFundingUsdt: Double = 0.0,
    val todayNetPnlUsdt: Double = 0.0,
    val todayTradesCount: Int = 0,
    val maxDrawdownUsdt: Double = 0.0,
    val maxDrawdownPercent: Double = 0.0,
    val averageNetWinUsdt: Double = 0.0,
    val averageNetLossUsdt: Double = 0.0,
    val longTradesCount: Int = 0,
    val shortTradesCount: Int = 0,
    val longWinRatePercent: Double = 0.0,
    val shortWinRatePercent: Double = 0.0
)

data class PaperPortfolioSnapshot(
    val config: PaperWalletConfig = PaperWalletConfig(),
    val availableBalanceUsdt: Double = config.initialBalanceUsdt,
    val reservedLimitMarginUsdt: Double = 0.0,
    val usedPositionMarginUsdt: Double = 0.0,
    val unrealizedGrossPnlUsdt: Double = 0.0,
    val unrealizedNetPnlUsdt: Double = 0.0,
    val equityUsdt: Double = config.initialBalanceUsdt,
    val totalNetPnlUsdt: Double = 0.0,
    val totalNetReturnPercent: Double = 0.0,
    val pendingOrders: List<PaperPendingOrder> = emptyList(),
    val openPositions: List<PaperPosition> = emptyList(),
    val closedTrades: List<PaperClosedTrade> = emptyList(),
    val stats: PaperTradingStatistics = PaperTradingStatistics(),
    val lastPriceTickMs: Long = 0L,
    val lastPriceTickPkt: String = "Awaiting live tick",
    val lastActionMessage: String? = null
)

data class PaperTradePrefill(
    val symbol: String,
    val baseAsset: String,
    val side: PaperOrderSide,
    val suggestedOrderType: PaperOrderType = PaperOrderType.MARKET,
    val entryPrice: Double,
    val stopLoss: Double?,
    val takeProfit: Double?,
    val leverage: Int = 3,
    val riskRewardLabel: String? = null,
    val netRiskRewardRatio: Double? = null,
    val setupQualityScore: Int? = null,
    val sourceSignalId: String? = null,
    val sourceLabel: String = "Signal Engine"
)

sealed class PaperOrderActionResult {
    data class Success(
        val snapshot: PaperPortfolioSnapshot,
        val message: String
    ) : PaperOrderActionResult()

    data class Rejected(
        val reason: String
    ) : PaperOrderActionResult()
}

fun formatUsdt(value: Double): String {
    val sign = if (value < 0) "-" else ""
    val absVal = kotlin.math.abs(value)
    return if (absVal >= 1000.0) {
        String.format(Locale.US, "%s$%,.2f", sign, absVal)
    } else {
        String.format(Locale.US, "%s$%.2f", sign, absVal)
    }
}
