package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.data.learning.LearningLanguageMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.UUID

private const val TAG = "NexusAiRepository"
private const val AI_TIMEOUT_MS = 60_000L
const val NEXUS_GEMINI_MODEL_NAME = "gemini-3.8-flash"

sealed interface NexusAiStreamResult {
    data class Chunk(val message: NexusChatMessage) : NexusAiStreamResult
    data class Completed(
        val message: NexusChatMessage,
        val onlineErrorReason: String? = null
    ) : NexusAiStreamResult
    data class Error(
        val provider: AiProviderType,
        val errorMessage: String,
        val failedQuery: String,
        val contextSnapshot: MarketContextSnapshot,
        val canRetry: Boolean = true,
        val isQuotaOrPaymentDisabled: Boolean = false
    ) : NexusAiStreamResult
}

/**
 * Manages Multi-Provider Generative AI (Firebase AI Logic Gemini 3.8 Flash + DeepSeek Provider Interface)
 * and explicit user-triggered Offline Rule-Based Analyzer.
 *
 * CRITICAL RUNTIME FIX:
 * Online AI failures (App Check 403, Quota 429, Payment Required 402, Network/Timeout, SDK role errors)
 * are NEVER hidden behind silent offline fallback. Failures emit NexusAiStreamResult.Error explicitly so
 * users and tests see the real runtime state, can Retry, switch providers, or explicitly choose Offline mode.
 */
