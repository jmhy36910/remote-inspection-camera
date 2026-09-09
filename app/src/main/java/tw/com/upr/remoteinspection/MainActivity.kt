package tw.com.upr.remoteinspection

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.View
import android.view.TextureView
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.foundation.text.KeyboardActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONArray
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private var remoteServer: NetworkControlServer? = null
    private var webServer: WebControlServer? = null
    private var remoteHandler: ((JSONObject) -> JSONObject)? = null
    private var savedScreenBrightness: Float? = null
    private var tcpClients by mutableIntStateOf(0)
    private var sleepScreen by mutableStateOf(false)
    private fun updateScreenBrightness() {
        val dim = tcpClients > 0 || sleepScreen
        if (dim && savedScreenBrightness == null) savedScreenBrightness = window.attributes.screenBrightness
        window.attributes = window.attributes.apply {
            screenBrightness = if (dim) 0f else savedScreenBrightness ?: -1f
        }
        if (!dim) savedScreenBrightness = null
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) show() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) show() else permission.launch(Manifest.permission.CAMERA)
    }
    private fun show() {
        setContent { Phase2Screen() }
        if (remoteServer == null) {
            remoteServer = NetworkControlServer(onCommand = { command ->
                var result = JSONObject().put("version", 1).put("ok", false).put("error", "UI timeout")
                val done = CountDownLatch(1)
                runOnUiThread {
                    result = remoteHandler?.invoke(command)
                        ?: JSONObject().put("ok", false).put("error", "camera UI is not ready")
                    done.countDown()
                }
                done.await(2, TimeUnit.SECONDS)
                result
            }, onClientCountChanged = { count ->
                runOnUiThread {
                    tcpClients = count
                    if (count > 0) {
                        if (savedScreenBrightness == null) savedScreenBrightness = window.attributes.screenBrightness
                        val attributes = window.attributes
                        attributes.screenBrightness = 0f
                        window.attributes = attributes
                    } else {
                        val previous = savedScreenBrightness
                        if (previous != null) {
                            val attributes = window.attributes
                            attributes.screenBrightness = previous
                            window.attributes = attributes
                            savedScreenBrightness = null
                        }
                    }
                    updateScreenBrightness()
                }
            }).also { it.start() }
            webServer = WebControlServer(this) { command -> dispatchRemote(command) }.also { it.start() }
        }
    }
    private fun dispatchRemote(command: JSONObject): JSONObject {
        var result = JSONObject().put("version", 1).put("ok", false).put("error", "UI timeout")
        val done = CountDownLatch(1)
        runOnUiThread {
            result = remoteHandler?.invoke(command) ?: JSONObject().put("ok", false).put("error", "camera UI is not ready")
            done.countDown()
        }
        done.await(2, TimeUnit.SECONDS)
        return result
    }
    override fun onDestroy() {
        remoteServer?.stop()
        remoteServer = null
        webServer?.stop()
        webServer = null
        super.onDestroy()
    }
    @Composable private fun Phase2Screen() {
        var texture by remember { mutableStateOf<TextureView?>(null) }
        var surfaceReady by remember { mutableStateOf(false) }
        var editingParameter by remember { mutableStateOf<String?>(null) }
        var status by remember { mutableStateOf("Ready to test a CaptureSession") }
        var controller by remember { mutableStateOf<CameraSessionController?>(null) }
        val cameraManager = remember { getSystemService(CameraManager::class.java) }
        val cameraIds = remember { cameraManager.cameraIdList.toList() }
        var selectedId by remember { mutableStateOf(cameraIds.firstOrNull() ?: "0") }
        val physicalIds = remember(selectedId) { cameraManager.getCameraCharacteristics(selectedId).physicalCameraIds.toList() }
        var hiddenCaps by remember { mutableStateOf(emptyList<CameraCapability>()) }
        var scanningHidden by remember { mutableStateOf(false) }
        var cameraMenuExpanded by remember { mutableStateOf(false) }
        var isoValue by remember { mutableFloatStateOf(100f) }
        var exposureMs by remember { mutableFloatStateOf(1f) }
        var focusDiopter by remember { mutableFloatStateOf(1.35f) }
        var isoText by remember { mutableStateOf("100") }
        var exposureText by remember { mutableStateOf("1.0") }
        var focusText by remember { mutableStateOf("1.35") }
        var zoomValue by remember { mutableFloatStateOf(1f) }
        var zoomText by remember { mutableStateOf("1.0") }
        var whiteBalance by remember { mutableStateOf("Auto") }
        var tempValue by remember { mutableFloatStateOf(4000f) }
        var tempText by remember { mutableStateOf("4000") }
        var cameraOn by remember { mutableStateOf(false) }
        var optionsMenuExpanded by remember { mutableStateOf(false) }
        var sizeMenuExpanded by remember { mutableStateOf(false) }
        var previewSizeMenuExpanded by remember { mutableStateOf(false) }
        var previewFpsMenuExpanded by remember { mutableStateOf(false) }
        var shutterBlack by remember { mutableStateOf(false) }
        val characteristics = remember(selectedId) { cameraManager.getCameraCharacteristics(selectedId) }
        val sensorOrientation = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val streamMap = remember(selectedId) { characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) }
        val resolutionOptions = remember(selectedId) {
            streamMap?.getOutputSizes(android.graphics.ImageFormat.JPEG).orEmpty().toList()
                .distinctBy { "${it.width}x${it.height}" }
                .sortedByDescending { it.width.toLong() * it.height.toLong() }
        }
        val previewResolutionOptions = remember(selectedId) {
            val reported = streamMap?.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty().toList()
            val practical = reported.filter { maxOf(it.width, it.height) <= 1920 && minOf(it.width, it.height) >= 320 }
            (if (practical.isNotEmpty()) practical else reported)
                .distinctBy { "${it.width}x${it.height}" }
                .sortedBy { it.width.toLong() * it.height.toLong() }
        }
        val adaptivePreviewResolution = previewResolutionOptions.minByOrNull {
            kotlin.math.abs(it.width.toLong() * it.height.toLong() - 1280L * 720L)
        } ?: Size(1280, 720)
        var selectedResolution by remember(selectedId) { mutableStateOf(resolutionOptions.firstOrNull() ?: Size(1920, 1080)) }
        val originalResolution = resolutionOptions.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: selectedResolution
        var originalSizeOutput by remember(selectedId) { mutableStateOf(true) }
        var rawMode by remember(selectedId) { mutableStateOf(false) }
        var yuvPreviewMode by remember(selectedId) { mutableStateOf(false) }
        val isoRange = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val focusMax = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 10f
        val zoomMax = (characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 10f).coerceAtLeast(1f)
        var detailsVisible by remember { mutableStateOf(false) }
        var manualMode by remember { mutableStateOf(false) }
        var previewWidth by remember(selectedId) { mutableIntStateOf(adaptivePreviewResolution.width) }
        var previewHeight by remember(selectedId) { mutableIntStateOf(adaptivePreviewResolution.height) }
        var previewQuality by remember { mutableIntStateOf(45) }
        var previewFps by remember { mutableIntStateOf(30) }
        var phonePreviewEnabled by remember { mutableStateOf(true) }
        var pcPreviewEnabled by remember { mutableStateOf(true) }
        var autoIso by remember { mutableStateOf(true) }
        var autoExposure by remember { mutableStateOf(true) }
        var autoFocus by remember { mutableStateOf(true) }
        var autoWhiteBalance by remember { mutableStateOf(true) }
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        fun activeIso() = if (manualMode && !autoIso) isoValue.toInt() else null
        fun activeExposure() = if (manualMode && !autoExposure) (exposureMs * 1000).toLong() else null
        fun activeFocus() = if (manualMode && !autoFocus) focusDiopter else null
        fun activeTemperature() = if (manualMode && !autoWhiteBalance) tempValue.toInt() else null
        fun photoResolution() = if (originalSizeOutput) originalResolution else selectedResolution
        fun updateActiveControls() { controller?.setAutoModes(autoIso, autoExposure, autoFocus, autoWhiteBalance); controller?.updateControls(activeIso(), activeExposure(), activeFocus(), zoomValue, activeTemperature()) }
        fun startWithActiveControls(id: String = selectedId) { controller?.start(id, null, zoomValue, iso = activeIso(), exposureUs = activeExposure(), focusDiopter = activeFocus(), resolution = photoResolution(), temperatureK = activeTemperature(), rawEnabled = rawMode, yuvPreview = yuvPreviewMode); controller?.setAutoModes(autoIso, autoExposure, autoFocus, autoWhiteBalance) }
        fun applyManualInputs() {
            isoValue = (isoText.toFloatOrNull() ?: isoValue).coerceIn((isoRange?.lower ?: 50).toFloat(), (isoRange?.upper ?: 3200).toFloat())
            exposureMs = (exposureText.toFloatOrNull() ?: exposureMs).coerceIn((exposureRange?.lower ?: 100000L) / 1000000f, (exposureRange?.upper ?: 1000000000L) / 1000000f)
            focusDiopter = (focusText.toFloatOrNull() ?: focusDiopter).coerceIn(0f, focusMax)
            zoomValue = (zoomText.toFloatOrNull() ?: zoomValue).coerceIn(1f, zoomMax)
            tempValue = (tempText.toFloatOrNull() ?: tempValue).coerceIn(2000f, 8000f)
            isoText = isoValue.toInt().toString(); exposureText = "%.3f".format(exposureMs)
            focusText = "%.2f".format(focusDiopter); zoomText = "%.2f".format(zoomValue); tempText = tempValue.toInt().toString()
            updateActiveControls()
            focusManager.clearFocus()
        }
        remoteHandler = { command ->
            val requestId = command.optString("requestId", "remote")
            val type = command.optString("type")
            fun response(ok: Boolean = true) = JSONObject().put("version", 1).put("requestId", requestId).put("ok", ok)
            when (type) {
                "get_state" -> response().put("cameraId", selectedId).put("cameraOn", cameraOn).put("manual", !(autoIso && autoExposure && autoFocus && autoWhiteBalance))
                    .put("previewWidth", previewWidth).put("previewHeight", previewHeight).put("previewQuality", previewQuality).put("previewFps", controller?.effectivePreviewFps() ?: previewFps).put("previewFpsRequested", previewFps)
                    .put("autoIso", autoIso).put("autoExposure", autoExposure).put("autoFocus", autoFocus).put("autoWhiteBalance", autoWhiteBalance)
                    .put("phonePreviewEnabled", phonePreviewEnabled).put("pcPreviewEnabled", pcPreviewEnabled)
                    .put("photoOriginal", originalSizeOutput).put("photoWidth", photoResolution().width).put("photoHeight", photoResolution().height)
                    .put("rawMode", rawMode)
                    .put("yuvPreviewMode", yuvPreviewMode)
                    .put("photoSizes", JSONArray(resolutionOptions.map { "${it.width}×${it.height}" }))
                    .put("previewSizes", JSONArray(previewResolutionOptions.map { "${it.width}×${it.height}" }))
                    .put("iso", isoValue).put("exposureMs", exposureMs).put("focusDiopter", focusDiopter).put("focusMax", focusMax).put("zoom", zoomValue).put("zoomMax", zoomMax).put("temperatureK", tempValue).put("status", status)
                    .put("cameraIds", JSONArray(cameraIds)).put("hiddenIds", JSONArray(hiddenCaps.map { it.id }))
                "scan_hidden" -> { if (!scanningHidden) { scanningHidden = true; scope.launch(Dispatchers.IO) { val found = CameraCapabilityRepository(this@MainActivity).scanHidden(); kotlinx.coroutines.withContext(Dispatchers.Main) { hiddenCaps = found; scanningHidden = false } } }; response().put("scanning", scanningHidden) }
                "set_camera" -> { val id = command.optString("cameraId", selectedId); selectedId = id; response().put("cameraId", id) }
                "set_raw_mode" -> { rawMode = command.optBoolean("enabled", false); if (cameraOn) startWithActiveControls(); response().put("rawMode", rawMode) }
                "set_preview_format" -> { yuvPreviewMode = command.optString("format", "jpeg").equals("yuv", true); if (cameraOn) startWithActiveControls(); response().put("yuvPreviewMode", yuvPreviewMode) }
                "set_iso" -> { isoValue = command.optDouble("value", isoValue.toDouble()).toFloat().coerceIn(50f, 3200f); isoText = isoValue.toInt().toString(); autoIso = false; manualMode = true; updateActiveControls(); response() }
                "set_exposure_us" -> { exposureMs = (command.optDouble("value", (exposureMs * 1000).toDouble()) / 1000.0).toFloat().coerceIn(0.1f, 1000f); exposureText = "%.3f".format(exposureMs); autoExposure = false; manualMode = true; updateActiveControls(); response() }
                "set_focus" -> { focusDiopter = command.optDouble("value", focusDiopter.toDouble()).toFloat().coerceIn(0f, focusMax); focusText = "%.2f".format(focusDiopter); autoFocus = false; manualMode = true; updateActiveControls(); response() }
                "set_zoom" -> { zoomValue = command.optDouble("value", zoomValue.toDouble()).toFloat().coerceIn(0.6f, zoomMax); zoomText = "%.2f".format(zoomValue); updateActiveControls(); response() }
                "set_temperature_k" -> { tempValue = command.optDouble("value", tempValue.toDouble()).toFloat().coerceIn(2000f, 8000f); tempText = tempValue.toInt().toString(); autoWhiteBalance = false; manualMode = true; updateActiveControls(); response() }
                "set_auto" -> { when (command.optString("parameter")) { "iso" -> autoIso = command.optBoolean("enabled", true); "exposure" -> autoExposure = command.optBoolean("enabled", true); "focus" -> autoFocus = command.optBoolean("enabled", true); "white_balance" -> autoWhiteBalance = command.optBoolean("enabled", true) }; manualMode = !(autoIso && autoExposure && autoFocus && autoWhiteBalance); updateActiveControls(); response() }
                "set_mode" -> { val isManual = command.optBoolean("manual", false); manualMode = isManual; autoIso = !isManual; autoExposure = !isManual; autoFocus = !isManual; autoWhiteBalance = !isManual; updateActiveControls(); response().put("manual", isManual) }
                "set_preview_config" -> { val requestedW = command.optInt("width", previewWidth); val requestedH = command.optInt("height", previewHeight); val matched = previewResolutionOptions.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height.toLong() - requestedW.toLong() * requestedH.toLong()) } ?: adaptivePreviewResolution; previewWidth = matched.width; previewHeight = matched.height; previewQuality = command.optInt("quality", previewQuality).coerceIn(30, 90); val requested = command.optInt("fps", previewFps).coerceIn(1, 30); val effective = controller?.setPreviewConfig(previewWidth, previewHeight, previewQuality, requested) ?: requested; previewFps = effective; response().put("previewWidth", previewWidth).put("previewHeight", previewHeight).put("previewQuality", previewQuality).put("previewFps", effective).put("previewFpsRequested", requested) }
                "set_preview" -> {
                    val enabled = command.optBoolean("enabled", true)
                    if (command.optString("target", "phone") == "pc") { pcPreviewEnabled = enabled; controller?.setPreviewEnabled(enabled) }
                    else phonePreviewEnabled = enabled
                    response().put("phonePreviewEnabled", phonePreviewEnabled).put("pcPreviewEnabled", pcPreviewEnabled)
                }
                "set_photo_original" -> { originalSizeOutput = command.optBoolean("enabled", true); if (cameraOn) startWithActiveControls(); response().put("photoOriginal", originalSizeOutput).put("photoWidth", photoResolution().width).put("photoHeight", photoResolution().height) }
                "set_photo_size" -> {
                    val requestedW = command.optInt("width", photoResolution().width)
                    val requestedH = command.optInt("height", photoResolution().height)
                    selectedResolution = resolutionOptions.minByOrNull { kotlin.math.abs(it.width.toLong() * it.height.toLong() - requestedW.toLong() * requestedH.toLong()) } ?: selectedResolution
                    originalSizeOutput = false
                    if (cameraOn) startWithActiveControls()
                    response().put("photoOriginal", false).put("photoWidth", photoResolution().width).put("photoHeight", photoResolution().height)
                }
                "capture" -> { scope.launch { controller?.capture() }; response() }
                "camera_power" -> { if (command.optBoolean("enabled", true)) { startWithActiveControls(); cameraOn = true } else { controller?.close(); cameraOn = false }; response() }
                else -> response(false).put("error", "unsupported type: $type")
            }
        }
        DisposableEffect(texture) {
            val t = texture
            if (t == null) onDispose { } else {
                val c = CameraSessionController(getSystemService(CameraManager::class.java), t, { status = it }, { jpeg ->
                    val event = JSONObject().put("version", 1).put("event", "photo").put("mime", "image/jpeg").put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                    remoteServer?.broadcast(event); webServer?.broadcast(event)
                }, { jpeg ->
                    webServer?.publishPreview(jpeg)
                    if (remoteServer?.hasClients() == true) {
                        val event = JSONObject().put("version", 1).put("event", "preview").put("mime", "image/jpeg").put("data", android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP))
                        remoteServer?.broadcast(event)
                    }
                }, { dng ->
                    val event = JSONObject().put("version", 1).put("event", "photo").put("mime", "image/x-adobe-dng").put("extension", "dng").put("data", android.util.Base64.encodeToString(dng, android.util.Base64.NO_WRAP))
                    remoteServer?.broadcast(event); webServer?.broadcast(event)
                }, { actualIso, actualExposureMs, actualFocus, actualZoom ->
                    runOnUiThread {
                        if (autoIso && editingParameter != "ISO") actualIso?.let { isoValue = it.toFloat(); isoText = it.toString() }
                        if (autoExposure && editingParameter != "曝光") actualExposureMs?.let { exposureMs = it; exposureText = "%.3f".format(it) }
                        if (autoFocus && editingParameter != "對焦") actualFocus?.let { focusDiopter = it; focusText = "%.2f".format(it) }
                        // CONTROL_ZOOM_RATIO is a capture-result value.  On
                        // Some OEM logical/physical cameras can briefly report
                        // 1.0 even though the requested crop is
                        // already active.  Never overwrite the user's
                        // requested zoom with that transient result; the
                        // requested value is the source of truth for both
                        // Android and the Windows controller.
                    }
                }, shouldStreamPreview = { remoteServer?.hasClients() == true || webServer?.hasPreviewClients() == true }, onPreviewFpsChanged = { effective ->
                    runOnUiThread { if (previewFps != effective) previewFps = effective }
                })
                c.setPreviewConfig(previewWidth, previewHeight, previewQuality, previewFps)
                controller = c
                onDispose { c.release(); controller = null }
            }
        }
        LaunchedEffect(surfaceReady, controller, selectedId) {
            if (surfaceReady && controller != null) {
                startWithActiveControls()
                cameraOn = true
            }
        }
        // Android may suspend Camera2 while this activity is temporarily in
        // the background.  Reopen only when the user had left this camera ON.
        val lifecycleOwner = LocalLifecycleOwner.current
        val resumeCamera by rememberUpdatedState {
            if (cameraOn && surfaceReady && controller != null) startWithActiveControls()
        }
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) resumeCamera()
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        val darkTheme = isSystemInDarkTheme()
        MaterialTheme(colorScheme = if (darkTheme) darkColorScheme(
            primary = Color(0xFF71DACA), background = Color(0xFF101819),
            surface = Color(0xFF182224)
        ) else lightColorScheme(
            primary = Color(0xFF006B60), background = Color(0xFFF2F6F5),
            surface = Color(0xFFFFFFFF)
        )) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
                    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Machine Vision", style = MaterialTheme.typography.titleLarge)
                                Text(if (tcpClients > 0) "PC 已連線 · $tcpClients" else "Camera · 檢測與遠端拍攝", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                            TextButton(onClick = { sleepScreen = true; updateScreenBrightness() }) { Text("省電") }
                            Box {
                                IconButton(onClick = { optionsMenuExpanded = true }) { Text("⋮", style = MaterialTheme.typography.headlineMedium) }
                                DropdownMenu(optionsMenuExpanded, { optionsMenuExpanded = false }) {
                                    DropdownMenuItem(text = { Text(if (scanningHidden) "搜尋中…" else "搜尋隱藏鏡頭") }, enabled = !scanningHidden, onClick = {
                                        optionsMenuExpanded = false; scanningHidden = true
                                        scope.launch(Dispatchers.IO) {
                                            val found = runCatching { CameraCapabilityRepository(this@MainActivity).scanHidden() }
                                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                                found.onSuccess { hiddenCaps = it }.onFailure { status = "搜尋失敗：${it.message}" }
                                                scanningHidden = false
                                            }
                                        }
                                    })
                                    DropdownMenuItem(text = { Text("照片尺寸 · ${photoResolution().width}×${photoResolution().height}") }, onClick = { sizeMenuExpanded = true; optionsMenuExpanded = false })
                                    DropdownMenuItem(text = { Text("原始尺寸輸出 · ${if (originalSizeOutput) "開" else "關"}") }, onClick = { originalSizeOutput = !originalSizeOutput; optionsMenuExpanded = false; if (cameraOn) startWithActiveControls() })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("RAW / DNG · ${if (rawMode) "開" else "關"}") }, onClick = { rawMode = !rawMode; optionsMenuExpanded = false; if (cameraOn) startWithActiveControls() })
                                    DropdownMenuItem(text = { Text("YUV 灰階預覽 · ${if (yuvPreviewMode) "開" else "關"}") }, onClick = { yuvPreviewMode = !yuvPreviewMode; optionsMenuExpanded = false; if (cameraOn) startWithActiveControls() })
                                    HorizontalDivider()
                                    DropdownMenuItem(text = { Text("傳送尺寸 · ${previewWidth}×${previewHeight}") }, onClick = { previewSizeMenuExpanded = true; optionsMenuExpanded = false })
                                    DropdownMenuItem(text = { Text("更新率 · $previewFps FPS") }, onClick = { previewFpsMenuExpanded = true; optionsMenuExpanded = false })
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { cameraMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) { Text("Camera $selectedId  ▾") }
                                DropdownMenu(cameraMenuExpanded, { cameraMenuExpanded = false }) {
                                    (cameraIds + hiddenCaps.map { it.id }).distinct().forEach { id ->
                                        DropdownMenuItem(text = { Text("Camera $id${hiddenCaps.find { it.id == id }?.let { " · " + it.classification } ?: ""}") }, onClick = { selectedId = id; cameraMenuExpanded = false })
                                    }
                                    physicalIds.forEach { id ->
                                        DropdownMenuItem(text = { Text("實體鏡頭 $id") }, onClick = {
                                            controller?.start(selectedId, id, zoomValue, activeIso(), activeExposure(), activeFocus(), photoResolution(), activeTemperature(), rawMode, yuvPreviewMode)
                                            cameraOn = true; cameraMenuExpanded = false
                                        })
                                    }
                                }
                            }
                            Text(if (cameraOn) "● LIVE" else "○ OFF", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                            Text(if (rawMode) "RAW + JPG" else "JPG", style = MaterialTheme.typography.labelMedium)
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(0.48f).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)).background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
                            AndroidView(
                                factory = { context -> TextureView(context).apply {
                                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                                        override fun onSurfaceTextureAvailable(s: android.graphics.SurfaceTexture, width: Int, height: Int) { surfaceReady = true }
                                        override fun onSurfaceTextureSizeChanged(s: android.graphics.SurfaceTexture, width: Int, height: Int) { controller?.refreshPreviewTransform() }
                                        override fun onSurfaceTextureDestroyed(s: android.graphics.SurfaceTexture): Boolean { surfaceReady = false; controller?.close(); return true }
                                        override fun onSurfaceTextureUpdated(s: android.graphics.SurfaceTexture) {}
                                    }
                                    texture = this
                                } },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (shutterBlack || !phonePreviewEnabled || !cameraOn) {
                                Box(Modifier.matchParentSize().background(Color.Black), contentAlignment = androidx.compose.ui.Alignment.Center) {
                                    if (!shutterBlack) Text(if (cameraOn) "手機預覽已隱藏" else "相機已關閉", color = Color(0xFFAAB8B7))
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text("${photoResolution().width} × ${photoResolution().height}", style = MaterialTheme.typography.labelLarge)
                                Text("原畫質拍照", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledIconButton(enabled = cameraOn && !shutterBlack, onClick = {
                                shutterBlack = true
                                scope.launch { controller?.capture(); kotlinx.coroutines.delay(180); shutterBlack = false }
                            }, modifier = Modifier.size(64.dp)) {
                                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_camera), contentDescription = "拍照", modifier = Modifier.size(32.dp))
                            }
                            OutlinedButton(onClick = { if (cameraOn) { controller?.close(); cameraOn = false } else { startWithActiveControls(); cameraOn = true } }) { Text(if (cameraOn) "關相機" else "開相機") }
                        }
                        Column(Modifier.fillMaxWidth().weight(0.52f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("預覽與傳輸", style = MaterialTheme.typography.titleSmall)
                                        TextButton(onClick = { detailsVisible = !detailsVisible }) { Text(if (detailsVisible) "收起詳情" else "詳情") }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        Text("手機顯示")
                                        Switch(phonePreviewEnabled, onCheckedChange = { phonePreviewEnabled = it })
                                        Text("PC 傳送")
                                        Switch(pcPreviewEnabled, onCheckedChange = { pcPreviewEnabled = it; controller?.setPreviewEnabled(it) })
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        TextButton(onClick = { previewSizeMenuExpanded = true }) { Text("${previewWidth}×${previewHeight} ▾") }
                                        TextButton(onClick = { previewFpsMenuExpanded = true }) { Text("$previewFps FPS ▾") }
                                    }
                                    if (detailsVisible) {
                                        Text(status, style = MaterialTheme.typography.bodySmall)
                                        Text("無遠端觀看時暫停預覽編碼。省電黑屏可降低螢幕耗電，相機仍保持工作。", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("拍攝參數", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                FilterChip(selected = !manualMode, onClick = { manualMode = false; autoIso = true; autoExposure = true; autoFocus = true; autoWhiteBalance = true; updateActiveControls() }, label = { Text("自動") })
                                FilterChip(selected = manualMode, onClick = { manualMode = true; autoIso = false; autoExposure = false; autoFocus = false; autoWhiteBalance = false; updateActiveControls() }, label = { Text("手動") })
                            }
                            if (manualMode) {
                                ParameterControl("ISO", "ISO", isoValue, isoText, (isoRange?.lower ?: 50).toFloat()..(isoRange?.upper ?: 3200).toFloat(), autoIso,
                                    { autoIso = it; updateActiveControls() }, { isoText = it; autoIso = false },
                                    { isoValue = it; isoText = it.toInt().toString(); autoIso = false; updateActiveControls() }, { applyManualInputs() }, { editingParameter = if (it) "ISO" else null })
                                ParameterControl("曝光", "ms", exposureMs, exposureText, ((exposureRange?.lower ?: 100000L) / 1000000f)..((exposureRange?.upper ?: 1000000000L) / 1000000f), autoExposure,
                                    { autoExposure = it; updateActiveControls() }, { exposureText = it; autoExposure = false },
                                    { exposureMs = it; exposureText = "%.3f".format(it); autoExposure = false; updateActiveControls() }, { applyManualInputs() }, { editingParameter = if (it) "曝光" else null })
                                ParameterControl("對焦", "D", focusDiopter, focusText, 0f..focusMax, autoFocus,
                                    { autoFocus = it; updateActiveControls() }, { focusText = it; autoFocus = false },
                                    { focusDiopter = it; focusText = "%.2f".format(it); autoFocus = false; updateActiveControls() }, { applyManualInputs() }, { editingParameter = if (it) "對焦" else null })
                                ParameterControl("倍率", "×", zoomValue, zoomText, 1f..zoomMax, null,
                                    {}, { zoomText = it },
                                    { zoomValue = it; zoomText = "%.2f".format(it); updateActiveControls() }, { applyManualInputs() }, {})
                                ParameterControl("色溫", "K", tempValue, tempText, 2000f..8000f, autoWhiteBalance,
                                    { autoWhiteBalance = it; updateActiveControls() }, { tempText = it; autoWhiteBalance = false },
                                    { tempValue = it; tempText = it.toInt().toString(); autoWhiteBalance = false; updateActiveControls() }, { applyManualInputs() }, {})
                                Button(onClick = { applyManualInputs() }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("套用輸入數值") }
                            } else {
                                Text("ISO ${isoValue.toInt()}  ·  ${"%.2f".format(exposureMs)} ms  ·  ${"%.2f".format(focusDiopter)} D", style = MaterialTheme.typography.bodyMedium)
                                Text("自動模式持續讀取相機數值；切換手動可分別鎖定各參數。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                    if (sleepScreen) {
                        Box(Modifier.fillMaxSize().background(Color.Black).clickable { sleepScreen = false; updateScreenBrightness() }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("省電黑屏\n輕觸恢復操作", color = Color(0xFF4B5756), style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        }
                    }
                }
            }
            if (sizeMenuExpanded) ChoiceDialog("照片尺寸", resolutionOptions.map { "${it.width} × ${it.height}" }, { sizeMenuExpanded = false }) { index ->
                selectedResolution = resolutionOptions[index]; originalSizeOutput = false; sizeMenuExpanded = false; if (cameraOn) startWithActiveControls()
            }
            if (previewSizeMenuExpanded) {
                ChoiceDialog("傳送解析度", previewResolutionOptions.map { "${it.width} × ${it.height}" }, { previewSizeMenuExpanded = false }) { index ->
                    previewWidth = previewResolutionOptions[index].width; previewHeight = previewResolutionOptions[index].height; previewSizeMenuExpanded = false
                    controller?.setPreviewConfig(previewWidth, previewHeight, previewQuality, previewFps)
                }
            }
            if (previewFpsMenuExpanded) {
                val rates = listOf(5, 10, 15, 20, 30)
                ChoiceDialog("傳送更新率", rates.map { "$it FPS${if (it == 15) " · 省電建議" else ""}" }, { previewFpsMenuExpanded = false }) { index ->
                    val requested = rates[index]; previewFpsMenuExpanded = false
                    val effective = controller?.setPreviewConfig(previewWidth, previewHeight, previewQuality, requested) ?: requested
                    previewFps = effective
                    if (effective < requested) status = "$requested FPS requested · this camera supports $effective FPS in normal mode"
                }
            }
        }
    }
}
