package com.hinnka.mycamera.raw

/**
 * OpenGL ES 3.1 compute port of darktable's denoiseprofile.cl wavelets path.
 *
 * The pipeline mirrors darktable process_wavelets_cl:
 * precondition -> repeated decompose -> repeated reduce/synthesize -> backtransform.
 */
object DenoiseProfileShaders {
    const val BANDS = 7
    const val REDUCE_FIRST_LOCAL_X = 16
    const val REDUCE_FIRST_LOCAL_Y = 16
    const val REDUCE_SECOND_LOCAL_X = 256
    const val REDUCE_SIZE = 64
    const val FLAT_AREA_BOOST = 1.2f

    private const val COMMON = """
        precision highp float;
        precision highp int;

        vec4 readPixel(sampler2D image, ivec2 coord, ivec2 size) {
            ivec2 c = clamp(coord, ivec2(0), size - ivec2(1));
            return texelFetch(image, c, 0);
        }

        vec4 dtPow(vec4 a, vec4 b) {
            return pow(a, b);
        }
    """

    val PRECONDITION_Y0U0V0 = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uInput;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutput;

        uniform ivec2 uImageSize;
        uniform vec4 uA;
        uniform vec4 uP;
        uniform vec4 uB;
        uniform mat3 uToY0U0V0;

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;

