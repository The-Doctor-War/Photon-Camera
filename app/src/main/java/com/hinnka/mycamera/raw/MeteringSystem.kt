package com.hinnka.mycamera.raw

import com.hinnka.mycamera.utils.PLog
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * 评价测光与场景分析系统
 */
object MeteringSystem {
    private const val TAG = "MeteringSystem"
    private const val DISPLAY_TARGET_LUMA = 0.18f
    private const val MAX_METERING_COMPENSATION_EV = 4f
    private const val LUMA_FLOOR = 0.001f
    private const val MAX_LINEAR_LUMA = 16.0f
    private const val RAW_CURVE_NEUTRAL_WHITE_POINT = 1.0f
    private const val AUTO_DEVELOP_EPSILON = 0.0001f
    private const val AUTO_CLIP_FRACTION = 0.0002f
    private const val SRGB_TRANSFER_THRESHOLD = 0.04045f
    private const val SRGB_LINEAR_SCALE = 12.92f
    private const val SRGB_TRANSFER_A = 0.055f
    private const val SRGB_TRANSFER_GAMMA = 2.4f
    private const val CENTER_WEIGHT_SIGMA = 0.32f
    private const val MIN_METERING_SAMPLE_COUNT = 32

    const val VIEWFINDER_METERING_HIGHLIGHT_EXCLUSION_LUMA = 0.95f


    data class MeteringResult(
        val meteredEv: Float,
        val dynamicRangeGap: Float,
        val avgLuma: Float,
        val clipLow: Float,
        val clipHigh: Float,
        val curveWhitePoint: Float
    ) {
        companion object {
            val EMPTY = MeteringResult(
                meteredEv = 0f,
                dynamicRangeGap = 0f,
                avgLuma = 0f,
                clipLow = 0f,
                clipHigh = 0f,
                curveWhitePoint = 0f
            )
        }
    }

    data class ShadowsHighlightsParams(
        val highlights: Float,
        val shadows: Float,
    ) {
        companion object {
            val NEUTRAL = ShadowsHighlightsParams(
                highlights = 0f,
                shadows = 0f,
            )
        }
    }

    data class GpuRawAutoExposureBaseStats(
        val histogram: IntArray,
        val histogramLogMin: Float,
        val histogramLogMax: Float,
        val weightedLogLumaSum: Double,
        val weightSum: Double,
        val sampleCount: Int,
        val sanitizedSampleCount: Int,
        val groupCount: Int
    )

    data class SrgbThumbnailMeteringStats(
        val weightedLogLumaSum: Double,
        val weightSum: Double,
        val sampleCount: Int,
        val sanitizedSampleCount: Int,
        val highlightRejectedSampleCount: Int,
        val midToneLinearLuma: Float
    )

    class GpuRawAutoExposurePlan internal constructor(
        val compensatedExposureScale: Float,
        val clipLow: Float,
        val clipHigh: Float,
        val p05: Float,
        val p25: Float,
        val p75: Float,
        val p90: Float,
        val p99: Float,
        val compensatedClipLow: Float,
        val compensatedClipHigh: Float,
        val compensatedP05: Float,
        val compensatedP25: Float,
        val compensatedP75: Float,
        val compensatedP90: Float,
        val compensatedP99: Float,
        val baselineExposure: Float,
        val baselineExposureScale: Float,
        val autoExposureScale: Float,
        val targetLuma: Float,
        val meteringCompensationEv: Float,
        val midToneLuma: Float,
        val midToneGain: Float,
        val highlightAnchorGain: Float,
        val adaptiveGain: Float,
        val viewfinderTargetLuma: Float,
        val viewfinderSampleCount: Int,
        val viewfinderSanitizedSampleCount: Int,
        val dcpBaselineExposureOffset: Float,
        val renderingEngineMeteringCompensationEv: Float,
        val totalBaseExposureEv: Float,
        val rawMeteredEv: Float,
        val dynamicRangeGap: Float,
        val sanitizedSampleCount: Int,
        val histogramSampleCount: Int,
        val histogramGroupCount: Int,
        val tag: String
    )

