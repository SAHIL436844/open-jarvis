package com.openjarvis.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * Reads text exposed by Android's accessibility tree.
 * Each node obtained from getChild() is recycled exactly once, and the root
 * returned by rootInActiveWindow is owned and recycled by the caller here.
 */
class ScreenReader(@Suppress("UNUSED_PARAMETER") owner: Any? = null) {

    fun extractAllText(): String {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return ""
        val parts = LinkedHashSet<String>()
        try {
            collectText(root, parts)
        } finally {
            root.recycle()
        }
        return parts.joinToString(" ")
    }

    private fun collectText(node: AccessibilityNodeInfo, parts: MutableSet<String>) {
        if (!node.isVisibleToUser) return
        node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        node.hintText?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)
        node.stateDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(parts::add)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectText(child, parts)
            } finally {
                child.recycle()
            }
        }
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        if (text.isBlank()) return null
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        val result = findMatchingNode(root, text, includeHints = false)
        root.recycle()
        return result
    }

    fun findNodeByHint(hint: String): AccessibilityNodeInfo? {
        if (hint.isBlank()) return null
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        val result = findMatchingNode(root, hint, includeHints = true)
        root.recycle()
        return result
    }

    private fun findMatchingNode(
        root: AccessibilityNodeInfo,
        target: String,
        includeHints: Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var matched: AccessibilityNodeInfo? = null
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val needle = target.trim()
            val matches = node.isVisibleToUser && (
                node.text?.toString()?.contains(needle, ignoreCase = true) == true ||
                node.contentDescription?.toString()?.contains(needle, ignoreCase = true) == true ||
                (includeHints && node.hintText?.toString()?.contains(needle, ignoreCase = true) == true)
            )
            if (matches) {
                matched = if (node === root) AccessibilityNodeInfo.obtain(node) else node
                break
            }
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let(queue::addLast)
            }
            if (node !== root) node.recycle()
        }
        while (queue.isNotEmpty()) {
            val pending = queue.removeFirst()
            if (pending !== root) pending.recycle()
        }
        return matched
    }

    fun getFocusedNode(): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        return focused
    }
}
