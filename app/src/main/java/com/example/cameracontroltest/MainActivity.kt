package com.example.cameracontroltest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat

class MainActivity : AppCompatActivity() {

  private lateinit var cameraManager: CameraManager
  private var cameraDevice: CameraDevice? = null
  private lateinit var captureRequestBuilder: CaptureRequest.Builder
  private var captureSession: CameraCaptureSession? = null

  private lateinit var surfaceView: SurfaceView
  private lateinit var surfaceHolder: SurfaceHolder
  private lateinit var wideButton: Button
  private lateinit var superWideButton: Button
  private lateinit var telephotoButton: Button

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

    cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Request permissions
    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 101)
      return
    }

    wideButton.setOnClickListener { switchCamera(wideCameraInfo.cameraId) }
    superWideButton.setOnClickListener { switchCamera(superWideCameraInfo.cameraId) }
    telephotoButton.setOnClickListener { switchCamera(telephotoCameraInfo.cameraId) }

    setupCamera()
  }



  private fun setupCamera() {
    try {
      for (id in cameraManager.cameraIdList) {
        val characteristics = cameraManager.getCameraCharacteristics(id)

        val lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING)
        if (lensFacing != CameraCharacteristics.LENS_FACING_BACK) {
          continue
        }

        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        if ((capabilities != null) && capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)) {
          cameraId = id
          val physicalIds = characteristics.physicalCameraIds
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

      // Create a CaptureRequest builder for preview
      captureRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
      captureRequestBuilder.addTarget(surface)

      // Configure the session with physical camera outputs
      cameraDevice!!.createCaptureSessionByOutputConfigurations(
        listOf(
          OutputConfiguration(surface).apply {
            // Set the physical camera ID here for the logical multi-camera
            physicalCameraId?.let { setPhysicalCameraId(it) }
          }
        ),
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(session: CameraCaptureSession) {
            captureSession = session
            try {
              // Start the preview
              captureSession?.setRepeatingRequest(captureRequestBuilder.build(), null, null)
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

  private fun switchCamera(id: String) {
    physicalCameraId = id
    openCamera() // Reopen the camera with the new cameraId
  }

  override fun onDestroy() {
    super.onDestroy()
    cameraDevice?.close()
    cameraDevice = null
  }
}