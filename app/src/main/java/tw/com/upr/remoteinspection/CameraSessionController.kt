package tw.com.upr.remoteinspection

import android.annotation.SuppressLint
import android.hardware.camera2.*
import android.hardware.camera2.DngCreator
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.util.Range
import android.media.ImageReader
import android.media.Image
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import android.os.Environment
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.Rect
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executor
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.nio.BufferUnderflowException

class CameraSessionController(private val cameraManager: CameraManager, private val textureView: TextureView, private val onStatus: (String) -> Unit, private val onPhotoBytes: ((ByteArray) -> Unit)? = null, private val onPreviewBytes: ((ByteArray) -> Unit)? = null, private val onRawBytes: ((ByteArray) -> Unit)? = null, private val onActualControls: ((Int?, Float?, Float?, Float?) -> Unit)? = null, private val shouldStreamPreview: () -> Boolean = { true }, private val onPreviewFpsChanged: (Int) -> Unit = {}) {
    private val thread = HandlerThread("camera2-phase2").apply { start() }
    private val handler = Handler(thread.looper)
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var captureCallback: CameraCaptureSession.CaptureCallback? = null
    private var stillReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var yuvReader: ImageReader? = null
    private var rawCharacteristics: CameraCharacteristics? = null
    private var rawEnabled = false
    private var yuvPreview = false
    private var pendingRawImage: Image? = null
    private var pendingRawResult: TotalCaptureResult? = null
    private var previewLoop: Runnable? = null
    private var previewSurface: Surface? = null
    private var requestBuilder: CaptureRequest.Builder? = null
    private var activeCameraId: String? = null
    private var activePhysicalId: String? = null
    private var activeResolution: android.util.Size? = null
    private var currentIso: Int? = null
    private var currentExposureNs: Long? = null
    private var currentFocus: Float? = null
    private var currentZoom: Float? = null
    private var currentAwbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var currentTemperatureK: Int? = null
    private var autoIso = true
    private var autoExposure = true
    private var autoFocus = true
    private var autoWhiteBalance = true
    private var isoRange: android.util.Range<Int>? = null
    private var exposureRange: android.util.Range<Long>? = null
    private var lastAutoAdjustMs = 0L
    private var activeArray: Rect? = null
    private var jpegOrientation: Int = 0
    @Volatile private var previewWidth = 720
    @Volatile private var previewHeight = 960
    @Volatile private var previewQuality = 45
    @Volatile private var previewFps = 30
    @Volatile private var effectivePreviewFps = 30
    @Volatile private var previewEnabled = true
    @Volatile private var configGeneration = 0L
    @Volatile private var yuvEncoding = false
    private var lastYuvFrameMs = 0L
    private var lastReadbackMs = 0L
    private var yuvBuffer = ByteArray(0)
    private val previewOutput = ByteArrayOutputStream(128 * 1024)
    private val meterPixels = IntArray(32 * 24)
    private var availableFpsRanges: Array<Range<Int>> = emptyArray()
    private var selectedFpsRange: Range<Int>? = null

    fun start(cameraId: String, physicalId: String?, zoom: Float?, iso: Int? = null, exposureUs: Long? = null, focusDiopter: Float? = null, resolution: android.util.Size? = null, temperatureK: Int? = null, rawEnabled: Boolean = false, yuvPreview: Boolean = false) {
        if (android.os.Looper.myLooper() != handler.looper) {
            handler.post { start(cameraId, physicalId, zoom, iso, exposureUs, focusDiopter, resolution, temperatureK, rawEnabled, yuvPreview) }; return
        }
        currentTemperatureK = temperatureK
        if (activeCameraId == cameraId && activePhysicalId == physicalId && activeResolution == resolution && this.rawEnabled == rawEnabled && this.yuvPreview == yuvPreview && requestBuilder != null) {
            if (temperatureK != null) updateControls(iso, exposureUs, focusDiopter, zoom, temperatureK)
            else updateRequest(zoom, iso, exposureUs, focusDiopter)
            return
        }
        close()
        if (!textureView.isAvailable) { onStatus("Preview surface is not ready"); return }
        val generation = configGeneration
        open(cameraId, physicalId, zoom, iso, exposureUs, focusDiopter, resolution, rawEnabled, yuvPreview, generation)
    }

