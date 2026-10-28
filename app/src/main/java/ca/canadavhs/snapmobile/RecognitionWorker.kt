package ca.canadavhs.snapmobile

import android.content.Context
import android.util.Base64
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ca.canadavhs.snapmobile.core.*
import com.googlecode.tesseract.android.TessBaseAPI
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object RecognitionProcess { val active: MutableSet<String> = ConcurrentHashMap.newKeySet() }

class RecognitionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val repo = (applicationContext as CaptureApplication).repository
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val attempt = repo.dao.recognition(id) ?: return@withContext Result.success()
        if (attempt.state !in listOf("QUEUED", "RUNNING") || !RecognitionProcess.active.add(id)) return@withContext Result.success()
        try {
            if (attempt.provider != repo.recognitionMode()) {
                repo.dao.update(attempt.copy(state = "CANCELLED", error = "Recognition mode changed.", completedAt = System.currentTimeMillis()))
                return@withContext Result.success()
            }
            if (attempt.provider == "OPENAI" && attempt.state == "RUNNING") {
                repo.dao.update(attempt.copy(state = "ERROR", error = "An earlier OpenAI response is uncertain. Retry manually; no automatic billable retry was made.", completedAt = System.currentTimeMillis()))
                return@withContext Result.success()
            }
            val allPhotos = repo.dao.photos(attempt.groupId).filter { !it.deleted && it.state == "READY" }
            if (photoFingerprint(allPhotos) != attempt.inputFingerprint) {
                repo.dao.update(attempt.copy(state = "STALE", error = "Photos changed before recognition started. Run it again.", completedAt = System.currentTimeMillis()))
                return@withContext Result.success()
            }
            repo.dao.update(attempt.copy(state = "RUNNING", error = ""))
            val selected = buildList {
                allPhotos.firstOrNull { it.role == "FRONT" }?.let { add(it) }
                allPhotos.firstOrNull { it.role == "BACK" }?.takeUnless { p -> any { it.id == p.id } }?.let { add(it) }
                allPhotos.forEach { p -> if (size < 3 && none { it.id == p.id }) add(p) }
            }
            val result = if (attempt.provider == "OPENAI") openAi(repo, attempt, selected) else local(repo, attempt, selected)
            ensureActive()
            repo.journal.withTransaction {
                val latest = repo.dao.recognition(id) ?: return@withTransaction
                if (latest.state == "CANCELLED") return@withTransaction
                val nowPhotos = repo.dao.photos(attempt.groupId).filter { !it.deleted && it.state == "READY" }
                val stale = photoFingerprint(nowPhotos) != attempt.inputFingerprint
                repo.dao.update(latest.copy(state = if (stale) "STALE" else "SUCCEEDED", resultJson = result.toString(),
                    error = if (stale) "Photos changed during recognition. Result retained as history; rerun before using it." else "", completedAt = System.currentTimeMillis()))
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val latest = repo.dao.recognition(id)
                if (latest != null && latest.state in listOf("QUEUED", "RUNNING")) repo.dao.update(latest.copy(state = "CANCELLED", error = "Recognition cancelled; captures are unchanged.", completedAt = System.currentTimeMillis()))
            }
            throw e
        } catch (e: Throwable) {
            // Native OCR load errors are actionable too; never let them crash the camera UI.
            if (e is VirtualMachineError) throw e
            val latest = repo.dao.recognition(id)
            if (latest != null && latest.state != "CANCELLED") repo.dao.update(latest.copy(state = "ERROR", error = safeRecognitionError(e), completedAt = System.currentTimeMillis()))
        } finally { RecognitionProcess.active.remove(id) }
        // Domain ERROR is persisted above. Completing the work item lets later products run.
        Result.success()
    }

    private fun safeRecognitionError(error: Throwable): String = when (error) {
        is java.io.IOException, is IllegalArgumentException, is IllegalStateException -> (error.message ?: "Recognition failed; retry from Batches.").take(350)
        is UnsatisfiedLinkError -> "Offline OCR is unavailable on this device. This build requires an arm64 Pixel; use Classical recognition or OpenAI."
        else -> "Recognition failed safely. Captures are unchanged; retry or choose another provider."
    }

    private suspend fun openAi(repo: CaptureRepository, row: RecognitionRow, photos: List<PhotoRow>): JSONObject {
        val key = SecureApiKeyStore(repo.context).read() ?: error("Save an OpenAI API key in Settings.")
        val encoded = mutableListOf<String>(); val provenance = JSONArray()
        for (photo in photos) {
            currentCoroutineContext().ensureActive()
            val file = repo.original(photo.id)
            check(hashFile(file) == photo.sha256) { "An original photo checksum changed. Recognition stopped; inspect the original." }
            val bitmap = recognitionBitmap(file)
            try {
                val bytes = ByteArrayOutputStream().use { out -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)); out.toByteArray() }
                encoded += Base64.encodeToString(bytes, Base64.NO_WRAP)
                provenance.put(JSONObject().put("photo_id", photo.id).put("original_sha256", photo.sha256).put("derivative_sha256", sha256(bytes)).put("width", bitmap.width).put("height", bitmap.height))
            } finally { bitmap.recycle() }
        }
        val schema = JSONObject(repo.context.assets.open("openai_recognition_schema.json").bufferedReader().use { it.readText() })
        return OpenAiRecognitionClient().recognize(key, row.model, encoded, schema).put("source_images", provenance).put("requested_model", row.model)
    }

    private suspend fun local(repo: CaptureRepository, row: RecognitionRow, photos: List<PhotoRow>): JSONObject {
        val details = JSONArray(); val codes = mutableListOf<BarcodeCandidate>(); val lines = mutableListOf<String>(); val confidences = mutableListOf<Int>()
        val ocr = if (row.provider == "OCR") TessBaseAPI() else null
        try {
            if (ocr != null) {
                val data = ensureTesseractData(repo.context)
                check(ocr.init(data.path, "eng", TessBaseAPI.OEM_LSTM_ONLY)) { "Offline OCR could not initialize its verified language data." }
                ocr.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
                ocr.setVariable("preserve_interword_spaces", "1")
                ocr.setVariable("user_defined_dpi", "300")
            }
            for (photo in photos) {
                currentCoroutineContext().ensureActive()
                val file = repo.original(photo.id)
                check(hashFile(file) == photo.sha256) { "An original photo checksum changed. Recognition stopped; inspect the original." }
                val bitmap = recognitionBitmap(file, if (ocr == null) 1600 else 2400)
                try {
                    val pixels = bitmap.argbPixels()
                    codes += scanOriginalBarcodes(file)
                    val metrics = ClassicalImageChecks.analyze(bitmap.width, bitmap.height, pixels)
                    details.put(JSONObject().put("photo_id", photo.id).put("original_sha256", photo.sha256)
                        .put("mean_brightness", metrics.meanBrightness).put("dark_fraction", metrics.darkFraction)
                        .put("bright_fraction", metrics.brightFraction).put("laplacian_variance", metrics.laplacianVariance).put("warnings", JSONArray(metrics.warnings)))
                    if (ocr != null) {
                        ocr.setImage(bitmap)
                        val text = ocr.getUTF8Text() ?: ""
                        confidences += ocr.meanConfidence()
                        lines += text.lineSequence().map { it.trim() }.filter { it.length >= 2 }.map { it.take(240) }.take(40).toList()
                        ocr.clear()
                    }
                } finally { bitmap.recycle() }
            }
        } finally { ocr?.recycle() }
        val unique = codes.distinctBy { "${it.format}:${it.raw}" }
        val textLines = lines.distinct().take(25)
        return JSONObject().put("title", "").put("edition", "").put("distributor", "").put("year", JSONObject.NULL)
            .put("provider_name", if (ocr == null) "zxing-classical" else "tesseract").put("model", row.model)
            .put("uses_trained_ocr_data", ocr != null).put("barcode_status", Barcodes.status(unique))
            .put("barcode_candidates", JSONArray(unique.map { JSONObject().put("raw", it.raw).put("format", it.format).put("normalized", it.normalized).put("valid", it.valid) }))
            .put("text_lines", JSONArray(textLines)).put("raw_text", textLines.joinToString("\n").take(6000))
            .put("ocr_character_confidence", if (confidences.isEmpty()) JSONObject.NULL else confidences.average() / 100.0)
            .put("image_checks", details).put("requires_review", true)
            .put("uncertainty_reasons", JSONArray(listOf(if (ocr == null) "Classical analysis decodes barcodes and checks pixels; it cannot identify a movie title." else "OCR transcribes visible text. Select and verify the printed movie title; it does not identify catalog editions.")))
    }
}

fun ensureTesseractData(context: Context): File {
    val root = File(context.filesDir, "tesseract"); val folder = File(root, "tessdata").apply { mkdirs() }
    val target = File(folder, "eng.traineddata")
    val expected = "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2"
    if (!target.exists() || hashFile(target) != expected) {
        val pending = File(folder, "eng.traineddata.pending")
        context.assets.open("tessdata/eng.traineddata").use { source -> pending.outputStream().use { source.copyTo(it) } }
        check(hashFile(pending) == expected) { "Offline OCR language data failed verification. Reinstall a verified APK update." }
        java.io.FileOutputStream(pending, true).use { it.fd.sync() }; atomicMove(pending, target)
    }
    return root
}
