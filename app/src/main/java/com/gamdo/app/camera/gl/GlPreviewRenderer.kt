package com.gamdo.app.camera.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The EGL context and GLES 2.0 program that colour the preview.
 *
 * ## Everything here is thread-confined
 *
 * An EGL context belongs to the thread that made it current. Every method on this
 * class must be called on the `HandlerThread` [PreviewColorEffect] owns — which is
 * also the thread CameraX delivers `SurfaceProcessor` callbacks on and the thread
 * `SurfaceTexture` delivers frame callbacks on. There is no locking, because a lock
 * would imply the opposite.
 *
 * ## Why setup happens before CameraX is told anything
 *
 * [setUp] creates the display, context and program against a 1×1 pbuffer, with no
 * camera and no output surface involved. That ordering is the cheapest half of the
 * O-14 fallback: on the device that fails GPU inference with `GL_INVALID_VALUE` 3
 * times out of 3 (W3-4), a driver that is going to refuse us will almost certainly
 * refuse here — and refusing here costs nothing, because the effect has not been
 * attached, the preview is still bound straight to its surface, and the user sees a
 * normal camera with no colour.
 *
 * Failures are surfaced as [IllegalStateException] rather than silent no-ops, so
 * that they reach [PreviewEffectPolicy] and produce exactly one log line.
 */
internal class GlPreviewRenderer {

    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var program = 0
    private var cameraTextureId = 0
    private var lutTextureId = 0

    private var aPosition = 0
    private var aTexCoord = 0
    private var uTexMatrix = 0
    private var uCamera = 0
    private var uLut = 0
    private var uLutStep = 0
    private var uVibrance = 0
    private var uSaturation = 0
    private var uFade = 0
    private var uGrain = 0
    private var uVignette = 0
    private var uHasHsl = 0
    private var uCropPx = 0
    private var uCropHalf = 0

    private var uploadedSpec: PreviewFilterSpec? = null

    private val positions: FloatBuffer = floatBuffer(
        floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
    )
    private val texCoords: FloatBuffer = floatBuffer(
        floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
    )

    /**
     * Brings up EGL and the program. Throws [IllegalStateException] on any failure.
     *
     * @throws IllegalStateException if EGL or shader compilation is unavailable
     */
    fun setUp() {
        check(!FORCE_SETUP_FAILURE) { "preview colour: setup failure forced for fallback verification" }

        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "no EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }

        val configs = arrayOfNulls<EGLConfig>(1)
        val configCount = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        check(
            EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, configCount, 0) &&
                configCount[0] > 0 && configs[0] != null,
        ) { "no suitable EGL config" }
        config = configs[0]

        context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

        pbuffer = EGL14.eglCreatePbufferSurface(
            display,
            config,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
            0,
        )
        check(pbuffer != EGL14.EGL_NO_SURFACE) { "eglCreatePbufferSurface failed" }
        check(EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { "eglMakeCurrent failed" }

        program = buildProgram()
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
        uCamera = GLES20.glGetUniformLocation(program, "uCamera")
        uLut = GLES20.glGetUniformLocation(program, "uLut")
        uLutStep = GLES20.glGetUniformLocation(program, "uLutStep")
        uVibrance = GLES20.glGetUniformLocation(program, "uVibrance")
        uSaturation = GLES20.glGetUniformLocation(program, "uSaturation")
        uFade = GLES20.glGetUniformLocation(program, "uFade")
        uGrain = GLES20.glGetUniformLocation(program, "uGrain")
        uVignette = GLES20.glGetUniformLocation(program, "uVignette")
        uHasHsl = GLES20.glGetUniformLocation(program, "uHasHsl")
        uCropPx = GLES20.glGetUniformLocation(program, "uCropPx")
        uCropHalf = GLES20.glGetUniformLocation(program, "uCropHalf")
        check(aPosition >= 0 && aTexCoord >= 0) { "shader attributes not found" }

        cameraTextureId = createExternalTexture()
        lutTextureId = createLutTexture()
        checkGl("setUp")
    }

    /** The texture name the input `SurfaceTexture` must be attached to. */
    fun cameraTexture(): Int = cameraTextureId

