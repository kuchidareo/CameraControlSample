package com.example.cameracontroltest

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Locale


class MainActivity : AppCompatActivity() {

  private lateinit var cameraManager: CameraManager
  private lateinit var cameraCharacteristics: CameraCharacteristics
  private var cameraDevice: CameraDevice? = null
  private lateinit var previewRequestBuilder: CaptureRequest.Builder
  private var captureSession: CameraCaptureSession? = null
  private lateinit var imageReader: ImageReader

  private lateinit var surfaceView: SurfaceView
  private lateinit var surfaceHolder: SurfaceHolder
  private lateinit var wideButton: Button
  private lateinit var superWideButton: Button
  private lateinit var telephotoButton: Button
  private lateinit var captureButton: Button

  private lateinit var focusGestureDetector: GestureDetector
  private lateinit var scaleGestureDetector: ScaleGestureDetector
  private var zoomLevel = 1f
  private var maxZoom: Float = 1f
  private val minZoom: Float = 0.5f
  private lateinit var sensorArraySize: Rect

  private var backgroundThread: HandlerThread? = null
  private var backgroundHandler: Handler? = null

  private var cameraId: String? = null
  private var physicalCameraId: String? = null
  private lateinit var wideCameraInfo: CameraInfoService.ExtendedCameraInfo
  private lateinit var superWideCameraInfo: CameraInfoService.ExtendedCameraInfo
  private lateinit var telephotoCameraInfo: CameraInfoService.ExtendedCameraInfo

  private val AF_TOUCH_WIDTH = 150
  private val AF_TOUCH_HEIGHT = 150

  private val previewWidth = 1440
  private val previewHeight = 1080
  private val captureWidth = 4032
  private val captureHeight = 3024

  @SuppressLint("ClickableViewAccessibility")
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    surfaceView = findViewById(R.id.surfaceView)
    surfaceHolder = surfaceView.holder
    surfaceHolder.setFixedSize(previewWidth, previewHeight)
    wideButton = findViewById(R.id.button_wide)
    superWideButton = findViewById(R.id.button_superwide)
    telephotoButton = findViewById(R.id.button_telephoto)
    captureButton = findViewById(R.id.button_capture)

    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    wideButton.setOnClickListener { switchCamera(wideCameraInfo.cameraId) }
    superWideButton.setOnClickListener { switchCamera(superWideCameraInfo.cameraId) }
    telephotoButton.setOnClickListener { switchCamera(telephotoCameraInfo.cameraId) }
    captureButton.setOnClickListener { capturePhoto() }

