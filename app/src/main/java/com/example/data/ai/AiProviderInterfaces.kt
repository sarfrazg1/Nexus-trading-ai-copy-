package com.example.data.ai

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.providers.ProviderHttpSupport
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import com.google.firebase.appcheck.FirebaseAppCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

enum class AiProviderType(
    val id: String,
    val displayName: String,
    val badgeLabel: String
) {
    GEMINI_FIREBASE("gemini_firebase", "Firebase AI Logic (Gemini 3.8 Flash)", "Gemini 3.8 Flash"),
    DEEPSEEK("deepseek", "DeepSeek Chat API", "DeepSeek Chat"),
    OFFLINE_RULE_BASED("offline", "NEXUS Offline Rule-Based Analyzer", "Limited Offline Rule-Based")
}

enum class AiProviderAvailability(val label: String) {
    READY("Ready"),
    COOLDOWN_RATE_LIMIT("Rate Limited / Cooldown"),
    DISABLED_PAYMENT_REQUIRED("Disabled (Payment Required — Zero Cost Policy)"),
    UNCONFIGURED("Not Configured"),
    ERROR("Runtime Error")
}

data class AiProviderStatus(
    val provider: AiProviderType,
    val availability: AiProviderAvailability,
    val detail: String,
    val cooldownUntilMillis: Long? = null
)

