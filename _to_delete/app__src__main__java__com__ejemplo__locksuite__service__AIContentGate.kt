package com.ejemplo.locksuite.service

import android.content.Context
import android.graphics.Bitmap

class AIContentGate(private val context: Context) {

    fun detectRegions(bitmap: Bitmap, strictMode: Boolean): List<DetectedRegion> {
        val faceRegions = AIFaceDetector.getOrCreate(context).detectFaceRegions(bitmap)
        if (faceRegions.isNotEmpty()) return faceRegions

        if (strictMode) {
            return AIPersonDetector.getOrCreate(context).detectPersonRegions(bitmap)
        }
        return emptyList()
    }

    companion object {
        fun releaseAll() {
            AIFaceDetector.release()
            AIPersonDetector.release()
        }
    }
}
