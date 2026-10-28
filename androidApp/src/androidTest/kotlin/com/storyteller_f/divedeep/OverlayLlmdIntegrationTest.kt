package com.storyteller_f.divedeep

import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real cross-app and model tests; excluded unless the instrumentation argument realLlmd=true. */
@RealLlmdIntegration
class OverlayLlmdIntegrationTest {
    @get:Rule
    internal val environment = AndroidE2eEnvironment()

    @Test
    fun translatedOverlayIsVisibleOnFixture() {
        val button = environment.translatedTitle()
        assertEquals("已翻译", button.text)
        assertTrue(environment.hasOverlayWindow())
    }

    @Test
    fun underlyingPageRespondsWhileOverlayIsVisible() {
        environment.translatedTitle()
        repeat(2) { index ->
            environment.requireObject(By.res(AndroidE2eEnvironment.FIXTURE_PACKAGE, "primary_button")).click()
            environment.requireObject(By.res(AndroidE2eEnvironment.FIXTURE_PACKAGE, "primary_button")
                .text("已点击 ${index + 1} 次"))
            assertTrue("Overlay must remain visible during the underlying interaction", environment.hasOverlayWindow())
        }
    }

    @Test
    fun tappingTranslationOpensActualSourceAndResult() {
        environment.translatedTitle().click()
        val source = environment.requireObject(By.res(APP_PACKAGE, "translation_source"))
        assertEquals(environment.title, source.text)
        val result = environment.requireObject(By.res(APP_PACKAGE, "translation_result")).text
        assertTrue("Translation result must contain text", result.isNotBlank())
        assertFalse("A loading indicator is not a translation", result == "翻译中")
        assertFalse("Mock translation must not satisfy the real-model test", result.contains("[English]"))
    }

    @Test
    fun destroyingServiceRemovesButtonsAndOpenDetails() {
        environment.translatedTitle().click()
        environment.requireObject(By.res(APP_PACKAGE, "translation_source"))
        assertTrue(environment.hasOverlayWindow())
        environment.disableService()
        // JUnit4's test body is a synchronous boundary; poll real lifecycle completion without Thread.sleep.
        runBlocking {
            withTimeout(AndroidE2eEnvironment.UI_TIMEOUT) {
                while (environment.hasOverlayWindow()) delay(POLL_INTERVAL)
            }
        }
        assertTrue(environment.device.wait(Until.gone(By.res(APP_PACKAGE, "translation_sheet")), AndroidE2eEnvironment.UI_TIMEOUT))
        assertFalse(environment.device.hasObject(By.desc("${environment.title} 已翻译")))
    }

    private companion object {
        const val APP_PACKAGE = "com.storyteller_f.divedeep"
        const val POLL_INTERVAL = 200L
    }
}