    // Initialize GestureDetector
    focusGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
      override fun onSingleTapUp(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        try {
          handleTapToFocus(x, y)
        } catch (e: Exception) {
          Log.i(TAG, e.toString())
        }
        return true
      }
    })

    scaleGestureDetector = ScaleGestureDetector(this, object: ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScale(detector: ScaleGestureDetector): Boolean {
        val scaleFactor = detector.scaleFactor
        handlePinchToZoom(scaleFactor)
        return true
      }
    })

    surfaceView.setOnTouchListener { _, event ->
      scaleGestureDetector.onTouchEvent(event)
      if (!scaleGestureDetector.isInProgress) {
        focusGestureDetector.onTouchEvent(event)
      }
      return@setOnTouchListener true
    }
  }

  override fun onResume() {
    super.onResume()
    startBackgroundThread()

    // Request permissions
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
      return
    }
    setupCamera()
  }

  override fun onPause() {
    stopBackgroundThread()
    super.onPause()
  }

  private fun startBackgroundThread() {
    backgroundThread = HandlerThread("CameraBackground").apply { start() }
    backgroundHandler = Handler(backgroundThread!!.looper)
  }

  private fun stopBackgroundThread() {
    backgroundThread?.quitSafely()
    try {
      backgroundThread?.join()
      backgroundThread = null
      backgroundHandler = null
    } catch (e: InterruptedException) {
      e.printStackTrace()
    }
  }

  private fun setupCamera() {
    try {
      for (id in cameraManager.cameraIdList) {
        val cameraCharacteristics = cameraManager.getCameraCharacteristics(id)

        val lensFacing = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING)
        if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
          continue
        }

        val capabilities = cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if ((capabilities != null) && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
          cameraId = id
          val physicalIds = cameraCharacteristics.physicalCameraIds
          CameraInfoService.initService(physicalIds.toList(), this)
          wideCameraInfo = CameraInfoService.getWideRangeCameraInfo()!!
          superWideCameraInfo = CameraInfoService.getSuperWideRangeCameraInfo()!!
          telephotoCameraInfo = CameraInfoService.getTelephotoCameraInfo()!!
          physicalCameraId = wideCameraInfo.cameraId
        }
      }

      if (cameraId == null) {
        Toast.makeText(this, "No logical multi-camera found", Toast.LENGTH_SHORT).show()
        return
      }

      openCamera()

    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun openCamera() {
    try {
      if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
        return
      }
      cameraManager.openCamera(cameraId!!, object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
          cameraDevice = camera
          cameraCharacteristics = physicalCameraId?.let { cameraManager.getCameraCharacteristics(it) }!!
          maxZoom = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)!!
          sensorArraySize = cameraCharacteristics[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]!!
          startPreview()
        }

        override fun onDisconnected(camera: CameraDevice) {
          camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
          camera.close()
        }
      }, null)
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun startPreview() {
    try {
      if (cameraDevice == null) return

      // Create the target surfaces
      val surface = surfaceHolder.surface

      imageReader = ImageReader.newInstance(captureWidth, captureHeight, ImageFormat.JPEG, 1)
      imageReader.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage()
        saveImage(image.planes[0].buffer)
        image.close()
      }, null)

      // Create a CaptureRequest builder for preview
      previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      previewRequestBuilder.apply {
        addTarget(surface)
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
      }

      // Configure the session with physical camera outputs
      cameraDevice!!.createCaptureSessionByOutputConfigurations(
        listOf(
          OutputConfiguration(surface).apply {
            physicalCameraId?.let { setPhysicalCameraId(it) }
          },
          OutputConfiguration(imageReader.surface).apply {
            physicalCameraId?.let { setPhysicalCameraId(it) }
          }
        ),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            try {
              // Start the preview
              captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
            } catch (e: CameraAccessException) {
              e.printStackTrace()
            }
          }

          override fun onConfigureFailed(session: CameraCaptureSession) {
            Toast.makeText(this@MainActivity, "Configuration failed", Toast.LENGTH_SHORT).show()
          }
        },
        null
      )
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun handlePinchToZoom(scaleFactor: Float) {
    var newZoomLevel = (zoomLevel * scaleFactor)
    if (newZoomLevel > maxZoom) {
      newZoomLevel = maxZoom
    }
    if (newZoomLevel != zoomLevel) {
      zoomLevel = newZoomLevel
      val cropRegion = createZoomArea(zoomLevel)
      previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
      captureSession!!.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
    }
  }

  private fun createZoomArea(zoomScale: Float): Rect? {
    if (sensorArraySize == null) return null
    var zoom = 0f
    if (zoomScale > maxZoom) {
      // limit by　maximum value, if  exceeded
      zoom = maxZoom
    } else if (zoomScale > minZoom) {
      // set zoom, when larger than threshold
      zoom = zoomScale
    } else {
      // return original sensor size, when smaller than threshold
      return sensorArraySize
    }

    // sensor size
    val sensor_width: Int = sensorArraySize.width()
    val sensor_height: Int = sensorArraySize.height()
    val center_x: Int = sensorArraySize.centerX()
    val center_y: Int = sensorArraySize.centerY()

    // zoom size
    val zoom_width = (sensor_width.toFloat() / zoom).toInt()
    val zoom_height = (sensor_height.toFloat() / zoom).toInt()

    // rectangle
    val right = center_x - zoom_width / 2
    val left = center_x + zoom_width / 2
    val top = center_y - zoom_height / 2
    val bottom = center_y + zoom_height / 2
    val rect = Rect(right, top, left, bottom)
    return rect
  }


  private fun handleTapToFocus(x: Float, y: Float) {
    val surface = surfaceHolder.surface
    val viewWidth = surfaceView.width
    val viewHeight = surfaceView.height
    val meteringRectangle = createAutoFocusAreaRect(x, y, viewWidth, viewHeight, zoomLevel)

    stopRepeating()
    cancelAutoFocusTrigger()

    previewRequestBuilder.apply {
      addTarget(surface)
      set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
      set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
      set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle))
    }

    captureSession!!.capture(previewRequestBuilder.build(), null, backgroundHandler)
    continueRepeating()
  }

  private fun stopRepeating() {
    try {
      captureSession?.stopRepeating()
    } catch (ex: CameraAccessException) {
      ex.printStackTrace()
    }
  }

  private fun continueRepeating() {
    previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, null)
    try {
      captureSession!!.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler)
    } catch (ex: CameraAccessException) {
      ex.printStackTrace()
    }
  }

  private fun cancelAutoFocusTrigger() {
    try {
      previewRequestBuilder.apply {
        set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
      }
//      captureSession!!.capture(previewRequestBuilder.build(), null, backgroundHandler)
    } catch (ex: CameraAccessException) {
      ex.printStackTrace()
    }
  }

  private fun createAutoFocusAreaRect(
    screenX: Float,
    screenY: Float,
    screenW: Int,
    screenH: Int,
    zoomLevel: Float
  ): MeteringRectangle? {
    // xy coordinates of camera sensor
    val point: Point = convAutoFocusTouch(screenX, screenY, screenW, screenH, zoomLevel)
    val sensor_x = point.x
    val sensor_y = point.y

    // origin(lef, right) of rectangle
    val touch_x = (sensor_x - AF_TOUCH_WIDTH / 2) as Int
    val touch_y = (sensor_y - AF_TOUCH_HEIGHT / 2) as Int
    val orig_x = Math.max(touch_x, 0)
    val orig_y = Math.max(touch_y, 0)
    val metering_weight = MeteringRectangle.METERING_WEIGHT_MAX - 1
    val focusAreaTouch = MeteringRectangle(
      orig_x,
      orig_y,
      AF_TOUCH_WIDTH,
      AF_TOUCH_HEIGHT,
      metering_weight
    )

    return focusAreaTouch
  }

  /**
   * convert xy coordinates of viewto xy coordinates of sensor
   */
  private fun convAutoFocusTouch(
    screenX: Float,
    screenY: Float,
    screenW: Int,
    screenH: Int,
    zoomLevel: Float
  ): Point {

    val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val displayRotation = windowManager.defaultDisplay.rotation
    val sensorOrientation = cameraCharacteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!

    val ratioX = screenX / screenW.toFloat()
    val ratioY = screenY / screenH.toFloat()
    val sensorWidth: Int = sensorArraySize.width() ?: 0
    val sensorHeight: Int = sensorArraySize.height() ?: 0
    var sensorX = (ratioX * sensorWidth).toInt()
    var sensorY = (ratioY * sensorHeight).toInt()

    when (displayRotation) {
      Surface.ROTATION_0 -> {
        if (sensorOrientation == 90) {
          sensorX = (ratioY * sensorWidth).toInt()
          sensorY = ((1 - ratioX) * sensorHeight).toInt()
        } else if (sensorOrientation == 270) {
          sensorX = ((1 - ratioY) * sensorWidth).toInt()
          sensorY = (ratioX * sensorHeight).toInt()
        }
      }
      Surface.ROTATION_90 -> {
        if (sensorOrientation == 90) {
          sensorX = ((1 - ratioX) * sensorWidth).toInt()
          sensorY = ((1 - ratioY) * sensorHeight).toInt()
        } else if (sensorOrientation == 270) {
          // No change needed for this configuration
        }
      }
      Surface.ROTATION_180 -> {
        if (sensorOrientation == 90) {
          sensorX = ((1 - ratioY) * sensorWidth).toInt()
          sensorY = (ratioX * sensorHeight).toInt()
        } else if (sensorOrientation == 270) {
          sensorX = (ratioY * sensorWidth).toInt()
          sensorY = ((1 - ratioX) * sensorHeight).toInt()
        }
      }
      Surface.ROTATION_270 -> {
        if (sensorOrientation == 90) {
          // No change needed for this configuration
        } else if (sensorOrientation == 270) {
          sensorX = ((1 - ratioX) * sensorWidth).toInt()
          sensorY = ((1 - ratioY) * sensorHeight).toInt()
        }
      }
    }

    // Apply zoom level
    val zoomFactor = 1 / zoomLevel
    val zoomedWidth = (sensorWidth * zoomFactor).toInt()
    val zoomedHeight = (sensorHeight * zoomFactor).toInt()
    val cropX = (sensorWidth - zoomedWidth) / 2
    val cropY = (sensorHeight - zoomedHeight) / 2

    // Adjust sensor coordinates based on the zoom crop
    sensorX = (cropX + sensorX * zoomFactor).toInt().coerceIn(0, sensorWidth - 1)
    sensorY = (cropY + sensorY * zoomFactor).toInt().coerceIn(0, sensorHeight - 1)

    return Point(sensorX, sensorY)
  }


  private fun capturePhoto() {
    try {
      val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
      captureRequestBuilder.apply {
        addTarget(imageReader.surface)

        val rotation = windowManager.defaultDisplay.rotation
        val sensorOrientation = cameraCharacteristics[CameraCharacteristics.SENSOR_ORIENTATION]!!
        val jpegOrientation = (sensorOrientation + rotationToDegrees(rotation) + 360) % 360

        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
      }

      // Lock focus and capture
      captureSession?.capture(captureRequestBuilder.build(), null, backgroundHandler)
    } catch (e: CameraAccessException) {
      e.printStackTrace()
    }
  }

  private fun rotationToDegrees(rotation: Int): Int {
    return when (rotation) {
      Surface.ROTATION_0 -> 0
      Surface.ROTATION_90 -> 90
      Surface.ROTATION_180 -> 180
      Surface.ROTATION_270 -> 270
      else -> 0
    }
  }

  private fun saveImage(buffer: ByteBuffer) {
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    val name = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + "_" + "1" + "_" + "1234"
    val dirname = name.substring(IntRange(0, 7)) // Uriはファイル名しか取って来れないから
    val edanikuId = "1234"
    val contentValues = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, name)
      put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
      put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camera-Control-Test/$dirname/$edanikuId")
    }

    val imageUri: Uri? = contentResolver.insert(
      MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
      contentValues
    )

    if (imageUri != null) {
      contentResolver.openOutputStream(imageUri)?.use { outputStream ->
        outputStream.write(bytes)
        outputStream.flush()
        Toast.makeText(this, "Photo saved: ${imageUri.encodedPath}", Toast.LENGTH_SHORT).show()
      }
    }

  }

  private fun switchCamera(id: String) {
    physicalCameraId = id
    openCamera() // Reopen the camera with the new cameraId
  }

  override fun onDestroy() {
    super.onDestroy()
    cameraDevice?.close()
    cameraDevice = null
  }

  companion object {
    private const val TAG = "Camera Control Test"
    private const val FILENAME_FORMAT = "yyyyMMddHHmmss"
    private val REQUIRED_PERMISSIONS =
      mutableListOf (
        Manifest.permission.CAMERA,
        Manifest.permission.VIBRATE
      ).toTypedArray()
  }
}