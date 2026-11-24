package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.data.model.AlertType
import com.example.data.settings.AppSettingsRepository
import com.example.util.NexusNotificationManager
import com.example.util.TimeZoneUtils
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.Locale

data class ParsedFcmAlertPayload(
    val eventId: String,
    val symbol: String,
    val signalId: String?,
    val alertType: AlertType,
    val title: String,
    val body: String,
    val timestampMs: Long,
    val openWorkflow: Boolean,
    val direction: String? = null,
    val expiresAtMs: Long? = null,
    val netRiskReward: Double? = null,
    val grossRiskReward: Double? = null
)

/**
 * Handles remote FCM push alerts when the app is in foreground or background.
 * Deduplicates on `event_id`, rejects expired opportunities, honors user alert/sound/vibration
 * settings, and routes taps to the exact signal workflow. Never places trades from push messages.
 */
class NexusFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        NexusNotificationManager(applicationContext).saveFcmToken(token)
        val alertsEnabled = AppSettingsRepository(applicationContext).alertNotificationsEnabled.value
        syncTopicSubscriptions(context = applicationContext, enabled = alertsEnabled)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val payload = parsePayload(
            data = remoteMessage.data,
            notifTitle = remoteMessage.notification?.title,
            notifBody = remoteMessage.notification?.body,
            sentTimeMs = remoteMessage.sentTime
        ) ?: return

        val settingsRepo = AppSettingsRepository(applicationContext)
        val mgr = NexusNotificationManager(applicationContext)
        mgr.setMasterAlertsEnabled(settingsRepo.alertNotificationsEnabled.value)
        if (!mgr.isMasterAlertsEnabled()) {
            Log.d(TAG, "Alert notifications disabled by user; skipping FCM event ${payload.eventId}")
            return
        }

        mgr.notifyTradeLifecycleAlert(
            eventId = payload.eventId,
            symbol = payload.symbol,
            signalId = payload.signalId,
            alertType = payload.alertType,
            title = payload.title,
            body = payload.body,
            timestampMs = payload.timestampMs,
            source = "Firebase Cloud Messaging",
            openWorkflow = payload.openWorkflow,
            direction = payload.direction,
            expiresAtMs = payload.expiresAtMs,
            playSound = mgr.isSoundAlertsEnabled()
        )
    }

    companion object {
        private const val TAG = "NexusFcmService"
        const val TOPIC_HIGH_REWARD_1_5 = "nexus_high_reward_1_5"
        const val TOPIC_QUALIFIED_1_4 = "nexus_qualified_1_4"

        fun syncTopicSubscriptions(context: Context? = null, enabled: Boolean) {
            val appCtx = context?.applicationContext
            val notifMgr = appCtx?.let { NexusNotificationManager(it) }
            runCatching {
                if (appCtx != null && FirebaseApp.getApps(appCtx).isEmpty()) {
                    notifMgr?.saveTopicSubscriptionStatus(
                        topics = emptySet(),
                        lastSyncMs = System.currentTimeMillis(),
                        statusNote = "FirebaseApp not initialized in current runtime"
                    )
                    return
                }
                val messaging = FirebaseMessaging.getInstance()
                if (notifMgr != null) {
                    messaging.token.addOnSuccessListener { token ->
                        if (!token.isNullOrBlank()) {
                            notifMgr.saveFcmToken(token)
                        }
                    }
                }
                val topics = listOf(TOPIC_HIGH_REWARD_1_5, TOPIC_QUALIFIED_1_4)
                if (enabled) {
                    topics.forEach { topic ->
                        messaging.subscribeToTopic(topic).addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                notifMgr?.saveTopicSubscriptionStatus(
                                    topics = topics.toSet(),
                                    lastSyncMs = System.currentTimeMillis(),
                                    statusNote = "Subscribed to FCM topics: ${topics.joinToString()}"
                                )
                            } else {
                                Log.d(TAG, "FCM subscribe failed for $topic: ${task.exception?.message}")
                            }
                        }
                    }
                } else {
                    topics.forEach { topic ->
                        messaging.unsubscribeFromTopic(topic).addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                notifMgr?.saveTopicSubscriptionStatus(
                                    topics = emptySet(),
                                    lastSyncMs = System.currentTimeMillis(),
                                    statusNote = "Unsubscribed from FCM opportunity topics (alerts disabled)"
                                )
                            }
                        }
                    }
                }
            }.onFailure { err ->
                Log.d(TAG, "FCM subscription sync skipped in current runtime: ${err.message}")
                notifMgr?.saveTopicSubscriptionStatus(
                    topics = emptySet(),
                    lastSyncMs = System.currentTimeMillis(),
                    statusNote = "FCM topic sync skipped: ${err.message ?: "unavailable"}"
                )
            }
        }

        fun parsePayload(
            data: Map<String, String>,
            notifTitle: String? = null,
            notifBody: String? = null,
            sentTimeMs: Long = 0L
        ): ParsedFcmAlertPayload? {
            val rawType = data["alert_type"]?.uppercase(Locale.US) ?: ""
            val alertType = AlertType.entries.firstOrNull { it.name == rawType } ?: AlertType.MARKET_CONDITION_CHANGE
            val symbol = data["symbol"]?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "BTCUSDT"
            val direction = data["direction"]?.uppercase(Locale.US)?.takeIf { it == "LONG" || it == "SHORT" }
            val netRr = data["net_rr"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val grossRr = data["gross_rr"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
            val expiresAtMs = data["expires_at_ms"]?.toLongOrNull()?.takeIf { it > 0L }
            val timestampMs = data["timestamp_ms"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: sentTimeMs.takeIf { it > 0L }
                ?: System.currentTimeMillis()
            val triggerPkt = data["trigger_pkt"]?.takeIf { it.isNotBlank() }
                ?: TimeZoneUtils.formatKarachiTimestamp(timestampMs)

            val defaultTitle = when (alertType) {
                AlertType.OPPORTUNITY_1_TO_5 ->
                    "NEXUS 1:5 HIGH REWARD OPPORTUNITY • $symbol ${direction.orEmpty()}".trim()
                AlertType.OPPORTUNITY_1_TO_4 ->
                    "NEXUS QUALIFIED 1:4 OPPORTUNITY • $symbol ${direction.orEmpty()}".trim()
                else -> null
            }

            val title = data["title"]?.takeIf { it.isNotBlank() }
                ?: notifTitle?.takeIf { it.isNotBlank() }
                ?: defaultTitle
                ?: return null

            val structuredBody = if (!data["entry_price"].isNullOrBlank() &&
                !data["stop_loss"].isNullOrBlank() &&
                !data["take_profit"].isNullOrBlank()
            ) {
                buildString {
                    append("$symbol ${direction ?: ""} | Trigger (PKT): $triggerPkt\n")
                    append("Entry: ${data["entry_price"]} | SL: ${data["stop_loss"]} | TP: ${data["take_profit"]}\n")
                    if (netRr != null) {
                        val grossPart = grossRr?.let { " (Gross 1:${String.format(Locale.US, "%.2f", it)})" }.orEmpty()
                        append("Net R:R 1:${String.format(Locale.US, "%.2f", netRr)}$grossPart\n")
                    }
                    data["risk_summary"]?.takeIf { it.isNotBlank() }?.let { append("$it\n") }
                    append("Alert Only — Not an Executed Order")
                }
            } else null

            val body = data["body"]?.takeIf { it.isNotBlank() }
                ?: notifBody?.takeIf { it.isNotBlank() }
                ?: structuredBody
                ?: "Market update received ($triggerPkt)."

            val signalId = data["signal_id"]?.takeIf { it.isNotBlank() }
            val eventId = data["event_id"]?.takeIf { it.isNotBlank() }
                ?: "fcm_${symbol}_${alertType.name}_${timestampMs / 60_000L}"
            val openWorkflow = data["open_workflow"]?.toBooleanStrictOrNull() ?: (signalId != null || alertType in setOf(
                AlertType.ENTRY_TRIGGER,
                AlertType.STOP_LOSS,
                AlertType.TAKE_PROFIT,
                AlertType.SETUP_INVALIDATION,
                AlertType.SIGNAL_EXPIRY,
                AlertType.OPPORTUNITY_1_TO_4,
                AlertType.OPPORTUNITY_1_TO_5
            ))

            return ParsedFcmAlertPayload(
                eventId = eventId,
                symbol = symbol,
                signalId = signalId,
                alertType = alertType,
                title = title,
                body = body,
                timestampMs = timestampMs,
                openWorkflow = openWorkflow,
                direction = direction,
                expiresAtMs = expiresAtMs,
                netRiskReward = netRr,
                grossRiskReward = grossRr
            )
        }
    }
}

