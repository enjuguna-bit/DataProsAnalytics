package com.datapros.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale

object FileUtils {

    private const val TAG = "FileUtils"

    private val knownImportExtensions = setOf("csv", "xls", "xlsx", "jpg", "jpeg", "png")

    /**
     * Maximum age of input cache files before they are eligible for deletion.
     * Default: 24 hours.
     */
    private const val INPUT_MAX_AGE_MS = 24 * 60 * 60 * 1_000L

    /**
     * Copy a file from a content URI into the app's external files directory and return
     * the absolute path.  Chaquopy Python scripts require real filesystem paths.
     *
     * A timestamp suffix is appended to avoid silently overwriting a previously imported
     * file that has the same display name. Old copies beyond [INPUT_MAX_AGE_MS] are
     * cleaned up automatically on each successful import to prevent storage bloat.
     */
    fun uriToPath(context: Context, uri: Uri, subfolder: String = "input"): String? {
        return try {
            val dir = File(context.getExternalFilesDir(null), subfolder).also { it.mkdirs() }

            // Clean up old cached input files before adding a new one.
            cleanOldFiles(dir, maxAgeMs = INPUT_MAX_AGE_MS)

            val rawName  = queryDisplayName(context, uri) ?: uri.lastPathSegment ?: "upload"
            val suffix   = System.currentTimeMillis()
            val destName = buildImportFileName(
                rawName = rawName,
                mimeType = context.contentResolver.getType(uri),
                timestamp = suffix
            )
            val dest     = File(dir, destName)

            context.contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(dest).use { sink -> source.copyTo(sink) }
            } ?: return null

            dest.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "uriToPath failed for $uri", e)
            null
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx < 0) null else cursor.getString(idx)
        }
    }

    /**
     * Returns the app-private external directory used for report output, organised
     * by today's date (e.g. `reports/2025-03-23/`) so reports from different days
     * are easy to find and browse.
     */
    fun outputDir(context: Context): String {
        val dated = File(
            File(context.getExternalFilesDir(null), "reports"),
            LocalDate.now().toString()
        )
        dated.mkdirs()
        return dated.absolutePath
    }

    /**
     * Deletes input cache files older than [maxAgeMs] milliseconds.
     * Silent on any I/O error; this is best-effort housekeeping.
     */
    fun cleanOldInputFiles(context: Context, maxAgeMs: Long = INPUT_MAX_AGE_MS) {
        val dir = File(context.getExternalFilesDir(null), "input")
        if (dir.exists()) cleanOldFiles(dir, maxAgeMs)
    }

    /**
     * Deletes report subdirectories older than [maxAgeDays] days.
     * Leaves the current day's folder untouched.
     */
    fun cleanOldReports(context: Context, maxAgeDays: Long = 7) {
        val reportsDir = File(context.getExternalFilesDir(null), "reports")
        if (!reportsDir.exists()) return
        val cutoff = System.currentTimeMillis() - maxAgeDays * 24 * 60 * 60 * 1_000L
        reportsDir.listFiles()?.forEach { child ->
            if (child.isDirectory && child.lastModified() < cutoff) {
                runCatching { child.deleteRecursively() }
                    .onFailure { Log.w(TAG, "Could not delete old report dir: ${child.name}", it) }
            }
        }
    }

    /**
     * Creates an ACTION_SEND intent so the user can share an Excel report via WhatsApp,
     * email, etc.
     */
    fun shareFile(context: Context, filePath: String): Intent {
        val uri = fileUri(context, filePath)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeFor(filePath)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens the report file with a suitable viewer (e.g. WPS, Microsoft Excel).
     */
    fun openFile(context: Context, filePath: String): Intent {
        val uri = fileUri(context, filePath)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeTypeFor(filePath))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private fun fileUri(context: Context, filePath: String): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.provider", File(filePath))

    /** Returns the correct MIME type for the given path. Falls back to xlsx. */
    private fun mimeTypeFor(filePath: String): String = when {
        filePath.endsWith(".csv",  ignoreCase = true) -> "text/csv"
        filePath.endsWith(".xls",  ignoreCase = true) -> "application/vnd.ms-excel"
        else -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }

    /**
     * MIME types accepted by the import pickers across the app.
     * CSV exports are sometimes exposed as `text/plain` or `application/octet-stream`,
     * so we keep the list broad enough to avoid rejecting valid downloads.
     */
    fun supportedImportMimeTypes(includeImages: Boolean = false): Array<String> = buildList {
        add("text/csv")
        add("application/csv")
        add("text/comma-separated-values")
        add("text/plain")
        add("application/octet-stream")
        add("application/vnd.ms-excel")
        add("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
        if (includeImages) {
            add("image/jpeg")
            add("image/jpg")
            add("image/png")
        }
    }.distinct().toTypedArray()

    /**
     * Build a safe on-disk name for an imported file.
     *
     * If the source name already ends in a known extension, we keep it. Otherwise we
     * infer one from the MIME type so files like CSV exports still get a usable suffix.
     */
    fun buildImportFileName(
        rawName: String?,
        mimeType: String?,
        timestamp: Long = System.currentTimeMillis()
    ): String {
        val cleanedName = sanitizeFileName((rawName ?: "upload").trim()).ifBlank { "upload" }
        val (baseName, knownExt) = splitKnownExtension(cleanedName)
        val extension = knownExt ?: inferExtensionFromMime(mimeType)
        val safeBaseName = if (knownExt != null) baseName else cleanedName

        return if (extension.isNullOrBlank()) {
            "${safeBaseName}_$timestamp"
        } else {
            "${safeBaseName}_$timestamp.$extension"
        }
    }

    /** Deletes files in [dir] whose last-modified timestamp is older than [maxAgeMs]. */
    private fun cleanOldFiles(dir: File, maxAgeMs: Long) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) {
                runCatching { file.delete() }
                    .onFailure { Log.w(TAG, "Could not delete stale file: ${file.name}", it) }
            }
        }
    }

    private fun sanitizeFileName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_")

    private fun splitKnownExtension(name: String): Pair<String, String?> {
        val lastDot = name.lastIndexOf('.')
        if (lastDot <= 0 || lastDot >= name.length - 1) {
            return name to null
        }

        val extension = name.substring(lastDot + 1).lowercase(Locale.ROOT)
        return if (extension in knownImportExtensions) {
            name.substring(0, lastDot) to extension
        } else {
            name to null
        }
    }

    private fun inferExtensionFromMime(mimeType: String?): String? {
        return when (mimeType?.lowercase(Locale.ROOT)) {
            "text/csv",
            "application/csv",
            "text/comma-separated-values",
            "text/plain" -> "csv"
            "application/vnd.ms-excel" -> "xls"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "image/jpeg",
            "image/jpg" -> "jpg"
            "image/png" -> "png"
            else -> null
        }
    }
}
