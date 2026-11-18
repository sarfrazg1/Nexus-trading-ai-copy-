package com.example.data.subscription

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Only two subscription plans exist in NEXUS TRADING AI.
 * Matched 1:1 with firestore.rules validation.
 */
enum class SubscriptionPlan(
    val id: String,
    val displayName: String,
    val pricePkr: Int,
    val durationMonths: Int,
    val durationDays: Int,
    val durationLabel: String
) {
    MONTHLY(
        id = "MONTHLY",
        displayName = "MONTHLY",
        pricePkr = 500,
        durationMonths = 1,
        durationDays = 30,
        durationLabel = "1 Month"
    ),
    GOLDEN_ANNUAL(
        id = "GOLDEN_ANNUAL",
        displayName = "GOLDEN ANNUAL",
        pricePkr = 4000,
        durationMonths = 12,
        durationDays = 365,
        durationLabel = "12 Months"
    );

    val formattedPrice: String
        get() = "PKR %,d".format(Locale.US, pricePkr)

    companion object {
        fun fromId(raw: String?): SubscriptionPlan? =
            entries.firstOrNull { it.id.equals(raw?.trim(), ignoreCase = true) }
    }
}

enum class ActivationCodeStatus {
    UNUSED,
    ACTIVATED,
    REVOKED,
    EXPIRED;

    companion object {
        fun fromString(raw: String?): ActivationCodeStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNUSED
    }
}

enum class SubscriptionStatus {
    NONE,
    ACTIVE,
    EXPIRED,
    REVOKED;

    companion object {
        fun fromString(raw: String?): SubscriptionStatus =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: NONE
    }
}

@Keep
@IgnoreExtraProperties
data class ActivationCodeRecord(
    @DocumentId val id: String = "",
    val code: String = "",
    val planId: String = SubscriptionPlan.MONTHLY.id,
    val planName: String = SubscriptionPlan.MONTHLY.displayName,
    val pricePkr: Long = SubscriptionPlan.MONTHLY.pricePkr.toLong(),
    val durationMonths: Long = SubscriptionPlan.MONTHLY.durationMonths.toLong(),
    val durationDays: Long = SubscriptionPlan.MONTHLY.durationDays.toLong(),
    val status: String = ActivationCodeStatus.UNUSED.name,
    val note: String? = null,
    val createdByUid: String = "",
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null,
    val activatedByUid: String? = null,
    val activatedByEmail: String? = null,
    val activatedAt: Timestamp? = null,
    val expiresAt: Timestamp? = null
) {
    fun parsedPlan(): SubscriptionPlan? = SubscriptionPlan.fromId(planId)

    fun effectiveStatus(nowMs: Long = System.currentTimeMillis()): ActivationCodeStatus {
        val rawStatus = ActivationCodeStatus.fromString(status)
        if (rawStatus == ActivationCodeStatus.ACTIVATED) {
            val expMs = expiresAt?.toDate()?.time
            if (expMs != null && expMs <= nowMs) {
                return ActivationCodeStatus.EXPIRED
            }
        }
        return rawStatus
    }
}

@Keep
@IgnoreExtraProperties
data class UserSubscriptionRecord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String = "",
    val displayName: String = "",
    val role: String = "USER",
    val subscriptionStatus: String = SubscriptionStatus.NONE.name,
    val accountDisabled: Boolean = false,
    val planId: String? = null,
    val planName: String? = null,
    val pricePkr: Long? = null,
    val activeCode: String? = null,
    val activatedAt: Timestamp? = null,
    val expiresAt: Timestamp? = null,
    val lastSeenAt: Timestamp? = null,
    val createdAt: Timestamp? = null,
    val updatedAt: Timestamp? = null
) {
    fun serverOrLocalNowMs(fallbackMs: Long = System.currentTimeMillis()): Long {
        val serverSeenMs = lastSeenAt?.toDate()?.time ?: 0L
        return maxOf(serverSeenMs, fallbackMs)
    }

    fun effectiveStatus(nowMs: Long): SubscriptionStatus {
        if (accountDisabled) return SubscriptionStatus.REVOKED
        val raw = SubscriptionStatus.fromString(subscriptionStatus)
        if (raw == SubscriptionStatus.ACTIVE) {
            val expMs = expiresAt?.toDate()?.time ?: return SubscriptionStatus.NONE
            if (expMs <= nowMs) return SubscriptionStatus.EXPIRED
        }
        return raw
    }

    fun hasActiveAccess(nowMs: Long): Boolean {
        if (accountDisabled) return false
        return effectiveStatus(nowMs) == SubscriptionStatus.ACTIVE
    }

    fun parsedPlan(): SubscriptionPlan? = SubscriptionPlan.fromId(planId)
}

@Keep
@IgnoreExtraProperties
data class SupportConfig(
    @DocumentId val id: String = "",
    val whatsappNumber: String = "",
    val supportMessage: String = "",
    val updatedByUid: String = "",
    val updatedAt: Timestamp? = null
) {
    val hasConfiguredWhatsApp: Boolean
        get() = whatsappNumber.trim().matches(Regex("^\\+?[0-9]{7,15}$"))
}

sealed interface AccessGateState {
    data object Loading : AccessGateState

    data class NeedsActivation(
        val uid: String,
        val email: String,
        val displayName: String,
        val userRecord: UserSubscriptionRecord?,
        val effectiveStatus: SubscriptionStatus,
        val isDisabled: Boolean,
        val isAdmin: Boolean,
        val supportConfig: SupportConfig,
        val serverNowMs: Long
    ) : AccessGateState

    data class Authorized(
        val uid: String,
        val email: String,
        val displayName: String,
        val userRecord: UserSubscriptionRecord?,
        val isAdmin: Boolean,
        val supportConfig: SupportConfig,
        val serverNowMs: Long
    ) : AccessGateState

    data class Error(
        val message: String
    ) : AccessGateState
}

object SubscriptionValidation {
    val CODE_REGEX = Regex("^[A-Z0-9-]{8,40}$")
    val PHONE_REGEX = Regex("^\\+?[0-9]{7,15}$")

    fun normalizeCode(raw: String): String =
        raw.trim().uppercase(Locale.US).replace(" ", "")

    fun isValidCodeFormat(normalizedCode: String): Boolean =
        CODE_REGEX.matches(normalizedCode)

    fun normalizePhoneNumber(raw: String): String {
        val trimmed = raw.trim().replace(" ", "").replace("-", "")
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("+")) {
            "+" + trimmed.drop(1).filter { it.isDigit() }
        } else {
            trimmed.filter { it.isDigit() }
        }
    }

    fun isValidOptionalPhone(normalized: String): Boolean =
        normalized.isEmpty() || PHONE_REGEX.matches(normalized)

    fun calculateExpiryMillis(startMillis: Long, plan: SubscriptionPlan): Long {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.timeInMillis = startMillis
        when (plan) {
            SubscriptionPlan.MONTHLY -> cal.add(Calendar.MONTH, 1)
            SubscriptionPlan.GOLDEN_ANNUAL -> cal.add(Calendar.YEAR, 1)
        }
        return cal.timeInMillis
    }
}
