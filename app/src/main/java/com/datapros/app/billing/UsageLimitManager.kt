package com.datapros.app.billing

import android.content.Context
import java.time.LocalDate

object UsageLimitManager {

    private const val PREFS = "subscriptions"
    private const val KEY_USAGE_DAY = "free_usage_day"
    private const val KEY_USAGE_COUNT = "free_usage_count"

    const val FREE_DAILY_REPORT_LIMIT = 2

    data class UsageDecision(
        val allowed: Boolean,
        val premium: Boolean,
        val used: Int,
        val limit: Int
    ) {
        val remaining: Int
            get() = (limit - used).coerceAtLeast(0)
    }

    fun evaluate(context: Context): UsageDecision {
        if (SubscriptionHelper.hasActiveSubscription(context)) {
            return UsageDecision(
                allowed = true,
                premium = true,
                used = 0,
                limit = FREE_DAILY_REPORT_LIMIT
            )
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val storedDay = prefs.getString(KEY_USAGE_DAY, null)
        val count = if (storedDay == today) prefs.getInt(KEY_USAGE_COUNT, 0) else 0

        return UsageDecision(
            allowed = count < FREE_DAILY_REPORT_LIMIT,
            premium = false,
            used = count,
            limit = FREE_DAILY_REPORT_LIMIT
        )
    }

    fun recordSuccessfulUsage(context: Context) {
        if (SubscriptionHelper.hasActiveSubscription(context)) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = todayKey()
        val storedDay = prefs.getString(KEY_USAGE_DAY, null)
        val count = if (storedDay == today) prefs.getInt(KEY_USAGE_COUNT, 0) else 0

        prefs.edit()
            .putString(KEY_USAGE_DAY, today)
            .putInt(KEY_USAGE_COUNT, count + 1)
            .apply()
    }

    private fun todayKey(): String = LocalDate.now().toString()
}
