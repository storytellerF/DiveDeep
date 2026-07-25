package com.storyteller_f.divedeep

import android.content.Context
import com.storyteller_f.divedeep.shared.CachingTranslationService
import com.storyteller_f.divedeep.shared.RoomTranslationCache
import com.storyteller_f.divedeep.shared.TranslationItem
import com.storyteller_f.divedeep.shared.TranslationRequest
import com.storyteller_f.divedeep.shared.TranslationService
import com.storyteller_f.divedeep.shared.buildTranslationCacheDatabase
import com.storyteller_f.divedeep.shared.getTranslationCacheDatabaseBuilder

class ConfiguredTranslationService(
    context: Context,
    private val configProvider: () -> TranslationConfig,
) : TranslationService {
    private val translationCacheDatabase = buildTranslationCacheDatabase(
        getTranslationCacheDatabaseBuilder(context),
    )
    private val httpService = OpenAiTranslationService(configProvider)
    private val ipcService = LlmdIpcTranslationService(context, configProvider)
    private val backendService = TranslationService { request ->
        when (configProvider().backend) {
            TranslationBackend.LocalLlmdIpc -> ipcService.translate(request)
            TranslationBackend.OpenAiHttp -> httpService.translate(request)
        }
    }
    private val cachingService = CachingTranslationService(
        delegate = backendService,
        cache = RoomTranslationCache(translationCacheDatabase.translationCacheDao()),
        cacheEnabled = { !configProvider().useMockTranslation },
    )

    override suspend fun translate(request: TranslationRequest): List<TranslationItem> =
        cachingService.translate(request)

    fun close() {
        ipcService.close()
        translationCacheDatabase.close()
    }
}
