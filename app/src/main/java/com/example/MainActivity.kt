package com.example

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.NavigationTab
import com.example.data.subscription.AccessGateState
import com.example.data.subscription.GoogleAuthHelper
import com.example.data.subscription.GoogleSignInOutcome
import com.example.data.subscription.SubscriptionRepository
import com.example.ui.components.BottomNavBar
import com.example.ui.components.NexusTopBar
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.dialogs.AddAlertDialog
import com.example.ui.dialogs.ApiConfigDialog
import com.example.ui.dialogs.AssetDetailDialog
import com.example.ui.dialogs.EconomicGlossaryDialog
import com.example.ui.dialogs.NewsDetailDialog
import com.example.ui.dialogs.RomanUrduGlossaryDialog
import com.example.ui.screens.ActivationScreen
import com.example.ui.screens.AdminPanelScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.LearningCenterScreen
import com.example.ui.screens.MarketsScreen
import com.example.ui.screens.NewsScreen
import com.example.ui.screens.PaperTradingScreen
import com.example.ui.screens.ScannerScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SignalsScreen
import com.example.ui.screens.TechnicalAnalysisScreen
import com.example.ui.theme.NexusTheme
import com.example.ui.theme.NexusTradingTheme
import com.example.ui.viewmodel.SubscriptionViewModel
import com.example.ui.viewmodel.TradingViewModel
import com.example.util.configureAppCheck
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var activeDeepLinkIntent by mutableStateOf<android.content.Intent?>(null)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        activeDeepLinkIntent = intent
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeDeepLinkIntent = intent
        configureAppCheck(this, intent)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val firebaseAuth = remember { FirebaseAuth.getInstance() }
            val authSnackbarHostState = remember { SnackbarHostState() }
            var currentUser by remember { mutableStateOf<FirebaseUser?>(firebaseAuth.currentUser) }
            var isSigningIn by remember { mutableStateOf(false) }
            var authError by remember { mutableStateOf<String?>(null) }

            DisposableEffect(firebaseAuth) {
                val listener = FirebaseAuth.AuthStateListener { auth ->
                    currentUser = auth.currentUser
                }
                firebaseAuth.addAuthStateListener(listener)
                onDispose { firebaseAuth.removeAuthStateListener(listener) }
            }

            NexusTradingTheme {
                val user = currentUser
                if (user == null) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AuthScreen(
                            isSigningIn = isSigningIn,
                            errorMessage = authError,
                            onGoogleSignInClick = {
                                if (!isSigningIn) {
                                    isSigningIn = true
                                    authError = null
                                    scope.launch {
                                        when (val outcome = GoogleAuthHelper.signInWithGoogle(context)) {
                                            is GoogleSignInOutcome.Success -> {
                                                currentUser = firebaseAuth.currentUser ?: outcome.user
                                                authError = null
                                                isSigningIn = false
                                            }
                                            is GoogleSignInOutcome.Cancelled -> {
                                                authError = outcome.detailMessage
                                                isSigningIn = false
                                                Toast.makeText(context, outcome.detailMessage, Toast.LENGTH_LONG).show()
                                                authSnackbarHostState.showSnackbar(outcome.detailMessage)
                                            }
                                            is GoogleSignInOutcome.Failure -> {
                                                authError = outcome.message
                                                isSigningIn = false
                                                Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
                                                authSnackbarHostState.showSnackbar(outcome.message)
                                            }
                                        }
                                    }
                                }
                            }
                        )
                        SnackbarHost(
                            hostState = authSnackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .safeDrawingPadding()
                                .padding(12.dp)
                        )
                    }
                } else {
                    key(user.uid) {
                        val subRepo = remember(user.uid) { SubscriptionRepository(context.applicationContext) }
                        val subVm: SubscriptionViewModel = viewModel(
                            key = "sub_${user.uid}",
                            factory = SubscriptionViewModel.Factory(user.uid, user, subRepo)
                        )
                        val gateState by subVm.gateState.collectAsStateWithLifecycle()
                        val actionState by subVm.actionState.collectAsStateWithLifecycle()
                        val adminCodesState by subVm.adminCodesState.collectAsStateWithLifecycle()
                        val adminUsersState by subVm.adminUsersState.collectAsStateWithLifecycle()

                        val isAdmin = when (val gs = gateState) {
                            is AccessGateState.Authorized -> gs.isAdmin
                            is AccessGateState.NeedsActivation -> gs.isAdmin
                            else -> subRepo.isVerifiedBootstrapAdmin(user)
                        }
                        val supportConfig = when (val gs = gateState) {
                            is AccessGateState.Authorized -> gs.supportConfig
                            is AccessGateState.NeedsActivation -> gs.supportConfig
                            else -> com.example.data.subscription.SupportConfig()
                        }
                        val serverNowMs = when (val gs = gateState) {
                            is AccessGateState.Authorized -> gs.serverNowMs
                            is AccessGateState.NeedsActivation -> gs.serverNowMs
                            else -> System.currentTimeMillis()
                        }

                        if (actionState.showAdminPanel && isAdmin) {
                            AdminPanelScreen(
                                adminEmail = user.email.orEmpty(),
                                codesState = adminCodesState,
                                usersState = adminUsersState,
                                supportConfig = supportConfig,
                                actionState = actionState,
                                serverNowMs = serverNowMs,
                                onGenerateCode = { plan, note -> subVm.adminGenerateCode(plan, note) },
                                onRevokeCode = { rec -> subVm.adminRevokeCode(rec) },
                                onRevokeUserAccess = { u -> subVm.adminRevokeUserAccess(u) },
                                onToggleUserDisabled = { u, dis -> subVm.adminToggleUserDisabled(u, dis) },
                                onSaveSupportConfig = { phone, msg -> subVm.adminSaveSupportConfig(phone, msg) },
                                onClearMessages = { subVm.clearAdminMessages() },
                                onBackClick = { subVm.closeAdminPanel() }
                            )
                        } else {
                            when (val gs = gateState) {
                                AccessGateState.Loading -> {
                                    GateLoadingScreen(email = user.email.orEmpty())
                                }
                                is AccessGateState.Error -> {
                                    GateErrorScreen(
                                        message = gs.message,
                                        onRetry = { subVm.initializeSession() },
                                        onSignOut = {
                                            scope.launch { GoogleAuthHelper.signOut(context) }
                                        }
                                    )
                                }
                                is AccessGateState.NeedsActivation -> {
                                    ActivationScreen(
                                        state = gs,
                                        actionState = actionState,
                                        onSubmitCode = { code -> subVm.redeemActivationCode(code) },
                                        onRecheckAccess = { subVm.initializeSession() },
                                        onOpenAdminPanel = { subVm.openAdminPanel() },
                                        onSignOutClick = {
                                            scope.launch { GoogleAuthHelper.signOut(context) }
                                        }
                                    )
                                }
                                is AccessGateState.Authorized -> {
                                    val tradingViewModel: TradingViewModel = viewModel(key = "trade_${user.uid}")
                                    val uiState by tradingViewModel.uiState.collectAsStateWithLifecycle()
                                    NexusTradingTheme(themeMode = uiState.themeMode) {
                                        NexusTradingApp(
                                            viewModel = tradingViewModel,
                                            authorizedState = gs,
                                            pendingDeepLinkIntent = activeDeepLinkIntent,
                                            onDeepLinkConsumed = { activeDeepLinkIntent = null },
                                            onOpenAdminPanel = { subVm.openAdminPanel() },
                                            onSignOutClick = {
                                                scope.launch { GoogleAuthHelper.signOut(context) }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GateLoadingScreen(email: String) {
    val colors = NexusTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(color = colors.gold, modifier = Modifier.size(40.dp))
            Text(
                text = "Verifying NEXUS Subscription...",
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            if (email.isNotBlank()) {
                Text(text = email, color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun GateErrorScreen(
    message: String,
    onRetry: () -> Unit,
    onSignOut: () -> Unit
) {
    val colors = NexusTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Verification Error",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = colors.bearishRed,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(text = message, color = colors.textSecondary)
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.testTag("gate_retry_btn")
            ) {
                Text("Retry")
            }
            OutlinedButton(onClick = onSignOut) {
                Text("Sign Out")
            }
        }
    }
}

@Composable
fun NexusTradingApp(
    viewModel: TradingViewModel = viewModel(),
    authorizedState: AccessGateState.Authorized? = null,
    pendingDeepLinkIntent: android.content.Intent? = null,
    onDeepLinkConsumed: () -> Unit = {},
    onOpenAdminPanel: () -> Unit = {},
    onSignOutClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    LaunchedEffect(Unit) {
        viewModel.bindLearningContext(context)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val isGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!isGranted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    LaunchedEffect(pendingDeepLinkIntent) {
        val dl = pendingDeepLinkIntent ?: return@LaunchedEffect
        val navTabName = dl.getStringExtra(com.example.util.NexusNotificationManager.EXTRA_NAV_TAB)
        val signalSymbol = dl.getStringExtra(com.example.util.NexusNotificationManager.EXTRA_SIGNAL_SYMBOL)
        val signalId = dl.getStringExtra(com.example.util.NexusNotificationManager.EXTRA_SIGNAL_ID)
        val alertTypeName = dl.getStringExtra(com.example.util.NexusNotificationManager.EXTRA_ALERT_TYPE)
        val openWorkflow = dl.getBooleanExtra(com.example.util.NexusNotificationManager.EXTRA_OPEN_WORKFLOW, false)
        if (!navTabName.isNullOrBlank()) {
            NavigationTab.entries.firstOrNull { it.name == navTabName }?.let { viewModel.selectTab(it) }
        }
        if (openWorkflow && !signalSymbol.isNullOrBlank()) {
            viewModel.openTradeWorkflowBySymbol(signalSymbol, signalId, alertTypeName)
        }
        onDeepLinkConsumed()
    }

    // Trigger user messages in snackbar
    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearUserMessage()
        }
    }

    // Predictive back handler
    BackHandler(
        enabled = uiState.selectedHighRewardOpportunity != null ||
                uiState.tradeWorkflowState.isVisible ||
                uiState.selectedGlossaryTerm != null ||
                uiState.selectedEconomicTerm != null ||
                uiState.selectedLessonForDetail != null ||
                uiState.selectedAssetDetail != null ||
                uiState.selectedNewsDetail != null ||
                uiState.showAddAlertDialog ||
                uiState.selectedApiConfigForEdit != null ||
                uiState.currentTab != NavigationTab.DASHBOARD
    ) {
        when {
            uiState.selectedHighRewardOpportunity != null -> viewModel.closeHighRewardDetail()
            uiState.tradeWorkflowState.isVisible -> viewModel.closeTradeWorkflow()
            uiState.selectedGlossaryTerm != null -> viewModel.closeGlossaryDialog()
            uiState.selectedEconomicTerm != null -> viewModel.dismissEconomicGlossary()
            uiState.selectedLessonForDetail != null -> viewModel.closeLessonDetail()
            uiState.selectedAssetDetail != null -> viewModel.closeAssetDetail()
            uiState.selectedNewsDetail != null -> viewModel.closeNewsDetail()
            uiState.showAddAlertDialog -> viewModel.closeAddAlertDialog()
            uiState.selectedApiConfigForEdit != null -> viewModel.closeApiConfigDialog()
            uiState.currentTab != NavigationTab.DASHBOARD -> viewModel.selectTab(NavigationTab.DASHBOARD)
        }
    }

    val btcStatus = uiState.assets.firstOrNull { it.id == "BTC" }?.dataStatus ?: com.example.data.model.MarketDataStatus.UNAVAILABLE
    val activeAlertsCount = uiState.alerts.count { it.isActive && !it.isTriggered }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
        topBar = {
            NexusTopBar(
                isWebSocketLive = uiState.isWebSocketLive,
                btcStatus = btcStatus,
                activeAlertsCount = activeAlertsCount,
                isRefreshing = uiState.isRefreshing,
                isLowPerformanceMode = uiState.isLowPerformanceMode,
                onRefreshClick = { viewModel.refreshMarketData() },
                onAddAlertClick = { viewModel.openAddAlertDialog("BTC/USDT") },
                onOpenAnalysisClick = { viewModel.openTechnicalAnalysis("BTC") },
                onOpenLearningClick = { viewModel.selectTab(NavigationTab.LEARNING) }
            )
        },
        bottomBar = {
            BottomNavBar(
                currentTab = uiState.currentTab,
                onTabSelected = { tab -> viewModel.selectTab(tab) },
                languageMode = uiState.learningLanguageMode
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background)
        ) {
            when (uiState.currentTab) {
                NavigationTab.DASHBOARD -> {
                    DashboardScreen(
                        assets = uiState.assets,
                        overview = uiState.marketOverview,
                        signals = uiState.signals,
                        alerts = uiState.alerts,
                        apiConfigs = uiState.apiConfigs,
                        onAssetClick = { asset -> viewModel.openAssetDetail(asset) },
                        onConnectFeedClick = { asset ->
                            val feed = uiState.apiConfigs.firstOrNull {
                                if (asset.id == "XAU" || asset.id == "XAG") it.id == "commodities_feed"
                                else if (asset.id == "OIL") it.id == "oil_energy_feed"
                                else it.id == "binance_public"
                            } ?: uiState.apiConfigs.first()
                            viewModel.openApiConfigDialog(feed)
                        },
                        onSetAlertClick = { symbol -> viewModel.openAddAlertDialog(symbol) },
                        onToggleAlert = { id -> viewModel.toggleAlert(id) },
                        onDeleteAlert = { id -> viewModel.deleteAlert(id) },
                        onViewAllSignals = { viewModel.selectTab(NavigationTab.AI_SIGNALS) },
                        onNavigateTab = { tab -> viewModel.selectTab(tab) },
                        onCopySignalLevels = { signal ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(
                                "Nexus Signal",
                                "${signal.assetSymbol} ${signal.type.label}\nEntry: ${signal.entryPrice}\nTarget: ${signal.targetPrice}\nStop: ${signal.stopLoss}"
                            )
                            clipboard.setPrimaryClip(clip)
                            viewModel.createAlert(signal.assetSymbol, signal.assetName, 0.0, com.example.data.model.AlertDirection.ABOVE, "Copied signal levels")
                        },
                        onToggleFavorite = { id -> viewModel.toggleFavorite(id) },
                        paperEquityUsdt = uiState.paperPortfolio.equityUsdt,
                        paperNetPnlUsdt = uiState.paperPortfolio.totalNetPnlUsdt
                    )
                }

                NavigationTab.MARKETS -> {
                    MarketsScreen(
                        assets = uiState.assets,
                        selectedCategory = uiState.selectedCategory,
                        statusFilter = uiState.statusFilter,
                        searchQuery = uiState.searchQuery,
                        onCategorySelected = { cat -> viewModel.setCategory(cat) },
                        onStatusFilterSelected = { stat -> viewModel.setStatusFilter(stat) },
                        onSearchQueryChange = { query -> viewModel.setSearchQuery(query) },
                        onAssetClick = { asset -> viewModel.openAssetDetail(asset) },
                        onConnectFeedClick = { asset ->
                            val feed = uiState.apiConfigs.firstOrNull {
                                if (asset.id == "XAU" || asset.id == "XAG") it.id == "commodities_feed"
                                else if (asset.id == "WTI" || asset.id == "BRENT" || asset.id == "NATGAS") it.id == "commodities_feed"
                                else it.id == "binance_public"
                            } ?: uiState.apiConfigs.first()
                            viewModel.openApiConfigDialog(feed)
                        },
                        onSetAlertClick = { symbol -> viewModel.openAddAlertDialog(symbol) },
                        onOpenTechnicalAnalysis = { symbol -> viewModel.openTechnicalAnalysis(symbol) },
                        onToggleFavorite = { id -> viewModel.toggleFavorite(id) }
                    )
                }

                NavigationTab.ANALYSIS -> {
                    TechnicalAnalysisScreen(
                        analysis = uiState.technicalAnalysis,
                        smcAnalysis = uiState.smcAnalysis,
                        candles = uiState.analysisCandles,
                        isLoading = uiState.isCalculatingAnalysis,
                        selectedSymbol = uiState.analysisAssetSymbol,
                        selectedTimeframe = uiState.analysisTimeframe,
                        selectedCategory = uiState.selectedIndicatorCategory,
                        analysisMode = uiState.analysisMode,
                        selectedSmcTab = uiState.selectedSmcTab,
                        errorMessage = uiState.analysisError,
                        onSymbolSelected = { sym -> viewModel.setAnalysisAsset(sym) },
                        onTimeframeSelected = { tf -> viewModel.setAnalysisTimeframe(tf) },
                        onCategorySelected = { cat -> viewModel.setIndicatorCategory(cat) },
                        onAnalysisModeChange = { mode -> viewModel.setAnalysisMode(mode) },
                        onSmcTabChange = { tab -> viewModel.setSmcTab(tab) },
                        onInfoClick = { term -> viewModel.openGlossaryTerm(term) },
                        onRefresh = { viewModel.loadTechnicalAnalysis() },
                        onSetAlert = { sym -> viewModel.openAddAlertDialog(sym) },
                        onBackClick = { viewModel.selectTab(NavigationTab.MARKETS) }
                    )
                }

                NavigationTab.AI_SIGNALS -> {
                    SignalsScreen(
                        currentAiSignal = uiState.currentAiSignal,
                        isAnalyzing = uiState.isAnalyzingSignal,
                        selectedSymbol = uiState.selectedSignalAssetSymbol,
                        selectedTimeframe = uiState.selectedSignalTimeframe,
                        isExpanded = uiState.isSignalExpanded,
                        onAssetSelected = { sym -> viewModel.setSignalAsset(sym) },
                        onTimeframeSelected = { tf -> viewModel.setSignalTimeframe(tf) },
                        onAnalyzeClick = { viewModel.loadAiSignal() },
                        onToggleExpanded = { viewModel.toggleSignalExpanded() },
                        onInfoClick = { term -> viewModel.openGlossaryTerm(term) },
                        aiSignalsList = uiState.aiSignalsList,
                        signals = uiState.signals,
                        signalFilter = uiState.signalFilter,
                        onFilterSelected = { filter -> viewModel.setSignalFilter(filter) },
                        onSetAlertClick = { symbol -> viewModel.openAddAlertDialog(symbol) },
                        onCopyLevelsClick = { signal ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText(
                                "Nexus Signal",
                                "${signal.assetSymbol} ${signal.type.label}\nEntry: ${signal.entryPrice}\nTarget: ${signal.targetPrice}\nStop: ${signal.stopLoss}"
                            )
                            clipboard.setPrimaryClip(clip)
                        },
                        errorMessage = uiState.signalAnalysisError,
                        onRetryClick = { viewModel.retryAiSignal() },
                        onPaperTradeClick = { aiSignal -> viewModel.openPaperTradeFromAiSignal(aiSignal) },
                        currentCentralVerdict = uiState.centralVerdicts.firstOrNull { verdict ->
                            val target = com.example.data.signal.CentralCandidateAdapters.normalizeSymbol(uiState.selectedSignalAssetSymbol)
                            verdict.symbol.equals(target, ignoreCase = true) ||
                                verdict.symbol.equals("${target}USDT", ignoreCase = true) ||
                                verdict.displaySymbol.equals(uiState.selectedSignalAssetSymbol, ignoreCase = true)
                        }
                    )
                }

                NavigationTab.NEXUS_AI -> {
                    val activeAiAsset = uiState.selectedAssetDetail
                        ?: uiState.assets.firstOrNull {
                            it.id.equals(uiState.analysisAssetSymbol, ignoreCase = true) ||
                                it.symbol.startsWith(uiState.analysisAssetSymbol, ignoreCase = true)
                        }
                    val activeAiAssetPrice = activeAiAsset?.currentPrice ?: 0.0
                    val freshnessLabel = activeAiAsset?.let {
                        "${it.dataStatus.label} • ${it.lastUpdatedFormatted}"
                    } ?: "Connecting Binance Feeds..."
                    com.example.ui.screens.NexusAiScreen(
                        messages = uiState.aiMessages,
                        conversations = uiState.aiConversations,
                        currentConversationId = uiState.currentAiConversationId,
                        isLoading = uiState.isAiLoading,
                        isOnlineAiAvailable = uiState.isOnlineAiAvailable,
                        lastAiError = uiState.lastAiError,
                        dataFreshnessLabel = freshnessLabel,
                        currentAssetSymbol = uiState.analysisAssetSymbol,
                        currentAssetPrice = activeAiAssetPrice,
                        languageMode = uiState.learningLanguageMode,
                        urduFontSize = uiState.urduFontSize,
                        selectedAiProvider = uiState.selectedAiProvider,
                        aiProviderStatuses = uiState.aiProviderStatuses,
                        hybridMarketState = uiState.hybridMarketState,
                        onSendMessage = { query -> viewModel.sendAiMessage(query) },
                        onRetryMessage = { msgId -> viewModel.retryAiMessage(msgId) },
                        onRetryLastFailedQuery = { viewModel.retryLastFailedAiQuery() },
                        onRunOfflineFallback = { viewModel.runExplicitOfflineFallback() },
                        onSelectAiProvider = { provider -> viewModel.switchAiProvider(provider) },
                        onDismissError = { viewModel.dismissAiError() },
                        onNewChat = { viewModel.newAiChat() },
                        onClearChat = { viewModel.clearAiChat() },
                        onDeleteConversation = { id -> viewModel.deleteAiConversation(id) },
                        onSelectConversation = { id -> viewModel.selectAiConversation(id) },
                        onAttachCurrentMarket = { viewModel.attachCurrentMarketToAi() }
                    )
                }

                NavigationTab.LEARNING -> {
                    LearningCenterScreen(
                        languageMode = uiState.learningLanguageMode,
                        selectedCategory = uiState.selectedLessonCategory,
                        searchQuery = uiState.learningSearchQuery,
                        bookmarkedLessonIds = uiState.bookmarkedLessonIds,
                        completedLessonIds = uiState.completedLessonIds,
                        recentlyViewedLessonIds = uiState.recentlyViewedLessonIds,
                        selectedLesson = uiState.selectedLessonForDetail,
                        onLanguageModeChange = { mode -> viewModel.setLearningLanguageMode(mode) },
                        onCategorySelect = { cat -> viewModel.selectLessonCategory(cat) },
                        onSearchQueryChange = { query -> viewModel.setLearningSearchQuery(query) },
                        onSelectLesson = { lesson -> viewModel.openLessonDetail(lesson) },
                        onCloseLessonDetail = { viewModel.closeLessonDetail() },
                        onToggleBookmark = { id -> viewModel.toggleLessonBookmark(id) },
                        onToggleCompleted = { id -> viewModel.toggleLessonCompleted(id) },
                        onPreviousLesson = { viewModel.openPreviousLesson() },
                        onNextLesson = { viewModel.openNextLesson() }
                    )
                }

                NavigationTab.SCANNER -> {
                    ScannerScreen(
                        scannerItems = uiState.scannerItems,
                        scannerFilter = uiState.scannerFilter,
                        onFilterSelected = { filter -> viewModel.setScannerFilter(filter) },
                        onSetAlertClick = { symbol -> viewModel.openAddAlertDialog(symbol) },
                        selectedTimeframe = uiState.scannerTimeframe,
                        onTimeframeSelected = { tf -> viewModel.setScannerTimeframe(tf) },
                        onInspectAsset = { assetId, symbol -> viewModel.inspectAssetInAnalysis(assetId, symbol) },
                        onRefreshScanner = { viewModel.refreshScanner() },
                        futuresScannerState = uiState.futuresScannerState,
                        futuresWatchlist = uiState.futuresWatchlist,
                        scannerScreenMode = uiState.scannerScreenMode,
                        onModeSelected = { mode -> viewModel.setScannerScreenMode(mode) },
                        onStartFuturesScanner = { viewModel.startFuturesScanner(context) },
                        onStopFuturesScanner = { viewModel.stopFuturesScanner(context) },
                        onToggleFuturesWatchlist = { symbol, _ -> viewModel.toggleFuturesWatchlistPair(symbol) },
                        onAddCustomFuturesPair = { raw -> viewModel.addCustomFuturesPair(raw) },
                        onRemoveFuturesPair = { symbol -> viewModel.removeFuturesWatchlistPair(symbol) },
                        onIntervalSelected = { sec -> viewModel.setFuturesScannerInterval(sec) },
                        onTogglePaperTrading = { enabled -> viewModel.setFuturesScannerPaperTrading(enabled) },
                        onExplainSignalWithAi = { signal -> viewModel.explainFuturesSignalWithAi(signal) },
                        onSimulatePaperTrade = { signal -> viewModel.openPaperTradeFromFuturesSignal(signal) },
                        strict92ConfidenceMode = uiState.strict92ConfidenceMode,
                        onToggleStrict92Mode = { enabled -> viewModel.setStrict92ConfidenceMode(enabled) },
                        onOpenWorkflow = { signal -> viewModel.openTradeWorkflow(signal) },
                        paperOrdersCount = uiState.tradeWorkflowState.paperOrders.size,
                        activeAlertRulesCount = uiState.tradeWorkflowState.tradeAlertRules.count { it.isActive },
                        highRewardScanState = uiState.highRewardScanState,
                        onScanHighRewardNow = { viewModel.scanHighRewardOpportunitiesNow() },
                        onInspectHighRewardOpportunity = { opp -> viewModel.openHighRewardDetail(opp) },
                        multiMarketStrictEvaluations = uiState.multiMarketStrictEvaluations,
                        isScanningMultiMarketStrict = uiState.isScanningMultiMarketStrict,
                        multiMarketSetupBacktestSummary = uiState.multiMarketSetupBacktestSummary,
                        onScanMultiMarketStrict = { viewModel.runMultiMarketStrictScan() },
                        onRunStrictSetupBacktest = { sym, type, dir ->
                            viewModel.runStrictSetupTimeMachineBacktest(sym, type, dir)
                        },
                        rankedMultiMarketBoard = uiState.rankedMultiMarketBoard,
                        multiMarketSetupDirectionalReport = uiState.multiMarketSetupDirectionalReport,
                        managedSignals = uiState.managedSignals,
                        signalTransitionLog = uiState.signalTransitionLog,
                        budgetConfig = uiState.budgetConfig,
                        dailyRiskLedger = uiState.dailyRiskLedger,
                        onUpdateBudgetConfig = { cfg -> viewModel.updateWorkflowBudgetConfig(cfg) },
                        centralVerdicts = uiState.centralVerdicts
                    )
                }

                NavigationTab.NEWS -> {
                    NewsScreen(
                        news = uiState.news,
                        newsFilter = uiState.newsFilter,
                        onFilterSelected = { filter -> viewModel.setNewsFilter(filter) },
                        onNewsClick = { newsItem -> viewModel.openNewsDetail(newsItem) },
                        onToggleBookmark = { id -> viewModel.toggleNewsBookmark(id) },
                        onRefreshNews = { viewModel.refreshNews() },
                        onOpenGlossaryTerm = { term -> viewModel.openEconomicGlossary(term) }
                    )
                }

                NavigationTab.PAPER_TRADING -> {
                    PaperTradingScreen(
                        portfolio = uiState.paperPortfolio,
                        prefill = uiState.paperTradePrefill,
                        assets = uiState.assets,
                        feedbackMessage = uiState.paperOrderFeedback,
                        managedSignals = uiState.managedSignals,
                        transitionLog = uiState.signalTransitionLog,
                        backtestReport = uiState.strategyBacktestReport,
                        isRunningBacktest = uiState.isRunningStrategyBacktest,
                        backtestProgress = uiState.strategyBacktestProgress,
                        backtestError = uiState.strategyBacktestError,
                        onResetWallet = { balance -> viewModel.resetPaperWallet(balance) },
                        onSubmitMarketOrder = { sym, side, qty, lev, sl, tp ->
                            viewModel.submitPaperMarketOrder(sym, side, qty, lev, sl, tp)
                        },
                        onSubmitLimitOrder = { sym, side, limit, qty, lev, sl, tp ->
                            viewModel.submitPaperLimitOrder(sym, side, limit, qty, lev, sl, tp)
                        },
                        onEditPendingOrder = { orderId, limit, qty, sl, tp ->
                            viewModel.editPendingPaperOrder(orderId, limit, qty, sl, tp)
                        },
                        onCancelPendingOrder = { orderId -> viewModel.cancelPendingPaperOrder(orderId) },
                        onUpdatePositionSlTp = { posId, sl, tp ->
                            viewModel.updatePaperPositionSlTp(posId, sl, tp)
                        },
                        onClosePosition = { posId -> viewModel.closePaperPosition(posId) },
                        onClearPrefill = { viewModel.clearPaperTradePrefill() },
                        onDismissFeedback = { viewModel.dismissPaperOrderFeedback() },
                        onRunSixMonthBacktest = { syms, days ->
                            viewModel.runSixMonthStrategyBacktest(syms, days)
                        },
                        forwardValidationRecords = uiState.forwardValidationRecords
                    )
                }

                NavigationTab.SETTINGS -> {
                    val settingsState = com.example.data.settings.AppSettingsState(
                        themeMode = uiState.themeMode,
                        languageMode = uiState.learningLanguageMode,
                        urduFontSize = uiState.urduFontSize,
                        isDataSavingMode = uiState.isDataSavingMode,
                        isLowPerformanceMode = uiState.isLowPerformanceMode,
                        defaultTimeframe = uiState.defaultChartTimeframe,
                        defaultWatchlistCategory = uiState.defaultWatchlistCategory,
                        alertNotificationsEnabled = uiState.alertNotificationsEnabled,
                        soundEffectsEnabled = uiState.soundEffectsEnabled,
                        refreshRate = uiState.refreshRate,
                        lastCacheClearedTimestamp = uiState.lastCacheClearedTimestamp,
                        lastMarketRefreshTimestamp = uiState.lastMarketDataRefreshTimestamp
                    )

                    val planLabel = authorizedState?.userRecord?.planName.orEmpty()
                    val expiryLabel = authorizedState?.userRecord?.expiresAt?.toDate()?.let { date ->
                        com.example.util.TimeZoneUtils.formatKarachiDateOnly(date.time)
                    }.orEmpty()

                    SettingsScreen(
                        settingsState = settingsState,
                        apiConfigs = uiState.apiConfigs,
                        onThemeSelect = { mode -> viewModel.setThemeMode(mode) },
                        onLanguageSelect = { lang -> viewModel.setAppLanguageMode(lang) },
                        onDataSavingToggle = { enabled -> viewModel.setDataSavingMode(enabled) },
                        onLowPerformanceToggle = { enabled -> viewModel.setLowPerformanceMode(enabled) },
                        onDefaultTimeframeSelect = { tf -> viewModel.setDefaultChartTimeframe(tf) },
                        onDefaultWatchlistSelect = { cat -> viewModel.setDefaultWatchlistCategory(cat) },
                        onRefreshRateSelect = { rate -> viewModel.setRefreshRate(rate) },
                        onAlertsToggle = { enabled -> viewModel.setAlertNotificationsEnabled(enabled) },
                        onSoundEffectsToggle = { enabled -> viewModel.setSoundEffectsEnabled(enabled) },
                        onClearCacheClick = { viewModel.clearTemporaryCache() },
                        onConfigureFeed = { config -> viewModel.openApiConfigDialog(config) },
                        onRefreshFeeds = { viewModel.refreshMarketData() },
                        onUrduFontSizeSelect = { size -> viewModel.setUrduFontSize(size) },
                        accountEmail = authorizedState?.email.orEmpty(),
                        subscriptionPlanLabel = planLabel,
                        subscriptionExpiryLabel = expiryLabel,
                        isAdmin = authorizedState?.isAdmin == true,
                        onOpenAdminPanel = onOpenAdminPanel,
                        onSignOutClick = onSignOutClick
                    )
                }
            }
        }
    }

    // Modal Dialogs
    if (uiState.showAddAlertDialog) {
        AddAlertDialog(
            defaultSymbol = uiState.defaultAlertAssetSymbol,
            onDismiss = { viewModel.closeAddAlertDialog() },
            onConfirm = { symbol, name, price, direction, note ->
                viewModel.createAlert(symbol, name, price, direction, note)
            }
        )
    }

    uiState.selectedApiConfigForEdit?.let { feedConfig ->
        ApiConfigDialog(
            config = feedConfig,
            onDismiss = { viewModel.closeApiConfigDialog() },
            onConnect = { key -> viewModel.connectApiFeed(feedConfig.id, key) },
            onDisconnect = { viewModel.disconnectApiFeed(feedConfig.id) }
        )
    }

    uiState.selectedAssetDetail?.let { asset ->
        AssetDetailDialog(
            asset = asset,
            onDismiss = { viewModel.closeAssetDetail() },
            onSetAlert = { symbol -> viewModel.openAddAlertDialog(symbol) },
            onConfigureFeed = {
                viewModel.closeAssetDetail()
                val feed = uiState.apiConfigs.firstOrNull {
                    if (asset.id == "XAU" || asset.id == "XAG") it.id == "commodities_feed"
                    else if (asset.id == "OIL") it.id == "oil_energy_feed"
                    else it.id == "binance_public"
                } ?: uiState.apiConfigs.first()
                viewModel.openApiConfigDialog(feed)
            },
            onOpenTechnicalAnalysis = { symbol ->
                viewModel.openTechnicalAnalysis(symbol)
            },
            onToggleFavorite = { viewModel.toggleFavorite(asset.id) }
        )
    }

    uiState.selectedNewsDetail?.let { news ->
        NewsDetailDialog(
            news = news,
            onDismiss = { viewModel.closeNewsDetail() },
            onToggleBookmark = { viewModel.toggleNewsBookmark(news.id) },
            onOpenGlossaryTerm = { term -> viewModel.openEconomicGlossary(term) },
            isFetchingFullArticle = uiState.isFetchingFullNewsArticle,
            selectedLanguage = uiState.selectedNewsLanguage,
            isTranslating = uiState.isTranslatingNews,
            translatedArticle = uiState.translatedNewsArticle,
            onSelectLanguage = { lang -> viewModel.selectNewsLanguage(lang) },
            onRetryTranslation = { viewModel.retryNewsTranslation() }
        )
    }

    uiState.selectedGlossaryTerm?.let { item ->
        RomanUrduGlossaryDialog(
            item = item,
            initialLanguageMode = uiState.learningLanguageMode,
            onOpenFullLesson = { lessonId -> viewModel.navigateToLessonInAcademy(lessonId) },
            onDismiss = { viewModel.closeGlossaryDialog() }
        )
    }

    uiState.selectedEconomicTerm?.let { term ->
        EconomicGlossaryDialog(
            term = term,
            currentLanguageMode = uiState.learningLanguageMode,
            onDismiss = { viewModel.dismissEconomicGlossary() }
        )
    }

    if (uiState.tradeWorkflowState.isVisible && uiState.tradeWorkflowState.selectedSignal != null) {
        com.example.ui.screens.TradeWorkflowSheet(
            workflowState = uiState.tradeWorkflowState,
            onDismiss = { viewModel.closeTradeWorkflow() },
            onUpdateBudgetConfig = { cfg -> viewModel.updateWorkflowBudgetConfig(cfg) },
            onRerunTimeMachine = { fee, slip, seed -> viewModel.rerunWorkflowTimeMachine(fee, slip, seed) },
            onSelectExecutionMode = { mode -> viewModel.setWorkflowExecutionMode(mode) },
            onExplicitConfirmationChanged = { confirmed -> viewModel.setWorkflowExplicitConfirmation(confirmed) },
            onSubmitPaperOrder = { viewModel.submitWorkflowPaperOrder() }
        )
    }

    uiState.selectedHighRewardOpportunity?.let { opp ->
        com.example.ui.dialogs.HighRewardOpportunityDialog(
            opportunity = opp,
            appLanguageMode = uiState.learningLanguageMode,
            onDismiss = { viewModel.closeHighRewardDetail() }
        )
    }
}
