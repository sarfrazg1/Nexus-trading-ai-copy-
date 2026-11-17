package com.example.data.providers

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Orchestrates all 6 public market data providers cleanly:
 * - Binance USDT-M Futures is the PRIMARY live trading source and runs independently;
 *   slow or failing optional providers NEVER block the core scanner or Binance quotes.
 * - Cross-exchange comparisons (Binance Futures vs Bybit V5 Linear Futures) strictly verify
 *   matching symbol, quote currency, instrument type, and timestamp skew via CrossExchangeValidator.
 */
class HybridMarketAggregator(
    val binanceFuturesProvider: BinanceFuturesPrimaryProvider = BinanceFuturesPrimaryProvider(),
    val bybitProvider: BybitV5PublicProvider = BybitV5PublicProvider(),
    val coinGeckoProvider: CoinGeckoDemoProvider = CoinGeckoDemoProvider(),
    val defiLlamaProvider: DefiLlamaProvider = DefiLlamaProvider(),
    val fearGreedProvider: AlternativeMeFearGreedProvider = AlternativeMeFearGreedProvider(),
    val gdeltNewsProvider: GdeltNewsProvider = GdeltNewsProvider(),
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    /**
     * Refreshes Binance USDT-M Futures quotes completely independently without waiting on any
     * optional provider (Bybit, CoinGecko, DefiLlama, Alternative.me, GDELT).
     */
    suspend fun refreshPrimaryFuturesOnly(
        symbols: List<String> = BinanceFuturesPrimaryProvider.DEFAULT_FUTURES_SYMBOLS
    ): ProviderFetchOutcome<Map<String, NormalizedMarketQuote>> {
        return binanceFuturesProvider.fetchFuturesQuotes(symbols)
    }

    /**
     * Fetches primary + optional providers in parallel. Optional provider timeouts or errors
     * are isolated inside supervisorScope and recorded in providerStatuses without failing primary quotes.
     */
    suspend fun refreshHybridMarketState(
        previousState: UnifiedHybridMarketState = UnifiedHybridMarketState(),
        optionalTimeoutMs: Long = 16_000L
    ): UnifiedHybridMarketState = supervisorScope {
        val primaryDeferred = async {
            binanceFuturesProvider.fetchFuturesQuotes()
        }
        val bybitDeferred = async {
            withTimeoutOrNull(optionalTimeoutMs) {
                bybitProvider.fetchLinearFuturesQuotes(BinanceFuturesPrimaryProvider.DEFAULT_FUTURES_SYMBOLS.toSet())
            }
        }
        val cgDeferred = async {
            withTimeoutOrNull(optionalTimeoutMs) {
                coinGeckoProvider.fetchGlobalAndTopRankings()
            }
        }
        val llamaDeferred = async {
            withTimeoutOrNull(optionalTimeoutMs) {
                defiLlamaProvider.fetchDefiAndStablecoins()
            }
        }
        val fngDeferred = async {
            withTimeoutOrNull(optionalTimeoutMs) {
                fearGreedProvider.fetchFearAndGreed()
            }
        }
        val gdeltDeferred = async {
            withTimeoutOrNull(optionalTimeoutMs) {
                gdeltNewsProvider.fetchFilteredNews()
            }
        }

        val primaryRes = primaryDeferred.await()
        val bybitRes = bybitDeferred.await()
        val cgRes = cgDeferred.await()
        val llamaRes = llamaDeferred.await()
        val fngRes = fngDeferred.await()
        val gdeltRes = gdeltDeferred.await()

        val primaryQuotes = primaryRes.data ?: previousState.primaryFuturesQuotes
        val bybitQuotes = bybitRes?.data.orEmpty()

        val comparisons = LinkedHashMap<String, CrossExchangeComparison>()
        for ((sym, binanceQuote) in primaryQuotes) {
            val bybitMatch = bybitQuotes[sym]
            comparisons[sym] = CrossExchangeValidator.compareQuotes(binanceQuote, bybitMatch)
        }

        val statusMap = LinkedHashMap<String, ProviderMetadata>()
        statusMap[BinanceFuturesPrimaryProvider.PROVIDER_ID] = primaryRes.metadata
        bybitRes?.let { statusMap[BybitV5PublicProvider.PROVIDER_ID] = it.metadata }
        cgRes?.let { statusMap[CoinGeckoDemoProvider.PROVIDER_ID] = it.metadata }
        llamaRes?.let { statusMap[DefiLlamaProvider.PROVIDER_ID] = it.metadata }
        fngRes?.let { statusMap[AlternativeMeFearGreedProvider.PROVIDER_ID] = it.metadata }
        gdeltRes?.let { statusMap[GdeltNewsProvider.PROVIDER_ID] = it.metadata }

        UnifiedHybridMarketState(
            primaryFuturesQuotes = primaryQuotes,
            crossExchangeComparisons = comparisons,
            coinGeckoGlobal = cgRes?.data ?: previousState.coinGeckoGlobal,
            defiSnapshot = llamaRes?.data ?: previousState.defiSnapshot,
            fearGreed = fngRes?.data ?: previousState.fearGreed,
            filteredNews = gdeltRes?.data ?: previousState.filteredNews,
            providerStatuses = previousState.providerStatuses + statusMap,
            assembledAtMillis = clockMillis()
        )
    }
}
