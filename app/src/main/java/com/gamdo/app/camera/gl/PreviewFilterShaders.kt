package com.gamdo.app.camera.gl

/**
 * The GLSL half of O-14, kept as strings so the numbers in it can come from
 * [PreviewFilterLut] instead of being typed twice.
 *
 * ## Why GLSL ES 1.00 and not 3.00
 *
 * ES 3.00 would be nicer to write — `texelFetch` removes the coordinate arithmetic
 * and integer operators would let the grain hash mirror [com.gamdo.app.edit.FilterEngine]'s
 * exactly. It is declined anyway. `samplerExternalOES` in ESSL 3 needs
 * `GL_OES_EGL_image_external_essl3`, which is a *second* extension to depend on, and
 * this is the device that fails GPU inference with `GL_INVALID_VALUE` 3 times out of
 * 3 (W3-4). ES 2.0 with `GL_OES_EGL_image_external` is the path every camera app on
 * Android has taken for a decade and the one CameraX's own default processor uses,
 * so it is the least novel thing we can ask this driver to do.
 *
 * The cost is the grain hash — see [grainAt].
 *
 * ## highp
 *
 * `highp` in a fragment shader is optional in ES 2.0, so it is guarded. The LUT
 * decode reconstructs a 16-bit value from two bytes, and `mediump` (10-bit mantissa)
 * cannot hold the result — it would throw away exactly the precision the two-byte
 * encoding exists to buy. Every ES 3.0-capable device supports `highp` in fragments,
 * which is every device this app runs on; the `#else` exists so a compile failure
 * lands in [PreviewEffectPolicy] rather than as undefined behaviour.
 */
internal object PreviewFilterShaders {

    /**
     * `aPosition` is a full-screen triangle strip in clip space; `vPos` carries the
     * same point in `[0, 1]` so the fragment stage can find itself inside the crop.
     *
     * `uTexMatrix` is the matrix CameraX hands back from
     * `SurfaceOutput.updateTransformMatrix` — it composes the SurfaceTexture's own
     * transform with the rotation and mirroring the output expects. Applying it here
     * rather than rotating vertices is what keeps front-camera mirroring and sensor
     * orientation consistent with what CameraX does for an un-effected preview.
     */
    val VERTEX = """
        uniform mat4 uTexMatrix;
        attribute vec4 aPosition;
        attribute vec4 aTexCoord;
        varying vec2 vTex;
        varying vec2 vPos;
        void main() {
            gl_Position = aPosition;
            vTex = (uTexMatrix * aTexCoord).xy;
            vPos = aPosition.xy * 0.5 + 0.5;
        }
    """.trimIndent()

    val FRAGMENT = buildFragment()

