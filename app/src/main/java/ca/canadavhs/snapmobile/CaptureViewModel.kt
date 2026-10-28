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
    var initialized by mutableStateOf(false)
    var flash = ImageCapture.FLASH_MODE_OFF
    var shutterSignal by mutableIntStateOf(0)
    init { viewModelScope.launch { try { repo.recover() } catch (e: Exception) { message = "Recovery needs attention: ${e.message}" } finally { initialized = true } } }
    fun action(block: suspend () -> Unit) { viewModelScope.launch { try { block() } catch (e: Exception) { message = e.message ?: "Operation failed. Originals are retained." } } }
    fun shoot(engine: CameraEngine) {
        if (busy || !initialized || engine.imageCapture == null) return
        busy = true
        viewModelScope.launch {
            var reserved: PhotoRow? = null
            try {
                reserved = repo.reserve()
                engine.capture(repo.pendingFile(reserved.id), flash)
                repo.complete(reserved.id)
                shutterSignal++
                if (repo.originals.usableSpace < 1_000_000_000) message = "Storage is below 1 GB. Export and back up this batch soon."
            } catch (e: Exception) {
                reserved?.let { repo.failed(it.id, e.message ?: "Camera write failed; files retained.") }
                message = e.message ?: "Capture failed. Check Batches for recovery."
            } finally { busy = false }
        }
    }
}
