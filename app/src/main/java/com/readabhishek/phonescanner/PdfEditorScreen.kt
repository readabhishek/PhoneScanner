@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.readabhishek.phonescanner

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Rectangle
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-screen PDF content editor. Loads each page of [source] as a bitmap
 * and lets the user place text, signatures, freehand ink (pen + highlighter),
 * shapes (rect + oval), and per-page crops. Signature bitmaps arrive
 * asynchronously from the parent via [pendingSignature]; the editor consumes
 * each one and then calls [onSignatureConsumed] so the parent can clear it
 * and deliver a subsequent pick.
 */
@Composable
fun PdfEditorScreen(
    source: PdfStorage.SavedPdf,
    pageBitmaps: List<Bitmap>,
    isLoading: Boolean,
    pendingSignature: Bitmap?,
    onLoadRequest: () -> Unit,
    onPickSignature: () -> Unit,
    onSignatureConsumed: () -> Unit,
    onBack: () -> Unit,
    onSave: (List<FileTools.PdfOverlay>, Map<Int, FileTools.CropRect>) -> Unit
) {
    BackHandler(onBack = onBack)

    LaunchedEffect(source.uri) { onLoadRequest() }

    var pageIndex by remember { mutableStateOf(0) }
    val items = remember { mutableStateListOf<EditorItem>() }
    val crops = remember { mutableStateMapOf<Int, FileTools.CropRect>() }
    var nextId by remember { mutableStateOf(0L) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    var textDialogOpen by remember { mutableStateOf(false) }
    var pendingTextPosFrac by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    var tool by remember { mutableStateOf(EditorTool.Select) }
    var color by remember { mutableStateOf(PaletteColor.Black) }

    // Text tool defaults — applied to new Text overlays and mirrored from
    // the currently selected Text overlay when the user edits it.
    var textFontKind by remember { mutableStateOf(FileTools.FontKind.SANS) }
    var textSizeFrac by remember { mutableStateOf(0.03f) }

    // In-progress gesture state, cleared on pointer-up.
    val inProgressPoints = remember { mutableStateListOf<Offset>() }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragCurrent by remember { mutableStateOf<Offset?>(null) }

    // Consume an incoming signature bitmap: drop it onto the current page.
    LaunchedEffect(pendingSignature) {
        val bmp = pendingSignature ?: return@LaunchedEffect
        val page = pageBitmaps.getOrNull(pageIndex)
        val pageAspect = if (page != null) page.width.toFloat() / page.height else 0.707f
        val imgAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val wFrac = 0.35f
        val hFrac = (wFrac * pageAspect / imgAspect).coerceIn(0.05f, 0.5f)
        val id = nextId++
        items += EditorItem(
            id = id,
            overlay = FileTools.PdfOverlay.Image(
                pageIndex = pageIndex,
                image = bmp,
                xFrac = 0.1f,
                yFrac = 0.5f,
                widthFrac = wFrac,
                heightFrac = hFrac
            )
        )
        selectedId = id
        tool = EditorTool.Select
        onSignatureConsumed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.pdf_editor_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            if (items.isEmpty() && crops.isEmpty()) onBack()
                            else onSave(items.map { it.overlay }, crops.toMap())
                        }
                    ) {
                        Icon(
                            Icons.Filled.Check,
                            contentDescription = stringResource(R.string.save)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isLoading || pageBitmaps.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                val page = pageBitmaps.getOrNull(pageIndex) ?: pageBitmaps.first()
                val pageAspect = page.width.toFloat() / page.height.toFloat()

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(12.dp)
                        )
                ) {
                    val boxWDp = maxWidth.value
                    val pageDpW = boxWDp
                    val pageDpH = boxWDp / pageAspect

                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pageAspect)
                    )

                    // Gesture layer — page-level pointer handling per tool.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pageAspect)
                            .pointerInput(pageIndex, tool, color) {
                                when (tool) {
                                    EditorTool.Pen,
                                    EditorTool.Highlighter -> {
                                        detectDragGestures(
                                            onDragStart = { pos ->
                                                selectedId = null
                                                inProgressPoints.clear()
                                                inProgressPoints += pos
                                            },
                                            onDragEnd = {
                                                commitInk(
                                                    items = items,
                                                    nextIdProvider = { nextId++ },
                                                    pageIndex = pageIndex,
                                                    pageSizePx = Size(
                                                        size.width.toFloat(),
                                                        size.height.toFloat()
                                                    ),
                                                    points = inProgressPoints.toList(),
                                                    tool = tool,
                                                    color = color
                                                )
                                                inProgressPoints.clear()
                                            },
                                            onDragCancel = { inProgressPoints.clear() },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                inProgressPoints += change.position
                                            }
                                        )
                                    }
                                    EditorTool.Rectangle,
                                    EditorTool.Oval,
                                    EditorTool.Crop -> {
                                        detectDragGestures(
                                            onDragStart = { pos ->
                                                selectedId = null
                                                dragStart = pos
                                                dragCurrent = pos
                                            },
                                            onDragEnd = {
                                                val s = dragStart
                                                val c = dragCurrent
                                                if (s != null && c != null) {
                                                    commitRect(
                                                        items = items,
                                                        crops = crops,
                                                        nextIdProvider = { nextId++ },
                                                        pageIndex = pageIndex,
                                                        pageSizePx = Size(
                                                            size.width.toFloat(),
                                                            size.height.toFloat()
                                                        ),
                                                        start = s,
                                                        end = c,
                                                        tool = tool,
                                                        color = color
                                                    )
                                                }
                                                dragStart = null
                                                dragCurrent = null
                                            },
                                            onDragCancel = {
                                                dragStart = null
                                                dragCurrent = null
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                dragCurrent = change.position
                                            }
                                        )
                                    }
                                    EditorTool.Eraser -> {
                                        detectTapGestures(
                                            onTap = { pos ->
                                                val pw = size.width.toFloat()
                                                val ph = size.height.toFloat()
                                                val xf = pos.x / pw
                                                val yf = pos.y / ph
                                                val hitId = findHitOverlay(
                                                    items = items,
                                                    pageIndex = pageIndex,
                                                    xFrac = xf,
                                                    yFrac = yf,
                                                    pageSizePx = Size(pw, ph)
                                                )
                                                if (hitId != null) {
                                                    items.removeAll { it.id == hitId }
                                                    if (selectedId == hitId) selectedId = null
                                                } else if (crops[pageIndex] != null) {
                                                    val cr = crops[pageIndex]!!
                                                    if (xf in cr.xFrac..(cr.xFrac + cr.widthFrac) &&
                                                        yf in cr.yFrac..(cr.yFrac + cr.heightFrac)
                                                    ) {
                                                        crops.remove(pageIndex)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                    EditorTool.Text -> {
                                        detectTapGestures(
                                            onTap = { pos ->
                                                val pw = size.width.toFloat()
                                                val ph = size.height.toFloat()
                                                pendingTextPosFrac =
                                                    pos.x / pw to pos.y / ph
                                                textDialogOpen = true
                                            }
                                        )
                                    }
                                    EditorTool.Select,
                                    EditorTool.Signature -> {
                                        detectTapGestures(
                                            onTap = { selectedId = null }
                                        )
                                    }
                                }
                            }
                    )

                    // Persisted + in-progress strokes / shapes / crop outline.
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(pageAspect)
                    ) {
                        val pw = size.width
                        val ph = size.height

                        // Persisted ink and shapes for this page.
                        items.asSequence()
                            .filter { it.overlay.pageIndex == pageIndex }
                            .forEach { item ->
                                when (val ov = item.overlay) {
                                    is FileTools.PdfOverlay.Ink -> drawInkOverlay(ov, pw, ph)
                                    is FileTools.PdfOverlay.Shape -> drawShapeOverlay(ov, pw, ph)
                                    else -> Unit
                                }
                            }

                        // Persisted crop outline (dashed).
                        crops[pageIndex]?.let { cr ->
                            val stroke = Stroke(
                                width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(
                                    floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                                )
                            )
                            drawRect(
                                color = Color(0xFF1E88E5),
                                topLeft = Offset(cr.xFrac * pw, cr.yFrac * ph),
                                size = Size(cr.widthFrac * pw, cr.heightFrac * ph),
                                style = stroke
                            )
                        }

                        // In-progress pen / highlighter preview.
                        if (inProgressPoints.size >= 2 &&
                            (tool == EditorTool.Pen || tool == EditorTool.Highlighter)
                        ) {
                            val previewColor = if (tool == EditorTool.Highlighter) {
                                color.composeColor.copy(alpha = 0.38f)
                            } else color.composeColor
                            val previewWidth =
                                if (tool == EditorTool.Highlighter) 16.dp.toPx()
                                else 3.dp.toPx()
                            val path = Path().apply {
                                moveTo(inProgressPoints.first().x, inProgressPoints.first().y)
                                for (i in 1 until inProgressPoints.size) {
                                    lineTo(inProgressPoints[i].x, inProgressPoints[i].y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = previewColor,
                                style = Stroke(
                                    width = previewWidth,
                                    cap = StrokeCap.Round,
                                    join = StrokeJoin.Round
                                )
                            )
                        }

                        // In-progress rectangle / oval / crop preview.
                        val s = dragStart
                        val c = dragCurrent
                        if (s != null && c != null &&
                            (tool == EditorTool.Rectangle ||
                                tool == EditorTool.Oval ||
                                tool == EditorTool.Crop)
                        ) {
                            val rect = ComposeRect(
                                left = min(s.x, c.x),
                                top = min(s.y, c.y),
                                right = max(s.x, c.x),
                                bottom = max(s.y, c.y)
                            )
                            val stroke = when (tool) {
                                EditorTool.Crop -> Stroke(
                                    width = 2.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(
                                        floatArrayOf(8.dp.toPx(), 6.dp.toPx())
                                    )
                                )
                                else -> Stroke(width = 2.dp.toPx())
                            }
                            val previewColor = when (tool) {
                                EditorTool.Crop -> Color(0xFF1E88E5)
                                else -> color.composeColor
                            }
                            when (tool) {
                                EditorTool.Oval -> drawOval(
                                    color = previewColor,
                                    topLeft = rect.topLeft,
                                    size = rect.size,
                                    style = stroke
                                )
                                else -> drawRect(
                                    color = previewColor,
                                    topLeft = rect.topLeft,
                                    size = rect.size,
                                    style = stroke
                                )
                            }
                        }
                    }

                    // Draggable boxes for text + image overlays on the current page.
                    items.filter {
                        it.overlay.pageIndex == pageIndex &&
                            (it.overlay is FileTools.PdfOverlay.Text ||
                                it.overlay is FileTools.PdfOverlay.Image)
                    }.forEach { item ->
                        OverlayBox(
                            item = item,
                            pageDpW = pageDpW,
                            pageDpH = pageDpH,
                            isSelected = item.id == selectedId,
                            interactive = tool == EditorTool.Select,
                            onSelect = { selectedId = item.id },
                            onMove = { dxDp, dyDp ->
                                val idx = items.indexOfFirst { it.id == item.id }
                                if (idx >= 0) {
                                    val cur = items[idx].overlay
                                    val updated = when (cur) {
                                        is FileTools.PdfOverlay.Text -> {
                                            val newX = (cur.xFrac + dxDp / pageDpW)
                                                .coerceIn(0f, (1f - cur.widthFrac).coerceAtLeast(0f))
                                            val newY = (cur.yFrac + dyDp / pageDpH)
                                                .coerceIn(0f, (1f - cur.heightFrac).coerceAtLeast(0f))
                                            cur.copy(xFrac = newX, yFrac = newY)
                                        }
                                        is FileTools.PdfOverlay.Image -> {
                                            val newX = (cur.xFrac + dxDp / pageDpW)
                                                .coerceIn(0f, (1f - cur.widthFrac).coerceAtLeast(0f))
                                            val newY = (cur.yFrac + dyDp / pageDpH)
                                                .coerceIn(0f, (1f - cur.heightFrac).coerceAtLeast(0f))
                                            cur.copy(xFrac = newX, yFrac = newY)
                                        }
                                        else -> cur
                                    }
                                    items[idx] = items[idx].copy(overlay = updated)
                                }
                            },
                            onResize = { dxDp, dyDp ->
                                val idx = items.indexOfFirst { it.id == item.id }
                                if (idx >= 0) {
                                    val cur = items[idx].overlay
                                    val updated = when (cur) {
                                        is FileTools.PdfOverlay.Image -> {
                                            // Drive new width from horizontal drag and
                                            // preserve the image's aspect ratio so the
                                            // signature doesn't skew.
                                            val maxW = (1f - cur.xFrac).coerceAtLeast(0.05f)
                                            val newW = (cur.widthFrac + dxDp / pageDpW)
                                                .coerceIn(0.05f, maxW)
                                            val aspect =
                                                if (cur.widthFrac > 0f)
                                                    cur.heightFrac / cur.widthFrac
                                                else 1f
                                            val maxH = (1f - cur.yFrac).coerceAtLeast(0.02f)
                                            val newH = (newW * aspect).coerceIn(0.02f, maxH)
                                            cur.copy(widthFrac = newW, heightFrac = newH)
                                        }
                                        is FileTools.PdfOverlay.Text -> {
                                            // Font size follows vertical drag; width is
                                            // recomputed from the character count so the
                                            // bounding box continues to fit the string.
                                            val newH = (cur.heightFrac + dyDp / pageDpH)
                                                .coerceIn(0.015f, 0.2f)
                                            val maxW = (1f - cur.xFrac).coerceAtLeast(0.05f)
                                            val newW =
                                                (cur.text.length * newH * 0.55f)
                                                    .coerceIn(0.05f, maxW)
                                            cur.copy(heightFrac = newH, widthFrac = newW)
                                        }
                                        else -> cur
                                    }
                                    items[idx] = items[idx].copy(overlay = updated)
                                }
                            }
                        )
                    }
                }

                // Tool row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolChip(tool, EditorTool.Select, Icons.Filled.NearMe, R.string.tool_select) { tool = it }
                    ToolChip(tool, EditorTool.Pen, Icons.Filled.Create, R.string.tool_pen) { tool = it }
                    ToolChip(tool, EditorTool.Highlighter, Icons.Filled.Brush, R.string.tool_highlighter) { tool = it }
                    ToolChip(tool, EditorTool.Eraser, Icons.Filled.Delete, R.string.tool_eraser) { tool = it }
                    ToolChip(tool, EditorTool.Rectangle, Icons.Filled.Rectangle, R.string.tool_rectangle) { tool = it }
                    ToolChip(tool, EditorTool.Oval, Icons.Filled.RadioButtonUnchecked, R.string.tool_oval) { tool = it }
                    ToolChip(tool, EditorTool.Crop, Icons.Filled.Crop, R.string.tool_crop) { tool = it }
                    ToolChip(tool, EditorTool.Text, Icons.Filled.TextFields, R.string.tool_text) { tool = it }
                    FilterChip(
                        selected = false,
                        onClick = onPickSignature,
                        label = { Text(stringResource(R.string.tool_signature)) },
                        leadingIcon = { Icon(Icons.Filled.Gesture, null, modifier = Modifier.size(18.dp)) }
                    )
                }

                // Currently-selected Text overlay (if any) — used to rebind
                // the color / font / size controls to edit-in-place.
                val selectedTextIdx = items.indexOfFirst {
                    it.id == selectedId && it.overlay is FileTools.PdfOverlay.Text
                }
                val selectedText = selectedTextIdx
                    .takeIf { it >= 0 }
                    ?.let { items[it].overlay as FileTools.PdfOverlay.Text }

                // Color palette — meaningful for tools that use color, and
                // also for retinting a selected Text overlay.
                val colorEnabled =
                    tool == EditorTool.Pen ||
                        tool == EditorTool.Highlighter ||
                        tool == EditorTool.Rectangle ||
                        tool == EditorTool.Oval ||
                        tool == EditorTool.Text ||
                        selectedText != null
                val activeSwatch = selectedText?.let { t ->
                    PaletteColor.values().firstOrNull { it.argb == t.colorArgb }
                } ?: color
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PaletteColor.values().forEach { sw ->
                        ColorSwatch(
                            swatch = sw,
                            selected = sw == activeSwatch,
                            enabled = colorEnabled,
                            onClick = {
                                color = sw
                                if (selectedText != null && selectedTextIdx >= 0) {
                                    items[selectedTextIdx] = items[selectedTextIdx].copy(
                                        overlay = selectedText.copy(colorArgb = sw.argb)
                                    )
                                }
                            }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (selectedId != null && tool == EditorTool.Select) {
                        OutlinedButton(
                            onClick = {
                                items.removeAll { it.id == selectedId }
                                selectedId = null
                            }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.delete))
                        }
                    } else if (tool == EditorTool.Crop && crops[pageIndex] != null) {
                        OutlinedButton(onClick = { crops.remove(pageIndex) }) {
                            Icon(Icons.Filled.CropFree, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.clear_crop))
                        }
                    }
                }

                // Text formatting row — shown when the Text tool is active or
                // a Text overlay is selected. Chooses font kind and size.
                if (tool == EditorTool.Text || selectedText != null) {
                    val activeFontKind = selectedText?.fontKind ?: textFontKind
                    val activeSizeFrac = selectedText?.heightFrac ?: textSizeFrac
                    val applyFontKind: (FileTools.FontKind) -> Unit = { kind ->
                        if (selectedText != null && selectedTextIdx >= 0) {
                            items[selectedTextIdx] = items[selectedTextIdx].copy(
                                overlay = selectedText.copy(fontKind = kind)
                            )
                        } else {
                            textFontKind = kind
                        }
                    }
                    val applySize: (Float) -> Unit = { newSize ->
                        val clamped = newSize.coerceIn(0.015f, 0.2f)
                        if (selectedText != null && selectedTextIdx >= 0) {
                            val newW = (selectedText.text.length * clamped * 0.55f)
                                .coerceIn(0.05f, (1f - selectedText.xFrac).coerceAtLeast(0.05f))
                            items[selectedTextIdx] = items[selectedTextIdx].copy(
                                overlay = selectedText.copy(
                                    heightFrac = clamped,
                                    widthFrac = newW
                                )
                            )
                        } else {
                            textSizeFrac = clamped
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FontChip(
                            label = stringResource(R.string.font_sans),
                            selected = activeFontKind == FileTools.FontKind.SANS,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                            onClick = { applyFontKind(FileTools.FontKind.SANS) }
                        )
                        FontChip(
                            label = stringResource(R.string.font_serif),
                            selected = activeFontKind == FileTools.FontKind.SERIF,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
                            onClick = { applyFontKind(FileTools.FontKind.SERIF) }
                        )
                        FontChip(
                            label = stringResource(R.string.font_mono),
                            selected = activeFontKind == FileTools.FontKind.MONOSPACE,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            onClick = { applyFontKind(FileTools.FontKind.MONOSPACE) }
                        )
                        Spacer(Modifier.size(8.dp))
                        IconButton(
                            onClick = { applySize(activeSizeFrac - 0.005f) }
                        ) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.font_size_smaller)) }
                        Text(
                            text = "${(activeSizeFrac * 1000f).roundToInt()}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        IconButton(
                            onClick = { applySize(activeSizeFrac + 0.005f) }
                        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.font_size_bigger)) }
                    }
                }

                // Page nav
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { pageIndex = (pageIndex - 1).coerceAtLeast(0) },
                        enabled = pageIndex > 0
                    ) { Icon(Icons.Filled.ChevronLeft, contentDescription = null) }
                    Text(
                        text = "${pageIndex + 1} / ${pageBitmaps.size}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    IconButton(
                        onClick = {
                            pageIndex = (pageIndex + 1).coerceAtMost(pageBitmaps.size - 1)
                        },
                        enabled = pageIndex < pageBitmaps.size - 1
                    ) { Icon(Icons.Filled.ChevronRight, contentDescription = null) }
                }
            }
        }
    }

    if (textDialogOpen) {
        AddTextDialog(
            onConfirm = { entered ->
                textDialogOpen = false
                if (entered.isBlank()) {
                    pendingTextPosFrac = null
                    return@AddTextDialog
                }
                val heightFrac = textSizeFrac
                val widthFrac = (entered.length * heightFrac * 0.55f)
                    .coerceIn(0.05f, 0.9f)
                val pos = pendingTextPosFrac
                val x = pos?.first?.coerceIn(0f, (1f - widthFrac).coerceAtLeast(0f)) ?: 0.1f
                val y = pos?.second?.coerceIn(0f, (1f - heightFrac).coerceAtLeast(0f)) ?: 0.15f
                val id = nextId++
                items += EditorItem(
                    id = id,
                    overlay = FileTools.PdfOverlay.Text(
                        pageIndex = pageIndex,
                        text = entered,
                        xFrac = x,
                        yFrac = y,
                        widthFrac = widthFrac,
                        heightFrac = heightFrac,
                        colorArgb = color.argb,
                        fontKind = textFontKind
                    )
                )
                selectedId = id
                pendingTextPosFrac = null
                tool = EditorTool.Select
            },
            onDismiss = {
                textDialogOpen = false
                pendingTextPosFrac = null
            }
        )
    }
}

