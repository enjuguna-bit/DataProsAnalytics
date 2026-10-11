package com.datapros.app.billing

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import com.datapros.app.BuildConfig
import com.datapros.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Helper object to launch subscription from anywhere in the app
 * Usage: SubscriptionHelper.launch(context)
 */
object SubscriptionHelper {

    private const val TAG = "SubscriptionHelper"

    // TEMPORARY: local founder access while the payment processor is unavailable.
    // Remove this override once a replacement processor is configured.
    private const val TEMPORARY_UNLIMITED_PHONE = "254723135659"

    private const val PREFS = "subscriptions"
    private val BASE_URL: String = BuildConfig.BILLING_BASE_URL
    private val STATUS_BASE_URL: String = "$BASE_URL/status"
    private val INIT_URL: String = "$BASE_URL/init"
    private val CLAIM_URL: String = "$BASE_URL/claim"
    private const val STATUS_CACHE_MS = 5 * 60 * 1000L // 5 minutes

    private const val KEY_LAST_PHONE = "last_phone"
    private const val KEY_LAST_ATTEMPT = "last_attempt"
    private const val KEY_LAST_INTENT = "last_intent_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PREMIUM_ACTIVE = "premium_active"
    private const val KEY_PREMIUM_PLAN = "premium_plan"
    private const val KEY_PREMIUM_UNTIL = "premium_until"
    private const val KEY_PREMIUM_LAST_CHECK = "premium_last_checked"
    private const val KEY_PREMIUM_MESSAGE = "premium_message"
    private const val KEY_PREMIUM_REASON = "premium_reason"
    private const val KEY_PREMIUM_CLAIM_REQUIRED = "premium_claim_required"

    // Use secure client with certificate pinning for billing API
    private val httpClient: OkHttpClient by lazy { NetworkSecurityConfig.createSecureBillingClient() }
    // Fallback client used only when pinning/handshake validation fails
    private val fallbackHttpClient: OkHttpClient by lazy { NetworkSecurityConfig.createStandardClient() }

    data class SubscriptionStatus(
        val premium: Boolean,
        val plan: String?,
        val paidUntilMillis: Long?,
        val lastCheckedMillis: Long,
        val message: String? = null,
        val reason: String? = null,
        val claimRequired: Boolean = false
    )

    data class ClaimResult(
        val success: Boolean,
        val processing: Boolean,
        val message: String?
    )
    
