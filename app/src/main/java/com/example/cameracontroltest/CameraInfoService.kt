package com.example.cameracontroltest

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.internal.compat.CameraManagerCompat

object CameraInfoService {
  // フロント
  private const val KEY_FRONT = "KEY_FRONT"
  // 望遠
  private const val KEY_TELEPHOTO = "KEY_TELEPHOTO"
  // 広角
  private const val KEY_WIDE_RANGE = "KEY_WIDE_RANGE"
  // 超広角
  private const val KEY_SUPER_WIDE_RANGE = "KEY_SUPER_WIDE_RANGE"

  private var sortedCameraInfoMap: MutableMap<String, ExtendedCameraInfo>? = null

  @androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)
  @SuppressLint("RestrictedApi")
  fun initService(cameraIdList: List<String>, context: Context) {
    if (sortedCameraInfoMap != null) return
    sortedCameraInfoMap = mutableMapOf()

    // FocalLengthとSensorSizeを取得する
    val extendedCameraInfoList = mutableListOf<ExtendedCameraInfo>()
    cameraIdList.forEach { cameraId ->
      val cameraManager = CameraManagerCompat.from(context).unwrap()
      val characteristics = cameraManager.getCameraCharacteristics(cameraId)
      val isBack = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
      val focalLength =
        characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
          ?.getOrNull(0) ?: 0F
      val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let {
        it.width * it.height // Calculate sensor size (width * height)
      } ?: 0F

      if (isBack.not()) {
        sortedCameraInfoMap?.set(
          KEY_FRONT,
          ExtendedCameraInfo(cameraId, focalLength, sensorSize, characteristics)
        )
        return@forEach
      }

      val notAdded = extendedCameraInfoList.none { it.focalLength == focalLength && it.sensorSize == sensorSize }
      if (notAdded) {
        extendedCameraInfoList.add(
          ExtendedCameraInfo(cameraId, focalLength, sensorSize, characteristics)
        )
      }
    }

    // SensorSizeが一番大きいものを広角カメラとする。　TOOD: より確実なParameterを考える
    // その後、焦点距離を広角のものと比較して超広角、望遠のCameraを確定
    val sortedExtendedCameraInfoList = extendedCameraInfoList.sortedWith(compareBy({ it.focalLength }, { -it.sensorSize }))
    val wideRangeIndex = sortedExtendedCameraInfoList.indexOfFirst { it.sensorSize == sortedExtendedCameraInfoList.maxByOrNull { it.sensorSize }?.sensorSize }

    // 超広角格納
    if (wideRangeIndex - 1 >= 0) {
      sortedCameraInfoMap?.set(
        KEY_SUPER_WIDE_RANGE,
        sortedExtendedCameraInfoList[wideRangeIndex - 1]
      )
    }

    // 広角格納
    if (wideRangeIndex >= 0) {
      sortedCameraInfoMap?.set(
        KEY_WIDE_RANGE,
        sortedExtendedCameraInfoList[wideRangeIndex]
      )
    }

    // 望遠角格納
    if (wideRangeIndex + 1 <= sortedExtendedCameraInfoList.lastIndex) {
      sortedCameraInfoMap?.set(
        KEY_TELEPHOTO,
        sortedExtendedCameraInfoList[wideRangeIndex + 1]
      )
    }
  }



  data class ExtendedCameraInfo(
    val cameraId: String,
    val focalLength: Float,
    val sensorSize: Float,
//    val cameraInfo: CameraInfo,
    val cameraCharacteristics: CameraCharacteristics,
  )

  fun getTelephotoCameraInfo() = sortedCameraInfoMap?.let { it[KEY_TELEPHOTO] }

  fun getWideRangeCameraInfo() = sortedCameraInfoMap?.let { it[KEY_WIDE_RANGE] }

  fun getSuperWideRangeCameraInfo() = sortedCameraInfoMap?.let { it[KEY_SUPER_WIDE_RANGE] }
}