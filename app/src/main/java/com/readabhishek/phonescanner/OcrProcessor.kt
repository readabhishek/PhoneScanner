package com.readabhishek.phonescanner

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Wraps Google ML Kit Text Recognition (on-device, Latin script) in a
 * coroutine-friendly API. Given the list of per-page image URIs returned by
 * the document scanner, it runs OCR on each page and concatenates the
 * resulting text with a blank line between pages.
 */
object OcrProcessor {

    /**
     * Extracts text from every page image. Returns "" if nothing readable
     * was found on any page.
     */
    suspend fun extractText(context: Context, pageUris: List<Uri>): String {
        if (pageUris.isEmpty()) return ""
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val builder = StringBuilder()
        try {
            pageUris.forEach { uri ->
                val image = InputImage.fromFilePath(context, uri)
                val pageText = suspendCancellableCoroutine<String> { cont ->
                    recognizer.process(image)
                        .addOnSuccessListener { visionText -> cont.resume(visionText.text) }
                        .addOnFailureListener { e -> cont.resumeWithException(e) }
                }
                if (pageText.isNotBlank()) {
                    if (builder.isNotEmpty()) builder.append("\n\n")
                    builder.append(pageText.trim())
                }
            }
        } finally {
            recognizer.close()
        }
        return builder.toString()
    }
}
