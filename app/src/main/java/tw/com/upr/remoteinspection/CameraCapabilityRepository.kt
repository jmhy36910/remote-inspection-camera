package tw.com.upr.remoteinspection

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.hardware.camera2.CaptureResult

data class CameraCapability(
    val id: String,
    val facing: String,
    val physicalIds: List<String>,
    val focalLengthsMm: List<Float>,
    val sensorSizeMm: String,
    val resolutions: List<String>,
    val fpsRanges: List<String>,
    val zoomRange: String,
    val focusRangeDiopter: String,
    val isoRange: String,
    val exposureRangeUs: String,
    val capabilities: List<String>,
    val stabilization: List<String>,
    val activePhysicalIdResult: Boolean,
    val classification: String
)

class CameraCapabilityRepository(context: Context) {
    private val manager = context.getSystemService(CameraManager::class.java)

    fun scan(): List<CameraCapability> = manager.cameraIdList.mapNotNull { id ->
        runCatching { read(id) }.getOrNull()
    }

    /** Probe OEM camera IDs that may be omitted from CameraManager.cameraIdList. */
    fun scanHidden(maxId: Int = 99): List<CameraCapability> {
        val publicIds = manager.cameraIdList.toSet()
        return (0..maxId).map { it.toString() }
            .filterNot(publicIds::contains)
            .mapNotNull { id -> runCatching { read(id) }.getOrNull() }
    }

    private fun read(id: String): CameraCapability {
        val c = manager.getCameraCharacteristics(id)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList().orEmpty()
        val sensor = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val zoom = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
        val focus = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val iso = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList()?.map(::capabilityName).orEmpty()
        val resultKeys = c.getAvailableCaptureResultKeys()
        val activeId = Build.VERSION.SDK_INT >= 29 && resultKeys.contains(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val sizes = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.take(20)?.map { "${it.width}x${it.height}" }.orEmpty()
        val fps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.map { "${it.lower}-${it.upper}" }.orEmpty()
        val facing = when (c.get(CameraCharacteristics.LENS_FACING)) { CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"; CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"; else -> "BACK" }
        return CameraCapability(id, facing, c.physicalCameraIds.toList(), focal, sensor?.let { "${it.width} x ${it.height}" } ?: "n/a", sizes, fps,
            zoom?.let { "${it.lower}..${it.upper}" } ?: "n/a", focus?.let { "0..$it D" } ?: "fixed/unknown",
            iso?.let { "${it.lower}..${it.upper}" } ?: "n/a", exposure?.let { "${it.lower / 1000}..${it.upper / 1000} us" } ?: "n/a",
            caps, c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.map { if (it == 1) "ON" else "OFF" }.orEmpty(), activeId, classify(focal, sensor))
    }

    private fun classify(focal: List<Float>, sensor: android.util.SizeF?): String {
        if (focal.isEmpty()) return "UNKNOWN"
        val eq = focal.maxOrNull()!! * ((sensor?.width ?: 6f) / 6f)
        return when { eq < 3f -> "ULTRA_WIDE_CANDIDATE"; eq < 6f -> "MAIN_CANDIDATE"; else -> "TELEPHOTO_CANDIDATE" }
    }

    private fun capabilityName(value: Int) = when (value) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        else -> "CAP_$value"
    }
}
