package com.hinnka.mycamera.raw

import com.hinnka.mycamera.utils.PLog
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal object DngHdrProfileGainTableGenerator {
    private const val TAG = "DngHdrProfileGainTableGenerator"

    private const val MAP_INPUT_WEIGHT_COUNT = 5
    private const val MIN_BASELINE_EV = 0.05f
    private const val MAX_BASELINE_EV = 8f
    private const val DEFAULT_TABLE_POINTS = 257
    private const val MIN_TABLE_POINTS = 257
    private const val MAX_TABLE_POINTS = 257
    private const val HISTOGRAM_BINS = 256
    private const val TARGET_TILE_PX = 64
    private const val GRID_MIN_H = 8
    private const val GRID_MIN_V = 6
    private const val GRID_MAX_H = 63
    private const val GRID_MAX_V = 72
    private const val INPUT_HEADROOM = 1.12f
    private const val MIN_HIGHLIGHT_GAIN = 0.08f
    private const val MAX_HIGHLIGHT_GAIN = 0.95f
    private val BASE_INPUT_WEIGHTS = floatArrayOf(
        0.1063f,
        0.3576f,
        0.0361f,
        0f,
        0.5f
    )

    fun forHdrBaselineExposure(
        baselineExposureEv: Float,
        tablePointCount: Int = DEFAULT_TABLE_POINTS,
    ): DngProfileGainTableMap? {
        if (!baselineExposureEv.isFinite() || baselineExposureEv <= MIN_BASELINE_EV) {
            return null
        }
        val safeBaselineEv = baselineExposureEv.coerceIn(0f, MAX_BASELINE_EV)
        val safePointCount = tablePointCount.coerceIn(MIN_TABLE_POINTS, MAX_TABLE_POINTS)
        val inputWeights = hdrPgtmInputWeights(safeBaselineEv)
        val gains = buildMonotonicHdrProfileGainTable(
            baselineExposureEv = safeBaselineEv,
            pointCount = safePointCount
        )
        return DngProfileGainTableMap(
            mapPointsV = 1,
            mapPointsH = 1,
            mapSpacingV = 1.0,
            mapSpacingH = 1.0,
            mapOriginV = 0.0,
            mapOriginH = 0.0,
            mapPointsN = safePointCount,
            mapInputWeights = inputWeights,
            gamma = 1f,
            gains = gains,
            sourceTag = DngProfileGainTableMap.TAG_PROFILE_GAIN_TABLE_MAP2
        )
    }

    fun forHdrRawBuffer(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        baselineExposureEv: Float,
        blackLevel: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        whiteLevel: Int = 65535,
        tablePointCount: Int = DEFAULT_TABLE_POINTS,
    ): DngProfileGainTableMap? {
        if (width <= 0 || height <= 0 || rawBuffer.capacity() < width.toLong() * height.toLong() * 2L) {
            return forHdrBaselineExposure(baselineExposureEv, tablePointCount)
        }
        if (!baselineExposureEv.isFinite() || baselineExposureEv <= MIN_BASELINE_EV) {
            return null
        }
        val safeBaselineEv = baselineExposureEv.coerceIn(0f, MAX_BASELINE_EV)
        val safePointCount = tablePointCount.coerceIn(MIN_TABLE_POINTS, MAX_TABLE_POINTS)
        val inputWeights = hdrPgtmInputWeights(safeBaselineEv)
        val grid = chooseHdrPgtmGrid(width, height)
        val histograms = IntArray(grid.mapPointsH * grid.mapPointsV * HISTOGRAM_BINS)
        val sampleCounts = IntArray(grid.mapPointsH * grid.mapPointsV)
        val globalHistogram = IntArray(HISTOGRAM_BINS)
        val samples = accumulateHdrPgtmHistograms(
            rawBuffer = rawBuffer,
            width = width,
            height = height,
            cfaPattern = cfaPattern,
            blackLevel = blackLevel,
            whiteLevel = whiteLevel,
            grid = grid,
            histograms = histograms,
            sampleCounts = sampleCounts,
            globalHistogram = globalHistogram
        )
        if (samples <= 0) {
            return forHdrBaselineExposure(safeBaselineEv, safePointCount)
        }

        val globalStats = statsFromHistogram(
            histogram = globalHistogram,
            offset = 0,
            sampleCount = samples
        )
        val cellStats = Array(grid.mapPointsH * grid.mapPointsV) { index ->
            val count = sampleCounts[index]
            if (count > 0) {
                statsFromHistogram(
                    histogram = histograms,
                    offset = index * HISTOGRAM_BINS,
                    sampleCount = count
                )
            } else {
                globalStats
            }
        }
        val curveParams = smoothHdrPgtmCurveParams(
            stats = cellStats.map {
                buildHdrPgtmCurveParams(
                    cell = it,
                    global = globalStats,
                    baselineExposureEv = safeBaselineEv
                )
            }.toTypedArray(),
            grid = grid
        )
        val gains = FloatArray(grid.mapPointsH * grid.mapPointsV * safePointCount)
        for (y in 0 until grid.mapPointsV) {
            for (x in 0 until grid.mapPointsH) {
                val cellIndex = y * grid.mapPointsH + x
                writeHdrPgtmCurve(
                    output = gains,
                    outputOffset = cellIndex * safePointCount,
                    pointCount = safePointCount,
                    params = curveParams[cellIndex]
                )
            }
        }
        PLog.d(
            TAG,
            "Adaptive HDR ProfileGainTableMap2: grid=${grid.mapPointsH}x${grid.mapPointsV} " +
                "points=$safePointCount samples=$samples baselineEv=$safeBaselineEv " +
                "inputScale=${inputWeights.sum()} highlightGain=${hdrPgtmHighlightGain(safeBaselineEv)} " +
                "globalP50=${globalStats.p50} globalP90=${globalStats.p90} globalP98=${globalStats.p98}"
        )
        return DngProfileGainTableMap(
            mapPointsV = grid.mapPointsV,
            mapPointsH = grid.mapPointsH,
            mapSpacingV = grid.mapSpacingV,
            mapSpacingH = grid.mapSpacingH,
            mapOriginV = 0.0,
            mapOriginH = 0.0,
            mapPointsN = safePointCount,
            mapInputWeights = inputWeights,
            gamma = 1f,
            gains = gains,
            sourceTag = DngProfileGainTableMap.TAG_PROFILE_GAIN_TABLE_MAP2
        )
    }

    private fun chooseHdrPgtmGrid(width: Int, height: Int): HdrPgtmGrid {
        val mapPointsH = ((width + TARGET_TILE_PX - 1) / TARGET_TILE_PX)
            .coerceIn(GRID_MIN_H, GRID_MAX_H)
        val mapPointsV = ((height + TARGET_TILE_PX - 1) / TARGET_TILE_PX)
            .coerceIn(GRID_MIN_V, GRID_MAX_V)
        return HdrPgtmGrid(
            mapPointsH = mapPointsH,
            mapPointsV = mapPointsV,
            mapSpacingH = if (mapPointsH > 1) 1.0 / (mapPointsH - 1).toDouble() else 1.0,
            mapSpacingV = if (mapPointsV > 1) 1.0 / (mapPointsV - 1).toDouble() else 1.0
        )
    }

    private fun accumulateHdrPgtmHistograms(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        cfaPattern: Int,
        blackLevel: FloatArray,
        whiteLevel: Int,
        grid: HdrPgtmGrid,
        histograms: IntArray,
        sampleCounts: IntArray,
        globalHistogram: IntArray,
    ): Int {
        val input = rawBuffer.duplicate().order(ByteOrder.nativeOrder())
        val cfaDim = RawCfaCorrection.repeatPatternDim(cfaPattern)
        val blockW = max(2, cfaDim.getOrElse(1) { 2 })
        val blockH = max(2, cfaDim.getOrElse(0) { 2 })
        val step = chooseHdrPgtmSampleStep(width, height, max(blockW, blockH))
        val safeWhiteLevel = whiteLevel.coerceAtLeast(1).toFloat()
        var samples = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val sample = sampleBayerBlockStats(
                    rawBuffer = input,
                    width = width,
                    height = height,
                    startX = x,
                    startY = y,
                    blockW = blockW,
                    blockH = blockH,
                    cfaPattern = cfaPattern,
                    blackLevel = blackLevel,
                    whiteLevel = safeWhiteLevel
                )
                if (sample != null) {
                    val tableInput = hdrPgtmSampleInput(sample)
                    val bin = (tableInput * (HISTOGRAM_BINS - 1)).toInt()
                        .coerceIn(0, HISTOGRAM_BINS - 1)
                    val gridX = ((x + blockW * 0.5f) / max(1, width) * grid.mapPointsH)
                        .toInt()
                        .coerceIn(0, grid.mapPointsH - 1)
                    val gridY = ((y + blockH * 0.5f) / max(1, height) * grid.mapPointsV)
                        .toInt()
                        .coerceIn(0, grid.mapPointsV - 1)
                    val cellIndex = gridY * grid.mapPointsH + gridX
                    histograms[cellIndex * HISTOGRAM_BINS + bin]++
                    sampleCounts[cellIndex]++
                    globalHistogram[bin]++
                    samples++
                }
                x += step
            }
            y += step
        }
        return samples
    }

    private fun chooseHdrPgtmSampleStep(width: Int, height: Int, blockSize: Int): Int {
        val pixels = width.toLong() * height.toLong()
        val rawStep = when {
            pixels > 32_000_000L -> 8
            pixels > 16_000_000L -> 6
            pixels > 8_000_000L -> 4
            else -> 2
        }
        val aligned = ((rawStep + blockSize - 1) / blockSize) * blockSize
        return aligned.coerceAtLeast(blockSize)
    }

    private fun sampleBayerBlockStats(
        rawBuffer: ByteBuffer,
        width: Int,
        height: Int,
        startX: Int,
        startY: Int,
        blockW: Int,
        blockH: Int,
        cfaPattern: Int,
        blackLevel: FloatArray,
        whiteLevel: Float,
    ): HdrPgtmSample? {
        var redSum = 0f
        var greenSum = 0f
        var blueSum = 0f
        var redCount = 0
        var greenCount = 0
        var blueCount = 0
        val endY = min(height, startY + blockH)
        val endX = min(width, startX + blockW)
        for (y in startY until endY) {
            val rowOffset = y * width
            for (x in startX until endX) {
                val channel = RawCfaCorrection.channelIndexForPixel(cfaPattern, x, y)
                val black = blackLevel.getOrElse(channel) {
                    blackLevel.firstOrNull() ?: 0f
                }
                val value = rawBuffer.getShort((rowOffset + x) * 2).toInt() and 0xFFFF
                val normalized = ((value.toFloat() - black) / max(whiteLevel - black, 1f))
                    .coerceIn(0f, 4f)
                when (RawCfaCorrection.colorCodeForPixel(cfaPattern, x, y)) {
                    0 -> {
                        redSum += normalized
                        redCount++
                    }

                    2 -> {
                        blueSum += normalized
                        blueCount++
                    }

                    else -> {
                        greenSum += normalized
                        greenCount++
                    }
                }
            }
        }
        val totalCount = redCount + greenCount + blueCount
        if (totalCount <= 0) return null
        val fallback = (redSum + greenSum + blueSum) / totalCount.toFloat()
        val red = if (redCount > 0) redSum / redCount.toFloat() else fallback
        val green = if (greenCount > 0) greenSum / greenCount.toFloat() else fallback
        val blue = if (blueCount > 0) blueSum / blueCount.toFloat() else fallback
        val luma = (0.2126f * red + 0.7152f * green + 0.0722f * blue)
            .takeIf { it.isFinite() } ?: return null
        val maxChannel = max(red, max(green, blue)).takeIf { it.isFinite() } ?: return null
        return HdrPgtmSample(
            luma = luma.coerceIn(0f, 4f),
            maxChannel = maxChannel.coerceIn(0f, 4f)
        )
    }

    private fun hdrPgtmSampleInput(sample: HdrPgtmSample): Float {
        val normalizedWeightedInput = 0.5f * sample.luma + 0.5f * sample.maxChannel
        return (normalizedWeightedInput * INPUT_HEADROOM).coerceIn(0f, 1f)
    }

    private fun statsFromHistogram(
        histogram: IntArray,
        offset: Int,
        sampleCount: Int,
    ): HdrPgtmCellStats {
        return HdrPgtmCellStats(
            p10 = percentileFromHistogram(histogram, offset, sampleCount, 0.10f),
            p50 = percentileFromHistogram(histogram, offset, sampleCount, 0.50f),
            p90 = percentileFromHistogram(histogram, offset, sampleCount, 0.90f),
            p98 = percentileFromHistogram(histogram, offset, sampleCount, 0.98f),
            highlightFraction = fractionAboveHistogram(histogram, offset, sampleCount, 0.92f)
        )
    }

    private fun percentileFromHistogram(
        histogram: IntArray,
        offset: Int,
        sampleCount: Int,
        percentile: Float,
    ): Float {
        if (sampleCount <= 0) return 0f
        val target = max(1, (sampleCount * percentile).toInt())
        var cumulative = 0
        for (bin in 0 until HISTOGRAM_BINS) {
            cumulative += histogram[offset + bin]
            if (cumulative >= target) {
                return bin.toFloat() / (HISTOGRAM_BINS - 1).toFloat()
            }
        }
        return 1f
    }

    private fun fractionAboveHistogram(
        histogram: IntArray,
        offset: Int,
        sampleCount: Int,
        threshold: Float,
    ): Float {
        if (sampleCount <= 0) return 0f
        val firstBin = (threshold.coerceIn(0f, 1f) * (HISTOGRAM_BINS - 1)).toInt()
        var count = 0
        for (bin in firstBin until HISTOGRAM_BINS) {
            count += histogram[offset + bin]
        }
        return count.toFloat() / sampleCount.toFloat()
    }

    private fun buildHdrPgtmCurveParams(
        cell: HdrPgtmCellStats,
        global: HdrPgtmCellStats,
        baselineExposureEv: Float,
    ): HdrPgtmCurveParams {
        val globalDynamicRangeEv = log2((global.p98 + 0.006f) / (global.p10 + 0.006f))
            .coerceIn(0f, 8f)
        val sceneContrastStrength = smoothStep(1.8f, 5.5f, globalDynamicRangeEv)
        val hdrStrength = max(
            smoothStep(0.35f, 3.2f, baselineExposureEv),
            0.70f * sceneContrastStrength
        ).coerceIn(0f, 1f)
        val globalMedian = global.p50.coerceIn(0.025f, 0.85f)
        val cellMedian = cell.p50.coerceIn(0.002f, 1f)
        val medianEv = log2(cellMedian / globalMedian)
        val brightRegion = smoothStep(0.25f, 1.7f, medianEv)
        val darkRegion = smoothStep(0.25f, 2.4f, -medianEv)
        val highlightPressure = (
            0.45f * smoothStep(0.48f, 0.88f, cell.p90) +
                0.35f * smoothStep(0.70f, 0.985f, cell.p98) +
                0.20f * smoothStep(0.015f, 0.30f, cell.highlightFraction)
            ).coerceIn(0f, 1f) * hdrStrength
        val combinedPressure = max(
            highlightPressure,
            0.60f * brightRegion * hdrStrength
        ).coerceIn(0f, 1f)
        val localDodgeEv = 0.78f * darkRegion *
            (1f - smoothStep(0.34f, 0.78f, cell.p90)) *
            hdrStrength
        val localBurnEv = (0.38f * brightRegion + 0.32f * combinedPressure) * hdrStrength
        val lowGain = 2.0f.pow((localDodgeEv - localBurnEv).coerceIn(-0.65f, 0.88f))
            .coerceIn(0.62f, 1.85f)
        return HdrPgtmCurveParams(
            lowGain = lowGain,
            highlightGain = hdrPgtmHighlightGain(baselineExposureEv),
            rollPower = lerp(0.66f, 0.42f, max(combinedPressure, brightRegion)),
            rollStart = lerp(0.020f, 0.006f, combinedPressure),
            highlightPressure = combinedPressure
        )
    }

    private fun smoothHdrPgtmCurveParams(
        stats: Array<HdrPgtmCurveParams>,
        grid: HdrPgtmGrid,
    ): Array<HdrPgtmCurveParams> {
        var current = stats
        repeat(2) {
            val next = Array(current.size) { current[it] }
            for (y in 0 until grid.mapPointsV) {
                for (x in 0 until grid.mapPointsH) {
                    var weightSum = 0f
                    var lowGain = 0f
                    var highlightGain = 0f
                    var rollPower = 0f
                    var rollStart = 0f
                    var highlightPressure = 0f
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            val nx = (x + dx).coerceIn(0, grid.mapPointsH - 1)
                            val ny = (y + dy).coerceIn(0, grid.mapPointsV - 1)
                            val weight = if (dx == 0 && dy == 0) 4f else if (dx == 0 || dy == 0) 2f else 1f
                            val value = current[ny * grid.mapPointsH + nx]
                            weightSum += weight
                            lowGain += value.lowGain * weight
                            highlightGain += value.highlightGain * weight
                            rollPower += value.rollPower * weight
                            rollStart += value.rollStart * weight
                            highlightPressure += value.highlightPressure * weight
                        }
                    }
                    val index = y * grid.mapPointsH + x
                    next[index] = HdrPgtmCurveParams(
                        lowGain = lowGain / weightSum,
                        highlightGain = highlightGain / weightSum,
                        rollPower = rollPower / weightSum,
                        rollStart = rollStart / weightSum,
                        highlightPressure = highlightPressure / weightSum,
                    )
                }
            }
            current = next
        }
        return current
    }

    private fun writeHdrPgtmCurve(
        output: FloatArray,
        outputOffset: Int,
        pointCount: Int,
        params: HdrPgtmCurveParams,
    ) {
        var previousOutput = 0f
        for (index in 0 until pointCount) {
            val input = tableInputForIndex(index, pointCount)
            if (input <= 1e-6f) {
                output[outputOffset + index] = params.lowGain
                previousOutput = 0f
                continue
            }
            val rollInput = input.coerceIn(0f, 1f).pow(params.rollPower.coerceIn(0.30f, 0.85f))
            val roll = smoothStep(params.rollStart, 1f, rollInput)
            val highlightRoll = params.highlightPressure * smoothStep(0.12f, 0.78f, input)
            val gainMix = max(roll, highlightRoll).coerceIn(0f, 1f)
            val gain = 2.0f.pow(lerp(
                log2(params.lowGain),
                log2(params.highlightGain),
                gainMix
            ))
            var targetOutput = input * gain
            val outputCeiling = params.highlightGain * lerp(
                0.70f,
                1f,
                smoothStep(0.035f, 1f, input)
            )
            targetOutput = min(targetOutput, outputCeiling)
            val monotonicOutput = max(targetOutput, previousOutput)
            output[outputOffset + index] = (monotonicOutput / input).coerceIn(
                0.05f,
                2.2f
            )
            previousOutput = monotonicOutput
        }
    }

    private fun buildMonotonicHdrProfileGainTable(
        baselineExposureEv: Float,
        pointCount: Int,
    ): FloatArray {
        val params = HdrPgtmCurveParams(
            lowGain = 1f,
            highlightGain = hdrPgtmHighlightGain(baselineExposureEv),
            rollPower = 0.54f,
            rollStart = 0.014f,
            highlightPressure = smoothStep(0.35f, 3.2f, baselineExposureEv)
        )
        return FloatArray(pointCount).also { gains ->
            writeHdrPgtmCurve(
                output = gains,
                outputOffset = 0,
                pointCount = pointCount,
                params = params
            )
        }
    }

    private fun hdrPgtmInputWeights(baselineExposureEv: Float): FloatArray {
        val scale = hdrPgtmHighlightGain(baselineExposureEv)
        return FloatArray(MAP_INPUT_WEIGHT_COUNT) { index ->
            BASE_INPUT_WEIGHTS[index] * scale
        }
    }

    private fun hdrPgtmHighlightGain(baselineExposureEv: Float): Float {
        val baselineGain = 2.0f.pow(baselineExposureEv.coerceIn(0f, MAX_BASELINE_EV))
        return (INPUT_HEADROOM / baselineGain)
            .coerceIn(MIN_HIGHLIGHT_GAIN, MAX_HIGHLIGHT_GAIN)
    }

    private fun tableInputForIndex(index: Int, pointCount: Int): Float {
        if (pointCount <= 1) return 0f
        return if (index == pointCount - 1) {
            1f
        } else {
            index.toFloat() / pointCount.toFloat()
        }
    }

    private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / max(edge1 - edge0, 1e-6f)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float {
        return a + (b - a) * min(max(t, 0f), 1f)
    }

    private fun log2(value: Float): Float {
        return (ln(max(value, 1e-6f).toDouble()) / ln(2.0)).toFloat()
    }

    private data class HdrPgtmGrid(
        val mapPointsH: Int,
        val mapPointsV: Int,
        val mapSpacingH: Double,
        val mapSpacingV: Double,
    )

    private data class HdrPgtmCellStats(
        val p10: Float,
        val p50: Float,
        val p90: Float,
        val p98: Float,
        val highlightFraction: Float,
    )

    private data class HdrPgtmSample(
        val luma: Float,
        val maxChannel: Float,
    )

    private data class HdrPgtmCurveParams(
        val lowGain: Float,
        val highlightGain: Float,
        val rollPower: Float,
        val rollStart: Float,
        val highlightPressure: Float,
    )
}
