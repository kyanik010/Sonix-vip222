package com.example

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamAccountValidatorTest {
    private fun fixture(name: String): JSONObject {
        val stream = javaClass.classLoader!!.getResourceAsStream("xtream/$name")!!
        return JSONObject(stream.bufferedReader().use { it.readText() })
    }

    @Test
    fun acceptsActiveSubscriptionResponse() {
        val user = XtreamAccountValidator.validate(fixture("active.json"), 1_700_000_000_000L)
        assertEquals("fixture_user", user.getString("username"))
        assertEquals("Active", user.getString("status"))
    }

    @Test
    fun rejectsExpiredSubscriptionResponse() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validate(fixture("expired.json"), 1_700_000_000_000L)
        }
        assertEquals("الاشتراك منتهي أو موقوف: Expired", error.message)
    }

    @Test
    fun rejectsUnauthorizedResponse() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamAccountValidator.validate(fixture("unauthorized.json"), 1_700_000_000_000L)
        }
    }
}