    /**
     * Launch SubscriptionActivity from any Activity or Fragment
     */
    fun launch(context: Context) {
        val intent = Intent(context, SubscriptionActivity::class.java)
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Unable to open subscription page", Toast.LENGTH_LONG).show()
        }
    }
    
    fun hasActiveSubscription(context: Context): Boolean {
        if (hasTemporaryUnlimitedAccess(context)) return true
        return isActiveStatus(getCachedStatus(context))
    }

    /** Temporary local entitlement for the owner account during payment downtime. */
    fun hasTemporaryUnlimitedAccess(context: Context): Boolean {
        return normalizePhoneForComparison(getLastPhone(context)) == TEMPORARY_UNLIMITED_PHONE
    }

    fun isActiveStatus(
        status: SubscriptionStatus,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!status.premium) return false
        return status.paidUntilMillis?.let { it > nowMillis } ?: true
    }

    fun isPendingStatus(
        context: Context,
        status: SubscriptionStatus,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (isActiveStatus(status, nowMillis)) return false
        if (status.claimRequired) return true
        val details = statusDetailText(status)
        if (
            details.contains("processing") ||
            details.contains("pending") ||
            details.contains("claim") ||
            details.contains("confirm") ||
            details.contains("verif")
        ) {
            return true
        }
        if (isExplicitlyInactiveStatus(status, nowMillis)) return false
        return isPaymentPending(context)
    }

    fun isExplicitlyInactiveStatus(
        status: SubscriptionStatus,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (isActiveStatus(status, nowMillis)) return false
        if (status.paidUntilMillis != null && status.paidUntilMillis <= nowMillis) {
            return true
        }
        val details = statusDetailText(status)
        return details.contains("inactive") ||
            details.contains("expired") ||
            details.contains("not active") ||
            details.contains("not found") ||
            details.contains("no subscription") ||
            details.contains("free plan") ||
            details.contains("not subscribed")
    }

    fun formatPlanLabel(plan: String?): String? {
        val normalized = plan?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return normalized
            .replace("_", " ")
            .split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { token ->
                token.replaceFirstChar { first ->
                    if (first.isLowerCase()) first.titlecase() else first.toString()
                }
            }
    }

    fun isPaymentPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        return lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < 5 * 60 * 1000L
    }

    fun getCachedStatus(context: Context): SubscriptionStatus {
        if (hasTemporaryUnlimitedAccess(context)) {
            return SubscriptionStatus(
                premium = true,
                plan = "temporary_unlimited",
                paidUntilMillis = null,
                lastCheckedMillis = System.currentTimeMillis(),
                message = "Temporary unlimited access"
            )
        }
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val premium = prefs.getBoolean(KEY_PREMIUM_ACTIVE, false)
        val plan = prefs.getString(KEY_PREMIUM_PLAN, null)
        val paidUntilRaw = prefs.getLong(KEY_PREMIUM_UNTIL, 0L)
        val paidUntil = if (paidUntilRaw > 0) paidUntilRaw else null
        val lastChecked = prefs.getLong(KEY_PREMIUM_LAST_CHECK, 0L)
        val message = prefs.getString(KEY_PREMIUM_MESSAGE, null)
        val reason = prefs.getString(KEY_PREMIUM_REASON, null)
        val claimRequired = prefs.getBoolean(KEY_PREMIUM_CLAIM_REQUIRED, false)
        return SubscriptionStatus(
            premium = premium,
            plan = plan,
            paidUntilMillis = paidUntil,
            lastCheckedMillis = lastChecked,
            message = message,
            reason = reason,
            claimRequired = claimRequired
        )
    }

    suspend fun refreshSubscriptionStatus(
        context: Context,
        forceRefresh: Boolean = false
    ): SubscriptionStatus = withContext(Dispatchers.IO) {
        if (hasTemporaryUnlimitedAccess(context)) {
            return@withContext SubscriptionStatus(
                premium = true,
                plan = "temporary_unlimited",
                paidUntilMillis = null,
                lastCheckedMillis = System.currentTimeMillis(),
                message = "Temporary unlimited access"
            )
        }

        val cached = getCachedStatus(context)
        val lastPhone = getLastPhone(context)
        val deviceId = getDeviceId(context)

        if (lastPhone.isNullOrBlank()) {
            if (isActiveStatus(cached)) {
                return@withContext cached
            }
            val fallback = SubscriptionStatus(
                premium = false,
                plan = null,
                paidUntilMillis = null,
                lastCheckedMillis = System.currentTimeMillis(),
                message = context.getString(R.string.subscription_sync_phone_hint)
            )
            saveStatusCache(context, fallback)
            return@withContext fallback
        }

        if (!forceRefresh &&
            cached.lastCheckedMillis > 0 &&
            System.currentTimeMillis() - cached.lastCheckedMillis < STATUS_CACHE_MS
        ) {
            return@withContext cached
        }

        val candidates = normalizePhoneCandidates(lastPhone)
        var latest: SubscriptionStatus? = null

        for (phone in candidates) {
            val status = fetchStatusFromServer(phone, deviceId)
            if (status != null) {
                latest = status
                if (status.premium) break
            }
        }

        if (latest != null) {
            saveStatusCache(context, latest)
            reconcileTransientState(context, latest)
            return@withContext latest
        }

        if (cached.lastCheckedMillis > 0) {
            return@withContext cached
        }

        val fallback = SubscriptionStatus(false, null, null, System.currentTimeMillis())
        saveStatusCache(context, fallback)
        return@withContext fallback
    }

    fun refreshSubscriptionStatusBlocking(context: Context): SubscriptionStatus {
        return refreshSubscriptionStatusBlocking(context, false)
    }

    fun refreshSubscriptionStatusBlocking(context: Context, forceRefresh: Boolean): SubscriptionStatus {
        return runBlocking {
            refreshSubscriptionStatus(context, forceRefresh)
        }
    }
    
    fun requiresSubscription(context: Context): Boolean {
        return !hasActiveSubscription(context)
    }

    fun getDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        } catch (_: Exception) {
            null
        }
        val stableId = androidId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.takeIf { it != "9774d56d682e549c" }

        val chosen = stableId ?: java.util.UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, chosen).apply()
        return chosen
    }

    fun getLastPhone(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PHONE, null)
    }

    fun savePhone(context: Context, phone: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LAST_PHONE, phone).apply()
    }

    fun recordPaymentAttempt(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis()).apply()
    }

    fun clearPaymentAttempt(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_LAST_ATTEMPT).apply()
    }

    fun saveLastIntent(context: Context, intentId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_INTENT, intentId).apply()
    }

    fun getLastIntent(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_INTENT, null)
    }

    fun clearLastIntent(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_INTENT).apply()
    }

    private fun normalizePhoneCandidates(phone: String): List<String> {
        val trimmed = phone.trim()
        val cleaned = trimmed.replace(Regex("[^0-9+]"), "")
        val digits = cleaned.replace("+", "")

        val normalized = when {
            digits.startsWith("0") && digits.length == 10 -> "254" + digits.substring(1)
            digits.startsWith("254") && digits.length == 12 -> digits
            (digits.startsWith("7") || digits.startsWith("1")) && digits.length == 9 -> "254$digits"
            else -> null
        }

        val candidates = mutableListOf<String>()
        if (normalized != null) {
            candidates.add(normalized)
            candidates.add("+$normalized")
        }
        if (digits.isNotBlank()) {
            candidates.add(digits)
            candidates.add("+$digits")
        }
        if (cleaned.isNotBlank()) {
            candidates.add(cleaned)
        }
        return candidates.distinct()
    }

    private fun normalizePhoneForComparison(phone: String?): String? {
        if (phone.isNullOrBlank()) return null
        val digits = phone.replace(Regex("[^0-9]"), "")
        return when {
            digits.startsWith("0") && digits.length == 10 -> "254${digits.substring(1)}"
            digits.startsWith("254") && digits.length == 12 -> digits
            (digits.startsWith("7") || digits.startsWith("1")) && digits.length == 9 -> "254$digits"
            else -> null
        }
    }

    private fun fetchStatusFromServer(phone: String, deviceId: String): SubscriptionStatus? {
        val url = STATUS_BASE_URL.toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("phone", phone)
            ?.addQueryParameter("device_id", deviceId)
            ?.build()
            ?: return null

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        executeBillingRequest(request).use { response ->
            if (response == null) return null
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null

            val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val premium = json.optBoolean("premium", false)
            val plan = json.optString("plan", "").takeIf { it.isNotBlank() }
            val paidUntil = parseIsoToMillis(json.optString("paid_until", "").takeIf { it.isNotBlank() })
            val lastChecked = System.currentTimeMillis()
            val message = json.optString("message").takeIf { it.isNotBlank() }
            val reason = json.optString("reason").takeIf { it.isNotBlank() }
            val claimRequired = json.optBoolean("claim_required", false)

            return SubscriptionStatus(premium, plan, paidUntil, lastChecked, message, reason, claimRequired)
        }
    }

    suspend fun createPaymentIntent(
        phone: String,
        deviceId: String,
        plan: String,
        amount: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("phone", phone)
                put("device_id", deviceId)
                put("plan", plan)
                put("amount", amount)
            }
            val body = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(INIT_URL)
                .post(body)
                .build()

            executeBillingRequest(request).use { response ->
                if (response == null) return@withContext null
                if (!response.isSuccessful) return@withContext null
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) return@withContext null
                val json = runCatching { JSONObject(responseBody) }.getOrNull() ?: return@withContext null
                return@withContext json.optString("intent_id", "").takeIf { it.isNotBlank() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create payment intent", e)
            return@withContext null
        }
    }

    suspend fun claimSubscriptionWithIntent(
        context: Context,
        intentId: String
    ): ClaimResult = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("intent_id", intentId)
                put("device_id", getDeviceId(context))
                getLastPhone(context)?.let { put("phone", it) }
            }
            val body = payload.toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url(CLAIM_URL)
                .post(body)
                .build()

            executeBillingRequest(request).use { response ->
                if (response == null) {
                    return@withContext ClaimResult(false, false, "Unable to reach billing server")
                }
                val responseBody = response.body?.string().orEmpty()
                if (response.code == 202) {
                    val msg = runCatching {
                        JSONObject(responseBody).optString("error")
                    }.getOrNull()
                    return@withContext ClaimResult(false, true, msg)
                }
                if (response.isSuccessful) {
                    return@withContext ClaimResult(true, false, null)
                }
                val msg = runCatching {
                    JSONObject(responseBody).optString("error")
                }.getOrNull()
                return@withContext ClaimResult(false, false, msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to claim subscription", e)
            return@withContext ClaimResult(false, false, e.message)
        }
    }

    private fun executeBillingRequest(request: Request): Response? {
        try {
            return httpClient.newCall(request).execute()
        } catch (e: Exception) {
            if (!shouldFallbackToStandardClient(e)) {
                Log.w(TAG, "Pinned billing request failed", e)
                return null
            }
            return try {
                Log.w(TAG, "Pinned TLS failed, retrying billing request with standard client", e)
                fallbackHttpClient.newCall(request).execute()
            } catch (fallbackError: Exception) {
                Log.w(TAG, "Fallback billing request failed", fallbackError)
                null
            }
        }
    }

    private fun shouldFallbackToStandardClient(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return error is SSLPeerUnverifiedException ||
            error is SSLHandshakeException ||
            message.contains("Certificate pinning failure", ignoreCase = true) ||
            message.contains("Trust anchor", ignoreCase = true)
    }

    private fun parseIsoToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun reconcileTransientState(context: Context, status: SubscriptionStatus) {
        when {
            isActiveStatus(status) -> {
                clearPaymentAttempt(context)
                clearLastIntent(context)
            }
            isExplicitlyInactiveStatus(status) -> {
                clearPaymentAttempt(context)
            }
        }
    }

    private fun saveStatusCache(context: Context, status: SubscriptionStatus) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_PREMIUM_ACTIVE, status.premium)
            putString(KEY_PREMIUM_PLAN, status.plan)
            putLong(KEY_PREMIUM_UNTIL, status.paidUntilMillis ?: 0L)
            putLong(KEY_PREMIUM_LAST_CHECK, status.lastCheckedMillis)
            putString(KEY_PREMIUM_MESSAGE, status.message)
            putString(KEY_PREMIUM_REASON, status.reason)
            putBoolean(KEY_PREMIUM_CLAIM_REQUIRED, status.claimRequired)
            apply()
        }
    }

    private fun statusDetailText(status: SubscriptionStatus): String {
        return listOfNotNull(status.message, status.reason)
            .joinToString(" ")
            .trim()
            .lowercase()
    }
}
