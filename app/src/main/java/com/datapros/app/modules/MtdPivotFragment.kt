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

class MtdPivotFragment : BaseModuleFragment() {

    override val moduleTitle = "MTD Pivot Analyzer"
    override val moduleSubtitle = "New/Repeat disbursement targets with D/S and today's target"

    private var dayHeader = "D19"
    private var newTargetsInput = ""
    private var mtdTargetsInput = ""
    private var dailyTargetsInput = ""

    override fun process() {
        val inputPath = selectedFilePath ?: return
        if (!UsageGate.ensureAllowed(this)) return
        setLoading(true)
        lifecycleScope.launch {
            val officers = withContext(Dispatchers.IO) {
                PythonRunner.getMtdPivotOfficers(inputPath)
            }
            setLoading(false)
            showTargetsDialog(officers)
        }
    }

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runMtdPivot(
            inputPath = inputPath,
            outputDir = outputDir,
            dayHeader = dayHeader,
            newTargetsInput = newTargetsInput,
            mtdTargetsInput = mtdTargetsInput,
            dailyTargetsInput = dailyTargetsInput
        )

    override fun bindStats(stats: JSONObject) {
        val disbursed = stats.optInt("total_disbursed", 0)
        val totalNew = stats.optInt("total_new", 0)
        val mtdTarget = stats.optInt("total_mtd_target", 0)
        val todayTarget = stats.optInt("total_today_target", 0)

        setStats(
            v1 = "%,d".format(disbursed), l1 = "Grand Total",
            v2 = "%,d".format(totalNew), l2 = "New Loans",
            v3 = "%,d".format(mtdTarget), l3 = "MTD Target",
            v4 = "%,d".format(todayTarget), l4 = "Today's Target"
        )
    }

    private fun showTargetsDialog(officers: List<String>) {
        if (!isAdded) return

        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val dayLayout = TextInputLayout(context).apply { hint = "Day Header (e.g. D19)" }
        val dayInput = TextInputEditText(context).apply { setText(dayHeader) }
        dayLayout.addView(dayInput)
        container.addView(dayLayout)

        val prefill = officers.joinToString("\n") { "$it=" }

        val newLayout = TextInputLayout(context).apply { hint = "New Loan Targets (Officer=Target)" }
        val newInput = TextInputEditText(context).apply {
            minLines = 4
            maxLines = 8
            setText(if (newTargetsInput.isNotBlank()) newTargetsInput else prefill)
        }
        newLayout.addView(newInput)
        container.addView(newLayout)

        val mtdLayout = TextInputLayout(context).apply { hint = "MTD Targets (Officer=Target)" }
        val mtdInput = TextInputEditText(context).apply {
            minLines = 4
            maxLines = 8
            setText(if (mtdTargetsInput.isNotBlank()) mtdTargetsInput else prefill)
        }
        mtdLayout.addView(mtdInput)
        container.addView(mtdLayout)

        val dailyLayout = TextInputLayout(context).apply { hint = "Daily Targets (Officer=Target)" }
        val dailyInput = TextInputEditText(context).apply {
            minLines = 4
            maxLines = 8
            setText(if (dailyTargetsInput.isNotBlank()) dailyTargetsInput else prefill)
        }
        dailyLayout.addView(dailyInput)
        container.addView(dailyLayout)

        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("MTD Pivot Inputs")
            .setMessage("Use one officer per line in the format Officer=Target. Leave blank for zero targets.")
            .setView(scroll)
            .setNegativeButton("Cancel") { _, _ -> showStatus("Processing cancelled") }
            .setPositiveButton("Run", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dayHeader = dayInput.text?.toString()?.trim().orEmpty().ifBlank { "D19" }
                newTargetsInput = newInput.text?.toString().orEmpty()
                mtdTargetsInput = mtdInput.text?.toString().orEmpty()
                dailyTargetsInput = dailyInput.text?.toString().orEmpty()
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
