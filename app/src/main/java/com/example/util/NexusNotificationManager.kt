package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.alerts.AlertHistoryEntry
import com.example.data.model.AlertType
import com.example.data.model.MarketNews
import com.example.data.model.NavigationTab
import com.example.data.model.NewsImpactLevel
import com.example.data.scanner.FuturesDirection
import com.example.data.scanner.HighRewardOpportunity
import com.example.data.scanner.OpportunityPriorityTier
import com.example.data.scanner.OpportunityStatus
import com.example.data.signal.AiTradingSignal
import com.example.data.signal.SignalDecision
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class InAppNotificationEvent(
    val title: String,
    val message: String,
    val type: AlertType,
    val timestamp: Long = System.currentTimeMillis(),
    val timestampPkt: String = TimeZoneUtils.formatKarachiTimestamp(timestamp),
    val eventId: String? = null,
    val symbol: String? = null,
    val signalId: String? = null
)

class NexusNotificationManager(context: Context? = null) {

    private var appContext: Context? = context?.applicationContext ?: context

    private val prefs: SharedPreferences?
        get() = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val recentlyTriggered = mutableMapOf<String, Long>()
    private val deliveredEventIdsOrder = ArrayDeque<String>()
    private val deliveredEventIdsSet = mutableSetOf<String>()

    private val _inAppEvents = MutableSharedFlow<InAppNotificationEvent>(extraBufferCapacity = 16)
    val inAppEvents: SharedFlow<InAppNotificationEvent> = _inAppEvents.asSharedFlow()

    private val _alertHistory = MutableStateFlow<List<AlertHistoryEntry>>(emptyList())
    val alertHistory: StateFlow<List<AlertHistoryEntry>> = _alertHistory.asStateFlow()

    init {
        createNotificationChannel()
        loadDeliveredEventIds()
        loadAlertHistory()
    }

