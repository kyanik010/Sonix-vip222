package com.example

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Native Xtream Codes bridge used by the existing WebView application.
 *
 * Credentials are encrypted with an Android Keystore AES-GCM key. Network requests
 * are performed natively so they do not depend on file:// WebView CORS behavior.
 */
class XtreamBridge(context: Context) {
    private val appContext = context.applicationContext
    private val vault = CredentialVault(appContext)

    @JavascriptInterface
    fun login(server: String, username: String, password: String): String {
        return try {
            val normalizedServer = normalizeServer(server)
            require(username.isNotBlank()) { "أدخل اسم المستخدم" }
            require(password.isNotBlank()) { "أدخل كلمة المرور" }

            val response = request(normalizedServer, username, password, "player_api.php")
            val root = JSONObject(response)
            val userInfo = XtreamAccountValidator.validate(root, System.currentTimeMillis())

            vault.save(normalizedServer, username, password)
            JSONObject()
                .put("success", true)
                .put("user_info", userInfo)
                .put("server_info", root.optJSONObject("server_info") ?: JSONObject())
                .toString()
        } catch (error: Exception) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun restoreSession(): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("لا يوجد اشتراك محفوظ")
            val root = JSONObject(request(credentials.server, credentials.username, credentials.password, "player_api.php"))
            val userInfo = XtreamAccountValidator.validate(root, System.currentTimeMillis())
            JSONObject().put("success", true).put("user_info", userInfo)
                .put("server_info", root.optJSONObject("server_info") ?: JSONObject()).toString()
        } catch (error: Exception) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun streamUrl(type: String, streamId: String, extension: String): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            require(type in setOf("live", "movie", "series")) { "نوع البث غير مسموح" }
            require(streamId.toLongOrNull() != null) { "معرف البث غير صالح" }
            val safeExtension = extension.trim().lowercase().removePrefix(".")
            require(safeExtension.matches(Regex("[a-z0-9]{1,8}"))) { "امتداد البث غير صالح" }
            val url = "${credentials.server}/$type/${Uri.encode(credentials.username)}/${Uri.encode(credentials.password)}/${Uri.encode(streamId)}.$safeExtension"
            JSONObject().put("success", true).put("url", url).toString()
        } catch (error: Exception) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun load(action: String): String {
        return try {
            val credentials = vault.read()
                ?: throw IllegalStateException("سجّل الدخول أولاً")
            val allowed = setOf(
                "get_live_categories",
                "get_live_streams",
                "get_vod_categories",
                "get_vod_streams",
                "get_series_categories",
                "get_series"
            )
            require(action in allowed) { "طلب API غير مسموح" }
            request(credentials.server, credentials.username, credentials.password, "player_api.php", action)
        } catch (error: Exception) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun seriesInfo(seriesId: String): String {
        return requestSaved("get_series_info", "series_id" to seriesId)
    }

    @JavascriptInterface
    fun shortEpg(streamId: String): String {
        return requestSaved("get_short_epg", "stream_id" to streamId, "limit" to "10")
    }

    @JavascriptInterface
    fun vodInfo(vodId: String): String {
        return requestSaved("get_vod_info", "vod_id" to vodId)
    }

    @JavascriptInterface
    fun savedCredentials(): String {
        return try {
            val credentials = vault.read() ?: return JSONObject().put("success", false).toString()
            JSONObject()
                .put("success", true)
                .put("server", credentials.server)
                .put("username", credentials.username)
                .toString()
        } catch (error: Exception) {
            errorResult(error)
        }
    }

    @JavascriptInterface
    fun logout(): String {
        vault.clear()
        return JSONObject().put("success", true).toString()
    }

    private fun requestSaved(action: String, vararg parameters: Pair<String, String>): String {
        return try {
            val credentials = vault.read() ?: throw IllegalStateException("سجّل الدخول أولاً")
            request(
                credentials.server,
                credentials.username,
                credentials.password,
                "player_api.php",
                action,
                parameters.toMap()
            )
        } catch (error: Exception) {
            errorResult(error)
        }
    }

    private fun request(
        server: String,
        username: String,
        password: String,
        endpoint: String,
        action: String? = null,
        extra: Map<String, String> = emptyMap()
    ): String {
        val builder = Uri.parse("$server/$endpoint").buildUpon()
            .appendQueryParameter("username", username)
            .appendQueryParameter("password", password)
        if (!action.isNullOrBlank()) builder.appendQueryParameter("action", action)
        extra.forEach { (key, value) -> builder.appendQueryParameter(key, value) }

        val connection = URL(builder.build().toString()).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 25_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "SONIX-TV/1.0")
        return try {
            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (statusCode !in 200..299) {
                throw IllegalStateException("استجابة الخادم غير ناجحة (HTTP $statusCode)")
            }
            if (body.isBlank()) throw IllegalStateException("أعاد الخادم استجابة فارغة")
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun normalizeServer(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val uri = Uri.parse(trimmed)
        require((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
            "أدخل عنوان خادم صحيحاً يبدأ بـ http:// أو https://"
        }
        require(uri.query.isNullOrBlank() && uri.fragment.isNullOrBlank()) {
            "أدخل عنوان الخادم فقط دون معاملات إضافية"
        }
        return trimmed
    }

    private fun errorResult(error: Exception): String =
        JSONObject()
            .put("success", false)
            .put("error", error.message ?: "تعذر الاتصال بالخادم")
            .toString()
}

/** Small encrypted credential vault backed by an AES key held by Android Keystore. */
private class CredentialVault(context: Context) {
    private val preferences = context.getSharedPreferences("sonix_secure_account", Context.MODE_PRIVATE)

    fun save(server: String, username: String, password: String) {
        val plain = JSONObject()
            .put("server", server)
            .put("username", username)
            .put("password", password)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plain)
        preferences.edit()
            .putString("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun read(): StoredCredentials? {
        val encoded = preferences.getString("ciphertext", null) ?: return null
        val encodedIv = preferences.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateKey(),
            GCMParameterSpec(128, Base64.decode(encodedIv, Base64.NO_WRAP))
        )
        val plain = cipher.doFinal(Base64.decode(encoded, Base64.NO_WRAP))
        val json = JSONObject(String(plain, Charsets.UTF_8))
        return StoredCredentials(
            json.getString("server"),
            json.getString("username"),
            json.getString("password")
        )
    }

    fun clear() {
        preferences.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "sonix_xtream_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private data class StoredCredentials(
    val server: String,
    val username: String,
    val password: String
)
