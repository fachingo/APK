package ca.canadavhs.snapmobile

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
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
    var zoomRatio by mutableFloatStateOf(1f); private set
    var minZoom by mutableFloatStateOf(1f); private set
    var maxZoom by mutableFloatStateOf(1f); private set
    private var requestedZoom = 1f
    private var generation = 0
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var zoomObserver: androidx.lifecycle.Observer<ZoomState>? = null
    private var observedZoom: androidx.lifecycle.LiveData<ZoomState>? = null
    private fun stopZoomObservation() { zoomObserver?.let { observedZoom?.removeObserver(it) }; zoomObserver = null; observedZoom = null }
    private var previewView: PreviewView? = null
    private val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private var watchingDisplay = false
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) = Unit
        override fun onDisplayRemoved(id: Int) = Unit
        override fun onDisplayChanged(id: Int) { if (previewView?.display?.displayId == id) updateRotation() }
    }
    fun updateRotation() {
        previewView?.display?.rotation?.let { rotation -> imageCapture?.targetRotation = rotation; preview?.targetRotation = rotation }
    }
    fun bind(view: PreviewView, owner: LifecycleOwner, front: Boolean, hdr: Boolean,
             photoResolution: PhotoResolution, fast: Boolean) {
        val binding = ++generation
        stopZoomObservation()
        imageCapture = null; camera = null
        previewView = view
        if (!watchingDisplay) { displays.registerDisplayListener(displayListener, android.os.Handler(android.os.Looper.getMainLooper())); watchingDisplay = true }
        val future = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        future.addListener({
            try {
                if (binding != generation) return@addListener
                val p = future.get(); provider = p
                val base = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val extensions = ExtensionsManager.getInstanceAsync(context, p)
                extensions.addListener(extensionsReady@{
                    try {
                        if (binding != generation) return@extensionsReady
                        val manager = extensions.get(); hdrAvailable = manager.isExtensionAvailable(base, ExtensionMode.HDR)
                        val useHdr = hdr && hdrAvailable
                        val selector = if (useHdr) manager.getExtensionEnabledCameraSelector(base, ExtensionMode.HDR) else base
                        val previewCase = Preview.Builder().setResolutionSelector(ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                            .build().also { it.surfaceProvider = view.surfaceProvider }
                        val capture = ImageCapture.Builder().setCaptureMode(shutterMode(fast, useHdr))
                            .setResolutionSelector(photoResolution.selector()).setJpegQuality(if (fast) 95 else 100).build()
                        view.display?.rotation?.let { capture.targetRotation = it; previewCase.targetRotation = it }
                        p.unbindAll()
                        val bound = p.bindToLifecycle(owner, selector, previewCase, capture)
                        preview = previewCase; camera = bound; imageCapture = capture
                        observedZoom = bound.cameraInfo.zoomState
                        zoomObserver = androidx.lifecycle.Observer<ZoomState> { state ->
                            if (binding == generation) { zoomRatio = state.zoomRatio; minZoom = state.minZoomRatio; maxZoom = state.maxZoomRatio }
                        }.also { observedZoom?.observe(owner, it) }
                        zoom(requestedZoom)
                        resolution = capture.resolutionInfo?.resolution?.let { "${it.width} × ${it.height}" } ?: "Camera-selected size"
                        error = ""
                    } catch (e: Exception) { error = "Camera could not start: ${e.message}. Try disabling HDR or switching lens." }
                }, executor)
            } catch (e: Exception) { error = "Camera unavailable: ${e.message}" }
        }, executor)
    }
    fun attachGestures(view: PreviewView) {
        val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean { zoom(requestedZoom * detector.scaleFactor); return true }
        })
        val tap = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent) = true
            override fun onSingleTapUp(event: MotionEvent): Boolean { focus(event.x, event.y); view.performClick(); return true }
        })
        var pinched = false
        view.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) pinched = false
            if (event.pointerCount > 1) pinched = true
            scale.onTouchEvent(event)
            if (!pinched) tap.onTouchEvent(event)
            true
        }
    }
    fun zoom(value: Float) {
        val active = camera ?: return
        val state = active.cameraInfo.zoomState.value ?: return
        requestedZoom = value.coerceIn(state.minZoomRatio, state.maxZoomRatio)
        active.cameraControl.setZoomRatio(requestedZoom)
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
    fun unbind() {
        stopZoomObservation()
        generation++; provider?.unbindAll(); imageCapture = null; camera = null; preview = null
        if (watchingDisplay) { displays.unregisterDisplayListener(displayListener); watchingDisplay = false }
    }
    suspend fun capture(file: java.io.File, flash: Int) = suspendCancellableCoroutine { continuation ->
        val capture = imageCapture ?: run { continuation.resumeWithException(IllegalStateException("Camera is starting. Please wait.")); return@suspendCancellableCoroutine }
        capture.flashMode = flash; updateRotation()
        capture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) { if (continuation.isActive) continuation.resume(Unit) }
            override fun onError(exception: ImageCaptureException) { if (continuation.isActive) continuation.resumeWithException(exception) }
        })
    }
}
