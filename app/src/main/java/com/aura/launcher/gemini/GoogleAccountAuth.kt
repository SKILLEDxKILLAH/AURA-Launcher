package com.aura.launcher.gemini

import android.accounts.Account
import android.content.Context
import android.content.pm.PackageManager
import com.aura.launcher.ActivityBridge
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Thrown when Gemini needs the user to sign in (or re-consent) before it can continue. */
class SignInRequiredException(message: String = "Sign in with Google to use Gemini") : IOException(message)

/**
 * Signs the user in with their Google account and obtains an OAuth access token for the
 * Gemini API, so AURA talks to Gemini directly with no API key and without opening the Gemini app.
 *
 * Tokens last about an hour; after the first consent they are refreshed silently.
 */
class GoogleAccountAuth(private val context: Context) {
    private val client get() = Identity.getAuthorizationClient(context)
    private val mutex = Mutex()
    private var token: String? = null
    private var tokenExpiresAt = 0L
    private val sp = context.getSharedPreferences("aura_google_auth", Context.MODE_PRIVATE)

    /**
     * AURA asks for the narrowest access first (Gemini only). If Google ever says that isn't
     * enough, it switches to the broader Cloud scope once and remembers that.
     */
    private var broadScopes: Boolean
        get() = sp.getBoolean(K_BROAD, false)
        set(v) { sp.edit().putBoolean(K_BROAD, v).apply() }

    private val scopes get() = if (broadScopes) SCOPES else NARROW_SCOPES

    /** Called when Gemini refused the token for missing scope; returns true if a retry may help. */
    suspend fun widenScopes(): Boolean = mutex.withLock {
        if (broadScopes) return@withLock false
        broadScopes = true
        token = null
        tokenExpiresAt = 0L
        true
    }

    data class Profile(val email: String, val name: String?)

    /**
     * Shows Google's account picker and consent screen when needed. Returns the signed-in profile.
     * Must be called while the launcher is in the foreground.
     */
    suspend fun signIn(): Profile = mutex.withLock {
        val result = authorize(interactive = true)
        val access = result.accessToken ?: throw IOException("Google didn't return an access token")
        remember(access)
        fetchProfile(access)
    }

    /** A valid access token, refreshed silently. Throws [SignInRequiredException] if consent is needed. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = mutex.withLock {
        val cached = token
        if (!forceRefresh && cached != null && System.currentTimeMillis() < tokenExpiresAt) return cached
        if (forceRefresh && cached != null) runCatching { client.clearToken(ClearTokenRequest.builder().setToken(cached).build()).await() }
        val result = authorize(interactive = false)
        val access = result.accessToken ?: throw SignInRequiredException()
        remember(access)
        access
    }

    suspend fun signOut(email: String?) = mutex.withLock {
        token?.let { t -> runCatching { client.clearToken(ClearTokenRequest.builder().setToken(t).build()).await() } }
        if (email != null) {
            runCatching {
                client.revokeAccess(
                    RevokeAccessRequest.builder().setAccount(Account(email, "com.google")).setScopes(scopes).build()
                ).await()
            }
        }
        token = null
        tokenExpiresAt = 0L
    }

    private suspend fun authorize(interactive: Boolean): AuthorizationResult {
        val request = AuthorizationRequest.builder().setRequestedScopes(scopes).build()
        val first = try {
            client.authorize(request).await()
        } catch (e: ApiException) {
            throw explain(e)
        }
        if (!first.hasResolution()) return first
        if (!interactive) throw SignInRequiredException()

        val activity = ActivityBridge.activity ?: throw SignInRequiredException("Open AURA to finish signing in")
        val data = activity.resolveIntentSender(first.pendingIntent!!.intentSender)
            ?: throw IOException("Sign-in was cancelled")
        return try {
            client.getAuthorizationResultFromIntent(data)
        } catch (e: ApiException) {
            throw explain(e)
        }
    }

    private fun remember(access: String) {
        token = access
        tokenExpiresAt = System.currentTimeMillis() + TOKEN_LIFETIME_MS
    }

    private suspend fun fetchProfile(access: String): Profile = withContext(Dispatchers.IO) {
        val conn = (URL("https://www.googleapis.com/oauth2/v3/userinfo").openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $access")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("Couldn't read your Google profile (${conn.responseCode})")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            Profile(json.getString("email"), json.optString("name").takeIf { it.isNotBlank() })
        } finally {
            conn.disconnect()
        }
    }

    private fun explain(e: ApiException): IOException = when (e.statusCode) {
        CommonStatusCodes.DEVELOPER_ERROR -> IOException(
            "Google sign-in isn't set up for this build yet. In Google Cloud, add an Android OAuth client for " +
                "${context.packageName} with SHA-1 ${signingSha1(context)}. See AURA settings → Google setup."
        )
        CommonStatusCodes.NETWORK_ERROR -> IOException("No connection — couldn't reach Google")
        CommonStatusCodes.CANCELED -> IOException("Sign-in was cancelled")
        else -> IOException("Google sign-in failed (${CommonStatusCodes.getStatusCodeString(e.statusCode)})")
    }

    companion object {
        /** The Gemini API with the user's own credentials, plus their email (shown in AURA). */
        val NARROW_SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/generative-language.retriever"),
            Scope("https://www.googleapis.com/auth/userinfo.email"),
        )

        /** Fallback: also Google Cloud, as in Google's gcloud instructions. */
        val SCOPES = listOf(
            Scope("https://www.googleapis.com/auth/generative-language.retriever"),
            Scope("https://www.googleapis.com/auth/cloud-platform"),
            Scope("https://www.googleapis.com/auth/userinfo.email"),
        )
        private const val TOKEN_LIFETIME_MS = 50 * 60 * 1000L
        private const val K_BROAD = "broad_scopes"

        /** SHA-1 of the certificate that signed this APK, formatted for the Google Cloud console. */
        fun signingSha1(context: Context): String = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val cert = info.signingInfo!!.apkContentsSigners.first().toByteArray()
            MessageDigest.getInstance("SHA-1").digest(cert).joinToString(":") { "%02X".format(it) }
        }.getOrDefault("unknown")
    }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
