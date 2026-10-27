package ca.canadavhs.snapmobile

import android.content.Context
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class CameraEngine(private val context: Context) {
    var imageCapture by mutableStateOf<ImageCapture?>(null)
    var camera by mutableStateOf<Camera?>(null)
    var error by mutableStateOf("")
    var hdrAvailable by mutableStateOf(false)
    var resolution by mutableStateOf("")
    private var generation = 0
    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    fun bind(view: PreviewView, owner: LifecycleOwner, front: Boolean, hdr: Boolean) {
        val binding = ++generation
        imageCapture = null; camera = null
        previewView = view
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                if (binding != generation) return@addListener
                val p = future.get(); provider = p
                val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val extensions = ExtensionsManager.getInstanceAsync(context, p)
                extensions.addListener({
                    try {
                        if (binding != generation) return@addListener
                        val manager = extensions.get(); hdrAvailable = manager.isExtensionAvailable(base, ExtensionMode.HDR)
                        val selector = if (hdr && hdrAvailable) manager.getExtensionEnabledCameraSelector(base, ExtensionMode.HDR) else base
                        val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                        val maximum = context.getSharedPreferences("capture-settings", Context.MODE_PRIVATE).getBoolean("maximumResolution", true)
                        val strategy = if (maximum) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY else ResolutionStrategy(android.util.Size(4032, 3024), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                        val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(strategy).build())
                            .setJpegQuality(100).build()
                        p.unbindAll(); camera = p.bindToLifecycle(owner, selector, preview, capture); imageCapture = capture
                        resolution = capture.resolutionInfo?.resolution?.let { "${it.width} × ${it.height}" } ?: "Maximum quality"
                        error = ""
                    } catch (e: Exception) { error = "Camera could not start: ${e.message}. Try disabling HDR or switching lens." }
                }, ContextCompat.getMainExecutor(context))
            } catch (e: Exception) { error = "Camera unavailable: ${e.message}" }
        }, ContextCompat.getMainExecutor(context))
    }
    fun focus(x: Float, y: Float) {
        val factory = previewView?.meteringPointFactory ?: return
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(factory.createPoint(x, y)).setAutoCancelDuration(5, java.util.concurrent.TimeUnit.SECONDS).build())
    }
    fun exposure(fraction: Float) {
        val state = camera?.cameraInfo?.exposureState ?: return
        if (state.isExposureCompensationSupported) camera?.cameraControl?.setExposureCompensationIndex((if (fraction >= 0) fraction * state.exposureCompensationRange.upper else -fraction * state.exposureCompensationRange.lower).toInt().coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper))
    }
    fun torch(on: Boolean) { camera?.takeIf { it.cameraInfo.hasFlashUnit() }?.cameraControl?.enableTorch(on) }
    fun unbind() { generation++; provider?.unbindAll(); imageCapture = null; camera = null }
    suspend fun capture(file: java.io.File, flash: Int) = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture ?: run { continuation.resumeWithException(IllegalStateException("Camera is starting. Please wait.")); return@suspendCancellableCoroutine }
        capture.flashMode = flash; previewView?.display?.let { capture.targetRotation = it.rotation }
        val output = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(output, ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onError(exception: ImageCaptureException) { if (continuation.isActive) continuation.resumeWithException(exception) }
        })
    }
}
