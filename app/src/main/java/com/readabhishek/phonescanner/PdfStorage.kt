package com.readabhishek.phonescanner

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists scanner / imported PDFs into the device's public
 * "Documents/PhoneScanner" folder and provides CRUD helpers on top of
 * MediaStore (API 29+) or the legacy public Documents folder (API ≤ 28).
 */
object PdfStorage {

    const val FOLDER = "PhoneScanner"
    private const val MIME_PDF = "application/pdf"
    private const val MIME_TEXT = "text/plain"

    data class SavedPdf(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
        val sizeBytes: Long = 0L,
        val dateAddedMillis: Long = 0L,
        /** Base name without extension, e.g. "Scan_20260423_123456". */
        val baseName: String = displayName.substringBeforeLast('.')
    )

    // ----------------------------------------------------------------------
    // Save / import
    // ----------------------------------------------------------------------

    fun savePdf(context: Context, sourcePdfUri: Uri): SavedPdf {
        val baseName = defaultBaseName("Scan")
        return writePdf(context, baseName) { dest ->
            context.contentResolver.openInputStream(sourcePdfUri).use { input ->
                requireNotNull(input) { "Could not open source for reading" }
                input.copyTo(dest)
            }
        }
    }

    /**
     * Imports an external PDF (picked via Storage Access Framework) into
     * our folder, preserving its name but prefixing with "Import_" and a
     * timestamp to avoid collisions.
     */
    fun importPdf(context: Context, sourceUri: Uri, originalName: String?): SavedPdf {
        val cleaned = (originalName ?: "imported.pdf")
            .substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
        val baseName = "Import_" + timestamp() + "_" + cleaned
        return writePdf(context, baseName) { dest ->
            context.contentResolver.openInputStream(sourceUri).use { input ->
                requireNotNull(input) { "Could not open source for reading" }
                input.copyTo(dest)
            }
        }
    }

    /**
     * Writes the bytes of a freshly-built PDF (e.g. from images or a
     * signature) into the PhoneScanner folder.
     */
    fun writePdfBytes(context: Context, baseName: String, bytes: ByteArray): SavedPdf {
        return writePdf(context, baseName) { dest -> dest.write(bytes) }
    }

    /**
     * Saves plain text (usually OCR output) as a .txt file in the same
     * Documents/PhoneScanner folder.
     */
    fun saveText(context: Context, baseName: String, text: String): Uri {
        val displayName = "$baseName.txt"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            insertViaMediaStore(context, displayName, MIME_TEXT) { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
            }.first
        } else {
            val outFile = legacyDestFile(displayName)
            outFile.outputStream().use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Uri.fromFile(outFile)
        }
    }

    // ----------------------------------------------------------------------
    // Edit: rename + delete
    // ----------------------------------------------------------------------

    fun rename(context: Context, target: SavedPdf, newBaseName: String): SavedPdf {
        val sanitized = newBaseName
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .ifBlank { target.baseName }
            .take(80)
        val newDisplay = "$sanitized.pdf"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplay)
            }
            context.contentResolver.update(target.uri, values, null, null)
            target.copy(displayName = newDisplay, baseName = sanitized)
        } else {
            val oldFile = File(legacyFolder(), target.displayName)
            val newFile = File(legacyFolder(), newDisplay)
            if (oldFile.exists()) oldFile.renameTo(newFile)
            target.copy(
                uri = Uri.fromFile(newFile),
                displayName = newDisplay,
                baseName = sanitized
            )
        }
    }

    fun delete(context: Context, target: SavedPdf): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val rows = context.contentResolver.delete(target.uri, null, null)
            rows > 0
        } else {
            val f = File(legacyFolder(), target.displayName)
            f.exists() && f.delete()
        }
    }

    // ----------------------------------------------------------------------
    // Listing
    // ----------------------------------------------------------------------

    /** 20 most recent by default, for the Home screen. */
    fun listRecent(context: Context, limit: Int = 20): List<SavedPdf> =
        queryList(context, limit)

    /** Unlimited, for the Files tab. */
    fun listAll(context: Context): List<SavedPdf> =
        queryList(context, Int.MAX_VALUE)

    private fun queryList(context: Context, limit: Int): List<SavedPdf> {
        val results = mutableListOf<SavedPdf>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.SIZE,
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
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
                while (c.moveToNext() && results.size < limit) {
                    val id = c.getLong(idCol)
                    val name = c.getString(nameCol)
                    val path = c.getString(pathCol) ?: ""
                    val size = c.getLong(sizeCol)
                    // DATE_ADDED is in seconds since epoch
                    val millis = c.getLong(dateCol) * 1000L
                    val uri = Uri.withAppendedPath(collection, id.toString())
                    results += SavedPdf(uri, name, path, size, millis)
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
                            uri = Uri.fromFile(f),
                            displayName = f.name,
                            relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER",
                            sizeBytes = f.length(),
                            dateAddedMillis = f.lastModified()
                        )
                    }
            }
        }
        return results
    }

    // ----------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------

    private fun defaultBaseName(prefix: String) = "${prefix}_" + timestamp()

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun writePdf(
        context: Context,
        baseName: String,
        writer: (java.io.OutputStream) -> Unit
    ): SavedPdf {
        val displayName = "$baseName.pdf"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val (uri, relativePath) = insertViaMediaStore(context, displayName, MIME_PDF, writer)
            SavedPdf(
                uri = uri,
                displayName = displayName,
                relativePath = relativePath,
                baseName = baseName
            )
        } else {
            val outFile = legacyDestFile(displayName)
            outFile.outputStream().use { writer(it) }
            SavedPdf(
                uri = Uri.fromFile(outFile),
                displayName = displayName,
                relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER",
                sizeBytes = outFile.length(),
                dateAddedMillis = outFile.lastModified(),
                baseName = baseName
            )
        }
    }

    private fun insertViaMediaStore(
        context: Context,
        displayName: String,
        mime: String,
        writer: (java.io.OutputStream) -> Unit
    ): Pair<Uri, String> {
        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val destUri = resolver.insert(collection, values)
            ?: error("MediaStore rejected insert")

        resolver.openOutputStream(destUri).use { out ->
            requireNotNull(out) { "Could not open destination for writing" }
            writer(out)
        }

        val finalize = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        resolver.update(destUri, finalize, null, null)
        return destUri to relativePath
    }

    @Suppress("DEPRECATION")
    private fun legacyFolder(): File {
        val docsRoot = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOCUMENTS
        )
        return File(docsRoot, FOLDER).apply { if (!exists()) mkdirs() }
    }

    private fun legacyDestFile(displayName: String): File =
        File(legacyFolder(), displayName)

    /**
     * Convenience: copy a URI's bytes into a ByteArray (small PDFs only).
     * Not used here directly but kept for callers that need it.
     */
    @Suppress("unused")
    fun readAllBytes(stream: InputStream): ByteArray = stream.readBytes()
}
