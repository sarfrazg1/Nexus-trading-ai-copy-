package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.BearishRed
import com.example.ui.theme.BullishGreen
import com.example.ui.theme.NavyBorder
import com.example.ui.theme.NavyCard
import com.example.ui.theme.NexusGold
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SentimentGauge(
    fearAndGreedIndex: Int,
    sentimentLabel: String,
    bullishPercent: Int,
    volatilityState: String,
    hasLiveSentiment: Boolean = sentimentLabel != "Unavailable" && fearAndGreedIndex > 0,
    sourceLabel: String = "Alternative.me F&G",
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = NavyCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, Brush.verticalGradient(listOf(NavyBorder, NavyBorder.copy(alpha = 0.4f))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MARKET SENTIMENT",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = NexusGold,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                )
                Text(
                    text = "Breadth: $volatilityState",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Arc meter
                Box(
                    modifier = Modifier.size(110.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(100.dp)) {
                        val stroke = 12f
                        val arcSize = size.width - stroke
                        val arcOffset = Offset(stroke / 2, stroke / 2)

                        // Background track arc
                        drawArc(
                            color = Color(0xFF1B2A4A),
                            startAngle = 150f,
                            sweepAngle = 240f,
                            useCenter = false,
                            topLeft = arcOffset,
                            size = Size(arcSize, arcSize),
                            style = Stroke(width = stroke, cap = StrokeCap.Round)
                        )

                        if (hasLiveSentiment) {
                            val normalized = (fearAndGreedIndex.coerceIn(0, 100) / 100f) * 240f
                            drawArc(
                                brush = Brush.sweepGradient(
                                    0.0f to BearishRed,
                                    0.5f to NexusGold,
                                    1.0f to BullishGreen
                                ),
                                startAngle = 150f,
                                sweepAngle = normalized,
                                useCenter = false,
                                topLeft = arcOffset,
                                size = Size(arcSize, arcSize),
                                style = Stroke(width = stroke, cap = StrokeCap.Round)
                            )
                        }
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (hasLiveSentiment) "$fearAndGreedIndex" else "--",
                            style = MaterialTheme.typography.headlineMedium.copy(
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp
                            )
                        )
                        Text(
                            text = sentimentLabel.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = when {
                                    !hasLiveSentiment -> TextMuted
                                    fearAndGreedIndex >= 55 -> BullishGreen
                                    fearAndGreedIndex <= 45 -> BearishRed
                                    else -> NexusGold
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp
                            )
                        )
                    }
                }

                // Bull vs Bear Dominance
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 20.dp)
                ) {
                    val safeBullPct = bullishPercent.coerceIn(0, 100)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (hasLiveSentiment || safeBullPct > 0) "Bulls: $safeBullPct%" else "Bulls: --",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = BullishGreen,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Text(
                            text = if (hasLiveSentiment || safeBullPct > 0) "Bears: ${100 - safeBullPct}%" else "Bears: --",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = BearishRed,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { if (hasLiveSentiment || safeBullPct > 0) safeBullPct / 100f else 0.5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                        color = BullishGreen,
                        trackColor = if (hasLiveSentiment || safeBullPct > 0) BearishRed else Color(0xFF1B2A4A)
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Alternative.me F&G",
                            style = MaterialTheme.typography.bodySmall.copy(color = TextMuted, fontSize = 11.sp)
                        )
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary, fontSize = 10.sp)
                        )
                    }
                }
            }
        }
    }
}
