package com.openjarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Path
import android.accessibilityservice.GestureDescription
import android.util.DisplayMetrics
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class JarvisAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or 
                   AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY
            notificationTimeout = 100
        }
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun openAppByPackage(packageName: String) {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        }
    }

    fun openAppByLabel(label: String): Boolean {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        
        val apps = pm.queryIntentActivities(intent, 0)
        val normalizedLabel = label.lowercase()
        
        for (app in apps) {
            val appLabel = app.loadLabel(pm).toString().lowercase()
            if (appLabel.contains(normalizedLabel) || normalizedLabel.contains(appLabel)) {
                openAppByPackage(app.activityInfo.packageName)
                return true
            }
        }
        return false
    }

    fun tapByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val node = findNodeByText(rootNode, text)
        if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            rootNode.recycle()
            return result
        }
        rootNode.recycle()
        return false
    }

    fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            val arguments = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focused.recycle()
            rootNode.recycle()
            return result
        }
        rootNode.recycle()
        return false
    }

    fun clearAndTypeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        val result = if (focused != null) {
            val clearArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            }
            val cleared = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            val typeArgs = android.os.Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            val typed = cleared && focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, typeArgs)
            focused.recycle()
            typed
        } else false
        root.recycle()
        return result
    }

    fun longPressByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNodeByText(root, text)
        val result = node?.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK) == true
        node?.recycle()
        root.recycle()
        return result
    }

    fun tapCoordinates(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0 || x >= resources.displayMetrics.widthPixels ||
            y >= resources.displayMetrics.heightPixels) return false
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun swipe(direction: String, distance: String = "medium"): Boolean {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels.toFloat()
        val height = metrics.heightPixels.toFloat()
        val fraction = when (distance.lowercase()) {
            "short" -> 0.18f
            "long" -> 0.42f
            else -> 0.30f
        }
        val cx = width / 2f
        val cy = height / 2f
        val path = Path()
        when (direction.lowercase()) {
            "up" -> { path.moveTo(cx, cy + height * fraction / 2); path.lineTo(cx, cy - height * fraction / 2) }
            "down" -> { path.moveTo(cx, cy - height * fraction / 2); path.lineTo(cx, cy + height * fraction / 2) }
            "left" -> { path.moveTo(cx + width * fraction / 2, cy); path.lineTo(cx - width * fraction / 2, cy) }
            "right" -> { path.moveTo(cx - width * fraction / 2, cy); path.lineTo(cx + width * fraction / 2, cy) }
            else -> return false
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    fun pressBack() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun pressHome() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun pressRecents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    private fun findNodeByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val normalizedText = text.lowercase()
        
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            
            val nodeText = node.text?.toString()?.lowercase()
            val contentDesc = node.contentDescription?.toString()?.lowercase()
            
            if (nodeText?.contains(normalizedText) == true || contentDesc?.contains(normalizedText) == true) {
                if (node.isClickable && node.isVisibleToUser) {
                    return node
                }
            }
            
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    queue.add(child)
                }
            }
            node.recycle()
        }
        
        return null
    }

    companion object {
        var instance: JarvisAccessibilityService? = null
            private set
    }
}