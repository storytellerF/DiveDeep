package com.storyteller_f.divedeep

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.storyteller_f.divedeep.shared.OverlayRenderer
import com.storyteller_f.divedeep.shared.TranslationFrame

class AndroidOverlayRenderer(
    private val service: AccessibilityService,
) : OverlayRenderer {
    private companion object {
        const val TAG = "DiveDeepOverlay"
        const val PREVIEW_LOG_LIMIT = 5
        const val LABEL_BACKGROUND_COLOR = 0xCC246BFD.toInt()
        const val LABEL_TEXT_SIZE_SP = 12f
        const val LABEL_PADDING_HORIZONTAL = 8
        const val LABEL_PADDING_VERTICAL = 4
        const val LABEL_MAX_LINES = 3
        const val MIN_LABEL_WIDTH = 64
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: FrameLayout? = null

    override fun render(frame: TranslationFrame) {
        mainHandler.post {
            Log.i(TAG, "render frame target=${frame.targetLanguage} items=${frame.items.size}")
            frame.items.take(PREVIEW_LOG_LIMIT).forEach { item ->
                Log.i(TAG, "translation node=${item.nodeId} text=${item.translatedText}")
            }
            ensureOverlay()
            val overlay = overlayView ?: return@post
            overlay.removeAllViews()

            frame.items.forEach { item ->
                val label = TextView(service).apply {
                    text = item.translatedText
                    setTextColor(Color.WHITE)
                    textSize = LABEL_TEXT_SIZE_SP
                    setBackgroundColor(LABEL_BACKGROUND_COLOR)
                    setPadding(
                        LABEL_PADDING_HORIZONTAL,
                        LABEL_PADDING_VERTICAL,
                        LABEL_PADDING_HORIZONTAL,
                        LABEL_PADDING_VERTICAL,
                    )
                    maxLines = LABEL_MAX_LINES
                }
                overlay.addView(
                    label,
                    FrameLayout.LayoutParams(
                        item.bounds.width.coerceAtLeast(MIN_LABEL_WIDTH),
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        leftMargin = item.bounds.left
                        topMargin = item.bounds.top
                    },
                )
            }
        }
    }

    override fun clear() {
        mainHandler.post {
            overlayView?.let { view ->
                windowManager.removeView(view)
            }
            overlayView = null
        }
    }

    private fun ensureOverlay() {
        if (overlayView != null) return

        val view = FrameLayout(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
            importantForAccessibility = FrameLayout.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(view, params)
        overlayView = view
    }
}
