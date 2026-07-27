package com.ejemplo.locksuite.service

import android.graphics.Rect

enum class DetectionSource { FACE, BODY }

data class DetectedRegion(val rect: Rect, val source: DetectionSource)
