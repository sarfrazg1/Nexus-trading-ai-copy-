package com.example.data.risk

import com.example.data.scanner.FuturesDirection
import com.example.util.TimeZoneUtils
import java.util.Locale

enum class RiskCurrency(val label: String) {
    USDT("USDT"),
    PKR("PKR")
}

data class FuturesSymbolPrecision(
    val symbol: String,
    val stepSize: Double,
    val tickSize: Double,
    val minQty: Double,
    val maxQty: Double,
    val qtyDecimals: Int,
    val priceDecimals: Int
)

object FuturesSymbolPrecisionRegistry {
    private val knownSpecs = mapOf(
        "BTCUSDT" to FuturesSymbolPrecision("BTCUSDT", 0.001, 0.1, 0.001, 1000.0, 3, 1),
        "ETHUSDT" to FuturesSymbolPrecision("ETHUSDT", 0.001, 0.01, 0.001, 10000.0, 3, 2),
        "SOLUSDT" to FuturesSymbolPrecision("SOLUSDT", 0.01, 0.01, 0.01, 50000.0, 2, 2),
        "BNBUSDT" to FuturesSymbolPrecision("BNBUSDT", 0.01, 0.01, 0.01, 10000.0, 2, 2),
        "XRPUSDT" to FuturesSymbolPrecision("XRPUSDT", 0.1, 0.0001, 0.1, 1000000.0, 1, 4),
        "DOGEUSDT" to FuturesSymbolPrecision("DOGEUSDT", 1.0, 0.00001, 1.0, 10000000.0, 0, 5),
        "ADAUSDT" to FuturesSymbolPrecision("ADAUSDT", 1.0, 0.0001, 1.0, 5000000.0, 0, 4),
        "AVAXUSDT" to FuturesSymbolPrecision("AVAXUSDT", 0.1, 0.001, 0.1, 100000.0, 1, 3),
        "LINKUSDT" to FuturesSymbolPrecision("LINKUSDT", 0.01, 0.001, 0.01, 100000.0, 2, 3),
        "SUIUSDT" to FuturesSymbolPrecision("SUIUSDT", 0.1, 0.0001, 0.1, 1000000.0, 1, 4),
        "NEARUSDT" to FuturesSymbolPrecision("NEARUSDT", 0.1, 0.001, 0.1, 1000000.0, 1, 3),
        "APTUSDT" to FuturesSymbolPrecision("APTUSDT", 0.1, 0.001, 0.1, 500000.0, 1, 3)
    )

    fun forSymbol(symbol: String, referencePrice: Double = 100.0): FuturesSymbolPrecision {
        val norm = symbol.uppercase(Locale.US).trim()
        knownSpecs[norm]?.let { return it }
        return when {
            referencePrice >= 10000.0 -> FuturesSymbolPrecision(norm, 0.001, 0.1, 0.001, 1000.0, 3, 1)
            referencePrice >= 100.0 -> FuturesSymbolPrecision(norm, 0.01, 0.01, 0.01, 50000.0, 2, 2)
            referencePrice >= 1.0 -> FuturesSymbolPrecision(norm, 0.1, 0.001, 0.1, 500000.0, 1, 3)
            else -> FuturesSymbolPrecision(norm, 1.0, 0.0001, 1.0, 5000000.0, 0, 4)
        }
    }
}

data class BudgetConfig(
    val accountBalanceUsdt: Double = 1000.0,
    val maxRiskPerTradeAmount: Double = 20.0,
    val riskCurrency: RiskCurrency = RiskCurrency.USDT,
    val pkrPerUsdtRate: Double = 278.5,
    val maxRiskPercent: Double = 2.0,
    val leverageLimit: Int = 5,
    val feeRatePercentPerSide: Double = 0.05,
    val slippagePercentPerSide: Double = 0.03,
    val dailyLossLimitPercent: Double = 5.0,
    val maxTradesPerDay: Int = 5,
    val minRiskRewardRatio: Double = 1.5,
    val maxOpenExposurePercentOfBalance: Double = 80.0
)

