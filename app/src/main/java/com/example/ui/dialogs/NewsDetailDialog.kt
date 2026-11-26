package com.example.ui.dialogs

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.data.model.ArticleContentStatus
import com.example.data.model.EventStatus
import com.example.data.model.MarketNews
import com.example.data.model.NewsImpact
import com.example.data.news.EconomicGlossaryDatabase
import com.example.data.news.EconomicTerm
import com.example.data.news.NewsLanguageOption
import com.example.data.news.TranslatedNewsArticle
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.NotoNastaliqUrduFontFamily

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewsDetailDialog(
    news: MarketNews,
    onDismiss: () -> Unit,
    onToggleBookmark: () -> Unit,
    onOpenGlossaryTerm: ((EconomicTerm) -> Unit)? = null,
    isFetchingFullArticle: Boolean = false,
    selectedLanguage: NewsLanguageOption = NewsLanguageOption.ENGLISH,
    isTranslating: Boolean = false,
    translatedArticle: TranslatedNewsArticle? = null,
    onSelectLanguage: (NewsLanguageOption) -> Unit = {},
    onRetryTranslation: () -> Unit = {}
) {
    val colors = NexusTheme.colors
    val context = LocalContext.current
    val detectedTerms = remember(news.title) {
        EconomicGlossaryDatabase.detectTermsInText(news.title)
    }

    val activeTrans = translatedArticle?.takeIf {
        it.newsId == news.id && it.language == selectedLanguage && selectedLanguage != NewsLanguageOption.ENGLISH
    }
    val isUrduScript = selectedLanguage == NewsLanguageOption.URDU_SCRIPT
    val contentFont = if (isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
    val contentDirection = if (isUrduScript) LayoutDirection.Rtl else LayoutDirection.Ltr

    val pubPkt = news.publishedTimestampPkt.ifBlank { news.timestamp }
    val retrievedPkt = news.retrievedTimestampPkt

    val displayTitle = activeTrans?.title?.ifBlank { news.title } ?: news.title
    val displayBody = activeTrans?.body?.ifBlank { news.fullContent } ?: news.fullContent
    val displayFacts = activeTrans?.confirmedFacts?.takeIf { it.isNotEmpty() }
        ?: news.confirmedFacts.ifEmpty {
            listOfNotNull(
                "Reported Headline: ${news.title}",
                "Source: ${news.source}",
                "Publication Time (PKT): $pubPkt",
                retrievedPkt.takeIf { it.isNotBlank() }?.let { "App Retrieval Time (PKT): $it" },
                "Status: ${news.eventStatus.label}"
            )
        }
    val displayInterpretation = activeTrans?.aiInterpretation?.ifBlank { news.aiInterpretation }
        ?: news.aiInterpretation.ifBlank {
            "Probabilistic NLP tag: ${news.impact.label} (${news.impactLevel.label}). Do not treat headlines as proven price causality without candle confirmation."
        }
    val displayBtcImpact = activeTrans?.btcImpact?.ifBlank { news.btcImpactSummary } ?: news.btcImpactSummary
    val displayAltcoinImpact = activeTrans?.altcoinImpact?.ifBlank { news.altcoinImpactSummary } ?: news.altcoinImpactSummary
    val displayShortTerm = activeTrans?.shortTermScenario?.ifBlank { news.shortTermScenario } ?: news.shortTermScenario
    val displayLongTerm = activeTrans?.longTermScenario?.ifBlank { news.longTermScenario } ?: news.longTermScenario
    val displayUncertainty = activeTrans?.uncertaintyAndRisks?.ifBlank { news.uncertaintyAndRisks }
        ?: news.uncertaintyAndRisks.ifBlank {
            "News reactions are probabilistic and depend on futures positioning, liquidity, and confirmed candle closes."
        }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("news_detail_dialog"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = colors.card),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Header with category, impact, and close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.surfaceVariant)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
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

                        Text(
                            text = "• ${news.impact.label}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = impactColor,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp
                            )
                        )

                        Text(
                            text = "• ${news.impactLevel.label}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.textMuted,
                                fontSize = 10.sp
                            )
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = onToggleBookmark,
                            modifier = Modifier.testTag("news_detail_bookmark_btn")
                        ) {
                            Icon(
                                imageVector = if (news.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                contentDescription = "Bookmark article",
                                tint = if (news.isSaved) colors.gold else colors.textMuted
                            )
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.testTag("news_detail_close_btn")
                        ) {
                            Icon(imageVector = Icons.Default.Close, contentDescription = "Close dialog", tint = colors.textSecondary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Article Completeness + PKT Status Badges
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    val isComplete = news.contentStatus == ArticleContentStatus.COMPLETE
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background((if (isComplete) colors.bullishGreen else colors.gold).copy(alpha = 0.14f))
                            .border(
                                1.dp,
                                (if (isComplete) colors.bullishGreen else colors.gold).copy(alpha = 0.45f),
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                            .testTag("news_article_status_badge")
                    ) {
                        Text(
                            text = if (isComplete) {
                                "COMPLETE ARTICLE (${news.fetchedArticleCharCount} chars)"
                            } else {
                                "PARTIAL / SUMMARY ONLY"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (isComplete) colors.bullishGreen else colors.gold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 9.sp
                            )
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = news.eventStatus.label.uppercase(),
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (news.eventStatus == EventStatus.CONFIRMED) colors.bullishGreen else colors.gold,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }

                if (isFetchingFullArticle) {
                    Spacer(modifier = Modifier.height(6.dp))
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = colors.gold,
                        trackColor = colors.surfaceVariant
                    )
                    Text(
                        text = "Fetching full publisher article where permitted...",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 9.sp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 3-Language Selector Bar (English / Roman Urdu / Urdu Script)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.7f))
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .padding(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Translate,
                                contentDescription = "Article language selector",
                                tint = colors.gold,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ARTICLE LANGUAGE (NUMBERS PRESERVED)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.gold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 9.sp,
                                    letterSpacing = 0.6.sp
                                )
                            )
                        }

                        if (selectedLanguage != NewsLanguageOption.ENGLISH) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.card)
                                    .border(1.dp, colors.gold.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                    .clickable(enabled = !isTranslating) { onRetryTranslation() }
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("retry_news_translation_btn"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry online translation",
                                    tint = colors.gold,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Retry",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = colors.gold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NewsLanguageOption.entries.forEach { lang ->
                            val selected = selectedLanguage == lang
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (selected) colors.gold else colors.card)
                                    .border(1.dp, if (selected) colors.gold else colors.border, RoundedCornerShape(8.dp))
                                    .clickable { onSelectLanguage(lang) }
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                                    .testTag("news_lang_tab_${lang.name.lowercase()}"),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = lang.label,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = if (selected) colors.onGold else colors.textPrimary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = if (lang == NewsLanguageOption.URDU_SCRIPT) 12.sp else 10.sp,
                                        fontFamily = if (lang == NewsLanguageOption.URDU_SCRIPT) NotoNastaliqUrduFontFamily else FontFamily.Default
                                    )
                                )
                            }
                        }
                    }

                    if (isTranslating) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(2.dp)),
                            color = colors.gold,
                            trackColor = colors.card
                        )
                    }

                    activeTrans?.let { trans ->
                        Spacer(modifier = Modifier.height(6.dp))
                        val badge = if (trans.fromCache) "${trans.translationSource} • Cached" else trans.translationSource
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = if (trans.errorNote != null) colors.gold else colors.bullishGreen,
                                fontSize = 9.sp
                            ),
                            modifier = Modifier.testTag("news_translation_provenance")
                        )
                        trans.errorNote?.let { note ->
                            Text(
                                text = note,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textMuted,
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp
                                )
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Distinct Publication PKT vs App Retrieval PKT Timestamps
                Text(
                    text = "Source: ${news.source}",
                    style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                )
                Text(
                    text = "Published (PKT): $pubPkt",
                    style = MaterialTheme.typography.labelSmall.copy(color = colors.textSecondary, fontSize = 10.sp)
                )
                if (retrievedPkt.isNotBlank()) {
                    Text(
                        text = "App Retrieved (PKT): $retrievedPkt",
                        style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp)
                    )
                }

                // Affected Assets Row
                if (news.affectedAssets.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            "Affected Assets:",
                            style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp),
                            modifier = Modifier.align(Alignment.CenterVertically)
                        )
                        news.affectedAssets.forEach { asset ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(colors.surfaceVariant)
                                    .border(1.dp, colors.gold.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
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
                }

                // Roman Urdu Economic Terms Row
                if (detectedTerms.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.gold.copy(alpha = 0.08f))
                            .border(1.dp, colors.gold.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "ECONOMIC TERMS EXPLANATION (ROMAN URDU)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                letterSpacing = 0.8.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            detectedTerms.forEach { term ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(colors.surfaceVariant)
                                        .clickable { onOpenGlossaryTerm?.invoke(term) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = "Explain ${term.abbreviation}",
                                        tint = colors.gold,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${term.abbreviation} Wazaahat",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = colors.textPrimary,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Language-direction-aware Content Body & Structured Analysis Sections
                CompositionLocalProvider(LocalLayoutDirection provides contentDirection) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = displayTitle,
                            style = MaterialTheme.typography.titleMedium.copy(
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                lineHeight = if (isUrduScript) 28.sp else 22.sp,
                                fontFamily = contentFont
                            ),
                            modifier = Modifier.testTag("news_detail_title")
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // CONFIRMED FACTS CARD
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.bullishGreen.copy(alpha = 0.08f))
                                .border(1.dp, colors.bullishGreen.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                                .testTag("news_confirmed_facts_box")
                        ) {
                            Text(
                                text = if (isUrduScript) "تصدیق شدہ حقائق (CONFIRMED FACTS)" else "CONFIRMED FACTS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.bullishGreen,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.6.sp,
                                    fontFamily = contentFont
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            displayFacts.forEach { fact ->
                                Text(
                                    text = "• $fact",
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        color = colors.textPrimary,
                                        fontSize = if (isUrduScript) 13.sp else 11.sp,
                                        lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                        fontFamily = contentFont
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // AI / NLP INTERPRETATION CARD
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.surfaceVariant.copy(alpha = 0.7f))
                                .border(1.dp, colors.gold.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                                .testTag("news_ai_interpretation_box")
                        ) {
                            Text(
                                text = if (isUrduScript) "اے آئی تجزیہ (ممکنہ اثرات)" else "AI / NLP INTERPRETATION (PROBABILISTIC)",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.gold,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    letterSpacing = 0.6.sp,
                                    fontFamily = contentFont
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = displayInterpretation,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                    fontSize = if (isUrduScript) 13.sp else 11.sp,
                                    lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                    fontFamily = contentFont
                                )
                            )
                        }

                        // POTENTIAL BTC & ALTCOIN IMPACT + SCENARIOS
                        if (displayBtcImpact.isNotBlank() || displayAltcoinImpact.isNotBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(colors.surfaceVariant.copy(alpha = 0.55f))
                                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                                    .testTag("news_crypto_impact_box")
                            ) {
                                Text(
                                    text = if (isUrduScript) "بٹ کوائن اور آلٹ کوائنز پر ممکنہ اثر" else "POTENTIAL ASSET IMPACT",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = colors.gold,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 10.sp,
                                        fontFamily = contentFont
                                    )
                                )
                                if (displayBtcImpact.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = displayBtcImpact,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colors.textPrimary,
                                            fontSize = if (isUrduScript) 13.sp else 11.sp,
                                            lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                            fontFamily = contentFont
                                        )
                                    )
                                }
                                if (displayAltcoinImpact.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = displayAltcoinImpact,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = colors.textSecondary,
                                            fontSize = if (isUrduScript) 13.sp else 11.sp,
                                            lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                            fontFamily = contentFont
                                        )
                                    )
                                }
                                if (displayShortTerm.isNotBlank() || displayLongTerm.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isUrduScript) "قلیل و طویل مدتی منظرنامے" else "SHORT-TERM & LONG-TERM SCENARIOS",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = colors.bullishGreen,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 10.sp,
                                            fontFamily = contentFont
                                        )
                                    )
                                    if (displayShortTerm.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = displayShortTerm,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = colors.textSecondary,
                                                fontSize = if (isUrduScript) 13.sp else 11.sp,
                                                lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                                fontFamily = contentFont
                                            )
                                        )
                                    }
                                    if (displayLongTerm.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = displayLongTerm,
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = colors.textSecondary,
                                                fontSize = if (isUrduScript) 13.sp else 11.sp,
                                                lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                                fontFamily = contentFont
                                            )
                                        )
                                    }
                                }
                            }
                        }

                        // UNCERTAINTY & RISKS
                        Spacer(modifier = Modifier.height(10.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(colors.bearishRed.copy(alpha = 0.08f))
                                .border(1.dp, colors.bearishRed.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                                .padding(10.dp)
                                .testTag("news_uncertainty_risks_box")
                        ) {
                            Text(
                                text = if (isUrduScript) "غیر یقینی صورتحال اور خطرات" else "UNCERTAINTY & RISKS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = colors.bearishRed,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 10.sp,
                                    fontFamily = contentFont
                                )
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = displayUncertainty,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textSecondary,
                                    fontSize = if (isUrduScript) 13.sp else 11.sp,
                                    lineHeight = if (isUrduScript) 22.sp else 16.sp,
                                    fontFamily = contentFont
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Full or Summary-Only Article Content
                        Text(
                            text = if (news.contentStatus == ArticleContentStatus.COMPLETE) {
                                if (isUrduScript) "مکمل مضمون" else "COMPLETE ARTICLE TEXT"
                            } else {
                                if (isUrduScript) "دستیاب خلاصہ (مکمل متن ناشر کی طرف سے محدود ہے)" else "AVAILABLE SUMMARY ONLY (FULL ARTICLE RESTRICTED / UNAVAILABLE)"
                            },
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = colors.gold,
                                fontWeight = FontWeight.Bold,
                                fontSize = 10.sp,
                                fontFamily = contentFont
                            )
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = displayBody,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = colors.textSecondary,
                                lineHeight = if (isUrduScript) 25.sp else 20.sp,
                                fontSize = if (isUrduScript) 14.sp else 13.sp,
                                fontFamily = contentFont
                            ),
                            modifier = Modifier.testTag("news_detail_body")
                        )
                    }
                }

                // Open Article Link Button
                if (news.url.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(news.url))
                                context.startActivity(browserIntent)
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .minimumInteractiveComponentSize()
                            .testTag("open_article_link_btn"),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.gold)
                    ) {
                        Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open Original Source Article", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Transparent Analysis Method Tag & Risk Caution
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.5f))
                        .padding(10.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Analysis Method: ",
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.textMuted, fontSize = 10.sp)
                            )
                            Text(
                                text = news.analysisMethod,
                                style = MaterialTheme.typography.labelSmall.copy(color = colors.gold, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⚠️ Caution: Market reactions to macroeconomic news are probabilistic. Do not confuse possible market reactions with guaranteed price movements.",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = colors.textMuted,
                                fontSize = 10.sp,
                                lineHeight = 14.sp
                            )
                        )
                    }
                }
            }
        }
    }
}