    fun bindContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext ?: context
            createNotificationChannel()
            loadDeliveredEventIds()
            loadAlertHistory()
        }
    }

    private fun createNotificationChannel() {
        val ctx = appContext ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager: NotificationManager? =
                ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            val soundUri = runCatching {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }.getOrNull()
            val audioAttrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val name = "Nexus Trading Signals & Alerts"
            val descriptionText = "Real-time market price triggers, AI trading signals, and macro news catalysts"
            val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = descriptionText
                enableVibration(true)
                if (soundUri != null) {
                    setSound(soundUri, audioAttrs)
                }
            }
            notificationManager?.createNotificationChannel(channel)

            val highRewardChannel = NotificationChannel(
                CHANNEL_ID_HIGH_REWARD,
                "NEXUS 1:5 High Reward Opportunities",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Highest-priority sound & vibration alerts for confirmed >=1:5 net R:R setups (PKT)"
                enableVibration(true)
                enableLights(true)
                if (soundUri != null) {
                    setSound(soundUri, audioAttrs)
                }
            }
            notificationManager?.createNotificationChannel(highRewardChannel)

            val qualified14Channel = NotificationChannel(
                CHANNEL_ID_QUALIFIED_1_4,
                "NEXUS 1:4+ Qualified Opportunities",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Qualified notifications for confirmed 1:4 to <1:5 net R:R setups (PKT)"
                enableVibration(true)
                if (soundUri != null) {
                    setSound(soundUri, audioAttrs)
                }
            }
            notificationManager?.createNotificationChannel(qualified14Channel)
        }
    }

    // ==========================================
    // PREFERENCES FOR THE ALERT TYPES & FCM TOKEN
    // ==========================================
    fun isMasterAlertsEnabled(): Boolean = prefs?.getBoolean(KEY_MASTER_ALERTS, true) ?: true
    fun setMasterAlertsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_MASTER_ALERTS, enabled)?.apply()
    }

    fun isSoundAlertsEnabled(): Boolean = prefs?.getBoolean(KEY_SOUND_ALERTS, true) ?: true
    fun setSoundAlertsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SOUND_ALERTS, enabled)?.apply()
    }

    fun isVibrationEnabled(): Boolean = prefs?.getBoolean(KEY_VIBRATION_ALERTS, true) ?: true
    fun setVibrationEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_VIBRATION_ALERTS, enabled)?.apply()
    }

    fun isPriceAlertsEnabled(): Boolean = prefs?.getBoolean(KEY_PRICE_ALERTS, true) ?: true
    fun setPriceAlertsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PRICE_ALERTS, enabled)?.apply()
    }

    fun isLongSignalsEnabled(): Boolean = prefs?.getBoolean(KEY_LONG_SIGNALS, true) ?: true
    fun setLongSignalsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_LONG_SIGNALS, enabled)?.apply()
    }

    fun isShortSignalsEnabled(): Boolean = prefs?.getBoolean(KEY_SHORT_SIGNALS, true) ?: true
    fun setShortSignalsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_SHORT_SIGNALS, enabled)?.apply()
    }

    fun isNewsAlertsEnabled(): Boolean = prefs?.getBoolean(KEY_NEWS_ALERTS, true) ?: true
    fun setNewsAlertsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_NEWS_ALERTS, enabled)?.apply()
    }

    fun isMacroEventsEnabled(): Boolean = prefs?.getBoolean(KEY_MACRO_EVENTS, true) ?: true
    fun setMacroEventsEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_MACRO_EVENTS, enabled)?.apply()
    }

    fun isWatchlistUpdatesEnabled(): Boolean = prefs?.getBoolean(KEY_WATCHLIST_UPDATES, true) ?: true
    fun setWatchlistUpdatesEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_WATCHLIST_UPDATES, enabled)?.apply()
    }

    fun saveFcmToken(token: String) {
        if (token.isNotBlank()) {
            prefs?.edit()?.putString(KEY_FCM_TOKEN, token)?.apply()
        }
    }

    fun getFcmToken(): String? = prefs?.getString(KEY_FCM_TOKEN, null)

    fun saveTopicSubscriptionStatus(topics: Set<String>, lastSyncMs: Long, statusNote: String) {
        prefs?.edit()
            ?.putStringSet(KEY_FCM_SUBSCRIBED_TOPICS, topics)
            ?.putLong(KEY_FCM_TOPIC_SYNC_MS, lastSyncMs)
            ?.putString(KEY_FCM_TOPIC_STATUS_NOTE, statusNote)
            ?.apply()
    }

    fun getSubscribedTopics(): Set<String> = prefs?.getStringSet(KEY_FCM_SUBSCRIBED_TOPICS, emptySet()) ?: emptySet()

    fun getTopicSyncStatusNote(): String =
        prefs?.getString(KEY_FCM_TOPIC_STATUS_NOTE, "Topics not synced yet") ?: "Topics not synced yet"

    fun hasDeliveredEvent(eventId: String): Boolean = synchronized(deliveredEventIdsSet) {
        eventId in deliveredEventIdsSet
    }

    fun getDeliveredEventIds(): Set<String> = synchronized(deliveredEventIdsSet) {
        deliveredEventIdsSet.toSet()
    }

    // ==========================================
    // TRIGGER DISPATCHERS (DUPLICATE-PREVENTED)
    // ==========================================

    fun notifyPriceTarget(
        alertId: String,
        symbol: String,
        targetPrice: Double,
        currentPrice: Double,
        directionAbove: Boolean
    ): Boolean {
        if (!isPriceAlertsEnabled()) return false
        val key = "price_${alertId}_$symbol"
        if (isRecentlyTriggered(key)) return false

        val dirStr = if (directionAbove) "crossed above" else "crossed below"
        val title = "🎯 Price Alert: $symbol"
        val message = "$symbol has $dirStr target of $$targetPrice (Current: $$currentPrice) • Alert Only — Not an Executed Order"

        markTriggered(key)
        recordAlertHistory(
            AlertHistoryEntry(
                eventId = key,
                symbol = symbol,
                alertType = AlertType.PRICE_TARGET,
                title = title,
                message = message,
                timestampMs = System.currentTimeMillis(),
                deliverySource = "Local Price Monitor"
            )
        )
        postNotification(
            title = title,
            message = message,
            type = AlertType.PRICE_TARGET,
            notifId = 1001,
            symbol = symbol,
            openWorkflow = false
        )
        return true
    }

    fun notifySignal(signal: AiTradingSignal): Boolean {
        val isLong = signal.decision == SignalDecision.LONG
        val isShort = signal.decision == SignalDecision.SHORT

        if (isLong && !isLongSignalsEnabled()) return false
        if (isShort && !isShortSignalsEnabled()) return false
        if (!isLong && !isShort) return false

        val key = "signal_${signal.id}_${signal.symbol}_${signal.decision.name}"
        if (isRecentlyTriggered(key)) return false

        val alertType = if (isLong) AlertType.SIGNAL_LONG else AlertType.SIGNAL_SHORT
        val emoji = if (isLong) "🟢" else "🔴"
        val title = "$emoji ${signal.decision.name} Signal: ${signal.symbol}"
        val entryStr = signal.riskPlan?.entryZoneFormatted ?: "Market Zone"
        val slStr = signal.riskPlan?.stopLossFormatted ?: "Structure Buffer"
        val tpStr = signal.riskPlan?.takeProfit1Formatted ?: "Target 1"
        val message = "Entry: $entryStr • SL: $slStr • TP1: $tpStr (Model Conf: ${signal.confidence.totalScore}%) • Alert Only"

        markTriggered(key)
        recordAlertHistory(
            AlertHistoryEntry(
                eventId = key,
                symbol = signal.symbol,
                signalId = signal.id,
                alertType = alertType,
                title = title,
                message = message,
                timestampMs = System.currentTimeMillis(),
                deliverySource = "Signal Engine"
            )
        )
        postNotification(
            title = title,
            message = message,
            type = alertType,
            notifId = 2001,
            symbol = signal.symbol,
            signalId = signal.id,
            openWorkflow = true
        )
        return true
    }

    fun notifyNews(news: MarketNews): Boolean {
        val isHighImpact = news.impactLevel == NewsImpactLevel.HIGH
        if (isHighImpact && !isMacroEventsEnabled()) return false
        if (!isHighImpact && !isNewsAlertsEnabled()) return false

        val key = "news_${news.id}"
        if (isRecentlyTriggered(key)) return false

        val alertType = if (isHighImpact) AlertType.HIGH_IMPACT_EVENT else AlertType.IMPORTANT_NEWS
        val title = if (isHighImpact) "⚡ High-Impact Event: ${news.source}" else "📰 Market News: ${news.source}"
        val message = news.title

        markTriggered(key)
        recordAlertHistory(
            AlertHistoryEntry(
                eventId = key,
                symbol = news.affectedAssets.firstOrNull() ?: "MARKET",
                alertType = alertType,
                title = title,
                message = message,
                timestampMs = System.currentTimeMillis(),
                deliverySource = "News Monitor"
            )
        )
        postNotification(title, message, alertType, 3001, openWorkflow = false)
        return true
    }

    fun notifyWatchlistUpdate(symbol: String, condition: String): Boolean {
        if (!isWatchlistUpdatesEnabled()) return false
        val key = "watchlist_${symbol}_$condition"
        if (isRecentlyTriggered(key)) return false

        val title = "⭐ Watchlist Alert: $symbol"
        val message = "$symbol condition triggered: $condition"

        markTriggered(key)
        recordAlertHistory(
            AlertHistoryEntry(
                eventId = key,
                symbol = symbol,
                alertType = AlertType.WATCHLIST_UPDATE,
                title = title,
                message = message,
                timestampMs = System.currentTimeMillis(),
                deliverySource = "Watchlist Monitor"
            )
        )
        postNotification(title, message, AlertType.WATCHLIST_UPDATE, 4001, symbol = symbol, openWorkflow = false)
        return true
    }

    fun notifyFuturesScanSignal(
        symbol: String,
        isLong: Boolean,
        entryZone: String,
        sl: String,
        tp1: String,
        qualityScore: Int,
        signalId: String? = null
    ): Boolean {
        if (isLong && !isLongSignalsEnabled()) return false
        if (!isLong && !isShortSignalsEnabled()) return false

        val key = "futures_signal_${symbol}_${if (isLong) "LONG" else "SHORT"}"
        val lastTime = recentlyTriggered[key]
        val cooldownMs = 45 * 60 * 1000L // 45-minute duplicate-alert cooldown per pair and direction
        if (lastTime != null && (System.currentTimeMillis() - lastTime) < cooldownMs) {
            return false
        }

        val emoji = if (isLong) "🟢 LONG" else "🔴 SHORT"
        val title = "$emoji Futures Entry: $symbol ($qualityScore%)"
        val message = "Entry: $entryZone | SL: $sl | TP1: $tp1 • Alert Only — Not an Executed Order"

        recentlyTriggered[key] = System.currentTimeMillis()
        recordAlertHistory(
            AlertHistoryEntry(
                eventId = "${key}_${System.currentTimeMillis() / cooldownMs}",
                symbol = symbol,
                signalId = signalId,
                alertType = if (isLong) AlertType.SIGNAL_LONG else AlertType.SIGNAL_SHORT,
                title = title,
                message = message,
                timestampMs = System.currentTimeMillis(),
                deliverySource = "Futures Auto-Scanner"
            )
        )
        postNotification(
            title = title,
            message = message,
            type = if (isLong) AlertType.SIGNAL_LONG else AlertType.SIGNAL_SHORT,
            notifId = (symbol.hashCode() and 0x7FFFFFFF) % 10000 + 5000,
            symbol = symbol,
            signalId = signalId,
            openWorkflow = true
        )
        return true
    }

    /**
     * Persistent eventId-deduplicated dispatcher for Part 2 trade lifecycle alerts
     * (Entry trigger, SL, TP, Price target, Setup invalidation, Signal expiry, Market condition change)
     * and incoming FCM push notifications.
     */
    fun notifyTradeLifecycleAlert(
        eventId: String,
        symbol: String,
        signalId: String? = null,
        alertType: AlertType,
        title: String,
        body: String,
        timestampMs: Long = System.currentTimeMillis(),
        source: String = "Trade Monitor",
        openWorkflow: Boolean = true,
        direction: String? = null,
        expiresAtMs: Long? = null,
        playSound: Boolean = isSoundAlertsEnabled(),
        nowMs: Long = System.currentTimeMillis()
    ): Boolean {
        if (eventId.isBlank()) return false
        if (!isMasterAlertsEnabled()) return false
        if (expiresAtMs != null && nowMs > expiresAtMs) return false
        val normalizedDir = direction?.uppercase(java.util.Locale.US)
        if ((alertType == AlertType.SIGNAL_LONG || normalizedDir == "LONG") && !isLongSignalsEnabled()) return false
        if ((alertType == AlertType.SIGNAL_SHORT || normalizedDir == "SHORT") && !isShortSignalsEnabled()) return false
        if (hasDeliveredEvent(eventId)) return false

        markDeliveredEvent(eventId)

        val pktTime = TimeZoneUtils.formatKarachiTimestamp(timestampMs)
        val safeBody = when {
            body.contains("PKT", ignoreCase = false) && body.contains("Not an Executed Order", ignoreCase = true) -> body
            body.contains("Not an Executed Order", ignoreCase = true) -> "$body ($pktTime)"
            else -> "$body • Alert Only — Not an Executed Order ($pktTime)"
        }

        recordAlertHistory(
            AlertHistoryEntry(
                eventId = eventId,
                symbol = symbol,
                signalId = signalId,
                alertType = alertType,
                title = title,
                message = safeBody,
                timestampMs = timestampMs,
                timestampPkt = pktTime,
                deliverySource = source,
                isOrderExecution = false
            )
        )

        val targetChannel = when (alertType) {
            AlertType.OPPORTUNITY_1_TO_5 -> CHANNEL_ID_HIGH_REWARD
            AlertType.OPPORTUNITY_1_TO_4 -> CHANNEL_ID_QUALIFIED_1_4
            else -> CHANNEL_ID
        }

        postNotification(
            title = title,
            message = safeBody,
            type = alertType,
            notifId = (eventId.hashCode() and 0x7FFFFFFF) % 40000 + 10000,
            eventId = eventId,
            symbol = symbol,
            signalId = signalId,
            openWorkflow = openWorkflow,
            channelId = targetChannel,
            playSound = playSound
        )
        return true
    }

    /**
     * Dispatches a Part 4/5 NEXUS 1:5 or NEXUS QUALIFIED 1:4 opportunity push notification
     * with configured sound/vibration, Asia/Karachi (PKT) timestamp, full Gross vs Net R:R
     * & funding/fee/slippage breakdown, and persistent eventId deduplication. Never executes live orders.
     */
    fun notifyHighRewardOpportunity(
        opportunity: HighRewardOpportunity,
        playSound: Boolean = isSoundAlertsEnabled(),
        source: String = "NEXUS 1:5 Opportunity Detector"
    ): Boolean {
        if (!isMasterAlertsEnabled()) return false
        if (!opportunity.isQualified || opportunity.status != OpportunityStatus.ACTIVE) return false
        if (opportunity.netRiskRewardRatio < 4.0) return false
        val isLong = opportunity.direction == FuturesDirection.LONG
        if (isLong && !isLongSignalsEnabled()) return false
        if (!isLong && !isShortSignalsEnabled()) return false
        if (hasDeliveredEvent(opportunity.signatureKey)) return false

        markDeliveredEvent(opportunity.signatureKey)

        val isTopOneToFive = opportunity.tier == OpportunityPriorityTier.HIGHEST_1_TO_5_PLUS
        val resolvedAlertType = if (isTopOneToFive) AlertType.OPPORTUNITY_1_TO_5 else AlertType.OPPORTUNITY_1_TO_4
        val resolvedChannelId = if (isTopOneToFive) CHANNEL_ID_HIGH_REWARD else CHANNEL_ID_QUALIFIED_1_4

        val title = if (isTopOneToFive) {
            "NEXUS 1:5 HIGH REWARD OPPORTUNITY • ${opportunity.symbol} ${opportunity.direction.name}"
        } else {
            "NEXUS QUALIFIED 1:4 OPPORTUNITY (1:${String.format(java.util.Locale.US, "%.2f", opportunity.netRiskRewardRatio)}) • ${opportunity.symbol} ${opportunity.direction.name}"
        }

        val histText = opportunity.historicalTargetHitProbabilityPercent?.let {
            "Hist Prob: ${String.format(java.util.Locale.US, "%.1f", it)}% (n=${opportunity.historicalSampleSize})"
        } ?: "Hist Prob: Insufficient real history (n=${opportunity.historicalSampleSize})"

        val body = buildString {
            append("${opportunity.symbol} ${opportunity.direction.name} | Trigger (PKT): ${opportunity.entryTriggerTimestampPkt}\n")
            append("Entry: ${opportunity.entryFormatted} | SL: ${opportunity.stopLossFormatted} | TP: ${opportunity.takeProfitFormatted}\n")
            append("Net R:R 1:${String.format(java.util.Locale.US, "%.2f", opportunity.netRiskRewardRatio)} (Gross 1:${String.format(java.util.Locale.US, "%.2f", opportunity.grossRiskRewardRatio)}) | $1 risk -> $${String.format(java.util.Locale.US, "%.2f", opportunity.normalizedNetRewardUsd)} net\n")
            append("Est Net +$${String.format(java.util.Locale.US, "%.2f", opportunity.estimatedNetProfitUsdt)} / -$${String.format(java.util.Locale.US, "%.2f", opportunity.estimatedNetLossUsdt)} (+${String.format(java.util.Locale.US, "%.1f", opportunity.estimatedNetRoiPercent)}% ROI @ ${opportunity.leverageAssumed}x) | Costs: Fees $${String.format(java.util.Locale.US, "%.2f", opportunity.estimatedFeesUsdt)}, Slip $${String.format(java.util.Locale.US, "%.2f", opportunity.estimatedSlippageUsdt)}, Fund ${String.format(java.util.Locale.US, "%+.4f%%", opportunity.fundingRatePercent)} (-$${String.format(java.util.Locale.US, "%.2f", opportunity.estimatedFundingReserveUsdt)})\n")
            append("Conf: ${opportunity.modelConfidencePercent}% | $histText\n")
            append("Why: ${opportunity.qualificationReasons.firstOrNull() ?: "Confirmed MTF + HTF structure"}\n")
            append("Risk: ${opportunity.risksAndInvalidations.firstOrNull() ?: "Breach of SL cancels setup"}\n")
            append("Alert Only — Not an Executed Order")
        }

        recordAlertHistory(
            AlertHistoryEntry(
                eventId = opportunity.signatureKey,
                symbol = opportunity.symbol,
                signalId = opportunity.id,
                alertType = resolvedAlertType,
                title = title,
                message = body,
                timestampMs = opportunity.entryTriggerTimestampMs,
                timestampPkt = opportunity.entryTriggerTimestampPkt,
                deliverySource = source,
                isOrderExecution = false
            )
        )

        postNotification(
            title = title,
            message = body,
            type = resolvedAlertType,
            notifId = (opportunity.signatureKey.hashCode() and 0x7FFFFFFF) % 40000 + 50000,
            eventId = opportunity.signatureKey,
            symbol = opportunity.symbol,
            signalId = opportunity.id,
            openWorkflow = true,
            channelId = resolvedChannelId,
            playSound = playSound
        )
        return true
    }

    fun buildDeepLinkIntent(
        targetContext: Context,
        symbol: String?,
        signalId: String?,
        openWorkflow: Boolean,
        alertType: AlertType? = null
    ): Intent {
        return Intent(targetContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            if (!symbol.isNullOrBlank()) {
                putExtra(EXTRA_NAV_TAB, NavigationTab.SCANNER.name)
                putExtra(EXTRA_SIGNAL_SYMBOL, symbol)
                putExtra(EXTRA_OPEN_WORKFLOW, openWorkflow)
            }
            if (!signalId.isNullOrBlank()) {
                putExtra(EXTRA_SIGNAL_ID, signalId)
            }
            if (alertType != null) {
                putExtra(EXTRA_ALERT_TYPE, alertType.name)
            }
        }
    }

    private fun postNotification(
        title: String,
        message: String,
        type: AlertType,
        notifId: Int,
        eventId: String? = null,
        symbol: String? = null,
        signalId: String? = null,
        openWorkflow: Boolean = false,
        channelId: String = CHANNEL_ID,
        playSound: Boolean = isSoundAlertsEnabled()
    ) {
        // Emit in-app event for immediate UI banner/snackbars
        _inAppEvents.tryEmit(
            InAppNotificationEvent(
                title = title,
                message = message,
                type = type,
                eventId = eventId,
                symbol = symbol,
                signalId = signalId
            )
        )

        val ctx = appContext ?: return
        val shouldPlaySound = playSound && isSoundAlertsEnabled()
        val shouldVibrate = isVibrationEnabled()
        val soundUri = if (shouldPlaySound) {
            runCatching { RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION) }.getOrNull()
        } else null

        if (shouldPlaySound && type == AlertType.OPPORTUNITY_1_TO_5 && soundUri != null) {
            runCatching {
                RingtoneManager.getRingtone(ctx, soundUri)?.play()
            }
        }

        // Check POST_NOTIFICATIONS permission on Android 13+ (API 33)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionGranted = ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!permissionGranted) {
                Log.d("NexusNotification", "POST_NOTIFICATIONS permission not granted; using in-app notification only.")
                return
            }
        }

        try {
            val intent = buildDeepLinkIntent(ctx, symbol, signalId, openWorkflow, type)
            val pendingIntent = PendingIntent.getActivity(
                ctx,
                notifId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val resolvedPriority = when (type) {
                AlertType.OPPORTUNITY_1_TO_5 -> NotificationCompat.PRIORITY_MAX
                AlertType.OPPORTUNITY_1_TO_4 -> NotificationCompat.PRIORITY_DEFAULT
                else -> NotificationCompat.PRIORITY_HIGH
            }

            val builder = NotificationCompat.Builder(ctx, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(resolvedPriority)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)

            if (type == AlertType.OPPORTUNITY_1_TO_5) {
                builder.setCategory(NotificationCompat.CATEGORY_ALARM)
            } else if (type == AlertType.OPPORTUNITY_1_TO_4) {
                builder.setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            }

            if (shouldVibrate) {
                builder.setVibrate(longArrayOf(0L, 250L, 120L, 250L))
            } else {
                builder.setVibrate(longArrayOf(0L))
            }

            if (shouldPlaySound && soundUri != null) {
                builder.setSound(soundUri)
            } else {
                builder.setSilent(true)
            }

            NotificationManagerCompat.from(ctx).notify(notifId, builder.build())
        } catch (e: Exception) {
            Log.w("NexusNotification", "Failed to dispatch system notification: ${e.message}")
        }
    }

    private fun isRecentlyTriggered(key: String): Boolean {
        val lastTime = recentlyTriggered[key] ?: return false
        val cooldownMs = 15 * 60 * 1000L // 15 minutes cooldown
        return (System.currentTimeMillis() - lastTime) < cooldownMs
    }

    private fun markTriggered(key: String) {
        recentlyTriggered[key] = System.currentTimeMillis()
    }

    private fun markDeliveredEvent(eventId: String) {
        synchronized(deliveredEventIdsSet) {
            if (deliveredEventIdsSet.add(eventId)) {
                deliveredEventIdsOrder.addLast(eventId)
                while (deliveredEventIdsOrder.size > MAX_PERSISTED_EVENT_IDS) {
                    val oldest = deliveredEventIdsOrder.removeFirst()
                    deliveredEventIdsSet.remove(oldest)
                }
                val arr = JSONArray()
                deliveredEventIdsOrder.forEach { arr.put(it) }
                prefs?.edit()?.putString(KEY_DELIVERED_EVENT_IDS, arr.toString())?.apply()
            }
        }
    }

    private fun loadDeliveredEventIds() {
        val raw = prefs?.getString(KEY_DELIVERED_EVENT_IDS, null) ?: return
        try {
            val arr = JSONArray(raw)
            synchronized(deliveredEventIdsSet) {
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i)
                    if (id.isNotBlank() && deliveredEventIdsSet.add(id)) {
                        deliveredEventIdsOrder.addLast(id)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun recordAlertHistory(entry: AlertHistoryEntry) {
        val updated = (listOf(entry) + _alertHistory.value.filterNot { it.eventId == entry.eventId })
            .take(MAX_HISTORY_ENTRIES)
        _alertHistory.value = updated
        saveAlertHistory(updated)
    }

    private fun saveAlertHistory(entries: List<AlertHistoryEntry>) {
        try {
            val arr = JSONArray()
            entries.forEach { e ->
                arr.put(
                    JSONObject().apply {
                        put("eventId", e.eventId)
                        put("symbol", e.symbol)
                        put("signalId", e.signalId ?: "")
                        put("alertType", e.alertType.name)
                        put("title", e.title)
                        put("message", e.message)
                        put("timestampMs", e.timestampMs)
                        put("timestampPkt", e.timestampPkt)
                        put("deliverySource", e.deliverySource)
                    }
                )
            }
            prefs?.edit()?.putString(KEY_ALERT_HISTORY, arr.toString())?.apply()
        } catch (_: Exception) {
        }
    }

    private fun loadAlertHistory() {
        val raw = prefs?.getString(KEY_ALERT_HISTORY, null) ?: return
        try {
            val arr = JSONArray(raw)
            val list = mutableListOf<AlertHistoryEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val typeName = obj.optString("alertType", AlertType.PRICE_TARGET.name)
                val alertType = AlertType.entries.firstOrNull { it.name == typeName } ?: AlertType.PRICE_TARGET
                list.add(
                    AlertHistoryEntry(
                        eventId = obj.optString("eventId"),
                        symbol = obj.optString("symbol"),
                        signalId = obj.optString("signalId").takeIf { it.isNotBlank() },
                        alertType = alertType,
                        title = obj.optString("title"),
                        message = obj.optString("message"),
                        timestampMs = obj.optLong("timestampMs", 0L),
                        timestampPkt = obj.optString("timestampPkt"),
                        deliverySource = obj.optString("deliverySource", "Monitor"),
                        isOrderExecution = false
                    )
                )
            }
            _alertHistory.value = list
        } catch (_: Exception) {
        }
    }

    companion object {
        const val CHANNEL_ID = "nexus_trading_alerts"
        const val CHANNEL_ID_HIGH_REWARD = "nexus_1_5_opportunities"
        const val CHANNEL_ID_QUALIFIED_1_4 = "nexus_1_4_opportunities"
        const val EXTRA_NAV_TAB = "nexus_extra_nav_tab"
        const val EXTRA_SIGNAL_SYMBOL = "nexus_extra_signal_symbol"
        const val EXTRA_SIGNAL_ID = "nexus_extra_signal_id"
        const val EXTRA_OPEN_WORKFLOW = "nexus_extra_open_workflow"
        const val EXTRA_ALERT_TYPE = "nexus_extra_alert_type"

        private const val MAX_PERSISTED_EVENT_IDS = 500
        private const val MAX_HISTORY_ENTRIES = 100
        private const val PREFS_NAME = "nexus_notification_prefs"
        private const val KEY_MASTER_ALERTS = "notif_master_alerts"
        private const val KEY_SOUND_ALERTS = "notif_sound_alerts"
        private const val KEY_VIBRATION_ALERTS = "notif_vibration_alerts"
        private const val KEY_PRICE_ALERTS = "notif_price_alerts"
        private const val KEY_LONG_SIGNALS = "notif_long_signals"
        private const val KEY_SHORT_SIGNALS = "notif_short_signals"
        private const val KEY_NEWS_ALERTS = "notif_news_alerts"
        private const val KEY_MACRO_EVENTS = "notif_macro_events"
        private const val KEY_WATCHLIST_UPDATES = "notif_watchlist_updates"
        private const val KEY_DELIVERED_EVENT_IDS = "notif_delivered_event_ids"
        private const val KEY_ALERT_HISTORY = "notif_alert_history_v1"
        private const val KEY_FCM_TOKEN = "notif_fcm_token"
        private const val KEY_FCM_SUBSCRIBED_TOPICS = "notif_fcm_subscribed_topics"
        private const val KEY_FCM_TOPIC_SYNC_MS = "notif_fcm_topic_sync_ms"
        private const val KEY_FCM_TOPIC_STATUS_NOTE = "notif_fcm_topic_status_note"
    }
}

