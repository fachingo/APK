package ca.canadavhs.snapmobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.*
import androidx.camera.core.ImageCapture

class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    val repo = (application as CaptureApplication).repository
    val inventory = repo.journal.inventoryDao().watchItems().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transferJobs = repo.journal.inventoryDao().watchJobs().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val sessions = repo.dao.watchSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups = repo.dao.watchGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val photos = repo.dao.watchPhotos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recognition = repo.dao.watchRecognition().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archives = repo.dao.watchArchives().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var transferMaintenance by mutableStateOf(false)
    private var marketMaintenance by mutableStateOf(false)
    val maintenance:Boolean get()=transferMaintenance || marketMaintenance
    private var transferTask: kotlinx.coroutines.Job? = null
    fun transfer(block: suspend () -> Unit) {
        if (maintenance || pendingCapture) return
        transferMaintenance = true
        transferTask = viewModelScope.launch { try { block() } catch (e: kotlinx.coroutines.CancellationException) { message = "Transfer cancelled; existing data retained." } catch (e: Exception) { message = e.message ?: "Transfer failed; existing data retained." } finally { transferMaintenance = false } }
    }
    fun cancelTransfer() { transferTask?.cancel() }
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    var saving by mutableIntStateOf(0); private set
    val pendingCapture: Boolean get() = busy || saving > 0
    var initialized by mutableStateOf(false)
    var flash = ImageCapture.FLASH_MODE_OFF
    var shutterSignal by mutableIntStateOf(0)
    init {viewModelScope.launch {repo.marketBusy.collect {marketMaintenance=it}}; viewModelScope.launch { try { repo.recover() } catch (e: Exception) { message = "Recovery needs attention: ${e.message}" } finally { initialized = true } } }
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: Exception) { message = e.message ?: "Operation failed. Originals are retained." } } }
    fun scanCommand(engine: CameraEngine, result: (ca.canadavhs.snapmobile.core.CaptureCommand) -> Unit) {
        if(pendingCapture || maintenance || !initialized || engine.imageCapture==null) return
        busy=true;shutterSignal++
        viewModelScope.launch {
            val file=java.io.File(repo.context.cacheDir,"command-${java.util.UUID.randomUUID()}.jpg")
            try {
                engine.capture(file,ImageCapture.FLASH_MODE_OFF)
                val command=withContext(Dispatchers.IO) {
                    requireJpeg(file)
                    val bitmap=recognitionBitmap(file,2200)
                    val values=try {ca.canadavhs.snapmobile.core.CommandQrDecoder.decode(bitmap.width,bitmap.height,bitmap.argbPixels())} finally {bitmap.recycle()}
                    check(values.size==1) {"Show one supported CVHS1 QR card clearly. No inventory photo or command was added."}
                    val target=java.io.File(repo.originals,file.name)
                    java.io.FileOutputStream(file,true).use {it.fd.sync()};atomicMove(file,target);repo.sync(repo.originals)
                    val hash=hashFile(target)
                    repo.mutate {val session=repo.dao.active();repo.dao.insert(EventRow(sessionId=session?.id ?: "00000000-0000-0000-0000-000000000000",groupId=null,action="QR_PHOTO:${target.name}:$hash:${values.single().kind}"))}
                    values.single()
                }
                busy=false
                result(command)
            } catch(e:Exception) {message=e.message ?: "QR command could not be read. Inventory unchanged."}
            finally {file.delete();busy=false}
        }
    }
    fun shoot(engine: CameraEngine) = shoot(engine.imageCapture != null) { file, flash -> engine.capture(file, flash) }
    fun shootInventory(engine: CameraEngine, groupId: String, replaceId: String?, completed: () -> Unit) = shootPipeline(
        engine.imageCapture != null, { file, flash -> engine.capture(file,flash) },
        reserve = { repo.reserveInventoryPhoto(groupId,replaceId) }, afterSaved = { id -> if(replaceId!=null) repo.replaceInventoryPhoto(replaceId,id); completed() })
    internal fun shoot(cameraReady: Boolean, takePicture: suspend (java.io.File, Int) -> Unit) = shootPipeline(cameraReady,takePicture,{repo.reserve()},{})
    private fun shootPipeline(cameraReady: Boolean, takePicture: suspend (java.io.File, Int) -> Unit,
        reserve: suspend () -> PhotoRow, afterSaved: suspend (String) -> Unit) {
        if (maintenance || busy || saving >= 3 || !initialized || !cameraReady) return
        busy = true
        shutterSignal++ // Acknowledge the accepted press immediately, not after hashing.
        viewModelScope.launch {
            var reserved: PhotoRow? = null
            try {
                reserved = reserve()
                takePicture(repo.pendingFile(reserved.id), flash)
                val id = reserved.id
                saving++
                viewModelScope.launch {
                    try {
                        repo.complete(id)
                        afterSaved(id)
                        if (repo.originals.usableSpace < 1_000_000_000) message = "Storage is below 1 GB. Export and back up this batch soon."
                    } catch (e: Exception) {
                        repo.failed(id, e.message ?: "File verification failed; original retained.")
                        message = "Photo needs attention in Batches: ${e.message}"
                    } finally { saving-- }
                }
            } catch (e: Exception) {
                reserved?.let { repo.failed(it.id, e.message ?: "Camera write failed; files retained.") }
                message = e.message ?: "Capture failed. Check Batches for recovery."
            } finally { busy = false }
        }
    }
}
