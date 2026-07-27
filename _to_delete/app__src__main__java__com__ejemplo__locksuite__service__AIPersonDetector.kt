package com.ejemplo.locksuite.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

class AIPersonDetector private constructor(private val detector: ObjectDetector) {

    companion object {
        private const val MODEL_PATH = "efficientdet_lite0.tflite"
        private const val PERSON_LABEL = "person"
        private const val SCORE_THRESHOLD = 0.55f
        @Volatile private var instance: AIPersonDetector? = null

        fun getOrCreate(context: Context): AIPersonDetector =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): AIPersonDetector {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_PATH).setDelegate(Delegate.CPU).build()
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.IMAGE)
                .setScoreThreshold(SCORE_THRESHOLD)
                .setMaxResults(5)
                .build()
            return AIPersonDetector(ObjectDetector.createFromOptions(context, options))
        }

        fun release() { synchronized(this) { instance?.detector?.close(); instance = null } }
    }

    fun detectPersonRegions(bitmap: Bitmap): List<DetectedRegion> {
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap
                   else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val mpImage = BitmapImageBuilder(argb).build()
        val result = detector.detect(mpImage)
        return result.detections()
            .filter { d -> d.categories().any { it.categoryName() == PERSON_LABEL } }
            .map { DetectedRegion(it.boundingBox().toIntRect(), DetectionSource.BODY) }
    }

    private fun RectF.toIntRect() = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
}
