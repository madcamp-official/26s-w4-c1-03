package com.gamdo.app.ui.rescue

import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.network.RescueCapabilities
import com.gamdo.app.data.network.RescueRecommendation
import com.gamdo.app.data.rescue.RescueState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 3 **직접 수정** — the five conditions `docs/P2_P1_필수기능연결_요구사항_2026-07-30.md`
 * §4 makes mandatory, each pinned to a function in `DirectEditDecisions.kt`.
 *
 * Same constraint as [RescueFlowDecisionsTest]: no `androidTest`, no Robolectric, no
 * Compose test artifact, so the decisions are Android-free and the pane that draws
 * them is DONE-DEVICE. Everything §4 can be wrong about is here.
 *
 * The other authority these tests answer to is `gamdo-server`'s
 * `app/routes/edit_jobs.py` `parse_operations()`. Field names, value domains and the
 * mask geometry rules are asserted against that validator, because a request it
 * rejects costs the user an upload and lands on [LOCAL_FALLBACK_MESSAGE].
 */
class DirectEditDecisionsTest {

    // ---- fixtures ----------------------------------------------------------

    private val everything = RescueCapabilities(
        localStyle = true,
        removeObjects = true,
        outpaint = true,
        viewpoint = true,
        relight = true,
    )

    private val nothing = RescueCapabilities(
        localStyle = true,
        removeObjects = false,
        outpaint = false,
        viewpoint = false,
        relight = false,
    )

    /** An `analysis` object in the shape `reference_analysis.py` actually emits. */
    private fun analysisOf(vararg subjects: Triple<String, List<Double>, String>): JsonObject =
        buildJsonObject {
            putJsonArray("subjects") {
                subjects.forEach { (role, bbox, label) ->
                    addJsonObject {
                        put("role", role)
                        put("sourceLabel", label)
                        putJsonArray("bbox") { bbox.forEach { add(it) } }
                        put("confidence", 0.9)
                    }
                }
            }
        }

    private fun objectAt(x: Double, y: Double, w: Double, h: Double, label: String = "chair") =
        Triple("object", listOf(x, y, w, h), label)

    private fun personAt(x: Double, y: Double, w: Double, h: Double) =
        Triple("person", listOf(x, y, w, h), "person")

    private fun responseWith(
        capabilities: RescueCapabilities,
        analysis: JsonObject = JsonObject(emptyMap()),
    ) = RescueAnalysisResponse(analysis = analysis, capabilities = capabilities)

    private val oneObject = analysisOf(objectAt(0.05, 0.7, 0.12, 0.15))

    private val completeOutpaint = DirectEditDraft(
        operation = RescueOperation.OUTPAINT,
        outpaintDirection = OutpaintDirection.LEFT,
        outpaintAmount = OutpaintAmount.SMALL,
    )
    private val completeViewpoint = DirectEditDraft(
        operation = RescueOperation.VIEWPOINT,
        viewpointMotion = ViewpointMotion.RIGHT,
        viewpointStrength = ViewpointStrength.SUBTLE,
    )
    private val completeRelight = DirectEditDraft(
        operation = RescueOperation.RELIGHT,
        relightDirection = RelightDirection.FRONT,
        relightStrength = RelightStrength.MEDIUM,
    )

    // ---- §4 condition 1: a false capability cannot be run ------------------

    @Test
    fun `no capability means no direct operation and no entry point`() {
        val response = responseWith(nothing, oneObject)
        assertEquals(emptyList<RescueOperation>(), directEditOperations(response))
        assertFalse(
            "the 직접 수정 door must not be drawn when nothing behind it can run",
            offersDirectEdit(response),
        )
    }

    @Test
    fun `each capability opens exactly its own operation and no other`() {
        val cases = mapOf(
            RescueOperation.REMOVE_OBJECTS to nothing.copy(removeObjects = true),
            RescueOperation.OUTPAINT to nothing.copy(outpaint = true),
            RescueOperation.VIEWPOINT to nothing.copy(viewpoint = true),
            RescueOperation.RELIGHT to nothing.copy(relight = true),
        )
        for ((operation, capabilities) in cases) {
            assertEquals(
                "capabilities=$capabilities must offer only $operation",
                listOf(operation),
                directEditOperations(responseWith(capabilities, oneObject)),
            )
        }
    }

