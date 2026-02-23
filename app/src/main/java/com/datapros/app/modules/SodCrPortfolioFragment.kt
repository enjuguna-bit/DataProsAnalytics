package com.datapros.app.modules

import android.net.Uri
import com.datapros.app.utils.PythonRunner
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class SodCrPortfolioFragment : BaseModuleFragment() {

    override val moduleTitle = "SOD CR Per Portfolio"
    override val moduleSubtitle = "Upload raw CSV/Excel or image, then export processed Excel report"

    private data class ParsedRow(
        val name: String,
        val total: Int,
        val paid: Int,
        val outstanding: Int,
        val cr: Int,
        val targetTo80: Int
    )

    override fun filePickerMimeTypes(): Array<String> = arrayOf(
        "text/csv",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "image/jpeg",
        "image/png",
        "image/jpg"
    )

    override suspend fun runProcessor(inputPath: String, outputDir: String): JSONObject {
        val sourcePath = if (isImagePath(inputPath)) {
            buildStructuredCsvFromImage(inputPath)
        } else {
            inputPath
        }
        return PythonRunner.runSodCrPortfolio(sourcePath, outputDir)
    }

    override fun bindStats(stats: JSONObject) {
        val portfolios = stats.optInt("total_portfolios", 0)
        val outstanding = stats.optDouble("total_outstanding", 0.0)
        val avgRate = stats.optDouble("avg_collection_rate", 0.0)
        val gapCount = stats.optInt("below_80_count", 0)

        setStats(
            v1 = "%,d".format(portfolios), l1 = "Portfolios",
            v2 = "KES %,.0f".format(outstanding), l2 = "Outstanding",
            v3 = "%.1f%%".format(avgRate), l3 = "Avg CR",
            v4 = "%,d".format(gapCount), l4 = "Below 80%"
        )
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
                val csvPath = File(inputDir, "sod_cr_ocr_${System.currentTimeMillis()}.csv")

                csvPath.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.appendLine(
                        "\"Full Name\",\"Total Amount\",\"Paid Amount\",\"Outstanding Amount\",\"Collection Rate (%)\",\"Target To 80%\",\"Branch Name\""
                    )
                    rows.forEach { row ->
                        writer.appendLine(
                            "\"${escapeCsv(row.name)}\",\"${row.total}\",\"${row.paid}\",\"${row.outstanding}\",\"${row.cr}\",\"${row.targetTo80}\",\"MOLO\""
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
            "^([A-Z][A-Z\\s\\.'-]{2,}?)\\s+([0-9,]+)\\s+([0-9,]+)\\s+([0-9,]+)\\s+([0-9]{1,3})(?:\\s+([0-9,]+))?$"
        )

        val rows = mutableListOf<ParsedRow>()
        lines.forEach { line ->
            val cleaned = line
                .uppercase(Locale.ROOT)
                .replace(":", " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            if (cleaned.contains("FULL NAME") && cleaned.contains("TOTAL")) return@forEach
            if (cleaned.contains("TARGETTO80") || cleaned.contains("TARGET TO 80")) return@forEach

            val match = rowPattern.find(cleaned) ?: return@forEach
            val name = match.groupValues[1].trim().replace(Regex("\\s+"), " ")
            val total = parseInt(match.groupValues[2])
            val paid = parseInt(match.groupValues[3])
            val outstanding = parseInt(match.groupValues[4])
            val cr = parseInt(match.groupValues[5]).coerceIn(0, 100)
            val targetFromText = match.groupValues.getOrNull(6).orEmpty()
            val targetTo80 = if (targetFromText.isNotBlank()) {
                parseInt(targetFromText)
            } else {
                ((total * 0.8) - paid).coerceAtLeast(0.0).roundToInt()
            }

            if (total <= 0) return@forEach

            rows.add(
                ParsedRow(
                    name = name,
                    total = total,
                    paid = paid,
                    outstanding = outstanding,
                    cr = cr,
                    targetTo80 = targetTo80
                )
            )
        }

        return rows
    }

    private fun parseInt(raw: String): Int {
        val digits = raw.replace(Regex("[^0-9]"), "")
        return digits.toIntOrNull() ?: 0
    }

    private fun escapeCsv(value: String): String = value.replace("\"", "\"\"")
}
