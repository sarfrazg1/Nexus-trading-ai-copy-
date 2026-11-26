package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.model.NewsImpactLevel
import com.example.data.news.EconomicGlossaryDatabase
import com.example.data.news.EconomicTerm
import com.example.ui.components.NewsItemCard
import com.example.ui.theme.NexusTheme

@Composable
fun NewsScreen(
    news: List<MarketNews>,
    newsFilter: String,
    onFilterSelected: (String) -> Unit,
    onNewsClick: (MarketNews) -> Unit,
    onToggleBookmark: (String) -> Unit,
    onRefreshNews: () -> Unit = {},
    onOpenGlossaryTerm: (EconomicTerm) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    // 8 Required Part A Filters + Saved
    val filters = listOf(
        "ALL",
        "CRYPTO",
        "ETF & INSTITUTIONAL",
        "REGULATION & EXCHANGES",
        "FOREX & MACRO",
        "GOLD & SILVER",
        "OIL & ENERGY",
        "BULLISH",
        "BEARISH",
        "HIGH IMPACT",
        "SAVED"
    )

    val filteredNews = news.filter { item ->
        when (newsFilter) {
            "ALL" -> true
            "CRYPTO" -> item.category.equals("Crypto", ignoreCase = true) ||
                    item.category.contains("Crypto", ignoreCase = true) ||
                    item.affectedAssets.any { it in listOf("BTC", "ETH", "SOL", "BNB", "XRP", "DOGE", "ADA", "AVAX", "LINK", "SUI", "TON", "TRX", "LTC", "DOT", "BCH", "CRYPTO") }
            "ETF & INSTITUTIONAL" -> item.category.contains("ETF", ignoreCase = true) ||
                    item.category.contains("Institutional", ignoreCase = true) ||
                    item.title.contains("ETF", ignoreCase = true)
            "REGULATION & EXCHANGES" -> item.category.contains("Regulation", ignoreCase = true) ||
                    item.category.contains("Exchange", ignoreCase = true) ||
                    item.title.contains("SEC", ignoreCase = true) ||
                    item.title.contains("Binance", ignoreCase = true)
            "FOREX & MACRO" -> item.category.contains("Macro", ignoreCase = true) ||
                    item.category.contains("Forex", ignoreCase = true) ||
                    item.affectedAssets.any { it in listOf("DXY", "SPX", "MACRO") }
            "GOLD & SILVER" -> item.category.contains("Gold", ignoreCase = true) ||
                    (item.category.contains("Commodities", ignoreCase = true) && item.title.contains("Gold", ignoreCase = true)) ||
                    item.affectedAssets.any { it in listOf("XAU", "XAG") }
            "OIL & ENERGY" -> item.category.contains("Oil", ignoreCase = true) ||
                    item.category.contains("Energy", ignoreCase = true) ||
                    item.title.contains("Oil", ignoreCase = true) ||
                    item.affectedAssets.any { it in listOf("OIL", "GAS") }
            "BULLISH" -> item.impact == NewsImpact.BULLISH
            "BEARISH" -> item.impact == NewsImpact.BEARISH
            "HIGH IMPACT" -> item.impactLevel == NewsImpactLevel.HIGH
            "SAVED" -> item.isSaved
            else -> true
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "FINANCIAL NEWS & MACRO MONITOR • ASIA/KARACHI (PKT)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.0.sp
                        )
                    )
                    Text(
                        text = "Global Crypto & Macro Wire",
                        style = MaterialTheme.typography.titleLarge.copy(
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold
                        )
                    )
                }

                IconButton(
                    onClick = onRefreshNews,
                    modifier = Modifier.size(48.dp).testTag("refresh_news_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh News",
                        tint = colors.gold
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Quick Roman Urdu Economic Glossary Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.6f))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = colors.gold,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Urdu Glossary:",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = colors.gold,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    )
                }

                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(listOf("CPI", "PPI", "Fed Rate", "NFP", "DXY", "PCE", "ETF Flows")) { key ->
                        val term = EconomicGlossaryDatabase.findTermByKey(key) ?: return@items
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.card)
                                .clickable { onOpenGlossaryTerm(term) }
                                .padding(horizontal = 6.dp, vertical = 3.dp)
                                .testTag("glossary_chip_${key.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$key (i)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.textPrimary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(filters) { filter ->
                    val isSel = newsFilter == filter
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(if (isSel) colors.gold else colors.card)
                            .border(1.dp, if (isSel) colors.gold else colors.border, RoundedCornerShape(20.dp))
                            .clickable { onFilterSelected(filter) }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("news_filter_${filter.lowercase().replace(" ", "_")}"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isSel) colors.onGold else colors.textSecondary,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }

        if (filteredNews.isEmpty()) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (newsFilter == "SAVED") "No saved articles yet. Bookmark an article to read later." else "No news matching '$newsFilter'",
                        color = colors.textMuted,
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            items(filteredNews, key = { it.id }) { item ->
                NewsItemCard(
                    news = item,
                    onCardClick = { onNewsClick(item) },
                    onToggleBookmark = { onToggleBookmark(item.id) },
                    onTermClick = onOpenGlossaryTerm
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