    @Test
    fun `every capability on offers all four, in the order the contract lists them`() {
        assertEquals(
            listOf(
                RescueOperation.REMOVE_OBJECTS,
                RescueOperation.OUTPAINT,
                RescueOperation.VIEWPOINT,
                RescueOperation.RELIGHT,
            ),
            directEditOperations(responseWith(everything, oneObject)),
        )
    }

    @Test
    fun `local_style is never a direct operation — it has nothing to confirm and never uploads`() {
        assertFalse(RescueOperation.LOCAL_STYLE in DIRECT_EDIT_OPERATIONS)
        assertFalse(RescueOperation.LOCAL_STYLE in directEditOperations(responseWith(everything, oneObject)))
        assertNull(
            buildDirectOperation(DirectEditDraft(operation = RescueOperation.LOCAL_STYLE), emptyList()),
        )
    }

    @Test
    fun `removing objects is not offered when the photo has nothing removable`() {
        // capability on, analysis empty: the control would have nothing to select, so
        // its only reachable outcome is a disabled run button.
        assertEquals(
            emptyList<RescueOperation>(),
            directEditOperations(responseWith(nothing.copy(removeObjects = true))),
        )
        assertEquals(
            listOf(RescueOperation.REMOVE_OBJECTS),
            directEditOperations(responseWith(nothing.copy(removeObjects = true), oneObject)),
        )
    }

    @Test
    fun `a complete draft for a disabled operation still cannot be turned into a request`() {
        // The second lock: even if a chip list were re-derived wrongly, the request
        // path itself refuses. This is the assertion that makes condition 1 structural.
        val candidates = maskCandidates(oneObject)
        val drafts = listOf(
            completeOutpaint,
            completeViewpoint,
            completeRelight,
            DirectEditDraft(
                operation = RescueOperation.REMOVE_OBJECTS,
                maskIds = candidates.map { it.id }.toSet(),
            ),
        )
        for (draft in drafts) {
            assertNull(
                "draft=${draft.operation} must not build a request while its capability is off",
                directRunOperation(draft, candidates, nothing),
            )
            assertNotNull(
                "draft=${draft.operation} must build a request once its capability is on",
                directRunOperation(draft, candidates, everything),
            )
        }
    }

    // ---- §4 condition 2: no job before the user confirms -------------------

    @Test
    fun `an empty draft is not runnable`() {
        assertNull(buildDirectOperation(DirectEditDraft(), maskCandidates(oneObject)))
        assertNull(directRunOperation(DirectEditDraft(), maskCandidates(oneObject), everything))
    }

    @Test
    fun `every operation needs both of its parameters, not one`() {
        val partials = listOf(
            DirectEditDraft(operation = RescueOperation.OUTPAINT, outpaintDirection = OutpaintDirection.TOP),
            DirectEditDraft(operation = RescueOperation.OUTPAINT, outpaintAmount = OutpaintAmount.LARGE),
            DirectEditDraft(operation = RescueOperation.VIEWPOINT, viewpointMotion = ViewpointMotion.UP),
            DirectEditDraft(operation = RescueOperation.VIEWPOINT, viewpointStrength = ViewpointStrength.STANDARD),
            DirectEditDraft(operation = RescueOperation.RELIGHT, relightDirection = RelightDirection.LEFT),
            DirectEditDraft(operation = RescueOperation.RELIGHT, relightStrength = RelightStrength.STRONG),
            DirectEditDraft(operation = RescueOperation.REMOVE_OBJECTS),
        )
        for (draft in partials) {
            assertNull(
                "half-finished draft must not be runnable: $draft",
                buildDirectOperation(draft, maskCandidates(oneObject)),
            )
        }
    }

    @Test
    fun `mask ids that do not resolve against the current analysis build nothing`() {
        // A draft carries ids, not geometry. If the response behind them is gone the
        // request would be a rectangle measured against a different photo.
        val draft = DirectEditDraft(
            operation = RescueOperation.REMOVE_OBJECTS,
            maskIds = setOf("m0", "m7"),
        )
        assertNull(buildDirectOperation(draft, emptyList()))
    }

