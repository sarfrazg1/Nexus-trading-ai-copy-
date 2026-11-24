package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.broker.ExecutionMode
import com.example.data.broker.OrderSubmissionOutcome
import com.example.data.broker.TradeWorkflowState
import com.example.data.risk.BudgetConfig
import com.example.data.risk.RiskCurrency
import com.example.data.risk.RiskDecision
import com.example.data.scanner.FuturesDirection
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BearishRedBg
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.BullishGreenBg
import com.example.ui.theme.NavyBackground
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceVariant
import com.example.ui.theme.NexusGold
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TradeWorkflowSheet(
    workflowState: TradeWorkflowState,
    onDismiss: () -> Unit,
    onUpdateBudgetConfig: (BudgetConfig) -> Unit,
    onRerunTimeMachine: (feePctPerSide: Double, slippagePctPerSide: Double, seed: Long) -> Unit,
    onSelectExecutionMode: (ExecutionMode) -> Unit,
    onExplicitConfirmationChanged: (Boolean) -> Unit,
    onSubmitPaperOrder: () -> Unit
) {
    val signal = workflowState.selectedSignal ?: return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        BackHandler(onBack = onDismiss)

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .testTag("trade_workflow_sheet"),
            color = NavyBackground
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NavyCard)
                        .border(1.dp, NavyBorder)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "END-TO-END TRADE WORKFLOW",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = NexusGold,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${signal.symbol} • ${signal.direction.name}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            val dirColor = if (signal.direction == FuturesDirection.LONG) BullishGreen else BearishRed
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(dirColor.copy(alpha = 0.16f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "Model Conf: ${signal.qualityScore}%",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = dirColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("close_workflow_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Trade Workflow",
                            tint = TextPrimary
                        )
                    }
                }

                // Scrollable Pipeline Content
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp)
                        .testTag("workflow_lazy_column"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        // Pipeline Breadcrumb Ribbon
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            listOf(
                                "1.Scanner",
                                "2.News",
                                "3.Entry Timer",
                                "4.Exit Plan",
                                "5.Time Machine",
                                "6.Budget",
                                "7.Risk (12)",
                                "8.Confirm",
                                "9.Paper Order"
                            ).forEach { stage ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(NavyCard)
                                        .border(1.dp, NavyBorder, RoundedCornerShape(4.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        text = stage,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = NexusGold,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // SECTION 1-4: SCANNER, NEWS CHECK, ENTRY TIMER, EXIT PLAN, 92% EVIDENCE
                    item {
                        WorkflowCard(title = "1-4. SCANNER • NEWS CHECK • ENTRY TIMER • EXIT PLAN", icon = Icons.Default.Assessment) {
                            Text(
                                text = "Setup Pattern: ${signal.detectedPattern.label} | Lifecycle: ${signal.lifecycle.label}",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold)
                            )
                            Text(
                                text = "Entry: ${signal.entryZoneFormatted} • SL: ${signal.stopLossFormatted} • TP1: ${signal.tp1Formatted} • TP2: ${signal.tp2Formatted}",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                            )

                            signal.entryTimer?.let { timer ->
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Entry Timer: ${timer.status.label} — ${timer.statusExplanation}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = NexusGold, fontSize = 11.sp)
                                )
                            }

                            signal.exitPlan?.let { plan ->
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Pre-Entry Exit Basis: ${plan.invalidationBasis} | Setup Expiry (PKT): ${plan.expiryDeadlinePkt}",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                                )
                                Text(
                                    text = "⚠️ ${plan.stopOrderDisclaimer}",
                                    style = MaterialTheme.typography.labelSmall.copy(color = BearishRed, fontSize = 10.sp)
                                )
                            }

                            // Model Confidence Rubric Breakdown vs Historical Win Rate
                            signal.confidenceEvidence?.let { evidence ->
                                Spacer(modifier = Modifier.height(8.dp))
                                HorizontalDivider(color = NavyBorder)
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Transparent Model Confidence Rubric (${evidence.modelConfidenceScore}/100)",
                                    style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                                )
                                evidence.rubricBreakdown.forEach { item ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "${item.component}",
                                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp),
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${item.awardedPoints}/${item.maxPoints}",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = if (item.awardedPoints > 0) BullishGreen else TextMuted,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp
                                            )
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = evidence.separationNote,
                                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 10.sp, lineHeight = 14.sp)
                                )
                            }

                            // Related News Check
                            Spacer(modifier = Modifier.height(8.dp))
                            HorizontalDivider(color = NavyBorder)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "News Monitor Catalyst Check (${workflowState.relatedNews.size} relevant)",
                                style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                            )
                            if (workflowState.relatedNews.isEmpty()) {
                                Text(
                                    text = "No matching high-impact macro or ${signal.baseAsset} news catalysts in the active feed.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                                )
                            } else {
                                workflowState.relatedNews.take(3).forEach { news ->
                                    Text(
                                        text = "• [${news.impact.label}/${news.impactLevel.label}] ${news.title} (${news.timestamp})",
                                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp, lineHeight = 15.sp)
                                    )
                                }
                            }
                        }
                    }

                    // SECTION 5: TIME MACHINE — HISTORICAL BACKTEST & 10,000 MONTE CARLO
                    item {
                        TimeMachineSectionCard(
                            workflowState = workflowState,
                            onRerun = onRerunTimeMachine
                        )
                    }

                    // SECTION 6: BUDGET MANAGER
                    item {
                        BudgetManagerSectionCard(
                            workflowState = workflowState,
                            onUpdateBudgetConfig = onUpdateBudgetConfig
                        )
                    }

                    // SECTION 7: RISK MANAGER (12 PRE-TRADE CHECKS)
                    item {
                        RiskManagerSectionCard(workflowState = workflowState)
                    }

                    // SECTION 8 & 9: BROKER / ORDER EXECUTOR (SAFE MODE) & LIFECYCLE ALERTS
                    item {
                        BrokerAndAlertsSectionCard(
                            workflowState = workflowState,
                            onSelectExecutionMode = onSelectExecutionMode,
                            onExplicitConfirmationChanged = onExplicitConfirmationChanged,
                            onSubmitPaperOrder = onSubmitPaperOrder
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun TimeMachineSectionCard(
    workflowState: TradeWorkflowState,
    onRerun: (Double, Double, Long) -> Unit
) {
    var feeText by remember(workflowState.backtestConfig) {
        mutableStateOf(String.format(Locale.US, "%.2f", workflowState.backtestConfig.feePercentPerSide))
    }
    var slippageText by remember(workflowState.backtestConfig) {
        mutableStateOf(String.format(Locale.US, "%.2f", workflowState.backtestConfig.slippagePercentPerSide))
    }
    var seedText by remember(workflowState.monteCarloSeed) {
        mutableStateOf(workflowState.monteCarloSeed.toString())
    }

    WorkflowCard(title = "5. TIME MACHINE — BACKTEST & 10,000 MONTE CARLO", icon = Icons.Default.History) {
        if (workflowState.isLoadingHistory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), color = NexusGold, strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Fetching real historical Binance OHLCV candles...", color = TextSecondary, fontSize = 12.sp)
            }
        } else {
            // Parameter Controls
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CompactNumberInput(
                    label = "Fee%/side",
                    value = feeText,
                    onValueChange = { feeText = it },
                    modifier = Modifier.weight(1f)
                )
                CompactNumberInput(
                    label = "Slip%/side",
                    value = slippageText,
                    onValueChange = { slippageText = it },
                    modifier = Modifier.weight(1f)
                )
                CompactNumberInput(
                    label = "MC Seed",
                    value = seedText,
                    onValueChange = { seedText = it },
                    modifier = Modifier.weight(1.2f)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    val f = feeText.toDoubleOrNull()?.coerceIn(0.0, 1.0) ?: 0.05
                    val s = slippageText.toDoubleOrNull()?.coerceIn(0.0, 2.0) ?: 0.03
                    val seed = seedText.toLongOrNull() ?: 20261123L
                    onRerun(f, s, seed)
                },
                modifier = Modifier.fillMaxWidth().testTag("rerun_timemachine_btn"),
                border = androidx.compose.foundation.BorderStroke(1.dp, NexusGold.copy(alpha = 0.6f))
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, tint = NexusGold, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Re-Run Historical Backtest + 10,000 Simulations", color = NexusGold, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }

            workflowState.historyLoadError?.let { err ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(text = err, color = BearishRed, fontSize = 11.sp)
            }

            workflowState.backtestResult?.let { bt ->
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NavySurface)
                        .padding(10.dp)
                        .testTag("time_machine_backtest_card")
                ) {
                    Text(
                        text = "Historical Backtest (${bt.timeframe.label} • ${bt.completedCandlesEvaluated} completed bars)",
                        style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Period (PKT): ${bt.testPeriodStartPkt} -> ${bt.testPeriodEndPkt}",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Sample Size: ${bt.sampleSize} occurrences • Empirical Win Rate: ${String.format(Locale.US, "%.1f%%", bt.empiricalWinRateConservativePercent)} (Conservative)",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    )
                    Text(
                        text = "TP-First: ${bt.tpFirstCount} | SL-First: ${bt.slFirstCount} | Ambiguous Same-Candle: ${bt.ambiguousSameCandleCount} | Unresolved: ${bt.unresolvedCount}",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp)
                    )
                    Text(
                        text = "Avg R:R: ${String.format(Locale.US, "%.2fR", bt.averageRewardRisk)} • Avg Net Return: ${String.format(Locale.US, "%+.2f%%", bt.averageNetReturnPercent)} • Profit Factor: ${String.format(Locale.US, "%.2f", bt.profitFactor)}",
                        style = MaterialTheme.typography.bodySmall.copy(color = NexusGold, fontSize = 10.sp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bt.antiLookAheadNote,
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
                    )
                    bt.missingDataAndLimitations.forEach { lim ->
                        Text(
                            text = "• Limitation: $lim",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
                        )
                    }
                }
            }

            workflowState.monteCarloResult?.let { mc ->
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NavySurface)
                        .padding(10.dp)
                        .testTag("monte_carlo_card")
                ) {
                    Text(
                        text = "Monte Carlo (${mc.iterations} Paths • Seed ${mc.seed} • Horizon ${mc.simulationHorizonBars} bars)",
                        style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                    )
                    if (!mc.isSufficientData) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = mc.insufficientDataReason ?: "Insufficient historical data to run 10,000 simulations honestly.",
                            style = MaterialTheme.typography.bodySmall.copy(color = BearishRed, fontSize = 11.sp)
                        )
                    } else {
                        Text(
                            text = "Period (PKT): ${mc.historicalPeriodPkt} (${mc.historicalSampleSize} setups across ${mc.historicalCandleCount} bars)",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "TP-First: ${String.format(Locale.US, "%.1f%%", mc.tpFirstPercent)} | SL-First: ${String.format(Locale.US, "%.1f%%", mc.slFirstPercent)} | Neither Reached: ${String.format(Locale.US, "%.1f%%", mc.neitherReachedPercent)}",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                        )
                        mc.percentiles?.let { p ->
                            Text(
                                text = "Return Dist: P10 ${fmtPct(p.p10ReturnPercent)} | P25 ${fmtPct(p.p25ReturnPercent)} | Median ${fmtPct(p.p50MedianReturnPercent)} | P75 ${fmtPct(p.p75ReturnPercent)} | P90 ${fmtPct(p.p90ReturnPercent)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp)
                            )
                            Text(
                                text = "Avg Simulated Return: ${fmtPct(p.averageSimulatedReturnPercent)} | Worst Path Excursion: -${String.format(Locale.US, "%.2f%%", p.worstSimulatedDrawdownPercent)}",
                                style = MaterialTheme.typography.bodySmall.copy(color = NexusGold, fontSize = 10.sp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Method: ${mc.samplingMethod}",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp)
                        )
                    }
                    Text(
                        text = mc.disclaimer,
                        style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BudgetManagerSectionCard(
    workflowState: TradeWorkflowState,
    onUpdateBudgetConfig: (BudgetConfig) -> Unit
) {
    val cfg = workflowState.budgetConfig
    var balanceText by remember(cfg.accountBalanceUsdt) { mutableStateOf(cfg.accountBalanceUsdt.toString()) }
    var riskAmountText by remember(cfg.maxRiskPerTradeAmount) { mutableStateOf(cfg.maxRiskPerTradeAmount.toString()) }
    var maxRiskPctText by remember(cfg.maxRiskPercent) { mutableStateOf(cfg.maxRiskPercent.toString()) }
    var pkrRateText by remember(cfg.pkrPerUsdtRate) { mutableStateOf(cfg.pkrPerUsdtRate.toString()) }
    var levLimitText by remember(cfg.leverageLimit) { mutableStateOf(cfg.leverageLimit.toString()) }

    WorkflowCard(title = "6. BUDGET MANAGER & POSITION SIZING", icon = Icons.Default.AccountBalanceWallet) {
        // Currency Toggle: USDT vs PKR
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Max Risk Currency:", color = TextSecondary, fontSize = 11.sp)
            RiskCurrency.entries.forEach { curr ->
                val selected = cfg.riskCurrency == curr
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) NexusGold else NavySurfaceVariant)
                        .clickable { onUpdateBudgetConfig(cfg.copy(riskCurrency = curr)) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .testTag("currency_toggle_${curr.name}")
                ) {
                    Text(
                        text = curr.label,
                        color = if (selected) NavyBackground else TextSecondary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactNumberInput("Balance (USDT)", balanceText, { balanceText = it }, Modifier.weight(1f))
            CompactNumberInput("Max Loss (${cfg.riskCurrency.label})", riskAmountText, { riskAmountText = it }, Modifier.weight(1f))
            CompactNumberInput("Risk %", maxRiskPctText, { maxRiskPctText = it }, Modifier.weight(0.75f))
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactNumberInput("PKR/USDT", pkrRateText, { pkrRateText = it }, Modifier.weight(1f))
            CompactNumberInput("Lev Cap (x)", levLimitText, { levLimitText = it }, Modifier.weight(0.8f))
            Button(
                onClick = {
                    onUpdateBudgetConfig(
                        cfg.copy(
                            accountBalanceUsdt = balanceText.toDoubleOrNull()?.coerceAtLeast(1.0) ?: cfg.accountBalanceUsdt,
                            maxRiskPerTradeAmount = riskAmountText.toDoubleOrNull()?.coerceAtLeast(0.5) ?: cfg.maxRiskPerTradeAmount,
                            maxRiskPercent = maxRiskPctText.toDoubleOrNull()?.coerceIn(0.1, 25.0) ?: cfg.maxRiskPercent,
                            pkrPerUsdtRate = pkrRateText.toDoubleOrNull()?.coerceAtLeast(1.0) ?: cfg.pkrPerUsdtRate,
                            leverageLimit = levLimitText.toIntOrNull()?.coerceIn(1, 20) ?: cfg.leverageLimit
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = NexusGold),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("apply_budget_btn")
            ) {
                Text("Recalc", color = NavyBackground, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }

        workflowState.budgetPlan?.let { plan ->
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NavySurface)
                    .padding(10.dp)
                    .testTag("budget_plan_summary")
            ) {
                if (!plan.isValid && plan.validationError != null) {
                    Text(text = plan.validationError, color = BearishRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "Qty: ${plan.positionQuantityFormatted} ${plan.symbol.removeSuffix("USDT")} (Step ${plan.precisionSpec?.stepSize ?: 0.001}) | Notional: $${fmt2(plan.notionalValueUsdt)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                )
                Text(
                    text = "Est. Margin (${plan.leverageUsed}x): $${fmt2(plan.estimatedMarginUsdt)} | Est. Liq: $${fmt2(plan.estimatedLiquidationPrice)} (${String.format(Locale.US, "%.1f%%", plan.liquidationDistancePercent)} away)",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                )
                Text(
                    text = "SL Amount: $${fmt2(plan.stopLossAmountUsdt)} | Fees: $${fmt2(plan.estimatedFeesUsdt)} | Slippage: $${fmt2(plan.estimatedSlippageUsdt)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                )
                Text(
                    text = "Max Planned Loss: $${fmt2(plan.maximumPlannedLossUsdt)} (~PKR ${String.format(Locale.US, "%.0f", plan.maximumPlannedLossUsdt * plan.pkrPerUsdtRate)}) | R:R 1:${String.format(Locale.US, "%.2f", plan.rewardRiskRatio)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = NexusGold, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = plan.leverageRiskExplanation,
                    style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
                )
            }
        }
    }
}

@Composable
private fun RiskManagerSectionCard(workflowState: TradeWorkflowState) {
    val assessment = workflowState.riskAssessment ?: return
    val statusColor = when (assessment.overallDecision) {
        RiskDecision.PASS -> BullishGreen
        RiskDecision.WARNING -> NexusGold
        RiskDecision.BLOCK -> BearishRed
    }

    WorkflowCard(title = "7. RISK MANAGER (12 PRE-TRADE CHECKS)", icon = Icons.Default.Shield) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(statusColor.copy(alpha = 0.14f))
                .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                .padding(10.dp)
                .testTag("risk_overall_banner"),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "RISK GATE: ${assessment.overallDecision.label}",
                style = MaterialTheme.typography.titleSmall.copy(color = statusColor, fontWeight = FontWeight.Bold)
            )
            Text(
                text = if (assessment.canProceedToOrder) "Eligible for User Approval" else "BLOCKED (${assessment.blockedReasons.size})",
                style = MaterialTheme.typography.labelSmall.copy(color = statusColor, fontWeight = FontWeight.SemiBold)
            )
        }

        if (assessment.blockedReasons.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            assessment.blockedReasons.forEach { reason ->
                Text(
                    text = "⛔ $reason",
                    style = MaterialTheme.typography.bodySmall.copy(color = BearishRed, fontSize = 11.sp)
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        assessment.checks.forEach { check ->
            val color = when (check.decision) {
                RiskDecision.PASS -> BullishGreen
                RiskDecision.WARNING -> NexusGold
                RiskDecision.BLOCK -> BearishRed
            }
            val icon = when (check.decision) {
                RiskDecision.PASS -> Icons.Default.CheckCircle
                RiskDecision.WARNING -> Icons.Default.Warning
                RiskDecision.BLOCK -> Icons.Default.Error
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(icon, contentDescription = check.decision.label, tint = color, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            text = "${check.category.title} [${check.decision.label}]",
                            style = MaterialTheme.typography.labelSmall.copy(color = color, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                        )
                        Text(
                            text = "${check.actualValue} / ${check.limitValue}",
                            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp)
                        )
                    }
                    Text(
                        text = check.reason,
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp, lineHeight = 13.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BrokerAndAlertsSectionCard(
    workflowState: TradeWorkflowState,
    onSelectExecutionMode: (ExecutionMode) -> Unit,
    onExplicitConfirmationChanged: (Boolean) -> Unit,
    onSubmitPaperOrder: () -> Unit
) {
    val ticket = workflowState.proposedTicket
    val riskCanProceed = workflowState.riskAssessment?.canProceedToOrder == true

    WorkflowCard(title = "8-9. ORDER EXECUTOR (SAFE MODE) & TRADE ALERTS", icon = Icons.Default.Security) {
        // Execution Mode Picker
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExecutionMode.entries.forEach { mode ->
                val isSelected = workflowState.executionMode == mode
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) NexusGold else NavySurfaceVariant)
                        .clickable { onSelectExecutionMode(mode) }
                        .padding(horizontal = 8.dp, vertical = 8.dp)
                        .testTag("exec_mode_${mode.name}"),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = if (isSelected) NavyBackground else TextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    )
                }
            }
        }

        if (ticket != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NavySurface)
                    .padding(10.dp)
                    .testTag("proposed_order_ticket")
            ) {
                Text(
                    text = "PROPOSED ORDER TICKET (${ticket.executionMode.label})",
                    style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                )
                Text(
                    text = "${ticket.symbol} ${ticket.direction.name} • Qty: ${ticket.quantityFormatted} @ ${ticket.leverage}x",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                )
                Text(
                    text = "Entry: $${fmt2(ticket.entryPrice)} | SL: $${fmt2(ticket.stopLoss)} | TP1: $${fmt2(ticket.takeProfit1)} | TP2: $${fmt2(ticket.takeProfit2)}",
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                )
                Text(
                    text = "Margin: $${fmt2(ticket.estimatedMarginUsdt)} | Est. Fees: $${fmt2(ticket.estimatedFeesUsdt)} | Max Loss: $${fmt2(ticket.maxPlannedLossUsdt)} (~PKR ${String.format(Locale.US, "%.0f", ticket.maxPlannedLossPkr)})",
                    style = MaterialTheme.typography.bodySmall.copy(color = NexusGold, fontSize = 11.sp)
                )

                if (workflowState.executionMode == ExecutionMode.MANUAL_EXCHANGE_PLAN) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = ticket.exchangeIntegrationNotice,
                        style = MaterialTheme.typography.labelSmall.copy(color = BearishRed, fontSize = 10.sp, lineHeight = 14.sp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    ticket.manualOrderPlanSteps.forEach { step ->
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp, lineHeight = 14.sp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        // Explicit User Confirmation Checkbox
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(NavySurface)
                .clickable { onExplicitConfirmationChanged(!workflowState.userExplicitlyConfirmed) }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = workflowState.userExplicitlyConfirmed,
                onCheckedChange = onExplicitConfirmationChanged,
                colors = CheckboxDefaults.colors(checkedColor = NexusGold, checkmarkColor = NavyBackground),
                modifier = Modifier.testTag("workflow_confirm_checkbox")
            )
            Text(
                text = "I have verified Time Machine stats, Budget sizing & Risk checks and explicitly confirm this Safe-Mode action.",
                style = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontSize = 11.sp)
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onSubmitPaperOrder,
            enabled = workflowState.userExplicitlyConfirmed && riskCanProceed,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (riskCanProceed) BullishGreen else BearishRed,
                disabledContainerColor = NavySurfaceVariant
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("workflow_submit_order_btn")
        ) {
            Text(
                text = when {
                    !riskCanProceed -> "Blocked by Risk Manager — Cannot Submit"
                    workflowState.executionMode == ExecutionMode.MANUAL_EXCHANGE_PLAN -> "Generate Manual Order Plan (No Live Trade)"
                    else -> "Confirm & Submit PAPER Order + Register Lifecycle Alerts"
                },
                color = if (workflowState.userExplicitlyConfirmed && riskCanProceed) NavyBackground else TextMuted,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }

        // Outcome display
        workflowState.submissionOutcome?.let { outcome ->
            Spacer(modifier = Modifier.height(8.dp))
            val (bg, txtColor, msg) = when (outcome) {
                is OrderSubmissionOutcome.PaperOrderSubmitted -> Triple(BullishGreenBg, BullishGreen, outcome.message)
                is OrderSubmissionOutcome.ManualPlanOnly -> Triple(NavySurfaceVariant, NexusGold, outcome.reason)
                is OrderSubmissionOutcome.Rejected -> Triple(BearishRedBg, BearishRed, outcome.reason)
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(bg)
                    .padding(10.dp)
                    .testTag("order_submission_outcome")
            ) {
                Text(text = msg, color = txtColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        // Active Alert Rules & Alert History Log
        Spacer(modifier = Modifier.height(10.dp))
        HorizontalDivider(color = NavyBorder)
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = NexusGold, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Background Monitoring (${workflowState.tradeAlertRules.count { it.isActive }} active rules • FCM + WorkManager)",
                style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
            )
        }
        Text(
            text = "Alerts are strictly informational (Entry, SL, TP, Price, Invalidation, Expiry, Volatility) and are NEVER executed orders.",
            style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 10.sp)
        )
        workflowState.alertHistory.take(4).forEach { entry ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "• [${entry.alertType.label}] ${entry.title} — ${entry.timestampPkt}",
                style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp)
            )
        }
    }
}

@Composable
private fun WorkflowCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, NavyBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = NavyCard)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(imageVector = icon, contentDescription = null, tint = NexusGold, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = NexusGold,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun CompactNumberInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, fontSize = 9.sp, color = TextMuted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
        textStyle = MaterialTheme.typography.bodySmall.copy(color = TextPrimary, fontSize = 12.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NexusGold,
            unfocusedBorderColor = NavyBorder,
            focusedTextColor = TextPrimary,
            unfocusedTextColor = TextPrimary
        )
    )
}

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmtPct(v: Double): String = String.format(Locale.US, "%+.2f%%", v)
