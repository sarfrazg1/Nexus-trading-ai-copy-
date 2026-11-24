package com.example.data.broker

import com.example.data.alerts.AlertHistoryEntry
import com.example.data.alerts.TradeAlertRule
import com.example.data.model.Candle
import com.example.data.model.MarketNews
import com.example.data.risk.BudgetCalculationResult
import com.example.data.risk.BudgetConfig
import com.example.data.risk.DailyRiskLedger
import com.example.data.risk.RiskAssessmentResult
import com.example.data.scanner.FuturesScanSignal
import com.example.data.timemachine.BacktestConfig
import com.example.data.timemachine.HistoricalBacktestResult
import com.example.data.timemachine.MonteCarloResult

/**
 * Connected end-to-end Trade Workflow state:
 * SCANNER -> NEWS CHECK -> ENTRY TIMER -> EXIT PLAN -> TIME MACHINE -> BUDGET MANAGER -> RISK MANAGER -> USER APPROVAL -> PAPER ORDER
 */
data class TradeWorkflowState(
    val isVisible: Boolean = false,
    val selectedSignal: FuturesScanSignal? = null,
    val relatedNews: List<MarketNews> = emptyList(),
    val historicalCandles: List<Candle> = emptyList(),
    val isLoadingHistory: Boolean = false,
    val historyLoadError: String? = null,
    val backtestConfig: BacktestConfig = BacktestConfig(),
    val backtestResult: HistoricalBacktestResult? = null,
    val monteCarloSeed: Long = 20261123L,
    val monteCarloResult: MonteCarloResult? = null,
    val budgetConfig: BudgetConfig = BudgetConfig(),
    val budgetPlan: BudgetCalculationResult? = null,
    val riskAssessment: RiskAssessmentResult? = null,
    val proposedTicket: ProposedOrderTicket? = null,
    val executionMode: ExecutionMode = ExecutionMode.PAPER_TRADING,
    val userExplicitlyConfirmed: Boolean = false,
    val submissionOutcome: OrderSubmissionOutcome? = null,
    val registeredAlertRulesCount: Int = 0,
    val dailyRiskLedger: DailyRiskLedger = DailyRiskLedger(),
    val paperOrders: List<PaperOrderRecord> = emptyList(),
    val tradeAlertRules: List<TradeAlertRule> = emptyList(),
    val alertHistory: List<AlertHistoryEntry> = emptyList(),
    val fcmTokenAvailable: Boolean = false
)
