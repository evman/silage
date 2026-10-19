package dev.silage.app.browse

import org.siloserver.silo.model.personal.Collection as SiloCollection
import org.siloserver.silo.model.personal.CollectionGroup
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectionsScreenTest {
    private fun c(id: String, name: String, group: String? = null, order: Int = 0, items: Int? = null, poster: String? = null) =
        SiloCollection(id = id, name = name, groupId = group, sortOrder = order, itemCount = items, posterUrl = poster)

    @Test fun orderedByGroupThenPlaceThenName() {
        val groups = listOf(CollectionGroup("g2", "Studios", sortOrder = 2), CollectionGroup("g1", "Sagas", sortOrder = 1))
        val all = listOf(c("a", "Zeta", "g2"), c("b", "alpha"), c("c", "Marvel", "g1", order = 1), c("d", "Bond", "g1", order = 0))
        assertEquals(listOf("d", "c", "a", "b"), orderedCollections(all, groups, null).map { it.id })
        assertEquals(listOf("d", "c"), orderedCollections(all, groups, "g1").map { it.id })
    }

    @Test fun cardsOpenTheCollectionWithResolvedArtwork() {
        val card = c("x", "Bond", items = 25, poster = "/api/v2/images/1.jpg").toCard("https://silo.example")
        assertEquals("x", card.openTarget)
        assertEquals("25 items", card.caption)
        assertEquals("https://silo.example/api/v2/images/1.jpg", card.imageUrl)
        assertEquals("1 item", c("y", "One", items = 1).toCard(null).caption)
    }
}
