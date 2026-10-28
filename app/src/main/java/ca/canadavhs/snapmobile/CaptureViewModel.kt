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
    val sessions = repo.dao.watchSessions().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val groups = repo.dao.watchGroups().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val photos = repo.dao.watchPhotos().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recognition = repo.dao.watchRecognition().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val archives = repo.dao.watchArchives().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    var message by mutableStateOf("")
    var busy by mutableStateOf(false)
    var saving by mutableIntStateOf(0); private set
    val pendingCapture: Boolean get() = busy || saving > 0
    var initialized by mutableStateOf(false)
    var flash = ImageCapture.FLASH_MODE_OFF
    var shutterSignal by mutableIntStateOf(0)
    init { viewModelScope.launch { try { repo.recover() } catch (e: Exception) { message = "Recovery needs attention: ${e.message}" } finally { initialized = true } } }
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: Exception) { message = e.message ?: "Operation failed. Originals are retained." } } }
    fun shoot(engine: CameraEngine) = shoot(engine.imageCapture != null) { file, flash -> engine.capture(file, flash) }
    internal fun shoot(cameraReady: Boolean, takePicture: suspend (java.io.File, Int) -> Unit) {
        if (busy || saving >= 3 || !initialized || !cameraReady) return
        busy = true
        shutterSignal++ // Acknowledge the accepted press immediately, not after hashing.
        viewModelScope.launch {
            var reserved: PhotoRow? = null
            try {
                reserved = repo.reserve()
                takePicture(repo.pendingFile(reserved.id), flash)
                val id = reserved.id
                saving++
                viewModelScope.launch {
                    try {
                        repo.complete(id)
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
