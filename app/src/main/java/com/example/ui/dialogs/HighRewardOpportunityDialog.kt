package com.example.ui.dialogs

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.learning.LearningLanguageMode
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.OpportunityPriorityTier
import com.example.data.scanner.OpportunityStatus
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.NavyBackground
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NavySurface
import com.example.ui.theme.NavySurfaceVariant
import com.example.ui.theme.NexusGold
import com.example.ui.theme.NotoNastaliqUrduFontFamily
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.util.Locale

@Composable
fun HighRewardOpportunityDialog(
    opportunity: HighRewardOpportunity,
    appLanguageMode: LearningLanguageMode = LearningLanguageMode.ROMAN_URDU,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showUrduScript by remember(appLanguageMode) {
        mutableStateOf(appLanguageMode == LearningLanguageMode.STANDARD_URDU)
    }

    val isTopPriority = opportunity.tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS
    val accentColor = if (isTopPriority) BullishGreen else NexusGold
    val dirColor = if (opportunity.direction == FuturesDirection.LONG) BullishGreen else BearishRed
    val statusColor = when (opportunity.status) {
        OpportunityStatus.ACTIVE -> BullishGreen
        OpportunityStatus.INVALIDATED -> BearishRed
        OpportunityStatus.EXPIRED -> TextMuted
    }

    val activeExplanation = if (showUrduScript) {
        opportunity.urduScriptExplanation
    } else {
        opportunity.romanUrduExplanation
    }
    val explanationFont = if (showUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
    val explanationDirection = if (showUrduScript) LayoutDirection.Rtl else LayoutDirection.Ltr

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 20.dp)
                .clip(RoundedCornerShape(18.dp))
                .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .testTag("high_reward_detail_dialog"),
            colors = CardDefaults.cardColors(containerColor = NavyCard)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(accentColor.copy(alpha = 0.18f))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = if (isTopPriority) "NEXUS 1:5 HIGH REWARD OPPORTUNITY" else "NEXUS QUALIFIED 1:4 OPPORTUNITY",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = accentColor,
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(statusColor.copy(alpha = 0.16f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = opportunity.status.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = statusColor,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = opportunity.symbol,
                                style = MaterialTheme.typography.titleLarge.copy(
                                    color = TextPrimary,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(dirColor.copy(alpha = 0.16f))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = opportunity.direction.name,
                                    style = MaterialTheme.typography.labelMedium.copy(
                                        color = dirColor,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = "Trigger (PKT): ${opportunity.entryTriggerTimestampPkt}",
                            style = MaterialTheme.typography.bodySmall.copy(color = NexusGold, fontSize = 11.sp)
                        )
                        Text(
                            text = "Expires (PKT): ${opportunity.expiresAtPkt}",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.sp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("close_high_reward_dialog_btn")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close Opportunity Detail", tint = TextSecondary)
                    }
                }

                // Trade Levels Grid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavySurface)
                        .border(1.dp, NavyBorder, RoundedCornerShape(10.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    LevelMetric("ENTRY", opportunity.entryFormatted, TextPrimary)
                    LevelMetric("STOP LOSS", opportunity.stopLossFormatted, BearishRed)
                    LevelMetric("TAKE PROFIT", opportunity.takeProfitFormatted, BullishGreen)
                }

                // Net R:R & $1 vs $5 Financial Breakdown Card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.horizontalGradient(
                                listOf(NavySurface, accentColor.copy(alpha = 0.1f), NavySurface)
                            )
                        )
                        .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "NET R:R: 1:${fmt2(opportunity.netRiskRewardRatio)}",
                            style = MaterialTheme.typography.titleSmall.copy(
                                color = accentColor,
                                fontWeight = FontWeight.ExtraBold
                            )
                        )
                        Text(
                            text = "Gross: 1:${fmt2(opportunity.grossRiskRewardRatio)}",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                        )
                    }
                    Text(
                        text = "$1.00 Risk -> $${fmt2(opportunity.normalizedNetRewardUsd)} Net Reward (after ${fmt2(opportunity.feeRatePercentPerSide * 2)}% fees + ${fmt2(opportunity.slippagePercentPerSide * 2)}% slippage + funding ${String.format(Locale.US, "%+.4f%%", opportunity.fundingRatePercent)})",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    )
                    Text(
                        text = "Est. Net Profit: +$${fmt2(opportunity.estimatedNetProfitUsdt)} (+${fmt1(opportunity.estimatedNetRoiPercent)}% ROI) | Est. Net Loss: -$${fmt2(opportunity.estimatedNetLossUsdt)} (-${fmt1(opportunity.estimatedRiskMarginPercent)}% margin)",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp)
                    )
                    Text(
                        text = "Assumptions: $${fmt2(opportunity.budgetMarginUsdt)} isolated margin @ ${opportunity.leverageAssumed}x leverage (Notional $${fmt2(opportunity.notionalUsdt)}) | Fees $${fmt2(opportunity.estimatedFeesUsdt)} | Slippage $${fmt2(opportunity.estimatedSlippageUsdt)} | Funding Reserve $${fmt2(opportunity.estimatedFundingReserveUsdt)}",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.sp)
                    )
                }

                // Separate Model Confidence vs Empirical Historical Probability
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavySurfaceVariant)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Model Confidence: ${opportunity.modelConfidencePercent}%",
                            style = MaterialTheme.typography.labelMedium.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                        )
                        val histLabel = opportunity.historicalTargetHitProbabilityPercent?.let {
                            "Historical Hit Rate: ${fmt1(it)}% (n=${opportunity.historicalSampleSize})"
                        } ?: "Historical Hit Rate: N/A (n=${opportunity.historicalSampleSize})"
                        Text(
                            text = histLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (opportunity.historicalTargetHitProbabilityPercent != null) BullishGreen else TextMuted,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                    Text(
                        text = opportunity.historicalProbabilityNote,
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 10.sp)
                    )
                }

                // Roman Urdu / Urdu Script Clear Explanation Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NavySurface)
                        .border(1.dp, NexusGold.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Translate, contentDescription = null, tint = NexusGold, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TRADE EXPLANATION (URDU / ROMAN URDU)",
                                style = MaterialTheme.typography.labelSmall.copy(color = NexusGold, fontWeight = FontWeight.Bold)
                            )
                        }
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(NavyCard)
                                .border(1.dp, NavyBorder, RoundedCornerShape(8.dp))
                                .padding(2.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (!showUrduScript) NexusGold else NavyCard)
                                    .clickable { showUrduScript = false }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("toggle_roman_urdu_btn")
                            ) {
                                Text(
                                    text = "Roman Urdu",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (!showUrduScript) NavyBackground else TextSecondary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (showUrduScript) NexusGold else NavyCard)
                                    .clickable { showUrduScript = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("toggle_urdu_script_btn")
                            ) {
                                Text(
                                    text = "اردو",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (showUrduScript) NavyBackground else TextSecondary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }

                    CompositionLocalProvider(LocalLayoutDirection provides explanationDirection) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ExplanationBlock(
                                title = if (showUrduScript) "1. نیکسس نے یہ ٹریڈ کیوں منتخب کی؟" else "1. NEXUS ne ye trade kyun select ki?",
                                body = activeExplanation.whySelected,
                                fontFamily = explanationFont
                            )
                            ExplanationBlock(
                                title = if (showUrduScript) "2. $1 رِسک کہاں سے آ رہا ہے؟" else "2. $1 Risk kahan se aa raha hai?",
                                body = activeExplanation.whereOneDollarRiskComesFrom,
                                fontFamily = explanationFont
                            )
                            ExplanationBlock(
                                title = if (showUrduScript) "3. ممکنہ $5+ ریوارڈ کا حساب کیسے ہوا؟" else "3. Potential $5+ Reward ka hisab kaise hua?",
                                body = activeExplanation.howFiveDollarRewardCalculated,
                                fontFamily = explanationFont
                            )
                            ExplanationBlock(
                                title = if (showUrduScript) "4. سپورٹنگ انڈیکیٹرز، اسٹرکچر اور لیکویڈیٹی" else "4. Supporting Indicators, SMC & Liquidity",
                                body = activeExplanation.supportingSignals,
                                fontFamily = explanationFont
                            )
                            ExplanationBlock(
                                title = if (showUrduScript) "5. ٹریڈ کینسل / غیر مؤثر ہونے کی شرائط" else "5. Cancel / Invalidation Conditions",
                                body = activeExplanation.cancellationConditions,
                                fontFamily = explanationFont
                            )
                        }
                    }
                }

                // Risks & Qualification checklist
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NavySurface)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = accentColor, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("QUALIFICATION & INVALIDATION DETAILS", style = MaterialTheme.typography.labelSmall.copy(color = accentColor, fontWeight = FontWeight.Bold))
                    }
                    opportunity.qualificationReasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 11.sp))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = BearishRed, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("MAIN RISKS & CANCELLATION", style = MaterialTheme.typography.labelSmall.copy(color = BearishRed, fontWeight = FontWeight.Bold))
                    }
                    opportunity.risksAndInvalidations.forEach { risk ->
                        Text("• $risk", style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp))
                    }
                }

                // Footer Buttons & Safety Notice
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val text = "${opportunity.alertBannerTitle}\nTrigger (PKT): ${opportunity.entryTriggerTimestampPkt}\nEntry: ${opportunity.entryFormatted}\nSL: ${opportunity.stopLossFormatted}\nTP: ${opportunity.takeProfitFormatted}\nNet R:R: 1:${fmt2(opportunity.netRiskRewardRatio)}\nAlert Only — No Auto-Execution"
                            clipboard.setPrimaryClip(ClipData.newPlainText("NEXUS 1:5 Opportunity", text))
                        },
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp), tint = NexusGold)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Alert", color = NexusGold, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).minimumInteractiveComponentSize()
                    ) {
                        Text("Close", color = TextPrimary, fontSize = 12.sp)
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NavySurfaceVariant)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = NexusGold, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Alert & Analysis Only: Never executes live orders automatically and never guarantees profits. Background WorkManager scans run at Android's 15-min minimum interval when closed; continuous sub-minute monitoring requires Foreground Scanner Service or external FCM push server.",
                        style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 9.sp, lineHeight = 13.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelMetric(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelSmall.copy(color = TextMuted, fontSize = 9.sp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall.copy(color = valueColor, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun ExplanationBlock(title: String, body: String, fontFamily: FontFamily) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NavyCard)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall.copy(
                color = NexusGold,
                fontWeight = FontWeight.Bold,
                fontFamily = fontFamily,
                lineHeight = 18.sp
            )
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall.copy(
                color = TextPrimary,
                fontSize = 12.sp,
                fontFamily = fontFamily,
                lineHeight = 20.sp
            )
        )
    }
}

private fun fmt2(v: Double): String = String.format(Locale.US, "%.2f", v)
private fun fmt1(v: Double): String = String.format(Locale.US, "%.1f", v)
