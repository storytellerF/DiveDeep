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
    private val buttonViews = mutableMapOf<String, Button>()
    private var sheetView: View? = null
    private var selectedNodeId: String? = null

    override fun render(frame: TranslationFrame) {
        mainHandler.post {
            Log.i(TAG, "render frame target=${frame.targetLanguage} items=${frame.items.size}")
            frame.items.take(PREVIEW_LOG_LIMIT).forEach { item ->
                Log.i(TAG, "translation node=${item.nodeId} text=${item.translatedText}")
            }
            if (frame.nodes.isEmpty() && buttonViews.isEmpty() && sheetView == null) return@post

            val translatedByNodeId = frame.items.associateBy { it.nodeId }
            val overlayBounds = overlayBounds()
            val activeNodeIds = mutableSetOf<String>()
            frame.nodes.forEach { node ->
                val buttonBounds = buttonBoundsFor(node, overlayBounds) ?: return@forEach
                activeNodeIds += node.id
                showButton(node, translatedByNodeId[node.id], buttonBounds) {
                    selectedNodeId = node.id
                    render(frame)
                }
            }
            removeStaleButtons(activeNodeIds)

            val selectedNode = selectedNodeId?.let { nodeId ->
                frame.nodes.firstOrNull { it.id == nodeId }
            }
            if (selectedNode == null) {
                selectedNodeId = null
                hideSheet()
            } else {
                Log.i(TAG, "bottom sheet node=${selectedNode.id}")
                showSheet(frame, selectedNode, translatedByNodeId[selectedNode.id])
            }
        }
    }

    override fun clear() {
        mainHandler.post {
            removeStaleButtons(emptySet())
            hideSheet()
            selectedNodeId = null
            previousButtonPositions.clear()
        }
    }

    private fun showButton(
        node: ScreenTextNode,
        item: TranslationItem?,
        bounds: Rect,
        onClick: () -> Unit,
    ) {
        val existing = buttonViews[node.id]
        if (existing != null) {
            updateButton(existing, node, item)
            existing.setOnClickListener { onClick() }
            val params = existing.layoutParams as WindowManager.LayoutParams
            if (params.x != bounds.left || params.y != bounds.top) {
                params.x = bounds.left
                params.y = bounds.top
                windowManager.updateViewLayout(existing, params)
            }
            return
        }

        val button = translationButton(node, item, onClick)
        windowManager.addView(button, overlayParams(bounds.width(), bounds.height()).apply {
            x = bounds.left
            y = bounds.top
        })
        buttonViews[node.id] = button
    }

    private fun removeStaleButtons(activeNodeIds: Set<String>) {
        val iterator = buttonViews.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in activeNodeIds) {
                runCatching { windowManager.removeView(entry.value) }
                iterator.remove()
            }
        }
    }

    private fun showSheet(
        frame: TranslationFrame,
        node: ScreenTextNode,
        item: TranslationItem?,
    ) {
        hideSheet()
        val sheet = bottomSheet(frame, node, item)
        sheet.setPadding(SHEET_MARGIN, 0, SHEET_MARGIN, SHEET_MARGIN)
        windowManager.addView(
            sheet,
            overlayParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM or Gravity.START
            },
        )
        sheetView = sheet
    }

    private fun hideSheet() {
        sheetView?.let { runCatching { windowManager.removeView(it) } }
        sheetView = null
    }

    private fun overlayParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

    private fun translationButton(
        node: ScreenTextNode,
        item: TranslationItem?,
        onClick: () -> Unit,
    ): Button =
        Button(service).apply {
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(0, 0, 0, 0)
            textSize = BUTTON_TEXT_SIZE_SP
            setTextColor(Color.WHITE)
            updateButton(this, node, item)
            setOnClickListener { onClick() }
        }

    private fun updateButton(
        button: Button,
        node: ScreenTextNode,
        item: TranslationItem?,
    ) {
        button.text = if (item == null) "翻译中" else "已翻译"
        button.setBackgroundColor(if (item == null) BUTTON_LOADING_COLOR else BUTTON_DONE_COLOR)
        button.contentDescription = "${node.text} ${button.text}"
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
