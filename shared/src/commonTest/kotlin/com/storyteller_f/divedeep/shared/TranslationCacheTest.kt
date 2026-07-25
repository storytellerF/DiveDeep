package com.storyteller_f.divedeep.shared

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TranslationCacheTest {
    @Test
    fun md5HexMatchesKnownDigests() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", md5Hex("abc"))
    }

    @Test
    fun cachingTranslationServiceReusesCachedTextForCurrentNode() = runTest {
        val cache = InMemoryTranslationCache()
        var delegateCalls = 0
        val service = CachingTranslationService(
            delegate = TranslationService { request ->
                delegateCalls += 1
                request.items.map { node ->
                    TranslationItem(
                        nodeId = node.id,
                        sourceText = node.text,
                        translatedText = "translated:${node.text}",
                        bounds = node.bounds,
                    )
                }
            },
            cache = cache,
        )
        val firstRequest = TranslationRequest(
            targetLanguage = "en-US",
            items = listOf(ScreenTextNode("first", "标题", TextBounds(0, 0, 10, 10))),
        )
        val secondRequest = TranslationRequest(
            targetLanguage = "en-US",
            items = listOf(ScreenTextNode("second", "标题", TextBounds(10, 10, 20, 20))),
        )

        service.translate(firstRequest)
        val secondResult = service.translate(secondRequest)

        assertEquals(1, delegateCalls)
        assertEquals("second", secondResult.single().nodeId)
        assertEquals(TextBounds(10, 10, 20, 20), secondResult.single().bounds)
        assertEquals("translated:标题", secondResult.single().translatedText)
    }
}

private class InMemoryTranslationCache : TranslationCache {
    private val translations = mutableMapOf<String, String>()

    override suspend fun findTranslations(request: TranslationRequest): Map<String, String> =
        request.items
            .map { node -> translationCacheKey(request.sourceLanguage, request.targetLanguage, node.text) }
            .associateWith { key -> translations[key] }
            .filterValues { it != null }
            .mapValues { requireNotNull(it.value) }

    override suspend fun saveTranslations(request: TranslationRequest, translations: List<TranslationItem>) {
        translations.forEach { item ->
            this.translations[translationCacheKey(request.sourceLanguage, request.targetLanguage, item.sourceText)] =
                item.translatedText
        }
    }
}
