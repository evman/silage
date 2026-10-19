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

    @Test fun aCollectionTheStrictModelRejectsDoesNotHideTheOthers() {
        val json = org.siloserver.silo.network.SiloJson.parseToJsonElement("""
            {"items":[
              {"id":"c1","name":"Bond","item_count":25,"poster_url":"/p/1.jpg","group_id":"g1","sort_order":2},
              {"id":42,"name":"Smart","query_definition":"rules as a string","sort_config":[1,2],"allowed_profile_ids":null},
              {"id":"c3"},
              {"name":"No id"}
            ],
            "groups":[{"id":"g1","name":"Sagas","sort_order":1},{"name":"broken"}]}
        """)
        val (collections, groups) = parseCollections(json)
        assertEquals(listOf("c1", "42"), collections.map { it.id })
        assertEquals(25, collections[0].itemCount)
        assertEquals("g1", collections[0].groupId)
        assertEquals(listOf("Sagas"), groups.map { it.name })
    }

    @Test fun aBareListIsAcceptedToo() {
        val json = org.siloserver.silo.network.SiloJson.parseToJsonElement("""[{"id":"a","name":"A"}]""")
        assertEquals(listOf("a"), parseCollections(json).first.map { it.id })
    }
}
