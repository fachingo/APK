package ca.canadavhs.snapmobile

import android.content.Context
import androidx.room.withTransaction
import androidx.work.*
import ca.canadavhs.snapmobile.core.sha256
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

internal data class RecognitionSettings(val provider:String,val model:String,val authBinding:String) {
    fun json()=JSONObject().put("provider",provider).put("model",model).put("authBinding",authBinding)
}
internal fun CaptureRepository.recognitionSettings():RecognitionSettings {
    val provider=recognitionMode();check(provider!="OFF") {"Choose a recognition mode in Settings first."}
    val model=when(provider) {"OPENAI"->preferences.getString("openaiModel",DEFAULT_OPENAI_MODEL) ?: DEFAULT_OPENAI_MODEL;"OCR"->"tesseract-5.5.1/eng-fast-4.1.0";else->"zxing-3.5.4/classical-v1"}
    val binding=if(provider=="OPENAI") {require(validOpenAiModelId(model));SecureApiKeyStore(context).bindingId().also {check(it!="MISSING") {"Save your OpenAI API key in Settings first."}}} else "OFFLINE"
    return RecognitionSettings(provider,model,binding)
}
internal fun recognitionSourcePhotos(all:List<PhotoRow>):List<PhotoRow> = buildList {
    all.firstOrNull {it.role=="FRONT"}?.let {add(it)};all.firstOrNull {it.role=="BACK"}?.takeUnless {p->any {it.id==p.id}}?.let {add(it)}
    all.forEach {p->if(size<3 && none {it.id==p.id}) add(p)}
}
suspend fun CaptureRepository.queueRecognition(groupId:String):RecognitionRow {
    val row=mutate {queueRecognitionLocked(groupId,recognitionSettings())};scheduleRecognition(row);return row
}
internal suspend fun CaptureRepository.queueRecognitionLocked(groupId:String,settings:RecognitionSettings,approvalId:String=""):RecognitionRow {
    check(settings==recognitionSettings()) {"Recognition settings or credentials changed. Request a new review."}
    dao.group(groupId);val all=dao.photos(groupId).filter {!it.deleted};val photos=all.filter {it.state=="READY"}
    check(photos.isNotEmpty()) {"Capture a photo before recognition."};check(all.none {it.state!="READY"}) {"Resolve unfinished photographs before recognition."}
    val fingerprint=photoFingerprint(photos)
    val existing=dao.recognitions(groupId).firstOrNull {it.state in listOf("QUEUED","RUNNING") && it.provider==settings.provider && it.model==settings.model && it.authBinding==settings.authBinding && it.inputFingerprint==fingerprint}
    return existing ?: RecognitionRow(groupId=groupId,provider=settings.provider,model=settings.model,inputJson=photoSnapshot(photos),inputFingerprint=fingerprint,authBinding=settings.authBinding,approvalId=approvalId).also {dao.insert(it)}
}

private val recognitionSchedulingLock=Mutex()
suspend fun CaptureRepository.scheduleRecognition(row:RecognitionRow)=withContext(Dispatchers.IO) {
    recognitionSchedulingLock.withLock {
        val latest=dao.recognition(row.id) ?: return@withLock
        if(latest.state!="QUEUED") return@withLock
        val manager=WorkManager.getInstance(context)
        if(latest.workId.isNotEmpty()) {
            val info=manager.getWorkInfoById(UUID.fromString(latest.workId)).get()
            if(info!=null && !info.state.isFinished) return@withLock
        }
        val request=OneTimeWorkRequestBuilder<RecognitionWorker>().setInputData(workDataOf("id" to row.id))
            .apply {if(latest.provider=="OPENAI") setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())}.build()
        val admitted=mutate {val current=dao.recognition(row.id);if(current?.state=="QUEUED") {dao.update(current.copy(workId=request.id.toString()));true} else false}
        if(!admitted) return@withLock
        manager.enqueueUniqueWork(if(latest.provider=="OPENAI") "recognition-api" else "recognition-offline",ExistingWorkPolicy.APPEND_OR_REPLACE,request).result.get()
    }
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
