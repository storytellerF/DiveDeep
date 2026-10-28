package com.storyteller_f.divedeep

import android.app.UiAutomation
import android.content.ComponentName
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Owns a case's temporary baseline. Only the host restores the pre-run device/app snapshot. */
internal class AndroidE2eEnvironment : TestRule {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val arguments = InstrumentationRegistry.getArguments()
    private val settings = AndroidE2eSettings()
    private val runId get() = arguments.getString("hostRunId").orEmpty()
    lateinit var device: UiDevice
        private set
    val title = "中文测试页面 ${UUID.randomUUID()}"

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        // JUnit4's synchronous Statement is the boundary for the suspending DataStore API.
        override fun evaluate() = runBlocking<Unit> {
            check(arguments.getString("realLlmd") == "true") { "Explicit real-model opt-in required" }
            val oldFlags = Configurator.getInstance().uiAutomationFlags
            try {
                Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
                device = UiDevice.getInstance(instrumentation)
                settings.verifyHost(runId)
                runCase(base, description.methodName)
            } finally {
                Configurator.getInstance().uiAutomationFlags = oldFlags
            }
        }
    }

    private suspend fun runCase(base: Statement, testName: String) {
        var failure: Throwable? = null
        try {
            prepare(testName)
            base.evaluate()
        } catch (error: Throwable) {
            failure = error
            try {
                saveDiagnostics(testName)
            } catch (diagnosticError: Throwable) {
                error.addSuppressed(diagnosticError)
            }
        }
        try {
            withContext(NonCancellable) {
                // Restore only the agreed per-case baseline, never the original host snapshot.
                settings.disableService()
                settings.awaitServiceStopped()
                DiveDeepState.setEnabled(context, false)
                settings.stopFixture()
            }
        } catch (cleanupError: Throwable) {
            if (failure == null) failure = cleanupError else failure.addSuppressed(cleanupError)
        }
        failure?.let { throw it }
    }

    private suspend fun prepare(testName: String) {
        val variant = arguments.getString("llmdVariant") ?: "release"
        require(variant in listOf("release", "daily", "debug"))
        val target = LlmdTarget.fromPreference(variant)
        settings.disableService()
        settings.awaitServiceStopped()
        DiveDeepState.setEnabled(context, false)
        DiveDeepState.setTranslationConfig(context, TranslationConfig(llmdTarget = target))
        DiveDeepState.setPackageBlocked(context, FIXTURE_PACKAGE, false)
        authorizeLlmd(target, testName)
        settings.stopFixture()
        context.startActivity(Intent().apply {
            component = ComponentName(FIXTURE_PACKAGE, "$FIXTURE_PACKAGE.MainActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("test_title", title)
        })
        requireObject(By.res(FIXTURE_PACKAGE, "title_text"))
        DiveDeepState.setEnabled(context, true)
        settings.enableService()
    }

    private fun authorizeLlmd(target: LlmdTarget, testName: String) {
        context.startActivity(Intent("com.storytellerf.llmd.action.AUTHORIZE_CALLER").apply {
            component = ComponentName(target.packageName, "com.storytellerf.llmd.LlmdIpcAuthorizationActivity")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("caller_package", context.packageName)
        })
        check(requireObject(By.res(target.packageName, "ipc_authorization_package")).text == context.packageName)
        val selector = By.res(target.packageName, "ipc_authorization_allow")
        val allow = requireObject(selector)
        check(allow.isEnabled)
        device.takeScreenshot(File(artifactDirectory(testName), "authorization.png"))
        allow.click()
        check(device.wait(Until.gone(selector), UI_TIMEOUT)) { "LLMD authorization page did not close" }
    }

    fun requireObject(selector: BySelector, timeout: Long = UI_TIMEOUT): UiObject2 =
        checkNotNull(device.wait(Until.findObject(selector), timeout)) { "UI element missing: $selector" }

    fun translatedTitle(): UiObject2 = requireObject(By.desc("$title 已翻译"), MODEL_TIMEOUT)

    fun disableService() = settings.disableService()

    fun hasOverlayWindow(): Boolean = instrumentation
        .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        .windows.any { window ->
            window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                window.root?.packageName == context.packageName
        }

    private fun artifactDirectory(testName: String) =
        File(context.getExternalFilesDir(null), "e2e/$runId/$testName").apply { mkdirs() }

    private fun saveDiagnostics(testName: String) {
        val directory = artifactDirectory(testName)
        device.takeScreenshot(File(directory, "screen.png"))
        device.dumpWindowHierarchy(File(directory, "windows.xml"))
        File(directory, "overlay.log").writeText(settings.shell("logcat -d -t 1000 -s DiveDeepOverlay DiveDeepAccessibility"))
        File(directory, "device.txt").writeText(settings.shell("getprop ro.build.version.sdk"))
    }

    companion object {
        const val FIXTURE_PACKAGE = "com.storyteller_f.divedeep.fixture"
        const val UI_TIMEOUT = 15_000L
        const val MODEL_TIMEOUT = 180_000L
    }
}
