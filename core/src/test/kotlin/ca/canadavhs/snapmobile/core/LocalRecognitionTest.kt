package ca.canadavhs.snapmobile.core

import com.google.zxing.EncodeHintType
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import org.junit.Test
import kotlin.test.*

class LocalRecognitionTest {
    private fun raster(value: String, format: BarcodeFormat, width: Int = 600, height: Int = 220): IntArray {
        val matrix = MultiFormatWriter().encode(value, format, width, height, mapOf(EncodeHintType.MARGIN to 20))
        return IntArray(width * height) { index -> if (matrix[index % width, index / width]) 0xff000000.toInt() else 0xffffffff.toInt() }
    }
    @Test fun deterministicUpcDecoderNeedsNoModel() {
        val results = LocalBarcodeDecoder.decode(600, 220, raster("036000291452", BarcodeFormat.UPC_A))
        assertTrue(results.any { it.valid && it.normalized == "00036000291452" })
    }
    @Test fun deterministicUpceDecoder() {
        val results = LocalBarcodeDecoder.decode(600, 220, raster("04252614", BarcodeFormat.UPC_E))
        assertTrue(results.any { it.valid && it.raw == "04252614" && it.format == "UPC_E" })
    }
    @Test fun twoDifferentProductBarcodesRemainAmbiguous() {
        val first = raster("036000291452", BarcodeFormat.UPC_A)
        val second = raster("012345678905", BarcodeFormat.UPC_A)
        val pixels = IntArray(1200 * 220) { index -> val x = index % 1200; val y = index / 1200; if (x < 600) first[y * 600 + x] else second[y * 600 + x - 600] }
        val results = LocalBarcodeDecoder.decode(1200, 220, pixels)
        assertEquals("AMBIGUOUS", Barcodes.status(results))
    }
    @Test fun blankImageHasNoBarcode() { assertTrue(LocalBarcodeDecoder.decode(180, 120, IntArray(180 * 120) { -1 }).isEmpty()) }
    @Test fun classicalChecksFlagLowLightWithoutBlockingCapture() {
        val result = ClassicalImageChecks.analyze(120, 100, IntArray(12000) { 0xff101010.toInt() })
        assertTrue(result.meanBrightness < 32); assertTrue(result.darkFraction > 0.99); assertTrue(result.warnings.any { it.startsWith("Low light") })
    }
    @Test fun imageCheckOutputIsFiniteForTinyImages() {
        val result = ClassicalImageChecks.analyze(3, 3, IntArray(9) { -1 })
        assertTrue(result.laplacianVariance.isFinite()); assertEquals(1.0, result.brightFraction)
    }
}
