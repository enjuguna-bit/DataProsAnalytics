package com.datapros.app.modules

import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.datapros.app.billing.UsageGate
import com.datapros.app.utils.FileUtils
import com.datapros.app.utils.OcrImageHelper
import com.datapros.app.utils.PythonRunner
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

class MtdCrPortfolioFragment : BaseModuleFragment() {

    override val moduleTitle    = "MTD CR Per Portfolio"
    override val moduleSubtitle = "Upload raw CSV/Excel or image, enter target CR, then export report"

    private data class ParsedRow(
        val name: String,
        val total: Int,
        val paid: Int,
        val outstanding: Int,
        val currentCr: Double,
        val targetCr: Int,
        val targetAmount: Int
    )

    private var targetsInput: String = ""

    /**
     * Stores the prepared (possibly OCR-converted) input path across the two
     * async phases of processing (file prep → targets dialog → run).
     * Written and cleared on the main thread so no synchronisation is needed.
     */
    private var preparedInputPath: String? = null

    override fun filePickerMimeTypes(): Array<String> =
        FileUtils.supportedImportMimeTypes(includeImages = true)

    override fun process() {
        val path = selectedFilePath ?: return
        if (!UsageGate.ensureAllowed(this)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                // Phase 1: convert image → CSV (noop for structured files)
                val sourcePath = if (OcrImageHelper.isImagePath(path)) {
                    withContext(Dispatchers.IO) {
                        OcrImageHelper.buildCsvFromImage(
                            context       = requireContext(),
                            imagePath     = path,
                            outputDir     = File(requireContext().getExternalFilesDir(null), "input"),
                            csvPrefix     = "mtd_cr_ocr",
                            csvHeader     = """"Full Name","Total Amount","Paid Amount","Outstanding Amount","Collection Rate (%)","Target CR","TargetAmount","Branch Name"""",
                            rawTextToRows = ::parseRawText
                        )
                    }
                } else {
                    path
                }
                preparedInputPath = sourcePath

                // Phase 2: load officer list for targets dialog
                val officers = withContext(Dispatchers.IO) {
                    PythonRunner.getMtdCrOfficers(sourcePath)
                }

                if (!isAdded || view == null) return@launch
                setLoading(false)
                showTargetsDialog(officers)

            } catch (e: Exception) {
                preparedInputPath = null
                if (isAdded && view != null) {
                    setLoading(false)
                    showError(e.message ?: "Failed to prepare input")
                }
            }
        }
    }

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject {
        // Use the pre-converted path when available; fall back to converting inline.
        val sourcePath = preparedInputPath
            ?: if (OcrImageHelper.isImagePath(inputPath)) {
                OcrImageHelper.buildCsvFromImage(
                    context       = requireContext(),
                    imagePath     = inputPath,
                    outputDir     = File(requireContext().getExternalFilesDir(null), "input"),
                    csvPrefix     = "mtd_cr_ocr",
                    csvHeader     = """"Full Name","Total Amount","Paid Amount","Outstanding Amount","Collection Rate (%)","Target CR","TargetAmount","Branch Name"""",
                    rawTextToRows = ::parseRawText
                )
            } else {
                inputPath
            }
        preparedInputPath = null   // consume it
        return PythonRunner.runMtdCrPortfolio(
            inputPath    = sourcePath,
            outputDir    = outputDir,
            targetsInput = targetsInput
        )
    }

    override fun bindStats(stats: JSONObject) {
        val portfolios    = stats.optInt("total_portfolios", 0)
        val outstanding   = stats.optDouble("total_outstanding", 0.0)
        val avgRate       = stats.optDouble("avg_collection_rate", 0.0)
        val avgTarget     = stats.optDouble("avg_target_cr", 93.0)
        val targetAmount  = stats.optDouble("total_target_amount", 0.0)

        setStats(
            StatItem("%,d".format(portfolios),                 "Portfolios"),
            StatItem("KES %,.0f".format(outstanding),          "Outstanding"),
            StatItem("%.2f / %.0f".format(avgRate, avgTarget), "CR / Target"),
            StatItem("KES %,.0f".format(targetAmount),         "Target Amount")
        )
    }

    // ── Targets dialog ───────────────────────────────────────────────────────

    private fun showTargetsDialog(officers: List<String>) {
        if (!isAdded) return

        val context = requireContext()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val inputLayout = TextInputLayout(context).apply {
            hint = "DEFAULT=93  or  Officer Name=95"
        }
        val prefill = if (targetsInput.isNotBlank()) targetsInput else buildString {
            appendLine("DEFAULT=93")
            officers.forEach { appendLine("$it=") }
        }.trimEnd()

        val input = TextInputEditText(context).apply {
            minLines = 6; maxLines = 14
            setText(prefill)
        }
        inputLayout.addView(input)

        val scroll = ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(inputLayout)
            })
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("MTD CR Targets")
            .setMessage("Set target CR for this run.\nExample:\nDEFAULT=93\nJOASH MUTISYA=95")
            .setView(scroll)
            .setNeutralButton("Cancel")        { _, _ -> showStatus("Processing cancelled") }
            .setNegativeButton("Use Defaults") { _, _ ->
                targetsInput = "DEFAULT=93"
                runConfiguredProcess()
            }
            .setPositiveButton("Run", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val text = input.text?.toString()?.trim().orEmpty()
                if (text.isBlank()) {
                    inputLayout.error = "Enter at least DEFAULT=xx"
                    return@setOnClickListener
                }
                inputLayout.error = null
                targetsInput = text
                dialog.dismiss()
                runConfiguredProcess()
            }
        }
        dialog.show()
    }

    private fun runConfiguredProcess() {
        val path = selectedFilePath ?: return
        setLoading(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runProcessor(path, FileUtils.outputDir(requireContext()))
                }
                if (isAdded && view != null) handleResult(result)
            } catch (e: Exception) {
                if (isAdded && view != null) {
                    showError(e.message ?: "Processing failed")
                }
            } finally {
                if (isAdded && view != null) setLoading(false)
            }
        }
    }

    // ── OCR parsing ──────────────────────────────────────────────────────────

    private val rowPattern = Regex(
        """^([A-Z][A-Z\s.'\\-]{2,}?)\s+([0-9,]+)\s+([0-9,]+)\s+([0-9,]+)\s+([0-9]+(?:\.[0-9]+)?(?:/[0-9]{1,3})?)(?:\s+([0-9,]+))?$"""
    )

    private fun parseRawText(rawText: String): List<String> {
        return rawText
            .split("\n")
            .map { OcrImageHelper.normaliseLine(it) }
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                if (line.contains("FULL NAME") && line.contains("TOTAL")) return@mapNotNull null
                if (line.contains("TARGETAMOUNT")) return@mapNotNull null

                val m = rowPattern.find(line) ?: return@mapNotNull null
                val name        = m.groupValues[1].trim().replace(Regex("\\s+"), " ")
                val total       = OcrImageHelper.parseInt(m.groupValues[2])
                val paid        = OcrImageHelper.parseInt(m.groupValues[3])
                val outstanding = OcrImageHelper.parseInt(m.groupValues[4])
                val crToken     = m.groupValues[5].trim()
                val taToken     = m.groupValues.getOrNull(6).orEmpty().trim()

                if (total <= 0) return@mapNotNull null

                val currentCr: Double
                val targetCr: Int
                if (crToken.contains("/")) {
                    val parts = crToken.split("/", limit = 2)
                    currentCr = OcrImageHelper.parseDouble(parts[0]).coerceIn(0.0, 100.0)
                    targetCr  = OcrImageHelper.parseInt(parts.getOrElse(1) { "93" }).coerceIn(1, 100).let { if (it == 0) 93 else it }
                } else {
                    currentCr = OcrImageHelper.parseDouble(crToken).coerceIn(0.0, 100.0)
                    targetCr  = 93
                }

                val targetAmount = if (taToken.isNotBlank()) OcrImageHelper.parseInt(taToken)
                                   else max((total * (targetCr / 100.0) - paid).roundToInt(), 0)

                """"${OcrImageHelper.escapeCsv(name)}","$total","$paid","$outstanding","${"%.2f".format(currentCr)}","$targetCr","$targetAmount","MOLO""""
            }
    }
}
