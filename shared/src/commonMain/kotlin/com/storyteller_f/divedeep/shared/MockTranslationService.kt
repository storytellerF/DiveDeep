package com.storyteller_f.divedeep.shared

class MockTranslationService : TranslationService {
    override fun translate(request: TranslationRequest): List<TranslationItem> {
        val languageName = languageName(request.targetLanguage)
        return request.items.map { node ->
            TranslationItem(
                nodeId = node.id,
                sourceText = node.text,
                translatedText = "[$languageName] ${node.text}",
                bounds = node.bounds,
            )
        }
    }

    private fun languageName(languageTag: String): String {
        val normalized = languageTag.lowercase()
        return when {
            normalized.startsWith("en") -> "English"
            normalized.startsWith("ja") -> "Japanese"
            normalized.startsWith("ko") -> "Korean"
            normalized.startsWith("fr") -> "French"
            normalized.startsWith("de") -> "German"
            normalized.startsWith("es") -> "Spanish"
            else -> languageTag.ifBlank { "System" }
        }
    }
}
