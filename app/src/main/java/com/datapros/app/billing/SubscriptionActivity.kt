package com.datapros.app.billing

import android.net.Uri
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.datapros.app.R
import com.datapros.app.databinding.ActivitySubscriptionBinding
import com.datapros.app.utils.UiMotion
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
        val amount: Int,
        val paymentUrl: String
    )

    companion object {
        const val DAILY_PAYMENT_URL = "https://lipana.dev/pay/daily"
        const val WEEKLY_PAYMENT_URL = "https://lipana.dev/pay/weekly-subscription"
        const val SIX_HOUR_PAYMENT_URL = "https://lipana.dev/pay/smartuser"
        const val ONE_HOUR_PAYMENT_URL = "https://lipana.dev/pay/1hrmaster"
    }

    private val plans by lazy {
        mapOf(
            R.id.chipPlanOneHour to PlanOption("1-Hour", "one_hour", 60, ONE_HOUR_PAYMENT_URL),
            R.id.chipPlanSixHour to PlanOption("6-Hour", "six_hour", 100, SIX_HOUR_PAYMENT_URL),
            R.id.chipPlanDaily to PlanOption("24-Hour", "daily", 200, DAILY_PAYMENT_URL),
            R.id.chipPlanWeekly to PlanOption("Weekly", "weekly", 1000, WEEKLY_PAYMENT_URL)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySubscriptionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        selectedPlan = plans.getValue(R.id.chipPlanWeekly)
        setupUI()
        renderSelectedPlan()
        UiMotion.bindPress(binding.btnStartPayment, binding.btnCheckStatus)
        UiMotion.runEntryStagger(binding.root)
    }

    private fun setupUI() {
        binding.chipGroupPlans.setOnCheckedStateChangeListener { _, checkedIds ->
            val selectedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            selectedPlan = plans[selectedId] ?: selectedPlan
            renderSelectedPlan()
        }

        binding.btnStartPayment.setOnClickListener {
            val phone = binding.etPhoneNumber.text?.toString().orEmpty()
            if (validatePhoneNumber(phone)) {
                startSubscriptionFlow(selectedPlan, phone)
            }
        }

        binding.btnCheckStatus.setOnClickListener {
            val phone = binding.etPhoneNumber.text?.toString().orEmpty()
            if (!validatePhoneNumber(phone)) {
                return@setOnClickListener
            }
            val formattedPhone = formatPhoneNumber(phone)
            saveLastPhoneOnly(formattedPhone)
            refreshStatus(forceRefresh = true)
        }
    }

    private fun renderSelectedPlan() {
        binding.txtSelectedPlanSummary.text = getString(
            R.string.subscription_plan_format,
            selectedPlan.displayName
        )
    }

    private fun refreshStatus(forceRefresh: Boolean) {
        setBusy(true, getString(R.string.subscription_loading_status))
        lifecycleScope.launch {
            var status = SubscriptionHelper.refreshSubscriptionStatus(this@SubscriptionActivity, forceRefresh)
            if (!status.premium && status.claimRequired) {
                val intentId = SubscriptionHelper.getLastIntent(this@SubscriptionActivity)
                if (!intentId.isNullOrBlank()) {
                    val claim = SubscriptionHelper.claimSubscriptionWithIntent(this@SubscriptionActivity, intentId)
                    if (claim.success) {
                        status = SubscriptionHelper.refreshSubscriptionStatus(this@SubscriptionActivity, true)
                    } else if (!claim.message.isNullOrBlank()) {
                        setStatusUi(claim.message.orEmpty(), R.color.status_warning)
                    }
                }
            }
            setBusy(false, null)
            updateInlineStatus(status)
        }
    }

    private fun startSubscriptionFlow(plan: PlanOption, phone: String) {
        val formattedPhone = formatPhoneNumber(phone)
        val deviceId = SubscriptionHelper.getDeviceId(this)
        setBusy(true, getString(R.string.subscription_loading_payment))

        lifecycleScope.launch {
            val intentId = SubscriptionHelper.createPaymentIntent(
                formattedPhone,
                deviceId,
                plan.planCode,
                plan.amount
            )

            if (intentId.isNullOrBlank()) {
                setBusy(false, null)
                showSnackbar(getString(R.string.subscription_payment_error))
                return@launch
            }

            SubscriptionHelper.saveLastIntent(this@SubscriptionActivity, intentId)
            val opened = openPaymentPage(plan, formattedPhone, deviceId, intentId)
            if (opened) {
                saveSubscriptionAttempt(formattedPhone, plan.displayName, plan.amount)
            } else {
                SubscriptionHelper.clearLastIntent(this@SubscriptionActivity)
            }
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
            val reference = "bulksms|intent=$intentId|plan=${plan.planCode}|device=$deviceId|phone=$formattedPhone"
            val paymentUrl = Uri.parse(plan.paymentUrl).buildUpon()
                .appendQueryParameter("phone", formattedPhone)
                .appendQueryParameter("plan", plan.planCode)
                .appendQueryParameter("reference", reference)
                .appendQueryParameter("device_id", deviceId)
                .appendQueryParameter("intent_id", intentId)
                .build()
                .toString()

            val builder = CustomTabsIntent.Builder()
            builder.setShowTitle(true)
            builder.setToolbarColor(ContextCompat.getColor(this, R.color.color_primary))
            val customTabsIntent = builder.build()
            customTabsIntent.launchUrl(this, Uri.parse(paymentUrl))
            showSnackbar(getString(R.string.subscription_payment_opened))
            true
        } catch (_: Exception) {
            showSnackbar(getString(R.string.subscription_payment_error))
            false
        }
    }

    private fun setBusy(busy: Boolean, statusMessage: String?) {
        binding.paymentProgress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnStartPayment.isEnabled = !busy
        binding.btnCheckStatus.isEnabled = !busy
        if (busy && !statusMessage.isNullOrBlank()) {
            setStatusUi(statusMessage, R.color.status_info)
        }
    }

    private fun validatePhoneNumber(phone: String): Boolean {
        if (phone.isBlank()) {
            binding.phoneInputLayout.error = getString(R.string.subscription_phone_required)
            binding.subscriptionScroll.smoothScrollTo(0, 0)
            binding.etPhoneNumber.requestFocus()
            showSnackbar(getString(R.string.subscription_phone_required))
            return false
        }

        val cleaned = phone.replace(Regex("[^0-9+]"), "")
        val digits = cleaned.replace("+", "")

        val valid = when {
            digits.startsWith("0") -> digits.length == 10 && (digits[1] == '7' || digits[1] == '1')
            digits.startsWith("254") -> digits.length == 12 && (digits[3] == '7' || digits[3] == '1')
            digits.startsWith("7") || digits.startsWith("1") -> digits.length == 9
            else -> false
        }

        if (!valid) {
            binding.phoneInputLayout.error = getString(R.string.subscription_phone_invalid)
            binding.subscriptionScroll.smoothScrollTo(0, 0)
            binding.etPhoneNumber.requestFocus()
            showSnackbar(getString(R.string.subscription_phone_invalid))
        } else {
            binding.phoneInputLayout.error = null
        }

        return valid
    }

    private fun formatPhoneNumber(phone: String): String {
        var formatted = phone.replace(Regex("[^0-9+]"), "").replace("+", "")
        when {
            formatted.startsWith("0") && formatted.length == 10 -> formatted = "254${formatted.substring(1)}"
            formatted.startsWith("254") && formatted.length == 12 -> Unit
            (formatted.startsWith("7") || formatted.startsWith("1")) && formatted.length == 9 -> {
                formatted = "254$formatted"
            }
        }
        return formatted
    }

    private fun saveSubscriptionAttempt(phone: String, plan: String, amount: Int) {
        val prefs = getSharedPreferences("subscriptions", MODE_PRIVATE)
        prefs.edit().apply {
            putString("last_phone", phone)
            putString("last_plan", plan)
            putInt("last_amount", amount)
            putLong("last_attempt", System.currentTimeMillis())
            apply()
        }
    }

    private fun saveLastPhoneOnly(phone: String) {
        val prefs = getSharedPreferences("subscriptions", MODE_PRIVATE)
        prefs.edit().putString("last_phone", phone).apply()
    }

    override fun onResume() {
        super.onResume()
        checkIfReturningFromPayment()
    }

    private fun checkIfReturningFromPayment() {
        val prefs = getSharedPreferences("subscriptions", MODE_PRIVATE)
        val lastAttempt = prefs.getLong("last_attempt", 0)
        if (System.currentTimeMillis() - lastAttempt >= 5 * 60 * 1000) {
            return
        }

        lifecycleScope.launch {
            val status = SubscriptionHelper.refreshSubscriptionStatus(this@SubscriptionActivity, true)
            val now = System.currentTimeMillis()
            val active = status.premium && (status.paidUntilMillis == null || status.paidUntilMillis > now)

            if (active) {
                updateInlineStatus(status)
                showSnackbar(getString(R.string.subscription_active)) {
                    finish()
                }
                prefs.edit().remove("last_attempt").apply()
                SubscriptionHelper.clearLastIntent(this@SubscriptionActivity)
                return@launch
            }

            val intentId = SubscriptionHelper.getLastIntent(this@SubscriptionActivity)
            if (!intentId.isNullOrBlank()) {
                val claim = SubscriptionHelper.claimSubscriptionWithIntent(this@SubscriptionActivity, intentId)
                if (claim.success) {
                    val refreshed = SubscriptionHelper.refreshSubscriptionStatus(this@SubscriptionActivity, true)
                    updateInlineStatus(refreshed)
                    showSnackbar(getString(R.string.subscription_active)) {
                        finish()
                    }
                    prefs.edit().remove("last_attempt").apply()
                    SubscriptionHelper.clearLastIntent(this@SubscriptionActivity)
                    return@launch
                }
                if (!claim.processing && !claim.message.isNullOrBlank()) {
                    setStatusUi(claim.message.orEmpty(), R.color.status_warning)
                }
            }

            setStatusUi(
                status.message ?: getString(R.string.subscription_payment_pending),
                R.color.status_warning
            )
        }
    }

    private fun updateInlineStatus(status: SubscriptionHelper.SubscriptionStatus) {
        val now = System.currentTimeMillis()
        val isActive = status.premium && (status.paidUntilMillis == null || status.paidUntilMillis > now)
        val planLabel = status.plan?.trim()?.replace("_", " ")?.replaceFirstChar { it.uppercaseChar() }
        val paidUntil = formatPaidUntil(status.paidUntilMillis)

        val message = when {
            isActive && !planLabel.isNullOrBlank() && !paidUntil.isNullOrBlank() ->
                getString(R.string.subscription_active_plan_and_time, planLabel, paidUntil)
            isActive && !paidUntil.isNullOrBlank() ->
                getString(R.string.subscription_active_time_only, paidUntil)
            isActive && !planLabel.isNullOrBlank() ->
                getString(R.string.subscription_active_plan_only, planLabel)
            isActive ->
                getString(R.string.subscription_active)
            !status.message.isNullOrBlank() ->
                status.message.orEmpty()
            SubscriptionHelper.isPaymentPending(this) ->
                getString(R.string.subscription_payment_pending)
            else ->
                getString(R.string.subscription_status_default)
        }

        val color = when {
            isActive -> R.color.status_success
            status.claimRequired || status.reason == "device_unbound" || status.reason == "device_mismatch" ->
                R.color.status_warning
            SubscriptionHelper.isPaymentPending(this) -> R.color.status_warning
            else -> R.color.status_muted
        }
        setStatusUi(message, color)
    }

    private fun setStatusUi(message: String, colorRes: Int) {
        binding.statusCard.visibility = View.VISIBLE
        binding.txtInlineStatus.text = message
        binding.txtInlineStatus.setTextColor(ContextCompat.getColor(this, colorRes))
        binding.statusCard.strokeColor = ContextCompat.getColor(this, colorRes)
        UiMotion.reveal(binding.statusCard)
    }

    private fun formatPaidUntil(paidUntilMillis: Long?): String? {
        if (paidUntilMillis == null || paidUntilMillis <= 0L) return null
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        val dateTime = Instant.ofEpochMilli(paidUntilMillis).atZone(zone).toLocalDateTime()
        return formatter.format(dateTime)
    }

    private fun showSnackbar(message: String, action: (() -> Unit)? = null) {
        val snackbar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
        if (action != null) {
            snackbar.setAction(R.string.subscription_close) { action.invoke() }
        }
        snackbar.show()
    }
}
