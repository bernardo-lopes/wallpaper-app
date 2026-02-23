package com.example.photowallpaper.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class GoogleAuthManager(private val context: Context) {

    companion object {
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    }

    private val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestEmail()
        .requestProfile()
        .requestScopes(Scope(DRIVE_READONLY_SCOPE))
        .build()

    private val client: GoogleSignInClient = GoogleSignIn.getClient(context, gso)

    val signInIntent: Intent get() = client.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? =
        GoogleSignIn.getLastSignedInAccount(context)

    fun isSignedIn(): Boolean = getSignedInAccount() != null

    fun hasRequiredScopes(): Boolean {
        val account = getSignedInAccount() ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DRIVE_READONLY_SCOPE))
    }

    fun handleSignInResult(data: Intent?): GoogleSignInAccount {
        val task = GoogleSignIn.getSignedInAccountFromIntent(data)
        return task.getResult(Exception::class.java)
    }

    suspend fun getAccessToken(): String? = withContext(Dispatchers.IO) {
        val account = getSignedInAccount() ?: return@withContext null
        try {
            val scopeString = "oauth2:$DRIVE_READONLY_SCOPE"
            Log.d("GoogleAuthManager", "Requesting token for scope: $scopeString")

            val token = GoogleAuthUtil.getToken(
                context,
                account.account!!,
                scopeString
            )
            token
        } catch (e: UserRecoverableAuthException) {
            Log.w("GoogleAuthManager", "UserRecoverableAuthException: ${e.message}")
            throw e
        } catch (e: Exception) {
            Log.e("GoogleAuthManager", "Error getting access token", e)
            null
        }
    }

    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        try {
            Log.d("GoogleAuthManager", "Invalidating token")
            GoogleAuthUtil.clearToken(context, token)
        } catch (e: Exception) {
            Log.e("GoogleAuthManager", "Error clearing token", e)
        }
    }

    suspend fun silentSignIn(): GoogleSignInAccount? =
        suspendCancellableCoroutine { cont ->
            client.silentSignIn().addOnSuccessListener { account ->
                cont.resume(account)
            }.addOnFailureListener {
                cont.resume(null)
            }
        }

    suspend fun signOut() {
        suspendCancellableCoroutine { cont ->
            client.signOut().addOnCompleteListener {
                cont.resume(Unit)
            }
        }
    }

    suspend fun revokeAccess() {
        suspendCancellableCoroutine { cont ->
            client.revokeAccess().addOnCompleteListener {
                cont.resume(Unit)
            }
        }
    }
}
