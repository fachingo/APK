package ca.canadavhs.snapmobile.core

import com.google.zxing.*
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader

/** Deterministic open-source barcode decoding. No trained model or network. */
object LocalBarcodeDecoder {
    private val formats = listOf(BarcodeFormat.UPC_A, BarcodeFormat.UPC_E, BarcodeFormat.EAN_13,
        BarcodeFormat.EAN_8, BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.ITF)
    fun decode(width: Int, height: Int, pixels: IntArray): List<BarcodeCandidate> {
        require(width > 0 && height > 0 && pixels.size == width * height)
        val found = linkedMapOf<String, BarcodeCandidate>()
        val hints = mapOf<DecodeHintType, Any>(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to formats)
        var w = width; var h = height; var raster = pixels
        repeat(2) {
            val source = RGBLuminanceSource(w, h, raster)
            for (inverted in listOf(false, true)) {
                val luminance = if (inverted) source.invert() else source
                for (hybrid in listOf(true, false)) {
                    val bitmap = BinaryBitmap(if (hybrid) HybridBinarizer(luminance) else GlobalHistogramBinarizer(luminance))
                    val reader = MultiFormatReader().apply { setHints(hints) }
                    val results = try { GenericMultipleBarcodeReader(reader).decodeMultiple(bitmap, hints).toList() }
                        catch (_: NotFoundException) { emptyList() }
                    results.forEach { r ->
                        val candidate = Barcodes.candidate(r.text, r.barcodeFormat.name)
                        found["${candidate.format}:${candidate.raw}"] = candidate
                    }
                    reader.reset()
                }
            }
            val rotated = IntArray(raster.size)
            for (y in 0 until h) for (x in 0 until w) rotated[x * h + (h - 1 - y)] = raster[y * w + x]
            raster = rotated; val priorWidth = w; w = h; h = priorWidth
        }
        return found.values.toList()
    }
}

data class ImageMetrics(val meanBrightness: Double, val darkFraction: Double,
    val brightFraction: Double, val laplacianVariance: Double, val warnings: List<String>)

/** Classical pixel statistics only. Warnings are heuristics, never capture blockers. */
object ClassicalImageChecks {
    fun analyze(width: Int, height: Int, pixels: IntArray): ImageMetrics {
        require(width > 2 && height > 2 && pixels.size == width * height)
        fun gray(index: Int): Int {
            val c = pixels[index]
            return (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        val step = (maxOf(width, height) / 512).coerceAtLeast(1)
        var count = 0; var dark = 0; var bright = 0; var sum = 0.0; var lapSum = 0.0; var lapSquared = 0.0
        for (y in 1 until height - 1 step step) for (x in 1 until width - 1 step step) {
            val index = y * width + x; val g = gray(index)
            val lap = gray(index - 1) + gray(index + 1) + gray(index - width) + gray(index + width) - 4 * g
            count++; sum += g; if (g < 32) dark++; if (g > 245) bright++
            lapSum += lap; lapSquared += lap.toDouble() * lap
        }
        val n = count.coerceAtLeast(1).toDouble(); val variance = (lapSquared / n - (lapSum / n) * (lapSum / n)).coerceAtLeast(0.0)
        val warnings = buildList {
            if (sum / n < 55) add("Low light: verify cover text is readable")
            if (bright / n > 0.25) add("Large bright areas: check for glare or overexposure")
            if (variance < 70) add("Low detail: possible blur or a plain cover; inspect the photo")
        }
        return ImageMetrics(sum / n, dark / n, bright / n, variance, warnings)
    }
}
