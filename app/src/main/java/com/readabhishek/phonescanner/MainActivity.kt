@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.readabhishek.phonescanner

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.readabhishek.phonescanner.ui.theme.PhoneScannerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PhoneScanner — top-level host. Holds screen/tab state and the activity
 * result launchers, and delegates UI to [PhoneScannerRoot] and
 * [SignatureScreen].
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PhoneScannerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PhoneScannerApp()
                }
            }
        }
    }
}

/** Top-level screens (bottom-nav host + fullscreen sub-flows). */
private sealed class Screen {
    data object Main : Screen()
    data object Signature : Screen()
    data class Editor(val source: PdfStorage.SavedPdf) : Screen()
}

@Composable
fun PhoneScannerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    var tab by remember { mutableStateOf(Tab.Home) }
    var isProcessing by remember { mutableStateOf(false) }
    var recentScans by remember { mutableStateOf(emptyList<PdfStorage.SavedPdf>()) }
    var allScans by remember { mutableStateOf(emptyList<PdfStorage.SavedPdf>()) }
    var refreshTick by remember { mutableStateOf(0) }

    // Editor state — pages for the currently-open PDF, pending signature pick.
    var editorPages by remember { mutableStateOf(emptyList<Bitmap>()) }
    var editorLoading by remember { mutableStateOf(false) }
    var pendingSignature by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(refreshTick) {
        val snapshot = withContext(Dispatchers.IO) {
            PdfStorage.listAll(context)
        }
        allScans = snapshot
        recentScans = snapshot.take(20)
    }

    // --------- Scanner launcher ---------
    val scannerOptions = remember {
        GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(25)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
    }
    val scanner = remember { GmsDocumentScanning.getClient(scannerOptions) }

    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) {
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.scan_canceled))
            }
            return@rememberLauncherForActivityResult
        }
        val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
        val pdf = scanResult?.pdf
        if (pdf == null) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.save_failed, "no PDF returned")
                )
            }
            return@rememberLauncherForActivityResult
        }
        val pageUris = scanResult.pages.orEmpty().map { it.imageUri }

        scope.launch {
            isProcessing = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    PdfStorage.savePdf(context, pdf.uri)
                }
                refreshTick += 1

                val ocrText = runCatching {
                    withContext(Dispatchers.IO) {
                        OcrProcessor.extractText(context, pageUris)
                    }
                }.getOrElse { err ->
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.ocr_failed, err.message ?: "unknown error")
                    )
                    return@launch
                }

                if (ocrText.isBlank()) {
                    snackbarHostState.showSnackbar(
                        context.getString(
                            R.string.saved_to,
                            "${saved.relativePath}/${saved.displayName}"
                        )
                    )
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    PdfStorage.saveText(context, saved.baseName, ocrText)
                }

                val action = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.ocr_done),
                    actionLabel = context.getString(R.string.ocr_recognize),
                    withDismissAction = true
                )
                if (action == SnackbarResult.ActionPerformed) {
                    sendToGoogleDocs(context as Activity, saved.baseName, ocrText)
                }
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.save_failed, t.message ?: "unknown error")
                )
            } finally {
                isProcessing = false
            }
        }
    }

    val startScan: () -> Unit = {
        scanner.getStartScanIntent(context as Activity)
            .addOnSuccessListener { sender: IntentSender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.save_failed,
                        e.localizedMessage ?: "scanner unavailable"
                    ),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    // --------- SAF: pick a PDF ---------
    val pdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    FileTools.importPickedPdf(context, uri)
                }
                refreshTick += 1
                snackbarHostState.showSnackbar(
                    context.getString(R.string.pdf_imported, saved.displayName)
                )
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.pdf_import_failed, t.message ?: "unknown error")
                )
            } finally {
                isProcessing = false
            }
        }
    }

    // --------- SAF: pick multiple images ---------
    val imagesPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            isProcessing = true
            try {
                val saved = withContext(Dispatchers.IO) {
                    FileTools.buildPdfFromImages(context, uris)
                }
                refreshTick += 1
                snackbarHostState.showSnackbar(
                    context.getString(R.string.images_imported, uris.size, saved.displayName)
                )
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.images_import_failed, t.message ?: "unknown error")
                )
            } finally {
                isProcessing = false
            }
        }
    }

    // --------- SAF: pick a PDF to open directly in the editor ---------
    val editPdfPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val wrapped = FileTools.savedPdfFromPickedUri(context, uri)
        editorPages = emptyList()
        pendingSignature = null
        screen = Screen.Editor(wrapped)
    }

    // --------- SAF: pick a signature image or PDF for the editor ---------
    val signaturePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) {
                    FileTools.loadSignatureFromUri(context, uri)
                }
                if (bitmap == null) {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.signature_import_failed)
                    )
                } else {
                    pendingSignature = bitmap
                }
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(
                        R.string.signature_import_failed_with,
                        t.message ?: "unknown error"
                    )
                )
            }
        }
    }

    // --------- Action dispatcher ---------
    val onAction: (HomeAction) -> Unit = { action ->
        when (action) {
            HomeAction.Scan -> startScan()
            HomeAction.ImportPdf -> pdfPicker.launch(arrayOf("application/pdf"))
            HomeAction.ImportImages -> imagesPicker.launch(arrayOf("image/*"))
            HomeAction.Signature -> { screen = Screen.Signature }
            HomeAction.GoogleDoc -> {
                tab = Tab.Files
                scope.launch {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.google_doc_tile_hint)
                    )
                }
            }
            HomeAction.Edit -> editPdfPicker.launch(arrayOf("application/pdf"))
        }
    }

    // --------- Per-row actions ---------
    val onOpenPdf: (Uri) -> Unit = { uri -> openPdf(context as Activity, uri) }

    val onPickForGoogleDoc: (PdfStorage.SavedPdf) -> Unit = { scan ->
        scope.launch {
            isProcessing = true
            try {
                val text = withContext(Dispatchers.IO) {
                    FileTools.extractTextFromPdf(context, scan.uri)
                }
                if (text.isBlank()) {
                    snackbarHostState.showSnackbar(context.getString(R.string.ocr_no_text))
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    PdfStorage.saveText(context, scan.baseName, text)
                }
                val result = snackbarHostState.showSnackbar(
                    message = context.getString(R.string.ocr_done),
                    actionLabel = context.getString(R.string.ocr_recognize),
                    withDismissAction = true
                )
                if (result == SnackbarResult.ActionPerformed) {
                    sendToGoogleDocs(context as Activity, scan.baseName, text)
                }
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.ocr_failed, t.message ?: "unknown error")
                )
            } finally {
                isProcessing = false
            }
        }
    }

    val onRename: (PdfStorage.SavedPdf, String) -> Unit = { scan, newName ->
        scope.launch {
            try {
                val renamed = withContext(Dispatchers.IO) {
                    PdfStorage.rename(context, scan, newName)
                }
                refreshTick += 1
                snackbarHostState.showSnackbar(
                    context.getString(R.string.file_renamed, renamed.displayName)
                )
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.rename_failed, t.message ?: "unknown error")
                )
            }
        }
    }

    val onEditPdf: (PdfStorage.SavedPdf) -> Unit = { scan ->
        editorPages = emptyList()
        pendingSignature = null
        screen = Screen.Editor(scan)
    }

    val onDelete: (PdfStorage.SavedPdf) -> Unit = { scan ->
        scope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { PdfStorage.delete(context, scan) }
                if (ok) {
                    refreshTick += 1
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.file_deleted, scan.displayName)
                    )
                } else {
                    snackbarHostState.showSnackbar(
                        context.getString(R.string.delete_failed, "not found")
                    )
                }
            } catch (t: Throwable) {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.delete_failed, t.message ?: "unknown error")
                )
            }
        }
    }

    // --------- Screen routing ---------
    when (val s = screen) {
        Screen.Main -> PhoneScannerRoot(
            recentScans = recentScans,
            allScans = allScans,
            isProcessing = isProcessing,
            snackbarHostState = snackbarHostState,
            tab = tab,
            onTabChange = { tab = it },
            onAction = onAction,
            onOpenPdf = onOpenPdf,
            onPickForGoogleDoc = onPickForGoogleDoc,
            onEditPdf = onEditPdf,
            onRename = onRename,
            onDelete = onDelete
        )
        Screen.Signature -> SignatureScreen(
            onBack = { screen = Screen.Main },
            onSave = { bitmap ->
                scope.launch {
                    isProcessing = true
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            FileTools.buildSignaturePdf(
                                context,
                                bitmap,
                                title = "Signature — " +
                                    java.text.SimpleDateFormat(
                                        "yyyy-MM-dd",
                                        java.util.Locale.US
                                    ).format(java.util.Date())
                            )
                        }
                        refreshTick += 1
                        screen = Screen.Main
                        snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.saved_to,
                                "${saved.relativePath}/${saved.displayName}"
                            )
                        )
                    } catch (t: Throwable) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.save_failed, t.message ?: "unknown error")
                        )
                    } finally {
                        isProcessing = false
                    }
                }
            }
        )
        is Screen.Editor -> PdfEditorScreen(
            source = s.source,
            pageBitmaps = editorPages,
            isLoading = editorLoading,
            pendingSignature = pendingSignature,
            onLoadRequest = {
                if (editorPages.isEmpty() && !editorLoading) {
                    scope.launch {
                        editorLoading = true
                        try {
                            val rendered = withContext(Dispatchers.IO) {
                                FileTools.renderPdfPages(context, s.source.uri)
                            }
                            editorPages = rendered
                        } catch (t: Throwable) {
                            snackbarHostState.showSnackbar(
                                context.getString(
                                    R.string.pdf_editor_load_failed,
                                    t.message ?: "unknown error"
                                )
                            )
                            screen = Screen.Main
                        } finally {
                            editorLoading = false
                        }
                    }
                }
            },
            onPickSignature = {
                // Accept signature PDFs saved by this app or any image.
                signaturePicker.launch(arrayOf("image/*", "application/pdf"))
            },
            onSignatureConsumed = { pendingSignature = null },
            onBack = {
                editorPages = emptyList()
                pendingSignature = null
                screen = Screen.Main
            },
            onSave = { overlays, cropsByPage ->
                scope.launch {
                    isProcessing = true
                    try {
                        val saved = withContext(Dispatchers.IO) {
                            FileTools.savePdfWithOverlays(
                                context,
                                s.source,
                                overlays,
                                cropsByPage
                            )
                        }
                        refreshTick += 1
                        editorPages = emptyList()
                        pendingSignature = null
                        screen = Screen.Main
                        snackbarHostState.showSnackbar(
                            context.getString(
                                R.string.saved_to,
                                "${saved.relativePath}/${saved.displayName}"
                            )
                        )
                    } catch (t: Throwable) {
                        snackbarHostState.showSnackbar(
                            context.getString(R.string.save_failed, t.message ?: "unknown error")
                        )
                    } finally {
                        isProcessing = false
                    }
                }
            }
        )
    }
}

// ======================================================================
// Intent helpers
// ======================================================================

private fun openPdf(activity: Activity, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Open PDF")
    try {
        activity.startActivity(chooser)
    } catch (_: Throwable) {
        Toast.makeText(activity, "No app available to open PDFs", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Hands the OCR'd text to the Android share sheet as text/plain. When the
 * user picks "Docs" the Google Docs app creates a native Google Document
 * containing the text.
 */
private fun sendToGoogleDocs(activity: Activity, baseName: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, baseName)
        putExtra(Intent.EXTRA_TITLE, baseName)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(
        send,
        activity.getString(R.string.send_to_docs_chooser)
    )
    try {
        activity.startActivity(chooser)
    } catch (_: Throwable) {
        Toast.makeText(
            activity,
            activity.getString(R.string.send_to_docs_unavailable),
            Toast.LENGTH_LONG
        ).show()
    }
}