class AiProviderException(
    val provider: AiProviderType,
    val httpCode: Int? = null,
    val isPaymentRequired: Boolean = false,
    val isQuotaExhausted: Boolean = false,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

interface OnlineAiProvider {
    val providerType: AiProviderType
    fun isConfigured(): Boolean
    fun getStatus(): AiProviderStatus
    fun streamChat(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String>
}

/**
 * Legacy compatibility alias + Firebase AI Logic implementation.
 * Fixes runtime failure & timeout root causes cleanly:
 * 1. Never evaluates Firebase.ai.generativeModel(...) eagerly during ViewModel/class construction.
 * 2. Sanitizes multi-turn chat history to strictly alternating ("user", "model") pairs via
 *    FirebaseChatHistorySanitizer before calling startChat(history).
 * 3. Configures ThinkingLevel.LOW + bounded RequestOptions + Dispatchers.IO to eliminate 60s
 *    thinking/main-thread stalls, with stream-to-unary recovery if 0 text chunks arrive within 26s.
 * 4. Preserves CancellationException and surfaces real runtime errors transparently.
 */
interface GenerativeAiClient {
    fun isConfigured(): Boolean
    fun streamChat(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String>
}

object FirebaseChatHistorySanitizer {
    /**
     * Converts raw history pairs into strictly alternating ("user", "model") pairs
     * ending with "model" so Firebase startChat(history) + sendMessageStream(nextUserMsg)
     * never fails with role-alternation 400 errors.
     */
    fun sanitize(rawPairs: List<Pair<String, String>>, maxMessages: Int = 16): List<Pair<String, String>> {
        val paired = mutableListOf<Pair<String, String>>()
        var pendingUser: String? = null

        for ((role, text) in rawPairs) {
            val cleanText = text.trim()
            if (cleanText.isBlank()) continue
            when (role.lowercase()) {
                "user" -> {
                    pendingUser = cleanText
                }
                "model", "assistant" -> {
                    if (pendingUser != null) {
                        paired.add("user" to pendingUser)
                        paired.add("model" to cleanText)
                        pendingUser = null
                    }
                }
            }
        }

        val cappedCount = (maxMessages / 2) * 2
        return if (paired.size > cappedCount) {
            paired.takeLast(cappedCount)
        } else {
            paired
        }
    }
}

private class StreamFirstChunkStallException : CancellationException("No text chunk received before stream stall threshold")

class FirebaseGenerativeAiClient(
    private val context: Context? = null
) : GenerativeAiClient, OnlineAiProvider {

    override val providerType: AiProviderType = AiProviderType.GEMINI_FIREBASE

    private val lastRuntimeError = AtomicReference<String?>(null)

    override fun isConfigured(): Boolean {
        return try {
            if (context != null) {
                FirebaseApp.getApps(context).isNotEmpty()
            } else {
                FirebaseApp.getInstance() != null
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun getStatus(): AiProviderStatus {
        val err = lastRuntimeError.get()
        return when {
            !isConfigured() -> AiProviderStatus(
                provider = AiProviderType.GEMINI_FIREBASE,
                availability = AiProviderAvailability.UNCONFIGURED,
                detail = "FirebaseApp not initialized in current runtime context."
            )
            err != null -> AiProviderStatus(
                provider = AiProviderType.GEMINI_FIREBASE,
                availability = AiProviderAvailability.ERROR,
                detail = err
            )
            else -> AiProviderStatus(
                provider = AiProviderType.GEMINI_FIREBASE,
                availability = AiProviderAvailability.READY,
                detail = "Firebase AI Logic ($NEXUS_GEMINI_MODEL_NAME) ready via App Check"
            )
        }
    }

    private suspend fun prewarmAppCheckToken(): String {
        return try {
            val appCheck = FirebaseAppCheck.getInstance()
            val result = withTimeoutOrNull(APP_CHECK_PREFLIGHT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    appCheck.getAppCheckToken(false)
                        .addOnSuccessListener { token ->
                            if (cont.isActive) {
                                cont.resume("ok(exp=${token.expireTimeMillis})")
                            }
                        }
                        .addOnFailureListener { ex ->
                            if (cont.isActive) {
                                cont.resume("err(${ex.localizedMessage ?: ex::class.java.simpleName})")
                            }
                        }
                }
            }
            result ?: "timeout(${APP_CHECK_PREFLIGHT_TIMEOUT_MS}ms)"
        } catch (e: Exception) {
            "unavailable(${e.localizedMessage ?: e::class.java.simpleName})"
        }
    }

    override fun streamChat(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String> = flow {
        if (!isConfigured()) {
            val msg = "FirebaseApp is not initialized on this device/runtime."
            lastRuntimeError.set(msg)
            throw AiProviderException(AiProviderType.GEMINI_FIREBASE, message = msg)
        }

        val overallStartMs = System.currentTimeMillis()
        val sanitizedHistory = FirebaseChatHistorySanitizer.sanitize(history, maxMessages = 16)
            .map { (role, text) ->
                val compact = if (text.length > MAX_HISTORY_CHARS_PER_TURN) {
                    text.take(MAX_HISTORY_CHARS_PER_TURN) + "…"
                } else {
                    text
                }
                role to compact
            }

        val appCheckState = prewarmAppCheckToken()
        val afterAppCheckMs = System.currentTimeMillis() - overallStartMs
        Log.d(
            TAG,
            "Starting Gemini streamChat: appCheck=$appCheckState (${afterAppCheckMs}ms), sysLen=${systemInstruction.length}, promptLen=${userMessage.length}, historyTurns=${sanitizedHistory.size}"
        )

        val model = try {
            Firebase.ai.generativeModel(
                modelName = NEXUS_GEMINI_MODEL_NAME,
                generationConfig = generationConfig {
                    temperature = 0.45f
                    topP = 0.9f
                    maxOutputTokens = 2800
                    thinkingConfig = thinkingConfig {
                        thinkingLevel = ThinkingLevel.LOW
                    }
                },
                systemInstruction = content { text(systemInstruction) },
                requestOptions = RequestOptions(SDK_REQUEST_TIMEOUT_MS)
            )
        } catch (e: Exception) {
            val msg = "Firebase.ai.generativeModel init error: ${e.localizedMessage ?: e::class.java.simpleName}"
            lastRuntimeError.set(msg)
            Log.e(TAG, msg, e)
            throw AiProviderException(AiProviderType.GEMINI_FIREBASE, message = msg, cause = e)
        }

        val sdkHistory: List<Content> = sanitizedHistory.map { (role, text) ->
            content(role) { text(text) }
        }

        var receivedAny = false
        try {
            val streamStalled = try {
                coroutineScope {
                    val chunkChannel = Channel<String>(Channel.UNLIMITED)
                    val producerJob = launch {
                        try {
                            val responseFlow = if (sdkHistory.isNotEmpty()) {
                                model.startChat(history = sdkHistory).sendMessageStream(userMessage)
                            } else {
                                model.generateContentStream(userMessage)
                            }
                            responseFlow.collect { chunk ->
                                val chunkText = chunk.text.orEmpty()
                                if (chunkText.isNotEmpty()) {
                                    chunkChannel.send(chunkText)
                                }
                            }
                            chunkChannel.close()
                        } catch (t: Throwable) {
                            chunkChannel.close(t)
                        }
                    }

                    val watchdogJob = launch {
                        delay(STREAM_FIRST_CHUNK_TIMEOUT_MS)
                        if (!receivedAny && producerJob.isActive) {
                            Log.w(
                                TAG,
                                "Stream produced 0 text chunks after ${STREAM_FIRST_CHUNK_TIMEOUT_MS}ms (appCheck=$appCheckState); switching to unary fallback."
                            )
                            chunkChannel.close(StreamFirstChunkStallException())
                            producerJob.cancel(CancellationException("Stream first-chunk stall"))
                        }
                    }

                    try {
                        for (chunkText in chunkChannel) {
                            if (!receivedAny) {
                                receivedAny = true
                                watchdogJob.cancel()
                                val ttftMs = System.currentTimeMillis() - overallStartMs
                                Log.d(TAG, "First Gemini text chunk received at T+${ttftMs}ms")
                            }
                            emit(chunkText)
                        }
                        false
                    } finally {
                        watchdogJob.cancel()
                        if (producerJob.isActive) {
                            producerJob.cancel()
                        }
                    }
                }
            } catch (_: StreamFirstChunkStallException) {
                true
            }

            if (streamStalled && !receivedAny) {
                Log.w(TAG, "Executing unary Gemini fallback within ${UNARY_FALLBACK_TIMEOUT_MS}ms")
                val unaryResponse = withTimeout(UNARY_FALLBACK_TIMEOUT_MS) {
                    if (sdkHistory.isNotEmpty()) {
                        model.startChat(history = sdkHistory).sendMessage(userMessage)
                    } else {
                        model.generateContent(userMessage)
                    }
                }
                val unaryText = unaryResponse.text.orEmpty()
                if (unaryText.isNotEmpty()) {
                    receivedAny = true
                    Log.d(TAG, "Unary fallback succeeded at T+${System.currentTimeMillis() - overallStartMs}ms (len=${unaryText.length})")
                    emit(unaryText)
                } else {
                    Log.w(TAG, "Unary fallback completed with empty text at T+${System.currentTimeMillis() - overallStartMs}ms")
                }
            }

            if (receivedAny) {
                lastRuntimeError.set(null)
                Log.d(TAG, "Gemini response completed cleanly in ${System.currentTimeMillis() - overallStartMs}ms")
            }
        } catch (e: TimeoutCancellationException) {
            val elapsedMs = System.currentTimeMillis() - overallStartMs
            val detail = "Timed out after ${elapsedMs / 1000}s (appCheck=$appCheckState)"
            lastRuntimeError.set(detail)
            Log.w(TAG, "Firebase AI streamChat timed out ($detail)")
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = e.localizedMessage ?: e::class.java.simpleName
            lastRuntimeError.set(detail)
            Log.e(TAG, "Firebase AI streamChat failed at T+${System.currentTimeMillis() - overallStartMs}ms (appCheck=$appCheckState): $detail", e)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val TAG = "FirebaseGeminiProvider"
        private const val APP_CHECK_PREFLIGHT_TIMEOUT_MS = 6_000L
        private const val STREAM_FIRST_CHUNK_TIMEOUT_MS = 26_000L
        private const val UNARY_FALLBACK_TIMEOUT_MS = 26_000L
        private const val SDK_REQUEST_TIMEOUT_MS = 52_000L
        private const val MAX_HISTORY_CHARS_PER_TURN = 900
    }
}

/**
 * Optional DeepSeek AI Provider Interface using official DeepSeek OpenAI-compatible Chat Completions endpoint.
 * Zero-cost rule:
 * - Key is strictly optional and read from BuildConfig.DEEPSEEK_API_KEY (AI Studio Secrets / .env).
 * - Automatically disables itself if HTTP 402 (Insufficient balance / payment required) is returned.
 * - Enforces rate-limit cooldown on HTTP 429.
 */
class DeepSeekAiProvider(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKeyRaw: String = BuildConfig.DEEPSEEK_API_KEY,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) : OnlineAiProvider {

    override val providerType: AiProviderType = AiProviderType.DEEPSEEK

    private val availabilityRef = AtomicReference<AiProviderAvailability?>(null)
    private val statusDetailRef = AtomicReference<String?>(null)
    private val cooldownUntilRef = AtomicLong(0L)

    private fun resolveApiKey(): String? =
        ProviderHttpSupport.resolveOptionalSecret(apiKeyRaw, KEY_PLACEHOLDER)

    override fun isConfigured(): Boolean {
        if (resolveApiKey() == null) return false
        val state = availabilityRef.get()
        if (state == AiProviderAvailability.DISABLED_PAYMENT_REQUIRED) return false
        val until = cooldownUntilRef.get()
        if (until > 0L && clockMillis() < until) return false
        return true
    }

    override fun getStatus(): AiProviderStatus {
        val key = resolveApiKey()
        if (key == null) {
            return AiProviderStatus(
                provider = AiProviderType.DEEPSEEK,
                availability = AiProviderAvailability.UNCONFIGURED,
                detail = "Optional DEEPSEEK_API_KEY not configured in AI Studio Secrets."
            )
        }
        val state = availabilityRef.get()
        if (state == AiProviderAvailability.DISABLED_PAYMENT_REQUIRED) {
            return AiProviderStatus(
                provider = AiProviderType.DEEPSEEK,
                availability = AiProviderAvailability.DISABLED_PAYMENT_REQUIRED,
                detail = statusDetailRef.get() ?: "Disabled: payment or balance required (zero-cost policy)."
            )
        }
        val until = cooldownUntilRef.get()
        if (until > 0L && clockMillis() < until) {
            return AiProviderStatus(
                provider = AiProviderType.DEEPSEEK,
                availability = AiProviderAvailability.COOLDOWN_RATE_LIMIT,
                detail = statusDetailRef.get() ?: "DeepSeek free quota/rate limit cooldown active.",
                cooldownUntilMillis = until
            )
        }
        return AiProviderStatus(
            provider = AiProviderType.DEEPSEEK,
            availability = state ?: AiProviderAvailability.READY,
            detail = statusDetailRef.get() ?: "DeepSeek Chat configured ($MODEL_NAME)"
        )
    }

    override fun streamChat(
        systemInstruction: String,
        history: List<Pair<String, String>>,
        userMessage: String
    ): Flow<String> = flow {
        val apiKey = resolveApiKey() ?: throw AiProviderException(
            provider = AiProviderType.DEEPSEEK,
            message = "DeepSeek API key is not configured. Add DEEPSEEK_API_KEY in AI Studio Secrets or use Gemini."
        )

        if (availabilityRef.get() == AiProviderAvailability.DISABLED_PAYMENT_REQUIRED) {
            throw AiProviderException(
                provider = AiProviderType.DEEPSEEK,
                httpCode = 402,
                isPaymentRequired = true,
                message = statusDetailRef.get() ?: "DeepSeek disabled (HTTP 402 payment required — zero-cost policy)."
            )
        }

        val until = cooldownUntilRef.get()
        if (until > 0L && clockMillis() < until) {
            throw AiProviderException(
                provider = AiProviderType.DEEPSEEK,
                httpCode = 429,
                isQuotaExhausted = true,
                message = "DeepSeek rate-limit cooldown active for ${(until - clockMillis()) / 1000}s."
            )
        }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemInstruction)
            })
            for ((role, text) in FirebaseChatHistorySanitizer.sanitize(history, maxMessages = 16)) {
                val mappedRole = if (role == "model") "assistant" else "user"
                put(JSONObject().apply {
                    put("role", mappedRole)
                    put("content", text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", userMessage)
            })
        }

        val payload = JSONObject().apply {
            put("model", MODEL_NAME)
            put("messages", messages)
            put("temperature", 0.4)
            put("max_tokens", 2400)
            put("stream", true)
        }

        val request = Request.Builder()
            .url("$baseUrl/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream, application/json")
            .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        var attempt = 0
        var succeeded = false
        while (attempt < 2 && !succeeded) {
            attempt++
            try {
                client.newCall(request).execute().use { response ->
                    if (response.code == 402) {
                        val msg = "DeepSeek returned HTTP 402 (Insufficient balance / payment required) — provider disabled automatically (zero-cost policy)."
                        availabilityRef.set(AiProviderAvailability.DISABLED_PAYMENT_REQUIRED)
                        statusDetailRef.set(msg)
                        throw AiProviderException(
                            provider = AiProviderType.DEEPSEEK,
                            httpCode = 402,
                            isPaymentRequired = true,
                            message = msg
                        )
                    }
                    if (response.code == 429) {
                        val retryAfterSec = response.header("Retry-After")?.toLongOrNull() ?: 60L
                        val untilMs = clockMillis() + retryAfterSec * 1000L
                        cooldownUntilRef.set(untilMs)
                        val msg = "DeepSeek rate limit reached (HTTP 429). Paused for ${retryAfterSec}s."
                        availabilityRef.set(AiProviderAvailability.COOLDOWN_RATE_LIMIT)
                        statusDetailRef.set(msg)
                        throw AiProviderException(
                            provider = AiProviderType.DEEPSEEK,
                            httpCode = 429,
                            isQuotaExhausted = true,
                            message = msg
                        )
                    }
                    if (!response.isSuccessful) {
                        val bodyStr = response.body?.string().orEmpty()
                        if (response.code >= 500 && attempt < 2) {
                            delay(600L)
                            return@use
                        }
                        val errMsg = runCatching {
                            JSONObject(bodyStr).optJSONObject("error")?.optString("message")
                        }.getOrNull().orEmpty().ifBlank { "HTTP ${response.code}" }
                        availabilityRef.set(AiProviderAvailability.ERROR)
                        statusDetailRef.set("DeepSeek error: $errMsg")
                        throw AiProviderException(
                            provider = AiProviderType.DEEPSEEK,
                            httpCode = response.code,
                            message = "DeepSeek API failed ($errMsg)"
                        )
                    }

                    val contentType = response.header("Content-Type").orEmpty().lowercase()
                    val body = response.body ?: throw AiProviderException(
                        provider = AiProviderType.DEEPSEEK,
                        message = "DeepSeek returned an empty response body."
                    )

                    if (contentType.contains("text/event-stream")) {
                        val source = body.source()
                        var emittedAny = false
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            val trimmedLine = line.trim()
                            if (!trimmedLine.startsWith("data:")) continue
                            val dataPayload = trimmedLine.removePrefix("data:").trim()
                            if (dataPayload == "[DONE]") break
                            val deltaText = runCatching {
                                val choices = JSONObject(dataPayload).optJSONArray("choices")
                                val first = choices?.optJSONObject(0)
                                val deltaObj = first?.optJSONObject("delta")
                                val msgObj = first?.optJSONObject("message")
                                when {
                                    deltaObj != null && !deltaObj.isNull("content") -> deltaObj.optString("content", "")
                                    msgObj != null && !msgObj.isNull("content") -> msgObj.optString("content", "")
                                    else -> ""
                                }
                            }.getOrNull().orEmpty()
                            if (deltaText.isNotEmpty()) {
                                emittedAny = true
                                emit(deltaText)
                            }
                        }
                        if (!emittedAny) {
                            throw AiProviderException(
                                provider = AiProviderType.DEEPSEEK,
                                message = "DeepSeek SSE stream completed with empty response."
                            )
                        }
                        availabilityRef.set(AiProviderAvailability.READY)
                        statusDetailRef.set("DeepSeek Chat active ($MODEL_NAME)")
                        succeeded = true
                    } else {
                        val bodyStr = body.string()
                        if (bodyStr.trimStart().startsWith("data:")) {
                            var emittedAny = false
                            for (line in bodyStr.lineSequence()) {
                                val trimmedLine = line.trim()
                                if (!trimmedLine.startsWith("data:")) continue
                                val dataPayload = trimmedLine.removePrefix("data:").trim()
                                if (dataPayload == "[DONE]") break
                                val deltaText = runCatching {
                                    val choices = JSONObject(dataPayload).optJSONArray("choices")
                                    val first = choices?.optJSONObject(0)
                                    val deltaObj = first?.optJSONObject("delta")
                                    val msgObj = first?.optJSONObject("message")
                                    when {
                                        deltaObj != null && !deltaObj.isNull("content") -> deltaObj.optString("content", "")
                                        msgObj != null && !msgObj.isNull("content") -> msgObj.optString("content", "")
                                        else -> ""
                                    }
                                }.getOrNull().orEmpty()
                                if (deltaText.isNotEmpty()) {
                                    emittedAny = true
                                    emit(deltaText)
                                }
                            }
                            if (!emittedAny) {
                                throw AiProviderException(
                                    provider = AiProviderType.DEEPSEEK,
                                    message = "DeepSeek returned an empty stream response."
                                )
                            }
                        } else {
                            val contentText = runCatching {
                                JSONObject(bodyStr)
                                    .getJSONArray("choices")
                                    .getJSONObject(0)
                                    .getJSONObject("message")
                                    .getString("content")
                            }.getOrElse { ex ->
                                throw AiProviderException(
                                    provider = AiProviderType.DEEPSEEK,
                                    message = "DeepSeek response parse error: ${ex.message}",
                                    cause = ex
                                )
                            }
                            if (contentText.isBlank()) {
                                throw AiProviderException(
                                    provider = AiProviderType.DEEPSEEK,
                                    message = "DeepSeek returned an empty response."
                                )
                            }
                            emit(contentText.trim())
                        }
                        availabilityRef.set(AiProviderAvailability.READY)
                        statusDetailRef.set("DeepSeek Chat active ($MODEL_NAME)")
                        succeeded = true
                    }
                }
            } catch (e: IOException) {
                if (attempt >= 2) throw e
                delay(600L)
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val MODEL_NAME = "deepseek-chat"
        const val KEY_PLACEHOLDER = "OPTIONAL_DEEPSEEK_KEY"
    }
}
