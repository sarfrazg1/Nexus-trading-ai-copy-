package com.example.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.EventStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.data.news.EconomicGlossaryDatabase
import com.example.data.news.EconomicTerm
import com.example.ui.theme.NexusTheme

@Composable
fun NewsItemCard(
    news: MarketNews,
    onCardClick: () -> Unit,
    onToggleBookmark: () -> Unit,
    onTermClick: ((EconomicTerm) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val detectedTerms = remember(news.title) {
        EconomicGlossaryDatabase.detectTermsInText(news.title)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick)
            .testTag("news_card_${news.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: Category, Impact pill, Impact Level, Bookmark
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = news.category.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp
                            )
                        )
                    }

                    val impactColor = when (news.impact) {
                        NewsImpact.BULLISH -> colors.bullishGreen
                        NewsImpact.BEARISH -> colors.bearishRed
                        NewsImpact.NEUTRAL -> colors.textMuted
                    }
                    val impactBg = when (news.impact) {
                        NewsImpact.BULLISH -> colors.bullishGreenBg
                        NewsImpact.BEARISH -> colors.bearishRedBg
                        NewsImpact.NEUTRAL -> colors.surfaceVariant
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(impactBg)
                            .border(1.dp, impactColor.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = news.impact.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = impactColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp
                            )
                        )
                    }

                    val levelColor = when (news.impactLevel) {
                        NewsImpactLevel.HIGH -> colors.bearishRed
                        NewsImpactLevel.MEDIUM -> colors.gold
                        NewsImpactLevel.LOW -> colors.textMuted
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = news.impactLevel.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = levelColor,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 9.sp
                            )
                        )
                    }

                    if (news.eventStatus != EventStatus.CONFIRMED) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceVariant)
                                .border(1.dp, colors.gold.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = news.eventStatus.label.uppercase(),
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.gold,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }

                    if (news.isPreviouslySeen) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceVariant)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                                .testTag("news_seen_badge_${news.id}")
                        ) {
                            Text(
                                text = "SEEN",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.textMuted,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onToggleBookmark,
                    modifier = Modifier.size(48.dp).testTag("bookmark_btn_${news.id}")
                ) {
                    Icon(
                        imageVector = if (news.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                        contentDescription = "Save News",
                        tint = if (news.isSaved) colors.gold else colors.textMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = news.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    color = colors.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
            )

            if (detectedTerms.isNotEmpty() && onTermClick != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Urdu Wazaahat:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.textMuted,
                            fontSize = 10.sp
                        )
                    )
                    detectedTerms.take(2).forEach { term ->
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.gold.copy(alpha = 0.12f))
                                .clickable { onTermClick(term) }
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = "Explain ${term.abbreviation}",
                                tint = colors.gold,
                                modifier = Modifier.size(11.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = "${term.abbreviation} (i)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.gold,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = news.summary,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = colors.textSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                ),
                maxLines = 2
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    news.affectedAssets.take(4).forEach { asset ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(colors.surfaceVariant.copy(alpha = 0.8f))
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = asset,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.gold,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${news.source} • Pub: ${news.publishedTimestampPkt.ifBlank { news.timestamp }}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.textMuted,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    if (news.retrievedTimestampPkt.isNotBlank()) {
                        Text(
                            text = "Retrieved: ${news.retrievedTimestampPkt}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.textMuted,
                                fontSize = 8.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
