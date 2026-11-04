package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamAccountValidatorTest {
    // These response-field fixtures are isolated to local unit tests.
    private data class ApiFixture(val auth: Int, val status: String, val expiration: String)
    private fun fixture(name: String): ApiFixture = when (name) {
        "active" -> ApiFixture(1, "Active", "1893456000")
        "expired" -> ApiFixture(1, "Expired", "1609459200")
        "unauthorized" -> ApiFixture(0, "Disabled", "1893456000")
        else -> error("Unknown fixture: $name")
    }

    @Test
    fun acceptsActiveSubscriptionResponse() {
        val response = fixture("active")
        XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        assertEquals("Active", response.status)
    }

    @Test
    fun rejectsExpiredSubscriptionResponse() {
        val response = fixture("expired")
        val error = assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        }
        assertEquals("الاشتراك منتهي أو موقوف: Expired", error.message)
    }

    @Test
    fun rejectsUnauthorizedResponse() {
        val response = fixture("unauthorized")
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validateFields(response.auth, response.status, response.expiration, 1_700_000_000_000L)
        }
    }
}
