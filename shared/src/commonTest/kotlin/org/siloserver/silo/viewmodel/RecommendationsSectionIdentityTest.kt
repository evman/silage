package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.recommendation.DiscoverRow
import org.siloserver.silo.model.section.SectionItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame

class RecommendationsSectionIdentityTest {
    @Test
    fun productionSectionIdsSurviveRowInsertionAndReorder() {
        val returnedFrom = row(
            type = "cluster",
            label = "Because you enjoy Drama",
            sectionKind = "cluster",
            sectionKey = "2",
            contentId = "movie-b",
        )
        val popular = row(
            type = "popular",
            label = "Popular on This Server",
            sectionKind = "popular",
            contentId = "movie-popular",
        )

        val beforeRefresh = listOf(returnedFrom, popular).toResolvedSections()
        val afterRefresh = listOf(
            row(
                type = "cluster",
                label = "Because you enjoy Comedy",
                sectionKind = "cluster",
                sectionKey = "7",
                contentId = "movie-new",
            ),
            popular,
            returnedFrom,
        ).toResolvedSections()

        val originalId = beforeRefresh.single { section ->
            section.items.any { it.contentId == "movie-b" }
        }.id
        val refreshedId = afterRefresh.single { section ->
            section.items.any { it.contentId == "movie-b" }
        }.id
        val insertedId = afterRefresh.single { section ->
            section.items.any { it.contentId == "movie-new" }
        }.id

        assertEquals(originalId, refreshedId)
        assertNotEquals(insertedId, refreshedId)
    }

    /**
     * `discoverRowSectionKey` returns an empty kind for row types it does not
     * recognise, and both identity fields are `omitempty`, so unrecognised rows
     * — and every row from a server predating `section_kind` — fall back to
     * type+label. Section IDs key a LazyColumn, where a duplicate is a crash,
     * so collisions must resolve rather than propagate.
     */
    @Test
    fun collidingSectionIdentitiesStayUniqueForLazyListKeys() {
        val keyless = { contentId: String ->
            DiscoverRow(
                type = "server_row_this_client_does_not_know",
                label = "Handpicked",
                items = listOf(SectionItem(contentId = contentId, type = "movie", title = contentId)),
            )
        }

        val ids = listOf(keyless("movie-a"), keyless("movie-b"), keyless("movie-c"))
            .toResolvedSections()
            .map { it.id }

        assertEquals(3, ids.size)
        assertEquals(ids.size, ids.toSet().size, "section ids must be unique: $ids")
    }

    /**
     * `discoverRowSectionKey` sends no key for the singleton kinds —
     * for-you-main, similar-users, popular, recently-added, top-rated — and
     * `section_key` is `omitempty`, so the client sees null. Those rows change
     * contents on every refresh, so their identity must come from the kind
     * alone; deriving it from contents would break the detail return on exactly
     * the rows that churn most.
     *
     * The kind strings are the server's wire values (hyphenated), not the row
     * `type` values (underscored) — the two differ, and only the former is
     * matched.
     */
    @Test
    fun keylessServerKindsKeepTheirIdentityAcrossContentChurn() {
        val recentlyAdded = { contentIds: List<String> ->
            DiscoverRow(
                type = "recently_added",
                label = "Recently Added",
                sectionKind = "recently-added",
                sectionKey = null,
                items = contentIds.map { SectionItem(contentId = it, type = "movie", title = it) },
            )
        }

        val before = listOf(recentlyAdded(listOf("a", "b", "c"))).toResolvedSections().single().id
        val afterNewMedia =
            listOf(recentlyAdded(listOf("new", "a", "b"))).toResolvedSections().single().id

        assertEquals(before, afterNewMedia)
    }

