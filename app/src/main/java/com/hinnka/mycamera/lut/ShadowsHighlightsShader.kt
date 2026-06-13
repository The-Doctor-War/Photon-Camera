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
        const float SH_COMPRESS = 0.5;
        const float SH_HIGHLIGHTS_COLOR_ADJUSTMENT = 0.6;
        const float SH_SHADOWS_COLOR_ADJUSTMENT = 1.0;

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

        float shSign(float value) {
            return value < 0.0 ? -1.0 : 1.0;
        }

        float shSignedInv(float value, float signSource) {
            float inv = abs(value) > SH_LOW_APPROX ? 1.0 / abs(value) : 1.0 / SH_LOW_APPROX;
            return signSource < 0.0 ? -inv : inv;
        }

        float shColorCorrection(float adjustment, float signSource) {
            return (clamp(adjustment, 0.0, 1.0) - 0.5) * shSign(signSource) + 0.5;
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

        void shAddBaseSample(vec2 uv, float centerL, float spatialWeight, inout float sum, inout float weightSum) {
            float sampleL = shRgbToLabScaled(sampleToneSource(clamp(uv, vec2(0.0), vec2(1.0)))).x;
            float delta = sampleL - centerL;
            float rangeWeight = exp(-(delta * delta) / 0.25);
            float weight = spatialWeight * rangeWeight;
            sum += sampleL * weight;
            weightSum += weight;
        }

        float shSampleBaseL(vec2 uv, vec3 centerLab) {
            float centerL = centerLab.x;
            vec2 radiusSmall = uTexelSize * 8.0;
            vec2 radiusMid = uTexelSize * 32.0;
            vec2 radiusLarge = uTexelSize * 112.0;

            float sum = centerL * 0.22;
            float weightSum = 0.22;

            shAddBaseSample(uv + vec2(radiusSmall.x, 0.0), centerL, 0.13, sum, weightSum);
            shAddBaseSample(uv - vec2(radiusSmall.x, 0.0), centerL, 0.13, sum, weightSum);
            shAddBaseSample(uv + vec2(0.0, radiusSmall.y), centerL, 0.13, sum, weightSum);
            shAddBaseSample(uv - vec2(0.0, radiusSmall.y), centerL, 0.13, sum, weightSum);
            shAddBaseSample(uv + radiusSmall, centerL, 0.08, sum, weightSum);
            shAddBaseSample(uv - radiusSmall, centerL, 0.08, sum, weightSum);
            shAddBaseSample(uv + vec2(radiusSmall.x, -radiusSmall.y), centerL, 0.08, sum, weightSum);
            shAddBaseSample(uv + vec2(-radiusSmall.x, radiusSmall.y), centerL, 0.08, sum, weightSum);

            shAddBaseSample(uv + vec2(radiusMid.x, 0.0), centerL, 0.07, sum, weightSum);
            shAddBaseSample(uv - vec2(radiusMid.x, 0.0), centerL, 0.07, sum, weightSum);
            shAddBaseSample(uv + vec2(0.0, radiusMid.y), centerL, 0.07, sum, weightSum);
            shAddBaseSample(uv - vec2(0.0, radiusMid.y), centerL, 0.07, sum, weightSum);

            shAddBaseSample(uv + vec2(radiusLarge.x, 0.0), centerL, 0.035, sum, weightSum);
            shAddBaseSample(uv - vec2(radiusLarge.x, 0.0), centerL, 0.035, sum, weightSum);
            shAddBaseSample(uv + vec2(0.0, radiusLarge.y), centerL, 0.035, sum, weightSum);
            shAddBaseSample(uv - vec2(0.0, radiusLarge.y), centerL, 0.035, sum, weightSum);

            return sum / max(weightSum, 0.0001);
        }

        vec3 shOverlay(vec3 a, vec3 b, float opacity, float transform, float ccorrect) {
            float opacity2 = opacity * opacity;
            for (int i = 0; i < 4; i++) {
                if (opacity2 <= 0.0) break;

                float la = a.x;
                float lb = (b.x - 0.5) * shSign(opacity) * shSign(1.0 - la) + 0.5;
                lb = clamp(lb, 0.0, 1.0);
                float lref = shSignedInv(la, la);
                float href = shSignedInv(1.0 - la, 1.0 - la);

                float chunk = opacity2 > 1.0 ? 1.0 : opacity2;
                float optrans = chunk * transform;
                opacity2 -= 1.0;

                float overL = la > 0.5
                    ? 1.0 - (1.0 - 2.0 * (la - 0.5)) * (1.0 - lb)
                    : 2.0 * la * lb;
                a.x = la * (1.0 - optrans) + overL * optrans;

                float chromaFactor = a.x * lref * ccorrect + (1.0 - a.x) * href * (1.0 - ccorrect);
                a.y = a.y * (1.0 - optrans) + (a.y + b.y) * chromaFactor * optrans;
                a.z = a.z * (1.0 - optrans) + (a.z + b.z) * chromaFactor * optrans;
            }
            return a;
        }

        vec3 applyShadowsHighlights(vec3 inputColor, vec2 uv) {
            float highlights = 2.0 * clamp(uHighlights, -1.0, 1.0);
            float shadows = 2.0 * clamp(uShadows, -1.0, 1.0);
            if (abs(highlights) < 0.001 && abs(shadows) < 0.001) {
                return inputColor;
            }

            vec3 lab = shRgbToLabScaled(inputColor);
            float baseL = shSampleBaseL(uv, lab);
            vec3 maskLab = vec3(1.0 - baseL, 0.0, 0.0);
            float compressDenom = max(1.0 - SH_COMPRESS, 0.0001);

            float highlightsXform = clamp(1.0 - maskLab.x / compressDenom, 0.0, 1.0);
            float highlightsCcorrect = shColorCorrection(SH_HIGHLIGHTS_COLOR_ADJUSTMENT, -highlights);
            lab = shOverlay(lab, maskLab, -highlights, highlightsXform, 1.0 - highlightsCcorrect);

            float shadowsXform = clamp(maskLab.x / compressDenom - SH_COMPRESS / compressDenom, 0.0, 1.0);
            float shadowsCcorrect = shColorCorrection(SH_SHADOWS_COLOR_ADJUSTMENT, shadows);
            lab = shOverlay(lab, maskLab, shadows, shadowsXform, shadowsCcorrect);

            return shSanitizeColor(shLabScaledToRgb(lab));
        }
    """.trimIndent()
}