    fun hasManualRawDevelopAdjustments(
        rawExposureCompensation: Float,
        rawHighlightsAdjustment: Float,
        rawShadowsAdjustment: Float
    ): Boolean {
        return abs(rawExposureCompensation) > AUTO_DEVELOP_EPSILON ||
            abs(rawHighlightsAdjustment) > AUTO_DEVELOP_EPSILON ||
            abs(rawShadowsAdjustment) > AUTO_DEVELOP_EPSILON
    }

    fun analyzeSrgbThumbnail(
        width: Int,
        height: Int,
        argbPixels: IntArray
    ): SrgbThumbnailMeteringStats? {
        if (width <= 0 || height <= 0 || argbPixels.size < width * height) {
            return null
        }

        var weightedLogLumaSum = 0.0
        var weightSum = 0.0
        var sampleCount = 0
        var sanitizedSampleCount = 0
        var highlightRejectedSampleCount = 0

        for (y in 0 until height) {
            val yFraction = (y + 0.5f) / height.toFloat()
            for (x in 0 until width) {
                val pixel = argbPixels[y * width + x]
                val alpha = (pixel ushr 24) and 0xff
                if (alpha == 0) continue

                val alphaScale = alpha / 255f
                val r = srgbToLinear(((pixel ushr 16) and 0xff) / 255f)
                val g = srgbToLinear(((pixel ushr 8) and 0xff) / 255f)
                val b = srgbToLinear((pixel and 0xff) / 255f)
                val rawLuma = (0.2126f * r + 0.7152f * g + 0.0722f * b) * alphaScale
                val luma = sanitizeLinearLuma(rawLuma)
                if (luma != rawLuma) {
                    sanitizedSampleCount++
                }
                if (isHighlightExcludedFromMetering(luma)) {
                    highlightRejectedSampleCount++
                    continue
                }

                val xFraction = (x + 0.5f) / width.toFloat()
                val weight = centerWeight(xFraction, yFraction)
                weightedLogLumaSum += log2(luma.coerceAtLeast(LUMA_FLOOR)).toDouble() * weight.toDouble()
                weightSum += weight.toDouble()
                sampleCount++
            }
        }

        if (sampleCount < MIN_METERING_SAMPLE_COUNT || weightSum <= 0.0) {
            return null
        }

        val midToneLinearLuma = sanitizeAverageLuma(
            exp2((weightedLogLumaSum / weightSum).toFloat()),
            DISPLAY_TARGET_LUMA
        )
        return SrgbThumbnailMeteringStats(
            weightedLogLumaSum = weightedLogLumaSum,
            weightSum = weightSum,
            sampleCount = sampleCount,
            sanitizedSampleCount = sanitizedSampleCount,
            highlightRejectedSampleCount = highlightRejectedSampleCount,
            midToneLinearLuma = midToneLinearLuma
        )
    }

