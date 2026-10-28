package com.storyteller_f.divedeep

import android.app.ActivityManager
import android.app.UiAutomation
import android.content.ComponentName
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** Per-case operations only. The host owns the pre-run snapshot and its restoration. */
internal class AndroidE2eSettings(
    private val readSetting: (String) -> String? = AndroidSecureSettings()::read,
    private val executeShell: (String) -> String = ::executeShellCommand,
) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val service = ComponentName(context, DiveDeepAccessibilityService::class.java)

    fun verifyHost(runId: String) {
        require(runId.matches(Regex("[0-9a-f]{32}"))) { "Run through scripts/android_e2e.py" }
        check(shell("cat /data/local/tmp/divedeep-e2e.run").trim() == runId) { "Host run ownership does not match" }
    }

    fun disableService() {
        val current = serviceNames()
        val remaining = current.filterNot { ComponentName.unflattenFromString(it) == service }
        if (remaining != current) writeServices(remaining)
    }

    fun enableService() {
        val current = serviceNames()
        if (current.none { ComponentName.unflattenFromString(it) == service }) {
            writeServices(current + service.flattenToString())
        }
        shell("settings put secure accessibility_enabled 1")
        check(readSetting(Settings.Secure.ACCESSIBILITY_ENABLED) == "1")
    }

    suspend fun awaitServiceStopped() {
        withTimeout(AndroidE2eEnvironment.UI_TIMEOUT) {
            while (ownServiceRunning()) delay(POLL_INTERVAL)
        }
    }

    @Suppress("DEPRECATION")
    private fun ownServiceRunning(): Boolean =
        // Since Android O this API is limited to the caller's services, exactly the scope needed here.
        context.getSystemService(ActivityManager::class.java).getRunningServices(Int.MAX_VALUE)
            .any { it.service == service }

    private fun serviceNames(): List<String> {
        val value = readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val names = value.orEmpty().split(':').filter(String::isNotBlank)
        check(names.all { ComponentName.unflattenFromString(it) != null }) { "Invalid accessibility service list" }
        return names
    }

    private fun writeServices(names: List<String>) {
        val expected = names.takeIf { it.isNotEmpty() }?.joinToString(":")
        shell(if (expected == null) "settings delete secure enabled_accessibility_services"
              else "settings put secure enabled_accessibility_services $expected")
        check(readSetting(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) == expected) {
            "Accessibility service update was not confirmed"
        }
    }

    fun stopFixture() {
        shell("am force-stop ${AndroidE2eEnvironment.FIXTURE_PACKAGE}")
        check(shell("pidof ${AndroidE2eEnvironment.FIXTURE_PACKAGE}").isBlank()) { "Fixture process still running" }
    }

    fun shell(command: String): String = executeShell(command)

    private companion object {
        const val POLL_INTERVAL = 200L

        fun executeShellCommand(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
                .executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
    }
}
