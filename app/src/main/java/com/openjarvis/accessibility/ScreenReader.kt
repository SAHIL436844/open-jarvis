package com.openjarvis.accessibility

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class ScreenReader {
    fun extractAllText(): String {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return ""
        val builder = StringBuilder()
        appendText(root, builder)
        root.recycle()
        return builder.toString()
    }

    private fun appendText(node: AccessibilityNodeInfo, builder: StringBuilder) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { builder.append(it).append(' ') }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { builder.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                appendText(child, builder)
                child.recycle()
            }
        }
    }

    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val nodeText = node.text?.toString()?.lowercase()
            val description = node.contentDescription?.toString()?.lowercase()
            if (nodeText?.contains(text.lowercase()) == true ||
                description?.contains(text.lowercase()) == true
            ) {
                if (node !== root) root.recycle()
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            if (node !== root) node.recycle()
        }
        root.recycle()
        return null
    }

    fun findNodeByHint(hint: String): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.hintText?.toString()?.contains(hint, ignoreCase = true) == true) {
                if (node !== root) root.recycle()
                return node
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
            if (node !== root) node.recycle()
        }
        root.recycle()
        return null
    }

    fun getFocusedNode(): AccessibilityNodeInfo? {
        val root = JarvisAccessibilityService.instance?.rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        root.recycle()
        return focused
    }
}
