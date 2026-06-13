package com.hinnka.mycamera.raw

import android.annotation.SuppressLint
import android.util.Half
import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ShortBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

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


    data class MeteringResult(
        val meteredEv: Float,
        val dynamicRangeGap: Float,
        val avgLuma: Float,
        val p05: Float,
        val p50: Float,
        val p95: Float,
        val p99: Float,
        val p998: Float
    ) {
        fun scaleLuma(scale: Float): MeteringResult {
            val safeScale = if (scale.isFinite() && scale > 0f) scale else 1f
            return copy(
                avgLuma = (avgLuma * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                p05 = (p05 * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                p50 = (p50 * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                p95 = (p95 * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                p99 = (p99 * safeScale).coerceIn(0f, MAX_LINEAR_LUMA),
                p998 = (p998 * safeScale).coerceIn(0f, MAX_LINEAR_LUMA)
            )
        }

        companion object {
            val EMPTY = MeteringResult(
                meteredEv = 0f,
                dynamicRangeGap = 0f,
                avgLuma = 0f,
                p05 = 0f,
                p50 = 0f,
                p95 = 0f,
                p99 = 0f,
                p998 = 0f
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

    @SuppressLint("HalfFloat")
    fun analyzeLinearHalfFloatExposureEv(
        pixelBuffer: ShortBuffer,
        width: Int,
        height: Int,
        weightBuffer: ByteBuffer? = null, // Optional weight mask (e.g. depth map)
        linearExposureGain: Float
    ): MeteringResult {
        return analyzeExposureEv(
            width = width,
            height = height,
            weightBuffer = weightBuffer,
            targetLuma = DISPLAY_TARGET_LUMA * 2f.pow(-ACR3_AVERAGE_TONE_CURVE_EV),
            linearExposureGain = linearExposureGain,
            tag = "Linear RAW AE"
        ) { index ->
            val base = index * 4
            val r = Half.toFloat(pixelBuffer.get(base))
            val g = Half.toFloat(pixelBuffer.get(base + 1))
            val b = Half.toFloat(pixelBuffer.get(base + 2))
            r * 0.2126f + g * 0.7152f + b * 0.0722f
        }
    }

    private inline fun analyzeExposureEv(
        width: Int,
        height: Int,
        weightBuffer: ByteBuffer?,
        targetLuma: Float,
        linearExposureGain: Float,
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
        val p05 = percentile(lumas, 0.05f)
        val p50 = percentile(lumas, 0.50f)
        val p95 = percentile(lumas, 0.95f)
        val p99 = percentile(lumas, 0.99f)
        val p998 = percentile(lumas, 0.998f)

        val highlightAnchorGain = 1f //linearExposureGain / p998.coerceAtLeast(0.01f)
        val avgLuma = midToneLuma
        val midToneGain = targetLuma / midToneLuma.coerceAtLeast(LUMA_FLOOR)
        val dynamicRangeGap = midToneGain / highlightAnchorGain

        val extra = smoothStep(0.66f, 2.2f, dynamicRangeGap)
        val adaptiveGain = lerp(midToneGain * 1.2f, highlightAnchorGain * 1.2f, extra)
        val rawMeteredEv = log2(adaptiveGain.coerceIn(0.25f, 4.0f))

        PLog.d(
            TAG,
            "$tag: p05=$p05 p50=$p50 p95=$p95 p99=$p99 p998=$p998 " +
                "target=$targetLuma midToneLuma=$midToneLuma " +
                "midToneZoneMedianLuma=${midToneReference.zoneMedianLuma} " +
                "midToneBucketMedianLuma=${midToneReference.bucketMedianLuma} " +
                "midToneZones=${midToneReference.retainedZoneCount} midToneBuckets=${midToneReference.retainedBucketCount} " +
                "midToneGain=$midToneGain highlightAnchorGain=$highlightAnchorGain gain=$adaptiveGain " +
                "ev=$rawMeteredEv gap=$dynamicRangeGap " +
                "depth=${depthWeights?.enabled == true} depthSeparation=${depthWeights?.separation ?: 0f} " +
                "sanitized=$sanitizedSampleCount"
        )

        return MeteringResult(
            meteredEv = rawMeteredEv.coerceIn(-2f, 2f),
            dynamicRangeGap = dynamicRangeGap,
            avgLuma = avgLuma,
            p05 = p05,
            p50 = p50,
            p95 = p95,
            p99 = p99,
            p998 = p998
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

    private fun percentile(sortedValues: FloatArray, percentile: Float): Float {
        if (sortedValues.isEmpty()) {
            return 0f
        }

        val index = ((sortedValues.size - 1) * percentile.coerceIn(0f, 1f)).toInt()
        return sortedValues[index]
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
