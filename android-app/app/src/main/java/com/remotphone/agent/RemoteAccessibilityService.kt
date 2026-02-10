package com.remotphone.agent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RemoteAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RemoteAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events, just use the service for gestures
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    // ─── Tap ───────────────────────────────────────────
    fun performTap(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ─── Long Press ────────────────────────────────────
    fun performLongPress(x: Float, y: Float) {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ─── Swipe ─────────────────────────────────────────
    fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float, duration: Long = 300) {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.coerceIn(50, 2000)))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ─── Navigation ────────────────────────────────────
    fun performBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun performHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun performRecents() {
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun performNotifications() {
        performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    }

    // ─── Text Input ────────────────────────────────────
    fun inputText(text: String) {
        val focusedNode = findFocusedEditText(rootInActiveWindow) ?: return

        // Try setting text directly
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                (getCurrentText(focusedNode) ?: "") + text
            )
        }
        focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        focusedNode.recycle()
    }

    fun pressKey(keyCode: Int) {
        // For backspace (KEYCODE_DEL = 67)
        if (keyCode == 67) {
            val focusedNode = findFocusedEditText(rootInActiveWindow) ?: return
            val currentText = getCurrentText(focusedNode)
            if (currentText != null && currentText.isNotEmpty()) {
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        currentText.dropLast(1)
                    )
                }
                focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            focusedNode.recycle()
        }
    }

    private fun findFocusedEditText(root: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        root ?: return null

        if (root.isFocused && root.isEditable) return root

        for (i in 0 until root.childCount) {
            val child = root.getChild(i) ?: continue
            val result = findFocusedEditText(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun getCurrentText(node: AccessibilityNodeInfo): String? {
        return node.text?.toString()
    }
}
