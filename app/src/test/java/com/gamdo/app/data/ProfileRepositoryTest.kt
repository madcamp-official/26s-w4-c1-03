package com.gamdo.app.data

import com.gamdo.app.data.local.CardSelectionsDao
import com.gamdo.app.data.local.StyleProfileDao
import com.gamdo.app.data.local.entity.CardSelections
import com.gamdo.app.data.local.entity.StyleProfile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `card_selections.id` must carry the DDL's `sel_` prefix
 * (`docs/감도_GAMDO_DB스키마_v2.0.md` §3.4: `id TEXT PRIMARY KEY, -- 'sel_' + ULID`).
 * Same class of defect already fixed for `session_guides` in fe60b9e (`sgd_` -> `gid_`).
 *
 * `CardSelectionsDao`/`StyleProfileDao` are plain `@Dao`-annotated interfaces with no
 * android.* import, so a hand-written fake stands in for the Room-generated
 * implementation on the JVM — same workaround `CardRepositoryTest` uses for `Context`.
 */
class ProfileRepositoryTest {

    private class FakeCardSelectionsDao : CardSelectionsDao {
        val inserted = mutableListOf<CardSelections>()

        override suspend fun deleteRound(round: Int) {
            inserted.removeAll { it.round == round }
        }

        override suspend fun insert(selection: CardSelections) {
            inserted.add(selection)
        }

        override suspend fun insertAll(selections: List<CardSelections>) {
            inserted.addAll(selections)
        }

        override suspend fun forRound(round: Int): List<CardSelections> =
            inserted.filter { it.round == round }

        override suspend fun getAll(): List<CardSelections> = inserted.toList()

        override suspend fun count(): Int = inserted.size
    }

    private class FakeStyleProfileDao : StyleProfileDao {
        var saved: StyleProfile? = null

        override suspend fun upsert(profile: StyleProfile) {
            saved = profile
        }

        override suspend fun get(): StyleProfile? = saved

        override suspend fun count(): Int = if (saved != null) 1 else 0
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun sampleProfile() = StyleProfileResult(
        composition = emptyMap(),
        color = emptyMap(),
        recommendedPresetIds = listOf("clean_social"),
        summary = "테스트 요약",
    )

    @Test
    fun `card_selections rows carry the DDL 'sel_' id prefix, not the old round-cardId shape`() = runBlocking {
        val cardSelectionsDao = FakeCardSelectionsDao()
        val repository = ProfileRepository(FakeStyleProfileDao(), cardSelectionsDao, json)

        repository.saveInitialProfile(setOf("card_01", "card_02"), sampleProfile())

        assertEquals(2, cardSelectionsDao.inserted.size)
        cardSelectionsDao.inserted.forEach { row ->
            assertTrue("${row.id} must start with 'sel_' (DDL v2.0 §3.4)", row.id.startsWith("sel_"))
            assertTrue(
                "${row.id} must not use the old 'round-N:cardId' shape",
                !row.id.startsWith("round-"),
            )
        }
        // Distinct rows must not collide on id even though they share a round.
        assertEquals(2, cardSelectionsDao.inserted.map { it.id }.distinct().size)
    }
}
