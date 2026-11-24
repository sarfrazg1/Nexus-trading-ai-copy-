package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.indicators.IndicatorCategory
import com.example.data.indicators.OverallAnalysisResult
import com.example.data.model.AlertDirection
import com.example.data.model.ApiFeedConfig
import com.example.data.model.AssetCategory
import com.example.data.model.Candle
import com.example.data.model.CandleTimeframe
import com.example.data.model.MarketAlert
import com.example.data.model.MarketAsset
import com.example.data.model.MarketDataStatus
import com.example.data.model.MarketNews
import com.example.data.model.MarketOverview
import com.example.data.model.NavigationTab
import com.example.data.model.ScannerItem
import com.example.data.model.Signal
import com.example.data.model.Timeframe
import com.example.data.repository.TradingRepository
import com.example.data.smc.RomanUrduSMCGlossary
import com.example.data.smc.SmcAnalysisResult
import com.example.data.smc.SmcGlossaryItem
import com.example.data.signal.AiTradingSignal
import com.example.data.signal.RomanUrduSignalGlossary
import com.example.data.signal.SignalDecision
import com.example.data.learning.LearningContentDatabase
import com.example.data.learning.LearningLanguageMode
import com.example.data.learning.LearningPreferencesRepository
import com.example.data.learning.LessonCategory
import com.example.data.learning.LessonItem
import com.example.data.settings.AppSettingsRepository
import com.example.data.settings.AppThemeMode
import com.example.data.settings.RefreshRatePreference
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AnalysisMode(val title: String) {
    SMC_FIBONACCI("Smart Money & Fib"),
    INDICATORS("22 Indicators")
}

enum class SmcCategoryTab(val label: String) {
    ALL("All SMC"),
    STRUCTURE("Structure"),
    ORDER_BLOCKS("Order Blocks"),
    FVG("Fair Value Gaps"),
    LIQUIDITY("Liquidity & Stops"),
    FIBONACCI_SR("Fib & S/R"),
    MULTI_TIMEFRAME("Multi-Timeframe")
}

data class TradingUiState(
    val currentTab: NavigationTab = NavigationTab.DASHBOARD,
    val assets: List<MarketAsset> = emptyList(),
    val marketOverview: MarketOverview = MarketOverview(),
    val signals: List<Signal> = emptyList(),
    val alerts: List<MarketAlert> = emptyList(),
    val news: List<MarketNews> = emptyList(),
    val scannerItems: List<ScannerItem> = emptyList(),
    val apiConfigs: List<ApiFeedConfig> = emptyList(),
    val isRefreshing: Boolean = false,
    val isWebSocketLive: Boolean = false,
    val selectedTimeframe: Timeframe = Timeframe._24H,
    val selectedCategory: AssetCategory = AssetCategory.ALL,
    val statusFilter: String = "ALL",
    val searchQuery: String = "",
    val signalFilter: String = "ALL",
    val scannerFilter: String = "ALL",
    val newsFilter: String = "ALL",
    val selectedAssetId: String? = null,
    val selectedNewsDetail: MarketNews? = null,
    val showAddAlertDialog: Boolean = false,
    val defaultAlertAssetSymbol: String = "BTC/USDT",
    val selectedApiConfigForEdit: ApiFeedConfig? = null,
    val userMessage: String? = null,

    // Technical Analysis State
    val analysisMode: AnalysisMode = AnalysisMode.SMC_FIBONACCI,
    val technicalAnalysis: OverallAnalysisResult? = null,
    val smcAnalysis: SmcAnalysisResult? = null,
    val selectedSmcTab: SmcCategoryTab = SmcCategoryTab.ALL,
    val selectedGlossaryTerm: SmcGlossaryItem? = null,
    val isCalculatingAnalysis: Boolean = false,
    val analysisAssetSymbol: String = "BTC",
    val analysisTimeframe: CandleTimeframe = CandleTimeframe.H1,
    val selectedIndicatorCategory: IndicatorCategory = IndicatorCategory.ALL,
    val analysisCandles: List<Candle> = emptyList(),
    val analysisError: String? = null,

    // AI Signal Engine State
    val currentAiSignal: AiTradingSignal? = null,
    val aiSignalsList: List<AiTradingSignal> = emptyList(),
    val isAnalyzingSignal: Boolean = false,
    val selectedSignalAssetSymbol: String = "BTC",
    val selectedSignalTimeframe: CandleTimeframe = CandleTimeframe.H1,
    val isSignalExpanded: Boolean = false,
    val signalAnalysisError: String? = null,

    // Learning Center & Academy State
    val learningLanguageMode: LearningLanguageMode = LearningLanguageMode.BILINGUAL,
    val selectedLessonCategory: LessonCategory? = null,
    val learningSearchQuery: String = "",
    val bookmarkedLessonIds: Set<String> = emptySet(),
    val completedLessonIds: Set<String> = emptySet(),
    val recentlyViewedLessonIds: List<String> = emptyList(),
    val selectedLessonForDetail: LessonItem? = null,

    // Task 7: Theme, Settings & Performance Controls
    val themeMode: AppThemeMode = AppThemeMode.DARK,
    val isDataSavingMode: Boolean = false,
    val isLowPerformanceMode: Boolean = false,
    val defaultChartTimeframe: CandleTimeframe = CandleTimeframe.H1,
    val defaultWatchlistCategory: AssetCategory = AssetCategory.ALL,
    val alertNotificationsEnabled: Boolean = true,
    val soundEffectsEnabled: Boolean = false,
    val refreshRate: RefreshRatePreference = RefreshRatePreference.FAST_15S,
    val lastCacheClearedTimestamp: Long? = null,
    val lastMarketDataRefreshTimestamp: Long = 0L,

    // Task 8: News Analyzer, Scanner & Notification States
    val selectedEconomicTerm: com.example.data.news.EconomicTerm? = null,
    val scannerTimeframe: CandleTimeframe = CandleTimeframe.H1,
    val priceAlertsEnabled: Boolean = true,
    val longSignalsEnabled: Boolean = true,
    val shortSignalsEnabled: Boolean = true,
    val newsAlertsEnabled: Boolean = true,
    val macroEventsEnabled: Boolean = true,
    val watchlistUpdatesEnabled: Boolean = true,

    // Task 10: NEXUS AI Hybrid Trading Assistant
    val aiMessages: List<com.example.data.ai.NexusChatMessage> = emptyList(),
    val aiConversations: List<com.example.data.ai.NexusConversation> = emptyList(),
    val currentAiConversationId: String = "default_session",
    val isAiLoading: Boolean = false,
    val isOnlineAiAvailable: Boolean = false,
    val lastAiError: String? = null,
    val lastAiErrorState: com.example.data.ai.NexusAiStreamResult.Error? = null,
    val selectedAiProvider: com.example.data.ai.AiProviderType = com.example.data.ai.AiProviderType.GEMINI_FIREBASE,
    val aiProviderStatuses: List<com.example.data.ai.AiProviderStatus> = emptyList(),
    val hybridMarketState: com.example.data.providers.UnifiedHybridMarketState = com.example.data.providers.UnifiedHybridMarketState(),
    val urduFontSize: com.example.data.settings.UrduFontSize = com.example.data.settings.UrduFontSize.MEDIUM,

    // Binance USDT-M Futures Auto-Scanner State
    val futuresScannerState: com.example.data.scanner.ScannerEngineState = com.example.data.scanner.ScannerEngineState(),
    val futuresWatchlist: List<com.example.data.scanner.FuturesWatchlistPair> = emptyList(),
    val scannerScreenMode: Int = 0,

    // Part 2: End-to-End Trade Workflow, Risk/Budget, Paper Orders & Lifecycle Alerts
    val tradeWorkflowState: com.example.data.broker.TradeWorkflowState = com.example.data.broker.TradeWorkflowState(),
    val strict92ConfidenceMode: Boolean = false,
    val alertHistory: List<com.example.data.alerts.AlertHistoryEntry> = emptyList(),

    // Part 3: Complete News Fetching & 3-Language Translation
    val isFetchingFullNewsArticle: Boolean = false,
    val selectedNewsLanguage: com.example.data.news.NewsLanguageOption = com.example.data.news.NewsLanguageOption.ENGLISH,
    val isTranslatingNews: Boolean = false,
    val translatedNewsArticle: com.example.data.news.TranslatedNewsArticle? = null,
    val newsTranslationError: String? = null,

    // Part 4: NEXUS 1:5 High-Reward Opportunity Radar
    val highRewardScanState: com.example.data.scanner.HighRewardScanState = com.example.data.scanner.HighRewardScanState(),
    val selectedHighRewardOpportunity: com.example.data.scanner.HighRewardOpportunity? = null
) {
    val selectedAssetDetail: MarketAsset?
        get() = selectedAssetId?.let { id -> assets.firstOrNull { it.id == id } }
}

