package com.zaqizaba.rainassistant.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.zaqizaba.rainassistant.BuildConfig
import com.zaqizaba.rainassistant.model.AnswerDelay
import com.zaqizaba.rainassistant.model.RainNode
import com.zaqizaba.rainassistant.model.RainNodes

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
        get() {
            val saved = preferences.getString(storageKey(KEY_SESSION_ID, selectedNodeKey), "").orEmpty()
            if (saved.isNotBlank() || currentNode.key != RainNodes.DEFAULT_KEY) return saved
            return preferences.getString(KEY_SESSION_ID, "").orEmpty()
        }
        set(value) = preferences.edit()
            .putString(storageKey(KEY_SESSION_ID, selectedNodeKey), value.trim())
            .remove(KEY_SESSION_ID)
            .apply()

    var userName: String
        get() {
            val saved = preferences.getString(storageKey(KEY_USER_NAME, selectedNodeKey), "").orEmpty()
            if (saved.isNotBlank() || currentNode.key != RainNodes.DEFAULT_KEY) return saved
            return preferences.getString(KEY_USER_NAME, "").orEmpty()
        }
        set(value) = preferences.edit()
            .putString(storageKey(KEY_USER_NAME, selectedNodeKey), value.trim())
            .remove(KEY_USER_NAME)
            .apply()

    var selectedNodeKey: String
        get() = RainNodes.fromKey(preferences.getString(KEY_SELECTED_NODE, null)).key
        set(value) = preferences.edit()
            .putString(KEY_SELECTED_NODE, RainNodes.fromKey(value).key)
            .apply()

    val currentNode: RainNode
        get() = RainNodes.fromKey(selectedNodeKey)

    var answerDelaySeconds: Int
        get() = AnswerDelay.normalize(
            preferences.getInt(KEY_ANSWER_DELAY_SECONDS, AnswerDelay.DEFAULT_SECONDS),
        )
        set(value) = preferences.edit()
            .putInt(KEY_ANSWER_DELAY_SECONDS, AnswerDelay.normalize(value))
            .apply()

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

    fun clearLogin(nodeKey: String = selectedNodeKey) {
        val normalizedNodeKey = RainNodes.fromKey(nodeKey).key
        val editor = preferences.edit()
            .remove(storageKey(KEY_SESSION_ID, normalizedNodeKey))
            .remove(storageKey(KEY_USER_NAME, normalizedNodeKey))
        if (normalizedNodeKey == RainNodes.DEFAULT_KEY) {
            editor.remove(KEY_SESSION_ID).remove(KEY_USER_NAME)
        }
        editor.apply()
    }

    private fun storageKey(prefix: String, nodeKey: String): String = "${prefix}_$nodeKey"

    enum class ApiKeySource {
        USER,
        BUILT_IN,
        MISSING,
    }

    companion object {
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_CUSTOM_API_KEY = "custom_api_key"
        private const val KEY_SELECTED_NODE = "selected_node"
        private const val KEY_ANSWER_DELAY_SECONDS = "answer_delay_seconds"
    }
}
