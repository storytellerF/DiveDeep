package com.storyteller_f.divedeep.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class DiveDeepEngineTest {
    @Test
    fun refreshFiltersEmptyNodesAndRendersMockTranslations() {
        val rendered = mutableListOf<TranslationFrame>()
        val engine = DiveDeepEngine(
            captureDriver = ContentCaptureDriver {
                listOf(
                    ScreenTextNode("title", "开始测试", TextBounds(0, 0, 100, 40), NodeRole.Button),
                    ScreenTextNode("empty", "", TextBounds(0, 50, 100, 90)),
                    ScreenTextNode("hidden", "隐藏", TextBounds(0, 100, 100, 140), visible = false),
                )
            },
            translationService = MockTranslationService(),
            overlayRenderer = object : OverlayRenderer {
                override fun render(frame: TranslationFrame) {
                    rendered += frame
                }

                override fun clear() = Unit
            },
            targetLanguageProvider = { "en-US" },
        )

        val frame = engine.refresh()

        assertEquals(1, frame.items.size)
        assertEquals("[English] 开始测试", frame.items.single().translatedText)
        assertEquals(frame, rendered.single())
    }

    @Test
    fun refreshKeepsListItemsWithDistinctBoundsWhenViewIdsAreShared() {
        val engine = DiveDeepEngine(
            captureDriver = ContentCaptureDriver {
                listOf(
                    ScreenTextNode(
                        id = "item_title:0:0:100:40:第一项",
                        text = "第一项",
                        bounds = TextBounds(0, 0, 100, 40),
                    ),
                    ScreenTextNode(
                        id = "item_title:0:40:100:80:第二项",
                        text = "第二项",
                        bounds = TextBounds(0, 40, 100, 80),
                    ),
                    ScreenTextNode(
                        id = "item_title:0:80:100:120:第三项",
                        text = "第三项",
                        bounds = TextBounds(0, 80, 100, 120),
                    ),
                )
            },
            translationService = MockTranslationService(),
            overlayRenderer = object : OverlayRenderer {
                override fun render(frame: TranslationFrame) = Unit
                override fun clear() = Unit
            },
            targetLanguageProvider = { "en-US" },
        )

        val frame = engine.refresh()

        assertEquals(
            listOf("[English] 第一项", "[English] 第二项", "[English] 第三项"),
            frame.items.map { it.translatedText },
        )
    }
}
