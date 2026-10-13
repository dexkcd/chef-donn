package ph.chefdonn.receipts.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Photo plumbing for receipt capture. A receipt photo is normalized once (upright,
 * long edge ≤ [MAX_EDGE], JPEG) and written as a draft file; the draft is what OCR
 * reads, what the form shows, and what goes to the upload outbox on save. Drafts
 * live on disk so a form survives the camera app pushing ours out of memory.
 */
internal object ReceiptImages {

    /** Enough for small receipt print to OCR cleanly; ~200–400 KB per page. */
    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 80

    fun draftFile(context: Context, photoId: String): File =
        File(File(context.cacheDir, "receipt-drafts").apply { mkdirs() }, "$photoId.jpg")

    /** A fresh file + content Uri for the system camera to write into. */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "receipt-capture").apply { mkdirs() }
        val file = File(dir, "capture-${UUID.randomUUID()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.receipts.fileprovider", file)
    }

    /** Reads [source], writes the normalized JPEG to [out], returns the normalized bitmap for OCR. */
    fun prepare(context: Context, source: Uri, out: File): Bitmap {
        val resolver = context.contentResolver
        fun open() = resolver.openInputStream(source) ?: throw IOException("Can't open photo")

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")

        // Power-of-two subsample first so a 12 MP photo never fully lands in memory.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = open().use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Not an image")

        val rotation = runCatching { open().use { ExifInterface(it).rotationDegrees } }.getOrDefault(0)
        val scale = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
        val bitmap = if (scale < 1f || rotation != 0) {
            val m = Matrix().apply {
                if (scale < 1f) postScale(scale, scale)
                postRotate(rotation.toFloat())
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, m, true)
                .also { if (it !== decoded) decoded.recycle() }
        } else {
            decoded
        }

        val tmp = File(out.parentFile, out.name + ".part")
        tmp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        if (!tmp.renameTo(out)) throw IOException("Can't save photo")
        return bitmap
    }

    /** Decodes JPEG bytes no larger than needed for a [targetEdge]-pixel view. */
    fun decode(bytes: ByteArray, targetEdge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetEdge) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
