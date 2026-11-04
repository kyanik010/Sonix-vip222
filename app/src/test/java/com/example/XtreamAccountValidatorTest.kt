package com.example

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamAccountValidatorTest {
    // Fixture payloads are test-only and never packaged into the application.
    private fun fixture(name: String): JSONObject = when (name) {
        "active" -> JSONObject("""{"user_info":{"auth":1,"status":"Active","username":"fixture_user","exp_date":"1893456000"}}""")
        "expired" -> JSONObject("""{"user_info":{"auth":1,"status":"Expired","username":"fixture_user","exp_date":"1609459200"}}""")
        "unauthorized" -> JSONObject("""{"user_info":{"auth":0,"status":"Disabled","username":"fixture_user","exp_date":"1893456000"}}""")
        else -> error("Unknown fixture: $name")
    }

    @Test
    fun acceptsActiveSubscriptionResponse() {
        val user = XtreamAccountValidator.validate(fixture("active"), 1_700_000_000_000L)
        assertEquals("fixture_user", user.getString("username"))
        assertEquals("Active", user.getString("status"))
    }

    @Test
    fun rejectsExpiredSubscriptionResponse() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validate(fixture("expired"), 1_700_000_000_000L)
        }
        assertEquals("الاشتراك منتهي أو موقوف: Expired", error.message)
    }

    @Test
    fun rejectsUnauthorizedResponse() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validate(fixture("unauthorized"), 1_700_000_000_000L)
        }
    }
}
