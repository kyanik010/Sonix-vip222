package com.example

import org.json.JSONObject

/**
 * Validates the account section returned by player_api.php.
 * Field-level validation is pure Kotlin so JVM tests do not depend on Android JSON stubs.
 */
object XtreamAccountValidator {
    fun validate(root: JSONObject, nowMillis: Long): JSONObject {
        val userInfo = root.optJSONObject("user_info")
            ?: throw IllegalArgumentException("استجابة الخادم لا تحتوي على معلومات الحساب")
        validateFields(
            userInfo.optInt("auth", 0),
            userInfo.optString("status", ""),
            userInfo.optString("exp_date", ""),
            nowMillis
        )
        return userInfo
    }

    fun validateFields(auth: Int, rawStatus: String, expiration: String, nowMillis: Long) {
        if (auth != 1) {
            throw IllegalArgumentException("بيانات الدخول غير صحيحة أو الاشتراك غير مصرح به")
        }
        val status = rawStatus.trim().lowercase()
        if (status in setOf("expired", "disabled", "banned", "inactive")) {
            throw IllegalArgumentException("الاشتراك منتهي أو موقوف: $rawStatus")
        }
        if (expiration.isNotBlank() && expiration != "null" && expiration != "0") {
            val expirySeconds = expiration.toLongOrNull()
            if (expirySeconds != null && expirySeconds > 0 && expirySeconds * 1000L <= nowMillis) {
                throw IllegalArgumentException("انتهت صلاحية الاشتراك")
            }
        }
    }
}
