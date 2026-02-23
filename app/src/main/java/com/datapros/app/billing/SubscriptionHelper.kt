package com.datapros.app.billing

import android.content.Context
import android.content.Intent
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant

/**
 * Helper object to launch subscription from anywhere in the app
 * Usage: SubscriptionHelper.launch(context)
 */
object SubscriptionHelper {

    private const val PREFS = "subscriptions"
    private const val BASE_URL = "https://bulksmsbilling.enjuguna794.workers.dev"
    private const val STATUS_BASE_URL = "$BASE_URL/status"
    private const val INIT_URL = "$BASE_URL/init"
    private const val CLAIM_URL = "$BASE_URL/claim"
    private const val STATUS_CACHE_MS = 5 * 60 * 1000L

    private const val KEY_LAST_PHONE = "last_phone"
    private const val KEY_LAST_ATTEMPT = "last_attempt"
    private const val KEY_LAST_INTENT = "last_intent_id"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_PREMIUM_ACTIVE = "premium_active"
    private const val KEY_PREMIUM_PLAN = "premium_plan"
    private const val KEY_PREMIUM_UNTIL = "premium_until"
    private const val KEY_PREMIUM_LAST_CHECK = "premium_last_checked"

    private val httpClient: OkHttpClient by lazy { OkHttpClient() }

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

    fun launch(context: Context) {
        val intent = Intent(context, SubscriptionActivity::class.java)
        context.startActivity(intent)
    }

    /**
     * Check cached subscription state (fast, offline).
     * Use refreshSubscriptionStatus() to update from server.
     */
    fun hasActiveSubscription(context: Context): Boolean {
        val cached = getCachedStatus(context)
        if (!cached.premium) return false
        val now = System.currentTimeMillis()
        return cached.paidUntilMillis?.let { it > now } ?: true
    }

    /**
     * Returns true if a payment attempt was made very recently.
     */
    fun isPaymentPending(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastAttempt = prefs.getLong(KEY_LAST_ATTEMPT, 0L)
        return lastAttempt > 0 && System.currentTimeMillis() - lastAttempt < 5 * 60 * 1000L
    }

    fun getCachedStatus(context: Context): SubscriptionStatus {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val premium = prefs.getBoolean(KEY_PREMIUM_ACTIVE, false)
        val plan = prefs.getString(KEY_PREMIUM_PLAN, null)
        val paidUntilRaw = prefs.getLong(KEY_PREMIUM_UNTIL, 0L)
        val paidUntil = if (paidUntilRaw > 0) paidUntilRaw else null
        val lastChecked = prefs.getLong(KEY_PREMIUM_LAST_CHECK, 0L)
        return SubscriptionStatus(
            premium = premium,
            plan = plan,
            paidUntilMillis = paidUntil,
            lastCheckedMillis = lastChecked
        )
    }

    /**
     * Refresh status from the Cloudflare Worker and update cache.
     * When forceRefresh is false, a fresh cache returns immediately.
     */
    suspend fun refreshSubscriptionStatus(
        context: Context,
        forceRefresh: Boolean = false
    ): SubscriptionStatus = withContext(Dispatchers.IO) {
        val cached = getCachedStatus(context)
        val lastPhone = getLastPhone(context)
        val deviceId = getDeviceId(context)

        if (lastPhone.isNullOrBlank()) {
            if (cached.lastCheckedMillis > 0) {
                return@withContext cached
            }
            val fallback = SubscriptionStatus(
                premium = false,
                plan = null,
                paidUntilMillis = null,
                lastCheckedMillis = System.currentTimeMillis()
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
            return@withContext latest
        }

        // If the network fails, do not wipe a previously active cache.
        if (cached.lastCheckedMillis > 0) {
            return@withContext cached
        }

        val fallback = SubscriptionStatus(
            premium = false,
            plan = null,
            paidUntilMillis = null,
            lastCheckedMillis = System.currentTimeMillis()
        )
        saveStatusCache(context, fallback)
        return@withContext fallback
    }

    /**
     * Java-friendly blocking refresh wrapper.
     */
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

    private fun getLastPhone(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LAST_PHONE, null)
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

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return null

            val json = JSONObject(body)
            val premium = json.optBoolean("premium", false)
            val plan = json.optString("plan", "").takeIf { it.isNotBlank() }
            val paidUntil = parseIsoToMillis(json.optString("paid_until", "").takeIf { it.isNotBlank() })
            val message = json.optString("message", "").takeIf { it.isNotBlank() }
            val reason = json.optString("reason", "").takeIf { it.isNotBlank() }
            val claimRequired = json.optBoolean("claim_required", false)
            val lastChecked = System.currentTimeMillis()

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

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val responseBody = response.body?.string().orEmpty()
                if (responseBody.isBlank()) return@withContext null
                val json = JSONObject(responseBody)
                return@withContext json.optString("intent_id", "").takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
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

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(responseBody) }.getOrNull()
                val message = json?.optString("message")?.takeIf { it.isNotBlank() }
                    ?: json?.optString("error")?.takeIf { it.isNotBlank() }

                if (response.isSuccessful) {
                    return@withContext ClaimResult(true, false, message)
                }
                if (response.code == 202) {
                    return@withContext ClaimResult(false, true, message)
                }
                return@withContext ClaimResult(false, false, message)
            }
        } catch (e: Exception) {
            return@withContext ClaimResult(false, false, e.message)
        }
    }

    private fun parseIsoToMillis(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private fun saveStatusCache(context: Context, status: SubscriptionStatus) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean(KEY_PREMIUM_ACTIVE, status.premium)
            putString(KEY_PREMIUM_PLAN, status.plan)
            putLong(KEY_PREMIUM_UNTIL, status.paidUntilMillis ?: 0L)
            putLong(KEY_PREMIUM_LAST_CHECK, status.lastCheckedMillis)
            apply()
        }
    }
}
