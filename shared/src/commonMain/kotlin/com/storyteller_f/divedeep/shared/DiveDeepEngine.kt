package com.storyteller_f.divedeep.shared

class DiveDeepEngine(
    private val captureDriver: ContentCaptureDriver,
    private val translationService: TranslationService,
    private val overlayRenderer: OverlayRenderer,
    private val targetLanguageProvider: () -> String,
) {
    fun refresh(): TranslationFrame {
        val nodes = captureDriver.captureVisibleText()
            .filter { it.visible && it.text.isNotBlank() && !it.bounds.isEmpty }
            .distinctBy { it.id }

        val targetLanguage = targetLanguageProvider()
        val translated = translationService.translate(
            TranslationRequest(
                targetLanguage = targetLanguage,
                items = nodes,
            ),
        )
        val frame = TranslationFrame(targetLanguage, translated)
        overlayRenderer.render(frame)
        return frame
    }

    fun stop() {
        overlayRenderer.clear()
    }
}
