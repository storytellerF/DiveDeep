package com.storyteller_f.divedeep

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.storyteller_f.divedeep.shared.ContentCaptureDriver
import com.storyteller_f.divedeep.shared.DiveDeepEngine
import com.storyteller_f.divedeep.shared.OverlayRenderer
import com.storyteller_f.divedeep.shared.ScreenTextNode
import com.storyteller_f.divedeep.shared.TranslationFrame
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DiveDeepAccessibilityService : AccessibilityService() {
    private companion object {
        const val TAG = "DiveDeepAccessibility"
    }

    private lateinit var engine: DiveDeepEngine
    private lateinit var refreshExecutor: ExecutorService
    private lateinit var accessibilityCaptureDriver: AndroidAccessibilityCaptureDriver
    private lateinit var translationService: ConfiguredTranslationService
    @Volatile
    private var latestCapturedNodes: List<ScreenTextNode> = emptyList()
    private val refreshLock = Any()
    private var refreshRunning = false
    private var refreshPending = false

    private val stateListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            if (DiveDeepState.isEnabled(this)) {
                refresh()
            } else {
                engine.stop()
            }
            DiveDeepTileService.requestTileRefresh(this)
        }

    override fun onCreate() {
        super.onCreate()
        refreshExecutor = Executors.newSingleThreadExecutor()
        accessibilityCaptureDriver = AndroidAccessibilityCaptureDriver { rootInActiveWindow }
        val overlayRenderer = AndroidOverlayRenderer(this)
        translationService = ConfiguredTranslationService(this) {
            DiveDeepState.getTranslationConfig(this)
        }
        engine = DiveDeepEngine(
            captureDriver = ContentCaptureDriver { latestCapturedNodes },
            translationService = translationService,
            overlayRenderer = object : OverlayRenderer {
                override fun render(frame: TranslationFrame) {
                    if (DiveDeepState.isEnabled(this@DiveDeepAccessibilityService) &&
                        DiveDeepState.isPackageAllowed(this@DiveDeepAccessibilityService, rootInActiveWindow?.packageName)
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
        DiveDeepState.register(this, stateListener)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (DiveDeepState.isEnabled(this)) refresh()
        DiveDeepTileService.requestTileRefresh(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!DiveDeepState.isEnabled(this)) {
            engine.stop()
            return
        }
        if (!DiveDeepState.isPackageAllowed(this, event?.packageName ?: rootInActiveWindow?.packageName)) {
            engine.stop()
            return
        }
        refresh()
    }

    override fun onInterrupt() {
        engine.stop()
    }

    override fun onDestroy() {
        DiveDeepState.unregister(this, stateListener)
        engine.stop()
        translationService.close()
        refreshExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        if (!DiveDeepState.isEnabled(this)) {
            clearPendingRefresh()
            engine.stop()
            return
        }
        if (!DiveDeepState.isPackageAllowed(this, rootInActiveWindow?.packageName)) {
            clearPendingRefresh()
            engine.stop()
            return
        }
        latestCapturedNodes = accessibilityCaptureDriver.captureVisibleText()
        synchronized(refreshLock) {
            if (refreshRunning) {
                refreshPending = true
                return
            }
            refreshRunning = true
        }
        refreshExecutor.execute {
            runRefreshLoop()
        }
    }

    private fun runRefreshLoop() {
        while (true) {
            if (!DiveDeepState.isEnabled(this) ||
                !DiveDeepState.isPackageAllowed(this, rootInActiveWindow?.packageName)
            ) {
                engine.stop()
            } else {
                runCatching { engine.refresh() }
                    .onFailure { error ->
                        Log.e(TAG, "Translation refresh failed: ${error.message}", error)
                    }
            }

            synchronized(refreshLock) {
                if (refreshPending) {
                    refreshPending = false
                } else {
                    refreshRunning = false
                    return
                }
            }
        }
    }

    private fun clearPendingRefresh() {
        synchronized(refreshLock) {
            refreshPending = false
        }
    }
}
