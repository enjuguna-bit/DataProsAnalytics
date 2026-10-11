package com.datapros.app.billing

import android.content.Context
import java.time.LocalDate

/**
 * Manages the free-tier daily usage counter.
 *
 * Thread-safety note: Both [evaluate] and [recordSuccessfulUsage] acquire the same
 * lock to prevent a TOCTOU race where two coroutines could both observe
 * `count < FREE_DAILY_REPORT_LIMIT` and both be allowed past the gate.
 */
object UsageLimitManager {

    private const val PREFS           = "subscriptions"
    private const val KEY_USAGE_DAY   = "free_usage_day"
    private const val KEY_USAGE_COUNT = "free_usage_count"

    const val FREE_DAILY_REPORT_LIMIT = 2

    /** Snapshot of the current usage decision. */
    data class UsageDecision(
        val allowed: Boolean,
        val premium: Boolean,
        val used:    Int,
        val limit:   Int
    ) {
        val remaining: Int get() = (limit - used).coerceAtLeast(0)
    }

    /**
     * Returns the number of free reports still available today.
     * Returns [FREE_DAILY_REPORT_LIMIT] when the user has an active subscription
     * (unlimited, but we report the free limit for display purposes).
     */
    fun remainingToday(context: Context): Int = evaluate(context).remaining

    /**
     * Evaluates whether a new report run is allowed.
     *
     * The entire read is done inside [synchronized] so that a concurrent
     * [recordSuccessfulUsage] cannot cause the counter to be read stale.
     */
    fun evaluate(context: Context): UsageDecision {
        synchronized(this) {
            if (SubscriptionHelper.hasActiveSubscription(context)) {
                return UsageDecision(allowed = true, premium = true, used = 0, limit = FREE_DAILY_REPORT_LIMIT)
            }
            val (day, count) = readUsage(context)
            val todayCount = if (day == todayKey()) count else 0
            return UsageDecision(
                allowed = todayCount < FREE_DAILY_REPORT_LIMIT,
                premium = false,
                used    = todayCount,
                limit   = FREE_DAILY_REPORT_LIMIT
            )
        }
    }

    /**
     * Atomically increments today's usage counter.
     * No-op for premium users.
     *
     * Must be called only after a report was successfully generated (not merely started).
     */
    fun recordSuccessfulUsage(context: Context) {
        synchronized(this) {
            if (SubscriptionHelper.hasActiveSubscription(context)) return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = todayKey()
            val storedDay = prefs.getString(KEY_USAGE_DAY, null)
            val count     = if (storedDay == today) prefs.getInt(KEY_USAGE_COUNT, 0) else 0
            prefs.edit()
                .putString(KEY_USAGE_DAY, today)
                .putInt(KEY_USAGE_COUNT, count + 1)
                .apply()
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Read (day, count) from prefs without acquiring a lock — callers must synchronize. */
    private fun readUsage(context: Context): Pair<String?, Int> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USAGE_DAY, null) to prefs.getInt(KEY_USAGE_COUNT, 0)
    }

    private fun todayKey(): String = LocalDate.now().toString()
}
