package com.storyteller_f.divedeep

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.storyteller_f.divedeep.shared.ContentCaptureDriver
import com.storyteller_f.divedeep.shared.NodeRole
import com.storyteller_f.divedeep.shared.ScreenTextNode
import com.storyteller_f.divedeep.shared.TextBounds

class AndroidAccessibilityCaptureDriver(
    private val rootProvider: () -> AccessibilityNodeInfo?,
) : ContentCaptureDriver {
    override fun captureVisibleText(): List<ScreenTextNode> {
        val root = rootProvider() ?: return emptyList()
        val nodes = mutableListOf<ScreenTextNode>()
        traverse(root, depth = 0, nodes = nodes)
        return nodes
    }

    private fun traverse(
        node: AccessibilityNodeInfo,
        depth: Int,
        nodes: MutableList<ScreenTextNode>,
    ) {
        val text = node.text?.toString()?.trim().orEmpty()
        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        if (text.isNotBlank() && node.isVisibleToUser && !bounds.isEmpty) {
            nodes += ScreenTextNode(
                id = stableId(node, bounds, text),
                text = text,
                bounds = TextBounds(bounds.left, bounds.top, bounds.right, bounds.bottom),
                role = roleOf(node),
                depth = depth,
                visible = node.isVisibleToUser,
            )
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                traverse(child, depth + 1, nodes)
            }
        }
    }

    private fun stableId(node: AccessibilityNodeInfo, bounds: Rect, text: String): String {
        val baseId = node.viewIdResourceName?.takeIf { it.isNotBlank() }
            ?: node.className?.toString().orEmpty()
        return "$baseId:${bounds.left}:${bounds.top}:${bounds.right}:${bounds.bottom}:$text"
    }

    private fun roleOf(node: AccessibilityNodeInfo): NodeRole {
        val className = node.className?.toString().orEmpty()
        return when {
            node.isEditable -> NodeRole.Input
            node.isClickable || className.endsWith("Button") -> NodeRole.Button
            className.contains("List", ignoreCase = true) -> NodeRole.ListItem
            className.contains("Text", ignoreCase = true) -> NodeRole.Text
            else -> NodeRole.Unknown
        }
    }
}
