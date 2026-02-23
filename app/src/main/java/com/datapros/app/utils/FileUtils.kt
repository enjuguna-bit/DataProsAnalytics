package com.datapros.app.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object FileUtils {

    /**
     * Copy a file from a content URI to the app's external files directory
     * and return the absolute path. Chaquopy Python scripts need real file paths.
     */
    fun uriToPath(context: Context, uri: Uri, subfolder: String = "input"): String? {
        return try {
            val dir = File(context.getExternalFilesDir(null), subfolder)
            dir.mkdirs()

            val filename = getFileName(context, uri) ?: "uploaded_file"
            val dest = File(dir, filename)

            val input: InputStream = context.contentResolver.openInputStream(uri) ?: return null
            FileOutputStream(dest).use { out -> input.copyTo(out) }
            input.close()
            dest.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) name = cursor.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment
    }

    /**
     * Returns the app's private external directory used for report output.
     */
    fun outputDir(context: Context): String {
        val dir = File(context.getExternalFilesDir(null), "reports")
        dir.mkdirs()
        return dir.absolutePath
    }

    /**
     * Creates a share intent so the user can send the Excel report via WhatsApp, email, etc.
     */
    fun shareFile(context: Context, filePath: String): Intent {
        val file = File(filePath)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Opens the report file with a suitable viewer (e.g. WPS, Microsoft Excel).
     */
    fun openFile(context: Context, filePath: String): Intent {
        val file = File(filePath)
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
