package org.siloserver.silo.network

import kotlin.test.Test
import kotlin.test.assertEquals

class ApiResultErrorMessageTest {

    @Test
    fun `error uses server message when present`() {
        assertEquals(
            "boom",
            ApiResult.Error(code = 500, error = "internal", message = "boom").errorMessage("fallback"),
        )
    }

    @Test
    fun `error falls back when server message is blank`() {
        assertEquals(
            "fallback",
            ApiResult.Error(code = 500, error = "internal", message = "  ").errorMessage("fallback"),
        )
    }

    @Test
    fun `network error always uses the standard copy`() {
        assertEquals(
            "Network error. Check your connection.",
            ApiResult.NetworkError(IllegalStateException("offline")).errorMessage("fallback"),
        )
    }
}