    /** Wraps an output [surface] in an EGL window surface. */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val eglSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        return eglSurface
    }

    fun destroyWindowSurface(eglSurface: EGLSurface) {
        if (display != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, eglSurface)
        }
    }

    /**
     * Uploads [spec]'s table if it is not already resident.
     *
     * Guarded by equality rather than by a dirty flag so that re-selecting the
     * preset that is already showing costs nothing — [PreviewFilterSpec] compares by
     * preset id and slider positions, not by array identity.
     */
    fun useSpec(spec: PreviewFilterSpec) {
        if (uploadedSpec == spec) return
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        val buffer = ByteBuffer.allocateDirect(spec.lut.size).order(ByteOrder.nativeOrder())
        buffer.put(spec.lut).position(0)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            PreviewFilterLut.WIDTH, PreviewFilterLut.HEIGHT, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer,
        )
        checkGl("lut upload")
        uploadedSpec = spec
    }

    /**
     * Draws one frame of the camera texture into [target].
     *
     * @param textureTransform the matrix CameraX produced from
     *   `SurfaceOutput.updateTransformMatrix`, already composed with the
     *   `SurfaceTexture`'s own transform.
     * @param crop the visible window, for the positional stages only.
     */
    fun drawFrame(
        target: EGLSurface,
        width: Int,
        height: Int,
        textureTransform: FloatArray,
        spec: PreviewFilterSpec,
        crop: PreviewCrop,
    ) {
        check(EGL14.eglMakeCurrent(display, target, target, context)) { "eglMakeCurrent(target) failed" }
        useSpec(spec)

        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(uCamera, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTextureId)
        GLES20.glUniform1i(uLut, 1)

        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, textureTransform, 0)
        GLES20.glUniform2f(uLutStep, 1f / PreviewFilterLut.WIDTH, 1f / PreviewFilterLut.HEIGHT)
        GLES20.glUniform1f(uVibrance, spec.vibrance)
        GLES20.glUniform1f(uSaturation, spec.saturation)
        GLES20.glUniform1f(uFade, spec.fade)
        GLES20.glUniform1f(uGrain, spec.grain)
        GLES20.glUniform1f(uVignette, spec.vignette)
        GLES20.glUniform1f(uHasHsl, if (spec.hasHsl) 1f else 0f)
        GLES20.glUniform2f(uCropPx, crop.widthPx, crop.heightPx)
        GLES20.glUniform2f(uCropHalf, crop.halfU, crop.halfV)

        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glEnableVertexAttribArray(aTexCoord)
        GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aTexCoord)
        checkGl("draw")

        check(EGL14.eglSwapBuffers(display, target)) { "eglSwapBuffers failed" }
    }

    fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(
            display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
        )
        if (program != 0) GLES20.glDeleteProgram(program)
        if (cameraTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
        if (lutTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(lutTextureId), 0)
        if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        pbuffer = EGL14.EGL_NO_SURFACE
        program = 0
        cameraTextureId = 0
        lutTextureId = 0
        uploadedSpec = null
    }

    // --- internals -------------------------------------------------------------

    private fun buildProgram(): Int {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, PreviewFilterShaders.VERTEX)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, PreviewFilterShaders.FRAGMENT)
        val id = GLES20.glCreateProgram()
        check(id != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(id, vertex)
        GLES20.glAttachShader(id, fragment)
        GLES20.glLinkProgram(id)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(id, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(id)
            GLES20.glDeleteProgram(id)
            error("program link failed: $log")
        }
        // The shaders are attached to a linked program; deleting them here only
        // drops our reference.
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        return id
    }

    private fun compile(type: Int, source: String): Int {
        val id = GLES20.glCreateShader(type)
        check(id != 0) { "glCreateShader failed" }
        GLES20.glShaderSource(id, source)
        GLES20.glCompileShader(id)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetShaderInfoLog(id)
            GLES20.glDeleteShader(id)
            // The log is the only thing that can tell an unsupported extension from
            // a typo, and this pipeline's whole risk is the former.
            error("shader compile failed: $log")
        }
        return id
    }

    private fun createExternalTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "glGenTextures failed" }
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, ids[0])
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
        )
        return ids[0]
    }

    /**
     * NEAREST, and that is not an oversight.
     *
     * The shader interpolates the tone curve itself, because the stored value is a
     * 16-bit number split across two bytes: `GL_LINEAR` would blend the high byte
     * and the low byte independently, which produces a number that is not between
     * the two entries it is supposed to be between. It is wrong most visibly exactly
     * where the low byte wraps.
     */
    private fun createLutTexture(): Int {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        check(ids[0] != 0) { "glGenTextures failed" }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun checkGl(stage: String) {
        val error = GLES20.glGetError()
        check(error == GLES20.GL_NO_ERROR) { "$stage: GL error 0x${Integer.toHexString(error)}" }
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(values); position(0) }

    companion object {
        /**
         * Set to true to prove the fallback on a device: [setUp] then fails on its
         * first line, the effect is never attached, and the preview must come up
         * normally with no colour and one `W/PreviewColorEffect` line.
         *
         * A `var` rather than a `const` so that flipping it does not turn the
         * production path into dead code the compiler warns about — and so a debug
         * hook can flip it at runtime without a rebuild.
         */
        @JvmStatic
        var FORCE_SETUP_FAILURE: Boolean = false
    }
}