    /** Kinds that legitimately repeat still separate on their key. */
    @Test
    fun keyedServerKindsStayDistinctPerKey() {
        val ids = listOf(
            row("cluster", "Because you enjoy Drama", "cluster", "2", "movie-a"),
            row("cluster", "Because you enjoy Comedy", "cluster", "7", "movie-b"),
        ).toResolvedSections().map { it.id }

        assertEquals(ids.size, ids.toSet().size, "keyed rows must not collapse: $ids")
    }

    /**
     * The regression that motivated the singleton allowlist. Identifying a row
     * by a bare kind is only sound for kinds that appear at most once. A
     * repeatable kind arriving without a key must NOT collapse onto one id,
     * because [toResolvedSections] resolves duplicate ids by dropping rows —
     * the second section would disappear from the feed entirely.
     */
    @Test
    fun repeatableKindsWithoutKeysDoNotCollapseIntoOneSection() {
        val sections = listOf(
            row("cluster", "Because you enjoy Drama", "cluster", null, "movie-a"),
            row("cluster", "Because you enjoy Comedy", "cluster", null, "movie-b"),
        ).toResolvedSections()

        assertEquals(2, sections.size, "keyless repeatable rows must both survive")
        val ids = sections.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "section ids must be unique: $ids")
    }

    /**
     * A kind this client has never heard of is treated as potentially
     * repeatable for the same reason: the client cannot know it is a singleton,
     * so identity falls back to contents rather than risking a silent drop.
     */
    @Test
    fun unrecognisedKeylessKindsFallBackToContentIdentity() {
        val sections = listOf(
            row("mood", "Rainy Sunday", "mood-of-the-day", null, "movie-a"),
            row("mood", "Late Night", "mood-of-the-day", null, "movie-b"),
        ).toResolvedSections()

        assertEquals(2, sections.size, "unknown keyless rows must both survive")
        assertEquals(2, sections.map { it.id }.toSet().size)
    }

    @Test
    fun keylessSectionIdsSurviveInsertionAndReorder() {
        val first = keylessRow(label = "Handpicked", contentId = "movie-a")
        val second = keylessRow(label = "Handpicked", contentId = "movie-b")

        val beforeRefresh = listOf(first, second).toResolvedSections()
        val afterRefresh = listOf(
            keylessRow(label = "Handpicked", contentId = "movie-new"),
            second,
            first,
        ).toResolvedSections()

        beforeRefresh.forEach { original ->
            val contentId = original.items.single().contentId
            val refreshed = afterRefresh.single { it.items.single().contentId == contentId }
            assertEquals(original.id, refreshed.id)
        }
    }

    @Test
    fun suffixLikeLegacyLabelsCannotCollideWithGeneratedIds() {
        val ids = listOf(
            keylessRow(label = "Handpicked", contentId = "movie-a"),
            keylessRow(label = "Handpicked", contentId = "movie-b"),
            keylessRow(label = "Handpicked#1", contentId = "movie-a"),
        ).toResolvedSections().map { it.id }

        assertEquals(ids.size, ids.toSet().size, "section ids must be delimiter-safe: $ids")
    }

    @Test
    fun indistinguishableLegacyRowsCollapseToOneSection() {
        val duplicate = keylessRow(label = "Handpicked", contentId = "movie-a")

        val sections = listOf(duplicate, duplicate).toResolvedSections()

        assertEquals(1, sections.size)
        assertSame(duplicate.items.single(), sections.single().items.single())
    }

    private fun row(
        type: String,
        label: String,
        sectionKind: String,
        sectionKey: String? = null,
        contentId: String,
    ) = DiscoverRow(
        type = type,
        label = label,
        sectionKind = sectionKind,
        sectionKey = sectionKey,
        items = listOf(
            SectionItem(
                contentId = contentId,
                type = "movie",
                title = contentId,
            ),
        ),
    )

    private fun keylessRow(label: String, contentId: String) = DiscoverRow(
        type = "server_row_this_client_does_not_know",
        label = label,
        items = listOf(SectionItem(contentId = contentId, type = "movie", title = contentId)),
    )
}
