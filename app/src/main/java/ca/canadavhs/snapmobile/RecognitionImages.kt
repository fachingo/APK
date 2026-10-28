package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import java.io.File

/** Small, oriented derivatives for recognition. Never writes into an original. */
fun recognitionBitmap(file: File, longestEdge: Int = 1600): Bitmap {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    check(bounds.outWidth > 0 && bounds.outHeight > 0) { "Original image cannot be decoded." }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= longestEdge) sample *= 2
    var bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Cannot decode recognition preview.")
    val ratio = longestEdge.toDouble() / maxOf(bitmap.width, bitmap.height)
    if (ratio < 1) {
        val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
        if (scaled !== bitmap) bitmap.recycle(); bitmap = scaled
    }
    val exif = ExifInterface(file)
    if (exif.rotationDegrees != 0 || exif.isFlipped) {
        val matrix = Matrix().apply { if (exif.isFlipped) postScale(-1f, 1f); postRotate(exif.rotationDegrees.toFloat()) }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented !== bitmap) bitmap.recycle(); bitmap = oriented
    }
    return bitmap
}
fun Bitmap.argbPixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }
