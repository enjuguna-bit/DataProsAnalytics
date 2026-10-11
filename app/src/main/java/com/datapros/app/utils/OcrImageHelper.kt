package com.datapros.app.utils

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Shared OCR utilities used by SOD-CR and MTD-CR fragments.
 *
 * Both fragments support uploading either a structured CSV/Excel file or a
 * photo of the report screen.  When a photo is uploaded, ML Kit reads the text
 * and this helper converts the raw recognition output into a structured CSV that
 * the Python processors can consume.
 *
 * Changes in v2:
 *  - Added [isSpreadsheetPath] so callers can use a single, canonical function
 *    instead of negating [isImagePath] (which is easy to forget).
 *  - [buildCsvFromImage] now deletes the generated CSV file and re-throws when
 *    [rawTextToRows] returns empty results, so the file does not linger on disk
 *    as a zero-row stub that would confuse a subsequent processor run.
 *  - [normaliseLine] now also strips leading/trailing numeric artefacts that
 *    OCR sometimes introduces (e.g. row numbers or confidence markers).
 *  - Inline KDoc added to every public member.
 */
object OcrImageHelper {

    // ── Path classification ──────────────────────────────────────────────────

    /**
     * Returns `true` when [path] points to a JPEG or PNG image file.
     * Use this to decide whether OCR pre-processing is needed.
     */
    fun isImagePath(path: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        return p.endsWith(".jpg") || p.endsWith(".jpeg") || p.endsWith(".png")
    }

    /**
     * Returns `true` when [path] points to a CSV or Excel spreadsheet.
     * Convenience inverse of [isImagePath] for readability at call sites.
     */
    fun isSpreadsheetPath(path: String): Boolean {
        val p = path.lowercase(Locale.ROOT)
        return p.endsWith(".csv") || p.endsWith(".xls") || p.endsWith(".xlsx")
    }

    // ── OCR pipeline ─────────────────────────────────────────────────────────

    /**
     * Run ML Kit OCR on [imagePath] and convert the recognised text into a CSV
     * file written to [outputDir].  The returned path can be passed directly to
     * the Python processors.
     *
     * @param context       Android context (needed by ML Kit and FileProvider).
     * @param imagePath     Absolute path to the source image (JPEG or PNG).
     * @param outputDir     Directory where the generated CSV will be written.
     * @param csvPrefix     Filename prefix, e.g. `"sod_cr_ocr"` or `"mtd_cr_ocr"`.
     * @param csvHeader     The header row to write — caller-supplied so each fragment
     *                      can declare the exact columns its processor expects.
     * @param rawTextToRows Converts the full recognised text into CSV data lines.
     *                      Each returned string is one complete, quoted CSV row.
     *                      Return an empty list to signal that no valid rows were
     *                      found (triggers [IllegalArgumentException]).
     *
     * @return Absolute path of the generated CSV file.
     * @throws IllegalArgumentException when [rawTextToRows] returns no rows.
     */
    suspend fun buildCsvFromImage(
        context:       Context,
        imagePath:     String,
        outputDir:     File,
        csvPrefix:     String,
        csvHeader:     String,
        rawTextToRows: (String) -> List<String>
    ): String = withContext(Dispatchers.IO) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val csvFile    = File(outputDir.also { it.mkdirs() }, "${csvPrefix}_${System.currentTimeMillis()}.csv")
        try {
            val image      = InputImage.fromFilePath(context, Uri.fromFile(File(imagePath)))
            val visionText = Tasks.await(recognizer.process(image))
            val dataLines  = rawTextToRows(visionText.text)

            if (dataLines.isEmpty()) {
                // Delete the stub file before throwing so we don't leave an empty CSV.
                csvFile.delete()
                throw IllegalArgumentException(
                    "Could not read tabular rows from the image. " +
                    "Please upload a CSV/Excel file or take a clearer photo."
                )
            }

            csvFile.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine(csvHeader)
                dataLines.forEach { writer.appendLine(it) }
            }
            csvFile.absolutePath
        } finally {
            recognizer.close()
        }
    }

    // ── Shared parsing primitives ────────────────────────────────────────────

    /**
     * Parses a raw string (possibly containing commas and non-digit characters)
     * into an [Int].  Returns `0` when parsing fails.
     */
    fun parseInt(raw: String): Int = raw.replace(Regex("[^0-9]"), "").toIntOrNull() ?: 0

    /**
     * Parses a raw string into a [Double], replacing commas with dots first.
     * Returns `0.0` when parsing fails.
     */
    fun parseDouble(raw: String): Double =
        raw.replace(",", ".").replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0

    /**
     * Escapes a value for safe inclusion inside a CSV double-quoted field.
     * Per RFC 4180, a double-quote character is escaped by doubling it.
     */
    fun escapeCsv(value: String): String = value.replace("\"", "\"\"")

    /**
     * Normalises a single OCR line ready for regex matching:
     * - Converts to upper-case
     * - Replaces colons with spaces
     * - Strips leading digits (row numbers) that OCR sometimes prepends
     * - Collapses runs of whitespace to a single space
     * - Trims leading and trailing whitespace
     */
    fun normaliseLine(line: String): String =
        line.uppercase(Locale.ROOT)
            .replace(":", " ")
            .replace(Regex("^\\d+\\s+"), "")   // strip leading OCR row-number artefact
            .replace(Regex("\\s+"), " ")
            .trim()
}
