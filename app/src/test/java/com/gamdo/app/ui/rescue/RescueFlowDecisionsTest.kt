package com.gamdo.app.ui.rescue

import com.gamdo.app.data.network.EditJobResult
import com.gamdo.app.data.network.RescueAnalysisResponse
import com.gamdo.app.data.network.RescueCapabilities
import com.gamdo.app.data.network.RescueRecommendation
import com.gamdo.app.data.preset.ColorParams
import com.gamdo.app.data.preset.Composition
import com.gamdo.app.data.preset.ReferenceCompositionSlot
import com.gamdo.app.data.preset.ResolvedStyle
import com.gamdo.app.data.rescue.RescueState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AI 3 (사진 살리기) — the pure decisions behind the P1 wiring described in
 * `docs/AI3_사진살리기_통합계약_2026-07-28.md`'s "P1 연결 계약".
 *
 * Same rule as [com.gamdo.app.ui.reference.ReferenceFlowDecisionsTest]: this project
 * has no `androidTest` source set and no Robolectric, so anything holding a
 * `Context`, `Uri` or `Bitmap` cannot execute here. Everything under test has zero
 * `android.*` imports; `RescueSheet.kt` and the `ResultScreen` wiring that calls it
 * are DONE-DEVICE only.
 *
 * [RescueState] itself is JVM-safe — it is declared next to [com.gamdo.app.data.rescue.RescueController]
 * but references only kotlinx types and `java.io.File`, so its values can be built here.
 */
class RescueFlowDecisionsTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // ---- fixtures ----------------------------------------------------------

    private fun recommendation(
        id: String,
        kind: String,
        operation: JsonObject? = buildJsonObject { put("type", kind) },
    ) = RescueRecommendation(
        id = id,
        kind = kind,
        title = "제목",
        reason = "이유",
        operation = operation,
        confidence = 0.8,
    )

    private fun response(
        recommendations: List<RescueRecommendation>,
        capabilities: RescueCapabilities,
    ) = RescueAnalysisResponse(
        recommendations = recommendations,
        capabilities = capabilities,
    )

    private val allThree = listOf(
        recommendation("local_style", "local_style"),
        recommendation("remove_objects", "remove_objects"),
        recommendation("outpaint", "outpaint"),
    )

    // ---- rescueSectionFor --------------------------------------------------

    @Test
    fun `a closed sheet renders nothing, whatever the controller is doing`() {
        val states = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Recommendations(response(allThree, RescueCapabilities())),
            RescueState.Editing(buildJsonObject { put("type", "remove_objects") }),
            RescueState.Submitting,
            RescueState.Polling,
            RescueState.Candidates("job_1", emptyList()),
            RescueState.LocalFallback("analysis_unavailable"),
        )
        for (state in states) {
            assertEquals(
                "state=$state must stay hidden while the sheet is closed",
                RescueSection.HIDDEN,
                rescueSectionFor(state, opened = false),
            )
        }
    }

    @Test
    fun `an opened sheet on Idle is the intro — the step that asks before any upload`() {
        assertEquals(RescueSection.INTRO, rescueSectionFor(RescueState.Idle, opened = true))
    }

    @Test
    fun `analyzing and submitting and polling all render the same progress section`() {
        assertEquals(RescueSection.PROGRESS, rescueSectionFor(RescueState.Analyzing, opened = true))
        assertEquals(RescueSection.PROGRESS, rescueSectionFor(RescueState.Submitting, opened = true))
        assertEquals(RescueSection.PROGRESS, rescueSectionFor(RescueState.Polling, opened = true))
    }

    @Test
    fun `each remaining state maps to its own section`() {
        assertEquals(
            RescueSection.RECOMMENDATIONS,
            rescueSectionFor(RescueState.Recommendations(response(allThree, RescueCapabilities())), opened = true),
        )
        assertEquals(
            RescueSection.CONFIRM,
            rescueSectionFor(RescueState.Editing(buildJsonObject { put("type", "outpaint") }), opened = true),
        )
        assertEquals(
            RescueSection.CANDIDATES,
            rescueSectionFor(RescueState.Candidates("job_1", emptyList()), opened = true),
        )
        assertEquals(
            RescueSection.FALLBACK,
            rescueSectionFor(RescueState.LocalFallback("generation_unavailable"), opened = true),
        )
    }

    // ---- retention: nothing may outlive the flow that set it ----------------

    private val everyState = listOf(
        RescueState.Idle,
        RescueState.Analyzing,
        RescueState.Submitting,
        RescueState.Polling,
        RescueState.Candidates("job_1", emptyList()),
        RescueState.LocalFallback("analysis_unavailable"),
    )

    @Test
    fun `the card list survives the tap that moves Recommendations to Editing`() {
        val analysed = response(allThree, RescueCapabilities())
        val held = retainedRecommendations(RescueState.Recommendations(analysed), previous = null)
        assertEquals(analysed, held)
        assertEquals(
            analysed,
            retainedRecommendations(RescueState.Editing(buildJsonObject { put("type", "remove_objects") }), held),
        )
    }

    /**
     * AI 2's ghost-overlay defect in this feature's terms: a UI value set when the
     * flow started that a failed, cancelled or reset flow never cleared.
     */
    @Test
    fun `every state outside the picking pair drops the card list`() {
        val analysed = response(allThree, RescueCapabilities())
        for (state in everyState) {
            assertNull(
                "state=$state must not keep the analysis on screen",
                retainedRecommendations(state, previous = analysed),
            )
        }
    }

    @Test
    fun `the running operation is carried from Editing into Submitting and Polling`() {
        val operation = buildJsonObject { put("type", "remove_objects") }
        val seeded = retainedRunningOperation(RescueState.Editing(operation), previous = null)
        assertEquals(operation, seeded)
        assertEquals(operation, retainedRunningOperation(RescueState.Submitting, seeded))
        assertEquals(operation, retainedRunningOperation(RescueState.Polling, seeded))
    }

    @Test
    fun `a finished or abandoned job leaves no verb behind for the next one`() {
        val operation = buildJsonObject { put("type", "outpaint") }
        val ending = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Recommendations(response(allThree, RescueCapabilities())),
            RescueState.Candidates("job_1", emptyList()),
            RescueState.LocalFallback("generation_unavailable"),
        )
        for (state in ending) {
            assertNull("state=$state must drop the running operation", retainedRunningOperation(state, operation))
        }
    }

    @Test
    fun `a picked candidate survives only while the job's candidates are the state`() {
        assertEquals(
            "result_0",
            retainedCandidateId(RescueState.Candidates("job_1", emptyList()), previous = "result_0"),
        )
    }

    @Test
    fun `leaving Candidates drops the pick, so the screen falls back to the original`() {
        val leaving = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Recommendations(response(allThree, RescueCapabilities())),
            RescueState.Editing(buildJsonObject { put("type", "remove_objects") }),
            RescueState.Submitting,
            RescueState.Polling,
            RescueState.LocalFallback("generation_unavailable"),
        )
        for (state in leaving) {
            assertNull("state=$state must not keep a generated photo on screen", retainedCandidateId(state, "result_0"))
        }
    }

    // ---- offerableRecommendations (capabilities gate) ----------------------

    @Test
    fun `every capability on offers all three recommendations`() {
        val offered = offerableRecommendations(
            response(allThree, RescueCapabilities(localStyle = true, removeObjects = true, outpaint = true)),
        )
        assertEquals(listOf("local_style", "remove_objects", "outpaint"), offered.map { it.kind })
    }

    @Test
    fun `outpaint false is not offered — CAMP-2 has no FLUX workflow yet`() {
        val offered = offerableRecommendations(
            response(allThree, RescueCapabilities(localStyle = true, removeObjects = true, outpaint = false)),
        )
        assertEquals(listOf("local_style", "remove_objects"), offered.map { it.kind })
    }

    @Test
    fun `removeObjects false is not offered`() {
        val offered = offerableRecommendations(
            response(allThree, RescueCapabilities(localStyle = true, removeObjects = false, outpaint = true)),
        )
        assertEquals(listOf("local_style", "outpaint"), offered.map { it.kind })
    }

    @Test
    fun `localStyle false is not offered either — the gate is uniform, not per-kind special-casing`() {
        val offered = offerableRecommendations(
            response(allThree, RescueCapabilities(localStyle = false, removeObjects = true, outpaint = true)),
        )
        assertEquals(listOf("remove_objects", "outpaint"), offered.map { it.kind })
    }

    @Test
    fun `all capabilities off offers nothing at all`() {
        val offered = offerableRecommendations(
            response(allThree, RescueCapabilities(localStyle = false, removeObjects = false, outpaint = false)),
        )
        assertTrue(offered.isEmpty())
    }

    /**
     * The server's `ALLOWED_OPERATIONS` (`routes/edit_jobs.py`) includes `eye_fix`,
     * `skin_tone_even`, `relight` and `simplify_background`. AGENTS.md §6 규칙 3 makes
     * face alteration non-negotiable, so the app must not become able to run one
     * merely because a server build starts recommending it. The gate is a
     * whitelist, not a blacklist: a kind with no capability flag of its own cannot
     * be offered.
     */
    @Test
    fun `a kind the app does not know is never offered, even with every capability on`() {
        val offered = offerableRecommendations(
            response(
                listOf(
                    recommendation("eye_fix", "eye_fix"),
                    recommendation("skin_tone_even", "skin_tone_even"),
                    recommendation("relight", "relight"),
                    recommendation("simplify_background", "simplify_background"),
                    recommendation("remove_objects", "remove_objects"),
                ),
                RescueCapabilities(localStyle = true, removeObjects = true, outpaint = true),
            ),
        )
        assertEquals(listOf("remove_objects"), offered.map { it.kind })
    }

    @Test
    fun `a recommendation with no operation cannot be chosen, so it is not offered`() {
        val offered = offerableRecommendations(
            response(
                listOf(recommendation("remove_objects", "remove_objects", operation = null)),
                RescueCapabilities(localStyle = true, removeObjects = true, outpaint = true),
            ),
        )
        assertTrue(offered.isEmpty())
    }

    /** The card's own `operation.type` is what gets submitted, so it — not `kind` — must be gated too. */
    @Test
    fun `an operation type that disagrees with the gated kind is not offered`() {
        val smuggled = RescueRecommendation(
            id = "remove_objects",
            kind = "remove_objects",
            title = "제목",
            reason = "이유",
            operation = buildJsonObject { put("type", "skin_tone_even") },
        )
        val offered = offerableRecommendations(
            response(listOf(smuggled), RescueCapabilities(localStyle = true, removeObjects = true, outpaint = true)),
        )
        assertTrue(offered.isEmpty())
    }

    // ---- upload / submit permission ---------------------------------------

    @Test
    fun `remove_objects and outpaint are uploads, local_style is not`() {
        assertTrue(requiresUpload(buildJsonObject { put("type", "remove_objects") }))
        assertTrue(requiresUpload(buildJsonObject { put("type", "outpaint") }))
        assertFalse(requiresUpload(buildJsonObject { put("type", "local_style") }))
        assertTrue(requiresUpload(buildJsonObject { put("type", "viewpoint") }))
        assertTrue(requiresUpload(buildJsonObject { put("type", "relight") }))
    }

    @Test
    fun `viewpoint and relight require their own server capability`() {
        val recommendations = listOf(
            recommendation("viewpoint", "viewpoint"),
            recommendation("relight", "relight"),
        )
        assertTrue(offerableRecommendations(response(recommendations, RescueCapabilities())).isEmpty())
        val offered = offerableRecommendations(
            response(recommendations, RescueCapabilities(viewpoint = true, relight = true)),
        )
        assertEquals(listOf("viewpoint", "relight"), offered.map { it.kind })
    }

    /**
     * `local_style` is not in the server's `ALLOWED_OPERATIONS`; posting it would be
     * a 422 and, worse, an upload of the user's photo for an operation that never
     * needed to leave the phone.
     */
    @Test
    fun `submit is permitted only from Editing, and never for a local-only operation`() {
        assertTrue(canSubmit(RescueState.Editing(buildJsonObject { put("type", "remove_objects") })))
        assertTrue(canSubmit(RescueState.Editing(buildJsonObject { put("type", "outpaint") })))
        assertFalse(canSubmit(RescueState.Editing(buildJsonObject { put("type", "local_style") })))
    }

    @Test
    fun `submit is refused from every state that is not Editing`() {
        val states = listOf(
            RescueState.Idle,
            RescueState.Analyzing,
            RescueState.Recommendations(response(allThree, RescueCapabilities())),
            RescueState.Submitting,
            RescueState.Polling,
            RescueState.Candidates("job_1", emptyList()),
            RescueState.LocalFallback("analysis_unavailable"),
        )
        for (state in states) {
            assertFalse("state=$state must not permit an upload", canSubmit(state))
        }
    }

    @Test
    fun `an operation with no type at all is neither uploadable nor submittable`() {
        val empty = JsonObject(emptyMap())
        assertFalse(requiresUpload(empty))
        assertFalse(canSubmit(RescueState.Editing(empty)))
    }

    // ---- progress copy ------------------------------------------------------

    @Test
    fun `progress copy names the operation in everyday words, with no numbers`() {
        assertEquals("방해 요소를 지우고 있어요", progressMessageFor(buildJsonObject { put("type", "remove_objects") }))
        assertEquals("여백을 넓히고 있어요", progressMessageFor(buildJsonObject { put("type", "outpaint") }))
        assertEquals("사진을 살펴보고 있어요", progressMessageFor(null))
        assertEquals("보는 위치를 바꾸고 있어요", progressMessageFor(buildJsonObject { put("type", "viewpoint") }))
        assertEquals("빛의 균형을 맞추고 있어요", progressMessageFor(buildJsonObject { put("type", "relight") }))
    }

    @Test
    fun `confirm copy names the operation without technical words`() {
        assertEquals("방해 요소를 지울게요", confirmMessageFor(buildJsonObject { put("type", "remove_objects") }))
        assertEquals("여백을 넓힐게요", confirmMessageFor(buildJsonObject { put("type", "outpaint") }))
        // 내 감도로 정리하기 applies the user's 감도 now instead of closing the sheet on
        // an unchanged photo (브리프 §13 결함 2), so the line promises the change the
        // button makes. An unknown operation still promises nothing, because for it
        // nothing is what the sheet can do.
        assertEquals("내 감도로 정리할게요", confirmMessageFor(buildJsonObject { put("type", "local_style") }))
        assertEquals("지금 보정만 그대로 둘게요", confirmMessageFor(JsonObject(emptyMap())))
        assertEquals("보는 위치를 바꿀게요", confirmMessageFor(buildJsonObject { put("type", "viewpoint") }))
        assertEquals("빛의 균형을 맞출게요", confirmMessageFor(buildJsonObject { put("type", "relight") }))
    }

    /**
     * D10 / the contract's 기술 점수·전문 용어 노출 금지. The copy the sheet can produce
     * is a closed set, so it can simply be asserted to contain none of it.
     */
    @Test
    fun `no user-facing copy carries a score, a job id, or an engine name`() {
        val everyLine = listOf(
            progressMessageFor(null),
            progressMessageFor(buildJsonObject { put("type", "remove_objects") }),
            progressMessageFor(buildJsonObject { put("type", "outpaint") }),
            confirmMessageFor(buildJsonObject { put("type", "remove_objects") }),
            confirmMessageFor(buildJsonObject { put("type", "outpaint") }),
            confirmMessageFor(buildJsonObject { put("type", "local_style") }),
            fallbackMessage("edit_job_timeout"),
            LOCAL_FALLBACK_MESSAGE,
            GENERATIVE_BADGE_LABEL,
        )
        val banned = listOf(
            "LaMa", "FLUX", "ComfyUI", "mask", "마스크", "confidence", "신뢰도", "점수",
            "job", "job_", "outpaint", "remove_objects", "local_style", "%", "px",
        )
        for (line in everyLine) {
            for (word in banned) {
                assertFalse("'$line' must not contain '$word'", line.contains(word, ignoreCase = true))
            }
            assertFalse("'$line' must not contain a digit", line.any { it.isDigit() })
        }
    }

    // ---- LocalFallback ------------------------------------------------------

    /**
     * R5 / the contract's own wording. `LocalFallback.reason` is `analysis_unavailable`,
     * `generation_unavailable`, or the server's raw `fail_reason` forwarded through
     * `RescueRepository.submitAndPoll`'s `error(status.failReason ?: ...)`. None of it
     * may reach the user.
     */
    @Test
    fun `every fallback reason renders the one contract sentence and nothing else`() {
        val reasons = listOf(
            "analysis_unavailable",
            "generation_unavailable",
            "edit_job_timeout",
            "edit_job_fallback",
            "provider_not_ready",
            "HTTP 404 http_404: Not Found",
            "java.net.UnknownHostException: api.anjonghwa.madcamp-kaist.org",
            "",
        )
        for (reason in reasons) {
            val shown = fallbackMessage(reason)
            assertEquals("자연스러운 보정만 적용했어요", shown)
            if (reason.isNotEmpty()) {
                assertFalse("reason '$reason' leaked into the UI copy", shown.contains(reason))
            }
        }
    }

    // ---- candidates ---------------------------------------------------------

    private fun localResult(rank: Int, file: java.io.File) =
        RescueLocalResult(resultId = "result_$rank", filePath = file.absolutePath, rank = rank)

    private fun newFile(name: String) = temporaryFolder.newFile(name)

    @Test
    fun `candidates pair the server results with the files that were actually downloaded`() {
        val first = newFile("rescue_job_1_0.png")
        val second = newFile("rescue_job_1_1.png")
        val candidates = rescueCandidates(
            results = listOf(EditJobResult(url = "/a.png"), EditJobResult(url = "/b.png")),
            rows = listOf(localResult(0, first), localResult(1, second)),
        )
        assertEquals(listOf("result_0", "result_1"), candidates.map { it.resultId })
        assertEquals(listOf(first.absolutePath, second.absolutePath), candidates.map { it.filePath })
    }

    @Test
    fun `at most two candidates are shown`() {
        val files = (0..3).map { newFile("r$it.png") }
        val candidates = rescueCandidates(
            results = files.map { EditJobResult(url = "/${it.name}") },
            rows = files.mapIndexed { index, file -> localResult(index, file) },
        )
        assertEquals(MAX_RESCUE_CANDIDATES, candidates.size)
        assertEquals(2, candidates.size)
    }

    @Test
    fun `rows out of order are shown by rank`() {
        val zero = newFile("zero.png")
        val one = newFile("one.png")
        val candidates = rescueCandidates(
            results = listOf(EditJobResult(url = "/a.png"), EditJobResult(url = "/b.png")),
            rows = listOf(localResult(1, one), localResult(0, zero)),
        )
        assertEquals(listOf(0, 1), candidates.map { it.rank })
        assertEquals(zero.absolutePath, candidates.first().filePath)
    }

    /**
     * AGENTS.md §6 규칙 6 (더미·고정 이미지를 실제 결과로 보이지 않는다) in its most
     * literal form: a tile whose file is not on disk would render as an empty box
     * that still claims to be a result. The download is what makes a candidate real,
     * so a row without a file is not a candidate.
     */
    @Test
    fun `a recorded result whose file is missing is not a candidate`() {
        val present = newFile("present.png")
        val missing = RescueLocalResult("result_1", temporaryFolder.root.resolve("gone.png").absolutePath, 1)
        val candidates = rescueCandidates(
            results = listOf(EditJobResult(url = "/a.png"), EditJobResult(url = "/b.png")),
            rows = listOf(localResult(0, present), missing),
        )
        assertEquals(listOf("result_0"), candidates.map { it.resultId })
    }

    @Test
    fun `no local rows means no candidates, not empty tiles`() {
        val candidates = rescueCandidates(
            results = listOf(EditJobResult(url = "/a.png")),
            rows = emptyList(),
        )
        assertTrue(candidates.isEmpty())
    }

    /**
     * Fail-safe direction. `/edit-jobs` only accepts generative operation types
     * (`routes/edit_jobs.py` `ALLOWED_OPERATIONS`), and `db.py` writes `generative = 1`
     * for every result row, so a candidate here is always AI-completed. Deriving the
     * badge from `EditJobResult.generative` would mean a server that omitted the
     * field (it defaults to `false` in the Kotlin model) silently ships a generated
     * photo with no badge — the exact thing the contract calls 필수.
     */
    @Test
    fun `the AI badge is shown even when the server omits the generative flag`() {
        assertTrue(showsGenerativeBadge(EditJobResult(url = "/a.png", generative = false)))
        assertTrue(showsGenerativeBadge(EditJobResult(url = "/a.png", generative = true)))
        assertEquals("AI 생성 보완", GENERATIVE_BADGE_LABEL)
    }

    // ---- what gets sent with analyze/submit --------------------------------

    private fun style(
        scope: ResolvedStyle.ReferenceScope = ResolvedStyle.ReferenceScope.BOTH,
        slots: List<ReferenceCompositionSlot> = emptyList(),
        source: ResolvedStyle.Source = ResolvedStyle.Source.REFERENCE,
    ) = ResolvedStyle(
        source = source,
        sourceKey = "hash",
        displayName = "내 레퍼런스",
        composition = Composition(
            targetAspectRatio = "4:5",
            subjectScaleRange = listOf(0.25, 0.75),
            subjectPosition = "center",
            headroomRange = listOf(0.04, 0.24),
            horizonPosition = 0.5,
            cameraPitchRange = listOf(-5.0, 5.0),
            posePattern = "natural",
            backgroundRatio = listOf(0.3, 0.8),
        ),
        color = ColorParams(
            colorTemperature = 5200.0,
            exposureBias = 0.0,
            contrast = 0.0,
            saturation = 0.0,
            grain = 0.0,
            vignette = 0.0,
            blurStrength = 0.0,
            fade = 0.0,
        ),
        referenceScope = scope,
        referenceSlots = slots,
    )

    @Test
    fun `no active style sends an empty style object rather than invented numbers`() {
        assertEquals(JsonObject(emptyMap()), styleParamsJson(null))
        assertEquals(JsonObject(emptyMap()), referenceCompositionJson(null))
    }

    @Test
    fun `style params carry the composition the server actually reads`() {
        val params = styleParamsJson(style())
        val composition = params["composition"]!!.jsonObject
        assertEquals("4:5", composition["targetAspectRatio"]!!.jsonPrimitive.content)
        assertEquals(
            listOf(0.3, 0.8),
            composition["backgroundRatio"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() },
        )
    }

    /** No colour temperature, no confidence, no score — the contract's 기술 점수 노출 금지. */
    @Test
    fun `style params carry no colour or scoring fields`() {
        val params = styleParamsJson(style())
        val flattened = params.toString()
        for (banned in listOf("colorTemperature", "confidence", "score", "saturation", "contrast")) {
            assertFalse("styleParams must not carry $banned", flattened.contains(banned))
        }
    }

    @Test
    fun `a reference with slots sends them as the composition to aim at`() {
        val slots = listOf(
            ReferenceCompositionSlot(role = "person", visualKind = "person", bounds = listOf(0.1, 0.2, 0.3, 0.4)),
        )
        val sent = referenceCompositionJson(style(slots = slots))
        val bounds = sent["layoutSlots"]!!.jsonArray.single().jsonObject["bounds"]!!.jsonArray
        assertEquals(listOf(0.1, 0.2, 0.3, 0.4), bounds.map { it.jsonPrimitive.content.toDouble() })
    }

    @Test
    fun `a colour-only reference has no composition to send`() {
        val slots = listOf(
            ReferenceCompositionSlot(role = "person", visualKind = "person", bounds = listOf(0.1, 0.2, 0.3, 0.4)),
        )
        assertEquals(
            JsonObject(emptyMap()),
            referenceCompositionJson(style(scope = ResolvedStyle.ReferenceScope.COLOR, slots = slots)),
        )
    }

    @Test
    fun `a preset style is not a reference composition`() {
        val slots = listOf(
            ReferenceCompositionSlot(role = "person", visualKind = "person", bounds = listOf(0.1, 0.2, 0.3, 0.4)),
        )
        assertEquals(
            JsonObject(emptyMap()),
            referenceCompositionJson(style(source = ResolvedStyle.Source.PRESET, slots = slots)),
        )
    }

    // ---- operationType helper ----------------------------------------------

    @Test
    fun `operation type reads the wire field and tolerates a missing one`() {
        assertEquals("remove_objects", operationType(buildJsonObject { put("type", "remove_objects") }))
        assertNull(operationType(JsonObject(emptyMap())))
    }

    // ---- reopening on an outcome (브리프 §13 결함 5) --------------------------

    private val done = RescueState.Candidates(jobId = "job_1", results = emptyList())
    private val failed = RescueState.LocalFallback("generation_unavailable")

    /**
     * The defect itself: the user starts a generation, taps outside to watch the photo
     * while it runs (which hides rather than cancels, owner decision 2026-07-30), and
     * the job finishes into a sheet nobody has open. Both outcomes have to come back.
     */
    @Test
    fun `a job that finishes while the sheet is hidden brings the sheet back`() {
        assertTrue(reopensOnOutcome(RescueState.Polling, done, opened = false))
        assertTrue(reopensOnOutcome(RescueState.Submitting, done, opened = false))
        assertTrue(reopensOnOutcome(RescueState.Polling, failed, opened = false))
        assertTrue(reopensOnOutcome(RescueState.Submitting, failed, opened = false))
    }

    @Test
    fun `an open sheet is never reopened over itself`() {
        assertFalse(reopensOnOutcome(RescueState.Polling, done, opened = true))
        assertFalse(reopensOnOutcome(RescueState.Polling, failed, opened = true))
    }

    /**
     * The rule is an edge, and this is why. Once the user has seen the result and
     * closed it the controller is still sitting on the same outcome state, so a
     * state-shaped rule would re-open the sheet every recomposition and the user could
     * never put it away. Dismissing a finished sheet has to stick.
     */
    @Test
    fun `closing a finished sheet does not summon it again`() {
        assertFalse(reopensOnOutcome(done, done, opened = false))
        assertFalse(reopensOnOutcome(failed, failed, opened = false))
    }

    /** Cancelling goes to `Idle`, and the user who cancelled needs no announcement. */
    @Test
    fun `a cancel stays silent`() {
        assertFalse(reopensOnOutcome(RescueState.Polling, RescueState.Idle, opened = false))
    }

    /**
     * Opening a photo while the app-scoped controller still holds another photo's
     * results must not pop a sheet the user did not ask for. There is no running →
     * outcome edge in that case, because nothing ran on this screen.
     */
    @Test
    fun `arriving on a stale outcome opens nothing`() {
        assertFalse(reopensOnOutcome(RescueState.Idle, done, opened = false))
        assertFalse(reopensOnOutcome(RescueState.Analyzing, done, opened = false))
        assertFalse(
            reopensOnOutcome(
                RescueState.Recommendations(response(allThree, RescueCapabilities())),
                done,
                opened = false,
            ),
        )
    }
}
