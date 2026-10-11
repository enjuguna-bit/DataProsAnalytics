package com.datapros.app.billing

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.datapros.app.R
import com.datapros.app.databinding.ActivitySubscriptionBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class SubscriptionActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySubscriptionBinding
    private lateinit var selectedPlan: PlanOption

    data class PlanOption(
        val displayName: String,
        val planCode: String,
        val amount: Int,          // KES
        val paymentUrl: String
    )

    companion object {
        const val SIX_HOUR_PAYMENT_URL    = "https://lipana.dev/pay/1hrmaster"
        const val TWELVE_HOUR_PAYMENT_URL = "https://lipana.dev/pay/smartuser"
        const val DAILY_PAYMENT_URL       = "https://lipana.dev/pay/daily"
        const val WEEKLY_PAYMENT_URL      = "https://lipana.dev/pay/weekly-subscription"
        const val MONTHLY_PAYMENT_URL     = "https://lipana.dev/pay/cruisemaster"

        private const val POST_PAYMENT_CHECK_WINDOW_MS = 5 * 60 * 1000L  // 5 min
        private const val CLAIM_RETRY_DELAY_MS = 3_000L
    }

    private val plans: Map<Int, PlanOption> by lazy {
        mapOf(
            R.id.chipPlanSixHour    to PlanOption("6-Hour",  "six_hour",    60,   SIX_HOUR_PAYMENT_URL),
            R.id.chipPlanTwelveHour to PlanOption("12-Hour", "twelve_hour", 100,  TWELVE_HOUR_PAYMENT_URL),
            R.id.chipPlanDaily      to PlanOption("24-Hour", "daily",       200,  DAILY_PAYMENT_URL),
            R.id.chipPlanWeekly     to PlanOption("Weekly",  "weekly",      1000, WEEKLY_PAYMENT_URL),
            R.id.chipPlanMonthly    to PlanOption("Monthly", "monthly",     3000, MONTHLY_PAYMENT_URL)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        selectedPlan = plans.getValue(R.id.chipPlanWeekly)
        setupUI()
        renderSelectedPlan()
        restoreLastPhone()
        updateInlineStatus(SubscriptionHelper.getCachedStatus(this))
    }

    override fun onResume() {
        super.onResume()
        if (!checkIfReturningFromPayment()) {
            refreshStatus(forceRefresh = false, allowClaim = false)
        }
    }

    private fun setupUI() {
        binding.chipGroupPlans.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedPlan = plans[id] ?: selectedPlan
            renderSelectedPlan()
        }

        binding.btnStartPayment.setOnClickListener {
            val phone = binding.etPhoneNumber.text?.toString().orEmpty()
            if (validatePhoneNumber(phone)) startSubscriptionFlow(selectedPlan, phone)
        }

        binding.btnCheckStatus.setOnClickListener {
            val phone = binding.etPhoneNumber.text?.toString().orEmpty()
            if (!validatePhoneNumber(phone)) return@setOnClickListener
            saveLastPhoneOnly(formatPhoneNumber(phone))
            refreshStatus(forceRefresh = true, allowClaim = true)
        }
    }

    private fun renderSelectedPlan() {
        val label = "${selectedPlan.displayName}  ·  KES ${selectedPlan.amount}"
        binding.txtSelectedPlanSummary.text = getString(R.string.subscription_plan_format, label)
    }

    private fun restoreLastPhone() {
        val prefs = getSharedPreferences("subscriptions", MODE_PRIVATE)
        val lastPhone = prefs.getString("last_phone", null) ?: return
        if (binding.etPhoneNumber.text.isNullOrBlank()) {
            binding.etPhoneNumber.setText(lastPhone)
        }
    }

    private fun startSubscriptionFlow(plan: PlanOption, phone: String) {
        val formattedPhone = formatPhoneNumber(phone)
        val deviceId = SubscriptionHelper.getDeviceId(this)
        setBusy(true, getString(R.string.subscription_loading_payment))

        lifecycleScope.launch {
            val intentId = SubscriptionHelper.createPaymentIntent(
                formattedPhone, deviceId, plan.planCode, plan.amount
            )
            if (intentId.isNullOrBlank()) {
                setBusy(false, null)
                showSnackbar(getString(R.string.subscription_payment_error))
                return@launch
            }
            SubscriptionHelper.saveLastIntent(this@SubscriptionActivity, intentId)
            val opened = openPaymentPage(plan, formattedPhone, deviceId, intentId)
            if (opened) saveSubscriptionAttempt(formattedPhone, plan.displayName, plan.amount)
            else SubscriptionHelper.clearLastIntent(this@SubscriptionActivity)
            setBusy(false, null)
        }
    }

    private fun openPaymentPage(
        plan: PlanOption,
        formattedPhone: String,
        deviceId: String,
        intentId: String
    ): Boolean {
        return try {
            val reference =
                "bulksms|intent=$intentId|plan=${plan.planCode}|device=$deviceId|phone=$formattedPhone"
            val paymentUrl = Uri.parse(plan.paymentUrl).buildUpon()
                .appendQueryParameter("phone",     formattedPhone)
                .appendQueryParameter("plan",      plan.planCode)
                .appendQueryParameter("amount",    plan.amount.toString())
                .appendQueryParameter("reference", reference)
                .appendQueryParameter("device_id", deviceId)
                .appendQueryParameter("intent_id", intentId)
                .build().toString()

            val colorParams = CustomTabColorSchemeParams.Builder()
                .setToolbarColor(ContextCompat.getColor(this, R.color.color_primary))
                .build()
            val cct = CustomTabsIntent.Builder()
                .setShowTitle(true)
                .setDefaultColorSchemeParams(colorParams)
                .build()
            cct.launchUrl(this, Uri.parse(paymentUrl))
            showSnackbar(getString(R.string.subscription_payment_opened))
            true
        } catch (_: Exception) {
            showSnackbar(getString(R.string.subscription_payment_error))
            false
        }
    }

    private fun refreshStatus(forceRefresh: Boolean, allowClaim: Boolean) {
        setBusy(true, getString(R.string.subscription_loading_status))
        lifecycleScope.launch {
            val status = resolveStatus(forceRefresh, allowClaim)
            setBusy(false, null)
            updateInlineStatus(status)
        }
    }

    private suspend fun resolveStatus(
        forceRefresh: Boolean,
        allowClaim: Boolean
    ): SubscriptionHelper.SubscriptionStatus {
        var status = SubscriptionHelper.refreshSubscriptionStatus(this@SubscriptionActivity, forceRefresh)
        if (!allowClaim || !shouldAttemptClaim(status)) {
            return status
        }

        val intentId = SubscriptionHelper.getLastIntent(this@SubscriptionActivity)
        if (intentId.isNullOrBlank()) {
            return status
        }

        val claim = SubscriptionHelper.claimSubscriptionWithIntent(this@SubscriptionActivity, intentId)
        if (claim.success || claim.processing) {
            status = SubscriptionHelper.refreshSubscriptionStatus(this@SubscriptionActivity, true)
        } else if (!claim.message.isNullOrBlank()) {
            showSnackbar(claim.message)
        }
        return status
    }

    private fun shouldAttemptClaim(status: SubscriptionHelper.SubscriptionStatus): Boolean {
        if (SubscriptionHelper.isActiveStatus(status)) return false
        if (status.claimRequired) return true
        return SubscriptionHelper.isPendingStatus(this, status)
    }

    private fun checkIfReturningFromPayment(): Boolean {
        val prefs = getSharedPreferences("subscriptions", MODE_PRIVATE)
        val lastAttempt = prefs.getLong("last_attempt", 0)
        if (System.currentTimeMillis() - lastAttempt >= POST_PAYMENT_CHECK_WINDOW_MS) return false

        lifecycleScope.launch {
            var retries = 0
            var activated = false
            val maxRetries = 6 

            while (retries < maxRetries && !activated) {
                if (retries > 0) {
                    setStatusUi(getString(R.string.subscription_loading_status), R.color.status_info)
                    kotlinx.coroutines.delay(CLAIM_RETRY_DELAY_MS)
                }

                val status = resolveStatus(forceRefresh = true, allowClaim = true)
                activated = SubscriptionHelper.isActiveStatus(status)
                updateInlineStatus(status)

                if (!activated && !SubscriptionHelper.isPendingStatus(this@SubscriptionActivity, status)) {
                    break
                }
                retries++
            }

            if (activated) {
                showSnackbar(getString(R.string.subscription_active)) { finish() }
                prefs.edit().remove("last_attempt").apply()
                SubscriptionHelper.clearLastIntent(this@SubscriptionActivity)
            } else {
                updateInlineStatus(SubscriptionHelper.getCachedStatus(this@SubscriptionActivity))
            }
        }
        return true
    }

    private fun updateInlineStatus(status: SubscriptionHelper.SubscriptionStatus) {
        val isActive = SubscriptionHelper.isActiveStatus(status)
        val isPending = SubscriptionHelper.isPendingStatus(this, status)
        val planLabel = SubscriptionHelper.formatPlanLabel(status.plan)
        val paidUntil = formatPaidUntil(status.paidUntilMillis)
        val serverMessage = status.message?.takeIf { it.isNotBlank() }
            ?: status.reason?.takeIf { it.isNotBlank() }

        val message = when {
            isActive && !planLabel.isNullOrBlank() && !paidUntil.isNullOrBlank() ->
                getString(R.string.subscription_active_plan_and_time, planLabel, paidUntil)
            isActive && !paidUntil.isNullOrBlank() ->
                getString(R.string.subscription_active_time_only, paidUntil)
            isActive && !planLabel.isNullOrBlank() ->
                getString(R.string.subscription_active_plan_only, planLabel)
            isActive ->
                getString(R.string.subscription_active)
            isPending && !serverMessage.isNullOrBlank() ->
                serverMessage
            isPending ->
                getString(R.string.subscription_payment_pending)
            !planLabel.isNullOrBlank() && !paidUntil.isNullOrBlank() ->
                getString(R.string.subscription_inactive_plan_and_time, planLabel, paidUntil)
            !paidUntil.isNullOrBlank() ->
                getString(R.string.subscription_inactive_time_only, paidUntil)
            !planLabel.isNullOrBlank() ->
                getString(R.string.subscription_inactive_plan_only, planLabel)
            !serverMessage.isNullOrBlank() ->
                serverMessage
            else ->
                getString(R.string.subscription_inactive)
        }
        val color = when {
            isActive                                   -> R.color.status_success
            isPending                                  -> R.color.status_warning
            else                                       -> R.color.status_muted
        }
        setStatusUi(message, color)
    }

    private fun setStatusUi(message: String, colorRes: Int) {
        binding.statusCard.visibility = View.VISIBLE
        binding.txtInlineStatus.text  = message
        binding.txtInlineStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.statusCard.strokeColor = ContextCompat.getColor(this, colorRes)
    }

    private fun setBusy(busy: Boolean, statusMessage: String?) {
        binding.paymentProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnStartPayment.isEnabled  = !busy
        binding.btnCheckStatus.isEnabled   = !busy
        if (busy && !statusMessage.isNullOrBlank()) setStatusUi(statusMessage, R.color.status_info)
    }

    private fun validatePhoneNumber(phone: String): Boolean {
        if (phone.isBlank()) {
            showPhoneError(getString(R.string.subscription_phone_required))
            return false
        }
        val digits = phone.replace(Regex("[^\\d]"), "")
        val valid = when {
            digits.startsWith("0")   && digits.length == 10
                && (digits[1] == '7' || digits[1] == '1') -> true
            digits.startsWith("254") && digits.length == 12
                && (digits[3] == '7' || digits[3] == '1') -> true
            (digits.startsWith("7") || digits.startsWith("1")) && digits.length == 9 -> true
            else -> false
        }
        if (!valid) showPhoneError(getString(R.string.subscription_phone_invalid))
        else binding.phoneInputLayout.error = null
        return valid
    }

    private fun showPhoneError(msg: String) {
        binding.phoneInputLayout.error = msg
        binding.subscriptionScroll.smoothScrollTo(0, 0)
        binding.etPhoneNumber.requestFocus()
        showSnackbar(msg)
    }

    private fun formatPhoneNumber(phone: String): String {
        var d = phone.replace(Regex("[^\\d]"), "")
        return when {
            d.startsWith("0")   && d.length == 10 -> "254${d.substring(1)}"
            d.startsWith("254") && d.length == 12 -> d
            (d.startsWith("7") || d.startsWith("1")) && d.length == 9 -> "254$d"
            else -> d
        }
    }

    private fun saveSubscriptionAttempt(phone: String, plan: String, amount: Int) {
        getSharedPreferences("subscriptions", MODE_PRIVATE).edit().apply {
            putString("last_phone",   phone)
            putString("last_plan",    plan)
            putInt("last_amount",     amount)
            putLong("last_attempt",   System.currentTimeMillis())
            apply()
        }
    }

    private fun saveLastPhoneOnly(phone: String) {
        getSharedPreferences("subscriptions", MODE_PRIVATE).edit()
            .putString("last_phone", phone).apply()
    }

    private fun formatPaidUntil(paidUntilMillis: Long?): String? {
        if (paidUntilMillis == null || paidUntilMillis <= 0L) return null
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return Instant.ofEpochMilli(paidUntilMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
            .format(formatter)
    }

    private fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        val sb = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (action != null) sb.setAction(R.string.subscription_close) { action() }
        sb.show()
    }
}
