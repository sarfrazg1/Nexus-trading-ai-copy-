package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.data.learning.LearningLanguageMode
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
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
}

/**
 * Abstraction over generative AI inference so Firebase AI Logic (Gemini 3.8 Flash)
 * is used in production and deterministic/faked clients can be tested on JVM.
 */
interface GenerativeAiClient {
    fun isConfigured(): Boolean
    fun streamChat(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String>
}

class FirebaseGenerativeAiClient : GenerativeAiClient {
    private val probeModel by lazy {
        try {
            Firebase.ai.generativeModel(NEXUS_GEMINI_MODEL_NAME)
        } catch (e: Exception) {
            Log.e(TAG, "Firebase AI Logic probe initialization failed", e)
            null
        }
    }

    override fun isConfigured(): Boolean = probeModel != null

    override fun streamChat(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String> = flow {
        val model = Firebase.ai.generativeModel(
            modelName = NEXUS_GEMINI_MODEL_NAME,
            generationConfig = generationConfig {
                temperature = 0.45f
                topP = 0.9f
                maxOutputTokens = 2048
            },
            systemInstruction = content { text(systemInstruction) }
        )

        val sdkHistory: List<Content> = history.map { (role, text) ->
            content(role) { text(text) }
        }

        val responseFlow = if (sdkHistory.isNotEmpty()) {
            model.startChat(history = sdkHistory).sendMessageStream(userMessage)
        } else {
            model.generateContentStream(userMessage)
        }

        responseFlow.collect { chunk ->
            val chunkText = chunk.text.orEmpty()
            if (chunkText.isNotEmpty()) {
                emit(chunkText)
            }
        }
    }
}

/**
 * Manages Online Generative AI via Firebase AI Logic (free-tier Gemini 3.8 Flash)
 * with multi-turn conversation memory, real-time market grounding, categorized error handling,
 * retry support, and transparent fallback to OfflineTradingAnalysisEngine.
 */
class NexusAiRepository(
    private val context: Context? = null,
    private val aiClient: GenerativeAiClient = FirebaseGenerativeAiClient()
) {
    private val sharedPrefs = context?.getSharedPreferences("nexus_ai_conversations", Context.MODE_PRIVATE)

    fun isOnlineAiAvailable(): Boolean = aiClient.isConfigured()

    fun generateResponseStream(
        userPrompt: String,
        contextSnapshot: MarketContextSnapshot,
        languageMode: LearningLanguageMode,
        recentHistory: List<NexusChatMessage> = emptyList()
    ): Flow<NexusAiStreamResult> = flow {
        val contextSummary = "${contextSnapshot.assetName} (${contextSnapshot.symbol}) @ $${contextSnapshot.currentPrice}"
        val dataBadge = "${contextSnapshot.dataStatus} • ${contextSnapshot.lastUpdatedFormatted}"
        val followUps = generateFollowUps(languageMode, contextSnapshot, userPrompt)
        val messageId = UUID.randomUUID().toString()
        var onlineErrorReason: String? = null

        if (aiClient.isConfigured()) {
            val accumulatedText = StringBuilder()
            try {
                val systemInstruction = buildSystemInstruction(languageMode)
                val historyPairs = buildConversationHistory(recentHistory, userPrompt)
                val groundedTurnPrompt = buildGroundedTurnPrompt(userPrompt, contextSnapshot, languageMode)

                withTimeout(AI_TIMEOUT_MS) {
                    aiClient.streamChat(
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
                                        attachedContextSummary = contextSummary
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
                                suggestedFollowUps = followUps
                            )
                        )
                    )
                    return@flow
                } else {
                    onlineErrorReason = "Gemini returned an empty response. Tap Retry."
                    Log.e(TAG, onlineErrorReason)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Gemini streaming timed out after 60s", e)
                onlineErrorReason = "Gemini AI timed out after 60 seconds. Tap Retry."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Firebase AI streaming failed", e)
                onlineErrorReason = classifyOnlineAiError(e)
            }
        } else {
            onlineErrorReason = "Firebase AI Logic is not configured on this device."
            Log.e(TAG, onlineErrorReason)
        }

        val offlineResponse = OfflineTradingAnalysisEngine.generateAnalysis(
            query = userPrompt,
            context = contextSnapshot,
            language = languageMode,
            unavailableReason = onlineErrorReason,
            recentHistory = recentHistory
        )

        emit(
            NexusAiStreamResult.Completed(
                message = NexusChatMessage(
                    id = messageId,
                    conversationId = "",
                    sender = MessageSender.ASSISTANT,
                    text = offlineResponse,
                    isStreaming = false,
                    isOfflineEngine = true,
                    isErrorFallback = true,
                    errorReason = onlineErrorReason,
                    originalQueryForRetry = userPrompt.trim(),
                    dataTimestampBadge = dataBadge,
                    attachedContextSummary = contextSummary,
                    suggestedFollowUps = followUps
                ),
                onlineErrorReason = onlineErrorReason
            )
        )
    }

