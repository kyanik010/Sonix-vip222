package com.example

import org.json.JSONObject

/**
 * Validates the account section returned by player_api.php.
 * Kept independent from networking so test fixtures can exercise real response shapes.
 */
object XtreamAccountValidator {
    fun validate(root: JSONObject, nowMillis: Long): JSONObject {
        val userInfo = root.optJSONObject("user_info")
            ?: throw IllegalArgumentException("استجابة الخادم لا تحتوي على معلومات الحساب")

        if (userInfo.optInt("auth", 0) != 1) {
            throw IllegalArgumentException("بيانات الدخول غير صحيحة أو الاشتراك غير مصرح به")
        }

        val status = userInfo.optString("status", "").trim().lowercase()
        if (status in setOf("expired", "disabled", "banned", "inactive")) {
            throw IllegalArgumentException("الاشتراك منتهي أو موقوف: ${userInfo.optString("status")}")
        }

        val expiration = userInfo.optString("exp_date", "")
        if (expiration.isNotBlank() && expiration != "null" && expiration != "0") {
            val expirySeconds = expiration.toLongOrNull()
            if (expirySeconds != null && expirySeconds > 0 && expirySeconds * 1000L <= nowMillis) {
                throw IllegalArgumentException("انتهت صلاحية الاشتراك")
            }
        }
        return userInfo
    }
}
