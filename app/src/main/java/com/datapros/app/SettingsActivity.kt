package com.datapros.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.datapros.app.billing.SubscriptionActivity
import com.datapros.app.billing.SubscriptionHelper
import com.datapros.app.databinding.ActivitySettingsBinding
import com.datapros.app.utils.UiMotion
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.settingsToolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        binding.btnOpenSubscription.setOnClickListener {
            startActivity(Intent(this, SubscriptionActivity::class.java))
        }
        binding.btnRefreshSubscriptionStatus.setOnClickListener {
            refreshSubscriptionStatus(forceRefresh = true)
        }
        binding.btnDownloadGuide.setOnClickListener {
            openOfflineGuide()
        }

        renderSubscriptionStatus(SubscriptionHelper.getCachedStatus(this))
        UiMotion.runEntryStagger(binding.root)
    }

    override fun onResume() {
        super.onResume()
        refreshSubscriptionStatus(forceRefresh = false)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun refreshSubscriptionStatus(forceRefresh: Boolean) {
        binding.btnRefreshSubscriptionStatus.isEnabled = false
        binding.txtSubscriptionStatusLabel.text = getString(R.string.settings_subscription_status_loading)
        binding.txtSubscriptionStatusLabel.setTextColor(
            ContextCompat.getColor(this, R.color.status_info)
        )

        lifecycleScope.launch {
            val status = resolveSubscriptionStatus(forceRefresh)
            binding.btnRefreshSubscriptionStatus.isEnabled = true
            renderSubscriptionStatus(status)
        }
    }

    private suspend fun resolveSubscriptionStatus(
        forceRefresh: Boolean
    ): SubscriptionHelper.SubscriptionStatus {
        var status = SubscriptionHelper.refreshSubscriptionStatus(this, forceRefresh)
        val shouldAttemptClaim =
            !SubscriptionHelper.isActiveStatus(status) &&
                (status.claimRequired || SubscriptionHelper.isPendingStatus(this, status))

        if (!shouldAttemptClaim) {
            return status
        }

        val intentId = SubscriptionHelper.getLastIntent(this)
        if (intentId.isNullOrBlank()) {
            return status
        }

        val claim = SubscriptionHelper.claimSubscriptionWithIntent(this, intentId)
        if (claim.success || claim.processing) {
            status = SubscriptionHelper.refreshSubscriptionStatus(this, true)
        }
        return status
    }

    private fun renderSubscriptionStatus(status: SubscriptionHelper.SubscriptionStatus) {
        val isActive = SubscriptionHelper.isActiveStatus(status)
        val isPending = SubscriptionHelper.isPendingStatus(this, status)
        val planLabel = SubscriptionHelper.formatPlanLabel(status.plan)
        val paidUntil = formatPaidUntil(status.paidUntilMillis)

        binding.txtSubscriptionStatusLabel.text = when {
            isActive -> getString(R.string.settings_subscription_state_active)
            isPending -> getString(R.string.settings_subscription_state_pending)
            else -> getString(R.string.settings_subscription_state_inactive)
        }
        val colorRes = when {
            isActive -> R.color.status_success
            isPending -> R.color.status_warning
            else -> R.color.status_muted
        }
        binding.txtSubscriptionStatusLabel.setTextColor(ContextCompat.getColor(this, colorRes))

        val metaLines = mutableListOf<String>()
        if (!planLabel.isNullOrBlank()) {
            metaLines += getString(R.string.subscription_plan_format, planLabel)
        }
        if (!paidUntil.isNullOrBlank()) {
            metaLines += getString(R.string.settings_subscription_paid_until, paidUntil)
        }
        status.message?.takeIf { it.isNotBlank() }?.let { metaLines += it }
        status.reason
            ?.takeIf { it.isNotBlank() && it != status.message }
            ?.let { metaLines += it }
        if (status.lastCheckedMillis > 0L) {
            metaLines += getString(
                R.string.settings_subscription_last_checked,
                formatLastChecked(status.lastCheckedMillis)
            )
        }

        binding.txtSubscriptionStatusMeta.visibility =
            if (metaLines.isEmpty()) View.GONE else View.VISIBLE
        binding.txtSubscriptionStatusMeta.text = metaLines.joinToString("\n")
    }

    private fun openOfflineGuide() {
        startActivity(Intent(this, OfflineGuideActivity::class.java))
    }

    private fun formatPaidUntil(paidUntilMillis: Long?): String? {
        if (paidUntilMillis == null || paidUntilMillis <= 0L) return null
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return Instant.ofEpochMilli(paidUntilMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(formatter)
    }

    private fun formatLastChecked(lastCheckedMillis: Long): String {
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.getDefault())
        return Instant.ofEpochMilli(lastCheckedMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(formatter)
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }
}
