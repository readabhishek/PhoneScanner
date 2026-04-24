package com.readabhishek.phonescanner

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Helpers for importing / building / OCRing PDFs from sources outside the
 * document scanner: picked PDFs, picked images, and existing saved scans
 * that the user wants to convert to a Google Doc.
 */
object FileTools {

    // ----------------------------------------------------------------------
    // Import an existing PDF picked via SAF
    // ----------------------------------------------------------------------

    fun importPickedPdf(context: Context, pickedUri: Uri): PdfStorage.SavedPdf {
        val originalName = queryDisplayName(context.contentResolver, pickedUri)
        return PdfStorage.importPdf(context, pickedUri, originalName)
    }

    /**
     * Wrap a freshly-picked PDF uri in a [PdfStorage.SavedPdf] so the
     * editor can read it without first copying it into the PhoneScanner
     * folder. The editor's save step writes a brand-new PDF into our
     * folder, so the original picked file is left untouched.
     */
    fun savedPdfFromPickedUri(context: Context, pickedUri: Uri): PdfStorage.SavedPdf {
        val name = queryDisplayName(context.contentResolver, pickedUri) ?: "picked.pdf"
        val cleaned = name.substringBeforeLast('.')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(60)
            .ifBlank { "picked" }
        return PdfStorage.SavedPdf(
            uri = pickedUri,
            displayName = name,
            relativePath = "",
            sizeBytes = 0L,
            dateAddedMillis = 0L,
            baseName = cleaned
        )
    }

    // ----------------------------------------------------------------------
    // Build a PDF out of picked images (one image per page)
    // ----------------------------------------------------------------------

    /**
     * A4 @ 72dpi in points: 595 x 842.
     */
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val PAGE_MARGIN = 24