    suspend fun generateResponse(
        userPrompt: String,
        contextSnapshot: MarketContextSnapshot,
        languageMode: LearningLanguageMode,
        recentHistory: List<NexusChatMessage> = emptyList()
    ): NexusChatMessage {
        var finalMessage: NexusChatMessage? = null
        generateResponseStream(userPrompt, contextSnapshot, languageMode, recentHistory).collect { result ->
            when (result) {
                is NexusAiStreamResult.Chunk -> Unit
                is NexusAiStreamResult.Completed -> finalMessage = result.message
            }
        }
        return finalMessage ?: NexusChatMessage(
            conversationId = "",
            sender = MessageSender.ASSISTANT,
            text = OfflineTradingAnalysisEngine.generateAnalysis(userPrompt, contextSnapshot, languageMode),
            isOfflineEngine = true,
            isErrorFallback = true,
            originalQueryForRetry = userPrompt.trim()
        )
    }

    private fun buildConversationHistory(
        recentHistory: List<NexusChatMessage>,
        currentUserPrompt: String
    ): List<Pair<String, String>> {
        val trimmedCurrent = currentUserPrompt.trim()
        val filtered = recentHistory.filter { it.text.isNotBlank() && !it.isStreaming }
        // Exclude the just-appended current user prompt if it's at the tail
        val withoutCurrent = if (filtered.lastOrNull()?.sender == MessageSender.USER &&
            filtered.lastOrNull()?.text?.trim() == trimmedCurrent
        ) {
            filtered.dropLast(1)
        } else {
            filtered
        }

        // Keep up to 16 recent turns, skipping raw offline fallback error blocks so Gemini stays conversational
        return withoutCurrent
            .filter { !it.isErrorFallback }
            .takeLast(16)
            .map { msg ->
                val role = if (msg.sender == MessageSender.USER) "user" else "model"
                role to msg.text
            }
    }