            vec4 pixel = readPixel(uInput, coord, uImageSize);
            float alpha = pixel.a;
            vec4 t = max(
                2.0 * dtPow(max(vec4(0.0), pixel + uB), 1.0 - uP / 2.0) /
                ((-uP + 2.0) * sqrt(uA)),
                vec4(0.0)
            );
            vec3 outRgb = uToY0U0V0 * t.rgb;
            imageStore(uOutput, coord, vec4(outRgb, alpha));
        }
    """.trimIndent()

    val PRECONDITION_V2 = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uInput;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutput;

        uniform ivec2 uImageSize;
        uniform vec4 uA;
        uniform vec4 uP;
        uniform vec4 uB;
        uniform vec4 uWb;

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;

            vec4 pixel = readPixel(uInput, coord, uImageSize);
            float alpha = pixel.a;
            vec4 t = max(
                2.0 * dtPow(max(vec4(0.0), pixel / uWb + uB), 1.0 - uP / 2.0) /
                ((-uP + 2.0) * sqrt(uA)),
                vec4(0.0)
            );
            t.a = alpha;
            imageStore(uOutput, coord, t);
        }
    """.trimIndent()

    val DECOMPOSE = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uInput;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uCoarse;
        layout(rgba16f, binding = 2) writeonly uniform highp image2D uDetail;

        uniform ivec2 uImageSize;
        uniform int uScale;
        uniform float uInvSigma2;

        const float uFilter[25] = float[25](
            0.00390625, 0.015625, 0.0234375, 0.015625, 0.00390625,
            0.015625,   0.0625,   0.09375,   0.0625,   0.015625,
            0.0234375,  0.09375,  0.140625,  0.09375,  0.0234375,
            0.015625,   0.0625,   0.09375,   0.0625,   0.015625,
            0.00390625, 0.015625, 0.0234375, 0.015625, 0.00390625
        );

        vec4 weight(vec4 c1, vec4 c2, float invSigma2) {
            vec4 sqr = (c1 - c2) * (c1 - c2);
            float dt = (sqr.x + sqr.y + sqr.z) * invSigma2;
            float r = exp2(-max(0.0, dt * 0.02 - 9.0));
            return vec4(r);
        }

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;

            int mult = 1 << uScale;
            vec4 pixel = readPixel(uInput, coord, uImageSize);
            vec4 sum = vec4(0.0);
            vec4 wgt = vec4(0.0);

            for (int j = 0; j < 5; j++) {
                for (int i = 0; i < 5; i++) {
                    ivec2 sampleCoord = coord + ivec2(mult * (i - 2), mult * (j - 2));
                    int k = j * 5 + i;
                    vec4 px = readPixel(uInput, sampleCoord, uImageSize);
                    vec4 w = uFilter[k] * weight(pixel, px, uInvSigma2);
                    sum += w * px;
                    wgt += w;
                }
            }

            sum /= max(wgt, vec4(1e-20));
            sum.a = pixel.a;
            imageStore(uDetail, coord, pixel - sum);
            imageStore(uCoarse, coord, sum);
        }
    """.trimIndent()

    val SYNTHESIZE = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uCoarse;
        layout(binding = 1) uniform highp sampler2D uDetail;
        layout(rgba16f, binding = 2) writeonly uniform highp image2D uOutput;

        uniform ivec2 uImageSize;
        uniform vec4 uThreshold;
        uniform vec4 uBoost;

        const float FLAT_AREA_BOOST = $FLAT_AREA_BOOST;

        vec4 copySign(vec4 x, vec4 y) {
            return abs(x) * sign(y);
        }

        float luminanceDistance(vec4 a, vec4 b) {
            vec3 diff = a.rgb - b.rgb;
            return sqrt(dot(diff, diff));
        }

        float flatAreaWeight(ivec2 coord, vec4 center, vec4 threshold) {
            vec4 left = readPixel(uCoarse, coord + ivec2(-1, 0), uImageSize);
            vec4 right = readPixel(uCoarse, coord + ivec2(1, 0), uImageSize);
            vec4 up = readPixel(uCoarse, coord + ivec2(0, -1), uImageSize);
            vec4 down = readPixel(uCoarse, coord + ivec2(0, 1), uImageSize);
            float localGradient = max(
                max(luminanceDistance(center, left), luminanceDistance(center, right)),
                max(luminanceDistance(center, up), luminanceDistance(center, down))
            );
            float edgeThreshold = max(1e-5, threshold.x);
            return 1.0 - smoothstep(edgeThreshold * 0.5, edgeThreshold * 2.0, localGradient);
        }

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;

            vec4 c = readPixel(uCoarse, coord, uImageSize);
            vec4 d = readPixel(uDetail, coord, uImageSize);
            float flatWeight = flatAreaWeight(coord, c, uThreshold);
            vec4 localThreshold = uThreshold * (1.0 + FLAT_AREA_BOOST * flatWeight);
            vec4 amount = copySign(max(vec4(0.0), abs(d) - localThreshold), d);
            vec4 sum = c + uBoost * amount;
            sum.a = c.a;
            imageStore(uOutput, coord, sum);
        }
    """.trimIndent()

    val REDUCE_FIRST = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uInput;

        layout(std430, binding = 0) buffer AccuBuffer { vec4 accu[]; };
        shared vec4 sBuffer[${REDUCE_FIRST_LOCAL_X * REDUCE_FIRST_LOCAL_Y}];
        uniform ivec2 uImageSize;

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            int lid = int(gl_LocalInvocationID.y) * int(gl_WorkGroupSize.x) + int(gl_LocalInvocationID.x);
            bool inImage = coord.x < uImageSize.x && coord.y < uImageSize.y;
            vec4 pixel = inImage ? readPixel(uInput, coord, uImageSize) : vec4(0.0);
            sBuffer[lid] = inImage ? pixel * pixel : vec4(0.0);

            barrier();
            memoryBarrierShared();

            for (int offset = ${REDUCE_FIRST_LOCAL_X * REDUCE_FIRST_LOCAL_Y} / 2; offset > 0; offset /= 2) {
                if (lid < offset) sBuffer[lid] += sBuffer[lid + offset];
                barrier();
                memoryBarrierShared();
            }

            if (lid == 0) {
                int groupsX = int(gl_NumWorkGroups.x);
                int m = int(gl_WorkGroupID.y) * groupsX + int(gl_WorkGroupID.x);
                accu[m] = sBuffer[0];
            }
        }
    """.trimIndent()

    val REDUCE_SECOND = """
        #version 310 es
        precision highp float;
        precision highp int;
        layout(local_size_x = $REDUCE_SECOND_LOCAL_X, local_size_y = 1) in;
        layout(std430, binding = 0) readonly buffer InputBuffer { vec4 inputVals[]; };
        layout(std430, binding = 1) writeonly buffer ResultBuffer { vec4 resultVals[]; };
        shared vec4 sBuffer[$REDUCE_SECOND_LOCAL_X];
        uniform int uLength;

        void main() {
            int x = int(gl_GlobalInvocationID.x);
            vec4 sumY2 = vec4(0.0);
            int globalSize = int(gl_NumWorkGroups.x) * int(gl_WorkGroupSize.x);
            while (x < uLength) {
                sumY2 += inputVals[x];
                x += globalSize;
            }

            int lid = int(gl_LocalInvocationID.x);
            sBuffer[lid] = sumY2;
            barrier();
            memoryBarrierShared();

            for (int offset = $REDUCE_SECOND_LOCAL_X / 2; offset > 0; offset /= 2) {
                if (lid < offset) sBuffer[lid] += sBuffer[lid + offset];
                barrier();
                memoryBarrierShared();
            }

            if (lid == 0) {
                resultVals[int(gl_WorkGroupID.x)] = sBuffer[0];
            }
        }
    """.trimIndent()

    val BACKTRANSFORM_Y0U0V0 = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uInput;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutput;

        uniform ivec2 uImageSize;
        uniform vec4 uA;
        uniform vec4 uP;
        uniform vec4 uB;
        uniform float uBias;
        uniform vec4 uWb;
        uniform mat3 uToRgb;

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;

            vec4 t = readPixel(uInput, coord, uImageSize);
            float alpha = t.a;
            vec4 px = vec4(uToRgb * t.rgb, alpha);
            px = max(vec4(0.0), px);
            vec4 delta = px * px + vec4(uBias) * uWb;
            vec4 denominator = 4.0 / (sqrt(uA) * (2.0 - uP));
            vec4 z1 = (px + sqrt(max(vec4(0.0), delta))) / denominator;
            px = max(dtPow(z1, 1.0 / (1.0 - uP / 2.0)) - uB, vec4(0.0));
            px.a = alpha;
            imageStore(uOutput, coord, px);
        }
    """.trimIndent()

    val BACKTRANSFORM_V2 = """
        #version 310 es
        $COMMON
        layout(local_size_x = 16, local_size_y = 16) in;
        layout(binding = 0) uniform highp sampler2D uInput;
        layout(rgba16f, binding = 1) writeonly uniform highp image2D uOutput;

        uniform ivec2 uImageSize;
        uniform vec4 uA;
        uniform vec4 uP;
        uniform vec4 uB;
        uniform float uBias;
        uniform vec4 uWb;

        void main() {
            ivec2 coord = ivec2(gl_GlobalInvocationID.xy);
            if (coord.x >= uImageSize.x || coord.y >= uImageSize.y) return;

            vec4 px = max(vec4(0.0), readPixel(uInput, coord, uImageSize));
            float alpha = px.a;
            vec4 delta = px * px + vec4(uBias);
            vec4 denominator = 4.0 / (sqrt(uA) * (2.0 - uP));
            vec4 z1 = (px + sqrt(max(vec4(0.0), delta))) / denominator;
            px = max(dtPow(z1, 1.0 / (1.0 - uP / 2.0)) - uB, vec4(0.0));
            px *= uWb;
            px.a = alpha;
            imageStore(uOutput, coord, px);
        }
    """.trimIndent()
}