// ======================================================================
// Editor-local model
// ======================================================================

private enum class EditorTool { Select, Pen, Highlighter, Eraser, Rectangle, Oval, Crop, Text, Signature }

private enum class PaletteColor(val argb: Int, val composeColor: Color) {
    Black(0xFF000000.toInt(), Color(0xFF000000)),
    Red(0xFFE53935.toInt(), Color(0xFFE53935)),
    Blue(0xFF1E88E5.toInt(), Color(0xFF1E88E5)),
    Green(0xFF43A047.toInt(), Color(0xFF43A047)),
    Yellow(0xFFFDD835.toInt(), Color(0xFFFDD835)),
    Purple(0xFF8E24AA.toInt(), Color(0xFF8E24AA))
}

private data class EditorItem(
    val id: Long,
    val overlay: FileTools.PdfOverlay
)

// ======================================================================
// Commit helpers — convert in-progress gestures to overlays
// ======================================================================

private fun commitInk(
    items: androidx.compose.runtime.snapshots.SnapshotStateList<EditorItem>,
    nextIdProvider: () -> Long,
    pageIndex: Int,
    pageSizePx: Size,
    points: List<Offset>,
    tool: EditorTool,
    color: PaletteColor
) {
    if (points.size < 2 || pageSizePx.width <= 0f || pageSizePx.height <= 0f) return
    val frac = points.map { (it.x / pageSizePx.width) to (it.y / pageSizePx.height) }
    val (colorArgb, strokeWidthFrac) = when (tool) {
        EditorTool.Highlighter -> {
            val a = (color.argb and 0x00FFFFFF) or 0x60000000
            a to 0.040f
        }
        else -> color.argb to 0.004f
    }
    val id = nextIdProvider()
    items += EditorItem(
        id = id,
        overlay = FileTools.PdfOverlay.Ink(
            pageIndex = pageIndex,
            points = frac,
            colorArgb = colorArgb,
            strokeWidthFrac = strokeWidthFrac
        )
    )
}

