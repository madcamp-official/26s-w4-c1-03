package com.gamdo.app.camera.gl

import android.graphics.SurfaceTexture
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PreviewColorEffect"

/**
 * O-13 (1) / O-14 — the selected preset's colour, on the live preview, from the
 * same recipe the editor renders the saved file with.
 *
 * ## Why a `CameraEffect` and not something simpler
 *
 * Every simpler path was tried and is closed:
 *
 *  - `View.setRenderEffect` **cannot reach a `SurfaceView`'s content**.
 *    `SurfaceView.draw()` punches a transparent hole in the view hierarchy, and the
 *    View's `RenderNode` — the only thing `setRenderEffect` writes to — therefore
 *    holds that hole, not camera pixels. A Compose blend overlay fails identically.
 *    `PreviewView` runs `PERFORMANCE` (a `SurfaceView`) and W3-2 established that
 *    moving it to `COMPATIBLE` is a measurement-only concession, not a default.
 *  - `RuntimeShader` (AGSL) is API 33+. The device is API 31.
 *  - A 4×5 colour matrix — the one operator every cheap path shares — was built,
 *    fitted by least squares, and **measured**: 154 levels out over the RGB cube
 *    and 90 on the grey ramp for `clean_social`, because that preset lifts a
 *    near-black pixel by ~90 levels and a straight line cannot do that and leave
 *    the top half alone. `PreviewFilterModelTest` keeps that comparison executable.
 *
 * So the colour is applied where the pixels actually are: in a GLES fragment shader
 * on the preview stream, with [FilterEngine][com.gamdo.app.edit.FilterEngine]'s own
 * tone curve uploaded as a lookup table rather than re-derived. See
 * [PreviewFilterLut].
 *
 * ## PREVIEW only, and what that leaves alone
 *
 * [CameraEffect.PREVIEW] is the sole target. This matters twice:
 *
 *  - **Analysis keeps its unmodified frames**, and not merely because we aimed
 *    elsewhere. `ImageAnalysis` does not override `UseCase.getSupportedEffectTargets()`,
 *    whose base returns `Collections.emptySet()`, and `CameraUseCaseAdapter` attaches
 *    an effect to a use case only where `isEffectTargetsSupported` accepts it. No
 *    `CameraEffect` of any targets value can reach the analysis stream. The guide
 *    would be measuring a filtered image if one could.
 *  - **The saved JPEG stays unfiltered.** `FilterEngine` runs on it afterwards, in
 *    the result screen. Colouring it here as well would apply the look twice.
 *
 * ## Failure must not take the preview down
 *
 * This device fails GPU inference with `GL_INVALID_VALUE … glMapBufferRange`, 3
 * times out of 3 (W3-4), and this adds a second EGL context to that same process on
 * that same driver. The design consequence is [PreviewEffectPolicy] and the
 * ordering around it:
 *
 *  1. [create] builds the whole GL pipeline — display, context, program, textures —
 *     against a 1×1 pbuffer **before** CameraX is told the effect exists. If any of
 *     it fails, `create` returns null, nothing is ever attached, and the preview
 *     binds directly to its surface exactly as it does today.
 *  2. After attaching, a `ProcessingException`, a throwing draw, or a first frame
 *     that never arrives detaches the effect. Detaching rebinds the camera without
 *     it, so the preview comes back within a rebind rather than staying black.
 *  3. Detach happens at most once and is never re-armed, so a failing driver cannot
 *     make the colour blink.
 *
 * A camera showing nothing is far worse than a camera showing uncoloured frames.
 */