    @SuppressLint("MissingPermission")
    private fun open(cameraId: String, physicalId: String?, zoom: Float?, iso: Int?, exposureUs: Long?, focusDiopter: Float?, resolution: android.util.Size?, rawEnabled: Boolean, yuvPreview: Boolean, generation: Long) {
        onStatus("Opening Camera $cameraId${physicalId?.let { " → physical $it" } ?: ""} …")
        cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (generation != configGeneration) { device.close(); return }
                camera = device
                configure(device, cameraId, physicalId, zoom, iso, exposureUs, focusDiopter, resolution, rawEnabled, yuvPreview, generation)
            }
            override fun onDisconnected(device: CameraDevice) { onStatus("Disconnected"); device.close(); camera = null }
            override fun onError(device: CameraDevice, error: Int) { onStatus("Camera error $error"); device.close(); camera = null }
        }, handler)
    }

    private fun configure(device: CameraDevice, cameraId: String, physicalId: String?, zoom: Float?, iso: Int?, exposureUs: Long?, focusDiopter: Float?, resolution: android.util.Size?, rawEnabled: Boolean, yuvPreview: Boolean, generation: Long) {
        if (generation != configGeneration) { device.close(); return }
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        availableFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        resolvePreviewFps()
        rawCharacteristics = characteristics
        this.rawEnabled = rawEnabled
        this.yuvPreview = yuvPreview
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = when (textureView.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        jpegOrientation = if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensorOrientation + displayDegrees) % 360
        } else {
            (sensorOrientation - displayDegrees + 360) % 360
        }
        activeArray = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        this.isoRange = isoRange
        this.exposureRange = exposureRange
        val focusMax = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        val safeIso = iso?.let { isoRange?.let { range -> it.coerceIn(range.lower, range.upper) } ?: it }
        val safeExposure = exposureUs?.let { exposureRange?.let { range -> it.coerceIn((range.lower / 1000L).coerceAtLeast(1L), range.upper / 1000L) } ?: it }
        val safeFocus = focusDiopter?.let { focusMax?.let { max -> it.coerceIn(0f, max) } ?: it }
        currentIso = safeIso; currentExposureNs = safeExposure?.times(1000L); currentFocus = safeFocus; currentZoom = zoom
        displayBufferWidth = previewWidth
        displayBufferHeight = previewHeight
        displaySensorOrientation = sensorOrientation
        textureView.surfaceTexture?.setDefaultBufferSize(displayBufferWidth, displayBufferHeight)
        val surface = Surface(textureView.surfaceTexture)
        previewSurface = surface
        refreshPreviewTransform()
        val outputs = mutableListOf<OutputConfiguration>()
        outputs += OutputConfiguration(surface)
        val streamSurface = surface
        val selectedSize = resolution ?: android.util.Size(1920, 1080)
        val reader = ImageReader.newInstance(selectedSize.width, selectedSize.height, android.graphics.ImageFormat.JPEG, 2)
        stillReader = reader
        reader.setOnImageAvailableListener({ source -> source.acquireLatestImage()?.use { image ->
            val resolver = textureView.context.contentResolver
            val name = "MVC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Machine Vision Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) { onStatus("PHOTO FAILED · cannot create MediaStore item"); return@setOnImageAvailableListener }
            val jpeg = image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also(b::get) }
            resolver.openOutputStream(uri)?.use { out -> out.write(jpeg) }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            onPhotoBytes?.invoke(jpeg)
            onStatus("PHOTO SAVED · DCIM/Machine Vision Camera/$name")
        } }, handler)
        if (rawEnabled) {
            val rawSizes = map?.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR).orEmpty()
            val rawSize = rawSizes.maxByOrNull { it.width.toLong() * it.height.toLong() }
            if (rawSize == null) {
                onStatus("RAW unavailable · camera=$cameraId")
                this.rawEnabled = false
            } else {
                rawReader = ImageReader.newInstance(rawSize.width, rawSize.height, android.graphics.ImageFormat.RAW_SENSOR, 2)
                rawReader?.setOnImageAvailableListener({ source ->
                    pendingRawImage?.close()
                    pendingRawImage = source.acquireLatestImage()
                    writePendingRaw()
                }, handler)
            }
        }
        if (yuvPreview) {
            val yuvSizes = map?.getOutputSizes(android.graphics.ImageFormat.YUV_420_888).orEmpty()
            val yuvSize = yuvSizes.minByOrNull { kotlin.math.abs(it.width - previewWidth) + kotlin.math.abs(it.height - previewHeight) }
            if (yuvSize == null) {
                onStatus("YUV preview unavailable · camera=$cameraId")
                this.yuvPreview = false
            } else {
                yuvReader = ImageReader.newInstance(yuvSize.width, yuvSize.height, android.graphics.ImageFormat.YUV_420_888, 2)
                yuvReader?.setOnImageAvailableListener({ source ->
                    val image = runCatching { source.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                    try {
                        if (generation != configGeneration || yuvEncoding) return@setOnImageAvailableListener
                        yuvEncoding = true
                        runCatching { encodeYuvPreview(image) }
                            .onFailure {
                                if (it !is IllegalStateException && it !is BufferUnderflowException && it !is IndexOutOfBoundsException) {
                                    onStatus("YUV preview frame dropped · ${it.message ?: it::class.java.simpleName}")
                                }
                            }
                    } finally {
                        yuvEncoding = false
                        runCatching { image.close() }
                    }
                }, handler)
            }
        }
        val output = outputs.first()
        if (Build.VERSION.SDK_INT >= 28 && physicalId != null) output.setPhysicalCameraId(physicalId)
        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(value: CameraCaptureSession) {
                if (generation != configGeneration) { value.close(); return }
                session = value
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(streamSurface)
                    yuvReader?.surface?.let { addTarget(it) }
                    if (this@CameraSessionController.rawEnabled || this@CameraSessionController.yuvPreview) applyInspectionProcessingOff(this)
                    set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                    set(CaptureRequest.CONTROL_AF_MODE, if (safeFocus != null) CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    if (safeFocus != null) set(CaptureRequest.LENS_FOCUS_DISTANCE, safeFocus)
                    if (safeIso != null || safeExposure != null) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        if (safeIso != null) set(CaptureRequest.SENSOR_SENSITIVITY, safeIso)
                        if (safeExposure != null) set(CaptureRequest.SENSOR_EXPOSURE_TIME, safeExposure * 1000L)
                    }
                    if (zoom != null) applyZoom(this, zoom)
                }.build()
                requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(streamSurface)
                    yuvReader?.surface?.let { addTarget(it) }
                }
                activeCameraId = cameraId
                activePhysicalId = physicalId
                activeResolution = selectedSize
                captureCallback = object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, r: TotalCaptureResult) {
                        val now = android.os.SystemClock.uptimeMillis()
                        if (generation != configGeneration || now - lastReadbackMs < 250) return
                        lastReadbackMs = now
                        val active = if (Build.VERSION.SDK_INT >= 29) r.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) else null
                        val actualZoom = if (Build.VERSION.SDK_INT >= 30) r.get(CaptureResult.CONTROL_ZOOM_RATIO) else null
                        val lens = r.get(CaptureResult.LENS_FOCAL_LENGTH)
                        val actualIso = r.get(CaptureResult.SENSOR_SENSITIVITY)
                        val actualExposure = r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { it / 1_000_000f }
                        val actualFocus = r.get(CaptureResult.LENS_FOCUS_DISTANCE)
                        onActualControls?.invoke(actualIso, actualExposure, actualFocus, actualZoom)
                        onStatus("STREAMING · camera=$cameraId · active=${active ?: "n/a"} · zoom=${actualZoom ?: "n/a"} · focal=${lens ?: "n/a"} mm · ISO=${actualIso ?: "auto"} · exp=${actualExposure ?: "auto"}ms")
                    }
                }
                updateControls(currentIso, currentExposureNs?.div(1000L), currentFocus, currentZoom, currentTemperatureK)
            }
            override fun onConfigureFailed(value: CameraCaptureSession) { onStatus("Session configure failed · physical=${physicalId ?: "logical"}"); value.close() }
        }
        val sessionOutputs = (outputs + OutputConfiguration(reader.surface)).toMutableList()
        rawReader?.surface?.let { sessionOutputs += OutputConfiguration(it) }
        yuvReader?.surface?.let { sessionOutputs += OutputConfiguration(it) }
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                device.createCaptureSession(SessionConfiguration(SessionConfiguration.SESSION_REGULAR, sessionOutputs, Executor { handler.post(it) }, callback))
            }.onFailure {
                // Rapid RAW/YUV transitions can make the OEM camera service
                // reject the old stream graph with a transient broken pipe.
                // This is recoverable; never let it escape camera2-phase2.
                if (generation == configGeneration) {
                    onStatus("Session configure failed · ${it.message ?: it::class.java.simpleName}")
                    close()
                }
            }
        }
        if (!this.yuvPreview) startPreviewLoop()
    }

    private fun encodeYuvPreview(image: Image) {
        val now = android.os.SystemClock.uptimeMillis()
        val yPlane = image.planes[0]
        if (autoIso != autoExposure && now - lastAutoAdjustMs >= 250L) {
            val buffer = yPlane.buffer.duplicate()
            var sum = 0L; var count = 0
            for (row in 0 until image.height step maxOf(1, image.height / 24)) {
                for (col in 0 until image.width step maxOf(1, image.width / 32)) {
                    sum += buffer.get(row * yPlane.rowStride + col * yPlane.pixelStride).toInt() and 255
                    count++
                }
            }
            if (count > 0) adjustAutoExposure(sum.toFloat() / count / 255f)
        }
        if (onPreviewBytes == null || !previewEnabled || !shouldStreamPreview()) return
        if (now - lastYuvFrameMs < (1000L + effectivePreviewFps - 1) / effectivePreviewFps) return
        lastYuvFrameMs = now
        val width = image.width; val height = image.height
        val y = yPlane.buffer.duplicate()
        if (yuvBuffer.size != width * height * 3 / 2) {
            yuvBuffer = ByteArray(width * height * 3 / 2) { 128.toByte() }
        }
        val nv21 = yuvBuffer
        val yRowStride = yPlane.rowStride; val yPixelStride = yPlane.pixelStride
        var out = 0
        for (row in 0 until height) {
            val rowStart = row * yRowStride
            if (yPixelStride == 1) {
                y.position(rowStart); y.get(nv21, out, width); out += width
            } else for (col in 0 until width) { nv21[out++] = y.get(rowStart + col * yPixelStride) }
        }
        // Measurement preview intentionally uses only the Y plane.  Neutral
        // chroma produces a grayscale image, avoiding colour processing and
        // reducing both conversion work and network payload relevance.
        previewOutput.reset()
        previewOutput.let { outStream ->
            android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), previewQuality, outStream)
            onPreviewBytes.invoke(outStream.toByteArray())
        }
    }

    private fun updateRequest(zoom: Float?, iso: Int?, exposureUs: Long?, focusDiopter: Float?) {
        val builder = requestBuilder ?: return
        currentIso = iso; currentExposureNs = exposureUs?.times(1000L); currentFocus = focusDiopter; if (zoom != null) currentZoom = zoom
        builder.set(CaptureRequest.CONTROL_AF_MODE, if (focusDiopter != null) CaptureRequest.CONTROL_AF_MODE_OFF else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        if (focusDiopter != null) builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopter)
        if (!autoIso || !autoExposure) {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            currentIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            currentExposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
        } else builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        if (zoom != null) applyZoom(builder, zoom)
        selectedFpsRange?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
        if (rawEnabled || yuvPreview) applyInspectionProcessingOff(builder)
        applyWhiteBalance(builder, if (autoWhiteBalance) null else currentTemperatureK)
        runCatching { session?.setRepeatingRequest(builder.build(), captureCallback, handler) }
            .onFailure { onStatus("Control update failed · ${it.message}") }
    }

    fun setAutoModes(iso: Boolean, exposure: Boolean, focus: Boolean, whiteBalance: Boolean) {
        if (android.os.Looper.myLooper() != handler.looper) { handler.post { setAutoModes(iso, exposure, focus, whiteBalance) }; return }
        autoIso = iso; autoExposure = exposure; autoFocus = focus; autoWhiteBalance = whiteBalance
        if (requestBuilder != null && (!autoIso || !autoExposure)) {
            if (currentIso == null) currentIso = isoRange?.lower
            if (currentExposureNs == null) currentExposureNs = exposureRange?.lower
        }
    }

    fun updateControls(iso: Int?, exposureUs: Long?, focusDiopter: Float?, zoom: Float? = null, temperatureK: Int? = null) {
        if (android.os.Looper.myLooper() != handler.looper) { handler.post { updateControls(iso, exposureUs, focusDiopter, zoom, temperatureK) }; return }
        currentTemperatureK = temperatureK
        updateRequest(zoom, iso, exposureUs, focusDiopter)
    }

    fun setPreviewConfig(width: Int, height: Int, quality: Int, fps: Int): Int {
        previewWidth = width.coerceIn(320, 1280)
        previewHeight = height.coerceIn(320, 1920)
        previewQuality = quality.coerceIn(30, 90)
        previewFps = fps.coerceIn(1, 30)
        resolvePreviewFps()
        return effectivePreviewFps
    }

    fun effectivePreviewFps(): Int = effectivePreviewFps

    private fun resolvePreviewFps() {
        val requested = previewFps
        selectedFpsRange = availableFpsRanges
            .filter { it.lower <= requested && it.upper >= requested }
            .minByOrNull { it.upper }
            ?: availableFpsRanges.maxByOrNull { it.upper }
        effectivePreviewFps = minOf(requested, selectedFpsRange?.upper ?: requested).coerceAtLeast(1)
        onPreviewFpsChanged(effectivePreviewFps)
        if (effectivePreviewFps < requested) {
            onStatus("$requested FPS requested · normal Camera2 session supports up to $effectivePreviewFps FPS")
        }
    }

    fun setPreviewEnabled(enabled: Boolean) {
        if (android.os.Looper.myLooper() != handler.looper) { handler.post { setPreviewEnabled(enabled) }; return }
        previewEnabled = enabled
        if (!yuvPreview && camera != null) startPreviewLoop()
        else { previewLoop?.let(handler::removeCallbacks); previewLoop = null }
    }

    fun capture() {
        if (android.os.Looper.myLooper() != handler.looper) { handler.post { capture() }; return }
        val d = camera ?: return onStatus("PHOTO FAILED · camera is not open")
        val s = session ?: return onStatus("PHOTO FAILED · session is not ready")
        val target = stillReader?.surface ?: return onStatus("PHOTO FAILED · image reader is not ready")
        runCatching {
            val request = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(target)
                rawReader?.surface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                applyWhiteBalance(this, currentTemperatureK)
                if (!autoIso || !autoExposure) {
                    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    currentIso?.let { set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                    currentExposureNs?.let { set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
                }
                currentFocus?.let { set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
                currentZoom?.let { applyZoom(this, it) }
                if (rawEnabled || yuvPreview) applyInspectionProcessingOff(this)
            }.build()
            s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    pendingRawResult = result
                    writePendingRaw()
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                    val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.div(1_000_000L)
                    onStatus("PHOTO CAPTURED · actual ISO=${iso ?: "auto"} · exposure=${exposure ?: "auto"}ms")
                }
            }, handler)
            onStatus("PHOTO CAPTURE REQUESTED")
        }.onFailure { onStatus("PHOTO FAILED · ${it.message}") }
    }

    private fun writePendingRaw() {
        val image = pendingRawImage ?: return
        val result = pendingRawResult ?: return
        val characteristics = rawCharacteristics ?: return
        pendingRawImage = null
        pendingRawResult = null
        runCatching {
            val bytes = ByteArrayOutputStream().use { out ->
                DngCreator(characteristics, result).use { it.writeImage(out, image) }
                out.toByteArray()
            }
            image.close()
            val resolver = textureView.context.contentResolver
            val name = "MVC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.dng"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/Machine Vision Camera")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                onRawBytes?.invoke(bytes)
                onStatus("RAW SAVED · DCIM/Machine Vision Camera/$name")
            } else onStatus("RAW FAILED · cannot create MediaStore item")
        }.onFailure { image.close(); onStatus("RAW FAILED · ${it.message}") }
    }

    private fun applyWhiteBalance(builder: CaptureRequest.Builder, temperatureK: Int?) {
        if (temperatureK == null) {
            currentAwbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO
            builder.set(CaptureRequest.CONTROL_AWB_MODE, currentAwbMode)
            return
        }
        val k = temperatureK.coerceIn(2000, 8000)
        val t = (k - 2000f) / 6000f
        // Camera2 manual gains provide continuous Kelvin-like adjustment.
        val red = 2.4f - 1.4f * t
        val blue = 1.0f + 1.4f * t
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(red, 1.0f, blue, 1.0f))
    }

    private fun applyInspectionProcessingOff(builder: CaptureRequest.Builder) {
        // These are standard Camera2 request keys, but OEM support varies;
        // keep each request independent so one unsupported key cannot abort
        // the whole camera session.
        runCatching { builder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF) }
        runCatching { builder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF) }
        runCatching { builder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF) }
        runCatching { builder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF) }
        runCatching { builder.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF) }
        runCatching { builder.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF) }
    }

    private fun applyZoom(builder: CaptureRequest.Builder, zoom: Float) {
        val safeZoom = zoom.coerceAtLeast(1f)
        if (Build.VERSION.SDK_INT >= 30) {
            // CONTROL_ZOOM_RATIO is the platform zoom path.  Combining it
            // with a second crop region can make logical/physical cameras
            // fight each other and report a stale 1x result.
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, safeZoom)
            return
        }
        activeArray?.let { sensor ->
            val width = (sensor.width() / safeZoom).toInt().coerceAtLeast(2)
            val height = (sensor.height() / safeZoom).toInt().coerceAtLeast(2)
            val left = sensor.left + (sensor.width() - width) / 2
            val top = sensor.top + (sensor.height() - height) / 2
            builder.set(CaptureRequest.SCALER_CROP_REGION, Rect(left, top, left + width, top + height))
        }
    }

    private var displayBufferWidth = 0
    private var displayBufferHeight = 0
    private var displaySensorOrientation = 0

    fun refreshPreviewTransform() {
        textureView.post { applyFitTransform(displayBufferWidth, displayBufferHeight) }
    }

    private fun applyFitTransform(bufferWidth: Int, bufferHeight: Int) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return
        val rotation = (textureView.display?.rotation ?: Surface.ROTATION_0) * 90
        // Camera2's producer already applies the sensor orientation. Undo the
        // TextureView's default stretch, then fit once inside the entire view.
        val nativeWidth = if (displaySensorOrientation % 180 != 0) bufferHeight.toFloat() else bufferWidth.toFloat()
        val nativeHeight = if (displaySensorOrientation % 180 != 0) bufferWidth.toFloat() else bufferHeight.toFloat()
        val rotatedWidth = if (rotation % 180 != 0) nativeHeight else nativeWidth
        val rotatedHeight = if (rotation % 180 != 0) nativeWidth else nativeHeight
        val fit = minOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        val matrix = Matrix()
        matrix.setScale(nativeWidth * fit / viewWidth, nativeHeight * fit / viewHeight, viewWidth / 2f, viewHeight / 2f)
        matrix.postRotate(-rotation.toFloat(), viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    private fun startPreviewLoop() {
        previewLoop?.let(handler::removeCallbacks)
        val task = object : Runnable {
            override fun run() {
                if (camera == null || !textureView.isAvailable) return
                val started = android.os.SystemClock.uptimeMillis()
                val streaming = previewEnabled && shouldStreamPreview()
                val metering = autoIso != autoExposure && started - lastAutoAdjustMs >= 250L
                if (!streaming && !metering) { handler.postDelayed(this, 250L); return }
                val bitmap = runCatching { textureView.getBitmap(if (streaming) previewWidth else 32, if (streaming) previewHeight else 24) }.getOrNull()
                if (bitmap == null) { handler.postDelayed(this, 34L); return }
                if (streaming && onPreviewBytes != null) {
                    previewOutput.reset()
                    previewOutput.let { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, previewQuality, out)
                        onPreviewBytes.invoke(out.toByteArray())
                    }
                }
                if (metering) {
                    val sample = Bitmap.createScaledBitmap(bitmap, 32, 24, false)
                    val pixels = meterPixels
                    sample.getPixels(pixels, 0, 32, 0, 0, 32, 24)
                    var sum = 0L
                    pixels.forEach { p -> sum += (0.299 * ((p shr 16) and 255) + 0.587 * ((p shr 8) and 255) + 0.114 * (p and 255)).toLong() }
                    adjustAutoExposure(sum.toFloat() / pixels.size / 255f)
                    if (sample !== bitmap) sample.recycle()
                }
                bitmap.recycle()
                val exposureDelayMs = (currentExposureNs ?: 0L) / 1_000_000L
                handler.postDelayed(this, maxOf(1L, maxOf((1000L + effectivePreviewFps - 1) / effectivePreviewFps, exposureDelayMs) - (android.os.SystemClock.uptimeMillis() - started)))
            }
        }
        previewLoop = task
        handler.post(task)
    }

    private fun adjustAutoExposure(luma: Float) {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAutoAdjustMs < 250L || luma <= 0f) return
        lastAutoAdjustMs = now
        val factor = (0.45f / luma).coerceIn(0.7f, 1.4f)
        if (autoExposure) currentExposureNs = ((currentExposureNs ?: exposureRange?.lower ?: 1_000_000L) * factor).toLong().coerceIn(exposureRange?.lower ?: 1L, exposureRange?.upper ?: 1_000_000_000L)
        if (autoIso) currentIso = ((currentIso ?: isoRange?.lower ?: 100) * factor).toInt().coerceIn(isoRange?.lower ?: 50, isoRange?.upper ?: 3200)
        requestBuilder?.let { builder ->
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            currentIso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
            currentExposureNs?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            runCatching { session?.setRepeatingRequest(builder.build(), captureCallback, handler) }
        }
    }

    fun close() {
        if (android.os.Looper.myLooper() != handler.looper) { handler.post { close() }; return }
        configGeneration += 1L
        yuvEncoding = false
        previewLoop?.let(handler::removeCallbacks)
        previewLoop = null
        runCatching { session?.stopRepeating() }
        session?.close()
        session = null
        stillReader?.setOnImageAvailableListener(null, null)
        rawReader?.setOnImageAvailableListener(null, null)
        yuvReader?.setOnImageAvailableListener(null, null)
        stillReader?.close(); stillReader = null
        rawReader?.close(); rawReader = null
        yuvReader?.close(); yuvReader = null
        pendingRawImage?.close(); pendingRawImage = null
        pendingRawResult = null
        previewSurface?.release(); previewSurface = null
        requestBuilder = null
        activeCameraId = null
        activePhysicalId = null
        activeResolution = null
        camera?.close(); camera = null
    }
    fun release() { handler.post { close(); thread.quitSafely() } }
}