    internal fun buildSystemInstruction(lang: LearningLanguageMode): String {
        val langInstruction = when (lang) {
            LearningLanguageMode.ENGLISH ->
                "Respond in clear, natural, helpful English. Use beginner-friendly explanations when the user asks educational questions, and institutional precision for market analysis."
            LearningLanguageMode.ROMAN_URDU ->
                "Respond in natural, fluent Roman Urdu (conversational Pakistani style). Keep technical terms like RSI, BOS, Stop Loss, CPI, PPI, Open Interest clear and easy to understand."
            LearningLanguageMode.STANDARD_URDU ->
                "Respond in fluent, natural Pakistani Urdu in URDU SCRIPT (اردو رسم الخط). Keep technical terms clear alongside Urdu explanations."
            LearningLanguageMode.BILINGUAL ->
                "Provide a clear, natural Bilingual response: concise English analysis/explanation followed by clear Urdu script (اردو) summary."
        }

        return """
            You are NEXUS AI, a genuine conversational trading analyst and educator inside NEXUS TRADING AI.
            You answer ANY free-form user question directly and naturally — never repeat canned scripts or force unrelated templates.

            CORE CAPABILITIES:
            - Crypto, Commodities (Gold/Silver/Oil/Gas), Forex/Macro (CPI, PPI, Fed/FOMC, NFP, DXY, Treasury Yields).
            - Derivatives & Market Mechanics (Funding Rates, Open Interest, Liquidations, Long/Short Ratios).
            - Smart Money Concepts (BOS, CHoCH, Order Blocks, FVGs, Liquidity Sweeps) & 22 Technical Indicators.
            - Multi-turn conversation memory: understand follow-up questions ("why?", "what about ETH?", "where is invalidation?", "explain simply") using the chat history.

            LANGUAGE POLICY:
            - Default language setting: $langInstruction
            - If the user writes explicitly in another language or asks you to reply in English/Roman Urdu/Urdu, honor their explicit request naturally.

            TRUTHFULNESS & DATA GROUNDING (CRITICAL):
            1. For market/price/setup questions, use ONLY the real data in the attached VERIFIED MARKET CONTEXT (prices, 24h changes, RSI, EMAs, SMC levels, Funding Rate, Open Interest, Watchlist setups, and News).
            2. ALWAYS mention the data freshness/timestamp (and disclose clearly if live data, derivatives, or live news are STALE or UNAVAILABLE).
            3. NEVER invent prices, indicator values, whale transactions, or news catalysts that are not present in the context. If live news shows no clear catalyst for a move, state that honestly and explain what technical/derivatives/broad-market data shows instead.
            4. For trade or buying questions: NEVER guarantee profits and NEVER force a BUY or SELL call. Always provide balanced Bullish and Bearish scenarios, possible entry zones, invalidation/Stop Loss levels, and key risks (1-2% capital risk).
            5. For general or educational questions (e.g., "What is PPI?", "How do funding rates work?"), answer naturally and clearly even if no trade setup is active.
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

    private fun classifyOnlineAiError(e: Exception): String {
        val msg = (e.message ?: e.localizedMessage ?: "").lowercase()
        return when {
            msg.contains("429") || msg.contains("quota") || msg.contains("resource_exhausted") || msg.contains("rate limit") ->
                "Free-tier Gemini AI quota reached. Please wait briefly and tap Retry."
            msg.contains("403") || msg.contains("appcheck") || msg.contains("app check") || msg.contains("attestation") ->
                "App Check attestation rejected (HTTP 403). Ensure debug/Play integrity config is active."
            e is UnknownHostException || e is ConnectException || e is SocketTimeoutException ||
                msg.contains("unable to resolve host") || msg.contains("failed to connect") || msg.contains("network") ->
                "No internet connection to reach Gemini AI. Tap Retry once online."
            else ->
                "Online Gemini AI unavailable (${e.localizedMessage ?: e::class.java.simpleName})."
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
                    "What are the funding rate and open interest saying?",
                    "Which watchlist coin is holding up best?"
                )
                q.contains("buy") || q.contains("entry") -> listOf(
                    "Where is the safest invalidation / stop loss?",
                    "Compare 15m and 1h structure for ${ctx.symbol}",
                    "What bearish risks could invalidate this setup?"
                )
                else -> listOf(
                    "What are key support and resistance levels for ${ctx.symbol}?",
                    "Which watchlist coin has the strongest setup right now?",
                    "Explain funding rate and open interest impact"
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
        } catch (e: Exception) {
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
                        attachedContextSummary = obj.optString("attachedContextSummary").takeIf { it.isNotEmpty() }
                    )
                )
            }
            list
        } catch (e: Exception) {
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
            }
            arr.put(obj)
        }
        sharedPrefs?.edit()?.putString("messages_$conversationId", arr.toString())?.apply()
    }

    fun clearAllHistory() {
        sharedPrefs?.edit()?.clear()?.apply()
    }
}
