package com.example.data.providers

import com.example.data.model.MarketDataStatus
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class TimedCacheEntry<T>(
    val value: T,
    val fetchedAtMillis: Long,
    val endpoint: String
)

data class HttpExecutionResult(
    val isSuccess: Boolean,
    val httpCode: Int,
    val body: String? = null,
    val retryAfterSeconds: Long? = null,
    val errorMessage: String? = null
)

/**
 * Shared HTTP executor with per-provider circuit breaking, TTL caching,
 * rate-limit header handling, exponential backoff, and graceful zero-cost disablement
 * on HTTP 402 (payment required) or exhausted HTTP 429 quotas.
 */
class ProviderHttpSupport(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() },
    private val sleeper: suspend (Long) -> Unit = { ms -> delay(ms) }
) {
    private val cooldownUntilMillis = ConcurrentHashMap<String, Long>()
    private val disabledReasons = ConcurrentHashMap<String, Pair<ProviderHealthStatus, String>>()

    fun getDisabledState(providerId: String): Pair<ProviderHealthStatus, String>? {
        val disabled = disabledReasons[providerId]
        if (disabled != null && disabled.first == ProviderHealthStatus.PAYMENT_REQUIRED_DISABLED) {
            return disabled
        }
        val until = cooldownUntilMillis[providerId] ?: return disabled
        return if (clockMillis() < until) {
            disabled ?: (ProviderHealthStatus.RATE_LIMITED to "Rate limit cooldown active")
        } else {
            cooldownUntilMillis.remove(providerId)
            disabledReasons.remove(providerId)
            null
        }
    }

    fun resetProviderState(providerId: String) {
        cooldownUntilMillis.remove(providerId)
        disabledReasons.remove(providerId)
    }

    suspend fun getWithCacheAndRetry(
        providerId: String,
        providerName: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        cacheEntry: TimedCacheEntry<String>? = null,
        cacheTtlMillis: Long = 30_000L,
        maxAttempts: Int = 2,
        baseBackoffMs: Long = 600L
    ): ProviderFetchOutcome<String> {
        val now = clockMillis()

        // 1. Fresh cache hit -> LIVE without spending quota
        if (cacheEntry != null && (now - cacheEntry.fetchedAtMillis) < cacheTtlMillis) {
            return ProviderFetchOutcome(
                data = cacheEntry.value,
                metadata = ProviderMetadata(
                    providerId = providerId,
                    providerName = providerName,
                    endpointUsed = cacheEntry.endpoint,
                    dataStatus = MarketDataStatus.LIVE,
                    health = ProviderHealthStatus.ACTIVE,
                    fetchedAtMillis = cacheEntry.fetchedAtMillis,
                    timestampFormatted = formatPktTimestamp(cacheEntry.fetchedAtMillis),
                    rateLimitNote = "Served from TTL cache (${cacheTtlMillis / 1000}s)",
                    fromCache = true
                )
            )
        }

        // 2. Check if provider is gracefully disabled or cooling down
        val disabled = getDisabledState(providerId)
        if (disabled != null) {
            return cachedOrUnavailableOutcome(
                providerId = providerId,
                providerName = providerName,
                endpoint = url,
                health = disabled.first,
                reason = disabled.second,
                cacheEntry = cacheEntry
            )
        }

        // 3. Execute with bounded retries & exponential backoff
        var lastCode = 0
        var lastError: String? = null
        var retryAfterHeader: Long? = null

        for (attempt in 0 until maxAttempts) {
            try {
                val reqBuilder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "NexusTradingAI/1.0 (Android)")
                headers.forEach { (k, v) ->
                    if (v.isNotBlank()) reqBuilder.header(k, v)
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    lastCode = response.code
                    retryAfterHeader = response.header("Retry-After")?.toLongOrNull()

                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            val fetchedAt = clockMillis()
                            return ProviderFetchOutcome(
                                data = body,
                                metadata = ProviderMetadata(
                                    providerId = providerId,
                                    providerName = providerName,
                                    endpointUsed = url,
                                    dataStatus = MarketDataStatus.LIVE,
                                    health = ProviderHealthStatus.ACTIVE,
                                    fetchedAtMillis = fetchedAt,
                                    timestampFormatted = formatPktTimestamp(fetchedAt),
                                    rateLimitNote = response.header("X-RateLimit-Remaining")?.let { "Remaining: $it" }
                                )
                            )
                        }
                        lastError = "Empty HTTP ${response.code} body"
                    } else if (response.code == 402) {
                        val reason = "HTTP 402 Payment Required — provider disabled automatically (zero-cost policy)"
                        disabledReasons[providerId] = ProviderHealthStatus.PAYMENT_REQUIRED_DISABLED to reason
                        return cachedOrUnavailableOutcome(
                            providerId = providerId,
                            providerName = providerName,
                            endpoint = url,
                            health = ProviderHealthStatus.PAYMENT_REQUIRED_DISABLED,
                            reason = reason,
                            cacheEntry = cacheEntry
                        )
                    } else if (response.code == 429 || response.code == 418) {
                        lastError = "HTTP ${response.code} Rate limit / quota reached"
                        val waitMs = ((retryAfterHeader ?: (1L shl attempt)) * 1000L).coerceAtMost(4_000L)
                        if (attempt < maxAttempts - 1) {
                            sleeper(waitMs)
                        }
                    } else {
                        lastError = "HTTP ${response.code}"
                        if (response.code >= 500 && attempt < maxAttempts - 1) {
                            sleeper(baseBackoffMs * (1L shl attempt))
                        } else {
                            return cachedOrUnavailableOutcome(
                                providerId = providerId,
                                providerName = providerName,
                                endpoint = url,
                                health = ProviderHealthStatus.UNAVAILABLE,
                                reason = lastError ?: "HTTP error",
                                cacheEntry = cacheEntry
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                lastError = e.message ?: "Network I/O error"
                if (attempt < maxAttempts - 1) {
                    sleeper(baseBackoffMs * (1L shl attempt))
                }
            } catch (e: Exception) {
                lastError = e.message ?: e::class.java.simpleName
                break
            }
        }

        if (lastCode == 429 || lastCode == 418) {
            val cooldownSec = (retryAfterHeader ?: 60L).coerceIn(15L, 600L)
            cooldownUntilMillis[providerId] = clockMillis() + cooldownSec * 1000L
            val health = if (cooldownSec >= 120L) {
                ProviderHealthStatus.QUOTA_EXHAUSTED_DISABLED
            } else {
                ProviderHealthStatus.RATE_LIMITED
            }
            val msg = "HTTP $lastCode — $providerName paused for ${cooldownSec}s"
            disabledReasons[providerId] = health to msg
            return cachedOrUnavailableOutcome(
                providerId = providerId,
                providerName = providerName,
                endpoint = url,
                health = health,
                reason = msg,
                cacheEntry = cacheEntry
            )
        }

        return cachedOrUnavailableOutcome(
            providerId = providerId,
            providerName = providerName,
            endpoint = url,
            health = ProviderHealthStatus.UNAVAILABLE,
            reason = lastError ?: "Request failed",
            cacheEntry = cacheEntry
        )
    }

    private fun cachedOrUnavailableOutcome(
        providerId: String,
        providerName: String,
        endpoint: String,
        health: ProviderHealthStatus,
        reason: String,
        cacheEntry: TimedCacheEntry<String>?
    ): ProviderFetchOutcome<String> {
        return if (cacheEntry != null) {
            ProviderFetchOutcome(
                data = cacheEntry.value,
                metadata = ProviderMetadata(
                    providerId = providerId,
                    providerName = providerName,
                    endpointUsed = cacheEntry.endpoint,
                    dataStatus = MarketDataStatus.STALE,
                    health = health,
                    fetchedAtMillis = cacheEntry.fetchedAtMillis,
                    timestampFormatted = formatPktTimestamp(cacheEntry.fetchedAtMillis),
                    rateLimitNote = reason,
                    errorMessage = reason,
                    fromCache = true
                )
            )
        } else {
            ProviderFetchOutcome(
                data = null,
                metadata = ProviderMetadata(
                    providerId = providerId,
                    providerName = providerName,
                    endpointUsed = endpoint,
                    dataStatus = MarketDataStatus.UNAVAILABLE,
                    health = health,
                    fetchedAtMillis = null,
                    timestampFormatted = "Unavailable",
                    rateLimitNote = reason,
                    errorMessage = reason,
                    fromCache = false
                )
            )
        }
    }

    companion object {
        fun resolveOptionalSecret(rawValue: String, placeholder: String): String? {
            val trimmed = rawValue.trim()
            if (trimmed.isBlank() || trimmed == placeholder || trimmed.startsWith("OPTIONAL_")) {
                return null
            }
            return trimmed
        }
    }
}
