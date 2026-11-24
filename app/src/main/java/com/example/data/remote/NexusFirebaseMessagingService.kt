package com.example.data.remote

import android.content.Context
import android.util.Log
import com.example.data.model.AlertType
import com.example.util.NexusNotificationManager
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
    val openWorkflow: Boolean
)

/**
 * Handles remote FCM push alerts when the app is closed or backgrounded.
 * Deduplicates on `event_id` via NexusNotificationManager and routes taps to the exact signal workflow.
 * Never places trades from push messages.
 */
class NexusFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "FCM token refreshed")
        NexusNotificationManager(applicationContext).saveFcmToken(token)
        syncTopicSubscriptions(enabled = true)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val payload = parsePayload(
            data = remoteMessage.data,
            notifTitle = remoteMessage.notification?.title,
            notifBody = remoteMessage.notification?.body,
            sentTimeMs = remoteMessage.sentTime
        ) ?: return

        val mgr = NexusNotificationManager(applicationContext)
        mgr.notifyTradeLifecycleAlert(
            eventId = payload.eventId,
            symbol = payload.symbol,
            signalId = payload.signalId,
            alertType = payload.alertType,
            title = payload.title,
            body = payload.body,
            timestampMs = payload.timestampMs,
            source = "Firebase Cloud Messaging",
            openWorkflow = payload.openWorkflow
        )
    }

    companion object {
        private const val TAG = "NexusFcmService"
        const val TOPIC_HIGH_REWARD_1_5 = "nexus_high_reward_1_5"
        const val TOPIC_QUALIFIED_1_4 = "nexus_qualified_1_4"

        fun syncTopicSubscriptions(context: Context? = null, enabled: Boolean) {
            runCatching {
                val messaging = FirebaseMessaging.getInstance()
                if (context != null) {
                    messaging.token.addOnSuccessListener { token ->
                        if (!token.isNullOrBlank()) {
                            NexusNotificationManager(context.applicationContext).saveFcmToken(token)
                        }
                    }
                }
                if (enabled) {
                    messaging.subscribeToTopic(TOPIC_HIGH_REWARD_1_5)
                    messaging.subscribeToTopic(TOPIC_QUALIFIED_1_4)
                } else {
                    messaging.unsubscribeFromTopic(TOPIC_HIGH_REWARD_1_5)
                    messaging.unsubscribeFromTopic(TOPIC_QUALIFIED_1_4)
                }
            }.onFailure { err ->
                Log.d(TAG, "FCM subscription sync skipped in current runtime: ${err.message}")
            }
        }

        fun parsePayload(
            data: Map<String, String>,
            notifTitle: String? = null,
            notifBody: String? = null,
            sentTimeMs: Long = 0L
        ): ParsedFcmAlertPayload? {
            val title = data["title"]?.takeIf { it.isNotBlank() } ?: notifTitle?.takeIf { it.isNotBlank() } ?: return null
            val body = data["body"]?.takeIf { it.isNotBlank() } ?: notifBody?.takeIf { it.isNotBlank() } ?: "Market update received."
            val symbol = data["symbol"]?.uppercase(Locale.US)?.takeIf { it.isNotBlank() } ?: "BTCUSDT"
            val signalId = data["signal_id"]?.takeIf { it.isNotBlank() }
            val rawType = data["alert_type"]?.uppercase(Locale.US) ?: ""
            val alertType = AlertType.entries.firstOrNull { it.name == rawType } ?: AlertType.MARKET_CONDITION_CHANGE
            val timestampMs = data["timestamp_ms"]?.toLongOrNull()?.takeIf { it > 0L }
                ?: sentTimeMs.takeIf { it > 0L }
                ?: System.currentTimeMillis()
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
                openWorkflow = openWorkflow
            )
        }
    }
}
