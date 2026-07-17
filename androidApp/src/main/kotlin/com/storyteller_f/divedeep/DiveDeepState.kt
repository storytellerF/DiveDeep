package com.storyteller_f.divedeep

import android.content.Context
import android.content.Intent
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.diveDeepDataStore by preferencesDataStore(name = "dive_deep_state")

object DiveDeepState {
    const val DEFAULT_API_BASE_URL = "http://127.0.0.1:11435"
    const val DEFAULT_MODEL = "gemma-4-E2B-it"

    private val enabledKey = booleanPreferencesKey("enabled")
    private val blockedPackagesKey = stringSetPreferencesKey("blocked_packages")
    private val backendKey = stringPreferencesKey("backend")
    private val apiBaseUrlKey = stringPreferencesKey("api_base_url")
    private val modelKey = stringPreferencesKey("model")
    private val apiKeyKey = stringPreferencesKey("api_key")
    private val useMockTranslationKey = booleanPreferencesKey("use_mock_translation")

    fun settingsFlow(context: Context): Flow<DiveDeepSettings> =
        context.applicationContext.diveDeepDataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences -> preferences.toSettings() }

    suspend fun initialize(context: Context) {
        val appContext = context.applicationContext
        appContext.diveDeepDataStore.edit { preferences ->
            if (!preferences.contains(blockedPackagesKey)) {
                preferences[blockedPackagesKey] = defaultBlockedPackages(appContext)
            }
        }
    }

    suspend fun snapshot(context: Context): DiveDeepSettings {
        initialize(context)
        return settingsFlow(context).first()
    }

    suspend fun isEnabled(context: Context): Boolean =
        snapshot(context).enabled

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.diveDeepDataStore.edit { preferences ->
            preferences[enabledKey] = enabled
        }
    }

    suspend fun toggle(context: Context): Boolean {
        var enabled = false
        context.applicationContext.diveDeepDataStore.edit { preferences ->
            enabled = !(preferences[enabledKey] ?: false)
            preferences[enabledKey] = enabled
        }
        return enabled
    }

    suspend fun setPackageBlocked(context: Context, packageName: String, blocked: Boolean) {
        initialize(context)
        context.applicationContext.diveDeepDataStore.edit { preferences ->
            val blockedPackages = preferences[blockedPackagesKey].orEmpty().toMutableSet()
            if (blocked) {
                blockedPackages += packageName
            } else {
                blockedPackages -= packageName
            }
            preferences[blockedPackagesKey] = blockedPackages
        }
    }

    fun isPackageAllowed(settings: DiveDeepSettings, packageName: CharSequence?): Boolean {
        val normalized = packageName?.toString().orEmpty()
        if (normalized.isBlank()) return true
        return normalized !in settings.blockedPackages
    }

    suspend fun setTranslationConfig(context: Context, config: TranslationConfig) {
        context.applicationContext.diveDeepDataStore.edit { preferences ->
            preferences[backendKey] = config.backend.preferenceValue
            preferences[apiBaseUrlKey] = config.apiBaseUrl.ifBlank { DEFAULT_API_BASE_URL }
            preferences[modelKey] = config.model.ifBlank { DEFAULT_MODEL }
            preferences[apiKeyKey] = config.apiKey
            preferences[useMockTranslationKey] = config.useMockTranslation
        }
    }

    private fun Preferences.toSettings(): DiveDeepSettings =
        DiveDeepSettings(
            enabled = this[enabledKey] ?: false,
            blockedPackages = this[blockedPackagesKey].orEmpty(),
            translationConfig = TranslationConfig(
                backend = TranslationBackend.fromPreference(this[backendKey]),
                apiBaseUrl = this[apiBaseUrlKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_API_BASE_URL,
                model = this[modelKey]?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL,
                apiKey = this[apiKeyKey].orEmpty(),
                useMockTranslation = this[useMockTranslationKey] ?: false,
            ),
        )

    private fun defaultBlockedPackages(context: Context): Set<String> =
        context.packageManager
            .queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                0,
            )
            .map { it.activityInfo.packageName }
            .filter { it.isNotBlank() }
            .toSet()
}

enum class TranslationBackend(val preferenceValue: String) {
    LocalLlmdIpc("local_llmd_ipc"),
    OpenAiHttp("openai_http");

    companion object {
        fun fromPreference(value: String?): TranslationBackend =
            entries.firstOrNull { it.preferenceValue == value } ?: LocalLlmdIpc
    }
}

data class DiveDeepSettings(
    val enabled: Boolean = false,
    val blockedPackages: Set<String> = emptySet(),
    val translationConfig: TranslationConfig = TranslationConfig(),
)

data class TranslationConfig(
    val backend: TranslationBackend = TranslationBackend.LocalLlmdIpc,
    val apiBaseUrl: String = DiveDeepState.DEFAULT_API_BASE_URL,
    val model: String = DiveDeepState.DEFAULT_MODEL,
    val apiKey: String = "",
    val useMockTranslation: Boolean = false,
)
