package com.storyteller_f.divedeep.shared

class DiveDeepEngine(
    private val captureDriver: ContentCaptureDriver,
    private val translationService: TranslationService,
    private val overlayRenderer: OverlayRenderer,
    private val targetLanguageProvider: () -> String,
) {
    fun refresh(shouldContinue: () -> Boolean = { true }): TranslationFrame {
        val nodes = captureDriver.captureVisibleText()
            .filter { it.visible && it.text.isNotBlank() && !it.bounds.isEmpty }
            .distinctBy { it.id }

        val targetLanguage = targetLanguageProvider()
        val translated = mutableListOf<TranslationItem>()
        var frame = TranslationFrame(targetLanguage, emptyList())
        overlayRenderer.render(frame)

        nodes.forEach { node ->
            if (!shouldContinue()) return frame

            val nodeTranslation = translationService.translate(
                TranslationRequest(
                    targetLanguage = targetLanguage,
                    items = listOf(node),
                ),
            )
            if (!shouldContinue()) return frame

            translated += nodeTranslation
            frame = TranslationFrame(targetLanguage, translated.toList())
            overlayRenderer.render(frame)
        }
        return frame
    }

    fun stop() {
        overlayRenderer.clear()
    }
}
