@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.readabhishek.phonescanner

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Top-level actions the user can trigger from the Home tile grid.
 */
enum class HomeAction { Scan, ImportPdf, ImportImages, Signature, GoogleDoc, Edit }

/**
 * Bottom-nav tab.
 */
enum class Tab { Home, Files }

/**
 * Home tab: title, search, 6 circular tiles, recents, FAB.
 * Files tab: same chrome but a full, searchable, editable list.
 */
@Composable
fun PhoneScannerRoot(
    recentScans: List<PdfStorage.SavedPdf>,
    allScans: List<PdfStorage.SavedPdf>,
    isProcessing: Boolean,
    snackbarHostState: SnackbarHostState,
    tab: Tab,
    onTabChange: (Tab) -> Unit,
    onAction: (HomeAction) -> Unit,
    onOpenPdf: (Uri) -> Unit,
    onPickForGoogleDoc: (PdfStorage.SavedPdf) -> Unit,
    onEditPdf: (PdfStorage.SavedPdf) -> Unit,
    onRename: (PdfStorage.SavedPdf, String) -> Unit,
    onDelete: (PdfStorage.SavedPdf) -> Unit
) {
    var query by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            when (tab) {
                                Tab.Home -> R.string.app_name
                                Tab.Files -> R.string.files_title
                            }
                        ),
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Home,
                    onClick = { onTabChange(Tab.Home) },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_home)) }
                )
                NavigationBarItem(
                    selected = tab == Tab.Files,
                    onClick = { onTabChange(Tab.Files) },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = { Text(stringResource(R.string.nav_files)) }
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onAction(HomeAction.Scan) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        }
    ) { padding ->
        when (tab) {
            Tab.Home -> HomeTab(
                padding = padding,
                query = query,
                onQueryChange = { query = it },
                recentScans = recentScans.filterByQuery(query),
                isProcessing = isProcessing,
                onAction = onAction,
                onOpenPdf = onOpenPdf,
                onPickForGoogleDoc = onPickForGoogleDoc,
                onEditPdf = onEditPdf,
                onRename = onRename,
                onDelete = onDelete
            )
            Tab.Files -> FilesTab(
                padding = padding,
                query = query,
                onQueryChange = { query = it },
                files = allScans.filterByQuery(query),
                onOpenPdf = onOpenPdf,
                onPickForGoogleDoc = onPickForGoogleDoc,
                onEditPdf = onEditPdf,
                onRename = onRename,
                onDelete = onDelete
            )
        }
    }
}

private fun List<PdfStorage.SavedPdf>.filterByQuery(q: String): List<PdfStorage.SavedPdf> {
    val trimmed = q.trim()
    if (trimmed.isEmpty()) return this
    return filter { it.displayName.contains(trimmed, ignoreCase = true) }
}

// ======================================================================
// Home tab
// ======================================================================

@Composable
private fun HomeTab(
    padding: PaddingValues,
    query: String,
    onQueryChange: (String) -> Unit,
    recentScans: List<PdfStorage.SavedPdf>,
    isProcessing: Boolean,
    onAction: (HomeAction) -> Unit,
    onOpenPdf: (Uri) -> Unit,
    onPickForGoogleDoc: (PdfStorage.SavedPdf) -> Unit,
    onEditPdf: (PdfStorage.SavedPdf) -> Unit,
    onRename: (PdfStorage.SavedPdf, String) -> Unit,
    onDelete: (PdfStorage.SavedPdf) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            SearchField(query = query, onQueryChange = onQueryChange)
        }
        item {
            TilesGrid(onAction = onAction, isProcessing = isProcessing)
        }
        item {
            SectionHeader(text = stringResource(R.string.recent_scans))
        }
        if (recentScans.isEmpty()) {
            item {
                EmptyState(stringResource(R.string.no_scans_yet))
            }
        } else {
            items(recentScans, key = { it.uri.toString() }) { scan ->
                ScanRow(
                    scan = scan,
                    onOpen = { onOpenPdf(scan.uri) },
                    onGoogleDoc = { onPickForGoogleDoc(scan) },
                    onEdit = { onEditPdf(scan) },
                    onRename = { newName -> onRename(scan, newName) },
                    onDelete = { onDelete(scan) }
                )
            }
        }
        // Breathing room so the FAB doesn't sit on top of the last row.
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ======================================================================
// Files tab
// ======================================================================

