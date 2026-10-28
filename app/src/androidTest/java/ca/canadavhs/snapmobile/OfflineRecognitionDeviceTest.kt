package ca.canadavhs.snapmobile

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import com.googlecode.tesseract.android.TessBaseAPI
import org.junit.Assert.*
import org.junit.Test

/** Generated test artwork only; never reads or modifies captured photographs. */
class OfflineRecognitionDeviceTest {
    @Test fun bundledEnglishOcrRecognizesPrintedFixture() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1200, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        canvas.drawText("ALIEN VHS 1986", 50f, 200f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 95f })
        val api = TessBaseAPI()
        try {
            assertTrue(api.init(ensureTesseractData(context).path, "eng", TessBaseAPI.OEM_LSTM_ONLY))
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SINGLE_LINE); api.setImage(bitmap)
            val text = api.getUTF8Text(); assertTrue(text.contains("ALIEN")); assertTrue(text.contains("1986"))
        } finally { api.recycle(); bitmap.recycle() }
    }
    @Test fun actualAndroidKeystoreCanSaveReadAndRemoveIsolatedTestKey() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecureApiKeyStore(context, preferenceName = "recognition-device-test", alias = "snapims.test.openai.key")
        try { store.save("sk-device-test-not-a-real-api-key"); assertTrue(store.hasKey()); assertEquals("sk-device-test-not-a-real-api-key", store.read()) }
        finally { store.remove() }
        assertFalse(store.hasKey())
    }
}
