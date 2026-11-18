package com.example.data.subscription

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

sealed interface GoogleSignInOutcome {
    data class Success(val user: FirebaseUser) : GoogleSignInOutcome
    data class Cancelled(val detailMessage: String) : GoogleSignInOutcome
    data class Failure(val message: String) : GoogleSignInOutcome
}

object GoogleAuthHelper {
    private const val TAG = "GoogleAuthHelper"

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    /**
     * Interactive Credential Manager Google Sign-In flow using the Web Client ID
     * (`R.string.default_web_client_id` generated from `google-services.json`).
     * Exchanges the Google ID token with FirebaseAuth via `GoogleAuthProvider.getCredential`.
     */
    suspend fun signInWithGoogle(context: Context): GoogleSignInOutcome {
        val activity = context.findActivity()
            ?: return GoogleSignInOutcome.Failure(
                "Unable to launch Google Sign-In: an Activity context is required."
            )

        val serverClientId = runCatching {
            activity.getString(R.string.default_web_client_id).trim()
        }.getOrNull().orEmpty()

        if (serverClientId.isBlank()) {
            return GoogleSignInOutcome.Failure(
                "Google Sign-In Web Client ID (default_web_client_id) is missing in Firebase configuration."
            )
        }

        val credentialManager = CredentialManager.create(activity)

        return try {
            val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = serverClientId)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(activity, request)
            val user = exchangeCredentialWithFirebase(result.credential)
            GoogleSignInOutcome.Success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
            val rawDetail = e.message?.trim().orEmpty()
            val detail = if (rawDetail.isNotBlank()) {
                "Google Sign-In cancelled or failed: $rawDetail"
            } else {
                "Google Sign-In was cancelled before completion. If you selected a Gmail account, verify the APK SHA-1 fingerprint matches Firebase."
            }
            GoogleSignInOutcome.Cancelled(detail)
        } catch (e: NoCredentialException) {
            Log.e(TAG, "No Google credential available: ${e.message}", e)
            val detail = e.message?.trim().takeIf { !it.isNullOrBlank() }
                ?: "No Google account is available on this device. Please add a Google account in Settings."
            GoogleSignInOutcome.Failure(detail)
        } catch (e: GetCredentialException) {
            Log.e(TAG, "Credential Manager sign-in failed [${e.type}]: ${e.message}", e)
            val msg = e.message?.trim().takeIf { !it.isNullOrBlank() } ?: "Google Sign-In failed."
            GoogleSignInOutcome.Failure("Google Sign-In error (${e.type}): $msg")
        } catch (e: FirebaseAuthException) {
            Log.e(TAG, "FirebaseAuth signInWithCredential failed [${e.errorCode}]: ${e.message}", e)
            val msg = e.localizedMessage?.trim().takeIf { !it.isNullOrBlank() }
                ?: e.message?.trim().takeIf { !it.isNullOrBlank() }
                ?: "Firebase authentication failed."
            GoogleSignInOutcome.Failure("Firebase Auth (${e.errorCode}): $msg")
        } catch (e: Exception) {
            Log.e(TAG, "Google Auth exchange failed", e)
            GoogleSignInOutcome.Failure(
                e.localizedMessage?.trim().takeIf { !it.isNullOrBlank() }
                    ?: "Google authentication failed."
            )
        }
    }

    private suspend fun exchangeCredentialWithFirebase(
        credential: androidx.credentials.Credential
    ): FirebaseUser {
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            throw IllegalStateException("Google Sign-In returned an unsupported credential type (${credential.type}).")
        }

        val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
        if (googleCred.idToken.isBlank()) {
            throw IllegalStateException("Google Sign-In did not return an ID token. Verify Web Client ID configuration.")
        }

        val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
        val authResult = FirebaseAuth.getInstance().signInWithCredential(firebaseCred).await()
        return authResult.user ?: FirebaseAuth.getInstance().currentUser
            ?: throw IllegalStateException("FirebaseAuth succeeded but returned no current user.")
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
