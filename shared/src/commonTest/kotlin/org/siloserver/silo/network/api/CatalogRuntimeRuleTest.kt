package org.siloserver.silo.network.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogQueryRule
import kotlin.test.Test
import kotlin.test.assertEquals

class CatalogRuntimeRuleTest {
    @Test fun runtimeBoundsAreSentAsNumbersAndOtherFieldsAsText() {
        val groups = listOf(CatalogQueryGroup("any", listOf(
            CatalogQueryRule("runtime", "lt", "30"),
            CatalogQueryRule("runtime", "between", "", listOf("60", "120")),
            CatalogQueryRule("year", "between", "", listOf("1990", "1999")),
        ))).toV2Groups()
        val rules = groups.single().rules
        assertEquals(JsonPrimitive(30L), rules[0].value)
        assertEquals(JsonArray(listOf(JsonPrimitive(60L), JsonPrimitive(120L))), rules[1].value)
        assertEquals(JsonArray(listOf(JsonPrimitive("1990"), JsonPrimitive("1999"))), rules[2].value)
    }
}