    @Test
    fun `nothing may be submitted from the recommendation state — choose comes first`() {
        // The draft being complete is not permission to submit; the controller has to
        // be holding the operation. That is the same gate the recommendation cards use.
        assertFalse(allowsRun(RescueState.Recommendations(responseWith(everything, oneObject)), alreadyLaunched = false))
        assertFalse(allowsRun(RescueState.Idle, alreadyLaunched = false))
        val confirmed = requireNotNull(buildDirectOperation(completeOutpaint, emptyList()))
        assertTrue(allowsRun(RescueState.Editing(confirmed), alreadyLaunched = false))
    }

    // ---- §4 condition 3: exactly one job per run action --------------------

    @Test
    fun `the second tap of one run action is refused`() {
        val operation = requireNotNull(buildDirectOperation(completeRelight, emptyList()))
        val state = RescueState.Editing(operation)
        assertTrue("the first tap must fire", allowsRun(state, alreadyLaunched = false))
        assertFalse("the second tap must not", allowsRun(state, alreadyLaunched = true))
    }

    @Test
    fun `no state other than Editing can start a job, latched or not`() {
        val operation = requireNotNull(buildDirectOperation(completeViewpoint, emptyList()))
        val states = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Recommendations(responseWith(everything, oneObject)),
            RescueState.Submitting,
            RescueState.Polling,
            RescueState.Candidates("job_1", emptyList()),
            RescueState.LocalFallback("generation_unavailable"),
        )
        for (state in states) {
            assertFalse("state=$state must not start a job", allowsRun(state, alreadyLaunched = false))
            assertFalse("state=$state must not start a job", allowsRun(state, alreadyLaunched = true))
        }
        assertTrue(allowsRun(RescueState.Editing(operation), alreadyLaunched = false))
    }

    @Test
    fun `an operation that never uploads cannot start a job even from Editing`() {
        // `local_style` resolves on device. Submitting it would be an upload for work
        // that never needed to leave the phone, and a 422 on arrival.
        val local = buildJsonObject { put("type", "local_style") }
        assertFalse(allowsRun(RescueState.Editing(local), alreadyLaunched = false))
    }

    // ---- §4 condition 4: a running job survives leaving the sheet ----------

    @Test
    fun `dismissing while a job runs hides it, and every other dismissal abandons it`() {
        assertEquals(RescueDismiss.CLOSE_ONLY, dismissActionFor(RescueState.Submitting))
        assertEquals(RescueDismiss.CLOSE_ONLY, dismissActionFor(RescueState.Polling))
        // Already the shipped behaviour: on Candidates the pick is the success.
        assertEquals(RescueDismiss.CLOSE_ONLY, dismissActionFor(RescueState.Candidates("job_1", emptyList())))

        val abandoned = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Recommendations(responseWith(everything, oneObject)),
            RescueState.Editing(buildJsonObject { put("type", "outpaint") }),
            RescueState.LocalFallback("generation_unavailable"),
        )
        for (state in abandoned) {
            assertEquals(
                "state=$state has nothing to come back to, so dismissing must reset it",
                RescueDismiss.CANCEL_AND_RESET,
                dismissActionFor(state),
            )
        }
    }

    @Test
    fun `the draft survives the picking pair and nothing else`() {
        val previous = completeOutpaint
        assertEquals(previous, retainedDirectDraft(RescueState.Recommendations(responseWith(everything, oneObject)), previous))
        assertEquals(previous, retainedDirectDraft(RescueState.Editing(buildJsonObject { put("type", "outpaint") }), previous))
        val dropped = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Submitting,
            RescueState.Polling,
            RescueState.Candidates("job_1", emptyList()),
            RescueState.LocalFallback("generation_unavailable"),
        )
        for (state in dropped) {
            assertEquals(
                "state=$state must not leave a stale draft behind",
                DirectEditDraft(),
                retainedDirectDraft(state, previous),
            )
        }
    }

    @Test
    fun `the pane closes itself on every state outside the picking pair`() {
        assertTrue(retainedDirectPane(RescueState.Recommendations(responseWith(everything, oneObject)), previous = true))
        assertTrue(retainedDirectPane(RescueState.Editing(buildJsonObject { put("type", "relight") }), previous = true))
        // And it never opens itself.
        assertFalse(retainedDirectPane(RescueState.Recommendations(responseWith(everything, oneObject)), previous = false))
        val closed = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Submitting,
            RescueState.Polling,
            RescueState.Candidates("job_1", emptyList()),
            RescueState.LocalFallback("generation_unavailable"),
        )
        for (state in closed) {
            assertFalse("state=$state must return the sheet to its own section", retainedDirectPane(state, previous = true))
        }
    }

    // ---- §4 condition 5: a failure keeps the original and the local result --

    @Test
    fun `a fallback leaves no direct-edit remnant on screen`() {
        // Condition 5 is "후보가 없거나 실패하면 원본과 로컬 감도 결과를 유지한다". The
        // sheet keeps the local correction by rendering FALLBACK; this is the other
        // half — the pane and its draft must not survive to cover it.
        val fallback = RescueState.LocalFallback("generation_unavailable")
        assertFalse(retainedDirectPane(fallback, previous = true))
        assertEquals(DirectEditDraft(), retainedDirectDraft(fallback, completeRelight))
        assertEquals(RescueSection.FALLBACK, rescueSectionFor(fallback, opened = true))
        assertEquals(LOCAL_FALLBACK_MESSAGE, fallbackMessage("generation_unavailable"))
    }

    // ---- which section owns the confirm block -------------------------------

    @Test
    fun `the recommendation section confirms only what it offered`() {
        val card = RescueRecommendation(
            id = "outpaint",
            kind = "outpaint",
            title = "여백 늘리기",
            reason = "이유",
            operation = buildJsonObject {
                put("type", "outpaint")
                put("direction", "bottom")
                put("ratio", 0.10)
            },
            confidence = 0.8,
        )
        val response = RescueAnalysisResponse(
            recommendations = listOf(card),
            capabilities = everything,
            analysis = oneObject,
        )
        // A card the section drew: unchanged behaviour, the confirm block appears.
        assertTrue(offersConfirmFor(response, card.operation))
        // The same operation *type* with different parameters is a direct edit, and the
        // cards must not claim it — a run button under a list with nothing selected.
        val direct = requireNotNull(buildDirectOperation(completeOutpaint, emptyList()))
        assertFalse(offersConfirmFor(response, direct))
        assertFalse(offersConfirmFor(response, null))
        assertFalse(offersConfirmFor(null, card.operation))
    }

    @Test
    fun `a card whose capability is off cannot own the confirm block either`() {
        val card = RescueRecommendation(
            id = "relight",
            kind = "relight",
            title = "빛 균형 맞추기",
            reason = "이유",
            operation = buildJsonObject {
                put("type", "relight")
                put("direction", "front")
                put("strength", 0.65)
            },
            confidence = 0.8,
        )
        val off = RescueAnalysisResponse(recommendations = listOf(card), capabilities = nothing)
        assertFalse(offersConfirmFor(off, card.operation))
        val on = RescueAnalysisResponse(
            recommendations = listOf(card),
            capabilities = nothing.copy(relight = true),
        )
        assertTrue(offersConfirmFor(on, card.operation))
    }

    // ---- the wire, checked against edit_jobs.py -----------------------------

    @Test
    fun `outpaint goes out as direction plus one of the three ratios the server takes`() {
        assertEquals(
            """{"type":"outpaint","direction":"left","ratio":0.05}""",
            requireNotNull(buildDirectOperation(completeOutpaint, emptyList())).toString(),
        )
        assertEquals(
            """{"type":"outpaint","direction":"all","ratio":0.15}""",
            requireNotNull(
                buildDirectOperation(
                    completeOutpaint.copy(
                        outpaintDirection = OutpaintDirection.ALL,
                        outpaintAmount = OutpaintAmount.LARGE,
                    ),
                    emptyList(),
                ),
            ).toString(),
        )
    }

    @Test
    fun `viewpoint uses motion and a string strength, not direction and a number`() {
        // The two viewpoint fields are the easiest pair in this contract to get wrong:
        // relight next door takes `direction` and a *numeric* strength.
        assertEquals(
            """{"type":"viewpoint","motion":"right","strength":"subtle"}""",
            requireNotNull(buildDirectOperation(completeViewpoint, emptyList())).toString(),
        )
    }

    @Test
    fun `relight uses direction and a numeric strength inside the server's band`() {
        assertEquals(
            """{"type":"relight","direction":"front","strength":0.65}""",
            requireNotNull(buildDirectOperation(completeRelight, emptyList())).toString(),
        )
        for (strength in RelightStrength.entries) {
            assertTrue(
                "relight strength must stay inside 0.1..1.0: $strength",
                strength.wire in 0.1..1.0,
            )
        }
    }

    @Test
    fun `every wire value is one the server's validator accepts`() {
        assertEquals(
            setOf("top", "bottom", "left", "right", "all"),
            OutpaintDirection.entries.map { it.wire }.toSet(),
        )
        assertEquals(setOf(0.05, 0.10, 0.15), OutpaintAmount.entries.map { it.wire }.toSet())
        assertEquals(
            setOf("left", "right", "up", "down", "dolly_out"),
            ViewpointMotion.entries.map { it.wire }.toSet(),
        )
        assertEquals(setOf("subtle", "standard"), ViewpointStrength.entries.map { it.wire }.toSet())
        assertEquals(setOf("front", "left", "right"), RelightDirection.entries.map { it.wire }.toSet())
    }

    @Test
    fun `remove_objects goes out as normalized rects plus the area it believes it used`() {
        val candidates = maskCandidates(oneObject)
        assertEquals(1, candidates.size)
        val draft = DirectEditDraft(
            operation = RescueOperation.REMOVE_OBJECTS,
            maskIds = setOf(candidates.single().id),
        )
        assertEquals(
            """{"type":"remove_objects","masks":[{"rect":{"x":0.05,"y":0.7,"width":0.12,"height":0.15}}],""" +
                """"maskAreaRatio":0.018}""",
            requireNotNull(buildDirectOperation(draft, candidates)).toString(),
        )
    }

    // ---- mask geometry ------------------------------------------------------

    @Test
    fun `the primary person is never offered as something to delete`() {
        val analysis = analysisOf(
            personAt(0.3, 0.2, 0.4, 0.7),
            objectAt(0.02, 0.8, 0.1, 0.12),
        )
        val candidates = maskCandidates(analysis)
        assertEquals(1, candidates.size)
        assertEquals(0.02, candidates.single().x, 1e-9)
    }

    @Test
    fun `an object flush against the edge survives the server's rounding rule`() {
        // `x + width` arrives as 1.0002 because the analyzer rounds both to four
        // decimals independently. `_validate_mask` rejects anything over 1.0, so
        // without the clamp this candidate — an edge distraction, the exact thing the
        // feature is for — would silently disappear.
        val analysis = analysisOf(objectAt(0.9002, 0.9002, 0.1, 0.1))
        val candidate = maskCandidates(analysis).single()
        assertTrue("x + width must not exceed 1.0", candidate.x + candidate.width <= 1.0)
        assertTrue("y + height must not exceed 1.0", candidate.y + candidate.height <= 1.0)
        assertTrue("the clamped mask must still be thick enough to inpaint", candidate.width >= MIN_DIRECT_MASK_SIDE)
    }

    @Test
    fun `a sliver too thin for the server to inpaint is not offered`() {
        assertEquals(emptyList<RescueMaskCandidate>(), maskCandidates(analysisOf(objectAt(0.4, 0.4, 0.004, 0.3))))
        assertEquals(emptyList<RescueMaskCandidate>(), maskCandidates(analysisOf(objectAt(0.4, 0.4, 0.3, 0.004))))
    }

    @Test
    fun `a malformed analysis yields no candidates instead of throwing`() {
        assertEquals(emptyList<RescueMaskCandidate>(), maskCandidates(JsonObject(emptyMap())))
        assertEquals(
            emptyList<RescueMaskCandidate>(),
            maskCandidates(buildJsonObject { put("subjects", "not an array") }),
        )
        assertEquals(
            emptyList<RescueMaskCandidate>(),
            maskCandidates(buildJsonObject { putJsonArray("subjects") { add("nonsense") } }),
        )
        // A bbox with the wrong arity, and one with a non-numeric entry.
        assertEquals(
            emptyList<RescueMaskCandidate>(),
            maskCandidates(
                buildJsonObject {
                    putJsonArray("subjects") {
                        addJsonObject {
                            put("role", "object")
                            putJsonArray("bbox") { add(0.1); add(0.1) }
                        }
                        addJsonObject {
                            put("role", "object")
                            putJsonArray("bbox") { add(0.1); add(0.1); add("wide"); add(0.2) }
                        }
                    }
                },
            ),
        )
    }

    @Test
    fun `selection stops at the area the server will refuse`() {
        // Four 0.09 rectangles: three fit inside 0.30, the fourth does not.
        val analysis = analysisOf(
            objectAt(0.0, 0.0, 0.3, 0.3),
            objectAt(0.35, 0.0, 0.3, 0.3),
            objectAt(0.0, 0.35, 0.3, 0.3),
            objectAt(0.35, 0.35, 0.3, 0.3),
        )
        val candidates = maskCandidates(analysis)
        assertEquals(4, candidates.size)
        val three = candidates.take(3)
        assertEquals(0.27, three.sumOf { it.area }, 1e-6)
        assertTrue(canSelectMask(three.take(2), three[2]))
        assertFalse(
            "a fourth would put the request past the server's edit-area limit",
            canSelectMask(three, candidates[3]),
        )
        // And the builder refuses the same set, not just the picker.
        assertNull(
            buildDirectOperation(
                DirectEditDraft(
                    operation = RescueOperation.REMOVE_OBJECTS,
                    maskIds = candidates.map { it.id }.toSet(),
                ),
                candidates,
            ),
        )
    }

    @Test
    fun `deselecting something already selected is always allowed`() {
        val candidates = maskCandidates(
            analysisOf(objectAt(0.0, 0.0, 0.3, 0.3), objectAt(0.35, 0.0, 0.3, 0.3), objectAt(0.0, 0.35, 0.3, 0.3)),
        )
        // The set is already at the limit; tapping a member must still toggle it off.
        assertTrue(canSelectMask(candidates, candidates.first()))
    }

    @Test
    fun `no more masks are offered than the server accepts`() {
        val many = (0 until 12).map { objectAt(0.0, 0.0, 0.02, 0.02, "cup$it") }
        assertEquals(MAX_DIRECT_MASKS, maskCandidates(analysisOf(*many.toTypedArray())).size)
    }

    // ---- copy ---------------------------------------------------------------

    @Test
    fun `every choice the user can see has a label, and none of them is a number`() {
        // R7-1/D10: everyday words only. A ratio leaking out as "0.15" or "15%" is the
        // failure this guards, and it is one forgotten `when` branch away.
        val labels = buildList {
            RescueOperation.entries.forEach { add(directOperationLabel(it)) }
            RescueOperation.entries.forEach { add(directParameterHint(it)) }
            add(directParameterHint(null))
            OutpaintDirection.entries.forEach { add(outpaintDirectionLabel(it)) }
            OutpaintAmount.entries.forEach { add(outpaintAmountLabel(it)) }
            ViewpointMotion.entries.forEach { add(viewpointMotionLabel(it)) }
            ViewpointStrength.entries.forEach { add(viewpointStrengthLabel(it)) }
            RelightDirection.entries.forEach { add(relightDirectionLabel(it)) }
            RelightStrength.entries.forEach { add(relightStrengthLabel(it)) }
            add(DIRECT_EDIT_TITLE)
            add(DIRECT_GROUP_WHAT)
            add(DIRECT_GROUP_WHERE)
            add(DIRECT_GROUP_HOW_MUCH)
            add(DIRECT_PREPARE_LABEL)
            add(DIRECT_RUN_LABEL)
            add(DIRECT_BACK_LABEL)
            add(DIRECT_MASK_FRAME_DESCRIPTION)
        }
        for (label in labels) {
            assertTrue("every visible label must say something", label.isNotBlank())
            assertFalse("no digits in user-facing copy: \"$label\"", label.any { it.isDigit() })
            assertFalse("no wire values in user-facing copy: \"$label\"", label.any { it.code in 'a'.code..'z'.code })
        }
    }
}
