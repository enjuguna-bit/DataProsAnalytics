package com.datapros.app.utils

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

object OfflineGuideManager {

    private const val TAG = "OfflineGuideManager"
    private const val ASSET_NAME = "DataPros_User_Guide.docx"
    private const val TEXT_ASSET_NAME = "DataPros_User_Guide.txt"
    private const val GUIDE_FILE_NAME = "DataPros_User_Guide.docx"
    private const val GUIDE_MIME_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    private val DOWNLOAD_RELATIVE_DIR = Environment.DIRECTORY_DOWNLOADS + "/DataPros/"

    data class GuideHandle(
        val uri: Uri,
        val locationLabel: String,
        val savedToDownloads: Boolean
    )

    fun readBundledGuideText(context: Context): String? {
        val fromTextAsset = runCatching {
            context.assets.open(TEXT_ASSET_NAME).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.readText().trim()
            }
        }.onFailure { error ->
            Log.w(TAG, "Unable to read bundled text guide asset; falling back to DOCX.", error)
        }.getOrNull()

        if (!fromTextAsset.isNullOrBlank()) {
            return fromTextAsset
        }

        return runCatching {
            extractDocxText(context)
        }.onFailure { error ->
            Log.e(TAG, "Unable to read bundled guide from DOCX asset.", error)
        }.getOrNull()
    }

    fun exportBundledGuide(context: Context): GuideHandle? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            exportWithMediaStore(context)
        } else {
            exportToAppExternalDownloads(context)
        }
    }.getOrNull()

    fun openGuide(context: Context, guideHandle: GuideHandle): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(guideHandle.uri, GUIDE_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            return false
        }
        context.startActivity(intent)
        return true
    }

    private fun exportWithMediaStore(context: Context): GuideHandle {
        val resolver = context.contentResolver
        val targetUri = findExistingDownloadsUri(context) ?: createDownloadsUri(context)

        resolver.openOutputStream(targetUri, "w")?.use { output ->
            context.assets.open(ASSET_NAME).use { input -> input.copyTo(output) }
        } ?: error("Unable to open download destination for the offline guide.")

        resolver.update(targetUri, ContentValues().apply {
            put(MediaStore.Downloads.IS_PENDING, 0)
        }, null, null)

        return GuideHandle(
            uri = targetUri,
            locationLabel = "Downloads/DataPros/$GUIDE_FILE_NAME",
            savedToDownloads = true
        )
    }

    private fun createDownloadsUri(context: Context): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, GUIDE_FILE_NAME)
            put(MediaStore.MediaColumns.MIME_TYPE, GUIDE_MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOWNLOAD_RELATIVE_DIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create a downloads entry for the offline guide.")
    }

    private fun findExistingDownloadsUri(context: Context): Uri? {
        val resolver = context.contentResolver
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection =
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf(GUIDE_FILE_NAME, DOWNLOAD_RELATIVE_DIR)

        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            return ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
        }
        return null
    }

    private fun exportToAppExternalDownloads(context: Context): GuideHandle {
        val baseDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: context.getExternalFilesDir(null)
            ?: error("Unable to access external app storage for the offline guide.")
        val targetDir = File(baseDir, "offline-guide").also { it.mkdirs() }
        val targetFile = File(targetDir, GUIDE_FILE_NAME)

        context.assets.open(ASSET_NAME).use { input ->
            targetFile.outputStream().use { output -> input.copyTo(output) }
        }

        val guideUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            targetFile
        )
        return GuideHandle(
            uri = guideUri,
            locationLabel = targetFile.absolutePath,
            savedToDownloads = false
        )
    }

    private fun extractDocxText(context: Context): String {
        val tempFile = File.createTempFile("datapros-guide-", ".docx", context.cacheDir)
        try {
            context.assets.open(ASSET_NAME).use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }

            ZipFile(tempFile).use { zip ->
                val entry = zip.getEntry("word/document.xml")
                    ?: error("word/document.xml was not found in the bundled guide.")
                zip.getInputStream(entry).use { input ->
                    val xmlBytes = ByteArrayOutputStream()
                    input.copyTo(xmlBytes)
                    return normalizeGuideXml(xmlBytes.toString(Charsets.UTF_8.name()))
                }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun normalizeGuideXml(documentXml: String): String {
        return documentXml
            .replace(Regex("(?i)<w:tab[^>]*/>"), "\t")
            .replace(Regex("(?i)<w:(br|cr)[^>]*/>"), "\n")
            .replace(Regex("(?i)</w:tc>"), "\t")
            .replace(Regex("(?i)</w:tr>"), "\n")
            .replace(Regex("(?i)</w:p>"), "\n\n")
            .replace(Regex("(?s)<[^>]+>"), "")
            .replace('\u00A0', ' ')
            .replace('\u000B', '\n')
            .replace("&gt;", ">")
            .replace("&lt;", "<")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

}
