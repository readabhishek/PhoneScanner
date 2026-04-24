@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.readabhishek.phonescanner

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.max

/**
 * Full-screen drawing canvas for capturing a signature. Strokes are kept
 * as lists of points; Save rasterizes to a Bitmap which the caller then
 * embeds in a PDF via [FileTools.buildSignaturePdf].
 */
@Composable
fun SignatureScreen(
    onBack: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    BackHandler(onBack = onBack)

    // Each stroke = list of offsets in canvas coordinates.
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var canvasSize by remember { mutableStateOf(IntSize(1, 1)) }
    val density = LocalDensity.current
    val strokeWidthPx = with(density) { 3.dp.toPx() }
    val inkColor = MaterialTheme.colorScheme.onSurface
    val canvasBg = MaterialTheme.colorScheme.surface
    val border = MaterialTheme.colorScheme.outline

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.signature_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.signature_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(canvasBg)
                    .border(1.dp, border, RoundedCornerShape(16.dp))
            ) {
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    strokes.add(mutableListOf(offset))
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val last = strokes.lastOrNull() ?: return@detectDragGestures
                                    last.add(change.position)
                                    // Force recomposition by replacing the reference
                                    strokes[strokes.lastIndex] = last
                                }
                            )
                        }
                ) {
                    canvasSize = IntSize(size.width.toInt(), size.height.toInt())
                    strokes.forEach { points ->
                        if (points.size < 2) return@forEach
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            for (i in 1 until points.size) {
                                lineTo(points[i].x, points[i].y)
                            }
                        }
                        drawPath(
                            path = path,
                            color = inkColor,
                            style = Stroke(
                                width = strokeWidthPx,
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = { strokes.clear() },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Clear, contentDescription = null)
                    Text(
                        text = stringResource(R.string.signature_clear),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Button(
                    onClick = {
                        if (strokes.isEmpty()) return@Button
                        val bitmap = rasterizeStrokes(
                            strokes = strokes,
                            width = max(canvasSize.width, 1),
                            height = max(canvasSize.height, 1),
                            strokeWidthPx = strokeWidthPx
                        )
                        onSave(bitmap)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Text(
                        text = stringResource(R.string.signature_save),
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * Rasterize captured strokes into an ARGB_8888 Bitmap with a transparent
 * background — callers composite it onto a PDF page.
 */
private fun rasterizeStrokes(
    strokes: List<List<Offset>>,
    width: Int,
    height: Int,
    strokeWidthPx: Float
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.WHITE)
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = strokeWidthPx
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { points ->
        if (points.size < 2) return@forEach
        val path = android.graphics.Path().apply {
            moveTo(points.first().x, points.first().y)
            for (i in 1 until points.size) {
                lineTo(points[i].x, points[i].y)
            }
        }
        canvas.drawPath(path, paint)
    }
    return bitmap
}
