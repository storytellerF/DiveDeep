package com.storyteller_f.divedeep

import android.content.Context
import com.storyteller_f.divedeep.shared.TranslationItem
import com.storyteller_f.divedeep.shared.TranslationRequest
import com.storyteller_f.divedeep.shared.TranslationService

class ConfiguredTranslationService(
    context: Context,
    private val configProvider: () -> TranslationConfig,
) : TranslationService {
    private val httpService = OpenAiTranslationService(configProvider)
    private val ipcService = LlmdIpcTranslationService(context, configProvider)

    override fun translate(request: TranslationRequest): List<TranslationItem> =
        when (configProvider().backend) {
            TranslationBackend.LocalLlmdIpc -> ipcService.translate(request)
            TranslationBackend.OpenAiHttp -> httpService.translate(request)
        }

    fun close() {
        ipcService.close()
    }
}
