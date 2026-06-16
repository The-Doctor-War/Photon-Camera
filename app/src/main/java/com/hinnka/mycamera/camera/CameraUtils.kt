package com.hinnka.mycamera.camera

import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs

/**
 * 相机工具类
 */
object CameraUtils {

    private const val ASPECT_RATIO_TOLERANCE = 0.01f
    private const val PREVIEW_TARGET_SHORT_EDGE = 1080

    /**
     * 获取固定的预览尺寸
     * 使用固定尺寸可以避免切换画面比例时频繁重新配置相机会话，
     * 不同的画面比例通过 UI 裁切实现
     */
    fun getFixedPreviewSize(
        context: Context,
        cameraId: String,
        aspectRatio: AspectRatio
    ): Size {
        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return Size(1440, 1080)

            val previewSizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()
            if (previewSizes.isEmpty()) {
                return Size(1440, 1080)
            }

            val targetRatio = aspectRatio.getValue(true)
            return chooseBestPreviewSize(previewSizes, targetRatio) ?: Size(1440, 1080)
        } catch (e: Exception) {
            PLog.d("CameraUtils", "getFixedPreviewSize: ${e.message}")
            return Size(1440, 1080)
        }
    }

    /**
     * 获取最佳拍照尺寸
     */
    fun getBestCaptureSize(context: Context, cameraId: String, aspectRatio: AspectRatio): Size {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.YUV_420_888) ?: arrayOf(Size(1920, 1080))
            val targetRatio = aspectRatio.getValue(true)

            chooseBestCaptureSize(sizes.toList(), targetRatio) ?: Size(1920, 1080)
        } catch (e: Exception) {
//            PLog.e(TAG, "Failed to get capture size", e)
            Size(1920, 1080)
        }
    }

    private fun chooseBestCaptureSize(sizes: List<Size>, targetRatio: Float): Size? {
        if (sizes.isEmpty()) return null

        val aspectMatchedSize = sizes
            .filter { it.matchesAspectRatio(targetRatio) }
            .maxByOrNull { it.pixelCount() }
        val maxSize = sizes.maxByOrNull { it.pixelCount() } ?: return aspectMatchedSize

        val selected = chooseHigherEffectiveCropSize(
            aspectMatchedSize = aspectMatchedSize,
            cropSourceSize = maxSize,
            targetRatio = targetRatio
        )

        logSizeChoice(
            label = "Capture",
            targetRatio = targetRatio,
            aspectMatchedSize = aspectMatchedSize,
            cropSourceSize = maxSize,
            selected = selected
        )
        return selected
    }

    private fun chooseBestPreviewSize(sizes: List<Size>, targetRatio: Float): Size? {
        if (sizes.isEmpty()) return null

        val aspectMatchedSize = sizes
            .filter { it.matchesAspectRatio(targetRatio) }
            .let { matchingSizes ->
                matchingSizes
                    .filter { it.shortEdge() <= PREVIEW_TARGET_SHORT_EDGE }
                    .maxByOrNull { it.pixelCount() }
                    ?: matchingSizes.minByOrNull { it.pixelCount() }
            }
        val preview1080SourceSize = sizes
            .filter { it.shortEdge() <= PREVIEW_TARGET_SHORT_EDGE }
            .maxByOrNull { it.pixelCount() }
            ?: sizes.minByOrNull { it.pixelCount() }

        val selected = chooseHigherEffectiveCropSize(
            aspectMatchedSize = aspectMatchedSize,
            cropSourceSize = preview1080SourceSize,
            targetRatio = targetRatio
        )

        logSizeChoice(
            label = "Preview",
            targetRatio = targetRatio,
            aspectMatchedSize = aspectMatchedSize,
            cropSourceSize = preview1080SourceSize,
            selected = selected
        )
        return selected
    }

    private fun chooseHigherEffectiveCropSize(
        aspectMatchedSize: Size?,
        cropSourceSize: Size?,
        targetRatio: Float
    ): Size? {
        if (cropSourceSize == null) return aspectMatchedSize
        if (aspectMatchedSize == null) return cropSourceSize

        val aspectMatchedPixels = aspectMatchedSize.croppedPixelCount(targetRatio)
        val cropSourcePixels = cropSourceSize.croppedPixelCount(targetRatio)
        return if (cropSourcePixels > aspectMatchedPixels) {
            cropSourceSize
        } else {
            aspectMatchedSize
        }
    }

    private fun Size.matchesAspectRatio(targetRatio: Float): Boolean {
        return height > 0 && abs(width.toFloat() / height.toFloat() - targetRatio) < ASPECT_RATIO_TOLERANCE
    }

    private fun Size.pixelCount(): Long {
        return width.toLong() * height.toLong()
    }

    private fun Size.shortEdge(): Int {
        return minOf(width, height)
    }

    private fun Size.croppedPixelCount(targetRatio: Float): Long {
        val cropSize = cropToAspectRatio(targetRatio)
        return cropSize.width.toLong() * cropSize.height.toLong()
    }

    private fun Size.cropToAspectRatio(targetRatio: Float): Size {
        if (width <= 0 || height <= 0 || targetRatio <= 0f) return this

        val sourceRatio = width.toFloat() / height.toFloat()
        return if (sourceRatio > targetRatio) {
            Size((height * targetRatio).toInt().coerceIn(1, width), height)
        } else {
            Size(width, (width / targetRatio).toInt().coerceIn(1, height))
        }
    }

    private fun logSizeChoice(
        label: String,
        targetRatio: Float,
        aspectMatchedSize: Size?,
        cropSourceSize: Size?,
        selected: Size?
    ) {
        val selectedCrop = selected?.cropToAspectRatio(targetRatio)
        PLog.d(
            "CameraUtils",
            "$label size: targetRatio=${"%.3f".format(targetRatio)}, " +
                    "matched=${aspectMatchedSize?.toLogString() ?: "none"} " +
                    "(${aspectMatchedSize?.cropToAspectRatio(targetRatio)?.toLogString() ?: "none"} effective), " +
                    "cropSource=${cropSourceSize?.toLogString() ?: "none"} " +
                    "(${cropSourceSize?.cropToAspectRatio(targetRatio)?.toLogString() ?: "none"} effective), " +
                    "selected=${selected?.toLogString() ?: "none"} " +
                    "(${selectedCrop?.toLogString() ?: "none"} effective)"
        )
    }

    private fun Size.toLogString(): String {
        return "${width}x${height}"
    }
    
    /**
     * 获取 RAW 拍照尺寸
     * 
     * RAW 格式通常只有一个尺寸（传感器原生尺寸）
     */
    fun getRawCaptureSize(context: Context, cameraId: String): Size? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val sizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR) ?: return null
            
            if (sizes.isEmpty()) return null

            val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val preCorrectionSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)

            // Try to find a size that matches DngCreator's requirements (PixelArray or PreCorrectionActiveArray)
            val bestMatch = sizes.find {
                (it.width == pixelArraySize?.width && it.height == pixelArraySize.height) ||
                (it.width == preCorrectionSize?.width() && it.height == preCorrectionSize.height())
            }

            // Return the best match if found, otherwise fall back to the largest available size
            bestMatch ?: sizes.maxByOrNull { it.width * it.height }
        } catch (e: Exception) {
            PLog.e("CameraUtils", "Failed to get RAW capture size: ${e.message}")
            null
        }
    }
    
    /**
     * 格式化快门速度显示
     */
    fun formatShutterSpeed(exposureTimeNanos: Long): String {
        return when {
            exposureTimeNanos >= 1_000_000_000L -> {
                val seconds = exposureTimeNanos / 1_000_000_000.0
                String.format("%.1f\"", seconds)
            }
            else -> {
                val fraction = (1_000_000_000.0 / exposureTimeNanos).toInt()
                "1/$fraction"
            }
        }
    }
    
    /**
     * 格式化曝光补偿显示
     */
    fun formatExposureCompensation(value: Int, step: Float): String {
        val ev = value * step
        return when {
            ev > 0 -> "+${String.format("%.1f", ev)}"
            ev < 0 -> String.format("%.1f", ev)
            else -> "0"
        }
    }
}