class PreviewColorEffect private constructor(
    private val thread: HandlerThread,
    private val handler: Handler,
    executor: Executor,
    private val policy: PreviewEffectPolicy,
    processor: Processor,
    /** Invoked on the GL thread when the effect must be removed. The host clears it. */
    private val onDetach: (PreviewColorOffReason) -> Unit,
) : CameraEffect(
    TARGETS,
    executor,
    processor,
    { throwable ->
        // CameraX's own failure channel: SurfaceProcessorWithExecutor catches a
        // ProcessingException out of onInputSurface/onOutputSurface and hands it
        // here. Verified against camera-core 1.4.1's bytecode, not assumed.
        Log.w(TAG, "CameraX reported a processing failure", throwable)
    },
) {

    private val released = AtomicBoolean(false)

    /** The preset whose colour the shader is applying. Read on the GL thread. */
    var spec: PreviewFilterSpec
        get() = processor().spec
        set(value) { processor().spec = value }

    /** The 4:5 / 1:1 window, for the positional stages. See [PreviewCrop]. */
    var aspectRatioWtoH: Float
        get() = processor().aspectRatioWtoH
        set(value) { processor().aspectRatioWtoH = value }

    /** Null until the effect has been running; see [PreviewEffectPolicy]. */
    val offReason: PreviewColorOffReason? get() = policy.offReason

    private fun processor() = surfaceProcessor as Processor

    /**
     * Arms the first-frame deadline. Call immediately after `setEffects`.
     *
     * The watchdog is the only thing that can catch this device's known failure
     * shape: `glMapBufferRange` returns `GL_INVALID_VALUE` and the pipeline simply
     * goes quiet. Nothing throws, so nothing else would ever fire.
     */
    fun onAttached() {
        report(policy.onAttached(System.currentTimeMillis()))
        handler.postDelayed(
            { report(policy.onTick(System.currentTimeMillis())) },
            PreviewEffectPolicy.DEFAULT_FIRST_FRAME_DEADLINE_MS + WATCHDOG_SLACK_MS,
        )
    }

    /** Idempotent. Safe to call from any thread. */
    fun release() {
        if (!released.compareAndSet(false, true)) return
        policy.onReleased()
        handler.post { runCatching { processor().release() } }
        // quitSafely, not quit: the release above is already queued and it owns the
        // EGL teardown. Dropping it would leak the context and its thread.
        thread.quitSafely()
    }

    private fun report(decision: PreviewEffectDecision) {
        if (decision !is PreviewEffectDecision.Detach) return
        // The one log line. Deliberately at W: on a healthy device it never prints,
        // so its presence is the signal.
        Log.w(TAG, "preview colour off (${decision.reason}); preview continues uncoloured")
        onDetach(decision.reason)
    }

    /**
     * The GL half. Split out so the [CameraEffect] constructor — which must be
     * handed a processor before `this` exists — has something to take.
     */
    internal class Processor(
        private val renderer: GlPreviewRenderer,
        private val policy: PreviewEffectPolicy,
        /**
         * The GL thread's handler, and it is load-bearing.
         *
         * `SurfaceTexture.setOnFrameAvailableListener(listener)` — the one-argument
         * overload — resolves its Looper as "the calling thread's, or the **main**
         * thread's if it has none". A GL thread built from a bare `Executor` has
         * none, so every frame callback would have arrived on the main thread, where
         * this EGL context is not current and every `updateTexImage` and draw would
         * fail. The fallback would have caught it and switched the colour off
         * permanently on every device — a working safeguard hiding a total failure,
         * which is the worst of both.
         */
        private val handler: Handler,
    ) : SurfaceProcessor {

        @Volatile
        var spec: PreviewFilterSpec = PreviewFilterSpec.of(com.gamdo.app.edit.PhotoFilters.ORIGINAL)

        @Volatile
        var aspectRatioWtoH: Float = 0.8f

        private var surfaceTexture: SurfaceTexture? = null
        private var inputSurface: Surface? = null
        private var output: SurfaceOutput? = null
        private var outputEglSurface: EGLSurface? = null
        private var outputWidth = 0
        private var outputHeight = 0

        private val textureTransform = FloatArray(16)
        private val outputTransform = FloatArray(16)

        private var frames = 0L
        private var drawNanos = 0L

        override fun onInputSurface(request: SurfaceRequest) {
            val texture = SurfaceTexture(renderer.cameraTexture())
            texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(texture)
            texture.setOnFrameAvailableListener({ onFrame(it) }, handler)
            surfaceTexture = texture
            inputSurface = surface
            // The camera writes here until CameraX takes it back; releasing on the
            // result callback rather than on our own teardown is what keeps a
            // rebind from tearing down a surface the camera is still filling.
            request.provideSurface(surface, Runnable::run) {
                texture.setOnFrameAvailableListener(null)
                texture.release()
                surface.release()
                if (surfaceTexture === texture) surfaceTexture = null
                if (inputSurface === surface) inputSurface = null
            }
        }

        override fun onOutputSurface(surfaceOutput: SurfaceOutput) {
            // This method used to kill the app on every front/rear flip: 13 of 13 on
            // SM-G970N, and 0 of 3 when a sheet happened to be open — an open sheet
            // resizes the preview pane, and a different size makes CameraX hand back
            // a different native window, which sidestepped the collision. The cause
            // is the executor on the release callback below, not anything here.
            //
            // The two lines that follow are belt-and-braces. In the observed order
            // CameraX runs the previous output's release callback before this call
            // arrives, so they are no-ops; nothing in the contract promises that
            // order, and if it inverts the cost is the EGL_BAD_ALLOC below.
            val retired = output
            releaseOutput()
            retired?.close()
            output = surfaceOutput
            outputWidth = surfaceOutput.size.width
            outputHeight = surfaceOutput.size.height
            // `handler`, not `Runnable::run`, and this is the whole bug. Run inline,
            // this callback executes on CameraX's thread, where `eglMakeCurrent`
            // cannot unbind a context that is current on the GL thread — so the
            // destroy only *flags* the surface, the native window stays connected,
            // and the next `eglCreateWindowSurface` for it fails with EGL_BAD_ALLOC.
            // Every EGL call has to land on the thread that owns the context.
            val surface = surfaceOutput.getSurface({ handler.post(it) }) { _ ->
                // Identity-guarded for the same reason `onInputSurface` guards its
                // texture: this fires for whichever output CameraX has finished with,
                // which after a rebind is no longer the one held here. Unguarded, the
                // retired output's callback destroys the *live* EGL surface and the
                // preview stays black until something rebinds it again.
                if (output === surfaceOutput) releaseOutput()
                surfaceOutput.close()
            }
            // A failure here now detaches instead of crashing. `create`'s KDoc calls an
            // uncoloured preview the good failure and `onFrame` already treats a GL
            // throw that way; this path was the one that still reached CameraX's
            // handler as a fatal exception.
            outputEglSurface = runCatching { renderer.createWindowSurface(surface) }
                .onFailure {
                    Log.w(TAG, "preview colour output surface unavailable", it)
                    policy.onDrawFailed()
                }
                .getOrNull()
        }

        private fun onFrame(texture: SurfaceTexture) {
            val target = outputEglSurface ?: return
            val sink = output ?: return
            try {
                texture.updateTexImage()
                texture.getTransformMatrix(textureTransform)
                // CameraX composes its own rotation and mirroring onto ours. Doing
                // this by hand is how a front-camera preview ends up mirrored
                // differently from the capture.
                sink.updateTransformMatrix(outputTransform, textureTransform)
                // Recomputed per frame rather than cached: the 4:5 / 1:1 toggle
                // changes it, and it is three multiplies against a draw.
                val currentCrop = PreviewCrop.fit(outputWidth, outputHeight, aspectRatioWtoH)
                val startedAt = System.nanoTime()
                renderer.drawFrame(
                    target = target,
                    width = outputWidth,
                    height = outputHeight,
                    textureTransform = outputTransform,
                    spec = spec,
                    crop = currentCrop,
                )
                recordCost(System.nanoTime() - startedAt)
                policy.onFrameDrawn()
            } catch (t: Throwable) {
                // Any GL failure lands here. The policy decides once; the host
                // detaches; the preview rebinds without colour.
                Log.w(TAG, "preview colour draw failed", t)
                policy.onDrawFailed()
            }
        }

        /**
         * §7-1's measurement hook. The per-frame GPU cost cannot be read directly
         * without `EXT_disjoint_timer_query`, which mobile drivers rarely expose, so
         * what is timed is the CPU-side submit plus `eglSwapBuffers` — which blocks
         * on the previous frame once the GPU is the bottleneck, and is therefore a
         * usable proxy for exactly the case that matters.
         */
        private fun recordCost(nanos: Long) {
            frames++
            drawNanos += nanos
            if (frames % COST_LOG_EVERY_FRAMES != 0L) return
            val meanMs = drawNanos / COST_LOG_EVERY_FRAMES / 1_000_000.0
            Log.d(TAG, "preview colour: %.2f ms/frame over %d frames".format(meanMs, COST_LOG_EVERY_FRAMES))
            drawNanos = 0
        }

        fun release() {
            releaseOutput()
            surfaceTexture?.setOnFrameAvailableListener(null)
            renderer.release()
        }

        private fun releaseOutput() {
            outputEglSurface?.let { renderer.destroyWindowSurface(it) }
            outputEglSurface = null
            output = null
        }
    }

    companion object {
        /**
         * `PREVIEW`, and nothing else. Named so a one-character change cannot
         * quietly become a defect — `PreviewColorTargetsTest` pins it.
         *
         * Adding `IMAGE_CAPTURE` here would colour the saved JPEG a second time, on
         * top of the pass `FilterEngine` already runs in the result screen. Adding
         * `VIDEO_CAPTURE` would attach a processor to a use case this app does not
         * bind.
         *
         * `IMAGE_ANALYSIS` is not reachable at all, and that is a property of
         * CameraX rather than of our restraint: `CameraEffect` defines only
         * `PREVIEW`, `VIDEO_CAPTURE` and `IMAGE_CAPTURE`, and
         * `CameraUseCaseAdapter` routes an effect to a use case only when
         * `UseCase.isEffectTargetsSupported` says yes — which reads
         * `getSupportedEffectTargets()`, a method `ImageAnalysis` does not override
         * and whose base returns `Collections.emptySet()`. The guide therefore
         * cannot be handed a filtered frame even by a mistake here. (Verified
         * against camera-core 1.4.1's bytecode.)
         */
        const val TARGETS = PREVIEW

        /** Frames between cost lines. 120 is ~5s at the preview's measured 24fps. */
        private const val COST_LOG_EVERY_FRAMES = 120L

        /** Margin so the watchdog fires *after* the deadline it is checking. */
        private const val WATCHDOG_SLACK_MS = 250L

        /**
         * Builds the effect, or returns **null** if this device's GL cannot run it.
         *
         * Null is the good failure: nothing has been attached, so the caller simply
         * does not call `setEffects` and the preview behaves exactly as it did
         * before O-14 — running, uncoloured, with one line in the log.
         *
         * @param onDetach invoked on the GL thread if the effect has to be removed
         *   *after* attaching. The caller must hop to the main thread and call
         *   `CameraController.clearEffects()`.
         */
        fun create(onDetach: (PreviewColorOffReason) -> Unit): PreviewColorEffect? {
            // A HandlerThread rather than a plain executor, so the thread has a
            // Looper for SurfaceTexture to deliver frame callbacks on. See
            // Processor's `handler` parameter for what happens without one.
            val thread = HandlerThread("gamdo-preview-gl").apply { start() }
            val handler = Handler(thread.looper)
            val executor = Executor { handler.post(it) }
            val renderer = GlPreviewRenderer()
            val policy = PreviewEffectPolicy()

            // Setup runs to completion before this returns, because the caller's
            // next move depends on the answer: a null here means the effect is
            // never attached and the preview binds normally. Blocking is fine —
            // `create` is already called off the main thread.
            var failure: Throwable? = null
            val done = CountDownLatch(1)
            handler.post {
                try {
                    renderer.setUp()
                } catch (t: Throwable) {
                    failure = t
                } finally {
                    done.countDown()
                }
            }
            done.await()

            failure?.let {
                Log.w(
                    TAG,
                    "preview colour unavailable (${PreviewColorOffReason.SETUP_FAILED}); " +
                        "preview runs uncoloured",
                    it,
                )
                policy.onSetupFailed()
                handler.post { runCatching { renderer.release() } }
                thread.quitSafely()
                return null
            }
            return PreviewColorEffect(
                thread = thread,
                handler = handler,
                executor = executor,
                policy = policy,
                processor = Processor(renderer, policy, handler),
                onDetach = onDetach,
            )
        }
    }
}
