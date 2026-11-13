package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ai.MessageSender
import com.example.data.ai.NexusChatMessage
import com.example.data.ai.NexusConversation
import com.example.data.learning.LearningLanguageMode
import com.example.data.settings.UrduFontSize
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.NotoNastaliqUrduFontFamily
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NexusAiScreen(
    messages: List<NexusChatMessage>,
    conversations: List<NexusConversation>,
    currentConversationId: String,
    isLoading: Boolean,
    isOnlineAiAvailable: Boolean,
    lastAiError: String? = null,
    dataFreshnessLabel: String = "Binance Public Feeds",
    currentAssetSymbol: String,
    currentAssetPrice: Double,
    languageMode: LearningLanguageMode,
    urduFontSize: UrduFontSize = UrduFontSize.MEDIUM,
    onSendMessage: (String) -> Unit,
    onRetryMessage: (String) -> Unit = {},
    onDismissError: () -> Unit = {},
    onNewChat: () -> Unit,
    onClearChat: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onSelectConversation: (String) -> Unit,
    onAttachCurrentMarket: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexusTheme.colors
    val context = LocalContext.current
    val listState = rememberLazyListState()
    var inputText by remember { mutableStateOf("") }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length, isLoading) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    val isUrduScript = languageMode == LearningLanguageMode.STANDARD_URDU
    val isBilingual = languageMode == LearningLanguageMode.BILINGUAL
    val urduScale = urduFontSize.scaleFactor

    val suggestedQuestions = remember(languageMode) {
        when (languageMode) {
            LearningLanguageMode.ENGLISH -> listOf(
                "Why is the crypto market falling or moving today?",
                "Is this a good time to buy BTC? Give balanced scenarios",
                "Which watchlist coin has the strongest setup?",
                "What are key support, resistance, and entry zones for ETH?",
                "Explain CPI, PPI, and Fed rate decisions simply",
                "Explain funding rates, open interest, and liquidations"
            )
            LearningLanguageMode.ROMAN_URDU -> listOf(
                "Aaj crypto market kyun gir rahi ya move kar rahi hai?",
                "Kya ab BTC khareedna sahi hai? Balanced scenarios batayein",
                "Watchlist mein sab se strong setup kis coin ka hai?",
                "ETH ke support, resistance aur entry zones kya hain?",
                "CPI, PPI aur Fed rate decisions asaan alfaaz mein samjhayein",
                "Funding rate, open interest aur liquidations kya hotay hain?"
            )
            LearningLanguageMode.STANDARD_URDU -> listOf(
                "آج کرپٹو مارکیٹ کیوں گر رہی یا متحرک ہے؟",
                "کیا اس وقت بٹ کوائن خریدنا مناسب ہے؟ متوازن منظرنامے بتائیں",
                "واچ لسٹ میں سب سے مضبوط سیٹ اپ کس کوائن کا ہے؟",
                "ایتھیریم کے سپورٹ، مزاحمت اور انٹری زونز کیا ہیں؟",
                "سی پی آئی، پی پی آئی اور فیڈ کے فیصلے آسان الفاظ میں سمجھائیں",
                "فنڈنگ ریٹ، اوپن انٹرسٹ اور لیکویڈیشن کی وضاحت کریں"
            )
            LearningLanguageMode.BILINGUAL -> listOf(
                "Why is crypto moving today? (آج مارکیٹ کیوں متحرک ہے؟)",
                "Strongest watchlist setup? (مضبوط ترین سیٹ اپ)",
                "BTC balanced scenarios & entry zones (متوازن تجزیہ)",
                "Explain CPI, PPI & Fed (معاشی اشاریوں کی وضاحت)",
                "Funding rate, OI & liquidations (ڈیریویٹوز کی وضاحت)"
            )
        }
    }

    val isFallbackModeActive = !isOnlineAiAvailable || lastAiError != null

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        // Top Header Bar
        Surface(
            color = colors.card,
            shadowElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(colors.gold.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "NEXUS AI",
                                tint = colors.gold,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "NEXUS AI",
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(if (!isFallbackModeActive) colors.bullishGreen else colors.gold)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (!isFallbackModeActive) {
                                        "Gemini 3.8 Flash • Free Tier"
                                    } else {
                                        "Limited Offline Fallback Active"
                                    },
                                    color = if (!isFallbackModeActive) colors.textSecondary else colors.gold,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }

                    Row {
                        IconButton(
                            onClick = onNewChat,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ai_new_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "New Chat",
                                tint = colors.textSecondary
                            )
                        }
                        IconButton(
                            onClick = onClearChat,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("ai_clear_chat_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Clear Chat",
                                tint = colors.textSecondary
                            )
                        }
                    }
                }

                // Active Market Context & Data Freshness Badge
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val priceText = if (currentAssetPrice > 0.0) {
                        "$currentAssetSymbol • $${String.format(Locale.US, "%.2f", currentAssetPrice)}"
                    } else {
                        "$currentAssetSymbol • Connecting..."
                    }
                    Text(
                        text = priceText,
                        color = colors.gold,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    Text(
                        text = dataFreshnessLabel,
                        color = colors.textSecondary,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Conversation Threads Switcher (when multiple chats exist)
                if (conversations.size > 1) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        conversations.forEach { conv ->
                            val isSelected = conv.id == currentConversationId
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) colors.gold.copy(alpha = 0.2f) else colors.background,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSelected) colors.gold else colors.border
                                ),
                                modifier = Modifier.clickable { onSelectConversation(conv.id) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = conv.title,
                                        color = if (isSelected) colors.gold else colors.textSecondary,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (conversations.size > 1) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Delete conversation",
                                            tint = colors.textSecondary,
                                            modifier = Modifier
                                                .size(12.dp)
                                                .clickable { onDeleteConversation(conv.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Transparent Online AI Error Banner with Retry & Dismiss
        if (lastAiError != null) {
            val lastFallbackMsgId = messages.lastOrNull { it.isOfflineEngine && it.originalQueryForRetry != null }?.id
            Surface(
                color = colors.gold.copy(alpha = 0.14f),
                border = androidx.compose.foundation.BorderStroke(1.dp, colors.gold.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .testTag("ai_error_banner"),
                shape = RoundedCornerShape(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = "AI Warning",
                        tint = colors.gold,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = lastAiError,
                        color = colors.textPrimary,
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (lastFallbackMsgId != null && !isLoading) {
                        TextButton(
                            onClick = { onRetryMessage(lastFallbackMsgId) },
                            modifier = Modifier.testTag("ai_banner_retry_button")
                        ) {
                            Text("Retry", color = colors.gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    IconButton(
                        onClick = onDismissError,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss error",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Messages List or Welcome Screen
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (messages.isEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    item {
                        Spacer(modifier = Modifier.height(18.dp))
                        Box(
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(colors.gold.copy(alpha = 0.12f))
                                .border(1.dp, colors.gold.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = colors.gold,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (isUrduScript) "نیکسس اے آئی میں خوش آمدید" else "Welcome to NEXUS AI",
                            color = colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = (20 * urduScale).sp,
                            fontFamily = if (isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
                        )
                        Text(
                            text = if (isUrduScript) "آپ کا ذاتی کنورسیشنل ٹریڈنگ اینالسٹ (مفت جیمینائی ماڈل)" else "Conversational AI Trading Analyst • Live Binance & Derivatives Grounded",
                            color = colors.textSecondary,
                            fontSize = (12 * urduScale).sp,
                            textAlign = TextAlign.Center,
                            fontFamily = if (isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = colors.card,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.TrendingUp,
                                        contentDescription = null,
                                        tint = colors.bullishGreen,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isUrduScript) "لائیو بائنانس اور ٹیکنیکل انجن سے منسلک" else "Connected to Live Binance, Derivatives & Watchlist Engines",
                                        color = colors.textPrimary,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (isUrduScript)
                                        "آپ کوئی بھی آزادانہ سوال اردو، رومن اردو یا انگریزی میں پوچھ سکتے ہیں — فالو اپ سوالات سمیت۔"
                                    else
                                        "Ask any free-form question in English, Roman Urdu, or Urdu — including follow-ups, macro concepts, watchlist comparisons, and balanced risk scenarios.",
                                    color = colors.textSecondary,
                                    fontSize = 11.sp,
                                    fontFamily = if (isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))
                        Text(
                            text = if (isUrduScript) "تجویز کردہ سوالات:" else "Try Asking Anything:",
                            color = colors.textSecondary,
                            fontWeight = FontWeight.Medium,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    items(suggestedQuestions) { question ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = colors.card,
                            border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.8f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onSendMessage(question) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ChatBubbleOutline,
                                    contentDescription = null,
                                    tint = colors.gold,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = question,
                                    color = colors.textPrimary,
                                    fontSize = (13 * urduScale).sp,
                                    fontFamily = if (isUrduScript || (isBilingual && question.contains("[\u0600-\u06FF]".toRegex())))
                                        NotoNastaliqUrduFontFamily else FontFamily.Default
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        ChatMessageItem(
                            message = msg,
                            languageMode = languageMode,
                            urduFontSize = urduFontSize,
                            isLoading = isLoading,
                            onCopy = { text ->
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("NEXUS AI Response", text)
                                clipboard.setPrimaryClip(clip)
                            },
                            onRetry = { onRetryMessage(msg.id) },
                            onFollowUpClick = { followUp -> onSendMessage(followUp) }
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .testTag("ai_loading_indicator"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colors.gold
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isUrduScript) "لائیو بائنانس ڈیٹا اور جیمینائی اے آئی سے جواب تیار ہو رہا ہے..." else "Fetching live Binance data & generating AI response...",
                                    color = colors.textSecondary,
                                    fontSize = 12.sp,
                                    fontFamily = if (isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
                                )
                            }
                        }
                    }
                }
            }
        }

        // Input Field & Action Bar
        Surface(
            color = colors.card,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.gold.copy(alpha = 0.1f))
                            .clickable(enabled = !isLoading) { onAttachCurrentMarket() }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrendingUp,
                            contentDescription = null,
                            tint = colors.gold,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Analyze Live: $currentAssetSymbol",
                            color = colors.gold,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Text(
                        text = "Free-Tier Gemini • Public Binance",
                        color = colors.textSecondary,
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isUrduInput = inputText.any { it in '\u0600'..'\u06FF' }

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = if (isUrduScript) "مارکیٹ، سگنلز یا کوئی بھی تجارتی سوال پوچھیں..."
                                else if (languageMode == LearningLanguageMode.ROMAN_URDU) "Market, coins ya koi bhi trading sawal poochein..."
                                else "Ask any trading, market, or follow-up question...",
                                color = colors.textSecondary.copy(alpha = 0.6f),
                                fontSize = 13.sp,
                                fontFamily = if (isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = colors.gold,
                            unfocusedBorderColor = colors.border,
                            focusedTextColor = colors.textPrimary,
                            unfocusedTextColor = colors.textPrimary
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("ai_input_text_field"),
                        maxLines = 4,
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontSize = (14 * urduScale).sp,
                            fontFamily = if (isUrduInput || isUrduScript) NotoNastaliqUrduFontFamily else FontFamily.Default,
                            textAlign = if (isUrduInput || isUrduScript) TextAlign.Right else TextAlign.Left
                        ),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (inputText.isNotBlank() && !isLoading) {
                                val query = inputText
                                inputText = ""
                                onSendMessage(query)
                            }
                        })
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank() && !isLoading) {
                                val query = inputText
                                inputText = ""
                                onSendMessage(query)
                            }
                        },
                        enabled = inputText.isNotBlank() && !isLoading,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(if (inputText.isNotBlank() && !isLoading) colors.gold else colors.border.copy(alpha = 0.4f))
                            .testTag("ai_send_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send",
                            tint = if (inputText.isNotBlank() && !isLoading) Color.Black else colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatMessageItem(
    message: NexusChatMessage,
    languageMode: LearningLanguageMode,
    urduFontSize: UrduFontSize,
    isLoading: Boolean,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onFollowUpClick: (String) -> Unit
) {
    val colors = NexusTheme.colors
    val isUser = message.sender == MessageSender.USER
    val containsUrduCharacters = message.text.any { it in '\u0600'..'\u06FF' }
    val useNastaleeq = containsUrduCharacters || languageMode == LearningLanguageMode.STANDARD_URDU
    val urduScale = urduFontSize.scaleFactor
    val layoutDir = if (containsUrduCharacters) LayoutDirection.Rtl else LayoutDirection.Ltr

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = if (isUser) "You" else "NEXUS AI",
                color = colors.textSecondary,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            if (!isUser && message.isOfflineEngine) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.gold.copy(alpha = 0.16f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "Limited Offline Fallback",
                        color = colors.gold,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else if (!isUser && !message.isOfflineEngine) {
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.bullishGreen.copy(alpha = 0.14f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = if (message.isStreaming) "Gemini Streaming…" else "Gemini 3.8 Flash",
                        color = colors.bullishGreen,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 2.dp,
                bottomEnd = if (isUser) 2.dp else 14.dp
            ),
            color = if (isUser) colors.gold.copy(alpha = 0.2f) else colors.card,
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = when {
                    isUser -> colors.gold.copy(alpha = 0.5f)
                    message.isOfflineEngine -> colors.gold.copy(alpha = 0.45f)
                    else -> colors.border
                }
            ),
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.attachedContextSummary != null || message.dataTimestampBadge != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.background)
                            .padding(6.dp)
                    ) {
                        Column {
                            message.attachedContextSummary?.let { summary ->
                                Text(
                                    text = "Market Context: $summary",
                                    color = colors.textSecondary,
                                    fontSize = 10.sp
                                )
                            }
                            message.dataTimestampBadge?.let { badge ->
                                Text(
                                    text = "Data Timestamp: $badge",
                                    color = colors.textSecondary.copy(alpha = 0.85f),
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                CompositionLocalProvider(LocalLayoutDirection provides layoutDir) {
                    Text(
                        text = message.text,
                        color = colors.textPrimary,
                        fontSize = (14 * urduScale).sp,
                        lineHeight = (22 * urduScale).sp,
                        fontFamily = if (useNastaleeq) NotoNastaliqUrduFontFamily else FontFamily.Default,
                        textAlign = if (containsUrduCharacters) TextAlign.Right else TextAlign.Left,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (!isUser && !message.isStreaming) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (message.isOfflineEngine && message.originalQueryForRetry != null) {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(colors.gold.copy(alpha = 0.15f))
                                    .clickable(enabled = !isLoading) { onRetry() }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                                    .testTag("ai_retry_button"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Retry Online AI",
                                    tint = colors.gold,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "Retry Online AI",
                                    color = colors.gold,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        IconButton(
                            onClick = { onCopy(message.text) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy Response",
                                tint = colors.textSecondary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        if (!isUser && message.suggestedFollowUps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Column(modifier = Modifier.padding(start = 4.dp)) {
                message.suggestedFollowUps.forEach { followUp ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.background,
                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.border.copy(alpha = 0.6f)),
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .clickable(enabled = !isLoading) { onFollowUpClick(followUp) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Lightbulb,
                                contentDescription = null,
                                tint = colors.gold,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = followUp,
                                color = colors.gold,
                                fontSize = (11 * urduScale).sp,
                                fontFamily = if (followUp.any { it in '\u0600'..'\u06FF' }) NotoNastaliqUrduFontFamily else FontFamily.Default
                            )
                        }
                    }
                }
            }
        }
    }
}