class NexusAiRepository(
    private val context: Context? = null,
    private val aiClient: GenerativeAiClient = FirebaseGenerativeAiClient(context),
    private val deepSeekProvider: OnlineAiProvider = DeepSeekAiProvider()
) {
    private val sharedPrefs = context?.getSharedPreferences("nexus_ai_conversations", Context.MODE_PRIVATE)

    private val geminiProviderAdapter: OnlineAiProvider = if (aiClient is OnlineAiProvider) {
        aiClient
    } else {
        object : OnlineAiProvider {
            override val providerType: AiProviderType = AiProviderType.GEMINI_FIREBASE
            override fun isConfigured(): Boolean = aiClient.isConfigured()
            override fun getStatus(): AiProviderStatus = if (aiClient.isConfigured()) {
                AiProviderStatus(
                    provider = AiProviderType.GEMINI_FIREBASE,
                    availability = AiProviderAvailability.READY,
                    detail = "Firebase AI Logic ($NEXUS_GEMINI_MODEL_NAME) ready"
                )
            } else {
                AiProviderStatus(
                    provider = AiProviderType.GEMINI_FIREBASE,
                    availability = AiProviderAvailability.UNCONFIGURED,
                    detail = "Firebase AI client not configured."
                )
            }

            override fun streamChat(
                systemInstruction: String,
                history: List<Pair<String, String>>,
                userMessage: String
            ): Flow<String> = aiClient.streamChat(systemInstruction, history, userMessage)
        }
    }

    fun isOnlineAiAvailable(): Boolean =
        geminiProviderAdapter.isConfigured() || deepSeekProvider.isConfigured()

    fun getProviderStatuses(): List<AiProviderStatus> = listOf(
        geminiProviderAdapter.getStatus(),
        deepSeekProvider.getStatus(),
        AiProviderStatus(
            provider = AiProviderType.OFFLINE_RULE_BASED,
            availability = AiProviderAvailability.READY,
            detail = "Deterministic local indicator & glossary engine (Zero network required)"
        )
    )

    fun generateResponseStream(
        userPrompt: String,
        contextSnapshot: MarketContextSnapshot,
        languageMode: LearningLanguageMode,
        recentHistory: List<NexusChatMessage> = emptyList(),
        selectedProvider: AiProviderType = AiProviderType.GEMINI_FIREBASE
    ): Flow<NexusAiStreamResult> = flow {
        val contextSummary = "${contextSnapshot.assetName} (${contextSnapshot.symbol}) @ $${contextSnapshot.currentPrice}"
        val dataBadge = "${contextSnapshot.dataStatus} • ${contextSnapshot.instrumentTypeLabel} • ${contextSnapshot.lastUpdatedFormatted}"
        val followUps = generateFollowUps(languageMode, contextSnapshot, userPrompt)
        val messageId = UUID.randomUUID().toString()

        if (selectedProvider == AiProviderType.OFFLINE_RULE_BASED) {
            val offlineMsg = runOfflineRuleBasedAnalysis(
                userPrompt = userPrompt,
                contextSnapshot = contextSnapshot,
                languageMode = languageMode,
                unavailableReason = "User-Selected Offline Rule-Based Analyzer",
                recentHistory = recentHistory
            )
            emit(NexusAiStreamResult.Completed(message = offlineMsg))
            return@flow
        }

        val activeProvider = when (selectedProvider) {
            AiProviderType.DEEPSEEK -> deepSeekProvider
            AiProviderType.GEMINI_FIREBASE -> geminiProviderAdapter
            AiProviderType.OFFLINE_RULE_BASED -> geminiProviderAdapter
        }

        val providerStatus = activeProvider.getStatus()
        if (!activeProvider.isConfigured()) {
            val notReadyMsg = providerStatus.detail.ifBlank {
                "${selectedProvider.displayName} is not configured or disabled."
            }
            Log.e(TAG, "${selectedProvider.displayName} not ready: $notReadyMsg")
            emit(
                NexusAiStreamResult.Error(
                    provider = selectedProvider,
                    errorMessage = notReadyMsg,
                    failedQuery = userPrompt.trim(),
                    contextSnapshot = contextSnapshot,
                    canRetry = providerStatus.availability != AiProviderAvailability.DISABLED_PAYMENT_REQUIRED,
                    isQuotaOrPaymentDisabled = providerStatus.availability == AiProviderAvailability.DISABLED_PAYMENT_REQUIRED ||
                        providerStatus.availability == AiProviderAvailability.COOLDOWN_RATE_LIMIT
                )
            )
            return@flow
        }

        val accumulatedText = StringBuilder()
        try {
            val systemInstruction = buildSystemInstruction(languageMode)
            val historyPairs = buildConversationHistory(recentHistory, userPrompt)
            val groundedTurnPrompt = buildGroundedTurnPrompt(userPrompt, contextSnapshot, languageMode)

            withTimeout(AI_TIMEOUT_MS) {
                activeProvider.streamChat(
                    systemInstruction = systemInstruction,
                    history = historyPairs,
                    userMessage = groundedTurnPrompt
                ).collect { chunkText ->
                    if (chunkText.isNotEmpty()) {
                        accumulatedText.append(chunkText)
                        emit(
                            NexusAiStreamResult.Chunk(
                                NexusChatMessage(
                                    id = messageId,
                                    conversationId = "",
                                    sender = MessageSender.ASSISTANT,
                                    text = accumulatedText.toString(),
                                    isStreaming = true,
                                    isOfflineEngine = false,
                                    isErrorFallback = false,
                                    dataTimestampBadge = dataBadge,
                                    attachedContextSummary = contextSummary,
                                    providerUsed = selectedProvider.badgeLabel
                                )
                            )
                        )
                    }
                }
            }

            val finalText = accumulatedText.toString().trim()
            if (finalText.isNotEmpty()) {
                emit(
                    NexusAiStreamResult.Completed(
                        message = NexusChatMessage(
                            id = messageId,
                            conversationId = "",
                            sender = MessageSender.ASSISTANT,
                            text = finalText,
                            isStreaming = false,
                            isOfflineEngine = false,
                            isErrorFallback = false,
                            dataTimestampBadge = dataBadge,
                            attachedContextSummary = contextSummary,
                            suggestedFollowUps = followUps,
                            providerUsed = selectedProvider.badgeLabel
                        )
                    )
                )
            } else {
                val emptyMsg = "${selectedProvider.badgeLabel} returned an empty response. Check runtime logs or Retry."
                Log.e(TAG, emptyMsg)
                emit(
                    NexusAiStreamResult.Error(
                        provider = selectedProvider,
                        errorMessage = emptyMsg,
                        failedQuery = userPrompt.trim(),
                        contextSnapshot = contextSnapshot,
                        canRetry = true,
                        isQuotaOrPaymentDisabled = false
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "${selectedProvider.badgeLabel} timed out after 60s", e)
            emit(
                NexusAiStreamResult.Error(
                    provider = selectedProvider,
                    errorMessage = "${selectedProvider.badgeLabel} timed out after 60s. Check connection and tap Retry, Switch Provider, or Run Limited Offline Analyzer.",
                    failedQuery = userPrompt.trim(),
                    contextSnapshot = contextSnapshot,
                    canRetry = true,
                    isQuotaOrPaymentDisabled = false
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiProviderException) {
            Log.e(TAG, "${e.provider.badgeLabel} runtime failure", e)
            emit(
                NexusAiStreamResult.Error(
                    provider = e.provider,
                    errorMessage = e.message ?: classifyOnlineAiError(e, e.provider),
                    failedQuery = userPrompt.trim(),
                    contextSnapshot = contextSnapshot,
                    canRetry = !e.isPaymentRequired,
                    isQuotaOrPaymentDisabled = e.isPaymentRequired || e.isQuotaExhausted
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "${selectedProvider.badgeLabel} runtime exception", e)
            val errMsg = classifyOnlineAiError(e, selectedProvider)
            val isQuota = errMsg.contains("quota", ignoreCase = true) ||
                errMsg.contains("429") ||
                errMsg.contains("402")
            emit(
                NexusAiStreamResult.Error(
                    provider = selectedProvider,
                    errorMessage = errMsg,
                    failedQuery = userPrompt.trim(),
                    contextSnapshot = contextSnapshot,
                    canRetry = !errMsg.contains("402"),
                    isQuotaOrPaymentDisabled = isQuota
                )
            )
        }
    }

    /**
     * Explicitly invokes NEXUS OfflineTradingAnalysisEngine ONLY when selected by the user
     * or when the user taps "Use Limited Offline Analyzer" on an explicit error card.
     */
    fun runOfflineRuleBasedAnalysis(
        userPrompt: String,
        contextSnapshot: MarketContextSnapshot,
        languageMode: LearningLanguageMode,
        unavailableReason: String? = null,
        recentHistory: List<NexusChatMessage> = emptyList()
    ): NexusChatMessage {
        val contextSummary = "${contextSnapshot.assetName} (${contextSnapshot.symbol}) @ $${contextSnapshot.currentPrice}"
        val dataBadge = "${contextSnapshot.dataStatus} • ${contextSnapshot.instrumentTypeLabel} • ${contextSnapshot.lastUpdatedFormatted}"
        val followUps = generateFollowUps(languageMode, contextSnapshot, userPrompt)
        val offlineText = OfflineTradingAnalysisEngine.generateAnalysis(
            query = userPrompt,
            context = contextSnapshot,
            language = languageMode,
            unavailableReason = unavailableReason,
            recentHistory = recentHistory
        )
        return NexusChatMessage(
            conversationId = "",
            sender = MessageSender.ASSISTANT,
            text = offlineText,
            isStreaming = false,
            isOfflineEngine = true,
            isErrorFallback = !unavailableReason.isNullOrBlank() &&
                !unavailableReason.contains("User-Selected", ignoreCase = true),
            errorReason = unavailableReason,
            originalQueryForRetry = userPrompt.trim(),
            dataTimestampBadge = dataBadge,
            attachedContextSummary = contextSummary,
            suggestedFollowUps = followUps,
            providerUsed = AiProviderType.OFFLINE_RULE_BASED.badgeLabel
        )
    }

    suspend fun generateResponse(
        userPrompt: String,
        contextSnapshot: MarketContextSnapshot,
        languageMode: LearningLanguageMode,
        recentHistory: List<NexusChatMessage> = emptyList(),
        selectedProvider: AiProviderType = AiProviderType.GEMINI_FIREBASE
    ): NexusChatMessage {
        var finalMessage: NexusChatMessage? = null
        var terminalError: NexusAiStreamResult.Error? = null
        generateResponseStream(
            userPrompt = userPrompt,
            contextSnapshot = contextSnapshot,
            languageMode = languageMode,
            recentHistory = recentHistory,
            selectedProvider = selectedProvider
        ).collect { result ->
            when (result) {
                is NexusAiStreamResult.Chunk -> Unit
                is NexusAiStreamResult.Completed -> finalMessage = result.message
                is NexusAiStreamResult.Error -> terminalError = result
            }
        }
        if (finalMessage != null) return finalMessage!!
        val err = terminalError
        throw AiProviderException(
            provider = err?.provider ?: selectedProvider,
            isQuotaExhausted = err?.isQuotaOrPaymentDisabled == true,
            message = err?.errorMessage ?: "Online AI request failed."
        )
    }

    internal fun buildConversationHistory(
        recentHistory: List<NexusChatMessage>,
        currentUserPrompt: String
    ): List<Pair<String, String>> {
        val trimmedCurrent = currentUserPrompt.trim()
        val filtered = recentHistory.filter { it.text.isNotBlank() && !it.isStreaming }
        val withoutCurrent = if (filtered.lastOrNull()?.sender == MessageSender.USER &&
            filtered.lastOrNull()?.text?.trim() == trimmedCurrent
        ) {
            filtered.dropLast(1)
        } else {
            filtered
        }

        val rawPairs = withoutCurrent
            .filter { !it.isErrorFallback }
            .map { msg ->
                val role = if (msg.sender == MessageSender.USER) "user" else "model"
                role to msg.text
            }

        return FirebaseChatHistorySanitizer.sanitize(rawPairs, maxMessages = 16)
    }

    internal fun buildSystemInstruction(lang: LearningLanguageMode): String {
        val langInstruction = when (lang) {
            LearningLanguageMode.ENGLISH ->
                "Respond in clear, natural, helpful English. Use beginner-friendly explanations for educational questions, and institutional precision for market analysis."
            LearningLanguageMode.ROMAN_URDU ->
                "Respond in natural, fluent Roman Urdu (conversational Pakistani style). Keep technical terms like RSI, BOS, Stop Loss, CPI, PPI, Open Interest, TVL clear."
            LearningLanguageMode.STANDARD_URDU ->
                "Respond in fluent, natural Pakistani Urdu in URDU SCRIPT (اردو رسم الخط). Keep technical terms clear alongside Urdu explanations."
            LearningLanguageMode.BILINGUAL ->
                "Provide a clear, natural Bilingual response: concise English analysis/explanation followed by clear Urdu script (اردو) summary."
        }

        return """
            You are NEXUS AI, a genuine conversational trading analyst and educator inside NEXUS TRADING AI.
            You answer ANY free-form user question directly and naturally — never repeat canned scripts or force unrelated templates.

            CORE CAPABILITIES:
            - Crypto Futures (Binance USDT-M & Bybit V5 cross-check), CoinGecko Global Cap & Rankings, DefiLlama DeFi TVL & Stablecoin Float, Alternative.me Fear & Greed, GDELT Macro/Financial Wire.
            - Derivatives & Market Mechanics (Funding Rates, Open Interest, Liquidations, Long/Short Ratios).
            - Smart Money Concepts (BOS, CHoCH, Order Blocks, FVGs, Liquidity Sweeps) & 22 Technical Indicators.
            - Multi-turn conversation memory: understand follow-up questions using the sanitized chat history.

            LANGUAGE POLICY:
            - Default language setting: $langInstruction
            - If the user writes explicitly in Standard Urdu, Roman Urdu, or English, honor their request naturally.

            TRUTHFULNESS & STRICT SEPARATION RULES (CRITICAL):
            1. For market/price/setup questions, cite ONLY real values in the attached VERIFIED HYBRID MARKET FACTS.
            2. ALWAYS distinguish Spot Market vs USDT-M Perpetual Futures explicitly; NEVER blend Spot and Futures quotes silently.
            3. ALWAYS report timestamps (UTC) and disclose when any provider is STALE, RATE_LIMITED, or UNAVAILABLE.
            4. SEPARATE VERIFIED FACTS vs POSSIBLE EXPLANATIONS:
               - Present verified price, funding, OI, cross-exchange spread, global cap, TVL, and sentiment under VERIFIED FACTS.
               - Present filtered news headlines ONLY under POSSIBLE EXPLANATIONS / CONTEXT — NEVER treat headlines as proven causal drivers without corroborating official/on-chain evidence.
            5. NEVER invent prices, indicator values, missing API fields, whale transactions, or news catalysts.
            6. For trade or buying questions: NEVER guarantee profits and NEVER force a BUY or SELL call. Provide balanced Bullish and Bearish scenarios, possible entry zones, invalidation/Stop Loss levels, and key risks (1-2% capital risk).
        """.trimIndent()
    }

    internal fun buildGroundedTurnPrompt(
        userPrompt: String,
        ctx: MarketContextSnapshot,
        lang: LearningLanguageMode
    ): String {
        return """
            ${ctx.toPromptContext(lang)}

            USER QUESTION:
            ${userPrompt.trim()}
        """.trimIndent()
    }

    private fun classifyOnlineAiError(e: Exception, provider: AiProviderType): String {
        val msg = (e.message ?: e.localizedMessage ?: "").lowercase()
        val label = provider.badgeLabel
        return when {
            msg.contains("402") || msg.contains("payment") || msg.contains("insufficient balance") ->
                "$label returned HTTP 402 (payment/balance required) — disabled automatically under zero-cost policy."
            msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") || msg.contains("rate limit") ->
                "$label free-tier quota or rate limit reached (HTTP 429). Wait briefly and tap Retry or switch provider."
            msg.contains("401") || msg.contains("403") || msg.contains("unauthenticated") ||
                msg.contains("appcheck") || msg.contains("app check") || msg.contains("attestation") ->
                "Firebase App Check attestation rejected (HTTP 401/403). Verify debug token or Play Integrity attestation on device."
            msg.contains("role") || msg.contains("alternat") || msg.contains("content") ->
                "$label chat turn history formatting error (${e.localizedMessage ?: "invalid turn sequence"})."
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
                msg.contains("unable to resolve host") || msg.contains("failed to connect") || msg.contains("network") ->
                "No internet connection to reach $label. Tap Retry once online or use Limited Offline Analyzer."
            else ->
                "$label runtime error: ${e.localizedMessage ?: e::class.java.simpleName}"
        }
    }

    private fun generateFollowUps(
        lang: LearningLanguageMode,
        ctx: MarketContextSnapshot,
        userPrompt: String
    ): List<String> {
        val q = userPrompt.lowercase()
        return when (lang) {
            LearningLanguageMode.ENGLISH -> when {
                q.contains("fall") || q.contains("crash") || q.contains("drop") -> listOf(
                    "Could ${ctx.symbol} fall further or bounce from ${ctx.supportLevel?.let { "$${String.format("%.2f", it)}" } ?: "support"}?",
                    "What are Binance vs Bybit spread, funding rate, and OI saying?",
                    "Which watchlist coin has the strongest setup?"
                )
                q.contains("buy") || q.contains("entry") -> listOf(
                    "Where is the safest invalidation / stop loss?",
                    "Compare 15m and 1h structure for ${ctx.symbol}",
                    "What bearish risks could invalidate this setup?"
                )
                else -> listOf(
                    "What are key support and resistance levels for ${ctx.symbol}?",
                    "Show DeFi TVL, stablecoin float, and Fear & Greed impact",
                    "Which watchlist coin has the strongest setup right now?"
                )
            }
            LearningLanguageMode.ROMAN_URDU -> listOf(
                "${ctx.symbol} mein mazeed girawat ka khatra hai ya bounce?",
                "Safe entry zone aur Stop Loss kahan banta hai?",
                "Watchlist mein sab se strong setup kis coin ka hai?"
            )
            LearningLanguageMode.STANDARD_URDU -> listOf(
                "کیا مارکیٹ مزید نیچے گر سکتی ہے؟",
                "محفوظ انٹری زون اور اسٹاپ لاس کہاں ہے؟",
                "واچ لسٹ میں سب سے مضبوط سیٹ اپ کس کوائن کا ہے؟"
            )
            LearningLanguageMode.BILINGUAL -> listOf(
                "Entry Zones & Risks (انٹری زون اور خطرات)",
                "Strongest Watchlist Setup (مضبوط ترین سیٹ اپ)",
                "Support & Resistance (سپورٹ اور مزاحمت)"
            )
        }
    }

    // ==========================================
    // Local Conversation History Persistence
    // ==========================================

    fun loadConversations(): List<NexusConversation> {
        val raw = sharedPrefs?.getString("saved_conversations_list", null) ?: return listOf(
            NexusConversation(id = "default_session", title = "Market Analysis")
        )
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<NexusConversation>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NexusConversation(
                        id = obj.getString("id"),
                        title = obj.getString("title"),
                        createdAt = obj.getLong("createdAt"),
                        lastActiveAt = obj.getLong("lastActiveAt")
                    )
                )
            }
            if (list.isEmpty()) listOf(NexusConversation(id = "default_session", title = "Market Analysis")) else list
        } catch (_: Exception) {
            listOf(NexusConversation(id = "default_session", title = "Market Analysis"))
        }
    }

    fun saveConversations(list: List<NexusConversation>) {
        val arr = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("createdAt", item.createdAt)
                put("lastActiveAt", item.lastActiveAt)
            }
            arr.put(obj)
        }
        sharedPrefs?.edit()?.putString("saved_conversations_list", arr.toString())?.apply()
    }

    fun loadMessages(conversationId: String): List<NexusChatMessage> {
        val raw = sharedPrefs?.getString("messages_$conversationId", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            val list = mutableListOf<NexusChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    NexusChatMessage(
                        id = obj.getString("id"),
                        conversationId = conversationId,
                        sender = MessageSender.valueOf(obj.getString("sender")),
                        text = obj.getString("text"),
                        timestamp = obj.getLong("timestamp"),
                        isOfflineEngine = obj.optBoolean("isOfflineEngine", false),
                        isErrorFallback = obj.optBoolean("isErrorFallback", false),
                        errorReason = obj.optString("errorReason").takeIf { it.isNotEmpty() },
                        originalQueryForRetry = obj.optString("originalQueryForRetry").takeIf { it.isNotEmpty() },
                        dataTimestampBadge = obj.optString("dataTimestampBadge").takeIf { it.isNotEmpty() },
                        attachedContextSummary = obj.optString("attachedContextSummary").takeIf { it.isNotEmpty() },
                        providerUsed = obj.optString("providerUsed").ifEmpty { "Gemini 3.8 Flash" }
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveMessages(conversationId: String, messages: List<NexusChatMessage>) {
        val arr = JSONArray()
        for (msg in messages.takeLast(50)) {
            val obj = JSONObject().apply {
                put("id", msg.id)
                put("sender", msg.sender.name)
                put("text", msg.text)
                put("timestamp", msg.timestamp)
                put("isOfflineEngine", msg.isOfflineEngine)
                put("isErrorFallback", msg.isErrorFallback)
                put("errorReason", msg.errorReason ?: "")
                put("originalQueryForRetry", msg.originalQueryForRetry ?: "")
                put("dataTimestampBadge", msg.dataTimestampBadge ?: "")
                put("attachedContextSummary", msg.attachedContextSummary ?: "")
                put("providerUsed", msg.providerUsed)
            }
            arr.put(obj)
        }
        sharedPrefs?.edit()?.putString("messages_$conversationId", arr.toString())?.apply()
    }

    fun clearAllHistory() {
        sharedPrefs?.edit()?.clear()?.apply()
    }
}
