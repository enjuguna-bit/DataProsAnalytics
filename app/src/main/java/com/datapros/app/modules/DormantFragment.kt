package com.datapros.app.modules

import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.datapros.app.billing.UsageGate
import com.datapros.app.utils.PythonRunner
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class DormantFragment : BaseModuleFragment() {

    override val moduleTitle = "Dormant Arrangements"
    override val moduleSubtitle = "Branch data cleanup - Phone normalization and dedup"

    private var processAllBranches = true
    private var selectedBranch = ""
    private var removeDuplicates = true
    private var fillNa = true
    private var addFormatting = true

    override fun process() {
        val inputPath = selectedFilePath ?: return
        if (!UsageGate.ensureAllowed(this)) return
        setLoading(true)
        lifecycleScope.launch {
            val branches = withContext(Dispatchers.IO) {
                PythonRunner.getDormantBranches(inputPath)
            }
            setLoading(false)
            showOptionsDialog(branches)
        }
    }

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject =
        PythonRunner.runDormant(
            inputPath = inputPath,
            outputDir = outputDir,
            processAllBranches = processAllBranches,
            selectedBranch = selectedBranch,
            removeDuplicates = removeDuplicates,
            fillNa = fillNa,
            addFormatting = addFormatting
        )

    override fun bindStats(stats: JSONObject) {
        val records = stats.optInt("total_records", stats.optInt("total_dormant", 0))
        val balance = stats.optDouble("total_loan_balance", stats.optDouble("total_balance", 0.0))
        val branches = stats.optInt("num_branches", 0)
        val duplicates = stats.optInt("duplicates_removed", stats.optInt("critical_count", 0))

        setStats(
            v1 = "%,d".format(records), l1 = "Processed Records",
            v2 = "KES %,.0f".format(balance), l2 = "Loan Balance",
            v3 = branches.toString(), l3 = "Branches",
            v4 = duplicates.toString(), l4 = "Duplicates Removed"
        )
    }

    private fun showOptionsDialog(branches: List<String>) {
        if (!isAdded) return

        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }

        val allBranchesSwitch = SwitchMaterial(context).apply {
            text = "Process all branches"
            isChecked = processAllBranches
        }
        container.addView(allBranchesSwitch)

        val branchInputLayout = TextInputLayout(context).apply {
            hint = "Branch (single-branch mode)"
            isEnabled = !allBranchesSwitch.isChecked
        }
        val branchInput = MaterialAutoCompleteTextView(context).apply {
            if (branches.isNotEmpty()) {
                setSimpleItems(branches.toTypedArray())
            }
            if (selectedBranch.isNotBlank()) {
                setText(selectedBranch, false)
            }
            isEnabled = !allBranchesSwitch.isChecked
        }
        branchInputLayout.addView(branchInput)
        container.addView(branchInputLayout)

        val removeDuplicatesCheck = MaterialCheckBox(context).apply {
            text = "Remove duplicate phone numbers"
            isChecked = removeDuplicates
        }
        container.addView(removeDuplicatesCheck)

        val fillNaCheck = MaterialCheckBox(context).apply {
            text = "Fill empty phone numbers with N/A"
            isChecked = fillNa
        }
        container.addView(fillNaCheck)

        val formattingCheck = MaterialCheckBox(context).apply {
            text = "Apply Excel formatting"
            isChecked = addFormatting
        }
        container.addView(formattingCheck)

        allBranchesSwitch.setOnCheckedChangeListener { _, checked ->
            branchInputLayout.isEnabled = !checked
            branchInput.isEnabled = !checked
            if (checked) {
                branchInputLayout.error = null
            }
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("Dormant Processing Options")
            .setView(container)
            .setNegativeButton("Cancel") { _, _ -> showStatus("Processing cancelled") }
            .setPositiveButton("Run", null)
            .create()

        dialog.setOnShowListener {
            val runButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            runButton.setOnClickListener {
                val processAll = allBranchesSwitch.isChecked
                val requestedBranch = branchInput.text?.toString()?.trim().orEmpty()

                if (!processAll) {
                    if (requestedBranch.isBlank()) {
                        branchInputLayout.error = "Please select a branch"
                        return@setOnClickListener
                    }
                    val valid = branches.any { it.equals(requestedBranch, ignoreCase = true) }
                    if (branches.isNotEmpty() && !valid) {
                        branchInputLayout.error = "Please select a valid branch from the list"
                        return@setOnClickListener
                    }
                }

                branchInputLayout.error = null
                processAllBranches = processAll
                selectedBranch = if (processAll) {
                    ""
                } else {
                    branches.firstOrNull { it.equals(requestedBranch, ignoreCase = true) } ?: requestedBranch
                }
                removeDuplicates = removeDuplicatesCheck.isChecked
                fillNa = fillNaCheck.isChecked
                addFormatting = formattingCheck.isChecked

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