    private fun buildFragment(): String {
        val lut = PreviewFilterLut
        // Every literal below is read from the Kotlin constants the LUT was built
        // with. A scale that disagreed between builder and shader would be a
        // uniform colour cast that no JVM test could see.
        val channelScale = lut.CHANNEL_SCALE.glsl()
        val hueScale = lut.HUE_SCALE.glsl()
        val shiftSpan = lut.SHIFT_SPAN.glsl()
        val shiftBias = lut.SHIFT_BIAS.glsl()
        val lastTone = (lut.TONE_SAMPLES - 1).toFloat().glsl()
        val curveScale = ((lut.TONE_SAMPLES - 1) / lut.TONE_DOMAIN).glsl()
        return """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif

            uniform samplerExternalOES uCamera;
            uniform sampler2D uLut;
            uniform vec2 uLutStep;
            uniform float uVibrance;
            uniform float uSaturation;
            uniform float uFade;
            uniform float uGrain;
            uniform float uVignette;
            uniform float uHasHsl;
            uniform vec2 uCropPx;
            uniform vec2 uCropHalf;

            varying vec2 vTex;
            varying vec2 vPos;

            const vec3 kLuma = vec3(${lut.WEIGHT_R.glsl()}, ${lut.WEIGHT_G.glsl()}, ${lut.WEIGHT_B.glsl()});

            // 16-bit fixed point out of the R and G bytes. NEAREST sampling, and the
            // +0.5 lands on the texel centre so a driver's rounding cannot pick the
            // neighbour.
            float lut16(float x, float row) {
                vec4 t = texture2D(uLut, vec2((x + 0.5) * uLutStep.x, (row + 0.5) * uLutStep.y));
                return (t.r * 255.0 * 256.0 + t.g * 255.0) / 65535.0;
            }

            float luma(float r, float g, float b) {
                return kLuma.x * r + kLuma.y * g + kLuma.z * b;
            }

            // Multiply/add/fract only — no sin, whose result is implementation
            // defined in GLSL. See PreviewFilterModel.grainAt.
            float grain(vec2 p) {
                float px = fract(p.x * 0.1031);
                float py = fract(p.y * 0.1030);
                float pz = fract(p.x * 0.0973);
                float d = px * (py + 33.33) + py * (pz + 33.33) + pz * (px + 33.33);
                px += d; py += d; pz += d;
                return fract((px + py) * pz) - 0.5;
            }

            void main() {
                // Quantise to 8 bits before the channel tables. The engine indexes
                // them with a byte; the sampler hands us a float. Without this the
                // preview would interpolate a table the file steps through.
                vec3 idx = floor(clamp(texture2D(uCamera, vTex).rgb, 0.0, 1.0) * 255.0 + 0.5);
                float r = lut16(idx.r, ${lut.ROW_R.glsl()}) * $channelScale;
                float g = lut16(idx.g, ${lut.ROW_G.glsl()}) * $channelScale;
                float b = lut16(idx.b, ${lut.ROW_B.glsl()}) * $channelScale;

                float l = luma(r, g, b);
                if (l > 1e-4) {
                    float pos = clamp(l * $curveScale, 0.0, $lastTone);
                    float lo = floor(pos);
                    float hi = min(lo + 1.0, $lastTone);
                    float frac = pos - lo;
                    float cLo = lut16(lo, ${lut.ROW_TONE.glsl()});
                    float cHi = lut16(hi, ${lut.ROW_TONE.glsl()});
                    float scale = (cLo + (cHi - cLo) * frac) / l;
                    r *= scale; g *= scale; b *= scale;
                }

                if (uVibrance != 0.0 || uSaturation != 0.0) {
                    float mx = max(r, max(g, b));
                    float mn = min(r, min(g, b));
                    float current = mx > 1e-4 ? (mx - mn) / mx : 0.0;
                    float f = 1.0 + uSaturation + uVibrance * (1.0 - current);
                    float lum = luma(r, g, b);
                    r = lum + (r - lum) * f;
                    g = lum + (g - lum) * f;
                    b = lum + (b - lum) * f;
                }

                if (uHasHsl > 0.5) {
                    float mx = max(r, max(g, b));
                    float mn = min(r, min(g, b));
                    float delta = mx - mn;
                    if (delta > 1e-4) {
                        float deg;
                        if (mx == r) { deg = 60.0 * ((g - b) / delta); }
                        else if (mx == g) { deg = 60.0 * ((b - r) / delta + 2.0); }
                        else { deg = 60.0 * ((r - g) / delta + 4.0); }
                        if (deg < 0.0) { deg += 360.0; }
                        float bin = clamp(floor(deg), 0.0, 359.0);
                        float sMul = lut16(bin, ${lut.ROW_HUE_SAT.glsl()}) * $hueScale;
                        float lMul = lut16(bin, ${lut.ROW_HUE_LUM.glsl()}) * $hueScale;
                        float rot = lut16(bin, ${lut.ROW_HUE_SHIFT.glsl()}) * $shiftSpan - $shiftBias;
                        float lum = luma(r, g, b);
                        r = (lum + (r - lum) * sMul) * lMul;
                        g = (lum + (g - lum) * sMul) * lMul;
                        b = (lum + (b - lum) * sMul) * lMul;
                        float rad = rot * 0.017453292;
                        float c = cos(rad);
                        float s = sin(rad);
                        float nr = r * (kLuma.x + c * (1.0 - kLuma.x) - s * kLuma.x)
                                 + g * (kLuma.y - c * kLuma.y - s * kLuma.y)
                                 + b * (kLuma.z - c * kLuma.z + s * (1.0 - kLuma.z));
                        float ng = r * (kLuma.x - c * kLuma.x + s * 0.143)
                                 + g * (kLuma.y + c * (1.0 - kLuma.y) + s * 0.140)
                                 + b * (kLuma.z - c * kLuma.z - s * 0.283);
                        float nb = r * (kLuma.x - c * kLuma.x - s * (1.0 - kLuma.x))
                                 + g * (kLuma.y - c * kLuma.y + s * kLuma.y)
                                 + b * (kLuma.z + c * (1.0 - kLuma.z) + s * kLuma.z);
                        r = nr; g = ng; b = nb;
                    }
                }

                if (uFade != 0.0) {
                    r = uFade + r * (1.0 - uFade);
                    g = uFade + g * (1.0 - uFade);
                    b = uFade + b * (1.0 - uFade);
                }
                if (uGrain != 0.0) {
                    // Crop-space pixels, so the grain is the size the file's would
                    // be relative to the frame rather than the size of a surface
                    // pixel.
                    vec2 q = (vPos - 0.5) / uCropHalf;
                    float n = grain(floor((q * 0.5 + 0.5) * uCropPx));
                    r += n * uGrain; g += n * uGrain; b += n * uGrain;
                }
                if (uVignette != 0.0) {
                    // Measured from the visible crop, not the 4:3 surface — see
                    // PreviewCrop for why those are different and why it matters.
                    vec2 q = (vPos - 0.5) / uCropHalf;
                    vec2 offset = q * uCropPx * 0.5;
                    float d = length(offset) / max(0.5 * length(uCropPx), 1.0);
                    float v = 1.0 + uVignette * smoothstep(0.45, 1.0, d);
                    r *= v; g *= v; b *= v;
                }

                gl_FragColor = vec4(r, g, b, 1.0);
            }
        """.trimIndent()
    }

    /** GLSL needs a decimal point on every float literal. */
    private fun Float.glsl(): String = if (this == toLong().toFloat()) "$this" else toString()

    private fun Int.glsl(): String = "$this.0"
}
