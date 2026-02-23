package com.datapros.app.modules

import android.net.Uri
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.datapros.app.billing.UsageGate
import com.datapros.app.utils.FileUtils
import com.datapros.app.utils.PythonRunner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

class MtdCrPortfolioFragment : BaseModuleFragment() {

    override val moduleTitle = "MTD CR Per Portfolio"
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

    private val targetByName = mapOf(
        "JOASH MUTISYA" to 95,
        "BRIAN WANJALA" to 92,
        "ESTHER MUMBI" to 91,
        "ALEXANDER MWANGI" to 93,
        "BENSON KIRUI" to 94
    )

    private var targetsInput: String = ""
    private var preparedInputPath: String? = null

    override fun filePickerMimeTypes(): Array<String> = arrayOf(
        "text/csv",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "image/jpeg",
        "image/png",
        "image/jpg"
    )

    override fun process() {
        val path = selectedFilePath ?: return
        if (!UsageGate.ensureAllowed(this)) return

        setLoading(true)
        lifecycleScope.launch {
            try {
                val sourcePath = withContext(Dispatchers.IO) {
                    if (isImagePath(path)) buildStructuredCsvFromImage(path) else path
                }
                preparedInputPath = sourcePath
                val officers = withContext(Dispatchers.IO) {
                    PythonRunner.getMtdCrOfficers(sourcePath)
                }
                setLoading(false)
                showTargetsDialog(officers)
            } catch (exc: Exception) {
                preparedInputPath = null
                setLoading(false)
                showStatus("Error: ${exc.message ?: "Failed to prepare input"}")
            }
        }
    }

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject {
        val sourcePath = preparedInputPath ?: if (isImagePath(inputPath)) {
            buildStructuredCsvFromImage(inputPath)
        } else {
            inputPath
        }
        return try {
            PythonRunner.runMtdCrPortfolio(
                inputPath = sourcePath,
                outputDir = outputDir,
                targetsInput = targetsInput
            )
        } finally {
            preparedInputPath = null
        }
    }

    override fun bindStats(stats: JSONObject) {
        val portfolios = stats.optInt("total_portfolios", 0)
        val outstanding = stats.optDouble("total_outstanding", 0.0)
        val avgRate = stats.optDouble("avg_collection_rate", 0.0)
        val avgTarget = stats.optDouble("avg_target_cr", 93.0)
        val targetAmount = stats.optDouble("total_target_amount", 0.0)

        setStats(
            v1 = "%,d".format(portfolios), l1 = "Portfolios",
            v2 = "KES %,.0f".format(outstanding), l2 = "Outstanding",
            v3 = "%.2f/%.0f".format(avgRate, avgTarget), l3 = "CR/Target",
            v4 = "KES %,.0f".format(targetAmount), l4 = "Target Amount"
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

        val inputLayout = TextInputLayout(context).apply {
            hint = "DEFAULT=93 or Officer Name=95"
        }

        val prefill = if (targetsInput.isNotBlank()) {
            targetsInput
        } else {
            buildString {
                appendLine("DEFAULT=93")
                officers.forEach { appendLine("$it=") }
            }.trimEnd()
        }

        val input = TextInputEditText(context).apply {
            minLines = 6
            maxLines = 14
            setText(prefill)
        }
        inputLayout.addView(input)
        container.addView(inputLayout)

        val scroll = ScrollView(context).apply { addView(container) }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle("MTD CR Targets")
            .setMessage("Set target CR for this run. Example:\nDEFAULT=93\nJOASH MUTISYA=95")
            .setView(scroll)
            .setNeutralButton("Cancel") { _, _ ->
                showStatus("Processing cancelled")
            }
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
            val result = withContext(Dispatchers.IO) {
                runProcessor(path, FileUtils.outputDir(requireContext()))
            }
            setLoading(false)
            handleResult(result)
        }
    }

    private fun isImagePath(path: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png")
    }

