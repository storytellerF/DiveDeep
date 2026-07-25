package com.storyteller_f.divedeep

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.storyteller_f.divedeep.shared.ContentCaptureDriver
import com.storyteller_f.divedeep.shared.DiveDeepEngine
import com.storyteller_f.divedeep.shared.OverlayRenderer
import com.storyteller_f.divedeep.shared.ScreenTextNode
import com.storyteller_f.divedeep.shared.TranslationFrame
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DiveDeepAccessibilityService : AccessibilityService() {
    private companion object {
        const val TAG = "DiveDeepAccessibility"
    }

    private lateinit var engine: DiveDeepEngine
    private lateinit var accessibilityCaptureDriver: AndroidAccessibilityCaptureDriver
    private lateinit var translationService: ConfiguredTranslationService
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile
    private var latestCapturedNodes: List<ScreenTextNode> = emptyList()
    @Volatile
    private var latestSettings = DiveDeepSettings()
    private val refreshLock = Any()
    private var refreshRunning = false
    private var refreshPending = false
    @Volatile
    private var refreshGeneration = 0L
    @Volatile
    private var serviceConnected = false

    override fun onCreate() {
        super.onCreate()
        accessibilityCaptureDriver = AndroidAccessibilityCaptureDriver { rootInActiveWindow }
        val overlayRenderer = AndroidOverlayRenderer(this)
        translationService = ConfiguredTranslationService(this) {
            latestSettings.translationConfig
        }
        engine = DiveDeepEngine(
            captureDriver = ContentCaptureDriver { latestCapturedNodes },
            translationService = translationService,
            overlayRenderer = object : OverlayRenderer {
                override fun render(frame: TranslationFrame) {
                    val settings = latestSettings
                    if (settings.enabled &&
                        DiveDeepState.isPackageAllowed(settings, rootInActiveWindow?.packageName)
                    ) {
                        overlayRenderer.render(frame)
                    }
                }

                override fun clear() {
                    overlayRenderer.clear()
                }
            },
            targetLanguageProvider = { Locale.getDefault().toLanguageTag() },
        )
        collectSettings()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        if (latestSettings.enabled) refresh()
        DiveDeepTileService.requestTileRefresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val settings = latestSettings
        if (!settings.enabled) {
            engine.stop()
            return
        }
        if (!DiveDeepState.isPackageAllowed(settings, event?.packageName ?: rootInActiveWindow?.packageName)) {
            engine.stop()
            return
        }
        refresh()
    }

    override fun onInterrupt() {
        engine.stop()
    }

    override fun onDestroy() {
        serviceConnected = false
        serviceScope.cancel()
        engine.stop()
        translationService.close()
        super.onDestroy()
    }

    private fun refresh() {
        val settings = latestSettings
        val shouldRefresh = serviceConnected &&
            settings.enabled &&
            DiveDeepState.isPackageAllowed(settings, rootInActiveWindow?.packageName)
        if (!shouldRefresh) {
            clearPendingRefresh()
            if (serviceConnected) {
                engine.stop()
            }
            return
        }
        latestCapturedNodes = accessibilityCaptureDriver.captureVisibleText()
        val generation = synchronized(refreshLock) {
            refreshGeneration += 1
            if (refreshRunning) {
                refreshPending = true
                return
            }
            refreshRunning = true
            refreshGeneration
        }
        serviceScope.launch(Dispatchers.IO) {
            runRefreshLoop(generation)
        }
    }

    private suspend fun runRefreshLoop(initialGeneration: Long) {
        var generation = initialGeneration
        while (true) {
            val settings = latestSettings
            if (!settings.enabled ||
                !DiveDeepState.isPackageAllowed(settings, rootInActiveWindow?.packageName)
            ) {
                engine.stop()
            } else {
                runCatching { engine.refresh { generation == refreshGeneration } }
                    .onFailure { error ->
                        Log.e(TAG, "Translation refresh failed: ${error.message}", error)
                    }
            }

            synchronized(refreshLock) {
                if (refreshPending) {
                    refreshPending = false
                    generation = refreshGeneration
                } else {
                    refreshRunning = false
                    return
                }
            }
        }
    }

    private fun collectSettings() {
        serviceScope.launch {
            DiveDeepState.initialize(this@DiveDeepAccessibilityService)
            DiveDeepState.settingsFlow(this@DiveDeepAccessibilityService).collect { settings ->
                latestSettings = settings
                if (settings.enabled) {
                    refresh()
                } else {
                    clearPendingRefresh()
                    engine.stop()
                }
                DiveDeepTileService.requestTileRefresh(this@DiveDeepAccessibilityService)
            }
        }
    }

    private fun clearPendingRefresh() {
        synchronized(refreshLock) {
            refreshPending = false
        }
    }
}