private fun commitRect(
    items: androidx.compose.runtime.snapshots.SnapshotStateList<EditorItem>,
    crops: androidx.compose.runtime.snapshots.SnapshotStateMap<Int, FileTools.CropRect>,
    nextIdProvider: () -> Long,
    pageIndex: Int,
    pageSizePx: Size,
    start: Offset,
    end: Offset,
    tool: EditorTool,
    color: PaletteColor
) {
    if (pageSizePx.width <= 0f || pageSizePx.height <= 0f) return
    val pw = pageSizePx.width
    val ph = pageSizePx.height
    val leftF = min(start.x, end.x) / pw
    val topF = min(start.y, end.y) / ph
    val widthF = abs(end.x - start.x) / pw
    val heightF = abs(end.y - start.y) / ph
    if (widthF < 0.01f || heightF < 0.01f) return
    val cLeft = leftF.coerceIn(0f, 1f)
    val cTop = topF.coerceIn(0f, 1f)
    val cW = widthF.coerceAtMost(1f - cLeft)
    val cH = heightF.coerceAtMost(1f - cTop)
    when (tool) {
        EditorTool.Crop -> {
            crops[pageIndex] = FileTools.CropRect(
                pageIndex = pageIndex,
                xFrac = cLeft,
                yFrac = cTop,
                widthFrac = cW,
                heightFrac = cH
            )
        }
        EditorTool.Rectangle, EditorTool.Oval -> {
            val kind = if (tool == EditorTool.Oval)
                FileTools.PdfOverlay.ShapeKind.OVAL
            else
                FileTools.PdfOverlay.ShapeKind.RECT
            items += EditorItem(
                id = nextIdProvider(),
                overlay = FileTools.PdfOverlay.Shape(
                    pageIndex = pageIndex,
                    kind = kind,
                    xFrac = cLeft,
                    yFrac = cTop,
                    widthFrac = cW,
                    heightFrac = cH,
                    colorArgb = color.argb,
                    strokeWidthFrac = 0.004f
                )
            )
        }
        else -> Unit
    }
}