data class BudgetCalculationResult(
    val isValid: Boolean,
    val validationError: String? = null,
    val symbol: String = "",
    val direction: FuturesDirection = FuturesDirection.LONG,
    val entryPrice: Double = 0.0,
    val stopLossPrice: Double = 0.0,
    val takeProfit1Price: Double = 0.0,
    val takeProfit2Price: Double = 0.0,
    val configuredMaxLossUsdt: Double = 0.0,
    val configuredMaxLossPkr: Double = 0.0,
    val effectivePermittedLossUsdt: Double = 0.0,
    val positionQuantity: Double = 0.0,
    val positionQuantityFormatted: String = "0",
    val precisionSpec: FuturesSymbolPrecision? = null,
    val notionalValueUsdt: Double = 0.0,
    val estimatedMarginUsdt: Double = 0.0,
    val leverageUsed: Int = 1,
    val stopLossDistanceUsdt: Double = 0.0,
    val stopLossAmountUsdt: Double = 0.0,
    val maximumPlannedLossUsdt: Double = 0.0,
    val estimatedFeesUsdt: Double = 0.0,
    val estimatedSlippageUsdt: Double = 0.0,
    val projectedTp1NetProfitUsdt: Double = 0.0,
    val projectedTp2NetProfitUsdt: Double = 0.0,
    val rewardRiskRatio: Double = 0.0,
    val estimatedLiquidationPrice: Double = 0.0,
    val liquidationDistancePercent: Double = 0.0,
    val pkrPerUsdtRate: Double = 278.5,
    val leverageRiskExplanation: String = LEVERAGE_RISK_EXPLANATION
) {
    companion object {
        const val LEVERAGE_RISK_EXPLANATION =
            "Critical Leverage Notice: Higher leverage NEVER reduces your underlying dollar trading loss on a position. Using leverage shrinks your posted margin while magnifying liquidation risk and margin call sensitivity during sudden wicks or slippage."
    }
}

enum class RiskDecision(val label: String) {
    PASS("PASS"),
    WARNING("WARNING"),
    BLOCK("BLOCK")
}

enum class RiskCheckCategory(val title: String) {
    MAX_PERMITTED_LOSS("Max Permitted Loss"),
    DAILY_LOSS_LIMIT("Daily Loss Limit"),
    MAX_TRADES_LIMIT("Max Daily Trades"),
    POSITION_SIZE("Position Size & Min Qty"),
    LEVERAGE_CAP("Leverage Limit"),
    MARGIN_REQUIREMENTS("Margin Requirement"),
    RISK_REWARD_RATIO("Risk / Reward Ratio"),
    AVAILABLE_BALANCE("Available Balance"),
    ENTRY_VALIDITY("Entry Validity"),
    STOP_LOSS_DISTANCE("Stop-Loss Distance"),
    LIQUIDATION_RISK("Liquidation Risk"),
    EXISTING_EXPOSURE("Existing Exposure")
}

data class RiskCheckItem(
    val category: RiskCheckCategory,
    val decision: RiskDecision,
    val reason: String,
    val actualValue: String,
    val limitValue: String
)

data class DailyRiskLedger(
    val dateKeyPkt: String = TimeZoneUtils.formatKarachiShort(System.currentTimeMillis()).substringBefore(","),
    val tradesTakenToday: Int = 0,
    val realizedPnlUsdtToday: Double = 0.0,
    val existingOpenMarginUsdt: Double = 0.0,
    val openOrderSymbols: List<String> = emptyList()
)

data class RiskAssessmentResult(
    val overallDecision: RiskDecision,
    val checks: List<RiskCheckItem>,
    val blockedReasons: List<String>,
    val warningReasons: List<String>,
    val canProceedToOrder: Boolean,
    val evaluatedAtPkt: String = TimeZoneUtils.formatKarachiTimestamp(System.currentTimeMillis())
)
