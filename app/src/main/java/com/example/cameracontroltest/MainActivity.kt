package com.example.cameracontroltest

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
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

  private var cameraId: String? = null
  private var physicalCameraId: String? = null
  private lateinit var wideCameraInfo: CameraInfoService.ExtendedCameraInfo
  private lateinit var superWideCameraInfo: CameraInfoService.ExtendedCameraInfo
  private lateinit var telephotoCameraInfo: CameraInfoService.ExtendedCameraInfo

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    surfaceView = findViewById(R.id.surfaceView)
    surfaceHolder = surfaceView.holder
    wideButton = findViewById(R.id.button_wide)
    superWideButton = findViewById(R.id.button_superwide)
    telephotoButton = findViewById(R.id.button_telephoto)
    captureButton = findViewById(R.id.button_capture)

    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Request permissions
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
      return
    }

    wideButton.setOnClickListener { switchCamera(wideCameraInfo.cameraId) }
    superWideButton.setOnClickListener { switchCamera(superWideCameraInfo.cameraId) }
    telephotoButton.setOnClickListener { switchCamera(telephotoCameraInfo.cameraId) }
    captureButton.setOnClickListener {
      capturePhoto()
    }

    setupCamera()
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

      imageReader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1)
      imageReader.setOnImageAvailableListener({ reader ->
        val image = reader.acquireLatestImage()
        saveImage(image.planes[0].buffer)
        image.close()
      }, null)

      // Create a CaptureRequest builder for preview
      previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      previewRequestBuilder.addTarget(surface)

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
              captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, null)
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

  private fun capturePhoto() {
    try {
      val captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
      captureRequestBuilder.apply {
        addTarget(imageReader.surface)

        val rotation = windowManager.defaultDisplay.rotation
        val sensorOrientation = cameraCharacteristics[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0
        val jpegOrientation = (sensorOrientation + rotationToDegrees(rotation) + 360) % 360

        set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
      }

      // Lock focus and capture
      captureSession?.capture(captureRequestBuilder.build(), null, null)
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