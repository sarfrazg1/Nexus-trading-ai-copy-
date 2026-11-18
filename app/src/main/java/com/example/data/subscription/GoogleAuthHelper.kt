package com.example.data.subscription

import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.security.SecureRandom
import java.util.Base64

sealed interface GoogleSignInOutcome {
    data class Success(val user: FirebaseUser) : GoogleSignInOutcome
    data object Cancelled : GoogleSignInOutcome
    data class Failure(val message: String) : GoogleSignInOutcome
}

object GoogleAuthHelper {
    private const val TAG = "GoogleAuthHelper"

    private fun generateNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * Two-phase Credential Manager Google Sign-In flow:
     * Phase 1: Silent authorized-account check via GetGoogleIdOption.
     * Phase 2: Explicit Google Sign-In bottom sheet via GetSignInWithGoogleOption.
     */
    suspend fun signInWithGoogle(activityContext: Context): GoogleSignInOutcome {
        val credentialManager = CredentialManager.create(activityContext)
        val serverClientId = runCatching {
            activityContext.getString(R.string.default_web_client_id)
        }.getOrNull().orEmpty()

        if (serverClientId.isBlank()) {
            return GoogleSignInOutcome.Failure(
                "Google Sign-In client ID is missing in generated Firebase configuration."
            )
        }

        // Phase 1: Silent previously-authorized sign-in
        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(true)
                .setNonce(generateNonce())
                .build()

            val silentRequest = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result = credentialManager.getCredential(activityContext, silentRequest)
            val user = exchangeCredentialWithFirebase(result.credential)
            if (user != null) {
                return GoogleSignInOutcome.Success(user)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            // Normal on first sign-in: proceed to explicit Google account picker
        } catch (_: GetCredentialCancellationException) {
            return GoogleSignInOutcome.Cancelled
        } catch (e: GetCredentialException) {
            Log.d(TAG, "Silent Google Sign-In skipped: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "Silent Google Sign-In unexpected error: ${e.message}")
        }

        // Phase 2: Interactive Google Sign-In bottom sheet
        return try {
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId)
                .setNonce(generateNonce())
                .build()

            val explicitRequest = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(activityContext, explicitRequest)
            val user = exchangeCredentialWithFirebase(result.credential)
                ?: return GoogleSignInOutcome.Failure("Google Sign-In returned an unsupported credential type.")
            GoogleSignInOutcome.Success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInOutcome.Cancelled
        } catch (_: NoCredentialException) {
            GoogleSignInOutcome.Failure("No Google account is available on this device. Please add a Google account in Settings.")
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Interactive Google Sign-In failed", e)
            GoogleSignInOutcome.Failure(e.message ?: "Google Sign-In failed.")
        } catch (e: Exception) {
            Log.e(TAG, "Google Auth exchange failed", e)
            GoogleSignInOutcome.Failure(e.message ?: "Authentication failed.")
        }
    }

    private suspend fun exchangeCredentialWithFirebase(
        credential: androidx.credentials.Credential
    ): FirebaseUser? {
        if (credential is CustomCredential &&
            credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
            val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
            val authResult = FirebaseAuth.getInstance().signInWithCredential(firebaseCred).await()
            return authResult.user
        }
        return null
    }

    suspend fun signOut(context: Context) {
        withContext(NonCancellable) {
            runCatching {
                CredentialManager.create(context)
                    .clearCredentialState(ClearCredentialStateRequest())
            }
            FirebaseAuth.getInstance().signOut()
        }
    }
}
