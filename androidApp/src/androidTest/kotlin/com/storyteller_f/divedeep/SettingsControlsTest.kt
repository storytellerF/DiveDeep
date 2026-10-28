package com.storyteller_f.divedeep

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class SettingsControlsTest(private val target: LlmdTarget) {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun selectedLlmdVariantIsSavedWithLocalBackend() {
        var saved: TranslationConfig? = null
        compose.setContent {
            DiveDeepSettingsScreen(
                enabled = false,
                config = TranslationConfig(backend = TranslationBackend.OpenAiHttp),
                blockedPackages = emptySet(),
                appEntries = emptyList(),
                llmdAuthorizationMessage = "",
                onToggle = {},
                onOpenAccessibilitySettings = {},
                onSaveConfig = { saved = it },
                onAuthorizeLlmd = {},
                onPackageBlockedChange = { _, _ -> },
            )
        }
        selectRadio("本机 llmd IPC")
        selectRadio(target.displayName)
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("保存翻译配置"))
        compose.onNodeWithText("保存翻译配置").performClick()
        compose.runOnIdle {
            assertEquals(TranslationBackend.LocalLlmdIpc, saved?.backend)
            assertEquals(target, saved?.llmdTarget)
        }
    }

    @Test
    fun toggleEnablesFromDisabledState() = verifyToggle(initialEnabled = false)

    @Test
    fun toggleDisablesFromEnabledState() = verifyToggle(initialEnabled = true)

    private fun verifyToggle(initialEnabled: Boolean) {
        val enabled = mutableStateOf(initialEnabled)
        compose.setContent {
            DiveDeepSettingsScreen(
                enabled = enabled.value,
                config = TranslationConfig(llmdTarget = target),
                blockedPackages = emptySet(),
                appEntries = emptyList(),
                llmdAuthorizationMessage = "",
                onToggle = { enabled.value = !enabled.value },
                onOpenAccessibilitySettings = {},
                onSaveConfig = {},
                onAuthorizeLlmd = {},
                onPackageBlockedChange = { _, _ -> },
            )
        }
        compose.onNodeWithText(if (initialEnabled) "停止翻译" else "开始翻译").performClick()
        compose.onNodeWithText(if (initialEnabled) "开始翻译" else "停止翻译").assertExists()
        compose.runOnIdle { assertEquals(!initialEnabled, enabled.value) }
    }

    private fun selectRadio(label: String) {
        compose.onNode(isSelectable() and hasText(label)).performScrollTo().performClick()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun targets(): List<Array<LlmdTarget>> = LlmdTarget.entries.map { arrayOf(it) }
    }
}
