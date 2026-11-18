package com.example.data.subscription

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject

enum class OperationType {
    CREATE,
    UPDATE,
    DELETE,
    LIST,
    GET,
    WRITE
}

object FirestoreErrorHandler {
    private const val TAG = "NexusFirestoreError"

    fun handleFirestoreError(
        error: Exception,
        operationType: OperationType,
        path: String?
    ) {
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        val payload = JSONObject().apply {
            put("error", error.message ?: error.javaClass.simpleName)
            put("operationType", operationType.name.lowercase())
            put("path", path ?: JSONObject.NULL)
            put(
                "authInfo",
                JSONObject().apply {
                    put("userId", user?.uid ?: JSONObject.NULL)
                    put("email", user?.email ?: JSONObject.NULL)
                    put("emailVerified", user?.isEmailVerified ?: false)
                }
            )
        }
        Log.e(TAG, "Firestore Error: $payload", error)
    }

    fun friendlyMessage(error: Throwable, fallback: String): String {
        val msg = error.message.orEmpty()
        return when {
            msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                "Access denied by server security rules. Verify your account or activation code status."
            msg.contains("UNAVAILABLE", ignoreCase = true) ||
                msg.contains("network", ignoreCase = true) ||
                msg.contains("offline", ignoreCase = true) ->
                "Network unavailable. An active internet connection is required to verify activation status."
            !error.message.isNullOrBlank() -> error.message!!
            else -> fallback
        }
    }
}