class TradingViewModel(
    private val repository: TradingRepository = TradingRepository()
) : ViewModel() {

    private val _currentTab = MutableStateFlow(NavigationTab.DASHBOARD)
    private val _selectedTimeframe = MutableStateFlow(Timeframe._24H)
    private val _selectedCategory = MutableStateFlow(AssetCategory.ALL)
    private val _statusFilter = MutableStateFlow("ALL")
    private val _searchQuery = MutableStateFlow("")
    private val _signalFilter = MutableStateFlow("ALL")
    private val _scannerFilter = MutableStateFlow("ALL")
    private val _newsFilter = MutableStateFlow("ALL")
    private val _selectedAssetId = MutableStateFlow<String?>(null)
    private val _selectedNewsDetail = MutableStateFlow<MarketNews?>(null)
    private val _showAddAlertDialog = MutableStateFlow(false)
    private val _defaultAlertAssetSymbol = MutableStateFlow("BTC/USDT")
    private val _selectedApiConfigForEdit = MutableStateFlow<ApiFeedConfig?>(null)
    private val _userMessage = MutableStateFlow<String?>(null)

    // Technical Indicators & SMC Engine State
    private val _analysisMode = MutableStateFlow(AnalysisMode.SMC_FIBONACCI)
    private val _technicalAnalysis = MutableStateFlow<OverallAnalysisResult?>(null)
    private val _smcAnalysis = MutableStateFlow<SmcAnalysisResult?>(null)
    private val _selectedSmcTab = MutableStateFlow(SmcCategoryTab.ALL)
    private val _selectedGlossaryTerm = MutableStateFlow<SmcGlossaryItem?>(null)
    private val _isCalculatingAnalysis = MutableStateFlow(false)
    private val _analysisAssetSymbol = MutableStateFlow("BTC")
    private val _analysisTimeframe = MutableStateFlow(CandleTimeframe.H1)
    private val _selectedIndicatorCategory = MutableStateFlow(IndicatorCategory.ALL)
    private val _analysisCandles = MutableStateFlow<List<Candle>>(emptyList())
    private val _analysisError = MutableStateFlow<String?>(null)

    // AI Signal Engine State
    private val _currentAiSignal = MutableStateFlow<AiTradingSignal?>(null)
    private val _aiSignalsList = MutableStateFlow<List<AiTradingSignal>>(emptyList())
    private val _isAnalyzingSignal = MutableStateFlow(false)
    private val _selectedSignalAssetSymbol = MutableStateFlow("BTC")
    private val _selectedSignalTimeframe = MutableStateFlow(CandleTimeframe.H1)
    private val _isSignalExpanded = MutableStateFlow(false)
    private val _signalAnalysisError = MutableStateFlow<String?>(null)

    // Learning Center & App Settings State
    private val learningPrefsRepo = LearningPreferencesRepository()
    private val appSettingsRepo = AppSettingsRepository()
    private var nexusAiRepo = com.example.data.ai.NexusAiRepository()
    val futuresScannerRepo = com.example.data.scanner.FuturesScannerRepository.getInstance(notificationManager = repository.notificationManager)
    private val _scannerScreenMode = MutableStateFlow(0)

    private val _selectedLessonCategory = MutableStateFlow<LessonCategory?>(null)
    private val _learningSearchQuery = MutableStateFlow("")
    private val _selectedLessonForDetail = MutableStateFlow<LessonItem?>(null)
    private val _selectedEconomicTerm = MutableStateFlow<com.example.data.news.EconomicTerm?>(null)
    private val _scannerTimeframe = MutableStateFlow(CandleTimeframe.H1)
    private val _learningUpdate = MutableStateFlow(0)

    // Task 10: NEXUS AI State
    private val _aiConversations = MutableStateFlow<List<com.example.data.ai.NexusConversation>>(listOf(com.example.data.ai.NexusConversation(id = "default_session", title = "Market Analysis")))
    private val _currentAiConversationId = MutableStateFlow("default_session")
    private val _aiMessages = MutableStateFlow<List<com.example.data.ai.NexusChatMessage>>(emptyList())
    private val _isAiLoading = MutableStateFlow(false)
    private val _lastAiError = MutableStateFlow<String?>(null)
    private val _lastAiErrorState = MutableStateFlow<com.example.data.ai.NexusAiStreamResult.Error?>(null)
    private val _selectedAiProvider = MutableStateFlow(com.example.data.ai.AiProviderType.GEMINI_FIREBASE)
    private val lastDiscussedAssetByConversation = mutableMapOf<String, String>()
    private var lastAiSentQuery: String? = null
    private var lastAiSentTimestampMs: Long = 0L
    private val _tradeWorkflow = MutableStateFlow(com.example.data.broker.TradeWorkflowState())

    // Part 3: News Full Content + 3-Language Translation
    private var newsTranslationService = com.example.data.news.NewsTranslationService()
    private val _isFetchingFullNewsArticle = MutableStateFlow(false)
    private val _selectedNewsLanguage = MutableStateFlow(com.example.data.news.NewsLanguageOption.ENGLISH)
    private val _isTranslatingNews = MutableStateFlow(false)
    private val _translatedNewsArticle = MutableStateFlow<com.example.data.news.TranslatedNewsArticle?>(null)
    private val _newsTranslationError = MutableStateFlow<String?>(null)
    private val _selectedHighRewardOpportunity = MutableStateFlow<com.example.data.scanner.HighRewardOpportunity?>(null)

    val uiState: StateFlow<TradingUiState> = combine(
        combine(
            combine(_currentTab, _learningUpdate) { tab, _ -> tab },
            repository.assets,
            repository.marketOverview,
            repository.signals,
            repository.alerts
        ) { tab, assets, overview, signals, alerts ->
            TabAssetsOverviewSignalsAlerts(tab, assets, overview, signals, alerts)
        },
        combine(
            repository.news,
            repository.scannerItems,
            repository.apiConfigs,
            repository.isRefreshing,
            repository.isWebSocketLive
        ) { news, scanner, apiConfigs, isRefreshing, wsLive ->
            NewsScannerApiRefreshingWs(news, scanner, apiConfigs, isRefreshing, wsLive)
        },
        combine(
            _selectedCategory,
            _statusFilter,
            _searchQuery,
            _signalFilter,
            _selectedTimeframe
        ) { category, statFilter, search, sigFilter, tf ->
            CategoryStatusSearchFilters(category, statFilter, search, sigFilter, tf)
        },
        combine(
            _selectedAssetId,
            _selectedNewsDetail,
            _showAddAlertDialog,
            _defaultAlertAssetSymbol,
            _selectedApiConfigForEdit
        ) { assetId, newsDetail, addAlert, defaultSymbol, apiConfigEdit ->
            DialogStates(assetId, newsDetail, addAlert, defaultSymbol, apiConfigEdit)
        },
        combine(
            _technicalAnalysis,
            _isCalculatingAnalysis,
            _analysisAssetSymbol,
            _analysisTimeframe,
            _selectedIndicatorCategory
        ) { analysis, calculating, symbol, timeframe, category ->
            AnalysisCoreState(analysis, calculating, symbol, timeframe, category)
        }
    ) { group1, group2, group3, group4, group5 ->
        TradingUiState(
            currentTab = group1.tab,
            assets = group1.assets,
            marketOverview = group1.overview,
            signals = group1.signals,
            alerts = group1.alerts,
            news = group2.news,
            scannerItems = group2.scanner,
            apiConfigs = group2.apiConfigs,
            isRefreshing = group2.isRefreshing,
            isWebSocketLive = group2.wsLive,
            selectedTimeframe = group3.timeframe,
            selectedCategory = group3.category,
            statusFilter = group3.statFilter,
            searchQuery = group3.search,
            signalFilter = group3.sigFilter,
            scannerFilter = _scannerFilter.value,
            newsFilter = _newsFilter.value,
            selectedAssetId = group4.assetId,
            selectedNewsDetail = group4.newsDetail,
            showAddAlertDialog = group4.addAlert,
            defaultAlertAssetSymbol = group4.defaultSymbol,
            selectedApiConfigForEdit = group4.apiConfigEdit,
            userMessage = _userMessage.value,
            analysisMode = _analysisMode.value,
            technicalAnalysis = group5.analysis,
            smcAnalysis = _smcAnalysis.value,
            selectedSmcTab = _selectedSmcTab.value,
            selectedGlossaryTerm = _selectedGlossaryTerm.value,
            isCalculatingAnalysis = group5.isCalculating,
            analysisAssetSymbol = group5.symbol,
            analysisTimeframe = group5.timeframe,
            selectedIndicatorCategory = group5.category,
            analysisCandles = _analysisCandles.value,
            analysisError = _analysisError.value,
            currentAiSignal = _currentAiSignal.value,
            aiSignalsList = if (_aiSignalsList.value.isNotEmpty()) _aiSignalsList.value else repository.aiSignals.value,
            isAnalyzingSignal = _isAnalyzingSignal.value,
            selectedSignalAssetSymbol = _selectedSignalAssetSymbol.value,
            selectedSignalTimeframe = _selectedSignalTimeframe.value,
            isSignalExpanded = _isSignalExpanded.value,
            learningLanguageMode = appSettingsRepo.languageMode.value,
            selectedLessonCategory = _selectedLessonCategory.value,
            learningSearchQuery = _learningSearchQuery.value,
            bookmarkedLessonIds = learningPrefsRepo.bookmarkedIds.value,
            completedLessonIds = learningPrefsRepo.completedIds.value,
            recentlyViewedLessonIds = learningPrefsRepo.recentlyViewedIds.value,
            selectedLessonForDetail = _selectedLessonForDetail.value,
            themeMode = appSettingsRepo.themeMode.value,
            isDataSavingMode = appSettingsRepo.isDataSavingMode.value,
            isLowPerformanceMode = appSettingsRepo.isLowPerformanceMode.value,
            defaultChartTimeframe = appSettingsRepo.defaultTimeframe.value,
            defaultWatchlistCategory = appSettingsRepo.defaultWatchlistCategory.value,
            alertNotificationsEnabled = appSettingsRepo.alertNotificationsEnabled.value,
            soundEffectsEnabled = appSettingsRepo.soundEffectsEnabled.value,
            refreshRate = RefreshRatePreference.fromSeconds(appSettingsRepo.refreshIntervalSeconds.value),
            lastCacheClearedTimestamp = appSettingsRepo.lastCacheClearedTimestamp.value,
            lastMarketDataRefreshTimestamp = appSettingsRepo.lastDataRefreshTimestamp.value,
            selectedEconomicTerm = _selectedEconomicTerm.value,
            scannerTimeframe = _scannerTimeframe.value,
            priceAlertsEnabled = repository.notificationManager.isPriceAlertsEnabled(),
            longSignalsEnabled = repository.notificationManager.isLongSignalsEnabled(),
            shortSignalsEnabled = repository.notificationManager.isShortSignalsEnabled(),
            newsAlertsEnabled = repository.notificationManager.isNewsAlertsEnabled(),
            macroEventsEnabled = repository.notificationManager.isMacroEventsEnabled(),
            watchlistUpdatesEnabled = repository.notificationManager.isWatchlistUpdatesEnabled(),
            aiMessages = _aiMessages.value,
            aiConversations = _aiConversations.value,
            currentAiConversationId = _currentAiConversationId.value,
            isAiLoading = _isAiLoading.value,
            isOnlineAiAvailable = nexusAiRepo.getProviderStatuses().any {
                it.provider != com.example.data.ai.AiProviderType.OFFLINE_RULE_BASED &&
                    it.availability == com.example.data.ai.AiProviderAvailability.READY
            },
            lastAiError = _lastAiError.value,
            lastAiErrorState = _lastAiErrorState.value,
            selectedAiProvider = _selectedAiProvider.value,
            aiProviderStatuses = nexusAiRepo.getProviderStatuses(),
            hybridMarketState = repository.hybridMarketState.value,
            urduFontSize = appSettingsRepo.urduFontSize.value,
            futuresScannerState = futuresScannerRepo.scannerState.value,
            futuresWatchlist = futuresScannerRepo.watchlist.value,
            scannerScreenMode = _scannerScreenMode.value,
            tradeWorkflowState = _tradeWorkflow.value.copy(
                budgetConfig = futuresScannerRepo.budgetConfig.value,
                dailyRiskLedger = futuresScannerRepo.dailyRiskLedger.value,
                paperOrders = futuresScannerRepo.paperOrders.value,
                tradeAlertRules = futuresScannerRepo.tradeAlertRules.value,
                alertHistory = repository.notificationManager.alertHistory.value,
                fcmTokenAvailable = !repository.notificationManager.getFcmToken().isNullOrBlank()
            ),
            strict92ConfidenceMode = futuresScannerRepo.scannerState.value.strict92ConfidenceMode,
            alertHistory = repository.notificationManager.alertHistory.value,
            isFetchingFullNewsArticle = _isFetchingFullNewsArticle.value,
            selectedNewsLanguage = _selectedNewsLanguage.value,
            isTranslatingNews = _isTranslatingNews.value,
            translatedNewsArticle = _translatedNewsArticle.value,
            newsTranslationError = _newsTranslationError.value,
            highRewardScanState = futuresScannerRepo.highRewardState.value,
            selectedHighRewardOpportunity = _selectedHighRewardOpportunity.value
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TradingUiState()
    )

    init {
        viewModelScope.launch {
            repository.refreshData()
            loadTechnicalAnalysis("BTC", CandleTimeframe.H1)
            loadAiSignal("BTC", CandleTimeframe.H1)
            refreshAllAiSignals()
        }

        viewModelScope.launch {
            futuresScannerRepo.scannerState.collect {
                _learningUpdate.value++
            }
        }
        viewModelScope.launch {
            futuresScannerRepo.watchlist.collect {
                _learningUpdate.value++
            }
        }
        viewModelScope.launch {
            _tradeWorkflow.collect {
                _learningUpdate.value++
            }
        }
        viewModelScope.launch {
            futuresScannerRepo.paperOrders.collect {
                _learningUpdate.value++
            }
        }
        viewModelScope.launch {
            futuresScannerRepo.tradeAlertRules.collect {
                _learningUpdate.value++
            }
        }
        viewModelScope.launch {
            repository.notificationManager.alertHistory.collect {
                _learningUpdate.value++
            }
        }
        viewModelScope.launch {
            futuresScannerRepo.highRewardState.collect {
                _learningUpdate.value++
            }
        }
    }

    fun selectTab(tab: NavigationTab) {
        _currentTab.value = tab
        if (tab == NavigationTab.ANALYSIS && _technicalAnalysis.value == null) {
            loadTechnicalAnalysis(_analysisAssetSymbol.value, _analysisTimeframe.value)
        }
        if (tab == NavigationTab.AI_SIGNALS && (_currentAiSignal.value == null || _currentAiSignal.value?.noTradeReason?.code == "DATA_UNAVAILABLE")) {
            loadAiSignal(_selectedSignalAssetSymbol.value, _selectedSignalTimeframe.value)
            if (_aiSignalsList.value.isEmpty()) {
                refreshAllAiSignals()
            }
        }
    }

    fun refreshMarketData() {
        viewModelScope.launch {
            repository.refreshData()
            loadAiSignal(_selectedSignalAssetSymbol.value, _selectedSignalTimeframe.value)
            refreshAllAiSignals()
            _userMessage.value = "Market data updated"
        }
    }

    fun loadTechnicalAnalysis(
        symbol: String = _analysisAssetSymbol.value,
        timeframe: CandleTimeframe = _analysisTimeframe.value
    ) {
        _analysisAssetSymbol.value = symbol
        _analysisTimeframe.value = timeframe
        _isCalculatingAnalysis.value = true
        _analysisError.value = null

        viewModelScope.launch {
            try {
                val assetName = repository.assets.value.firstOrNull {
                    it.symbol.equals(symbol, ignoreCase = true) || it.id.equals(symbol, ignoreCase = true)
                }?.name ?: symbol

                val candles = repository.getCandles(symbol, timeframe)
                _analysisCandles.value = candles

                val techDeferred = async { repository.calculateTechnicalAnalysis(symbol, assetName, timeframe) }
                val smcDeferred = async { repository.calculateSmcAnalysis(symbol, assetName, timeframe) }

                _technicalAnalysis.value = techDeferred.await()
                _smcAnalysis.value = smcDeferred.await()
            } catch (e: Exception) {
                _analysisError.value = e.message ?: "Technical calculation error"
            } finally {
                _isCalculatingAnalysis.value = false
            }
        }
    }

    fun setAnalysisMode(mode: AnalysisMode) {
        _analysisMode.value = mode
    }

    fun setSmcTab(tab: SmcCategoryTab) {
        _selectedSmcTab.value = tab
    }

    fun openGlossaryTerm(termKey: String) {
        _selectedGlossaryTerm.value = RomanUrduSignalGlossary.getTerm(termKey) ?: RomanUrduSMCGlossary.getItem(termKey)
    }

    fun closeGlossaryDialog() {
        _selectedGlossaryTerm.value = null
    }

    fun loadAiSignal(
        symbol: String = _selectedSignalAssetSymbol.value,
        timeframe: CandleTimeframe = _selectedSignalTimeframe.value
    ) {
        _selectedSignalAssetSymbol.value = symbol
        _selectedSignalTimeframe.value = timeframe
        _isAnalyzingSignal.value = true
        _signalAnalysisError.value = null

        viewModelScope.launch {
            try {
                val assetName = repository.assets.value.firstOrNull {
                    it.symbol.equals(symbol, ignoreCase = true) || it.id.equals(symbol, ignoreCase = true)
                }?.name ?: symbol

                val signal = repository.calculateAiSignal(symbol, assetName, timeframe)
                _currentAiSignal.value = signal
            } catch (e: Exception) {
                _signalAnalysisError.value = e.message ?: "Failed to generate AI signal"
            } finally {
                _isAnalyzingSignal.value = false
            }
        }
    }

    fun refreshAllAiSignals() {
        viewModelScope.launch {
            try {
                val list = repository.refreshAiSignals()
                if (list.isNotEmpty()) {
                    _aiSignalsList.value = list
                }
            } catch (e: Exception) {
                // Keep current signals if refresh fails
            }
        }
    }

    fun setSignalAsset(symbol: String) {
        _selectedSignalAssetSymbol.value = symbol
        loadAiSignal(symbol, _selectedSignalTimeframe.value)
    }

    fun setSignalTimeframe(timeframe: CandleTimeframe) {
        _selectedSignalTimeframe.value = timeframe
        loadAiSignal(_selectedSignalAssetSymbol.value, timeframe)
    }

    fun toggleSignalExpanded() {
        _isSignalExpanded.value = !_isSignalExpanded.value
    }

    fun setAnalysisTimeframe(timeframe: CandleTimeframe) {
        _analysisTimeframe.value = timeframe
        loadTechnicalAnalysis(_analysisAssetSymbol.value, timeframe)
    }

    fun setAnalysisAsset(symbol: String) {
        _analysisAssetSymbol.value = symbol
        loadTechnicalAnalysis(symbol, _analysisTimeframe.value)
    }

    fun setIndicatorCategory(category: IndicatorCategory) {
        _selectedIndicatorCategory.value = category
    }

    fun openTechnicalAnalysis(symbol: String = "BTC") {
        _analysisAssetSymbol.value = symbol
        _currentTab.value = NavigationTab.ANALYSIS
        loadTechnicalAnalysis(symbol, _analysisTimeframe.value)
    }

    fun setTimeframe(timeframe: Timeframe) {
        _selectedTimeframe.value = timeframe
    }

    fun setCategory(category: AssetCategory) {
        _selectedCategory.value = category
    }

    fun setStatusFilter(filter: String) {
        _statusFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSignalFilter(filter: String) {
        _signalFilter.value = filter
    }

    fun setScannerFilter(filter: String) {
        _scannerFilter.value = filter
    }

    fun setNewsFilter(filter: String) {
        _newsFilter.value = filter
    }

    fun openAssetDetail(asset: MarketAsset) {
        _selectedAssetId.value = asset.id
    }

    fun closeAssetDetail() {
        _selectedAssetId.value = null
    }

    fun openNewsDetail(news: MarketNews) {
        val defaultLang = when (appSettingsRepo.languageMode.value) {
            LearningLanguageMode.STANDARD_URDU -> com.example.data.news.NewsLanguageOption.URDU_SCRIPT
            LearningLanguageMode.ROMAN_URDU -> com.example.data.news.NewsLanguageOption.ROMAN_URDU
            LearningLanguageMode.ENGLISH, LearningLanguageMode.BILINGUAL -> com.example.data.news.NewsLanguageOption.ENGLISH
        }
        _selectedNewsDetail.value = news
        _selectedNewsLanguage.value = defaultLang
        _translatedNewsArticle.value = null
        _newsTranslationError.value = null
        _isFetchingFullNewsArticle.value = true
        _learningUpdate.value++

        viewModelScope.launch {
            try {
                val hydrated = repository.fetchFullNewsArticle(news.id)
                    ?: repository.financialNewsService.fetchFullArticleForDetail(news)
                if (_selectedNewsDetail.value?.id == news.id) {
                    _selectedNewsDetail.value = hydrated
                }
                _isFetchingFullNewsArticle.value = false
                _learningUpdate.value++
                if (defaultLang != com.example.data.news.NewsLanguageOption.ENGLISH) {
                    translateCurrentNewsDetail(hydrated, defaultLang, forceRetry = false)
                }
            } catch (_: Exception) {
                _isFetchingFullNewsArticle.value = false
                _learningUpdate.value++
            }
        }
    }

    fun closeNewsDetail() {
        _selectedNewsDetail.value = null
        _isFetchingFullNewsArticle.value = false
        _isTranslatingNews.value = false
        _translatedNewsArticle.value = null
        _newsTranslationError.value = null
        _learningUpdate.value++
    }

    fun selectNewsLanguage(language: com.example.data.news.NewsLanguageOption) {
        _selectedNewsLanguage.value = language
        _newsTranslationError.value = null
        if (language == com.example.data.news.NewsLanguageOption.ENGLISH) {
            _translatedNewsArticle.value = null
            _isTranslatingNews.value = false
            _learningUpdate.value++
            return
        }
        val news = _selectedNewsDetail.value ?: return
        viewModelScope.launch {
            translateCurrentNewsDetail(news, language, forceRetry = false)
        }
    }

    fun retryNewsTranslation() {
        val news = _selectedNewsDetail.value ?: return
        val lang = _selectedNewsLanguage.value
        if (lang == com.example.data.news.NewsLanguageOption.ENGLISH) return
        viewModelScope.launch {
            translateCurrentNewsDetail(news, lang, forceRetry = true)
        }
    }

    private suspend fun translateCurrentNewsDetail(
        news: MarketNews,
        language: com.example.data.news.NewsLanguageOption,
        forceRetry: Boolean
    ) {
        _isTranslatingNews.value = true
        _newsTranslationError.value = null
        _learningUpdate.value++
        try {
            val result = newsTranslationService.translateArticle(
                news = news,
                targetLanguage = language,
                preferredProvider = _selectedAiProvider.value,
                forceRetry = forceRetry
            )
            if (_selectedNewsDetail.value?.id == news.id && _selectedNewsLanguage.value == language) {
                _translatedNewsArticle.value = result
                _newsTranslationError.value = result.errorNote
            }
        } catch (e: Exception) {
            _newsTranslationError.value = e.message ?: "Translation failed"
        } finally {
            _isTranslatingNews.value = false
            _learningUpdate.value++
        }
    }

    fun openAddAlertDialog(defaultSymbol: String = "BTC/USDT") {
        _defaultAlertAssetSymbol.value = defaultSymbol
        _showAddAlertDialog.value = true
    }

    fun closeAddAlertDialog() {
        _showAddAlertDialog.value = false
    }

    fun openApiConfigDialog(config: ApiFeedConfig) {
        _selectedApiConfigForEdit.value = config
    }

    fun closeApiConfigDialog() {
        _selectedApiConfigForEdit.value = null
    }

    fun createAlert(symbol: String, assetName: String, targetPrice: Double, direction: AlertDirection, note: String) {
        repository.createAlert(symbol, assetName, targetPrice, direction, note)
        _showAddAlertDialog.value = false
        _userMessage.value = "Alert created for $symbol at $$targetPrice"
    }

    fun toggleAlert(alertId: String) {
        repository.toggleAlert(alertId)
    }

    fun deleteAlert(alertId: String) {
        repository.deleteAlert(alertId)
        _userMessage.value = "Alert deleted"
    }

    fun toggleNewsBookmark(newsId: String) {
        repository.toggleNewsBookmark(newsId)
        _selectedNewsDetail.value?.let { current ->
            if (current.id == newsId) {
                _selectedNewsDetail.value = current.copy(isSaved = !current.isSaved)
            }
        }
    }

    fun toggleFavorite(assetId: String) {
        repository.toggleFavorite(assetId)
    }

    fun openEconomicGlossary(term: com.example.data.news.EconomicTerm) {
        _selectedEconomicTerm.value = term
    }

    fun dismissEconomicGlossary() {
        _selectedEconomicTerm.value = null
    }

    fun refreshNews() {
        viewModelScope.launch {
            repository.refreshNews(forceRefresh = true)
            _userMessage.value = "Market dispatches updated"
        }
    }

    fun setScannerTimeframe(timeframe: CandleTimeframe) {
        _scannerTimeframe.value = timeframe
        refreshScanner(timeframe)
    }

    fun refreshScanner(timeframe: CandleTimeframe = _scannerTimeframe.value) {
        if (_scannerScreenMode.value == 0) {
            refreshFuturesScannerOnDemand()
        } else {
            viewModelScope.launch {
                repository.refreshScanner(timeframe)
            }
        }
    }

    fun refreshFuturesScannerOnDemand() {
        viewModelScope.launch(Dispatchers.IO) {
            val pairs = futuresScannerRepo.getSelectedPairs()
            if (pairs.isEmpty()) {
                futuresScannerRepo.updateScannerStatus(
                    status = com.example.data.scanner.ScannerStatus.PAUSED,
                    errorMessage = "No pairs selected in Watchlist. Select at least 1 coin to scan."
                )
                return@launch
            }
            futuresScannerRepo.updateScannerStatus(com.example.data.scanner.ScannerStatus.SCANNING)
            var scannedCount = 0
            for (pair in pairs) {
                try {
                    futuresScannerRepo.updateScannerStatus(
                        status = com.example.data.scanner.ScannerStatus.SCANNING,
                        currentlyScanning = pair.symbol
                    )
                    val c15m = repository.futuresApiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.M15, limit = 50)
                    val c1h = repository.futuresApiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.H1, limit = 50)
                    val c4h = repository.futuresApiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.H4, limit = 30)
                    val c5m = repository.futuresApiClient.fetchFuturesCandles(pair.symbol, CandleTimeframe.M5, limit = 30)
                    val completed15m = com.example.util.TimeZoneUtils.filterCompletedCandles(c15m)
                    val currentPrice = pair.currentPrice.takeIf { it > 0.0 }
                        ?: completed15m.lastOrNull()?.close
                        ?: 0.0
                    if (currentPrice > 0.0) {
                        val funding = repository.futuresApiClient.fetchFundingRate(pair.symbol)
                        val result = com.example.data.scanner.BinanceFuturesScannerEngine.evaluatePair(
                            symbol = pair.symbol,
                            baseAsset = pair.baseAsset,
                            currentPrice = currentPrice,
                            candles4h = c4h,
                            candles1h = c1h,
                            candles15m = c15m,
                            candles5m = c5m,
                            fundingRate = funding
                        )
                        result.signal?.let { futuresScannerRepo.onSignalEvaluated(it) }

                        val playHrSound = appSettingsRepo.soundEffectsEnabled.value || futuresScannerRepo.scannerState.value.soundAlertsEnabled
                        val hrResult = com.example.data.scanner.HighRewardOpportunityDetector.scanPair(
                            symbol = pair.symbol,
                            baseAsset = pair.baseAsset,
                            currentPrice = currentPrice,
                            candles4h = c4h,
                            candles1h = c1h,
                            candles15m = c15m,
                            candles5m = c5m,
                            fundingRatePercent = funding,
                            budgetConfig = futuresScannerRepo.budgetConfig.value
                        )
                        futuresScannerRepo.onHighRewardScanEvaluated(
                            symbol = pair.symbol,
                            result = hrResult,
                            playSound = playHrSound,
                            source = "On-Demand 1:5 Radar"
                        )
                        scannedCount++
                    }
                } catch (_: Exception) {
                }
            }
            futuresScannerRepo.updateScannerStatus(
                status = com.example.data.scanner.ScannerStatus.IDLE,
                currentlyScanning = null
            )
            val hrCount = futuresScannerRepo.highRewardState.value.activeOpportunities.size
            _userMessage.value = "Futures scan completed ($scannedCount pairs | $hrCount active >=1:4/1:5 setups)"
        }
    }

    fun inspectAssetInAnalysis(assetId: String, symbol: String) {
        val cleanSym = symbol.replace("/USDT", "").replace("/USD", "").replace("/", "")
        _analysisAssetSymbol.value = cleanSym
        _currentTab.value = NavigationTab.ANALYSIS
        loadTechnicalAnalysis(cleanSym, _analysisTimeframe.value)
    }

    fun setPriceAlertsEnabled(enabled: Boolean) {
        repository.notificationManager.setPriceAlertsEnabled(enabled)
    }

    fun setLongSignalsEnabled(enabled: Boolean) {
        repository.notificationManager.setLongSignalsEnabled(enabled)
    }

    fun setShortSignalsEnabled(enabled: Boolean) {
        repository.notificationManager.setShortSignalsEnabled(enabled)
    }

    fun setNewsAlertsEnabled(enabled: Boolean) {
        repository.notificationManager.setNewsAlertsEnabled(enabled)
    }

    fun setMacroEventsEnabled(enabled: Boolean) {
        repository.notificationManager.setMacroEventsEnabled(enabled)
    }

    fun setWatchlistUpdatesEnabled(enabled: Boolean) {
        repository.notificationManager.setWatchlistUpdatesEnabled(enabled)
    }

    fun connectApiFeed(feedId: String, apiKey: String) {
        repository.connectApiFeed(feedId, apiKey)
        _selectedApiConfigForEdit.value = null
        _userMessage.value = "API feed connected successfully"
    }

    fun disconnectApiFeed(feedId: String) {
        repository.disconnectApiFeed(feedId)
        _selectedApiConfigForEdit.value = null
        _userMessage.value = "API feed disconnected"
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    fun bindLearningContext(context: Context) {
        val appContext = context.applicationContext ?: context
        repository.notificationManager.bindContext(appContext)
        futuresScannerRepo.bindContext(appContext)
        com.example.data.alerts.BackgroundAlertWorker.schedulePeriodicMonitoring(appContext)
        learningPrefsRepo.bindContext(appContext)
        appSettingsRepo.bindContext(appContext)
        com.example.data.remote.NexusFirebaseMessagingService.syncTopicSubscriptions(
            context = appContext,
            enabled = appSettingsRepo.alertNotificationsEnabled.value
        )
        nexusAiRepo = com.example.data.ai.NexusAiRepository(appContext)
        newsTranslationService = com.example.data.news.NewsTranslationService(appContext)
        _aiConversations.value = nexusAiRepo.loadConversations()
        val activeId = _aiConversations.value.firstOrNull()?.id ?: "default_session"
        _currentAiConversationId.value = activeId
        _aiMessages.value = nexusAiRepo.loadMessages(activeId)
        repository.setDataSavingMode(appSettingsRepo.isDataSavingMode.value)
        repository.setLowPerformanceMode(appSettingsRepo.isLowPerformanceMode.value)
        repository.setRefreshIntervalSeconds(appSettingsRepo.refreshIntervalSeconds.value)
        _learningUpdate.value++
    }

    fun setUrduFontSize(size: com.example.data.settings.UrduFontSize) {
        appSettingsRepo.setUrduFontSize(size)
        _learningUpdate.value++
    }

    // ==========================================
    // TASK 10: NEXUS AI Methods
    // ==========================================

    fun sendAiMessage(query: String) {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isBlank() || _isAiLoading.value) return
        val nowMs = System.currentTimeMillis()
        if (trimmedQuery == lastAiSentQuery && (nowMs - lastAiSentTimestampMs) < 2_000L) return
        lastAiSentQuery = trimmedQuery
        lastAiSentTimestampMs = nowMs

        val currentId = _currentAiConversationId.value
        val userMsg = com.example.data.ai.NexusChatMessage(
            conversationId = currentId,
            sender = com.example.data.ai.MessageSender.USER,
            text = trimmedQuery
        )
        val baseList = _aiMessages.value + userMsg
        _aiMessages.value = baseList
        _isAiLoading.value = true
        _lastAiError.value = null
        _lastAiErrorState.value = null
        updateConversationTitleIfDefault(currentId, trimmedQuery)

        executeAiTurn(
            conversationId = currentId,
            query = trimmedQuery,
            baseList = baseList
        )
    }

    fun switchAiProvider(provider: com.example.data.ai.AiProviderType) {
        _selectedAiProvider.value = provider
        _lastAiError.value = null
        _lastAiErrorState.value = null
        _learningUpdate.value++
    }

    fun retryLastFailedAiQuery() {
        if (_isAiLoading.value) return
        val err = _lastAiErrorState.value ?: return
        val currentId = _currentAiConversationId.value
        val currentList = _aiMessages.value
        _isAiLoading.value = true
        _lastAiError.value = null
        _lastAiErrorState.value = null

        executeAiTurn(
            conversationId = currentId,
            query = err.failedQuery,
            baseList = currentList
        )
    }

    fun runExplicitOfflineFallback() {
        if (_isAiLoading.value) return
        val err = _lastAiErrorState.value ?: return
        val currentId = _currentAiConversationId.value
        val offlineMsg = nexusAiRepo.runOfflineRuleBasedAnalysis(
            userPrompt = err.failedQuery,
            contextSnapshot = err.contextSnapshot,
            languageMode = appSettingsRepo.languageMode.value,
            unavailableReason = "${err.provider.badgeLabel}: ${err.errorMessage}",
            recentHistory = _aiMessages.value.takeLast(16)
        ).copy(conversationId = currentId)
        val finalized = _aiMessages.value + offlineMsg
        _aiMessages.value = finalized
        nexusAiRepo.saveMessages(currentId, finalized)
        _lastAiError.value = null
        _lastAiErrorState.value = null
        _learningUpdate.value++
    }

    fun retryAiMessage(messageId: String) {
        if (_isAiLoading.value) return
        val currentList = _aiMessages.value
        val targetMsg = currentList.firstOrNull { it.id == messageId } ?: return
        val retryQuery = targetMsg.originalQueryForRetry
            ?: currentList.lastOrNull { it.sender == com.example.data.ai.MessageSender.USER }?.text
            ?: return

        val currentId = _currentAiConversationId.value
        val baseList = currentList.filterNot { it.id == messageId }
        _aiMessages.value = baseList
        _isAiLoading.value = true
        _lastAiError.value = null
        _lastAiErrorState.value = null

        executeAiTurn(
            conversationId = currentId,
            query = retryQuery,
            baseList = baseList
        )
    }

    fun dismissAiError() {
        _lastAiError.value = null
        _lastAiErrorState.value = null
    }

    private fun executeAiTurn(
        conversationId: String,
        query: String,
        baseList: List<com.example.data.ai.NexusChatMessage>
    ) {
        viewModelScope.launch {
            try {
                // 1. Ensure fresh live Binance & news feeds with tight bounds so optional feeds never stall AI
                val now = System.currentTimeMillis()
                val btcAsset = repository.assets.value.firstOrNull { it.id.equals("BTC", ignoreCase = true) }
                val isMarketMissingOrStale = btcAsset?.currentPrice == null ||
                    btcAsset.lastUpdated == null ||
                    (now - btcAsset.lastUpdated) > 20_000L

                val marketRefreshJob = if (isMarketMissingOrStale) {
                    async {
                        kotlinx.coroutines.withTimeoutOrNull(6_000L) {
                            try {
                                repository.refreshData()
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (_: Exception) { }
                        }
                    }
                } else null

                val newsRefreshJob = if (!repository.financialNewsService.lastFetchWasLive) {
                    async {
                        kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                            try {
                                repository.refreshNews(forceRefresh = false)
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (_: Exception) { }
                        }
                    }
                } else null

                marketRefreshJob?.await()
                newsRefreshJob?.await()

                // 2. Resolve target asset(s) from user query, per-conversation memory, or active screen
                val allAssets = repository.assets.value
                val mentionedAssets = com.example.data.ai.MarketContextBuilder.detectMentionedAssets(query, allAssets)
                val targetAssetId = mentionedAssets.firstOrNull()
                    ?: lastDiscussedAssetByConversation[conversationId]
                    ?: _selectedAssetId.value
                    ?: _analysisAssetSymbol.value
                lastDiscussedAssetByConversation[conversationId] = targetAssetId

                val activeAsset = allAssets.firstOrNull { it.id.equals(targetAssetId, ignoreCase = true) }
                    ?: allAssets.firstOrNull { it.id.equals("BTC", ignoreCase = true) }
                    ?: allAssets.firstOrNull()

                val resolvedSymbol = activeAsset?.id ?: "BTC"
                val resolvedName = activeAsset?.name ?: resolvedSymbol
                val tf = _analysisTimeframe.value

                val secondaryAssetId = mentionedAssets.firstOrNull { !it.equals(resolvedSymbol, ignoreCase = true) }

                // 3. Gather fresh candles, technical indicators, SMC, and Binance Futures derivatives in parallel
                val useExistingAnalysis = resolvedSymbol.equals(_analysisAssetSymbol.value, ignoreCase = true) &&
                    _technicalAnalysis.value != null &&
                    _smcAnalysis.value != null &&
                    _analysisCandles.value.isNotEmpty()

                val candlesDeferred = async {
                    if (useExistingAnalysis) {
                        _analysisCandles.value
                    } else {
                        kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                            try {
                                repository.getCandles(resolvedSymbol, tf, 160)
                            } catch (ce: kotlinx.coroutines.CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                emptyList()
                            }
                        } ?: emptyList()
                    }
                }
                val derivativesDeferred = async {
                    kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                        try {
                            repository.fetchDerivativesForAsset(resolvedSymbol)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                val secondaryAssetDeferred = secondaryAssetId?.let { secId ->
                    async {
                        buildSecondaryComparisonSummary(secId, allAssets, tf)
                    }
                }

                val candles = candlesDeferred.await()

                val taResult = if (useExistingAnalysis) {
                    _technicalAnalysis.value
                } else if (candles.size >= 15) {
                    com.example.data.indicators.TechnicalIndicatorsEngine.analyze(resolvedSymbol, resolvedName, tf, candles)
                } else null

                val smcResult = if (useExistingAnalysis) {
                    _smcAnalysis.value
                } else if (candles.size >= 15) {
                    kotlinx.coroutines.withTimeoutOrNull(4_000L) {
                        try {
                            repository.calculateSmcAnalysis(resolvedSymbol, resolvedName, tf)
                        } catch (ce: kotlinx.coroutines.CancellationException) {
                            throw ce
                        } catch (_: Exception) {
                            null
                        }
                    }
                } else null

                val matchedAiSignal = (_aiSignalsList.value.ifEmpty { repository.aiSignals.value })
                    .firstOrNull { it.symbol.equals(resolvedSymbol, ignoreCase = true) }
                    ?: _currentAiSignal.value?.takeIf { it.symbol.equals(resolvedSymbol, ignoreCase = true) }

                val matchedFuturesSignal = futuresScannerRepo.scannerState.value.activeSignals.firstOrNull {
                    it.baseAsset.equals(resolvedSymbol, ignoreCase = true) ||
                        query.contains(it.symbol, ignoreCase = true) ||
                        query.contains(it.baseAsset, ignoreCase = true)
                } ?: futuresScannerRepo.scannerState.value.watchlistSetups.firstOrNull {
                    it.baseAsset.equals(resolvedSymbol, ignoreCase = true)
                } ?: futuresScannerRepo.scannerState.value.activeSignals.firstOrNull()

                val derivativesSnapshot = derivativesDeferred.await()
                val comparisonSummary = secondaryAssetDeferred?.await()
                val workflowSummary = buildWorkflowContextSummary()

                val contextSnapshot = com.example.data.ai.MarketContextBuilder.buildSnapshot(
                    asset = activeAsset,
                    candles = candles,
                    timeframe = tf,
                    technicalAnalysis = taResult,
                    smcAnalysis = smcResult,
                    signal = matchedAiSignal,
                    newsList = repository.news.value,
                    futuresSignal = matchedFuturesSignal,
                    allAssets = repository.assets.value,
                    marketOverview = repository.marketOverview.value,
                    scannerItems = repository.scannerItems.value,
                    activeFuturesSignals = futuresScannerRepo.scannerState.value.activeSignals,
                    watchlistSetups = futuresScannerRepo.scannerState.value.watchlistSetups,
                    allAiSignals = _aiSignalsList.value.ifEmpty { repository.aiSignals.value },
                    derivatives = derivativesSnapshot,
                    isLiveNewsFeed = repository.financialNewsService.lastFetchWasLive,
                    newsLastFetchedFormatted = repository.financialNewsService.lastFetchTimeFormatted,
                    hybridState = repository.hybridMarketState.value,
                    comparisonAssetSummary = comparisonSummary,
                    workflowContextSummary = workflowSummary
                )

                var finalizedList = baseList
                nexusAiRepo.generateResponseStream(
                    userPrompt = query,
                    contextSnapshot = contextSnapshot,
                    languageMode = appSettingsRepo.languageMode.value,
                    recentHistory = baseList.takeLast(18),
                    selectedProvider = _selectedAiProvider.value
                ).collect { event ->
                    when (event) {
                        is com.example.data.ai.NexusAiStreamResult.Chunk -> {
                            val streamingMsg = event.message.copy(conversationId = conversationId)
                            _aiMessages.value = baseList + streamingMsg
                        }
                        is com.example.data.ai.NexusAiStreamResult.Completed -> {
                            val completedMsg = event.message.copy(conversationId = conversationId)
                            finalizedList = baseList + completedMsg
                            _aiMessages.value = finalizedList
                            nexusAiRepo.saveMessages(conversationId, finalizedList)
                            _lastAiError.value = event.onlineErrorReason
                            _lastAiErrorState.value = null
                        }
                        is com.example.data.ai.NexusAiStreamResult.Error -> {
                            // Never hide online AI runtime errors behind silent fallback.
                            nexusAiRepo.saveMessages(conversationId, baseList)
                            _lastAiError.value = event.errorMessage
                            _lastAiErrorState.value = event
                            _learningUpdate.value++
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("TradingViewModel", "Error in executeAiTurn", e)
                val reason = e.localizedMessage ?: "Unexpected runtime error"
                val fallbackSnap = com.example.data.ai.MarketContextBuilder.buildSnapshot(
                    asset = repository.assets.value.firstOrNull { it.id == "BTC" },
                    candles = emptyList(),
                    hybridState = repository.hybridMarketState.value
                )
                _lastAiError.value = reason
                _lastAiErrorState.value = com.example.data.ai.NexusAiStreamResult.Error(
                    provider = _selectedAiProvider.value,
                    errorMessage = reason,
                    failedQuery = query,
                    contextSnapshot = fallbackSnap
                )
                _learningUpdate.value++
            } finally {
                _isAiLoading.value = false
            }
        }
    }

    private suspend fun buildSecondaryComparisonSummary(
        secondaryAssetId: String,
        allAssets: List<MarketAsset>,
        timeframe: CandleTimeframe
    ): String? {
        val asset = allAssets.firstOrNull { it.id.equals(secondaryAssetId, ignoreCase = true) } ?: return null
        val candles = kotlinx.coroutines.withTimeoutOrNull(4_000L) {
            try {
                repository.getCandles(asset.id, timeframe, 100)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                emptyList()
            }
        } ?: emptyList()

        val ta = if (candles.size >= 15) {
            com.example.data.indicators.TechnicalIndicatorsEngine.analyze(asset.id, asset.name, timeframe, candles)
        } else null
        val deriv = kotlinx.coroutines.withTimeoutOrNull(3_000L) {
            try {
                repository.fetchDerivativesForAsset(asset.id)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Exception) {
                null
            }
        }

        val priceStr = asset.currentPrice?.let { "$${String.format(java.util.Locale.US, "%.2f", it)}" } ?: "UNAVAILABLE"
        val chgStr = asset.change24hPercent?.let { String.format(java.util.Locale.US, "%+.2f%%", it) } ?: "N/A"
        val rsiStr = ta?.momentum?.rsi14?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "Unavailable"
        val ema20 = ta?.trend?.emaSet?.ema20
        val ema50 = ta?.trend?.emaSet?.ema50
        val emaTrend = if (ema20 != null && ema50 != null) {
            if (ema20 > ema50) "Bullish (EMA20 > EMA50)" else "Bearish (EMA20 < EMA50)"
        } else "Unavailable"
        val fundingStr = deriv?.fundingRatePercent?.let { String.format(java.util.Locale.US, "%+.4f%%", it) } ?: "Unavailable"

        return "${asset.name} (${asset.symbol}) | Price: $priceStr (24h: $chgStr) | Status: ${asset.dataStatus.label} | ${timeframe.label} RSI(14): $rsiStr | EMA Trend: $emaTrend | Funding: $fundingStr"
    }

    private fun buildWorkflowContextSummary(): String {
        val budgetCfg = futuresScannerRepo.budgetConfig.value
        val ledger = futuresScannerRepo.dailyRiskLedger.value
        val paperOrders = futuresScannerRepo.paperOrders.value
        val alertRules = futuresScannerRepo.tradeAlertRules.value
        val wf = _tradeWorkflow.value

        return buildString {
            appendLine(
                "Budget Config: Balance=${String.format(java.util.Locale.US, "%.2f", budgetCfg.accountBalanceUsdt)} USDT | " +
                    "Max Risk/Trade=${String.format(java.util.Locale.US, "%.2f", budgetCfg.maxRiskPerTradeAmount)} ${budgetCfg.riskCurrency.label} " +
                    "(Cap ${String.format(java.util.Locale.US, "%.1f%%", budgetCfg.maxRiskPercent)}) | " +
                    "Leverage Limit=${budgetCfg.leverageLimit}x | Min R:R=${String.format(java.util.Locale.US, "%.2f", budgetCfg.minRiskRewardRatio)} | " +
                    "Fee=${budgetCfg.feeRatePercentPerSide}%/side | Slippage=${budgetCfg.slippagePercentPerSide}%/side"
            )
            appendLine(
                "Daily Risk Ledger (${ledger.dateKeyPkt} PKT): Trades Today=${ledger.tradesTakenToday}/${budgetCfg.maxTradesPerDay} | " +
                    "Realized PnL=${String.format(java.util.Locale.US, "%+.2f", ledger.realizedPnlUsdtToday)} USDT | " +
                    "Open Paper Orders=${paperOrders.size} | Active Lifecycle Alert Rules=${alertRules.count { it.isActive }} | Execution Mode=Paper Safe Mode"
            )
            wf.selectedSignal?.let { sig ->
                appendLine("Selected Workflow Setup: ${sig.symbol} ${sig.direction.name} (${sig.detectedPattern.label}) | Entry=${sig.entryZoneFormatted} | SL=${sig.stopLossFormatted} | TP2=${sig.tp2Formatted}")
            }
            wf.budgetPlan?.let { bp ->
                if (bp.isValid) {
                    appendLine(
                        "Calculated Size: Qty=${bp.positionQuantityFormatted} | Notional=$${String.format(java.util.Locale.US, "%.2f", bp.notionalValueUsdt)} | " +
                            "Est. Margin=$${String.format(java.util.Locale.US, "%.2f", bp.estimatedMarginUsdt)} | " +
                            "Max Planned Loss=$${String.format(java.util.Locale.US, "%.2f", bp.maximumPlannedLossUsdt)} | " +
                            "Est. Liquidation=${String.format(java.util.Locale.US, "%.2f", bp.estimatedLiquidationPrice)} (${String.format(java.util.Locale.US, "%.1f%%", bp.liquidationDistancePercent)} away)"
                    )
                }
            }
            wf.riskAssessment?.let { risk ->
                val blockers = risk.blockedReasons.joinToString("; ").ifBlank { "None" }
                appendLine("Risk Guard Decision: ${risk.overallDecision.label} | Can Proceed=${risk.canProceedToOrder} | Blockers: $blockers")
            }
            wf.backtestResult?.let { bt ->
                appendLine(
                    "Time Machine Backtest (${bt.symbol} ${bt.timeframe.label}, ${bt.testPeriodStartPkt} -> ${bt.testPeriodEndPkt}): " +
                        "Samples=${bt.sampleSize}, TP-First=${bt.tpFirstCount}, SL-First=${bt.slFirstCount}, Ambiguous=${bt.ambiguousSameCandleCount}, Unresolved=${bt.unresolvedCount} | " +
                        "${bt.winRateSummaryFormatted} | Avg R:R=${String.format(java.util.Locale.US, "%.2f", bt.averageRewardRisk)}"
                )
            }
            wf.monteCarloResult?.let { mc ->
                if (mc.isSufficientData) {
                    appendLine(
                        "Monte Carlo (${mc.iterations} runs, Seed=${mc.seed}, Horizon=${mc.simulationHorizonBars} bars): " +
                            "TP-First=${String.format(java.util.Locale.US, "%.1f%%", mc.tpFirstPercent)} | " +
                            "SL-First=${String.format(java.util.Locale.US, "%.1f%%", mc.slFirstPercent)} | " +
                            "Unresolved=${String.format(java.util.Locale.US, "%.1f%%", mc.neitherReachedPercent)} " +
                            "(Historical bootstrap simulation ONLY — NOT a guaranteed win rate)"
                    )
                } else {
                    appendLine("Monte Carlo: Insufficient historical data (${mc.insufficientDataReason ?: "need more completed candles/setups"}). Never fabricated.")
                }
            }
        }.trim()
    }

    private fun updateConversationTitleIfDefault(conversationId: String, firstQuery: String) {
        val currentConvs = _aiConversations.value
        val target = currentConvs.firstOrNull { it.id == conversationId } ?: return
        val currentLang = appSettingsRepo.languageMode.value
        if (target.title == "Market Analysis" || target.title.startsWith("Analysis ")) {
            val newTitle = firstQuery.take(32).let { if (firstQuery.length > 32) "$it…" else it }
            val updated = currentConvs.map {
                if (it.id == conversationId) {
                    it.copy(title = newTitle, lastActiveAt = System.currentTimeMillis(), languageMode = currentLang)
                } else it
            }
            _aiConversations.value = updated
            nexusAiRepo.saveConversations(updated)
        } else {
            val updated = currentConvs.map {
                if (it.id == conversationId) {
                    it.copy(lastActiveAt = System.currentTimeMillis(), languageMode = currentLang)
                } else it
            }
            _aiConversations.value = updated
            nexusAiRepo.saveConversations(updated)
        }
    }

    fun newAiChat() {
        val newId = "chat_" + System.currentTimeMillis()
        val newConv = com.example.data.ai.NexusConversation(
            id = newId,
            title = "Analysis ${com.example.util.TimeZoneUtils.formatKarachiCompact(System.currentTimeMillis())}",
            languageMode = appSettingsRepo.languageMode.value
        )
        val updatedConvs = listOf(newConv) + _aiConversations.value
        _aiConversations.value = updatedConvs
        _currentAiConversationId.value = newId
        _aiMessages.value = emptyList()
        _lastAiError.value = null
        _lastAiErrorState.value = null
        lastDiscussedAssetByConversation.remove(newId)
        nexusAiRepo.saveConversations(updatedConvs)
    }

    fun clearAiChat() {
        val currentId = _currentAiConversationId.value
        _aiMessages.value = emptyList()
        _lastAiError.value = null
        _lastAiErrorState.value = null
        lastDiscussedAssetByConversation.remove(currentId)
        nexusAiRepo.saveMessages(currentId, emptyList())
        _userMessage.value = "Chat conversation cleared"
    }

    fun deleteAiConversation(id: String) {
        val updated = _aiConversations.value.filter { it.id != id }
        val finalConvs = if (updated.isEmpty()) {
            listOf(
                com.example.data.ai.NexusConversation(
                    id = "default_session",
                    title = "Market Analysis",
                    languageMode = appSettingsRepo.languageMode.value
                )
            )
        } else updated
        lastDiscussedAssetByConversation.remove(id)
        _aiConversations.value = finalConvs
        nexusAiRepo.saveConversations(finalConvs)
        nexusAiRepo.saveMessages(id, emptyList())
        val nextConv = finalConvs.first()
        _currentAiConversationId.value = nextConv.id
        _aiMessages.value = nexusAiRepo.loadMessages(nextConv.id)
        _lastAiError.value = null
        _lastAiErrorState.value = null
    }

    fun selectAiConversation(id: String) {
        _currentAiConversationId.value = id
        _aiMessages.value = nexusAiRepo.loadMessages(id)
        _lastAiError.value = null
        _lastAiErrorState.value = null
        _aiConversations.value.firstOrNull { it.id == id }?.let { conv ->
            if (conv.languageMode != appSettingsRepo.languageMode.value) {
                setAppLanguageMode(conv.languageMode)
            }
        }
    }

    fun attachCurrentMarketToAi() {
        val activeAsset = _selectedAssetId.value?.let { id ->
            repository.assets.value.firstOrNull { it.id.equals(id, ignoreCase = true) }
        } ?: repository.assets.value.firstOrNull { it.id.equals(_analysisAssetSymbol.value, ignoreCase = true) }
        ?: repository.assets.value.firstOrNull { it.id.equals("BTC", ignoreCase = true) }
        val sym = activeAsset?.symbol ?: "BTCUSDT"
        sendAiMessage("Analyze current live market condition, derivatives, support/resistance, and balanced scenarios for $sym")
    }

    fun setThemeMode(mode: AppThemeMode) {
        appSettingsRepo.setThemeMode(mode)
        _learningUpdate.value++
    }

    fun setAppLanguageMode(mode: LearningLanguageMode) {
        appSettingsRepo.setLanguageMode(mode)
        learningPrefsRepo.setLanguageMode(mode)
        _learningUpdate.value++
    }

    fun setLearningLanguageMode(mode: LearningLanguageMode) {
        setAppLanguageMode(mode)
    }

    fun setDataSavingMode(enabled: Boolean) {
        appSettingsRepo.setDataSavingMode(enabled)
        repository.setDataSavingMode(enabled)
        _learningUpdate.value++
        _userMessage.value = if (enabled) "Data Saving Mode Enabled" else "Data Saving Mode Disabled"
    }

    fun setLowPerformanceMode(enabled: Boolean) {
        appSettingsRepo.setLowPerformanceMode(enabled)
        repository.setLowPerformanceMode(enabled)
        _learningUpdate.value++
        _userMessage.value = if (enabled) "Low Performance Device Mode Enabled" else "Low Performance Device Mode Disabled"
    }

    fun setDefaultChartTimeframe(timeframe: CandleTimeframe) {
        appSettingsRepo.setDefaultTimeframe(timeframe)
        _analysisTimeframe.value = timeframe
        _selectedSignalTimeframe.value = timeframe
        _learningUpdate.value++
    }

    fun setDefaultWatchlistCategory(category: AssetCategory) {
        appSettingsRepo.setDefaultWatchlistCategory(category)
        _selectedCategory.value = category
        _learningUpdate.value++
    }

    fun setAlertNotificationsEnabled(enabled: Boolean) {
        appSettingsRepo.setAlertNotificationsEnabled(enabled)
        com.example.data.remote.NexusFirebaseMessagingService.syncTopicSubscriptions(enabled = enabled)
        _learningUpdate.value++
    }

    fun setSoundEffectsEnabled(enabled: Boolean) {
        appSettingsRepo.setSoundEffectsEnabled(enabled)
        _learningUpdate.value++
    }

    fun setRefreshRate(rate: RefreshRatePreference) {
        appSettingsRepo.setRefreshIntervalSeconds(rate.seconds)
        repository.setRefreshIntervalSeconds(rate.seconds)
        _learningUpdate.value++
    }

    fun clearTemporaryCache() {
        val repoPurged = repository.clearCache()
        newsTranslationService.clearCache()
        appSettingsRepo.clearTemporaryCache()
        _learningUpdate.value++
        _userMessage.value = "Cache cleared: $repoPurged buffers freed"
    }

    fun setLearningSearchQuery(query: String) {
        _learningSearchQuery.value = query
        _learningUpdate.value++
    }

    fun selectLessonCategory(category: LessonCategory?) {
        _selectedLessonCategory.value = category
        _learningUpdate.value++
    }

    fun openLessonDetail(lesson: LessonItem) {
        learningPrefsRepo.recordLessonViewed(lesson.id)
        _selectedLessonForDetail.value = lesson
        _learningUpdate.value++
    }

    fun closeLessonDetail() {
        _selectedLessonForDetail.value = null
        _learningUpdate.value++
    }

    fun toggleLessonBookmark(lessonId: String) {
        learningPrefsRepo.toggleBookmark(lessonId)
        _learningUpdate.value++
    }

    fun toggleLessonCompleted(lessonId: String) {
        learningPrefsRepo.toggleCompleted(lessonId)
        _learningUpdate.value++
    }

    fun openPreviousLesson() {
        val current = _selectedLessonForDetail.value ?: return
        val all: List<LessonItem> = LearningContentDatabase.allLessons
        val index = all.indexOfFirst { it.id == current.id }
        if (index > 0) {
            openLessonDetail(all[index - 1])
        } else if (all.isNotEmpty()) {
            openLessonDetail(all.last())
        }
    }

    fun openNextLesson() {
        val current = _selectedLessonForDetail.value ?: return
        val all: List<LessonItem> = LearningContentDatabase.allLessons
        val index = all.indexOfFirst { it.id == current.id }
        if (index >= 0 && index < all.size - 1) {
            openLessonDetail(all[index + 1])
        } else if (all.isNotEmpty()) {
            openLessonDetail(all.first())
        }
    }

    fun navigateToLessonInAcademy(lessonId: String) {
        val lesson = LearningContentDatabase.findLesson(lessonId)
        if (lesson != null) {
            _selectedLessonForDetail.value = lesson
            learningPrefsRepo.recordLessonViewed(lesson.id)
            _selectedGlossaryTerm.value = null
            _currentTab.value = NavigationTab.LEARNING
            _learningUpdate.value++
        }
    }

    fun setScannerScreenMode(mode: Int) {
        _scannerScreenMode.value = mode
        _learningUpdate.value++
    }

    fun startFuturesScanner(context: android.content.Context) {
        com.example.data.scanner.BinanceFuturesScannerService.startService(context)
        _userMessage.value = "Binance Futures Scanner Started"
    }

    fun stopFuturesScanner(context: android.content.Context) {
        com.example.data.scanner.BinanceFuturesScannerService.stopService(context)
        _userMessage.value = "Binance Futures Scanner Stopped"
    }

    fun toggleFuturesWatchlistPair(symbol: String) {
        futuresScannerRepo.togglePairScanning(symbol)
        _learningUpdate.value++
    }

    fun addCustomFuturesPair(symbol: String) {
        futuresScannerRepo.addCustomPair(symbol)
        _learningUpdate.value++
    }

    fun removeFuturesWatchlistPair(symbol: String) {
        futuresScannerRepo.removePair(symbol)
        _learningUpdate.value++
    }

    fun setFuturesScannerInterval(seconds: Int) {
        futuresScannerRepo.setScanInterval(seconds)
        _learningUpdate.value++
    }

    fun setFuturesScannerPaperTrading(enabled: Boolean) {
        futuresScannerRepo.setPaperTradingEnabled(enabled)
        _learningUpdate.value++
    }

    fun explainFuturesSignalWithAi(signal: com.example.data.scanner.FuturesScanSignal) {
        val timerNote = signal.entryTimer?.let { "${it.status.label} (${it.statusExplanation})" } ?: signal.lifecycle.label
        val query = "Please explain the ${signal.symbol} ${signal.direction.name} potential setup (${signal.detectedPattern.label}). Quality score is ${signal.qualityScore}/100 (${signal.qualityRating}). Entry Timer status is $timerNote. Break down 4H/1H/15M confluences, the Pre-Entry Exit Plan (SL ${signal.stopLossFormatted}, TP1 ${signal.tp1Formatted}, TP2 ${signal.tp2Formatted}, TP3 ${signal.tp3Formatted}), invalidation rules, and risks."
        selectTab(NavigationTab.NEXUS_AI)
        sendAiMessage(query)
    }

    fun simulateFuturesPaperTrade(signal: com.example.data.scanner.FuturesScanSignal) {
        openTradeWorkflow(signal)
    }

    fun setStrict92ConfidenceMode(enabled: Boolean) {
        futuresScannerRepo.setStrict92ConfidenceMode(enabled)
        _userMessage.value = if (enabled) {
            "Strict >=92% Model Confidence Filter Enabled"
        } else {
            "Standard Multi-Timeframe Confluence Filter Restored"
        }
        _learningUpdate.value++
    }

    fun openTradeWorkflow(signal: com.example.data.scanner.FuturesScanSignal) {
        val newsMatches = repository.news.value.filter { n ->
            n.affectedAssets.any { it.equals(signal.baseAsset, ignoreCase = true) || it.equals(signal.symbol, ignoreCase = true) } ||
                n.impactLevel == com.example.data.model.NewsImpactLevel.HIGH ||
                n.title.contains(signal.baseAsset, ignoreCase = true)
        }
        val budgetCfg = futuresScannerRepo.budgetConfig.value
        val budgetPlan = com.example.data.risk.BudgetManagerEngine.calculatePlanForSignal(
            signal = signal,
            budgetConfig = budgetCfg
        )
        val riskAssessment = com.example.data.risk.RiskManagerEngine.evaluate(
            signal = signal,
            budgetConfig = budgetCfg,
            budgetPlan = budgetPlan,
            requestedLeverage = signal.suggestedLeverage,
            dailyLedger = futuresScannerRepo.dailyRiskLedger.value
        )
        val ticket = com.example.data.broker.PaperOrderBrokerEngine.buildProposedTicket(
            signal = signal,
            budgetPlan = budgetPlan,
            mode = _tradeWorkflow.value.executionMode
        )

        _tradeWorkflow.value = _tradeWorkflow.value.copy(
            isVisible = true,
            selectedSignal = signal,
            relatedNews = newsMatches,
            isLoadingHistory = true,
            historyLoadError = null,
            backtestResult = null,
            monteCarloResult = null,
            budgetConfig = budgetCfg,
            budgetPlan = budgetPlan,
            riskAssessment = riskAssessment,
            proposedTicket = ticket,
            userExplicitlyConfirmed = false,
            submissionOutcome = null
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val candles = repository.futuresApiClient.fetchFuturesCandles(
                    symbol = signal.symbol,
                    timeframe = signal.primaryTimeframe,
                    limit = 400
                )
                runTimeMachineForWorkflow(
                    signal = signal,
                    candles = candles,
                    btConfig = _tradeWorkflow.value.backtestConfig,
                    seed = _tradeWorkflow.value.monteCarloSeed
                )
            } catch (e: Exception) {
                _tradeWorkflow.value = _tradeWorkflow.value.copy(
                    isLoadingHistory = false,
                    historyLoadError = "Could not fetch Binance OHLCV history: ${e.message ?: "Network unavailable"}"
                )
            }
        }
    }

    fun openHighRewardDetail(opportunity: com.example.data.scanner.HighRewardOpportunity) {
        _selectedHighRewardOpportunity.value = opportunity
        _learningUpdate.value++
    }

    fun closeHighRewardDetail() {
        _selectedHighRewardOpportunity.value = null
        _learningUpdate.value++
    }

    fun scanHighRewardOpportunitiesNow() {
        refreshFuturesScannerOnDemand()
    }

    fun openTradeWorkflowBySymbol(symbol: String, signalId: String? = null, alertTypeName: String? = null) {
        _currentTab.value = NavigationTab.SCANNER
        _scannerScreenMode.value = 0
        val isHighRewardDeepLink = alertTypeName == com.example.data.model.AlertType.OPPORTUNITY_1_TO_5.name ||
            alertTypeName == com.example.data.model.AlertType.OPPORTUNITY_1_TO_4.name ||
            signalId?.startsWith("hr_") == true

        val hrMatch = futuresScannerRepo.highRewardState.value.activeOpportunities.firstOrNull {
            (!signalId.isNullOrBlank() && it.id == signalId) || it.symbol.equals(symbol, ignoreCase = true)
        }
        if (hrMatch != null && isHighRewardDeepLink) {
            openHighRewardDetail(hrMatch)
            return
        }
        if (isHighRewardDeepLink) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val normSym = symbol.uppercase().let { if (it.endsWith("USDT")) it else "${it}USDT" }
                    val base = normSym.removeSuffix("USDT")
                    val c15m = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.M15, limit = 50)
                    val c1h = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.H1, limit = 50)
                    val c4h = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.H4, limit = 30)
                    val c5m = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.M5, limit = 30)
                    val price = com.example.util.TimeZoneUtils.filterCompletedCandles(c15m).lastOrNull()?.close ?: 0.0
                    if (price > 0.0) {
                        val funding = repository.futuresApiClient.fetchFundingRate(normSym)
                        val hrResult = com.example.data.scanner.HighRewardOpportunityDetector.scanPair(
                            symbol = normSym,
                            baseAsset = base,
                            currentPrice = price,
                            candles4h = c4h,
                            candles1h = c1h,
                            candles15m = c15m,
                            candles5m = c5m,
                            fundingRatePercent = funding,
                            budgetConfig = futuresScannerRepo.budgetConfig.value
                        )
                        futuresScannerRepo.onHighRewardScanEvaluated(
                            symbol = normSym,
                            result = hrResult,
                            playSound = false,
                            source = "Deep-Link Re-Validation"
                        )
                        val liveOpp = hrResult.qualifiedOpportunities.firstOrNull()
                        if (liveOpp != null) {
                            openHighRewardDetail(liveOpp)
                        } else {
                            _userMessage.value = hrResult.bestDiagnostic.reason
                        }
                    }
                } catch (_: Exception) {
                    _userMessage.value = "Opened 1:5 Radar for $symbol"
                }
            }
            return
        }
        val state = futuresScannerRepo.scannerState.value
        val allSignals = state.activeSignals + state.watchlistSetups + state.signalHistory
        val match = (if (!signalId.isNullOrBlank()) allSignals.firstOrNull { it.id == signalId } else null)
            ?: allSignals.firstOrNull { it.symbol.equals(symbol, ignoreCase = true) }

        if (match != null) {
            openTradeWorkflow(match)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val normSym = symbol.uppercase().let { if (it.endsWith("USDT")) it else "${it}USDT" }
                    val base = normSym.removeSuffix("USDT")
                    val c15m = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.M15, limit = 60)
                    val c1h = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.H1, limit = 60)
                    val c4h = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.H4, limit = 30)
                    val c5m = repository.futuresApiClient.fetchFuturesCandles(normSym, CandleTimeframe.M5, limit = 30)
                    val price = com.example.util.TimeZoneUtils.filterCompletedCandles(c15m).lastOrNull()?.close ?: 0.0
                    if (price > 0.0) {
                        val eval = com.example.data.scanner.BinanceFuturesScannerEngine.evaluatePair(
                            symbol = normSym,
                            baseAsset = base,
                            currentPrice = price,
                            candles4h = c4h,
                            candles1h = c1h,
                            candles15m = c15m,
                            candles5m = c5m
                        )
                        val sig = eval.signal
                        if (sig != null) {
                            futuresScannerRepo.onSignalEvaluated(sig)
                            openTradeWorkflow(sig)
                        } else {
                            _userMessage.value = eval.noTradeReason ?: "No qualifying setup currently active on $normSym"
                        }
                    }
                } catch (_: Exception) {
                    _userMessage.value = "Opened Scanner for $symbol"
                }
            }
        }
    }

    fun closeTradeWorkflow() {
        _tradeWorkflow.value = _tradeWorkflow.value.copy(isVisible = false)
    }

    fun updateWorkflowBudgetConfig(newConfig: com.example.data.risk.BudgetConfig) {
        futuresScannerRepo.updateBudgetConfig(newConfig)
        val signal = _tradeWorkflow.value.selectedSignal ?: return
        val budgetPlan = com.example.data.risk.BudgetManagerEngine.calculatePlanForSignal(
            signal = signal,
            budgetConfig = newConfig
        )
        val riskAssessment = com.example.data.risk.RiskManagerEngine.evaluate(
            signal = signal,
            budgetConfig = newConfig,
            budgetPlan = budgetPlan,
            requestedLeverage = signal.suggestedLeverage,
            dailyLedger = futuresScannerRepo.dailyRiskLedger.value
        )
        val ticket = com.example.data.broker.PaperOrderBrokerEngine.buildProposedTicket(
            signal = signal,
            budgetPlan = budgetPlan,
            mode = _tradeWorkflow.value.executionMode
        )
        _tradeWorkflow.value = _tradeWorkflow.value.copy(
            budgetConfig = newConfig,
            budgetPlan = budgetPlan,
            riskAssessment = riskAssessment,
            proposedTicket = ticket,
            submissionOutcome = null
        )
    }

    fun rerunWorkflowTimeMachine(feePctPerSide: Double, slippagePctPerSide: Double, seed: Long) {
        val signal = _tradeWorkflow.value.selectedSignal ?: return
        val newBtConfig = _tradeWorkflow.value.backtestConfig.copy(
            feePercentPerSide = feePctPerSide,
            slippagePercentPerSide = slippagePctPerSide
        )
        _tradeWorkflow.value = _tradeWorkflow.value.copy(
            backtestConfig = newBtConfig,
            monteCarloSeed = seed,
            isLoadingHistory = true,
            historyLoadError = null
        )

        val cachedCandles = _tradeWorkflow.value.historicalCandles
        if (cachedCandles.isNotEmpty()) {
            runTimeMachineForWorkflow(signal, cachedCandles, newBtConfig, seed)
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val candles = repository.futuresApiClient.fetchFuturesCandles(
                        symbol = signal.symbol,
                        timeframe = signal.primaryTimeframe,
                        limit = 400
                    )
                    runTimeMachineForWorkflow(signal, candles, newBtConfig, seed)
                } catch (e: Exception) {
                    _tradeWorkflow.value = _tradeWorkflow.value.copy(
                        isLoadingHistory = false,
                        historyLoadError = "Could not fetch historical OHLCV data: ${e.message ?: "Network error"}"
                    )
                }
            }
        }
    }

    private fun runTimeMachineForWorkflow(
        signal: com.example.data.scanner.FuturesScanSignal,
        candles: List<Candle>,
        btConfig: com.example.data.timemachine.BacktestConfig,
        seed: Long
    ) {
        val btResult = com.example.data.timemachine.TimeMachineEngine.runBacktestForSignal(
            signal = signal,
            candles = candles,
            config = btConfig
        )
        val mcResult = com.example.data.timemachine.TimeMachineEngine.runMonteCarlo10000(
            symbol = signal.symbol,
            timeframe = signal.primaryTimeframe,
            direction = signal.direction,
            slDistancePercent = signal.slDistancePercent.coerceAtLeast(0.25),
            tpDistancePercent = signal.tp2DistancePercent.coerceAtLeast(0.4),
            candles = candles,
            backtestResult = btResult,
            mcConfig = com.example.data.timemachine.MonteCarloConfig(seed = seed),
            backtestConfig = btConfig
        )

        val updatedEvidence = signal.confidenceEvidence?.copy(
            empiricalHistoricalWinRatePercent = if (btResult.sampleSize > 0) btResult.empiricalWinRateConservativePercent else null,
            historicalSampleSize = btResult.sampleSize
        )
        val enrichedSignal = signal.copy(confidenceEvidence = updatedEvidence ?: signal.confidenceEvidence)

        _tradeWorkflow.value = _tradeWorkflow.value.copy(
            selectedSignal = enrichedSignal,
            historicalCandles = candles,
            isLoadingHistory = false,
            historyLoadError = if (candles.isEmpty()) "No completed historical OHLCV candles returned for ${signal.symbol}." else null,
            backtestResult = btResult,
            monteCarloResult = mcResult
        )
    }

    fun setWorkflowExecutionMode(mode: com.example.data.broker.ExecutionMode) {
        val signal = _tradeWorkflow.value.selectedSignal ?: return
        val budgetPlan = _tradeWorkflow.value.budgetPlan ?: return
        val ticket = com.example.data.broker.PaperOrderBrokerEngine.buildProposedTicket(
            signal = signal,
            budgetPlan = budgetPlan,
            mode = mode
        )
        _tradeWorkflow.value = _tradeWorkflow.value.copy(
            executionMode = mode,
            proposedTicket = ticket,
            submissionOutcome = null
        )
    }

    fun setWorkflowExplicitConfirmation(confirmed: Boolean) {
        _tradeWorkflow.value = _tradeWorkflow.value.copy(
            userExplicitlyConfirmed = confirmed,
            submissionOutcome = null
        )
    }

    fun submitWorkflowPaperOrder() {
        val wf = _tradeWorkflow.value
        val signal = wf.selectedSignal ?: return
        val ticket = wf.proposedTicket ?: return
        val risk = wf.riskAssessment ?: return

        val outcome = com.example.data.broker.PaperOrderBrokerEngine.submitOrder(
            ticket = ticket,
            riskAssessment = risk,
            userExplicitlyConfirmed = wf.userExplicitlyConfirmed,
            hasSeparateBackendExchangeAuthorization = false
        )

        if (outcome is com.example.data.broker.OrderSubmissionOutcome.PaperOrderSubmitted) {
            futuresScannerRepo.recordSubmittedPaperOrder(outcome.order)
            futuresScannerRepo.markPaperTradeExecuted(signal.id)
            val rules = com.example.data.alerts.AlertMonitorEngine.createLifecycleRulesForSignal(signal)
            futuresScannerRepo.addTradeAlertRules(rules)

            val updatedBudgetPlan = wf.budgetPlan
            val refreshedRisk = if (updatedBudgetPlan != null) {
                com.example.data.risk.RiskManagerEngine.evaluate(
                    signal = signal,
                    budgetConfig = wf.budgetConfig,
                    budgetPlan = updatedBudgetPlan,
                    requestedLeverage = signal.suggestedLeverage,
                    dailyLedger = futuresScannerRepo.dailyRiskLedger.value
                )
            } else risk

            _tradeWorkflow.value = wf.copy(
                riskAssessment = refreshedRisk,
                submissionOutcome = outcome,
                registeredAlertRulesCount = rules.size
            )
            _userMessage.value = outcome.message
        } else {
            _tradeWorkflow.value = wf.copy(submissionOutcome = outcome)
        }
    }

    private data class TabAssetsOverviewSignalsAlerts(
        val tab: NavigationTab,
        val assets: List<MarketAsset>,
        val overview: MarketOverview,
        val signals: List<Signal>,
        val alerts: List<MarketAlert>
    )

    private data class NewsScannerApiRefreshingWs(
        val news: List<MarketNews>,
        val scanner: List<ScannerItem>,
        val apiConfigs: List<ApiFeedConfig>,
        val isRefreshing: Boolean,
        val wsLive: Boolean
    )

    private data class CategoryStatusSearchFilters(
        val category: AssetCategory,
        val statFilter: String,
        val search: String,
        val sigFilter: String,
        val timeframe: Timeframe
    )

    private data class DialogStates(
        val assetId: String?,
        val newsDetail: MarketNews?,
        val addAlert: Boolean,
        val defaultSymbol: String,
        val apiConfigEdit: ApiFeedConfig?
    )

    private data class AnalysisCoreState(
        val analysis: OverallAnalysisResult?,
        val isCalculating: Boolean,
        val symbol: String,
        val timeframe: CandleTimeframe,
        val category: IndicatorCategory
    )
}