    fun prepareGpuLinearRawAutoExposure(
        stats: GpuRawAutoExposureBaseStats,
        baselineExposure: Float,
        viewfinderThumbnailStats: SrgbThumbnailMeteringStats,
        dcpBaselineExposureOffset: Float,
        renderingEngineMeteringCompensationEv: Float,
        meteringCompensationEv: Float = RAW_RENDERING_ENGINE_METERING_COMPENSATION_EV,
        tag: String = "Linear RAW AE GPU"
    ): GpuRawAutoExposurePlan? {
        if (stats.histogram.isEmpty() || stats.sampleCount <= 0) {
            return null
        }

        val safeMeteringCompensationEv = sanitizeMeteringCompensationEv(meteringCompensationEv)
        val targetLuma = viewfinderThumbnailStats.midToneLinearLuma
        val midToneLuma = if (stats.weightSum > 0.0) {
            exp2((stats.weightedLogLumaSum / stats.weightSum).toFloat())
        } else {
            targetLuma
        }.let { sanitizeAverageLuma(it, targetLuma) }

        val clipLow = percentileFromLogHistogram(
            histogram = stats.histogram,
            percentile = AUTO_CLIP_FRACTION,
            logMin = stats.histogramLogMin,
            logMax = stats.histogramLogMax
        )
        val p05 = percentileFromLogHistogram(stats.histogram, 0.05f, stats.histogramLogMin, stats.histogramLogMax)
        val p25 = percentileFromLogHistogram(stats.histogram, 0.25f, stats.histogramLogMin, stats.histogramLogMax)
        val p75 = percentileFromLogHistogram(stats.histogram, 0.75f, stats.histogramLogMin, stats.histogramLogMax)
        val p90 = percentileFromLogHistogram(stats.histogram, 0.90f, stats.histogramLogMin, stats.histogramLogMax)
        val p99 = percentileFromLogHistogram(stats.histogram, 0.99f, stats.histogramLogMin, stats.histogramLogMax)
        val clipHigh = percentileFromLogHistogram(
            histogram = stats.histogram,
            percentile = 1f - AUTO_CLIP_FRACTION,
            logMin = stats.histogramLogMin,
            logMax = stats.histogramLogMax
        )

        val safeDcpBaselineExposureOffset = sanitizeExposureEv(dcpBaselineExposureOffset)
        val safeRenderingEngineMeteringCompensationEv =
            sanitizeExposureEv(renderingEngineMeteringCompensationEv)
        val totalBaseExposureEv = resolveViewfinderMeteringBaseExposureEv(
            baselineExposure = baselineExposure,
            dcpBaselineExposureOffset = safeDcpBaselineExposureOffset,
            renderingEngineMeteringCompensationEv = safeRenderingEngineMeteringCompensationEv
        )
        val baselineExposureScale = exposureScaleFromEv(totalBaseExposureEv)
        val meteringClipLow = (clipLow * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringP05 = (p05 * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringP25 = (p25 * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringP75 = (p75 * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringP90 = (p90 * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringP99 = (p99 * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringClipHigh = (clipHigh * baselineExposureScale).coerceIn(0f, MAX_LINEAR_LUMA)
        val meteringMidToneLuma = (midToneLuma * baselineExposureScale).coerceIn(LUMA_FLOOR, MAX_LINEAR_LUMA)

        val highlightAnchorGain = maxOf(1f, meteringClipHigh) / meteringClipHigh.coerceAtLeast(0.01f)
        val midToneGain = targetLuma / meteringMidToneLuma.coerceAtLeast(LUMA_FLOOR)
        val dynamicRangeGap = evDifference(meteringClipHigh, meteringClipLow)
        val adaptiveGain = midToneGain
        val autoExposureScale = adaptiveGain.coerceIn(0.25f, 4.0f)
        val rawMeteredEv = log2(autoExposureScale)
        val compensatedExposureScale = baselineExposureScale * autoExposureScale

        return GpuRawAutoExposurePlan(
            compensatedExposureScale = compensatedExposureScale,
            clipLow = meteringClipLow,
            clipHigh = meteringClipHigh,
            p05 = meteringP05,
            p25 = meteringP25,
            p75 = meteringP75,
            p90 = meteringP90,
            p99 = meteringP99,
            compensatedClipLow = (meteringClipLow * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            compensatedClipHigh = (meteringClipHigh * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            compensatedP05 = (meteringP05 * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            compensatedP25 = (meteringP25 * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            compensatedP75 = (meteringP75 * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            compensatedP90 = (meteringP90 * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            compensatedP99 = (meteringP99 * autoExposureScale).coerceIn(0f, MAX_LINEAR_LUMA),
            baselineExposure = baselineExposure,
            baselineExposureScale = baselineExposureScale,
            autoExposureScale = autoExposureScale,
            targetLuma = targetLuma,
            meteringCompensationEv = safeMeteringCompensationEv,
            midToneLuma = meteringMidToneLuma,
            midToneGain = midToneGain,
            highlightAnchorGain = highlightAnchorGain,
            adaptiveGain = adaptiveGain,
            viewfinderTargetLuma = targetLuma,
            viewfinderSampleCount = viewfinderThumbnailStats.sampleCount,
            viewfinderSanitizedSampleCount = viewfinderThumbnailStats.sanitizedSampleCount,
            dcpBaselineExposureOffset = safeDcpBaselineExposureOffset,
            renderingEngineMeteringCompensationEv = safeRenderingEngineMeteringCompensationEv,
            totalBaseExposureEv = totalBaseExposureEv,
            rawMeteredEv = rawMeteredEv,
            dynamicRangeGap = dynamicRangeGap,
            sanitizedSampleCount = stats.sanitizedSampleCount,
            histogramSampleCount = stats.sampleCount,
            histogramGroupCount = stats.groupCount,
            tag = tag
        )
    }

    fun finishGpuLinearRawAutoExposure(plan: GpuRawAutoExposurePlan): MeteringResult {
        val curveWhitePoint = if (plan.compensatedClipHigh > 1f) {
            plan.compensatedClipHigh.coerceIn(1.05f, 4.0f)
        } else {
            RAW_CURVE_NEUTRAL_WHITE_POINT
        }

        PLog.d(
            TAG,
            "${plan.tag}: clipLow=${plan.clipLow} clipHigh=${plan.clipHigh} " +
                "p05=${plan.p05} p25=${plan.p25} p75=${plan.p75} p90=${plan.p90} p99=${plan.p99} " +
                "baselineScale=${plan.baselineExposureScale} autoScale=${plan.autoExposureScale} " +
                "finalScale=${plan.compensatedExposureScale} " +
                "compClipLow=${plan.compensatedClipLow} compClipHigh=${plan.compensatedClipHigh} " +
                "compP05=${plan.compensatedP05} compP25=${plan.compensatedP25} " +
                "compP75=${plan.compensatedP75} compP90=${plan.compensatedP90} compP99=${plan.compensatedP99} " +
                "mode=ViewfinderThumbnail baselineExposure=${plan.baselineExposure} " +
                "dcpBaselineExposureOffset=${plan.dcpBaselineExposureOffset} " +
                "engineMeteringEv=${plan.renderingEngineMeteringCompensationEv} " +
                "totalBaseExposureEv=${plan.totalBaseExposureEv} " +
                "target=${plan.targetLuma} meteringCompensationEv=${plan.meteringCompensationEv} " +
                "viewfinderTarget=${plan.viewfinderTargetLuma} " +
                "viewfinderSamples=${plan.viewfinderSampleCount} " +
                "viewfinderSanitizedSamples=${plan.viewfinderSanitizedSampleCount} " +
                "midToneLuma=${plan.midToneLuma} " +
                "midToneGain=${plan.midToneGain} highlightAnchorGain=${plan.highlightAnchorGain} " +
                "gain=${plan.adaptiveGain} ev=${plan.rawMeteredEv} gap=${plan.dynamicRangeGap} " +
                "sanitizedSamples=${plan.sanitizedSampleCount} histogramSamples=${plan.histogramSampleCount} " +
                "histogramGroups=${plan.histogramGroupCount}"
        )

        return MeteringResult(
            meteredEv = plan.rawMeteredEv.coerceIn(-2f, 2f),
            dynamicRangeGap = plan.dynamicRangeGap,
            avgLuma = plan.midToneLuma,
            clipLow = plan.clipLow,
            clipHigh = plan.clipHigh,
            curveWhitePoint = curveWhitePoint
        )
    }

    private fun sanitizeLinearLuma(value: Float): Float {
        return when {
            value.isFinite() -> value.coerceIn(0f, MAX_LINEAR_LUMA)
            value == Float.POSITIVE_INFINITY -> MAX_LINEAR_LUMA
            else -> 0f
        }
    }

    private fun sanitizeAverageLuma(value: Float, fallback: Float): Float {
        return if (value.isFinite() && value > 0f) {
            value.coerceIn(LUMA_FLOOR, MAX_LINEAR_LUMA)
        } else {
            fallback.coerceIn(LUMA_FLOOR, MAX_LINEAR_LUMA)
        }
    }

    fun resolveViewfinderMeteringBaseExposureEv(
        baselineExposure: Float,
        dcpBaselineExposureOffset: Float,
        renderingEngineMeteringCompensationEv: Float
    ): Float {
        return sanitizeExposureEv(
            sanitizeExposureEv(baselineExposure) +
                sanitizeExposureEv(dcpBaselineExposureOffset) +
                sanitizeExposureEv(renderingEngineMeteringCompensationEv)
        )
    }

    fun resolveViewfinderMeteringBaseExposureScale(
        baselineExposure: Float,
        dcpBaselineExposureOffset: Float,
        renderingEngineMeteringCompensationEv: Float
    ): Float {
        return exposureScaleFromEv(
            resolveViewfinderMeteringBaseExposureEv(
                baselineExposure = baselineExposure,
                dcpBaselineExposureOffset = dcpBaselineExposureOffset,
                renderingEngineMeteringCompensationEv = renderingEngineMeteringCompensationEv
            )
        )
    }

    private fun isHighlightExcludedFromMetering(luma: Float): Boolean {
        return luma.isFinite() && luma >= VIEWFINDER_METERING_HIGHLIGHT_EXCLUSION_LUMA
    }

    private fun centerWeight(xFraction: Float, yFraction: Float): Float {
        val dx = (xFraction - 0.5f) / CENTER_WEIGHT_SIGMA
        val dy = (yFraction - 0.5f) / CENTER_WEIGHT_SIGMA
        val gaussian = exp((-0.5f * (dx * dx + dy * dy)).toDouble()).toFloat()
        return 0.35f + gaussian * 0.65f
    }

    private fun srgbToLinear(value: Float): Float {
        val clamped = value.coerceIn(0f, 1f)
        return if (clamped <= SRGB_TRANSFER_THRESHOLD) {
            clamped / SRGB_LINEAR_SCALE
        } else {
            ((clamped + SRGB_TRANSFER_A) / (1f + SRGB_TRANSFER_A)).pow(SRGB_TRANSFER_GAMMA)
        }
    }

    private fun percentileFromLogHistogram(
        histogram: IntArray,
        percentile: Float,
        logMin: Float,
        logMax: Float
    ): Float {
        if (histogram.isEmpty()) {
            return 0f
        }

        var total = 0L
        for (count in histogram) {
            if (count > 0) {
                total += count.toLong()
            }
        }
        if (total <= 0L) {
            return 0f
        }

        val targetIndex = ((total - 1L) * percentile.coerceIn(0f, 1f)).toLong()
        var cumulative = 0L
        for (i in histogram.indices) {
            val count = histogram[i]
            if (count <= 0) continue
            cumulative += count.toLong()
            if (cumulative > targetIndex) {
                val fraction = (i + 0.5f) / histogram.size.toFloat()
                val logLuma = logMin + (logMax - logMin) * fraction
                return exp2(logLuma).coerceIn(0f, MAX_LINEAR_LUMA)
            }
        }

        return exp2(logMax).coerceIn(0f, MAX_LINEAR_LUMA)
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

    private fun exposureScaleFromEv(ev: Float): Float {
        return if (ev.isFinite()) {
            exp2(ev.coerceIn(-MAX_METERING_COMPENSATION_EV, MAX_METERING_COMPENSATION_EV))
        } else {
            1f
        }
    }

    private fun sanitizeExposureEv(ev: Float): Float {
        return if (ev.isFinite()) {
            ev.coerceIn(-MAX_METERING_COMPENSATION_EV, MAX_METERING_COMPENSATION_EV)
        } else {
            0f
        }
    }

    private fun sanitizeMeteringCompensationEv(meteringCompensationEv: Float): Float {
        return if (meteringCompensationEv.isFinite()) {
            meteringCompensationEv.coerceIn(-MAX_METERING_COMPENSATION_EV, MAX_METERING_COMPENSATION_EV)
        } else {
            RAW_RENDERING_ENGINE_METERING_COMPENSATION_EV
        }
    }

}
