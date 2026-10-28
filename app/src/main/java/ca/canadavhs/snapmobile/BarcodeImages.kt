package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import ca.canadavhs.snapmobile.core.BarcodeCandidate
import ca.canadavhs.snapmobile.core.LocalBarcodeDecoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Full-frame pass plus overlapping native-pixel tiles: tiny codes retain their bars.
 * Only one tile is decoded at a time. The stored JPEG/EXIF is never changed.
 * ZXing tries both orientations, so original EXIF rotation need not be applied here.
 */
private val barcodeImageLock = Mutex()

@Suppress("DEPRECATION")
internal suspend fun scanOriginalBarcodes(file: File, regions: (File) -> BarcodeRegions = ::AndroidBarcodeRegions): List<BarcodeCandidate> = barcodeImageLock.withLock {
    val found = linkedMapOf<String, BarcodeCandidate>()
    fun collect(values: List<BarcodeCandidate>) { values.forEach { found["${it.format}:${it.raw}"] = it } }
    val overview = recognitionBitmap(file, 2200)
    try { collect(LocalBarcodeDecoder.decode(overview.width, overview.height, overview.argbPixels())) }
    finally { overview.recycle() }
    val decoder = regions(file)
    try {
        if (maxOf(decoder.width, decoder.height) <= 2200) return@withLock found.values.toList()
        // 1536px tiles, >=512px overlap. Small barcodes at every image edge are covered.
        for (top in tileStarts(decoder.height)) for (left in tileStarts(decoder.width)) {
            currentCoroutineContext().ensureActive()
            val rect = Rect(left, top, minOf(left + 1536, decoder.width), minOf(top + 1536, decoder.height))
            val tile = decoder.read(rect) ?: error("Cannot read barcode region.")
            try { collect(LocalBarcodeDecoder.decode(tile.width, tile.height, tile.argbPixels())) }
            finally { tile.recycle() }
        }
    } finally { decoder.close() }
    return@withLock found.values.toList()
}
internal fun tileStarts(length: Int, size: Int = 1536, stride: Int = 1024): List<Int> {
    require(length > 0 && size > 0 && stride in 1..size)
    val last = (length - size).coerceAtLeast(0)
    return ((0..last step stride).toList() + last).distinct()
}

internal interface BarcodeRegions : AutoCloseable {
    val width: Int
    val height: Int
    fun read(rect: Rect): Bitmap?
}
@Suppress("DEPRECATION")
private class AndroidBarcodeRegions(file: File) : BarcodeRegions {
    private val decoder = BitmapRegionDecoder.newInstance(file.path, false)
    override val width get() = decoder.width
    override val height get() = decoder.height
    override fun read(rect: Rect): Bitmap? = decoder.decodeRegion(rect, BitmapFactory.Options())
    override fun close() { decoder.recycle() }
}
