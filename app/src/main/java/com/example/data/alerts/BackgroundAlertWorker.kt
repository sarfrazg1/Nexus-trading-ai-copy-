package com.example.data.alerts

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.model.CandleTimeframe
import com.example.data.remote.BinanceFuturesApiClient
import com.example.data.scanner.FuturesScannerRepository
import com.example.data.scanner.HighRewardOpportunityDetector
import com.example.data.settings.AppSettingsRepository
import com.example.util.NexusNotificationManager
import com.example.util.TimeZoneUtils
import java.util.concurrent.TimeUnit

/**
 * Persistent background WorkManager worker that evaluates persisted TradeAlertRules, watchlist price shifts,
 * and confirmed >=1:4 / >=1:5 High-Reward Opportunities even when the Android app UI is closed,
 * complementing Firebase Cloud Messaging (platform minimum periodic interval: 15 minutes).
 */
class BackgroundAlertWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val notifManager = NexusNotificationManager(applicationContext)
            val scannerRepo = FuturesScannerRepository.getInstance(applicationContext, notifManager)
            val settingsRepo = AppSettingsRepository(applicationContext)
            val rules = scannerRepo.getActiveTradeAlertRules()
            val watchlistPairs = scannerRepo.getSelectedPairs()
            val symbolsToQuery = (rules.map { it.symbol } + watchlistPairs.map { it.symbol }).distinct()

            if (symbolsToQuery.isEmpty()) {
                return Result.success()
            }

            val apiClient = BinanceFuturesApiClient()
            val tickers = apiClient.fetch24hrTickers(symbolsToQuery)
            if (tickers.isEmpty()) {
                return Result.retry()
            }

            val livePrices = tickers.mapValues { it.value.lastPrice }
            val priceChanges = tickers.mapValues { it.value.priceChangePercent }
            val state = scannerRepo.scannerState.value
            val signalsById = (state.activeSignals + state.watchlistSetups).associateBy { it.id }

            val triggeredEvents = AlertMonitorEngine.evaluateRules(
                rules = rules,
                livePrices = livePrices,
                signalsById = signalsById,
                priceChange24hBySymbol = priceChanges,
                alreadyTriggeredEventIds = notifManager.getDeliveredEventIds()
            )

            triggeredEvents.forEach { ev ->
                val dispatched = notifManager.notifyTradeLifecycleAlert(
                    eventId = ev.eventId,
                    symbol = ev.symbol,
                    signalId = ev.signalId.takeIf { it.isNotBlank() },
                    alertType = ev.alertType,
                    title = ev.title,
                    body = ev.body,
                    timestampMs = ev.timestampMs,
                    source = "WorkManager Background Monitor",
                    openWorkflow = ev.openWorkflowOnTap
                )
                if (dispatched && ev.ruleId.isNotBlank()) {
                    scannerRepo.markTradeAlertRuleTriggered(ev.ruleId)
                }
            }

            // Part 4: Closed-app / background periodic scan for genuine >=1:4 and >=1:5 setups
            if (settingsRepo.alertNotificationsEnabled.value) {
                val playSound = settingsRepo.soundEffectsEnabled.value || scannerRepo.scannerState.value.soundAlertsEnabled
                val budgetConfig = scannerRepo.budgetConfig.value
                for (pair in watchlistPairs.take(6)) {
                    try {
                        val c15m = apiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.M15, limit = 50)
                        val c1h = apiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.H1, limit = 50)
                        val c4h = apiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.H4, limit = 30)
                        val c5m = apiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.M5, limit = 30)
                        val completed15m = TimeZoneUtils.filterCompletedCandles(c15m)
                        val currentPrice = livePrices[pair.symbol]?.takeIf { it > 0.0 }
                            ?: completed15m.lastOrNull()?.close
                            ?: 0.0
                        if (currentPrice > 0.0) {
                            val funding = apiClient.fetchFundingRate(pair.symbol)
                            val hrResult = HighRewardOpportunityDetector.scanPair(
                                symbol = pair.symbol,
                                baseAsset = pair.baseAsset,
                                currentPrice = currentPrice,
                                candles4h = c4h,
                                candles1h = c1h,
                                candles15m = c15m,
                                candles5m = c5m,
                                fundingRatePercent = funding,
                                budgetConfig = budgetConfig
                            )
                            scannerRepo.onHighRewardScanEvaluated(
                                symbol = pair.symbol,
                                result = hrResult,
                                playSound = playSound,
                                source = "WorkManager Background Scanner (15m)"
                            )
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Background alert check failed: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "NexusBgAlertWorker"
        private const val WORK_NAME = "nexus_periodic_alert_monitor"

        fun schedulePeriodicMonitoring(context: Context) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = PeriodicWorkRequestBuilder<BackgroundAlertWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule WorkManager alert worker: ${e.message}")
            }
        }
    }
}
