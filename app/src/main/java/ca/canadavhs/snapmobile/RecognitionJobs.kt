package ca.canadavhs.snapmobile

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import ca.canadavhs.snapmobile.core.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

const val DEFAULT_OPENAI_MODEL = "gpt-4.1-mini"
val RECOGNITION_MODES = listOf("OFF", "CLASSICAL", "OCR", "OPENAI")
fun CaptureRepository.recognitionMode(): String = preferences.getString("recognitionMode", "OFF").takeIf { it in RECOGNITION_MODES } ?: "OFF"
fun photoFingerprint(photos: List<PhotoRow>): String = sha256(photos.joinToString("\n") { "${it.id}\u0000${it.sha256}\u0000${it.role}\u0000${it.position}" }.toByteArray())
fun photoSnapshot(photos: List<PhotoRow>): String = JSONArray(photos.map { JSONObject().put("id", it.id).put("sha256", it.sha256).put("role", it.role).put("position", it.position) }).toString()

suspend fun CaptureRepository.setRecognitionMode(mode: String) = withContext(Dispatchers.IO) {
    require(mode in RECOGNITION_MODES)
    check(preferences.edit().putString("recognitionMode", mode).commit())
    for (row in dao.unsettledRecognition().filter { it.provider != mode }) {
        journal.withTransaction {
            val latest = dao.recognition(row.id)
            if (latest != null && latest.state in listOf("QUEUED", "RUNNING")) dao.update(latest.copy(state = "CANCELLED", error = "Recognition mode changed; result was not applied.", completedAt = System.currentTimeMillis()))
        }
        runCatching { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(row.workId)) }
    }
}

suspend fun CaptureRepository.queueRecognition(groupId: String): RecognitionRow = withContext(Dispatchers.IO) {
    val provider = recognitionMode(); check(provider != "OFF") { "Choose a recognition mode in Settings first." }
    if (provider == "OPENAI") check(SecureApiKeyStore(context).hasKey()) { "Save your OpenAI API key in Settings first." }
    val row = journal.withTransaction {
        dao.group(groupId)
        val photos = dao.photos(groupId).filter { !it.deleted && it.state == "READY" }
        check(photos.isNotEmpty()) { "Capture a photo before running recognition." }
        check(dao.photos(groupId).none { !it.deleted && it.state == "RESERVED" }) { "Wait for the photograph to finish saving." }
        val fingerprint = photoFingerprint(photos)
        val existing = dao.recognitions(groupId).firstOrNull { it.state in listOf("QUEUED", "RUNNING") && it.provider == provider && it.inputFingerprint == fingerprint }
        if (existing != null) existing else {
            val model = when (provider) { "OPENAI" -> preferences.getString("openaiModel", DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL; "OCR" -> "tesseract-5.5.1/eng-fast-4.1.0"; else -> "zxing-3.5.4/classical-v1" }
            if (provider == "OPENAI") require(validOpenAiModelId(model)) { "Choose a valid OpenAI model ID in Settings." }
            val created = RecognitionRow(groupId = groupId, provider = provider, model = model, inputJson = photoSnapshot(photos), inputFingerprint = fingerprint)
            dao.insert(created); created
        }
    }
    scheduleRecognition(row); row
}

suspend fun CaptureRepository.scheduleRecognition(row: RecognitionRow) = withContext(Dispatchers.IO) {
    val manager = WorkManager.getInstance(context)
    if (row.workId.isNotEmpty()) {
        val info = runCatching { manager.getWorkInfoById(UUID.fromString(row.workId)).get() }.getOrNull()
        if (info != null && !info.state.isFinished) return@withContext
    }
    val request = OneTimeWorkRequestBuilder<RecognitionWorker>().setInputData(workDataOf("id" to row.id))
        .apply { if (row.provider == "OPENAI") setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()) }.build()
    val latest = dao.recognition(row.id) ?: return@withContext
    if (latest.state != "QUEUED") return@withContext
    dao.update(latest.copy(workId = request.id.toString()))
    manager.enqueueUniqueWork(if (row.provider == "OPENAI") "recognition-api" else "recognition-offline", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
}

suspend fun CaptureRepository.recoverRecognition() = withContext(Dispatchers.IO) {
    for (row in dao.unsettledRecognition()) {
        if (row.id in RecognitionProcess.active) continue
        if (row.state == "RUNNING" && row.provider == "OPENAI") {
            dao.update(row.copy(state = "ERROR", error = "The app stopped during an OpenAI request. The response is uncertain; retry manually to avoid duplicate charges.", completedAt = System.currentTimeMillis()))
            runCatching { WorkManager.getInstance(context).cancelWorkById(UUID.fromString(row.workId)) }
        } else {
            val queued = row.copy(state = "QUEUED"); dao.update(queued); scheduleRecognition(queued)
        }
    }
}

suspend fun CaptureRepository.confirmRecognitionTitle(id: String, title: String) = withContext(Dispatchers.IO) {
    val cleaned = title.trim(); require(cleaned.isNotEmpty() && cleaned.length <= 240) { "Enter the printed title (1–240 characters)." }
    journal.withTransaction {
        val row = dao.recognition(id) ?: error("Recognition result not found.")
        check(row.state == "SUCCEEDED") { "Only a completed current suggestion can be confirmed." }
        val photos = dao.photos(row.groupId).filter { !it.deleted && it.state == "READY" }
        check(photoFingerprint(photos) == row.inputFingerprint) { "Photos changed; run recognition again before confirming." }
        dao.update(row.copy(acceptedTitle = cleaned))
    }
}

suspend fun CaptureRepository.recognitionManifest(groupId: String, maxRecords: Int = 1): JSONArray {
    val fingerprint = photoFingerprint(dao.photos(groupId).filter { !it.deleted && it.state == "READY" })
    return JSONArray(dao.recognitions(groupId).filter { it.state == "SUCCEEDED" && it.inputFingerprint == fingerprint }.take(maxRecords.coerceIn(1, 10)).map { row ->
    JSONObject().put("id", row.id).put("provider", row.provider).put("model", row.model)
        .put("requested_at_ms", row.requestedAt).put("completed_at_ms", row.completedAt ?: JSONObject.NULL)
        .put("input_fingerprint", row.inputFingerprint).put("source_photos", JSONArray(row.inputJson))
        .put("result", JSONObject(row.resultJson)).put("confirmed_title", row.acceptedTitle ?: JSONObject.NULL)
    })
}