    fun buildPdfFromImages(context: Context, imageUris: List<Uri>): PdfStorage.SavedPdf {
        require(imageUris.isNotEmpty()) { "No images to import" }
        val doc = PdfDocument()
        try {
            imageUris.forEachIndexed { index, uri ->
                val bitmap = decodeBitmap(context.contentResolver, uri)
                    ?: return@forEachIndexed
                val pageInfo = PdfDocument.PageInfo
                    .Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1)
                    .create()
                val page = doc.startPage(pageInfo)
                drawBitmapCentered(page.canvas, bitmap)
                doc.finishPage(page)
                bitmap.recycle()
            }
            val bytes = ByteArrayOutputStream().use { bos ->
                doc.writeTo(bos)
                bos.toByteArray()
            }
            val baseName = "Images_" + timestampForBaseName()
            return PdfStorage.writePdfBytes(context, baseName, bytes)
        } finally {
            doc.close()
        }
    }

    private fun decodeBitmap(resolver: ContentResolver, uri: Uri): Bitmap? {
        return try {
            resolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun drawBitmapCentered(canvas: Canvas, bitmap: Bitmap) {
        // Fit the bitmap within page minus margins, preserving aspect ratio.
        val maxW = PAGE_WIDTH - 2 * PAGE_MARGIN
        val maxH = PAGE_HEIGHT - 2 * PAGE_MARGIN
        val scale = minOf(
            maxW.toFloat() / bitmap.width,
            maxH.toFloat() / bitmap.height
        ).coerceAtMost(1f)
        val drawW = bitmap.width * scale
        val drawH = bitmap.height * scale
        val left = (PAGE_WIDTH - drawW) / 2f
        val top = (PAGE_HEIGHT - drawH) / 2f
        val dst = RectF(left, top, left + drawW, top + drawH)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(bitmap, null, dst, paint)
    }

    // ----------------------------------------------------------------------
    // Single-page PDF from a signature bitmap
    // ----------------------------------------------------------------------

    fun buildSignaturePdf(
        context: Context,
        signature: Bitmap,
        title: String
    ): PdfStorage.SavedPdf {
        val doc = PdfDocument()
        try {
            val pageInfo = PdfDocument.PageInfo
                .Builder(PAGE_WIDTH, PAGE_HEIGHT, 1)
                .create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            // White page is implicit. Draw a thin caption on top.
            val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF334155.toInt()
                textSize = 14f
                isFakeBoldText = false
            }
            canvas.drawText(
                title,
                PAGE_MARGIN.toFloat(),
                (PAGE_MARGIN + 18).toFloat(),
                captionPaint
            )
            drawBitmapCentered(canvas, signature)
            doc.finishPage(page)
            val bytes = ByteArrayOutputStream().use { bos ->
                doc.writeTo(bos)
                bos.toByteArray()
            }
            val baseName = "Signature_" + timestampForBaseName()
            return PdfStorage.writePdfBytes(context, baseName, bytes)
        } finally {
            doc.close()
        }
    }

    // ----------------------------------------------------------------------
    // PDF → OCR text (for "Save as Google Doc" on an existing PDF)
    // ----------------------------------------------------------------------

    suspend fun extractTextFromPdf(context: Context, pdfUri: Uri): String {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(pdfUri, "r")
            ?: error("Could not open PDF")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val builder = StringBuilder()
        // PdfRenderer did not implement AutoCloseable until API 35, so we
        // can't use `.use { }` here on our compileSdk 34. Construct inside
        // try so a throwing ctor still lets `finally` close pfd.
        var renderer: PdfRenderer? = null
        try {
            val r = PdfRenderer(pfd).also { renderer = it }
            val total = r.pageCount
            for (index in 0 until total) {
                val page = r.openPage(index)
                try {
                    // Render at ~200dpi for legible OCR.
                    val targetDpi = 200
                    val scale = targetDpi / 72f
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(
                        width, height, Bitmap.Config.ARGB_8888
                    )
                    // White background so transparent PDFs still OCR well.
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(
                        bitmap,
                        Rect(0, 0, width, height),
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    val text = recognizeBitmap(recognizer, bitmap)
                    bitmap.recycle()
                    if (text.isNotBlank()) {
                        if (builder.isNotEmpty()) builder.append("\n\n")
                        builder.append(text.trim())
                    }
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer?.close()
            recognizer.close()
            // PdfRenderer.close() already closes the owned file descriptor,
            // but closing an already-closed ParcelFileDescriptor is a no-op.
            runCatching { pfd.close() }
        }
        return builder.toString()
    }

    private suspend fun recognizeBitmap(
        recognizer: com.google.mlkit.vision.text.TextRecognizer,
        bitmap: Bitmap
    ): String = suspendCancellableCoroutine { cont ->
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
            .addOnFailureListener { e -> cont.resumeWithException(e) }
    }

    // ----------------------------------------------------------------------
    // URI metadata helpers
    // ----------------------------------------------------------------------

    private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    private fun timestampForBaseName(): String =
        java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date())

    // ----------------------------------------------------------------------
    // PDF content editor: render pages + composite overlays back into a PDF
    // ----------------------------------------------------------------------

    /**
     * One placed item on top of a PDF page. Positions/sizes are stored as
     * fractions of the original page (0..1) so overlays are resolution-
     * independent across preview and final-render steps, and survive cropping
     * (a crop can shift the visible range but the underlying fractions stay
     * valid). Ink stroke width is also stored as a fraction of page width so
     * the stroke stays visually consistent after rasterization.
     */
    sealed class PdfOverlay {
        abstract val pageIndex: Int

        data class Text(
            override val pageIndex: Int,
            val text: String,
            val xFrac: Float,
            val yFrac: Float,
            val widthFrac: Float,
            val heightFrac: Float,
            val colorArgb: Int,
            val fontKind: FontKind = FontKind.SANS
        ) : PdfOverlay()

        data class Image(
            override val pageIndex: Int,
            val image: Bitmap,
            val xFrac: Float,
            val yFrac: Float,
            val widthFrac: Float,
            val heightFrac: Float
        ) : PdfOverlay()

        /**
         * Freehand stroke (pen or highlighter). For highlighter, the caller
         * should apply a low-alpha [colorArgb] and larger [strokeWidthFrac].
         */
        data class Ink(
            override val pageIndex: Int,
            val points: List<Pair<Float, Float>>,
            val colorArgb: Int,
            val strokeWidthFrac: Float
        ) : PdfOverlay()

        data class Shape(
            override val pageIndex: Int,
            val kind: ShapeKind,
            val xFrac: Float,
            val yFrac: Float,
            val widthFrac: Float,
            val heightFrac: Float,
            val colorArgb: Int,
            val strokeWidthFrac: Float
        ) : PdfOverlay()

        enum class ShapeKind { RECT, OVAL }
    }

    /**
     * Typeface family for rendered text. Kept intentionally small — we can't
     * reliably detect the exact font used in an existing PDF, but offering
     * Sans / Serif / Monospace covers the common "match the document" cases.
     */
    enum class FontKind { SANS, SERIF, MONOSPACE }

    /** Per-page crop: visible rectangle on the original page, in fractions. */
    data class CropRect(
        val pageIndex: Int,
        val xFrac: Float,
        val yFrac: Float,
        val widthFrac: Float,
        val heightFrac: Float
    )

    /**
     * Render every page of [pdfUri] into a list of ARGB_8888 Bitmaps at
     * [targetDpi]. Used by the editor to show each page as a backdrop
     * the user places overlays on.
     */
    fun renderPdfPages(
        context: Context,
        pdfUri: Uri,
        targetDpi: Int = 150
    ): List<Bitmap> {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(pdfUri, "r")
            ?: error("Could not open PDF")
        val pages = mutableListOf<Bitmap>()
        // PdfRenderer isn't AutoCloseable until API 35 — use try/finally.
        // Construct inside try so a throwing ctor still closes `pfd`.
        var renderer: PdfRenderer? = null
        try {
            val r = PdfRenderer(pfd).also { renderer = it }
            for (i in 0 until r.pageCount) {
                val page = r.openPage(i)
                try {
                    val scale = targetDpi / 72f
                    val w = (page.width * scale).toInt().coerceAtLeast(1)
                    val h = (page.height * scale).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(
                        bmp,
                        Rect(0, 0, w, h),
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                    )
                    pages += bmp
                } finally {
                    page.close()
                }
            }
        } finally {
            renderer?.close()
            runCatching { pfd.close() }
        }
        return pages
    }

    /**
     * Load a picked signature image. If the uri points at an image, decode
     * it directly; if it points at a PDF (e.g. a signature PDF saved by
     * this app) render the first page to a bitmap.
     */
    fun loadSignatureFromUri(context: Context, uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri) ?: ""
        return if (mime == "application/pdf") {
            renderPdfPages(context, uri, targetDpi = 220).firstOrNull()
        } else {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }

    /**
     * Rebuild [source] as a new PDF with the given overlays drawn on top
     * of each page. Because the Android PDF APIs can't copy vector page
     * content between documents, each original page is rasterized at a
     * print-quality DPI and drawn as a bitmap before the overlays are
     * composited on. The resulting PDF is written to the PhoneScanner
     * folder with an "_edited_<timestamp>" suffix.
     */
    fun savePdfWithOverlays(
        context: Context,
        source: PdfStorage.SavedPdf,
        overlays: List<PdfOverlay>,
        crops: Map<Int, CropRect> = emptyMap()
    ): PdfStorage.SavedPdf {
        val resolver = context.contentResolver
        val pfd = resolver.openFileDescriptor(source.uri, "r")
            ?: error("Could not open PDF")
        val outDoc = PdfDocument()
        var renderer: PdfRenderer? = null
        try {
            val r = PdfRenderer(pfd).also { renderer = it }
            for (i in 0 until r.pageCount) {
                val page = r.openPage(i)
                try {
                    val pageW = page.width
                    val pageH = page.height
                    // Rasterize the original page at ~200dpi so the composited
                    // PDF stays reasonably sharp when viewed or printed.
                    val scale = 200f / 72f
                    val rW = (pageW * scale).toInt().coerceAtLeast(1)
                    val rH = (pageH * scale).toInt().coerceAtLeast(1)
                    val bg = Bitmap.createBitmap(rW, rH, Bitmap.Config.ARGB_8888)
                    bg.eraseColor(android.graphics.Color.WHITE)
                    page.render(
                        bg,
                        Rect(0, 0, rW, rH),
                        null,
                        PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                    )

                    val crop = crops[i]
                    val outW: Int
                    val outH: Int
                    if (crop != null) {
                        outW = (pageW * crop.widthFrac).toInt().coerceAtLeast(1)
                        outH = (pageH * crop.heightFrac).toInt().coerceAtLeast(1)
                    } else {
                        outW = pageW
                        outH = pageH
                    }
                    val pageInfo = PdfDocument.PageInfo
                        .Builder(outW, outH, i + 1)
                        .create()
                    val outPage = outDoc.startPage(pageInfo)
                    val canvas = outPage.canvas
                    // When cropping, shift the coordinate system so the crop's
                    // top-left maps to (0,0). Overlays still live in original-
                    // page fractions, so drawing them after translate puts
                    // them in the right place; content outside the new page
                    // bounds is clipped by the page itself.
                    if (crop != null) {
                        canvas.translate(-crop.xFrac * pageW, -crop.yFrac * pageH)
                    }
                    canvas.drawBitmap(
                        bg,
                        null,
                        RectF(0f, 0f, pageW.toFloat(), pageH.toFloat()),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                    )
                    bg.recycle()

                    overlays.asSequence()
                        .filter { it.pageIndex == i }
                        .forEach { ov ->
                            drawOverlay(canvas, ov, pageW.toFloat(), pageH.toFloat())
                        }
                    outDoc.finishPage(outPage)
                } finally {
                    page.close()
                }
            }

            val bytes = ByteArrayOutputStream().use { bos ->
                outDoc.writeTo(bos)
                bos.toByteArray()
            }
            val baseName = source.baseName + "_edited_" + timestampForBaseName()
            return PdfStorage.writePdfBytes(context, baseName, bytes)
        } finally {
            renderer?.close()
            outDoc.close()
            runCatching { pfd.close() }
        }
    }

    private fun drawOverlay(
        canvas: Canvas,
        ov: PdfOverlay,
        pw: Float,
        ph: Float
    ) {
        when (ov) {
            is PdfOverlay.Text -> {
                val x = ov.xFrac * pw
                val y = ov.yFrac * ph
                val h = ov.heightFrac * ph
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ov.colorArgb
                    textSize = h
                    isFakeBoldText = false
                    typeface = when (ov.fontKind) {
                        FontKind.SERIF -> Typeface.SERIF
                        FontKind.MONOSPACE -> Typeface.MONOSPACE
                        FontKind.SANS -> Typeface.SANS_SERIF
                    }
                }
                // Canvas.drawText draws from the text baseline; sit the
                // text inside the overlay's box by nudging ~82% down.
                canvas.drawText(ov.text, x, y + h * 0.82f, paint)
            }
            is PdfOverlay.Image -> {
                val x = ov.xFrac * pw
                val y = ov.yFrac * ph
                val w = ov.widthFrac * pw
                val h = ov.heightFrac * ph
                val dst = RectF(x, y, x + w, y + h)
                canvas.drawBitmap(
                    ov.image,
                    null,
                    dst,
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
                )
            }
            is PdfOverlay.Ink -> {
                if (ov.points.size < 2) return
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ov.colorArgb
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = (ov.strokeWidthFrac * pw).coerceAtLeast(0.5f)
                }
                val path = Path()
                val first = ov.points.first()
                path.moveTo(first.first * pw, first.second * ph)
                for (i in 1 until ov.points.size) {
                    val p = ov.points[i]
                    path.lineTo(p.first * pw, p.second * ph)
                }
                canvas.drawPath(path, paint)
            }
            is PdfOverlay.Shape -> {
                val left = ov.xFrac * pw
                val top = ov.yFrac * ph
                val right = left + ov.widthFrac * pw
                val bottom = top + ov.heightFrac * ph
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = ov.colorArgb
                    style = Paint.Style.STROKE
                    strokeWidth = (ov.strokeWidthFrac * pw).coerceAtLeast(0.5f)
                }
                val rect = RectF(left, top, right, bottom)
                when (ov.kind) {
                    PdfOverlay.ShapeKind.RECT -> canvas.drawRect(rect, paint)
                    PdfOverlay.ShapeKind.OVAL -> canvas.drawOval(rect, paint)
                }
            }
        }
    }
}
