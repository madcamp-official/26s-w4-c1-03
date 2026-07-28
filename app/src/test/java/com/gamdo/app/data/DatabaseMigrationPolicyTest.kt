package com.gamdo.app.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the one Room setting that can destroy every byte of user data.
 *
 * `.fallbackToDestructiveMigration()` tells Room that when the compiled schema
 * does not match the schema on disk it should **drop and recreate every table**.
 * In an app where the server is not the source of truth — DB 스키마 v2.0 §6:
 * "로컬이 원천이므로 앱 삭제 = 데이터 전체 소실" — that setting turns any schema
 * edit into silent, total data loss: `style_profile`, every `captures` row, the
 * whole `capture_edit_stack`, and the `sessions`/`session_guides` KPI evidence.
 *
 * The failure has no crash, no log and no prompt. It is invisible until somebody
 * notices a table is empty, and by then the rows are gone. AGENTS.md §7 규칙 2
 * explicitly *allows* adding a nullable column, so the trigger is a change the
 * rules invite — the demo-eve hotfix that costs the demo its evidence.
 *
 * ## Why this test reads source text
 *
 * There is no `androidTest` source set and no Robolectric, so `Room.databaseBuilder`
 * cannot be constructed on the JVM at all — the builder needs a `Context`. That
 * leaves no way to assert this behaviourally. Reading the source is the only
 * gate available, and an imperfect gate on total data loss beats none.
 * `CardRepositoryTest` already reads real files from `src/main/assets` the same
 * way, so the working-directory assumption (`app/`) is established here.
 *
 * If this test ever fails, do not delete it and do not re-add the call. Write a
 * real `Migration` — see the KDoc on `AppContainer.database`.
 */
class DatabaseMigrationPolicyTest {

    private val appContainerSource = File("src/main/java/com/gamdo/app/data/AppContainer.kt")

    /**
     * Strips block and line comments so the assertions below read *code*, not prose.
     *
     * The first version of this test matched raw text and failed against the fix,
     * because the KDoc explaining why the fallback is absent naturally names it.
     * A guard that forbids discussing the thing it guards is a guard that gets
     * deleted the first time someone documents the decision.
     */
    private fun codeOf(file: File): String =
        file.readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines().joinToString("\n") { it.substringBefore("//") }

    @Test
    fun `the source file this test guards actually exists and still builds a Room database`() {
        // Without this, a moved or renamed file would make every assertion below
        // pass vacuously against an empty string.
        assertTrue(
            "AppContainer.kt not found at ${appContainerSource.absolutePath} — " +
                "if the file moved, update this test rather than deleting it.",
            appContainerSource.isFile,
        )
        assertTrue(
            "AppContainer no longer calls Room.databaseBuilder — this guard is " +
                "now pointed at the wrong place and is passing for the wrong reason.",
            codeOf(appContainerSource).contains("Room.databaseBuilder"),
        )
    }

    @Test
    fun `the database is not built with destructive migration`() {
        assertFalse(
            "AppContainer builds the Room database with a destructive-migration " +
                "fallback. On any schema change this drops all 14 local tables " +
                "with no crash and no prompt. Add a Migration instead.",
            codeOf(appContainerSource).contains("fallbackToDestructiveMigration"),
        )
    }

    /**
     * The sibling escape hatches. `fallbackToDestructiveMigrationOnDowngrade` and
     * `fallbackToDestructiveMigrationFrom` lose data on a narrower trigger, which
     * makes them harder to notice, not safer.
     */
    @Test
    fun `no narrower destructive fallback is used either`() {
        val code = codeOf(appContainerSource)
        for (variant in listOf(
            "fallbackToDestructiveMigrationOnDowngrade",
            "fallbackToDestructiveMigrationFrom",
        )) {
            assertFalse(
                "AppContainer uses $variant — same data loss, narrower trigger.",
                code.contains(variant),
            )
        }
    }

    /**
     * The comment stripper is load-bearing: if it silently stopped working, the
     * assertions above would go back to matching prose and this whole class would
     * fail for the wrong reason. Pin its behaviour rather than trusting it.
     */
    @Test
    fun `the comment stripper removes prose but keeps code`() {
        val probe = File.createTempFile("probe", ".kt")
        probe.deleteOnExit()
        probe.writeText(
            """
            /** Never call fallbackToDestructiveMigration() here. */
            val a = Room.databaseBuilder(x) // fallbackToDestructiveMigration
                .build()
            """.trimIndent(),
        )
        val code = codeOf(probe)
        assertFalse("block and line comments must be stripped", code.contains("fallbackToDestructive"))
        assertTrue("code outside comments must survive", code.contains("Room.databaseBuilder"))
        assertTrue("code after a stripped line comment must survive", code.contains(".build()"))
    }
}
