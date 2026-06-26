package com.hinnka.mycamera.lut

import android.opengl.GLES30

object ShadowsHighlightsShader {
    fun bindUniforms(program: Int, highlights: Float, shadows: Float) {
        bindUniformLocations(
            highlightsLocation = GLES30.glGetUniformLocation(program, "uHighlights"),
            shadowsLocation = GLES30.glGetUniformLocation(program, "uShadows"),
            highlights = highlights,
            shadows = shadows
        )
    }

    fun bindUniformLocations(
        highlightsLocation: Int,
        shadowsLocation: Int,
        highlights: Float,
        shadows: Float
    ) {
        GLES30.glUniform1f(highlightsLocation, highlights)
        GLES30.glUniform1f(shadowsLocation, shadows)
    }

    val GLSL = """
        const float SH_LAB_EPSILON = 216.0 / 24389.0;
        const float SH_LAB_KAPPA = 24389.0 / 27.0;
        const float SH_LOW_APPROX = 0.000001;
        const float SH_RANGE_SIGMA = 0.115;
        const float SH_RANGE_SIGMA2 = SH_RANGE_SIGMA * SH_RANGE_SIGMA;
        // Negative highlights recover high-key structure: move the highlight base out of
        // the near-white range, expand L - base detail, and keep chroma from washing out.
        const float SH_HIGHLIGHT_START = 0.38;
        const float SH_HIGHLIGHT_FULL = 0.78;
        const float SH_HIGHLIGHT_RECOVERY_SHIFT = 0.12;
        const float SH_HIGHLIGHT_BRIGHTEN_SHIFT = 0.12;
        const float SH_HIGHLIGHT_DETAIL_GAIN = 1.15;
        const float SH_HIGHLIGHT_CHROMA_GAIN = 0.55;
        const float SH_SHADOW_FULL = 0.22;
        const float SH_SHADOW_END = 0.62;
        const float SH_SHADOW_MAX_SHIFT = 0.18;
        const float SH_DETAIL_LIMIT = 0.22;
        const float SH_DETAIL_GAIN_LIMIT = 2.2;

        float shSanitizeFloat(float value) {
            if (value != value) return 0.0;
            return value;
        }

        vec3 shSanitizeColor(vec3 color) {
            return vec3(
                shSanitizeFloat(color.r),
                shSanitizeFloat(color.g),
                shSanitizeFloat(color.b)
            );
        }

        vec3 shLabF(vec3 value) {
            vec3 linearPart = (SH_LAB_KAPPA * value + vec3(16.0)) / 116.0;
            vec3 cubePart = pow(max(value, vec3(0.0)), vec3(1.0 / 3.0));
            return mix(linearPart, cubePart, step(vec3(SH_LAB_EPSILON), value));
        }

        vec3 shLabFInv(vec3 value) {
            vec3 cubePart = value * value * value;
            vec3 linearPart = (116.0 * value - vec3(16.0)) / SH_LAB_KAPPA;
            return mix(linearPart, cubePart, step(vec3(0.20689655172413796), value));
        }

        vec3 shXyzToLab(vec3 xyz) {
            vec3 f = shLabF(xyz / vec3(0.9642, 1.0, 0.8249));
            return vec3(116.0 * f.y - 16.0, 500.0 * (f.x - f.y), 200.0 * (f.y - f.z));
        }

        vec3 shLabToXyz(vec3 lab) {
            float fy = (lab.x + 16.0) / 116.0;
            vec3 f = vec3(lab.y / 500.0 + fy, fy, fy - lab.z / 200.0);
            return vec3(0.9642, 1.0, 0.8249) * shLabFInv(f);
        }

        vec3 shRgbToLabScaled(vec3 color) {
            vec3 lab = shXyzToLab(shRgbToXyz(color));
            return lab / vec3(100.0, 128.0, 128.0);
        }

        vec3 shLabScaledToRgb(vec3 labScaled) {
            vec3 lab = labScaled * vec3(100.0, 128.0, 128.0);
            return shXyzToRgb(shLabToXyz(lab));
        }

        float shTonalRangeWeight(float sampleL, float centerL) {
            float delta = sampleL - centerL;
            float bilateral = exp(-(delta * delta) / max(2.0 * SH_RANGE_SIGMA2, SH_LOW_APPROX));
            float edgeStop = 1.0 - smoothstep(0.16, 0.32, abs(delta));
            return bilateral * edgeStop;
        }

        void shAddBaseSample(vec2 uv, float centerL, float spatialWeight, inout float sum, inout float weightSum) {
            float sampleL = shRgbToLabScaled(sampleToneSource(clamp(uv, vec2(0.0), vec2(1.0)))).x;
            float weight = spatialWeight * shTonalRangeWeight(sampleL, centerL);
            sum += sampleL * weight;
            weightSum += weight;
        }

        float shSampleBaseL(vec2 uv, float centerL) {
            vec2 radiusSmall = uTexelSize * 8.0;
            vec2 radiusMid = uTexelSize * 40.0;
            vec2 radiusLarge = uTexelSize * 144.0;

            float sum = centerL * 0.55;
            float weightSum = 0.55;

            shAddBaseSample(uv + vec2(radiusSmall.x, 0.0), centerL, 0.14, sum, weightSum);
            shAddBaseSample(uv - vec2(radiusSmall.x, 0.0), centerL, 0.14, sum, weightSum);
            shAddBaseSample(uv + vec2(0.0, radiusSmall.y), centerL, 0.14, sum, weightSum);
            shAddBaseSample(uv - vec2(0.0, radiusSmall.y), centerL, 0.14, sum, weightSum);
            shAddBaseSample(uv + radiusSmall, centerL, 0.09, sum, weightSum);
            shAddBaseSample(uv - radiusSmall, centerL, 0.09, sum, weightSum);
            shAddBaseSample(uv + vec2(radiusSmall.x, -radiusSmall.y), centerL, 0.09, sum, weightSum);
            shAddBaseSample(uv + vec2(-radiusSmall.x, radiusSmall.y), centerL, 0.09, sum, weightSum);

            shAddBaseSample(uv + vec2(radiusMid.x, 0.0), centerL, 0.075, sum, weightSum);
            shAddBaseSample(uv - vec2(radiusMid.x, 0.0), centerL, 0.075, sum, weightSum);
            shAddBaseSample(uv + vec2(0.0, radiusMid.y), centerL, 0.075, sum, weightSum);
            shAddBaseSample(uv - vec2(0.0, radiusMid.y), centerL, 0.075, sum, weightSum);

            shAddBaseSample(uv + vec2(radiusLarge.x, 0.0), centerL, 0.035, sum, weightSum);
            shAddBaseSample(uv - vec2(radiusLarge.x, 0.0), centerL, 0.035, sum, weightSum);
            shAddBaseSample(uv + vec2(0.0, radiusLarge.y), centerL, 0.035, sum, weightSum);
            shAddBaseSample(uv - vec2(0.0, radiusLarge.y), centerL, 0.035, sum, weightSum);

            return sum / max(weightSum, 0.0001);
        }

        float shSmoothUnit(float value) {
            float t = clamp(value, 0.0, 1.0);
            return t * t * (3.0 - 2.0 * t);
        }

        float shHighlightWeight(float value) {
            return shSmoothUnit(
                (value - SH_HIGHLIGHT_START) / max(SH_HIGHLIGHT_FULL - SH_HIGHLIGHT_START, SH_LOW_APPROX)
            );
        }

        float shShadowWeight(float value) {
            return shSmoothUnit(
                (SH_SHADOW_END - value) / max(SH_SHADOW_END - SH_SHADOW_FULL, SH_LOW_APPROX)
            );
        }

        float shEdgeConfidence(float baseL, float centerL) {
            return 1.0 - smoothstep(SH_RANGE_SIGMA * 1.35, SH_RANGE_SIGMA * 2.7, abs(centerL - baseL));
        }

        float shHighlightRegionMask(float baseL, float centerL) {
            return shHighlightWeight(baseL) * shEdgeConfidence(baseL, centerL);
        }

        float shApplyHighlightBase(float baseL, float centerL, float highlights) {
            float mask = shHighlightRegionMask(baseL, centerL);
            if (highlights < 0.0) {
                return baseL - (-highlights) * SH_HIGHLIGHT_RECOVERY_SHIFT * mask;
            }

            return baseL + highlights * SH_HIGHLIGHT_BRIGHTEN_SHIFT * mask;
        }

        float shApplyShadowBase(float baseL, float centerL, float shadows) {
            float mask = min(shShadowWeight(baseL), shShadowWeight(centerL));
            if (shadows > 0.0) {
                return baseL + shadows * SH_SHADOW_MAX_SHIFT * mask;
            }

            return baseL - (-shadows) * SH_SHADOW_MAX_SHIFT * mask;
        }

        float shAdjustTone(float value, float highlights, float shadows) {
            float adjusted = shApplyHighlightBase(value, value, highlights);
            adjusted = shApplyShadowBase(adjusted, value, shadows);
            return clamp(adjusted, 0.0, 1.0);
        }

        float shHighlightDetailGain(float baseL, float highlights) {
            if (highlights >= 0.0) {
                return 1.0;
            }

            float highMask = shHighlightWeight(baseL);
            float targetGain = 1.0 + (-highlights) * SH_HIGHLIGHT_DETAIL_GAIN;
            return mix(1.0, min(targetGain, SH_DETAIL_GAIN_LIMIT), highMask);
        }

        float shBoundedDetailScale(float adjustedBaseL, float detail, float targetGain) {
            if (abs(detail) < SH_LOW_APPROX) {
                return 1.0;
            }

            float boundScale = detail > 0.0
                ? (1.0 - adjustedBaseL) / max(detail, SH_LOW_APPROX)
                : adjustedBaseL / max(-detail, SH_LOW_APPROX);
            return clamp(min(targetGain, boundScale), 0.0, targetGain);
        }

        vec3 applyShadowsHighlights(vec3 inputColor, vec2 uv) {
            float highlights = clamp(uHighlights, -1.0, 1.0);
            float shadows = clamp(uShadows, -1.0, 1.0);
            if (abs(highlights) < 0.001 && abs(shadows) < 0.001) {
                return inputColor;
            }

            vec3 lab = shRgbToLabScaled(inputColor);
            float centerL = clamp(lab.x, 0.0, 1.0);
            float baseL = clamp(shSampleBaseL(uv, centerL), 0.0, 1.0);
            float detail = clamp(centerL - baseL, -SH_DETAIL_LIMIT, SH_DETAIL_LIMIT);

            float highlightBaseL = shApplyHighlightBase(baseL, centerL, highlights);
            float adjustedBaseL = shApplyShadowBase(highlightBaseL, centerL, shadows);
            adjustedBaseL = clamp(adjustedBaseL, 0.0, 1.0);

            float detailGain = shHighlightDetailGain(baseL, highlights);
            float detailScale = shBoundedDetailScale(adjustedBaseL, detail, detailGain);
            float localL = clamp(adjustedBaseL + detail * detailScale, 0.0, 1.0);
            float directL = shAdjustTone(centerL, highlights, shadows);
            float edgeFallback = smoothstep(SH_RANGE_SIGMA * 1.5, SH_RANGE_SIGMA * 3.0, abs(centerL - baseL));
            lab.x = mix(localL, directL, edgeFallback);

            float chromaMask = max(-highlights, 0.0) * shHighlightRegionMask(baseL, centerL);
            lab.yz *= 1.0 + SH_HIGHLIGHT_CHROMA_GAIN * chromaMask;

            return shSanitizeColor(shLabScaledToRgb(lab));
        }
    """.trimIndent()
}