/** Topmost (by placement order, last-wins) overlay containing the tap point. */
private fun findHitOverlay(
    items: androidx.compose.runtime.snapshots.SnapshotStateList<EditorItem>,
    pageIndex: Int,
    xFrac: Float,
    yFrac: Float,
    pageSizePx: Size
): Long? {
    val pageItems = items.filter { it.overlay.pageIndex == pageIndex }
    for (i in pageItems.indices.reversed()) {
        val it = pageItems[i]
        if (hit(it.overlay, xFrac, yFrac, pageSizePx)) return it.id
    }
    return null
}

private fun hit(
    ov: FileTools.PdfOverlay,
    xFrac: Float,
    yFrac: Float,
    pageSizePx: Size
): Boolean {
    return when (ov) {
        is FileTools.PdfOverlay.Text -> inRect(xFrac, yFrac, ov.xFrac, ov.yFrac, ov.widthFrac, ov.heightFrac)
        is FileTools.PdfOverlay.Image -> inRect(xFrac, yFrac, ov.xFrac, ov.yFrac, ov.widthFrac, ov.heightFrac)
        is FileTools.PdfOverlay.Shape -> inRect(xFrac, yFrac, ov.xFrac, ov.yFrac, ov.widthFrac, ov.heightFrac)
        is FileTools.PdfOverlay.Ink -> {
            // Hit if the tap is within ~stroke-width of any segment endpoint.
            val thresholdFrac = (ov.strokeWidthFrac * 2f).coerceAtLeast(0.015f)
            val pw = pageSizePx.width
            val ph = pageSizePx.height
            val tx = xFrac * pw
            val ty = yFrac * ph
            ov.points.any { (fx, fy) ->
                hypot(fx * pw - tx, fy * ph - ty) <= thresholdFrac * pw
            }
        }
    }
}

