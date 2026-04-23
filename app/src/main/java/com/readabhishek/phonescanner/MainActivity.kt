package com.readabhishek.phonescanner

import android.app.Activity
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.readabhishek.phonescanner.ui.theme.PhoneScannerTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PhoneScanner — scans documents with the camera using Google's ML Kit
 * Document Scanner, then saves the result as a PDF in the device's public
 * Documents/PhoneScanner folder.
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneScannerApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var recentScans by remember { mutableStateOf(emptyList<PdfStorage.SavedPdf>()) }
    var refreshTick by remember { mutableStateOf(0) }

    // Load recent scans on first composition and whenever refreshTick changes.
    LaunchedEffect(refreshTick) {
        recentScans = withContext(Dispatchers.IO) { PdfStorage.listRecent(context) }
    }

    // Configure the ML Kit scanner: full-feature mode (includes filters),
    // allow gallery import, unlimited pages, and ask for a PDF result.
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

    // Launcher to receive the IntentSender result from the scanner flow.
    val scanLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result: ActivityResult ->
        if (result.resultCode != Activity.RESULT_OK) {
            scope.launch {
                snackbarHostState.showSnackbar(
                    context.getString(R.string.scan_canceled)
                )
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

        scope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    PdfStorage.savePdf(context, pdf.uri)
                }
                refreshTick += 1
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
                    context.getString(R.string.save_failed, e.localizedMessage ?: "scanner unavailable"),
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        HomeContent(
            padding = padding,
            recentScans = recentScans,
            onScanClick = startScan,
            onOpenPdf = { uri -> openPdf(context as Activity, uri) }
        )
    }
}

@Composable
private fun HomeContent(
    padding: PaddingValues,
    recentScans: List<PdfStorage.SavedPdf>,
    onScanClick: () -> Unit,
    onOpenPdf: (Uri) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.DocumentScanner,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = stringResource(R.string.scan_hint),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onScanClick) {
                    Text(stringResource(R.string.scan_button))
                }
            }
        }

        Text(
            text = stringResource(R.string.recent_scans),
            style = MaterialTheme.typography.titleMedium
        )

        if (recentScans.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.no_scans_yet),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(recentScans, key = { it.uri.toString() }) { scan ->
                    RecentScanRow(scan = scan, onOpen = { onOpenPdf(scan.uri) })
                }
            }
        }
    }
}

@Composable
private fun RecentScanRow(scan: PdfStorage.SavedPdf, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.size(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(scan.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        scan.relativePath,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                TextButton(onClick = onOpen) {
                    Text(stringResource(R.string.open))
                }
            }
        }
    }
}

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
