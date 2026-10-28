package ca.canadavhs.snapmobile

import android.content.SharedPreferences
import android.util.Size
import androidx.camera.core.ImageCapture
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy

/** CameraX chooses the closest supported size; display the negotiated dimensions. */
enum class PhotoResolution(val label: String, val size: Size?) {
    SMALL("1.2 MP · smaller files", Size(1280, 960)),
    BALANCED("3 MP · balanced", Size(2048, 1536)),
    LARGE("6 MP · more detail", Size(2880, 2160)),
    FULL("12 MP · high detail", Size(4032, 3024)),
    MAXIMUM("Maximum available", null);
    fun selector(): ResolutionSelector = ResolutionSelector.Builder().setResolutionStrategy(
        size?.let { ResolutionStrategy(it, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER) }
            ?: ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
    ).build()
    companion object {
        fun read(prefs: SharedPreferences): PhotoResolution = entries.firstOrNull { it.name == prefs.getString("photoResolution", null) } ?: BALANCED
    }
}
@androidx.annotation.OptIn(markerClass = [androidx.camera.core.ExperimentalZeroShutterLag::class])
fun shutterMode(fast: Boolean, hdr: Boolean): Int = when {
    !fast -> ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
    hdr -> ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
    else -> ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG
}
