package com.zaqizaba.rainassistant.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zaqizaba.rainassistant.BuildConfig

class SecureStore(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "rain_assistant_private",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    var sessionId: String
        get() = preferences.getString(KEY_SESSION_ID, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_SESSION_ID, value.trim()).apply()

    var userName: String
        get() = preferences.getString(KEY_USER_NAME, "").orEmpty()
        set(value) = preferences.edit().putString(KEY_USER_NAME, value.trim()).apply()

    val customApiKey: String
        get() = preferences.getString(KEY_CUSTOM_API_KEY, "").orEmpty()

    val effectiveApiKey: String
        get() = customApiKey.ifBlank { BuildConfig.DEFAULT_DEEPSEEK_API_KEY }

    val apiKeySource: ApiKeySource
        get() = when {
            customApiKey.isNotBlank() -> ApiKeySource.USER
            BuildConfig.DEFAULT_DEEPSEEK_API_KEY.isNotBlank() -> ApiKeySource.BUILT_IN
            else -> ApiKeySource.MISSING
        }

    fun saveCustomApiKey(value: String) {
        preferences.edit().putString(KEY_CUSTOM_API_KEY, value.trim()).apply()
    }

    fun clearCustomApiKey() {
        preferences.edit().remove(KEY_CUSTOM_API_KEY).apply()
    }

    fun clearLogin() {
        preferences.edit().remove(KEY_SESSION_ID).remove(KEY_USER_NAME).apply()
    }

    enum class ApiKeySource {
        USER,
        BUILT_IN,
        MISSING,
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
    }
}

