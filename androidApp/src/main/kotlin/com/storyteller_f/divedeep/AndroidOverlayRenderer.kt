package com.storyteller_f.divedeep

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.storyteller_f.divedeep.shared.OverlayRenderer
import com.storyteller_f.divedeep.shared.ScreenTextNode
import com.storyteller_f.divedeep.shared.TextBounds
import com.storyteller_f.divedeep.shared.TranslationFrame
import com.storyteller_f.divedeep.shared.TranslationItem

class AndroidOverlayRenderer(
    private val service: AccessibilityService,
) : OverlayRenderer {
    private companion object {
        const val TAG = "DiveDeepOverlay"
        const val PREVIEW_LOG_LIMIT = 5
        const val BUTTON_WIDTH = 96
        const val BUTTON_HEIGHT = 48
        const val BUTTON_MARGIN = 4
        const val BUTTON_TEXT_SIZE_SP = 11f
        const val SHEET_PADDING = 24
        const val SHEET_MARGIN = 24
        const val SHEET_TITLE_SIZE_SP = 14f
        const val SHEET_BODY_SIZE_SP = 16f
        const val SHEET_BACKGROUND_COLOR = 0xF2242733.toInt()
        const val SHEET_SOURCE_COLOR = 0xFFCBD5E1.toInt()
        const val SHEET_TRANSLATION_COLOR = Color.WHITE
        const val BUTTON_DONE_COLOR = 0xE0246BFD.toInt()
        const val BUTTON_LOADING_COLOR = 0xE0F59E0B.toInt()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val previousButtonPositions = mutableMapOf<String, Rect>()
    private var overlayView: FrameLayout? = null
    private var selectedNodeId: String? = null

    override fun render(frame: TranslationFrame) {
        mainHandler.post {
            Log.i(TAG, "render frame target=${frame.targetLanguage} items=${frame.items.size}")
            frame.items.take(PREVIEW_LOG_LIMIT).forEach { item ->
                Log.i(TAG, "translation node=${item.nodeId} text=${item.translatedText}")
            }
            if (frame.nodes.isEmpty() && overlayView == null) return@post

            val overlay = ensureOverlay() ?: return@post
            overlay.removeAllViews()

            val translatedByNodeId = frame.items.associateBy { it.nodeId }
            val overlayBounds = overlayBounds()
            frame.nodes.forEach { node ->
                val buttonBounds = buttonBoundsFor(node, overlayBounds) ?: return@forEach
                overlay.addView(
                    translationButton(
                        node = node,
                        item = translatedByNodeId[node.id],
                        onClick = {
                            selectedNodeId = node.id
                            render(frame)
                        },
                    ),
                    FrameLayout.LayoutParams(BUTTON_WIDTH, BUTTON_HEIGHT).apply {
                        leftMargin = buttonBounds.left
                        topMargin = buttonBounds.top
                    },
                )
            }

            selectedNodeId?.let { nodeId ->
                val selectedNode = frame.nodes.firstOrNull { it.id == nodeId }
                if (selectedNode == null) {
                    selectedNodeId = null
                } else {
                    Log.i(TAG, "bottom sheet node=${selectedNode.id}")
                    overlay.addView(bottomSheet(frame, selectedNode, translatedByNodeId[nodeId]))
                }
            }
        }
    }

    override fun clear() {
        mainHandler.post {
            overlayView?.let { view ->
                windowManager.removeView(view)
            }
            overlayView = null
            selectedNodeId = null
            previousButtonPositions.clear()
        }
    }

    private fun translationButton(
        node: ScreenTextNode,
        item: TranslationItem?,
        onClick: () -> Unit,
    ): Button =
        Button(service).apply {
            text = if (item == null) "翻译中" else "已翻译"
            textSize = BUTTON_TEXT_SIZE_SP
            setTextColor(Color.WHITE)
            setBackgroundColor(if (item == null) BUTTON_LOADING_COLOR else BUTTON_DONE_COLOR)
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            contentDescription = "${node.text} ${text}"
            setPadding(0, 0, 0, 0)
            setOnClickListener { onClick() }
        }

    private fun bottomSheet(
        frame: TranslationFrame,
        node: ScreenTextNode,
        item: TranslationItem?,
    ): View =
        LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHEET_BACKGROUND_COLOR)
            setPadding(SHEET_PADDING, SHEET_PADDING, SHEET_PADDING, SHEET_PADDING)
            addView(sheetText("原文", SHEET_TITLE_SIZE_SP, SHEET_SOURCE_COLOR))
            addView(sheetText(node.text, SHEET_BODY_SIZE_SP, SHEET_SOURCE_COLOR))
            addView(sheetText("目标语言 ${frame.targetLanguage}", SHEET_TITLE_SIZE_SP, SHEET_SOURCE_COLOR))
            addView(sheetText(item?.translatedText ?: "翻译中", SHEET_BODY_SIZE_SP, SHEET_TRANSLATION_COLOR))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = SHEET_MARGIN
                rightMargin = SHEET_MARGIN
                bottomMargin = SHEET_MARGIN
            }
        }

    private fun sheetText(
        value: String,
        sizeSp: Float,
        color: Int,
    ): TextView =
        TextView(service).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            setPadding(0, BUTTON_MARGIN, 0, BUTTON_MARGIN)
        }

    private fun buttonBoundsFor(node: ScreenTextNode, overlayBounds: Rect): Rect? {
        val visibleBounds = node.bounds.toRect().intersectedWith(overlayBounds)
        if (visibleBounds.width() < BUTTON_WIDTH || visibleBounds.height() < BUTTON_HEIGHT) {
            previousButtonPositions.remove(node.id)
            return null
        }

        val desired = Rect(
            node.bounds.right - BUTTON_WIDTH - BUTTON_MARGIN,
            node.bounds.top + BUTTON_MARGIN,
            node.bounds.right - BUTTON_MARGIN,
            node.bounds.top + BUTTON_MARGIN + BUTTON_HEIGHT,
        )
        val positioned = if (overlayBounds.contains(desired)) {
            desired
        } else {
            previousButtonPositions[node.id]?.takeIf { overlayBounds.contains(it) }
                ?: desired.clampedTo(overlayBounds)
        }
        previousButtonPositions[node.id] = positioned
        return positioned
    }

    private fun ensureOverlay(): FrameLayout? {
        overlayView?.let { return it }

        val view = FrameLayout(service).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        windowManager.addView(view, params)
        overlayView = view
        return view
    }

    private fun overlayBounds(): Rect {
        val metrics = service.resources.displayMetrics
        return Rect(0, 0, metrics.widthPixels, metrics.heightPixels)
    }

    private fun TextBounds.toRect(): Rect = Rect(left, top, right, bottom)

    private fun Rect.intersectedWith(other: Rect): Rect {
        val result = Rect(this)
        return if (result.intersect(other)) result else Rect()
    }

    private fun Rect.clampedTo(container: Rect): Rect {
        val left = this.left.coerceIn(container.left, container.right - width())
        val top = this.top.coerceIn(container.top, container.bottom - height())
        return Rect(left, top, left + width(), top + height())
    }
}
