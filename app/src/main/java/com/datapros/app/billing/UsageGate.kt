package com.datapros.app.billing

import androidx.fragment.app.Fragment
import com.datapros.app.R
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar

object UsageGate {

    fun ensureAllowed(fragment: Fragment): Boolean {
        if (!fragment.isAdded) return false
        val context = fragment.requireContext()
        val decision = UsageLimitManager.evaluate(context)
        if (decision.allowed) {
            return true
        }

        if (SubscriptionHelper.isPaymentPending(context)) {
            Snackbar.make(
                fragment.requireView(),
                fragment.getString(R.string.usage_limit_payment_pending),
                Snackbar.LENGTH_LONG
            ).show()
            return false
        }

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.usage_limit_title)
            .setMessage(fragment.getString(R.string.usage_limit_message, decision.limit))
            .setPositiveButton(R.string.usage_limit_upgrade) { _, _ ->
                SubscriptionHelper.launch(context)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        return false
    }

    fun recordSuccess(fragment: Fragment) {
        if (!fragment.isAdded) return
        UsageLimitManager.recordSuccessfulUsage(fragment.requireContext())
    }
}
