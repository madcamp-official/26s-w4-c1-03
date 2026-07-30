package com.gamdo.app.ui.camera

/**
 * Who may see the debug HUD, and when (P1-C1).
 *
 * The camera screen carries a read-out of raw object counts, analysis fps, IoU,
 * matchScore and the latched template id. None of that is product UI — D2-5 keeps
 * `matchScore` out of the shipped screen entirely, and P1-C1 adds that the numbers
 * must not be the *default* state of a debug build either. The reported defect was
 * exactly that: after clearing app data the HUD was on screen before anyone asked
 * for it, so every screenshot and every demo of the camera screen showed it.
 *
 * Two independent conditions, kept apart on purpose:
 *
 * - **availability** is the build type. `BuildConfig.DEBUG` is a compile-time
 *   constant, so a `false` here makes the whole HUD branch dead code — this is what
 *   the `demo` build type (`isDebuggable = false`) relies on, and it is why the demo
 *   APK cannot be made to show the HUD by any runtime state at all.
 * - **visibility** is a deliberate act by whoever is holding the phone.
 *
 * Extracted from `CameraScreen` rather than left as two inline booleans because
 * this module has no `androidTest` source set and no Robolectric: a `@Composable`
 * cannot be executed on the JVM, so a decision written inside one cannot be tested.
 * Here it can, and `DebugHudGateTest` pins it.
 */
object DebugHudGate {

    /** Whether the HUD exists in this build at all. */
    fun availableIn(isDebugBuild: Boolean): Boolean = isDebugBuild

    /**
     * What the HUD toggle starts at — **hidden, in every build type**.
     *
     * Takes [isDebugBuild] and ignores it, and that is the point: the parameter
     * names the thing this answer is not allowed to depend on. It used to, as
     * `mutableStateOf(BuildConfig.DEBUG)`, which is the P1-C1 defect. A caller
     * reading this signature cannot re-derive the default from the build type
     * without deleting the argument first, which is a visible edit.
     */
    @Suppress("UNUSED_PARAMETER")
    fun initialVisible(isDebugBuild: Boolean): Boolean = false

    /**
     * Whether the HUD renders right now.
     *
     * Both terms are required. [toggledOn] survives process death via
     * `rememberSaveable`, so a value restored from a bundle written by a debug
     * build must not be able to surface the read-outs in a build that has no HUD.
     */
    fun visible(isDebugBuild: Boolean, toggledOn: Boolean): Boolean =
        availableIn(isDebugBuild) && toggledOn
}
