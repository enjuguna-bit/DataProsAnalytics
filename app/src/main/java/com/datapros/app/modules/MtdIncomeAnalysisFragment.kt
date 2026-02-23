package com.datapros.app.modules

import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.datapros.app.billing.UsageGate
import com.datapros.app.utils.PythonRunner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MtdIncomeAnalysisFragment : BaseModuleFragment() {

    override val moduleTitle = "MTD Income Analysis"
    override val moduleSubtitle = "Analyze income by selected day and daily target per portfolio"

    private var dayInput: String = "1"
    private var dailyTargetsInput: String = ""

    override fun process() {
        val inputPath = selectedFilePath ?: return
        if (!UsageGate.ensureAllowed(this)) return
        setLoading(true)
        lifecycleScope.launch {
            val portfolios = withContext(Dispatchers.IO) {
                PythonRunner.getMtdIncomePortfolios(inputPath)
            }
            setLoading(false)
            showInputsDialog(portfolios)
        }
    }

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runMtdIncomeAnalysis(
            inputPath = inputPath,
            outputDir = outputDir,
            dayInput = dayInput,
            dailyTargetsInput = dailyTargetsInput
        )

    override fun bindStats(stats: JSONObject) {
        val day = stats.optInt("day_number", 1)
        val actual = stats.optDouble("total_actual_income", 0.0)
        val expected = stats.optDouble("total_expected_income", 0.0)
        val gap = stats.optDouble("total_income_gap", 0.0)
        val onTrack = stats.optInt("on_track_count", 0)
        val totalPortfolios = stats.optInt("total_portfolios", 0)

        setStats(
            v1 = "Day %d".format(day), l1 = "Analysis Day",
            v2 = "KES %,.0f".format(actual), l2 = "Actual Income",
            v3 = "KES %,.0f".format(expected), l3 = "Expected Income",
            v4 = "%d/%d".format(onTrack, totalPortfolios), l4 = "On Track"
        )

        if (gap < 0) {
            showStatus("Gap: KES %,.0f behind target".format(kotlin.math.abs(gap)))
        } else {
            showStatus("Gap: KES %,.0f above target".format(gap))
        }
    }

    private fun showInputsDialog(portfolios: List<String>) {
        if (!isAdded) return

        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val dayLayout = TextInputLayout(context).apply {
            hint = "Day to Analyze (e.g. 23)"
        }
        val dayEdit = TextInputEditText(context).apply {
            setText(dayInput)
        }
        dayLayout.addView(dayEdit)
        container.addView(dayLayout)

        val targetsLayout = TextInputLayout(context).apply {
            hint = "Daily Target per Portfolio (Name=Amount)"
        }

        val prefill = if (dailyTargetsInput.isNotBlank()) {
            dailyTargetsInput
        } else {
            buildString {
                appendLine("DEFAULT=0")
                portfolios.forEach { appendLine("$it=") }
            }.trimEnd()
        }

        val targetsEdit = TextInputEditText(context).apply {
            minLines = 6
            maxLines = 14
            setText(prefill)
        }
        targetsLayout.addView(targetsEdit)
        container.addView(targetsLayout)

        val scroll = ScrollView(context).apply {
            addView(container)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("MTD Income Inputs")
            .setMessage("Set day and daily targets. Example:\nDEFAULT=5000\nJOASH MUTISYA=7000")
            .setView(scroll)
            .setNegativeButton("Cancel") { _, _ ->
                showStatus("Processing cancelled")
            }
            .setPositiveButton("Run", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val dayText = dayEdit.text?.toString()?.trim().orEmpty()
                if (dayText.isBlank()) {
                    dayLayout.error = "Enter analysis day"
                    return@setOnClickListener
                }
                dayLayout.error = null
                dayInput = dayText
                dailyTargetsInput = targetsEdit.text?.toString().orEmpty()
                dialog.dismiss()
                runConfiguredProcess()
            }
        }

        dialog.show()
    }

    private fun runConfiguredProcess() {
        super.process()
    }
}
