package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.subscription.AccessGateState
import com.example.data.subscription.ActivationCodeRecord
import com.example.data.subscription.FirestoreErrorHandler
import com.example.data.subscription.SubscriptionPlan
import com.example.data.subscription.SubscriptionRepository
import com.example.data.subscription.SubscriptionStatus
import com.example.data.subscription.SupportConfig
import com.example.data.subscription.UserSubscriptionRecord
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface AdminCollectionState<out T> {
    data object Idle : AdminCollectionState<Nothing>
    data object Loading : AdminCollectionState<Nothing>
    data class Ready<T>(val items: List<T>) : AdminCollectionState<T>
    data class Error(val message: String) : AdminCollectionState<Nothing>
}

data class SubscriptionUiActionState(
    val isSubmittingCode: Boolean = false,
    val codeErrorMessage: String? = null,
    val codeSuccessMessage: String? = null,
    val showAdminPanel: Boolean = false,
    val isAdminActionBusy: Boolean = false,
    val adminActionMessage: String? = null,
    val adminActionError: String? = null,
    val lastGeneratedCode: String? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionViewModel(
    private val uid: String,
    private val currentUser: FirebaseUser,
    private val repository: SubscriptionRepository
) : ViewModel() {

    private val _isAdmin = MutableStateFlow(repository.isVerifiedBootstrapAdmin(currentUser))
    private val _profileReady = MutableStateFlow(false)
    private val _profileSyncError = MutableStateFlow<String?>(null)
    private val _serverClockMs = MutableStateFlow(System.currentTimeMillis())
    private val _actionState = MutableStateFlow(SubscriptionUiActionState())
    private var lastAutoExpiredKey: String? = null

    val actionState: StateFlow<SubscriptionUiActionState> = _actionState

    val gateState: StateFlow<AccessGateState> = combine(
        _profileReady,
        _profileSyncError,
        _isAdmin,
        _serverClockMs,
        combine(
            repository.observeUserRecord(uid),
            repository.observeSupportConfig()
        ) { record, support -> record to support }
    ) { ready, syncError, isAdmin, serverClockMs, (record, support) ->
        if (!ready && record == null) {
            if (syncError != null) {
                AccessGateState.Error(syncError)
            } else {
                AccessGateState.Loading
            }
        } else {
            val effectiveNow = maxOf(
                serverClockMs,
                record?.serverOrLocalNowMs(System.currentTimeMillis()) ?: System.currentTimeMillis()
            )
            val email = currentUser.email.orEmpty().ifBlank { record?.email.orEmpty() }
            val displayName = currentUser.displayName.orEmpty().ifBlank { record?.displayName.orEmpty() }

            if (record != null && record.hasActiveAccess(effectiveNow)) {
                AccessGateState.Authorized(
                    uid = uid,
                    email = email,
                    displayName = displayName,
                    userRecord = record,
                    isAdmin = isAdmin,
                    supportConfig = support,
                    serverNowMs = effectiveNow
                )
            } else {
                val effectiveStatus = record?.effectiveStatus(effectiveNow) ?: SubscriptionStatus.NONE
                if (record != null &&
                    record.subscriptionStatus == SubscriptionStatus.ACTIVE.name &&
                    effectiveStatus == SubscriptionStatus.EXPIRED
                ) {
                    val expireKey = "${record.userId}:${record.activeCode.orEmpty()}"
                    if (lastAutoExpiredKey != expireKey) {
                        lastAutoExpiredKey = expireKey
                        viewModelScope.launch {
                            repository.syncExpirationIfNeeded(uid, record, effectiveNow)
                        }
                    }
                }
                AccessGateState.NeedsActivation(
                    uid = uid,
                    email = email,
                    displayName = displayName,
                    userRecord = record,
                    effectiveStatus = effectiveStatus,
                    isDisabled = record?.accountDisabled == true,
                    isAdmin = isAdmin,
                    supportConfig = support,
                    serverNowMs = effectiveNow
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = AccessGateState.Loading
    )

    val adminCodesState: StateFlow<AdminCollectionState<ActivationCodeRecord>> =
        combine(_isAdmin, _actionState.map { it.showAdminPanel }) { admin, open -> admin && open }
            .flatMapLatest { active ->
                if (!active) {
                    flowOf<AdminCollectionState<ActivationCodeRecord>>(AdminCollectionState.Idle)
                } else {
                    repository.observeAllCodes()
                        .map<List<ActivationCodeRecord>, AdminCollectionState<ActivationCodeRecord>> {
                            AdminCollectionState.Ready(it)
                        }
                        .catch { error ->
                            val firestoreErr = generateSequence(error) { it.cause }
                                .filterIsInstance<FirebaseFirestoreException>()
                                .firstOrNull()
                            if (error is CancellationException && firestoreErr == null) {
                                throw error
                            }
                            emit(
                                AdminCollectionState.Error(
                                    FirestoreErrorHandler.friendlyMessage(
                                        firestoreErr ?: error,
                                        "Unable to load activation codes."
                                    )
                                )
                            )
                        }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = AdminCollectionState.Idle
            )

    val adminUsersState: StateFlow<AdminCollectionState<UserSubscriptionRecord>> =
        combine(_isAdmin, _actionState.map { it.showAdminPanel }) { admin, open -> admin && open }
            .flatMapLatest { active ->
                if (!active) {
                    flowOf<AdminCollectionState<UserSubscriptionRecord>>(AdminCollectionState.Idle)
                } else {
                    repository.observeAllUsers()
                        .map<List<UserSubscriptionRecord>, AdminCollectionState<UserSubscriptionRecord>> {
                            AdminCollectionState.Ready(it)
                        }
                        .catch { error ->
                            val firestoreErr = generateSequence(error) { it.cause }
                                .filterIsInstance<FirebaseFirestoreException>()
                                .firstOrNull()
                            if (error is CancellationException && firestoreErr == null) {
                                throw error
                            }
                            emit(
                                AdminCollectionState.Error(
                                    FirestoreErrorHandler.friendlyMessage(
                                        firestoreErr ?: error,
                                        "Unable to load user records."
                                    )
                                )
                            )
                        }
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = AdminCollectionState.Idle
            )

    init {
        initializeSession()
    }

    fun initializeSession() {
        viewModelScope.launch {
            _profileSyncError.value = null
            _isAdmin.value = repository.checkIsAdmin(currentUser)
            val syncResult = repository.ensureUserProfileAndSyncClock(currentUser)
            syncResult
                .onSuccess { serverTime ->
                    _serverClockMs.value = serverTime
                    _profileReady.value = true
                }
                .onFailure { error ->
                    _profileSyncError.value = FirestoreErrorHandler.friendlyMessage(
                        error,
                        "Unable to verify subscription status."
                    )
                    _profileReady.value = true
                }
        }
    }

    fun redeemActivationCode(rawCode: String) {
        if (_actionState.value.isSubmittingCode) return
        _actionState.value = _actionState.value.copy(
            isSubmittingCode = true,
            codeErrorMessage = null,
            codeSuccessMessage = null
        )
        viewModelScope.launch {
            val result = repository.activateCodeAtomic(rawCode)
            result
                .onSuccess { record ->
                    _serverClockMs.value = maxOf(_serverClockMs.value, System.currentTimeMillis())
                    _actionState.value = _actionState.value.copy(
                        isSubmittingCode = false,
                        codeErrorMessage = null,
                        codeSuccessMessage = "${record.planName ?: "Subscription"} activated successfully."
                    )
                }
                .onFailure { error ->
                    _actionState.value = _actionState.value.copy(
                        isSubmittingCode = false,
                        codeErrorMessage = FirestoreErrorHandler.friendlyMessage(
                            error,
                            "Activation failed. Check your code and try again."
                        ),
                        codeSuccessMessage = null
                    )
                }
        }
    }

    fun clearCodeMessages() {
        _actionState.value = _actionState.value.copy(
            codeErrorMessage = null,
            codeSuccessMessage = null
        )
    }

    fun openAdminPanel() {
        if (_isAdmin.value) {
            _actionState.value = _actionState.value.copy(
                showAdminPanel = true,
                adminActionMessage = null,
                adminActionError = null
            )
        }
    }

    fun closeAdminPanel() {
        _actionState.value = _actionState.value.copy(
            showAdminPanel = false,
            adminActionMessage = null,
            adminActionError = null
        )
    }

    fun clearAdminMessages() {
        _actionState.value = _actionState.value.copy(
            adminActionMessage = null,
            adminActionError = null
        )
    }

    fun adminGenerateCode(plan: SubscriptionPlan, note: String) {
        if (!_isAdmin.value || _actionState.value.isAdminActionBusy) return
        _actionState.value = _actionState.value.copy(
            isAdminActionBusy = true,
            adminActionMessage = null,
            adminActionError = null
        )
        viewModelScope.launch {
            repository.createActivationCode(plan = plan, note = note)
                .onSuccess { newCode ->
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        lastGeneratedCode = newCode,
                        adminActionMessage = "Created ${plan.displayName} code: $newCode"
                    )
                }
                .onFailure { error ->
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionError = FirestoreErrorHandler.friendlyMessage(
                            error,
                            "Failed to generate code."
                        )
                    )
                }
        }
    }

    fun adminRevokeCode(record: ActivationCodeRecord) {
        if (!_isAdmin.value || _actionState.value.isAdminActionBusy) return
        _actionState.value = _actionState.value.copy(
            isAdminActionBusy = true,
            adminActionMessage = null,
            adminActionError = null
        )
        viewModelScope.launch {
            repository.revokeActivationCode(record)
                .onSuccess {
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionMessage = "Code ${record.code} revoked."
                    )
                }
                .onFailure { error ->
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionError = FirestoreErrorHandler.friendlyMessage(
                            error,
                            "Failed to revoke code."
                        )
                    )
                }
        }
    }

    fun adminRevokeUserAccess(record: UserSubscriptionRecord) {
        if (!_isAdmin.value || _actionState.value.isAdminActionBusy) return
        _actionState.value = _actionState.value.copy(
            isAdminActionBusy = true,
            adminActionMessage = null,
            adminActionError = null
        )
        viewModelScope.launch {
            repository.revokeUserSubscription(record)
                .onSuccess {
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionMessage = "Revoked subscription for ${record.email}"
                    )
                }
                .onFailure { error ->
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionError = FirestoreErrorHandler.friendlyMessage(
                            error,
                            "Failed to revoke user access."
                        )
                    )
                }
        }
    }

    fun adminToggleUserDisabled(record: UserSubscriptionRecord, disabled: Boolean) {
        if (!_isAdmin.value || _actionState.value.isAdminActionBusy) return
        _actionState.value = _actionState.value.copy(
            isAdminActionBusy = true,
            adminActionMessage = null,
            adminActionError = null
        )
        viewModelScope.launch {
            repository.setUserAccountDisabled(record, disabled)
                .onSuccess {
                    val verb = if (disabled) "disabled" else "re-enabled"
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionMessage = "Account $verb for ${record.email}"
                    )
                }
                .onFailure { error ->
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionError = FirestoreErrorHandler.friendlyMessage(
                            error,
                            "Failed to update account status."
                        )
                    )
                }
        }
    }

    fun adminSaveSupportConfig(whatsappNumber: String, supportMessage: String) {
        if (!_isAdmin.value || _actionState.value.isAdminActionBusy) return
        _actionState.value = _actionState.value.copy(
            isAdminActionBusy = true,
            adminActionMessage = null,
            adminActionError = null
        )
        viewModelScope.launch {
            repository.updateSupportConfig(whatsappNumber, supportMessage)
                .onSuccess {
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionMessage = "WhatsApp contact configuration saved."
                    )
                }
                .onFailure { error ->
                    _actionState.value = _actionState.value.copy(
                        isAdminActionBusy = false,
                        adminActionError = FirestoreErrorHandler.friendlyMessage(
                            error,
                            "Failed to save support configuration."
                        )
                    )
                }
        }
    }

    class Factory(
        private val uid: String,
        private val currentUser: FirebaseUser,
        private val repository: SubscriptionRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SubscriptionViewModel(
                uid = uid,
                currentUser = currentUser,
                repository = repository
            ) as T
        }
    }
}
