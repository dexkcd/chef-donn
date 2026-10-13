package ph.chefdonn.receipts.ui

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import ph.chefdonn.receipts.OcrLayout
import ph.chefdonn.receipts.OcrLine
import kotlin.coroutines.resume

/**
 * On-device text recognition (bundled model: no network, no Play Services download).
 * Returns the receipt's printed rows top to bottom; empty when nothing was read, in
 * which case the user simply types the fields.
 */
internal suspend fun readReceiptRows(bitmap: Bitmap): List<String> = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    recognizer.process(InputImage.fromBitmap(bitmap, 0))
        .addOnSuccessListener { text ->
            val lines = text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, box.left, box.top, box.right, box.bottom)
            }
            cont.resume(OcrLayout.rows(lines))
        }
        .addOnFailureListener { cont.resume(emptyList()) }
        .addOnCompleteListener { recognizer.close() }
}