@Composable
private fun FilesTab(
    padding: PaddingValues,
    query: String,
    onQueryChange: (String) -> Unit,
    files: List<PdfStorage.SavedPdf>,
    onOpenPdf: (Uri) -> Unit,
    onPickForGoogleDoc: (PdfStorage.SavedPdf) -> Unit,
    onEditPdf: (PdfStorage.SavedPdf) -> Unit,
    onRename: (PdfStorage.SavedPdf, String) -> Unit,
    onDelete: (PdfStorage.SavedPdf) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { SearchField(query = query, onQueryChange = onQueryChange) }
        item {
            Text(
                text = stringResource(R.string.files_subtitle, files.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        if (files.isEmpty()) {
            item { EmptyState(stringResource(R.string.files_empty)) }
        } else {
            items(files, key = { it.uri.toString() }) { scan ->
                ScanRow(
                    scan = scan,
                    onOpen = { onOpenPdf(scan.uri) },
                    onGoogleDoc = { onPickForGoogleDoc(scan) },
                    onEdit = { onEditPdf(scan) },
                    onRename = { newName -> onRename(scan, newName) },
                    onDelete = { onDelete(scan) }
                )
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

// ======================================================================
// Reusable pieces
// ======================================================================

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = { Text(stringResource(R.string.search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ----- Tiles -----

private data class TileSpec(
    val action: HomeAction,
    val icon: ImageVector,
    val labelRes: Int
)

@Composable
private fun TilesGrid(onAction: (HomeAction) -> Unit, isProcessing: Boolean) {
    val tiles = listOf(
        TileSpec(HomeAction.Scan, Icons.Filled.DocumentScanner, R.string.tile_scan),
        TileSpec(HomeAction.ImportPdf, Icons.Filled.UploadFile, R.string.tile_import_pdf),
        TileSpec(HomeAction.ImportImages, Icons.Filled.Image, R.string.tile_import_images),
        TileSpec(HomeAction.Signature, Icons.Outlined.Draw, R.string.tile_signature),
        TileSpec(HomeAction.GoogleDoc, Icons.Filled.Article, R.string.tile_google_doc),
        TileSpec(HomeAction.Edit, Icons.Filled.Edit, R.string.tile_edit)
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        tiles.chunked(3).forEach { rowTiles ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowTiles.forEach { tile ->
                    Tile(
                        icon = tile.icon,
                        label = stringResource(tile.labelRes),
                        onClick = { onAction(tile.action) },
                        enabled = !(isProcessing && tile.action == HomeAction.Scan),
                        modifier = Modifier.weight(1f)
                    )
                }
                // Pad a short final row so tiles don't stretch across the width.
                repeat(3 - rowTiles.size) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Tile(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val tint = MaterialTheme.colorScheme.primary
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(30.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp)
        )
    }
}

// ----- Scan row with menu -----

@Composable
private fun ScanRow(
    scan: PdfStorage.SavedPdf,
    onOpen: () -> Unit,
    onGoogleDoc: () -> Unit,
    onEdit: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scan.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatSubtitle(scan),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null)
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.open)) },
                    onClick = { menuOpen = false; onOpen() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.ocr_recognize)) },
                    onClick = { menuOpen = false; onGoogleDoc() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.edit_pdf)) },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onEdit() }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.rename)) },
                    leadingIcon = {
                        Icon(Icons.Filled.DriveFileRenameOutline, contentDescription = null)
                    },
                    onClick = { menuOpen = false; renameOpen = true }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; deleteOpen = true }
                )
            }
        }
    }

    if (renameOpen) {
        RenameDialog(
            currentName = scan.baseName,
            onConfirm = { newName ->
                renameOpen = false
                onRename(newName)
            },
            onDismiss = { renameOpen = false }
        )
    }
    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text(stringResource(R.string.delete_title)) },
            text = {
                Text(stringResource(R.string.delete_body, scan.displayName))
            },
            confirmButton = {
                TextButton(onClick = { deleteOpen = false; onDelete() }) {
                    Text(stringResource(R.string.delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun RenameDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rename)) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

// ----- Formatting helpers -----

private fun formatSubtitle(scan: PdfStorage.SavedPdf): String {
    val datePart = if (scan.dateAddedMillis > 0) {
        SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date(scan.dateAddedMillis))
    } else ""
    val sizePart = if (scan.sizeBytes > 0) humanBytes(scan.sizeBytes) else ""
    return listOf(datePart, sizePart).filter { it.isNotBlank() }.joinToString("  ")
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    val mb = kb / 1024.0
    return String.format(Locale.US, "%.1f MB", mb)
}