    private suspend fun buildStructuredCsvFromImage(imagePath: String): String {
        val context = requireContext()
        return withContext(Dispatchers.IO) {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val image = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
                val visionText = Tasks.await(recognizer.process(image))
                val rows = parseRows(visionText.text)
                if (rows.isEmpty()) {
                    throw IllegalArgumentException(
                        "Could not read tabular rows from image. Upload CSV/Excel or a clearer image."
                    )
                }

                val inputDir = File(context.getExternalFilesDir(null), "input")
                inputDir.mkdirs()
                val csvPath = File(inputDir, "mtd_cr_ocr_${System.currentTimeMillis()}.csv")

                csvPath.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(
                        "\"Full Name\",\"Total Amount\",\"Paid Amount\",\"Outstanding Amount\",\"Collection Rate (%)\",\"Target CR\",\"TargetAmount\",\"Branch Name\""
                    )
                    rows.forEach { row ->
                        writer.appendLine(
                            "\"${escapeCsv(row.name)}\",\"${row.total}\",\"${row.paid}\",\"${row.outstanding}\",\"${String.format(Locale.US, "%.2f", row.currentCr)}\",\"${row.targetCr}\",\"${row.targetAmount}\",\"MOLO\""
                        )
                    }
                }

                csvPath.absolutePath
            } finally {
                recognizer.close()
            }
        }
    }

    private fun parseRows(rawText: String): List<ParsedRow> {
        val lines = rawText
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val rowPattern = Regex(
            "^([A-Z][A-Z\\s\\.'-]{2,}?)\\s+([0-9,]+)\\s+([0-9,]+)\\s+([0-9,]+)\\s+([0-9]+(?:\\.[0-9]+)?(?:/[0-9]{1,3})?)\\s*([0-9,]+)?$"
        )

        val rows = mutableListOf<ParsedRow>()
        lines.forEach { line ->
            val cleaned = line
                .uppercase(Locale.ROOT)
                .replace(":", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.contains("FULL NAME") && cleaned.contains("TOTAL")) return@forEach
            if (cleaned.contains("TARGETAMOUNT")) return@forEach

            val match = rowPattern.find(cleaned) ?: return@forEach
            val name = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val total = parseInt(match.groupValues[2])
            val paid = parseInt(match.groupValues[3])
            val outstanding = parseInt(match.groupValues[4])
            val crToken = match.groupValues[5].trim()
            val targetAmountToken = match.groupValues.getOrNull(6).orEmpty().trim()

            if (total <= 0) return@forEach

            val currentCr: Double
            val targetCr: Int
            if (crToken.contains("/")) {
                val parts = crToken.split("/", limit = 2)
                currentCr = parseDouble(parts[0]).coerceIn(0.0, 100.0)
                targetCr = parseInt(parts.getOrElse(1) { "93" }).coerceIn(0, 100).let { if (it == 0) 93 else it }
            } else {
                currentCr = parseDouble(crToken).coerceIn(0.0, 100.0)
                targetCr = targetByName[name] ?: 93
            }

            val targetAmount = if (targetAmountToken.isNotBlank()) {
                parseInt(targetAmountToken)
            } else {
                max((total * (targetCr / 100.0) - paid).roundToInt(), 0)
            }

            rows.add(
                ParsedRow(
                    name = name,
                    total = total,
                    paid = paid,
                    outstanding = outstanding,
                    currentCr = currentCr,
                    targetCr = targetCr,
                    targetAmount = targetAmount
                )
            )
        }

        return rows
    }

    private fun parseInt(raw: String): Int {
        val digits = raw.replace(Regex("[^0-9]"), "")
        return digits.toIntOrNull() ?: 0
    }

    private fun parseDouble(raw: String): Double {
        val clean = raw.replace(",", ".").replace(Regex("[^0-9.]"), "")
        return clean.toDoubleOrNull() ?: 0.0
    }

    private fun escapeCsv(value: String): String = value.replace("\"", "\"\"")
}
