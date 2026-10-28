package com.storyteller_f.divedeep

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

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

    private data class ButtonUiState(
        val nodeId: String,
        val nodeText: String,
        val bounds: Rect,
        val translated: Boolean,
    )

    private data class SheetUiState(
        val sourceText: String,
        val targetLanguage: String,
        val translatedText: String?,
    )

    private data class OverlayUiState(
        val buttons: List<ButtonUiState> = emptyList(),
        val sheet: SheetUiState? = null,
    )

    private val computeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val rendererScope = CoroutineScope(SupervisorJob() + computeDispatcher)
    private val uiState = MutableStateFlow(OverlayUiState())
    private val windowManager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val previousButtonPositions = mutableMapOf<String, Rect>()
    private val buttonViews = mutableMapOf<String, Button>()
    private var sheetView: View? = null

    @Volatile
    private var selectedNodeId: String? = null

    @Volatile
    private var latestFrame: TranslationFrame? = null

    init {
        rendererScope.launch(Dispatchers.Main.immediate) {
            uiState.collect(::applyUiState)
        }
    }

    override fun render(frame: TranslationFrame) {
        latestFrame = frame
        rendererScope.launch {
            uiState.value = computeUiState(frame)
        }
    }

    override fun clear() {
        rendererScope.launch {
            selectedNodeId = null
            previousButtonPositions.clear()
            uiState.value = OverlayUiState()
        }
    }

    fun close() {
        rendererScope.cancel()
        removeStaleButtons(emptySet())
        hideSheet()
    }

    private fun computeUiState(frame: TranslationFrame): OverlayUiState {
        Log.i(TAG, "render frame target=${frame.targetLanguage} items=${frame.items.size}")
        frame.items.take(PREVIEW_LOG_LIMIT).forEach { item ->
            Log.i(TAG, "translation node=${item.nodeId} text=${item.translatedText}")
        }

        val translatedByNodeId = frame.items.associateBy { it.nodeId }
        val overlayBounds = overlayBounds()
        val buttons = frame.nodes.mapNotNull { node ->
            val buttonBounds = buttonBoundsFor(node, overlayBounds) ?: return@mapNotNull null
            ButtonUiState(
                nodeId = node.id,
                nodeText = node.text,
                bounds = buttonBounds,
                translated = translatedByNodeId[node.id] != null,
            )
        }

        val selectedNode = selectedNodeId?.let { nodeId ->
            frame.nodes.firstOrNull { it.id == nodeId }
        }
        if (selectedNode == null) {
            selectedNodeId = null
            return OverlayUiState(buttons = buttons)
        }

        Log.i(TAG, "bottom sheet node=${selectedNode.id}")
        return OverlayUiState(
            buttons = buttons,
            sheet = SheetUiState(
                sourceText = selectedNode.text,
                targetLanguage = frame.targetLanguage,
                translatedText = translatedByNodeId[selectedNode.id]?.translatedText,
            ),
        )
    }

    private fun onButtonClicked(nodeId: String) {
        selectedNodeId = nodeId
        val frame = latestFrame ?: return
        rendererScope.launch {
            uiState.value = computeUiState(frame)
        }
    }

    private fun isIdle(state: OverlayUiState): Boolean =
        state.buttons.isEmpty() && state.sheet == null && buttonViews.isEmpty() && sheetView == null

    private fun applyUiState(state: OverlayUiState) {
        if (isIdle(state)) return

        val activeNodeIds = mutableSetOf<String>()
        state.buttons.forEach { buttonState ->
            activeNodeIds += buttonState.nodeId
            showButton(buttonState) {
                onButtonClicked(buttonState.nodeId)
            }
        }
        removeStaleButtons(activeNodeIds)

        val sheetState = state.sheet
        if (sheetState == null) {
            hideSheet()
        } else {
            showSheet(sheetState)
        }
    }

    private fun showButton(
        state: ButtonUiState,
        onClick: () -> Unit,
    ) {
        val existing = buttonViews[state.nodeId]
        if (existing != null) {
            updateButton(existing, state)
            existing.setOnClickListener { onClick() }
            val params = existing.layoutParams as WindowManager.LayoutParams
            if (params.x != state.bounds.left || params.y != state.bounds.top) {
                params.x = state.bounds.left
                params.y = state.bounds.top
                windowManager.updateViewLayout(existing, params)
            }
            return
        }

        val button = translationButton(state, onClick)
        windowManager.addView(button, overlayParams(state.bounds.width(), state.bounds.height()).apply {
            x = state.bounds.left
            y = state.bounds.top
        })
        buttonViews[state.nodeId] = button
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

    private fun showSheet(state: SheetUiState) {
        hideSheet()
        val sheet = bottomSheet(state)
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
        state: ButtonUiState,
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
            updateButton(this, state)
            setOnClickListener { onClick() }
        }

    private fun updateButton(
        button: Button,
        state: ButtonUiState,
    ) {
        button.text = if (state.translated) "已翻译" else "翻译中"
        button.setBackgroundColor(if (state.translated) BUTTON_DONE_COLOR else BUTTON_LOADING_COLOR)
        button.contentDescription = "${state.nodeText} ${button.text}"
    }

    private fun bottomSheet(state: SheetUiState): View =
        LinearLayout(service).apply {
            id = R.id.translation_sheet
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SHEET_BACKGROUND_COLOR)
            setPadding(SHEET_PADDING, SHEET_PADDING, SHEET_PADDING, SHEET_PADDING)
            addView(sheetText("原文", SHEET_TITLE_SIZE_SP, SHEET_SOURCE_COLOR))
            addView(sheetText(state.sourceText, SHEET_BODY_SIZE_SP, SHEET_SOURCE_COLOR).apply {
                id = R.id.translation_source
            })
            addView(sheetText("目标语言 ${state.targetLanguage}", SHEET_TITLE_SIZE_SP, SHEET_SOURCE_COLOR))
            addView(sheetText(state.translatedText ?: "翻译中", SHEET_BODY_SIZE_SP, SHEET_TRANSLATION_COLOR).apply {
                id = R.id.translation_result
            })
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