private fun inRect(
    xFrac: Float,
    yFrac: Float,
    rx: Float,
    ry: Float,
    rw: Float,
    rh: Float
): Boolean = xFrac in rx..(rx + rw) && yFrac in ry..(ry + rh)

// ======================================================================
// Drawing helpers for the Compose preview canvas
// ======================================================================

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInkOverlay(
    ov: FileTools.PdfOverlay.Ink,
    pw: Float,
    ph: Float
) {
    if (ov.points.size < 2) return
    val path = Path().apply {
        moveTo(ov.points.first().first * pw, ov.points.first().second * ph)
        for (i in 1 until ov.points.size) {
            val p = ov.points[i]
            lineTo(p.first * pw, p.second * ph)
        }
    }
    val argb = ov.colorArgb
    val a = ((argb ushr 24) and 0xFF) / 255f
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    drawPath(
        path = path,
        color = Color(r, g, b, a),
        style = Stroke(
            width = (ov.strokeWidthFrac * pw).coerceAtLeast(1f),
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawShapeOverlay(
    ov: FileTools.PdfOverlay.Shape,
    pw: Float,
    ph: Float
) {
    val argb = ov.colorArgb
    val a = ((argb ushr 24) and 0xFF) / 255f
    val r = ((argb ushr 16) and 0xFF) / 255f
    val g = ((argb ushr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val topLeft = Offset(ov.xFrac * pw, ov.yFrac * ph)
    val rectSize = Size(ov.widthFrac * pw, ov.heightFrac * ph)
    val stroke = Stroke(width = (ov.strokeWidthFrac * pw).coerceAtLeast(1f))
    when (ov.kind) {
        FileTools.PdfOverlay.ShapeKind.RECT ->
            drawRect(Color(r, g, b, a), topLeft = topLeft, size = rectSize, style = stroke)
        FileTools.PdfOverlay.ShapeKind.OVAL ->
            drawOval(Color(r, g, b, a), topLeft = topLeft, size = rectSize, style = stroke)
    }
}

// ======================================================================
// Small composables
// ======================================================================

@Composable
private fun ToolChip(
    current: EditorTool,
    this_: EditorTool,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onPick: (EditorTool) -> Unit
) {
    FilterChip(
        selected = current == this_,
        onClick = { onPick(this_) },
        label = { Text(stringResource(labelRes)) },
        leadingIcon = { Icon(icon, null, modifier = Modifier.size(18.dp)) }
    )
}

@Composable
private fun FontChip(
    label: String,
    selected: Boolean,
    fontFamily: androidx.compose.ui.text.font.FontFamily,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                fontFamily = fontFamily
            )
        }
    )
}

@Composable
private fun ColorSwatch(
    swatch: PaletteColor,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val borderColor =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (selected) 3.dp else 1.dp
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(if (enabled) swatch.composeColor else swatch.composeColor.copy(alpha = 0.35f))
            .border(borderWidth, borderColor, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
    )
}

@Composable
private fun OverlayBox(
    item: EditorItem,
    pageDpW: Float,
    pageDpH: Float,
    isSelected: Boolean,
    interactive: Boolean,
    onSelect: () -> Unit,
    onMove: (dxDp: Float, dyDp: Float) -> Unit,
    onResize: (dxDp: Float, dyDp: Float) -> Unit
) {
    val density = LocalDensity.current
    val ov = item.overlay
    // This box only renders text / image overlays — callers filter.
    val (xFrac, yFrac, wFrac, hFrac) = when (ov) {
        is FileTools.PdfOverlay.Text -> OverlayGeom(ov.xFrac, ov.yFrac, ov.widthFrac, ov.heightFrac)
        is FileTools.PdfOverlay.Image -> OverlayGeom(ov.xFrac, ov.yFrac, ov.widthFrac, ov.heightFrac)
        else -> return
    }
    val xDp = xFrac * pageDpW
    val yDp = yFrac * pageDpH
    val wDp = (wFrac * pageDpW).coerceAtLeast(12f)
    val hDp = (hFrac * pageDpH).coerceAtLeast(12f)

    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
    val borderWidth = if (isSelected) 2.dp else 1.dp

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    (xDp * density.density).roundToInt(),
                    (yDp * density.density).roundToInt()
                )
            }
            .requiredSize(wDp.dp, hDp.dp)
    ) {
        // Inner draggable/selectable area that carries the border and content.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
                .then(
                    if (interactive) {
                        Modifier
                            .clickable { onSelect() }
                            .pointerInput(item.id) {
                                detectDragGestures(
                                    onDragStart = { onSelect() },
                                    onDrag = { change, drag ->
                                        change.consume()
                                        onMove(drag.x / density.density, drag.y / density.density)
                                    }
                                )
                            }
                    } else Modifier
                )
        ) {
            when (ov) {
                is FileTools.PdfOverlay.Text -> {
                    val tColor = Color(
                        ((ov.colorArgb ushr 16) and 0xFF) / 255f,
                        ((ov.colorArgb ushr 8) and 0xFF) / 255f,
                        (ov.colorArgb and 0xFF) / 255f,
                        ((ov.colorArgb ushr 24) and 0xFF) / 255f
                    )
                    val family = when (ov.fontKind) {
                        FileTools.FontKind.SERIF -> androidx.compose.ui.text.font.FontFamily.Serif
                        FileTools.FontKind.MONOSPACE -> androidx.compose.ui.text.font.FontFamily.Monospace
                        FileTools.FontKind.SANS -> androidx.compose.ui.text.font.FontFamily.SansSerif
                    }
                    Text(
                        text = ov.text,
                        color = tColor,
                        fontSize = hDp.sp,
                        fontFamily = family,
                        maxLines = 1,
                        softWrap = false
                    )
                }
                is FileTools.PdfOverlay.Image -> {
                    Image(
                        bitmap = ov.image.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                else -> Unit
            }
        }

        // Resize handle — bottom-right corner, visible only when selected
        // so it doesn't obscure the overlay the rest of the time.
        if (interactive && isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .border(1.dp, Color.White, CircleShape)
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { onSelect() },
                            onDrag = { change, drag ->
                                change.consume()
                                onResize(
                                    drag.x / density.density,
                                    drag.y / density.density
                                )
                            }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.OpenInFull,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

private data class OverlayGeom(val x: Float, val y: Float, val w: Float, val h: Float)

@Composable
private fun AddTextDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_editor_add_text)) },
        text = {
            TextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true
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
