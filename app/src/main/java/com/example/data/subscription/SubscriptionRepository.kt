package com.example.data.subscription

import android.content.Context
import android.util.Log
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Date

class SubscriptionRepository(context: Context) {

    companion object {
        private const val TAG = "SubscriptionRepository"
        const val BOOTSTRAP_ADMIN_EMAIL = "sarfarazmech00@gmail.com"
        private const val COLLECTION_USERS = "users"
        private const val COLLECTION_CODES = "activation_codes"
        private const val COLLECTION_ADMINS = "admins"
        private const val COLLECTION_CONFIG = "app_config"
        private const val DOC_SUPPORT = "support"
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(
        context.applicationContext.getString(R.string.firestore_database_id)
    )
    private val random = SecureRandom()

    private fun requireUserId(): String =
        auth.currentUser?.uid
            ?: throw IllegalStateException("Authentication required. Please sign in with Google.")

    fun isVerifiedBootstrapAdmin(user: FirebaseUser?): Boolean {
        if (user == null) return false
        return user.isEmailVerified &&
            user.email?.trim()?.equals(BOOTSTRAP_ADMIN_EMAIL, ignoreCase = true) == true
    }

    suspend fun checkIsAdmin(user: FirebaseUser?): Boolean {
        if (user == null) return false
        if (isVerifiedBootstrapAdmin(user)) return true
        return try {
            val snap = db.collection(COLLECTION_ADMINS).document(user.uid).get().await()
            snap.exists()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun observeUserRecord(uid: String): Flow<UserSubscriptionRecord?> = callbackFlow {
        val docRef = db.collection(COLLECTION_USERS).document(uid)
        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                FirestoreErrorHandler.handleFirestoreError(
                    error,
                    OperationType.GET,
                    "$COLLECTION_USERS/$uid"
                )
                trySend(null)
                return@addSnapshotListener
            }
            val record = snapshot?.takeIf { it.exists() }?.toObject(
                UserSubscriptionRecord::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            )
            trySend(record)
        }
        awaitClose { listener.remove() }
    }

    fun observeSupportConfig(): Flow<SupportConfig> = callbackFlow {
        val docRef = db.collection(COLLECTION_CONFIG).document(DOC_SUPPORT)
        val listener = docRef.addSnapshotListener { snapshot, error ->
            if (error != null) {
                FirestoreErrorHandler.handleFirestoreError(
                    error,
                    OperationType.GET,
                    "$COLLECTION_CONFIG/$DOC_SUPPORT"
                )
                trySend(SupportConfig())
                return@addSnapshotListener
            }
            val config = snapshot?.takeIf { it.exists() }?.toObject(
                SupportConfig::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: SupportConfig()
            trySend(config)
        }
        awaitClose { listener.remove() }
    }

    fun observeAllCodes(): Flow<List<ActivationCodeRecord>> = callbackFlow {
        val query = db.collection(COLLECTION_CODES)
            .orderBy("createdAt", Query.Direction.DESCENDING)
        val listener = query.addSnapshotListener { snapshots, error ->
            if (error != null) {
                FirestoreErrorHandler.handleFirestoreError(
                    error,
                    OperationType.LIST,
                    COLLECTION_CODES
                )
                close(error)
                return@addSnapshotListener
            }
            val list = snapshots?.documents.orEmpty().mapNotNull { doc ->
                doc.toObject(
                    ActivationCodeRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            trySend(list)
        }
        awaitClose { listener.remove() }
    }

    fun observeAllUsers(): Flow<List<UserSubscriptionRecord>> = callbackFlow {
        val query = db.collection(COLLECTION_USERS)
            .orderBy("updatedAt", Query.Direction.DESCENDING)
        val listener = query.addSnapshotListener { snapshots, error ->
            if (error != null) {
                FirestoreErrorHandler.handleFirestoreError(
                    error,
                    OperationType.LIST,
                    COLLECTION_USERS
                )
                close(error)
                return@addSnapshotListener
            }
            val list = snapshots?.documents.orEmpty().mapNotNull { doc ->
                doc.toObject(
                    UserSubscriptionRecord::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
            trySend(list)
        }
        awaitClose { listener.remove() }
    }

    suspend fun ensureUserProfileAndSyncClock(user: FirebaseUser): Result<Long> {
        val uid = user.uid
        val userRef = db.collection(COLLECTION_USERS).document(uid)
        return try {
            val existing = userRef.get().await()
            val email = user.email?.trim().orEmpty().ifEmpty { "unknown@nexus.local" }.take(200)
            val displayName = user.displayName?.trim().orEmpty().take(120)

            if (!existing.exists()) {
                val createMap = mapOf(
                    "userId" to uid,
                    "email" to email,
                    "displayName" to displayName,
                    "role" to "USER",
                    "subscriptionStatus" to SubscriptionStatus.NONE.name,
                    "accountDisabled" to false,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.set(createMap).await()
            } else {
                val updateMap = mapOf(
                    "email" to email,
                    "displayName" to displayName,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                userRef.update(updateMap).await()
            }

            val refreshed = userRef.get().await()
            val serverNow = refreshed.getTimestamp("lastSeenAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                ?.toDate()?.time
                ?: System.currentTimeMillis()
            Result.success(serverNow)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_USERS/$uid")
            Result.failure(e)
        }
    }

    suspend fun syncExpirationIfNeeded(uid: String, record: UserSubscriptionRecord, serverNowMs: Long): Result<Unit> {
        if (record.subscriptionStatus != SubscriptionStatus.ACTIVE.name) {
            return Result.success(Unit)
        }
        val expiryMs = record.expiresAt?.toDate()?.time ?: return Result.success(Unit)
        if (expiryMs > serverNowMs) {
            return Result.success(Unit)
        }
        val userRef = db.collection(COLLECTION_USERS).document(uid)
        return try {
            userRef.update(
                mapOf(
                    "subscriptionStatus" to SubscriptionStatus.EXPIRED.name,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, "$COLLECTION_USERS/$uid")
            Result.failure(e)
        }
    }

    suspend fun activateCodeAtomic(rawCode: String): Result<UserSubscriptionRecord> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val userEmail = auth.currentUser?.email.orEmpty().take(200)
        val normalizedCode = SubscriptionValidation.normalizeCode(rawCode)
        if (!SubscriptionValidation.isValidCodeFormat(normalizedCode)) {
            return Result.failure(
                IllegalArgumentException("Invalid code format. Enter an 8-40 character code (letters, numbers, hyphens).")
            )
        }

        val userRef = db.collection(COLLECTION_USERS).document(uid)
        val codeRef = db.collection(COLLECTION_CODES).document(normalizedCode)

        return try {
            // Anchor local time against server clock first so expiry calculation never suffers local clock skew
            val serverAnchorMs = run {
                val userSnap = userRef.get().await()
                if (!userSnap.exists()) {
                    val user = auth.currentUser!!
                    ensureUserProfileAndSyncClock(user).getOrElse { System.currentTimeMillis() }
                } else {
                    userRef.update(
                        mapOf(
                            "lastSeenAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    userRef.get().await()
                        .getTimestamp("lastSeenAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
                        ?.toDate()?.time
                        ?: System.currentTimeMillis()
                }
            }

            val updatedUser = db.runTransaction { transaction ->
                val userSnap = transaction.get(userRef)
                if (!userSnap.exists()) {
                    throw IllegalStateException("User profile was not found. Please sign out and sign in again.")
                }
                val isDisabled = userSnap.getBoolean("accountDisabled") == true
                if (isDisabled) {
                    throw IllegalStateException("Your account has been disabled by the administrator. Please contact support.")
                }

                val codeSnap = transaction.get(codeRef)
                if (!codeSnap.exists()) {
                    throw IllegalArgumentException("Activation code '$normalizedCode' does not exist.")
                }
                val status = codeSnap.getString("status").orEmpty()
                when (ActivationCodeStatus.fromString(status)) {
                    ActivationCodeStatus.ACTIVATED ->
                        throw IllegalStateException("This activation code has already been used.")
                    ActivationCodeStatus.REVOKED ->
                        throw IllegalStateException("This activation code has been revoked by the administrator.")
                    ActivationCodeStatus.EXPIRED ->
                        throw IllegalStateException("This activation code has expired.")
                    ActivationCodeStatus.UNUSED -> Unit
                }

                val planId = codeSnap.getString("planId")
                val plan = SubscriptionPlan.fromId(planId)
                    ?: throw IllegalStateException("Activation code carries an unsupported plan.")

                val expiryMs = SubscriptionValidation.calculateExpiryMillis(serverAnchorMs, plan)
                val expiryTimestamp = Timestamp(Date(expiryMs))

                transaction.update(
                    codeRef,
                    mapOf(
                        "status" to ActivationCodeStatus.ACTIVATED.name,
                        "activatedByUid" to uid,
                        "activatedByEmail" to userEmail,
                        "activatedAt" to FieldValue.serverTimestamp(),
                        "expiresAt" to expiryTimestamp,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                transaction.update(
                    userRef,
                    mapOf(
                        "subscriptionStatus" to SubscriptionStatus.ACTIVE.name,
                        "planId" to plan.id,
                        "planName" to plan.displayName,
                        "pricePkr" to plan.pricePkr,
                        "activeCode" to normalizedCode,
                        "activatedAt" to FieldValue.serverTimestamp(),
                        "expiresAt" to expiryTimestamp,
                        "lastSeenAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )

                UserSubscriptionRecord(
                    id = uid,
                    userId = uid,
                    email = userEmail,
                    displayName = userSnap.getString("displayName").orEmpty(),
                    role = "USER",
                    subscriptionStatus = SubscriptionStatus.ACTIVE.name,
                    accountDisabled = false,
                    planId = plan.id,
                    planName = plan.displayName,
                    pricePkr = plan.pricePkr.toLong(),
                    activeCode = normalizedCode,
                    activatedAt = Timestamp(Date(serverAnchorMs)),
                    expiresAt = expiryTimestamp,
                    lastSeenAt = Timestamp(Date(serverAnchorMs))
                )
            }.await()

            Result.success(updatedUser)
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFirestoreException) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_CODES/$normalizedCode")
            val message = if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                "Activation denied by security rules. Code may already be used, invalid, or your account is restricted."
            } else {
                e.message ?: "Failed to redeem activation code."
            }
            Result.failure(IllegalStateException(message, e))
        } catch (e: Exception) {
            Log.e(TAG, "Activation transaction failed", e)
            Result.failure(e)
        }
    }

    // =========================================================================
    // ADMIN-ONLY OPERATIONS (Enforced server-side in firestore.rules)
    // =========================================================================

    fun generateSecureCodeString(plan: SubscriptionPlan): String {
        val prefix = if (plan == SubscriptionPlan.GOLDEN_ANNUAL) "NXG" else "NXM"
        fun block(len: Int): String = buildString(len) {
            repeat(len) {
                append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)])
            }
        }
        return "$prefix-${block(4)}-${block(4)}"
    }

    suspend fun createActivationCode(
        plan: SubscriptionPlan,
        note: String = "",
        customCode: String? = null
    ): Result<String> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val code = if (!customCode.isNullOrBlank()) {
            SubscriptionValidation.normalizeCode(customCode)
        } else {
            generateSecureCodeString(plan)
        }

        if (!SubscriptionValidation.isValidCodeFormat(code)) {
            return Result.failure(IllegalArgumentException("Generated code format is invalid."))
        }
        val cleanNote = note.trim().take(160)

        return try {
            val codeRef = db.collection(COLLECTION_CODES).document(code)
            val payload = hashMapOf<String, Any>(
                "code" to code,
                "planId" to plan.id,
                "planName" to plan.displayName,
                "pricePkr" to plan.pricePkr,
                "durationMonths" to plan.durationMonths,
                "durationDays" to plan.durationDays,
                "status" to ActivationCodeStatus.UNUSED.name,
                "createdByUid" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            if (cleanNote.isNotEmpty()) {
                payload["note"] = cleanNote
            }
            codeRef.set(payload).await()
            Result.success(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.CREATE, "$COLLECTION_CODES/$code")
            Result.failure(e)
        }
    }

    suspend fun revokeActivationCode(codeRecord: ActivationCodeRecord): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val codeId = codeRecord.code.ifBlank { codeRecord.id }
        return try {
            val batch = db.batch()
            batch.update(
                db.collection(COLLECTION_CODES).document(codeId),
                mapOf(
                    "status" to ActivationCodeStatus.REVOKED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            val activatedUid = codeRecord.activatedByUid
            if (!activatedUid.isNullOrBlank()) {
                val userRef = db.collection(COLLECTION_USERS).document(activatedUid)
                val userSnap = userRef.get().await()
                if (userSnap.exists() && userSnap.getString("activeCode") == codeId) {
                    batch.update(
                        userRef,
                        mapOf(
                            "subscriptionStatus" to SubscriptionStatus.REVOKED.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, "$COLLECTION_CODES/$codeId")
            Result.failure(e)
        }
    }

    suspend fun revokeUserSubscription(userRecord: UserSubscriptionRecord): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val targetUid = userRecord.userId.ifBlank { userRecord.id }
        return try {
            val batch = db.batch()
            batch.update(
                db.collection(COLLECTION_USERS).document(targetUid),
                mapOf(
                    "subscriptionStatus" to SubscriptionStatus.REVOKED.name,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            val activeCode = userRecord.activeCode
            if (!activeCode.isNullOrBlank()) {
                val codeRef = db.collection(COLLECTION_CODES).document(activeCode)
                if (codeRef.get().await().exists()) {
                    batch.update(
                        codeRef,
                        mapOf(
                            "status" to ActivationCodeStatus.REVOKED.name,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, "$COLLECTION_USERS/$targetUid")
            Result.failure(e)
        }
    }

    suspend fun setUserAccountDisabled(userRecord: UserSubscriptionRecord, disabled: Boolean): Result<Unit> {
        try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val targetUid = userRecord.userId.ifBlank { userRecord.id }
        val newStatus = if (disabled) {
            SubscriptionStatus.REVOKED.name
        } else {
            val expMs = userRecord.expiresAt?.toDate()?.time
            when {
                userRecord.activeCode.isNullOrBlank() || expMs == null -> SubscriptionStatus.NONE.name
                expMs <= System.currentTimeMillis() -> SubscriptionStatus.EXPIRED.name
                else -> SubscriptionStatus.ACTIVE.name
            }
        }
        return try {
            db.collection(COLLECTION_USERS).document(targetUid).update(
                mapOf(
                    "accountDisabled" to disabled,
                    "subscriptionStatus" to newStatus,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.UPDATE, "$COLLECTION_USERS/$targetUid")
            Result.failure(e)
        }
    }

    suspend fun updateSupportConfig(rawWhatsappNumber: String, supportMessage: String): Result<Unit> {
        val uid = try {
            requireUserId()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val normalizedPhone = SubscriptionValidation.normalizePhoneNumber(rawWhatsappNumber)
        if (!SubscriptionValidation.isValidOptionalPhone(normalizedPhone)) {
            return Result.failure(
                IllegalArgumentException("Enter a valid international WhatsApp number (e.g. +923001234567) or leave blank to clear.")
            )
        }
        val cleanMessage = supportMessage.trim().take(300)

        return try {
            val payload = mapOf(
                "whatsappNumber" to normalizedPhone,
                "supportMessage" to cleanMessage,
                "updatedByUid" to uid,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            db.collection(COLLECTION_CONFIG).document(DOC_SUPPORT).set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FirestoreErrorHandler.handleFirestoreError(e, OperationType.WRITE, "$COLLECTION_CONFIG/$DOC_SUPPORT")
            Result.failure(e)
        }
    }
}
