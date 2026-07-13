package com.storyteller_f.divedeep

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

object DiveDeepState {
    const val DEFAULT_API_BASE_URL = "http://127.0.0.1:11435"
    const val DEFAULT_MODEL = "gemma-4-E2B-it"

    private const val PREFS = "dive_deep_state"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BLOCKED_PACKAGES = "blocked_packages"
    private const val KEY_BACKEND = "backend"
    private const val KEY_API_BASE_URL = "api_base_url"
    private const val KEY_MODEL = "model"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_USE_MOCK_TRANSLATION = "use_mock_translation"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun toggle(context: Context): Boolean {
        val enabled = !isEnabled(context)
        setEnabled(context, enabled)
        return enabled
    }

    fun getBlockedPackages(context: Context): Set<String> {
        ensureDefaultBlockedPackages(context)
        return prefs(context).getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
    }

    fun setPackageBlocked(context: Context, packageName: String, blocked: Boolean) {
        ensureDefaultBlockedPackages(context)
        val blockedPackages = getBlockedPackages(context).toMutableSet()
        if (blocked) {
            blockedPackages += packageName
        } else {
            blockedPackages -= packageName
        }
        prefs(context).edit().putStringSet(KEY_BLOCKED_PACKAGES, blockedPackages).apply()
    }

    fun isPackageAllowed(context: Context, packageName: CharSequence?): Boolean {
        val normalized = packageName?.toString().orEmpty()
        if (normalized.isBlank()) return true
        return normalized !in getBlockedPackages(context)
    }

    fun getTranslationConfig(context: Context): TranslationConfig {
        val sharedPreferences = prefs(context)
        return TranslationConfig(
            backend = TranslationBackend.fromPreference(
                sharedPreferences.getString(KEY_BACKEND, null),
            ),
            apiBaseUrl = sharedPreferences.getString(KEY_API_BASE_URL, DEFAULT_API_BASE_URL)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_API_BASE_URL,
            model = sharedPreferences.getString(KEY_MODEL, DEFAULT_MODEL)
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_MODEL,
            apiKey = sharedPreferences.getString(KEY_API_KEY, null).orEmpty(),
            useMockTranslation = sharedPreferences.getBoolean(KEY_USE_MOCK_TRANSLATION, false),
        )
    }

    fun setTranslationConfig(context: Context, config: TranslationConfig) {
        prefs(context).edit()
            .putString(KEY_BACKEND, config.backend.preferenceValue)
            .putString(KEY_API_BASE_URL, config.apiBaseUrl.ifBlank { DEFAULT_API_BASE_URL })
            .putString(KEY_MODEL, config.model.ifBlank { DEFAULT_MODEL })
            .putString(KEY_API_KEY, config.apiKey)
            .putBoolean(KEY_USE_MOCK_TRANSLATION, config.useMockTranslation)
            .apply()
    }

    fun register(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregister(context: Context, listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun ensureDefaultBlockedPackages(context: Context) {
        val sharedPreferences = prefs(context)
        if (sharedPreferences.contains(KEY_BLOCKED_PACKAGES)) return

        val launcherPackages = context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )
            .map { it.activityInfo.packageName }
            .filter { it.isNotBlank() }
            .toSet()
        sharedPreferences.edit()
            .putStringSet(KEY_BLOCKED_PACKAGES, launcherPackages)
            .apply()
    }
}

enum class TranslationBackend(val preferenceValue: String) {
    LocalLlmdIpc("local_llmd_ipc"),
    OpenAiHttp("openai_http");

    companion object {
        fun fromPreference(value: String?): TranslationBackend =
            entries.firstOrNull { it.preferenceValue == value } ?: LocalLlmdIpc
    }
}

data class TranslationConfig(
    val backend: TranslationBackend = TranslationBackend.LocalLlmdIpc,
    val apiBaseUrl: String = DiveDeepState.DEFAULT_API_BASE_URL,
    val model: String = DiveDeepState.DEFAULT_MODEL,
    val apiKey: String = "",
    val useMockTranslation: Boolean = false,
)
