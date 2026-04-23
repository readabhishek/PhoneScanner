package com.readabhishek.phonescanner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists a ML Kit scanner-produced PDF file into the device's public
 * "Documents/PhoneScanner" folder so it is visible from the Files app,
 * Gmail attachments, and other apps.
 *
 * On Android 10 (API 29) and later we use MediaStore with scoped storage.
 * On earlier versions we copy directly to external storage (declared in the
 * manifest with maxSdkVersion 28).
 */
object PdfStorage {

    private const val FOLDER = "PhoneScanner"
    private const val MIME_PDF = "application/pdf"

    data class SavedPdf(val uri: Uri, val displayName: String, val relativePath: String)

    fun savePdf(context: Context, sourcePdfUri: Uri): SavedPdf {
        val displayName = "Scan_" +
            SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".pdf"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, sourcePdfUri, displayName)
        } else {
            saveLegacy(context, sourcePdfUri, displayName)
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        sourcePdfUri: Uri,
        displayName: String
    ): SavedPdf {
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_PDF)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val destUri = resolver.insert(collection, values)
            ?: error("MediaStore rejected insert")

        resolver.openInputStream(sourcePdfUri).use { input ->
            requireNotNull(input) { "Could not open scanner PDF for reading" }
            resolver.openOutputStream(destUri).use { output ->
                requireNotNull(output) { "Could not open destination PDF for writing" }
                input.copyTo(output)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val finalize = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(destUri, finalize, null, null)
        }

        return SavedPdf(destUri, displayName, relativePath)
    }

    private fun saveLegacy(
        context: Context,
        sourcePdfUri: Uri,
        displayName: String
    ): SavedPdf {
        @Suppress("DEPRECATION")
        val docsRoot = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        val outDir = File(docsRoot, FOLDER).apply { if (!exists()) mkdirs() }
        val outFile = File(outDir, displayName)

        context.contentResolver.openInputStream(sourcePdfUri).use { input ->
            requireNotNull(input) { "Could not open scanner PDF for reading" }
            outFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return SavedPdf(
            uri = Uri.fromFile(outFile),
            displayName = displayName,
            relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER"
        )
    }

    /**
     * Lists previously saved scans for the simple in-app "Recent scans" list.
     * On API 29+ we query MediaStore; on older devices we walk the folder.
     */
    fun listRecent(context: Context, limit: Int = 20): List<SavedPdf> {
        val results = mutableListOf<SavedPdf>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.DATE_ADDED
            )
            val selection = MediaStore.Files.FileColumns.RELATIVE_PATH + " LIKE ? AND " +
                MediaStore.Files.FileColumns.MIME_TYPE + " = ?"
            val args = arrayOf("${Environment.DIRECTORY_DOCUMENTS}/$FOLDER%", MIME_PDF)
            val sort = MediaStore.Files.FileColumns.DATE_ADDED + " DESC"
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            context.contentResolver.query(collection, projection, selection, args, sort)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                while (c.moveToNext() && results.size < limit) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol)
                    val path = c.getString(pathCol) ?: ""
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    results += SavedPdf(uri, name, path)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val docsRoot = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            )
            val folder = File(docsRoot, FOLDER)
            if (folder.isDirectory) {
                folder.listFiles { f -> f.extension.equals("pdf", ignoreCase = true) }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(limit)
                    ?.forEach { f ->
                        results += SavedPdf(
                            Uri.fromFile(f),
                            f.name,
                            "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER"
                        )
                    }
            }
        }
        return results
    }
}
