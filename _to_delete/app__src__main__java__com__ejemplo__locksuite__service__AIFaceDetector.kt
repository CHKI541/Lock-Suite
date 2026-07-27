package com.ejemplo.locksuite.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facedetector.FaceDetector

class AIFaceDetector private constructor(private val detector: FaceDetector) {

    fun detectFaceRegions(bitmap: Bitmap): List<DetectedRegion> {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                    else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val mpImage = BitmapImageBuilder(argb).build()
        val result = detector.detect(mpImage)
        return result.detections().map {
            DetectedRegion(it.boundingBox().toIntRect(), DetectionSource.FACE)
        }
    }

    private fun RectF.toIntRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())

    fun close() = detector.close()

    companion object {
        private const val MODEL_PATH = "blaze_face_short_range.tflite"
        @Volatile private var instance: AIFaceDetector? = null

        fun getOrCreate(context: Context): AIFaceDetector =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): AIFaceDetector {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH).setDelegate(Delegate.CPU).build()
            val options = FaceDetector.FaceDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setMinDetectionConfidence(0.5f)
                .build()
            return AIFaceDetector(FaceDetector.createFromOptions(context, options))
        }

        fun release() { synchronized(this) { instance?.close(); instance = null } }
    }
}
