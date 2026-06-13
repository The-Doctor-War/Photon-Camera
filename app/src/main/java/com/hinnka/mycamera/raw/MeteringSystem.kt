package com.hinnka.mycamera.raw

import android.annotation.SuppressLint
import android.util.Half
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 评价测光与场景分析系统
 */
object MeteringSystem {
    private const val TAG = "MeteringSystem"
    private const val DISPLAY_TARGET_LUMA = 0.18f
    private const val ACR3_AVERAGE_TONE_CURVE_EV = 1f
    private const val MID_TONE_GRID_COLUMNS = 7
    private const val MID_TONE_GRID_ROWS = 7
    private const val MID_TONE_ZONE_SAMPLE_TRIM_FRACTION = 0.15f
    private const val MID_TONE_ZONE_REJECT_FRACTION = 0.15f
    private const val MID_TONE_BUCKET_COUNT = 9
    private const val MID_TONE_MIN_BUCKET_RANGE_EV = 0.35f
    private const val MID_TONE_MIN_WEIGHT = 0.001f
    private const val LUMA_FLOOR = 0.001f
    private const val MAX_LINEAR_LUMA = 16.0f
    private const val MIN_DEPTH_SEPARATION = 0.08f
    private const val RAW_CURVE_NEUTRAL_WHITE_POINT = 1.0f
    private const val AUTO_DEVELOP_EPSILON = 0.0001f
    private const val AUTO_CLIP_FRACTION = 0.0002f
    private const val RAW_AUTO_HIGHLIGHT_LIMIT = 0.95f
    private const val RAW_AUTO_STANDARD_SHADOW_LIMIT = 0.45f
    private const val RAW_AUTO_HDR_SHADOW_LIMIT = 0.9f
    private const val RAW_AUTO_HDR_BASELINE_START = 1.0f
    private const val RAW_AUTO_HDR_BASELINE_FULL = 2.0f
    private const val RAW_AUTO_FULL_ADJUSTMENT_EV = 1.6f
    private const val RAW_AUTO_LOW_KEY_P75_RATIO_FULL = 0.45f
    private const val RAW_AUTO_LOW_KEY_P75_RATIO_START = 0.85f
    private const val RAW_AUTO_LOW_KEY_P90_RATIO_FULL = 0.70f
    private const val RAW_AUTO_LOW_KEY_P90_RATIO_START = 1.25f
    private const val RAW_AUTO_LOW_KEY_MAX_SHADOW_ATTENUATION = 0.78f
    private const val MERTENS_WELL_EXPOSED_CENTER = 0.5f
    private const val MERTENS_WELL_EXPOSED_SIGMA = 0.2f
    private const val MERTENS_MIN_TONE_WEIGHT = 0.0001f
    private const val MERTENS_EV_INTENSITY_FLOOR = 0.0005f
    private val MERTENS_VIRTUAL_EXPOSURE_EVS = floatArrayOf(-2f, -1f, 0f, 1f, 2f)


