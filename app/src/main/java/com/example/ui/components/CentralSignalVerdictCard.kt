package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.signal.CentralSignalState
import com.example.data.signal.CentralSignalVerdict
import com.example.ui.theme.NexusTheme
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CentralSignalVerdictCard(
    verdict: CentralSignalVerdict,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val accentColor = when (verdict.state) {
        CentralSignalState.APPROVED_LONG -> colors.bullishGreen
        CentralSignalState.APPROVED_SHORT -> colors.bearishRed
        CentralSignalState.WAIT -> colors.warningOrange
        CentralSignalState.REJECTED, CentralSignalState.INVALIDATED -> colors.bearishRed
        CentralSignalState.EXPIRED -> colors.textMuted
    }
    val statusIcon = when (verdict.state) {
        CentralSignalState.APPROVED_LONG, CentralSignalState.APPROVED_SHORT -> Icons.Default.CheckCircle
        CentralSignalState.WAIT -> Icons.Default.HourglassTop
        CentralSignalState.REJECTED, CentralSignalState.INVALIDATED -> Icons.Default.Block
        CentralSignalState.EXPIRED -> Icons.Default.Warning
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("central_verdict_card_${verdict.symbol}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Central Signal Authority Gate",
                        tint = colors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "CENTRAL AUTHORITY • ${verdict.displaySymbol} (${verdict.timeframe.label})",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.6.sp
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(accentColor.copy(alpha = 0.16f))
                        .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .testTag("central_verdict_state_${verdict.symbol}")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = verdict.state.label,
                            tint = accentColor,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = verdict.state.label,
                            color = accentColor,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                MetricBadge(
                    label = "Setup Quality",
                    value = verdict.finalQualityScore?.let { "$it/100 (>=92)" } ?: "N/A",
                    accent = if ((verdict.finalQualityScore ?: 0) >= 92) colors.bullishGreen else colors.textSecondary
                )
                MetricBadge(
                    label = "Net R:R",
                    value = if (verdict.netRiskReward > 0.0) "1:${String.format(Locale.US, "%.2f", verdict.netRiskReward)}" else "N/A",
                    accent = if (verdict.netRiskReward >= 4.0) colors.bullishGreen else colors.warningOrange
                )
                MetricBadge(
                    label = "Data",
                    value = verdict.dataStatus.label,
                    accent = when (verdict.dataStatus.name) {
                        "LIVE" -> colors.bullishGreen
                        "DELAYED" -> colors.warningOrange
                        else -> colors.bearishRed
                    }
                )
                MetricBadge(
                    label = "Filters",
                    value = if (verdict.allMandatoryFiltersPassed) "ALL PASSED" else "${verdict.failedFilters.size.coerceAtLeast(1)} BLOCKED",
                    accent = if (verdict.allMandatoryFiltersPassed) colors.bullishGreen else colors.bearishRed
                )
            }

            Text(
                text = "Note: Quality Score >=92/100 indicates SETUP QUALITY, NOT WIN PROBABILITY.",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colors.gold.copy(alpha = 0.9f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                )
            )

            if (verdict.entryPrice > 0.0 || verdict.stopLoss > 0.0 || verdict.takeProfit1 > 0.0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LevelCell("Entry", fmtVerdictPrice(verdict.entryPrice), colors.textPrimary)
                    LevelCell("SL", fmtVerdictPrice(verdict.stopLoss), colors.bearishRed)
                    LevelCell("TP1", fmtVerdictPrice(verdict.takeProfit1), colors.bullishGreen)
                    LevelCell("TP2", fmtVerdictPrice(verdict.takeProfit2.takeIf { it > 0.0 } ?: verdict.takeProfit1), colors.bullishGreen)
                }
            }

            Text(
                text = "Source: ${verdict.dataSourceName} • Funding: ${verdict.fundingStatus} • Liquidation: ${verdict.liquidationStatus}",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colors.textMuted,
                    fontSize = 10.sp
                )
            )

            Text(
                text = verdict.explanation,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textPrimary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            )

            val passedFilterLabels = verdict.strictQualityReport?.mandatoryFilters
                ?.filter { it.passed }
                ?.map { "#${it.filterNumber} ${it.name}" }
                ?.takeIf { it.isNotEmpty() }
                ?: verdict.stageSummaries.filter { it.passed }.map { "Stage ${it.stageCode}: ${it.stageName}" }

            if (passedFilterLabels.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.bullishGreen.copy(alpha = 0.08f))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Passed Confirmations (${passedFilterLabels.size}):",
                        color = colors.bullishGreen,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = passedFilterLabels.joinToString(" • "),
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }

            if (verdict.failedFilters.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant)
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = "Gate Rejections / Pending Confirmations:",
                        color = colors.bearishRed,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    verdict.failedFilters.take(4).forEach { fail ->
                        Text(
                            text = "• $fail",
                            color = colors.textSecondary,
                            fontSize = 10.sp,
                            lineHeight = 13.sp
                        )
                    }
                }
            }

            Text(
                text = "Evaluated (PKT): ${verdict.evaluatedAtPkt}" +
                    if (verdict.suppressedAsDuplicate) " • Duplicate Alert Suppressed" else "",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = colors.textMuted,
                    fontSize = 9.sp
                )
            )
        }
    }
}

@Composable
private fun LevelCell(label: String, value: String, color: Color) {
    val colors = NexusTheme.colors
    Column {
        Text(text = label, color = colors.textMuted, fontSize = 9.sp)
        Text(text = value, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

private fun fmtVerdictPrice(price: Double): String {
    if (!price.isFinite() || price <= 0.0) return "N/A"
    return when {
        price >= 1000.0 -> String.format(Locale.US, "$%,.2f", price)
        price >= 1.0 -> String.format(Locale.US, "$%.4f", price)
        else -> String.format(Locale.US, "$%.6f", price)
    }
}

@Composable
fun CentralSignalAuthoritySection(
    verdicts: List<CentralSignalVerdict>,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val sorted = verdicts.sortedWith(
        compareByDescending<CentralSignalVerdict> { it.state.isApproved }
            .thenByDescending { it.evaluatedAtMs }
    )
    val approvedCount = sorted.count { it.state.isApproved }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("central_signal_authority_section"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.surface),
        border = BorderStroke(1.dp, colors.gold.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "CENTRAL SIGNAL AUTHORITY (SINGLE GATE)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                    )
                    Text(
                        text = "Score >=92 • All Mandatory Filters • Net R:R >=1:4.0 • Zero Conflicting Flips",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = colors.textSecondary,
                            fontSize = 10.sp
                        )
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (approvedCount > 0) colors.bullishGreen.copy(alpha = 0.16f)
                            else colors.surfaceVariant
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "$approvedCount Approved",
                        color = if (approvedCount > 0) colors.bullishGreen else colors.textSecondary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (sorted.isEmpty()) {
                Text(
                    text = "Awaiting scanner or signal evaluation. Every LONG/SHORT candidate and new-signal alert must pass all 5 Central Signal Authority gates.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textMuted,
                        fontSize = 11.sp
                    )
                )
            } else {
                sorted.take(4).forEach { verdict ->
                    CentralSignalVerdictCard(verdict = verdict)
                }
            }
        }
    }
}

@Composable
private fun MetricBadge(
    label: String,
    value: String,
    accent: Color
) {
    val colors = NexusTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, accent.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = "$label: $value",
            color = accent,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
