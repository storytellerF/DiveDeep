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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class DiveDeepAccessibilityService : AccessibilityService() {
    private companion object {
        const val TAG = "DiveDeepAccessibility"
    }

    private lateinit var engine: DiveDeepEngine
    private lateinit var accessibilityCaptureDriver: AndroidAccessibilityCaptureDriver
    private lateinit var translationService: ConfiguredTranslationService
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val refreshRequests = Channel<RefreshRequest>(Channel.CONFLATED)
    private val refreshRevision = MutableStateFlow(0L)
    private val settings = MutableStateFlow(DiveDeepSettings())
    private var refreshNodes: List<ScreenTextNode> = emptyList()
    private var serviceConnected = false

    private data class RefreshRequest(
        val nodes: List<ScreenTextNode>,
        val revision: Long,
        val shouldRefresh: Boolean,
    )

    override fun onCreate() {
        super.onCreate()
        accessibilityCaptureDriver = AndroidAccessibilityCaptureDriver { rootInActiveWindow }
        val overlayRenderer = AndroidOverlayRenderer(this)
        translationService = ConfiguredTranslationService(this) {
            settings.value.translationConfig
        }
        engine = DiveDeepEngine(
            captureDriver = ContentCaptureDriver { refreshNodes },
            translationService = translationService,
            overlayRenderer = object : OverlayRenderer {
                override fun render(frame: TranslationFrame) {
                    val currentSettings = settings.value
                    if (currentSettings.enabled &&
                        DiveDeepState.isPackageAllowed(currentSettings, rootInActiveWindow?.packageName)
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
        processRefreshRequests()
        collectSettings()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceConnected = true
        if (settings.value.enabled) refresh()
        DiveDeepTileService.requestTileRefresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val currentSettings = settings.value
        if (!currentSettings.enabled) {
            stopRefreshing()
            return
        }
        if (!DiveDeepState.isPackageAllowed(currentSettings, event?.packageName ?: rootInActiveWindow?.packageName)) {
            stopRefreshing()
            return
        }
        refresh()
    }

    override fun onInterrupt() {
        stopRefreshing()
    }

    override fun onDestroy() {
        serviceConnected = false
        refreshRequests.close()
        serviceScope.cancel()
        engine.stop()
        translationService.close()
        super.onDestroy()
    }

    private fun refresh() {
        val currentSettings = settings.value
        val shouldRefresh = serviceConnected &&
            currentSettings.enabled &&
            DiveDeepState.isPackageAllowed(currentSettings, rootInActiveWindow?.packageName)
        if (!shouldRefresh) {
            stopRefreshing()
            return
        }
        submitRefresh(accessibilityCaptureDriver.captureVisibleText(), shouldRefresh = true)
    }

    private fun processRefreshRequests() {
        serviceScope.launch(Dispatchers.IO) {
            for (request in refreshRequests) {
                if (!request.shouldRefresh) {
                    engine.stop()
                    continue
                }

                refreshNodes = request.nodes
                runCatching { engine.refresh { request.revision == refreshRevision.value } }
                    .onFailure { error ->
                        if (error is CancellationException) throw error
                        Log.e(TAG, "Translation refresh failed: ${error.message}", error)
                    }
            }
        }
    }

    private fun stopRefreshing() {
        submitRefresh(emptyList(), shouldRefresh = false)
    }

    private fun submitRefresh(nodes: List<ScreenTextNode>, shouldRefresh: Boolean) {
        val revision = refreshRevision.value + 1
        refreshRevision.value = revision
        refreshRequests.trySend(RefreshRequest(nodes, revision, shouldRefresh))
    }

    private fun collectSettings() {
        serviceScope.launch {
            DiveDeepState.initialize(this@DiveDeepAccessibilityService)
            DiveDeepState.settingsFlow(this@DiveDeepAccessibilityService).collect { currentSettings ->
                settings.value = currentSettings
                if (currentSettings.enabled) {
                    refresh()
                } else {
                    stopRefreshing()
                }
                DiveDeepTileService.requestTileRefresh(this@DiveDeepAccessibilityService)
            }
        }
    }
}
