package com.huji.couchmirage.utils

import io.github.sceneview.node.ModelNode
import kotlin.math.max
import kotlin.math.sqrt

object ARScaleHelper {

    fun extractModelExtent(modelNode: ModelNode): Float {
        val asset = modelNode.modelInstance.asset
        val boundingBox = asset.boundingBox
        val halfExtent = boundingBox.halfExtent
        // Extent is size, so * 2
        val sizeX = halfExtent[0] * 2.0f
        val sizeY = halfExtent[1] * 2.0f
        val sizeZ = halfExtent[2] * 2.0f
        return max(sizeX, max(sizeY, sizeZ))
    }

    fun computeSafeScale(
        modelExtent: Float,
        targetDiameter: Float,
        minScale: Float,
        maxScale: Float,
        fallbackScale: Float,
        invalidExtentThreshold: Float
    ): Float {
        if (!modelExtent.isFinite() || modelExtent <= invalidExtentThreshold) {
            return fallbackScale
        }
        return (targetDiameter / modelExtent).coerceIn(minScale, maxScale)
    }

    fun screenScaleFactor(smallestScreenWidthDp: Int): Float {
        return when (smallestScreenWidthDp) {
            in 960..Int.MAX_VALUE -> 1.35f
            in 840..959 -> 1.25f
            in 720..839 -> 1.18f
            in 600..719 -> 1.12f
            else -> 1.0f
        }
    }

    fun adaptiveTargetSize(
        baseSize: Float,
        maxAdaptiveSize: Float,
        smallestScreenWidthDp: Int
    ): Float {
        val factor = screenScaleFactor(smallestScreenWidthDp)
        return (baseSize * factor).coerceIn(baseSize, maxAdaptiveSize)
    }

    fun maxScaleAllowedByCameraDistance(
        cameraDistance: Float?,
        modelExtent: Float,
        cameraSafetyMargin: Float,
        minAllowedRadius: Float,
        minScale: Float,
        maxScale: Float,
        invalidExtentThreshold: Float
    ): Float {
        if (modelExtent <= invalidExtentThreshold || !modelExtent.isFinite()) {
            return maxScale
        }
        val distance = cameraDistance ?: return maxScale
        val maxRadius = maxOf(distance - cameraSafetyMargin, minAllowedRadius)
        val maxDiameter = maxRadius * 2f
        return (maxDiameter / modelExtent).coerceIn(minScale, maxScale)
    }

    fun clampScaleByCameraDistance(
        rawScale: Float,
        cameraDistance: Float?,
        modelExtent: Float,
        cameraSafetyMargin: Float,
        minAllowedRadius: Float,
        minScale: Float,
        maxScale: Float,
        invalidExtentThreshold: Float
    ): Float {
        if (modelExtent <= invalidExtentThreshold || !modelExtent.isFinite()) {
            return rawScale
        }
        val maxScaleByDistance = maxScaleAllowedByCameraDistance(
            cameraDistance = cameraDistance,
            modelExtent = modelExtent,
            cameraSafetyMargin = cameraSafetyMargin,
            minAllowedRadius = minAllowedRadius,
            minScale = minScale,
            maxScale = maxScale,
            invalidExtentThreshold = invalidExtentThreshold
        )
        return rawScale.coerceAtMost(maxScaleByDistance).coerceAtLeast(minScale)
    }

    fun estimateRenderedRadius(
        modelExtent: Float,
        scale: Float,
        fallbackDiameter: Float,
        minAllowedRadius: Float,
        invalidExtentThreshold: Float
    ): Float {
        val diameter = if (modelExtent > invalidExtentThreshold && modelExtent.isFinite()) {
            modelExtent * scale
        } else {
            fallbackDiameter
        }
        return maxOf(diameter / 2f, minAllowedRadius)
    }

    fun distance(
        x1: Float,
        y1: Float,
        z1: Float,
        x2: Float,
        y2: Float,
        z2: Float
    ): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        val dz = z1 - z2
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