    data class MeteringResult(
        val meteredEv: Float,
        val dynamicRangeGap: Float,
        val avgLuma: Float,
        val clipLow: Float,
        val clipHigh: Float,
        val highlights: Float,
        val shadows: Float,
        val curveWhitePoint: Float
    ) {
        fun scaleLuma(scale: Float): MeteringResult {
            val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
            return copy(
                avgLuma = (avgLuma * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                clipLow = (clipLow * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                clipHigh = (clipHigh * safeScale).coerceIn(0f, MAX_LINEAR_LUMA)
            )
        }

        companion object {
            val EMPTY = MeteringResult(
                meteredEv = 0f,
                dynamicRangeGap = 0f,
                avgLuma = 0f,
                clipLow = 0f,
                clipHigh = 0f,
                highlights = 0f,
                shadows = 0f,
                curveWhitePoint = 0f
            )
        }
    }

    data class ShadowsHighlightsParams(
        val highlights: Float,
        val shadows: Float,
        val curveWhitePoint: Float,
    ) {
        companion object {
            val NEUTRAL = ShadowsHighlightsParams(
                highlights = 0f,
                shadows = 0f,
                curveWhitePoint = RAW_CURVE_NEUTRAL_WHITE_POINT,
            )
        }
    }

    private data class DepthWeightMap(
        val weights: FloatArray,
        val separation: Float,
        val enabled: Boolean
    )

    private data class MidToneZone(
        val luma: Float,
        val weight: Float
    )

    private data class MidToneReference(
        val luma: Float,
        val zoneMedianLuma: Float,
        val bucketMedianLuma: Float,
        val retainedZoneCount: Int,
        val retainedBucketCount: Int
    )

    private data class MidToneBucket(
        var weightedLogLumaSum: Double = 0.0,
        var weightSum: Double = 0.0,
        var zoneCount: Int = 0
    )

    private data class MidToneSample(
        val luma: Float,
        val weight: Float
    )

    private data class ExposureFusionToneStats(
        val highlightAdjustmentEv: Float,
        val shadowAdjustmentEv: Float,
        val highlightRegionWeight: Float,
        val shadowRegionWeight: Float
    )



    @SuppressLint("HalfFloat")
    fun analyzeLinearHalfFloatExposureEv(
        pixelBuffer: ShortBuffer,
        width: Int,
        height: Int,
        weightBuffer: ByteBuffer? = null, // Optional weight mask (e.g. depth map)
        baselineExposure: Float = 0f
    ): MeteringResult {
        return analyzeExposureEv(
            width = width,
            height = height,
            weightBuffer = weightBuffer,
            targetLuma = DISPLAY_TARGET_LUMA * 2f.pow(-ACR3_AVERAGE_TONE_CURVE_EV),
            baselineExposure = baselineExposure,
            tag = "Linear RAW AE"
        ) { index ->
            val base = index * 4
            val r = Half.toFloat(pixelBuffer.get(base))
            val g = Half.toFloat(pixelBuffer.get(base + 1))
            val b = Half.toFloat(pixelBuffer.get(base + 2))
            r * 0.2126f + g * 0.7152f + b * 0.0722f
        }
    }

    fun hasManualRawDevelopAdjustments(
        rawExposureCompensation: Float,
        rawHighlightsAdjustment: Float,
        rawShadowsAdjustment: Float
    ): Boolean {
        return abs(rawExposureCompensation) > AUTO_DEVELOP_EPSILON ||
            abs(rawHighlightsAdjustment) > AUTO_DEVELOP_EPSILON ||
            abs(rawShadowsAdjustment) > AUTO_DEVELOP_EPSILON
    }

    private inline fun analyzeExposureEv(
        width: Int,
        height: Int,
        weightBuffer: ByteBuffer?,
        targetLuma: Float,
        baselineExposure: Float,
        tag: String,
        lumaAt: (Int) -> Float
    ): MeteringResult {
        val pixelCount = width * height
        if (pixelCount == 0) return MeteringResult.EMPTY

        val depthWeights = decodeDepthWeights(weightBuffer, pixelCount)
        val lumas = FloatArray(pixelCount)
        var sanitizedSampleCount = 0

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                val rawLuma = lumaAt(idx)
                val luma = sanitizeLinearLuma(rawLuma)
                if (rawLuma != luma) {
                    sanitizedSampleCount++
                }
                lumas[idx] = luma
            }
        }

        val midToneReference = calculateMidToneReferenceLuma(
            lumas = lumas,
            width = width,
            height = height,
            depthWeights = depthWeights?.weights,
            fallback = targetLuma
        )
        val midToneLuma = midToneReference.luma

        lumas.sort()
        val clipLow = percentile(lumas, AUTO_CLIP_FRACTION)
        val p05 = percentile(lumas, 0.05f)
        val p25 = percentile(lumas, 0.25f)
        val p75 = percentile(lumas, 0.75f)
        val p90 = percentile(lumas, 0.90f)
        val p99 = percentile(lumas, 0.99f)
        val clipHigh = percentile(lumas, 1f - AUTO_CLIP_FRACTION)

        val highlightAnchorGain = maxOf(1f, clipHigh) / clipHigh.coerceAtLeast(0.01f)
        val midToneGain = targetLuma / midToneLuma.coerceAtLeast(LUMA_FLOOR)
        val dynamicRangeGap = evDifference(clipHigh, clipLow)

        val extra = smoothStep(4f, 12f, dynamicRangeGap)
        val adaptiveGain = lerp(midToneGain * 1.2f, highlightAnchorGain * 1.1f, extra)
        val rawMeteredEv = log2(adaptiveGain.coerceIn(0.25f, 4.0f))

        val compensatedExposureScale = exp2(rawMeteredEv)
        val compensatedClipLow = (clipLow * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val compensatedP05 = (p05 * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val compensatedP25 = (p25 * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val compensatedP75 = (p75 * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val compensatedP90 = (p90 * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val compensatedP99 = (p99 * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val compensatedClipHigh = (clipHigh * compensatedExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val exposureFusionToneStats = calculateExposureFusionToneStats(
            sortedLumas = lumas,
            exposureScale = compensatedExposureScale,
            targetLuma = targetLuma
        )
        val highlights = -calculateAutoHighlightCompression(exposureFusionToneStats.highlightAdjustmentEv)
        val shadowLimit = resolveAutoShadowLimit(baselineExposure)
        val unprotectedShadows = calculateAutoShadowLift(
            stats = exposureFusionToneStats,
            shadowLimit = shadowLimit
        )
        val lowKeyShadowProtection = calculateLowKeyShadowProtection(
            compensatedP75 = compensatedP75,
            compensatedP90 = compensatedP90,
            targetLuma = targetLuma
        )
        val shadows = applyLowKeyShadowProtection(
            shadows = unprotectedShadows,
            protection = lowKeyShadowProtection,
            shadowLimit = shadowLimit
        )

        // Curve points
        val curveWhitePoint = if (compensatedClipHigh > 1f) {
            compensatedClipHigh.coerceIn(1.05f, 4.0f)
        } else {
            RAW_CURVE_NEUTRAL_WHITE_POINT
        }

        PLog.d(
            TAG,
            "$tag: clipLow=$clipLow clipHigh=$clipHigh " +
                "p05=$p05 p25=$p25 p75=$p75 p90=$p90 p99=$p99 " +
                "compScale=$compensatedExposureScale " +
                "compClipLow=$compensatedClipLow compClipHigh=$compensatedClipHigh " +
                "compP05=$compensatedP05 compP25=$compensatedP25 " +
                "compP75=$compensatedP75 compP90=$compensatedP90 compP99=$compensatedP99 " +
                "highlightAdjustmentEv=${exposureFusionToneStats.highlightAdjustmentEv} " +
                "shadowAdjustmentEv=${exposureFusionToneStats.shadowAdjustmentEv} " +
                "highlightRegionWeight=${exposureFusionToneStats.highlightRegionWeight} " +
                "shadowRegionWeight=${exposureFusionToneStats.shadowRegionWeight} " +
                "lowKeyShadowProtection=$lowKeyShadowProtection " +
                "baselineExposure=$baselineExposure shadowLimit=$shadowLimit " +
                "highlights=$highlights shadows=$shadows unprotectedShadows=$unprotectedShadows " +
                "target=$targetLuma midToneLuma=$midToneLuma " +
                "midToneGain=$midToneGain highlightAnchorGain=$highlightAnchorGain gain=$adaptiveGain " +
                "ev=$rawMeteredEv gap=$dynamicRangeGap "
        )

        return MeteringResult(
            meteredEv = rawMeteredEv.coerceIn(-2f, 2f),
            dynamicRangeGap = dynamicRangeGap,
            avgLuma = midToneLuma,
            clipLow = clipLow,
            clipHigh = clipHigh,
            highlights = highlights,
            shadows = shadows,
            curveWhitePoint = curveWhitePoint
        )
    }

    private fun decodeDepthWeights(
        weightBuffer: ByteBuffer?,
        pixelCount: Int
    ): DepthWeightMap? {
        if (weightBuffer == null || pixelCount == 0 || weightBuffer.capacity() < pixelCount) {
            return null
        }

        val stride = if (weightBuffer.capacity() >= pixelCount * 4) 4 else 1
        val depthValues = FloatArray(pixelCount)
        for (i in 0 until pixelCount) {
            depthValues[i] = (weightBuffer.get(i * stride).toInt() and 0xFF) / 255f
        }

        val sortedDepth = depthValues.copyOf()
        sortedDepth.sort()
        val median = percentile(sortedDepth, 0.50f)
        val highAnchor = percentile(sortedDepth, 0.90f)
        val lowAnchor = percentile(sortedDepth, 0.10f)
        val depthRange = (highAnchor - lowAnchor).coerceAtLeast(0f)
        val hasUsableSeparation = depthRange >= MIN_DEPTH_SEPARATION

        val weights = FloatArray(pixelCount) { index ->
            if (!hasUsableSeparation) {
                1f
            } else {
                calculateDepthSeparationWeight(
                    depth = depthValues[index],
                    median = median,
                    depthRange = depthRange
                )
            }
        }

        return DepthWeightMap(
            weights = weights,
            separation = depthRange,
            enabled = hasUsableSeparation
        )
    }

    private fun calculateDepthSeparationWeight(
        depth: Float,
        median: Float,
        depthRange: Float
    ): Float {
        val normalizedSeparation = kotlin.math.abs(depth - median) / depthRange.coerceAtLeast(MIN_DEPTH_SEPARATION)
        val saliency = smoothStep(0.18f, 0.88f, normalizedSeparation)
        return lerp(0.88f, 1.55f, saliency)
    }

    private fun sanitizeLinearLuma(value: Float): Float {
        return when {
            value.isFinite() -> value.coerceIn(0f, MAX_LINEAR_LUMA)
            value == Float.POSITIVE_INFINITY -> MAX_LINEAR_LUMA
            else -> 0f
        }
    }

    private fun calculateMidToneReferenceLuma(
        lumas: FloatArray,
        width: Int,
        height: Int,
        depthWeights: FloatArray?,
        fallback: Float
    ): MidToneReference {
        if (lumas.isEmpty() || width <= 0 || height <= 0) {
            return fallbackMidToneReference(fallback)
        }

        val zones = ArrayList<MidToneZone>(MID_TONE_GRID_COLUMNS * MID_TONE_GRID_ROWS)
        for (zoneY in 0 until MID_TONE_GRID_ROWS) {
            val startY = zoneY * height / MID_TONE_GRID_ROWS
            val endY = (zoneY + 1) * height / MID_TONE_GRID_ROWS
            if (startY >= endY) continue

            for (zoneX in 0 until MID_TONE_GRID_COLUMNS) {
                val startX = zoneX * width / MID_TONE_GRID_COLUMNS
                val endX = (zoneX + 1) * width / MID_TONE_GRID_COLUMNS
                if (startX >= endX) continue

                val zoneLuma = calculateZoneTrimmedMeanLuma(lumas, width, startX, endX, startY, endY, fallback)
                val centerU = ((startX + endX) * 0.5f) / width
                val centerV = ((startY + endY) * 0.5f) / height
                zones += MidToneZone(
                    luma = zoneLuma,
                    weight = calculateMidToneZoneWeight(
                        depthWeights = depthWeights,
                        width = width,
                        startX = startX,
                        endX = endX,
                        startY = startY,
                        endY = endY,
                        centerU = centerU,
                        centerV = centerV
                    )
                )
            }
        }

        if (zones.isEmpty()) {
            return fallbackMidToneReference(fallback)
        }

        zones.sortBy { it.luma }
        val rejectZoneCount = (zones.size * MID_TONE_ZONE_REJECT_FRACTION)
            .toInt()
            .coerceAtMost((zones.size - 1) / 2)
        val startIndex = rejectZoneCount
        val endIndex = zones.size - rejectZoneCount
        if (startIndex >= endIndex) {
            val fallbackLuma = sanitizeAverageLuma(zones[zones.size / 2].luma, fallback)
            return MidToneReference(
                luma = fallbackLuma,
                zoneMedianLuma = fallbackLuma,
                bucketMedianLuma = fallbackLuma,
                retainedZoneCount = 1,
                retainedBucketCount = 1
            )
        }

        val zoneMedianLuma = sanitizeAverageLuma(
            weightedMedianMidToneLuma(zones, startIndex, endIndex),
            fallback
        )
        val bucketSamples = buildToneBalancedMidToneSamples(zones, startIndex, endIndex)
        val bucketMedianLuma = sanitizeAverageLuma(
            weightedMedianMidToneSampleLuma(bucketSamples).takeIf { it > 0f } ?: zoneMedianLuma,
            fallback
        )

        return MidToneReference(
            luma = bucketMedianLuma,
            zoneMedianLuma = zoneMedianLuma,
            bucketMedianLuma = bucketMedianLuma,
            retainedZoneCount = endIndex - startIndex,
            retainedBucketCount = bucketSamples.size
        )
    }

    private fun fallbackMidToneReference(fallback: Float): MidToneReference {
        return MidToneReference(
            luma = fallback,
            zoneMedianLuma = fallback,
            bucketMedianLuma = fallback,
            retainedZoneCount = 0,
            retainedBucketCount = 0
        )
    }

    private fun calculateZoneTrimmedMeanLuma(
        lumas: FloatArray,
        width: Int,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int,
        fallback: Float,
    ): Float {
        val sampleCount = (endX - startX) * (endY - startY)
        if (sampleCount <= 0) {
            return fallback
        }

        val samples = FloatArray(sampleCount)
        var sampleIndex = 0
        for (y in startY until endY) {
            val rowOffset = y * width
            for (x in startX until endX) {
                samples[sampleIndex++] = lumas[rowOffset + x]
            }
        }

        return trimmedMean(samples, MID_TONE_ZONE_SAMPLE_TRIM_FRACTION, fallback)
    }

    private fun trimmedMean(
        values: FloatArray,
        trimFraction: Float,
        fallback: Float
    ): Float {
        if (values.isEmpty()) {
            return fallback
        }

        values.sort()
        val trimCount = (values.size * trimFraction.coerceIn(0f, 0.49f))
            .toInt()
            .coerceAtMost((values.size - 1) / 2)
        val startIndex = trimCount
        val endIndex = values.size - trimCount
        var sum = 0.0
        for (i in startIndex until endIndex) {
            sum += values[i].toDouble()
        }

        return (sum / (endIndex - startIndex).coerceAtLeast(1)).toFloat()
    }

    private fun weightedMedianMidToneLuma(
        sortedZones: List<MidToneZone>,
        startIndex: Int,
        endIndex: Int
    ): Float {
        var totalWeight = 0.0
        for (i in startIndex until endIndex) {
            val weight = sortedZones[i].weight
            if (weight.isFinite() && weight > 0f) {
                totalWeight += weight.toDouble()
            }
        }

        if (totalWeight <= 0.0) {
            return sortedZones[(startIndex + endIndex - 1) / 2].luma
        }

        val halfWeight = totalWeight * 0.5
        var cumulativeWeight = 0.0
        for (i in startIndex until endIndex) {
            val weight = sortedZones[i].weight
            if (weight.isFinite() && weight > 0f) {
                cumulativeWeight += weight.toDouble()
            }
            if (cumulativeWeight >= halfWeight) {
                return sortedZones[i].luma
            }
        }

        return sortedZones[endIndex - 1].luma
    }

    private fun buildToneBalancedMidToneSamples(
        sortedZones: List<MidToneZone>,
        startIndex: Int,
        endIndex: Int
    ): List<MidToneSample> {
        if (startIndex >= endIndex) {
            return emptyList()
        }

        val lowLogLuma = log2(sortedZones[startIndex].luma.coerceAtLeast(LUMA_FLOOR))
        val highLogLuma = log2(sortedZones[endIndex - 1].luma.coerceAtLeast(LUMA_FLOOR))
        val logRange = highLogLuma - lowLogLuma
        if (!logRange.isFinite() || logRange < MID_TONE_MIN_BUCKET_RANGE_EV) {
            return listOf(
                MidToneSample(
                    luma = weightedGeometricMeanMidToneLuma(sortedZones, startIndex, endIndex),
                    weight = 1f
                )
            )
        }

        val buckets = Array(MID_TONE_BUCKET_COUNT) { MidToneBucket() }
        for (i in startIndex until endIndex) {
            val zone = sortedZones[i]
            val weight = zone.weight
            if (weight.isFinite() && weight > 0f) {
                val logLuma = log2(zone.luma.coerceAtLeast(LUMA_FLOOR))
                val bucketIndex = (((logLuma - lowLogLuma) / logRange) * MID_TONE_BUCKET_COUNT)
                    .toInt()
                    .coerceIn(0, MID_TONE_BUCKET_COUNT - 1)
                val bucket = buckets[bucketIndex]
                bucket.weightedLogLumaSum += (logLuma * weight).toDouble()
                bucket.weightSum += weight.toDouble()
                bucket.zoneCount++
            }
        }

        val samples = ArrayList<MidToneSample>(MID_TONE_BUCKET_COUNT)
        for (bucket in buckets) {
            if (bucket.zoneCount > 0 && bucket.weightSum > 0.0) {
                val representativeLogLuma = (bucket.weightedLogLumaSum / bucket.weightSum).toFloat()
                samples += MidToneSample(
                    luma = exp2(representativeLogLuma),
                    weight = (bucket.weightSum / bucket.zoneCount).toFloat().coerceAtLeast(MID_TONE_MIN_WEIGHT)
                )
            }
        }
        return samples
    }

    private fun weightedMedianMidToneSampleLuma(samples: List<MidToneSample>): Float {
        if (samples.isEmpty()) {
            return 0f
        }

        var totalWeight = 0.0
        for (sample in samples) {
            if (sample.weight.isFinite() && sample.weight > 0f) {
                totalWeight += sample.weight.toDouble()
            }
        }

        if (totalWeight <= 0.0) {
            return samples[samples.size / 2].luma
        }

        val halfWeight = totalWeight * 0.5
        var cumulativeWeight = 0.0
        for (sample in samples) {
            if (sample.weight.isFinite() && sample.weight > 0f) {
                cumulativeWeight += sample.weight.toDouble()
            }
            if (cumulativeWeight >= halfWeight) {
                return sample.luma
            }
        }

        return samples.last().luma
    }

    private fun weightedGeometricMeanMidToneLuma(
        sortedZones: List<MidToneZone>,
        startIndex: Int,
        endIndex: Int
    ): Float {
        var weightedLogSum = 0.0
        var totalWeight = 0.0
        for (i in startIndex until endIndex) {
            val zone = sortedZones[i]
            val weight = zone.weight
            if (weight.isFinite() && weight > 0f) {
                weightedLogSum += (log2(zone.luma.coerceAtLeast(LUMA_FLOOR)) * weight).toDouble()
                totalWeight += weight.toDouble()
            }
        }

        return if (totalWeight > 0.0) {
            exp2((weightedLogSum / totalWeight).toFloat())
        } else {
            sortedZones[(startIndex + endIndex - 1) / 2].luma
        }
    }

    private fun calculateMidToneZoneWeight(
        depthWeights: FloatArray?,
        width: Int,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int,
        centerU: Float,
        centerV: Float
    ): Float {
        val centerWeight = calculateMidToneCenterWeight(centerU, centerV)
        val depthWeight = calculateZoneDepthWeight(depthWeights, width, startX, endX, startY, endY)
        val weight = centerWeight * depthWeight
        return if (weight.isFinite()) {
            weight.coerceAtLeast(MID_TONE_MIN_WEIGHT)
        } else {
            MID_TONE_MIN_WEIGHT
        }
    }

    private fun calculateZoneDepthWeight(
        depthWeights: FloatArray?,
        width: Int,
        startX: Int,
        endX: Int,
        startY: Int,
        endY: Int
    ): Float {
        if (depthWeights == null) {
            return 1f
        }

        var sum = 0.0
        var count = 0
        for (y in startY until endY) {
            val rowOffset = y * width
            for (x in startX until endX) {
                val weight = depthWeights.getOrNull(rowOffset + x) ?: continue
                if (weight.isFinite() && weight > 0f) {
                    sum += weight.toDouble()
                    count++
                }
            }
        }

        return if (count > 0) {
            (sum / count).toFloat().coerceAtLeast(MID_TONE_MIN_WEIGHT)
        } else {
            1f
        }
    }

    private fun calculateMidToneCenterWeight(u: Float, v: Float): Float {
        val centerWeight = gaussian2d(u, v, 0.5f, 0.5f, 0.32f)
        return 0.35f + centerWeight * 0.65f
    }

    private fun sanitizeAverageLuma(value: Float, fallback: Float): Float {
        return if (value.isFinite() && value > 0f) {
            value.coerceIn(LUMA_FLOOR, MAX_LINEAR_LUMA)
        } else {
            fallback.coerceIn(LUMA_FLOOR, MAX_LINEAR_LUMA)
        }
    }

    private fun calculateExposureFusionToneStats(
        sortedLumas: FloatArray,
        exposureScale: Float,
        targetLuma: Float
    ): ExposureFusionToneStats {
        if (sortedLumas.isEmpty()) {
            return ExposureFusionToneStats(
                highlightAdjustmentEv = 0f,
                shadowAdjustmentEv = 0f,
                highlightRegionWeight = 0f,
                shadowRegionWeight = 0f
            )
        }

        val safeExposureScale = if (exposureScale.isFinite() && exposureScale > 0f) {
            exposureScale
        } else {
            1f
        }
        var highlightEvEnergySum = 0.0
        var shadowEvEnergySum = 0.0
        var highlightWeightSum = 0.0
        var shadowWeightSum = 0.0
        for (luma in sortedLumas) {
            val baseLuma = sanitizeLinearLuma(luma * safeExposureScale)
            val baseIntensity = linearLumaToExposureFusionIntensity(
                luma = baseLuma,
                targetLuma = targetLuma
            )
            val fusedIntensity = calculateVirtualMertensFusionIntensity(
                baseLuma = baseLuma,
                targetLuma = targetLuma
            )
            val fusionDelta = fusedIntensity - baseIntensity
            val highlightMask = smoothStep(
                targetLuma,
                0.78f,
                baseIntensity
            )
            val shadowMask = 1f - smoothStep(
                0.005f,
                targetLuma,
                baseIntensity
            )
            val highlightAdjustmentEv = if (fusionDelta < 0f) {
                log2(baseIntensity.coerceAtLeast(MERTENS_EV_INTENSITY_FLOOR) /
                    fusedIntensity.coerceAtLeast(MERTENS_EV_INTENSITY_FLOOR))
            } else {
                0f
            }
            val shadowAdjustmentEv = if (fusionDelta > 0f) {
                log2(fusedIntensity.coerceAtLeast(MERTENS_EV_INTENSITY_FLOOR) /
                    baseIntensity.coerceAtLeast(MERTENS_EV_INTENSITY_FLOOR))
            } else {
                0f
            }
            highlightEvEnergySum += (highlightAdjustmentEv * highlightAdjustmentEv * highlightMask).toDouble()
            shadowEvEnergySum += (shadowAdjustmentEv * shadowAdjustmentEv * shadowMask).toDouble()
            highlightWeightSum += highlightMask.toDouble()
            shadowWeightSum += shadowMask.toDouble()
        }

        val sampleCount = sortedLumas.size.toFloat().coerceAtLeast(1f)
        val highlightAdjustmentEv = if (highlightWeightSum > MERTENS_MIN_TONE_WEIGHT) {
            sqrt(highlightEvEnergySum / highlightWeightSum)
                .toFloat()
                .coerceAtLeast(0f)
        } else {
            0f
        }
        val shadowAdjustmentEv = if (shadowWeightSum > MERTENS_MIN_TONE_WEIGHT) {
            sqrt(shadowEvEnergySum / shadowWeightSum)
                .toFloat()
                .coerceAtLeast(0f)
        } else {
            0f
        }

        return ExposureFusionToneStats(
            highlightAdjustmentEv = highlightAdjustmentEv,
            shadowAdjustmentEv = shadowAdjustmentEv,
            highlightRegionWeight = (highlightWeightSum / sampleCount).toFloat().coerceIn(0f, 1f),
            shadowRegionWeight = (shadowWeightSum / sampleCount).toFloat().coerceIn(0f, 1f)
        )
    }

    private fun calculateVirtualMertensFusionIntensity(
        baseLuma: Float,
        targetLuma: Float
    ): Float {
        val baseIntensity = linearLumaToExposureFusionIntensity(
            luma = baseLuma,
            targetLuma = targetLuma
        )
        var weightedIntensitySum = 0.0
        var weightSum = 0.0
        for (ev in MERTENS_VIRTUAL_EXPOSURE_EVS) {
            val virtualIntensity = linearLumaToExposureFusionIntensity(
                luma = baseLuma * exp2(ev),
                targetLuma = targetLuma
            )
            val weight = mertensWellExposedness(virtualIntensity)
            weightedIntensitySum += (virtualIntensity * weight).toDouble()
            weightSum += weight.toDouble()
        }

        return if (weightSum > 0.0) {
            (weightedIntensitySum / weightSum).toFloat().coerceIn(0f, 1f)
        } else {
            baseIntensity
        }
    }

    private fun linearLumaToExposureFusionIntensity(
        luma: Float,
        targetLuma: Float
    ): Float {
        if (!luma.isFinite()) {
            return 0f
        }
        val safeTarget = targetLuma.coerceAtLeast(LUMA_FLOOR)
        return (sanitizeLinearLuma(luma) / (safeTarget * 2f)).coerceIn(0f, 1f)
    }

    private fun mertensWellExposedness(intensity: Float): Float {
        val offset = intensity.coerceIn(0f, 1f) - MERTENS_WELL_EXPOSED_CENTER
        val sigma2 = MERTENS_WELL_EXPOSED_SIGMA * MERTENS_WELL_EXPOSED_SIGMA
        return exp((-0.5f * offset * offset / sigma2).toDouble()).toFloat().coerceIn(0f, 1f)
    }

    private fun calculateAutoHighlightCompression(highlightAdjustmentEv: Float): Float {
        return (highlightAdjustmentEv / RAW_AUTO_FULL_ADJUSTMENT_EV)
            .coerceIn(0f, RAW_AUTO_HIGHLIGHT_LIMIT)
    }

    private fun resolveAutoShadowLimit(baselineExposure: Float): Float {
        val hdrAllowance = smoothStep(
            RAW_AUTO_HDR_BASELINE_START,
            RAW_AUTO_HDR_BASELINE_FULL,
            baselineExposure.takeIf { it.isFinite() } ?: 0f
        )
        return lerp(
            RAW_AUTO_STANDARD_SHADOW_LIMIT,
            RAW_AUTO_HDR_SHADOW_LIMIT,
            hdrAllowance
        )
    }

    private fun calculateAutoShadowLift(
        stats: ExposureFusionToneStats,
        shadowLimit: Float
    ): Float {
        return (stats.shadowAdjustmentEv / RAW_AUTO_FULL_ADJUSTMENT_EV)
            .coerceIn(0f, shadowLimit.coerceAtLeast(0f))
    }

    private fun calculateLowKeyShadowProtection(
        compensatedP75: Float,
        compensatedP90: Float,
        targetLuma: Float
    ): Float {
        val safeTarget = targetLuma.coerceAtLeast(LUMA_FLOOR)
        val upperMidDarkness = 1f - smoothStep(
            RAW_AUTO_LOW_KEY_P75_RATIO_FULL,
            RAW_AUTO_LOW_KEY_P75_RATIO_START,
            compensatedP75 / safeTarget
        )
        val highToneDarkness = 1f - smoothStep(
            RAW_AUTO_LOW_KEY_P90_RATIO_FULL,
            RAW_AUTO_LOW_KEY_P90_RATIO_START,
            compensatedP90 / safeTarget
        )
        return minOf(upperMidDarkness, highToneDarkness).coerceIn(0f, 1f)
    }

    private fun applyLowKeyShadowProtection(
        shadows: Float,
        protection: Float,
        shadowLimit: Float
    ): Float {
        val attenuation = lerp(
            start = 1f,
            end = 1f - RAW_AUTO_LOW_KEY_MAX_SHADOW_ATTENUATION,
            fraction = protection
        )
        return (shadows * attenuation).coerceIn(0f, shadowLimit.coerceAtLeast(0f))
    }

    private fun percentile(sortedValues: FloatArray, percentile: Float): Float {
        if (sortedValues.isEmpty()) {
            return 0f
        }

        val index = ((sortedValues.size - 1) * percentile.coerceIn(0f, 1f)).toInt()
        return sortedValues[index]
    }

    private fun evDifference(brighter: Float, darker: Float): Float {
        val safeBrighter = sanitizeLinearLuma(brighter).coerceAtLeast(LUMA_FLOOR)
        val safeDarker = sanitizeLinearLuma(darker).coerceAtLeast(1e-5f)
        return log2(safeBrighter / safeDarker)
    }

    private fun log2(value: Float): Float {
        return (ln(value.toDouble()) / ln(2.0)).toFloat()
    }

    private fun exp2(value: Float): Float {
        return exp(value * ln(2.0)).toFloat()
    }

    private fun gaussian2d(
        u: Float,
        v: Float,
        centerU: Float,
        centerV: Float,
        sigma: Float
    ): Float {
        if (!u.isFinite() || !v.isFinite() || !centerU.isFinite() || !centerV.isFinite() || !sigma.isFinite()) {
            return 0f
        }
        val safeSigma = sigma.coerceAtLeast(0.001f)
        val du = (u - centerU) / safeSigma
        val dv = (v - centerV) / safeSigma
        return exp((-0.5f * (du * du + dv * dv)).toDouble()).toFloat()
    }

    private fun lerp(start: Float, end: Float, fraction: Float): Float {
        return start + (end - start) * fraction.coerceIn(0f, 1f)
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        if (!edge0.isFinite() || !edge1.isFinite() || !x.isFinite()) {
            return 0f
        }
        val width = edge1 - edge0
        if (kotlin.math.abs(width) < 0.000001f) {
            return if (x >= edge1) 1f else 0f
        }
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
