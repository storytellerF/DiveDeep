package com.storyteller_f.divedeep.shared

data class TextBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val isEmpty: Boolean get() = width == 0 || height == 0
}

data class ScreenTextNode(
    val id: String,
    val text: String,
    val bounds: TextBounds,
    val role: NodeRole = NodeRole.Text,
    val depth: Int = 0,
    val visible: Boolean = true,
)

enum class NodeRole {
    Button,
    Input,
    ListItem,
    Text,
    Unknown,
}

data class TranslationRequest(
    val sourceLanguage: String = "zh",
    val targetLanguage: String,
    val items: List<ScreenTextNode>,
)

data class TranslationItem(
    val nodeId: String,
    val sourceText: String,
    val translatedText: String,
    val bounds: TextBounds,
)

data class TranslationFrame(
    val targetLanguage: String,
    val items: List<TranslationItem>,
)

fun interface ContentCaptureDriver {
    fun captureVisibleText(): List<ScreenTextNode>
}

fun interface TranslationService {
    fun translate(request: TranslationRequest): List<TranslationItem>
}

interface OverlayRenderer {
    fun render(frame: TranslationFrame)
    fun clear()
}
